# JS1 M4 Attempt 2 — Bounded-Agent Surface Hardening + Repeat Canary

**Result: M4 ATTEMPT 2: PASS.**

The same task attempt 1 failed was carried to trusted terminal completion:
model `done` → controller focused verifier GREEN → controller closure verifier
GREEN over the project's whole suite → terminal accepted completion, in 50 of
60 turns, on one provider and one model, with a real SIGKILL **after** a
committed mutation and exact recovery either side of it.

Attempt 1 (`js1-m4-current-effcf7d @ 0ad7070`) is untouched. Its journal still
digests to `fd67ab0a…543230e`, its target worktree and controller checkout are
byte-identical to how that run left them.

---

## 1. Coordinates

| Coordinate | Value |
| --- | --- |
| Attempt-2 executable oracle | `d03564521b3bdf0733e42b13e7e89c2563d2be19` |
| Attempt-2 evidence oracle | recorded by the commit containing this file |
| Attempt-1 evidence (frozen) | `0ad7070368ae79ebbbba95a998f5ffda4b551dd9` |
| M3 executable oracle | `b1d69867c1d02d34f9130309ae55c9baffc55c29` |
| M3 evidence oracle | `64effe150269fdebfdd9ccb619cab89187494b75` |
| Upstream base (re-verified, unmoved) | `effcf7dbf439bd3baa2718bc3e780f2031ecae59` |
| Bounded Jolt | `f8899905d98a0abdcc6b4ae61dfd5c8bdb9c7277` |
| SCI | `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53` |

Upstream had not moved, so attempt 2 branches from the frozen M3/M4 executable
coordinate. No forward-port. Attempt 1 was not rebased.

### Hardening commits

| Commit | What |
| --- | --- |
| `d3f01ec` | the four targeted hardenings (3A–3D) plus §4 and §5 |
| `284eb93` | context-block arity restored; orientation tests moved to the bounded lane |
| `d035645` | suite made hermetic enough to be its own closure gate |

## 2. The four hardenings

**3A — the model-visible surface matches real authority.** New
`samizdat.agent.surface` derives ONE description of what a branch may call:
bounded, from its own durable ContextSpec; ordinary, from the dispatch catalog.
The per-turn context block declares each part's tool needs and drops the parts
the surface cannot meet, so `task-none.md` no longer tells a bounded branch to
run `task create`. The arbiter filters gates whose `:tool` is off-surface,
closing the same class of bug on the path that PREFILLS a tool name into the
assistant turn.

**3B — the tool-call protocol is taught.** The trusted orientation now renders,
from the surface: the fenced envelope with a concrete example, the XML form for
multi-line content, and the in-eval rule with explicit RIGHT/WRONG. A narrowed
binding narrows every sentence.

**3C — a surgical anchored mutation, with one recorded deviation.**
`(project/edit path base old-text new-text)` replaces the ONE exact occurrence
of `old-text` and leaves every other byte alone; zero and multiple occurrences
are both refused rather than guessed. It reuses the whole-file form's
confinement, protected-path refusal, symlink refusal, digest anchor and atomic
publication, records intent before and outcome after, and replays from its
receipt without writing.

**DEVIATION FROM THE SPEC, stated plainly.** §3C asked for a distinct
`project/replace` operation with its own `:project/replace` capability. That is
not implementable against the frozen runtime: `jolt.sandbox/profiles` is a
CLOSED maximum enforced by `resolve-context-spec` (`authorized ⊆ profile-max`),
and `checked-operations` rejects a duplicate operation id, so a separate
capability would require editing the pinned Jolt — which `bin/js1-m3` refuses
on SHA, which would move the RuntimeCoordinate embedded in every durable
binding, and which §6/§7 place out of scope. The anchored form is therefore an
ARITY of `project/edit` under the same `:project/edit` authority. Every
REQUIRED semantic in §3C's contract list is met; the surface name and the
separate capability id are not. An anchored replacement is not new authority
over the project — it is a narrower way to spend authority `project/edit`
already holds — and receipts stay distinguishable by argument count.

