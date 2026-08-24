# Code review 2026-08, pass 3

Scope: full production corpus — 56 `src/` + 10 `gui/` + 17 `vendor/` + 5
`resources/cells/` files (95 parsed). Vendored mycelium/ring_chez are in scope
as the executor/capability layer of this project, reviewed with emphasis on
their wiring and trust boundaries. Tests and docs out of scope except as
evidence.

Method: chiasmus phased review — call graph (914 fns, 5959 edges, snapshot
`review-pass3`), dead-code / cycles / layer-violation / communities / surprises
passes; wavescope hot-spot targeting; a repo-wide orphan scan (fixed-boundary
regex over 690 defs); four parallel deep sweeps (files/lisp, llm stack, config
wiring, http/process); and every non-trivial claim re-verified on the main
thread — live jolt probes for the security and process findings, source-level
confirmation from jolt's own test suite for the adapter decode question.

Baseline: `jolt test` → **979 tests, 3095 assertions, 0 failures, 0 errors**.
Tree is exactly at fe2d507 (the pass-2 fix commit); nothing has changed since,
so this is a fresh full pass, not a delta.

Tool false positives excluded (known): the six `samizdat.server` handlers
dispatched through the `routes` var table; the `decompose-node`/`solve` cycle
(intentional tree recursion); LSP's gtk-*/%1/%2/`c`-fixture diagnostics.

---

## Issues

### 1. CRITICAL — model-reachable arbitrary command execution on the verify path, with the unscrubbed parent environment

`src/samizdat/agent/verify.clj:43-59` (focused-cmd), `:113-127` (run-verify),
`src/samizdat/agent/tools/ship.clj:281-297`, `src/samizdat/engine/proc.clj:67-91`

Three defects compound into one hole:

- **Quoting breakout.** `focused-cmd` builds `jolt -A:test -e '<expr>'` where
  `<expr>` interpolates namespaces derived from changed file names
  (`ns-from-test-path`, verify.clj:28-41, replaces only `_`→`-` and `/`→`.`).
  The safety argument is the comment at :57-58 — "the expr has no single
  quotes of its own" — which assumes away exactly the character a
  model-chosen file name can carry. A test file named
  `test/foo'; <CMD>; echo '.clj` closes the shell's single-quote and injects
  `<CMD>`.
- **No permission decision.** The `done` tool → ship → run-verify path runs
  `sh -c` directly; the permission engine sits only on the shell tool.
  security.md property 2 ("no path from toolcall to sub skips perm or scrub")
  is violated outright.
- **Full parent env.** `run-verify` passes no `:env`, and proc/run's contract
  (proc.clj:72-75) is that an omitted `:env` inherits the parent environment
  — every provider key the process holds. Output returns in the tool result
  the branch reads (`verify-block` tails `:output` into the done result), so
  `<CMD> = printenv` exfiltrates the whole environment into model space.
  None of those values are in redact's known-set (that is populated by the
  shell tool's scrub path, which this path skips) — property 1 broken too.

**Verified end-to-end** (jolt probe): `focused-cmd` on the crafted name
returns a command whose shell parse executes the injected segment; running it
through `run-verify` yields

```
{:green? true, :exit 0, :output "MARKER-INJECTED\n))(let [s (clojure.test/run-tests ..."}
```

— the marker lands in the model-bound output and the injection even satisfies
the ship gate. `gitdiff.clj:18` shares the unscrubbed-env half (operator-fixed
`git` invocation, lower risk, same fix).

**Fix direction (TDD).** Tests first:
- `focused-cmd` with a crafted name (`test/foo'; x; echo '.clj`) must produce
  a command containing no unbalanced `'` — simplest correct rule:
  `ns-from-test-path` whitelists `[a-zA-Z0-9.*-]` and returns nil for anything
  else (the file is skipped, ship falls back to `:verify-cmd`).
- `run-verify` result must come from a child whose env is
  `(secrets/scrub-env {})`-style scrubbed — probe: set a fake sensitive env
  var in the parent, verify output of `printenv` does not contain it.
- The verify output that reaches the branch passes `redact` with the config
  api-key in the known-set.
Consider also routing the built command through `policy/decide` with a
synthetic grant class (machine-rung), so the skip-perm property is restored
structurally rather than by luck.

### 2. HIGH — `samizdat.engine.lint` is a fully orphaned namespace: the vacuity linters never run

`src/samizdat/engine/lint.clj` (348 lines); zero references anywhere.

