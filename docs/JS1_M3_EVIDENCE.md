# JS1 M3 Evidence

## Coordinates and load boundary

- Samizdat branch: `js1-m3-current-effcf7d`
- M3 entry port: `2ef127c` (`feat: forward-port M2 bounded execution`)
- Upstream at the base: `effcf7d`
- Frozen M2 closure: `a7e857f`
- Jolt M1 runtime base: `4af2362176160f2ed0e366689d7232b1a38adfec`
- M2/M3 bounded Jolt checkout: `f8899905d98a0abdcc6b4ae61dfd5c8bdb9c7277`
- SCI: `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`
- New schema: migrations `v21` through `v25`, after the M2 evaluator at `v20`

This change is in Samizdat mechanism (`src/`), runtime-loaded policy/behavior
(`resources/`), and tests. It does not edit Jolt core, its reader/host
bootstrap, a core overlay, or `stdlib`; no seed image or remint/fixpoint gate is
therefore implicated. Prompt text and the cancellation grace remain
runtime-loaded resources rather than compiled behavior constants.

## Gates run on this machine

Using `/usr/local/bin/scheme` and the pinned Jolt executable:

```text
ordinary suite (jolt -M:test):
  1569 tests, 6228 assertions, 0 failures, 0 errors

bounded evaluator lane (SAMIZDAT_BOUNDED_TEST=1, pinned local SCI):
  25 tests, 294 assertions, 0 failures, 0 errors

focused M3 authority, provenance, and TurnLease lane:
  47 tests, 217 assertions, 0 failures, 0 errors
```

The pre-freeze working-tree record above is superseded by the immutable final
closure below. The runs used the pinned
`../jolt-m2-verify-closure` checkout because sibling `../jolt` cannot resolve
`jolt.publish`; `bin/js1-m3` pins and checks that usable checkout, SCI, Chez,
and a clean Samizdat tree before it emits exact evidence.

## Durable evaluator recovery

- Migration `v21` records one complete `EvaluatorBinding` per bounded run
  before branch creation and before its first provider turn: binding/work/
  instance/spec ids, full inert `ContextSpec`, runtime coordinate, and root.
- Resume does not consult current bounded defaults. It validates the persisted
  context's self-coordinate, exact canonical root, current runtime coordinate,
  and deterministic identities before allocating SCI.
- Durable history is validated before allocation. Reconstruction then creates
  one fresh context and replays committed evaluations from receipts; replay
  mode performs no real project operation. Pending or inconsistent history
  fails closed.
- The bounded recovery test persists a definition, reconstructs from only the
  run record and durable history, and observes that definition in the fresh
  context.
- Migration `v25` persists exact trusted-orientation bytes and a SHA-256
  digest. A zero-history recovery test changes the prompt renderer and verifies
  reconstruction installs persisted bytes, never the drifted prompt.

This is a closed-world provenance check: accepting a local source hash while
silently changing context defaults, authority, runtime, or history is not a
valid recovery.

## TurnLease concurrency contract

### Ownership and states

The scheduler is the sole TurnLease minter and revoker. Each lease names one
`run-id` / `branch-id` / scheduled turn and owns one interrupt token. Its state
is `:active` or terminal `:revoked`; the first revoker owns the reason and no
transition returns a lease to active. Bounded workflows are scheduled at beam
width one, while ordinary beam branches may still advance concurrently.

### Linearization points

- Lease revocation and short effect permits synchronize on the same state
  monitor. Whichever acquires it first defines the order.
- For bounded evaluator operations, the durable intent append is the semantic
  operation's initiation point. If revocation wins first, there is no intent
  and no edit. If intent wins first, the already-authorized operation may
  finish outside the monitor and records its outcome.
- For bounded `done`, the permit is the controller verification launch point.
  A stale turn launches no verifier; an already-launched controller-owned
  verification is followed through to its green/red result.
- At a deadline the scheduler revokes first, then fires the lease interrupt
  token and cancels the worker future. A delayed return is never accepted.

