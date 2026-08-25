# JS1 Cutover — REVISE

## Decision

**REVISE.** JS1 is not approved for self-hosting or merge approval yet. The
bounded evaluator architecture remains the intended design; the remaining
gates are evidence gaps, not a decision to replace it.

## Baseline

- Samizdat branch: `js1-bounded-samizdat` at
  `8995e113`, whose recovery base `321661649e174bb748adeb6970dad6c166003343`
  was rebased on current upstream `dae78547a66c80f31fa7a78d0f9483186a2b0af9`.
- Current Jolt evaluator runtime:
  `279bca18bbf50f37b8574a4e6998dee40313cd26` on
  `js1-runtime-current-upstream`, rebased on upstream `edda7aec`.
- Historical evidence remains distinct: JS0 freeze
  `js0-functional-sci-upstream-freeze` at `04dd42db`, post-JS0 runtime
  `619ef196`, and pre-current-upstream checkpoint
  `js1-runtime-pre-upstream-sci-merge`.
- SCI: `32d62a5136ad3dc148588752f5bcc4cc30b14752` (`0.13.53`).

## Executed Gates

| Gate | Result |
|---|---|
| Full Samizdat Jolt suite | `1047` tests / `3658` assertions, zero failures/errors |
| Direct bounded SCI sandbox | `28` tests / `268` assertions, zero failures/errors |
| Durable evaluator store | `18` tests / `78` assertions, zero failures/errors |
| Controlled OS-process recovery harness | `1` test / `30` assertions, zero failures/errors |
| Static local SmolVM harness | `12` tests / `156` assertions, zero failures/errors |
| Current Jolt gates | manifest / selfhost / scievaluator / testbin all pass |
| Trusted verifier hardening | injection / env scrub / redaction / scoped cleanup pass |

## Now Evidenced

- Runtime, language-surface, capability-catalog, and receipt-protocol
  coordinates are durable and checked before replay.
- Receipts remain strict canonical data while arbitrary valid SCI final values
  are rendered safely rather than incorrectly failing evaluation.
- Only successful evaluations become persistent computational state; failed or
  interrupted evaluations rebuild from committed history before reuse.
- Whole committed history reconstructs into one fresh SCI context with exact
  receipt consumption and zero repeated semantic world operations.
- A controlled real OS-process boundary proves helper reconstruction, no replay
  of an observation/edit, and fail-closed runtime/spec/pending-history cases.
- Trusted verification uses a structured scoped request with explicit scrubbed
  environment and bounded streams; output is redacted before model rendering,
  and unavailable or malformed verification fails closed.
- JS1 with multi-branch execution is refused; the current policy is one
  logical persistent `:main` evaluator.
- A live bounded-model run completed RED → deliberate SIGKILL → fresh-process
  resume → persistent-helper proof → repair → GREEN. See
  [`JS1_LIVE_DOGFOOD.md`](JS1_LIVE_DOGFOOD.md).
- The frozen bbagent A3c comparison is recorded in
  [`JS1_A3C_COMPARISON.md`](JS1_A3C_COMPARISON.md). JS1 retains the claimed
  bounded-programming and no-repeat replay properties, not A3c guest isolation.

## Remaining PASS Gates

- **Controller-budget authority and audit are not safe enough for PASS.** The
  resume API currently accepts a budget extension without an authenticated
  trusted principal; the cap update and event are not one durable atomic audit
  record; a branch timeout stops waiting but does not prove cancellation of the
  underlying turn. These are implementation blockers, not merely missing tests.

## Standing Non-Claims

- No self-hosting canary has run; **SELF-HOSTING CANARY: PASS** is not claimed.
- Jolt's own `selfhost` gate is not the JS1 self-hosting canary.
- No `project/run`, generic shell, network, Git mutation, multi-agent binding,
  JS2, or controller self-modification was added.
- SCI is a Jolt runtime dependency; plain-JVM and non-Linux/platform lanes are
  unexecuted.
- No performance or latency claim is made.
- A3c-style guest execution isolation, `project/run`, and clean-consumer
  SmolVM execution remain unclaimed; the guest pack is `:unbuilt`.
- Conversational turns remain separate from authoritative evaluator history;
  `:js1-binding-created` journal records provide resume reconstruction identity,
  but individual turns do not carry evaluator metadata.
