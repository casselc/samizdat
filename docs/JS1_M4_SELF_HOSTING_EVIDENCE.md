# JS1 M4 — Real Self-Hosting Canary

**Result: M4: FAIL — first causal failure class `:model-behavior`.**

The runtime, authority, recovery, provenance and verification contract held
throughout. The canary failed because neither model converged on a change the
controller's trusted verifier would accept: four real `done` attempts crossed
the controller-owned VerificationEnvironment and all four came back RED, and
the run exhausted its (twice-extended) turn budget without a GREEN.

This document records the failure. It is not a retry, and no code, prompt,
policy or threshold was changed to rescue the run.

---

## 1. Frozen coordinates

| Coordinate | Value |
| --- | --- |
| M3 executable oracle | `b1d69867c1d02d34f9130309ae55c9baffc55c29` |
| M3 evidence oracle | `64effe150269fdebfdd9ccb619cab89187494b75` |
| M4 code under test (controller) | `64effe150269fdebfdd9ccb619cab89187494b75` |
| M3 upstream base | `effcf7dbf439bd3baa2718bc3e780f2031ecae59` |
| Upstream `yogthos/samizdat main` at M4 entry | `effcf7dbf439bd3baa2718bc3e780f2031ecae59` |
| Frozen M2 closure | `a7e857fbde5b5603477a6982c28275aae728e294` |
| Bounded Jolt | `f8899905d98a0abdcc6b4ae61dfd5c8bdb9c7277` |
| Jolt M1 base | `4af2362176160f2ed0e366689d7232b1a38adfec` |
| SCI | `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53` |
| Chez | `/usr/local/bin/scheme` (csv10.4.1) |

Upstream had **not** moved from the frozen M3 base, so no forward-port was
performed and none was faked. `git diff b1d6986..64effe15` touches only
`docs/JS1_M3_EVIDENCE.md`, so the controller checkout at `64effe15` is
byte-identical in `src/`, `resources/`, `test/` and `bin/` to the executable
oracle that passed the final clean M3 gates.

## 2. Controller and target

| Role | Path | SHA | End state |
| --- | --- | --- | --- |
| Controller (immutable) | `/home/chuck/opencode/src/samizdat-controller-m4` | `64effe15` | **tracked-clean, zero untracked, unchanged** |
| Target (disposable) | `/home/chuck/opencode/src/samizdat-canary-target-m4` | `64effe15` | two tracked files modified, both explained by receipts |

Separate clones, separate filesystem trees. The target carries one
operator-placed, agent-unwritable file — `.samizdat/config.edn` holding
`{:run {:bounded {:profile :agent/project-develop}}}` — the userspace request
for the bounded lane. `project/edit` refuses that path through
`files/run-config?`.

Target base digests:

```
9dbc01d242d920365d88af0d0c4c1a21247ada54511693cec3939b28a785f705  src/samizdat/util.clj
6263ca9237ac3d2367c10f61a52ad6e1f17ff63bd08befa81107a0dbbc6b6f60  test/samizdat/util_test.clj
```

## 3. Exact M3 pre-canary gate — GREEN

`bin/js1-m3 test` from the tracked-clean controller checkout, pinned Jolt/SCI/
Chez, exit 0:

```
M3 authority, provenance, and lease closure:    49 tests,  246 assertions, 0 failures, 0 errors
pinned bounded evaluator:                       25 tests,  294 assertions, 0 failures, 0 errors
ordinary suite:                               1573 tests, 6320 assertions, 0 failures, 0 errors
```

The gate verified the Samizdat checkout, the pinned Jolt worktree, the vendored
SCI worktree and the SCI version before running any test.

## 4. Verification environment

Selected by trusted controller environment (`SAMIZDAT_VERIFY_ENV=bwrap`), never
from `gates.edn`.

```
selected      :bwrap
available?    true
coordinate    js1-ve/v1:bab68fdf5a128ea0094ce345680d8eb0101792a0e703f8a29bfc28e4ad82a861
attribution   sha256:e3fd1b7d73e88ec4f88e482c61912524bd48acbbe6ed3cd319a4c4ca387fff2f (bwrap-verification-env)
```

