# JS1 M1 Evidence

## Frozen Coordinates

- Samizdat base: `5aa94769160a92ffb5131adf776fdc06f6157405`
- Convergence branch: `js1-m1-glm-5aa9476`
- JS1 oracle: `casselc/samizdat@897cf534ffd12939c17048477c83fb4be4560672`
- Jolt: `casselc/jolt@4af2362176160f2ed0e366689d7232b1a38adfec`
- SCI: `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`

## Deterministic Gate

`test/samizdat/evaluator_test.clj` records and checks:

- exact EvaluatorSpec, EvaluatorInstance, EvaluatorBinding, ContextSpec, and
  RuntimeCoordinate identities;
- one data-dependent `project/list` then `project/search` evaluation with local
  distinct/take/vector aggregation and ordered durable receipts;
- helper persistence across committed evaluations;
- failed-evaluation rollback to committed state;
- pre-replay mismatch/pending refusal, request-time receipt mismatch/exhaustion,
  and post-evaluation unconsumed-receipt refusal before accepting a context;
- fresh-context whole-history reconstruction with stable logical identities and
  zero real semantic operations;
- exact `eval/doc/complete/done` plus read-profile discovery and prompt surface;
- no-network current manifest/turn/infer/parse/dispatch/tape traversal and
  zero-execution refusal of a fake top-level `shell` call;
- exact leverage facts derived only from receipts.

The exact bounded command is `bin/js1-m1 test`. It refuses a dirty Jolt or SCI
checkout before running, because source-mode evidence is meaningful only when
the frozen commit describes the loaded bytes.

## Scope

M1 does not claim project mutation, shell or network authority, trusted
verification, successful `done`, TurnLease/scheduler integration, budget
extension, provider epochs, shared evaluators, canary evidence, JS2, or M2+.

## Outcome (2026-08-27)

**M1: FAIL — exact technical blocker/evidence recorded; STOP**

The strict bounded coordinate gate refuses to run:

```text
$ bin/js1-m1 test          # (same refusal from bin/js1-m1 check)
js1-m1: pinned Jolt checkout has tracked modifications; exact evidence refused:
 M Makefile
 M host/chez/java/ffi.ss
 M host/chez/run-gosm.ss
 M host/chez/seed/image.ss
 M jolt-core/jolt/analyzer.clj
 M jolt-core/jolt/backend_scheme.clj
 M jolt-core/jolt/ir.clj
 M stdlib/clojure/core/async.clj
 M stdlib/jolt/ffi.clj
exit 1
```

Exact coordinate facts at refusal:

- `../jolt` HEAD is the pinned SHA `4af2362176160f2ed0e366689d7232b1a38adfec`
  (commit subject: "docs: record final Jolt runtime evidence").
- The M1-relevant Jolt files are unmodified: `jolt-core/jolt/sandbox.clj`,
  `stdlib/jolt/fs.clj`, and `vendor/sci` (HEAD `32d62a5136ad3dc148588752f5bcc4cc30b14752`,
  clean, `SCI_VERSION` = `0.13.53`).
- The 9 tracked modifications are unrelated in-flight work (AFFINE resources /
  compiler / FFI effort, plus untracked `AFFINE_RESOURCES_*.md` and
  owned-resource tests) that must be preserved, not reverted or committed from
  this milestone's boundary.
- The live runtime self-describes the same fact: the RuntimeCoordinate records
  `:runtime/jolt-version "js1-runtime-final-dirty"`, so even a green bounded run
  over these bytes is not frozen-coordinate evidence.

Advisory runs (executed over the dirty runtime; NOT frozen-coordinate proof):

- Bounded conformance lane, run manually with the exact `bin/js1-m1 test`
  command minus the refusing clean-check (`SAMIZDAT_BOUNDED_TEST=1`, SCI via
  `-Sdeps` local/root pin): `samizdat.evaluator-test` — 4 tests, 46 assertions,
  0 failures, 0 errors; printed `M1-EVALUATOR-IDENTITIES` (instance
  `inst:m1-conformance`, binding `bind:m1-conformance`, profile
  `:agent/project-read`, capabilities exactly the four read operations) and
  `M1-LEVERAGE {:evaluations 5, :operations-per-eval [2 0 0 0 0],
  :multi-operation-evals 1, :operation-order [[:project/list :project/search]
  [] [] [] []]}`.
