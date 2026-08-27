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
prompts.  An evaluator is a base capability.  Userspace decides only that a
workflow requests a profile (for example, `:project/develop`); the controller
decides what is granted (possibly `:project/read`); the trusted catalog decides
what either name can ever mean.  Prompt wording, deadline policy, and provider
selection remain project/config decisions.

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
2. **Authority.**  Userspace may request a profile but is never an authority
   source.  Effective authority is `userspace request ∩ controller
   authorization ∩ trusted profile/catalog maximum ∩ compiled runtime
   capability`.  Trusted capability IDs, profile maxima, ceilings, and
   controller policy live below self-editable userspace.  Explicit capability
   IDs are checked at every dispatch; a compiled context is not itself
   permission.
3. **Model surface.**  JS1 exposes only `eval`, `doc`, `complete`, and `done`.
   Project operations are Clojure vocabulary inside `eval`:
   `project/read`, `project/list`, `project/search`, `project/stat`, and
   `project/edit`.
4. **Authority-derived discovery.**  Trusted orientation, `doc`, and
   `complete` are projections of the binding's effective ContextSpec/catalog.
   They may not advertise an unavailable capability.  The orientation is
   rendered once when the binding is created, placed in the initial tape, and
   byte-stable/pinned for that binding lifetime; a material authority change
   creates a new binding/context coordinate rather than rewriting history.
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

13. **Two evaluator modes.**  Ordinary current Samizdat retains RFC-003's
    trusted in-process eval threat model.  Bounded JS1 is an explicit stronger
    mode: persistent SCI, no ambient host files/environment/secrets/network,
    and only projected semantic operations across the world boundary.  The
    convergence must not silently change ordinary-eval semantics globally.

14. **REPL leverage.**  The evaluator is a bounded programmable layer below
    the expensive model boundary.  It must support local observation,
    branching, filtering, aggregation, temporary abstractions, and persistent
    helpers while retaining per-operation authorization and receipts.  This is
    a product/evaluation behavior, not an authority widening rule.

## Mapping to current upstream

| Contract | Reference implementation | Current seam | Treatment | Evidence / principal risk |
|---|---|---|---|---|
| Spec / instance / binding | `agent/sandbox.clj` | new base evaluator namespace; `workflow`, `beam` ctx | rederive | lifecycle identity tests; do not tie binding to branch/provider |
| JS0 SCI authority / receipts | Jolt `jolt.sandbox` | pinned Jolt adapter/library boundary | use reference runtime | JS0 authority/replay conformance; ordinary JVM must not load SCI |
| Capability profile selection | hardcoded `:project/develop` in old workflow | userspace request + controller config + trusted catalog | rederive | userspace requests only; cells/manifests cannot widen or define maxima |
| `eval/doc/complete/done` dispatch | old `tools/base`, `tools/repl`, `loop` | current tool registry plus `agent.infer` / turn manifest | rederive | prompt/tool vocabulary equality |
| Authority-derived prompt | old `loop/orient-messages` | `samizdat.prompt`, initial `tape`, userspace prompt templates, infer render | rederive | trusted binding orientation is pinned once; generic prompt remains unchanged outside JS1 |
| Persistent SCI state | old sandbox provider registry | new evaluator provider owned by current run/context lifecycle | port concept | stable instance/binding IDs across rebuild; no global registry presumption |
| Evals / receipts | `store/evals.clj` | new append-only store beside journal/tape | port / adapt | tape is LLM state, eval receipts are SCI-world state; never conflate |
| Resume reconstruction | old `agent.resume` | current `agent.resume` / `workflow/compile-turn-loop` | rederive | preserve current task claims, tape, per-branch problem, provenance |
| Project operation substrate | old sandbox ops + `agent.files` | current confined file mechanism, or new evaluator adapter | port invariants | root/symlink/anchor tests |
| Done verification | old `verify`, `engine.proc`, Jolt scope | current verification/security policy chokepoints | use upstream where stronger; port only gaps | preserve current grants/redaction and Jolt scoped primitive |
| TurnLease | old `tools/base` + `beam` | current `beam/advance-all` / in-flight/deadline path | rederive | one scheduler owner; no second turn loop |
| Budget extension audit | old `security/controller`, `runs` | current control/runs store | port / rederive | upstream bare extension is insufficient; retain task/control audits |
| Provider provenance | old run-row only | current `llm.adapter`, `client`, `registry`, tape/inference | use upstream + extend provenance | provider switch must not alter evaluator coordinate |
| Single-player restriction | old branch/whole-run guards | current workflow selection/beam width/manifests | port invariant | reject before provider spend |
| Steering/adaptation observation | old gate counters only | current gates, `session`, `watch`, `memory`, `knowledge` | use upstream + add read-only signals | no automatic authority widening |
| REPL leverage / progress | canary receipts and persistent defs | current `session`, `watch`, adaptation, tape/turn records | rederive telemetry | activity is not world or objective progress |

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
RuntimeCoordinate, or replay semantics.  M3 journals an explicit
`inference-epoch` record containing provider/model/adapter settings and the
turn range.  Causality is `InferenceEpoch → turn → eval → semantic receipts`:
evaluations associate with an epoch through turn/run provenance, so the M1
evaluator schema contains no epoch field and never anticipates provider
provenance — the evaluator does not care who generated its Clojure.
Provider/model identity must not be placed in ContextSpec, evaluator
RuntimeCoordinate, semantic receipt authority identity, or replay authority:
replay has zero provider dependency.  The underway local-to-hosted Qwen
transition is precisely the kind of controller-recorded epoch transition this
must describe, including transition reason and relevant adapter/config
identity.

