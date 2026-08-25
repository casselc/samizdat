# RFC-002 — Manifest workflows and cells

## The layer

A **cell** is one step: a function of `(ctx, data)` returning `data`, with its
effects declared. A **manifest** is a state machine over cells — nodes, edges,
dispatch predicates, and constraints. Together they are the agentic loop, and
they are userspace: this project's copy, editable at runtime.

The base supplies the pieces; the manifest says how they snap together. That
division is what makes the loop something a project can evolve rather than
something it inherits.

## Per-project by construction

Both live in the `userspace` table, keyed `(kind, name, version)`, append-only.
A project seeds from the shipped template on first read and every edit is a new
version of *its* copy. The shipped files under `resources/` are never written.

This is the part that makes the layer real rather than nominal. Before it, a
supervisor "editing its cells" was editing `resources/cells/*.clj` — the file
every project loads — so improving one project's loop changed the harness for
all of them. A layer every project shares is not userspace whatever directory
it lives in.

Rollback is a version pointer, and `revert!` re-appends an older body as a new
version rather than deleting: the failed edit stays readable, because the edit
history of a system that rewrites itself is the most valuable thing in its
database.

## The two levels

The loop nests, and both levels are manifests:

```
manifests/beam.edn        the ROUND — advance every branch, score, cull,
                          settle, repopulate, spawn, tick, back edge
  └── manifests/loop.edn  the TURN — assemble, infer, parse, dispatch,
      (per-turn slice)    journal, arbiter, route
```

`workflow/turn-manifest` derives the per-turn slice by redirecting every edge
that would loop back to the start node or hand off to `:loop/finish` into
`:end`. So one manifest file serves both the whole-run driver and the beam's
per-round scheduling, and an edit reaches both — rather than two files that have
to be kept in agreement, which is how the two drivers drifted apart the first
time.

`workflow/iterating?` decides which is which: a manifest whose pass contains
`:llm/infer` *and* loops back to its start node is a turn the beam can schedule
against siblings. `orchestrator` loops back but its start node is an entire
nested run, so treating it as a turn would put a multi-minute job under the
per-turn deadline and run five at once.

## Constraints are the interesting part

A manifest can declare invariants that become compile-time errors:

```clojure
:constraints [{:type :must-follow :if :dispatch :then :journal}
              {:type :must-follow :if :journal  :then :arbiter}]
```

This is where a manifest earns being data rather than code. The loop's ordering
rules used to be comments; now an edit that breaks one is *refused*. The beam's
round carries two: scoring must precede the retention pass (retention reads
critic scores, and stale ones decide a live branch's fate on last round's
evidence), and a branch must be settled before its slot is refilled (otherwise
the record says a branch is running long after something took its place).

Two more ordering rules — directives before advance, cull before spawn — are
documented in `cells/beam.clj` rather than declared, because mycelium's
constraint vocabulary does not express them cleanly and a false compile error is
worse than a comment. That asymmetry is honest rather than ideal.

## The mutation protocol

An edit is not trusted because it parsed:

```
propose -> load into the live image -> validate (the loop still compiles)
        -> soak (dry-run with effectful cells stubbed to identity)
        -> commit as a new version | reject, registry restored
```

Nothing is written until the candidate survives, so a bad edit never enters the
project's version history. The **attempt** is recorded — in the journal, with
the reason — which is the right split: the store holds versions that were live,
the journal holds every attempt and its verdict.

The soak is why `:pure`/`:effects` declarations earn their keep: it stubs
effectful cells to identity so the dry-run does no IO, and `validate` rejects a
cell that declared neither, because otherwise the safety the marks exist for is
void.

## Writing a cell well

- **One transform.** A cell that does two things cannot be reused in a workflow
  that wants one of them.
- **Decide into the data map; let the manifest route.** Dispatch predicates
  should be trivial readers of explicit keys, so the routing stays visible in
  the manifest where it can be edited.
- **Declare effects honestly.** The soak's safety depends on it.
- **Compose before you write.** Most changes are an existing cell used in a
  different place, or an edge moved.

## Findings

### F1 — `:probe/ab-model` is an orphan: registered, reachable by nothing

Of 34 registered cells, 33 are referenced by a manifest. `:probe/ab-model` is
not, which means it never runs.

This is mine, from the llm-repl port, and I documented it as deliberate:
*"nothing routes on it yet, which is deliberate: the comparison is worth
recording before it is worth acting on."* Re-reading that with the wiring check
in front of me, it does not hold up — a cell no manifest reaches records nothing,
because it is never invoked. The claim describes an intention, not the system.

**Fix: either add the node to a manifest or say plainly that it is inert.** The
honest short-term answer is the latter, and it should be in the cell's docstring
rather than implied.

### F2 — A naive orphan check flags `:subworkflows` cells as missing

The same check reports `:loop/worker` as *referenced but not registered*, which
looks like a broken manifest. It is not: `orchestrator.edn` declares
`:subworkflows {:loop/worker "worker"}`, and `register-subworkflows!` registers
it as a workflow-cell at compile time, so it is absent from `cells/loaded` and
present when the manifest compiles. `orchestrator` compiles.

Recorded for the same reason as RFC-001 F2: the next person to write this check
will get the same false positive. A correct wiring check has to resolve
`:subworkflows` before comparing.

### F3 — The board's claim excluded the wrong parties (fixed here)

Found by asking what the task board does in a *team* workflow, and worth
recording as a pattern rather than a one-off.

`tasks/claim!` guarded on `(run_id IS NULL OR run_id = ?)` and set only
`run_id` — exclusive between runs, a no-op within one. On a team workflow the
competing implementors are branches of a **single** run, so two workers both
claimed the same task and both believed they held it:

```
W0 claim: true
W1 claim: true      <- same run, same task
another run: false
```

The function's docstring cited `A-4` — the read-then-write race between two beam
branches — and had fixed that half correctly while leaving the *granularity*
wrong for exactly the case it named. **A fix that addresses the mechanism of a
race without checking that it excludes the right parties is not a fix.**
Migration v12 adds `branch_id`; the holder is a branch, `run_id` keeps its
meaning (which board, NULL for backlog).

### F4 — Constraint coverage is partial, and unevenly

Two of the round's four ordering rules are enforced; the loop manifest's are
enforced; the turn's "tool before arbiter" and "settle before fire" rules are
documented in prose only. There is no way today to tell from a manifest which of
its invariants are checked and which are merely described, which means an editor
cannot know what the compiler will catch. **Not a bug — a gap in the layer's
self-description**, and the kind that gets discovered by breaking something the
compiler did not defend.