**3D — progressive final verification.** `done` crosses the focused verifier
first, so RED stays cheap; only a focused GREEN buys the closure run over the
project's whole suite. Completion requires both. Neither is selectable by the
model, and the closure argv (`jolt -M:test`) has no derived element at all.

**§4 — repeated-unchanged observation.** `samizdat.agent.observation` computes,
from durable receipts, the observations repeated with an identical result and
no intervening mutation of that resource, and feeds the finding to the existing
watch seam. Evidence-derived, feedback only, no read is ever blocked, no new
policy engine.

**§5A** the exhaustion sentence reports the cap the scheduler actually enforced.
**§5B** SCI context allocation and reconstruction are journalled as lifecycle
facts, without becoming a replay coordinate.

## 3. Hardening test gate — GREEN

`bin/js1-m3 test` from the tracked-clean controller at `d035645`, exit 0:

```
M3 authority, provenance, and lease closure:    49 tests,  246 assertions, 0 failures, 0 errors
pinned bounded evaluator:                       32 tests,  350 assertions, 0 failures, 0 errors
ordinary suite:                               1585 tests, 6373 assertions, 0 failures, 0 errors
```

Gate items: **A** the exact M1–M3 gate above. **B/C/D** `samizdat.surface-test`
(6 tests) and the orientation tests in `samizdat.evaluator-test`. **E** anchored
replacement: exact-anchor success, stale digest, zero-match, multiple-match,
empty anchor, symlink, path escape, protected config, non-string, missing
target, receipts, replay zero-actuation with unchanged mtime, correct digest.
**F** whole-file create/replace unchanged. **G/H/I/J** progressive verification.
**K** repeated-unchanged observation. **L** the ordinary suite.

### The pre-flight that mattered

The closure gate was RED on an UNTOUCHED target: 21 failures, 3 errors, none
about the target. Every one was a test that assumed the host it was written on
— `bounded-done` cases mock `ve/run` but let `ve/available?` reach the real
probe, and bubblewrap is deliberately absent inside the sandbox where the
closure gate runs them. Fixed as test hygiene in `d035645`. Without that
pre-flight, GREEN would have been unreachable for reasons belonging to the
harness, and attempt 2 would have recorded a false FAIL.

## 4. Controller and target

| Role | Path | SHA | End state |
| --- | --- | --- | --- |
| Controller (immutable) | `samizdat-controller-m4a2` | `d035645` | **0 tracked modifications, 0 untracked** |
| Target (disposable) | `samizdat-canary-target-m4a2` | `d035645` | 2 tracked files modified, both receipt-explained |

**The target starts at the hardening coordinate, not attempt 1's base, and this
is recorded deliberately** (§9). The closure gate runs the TARGET's own suite,
so a target at the older base would have had the harness verify a codebase that
is not itself. The task defect is unsolved at that base and both task files are
byte-identical to attempt 1:

```
9dbc01d242d920365d88af0d0c4c1a21247ada54511693cec3939b28a785f705  src/samizdat/util.clj
6263ca9237ac3d2367c10f61a52ad6e1f17ff63bd08befa81107a0dbbc6b6f60  test/samizdat/util_test.clj
```

The target's only operator-placed file is `.samizdat/config.edn`, which
`project/edit` refuses; it is byte-identical at the end of the run.

## 5. The run

| Field | Value |
| --- | --- |
| run-id | `af519213-5304-4812-9452-96ad20dc5337` |
| database | `m4a2-evidence/m4a2-canary.sqlite3` |
| task | identical to attempt 1, verbatim (`m4a2-evidence/run-request.json`) |
| binding / instance | `bind:af519213-…` / `inst:af519213-…` |
| profile | `:agent/project-develop`, caps `#{edit list read search stat}` |
| orientation digest | `sha256:347502fb9bb66121b910dcfb1586625e9411ba25b37745d03279d95b9b98a285` |
| budget | 60, **never extended**, ended at turn 50 |
| provider / model | `:openai` adapter → local router → `fireworks.kimi-k2p7-code`, ONE epoch `46159545-…`, never switched |
| verification env | `js1-ve/v1:bab68fdf…d82a861` (bwrap) |
| terminal | **completed**, branch B1 `done`, `2026-08-29T21:31:33.809Z` |