An operator pre-flight ran the environment once against the untouched target,
before the run existed and outside the `done` path: GREEN,
`1 test / 4 assertions / 0 failures / 0 errors`. Every later RED is therefore a
real verdict on real bytes, not a missing substrate.

## 5. Task

Recorded verbatim as the run's `problem`: make `samizdat.util/truncate-middle`
total.

`truncate-middle` derives `head-len`/`tail-len` by subtracting the 3-character
marker `" … "` from `max-len`; for `max-len` in `{0,1,2}` those go negative and
`subs` throws. Its production caller, the shell tool in
`src/samizdat/security/policy.clj`, passes `(max-output-chars)` — runtime-tunable
policy data in `resources/gates.edn` — so the crash is reachable from a retuned,
agent-editable threshold.

The original JS1 evaluator-history diagnostic task was **not** reused: M3 closed
durable evaluator recovery, so that diagnostic no longer describes an unsolved
defect at this coordinate. The replacement is real, unsolved on the target
coordinate, local, reachable through `project/read|list|search|stat|edit` alone,
and objectively settled by the controller's focused verifier over
`samizdat.util-test`.

## 6. Run

Created through `POST /v1/runs` on the running harness — the production route.
No fixture, no fake provider, no direct evaluator API call, no hand-edited
target, no `project/run`, no shell, no network in SCI.

| Field | Value |
| --- | --- |
| run-id | `020296a8-a068-4559-83c1-e00a361b0c22` |
| database | `/home/chuck/opencode/src/m4-evidence/m4-canary.sqlite3` |
| root | `/home/chuck/opencode/src/samizdat-canary-target-m4` |
| binding id | `bind:020296a8-a068-4559-83c1-e00a361b0c22` |
| instance id | `inst:020296a8-a068-4559-83c1-e00a361b0c22` |
| profile | `:agent/project-develop` |
| capabilities | `#{:project/read :project/list :project/search :project/stat :project/edit}` |
| per-eval timeout | 30000 ms |
| orientation digest | `sha256:75f9d4cfe5c1082a901d0d07640ea380f48441f530e811910ef742f48e1ef9d8` |
| initial max turns | 60 (extended 60→90→120) |
| beam width | 1 (bounded lane forces width one) |
| selected loop manifest | `critic` v1, chosen by the harness's own selection step; iterating, lease-compatible |
| started / ended | `2026-08-29T09:30:32.789Z` / `2026-08-29T10:37:10.633Z` |
| terminal | `failed`; branch B1 `exhausted` |

ContextSpec coordinate:

```
js0:[:map [[:context/bounds [:map [[:max-edit-chars 60000] [:max-list-entries 1000]
 [:max-read-chars 60000] [:max-search-file-chars 500000] [:max-search-files 20000]
 [:max-search-line-chars 300] [:max-search-pattern-chars 200] [:max-search-results 500]]]]
 [:context/capabilities [:vector [:project/edit :project/list :project/read :project/search :project/stat]]]
 [:context/profile :agent/project-develop]
 [:context/root "/home/chuck/opencode/src/samizdat-canary-target-m4"]
 [:context/timeout-ms 30000]]]
```

RuntimeCoordinate:

```
js1-rt/v1:[:map [[:runtime/evaluator-protocol 1]
 [:runtime/jolt-publish-source "jolt-publish/v1:sha256:914ccd9f722efd98fe8e1e1381574a3efba04ae45a689e8c1918d420db82f0c1"]
 [:runtime/jolt-source "4af2362176160f2ed0e366689d7232b1a38adfec"]
 [:runtime/jolt-version "js1-runtime-final-1-gf8899905"]
 [:runtime/language "js0-lang/v1: js0-pure-sci, 156 symbols"]
 [:runtime/receipt-protocol 1]
 [:runtime/sci-source "32d62a5136ad3dc148588752f5bcc4cc30b14752"]
 [:runtime/sci-version "0.13.53"]]]
```

## 7. Provider provenance

Real provider adapter against the operator's local OpenAI-compatible router.
No credentials appear here; the router requires none. Three InferenceEpochs,
122 InferenceInvocations:

