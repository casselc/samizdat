# JS1 Cutover — REVISE

## Decision

**REVISE.** The JS1 cutover is not approved to merge. Several required PASS
criteria remain unmet, and nothing should be read into the passing gates as
approval. This record leads with the decision, then states exactly which gates
executed and which PASS criteria are unmet, and preserves the standing
non-claims verbatim.

## Baseline

- Samizdat `main` @ `b93d601`. All JS1 work is **uncommitted** (working tree only).
- Jolt upstream JS0: `04dd42db` on branch `js0-functional-sci-upstream`.
  - Frozen tag: `js0-functional-sci-upstream-freeze`.
  - Upstream baseline: `c4547b5e`.
  - SCI: `32d62a51`.
- Upstream action: **none** — no Samizdat PR opened, no push made.

## Executed Gates (verified)

| Gate | Result |
|---|---|
| Full Samizdat Jolt suite | `946` tests / `2998` assertions |
| Direct sandbox (offline `-Scp` invocation) | `14` / `111` |
| Eval store (`samizdat.store.evals`) | `13` / `58` |
| Wiring require | guarded `samizdat.agent.sandbox` require verified; non-JS1 path incurs no dependency cost |
| Model tool surface | closed set `#{eval doc complete done}` (verified) |
| Host capability authority | `tools/base.clj` `phase-refusal` — single pre-dispatch enforcement point; profile flag set only from trusted config/workflow, never model input |
| Replay | journal `:js1-binding-created` carries `profile`, `binding-id`, `instance-id`, `spec-coordinate`, `preset`; `store/evals` durable ordered receipts `{op args result|error}` |
| Evaluator binding seam | `samizdat.agent.sandbox` `EvaluatorSpec` / `Instance` / `Binding` (three coordinates); `:js1/binding` in ctx checked first by `eval`/`doc`/`complete` → `sandbox/evaluate!` |
| Upstream PR / push | **none** (no PR, no push) |

## Unmet PASS Criteria (exact)

- **No real model dogfood task** — no real model has exercised the JS1 tool
  surface end-to-end.
- **No actual process terminate / restart / resume evidence** — durable restart
  is **not** demonstrated.
- **No frozen bbagent A3c comparison** — the frozen `bbagent` A3c baseline was
  not produced or compared.
- **No live controller budget proof** — no demonstration of a live controller
  budget under JS1.
- **No cross-platform run** — no non-Jolt / plain-JVM (or any other) platform
  lane was executed.

## Durable-restart status

**Not claimed completed.** Only the fail-closed resume *policy* is implemented;
no actual terminate → restart → resume cycle has been executed end-to-end.

## Files (uncommitted inventory, `main` @ `b93d601`)

Modified:
- `resources/prompts/system.md`
- `src/samizdat/agent/beam.clj`
- `src/samizdat/agent/files.clj`
- `src/samizdat/agent/resume.clj`
- `src/samizdat/agent/tools.clj`
- `src/samizdat/agent/tools/base.clj`
- `src/samizdat/agent/tools/repl.clj`
- `src/samizdat/store/migrations.clj`
- `src/samizdat/workflow.clj`
- `test/samizdat/test_runner.clj`

New (untracked):
- `src/samizdat/agent/sandbox.clj`
- `src/samizdat/store/evals.clj`
- `test/samizdat/evals_test.clj`
- `test/samizdat/sandbox_test.clj`
- `docs/JS1_FINDINGS.md`
- `artifacts/js1-evidence.edn`

## Non-Claims

### What IS claimed
- The JS1 phase-refusal gate rejects tools not in the closed vocabulary before dispatch.
- When `:js1/binding` is present in ctx, `eval`/`doc`/`complete` route to the sandbox binding and never touch `samizdat.repl`.
- When `:js1/binding` is absent, the tools behave exactly as before (bit-for-bit unchanged path).
- Resume of a JS1-profiled run fails closed when SCI is unavailable.
- The JS1 profile flag is set only from trusted config/workflow code, never from model input.
- The nREPL, developer REPL, and all non-JS1 workflows are unaffected.

### What is NOT claimed
- **Evaluator spec/instance/binding IDs in durable eval calls**: The existing `runs/start-run!` API in `store/runs.clj` does not expose sandbox binding identity. The journal's `:js1-binding-created` event carries the binding/spec/instance IDs for resume, but these are NOT incorporated into the eval turn rows themselves (the turns table schema was not editable). This is documented as an **integration blocker**.
- **SCI availability on plain JVM**: The sandbox requires `jolt.sandbox` and SCI, which are jolt-only. New-run fallback is logged; resume is fail-closed. This is a platform constraint, not a code defect.
- **JS1 doc/complete fidelity**: SCI `ns-publics` and `resolve`/`meta` provide a subset of the live REPL's introspection. Core var metadata may be stripped by jolt. The model gets correct answers for project vars and clojure.core; metadata-dependent features (arglists from core) may be degraded.
- **Beam multi-branch JS1 isolation**: All branches share the `:main` instance. A controller needing per-branch isolation must pass a distinct `:instance/key`. The current implementation does not do this automatically.
- **Performance**: No measurement of JS1 eval latency vs. live REPL has been made.

## Integration Blocker

The `runs/start-run!` function and the turns table schema do not carry sandbox binding metadata (evaluator spec, instance ID, binding ID). The journal's `:js1-binding-created` event records these for resume reconstruction, but the individual eval turn rows in the `turns` table have no column for them. Adding this would require either:
1. A schema migration (editing `stores/migrations/evals` — excluded by the file fence), or
2. A separate join table mapping turn IDs to binding metadata.

Neither is attempted. The journal event is the authority for resume.
