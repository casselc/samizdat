# JS1 Convergence Plan: Port the Contract

## Decision

Do **not** rebase `js1-bounded-samizdat`, merge its scheduler, or cherry-pick
its history onto current Samizdat.  It is an executable reference,
conformance oracle, and evidence corpus.

Converge on a fresh branch from current upstream by adding the bounded
evaluator contract at current seams.  Preserve current userspace, manifests,
tape, scheduler, task, provider, resume, and adaptation architecture unless a
specific JS1 invariant demonstrably requires a change.

This is a plan only.  It does not authorize implementation or JS2.

## Coordinates inspected

| Role | Coordinate |
|---|---|
| Current Samizdat upstream | `yogthos/samizdat` `main` `5aa94769160a92ffb5131adf776fdc06f6157405` |
| JS1 reference | `casselc/samizdat` `js1-bounded-samizdat` `3d718ba` |
| Frozen pre-canary JS1 PASS runtime | Samizdat `897cf534ffd12939c17048477c83fb4be4560672` |
| Bounded Jolt reference | `casselc/jolt` `js1-runtime-current-upstream` `4af2362176160f2ed0e366689d7232b1a38adfec` |
| SCI reference | `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53` |

The old JS1 and current upstream share fork point `dae7854`.  Upstream has
since adopted userspace-per-project, manifest-defined round/turn behavior,
tape/inference, task ownership, watch/steer/adaptation, and six audit rounds.
Those are integration inputs, not regressions to undo.

## Current architecture relevant to JS1

### Base versus project userspace

RFC-001's rule is binding: `src/` supplies mechanisms; versioned project
userspace supplies decisions.  `samizdat.userspace` and
`samizdat.store.userspace` own project-local cells, manifests, policies, and
prompts.  An evaluator is a base capability.  A project's choice to use a
bounded evaluator profile, its prompt wording, deadline policy, and provider
selection are userspace/config decisions.

### Current execution path

The current path is not the reference branch's old loop:

1. `samizdat.agent.infer` projects a branch to a pure `samizdat.tape`, invokes
   injected `complete-fn`, and absorbs the response.
2. `samizdat.workflow/run-turn` is the one turn composition, compiled from a
   manifest slice by `samizdat.manifests` / `workflow/compile-turn-loop`.
3. `samizdat.agent.beam/run!` and `run-rounds` own rounds, deadlines,
   in-flight bookkeeping, culling, directives, run teardown, and resume entry.
4. Cells/manifests in project userspace decide workflow behavior; task claims,
   gates, phases, steering, and adaptation are current policy mechanisms.
5. `store/journal`, `store/runs`, and `agent.resume` preserve and rebuild run
   state.  `tape` is durable/model-message-adjacent state, not evaluator state.

The integration must retain the two-level `beam.edn` / `loop.edn` model,
userspace prompt rendering, task ownership, tape fork/probe behavior, and the
post-audit fairness/resume fixes.

## JS1 contract inventory

The following are conformance properties, not prescriptions for old file
layout.

1. **Evaluator lifecycle.**  Distinguish immutable `EvaluatorSpec`, live
   `EvaluatorInstance`, and work-scoped `EvaluatorBinding`.  A branch, task,
   cell, workflow, or provider is none of these.
2. **Authority.**  Capability selection is controller-owned:
   `requested ⊆ authorized ⊆ profile maximum`; explicit capability IDs are
   checked at every dispatch; a compiled context is not itself permission.
3. **Model surface.**  JS1 exposes only `eval`, `doc`, `complete`, and `done`.
   Project operations are Clojure vocabulary inside `eval`:
   `project/read`, `project/list`, `project/search`, `project/stat`, and
   `project/edit`.
4. **Authority-derived discovery.**  Prompt, `doc`, and `complete` are
   projections of the binding's effective ContextSpec/catalog.  They may not
   advertise an unavailable capability.
5. **Persistent bounded SCI.**  Successful definitions persist for the
   binding; model code cannot select authority, root, profile, or instance.
