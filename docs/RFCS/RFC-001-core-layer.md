# RFC-001 — The core layer

## The rule

samizdat exists to be modifiable by its own agent at runtime. That single
requirement decides where every line of code goes.

**The base is `src/`.** It is compiled into the binary and provides
capabilities with no opinions: how to talk to a provider, how to run a tool on
the machine, how to reach the database, how to render a template, how to
compile and validate a workflow. Nothing in the base decides what the harness
*does*.

**Userspace is how those capabilities are assembled into an agentic loop** —
cells, the manifests that wire them, the policy tables they read, the prompts
they speak. It belongs to the *project*, not to the harness. See RFC-002.

The test to apply to any change: *could the agent change this about itself, at
runtime, without a rebuild?* If the answer is no and the thing is a behaviour
rather than a capability, it is in the wrong place.

## Why the base has to be small

A capability in the base is available to every project forever. A decision in
the base is a decision no project can revise. The asymmetry is the whole
argument: getting a capability slightly wrong costs a rebuild, while getting a
*decision* wrong costs every project that ever runs the binary, and the agent
whose job is to improve the loop cannot reach it.

That is why the base/userspace line is drawn at "does it decide anything"
rather than at "is it complicated" or "is it likely to change."

## The seam

`samizdat.userspace` is the read seam. Every loader in the base goes through
it rather than reaching for `io/resource`:

```
userspace/body :cell     "loop"       -> Clojure source
userspace/body :manifest "beam"       -> EDN
userspace/body :policy   "gates"      -> EDN
userspace/body :prompt   "system"     -> markdown
```

It returns the *project's* current version, seeding the shipped template as
version 1 on first read. `system/start!` binds it to the project's database
connection.

**Unbound is a valid state and serves the template.** A test, a bare REPL, or a
tool with no run behind it gets exactly the behaviour the harness had before
the store existed. That is what let the store be added without a flag day:
1104 existing tests did not notice it appear underneath them.

Reads are cached and invalidated wholesale on any write. Coarse on purpose — a
prompt renders on every gate message and a threshold is read inside compiled
predicates, so a query per read would put SQLite in the path of string
interpolation, and a stale cell is the bug that looks like the supervisor's
edit silently not taking.

## What the base contains

| area | namespaces | what makes it a capability |
|---|---|---|
| provider | `llm.client`, `llm.adapter*`, `llm.message`, `llm.fence` | one retry ladder, one wall-clock bound, one message shape, one fence parser — a retry ladder that differs by provider is one nobody can reason about |
| inference | `agent.infer`, `tape` | the step is `tape -> tape'` with the model call injected; the four drivers (commit, bounce, trampoline, fan) are different ways of applying it |
| the machine | `agent.tools.*`, `engine.proc`, `security.policy`, `lsp.client` | doing a thing on request; the decision to request it is upstream |
| durability | `store.*` | rows and SQL, nothing else |
| scheduling | `agent.beam` | advancing branches, fanning out under a deadline, disposing sessions, closing rows |
| compilation | `workflow`, `cells`, `mutation` | loading, validating and hot-swapping userspace; the safety around an edit, not the edit |
| the record | `store.journal`, `events` | append-only, and a live tap that cannot stall the loop |

## The turn, and the one definition of it

`agent.loop` holds the *steps* a turn is made of — assemble, call, absorb,
dispatch, journal, arbitrate, steer — as public functions with nothing
composing them. What a turn **is** lives in the loop manifest, whose cells call
those steps.

There used to be two definitions. `agent.loop/run-turn` composed the steps in
compiled Clojure while the manifest composed the same steps as cells, so an
edit to the manifest reached only one of them. `workflow/run-turn` is the single
composition now, and it is defined *by* compiling the manifest's per-turn slice,
so it cannot drift from what production runs.

The order inside a turn is load-bearing — the tool runs before the arbiter, so a
gate sees the state the turn produced rather than the state it started from; and
predictions settle before new gates fire, so a gate cannot be credited with an
outcome that preceded it. Those are the manifest's constraints to keep. What the
base guarantees is that each step does one thing and declares what it touched.