| Epoch | Opened at turn | Provider | Model | Adapter | Nonsecret config digest | Closed |
| --- | --- | --- | --- | --- | --- | --- |
| `e1009cfa-2fa0-4294-bac0-bc1cd4501ab2` | 1 | `local` | `openrouter.z-ai/glm-5.3` | `local` | `1b035b57…a49fa0` | `10:05:05.942Z` |
| `568a8651-0cff-4461-9fb5-96ef81e3d4df` | 18 | `openai` | `openrouter.z-ai/glm-5.3` | `OpenAI` | `bfc5ace2…0cdf23` | `10:22:49.762Z` |
| `10a11ced-58e6-4c61-979a-f9b0ff239418` | 24 | `openai` | `fireworks.kimi-k2p7-code` | `OpenAI` | `32ebab6c…ecb021` | open |

Each transition was a **restart-and-resume of the same run**, not a new run: the
same durable `EvaluatorBinding`, the same target tree, the same task, the same
durable budget. Epoch reuse/closure behaved exactly as M3 specifies — the open
epoch closed and its successor opened when the safe realization changed, while
evaluator authority stayed identical.

This is therefore a **mixed-provider, mixed-model canary**; no claim is made
that any one model performed the whole run.

## 8. Interruption and recovery — the contract held

Two real process interruptions (`SIGKILL`, not `rebuild!` inside a live run),
each at a safe point: the last turn was journalled, every evaluation carried a
completion row (0 pending), and the only work in flight was a provider HTTP
call whose answer can no longer actuate.

Both restarts brought up a **new controller process** against the same durable
DB and the same target tree. Each self-reconciled the crashed run
(`marked 1 run(s) interrupted: still flagged running with no process`) and was
resumed through `POST /v1/runs/{id}/resume`.

### Cycle 1 — interrupt after turn 17, resume at turn 18

| Required observation | Result |
| --- | --- |
| logical binding identity unchanged | `binding-id`, `instance-id` identical |
| ContextSpec unchanged | `spec-id` (which embeds the full ContextSpec and its coordinate) identical |
| RuntimeCoordinate unchanged | identical |
| trusted-orientation bytes/digest unchanged | `orientation-digest` identical; resume restores persisted bytes, not a re-rendered prompt |
| fresh live SCI context | new OS process; the prior context died with the killed PID and `reconstruct!` allocates a new one |
| **zero world re-actuation during replay** | receipts **12 → 12**, evals **9 → 9**, completions **9 → 9**; the evaluator evidence projection was byte-identical either side (`order` = `[[read],[read],[stat],[]]`) |
| target tree not rewritten by replay | both file digests **and mtimes** unchanged across the restart |
| original durable turn budget retained | `max_turns` 60 before and after; resume asked for nothing and got nothing |
| next TurnLease freshly minted | the resumed beam continued at turn 18, one past the last journalled turn |
| work continued after recovery | turn 18 succeeded — the agent read `project/edit`'s contract and went on to make its first mutation |

### Cycle 2 — interrupt after turn 23, resume at turn 24 with a different model

Same invariants held; additionally the second epoch closed and a third opened on
the new model, with **no evaluator-authority change**.

### Honest limitation on the replay evidence

Both interruptions fell **before** the first `project/edit` was committed. The
zero-actuation replay evidence therefore covers observations only
(`project/read`, `project/stat`) — it does **not** demonstrate that a committed
*edit* receipt is replayed without re-actuation. That specific case remains
covered by the M3 test lane, not by this canary. A future canary should place an
interruption after a committed edit.

## 9. Budget authority

Two controller-authorized extensions through the audited, idempotent durable
transaction, each with a stable request id and a retained reason:

```
budget extension 020296a8-… 60 -> 90   request m4-extend-1
budget extension 020296a8-… 90 -> 120  request m4-extend-2
```

Neither resume widened the budget; only the trusted extension act did, and the
run then genuinely scheduled to turn 120.

## 10. Operator interventions

All durable, attributable, and delivered through the normal interventions queue.
Nothing was hand-edited in the target, no capability was widened, no evaluator
history was touched, no verification was bypassed.