6. **Commit-only state.**  Failed/interrupted evaluations rebuild to committed
   history; rollback failure poisons the instance rather than continuing.
7. **Durable replay.**  Each evaluation has append-only intent/outcome receipts
   and a terminal completion.  Fresh-process reconstruction replays all
   committed history in one fresh SCI context, makes zero real world calls,
   consumes exactly the recorded receipt trace, and fails closed on mismatch,
   gap, pending intent, or unconsumed receipt.
8. **Mutation.**  Project edits are root-confined, anchored optimistic edits;
   paths, symlinks, UTF-8/bounds, and write size fail closed.
9. **Trusted completion.**  `done` reaches controller-owned verification with
   structured argv, scrubbed/redacted bounded output, and scoped-process
   cleanup; the model never supplies an arbitrary shell.
10. **Turn authority.**  Every turn has controller-minted TurnLease authority.
    Permit issuance/effect initiation and revocation are linearly ordered;
    stale turns cannot initiate a new effect.  Revocation precedes interrupt;
    non-quiescence fails closed.
11. **Budget authority.**  Widening max turns is a separate trusted,
    monotonic, idempotent, ceiling-bounded transaction with retained audit.
12. **Coordinates and scope.**  RuntimeCoordinate, ContextSpec, authority, and
    receipt protocol coordinates are exact replay inputs.  JS1 is initially
    single-player: multi-branch and whole-run fan-out are refused.

## Mapping to current upstream

| Contract | Reference implementation | Current seam | Treatment | Evidence / principal risk |
|---|---|---|---|---|
| Spec / instance / binding | `agent/sandbox.clj` | new base evaluator namespace; `workflow`, `beam` ctx | rederive | lifecycle identity tests; do not tie binding to branch/provider |
| JS0 SCI authority / receipts | Jolt `jolt.sandbox` | pinned Jolt adapter/library boundary | use reference runtime | JS0 authority/replay conformance; ordinary JVM must not load SCI |
| Capability profile selection | hardcoded `:project/develop` in old workflow | project userspace policy + controller config | rederive | cells/manifests cannot widen profile |
| `eval/doc/complete/done` dispatch | old `tools/base`, `tools/repl`, `loop` | current tool registry plus `agent.infer` / turn manifest | rederive | prompt/tool vocabulary equality |
| Authority-derived prompt | old `loop/orient-messages` | `samizdat.prompt`, userspace prompt templates, infer render | port concept | generic prompt must remain unchanged outside JS1 |
| Persistent SCI state | old sandbox provider registry | new evaluator provider/registry | port concept | stable instance/binding IDs across rebuild |
| Evals / receipts | `store/evals.clj` | new append-only store beside journal/tape | port / adapt | tape is LLM state, eval receipts are SCI-world state; never conflate |
| Resume reconstruction | old `agent.resume` | current `agent.resume` / `workflow/compile-turn-loop` | rederive | preserve current task claims, tape, per-branch problem, provenance |
| Project operation substrate | old sandbox ops + `agent.files` | current confined file mechanism, or new evaluator adapter | port invariants | root/symlink/anchor tests |
| Done verification | old `verify`, `engine.proc`, Jolt scope | current verification/security policy chokepoints | use upstream where stronger; port only gaps | preserve current grants/redaction and Jolt scoped primitive |
| TurnLease | old `tools/base` + `beam` | current `beam/advance-all` / in-flight/deadline path | rederive | one scheduler owner; no second turn loop |
| Budget extension audit | old `security/controller`, `runs` | current control/runs store | port / rederive | upstream bare extension is insufficient; retain task/control audits |
| Provider provenance | old run-row only | current `llm.adapter`, `client`, `registry`, tape/inference | use upstream + extend provenance | provider switch must not alter evaluator coordinate |
| Single-player restriction | old branch/whole-run guards | current workflow selection/beam width/manifests | port invariant | reject before provider spend |
| Steering/adaptation observation | old gate counters only | current gates, `session`, `watch`, `memory`, `knowledge` | use upstream + add read-only signals | no automatic authority widening |