## Context is a budget, and it is policy

Ten numbers decide how much the model gets to see — one tool result, one file
read, the tail of a failing test run, the judge's view of the rules and the
transcript, search ranges, inbox lines, verbatim exchanges before compaction,
the threshold compaction engages at — plus the verification timeout. All are
`gates.edn :context-budget` and `:verify-timeout-ms`.

They are the most project-specific values in the harness. A suite that takes
twelve minutes and one that takes four seconds are both normal; a codebase of
200-line namespaces read by a large-context model wants nothing like the same
values as one with generated files read by a small local model. And they are the
values most likely to be the real cause of a run going wrong while being hardest
to diagnose: **a branch that cannot see the line its test failed on looks
exactly like a branch that cannot read a stack trace.**

They are `:cost-ceiling`, so the capability tier may not raise them. Every one
trades context for evidence, and a struggling run is the last one that should be
handed more context on the harness's own initiative.

## The tape, and why nothing early may move

A model call is a pure function of the message array, so the array is a
reduction accumulator: appending is a turn, copying is a fork, applying and
discarding is a probe.

One invariant holds the whole thing up: **nothing early in the message array may
change between turns.** Prefix caching depends on it. Three designs have run
into it so far:

- Compaction used to append its digest to the *problem* message, rewriting index
  1 every time it fired and invalidating the entire conversation behind it. It
  now rewrites each aged-out message in place, once, so roles, order and count
  are unchanged and the prefix before the newest rewrite is byte-stable.
- The current task is appended once on claim and never rewritten, and marked
  `:pinned?` so compaction leaves it alone. A block held at a fixed early
  position and rewritten on task change would invalidate everything behind it.
- The per-turn context block is *appended*, which is where the cache boundary
  already is, so it costs nothing.

## Findings

Writing this exposed the following. `src-audit-2026-08-4.md` asked the same
question in a previous pass; its open items are resolved or carried here.

### F1 — Two places document a live subsystem as dead

`agent/loop.clj` says, as the justification for how the safe-state trigger is
keyed:

> No tool on the current surface emits `:claim-status` artifacts (the proof
> engines that did are gone), so the old `:confirmed` trigger keyed on a status
> that never occurred.

`phases.edn` says the same thing about its `:transitions` table. **Both are
false.** `agent/tools/ship.clj` emits `:claim-status :confirmed` on a green
ship-verify. Verified consequences:

- Cross-branch artifact sharing is **live** — `loop/shareable?` returns true for
  a ship artifact under the default `gates.edn :share`. Two comments say the
  subsystem it feeds is dead.
- The winner rubric's `confirmed-count` and `engine-diversity` components have
  data to rank on again, having been constant-zero for the proof-era gap.

Nothing crashes. The defect is that the next person to read either comment will
believe a running subsystem is vestigial, and either build a workaround or
delete it. **Severity: low impact, high misleading potential.** Fix: correct
both comments and state what does emit the status.

### F2 — A dead-key audit over `src/` alone is wrong by 18 keys

`R2-13` found five `gates.edn` keys with no reader. Re-running that check today
reports **eighteen** — and all eighteen are false positives. A threshold's
reader is frequently *data in the same file*: a gate entry names its budget key
in `:budget`, and a `:when` form references a threshold by keyword. Scanning
`src/` alone cannot see either.

Including `gates.edn` and every resource in the scan, and accepting
`{:keys [...]}` destructuring as a read, gives **zero** dead keys.

This is recorded because the next person to audit will write the same naive scan
and get the same eighteen. The honest check needs three things: search resources
too, treat gates.edn's own `:budget`/`:when` as readers, and accept destructured
reads. `agent-test/every-context-budget-key-is-actually-read` does this for one
map and is the pattern to copy.

### F3 — Documentation drift is the failure mode this layer has

F1 and F2 are the same shape: the code was correct and the description was not.
That is what this layer is prone to, because a comment justifying a design
decision is written once and the world moves. The mitigation that works is a
test that asserts the claim — as the context-budget key test does — rather than
a resolution to keep comments fresh.
