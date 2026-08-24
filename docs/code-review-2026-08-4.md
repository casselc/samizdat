# Code review — 2026-08 fourth pass

Scope: full production corpus — `src/` (61 files), `gui/` (10), `vendor/` (17,
reviewed only at trust boundaries), `resources/cells/` (5). 94 files / 904
functions in the call-graph corpus; 5956 call edges. Not re-reviewed: the
three earlier 2026-08 passes (docs/code-review-2026-08*.md, 50+ findings, all
fixed at HEAD ca56f2f) — this pass verifies those fixes are present and hunts
what remains.

Method: chiasmus structural pipeline (summary / dead-code / cycles /
layer-violation; snapshot `review4`), hand-rolled orphan scan (regex over all
def forms, symbol-boundary aware), four parallel deep-read sweeps (LLM stack,
agent core, kernel/store, tools+manifests wiring), and main-thread
verification of every surviving candidate against the current tree. Every
finding below was re-checked by grep/read at reported line numbers after two
of the four subagent reports turned out to quote the *pre-review3* code from
the committed review docs instead of the working tree.

Baseline: `jolt test` — **999 tests, 3158 assertions, 0 failures, 0 errors**
(ulimit 1024). chiasmus: dead-code = only the 6 known `samizdat.server`
routes-table handlers (dispatched as vars — documented FP); cycles = the
intentional `decompose-node`/`solve` tree recursion; layer-violation = empty.

---

## Issues

### 1. MEDIUM — the safe-state failure rung is an entire unshipped feature wired as if live
`src/samizdat/agent/state.clj:663` (`mark-green`), `:669` (`snapshot-covers?`),
`:704` (`safe-state-due?`); `src/samizdat/agent/loop.clj:528-530`;
`src/samizdat/agent/gates.clj:98-115`; `resources/prompts/safe-state.md`.

`mark-green` — the only thing that ever sets `:green-snapshot` on a branch —
has zero production callers (one def, one comment in `resume.clj:65`, tests).
`snapshot-covers?` is test-only. `safe-state-due?` is genuinely wired from the
`:safe-state` gate (`gates.clj:106`) but can never fire because its first
conjunct reads a key nothing writes. `loop.clj:529` hard-codes
`(let [coverage nil ...] :safe-state-coverage coverage)` with a comment saying
it "stays dormant until the store-checkpoint version arrives."

Why it matters: the gate table, its `/v1/harness/gates` surface, the prompt,
and the budget key all present DS1's third failure rung as a live safety net.
An operator reading them would believe repeated-failure recovery exists; it
does not. There is also a latent message bug: `gates.clj:113` interpolates
`(:reason safe-state-coverage)` — nil today *and* nil-shaped if snapshots are
ever wired without coverage, producing "The harness CANNOT rewind for you:
Undo by hand…".

Verified by: grep `mark-green|record-snapshot` → def + comment + tests only;
`safe-state-coverage` produced only at `loop.clj:533` from the hard-coded
nil. Tests at `agent_test.clj:765` exercise the dead fns directly (a green
suite masking a dead feature).

Fix direction (TDD): decide ship-or-strip. To ship: write the failing test
that a confirmed `done` calls `mark-green` with the replay log and that the
next turn's `:safe-state-coverage` is non-nil; then wire the call site and
replace the hard-coded nil with `(state/snapshot-covers? branch log)`. To
strip: delete the three fns, the gate entry, the prompt, the budget key, and
the `loop.clj` coverage plumbing, and pin the absence the way
`:sketch-duplicate-threshold`'s removal is pinned (`agent_test.clj:279-285`).

### 2. LOW — unknown intervention `kind` returns 500, not 400 — **WITHDRAWN**
`src/samizdat/store/interventions.clj:50-52`, `src/samizdat/server.clj`
(catch-all at `:237`).