## Overlap: use current upstream rather than port old code

Current upstream already solves, more generally, the following concerns:

- userspace versioning, cache invalidation, prompt/cell/manifest ownership;
- one canonical turn composition and manifest-slice validation;
- tape rendering, probes, context budgeting, provider retry/fencing/cache keys;
- task holder guards, task statement pinning, resume restoration, and branch
  fairness/culling;
- directive queues, watcher/steer separation, adaptation/knowledge memory;
- post-audit resume fidelity, in-flight turn handling, stop/abort windows, and
  per-branch problem restoration;
- security policy/grant, secret redaction, and model-bound shell chokepoints.

The old JS1 branch deleted or bypassed several of these while proving a narrow
bounded run.  Those deletions are not JS1 requirements.  In particular,
`tape` and evaluator receipts represent different traces and both belong in
the converged system.

## Gaps current upstream still has

- no first-class EvaluatorSpec / Instance / Binding abstraction;
- no bounded persistent SCI provider or explicit semantic-operation replay;
- no append-only evaluator receipt store / exact whole-history reconstruction;
- no authority-derived bounded prompt or four-tool JS1 surface;
- no lease permit/revocation fence at evaluator effect initiation;
- no controller-only retained, idempotent budget-extension authority matching
  the JS1 contract;
- no durable evaluator-level provenance linking receipt history to the model
  provider/model transition that produced each inference epoch.

## Proposed evaluator abstraction and lifecycle

### Base objects

`EvaluatorSpec` is inert, canonical, and self-certifying.  It contains a
profile ID, effective capability IDs, bounds, root identity, timeout, and a
ContextSpec coordinate.  `EvaluatorInstance` is the live Jolt/SCI context.
`EvaluatorBinding` ties one instance identity to one work identity and records
the RuntimeCoordinate.

The controller selects a userspace-declared profile and may attenuate it.
Cells receive a binding reference but never an authority-construction API.
The binding is installed in current scheduler ctx only after the controller
has minted it.

### Evaluation state machine

`begin → pending → intent* → outcome* → completed|failed` is append-only.
`pending` means no terminal completion row; an intent without outcome is
unknown actuation and blocks completion/resume.  A recorded failure rebuilds
from committed history.  A rebuild validates all coordinates before allocating
a replacement context and replays in receipt mode with zero host operations.

This is not a second tape: tape tracks model conversation and probes; evaluator
history tracks SCI code and semantic world operations.  Resume restores both,
in their respective current-upstream order.

### Provider provenance

Provider/model choice remains an inference coordinate owned by current
`llm.adapter`/`client`/`registry`; it is not evaluator authority.  A controller
may switch providers at a turn boundary without changing ContextSpec,
RuntimeCoordinate, or replay semantics.  Journal an explicit
`inference-epoch` record containing provider/model/adapter settings and the
turn range.  Eval records reference the active epoch (or a stable epoch ID),
so evidence can state which provider produced source without treating that
provider as an authority grant.

### Scheduler / TurnLease

`beam/advance-all` remains the only owner of turns.  It mints a lease per
iterating single-player turn, passes it through the current turn ctx, revokes
before deadline interruption, then awaits quiescence.  A lease permit and the
durable semantic-operation intent append share one linearization monitor:

`permit first → effect was authorized`; `revoke first → stale refusal and no
launch`.

Current upstream in-flight/forfeit accounting, abort, directives, fairness,
and manifest-defined scheduling stay intact.  A failed-quiescence result
records retained terminal reason and is non-resumable.  Non-iterating or
multi-branch JS1 shapes refuse before provider work.

### Authority → discovery → prompt

At send time, compose a JS1 system projection from:

1. the binding's effective ContextSpec capability catalog;
2. base descriptions for exactly `eval/doc/complete/done`; and
3. a project-userspace bounded prompt fragment teaching persistent SCI
   programming and project operations inside `eval`.

The base renderer emits only capability descriptions actually present in the
binding.  Userspace supplies wording and task guidance but cannot name a
capability absent from the projection.  `doc` and `complete` read the same
catalog.  This preserves current per-project prompts while preventing the
attempt-1 generic-tool mismatch.