| id | kind | issued by | applied at turn | substance |
| --- | --- | --- | --- | --- |
| 1,2,3,4,5,7,8 | message | `watch` | 7,10,18,23,24,29,29 | the **harness's own** supervisor, raising `provider-unreliable` and `tool-failing` |
| 6 | message | `m4-operator` | 24 | anti-stagnation: reassess durable state, stop re-discovering the surface, converge to a testable edit and `done`; plus a note that the SCI context is persistent across turns and restarts |
| 9 | review | `m4-operator` | 55 | both edits are committed — stop exploring, call `done` |
| 10 | review | `m4-operator` | 89 | the controller already gave a verdict; edit, then `done`, then read the verifier output |

None supplied hidden authority and none solved the task. Intervention 6 named no
implementation detail the agent had not already derived itself.

## 11. Trajectory

| Metric | Value |
| --- | --- |
| turns | 120 |
| by tool | `eval` 97, `doc` 5, `complete` 4, `done` 4, `__no_call__` 1, `__parse_error__` 2, `__provider_error__` 2, out-of-context tool names 5 |
| by category | neutral 66, failure 39, mechanics 15 |
| evaluations | 94 (59 completed, 35 failed) |
| semantic operations | 78 — `project/read` 54, `project/stat` 13, `project/edit` 11 |
| ops / eval | 0.83 |
| ops / model turn | 0.65 |
| multi-operation evals | 13 |
| project edits (committed) | 11 outcome receipts over 2 files |
| repeated observation signatures | `read src/samizdat/util.clj` ×29, `read test/samizdat/util_test.clj` ×23, `stat util_test.clj` ×8, `stat util.clj` ×5, one identical `edit` re-issued ×2 |
| verifier attempts | 4 — **RED, RED, RED, RED**; no GREEN |
| provider switches | 2 (3 epochs) |
| provider errors | 2 turns settled as `__provider_error__`; 5 upstream 500s out of 135 chat calls observed at the endpoint |
| TurnLease timeouts | 0 |
| operator interventions | 3 (1 message, 2 review) + 7 harness-issued watch messages |
| budget | 60 → 90 → 120, exhausted |

### REPL leverage — the good half

Between turns 12 and 15 the agent used the bounded SCI context exactly as
intended: it defined `buggy` and `fixed` implementations side by side in a single
evaluation, ran both across a range of `max-len` values, and returned one compact
conclusion rather than a round trip per fact:

```
["marker-len=3"
 "old-derived-lens=[[-1 -2] [-1 -1] [0 -1] [0 0] [0 1] [1 1]]"
 "old-negative-for=(0 1 2)"
 "fixed-counts=[0 1 2 3 4 5 6 7 8 9 10 11]"
 "fixed-total=true"
 "preserved-20=\"The quic …  lazy dog\""
 "preserved-10=\"abc … wxyz\""
 "boundary=[\"\" \"a\" \"ab\" \" … \" \" … j\" \"a … j\"]"]
```

That is observe → branch/filter/reduce locally → return a compact conclusion,
and it produced the correct fix before a single byte was written.

### Persistent helper state — not exercised

**No `def` was ever evaluated in the SCI context.** Every computation used
`let`-bound local functions, rebuilt from scratch in each evaluation. So the
"persistent SCI state was actually useful" criterion is met by the compound
computational-eval half only, and helper *reuse* — and therefore
helper-reconstruction-after-restart — was never observed. Operator intervention 6
explicitly pointed at `def` persistence at turn 24 and did not change the
behaviour.

## 12. Verification — real, and really RED

Every `done` crossed `ship/bounded-done` → controller-owned
VerificationEnvironment → pinned verifier argv derived from the run's own edit
receipts. The controller, not the model, decided.

