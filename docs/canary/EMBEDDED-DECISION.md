# The embedded closed-domain decision canary

A small proof that one shape works end to end, and a measurement of whether it
is currently worth using. Those are different questions and this document
answers both, because the first one passed and the second one did not.

## The shape

    trusted state -> finite legal domain -> jolt-llama embedded scoring
                  -> trusted selection -> journalled, auditable decision

No text is generated anywhere. The scorer returns a map of candidate id to
log-probability and nothing else: no sampler, no grammar, no free-form string,
nothing parsed out of a reply. What a model can influence is the ORDER of a
list that trusted code wrote down before the model was consulted.

## Exact coordinates

    jolt-llama   casselc/jolt-llama
                 agent/onbox/embedded-libllama-v0
                 6ec88472bc63371ac9b70729ffbd58e35f35ce8d

    model        Qwen3.5-0.8B Q4_0 (qwen35 0.8B Q4_0)
    samizdat     agent/onbox/embedded-controller-canary

Reproduce with:

    jolt -A:canary -m canary.embedded
    # needs JOLT_LLAMA_LIB and JOLT_LLAMA_MODEL

## Where the pieces live, and why

Per AGENTS.md: `src/` is mechanism, `resources/` is behaviour.

| Piece | Lives in | Because |
| --- | --- | --- |
| domain legality, ranking, selection rule, journal record | `src/samizdat/decide.clj` | mechanism; knows nothing about when it is used |
| the action vocabulary, when to score, what to journal | `resources/cells/decide.clj` | behaviour; editable at runtime |
| margin, domain size cap, comparability requirement | `resources/gates.edn` | policy numbers, never constants in code |
| the wiring | `resources/manifests/decide.edn` | a capability no manifest reaches is unreachable |
| the operator entry | `resources/manual.edn` | so the next run knows it exists |
| the real-model run | `dev/canary/embedded.clj`, `:canary` alias | the ONLY place an inference engine loads |

**samizdat gained no runtime dependency.** jolt-llama, the native shim and the
model weights live only behind the `:canary` alias. `jolt test` and `jolt serve`
never see them. The mechanism is exercised by the ordinary suite with an
injected scorer and no model present — 12 tests, 42 assertions.

## What is proved

Against a real model, all five assertions hold:

    no machine state in the record:       true
    every offered option is recorded:     true
    a scorer cannot introduce an option:  true
    an unknown id cannot be selected:     true
    scores are exactly comparable:        true

The third and fourth are the ones that matter. A deliberately hostile scorer
that returns `{:hold -9.0 :DELETE-EVERYTHING 0.0}` — scoring an option outside
the domain, best — cannot get `:DELETE-EVERYTHING` into the ranking or the
selection, because `rank` only ever maps over the candidates trusted code
supplied. The model has no channel by which to name an action.

### Ordering is enforced at compile time, not by convention

    Constraint :must-precede violated: :score must appear before :apply
    on path [:start :apply :score] but does not

Reordering the manifest so it acts before journalling is refused by the
compiler. So is scoring a domain that was never established as legal. Both
invariants are declared `:enforced true` and derived into real constraints —
`enforced-constraints` returns 2 and `unenforced-invariants` returns 0, so the
documented set and the checked set cannot drift.

### The journal round trip is real

Through the actual cell, into actual SQLite, read back the way an auditor would:

    read back from SQLite: true
    recorded decision: "act"   selected: "hold"   margin: 1.5826157331466675
    recorded domain size: 5
    round-trip carries no machine state: true

`leaks?` is checked BEFORE the write, not after. An append-only journal has no
second chance, so a record that would carry a pointer, a state blob, logits,
token vectors or the prompt is replaced by a deferral naming the offending keys.

## What is NOT proved: the model carries no signal

This is the substantive negative result, and it is the reason the canary
measured sensitivity instead of stopping at the assertions.

    situation "healthy":      hold -1.08148  rollback -2.76148  scale -3.13995 ...
    situation "api degraded": hold -1.03207  rollback -2.61468  scale -2.87070 ...

    ranking under "healthy"      = [:hold :rollback :scale :restart :page]
    ranking under "api degraded" = [:hold :rollback :scale :restart :page]
    ranking CHANGED with state: false

`api: p95=780ms err=41` versus `p95=95ms err=0` does not change the ordering at
all. Every candidate moved in the same direction by a similar amount, so the
state shifted the overall distribution slightly and the ARGMAX not at all.
`hold` moved least of the five (0.049).

Worse for the guard: the decision was `:act`, not `:defer`, with a margin of
1.58 — far above the 0.5 floor. **The margin rule protects against ambiguity,
not against a confidently insensitive model.** A model can be uninformative and
certain at the same time, and this one is.