### Shutdown and drain

After interruption the scheduler waits the runtime-configured
`:turn-cancel-grace-ms`. Confirmed quiescence permits a fresh lease on the next
turn. Failure to quiesce writes retained
`runs.terminal_reason = 'turn-cancellation-fault'`, journals the fault, fails
the run, accepts no delayed answer, and refuses both resume and budget
extension. The event tail is explanatory; the retained run column is the fact
that survives pruning.

There is no starvation or round-robin fairness claim. The scheduler preserves
its existing per-round barrier and branch parallelism; deadlines bound a
scheduled turn, not fair provider or OS service. The guarantee is serial
authority for one branch and no next authority before quiescence, not eventual
progress.

## Budget authority

- Migration `v23` retains one globally idempotent audit row per request id.
- Only an opaque process-local authority minted from trusted controller config
  can extend a budget. Request-shaped maps, the raw token string, unminted
  records, queued `extend` directives, and ordinary resume carry no authority.
- One transaction inserts the audit, compare-and-sets the observed cap, reopens
  only exhausted branches, and journals the act. Equal/lower caps, configured
  ceiling violations, request-id conflicts, completed/aborted runs, and
  cancellation-faulted runs are refused.
- Resume may name an at-or-below cap for compatibility but never lowers or
  raises the durable row. It runs under the recorded cap.

## InferenceEpoch and telemetry

- Migration `v22` records provider/model, exact scheduled turn, and bounded
  binding/spec/runtime coordinates immediately before a branch provider call.
  The eventual turn row references that epoch, including provider-error and
  no-call settlements.
- Migration `v25` records adapter and nonsecret realization digest. Calls with
  the same realization reuse an open epoch; a provider switch closes it and
  creates its successor. Epoch streams are insertion ordered, not UUID ordered,
  so same-millisecond switches retain causal order.
- Eval rows and intent/outcome receipts carry the dispatch epoch id. The focused
  gate exercises provider epoch -> tool dispatch -> eval -> exact receipt.
- Evaluator evidence exposed by the run API is a projection of durable binding,
  evaluation, receipt, completion, and epoch rows. The evidence path imports no
  evaluator namespace, allocates no SCI context, replays no source, and invokes
  no project operation.
- Bounded requests are refused before run creation or resume when the selected
  manifest is noniterating, because whole-run manifests bypass TurnLease. The
  focused gate also covers controller-authorized human `extend`, pre-revocation
  effects, and launched `done` verification followthrough.

## Nonclaims

M3 does not add shared mutable SCI across beam branches, generic shell
authority, simulation scheduling/fault/replay policy, a new verification
environment, provider fairness, forceful thread termination, Jolt runtime or
ABI changes, JS2, or an upstream rebase. Simulation policy remains outside the
core; these changes expose only authority, interruption, durable event, and
read-model seams.

## Final M3 Closure

- M3 code under test: `be54c2e45ebab966ff07cfc3405b804f53128bde`
- M3 evidence commit: recorded by the commit containing this section.
- M3 upstream base: `effcf7dbf439bd3baa2718bc3e780f2031ecae59`
- Frozen M2 closure: `a7e857f`; bounded Jolt checkout: `f8899905d98a0abdcc6b4ae61dfd5c8bdb9c7277`; Jolt base: `4af2362176160f2ed0e366689d7232b1a38adfec`; SCI: `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`.

`bin/js1-m3 test` ran from a tracked-clean checkout at the code-under-test
commit: focused authority/provenance/lease **47 tests / 217 assertions**,
bounded evaluator **25 / 294**, and ordinary suite **1569 / 6228**, all with
zero failures/errors. It pins the bounded Jolt checkout and SCI before running.

M3 PASS: exact orientation recovery, zero-world replay, causal
epoch→turn→eval→receipt records, TurnLease stale-effect refusal and trusted
done follow-through, audited idempotent human/controller budget extension,
bounded-workflow refusal outside the lease-compatible mode, and read-only REPL
evidence projection are green. STOP FOR REVIEW.