### Two security modes and trusted verification

Convergence supports two explicit modes rather than silently changing
RFC-003's existing threat model:

| Mode | Evaluation | World authority |
|---|---|---|
| Ordinary Samizdat | current trusted live in-process eval | current RFC-003 model |
| Bounded JS1 | persistent SCI under a ContextSpec | no ambient files, environment/secrets, network, or host execution; only semantic operations |

`done` is a model ControlEvent, not a special shell grant.  Verification is a
controller-owned effect: the controller chooses executable, argv, cwd,
scrubbed environment, timeout, process scope, and output bounds.  Reuse current
security/redaction/process policy and the bounded Jolt scope primitive where
they preserve this rule.  The bounded model still has no generic shell.

Do not introduce a process-global evaluator registry merely because the
contract names spec/instance/binding.  First use current run/context lifecycle
ownership.  Persist specs, binding/instance identities, coordinates, and
committed history; add a registry only if reacquisition/concurrency evidence
requires it, since current upstream intentionally removed obsolete global
session registries.

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

At binding creation, render one trusted bounded orientation from:

1. the binding's effective ContextSpec capability catalog;
2. base descriptions for exactly `eval/doc/complete/done`; and
3. trusted concise guidance for persistent SCI composition, `doc`/`complete`,
   and project operations inside `eval`.

Place that orientation in the initial tape and pin it byte-for-byte for the
binding lifetime so current prefix-cache/tape behavior is stable.  The base
renderer emits only capability descriptions actually present in the binding;
`doc` and `complete` read the same catalog.  If authority changes materially,
mint a new binding and ContextSpec coordinate.

Compose the initial tape from two structurally distinct sections:

- **SYSTEM / TRUSTED SURFACE:** generated, pinned, and byte-stable; lists
  exactly the gated top-level tools and exactly the effective ContextSpec
  semantic operations; explicitly marked authoritative.
- **PROJECT GUIDANCE:** project userspace conventions, task, and domain advice;
  explicitly non-authoritative.

Mechanically test generated tool definitions against the gated vocabulary and
generated semantic-operation definitions against the effective ContextSpec —
nothing else.  Free-form guidance may say “unlike ordinary Samizdat, shell is
unavailable here”; that is prose, not a tool definition.  Only the trusted
section defines callable authority.  This preserves project-local guidance
while preventing canary attempt 1's generic-prompt failure.

The orientation should teach, briefly, why composition matters: discover with
`doc`/`complete`; gather multiple read-only observations inside one `eval`;
branch/filter/reduce locally; define and reuse small helpers; return a compact
conclusion rather than paying a model round trip after every observation.  It
must not become a long tutorial.

### REPL leverage and progress telemetry

REPL leverage is the ability to move cheap control flow below the model
boundary without moving it above the authority boundary:

`MODEL → persistent SCI → authorized semantic operations → WORLD`.