## 6. Recovery — this time after a committed mutation

Two real SIGKILLs, each at a safe point (0 pending evaluations), each followed
by a new process against the same durable DB and target and a resume of the
SAME run through `POST /v1/runs/{id}/resume`.

| | Cycle 1 | Cycle 2 |
| --- | --- | --- |
| interrupted after turn | 16 | 27 |
| committed mutation receipts in history | **3** | **3** |
| evaluations replayed | 10 | 18 |
| receipts before → after | 18 → 18 | 24 → 24 |
| evals before → after | 16 → 16 | 27 → 27 |
| completions before → after | 16 → 16 | 27 → 27 |
| edit outcomes before → after | 3 → 3 | 3 → 3 |
| target digest across replay | `47a9dc70…` unchanged | `47a9dc70…` unchanged |
| target **mtime** across replay | 1788038367 unchanged | 1788038367 unchanged |
| durable budget | 60 → 60 | 60 → 60 |
| binding / instance / spec / runtime / orientation digest | all identical | all identical |

**This is the evidence attempt 1 could not produce.** Both of attempt 1's
crashes fell before the first committed edit, so its zero-actuation result
covered observations only. Here the replayed history CONTAINS three mutation
receipts, and replay consumed them without writing: the counters did not move
and the file's mtime did not move, which is the strong form — not even a
rewrite with identical bytes.

SCI context lifecycle, now durable (§5B):

```
allocated      1a64e5ed-2310-4633-b819-5b51c1711098
reconstructed  31ebf67d-9cba-480e-91ae-5ab780fb2b43  supersedes 1a64e5ed…  replayed 10
reconstructed  ff440987-3f53-4267-abfd-ed02db6b3be0  supersedes 31ebf67d…  replayed 18
```

## 7. Persistent SCI helper — exercised and survived

The agent defined `truncate-middle` as a durable `def` in the bounded context
(eval seq 17) and referenced it in **37 evaluations**. The second SIGKILL fell
AFTER it existed, and the helper was reconstructed from durable history into
the fresh context `ff440987-…` and called successfully on the very next turns
(29–33, all `true`). This is the helper-reconstruction observation §15 asks
for, which neither attempt 1 nor cycle 1 could supply.

## 8. Verification — both gates, real

One `done`, one verification pair, both green:

| Gate | Green | Exit | Duration | Input coordinate |
| --- | --- | --- | --- | --- |
| focused | true | 0 | 3 338 ms | `sha256:ec59d43fd8554e2adf8…` |
| closure | true | 0 | 343 452 ms | `sha256:ec59d43fd8554e2adf8…` |

Closure stdout: `Ran 1585 tests. 6254 assertions passed, 0 failures, 0 errors.`

Both ran in the controller-owned bwrap environment over a private copy of the
root, with RFC-012 envelopes. The model selected neither.

## 9. Target integrity

Controller `d035645`, zero tracked modifications, zero untracked files.

Target diff is exactly the two files the receipts name:

```
 src/samizdat/util.clj       | 11 +++++++----
 test/samizdat/util_test.clj | 14 +++++++++++++-
```

The source change is surgical and preserves every unrelated definition —
`sh-quote` and `generation-cache`, the two functions attempt 1 destroyed, are
untouched:

```clojure
     (let [marker " … "
-          marker-len (count marker)
-          head-len (quot (- max-len marker-len) 2)
-          tail-len (- max-len marker-len head-len)]
-      (str (subs s 0 head-len) marker (subs s (- (count s) tail-len))))))
+          marker-len (count marker)]
+      (if (< max-len marker-len)
+        (subs s 0 max-len)
+        (let [usable-len (- max-len marker-len)
+              head-len (quot usable-len 2)
+              tail-len (- usable-len head-len)]
+          (str (subs s 0 head-len) marker (subs s (- (count s) tail-len))))))))
```