| # | invocation-index | input coordinate | verdict | verifier stderr |
| --- | --- | --- | --- | --- |
| 1 | 1 | `sha256:94846176…ae7847` | RED, exit 1 | `EOF while reading (/workspace/src/samizdat/util.clj:88:1)` |
| 2 | 2 | `sha256:ece9fc18…fa3880` | RED, exit 1 | `Don't know how to create ISeq from: java.lang.Character` at `util.clj:28` |
| 3 | 3 | `sha256:ece9fc18…fa3880` | RED, exit 1 | same |
| 4 | 4 | `sha256:dcaf5e63…3b5940` | RED, exit 1 | same |

All four ran in the bwrap sandbox over a private copy of the root, exit 1,
`disposition: terminated`, ~3.3 s each, full RFC-012 run envelopes with
attribution and input coordinates. No host escape; no verification touched the
authoritative tree; the final on-disk digests equal the last edit receipts, so
test execution mutated nothing.

**No GREEN was ever produced.** The model said it was finished four times and the
controller refused four times, correctly, each time with a real failure in the
bytes the model had actually written.

## 13. Target integrity

Controller: `64effe15`, zero tracked modifications, zero untracked files.

Target: exactly the two files the receipts name.

```
 src/samizdat/util.clj       | 86 ++++++++++++++-----------------
 test/samizdat/util_test.clj | 28 ++++++++++-
```

Final on-disk digests match the last outcome receipts exactly:

```
de4a4c9c4ba9a38c508ca89dd80114f85512f98198bbf3a7ed70b4ec1b7012cb  src/samizdat/util.clj   (1975 bytes)
8700c401f9bced72256f44340c74fa57d08743066ae5da2a2aff05bc181e4616  test/samizdat/util_test.clj (2751 bytes)
```

No unexplained mutation. No protected controller/config mutation — the target's
`.samizdat/config.edn` is byte-identical to what the operator placed. No
historical edit was actuated again by replay.

## 14. First causal failure, and what it was not

**Class: `:model-behavior`.**

The first `project/edit` (turn ~28) was correct: it made `truncate-middle` total
with exactly the arithmetic the agent had already validated in SCI, and the
paired test edit added four well-chosen assertions including a
`(range 0 50)` totality property. That change was *one unbalanced paren* away
from GREEN.

Instead of repairing that paren, the agent — after the first RED — **rewrote
`src/samizdat/util.clj` wholesale from memory**, replacing the real
`sh-quote` and `generation-cache` with an invented `defn-memo` /
`memoize-by-generation` pair and shrinking the file from 3839 to 1974 bytes. It
then spent the remaining ~60 turns failing to repair the syntax of its own
invention, oscillating between inspections the harness's repeat- and
oscillation-guards flagged twice.

The proximate cause is the whole-file shape of `project/edit`: the only
mutation primitive is "replace this entire file with this string", so a model
that cannot hold ~90 lines verbatim regenerates them, and regeneration is
reconstruction from priors, not editing. Both models reached for it.

Secondary contributing classes, recorded but not first-causal:

- **`:provider`** — `openrouter.z-ai/glm-5.3` through this endpoint burned its
  entire 16k output budget on reasoning and returned `content: null` on exactly
  the long-output turns that would have carried the edit. The router surfaced
  that as `500 [json.exception.type_error.302] type must be string, but is null`
  after 190–210 s. Five such 500s in 135 observed chat calls; two turns settled
  as `__provider_error__`. This forced the model switch.
- **`:operator-environment`** — the router answers llama.cpp's `/props`, so
  declaring it `:local` made the adapter send `cache_prompt` and
  `chat_template_kwargs`, which Fireworks rejects outright (`Extra inputs are
  not permitted, field: 'cache_prompt'`). Corrected by declaring `:openai`
  before the first resume. Operator misconfiguration, not a harness defect.
- **`:prompt`** — see finding F-1.

It was **not** `:runtime`, `:authority`, `:replay`, `:resume`, `:turn-lease`,
`:verification`, `:task` or `:evidence-harness`. Every one of those held.

## 15. Findings (record only — none implemented in M4)

**F-1 — the bounded lane's per-turn context advertises tools it does not have.**
The context block injects `resources/prompts/task-none.md`, which tells the model
"create one with `task create` … or `task claim` an open one from the board". The
bounded catalog is `eval, doc, complete, done` plus the `project/*` operations;
there is no task tool. **Both** models chased it — GLM across turns 19–23,
Kimi across turns 24–27 — burning at least nine turns on `task`, `task/create`,
`task-create` and `complete "task"`. The per-turn context block should be filtered
to the bound catalog in the bounded lane.

