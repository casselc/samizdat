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