It has two axes.  The composition axis ranges from **L0 tool-shaped REPL**
(one operation then return) through **L1 computational REPL** (local parsing,
filtering, or transformation) to **L2 agentic REPL** (multiple observations,
data-dependent branching, aggregation/micro-workflow, then at most deliberate
actuation).  The persistence axis ranges independently from ephemeral
composition to defined-and-reused helpers.  Neither an arbitrary L2 count nor
helper reuse is an authority invariant or PASS criterion; both are useful
product evidence.

Telemetry splits into exact and heuristic classes.  **Exact** facts derive
directly from receipts/turns with no new tracing: semantic operations per eval
and model turn; multi-operation eval count/rate; operation kinds/order;
distinct versus repeated observation arguments; mutations per eval;
observations between mutations; verification attempts; provider latency/retries;
and eval success/failure.  **Heuristic** facts remain explicitly heuristic
unless later instrumentation warrants them: whether operation A's result caused
operation B, local collection-processing intensity, helper identity/reuse, and
objective-progress reduction.  M1's deterministic fixture proves
data-dependent composition exactly because its program is known; arbitrary
model traces cannot establish it from receipts alone.  Do not add complex
evaluator instrumentation merely to compute leverage metrics in M1.

Expose these facts read-only to current `session`, `watch`, steering, and
adaptation.  Distinguish **computational activity** (an eval completed),
**world evidence** (a previously unseen observation coordinate/result, mutation,
test/verification result, or other new durable evidence), and **objective
progress** (the residual objective demonstrably narrowed).  Re-reading the
same file 300 times is activity without new world evidence; current adaptation
policy, not the evaluator, judges whether new evidence is progress.  No signal
may widen authority or budget automatically.

Observation and actuation remain asymmetric.  Encourage rich composition of
read/list/search/stat and pure computation.  Prefer observe + compute +
decide followed by one/few anchored edits.  Each edit remains individually
authorized, intent-recorded, outcome-recorded, and replayable; rollback of SCI
state cannot undo an already completed external mutation.  Semantic operations
therefore return canonical structured data (coordinates, paths, fields, facts),
not model-oriented prose; SCI performs interpretation and aggregation.

## Adaptation and steering signals

Expose read-only evaluator telemetry to current `session`, `watch`, and
adaptation cells: repeated evaluation failures, completed evaluations without
new artifact/mutation/test evidence, repeated project reads/searches without
an edit or verification attempt, unresolved intent, repeated RED outcomes,
and provider degradation/retry latency.  Steering may select an existing
userspace directive, task, workflow, or provider epoch at a boundary.  It may
not mint capabilities, increase a profile, bypass a lease, or widen budget.

The ongoing canary confirms the importance of these signals: authority-derived
prompting changed behavior from zero operations to real read/list/stat/edit
work, but a long sequence of successful `eval` calls can still make no forward
progress.  That is a current steering/adaptation input, not a reason to weaken
the evaluator boundary.

## Convergence milestones

Each milestone is a stop-and-review point, not a promise to immediately start
the next one.

1. **M1 — read-only bounded evaluator vertical slice.**  On one frozen current
   upstream base, deliver a complete executable path: Spec → Instance → Binding
   → minimal durable receipt store → pinned Jolt/SCI adapter → trusted
   `:agent/project-read` ContextSpec
   (`read/list/search/stat`) → `eval/doc/complete/done` → stable initial-tape
   orientation → persistent helpers → multi-observation computational eval →
   commit-only failure behavior → fresh-process reconstruction with zero real
   world operations.  Keep ordinary non-SCI Samizdat unchanged.  **Stop and
   review only after this runs.**  In M1, `done` is recognized as a ControlEvent
   and the controller returns `:verification-unavailable` / `:completion-refused`;
   the run does **not** become successfully terminal.  From M2, controller-owned
   verification runs and only GREEN permits successful termination.  `done` is
   in the four-tool vocabulary from day one and is never a shell grant.
2. **M2 — develop, complete receipts, and verification.**  Add
   `project/edit`, anchored optimistic mutation, full intent/outcome/exhaustion
   refusal, root/path/symlink/bound checks, and controller-owned `done`
   verification with scoped process cleanup.  **Stop and review.**