**Withdrawn during the fix pass**: `control/intervene!` already maps a bad
`kind` to 400 (`control.clj:232-238`, the "review3 #12" branch), and
`control_test.clj:152-157` pins it; the route passes `(:status r)`
through. This pass's grep for `400` ran against `server.clj` only and so
never saw the api-layer fix — a layer-blind search, not a missing fix.
The store-layer throw is real but unreachable through the api unchanged.

### 3. LOW — `lsp shutdown!` exists but nothing ever calls it
`src/samizdat/lsp/client.clj:190`.

Client entries are never removed from the `clients` atom and no production
path stops a spawned clojure-lsp. In practice `root` is single-valued
(`config [:run :root]` or `user.dir`, `workflow.clj:240`) and clients live
for the process lifetime, so the leak is bounded — but `shutdown!` is
written, tested, and dead.

Verified by: orphan scan (test-only, 1 ref) + grep — `client-for` call sites
are only `tools/lsp.clj:63,71`.

Fix direction (TDD): pick one — call it from system shutdown (`system.clj`)
with a test that the atom empties and the process is gone, or delete it.

### 4. LOW — `gates/reload-config!` unwired; gate thresholds need a process restart
`src/samizdat/agent/gates.clj:52`.

`gates.edn` is read once into `config-cache`; `reload-config!` (tested) has
no production caller, so tuning a threshold mid-run is impossible by design
accident. Either wire it into the interventions surface (an operator
"reload-gates" directive) or drop it and the test.

### 5. LOW — `knowledge/forget!` unreachable from the tool surface
`src/samizdat/store/knowledge.clj:81`.

The knowledge tools can `remember` and `recall` but the delete path exists
only as a store fn + test. The tool surface's own philosophy (docstring:
"memories are cheap to re-record if a fact turns wrong") argues for the agent
being able to drop a wrong memory it just recalled. Wire a `forget` tool or
delete the fn.

### 6. LOW — GUI styling still colors the removed proof-engine tools
`gui/samizdat/gui/style.clj:66-72`.

`add_rule`/`retract_rule`/`verify`/`verify_smt`/`verify_template`/
`verify_lean`/`lean_search`/`proof_*`/`octave_eval`/`verify_octave`/`measure`
get prolog/smt/lean/octave colors; none is a registered run-tool (current
registry: 24 methods — branch_theses cells complete doc done edit_file eval
fetch_artifact fetch_turn give_up grep introspect lsp manifest message
read_file recall reload_cells remember shell skill task thesis write_file).
Cosmetic: unknown tools fall through to `:meta`. Residue of review3 #6's
tool-surface cleanup, which updated arbiter/state/prompt but not the GUI.

Fix direction (TDD): a style test asserting every colored name is in the
registered-tool list (the same pinning style as the vocabulary test), then
trim to the real tools — or reduce to `:else :meta` if no live tool merits a
non-meta class.

### 7. LOW — `journal/unsettled-gates` is test-only
`src/samizdat/store/journal.clj:317`.

Store fn exported, tested, never called from production. Either surface it
(the obvious consumer is a run-detail view showing gates that fired but never
settled — genuinely useful diagnostics) or fold it into the test namespace.

### 8. INFO — factory `worker` manifest declares no `:effects`
`resources/manifests/worker.edn`, surfaced as
`WARN samizdat.workflow - loop definition compiled with warnings:
[{:type :undeclared-effects, :cell :loop/worker}]` on every suite load
(`orchestrator.edn:15` composes it as a subworkflow).

Effect declarations are enforced only on agent-authored mutations
(`mutation.clj:103`); the factory manifests were never backfilled. Either
declare the worker's effects (silencing the standing WARN) or accept the
noise — but the warning firing on every load of *factory* definitions makes
real agent-mutation warnings easier to skim past.

### 9. INFO — `dev/nrepl_client.clj` is a manual-only utility
Zero code references; on the classpath only via `:dev`. Correct as a
developer CLI (`-m nrepl-client`), recorded so a future orphan sweep doesn't
re-flag it.

---

## Clean areas (verified this pass)