The namespace docstring says these run "BEFORE the engine" to catch
functionally-empty inputs — the SMT-LIB body whose assertions all sit in a
comment, after which Z3 SATs the empty constraint set ("the n=500 Sidon false
positive"). Nothing requires `samizdat.engine.lint`: not src, not test, not
resources, not deps.edn (verified by grep across the repo; the orphan scan
flagged `lint-smt`, `lint-prolog-program`, `lint-prolog-query`,
`vacuous-lean-statement?` with zero callers).

**Verified live**: the namespace loads and works —

```
(lint/lint-smt "; everything commented away")
→ {:ok false, :warnings ["All SMT-LIB content was inside comments; nothing to check."]}
```

— it catches exactly the class it was built for, and no code path calls it.
Any branch verifying engine claims today gets no vacuity guard.

**Fix direction (TDD).** Decide the wiring point (most natural: the verify
path or the artifact-claim acceptance path calls the linter matching the
claim's engine before spending a subprocess run; alternatively the journal's
artifact recording). First test: an artifact/verify input that is
all-comments SMT is rejected/blocked with the linter's warning; then thread
the call in. If the decision is to delete the namespace instead, delete it —
but the docstring's incident history argues for wiring, not removal.

### 3. MEDIUM — ring_chez answers HTTP/1.1 but silently truncates chunked request bodies

`vendor/ring_chez/adapter.clj:131-148, 217`

No `Transfer-Encoding: chunked` handling: with no `Content-Length` header,
`content-length` returns 0 and `request-complete?` is true the moment headers
arrive. The body becomes whatever chunked-framing bytes landed in the first
recv; the rest is dropped. `body-json` swallows the parse failure → nil →
`POST /v1/runs` starts a run with `problem nil`; chat/completions answers a
misleading 400. A 1.1-origin server must parse chunked or reject with
411/501.

**Fix direction (TDD).** Raw-socket test: chunked POST to `/v1/runs` — assert
411/501 (simplest correct) or the decoded body. Implement the reject branch
in `read-request` when `Transfer-Encoding` is present.

### 4. MEDIUM — unbounded request size, no read timeout, thread-per-connection

`vendor/ring_chez/adapter.clj:151-160, 49, 267`

`read-request` accumulates without a cap; `c-recv` blocks forever (only
SO_REUSEADDR is ever set); every accept spawns a future. A local client that
opens sockets and stalls pins threads and grows memory indefinitely.
`/slow` (server.clj:124-126) additionally accepts `ms` up to
`Long/MAX_VALUE`. Loopback-only bind (verified: `make-sockaddr` hardcodes
127.0.0.1, adapter.clj:103-104) keeps this a robustness bug, not a breach.

**Fix direction (TDD).** Cap `read-request` (e.g. 8 MB → 413), set
SO_RCVTIMEO on accepted sockets (a test with a socket that sends headers then
stalls must get a close within the timeout), and clamp `/slow`'s ms.

### 5. MEDIUM — multibyte UTF-8 split across recv boundaries corrupts request bodies

`vendor/ring_chez/adapter.clj:158`

Each recv chunk is decoded independently (`(str acc (ffi/read-bytes buf n))`).
jolt's own test suite states the consequence: "read-bytes would have to
substitute" — `jolt/test/chez/jolt-ffi-bytes-test.clj` documents that
`read-bytes` substitutes U+FFFD for invalid UTF-8 while only `read-array` is
binary-faithful. A codepoint straddling a TCP segment boundary therefore
decodes to two replacement chars, silently corrupting non-ASCII JSON bodies
(non-ASCII problem text is the norm here). Mechanism confirmed from source;
the end-to-end socket test below completes the proof.

**Fix direction (TDD).** Socket test: send headers plus the first byte of a
2-byte char, sleep, send the rest — assert the handler sees the original
string. Fix: accumulate bytes (read-array into a growing buffer), decode once
at request-complete.

### 6. MEDIUM — the arbiter and state machine still reference the removed proof-era tool surface

`src/samizdat/agent/state.clj:299-300, 322, 405, 492`;
`src/samizdat/agent/arbiter.clj:141-209`; `src/samizdat/agent/loop.clj:408-416`

`state/verification-tools` lists 9 tool names, none registered as a
`run-tool` method (24 exist; verified by defmethod enumeration).
`lean-verification-tools` lists 3, none registered. The arbiter settle table
names `review`, `audit`, `verify_template`, `retract_rule`, `add_rule`,
`sketch` — none exist. Consequences: the `:stuck` gate's
verification-compliance path can never settle met; `reenter-explore` can
never fire; the explore phase's designed exit ("bank a sketch") has no
producing tool, so `:explore` exits only via the cap force, and loop.clj's
message there references a `sketch` tool that does not exist;
`searches-since-attempt` is a dead read AND write (one grep hit: its own
dissoc). Settlement silently narrows and the gate met-rate telemetry —
"the only evidence any of this works" — is skewed unmet.

**Fix direction (TDD).** One cleanup: define the tool-name vocabulary from
the registry (derive `verification-tools` etc. from the actual `run-tool`
methods or a declared alias map), update the settle table's names to the
live surface, delete `searches-since-attempt`, and add a test asserting every
string in the settle table and both tool sets resolves to a registered tool
name — that test pins the whole class.

### 7. MEDIUM — `:tier-escalation` gate was deleted but its settle clause survives

`src/samizdat/agent/arbiter.clj:195` vs `src/samizdat/agent/gates.clj:385-388, 433`

The gate is gone from the `gates` vector (and `by-name`), but the arbiter
still has `:tier-escalation (called? "verify_template" "review" "audit")`.
Unreachable (settle runs only for fired gates; default false). Residue of the
same incomplete cleanup as issue 6. Fix: delete the clause; covered by the
same vocabulary test.

### 8. MEDIUM — timeout kill escalation reaches only the root process; trapped children survive

`src/samizdat/engine/proc.clj:42-47` (reap!)

`destroy-tree` sends SIGTERM tree-wide; the escalation is `.destroyForcibly`
on the root only. The namespace's own docstring cites 28 orphaned z3
processes as the failure it exists to prevent.

**Verified live**: `(proc/run {:timeout-ms 2000} "sh" -c" "trap '' TERM; sleep 600 & wait")`
→ `{:timeout true}`, and two `sleep 600` processes survived the reap
(cleaned up after the probe). Fix direction (TDD): reap! test with a
TERM-trapping child asserting no survivors (walk the child PIDs or use a
pgid-kill); escalate with a process-group kill
(`ProcessHandle/descendants` on jolt if available, else kill(−pgid)).

### 9. LOW — dead gate key `:sketch-duplicate-threshold` still served by `/v1/harness/gates`

`resources/gates.edn:235-240`; only references are the definition and a
comment at `store/artifacts.clj:258`. The code it documents hardcodes 0.6;
`sibling-sketches` is test-only. Same doc-rot class as the four keys deleted
in pass 2. Fix: delete the key (and decide `sibling-sketches`/`near-duplicate?`
fate — wire or delete).

### 10. LOW — orphan prompt `resources/prompts/tier-escalation.md`

Never loaded (`prompt` is called with 10 other names; verified). Delete with
issue 7's clause.

### 11. LOW — `cells/resource-dir` never called: an AOT binary launched outside the project root silently registers zero cells

`src/samizdat/cells.clj:145-150`; the loader uses plain relative
`default-dirs`. `fs/glob` on a missing dir → nil → "the kernel registers no
cells and runs no loop" (cells.clj:26-27). Latent. Fix: use `resource-dir`
in `default-dirs` (or io/resource), test: load-cells! with cwd elsewhere.

### 12. LOW — server/adapter conformance nits (batch)

- `409`/`503` responses carry reason phrase "OK" (`status-text` lacks both,
  adapter.clj:191-194, 217).
- Negative `limit` bypasses pagination (SQLite LIMIT −1 = no limit);
  server.clj:60, 135-136, 147-151, api/runs.clj:45, 105.
- Bad intervention `kind` → 500 not 400 (submit! throws through the
  catch-all), server.clj:157-161, interventions.clj:50-52.
- Query params never percent-decoded (server.clj:54-58).
- Truncated requests (peer FIN mid-request) are dispatched, not discarded
  (adapter.clj:156-157).
- Accepted fd leaked in the stop-server race (adapter.clj:255-258).
- `send-all` stops silently on send failure → client sees a
  content-length-complete truncated body (adapter.clj:230-233).

Fix direction: one adapter/server test namespace covering each; the fixes are
all local (add status-text entries, clamp limit ≥ 0, validate kind at the
API edge → 400, drop incomplete requests when `\r\n\r\n` never arrived,
close the raced conn, throw/log on send failure).

### 13. LOW — `control/watch` hardcodes branch `"B1"`

`src/samizdat/control.clj:65`. A beam run's other 4 branches are invisible to
the REPL supervisor's window. Fix: take the branch ids from `runs/branches`
(default: last active), test with a two-branch run.

### 14. LOW — `prefill-support?` protocol method has zero production callers

`src/samizdat/llm/adapter.clj:59`; implemented in both adapters, tested, but
the actual gate is the private `supports-prefill?` consulted inside
`chat-body` (openai.clj:49, 86). Two parallel paths deciding the same thing
invites drift (a provider update touching one and not the other). Fix: either
call the protocol method from `chat-body` or delete it from the protocol and
the tests.

### 15. INFO — minor observations (not defects requiring action now)

- `message.clj:168` `(assoc (select-keys m []) ...)` is an obfuscated empty
  map — messages are reduced to `{:role :content}`; adapters read only those
  two today, so behavior is fine; write `{}` or `{:role role}` next touch.
- `seed-from-run!` dedupes on raw claim text but quarantines on normalized
  text (artifacts.clj:154-160) — a claim that differs from its quarantined
  form only by whitespace/normalize-claim crosses. `:quarantined` journals
  the quarantine-list size, not the count actually filtered.
- `resume!`'s not-resumable error prints `status )` when the run is missing
  entirely (nil status).
- Orphan small fns with zero callers (verified): `secrets/scrubbed-process-env`,
  `gates/reload-config!`, `gui/textview.clj view-text`, `tools/ship.clj
  labelled-line`, `cells/reload!`. `dev/nrepl_client.clj` is a manual dev
  utility — fine as is.
- GUI style.clj colors eight unregistered proof-era tool names (cosmetic
  dead branches; follows issue 6's cleanup).

---

## Clean areas (checked, sound)

- **Security policy engine**: probes at HEAD — `cat README.md | sh` → :ask,
  `git commit -m a` → :allow, `echo $(rm -rf ~)` → :ask, `ls -la src` →
  :allow. Compound/no-allow-riding and benign near-misses both behave.
- **Path escape (files tools)**: verified live — `..`, absolute paths, and a
  symlink pointing outside the root are all refused fail-closed; writes
  refuse escaping paths without side effects. `resolve-under-root`'s
  canonicalize-and-prefix check is correct.
- **Paren repair (lisp.clj)**: verified live — balanced files pass through
  untouched, delimiters inside strings/comments are invisible to the scanner,
  trailing truncations are auto-closed only when the result re-reads,
  mid-file strays are never rewritten.
- **fence.clj parser**: opener-anchored extraction (reasoning between calls
  can't capture), json-fence shape-guarded (data display ≠ call),
  trailing-call end-anchored with name required, XML form requires both
  parts. No prose-becomes-a-call path found.
- **llm client retry**: bounded attempts, transport-only retries, fatal
  stops, provider retry-after honored, beyond-window waits treated as caps.
  Pass-2 fix shapes still present.
- **store layer**: pass-2 fix shapes verified at HEAD — `prune-finished!`
  swept at start-run!, caller-column-only `tasks/update!`, guarded `claim!`,
  migrations versioned in-transaction, `:fork-invite-floor` wired into beam
  repopulate (beam.clj:317).
- **beam**: child-id gap-filling (no PK collision on re-fork), pareto cull
  with scalar fallback, repopulate mark/ask split.
- **critic.clj**: score parser fails closed (partial vectors are nil), pareto
  domination correct, scores journaled.
- **Wiring positive sweep**: manifests ↔ cells complete in both directions
  (all 10 manifests resolve; all 20 defcells referenced); tools registry ↔
  system.md 24/24 both ways; all config defaults consumed; every gate key
  read in code exists in gates.edn (no lookup failures); the four pass-2
  deleted gate keys confirmed gone; maestro wired through mycelium into
  production.
- **proc.clj pipe draining**: REFUTED the deadlock suspect — a 300 KB-output
  child completed well inside its window with full output (jolt's shim drains
  concurrently).
- **ring_chez trust boundary**: loopback bind hardcoded (no non-loopback
  path exists); no path traversal (no file-serving routes); response framing
  and UTF-8 octet-count Content-Length consistent; header injection not
  reachable with current static headers.
- **Graph phases**: dead-code = only the six known routes-table handlers;
  cycles = only the known intentional recursion; layer-violation empty;
  surprises all peripheral→core-fn noise.

---

Recommended first fix: issue 1 — the verify-path injection. Say the word and
I'll start with the failing tests (`focused-cmd` quoting breakout, scrubbed
env, redacted output), then the guard.
