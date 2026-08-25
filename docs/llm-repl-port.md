# Porting llm-repl into samizdat

Source: `~/src/llm-repl` (MIT, © 2026 Michael Whitford). MIT is compatible with
this project's GPL-3.0-or-later; every file carrying ported code names the
source and keeps the MIT notice.

The items below came out of reading llm-repl end to end against samizdat. Each
is a bead; the ids are in the headings. LR-0 is the refactor the probe drivers
needed and was not in the original seven.

The framing every item is split against: **generic mechanism goes in `src/`
(it gets built); behaviour and policy go in `resources/` (the supervisor can
rewrite it at runtime).**

---

## LR-0 (samizdat-r0d) — the turn step becomes a pure function of the tape

**Why.** llm-repl's whole cheapness comes from one property: a completion is a
pure function of `messages[]`, so the step is a reducing function and every
driver (commit / probe / fan / fork) is a different way of applying that one
step. samizdat's turn reaches into the branch map for its messages and its
request knobs, journals from inside the call, and has no seam a caller can
drive with a tape of their own. Nothing downstream can observe a turn without
running one.

**Shape.** A `tape` value — `{:messages :turns :prefill :force-tool}` — and a
step `(step complete tape) -> {:tape :call :parsed :signals :said}` where
`complete` is the single injected effect, exactly llm-repl's `eval-rf`
`:complete` seam. `absorb-response` is already pure and is reused unchanged.

**src** `samizdat.tape` (the value), `samizdat.agent.infer` (the step + the
`complete` seam). **resources** none — this is mechanism.

**Acceptance.** A turn can be driven from a literal tape with a stub
`complete`, with no db, no provider and no branch map.

---

## LR-1 (samizdat-943) — forks inherit the parent's tape, and can branch an older turn

**Why.** `beam/open-branch!` opens every child with `initial-messages problem`
— a fresh two-message tape. The child re-derives everything its parent learned
from the problem statement. `crossover-block`'s docstring already claims the
child "carries them in its inherited history"; it does not.

**Shape.** llm-repl's `fork!`, including `{:at N}`: copy the tape, optionally
truncate to the first N messages, stamp `:forked-at`, and **re-derive** the
turn counter from the truncated tape rather than copying the parent's.

**src** `state/fork-branch` — pure. **resources** the inherit-or-not decision
and the fork depth as a `gates.edn` entry; the nudge prose stays in
`prompts/`.

**Acceptance.** A forked branch's messages are its parent's up to the fork
depth; `:forked-at` is recorded; counters agree with the tape.

---

## LR-2 (samizdat-1a0) — `bounce` / `trampoline`: probes that do not commit

**Why.** samizdat cannot ask "what would this branch do next?" without
spending a turn, a journal row and cull-counter movement. llm-repl fans N
inputs off a fixed prefix, each independent, tape unchanged, prefix cache
reused.

**Caveat that llm-repl does not have and we do.** samizdat's turn is not a
pure function of the tape all the way through — `:tool/dispatch` runs shell
and writes files. A probe therefore stops at inference + parse and never
reaches dispatch. Enforced by construction: the probe path calls
`infer/step`, which has no tool seam in it at all.

**src** `infer/bounce`, `infer/trampoline` — mechanism only. **resources** a
`:llm/trampoline` cell holding the policy: which candidates, how to score
them, whether to commit the winner.

**Acceptance.** A trampoline over a fixed tape returns one result per input,
leaves the tape at its original depth, and writes no turn rows.

---

## LR-3 (samizdat-8uz) — `ab`: fan one probe across config variants

**Why.** The dual of LR-2 — same tape, varied interpreter. samizdat has static
per-role model assignment (`:run :role-models`) but no way to run one decision
point under two models and keep the better result.

**src** `infer/ab` — fork per variant, same probe on each, per-variant errors
as data. **resources** the variant table and the judging, as a cell.

**Acceptance.** Two variants over one tape produce two independent results;
one failing variant does not sink the other.

---

## LR-4 (samizdat-r5z) — compaction in place, not appended to the problem message

**Why.** `llm/message/compact` appends its digest to the **problem message** to
keep strict alternation, which rewrites the shared prefix every time
compaction fires and busts the upstream prefix cache from index 1 onward.
`chat-memory`'s central design note is exactly this failure: compact each
assistant message **in place**, once, as it ages out of a k-window, so roles,
order and count are unchanged — alternation holds *and* the prefix stays
stable.

Three further pieces port whole:

- **the compression band** `|new| <= max(|original|, floor)` rather than
  "strictly shorter". A strict ratchet has an empty solution set at the small
  end; llm-repl recorded 31 compaction calls against one 26-char message
  before they found it. Measured floor: 120 chars.
- **one attempt per message, ever** (`:declined?`). A rejection is a negative
  cache entry with an infinite TTL. A false permanent costs one long-ish
  message; a false transient costs an unbounded loop.
- **the session fold** (`fold-split` / `apply-fold`) — collapse a finished
  session into one block plus a verbatim tail, under a strictly-shorter
  contract with a safe reject. This is what `artifacts/seed-from-run!` wants.

**src** the pure band / due-set / apply / fold functions, ported. **resources**
the k-window, floor and threshold as data; the compaction lens as a prompt if
it ever goes model-based.

**Acceptance.** Compacting twice touches each message once; the frame is
byte-identical across compactions; a declined message is never retried.

---

## LR-5 (samizdat-4k7) — `cache_prompt` / `id_slot` on the local endpoint

**Why.** No `cache_prompt`, `id_slot` or `n_predict` anywhere in `src/`.
llm-repl wrote a whole llama.cpp backend for these: without prefix-cache
pinning, LR-1's inherited tapes and LR-2's probes re-prefill the entire prefix
on every call, which is most of their cost.

**src** adapter layer only — thread a stable per-branch slot key through the
request and emit it for the local provider. Purely generic.

**Acceptance.** A local-provider request body carries `cache_prompt` and a
stable `id_slot`; no other provider's body changes by a byte.

---

## LR-6 (samizdat-0ln) — a curated operator manual, compiled from data

**Why.** llm-repl tags 13 vars `^{:manual "sentence"}` and compiles `(manual)`
from `ns-publics`, with help, the TUI overlay and a future MCP facade all
rendering that one compile. samizdat's agent drives the image through `eval`
and has `doc`/`complete`, but nothing tells it what the harness's own curated
command surface is.

**Invert llm-repl's design for our constraint.** Their curation lives in var
metadata, which is `src/`, which the supervisor cannot edit. Ours lives in
`resources/manual.edn`.

**src** the compile-and-render mechanism. **resources** `manual.edn` — the
selection and the prose.

**Acceptance.** The manual renders from resources; adding an entry needs no
rebuild; an entry naming a var that does not resolve fails loudly.

---

## LR-7 (samizdat-9yv) — first-present-wins prompt inheritance

**Why.** `roster/resolve-preamble` walks session > model > provider > config,
where absent means inherit, `false`/`""` means explicitly none, and a value is
a string or `{:file path}`. samizdat's `workflow-prompt` concatenates a
manifest prompt onto the base with no way to replace or suppress it.

**src** the chain resolver. **resources** the chain itself, as data.

**Acceptance.** A level with `false` suppresses the layer; an absent level
inherits; `{:file}` resolves through `io/resource`.

---

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