## Adaptation and steering signals

Expose read-only evaluator telemetry to current `session`, `watch`, and
adaptation cells: repeated evaluation failures, completed evaluations without
new artifact/mutation/test evidence, repeated project reads/searches without
an edit or verification attempt, unresolved intent, repeated RED outcomes,
and provider degradation/retry latency.  Steering may select an existing
userspace directive, task, workflow, or provider epoch at a boundary.  It may
not mint capabilities, increase a profile, bypass a lease, or widen budget.

The ongoing canary confirms the importance of these signals: authority-derived
prompting changed behavior from zero operations to real read/search/edit/test
work, but a long sequence of successful `eval` calls can still make no forward
progress.  That is a current steering/adaptation input, not a reason to weaken
the evaluator boundary.

## Convergence milestones

Each milestone is a stop-and-review point, not a promise to immediately start
the next one.

1. **Evaluator/store seam.**  On a fresh `5aa9476` branch, add inert
   Spec/Instance/Binding types and append-only evaluator tables, retaining
   tape/userspace unchanged.  Gate: plain-JVM receipt/store properties and
   migration/version tests.
2. **Read-only bounded SCI slice.**  Add Jolt-backed `:project/read` binding
   behind an explicit optional runtime adapter, plus four-tool dispatch and
   authority-derived prompt/catalog.  Gates: no-SCI normal suite; pinned-Jolt
   authority/prompt/attenuation tests; no generic prompt leakage.
3. **Develop/replay slice.**  Add `project/list/search/stat/edit`,
   intent/outcome receipts, commit-only state, and fresh-process whole-history
   replay.  Gates: root/anchor properties, zero-real-operation replay witness,
   runtime/spec/receipt mismatch refusal.
4. **Current scheduler/resume integration.**  Re-derive TurnLease in current
   `beam/advance-all`; journal binding and inference epochs; rebuild evaluator
   beside current tape/task/branch resume.  Gates: deterministic
   permit/revoke interleavings, deadline non-quiescence, task/tape resume
   fidelity, and provider-switch provenance without evaluator change.
5. **Current-upstream canary and conformance freeze.**  Run a disposable
   single-player task through current manifests/userspace with bounded prompt,
   focused verification, restart/rebuild witness, and no authority widening.
   Freeze a contract test matrix against the Jolt reference runtime before any
   multi-agent evaluator or JS2 work.

## First convergence gate

The first executable gate after milestone 1 is deliberately small:

1. mint an attenuated read-only binding for a temporary project root;
2. use the four-tool surface to define a persistent helper and perform one
   `project/stat` operation;
3. assert durable `begin → intent → outcome → complete` rows;
4. construct a fresh evaluator, replay history, and prove helper availability
   with zero real semantic operations; and
5. run the ordinary current upstream suite without SCI plus the pinned SCI
   conformance lane.

Do not add edit, scheduler leases, self-hosting, or provider switching until
this two-lane gate is green and reviewed.

## Deliberately deferred

- JS2, `project/run`, execution environments, SmolVM workers, or generic
  shell;
- multi-agent / shared evaluator bindings;
- automatic authority/budget widening;
- replacing upstream prompt/userspace, tape, manifest, task, or scheduler
  architecture;
- rebasing the old JS1 branch or moving the Jolt reference coordinate;
- treating the incomplete historical/current canary as convergence evidence.

## Recommendation

**Start a fresh branch from `5aa94769160a92ffb5131adf776fdc06f6157405`.
First implement the inert evaluator-spec/binding plus append-only evaluator
receipt-store seam.  Preserve the twelve contract invariants above.  Use
current upstream userspace, manifests, tape/inference/provider, scheduler,
tasks, resume, security policy, and adaptation machinery unchanged.  Re-derive
only evaluator lifecycle, semantic receipt/replay, and lease integration at
their current seams.  Run the first convergence gate above.  Stop and review
before the bounded SCI read-only slice.**