- Ordinary no-SCI full suite (`ulimit -n 1024; ../jolt/bin/jolt -M:test`):
  1343 tests, 4781 assertions, 0 failures, 0 errors.

Verdict rule applied: the sibling pinned Jolt checkout remains dirty; per the
frozen M1 prompt the exact gate failure is recorded as M1 FAIL rather than
altering that repository. Samizdat-side M1 work is left complete and green in
the working tree for a rerun once the Jolt checkout is clean at the pinned SHA.

## Closure (2026-08-27, verification fence)

The initial M1 FAIL above stands unchanged. Nothing in this closure alters the
verdict: the exact bounded gate still refuses to run because the sibling pinned
Jolt checkout carries 9 tracked modifications and the Samizdat working tree is
itself dirty.

### Store-test fence (owned files only)

`test/samizdat/evaluator_store_test.clj` was cleaned and made to compile:

- Removed six stray `%%` comment artifacts (mangled `;;` markers) in the two
  `begin!`/last-insert-id regression tests.
- Removed one stray `)` in `begin-last-insert-id-stays-inside-the-writer-section`
  that left the form unbalanced and the namespace unreadable.

The writer-critical-section regression is grounded in the actual db locking:
`db/with-conn` serializes ALL connection access through one global `locking`
monitor (`conn-lock`), Jolt monitors are per-thread reentrant, and
`last_insert_rowid()` answers for the connection, not the statement — so
`begin!`'s INSERT and its `last-insert-id` read must hold the monitor
continuously. The test parks thread A inside the redefined `db/last-insert-id`
(post-INSERT, pre-return) and proves a second writer cannot enter its section
until A releases, and that the id A returns names A's own row.

Focused ordinary test (advisory, NOT exact-gate):

```text
$ ulimit -n 1024; ../jolt/bin/jolt -A:test -e '(require (quote clojure.test) (quote samizdat.evaluator-store-test)) (clojure.test/run-tests (quote samizdat.evaluator-store-test))'
Ran 3 tests. 213 assertions passed, 0 failures, 0 errors.
```

Deterministic across three consecutive runs.

### bin/js1-m1 fence (tested; no external repo changed)

- `bin/js1-m1 sha` emits the tested Samizdat SHA:
  `Samizdat 335e664f91f8e6877641f1bec1aeb5131813f04c`.
- `bin/js1-m1 check` (and `test`) refuses the dirty Samizdat checkout (7 tracked
  modifications) before reaching Jolt/SCI.
- `check_jolt` in isolation refuses the dirty pinned Jolt checkout (the same 9
  tracked modifications recorded above).
- SCI is clean at `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`, so the
  SCI refusal branch is correctly not exercised.

### Full ordinary no-SCI suite (advisory, NOT exact-gate)

```text
$ ulimit -n 1024; ../jolt/bin/jolt -M:test
Ran 1354 tests. 4986 assertions passed, 2 failures, 0 errors.
```

The 2 failures are both in `base_test.clj` and both flag the uncommitted
`src/samizdat/evaluator.clj` (the M1 evaluator), not the store test:

- `no-new-model-facing-prose-in-src` — model-facing prose in `src/`.
- `nothing-in-src-decides-what-the-harness-does` — hardcoded thresholds in
  `src/` (19 literals: 30000, 4096, 200000, 128, 191, 194, 223, 160, 225, 236,
  238, 239, 159, 144, 241, 243, 143, 8, 5).

These are policy violations in the M1 evaluator source (thresholds and prose
belong in `resources/`, per AGENTS.md), outside this fence's owned files. They
supersede the earlier "0 failures" advisory run recorded above, which predated
the M1 evaluator source landing in `src/`.