3. **M3 — current scheduler/resume/controller integration.**  Re-derive
   TurnLease in current `beam/advance-all`; preserve current tape/task/branch
   problem/userspace resume while reconstructing the evaluator; add inference
   epochs, non-quiescence failure, retained audited budget extension, and
   read-only leverage/progress telemetry into session/watch/adaptation.
   **Stop and review.**
4. **M4 — current-upstream self-hosting canary and freeze.**  Run a disposable
   single-player task through current userspace/manifests/tasks/memory/
   adaptation with bounded orientation, interruption/reconstruction, trusted
   GREEN verification, leverage metrics, and reference-oracle comparison.
   Freeze the converged baseline.  Stop before JS2.

## Exact first executable convergence gate

M1's gate is a deterministic evaluator-level conformance test, not a
model-behavior test.  With the final read profile already present, mint a
read-only binding for a temporary root and execute **one** recorded evaluation
conceptually equivalent to:

```clojure
(let [entries (project/list "src")
      relevant? (some #(= "samizdat" (:name %)) entries)]
  (if relevant?
    (->> (project/search "defn" {:path "src/samizdat"})
         (map :path)
         distinct
         (take 5)
         vec)
    []))
```

The exact expression follows final structured operation APIs.  It must prove,
in one evaluator evaluation: multiple semantic observations; later branching
dependent on an earlier observation; local transform/aggregation; a compact
canonical result; and ordered durable receipts.  In the same slice, define a
small persistent helper, use it in a later recorded eval, then construct a
fresh evaluator and replay all committed evaluations.  Replay must consume the
same receipt sequence, reproduce result/state and helper availability, and
perform **zero** real world operations.

The same M1 test set explicitly executes a failed recorded evaluation followed
by rollback to committed state, and independent pending, receipt-mismatch,
receipt-exhaustion, and unconsumed-receipt cases.  Each must refuse before an
unrecorded world operation or replay interpretation occurs.

Expected evidence:

- `EvaluatorSpec`, `EvaluatorInstance`, `EvaluatorBinding`, ContextSpec, and
  RuntimeCoordinate identities recorded and exact;
- only `project/read|list|search|stat` advertised by prompt, `doc`, and
  `complete`, with no generic host tools;
- `begin → intent → outcome → complete` receipt rows in causal order;
- multi-observation / data-dependent branch / local aggregation leverage facts;
- failed evaluation rollback to committed state;
- fresh-process reconstruction, stable binding identity, zero replay-world
  operations, and exact mismatch/pending/exhaustion refusal cases;
- ordinary current upstream no-SCI suite remains green, and the pinned Jolt/SCI
  conformance lane is green.

Do not add edit, scheduler leases, canary work, provider switching, or any M2+
feature until this exact two-lane vertical gate is green and reviewed.

## Deliberately deferred

- JS2, `project/run`, execution environments, SmolVM workers, or generic
  shell;
- multi-agent / shared evaluator bindings;
- automatic authority/budget widening;
- process-global evaluator registry unless lifecycle evidence requires it;
- replacing upstream prompt/userspace, tape, manifest, task, or scheduler
  architecture;
- rebasing the old JS1 branch or moving the Jolt reference coordinate;
- treating the incomplete historical/current canary as convergence evidence.

## Base-motion discipline

`5aa9476` is the planning coordinate, not a command to implement on stale
upstream.  At implementation start: fetch `yogthos/samizdat` main, record its
actual SHA, create one convergence branch from that SHA, and freeze that
milestone base.  Do not continuously rebase merely because upstream moves;
decide deliberately whether to refresh only at a milestone boundary.

## Recommendation

**At implementation start, create a fresh branch from the then-current recorded
`yogthos/samizdat` main SHA (planning inspected `5aa94769160a92ffb5131adf776fdc06f6157405`).
First implement M1's complete read-only bounded evaluator vertical slice using
the trusted `:agent/project-read` profile, not inert substrate or a temporary
one-operation authority.  Preserve the fourteen contract invariants above and
use current upstream userspace, manifests, tape/inference/provider, scheduler,
tasks, resume, security policy, and adaptation machinery unchanged.  Re-derive
only evaluator lifecycle, receipt/replay, stable authority projection, and
their current seams.  Run the exact M1 gate above, record leverage facts, and
STOP for review before M2.**
