# JS1 M1 Implementation Prompt

## Mission

Implement **only M1** from `JS1_CONVERGENCE_PLAN.md`: a complete, executable,
read-only bounded evaluator vertical slice on current upstream Samizdat.

The governing rule is:

> PORT THE JS1 CONTRACT, NOT THE OLD JS1 GIT HISTORY.

The old `casselc/samizdat` `js1-bounded-samizdat` branch and
`casselc/jolt` `js1-runtime-current-upstream` are reference implementations,
conformance oracles, and evidence corpora.  Do not rebase, merge, or
cherry-pick their history as the integration strategy.

## Start state

1. Fetch `yogthos/samizdat` `main` and record its actual SHA.
2. Create one fresh convergence branch from that SHA and freeze it for M1.
3. Inspect current RFCs, especially RFC-001, RFC-003, RFC-004, RFC-005,
   RFC-006, RFC-009, and RFC-010.
4. Inspect the JS1 reference only to recover contract behavior and oracle
   tests.  Do not copy its old beam/workflow/resume architecture.

Do not continuously rebase this milestone if upstream moves.

## M1 outcome

Deliver this actual path on current Samizdat:

```text
EvaluatorSpec
  -> EvaluatorInstance
  -> EvaluatorBinding
  -> minimal append-only evaluator/receipt store
  -> pinned Jolt/SCI adapter
  -> trusted :agent/project-read ContextSpec
  -> eval / doc / complete / done
  -> project/read,list,search,stat inside persistent SCI
  -> durable committed history
  -> fresh-process reconstruction
  -> zero real world operations during replay
```

Use the final read profile now, not a temporary one-operation profile:

```clojure
:agent/project-read
#{:project/read :project/list :project/search :project/stat}
```

M1 is complete only when this works end to end.  Do not stop after types,
tables, or adapter plumbing that a later milestone would need to make real.

## Hard contract rules

### Authority

Userspace may request a profile but is never an authority source:

```text
userspace request
  ∩ controller authorization
  ∩ trusted profile/catalog maximum
  ∩ compiled runtime capability
  = effective ContextSpec
```

Trusted profile semantics, capability IDs, maxima, ceilings, and controller
policy live below self-editable userspace.  Cells/manifests may receive a
binding or request a profile; they cannot construct, widen, or define one.

### Two modes

Do not globally replace current RFC-003 ordinary eval:

| Mode | Evaluation | World access |
|---|---|---|
| Ordinary Samizdat | current trusted in-process eval | current RFC-003 model |
| Bounded JS1 | persistent SCI under ContextSpec | only projected semantic operations; no ambient files, env/secrets, network, or host execution |

Ordinary no-SCI Samizdat must remain green and must not load SCI accidentally.

### Binding and prompt

At binding creation, generate a byte-stable **SYSTEM / TRUSTED SURFACE** and
place it in the initial tape.  It must list exactly:

```text
eval
doc
complete
done
```

and exactly the semantic operations in the effective ContextSpec.  Generate it
from the same trusted catalog used by dispatch, `doc`, and `complete`.

Compose a separate, non-authoritative **PROJECT GUIDANCE** section from
userspace for task/domain/style guidance.  It cannot define callable authority.
Tests compare trusted advertised tool definitions to the gated vocabulary and
trusted advertised semantic-operation definitions to the effective ContextSpec;
they do not ban ordinary prose that mentions unavailable tools as unavailable.

Teach briefly that SCI is persistent and programmable: use `doc`/`complete` to
discover, combine several read-only observations in one `eval`, branch/filter/
aggregate locally, and define/reuse helpers.  Keep orientation concise.

### Durable evaluator history

Keep evaluator history separate from current tape:

```text
tape              = model-visible conversation/inference state
evaluator history = SCI computation plus semantic-operation receipts
```

Persist append-only `begin -> intent* -> outcome* -> completed|failed` records.
Pending means no completion row; an unsettled intent blocks completion and
reconstruction.  Receipt payloads are canonical structured data.  Failed or
interrupted recorded evals rebuild to committed history; rollback failure poisons
the instance and fails closed.

A fresh evaluator validates spec/binding/authority/runtime coordinates and
replays all committed history in one SCI context.  Replay consumes exactly the
receipt sequence, reproduces committed result/state, and performs zero real
semantic operations.  Provider access is never needed for replay.

### M1 `done`

`done` is in the model vocabulary from day one, but M1 recognizes it only as a
ControlEvent.  The controller returns `:verification-unavailable` or
`:completion-refused`; the run cannot become successfully terminal.  M2 adds
controller-owned verification, where only GREEN permits successful termination.
`done` is never a shell grant.

## Use current upstream architecture

Retain current userspace, manifests, tape/inference/provider, scheduler,
tasks, storage/resume, memory, and adaptation design.  Candidate seams to
investigate include current `samizdat.agent.infer`, `samizdat.tape`,
`samizdat.prompt`, `samizdat.manifests`, `samizdat.workflow`,
`samizdat.store.journal`, `samizdat.store.db`, and `samizdat.agent.resume`.

Do not wire TurnLease, scheduler deadlines, budget extension, `project/edit`,
or trusted subprocess verification in M1.  Those are later milestones.
Do not introduce a global evaluator registry unless actual lifecycle evidence
requires it; prefer current run/context ownership.

Provider/model provenance is also deferred to M3.  M1 evaluator tables must
not contain an inference-epoch field.  Later causal provenance is:

```text
InferenceEpoch -> turn -> eval -> semantic receipts
```

Provider/model identity is not ContextSpec, RuntimeCoordinate, receipt
authority, or replay authority.

## Exact M1 conformance gate

Write deterministic tests that mint a read-only binding for a temporary root
and execute one recorded evaluation conceptually equivalent to:

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

The final expression must use the actual M1 operation APIs.  Prove in one eval:

- multiple semantic observations;
- later branch/control flow depends on an earlier observation;
- local transformation/aggregation yields one compact canonical result;
- receipt intents/outcomes are ordered and durable.

Also prove:

1. a helper defined in one committed eval is reused in a later committed eval;
2. failed recorded eval rolls SCI state back to committed history;
3. pending, mismatch, receipt-exhaustion, and unconsumed-receipt cases refuse
   before unrecorded world work or replay interpretation;
4. fresh-process reconstruction preserves helper/result state, consumes the
   recorded receipts, and makes zero real world calls;
5. prompt/`doc`/`complete` advertise exactly `eval/doc/complete/done` and the
   effective read-profile operations, never callable authority outside them;
6. ordinary current-upstream no-SCI tests remain green, while the pinned
   Jolt/SCI conformance lane is green.

Record exact ContextSpec, RuntimeCoordinate, spec, instance, and binding
identities in the test evidence.  Derive simple exact leverage facts from
receipts (operations per eval, multi-operation eval, operation order).  Do not
add deep AST/dynamic dependency tracing merely to measure M1.

## Explicit exclusions

Do not implement any of the following in M1:

- `project/edit` or any mutation;
- generic shell, `project/run`, network, or package installation for the model;
- `done` verification/subprocess execution;
- TurnLease/scheduler-controller integration;
- budget extension authority;
- inference epochs/provider switching;
- multi-agent/shared evaluator bindings;
- JS2, SmolVM, or a Jolt rebase.

## Completion rule

After the exact M1 gate passes, document the evidence and **STOP FOR REVIEW**.
Do not begin M2 in the same pass.