### Nonclaim

M1 remains **FAIL**. The advisory green store test and the `bin/js1-m1`
refusal behaviour are recorded as evidence, not as a PASS. The exact gate still
requires a clean committed Samizdat checkout plus a clean Jolt worktree at the
pinned SHA; neither holds, and the full ordinary suite is not green (2 failures
in the M1 evaluator source).

## Closure correction (2026-08-27, before frozen-base commit)

The two ordinary-suite policy findings above were corrected in the evaluator
before this M1 closure was committed: bounded messages use the trusted prompt
resource, and mechanism ceilings are grouped and justified without changing the
ContextSpec-controlled operation bounds.  The earlier failing ordinary result
remains historical evidence; it is superseded by these advisory reruns on the
same dirty Jolt runtime:

```text
$ ulimit -n 1024; ../jolt/bin/jolt -M:test
Ran 1354 tests. 4988 assertions passed, 0 failures, 0 errors.

$ SAMIZDAT_BOUNDED_TEST=1 ../jolt/bin/jolt -Sdeps '{:deps {borkdude/sci {:local/root "../jolt/vendor/sci"}}}' -A:test -e '... samizdat.evaluator-test ...'
Ran 13 tests. 160 assertions passed, 0 failures, 0 errors.
```

The bounded rerun covers component-wise symlink refusal, bounded/strict UTF-8
read semantics, deterministic `project/stat` digest, per-evaluation timeout and
token narrowing, append-only receipt/replay behavior, and current workflow
bounded-profile activation/refusal.  It remains advisory because the Jolt
runtime is dirty and Samizdat has not yet been committed.  The exact-gate
nonclaim above still applies until clean coordinates are tested.

## Final M1 PASS (2026-08-27)

The initial FAIL remains a truthful record of the first exact-gate attempt.  It
is superseded for the frozen M1 implementation by this clean-coordinate run:

```text
Samizdat implementation: bd6075f6e225e43e619ab991d2942f43217de8d4
Jolt clean worktree:     4af2362176160f2ed0e366689d7232b1a38adfec
SCI clean submodule:     32d62a5136ad3dc148588752f5bcc4cc30b14752 / 0.13.53
Chez:                    /usr/local/bin/scheme (10.4.1)
```

The Jolt worktree was separately created at the pinned detached SHA; the dirty
`../jolt` checkout was neither modified nor used for this exact bounded lane.

```text
$ JOLT_CHEZ=/usr/local/bin/scheme \
  JOLT_HOME=/home/chuck/opencode/src/jolt-js1-m1-clean bin/js1-m1 test
Samizdat bd6075f6e225e43e619ab991d2942f43217de8d4
Jolt 4af2362176160f2ed0e366689d7232b1a38adfec
SCI 32d62a5136ad3dc148588752f5bcc4cc30b14752 / 0.13.53
Ran 13 tests. 160 assertions passed, 0 failures, 0 errors.

$ JOLT_CHEZ=/usr/local/bin/scheme \
  /home/chuck/opencode/src/jolt-js1-m1-clean/bin/jolt -M:test
Ran 1354 tests. 4988 assertions passed, 0 failures, 0 errors.
```

The bounded lane printed exact EvaluatorSpec, logical EvaluatorInstance,
EvaluatorBinding, ContextSpec, and RuntimeCoordinate identities; the runtime
coordinate is `js1-runtime-final` (not the prior dirty value).  It recorded a
fresh-context replay witness with zero real semantic operations and leverage
facts `{:evaluations 5, :operations-per-eval [2 0 0 0 0],
:multi-operation-evals 1, :operation-order [[:project/list :project/search]
[] [] [] []]}`.

**M1: PASS — gate evidence recorded; STOP.**

Nonclaims remain: this does not start M2, authorize mutation/shell/network,
TurnLease or scheduler work, provider epochs, trusted verification, multi-agent
bindings, JS2, or an upstream forward-port.  Upstream motion after frozen base
`5aa9476` remains a deliberate M1→M2 boundary decision.