**F-2 — the bounded system prompt does not describe the tool-call envelope.**
In the bounded lane the trusted orientation replaces the base system prompt
entirely, and the factory `loop`/`critic` manifests declare no `:prompt`, so the
model is never told the ```` ```tool-call ```` fence or its JSON/XML shape. The
run opened with `__no_call__` and two `__parse_error__` turns before the repair
ladder taught it the format. The ladder worked — this is a three-turn tax, not a
failure — but the trusted orientation is the natural place for the envelope, since
it is the only prompt the bounded lane guarantees.

**F-3 — whole-file `project/edit` invites regeneration.** See §14. An anchored
range/replace form, or a refusal when a replacement drops a previously present
top-level definition, would have converted this run's fatal step into a refusal
with evidence. Recorded as a finding; **not** an argument for `project/run`.

**F-4 — focused verification cannot see collateral damage.** The verifier runs
only the namespaces of the *changed test files*. Had the agent's rewritten
`util.clj` merely compiled, `samizdat.util-test` would have gone GREEN while
`sh-quote` and `generation-cache` — both live production dependencies — were
silently deleted. This run was saved only by the rewrite also being
syntactically broken. The bounded lane's completion gate has no notion of
"changed source namespaces whose dependents still load".

**F-5 — a resumed branch reports a stale turn cap.** After the extensions the
durable row read 120 and the scheduler ran to 120, but the exhausted branch's
retained reason says `turn cap of 60 reached` — the cap captured into the branch
at resume time. Cosmetic; the authority itself was correct.

**F-6 — live SCI context identity is not durably projected.**
`evaluator/describe` exposes `:evaluator/live-context`, but nothing journals it
and no read model surfaces it, so pre/post-restart context ids cannot be compared
from durable evidence alone. Context freshness had to be argued from the process
boundary and from reconstruction-from-receipts instead.

## 16. What M4 does and does not establish

Established by this run: the bounded lane really does run as an agent end to
end through the production path — production run creation, current userspace and
manifests, a real provider adapter, trusted bounded orientation, the TurnLease
scheduler, persistent SCI, authorized semantic operations against a real project
tree, durable journal and receipts, exact recovery across two real process
crashes, exact provider provenance across two model transitions, audited budget
extension, and a controller-owned verification environment that returns real
verdicts on real bytes and refuses completion when they are wrong.

Not established: that the system can carry a small, well-specified,
already-solved change to trusted GREEN completion under its own steam. It did
not, in 120 turns, with two capable models and three operator nudges.

M4 does **not** prove anything about one particular model, autonomous
long-horizon reliability, multi-agent correctness, JS2 execution safety, or
arbitrary task competence.

## 17. Preserved artifacts

- database / journal — `/home/chuck/opencode/src/m4-evidence/m4-canary.sqlite3`
- target worktree, left exactly as the run left it — `/home/chuck/opencode/src/samizdat-canary-target-m4`
- controller checkout, untouched at `64effe15` — `/home/chuck/opencode/src/samizdat-controller-m4`
- M3 gate log — `m4-evidence/m3-gate.log`
- controller logs for the three process lives — `m4-evidence/serve-1.log`, `serve-2.log`, `serve-3.log`
- pre-interruption snapshots — `m4-evidence/pre-interrupt.txt`, `pre-interrupt-2.txt`
- endpoint request/response provenance — `m4-evidence/provider-proxy.log`
- the destructive third edit, captured — `m4-evidence/util-after-third-edit.clj`
- machine-readable summary — `artifacts/js1-m4-self-hosting-canary.edn`
- supporting records committed for review — `artifacts/m4-canary/` (the journal itself stays untracked per `.gitignore`; pinned at `sha256:fd67ab0a91a5ab177c48a6dc1ccd044135c8f0342d4c46d2babf171ac543230e`)

**M4: FAIL. STOP FOR REVIEW.**