So: the mechanism is sound and the plumbing is auditable, but a 0.8B base model
with no controller tuning is not a usable ranker for this decision. Anything
built on this shape needs to measure sensitivity on its own domain first. The
canary reports `ranking CHANGED with state` for exactly that reason.

## What the project's own tests caught

Three failures appeared in the full suite that were caused by adding this
capability, all of them registration discipline working as designed:

* `cells_test` — `cells/decide.clj` was not in `cells/shipped-cells`, so it
  would have been missing from a built binary.
* `beam_test` — `:decide/score` read ctx keys it had not declared. Fixed by
  moving the scorer into the DATA map rather than adding a key to
  `manifests/ctx-keys`, which is the contract every driver must satisfy;
  putting a scorer there would oblige every driver to carry a key one
  capability uses.
* `beam_test` — the `decide` manifest was not in the catalogue the supervisor
  reads, so it was not selectable.

A fourth was caught by running the canary: `journal/note!` forwards its fourth
argument to `emit!` as OPTIONS, so the payload belongs under `:data`. Passing
the record bare stored an empty object, silently, because `emit!` does
`(or data {})`. The round trip is what exposed it; the in-memory
`journal-safe: true` check had been perfectly happy.

`samizdat.decide` was also added to `samizdat.cell-prelude`, since a shipped
cell reaching for a namespace nothing else in `src/` requires is exactly the
case that preload exists to catch.

## The blocker that had to be cleared first

This branch was silently broken under Jolt 0.8.0 before any of the above.

Jolt 0.8.0 changed `jolt.ffi/write` from `(write p type offset value)` to
`(write p type value offset)`. Both arguments are integers, so nothing refuses —
the old order writes the value into the offset. `vendor/ring_chez/adapter.clj`
was building its sockaddr with the old spelling.

Upstream `yogthos/samizdat` had already fixed this in `18b8d99`, along with
bumping four dependency pins that each needed their own migration. That commit
was cherry-picked ALONE rather than merging `upstream/main`, because the other
upstream commit (`1465b8a`, retiring memories instead of overwriting them) is a
feature and merging whole conflicts in 7 files including `beam.clj`,
`workflow.clj` and a deleted `watch.clj`. That merge is a real decision about
this fork's direction and should be made deliberately.

The same argument-order change was independently hit and fixed in jolt-llama
the same day, before this commit was found.

## Pre-existing failures, verified not caused here

The suite has 13 failures in `verification_env_test.clj`, which exercises OS
sandboxing and loopback networking. Because `ring_chez/adapter.clj`'s sockaddr
construction is exactly what the FFI fix touched, this could not be assumed
unrelated. It was checked: a worktree at `68bfad4`, before the cherry-pick,
produces the same 13 failures from the same 13 tests. Environmental, not ours.

## Deliberately not done

* Not wired into `loop`, `orchestrator` or any turn workflow. Reachable and
  testable via its own manifest; asserting that an existing workflow should use
  it is a separate decision with its own evidence.
* No `Adapter` implementation. That protocol is `chat-url`, `auth-headers`,
  `chat-body`, `parse-chat`, `prefill` — generation over HTTP, carrying the
  retry and timeout discipline generation needs. Ranking a closed set needs
  none of it.
* No multi-sequence state, no persistent state store, no candidate forking.
* No change to `manifests/ctx-keys`, the journal schema, or journal authority
  semantics. The decision is written with the existing `note!` seam.

## Follow-ups, and where they are recorded

AGENTS.md is unambiguous that beads is the only tracker and that work decided
against becomes a bead with its reason. `bd` is not installed on this machine,
so these could not be filed. They are listed here rather than dropped, and they
should be moved into beads by someone who has the tool:

* **Merge `upstream/main` (`1465b8a`, memory retirement).** Conflicts in 7
  files: `docs/RFCS/README.md`, `resources/cells/beam.clj`,
  `resources/cells/loop.clj`, `resources/manual.edn`,
  `src/samizdat/agent/beam.clj`, `src/samizdat/workflow.clj`, and
  `src/samizdat/watch.clj` (deleted upstream, modified here). Deliberately not
  attempted as a side effect of needing an FFI fix.
* **Decide whether any turn workflow should reach `decide`.** It is currently
  reachable only through its own manifest.
* **Measure sensitivity on a real domain before relying on this.** The current
  measurement says a 0.8B base model does not rank this decision usefully; the
  shape is only worth using where the scorer demonstrably carries signal.
* **The margin guard does not catch confident insensitivity.** A second guard
  keyed on whether the ranking responds to state at all would, and the canary
  already computes exactly that number.