- **Review-3 fixes all present at HEAD**: chunked refused with 411 +
  413 payload cap + SO_RCVTIMEO + 2 MiB default (`adapter.clj:185-214, 337-341,
  373`); send-all throws on stall (`:290-297`, cites review3 #12); accept-loop
  stop-race closes the fd (`:352-355`); drain-after-error; 409/411/413/503
  reason phrases (`:252-253`); negative-limit clamp (`api/runs.clj:45-48`);
  verify runs scrubbed-env + redacted output + quoted root
  (`agent/verify.clj:125-142`); gitdiff scrubs (`gitdiff.clj:18`); reap!
  enumerates the tree and SIGKILLs TERM-trappers (`engine/proc.clj:52-75`,
  cites review3 #8). The pipe-drain deadlock stays refuted (review3's 300 KB
  probe, `docs/code-review-2026-08-3.md:320`).
- **UTF-8 split across recv boundaries: REFUTED (was this pass's own issue 8,
  withdrawn).** `read-request` does NOT decode per recv — review3 #5's fix
  accumulates raw octets in one byte-array (`ffi/read-into!` appends each
  recv's slice, `adapter.clj:198-214`) and decodes once, from all of them
  (`decode-acc`, `:172-182`, whose comment names the exact U+FFFD failure
  mode). A single decode of a complete buffer cannot split a codepoint. The
  stale "per-recv str concat" shape quoted in an earlier draft of this report
  was the pre-review3 code.
- **Query params: percent-decoding present — refuted (was issue 9,
  withdrawn).** `url-decode` (`server.clj:54-79`, cited "review3 #12")
  decodes `%xx`/`+` byte-wise and rebuilds the string once from UTF-8, and
  `query-param` pipes through it (`:81-86`). `/slow`'s `ms` is likewise
  clamped to 10 s (`clamp-slow-ms`, `:147-154`, review3 #4) — the
  "Long/MAX_VALUE lease" claim was also stale.
- **Tool surface wiring complete, both directions**: all 24 `run-tool`
  methods documented in `resources/prompts/system.md` and vice versa; all 13
  tool-group namespaces required by `tools.clj`. (Subagent-reported "9
  unregistered verification tool names" and arbiter references to
  `retract_rule`/`add_rule`/`sketch` were stale — those survive only as
  comments explaining their removal: `arbiter.clj:78,131`; the live
  `verification-tools` is `#{"eval" "shell"}`, pinned by test per review3 #6.)
- **Manifests ↔ cells**: all 10 manifests' cells resolve to defined defcells;
  all 20 defcells referenced by ≥1 manifest; orchestrator's
  `:subworkflows {:loop/worker "worker"}` resolves.
- **Prompts**: all wired except none orphaned (the subagent's
  `tier-escalation.md` orphan does not exist — already deleted).
- **Gates config**: all 29 threshold/budget lookups in src resolve to keys in
  `gates.edn`; the 4 keys deleted in review-2 stay gone (pinned by
  `agent_test.clj:279-285`); no consumed-but-missing key.
- **Config defaults**: every default in `config.clj` has a production
  consumer; run-level optional keys all have fallbacks.
- **ring_chez trust boundary**: bind address hard-coded loopback
  (`adapter.clj:103-104`); no non-loopback code path exists.
- **Store layer spot checks**: parameterized SQL throughout; timestamps
  stringified at write; claim path guarded (verified in review-2, unchanged).

## Tool false positives (documented, not findings)

- chiasmus dead-code: the six `samizdat.server` handlers dispatched through
  the `routes` var table.
- chiasmus cycles: `decompose-node`/`solve` intentional recursion.
- Hand-rolled orphan scan first run reported 127 candidates — the scan's own
  bug (symbol-boundary regex included `/`, hiding every qualified call).
  After the fix: 26 candidates, of which the vendor/mycelium test-only fns
  are library surface, and the rest are covered by issues 3-7 above.
- Two of four subagent sweeps reported pre-review3 code as current (quoting
  the committed review docs rather than the tree). Their stale items were
  individually re-verified against HEAD before being discounted here — worth
  remembering on the next pass: a report quoting line numbers that don't
  match the tree is evidence about the report, not the tree.

## Recommended first fix

Issue 1 (safe-state rung) is the only one that misrepresents a safety
feature — everything else is dead code or conformance polish. Say the word
and I'll start with the failing tests: either the ship-path test (a confirmed
done marks green and the next turn sees non-nil coverage) or the strip-path
pin (the gate table no longer contains `:safe-state`).

---

## Fix log — 2026-08-4, same day

All actionable findings fixed on the working tree the same day; suite green
before commit.

- **#1 safe-state rung — SHIPPED.** `mark-green` now takes `[branch]` and
  stamps `:green-snapshot (count (:turns branch))` — the turn cursor into
  the journal replay log. Called from `loop.clj` `tool-step` in the
  artifact branch when `(:claim-status artifact)` is `:confirmed`.
  `snapshot-covers? [branch]` reads it; `loop.clj` steer-step now passes
  `(state/snapshot-covers? branch)` where a hard-coded `nil` used to be
  (with a comment claiming dormancy). `safe-state-due?` (2× cull-threshold
  window) unchanged. `:green-at-turn` renamed `:green-snapshot` throughout;
  gate message and `:doc` updated to match. Tests: rewritten
  `safe-state-coverage-gate` + new `confirmation-marks-the-green-point`;
  live probe verified the gate trips at 2× cull with a rendered message.
- **#2 intervention 400 — withdrawn**, see the issue text: fixed at the
  control layer since review3; this pass searched the wrong layer.
- **#3 `lsp shutdown!` — WIRED** via new `client/shutdown-all!` (locking,
  drains every root) called from `system/stop!`'s teardown doseq, so lazily
  spawned clojure-lsp children die with the harness. Test:
  `shutdown-all-empties-the-registry` (junk fake clients must not throw).
- **#4 `gates/reload-config!` — WIRED** into `system/start!` after config
  load: the config-cache atom survives `stop!`/`start!`, so a restart
  previously kept serving pre-edit thresholds forever. Test:
  `start-reloads-gate-thresholds` (redef-counted).
- **#5 `knowledge/forget!` — WIRED** as a `forget` tool (`{id}` → ok/fail),
  documented in `resources/prompts/system.md` next to remember/recall, with
  a tool-level test covering delete, unknown id, and missing-arg paths.
- **#6 GUI proof-engine colors — FIXED.** Families are now the real work
  kinds (`:claim :edit :read :run :meta`) over registered tools only;
  `every-classified-tool-is-a-registered-run-tool` pins palette keys to the
  families, and the are-table pins representative tools per family. A
  vocabulary probe over the 25 registered run-tools confirms zero ghost
  names (an initial draft of this very fix classified a nonexistent
  `mutate` — the probe caught it; it stays in /tmp/probe-vocab.jolt).
- **#7 `journal/unsettled-gates` — WIRED** into `api/runs.clj`
  `branch-detail` as `:unsettled-gates` — the run's own account of advice
  that fired and never settled, next to the turns it targeted. Test:
  `branch-detail-surfaces-unsettled-gates`.
- **#8 worker `:effects` WARN — FIXED** structurally: `workflow->cell`
  (`vendor/mycelium/compose.clj`) now aggregates subworkflow effects into
  the wrapper cell spec — pure only if every child is pure, undeclared if
  any child is undeclared, else the union. The factory manifest needs no
  backfill and future composed loops inherit the same discipline. 4 new
  compose tests (13 tests / 35 assertions green).
- **#9 dev nREPL client — INTENTIONAL**, recorded, no action.

Standing note: the fix pass also refreshed `resources/prompts/safe-state.md`
("rules" → "what you changed") — stale proof-era wording flagged during
finding #1's read of the prompt.