Final on-disk digests equal the last outcome receipts:

```
47a9dc70910f34ec6495e1dc3243c6dfe906c6034ec8911f385c1c1f0538b5d5  src/samizdat/util.clj
993111148964d83823981e678502c3057f4284f6df746b8cc866e9ccf650e174  test/samizdat/util_test.clj
```

## 10. Attempt 1 vs attempt 2

| | Attempt 1 | Attempt 2 |
| --- | --- | --- |
| turns | 120 (60→90→120, extended twice) | **50** of 60, never extended |
| evaluations | 94 (59 completed, 35 failed) | 49 (39 completed, 10 failed) |
| failed evals | 35 (37%) | 10 (20%) |
| semantic operations | 78 | 12 |
| repeated identical observations | read util.clj ×29, util_test ×23 | ×3 and ×2 |
| first committed mutation | turn ~28 | **turn 4** |
| anchored (surgical) mutations | 0 — the form did not exist | 3 of 3 |
| whole-file rewrites of an existing file | yes — destroyed `sh-quote`, `generation-cache` | **0** |
| source diff | 86 lines changed, file shrank 3839→1975 B | 11 lines changed |
| persistent SCI helpers defined | **0** | 1, referenced in 37 evals |
| helper survived reconstruction | n/a | **yes**, called on turns 29–33 |
| `done` attempts | 4 | **1** |
| focused verifier | 4 RED, 0 GREEN | 1 GREEN |
| closure verifier | did not exist | 1 GREEN (1585 tests) |
| SIGKILL relative to first mutation | both **before** | both **after** |
| replayed mutation receipts | 0 | 3 |
| provider errors / switches | 5 upstream 500s, 2 model transitions | **0 / 0** |
| operator interventions | 3 (1 message, 2 review) + 2 budget extensions | **1 review**, no extension |
| final status | FAIL `:model-behavior` | **PASS** |

**This comparison is evidence, not statistical proof.** n=1 on each side, and
attempt 2 also changed model (`fireworks.kimi-k2p7-code` throughout) and ran on
a stable provider path. No causal claim is made that the hardening produced the
outcome; what the record shows is that the specific failure modes attempt 1
exhibited did not recur, and that the surfaces built to prevent them were the
ones used — three of three mutations took the anchored form that did not exist
before, and the closure gate that did not exist before ran and passed.

## 11. Operator interventions

| id | kind | issued by | applied at turn | substance |
| --- | --- | --- | --- | --- |
| 1,2,3,4 | message | `watch` | 13, 21, 22, 34 | the harness's own supervisor |
| 5 | review | `m4a2-operator` | 49 | stop re-validating in the evaluator, call `done` and read the verifier |

One operator intervention. It supplied no authority and solved nothing: the
edits were already committed and correct at turn 16. Two operator process
interruptions (SIGKILL) are recorded in §6. No manual target edit, no widened
capability, no altered evaluator history, no bypassed verification, no budget
extension.

## 12. Nonclaims

- n=1 either side; no causal claim from the comparison.
- Nothing is established about any one model's general capability.
- No claim of autonomous long-horizon reliability: one operator review nudge
  was issued at turn 49, and the agent had spent turns 29–49 re-validating a
  helper one assertion per turn without shipping.
- Attempt 2's `ops/eval` is 0.24, LOWER than attempt 1's 0.83, and it recorded
  **zero multi-operation evaluations**. The compound-observation leverage
  attempt 1 showed did not recur; this agent front-loaded its reads and then
  computed locally against a persistent helper instead. Lower redundancy, not
  higher batching.
- The §4 repeated-unchanged-observation finding did not fire in this run: only
  12 semantic operations were performed and no coordinate reached the
  threshold. The mechanism is proven by its unit tests, not by this canary.
- `project/replace` does not exist under that name; see the §2 deviation.

**M4 ATTEMPT 2: PASS. STOP FOR REVIEW.**
