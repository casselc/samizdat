# Porting llm-repl into samizdat

Source: `~/src/llm-repl` (MIT, © 2026 Michael Whitford). MIT is compatible with
this project's GPL-3.0-or-later; every file carrying ported code names the
source and keeps the MIT notice.

## The items

Eight, from reading llm-repl end to end against samizdat. The rationale, the
design and the outcome of each live in its bead — `bd show <id>` — and are
deliberately not restated here.

| bead | item |
|---|---|
| `samizdat-r0d` | LR-0 the turn step becomes a pure function of the tape |
| `samizdat-943` | LR-1 forks inherit the parent's tape, and can branch an older turn |
| `samizdat-1a0` | LR-2 `bounce` / `trampoline`: probes that do not commit |
| `samizdat-8uz` | LR-3 `ab`: fan one probe across config variants |
| `samizdat-r5z` | LR-4 compaction in place, not appended to the problem message |
| `samizdat-4k7` | LR-5 `cache_prompt` / `id_slot` on the local endpoint |
| `samizdat-0ln` | LR-6 a curated operator manual, compiled from data |
| `samizdat-9yv` | LR-7 first-present-wins prompt inheritance |

What this file is FOR, and what no bead holds, is the shape of the result: a
worked example of the src/resources split the standing rule in `AGENTS.md`
demands, across eight changes at once.

## What landed

All eight, with the split the framing asks for. `src/` gained mechanism only;
every decision the harness makes about itself is a file under `resources/`.

**src (mechanism, compiled in)**

| file | what |
|---|---|
| `samizdat/tape.clj` | the tape as a value: append, fork by truncation, the compaction band, the due set, the session fold. Pure; knows nothing about branches, runs or providers. |
| `samizdat/agent/infer.clj` | the step and its four drivers — `step`, `bounce`, `trampoline`, `ab` — over an injected `complete`. No tool seam anywhere in it, which is what makes a probe safe by construction. |
| `samizdat/manual.clj` | resolve and render the curated surface from `manual.edn`, failing loud on an entry that does not resolve. |
| `samizdat/agent/state.clj` | `fork-branch`: inherit the conversation, reset every gate counter. `add-message` gained an optional turn stamp. |
| `samizdat/llm/message.clj` | `compact` rewritten to work in place. |
| `samizdat/llm/adapter/openai.clj` | `cache_prompt` / `id_slot` on the local endpoint only. |
| `samizdat/prompt.clj` | `resolve-chain` / `layer`: first-present-wins with the absent / blank / present trichotomy. |

**resources (behaviour, editable at runtime)**

| file | what |
|---|---|
| `cells/probe.clj` | the probe POLICY: which framings to try, how to judge them, whether to steer. |
| `manifests/probe.edn` | the factory loop plus the probe node — identical otherwise, so the two are a controlled comparison. |
| `gates.edn :probe` | probe width, the stuck trigger, the A/B variants. |
| `gates.edn :fork-inherit` | whether a fork inherits, and at what depth. |
| `prompts/probe-candidates.md` | the candidate framings, one per line. |
| `prompts/probe-steer.md` | what the harness says when it steers toward a winner. |
| `prompts/fork-thesis.md` | the fork nudge, which now differs for a child that inherited its parent's history. |
| `manual.edn` | which capabilities the agent is told it has, and the sentence each gets. |
| `prompt-chain.edn` | the layer chain for the base system prompt. |

## Two things worth knowing

**The turn↔message correspondence was not sound.** Compaction used to derive
its digest lines positionally — the k-th pair of body messages was the k-th
turn. It is not: a provider error or a no-call turn appends messages without
appending a turn row, so the mapping drifts by one on every such turn and the
digest silently describes the wrong turn. `add-message` now stamps the turn it
knows at creation time, and compaction matches on the stamp, falling back to
summarising a message from its own content — which is never a lie about which
turn it was. `fork-branch` slices the inherited turn log the same way.

**`:compacted?` marks do not persist**, and do not need to. `compact` runs on
the way to the wire and its output is discarded, so every turn recomputes from
the branch's pristine history. Because the replacement for a given message is a
pure function of that message and the turn log, the recomputation is
byte-identical — which is exactly the prefix stability the change is for. The
one-attempt-per-message rule inherited from llm-repl matters for a
model-produced summary and is inert for a deterministic one; it is kept because
the band is where a model-based lens would plug in.
