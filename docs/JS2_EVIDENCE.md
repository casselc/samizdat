# JS2 — Bounded Project Execution + Real Development Canary

**Result: JS2: PASS.**

A bounded model was given one genuinely new authority — `project/run`, arbitrary
project execution inside a controller-selected isolated environment — and
carried a real, previously-unsolved Samizdat defect to trusted terminal
completion with it: model `done` → controller focused verifier GREEN →
controller closure verifier GREEN over the project's whole suite → terminal
accepted completion, in **8 of 60 turns**, on one provider and one model, with a
real SIGKILL **after both a committed mutation and a completed execution** and
exact recovery either side of it.

The JS1 M4 records are untouched. `js1-m4-attempt2-effcf7d @ f811ea7` and
`js1-m4-current-effcf7d @ 0ad7070` are unmoved, unrebased, and not superseded;
both journals still digest to what those records say (`0ed52307…`,
`fd67ab0a…`). Attempt 2's evidence files are **not on this branch** — JS2
branched from the attempt-2 *executable* oracle `d035645`, and `f811ea7` comes
after it — so `git show f811ea7:docs/JS1_M4_ATTEMPT_2_EVIDENCE.md` is where
this document's comparisons are drawn from.

---

## 1. Coordinates

| Coordinate | Value |
| --- | --- |
| JS2 executable oracle | `26db106f6147f7a93375f891ce7edcd02965448d` |
| JS2 evidence oracle | recorded by the commit containing this file |
| JS2 Jolt | `c8d9181e23cc37aa91a38fdcbd01c93917b1be50` |
| JS2 Jolt base (frozen bounded runtime) | `f8899905d98a0abdcc6b4ae61dfd5c8bdb9c7277` |
| SCI | `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53` |
| Guest image | `sha256:c5329b4b7ecb1ac816cb86c1f6e7f57737d5240a2306be84847c8279e80ad984` |
| Upstream base (re-verified, unmoved) | `effcf7dbf439bd3baa2718bc3e780f2031ecae59` |
| Branched from (M4 attempt-2 executable oracle) | `d03564521b3bdf0733e42b13e7e89c2563d2be19` |
| M4 attempt-2 evidence oracle (frozen) | `f811ea7f3d025b6b376722082656bf69e3be3151` |
| M4 attempt-1 evidence oracle (frozen) | `0ad7070368ae79ebbbba95a998f5ffda4b551dd9` |
| M3 executable oracle | `b1d69867c1d02d34f9130309ae55c9baffc55c29` |

Upstream had not moved, so JS2 branches from the frozen M4 attempt-2 executable
coordinate. No forward-port. No M4 branch was rebased.

### Commits

| Commit | What |
| --- | --- |
| `b5b2f7a` | §3A conservative observation invalidation, §3B closure coverage |
| `7035476` | `:project/run`, `:agent/project-execute`, the execution environment, `bin/js2` |
| `7b22a73` | the host open-file coupling, found by canary attempt 1 |
| `774d2ba` | the launchers that lowered the limit they meant to raise |
| `26db106` | the orientation teaching a command the guest does not have, found by attempt 2 |

---

## 2. The authority, and why it is new

M4's anchored replacement was a *narrower way to spend* authority `project/edit`
already held, so it became an arity of an existing operation. Execution is not
that. `:project/run` is its own capability under its own profile
`:agent/project-execute` in a new Jolt runtime, and the two profiles that
existed before are byte-for-byte what they were:

```
:agent/project-read     #{read list search stat}                 unchanged
:agent/project-develop  #{read list search stat edit}            unchanged — NO run
:agent/project-execute  #{read list search stat edit run}        new
```

A develop binding does not gain execution because this profile exists, and the
runtime's closed maximum refuses it a second time if the controller's own table
ever lies. The RuntimeCoordinate moves with it — `js2-rt/v1:`, over a new Jolt
source *and* a capability catalog M4's coordinate did not name at all — so a JS1
binding and a JS2 binding cannot look like each other and a resume across them
fails closed.

**The model chooses the argv and almost nothing else.** The request is
structured data: a non-empty vector of bounded non-blank strings, never a shell
line, so there is no quoting rule to get wrong. The option set is **closed** —
`:cwd` (relative, non-escaping) and `:timeout-ms` (narrowing only) — and every
controller decision a model might try to make (image, network, mounts,
environment, resource limits, identity, host cwd, cleanup, provider) is refused
*by name* rather than ignored. There is deliberately no executable denylist:
`project/run` **is** the arbitrary-code authority, and pretending a character
set were the boundary would be pretending the wrong thing holds the line.

**The evaluation ceiling follows the effective capability set.** Thirty seconds
bounds computation inside SCI; a `project/run` is a controller-owned execution
in another machine, already bounded by its own pinned wall clock, and a real
suite takes minutes. It is still a mechanism bound the observed tier cannot
raise: a binding without `:project/run` keeps the thirty seconds exactly, and an
execute binding attenuated down to reads gets them back.

---

## 3. The exact gate — GREEN

`bin/js2 test` from the tracked-clean controller at `26db106f`, exit 0:

```
authority, provenance, lease and surface closure:      53 tests,  279 assertions, 0 failures, 0 errors
pinned bounded evaluator:                              50 tests,  421 assertions, 0 failures, 0 errors
execution environments (machine-backed):               91 tests,  761 assertions, 0 failures, 0 errors
ordinary suite:                                      1630 tests, 6678 assertions, 0 failures, 0 errors
```

`bin/js2` is a **new** gate, not a widened old one. Rewriting `bin/js1-m3` to
accept this runtime would make every historical M1–M4 record claim to have run
against a runtime that did not exist when they ran. It adds one pin the js1
lanes had no equivalent of: the guest image is checked **by digest**, because
that is what a model's argv actually runs inside.

Gate items: **A/B** the profile maxima above, plus attenuation, over-request and
over-profile refusals. **C** `project/run` advertised only when authorized,
never as a top-level tool, with a narrowed binding narrowing every sentence.
**D** request validation, including the closed option set. **E** isolation, for
real, in real machines. **F** the canonical result. **G** intent before,
outcome after. **H** replay. **I** the TurnLease. **J** edit/run interaction.
**K** `done` independence. **L** the M4 hardening regressions. **M** the
ordinary suite.

The Jolt side has its own gate: `make scievaluator` — sandbox, authority
conformance, SCI functional, interrupt nesting — green, with the JS2 assertions
added to the authority lane.

---

## 4. The pre-flight that mattered, again

M4 attempt 2 found the closure verifier RED on an untouched target and fixed it
before the canary. JS2's own new tests reproduced the same class of bug in the
same place.

The first JS2 closure pre-flight was **RED: 2 failures**, both from a new test
asserting that a low open-file limit produces `:host-fd-limit`. True on the
gate's host; false inside the closure verifier's sandbox, where there is no
machine manager and `:no-manager` is refused first and correctly. A refusal
*order* is not what that test is about, and it now holds the substrate's earlier
refusals true so the assertion means the same thing on a developer's machine, on
the gate's host, and inside the sandbox that runs the project's own suite as its
closure gate.

Clean-target baseline after the fix: **1630 tests / 6445 assertions, GREEN,
admissible**.

---

## 5. Controller and target

| Role | Path | SHA | End state |
| --- | --- | --- | --- |
| Controller (immutable) | `samizdat-controller-js2` | `26db106` | **0 tracked modifications** |
| Jolt (immutable) | `jolt-js2` | `c8d9181e` | **0 tracked modifications** |
| Target (disposable) | `samizdat-canary-target-js2` | `26db106` | 2 tracked files modified, both receipt-explained |

The target's only operator-placed file is `.samizdat/config.edn`, which
`project/edit` refuses; it is byte-identical at the end of the run.

---

## 6. The task

**The same task M4 attempt 1 failed and attempt 2 passed, byte-identical**
(`run-request.json` digests to `64e6e852…` in both records): make
`samizdat.util/truncate-middle` total.

It is real and unsolved at the JS2 base — both task files are byte-identical to
what M4 ran against (`9dbc01d2…`, `6263ca92…`) — and holding it constant is the
only way JS2's REPL-leverage question has a controlled comparison: same task,
same model, same budget, same provider path, with the capability and its
orientation as the difference. The cost is stated plainly: JS2's difficulty is
calibrated to M4's, and it is n=1 either side.

The §20 "validate with `project/run` before `done`" nudge lives in the
**orientation**, not the task, so the task text stays byte-identical.

---

## 7. The run

| Field | Value |
| --- | --- |
| run-id | `495fa9cb-b2c2-4a29-8c6d-bce497fd9efc` |
| database | `js2-evidence/js2-canary.sqlite3` |
| profile | `:agent/project-execute`, caps `#{edit list read run search stat}` |
| evaluation ceiling | 660000 ms (derived from the capability set) |
| orientation digest | `sha256:3b4b860eb2312f62646964d9cc1acae8e52bf15bf9275809cba3273528047fe8` |
| budget | 60, **never extended**, ended at turn **8** |
| provider / model | `:openai` adapter → local router → `fireworks.kimi-k2p7-code`, ONE epoch, never switched, **0 provider errors** |
| development environment | SmolVM ProjectExecutionEnvironment, `sha256:978648e5…` |
| acceptance environment | bwrap VerificationEnvironment, `js1-ve/v1:bab68fdf…` |
| terminal | **completed**, branch B1 `done`, `2026-08-30T04:16:11.481Z` |

**The two environments are deliberately different substrates.** Development ran
in an ephemeral virtual machine; acceptance ran in a bubblewrap sandbox over a
private copy. They cannot be confused, and neither is selectable by the model.

### What the model did

```
1  read  src/samizdat/util.clj
2  read  test/samizdat/util_test.clj
3  stat  src/samizdat/util.clj
4  edit  src/samizdat/util.clj          ← the fix
5  stat  test/samizdat/util_test.clj
6  edit  test/samizdat/util_test.clj    ← the assertions
7  run   bb --classpath src:test -e "…run samizdat.util-test and exit…"   exit 0
8  done                                 ← focused GREEN, closure GREEN
```

Turn 7 is the whole point of the milestone:

```clojure
(let [r (project/run ["bb" "--classpath" "src:test" "-e" "…"])]
  (if (= 0 (:exit r)) … …))
```

One evaluation ran the project's real test namespace in an isolated machine,
read the structured result, branched on it, and returned a conclusion — 2848 ms,
`Ran 1 tests containing 10 assertions. 0 failures, 0 errors.` The model then
called `done`, and the controller verified independently.

---

## 8. Isolation — the central claim

Ten adversarial probes, in real machines, at the frozen oracle
(`artifacts/js2-canary/probes/`), plus 91 machine-backed tests in the gate.
**The authoritative tree's input coordinate was byte-identical after every
one.**

| Attempt | Inside the workspace | On the authoritative tree |
| --- | --- | --- |
| modify an existing file | succeeded | unchanged |
| create a file | succeeded | unchanged |
| delete a file | succeeded | unchanged |
| chmod + rename | succeeded | unchanged |
| read the environment | only `HOME PATH LANG TMPDIR PWD SHLVL OLDPWD` — the constructed guest environment | — |
| read host paths | `/home/…/.ssh/id_rsa` absent, `/etc/shadow` permission denied | — |
| network | DNS fails, ping refused, loopback only | — |
| unmask the raw `/input` export | empty | — |
| privilege | `uid=1000`, no setuid binaries, `su` refuses | — |
| spawn a daemon | ran | **no surviving host process** |

Every write **succeeded**, which is the half that is easy to forget: a
development environment a compiler cannot write in is not one. They succeeded
and then vanished with the machine.

---

## 9. The timeout canary (§23)

A deliberately long command under a 15 s request:

```
status                 :timeout
exit present?          false          ← a deadline is not a program that chose a number
disposition            :hard-cleaned
cleanup                stop vm-8354941f / delete vm-8354941f, clean? true
poisoned after?        false          ← lifted only by a clean sweep
surviving children     none
next invocation        :completed, fresh environment
```

**The sweep is not decorative.** On the first timeout probe the manager's own
table still held the machine *after* the process tree had been reaped — bbagent's
measured A3a finding reproduced here. A host deadline bounds host *waiting*; the
machine is what has to end, and asking the manager and being told the table is
empty is a different claim from believing it.

---

## 10. Recovery — after a mutation AND an execution

One real SIGKILL, at a safe point (0 pending evaluations) with **2 committed
mutation receipts and 1 completed execution receipt** in durable history, then a
new process against the same DB and target and a resume of the SAME run.

| | Value |
| --- | --- |
| interrupted after turn | 7 |
| resumed at turn | 8 |
| mutation receipts in history | **2** |
| execution receipts in history | **1** |
| evaluations replayed | 7 |
| reconstruction | 134 ms |
| evals / edit outcomes / run outcomes | 7→7, 2→2, 1→1 |
| **execution-provider invocations, before → after replay** | **0 → 0** |
| machine manager table, before → after replay | `No machines found` → `No machines found` |
| target digest across replay | `576d37d9…` unchanged |
| target **mtime** across replay | 1788062785 unchanged |
| binding / instance / spec / runtime / context-spec / orientation digest | all identical |
| durable budget | 60 → 60 |

SCI context lifecycle:

```
allocated      2e8a22ca-f7e0-4037-9e1f-5b84d9b9c0eb
reconstructed  c5cce59e-a1cd-4b2c-a7df-8529ee27c986  supersedes 2e8a22ca…  replayed 7
```

**This is the evidence JS2 exists to produce.** M4 proved a replayed *mutation*
actuates nothing. Here the replayed history also contains an *execution*, and
the replay consumed it and launched no environment: the counter did not move,
the manager's table stayed empty, and the target's mtime did not move — not even
a rewrite with identical bytes.

The counter claim is stated in its honest form. The execution provider's
invocation counter is **process-local**, so after a restart it begins at zero;
"replay launched nothing" is the claim that it is *still* zero once a history
full of executions has been replayed into a fresh context. It is proven twice:
once live, by the resumed run, and once offline
(`artifacts/js2-canary/replay-proof.txt`) against a copy of the real durable
history, so the canary journal itself stays pristine.

---

## 11. Closure coverage (§3B, §24)

| | tests | assertions | failures | errors |
| --- | --- | --- | --- | --- |
| clean-target baseline (isolated) | 1630 | 6445 | 0 | 0 |
| final closure (isolated) | 1630 | **6451** | 0 | 0 |
| **delta** | 0 | **+6** | 0 | 0 |
| host-side ordinary gate | 1630 | 6678 | 0 | 0 |

The delta is the six assertions the model added. `decreased? false`,
`same-suite? true`, admissible, no warnings.

The **227-assertion gap** between the host suite and the isolated verifier is
the thing M4 could see and could not say. The environments differ by design — a
different toolchain, a different filesystem, host-dependent tests that skip
inside a sandbox — and M4 had the same class of gap (6373 vs 6254) recorded only
as two numbers in prose. It is now a computed field beside the suite, verifier
and input coordinates that make a count mean anything.

There is deliberately **no parity requirement and no assertion-count theorem**.
A coverage *decrease* warns and never refuses: deleting a test is a legitimate
change and this layer cannot tell a legitimate one from a regression. What it
*does* refuse is a closure result that has stopped being evidence — an
unreadable summary, a summary reporting zero tests, a summary contradicting its
own verdict — and §14 below records that arriving from a direction nobody
designed it for.

---

## 12. Trajectory, and the comparison M4 asked for

| | M4 attempt 2 | JS2 |
| --- | --- | --- |
| turns | 50 | **8** |
| evals | 49 | 7 |
| failed evals | 10 | **0** |
| semantic operations | 12 | 7 |
| **ops / eval** | **0.24** | **1.00** |
| ops / model turn | 0.24 | 0.88 |
| multi-operation evals | 0 | **0** |
| repeated observation signatures | 4 | **0** |
| anchored mutations | 3 | 2 |
| persistent helpers | 1 | 0 |
| `project/run` calls | — | 1 |
| done attempts | 1 | 1 |
| operator interventions | 1 | **0** |
| budget extensions | 0 | 0 |

Same task, same model, same budget, same provider path. **No causal claim is
made from one run each.**

Other §28 metrics: `project/run` per model turn 0.12; the one execution-bearing
eval had shape `run+analyze+branch`; **one** model roundtrip between the last
edit and the test evidence about it; zero repeated equivalent executions; wall
clock 616 s, of which execution was 2.8 s — **0.46%**. Inference dominates by
two orders of magnitude, which is the honest framing of what a 3-second machine
boot costs.

---

## 13. The three aborted attempts

All preserved, all digested, none hidden.

**Attempt 1 — `:execution-provider`, 16 turns.** Every `project/run` failed
`EMFILE` inside the guest while the prelude removed the project's `.git` (7684
loose objects). The controller had been launched with `ulimit -n 4096`, and that
**host** limit reached inside the guest through whatever serves the read-only
mount — while the guest's own limits were identical either way (1024 soft, 4096
hard). The model, correctly reading a failed environment as a failed run, saw
good work reported as broken.

The number was not the defect; the coupling was. An environment that behaves
differently depending on how the harness was launched is not isolated, it is
coincidentally working, and it failed in the worst available shape: as evidence
about the model's code. Fixed by a **pinned open-file floor** on the manager
spawn (part of `:executor/limits`, and therefore of the environment's identity),
a catalogued `:host-fd-limit` refusal for a host that cannot grant it, and
launchers that raise the **soft** limit only and only upward — `ulimit -n N`
sets the hard limit too, and a lowered hard limit is irreversible, which is how
three lines written to raise a limit were lowering one.

**Attempt 2 — `:prompt`, 16 turns.** The orientation told the model the whole
suite was `["bb" "-M:test"]`. Babashka has no `-M` alias flag; it reads `-M:test`
as a filename. Worse than the flag: the whole suite cannot run in that
environment at all, because the guest carries the toolchain and not the
project's *resolved dependencies*. The model spent three turns on a command that
could never work, on the authority of trusted controller prose. Fixed by an
orientation in which every named command has been run in the guest and seen to
work.

**Attempt 3 — `:model-behavior`, 60 turns.** A wholly valid production run in
which the model **never called `project/run` once**, corrupted its own test file
with an unmatched delimiter, and looped on re-reading files until the budget ran
out. Invalid as a JS2 canary under §20; **not** a JS2 failure — §30 is explicit
that a model failing to use `project/run` is not an isolation failure. No code
changed between attempt 3 and the passing run, and **no operator intervention
was needed**: the same controller, the same task, the same model, and the same
orientation finished in 8 turns on the next attempt.

That pair is the most sobering number in this document, and it is why §12's
comparison carries the disclaimer it does.

---

## 14. Two defects found in a component JS2 does not use

Both are in the **SmolVM verify** environment, both were found by JS2, and both
are recorded rather than fixed. JS2 verifies through bwrap and therefore
produces no evidence about that provider; changing a component a milestone
cannot measure is how blast radius grows.

1. **The same host coupling.** It composes the same guest command and would hit
   the same `EMFILE` on a repository with a large `.git` under a low host limit.
2. **Its closure argv cannot work.** `closure-argv` is `["bb" "-M:test"]` — no
   `-M` flag, and even corrected, no resolved dependencies in the guest. That
   provider has no working closure gate for a project like this one.

The interesting half is that the system already handles the second one
correctly, and not by design. A closure verifier that cannot load the code emits
output with no parseable summary, and §3B refuses exactly that:
`:closure-summary-unparseable`, `done` refused rather than accepted on a verdict
nobody could read. **A gate that cannot run now fails closed instead of failing
green** — arrived at from a direction the coverage signature was not designed
from.

---

## 15. What this does not establish

- **n=1 either side.** The M4 comparison is evidence, not proof, and no causal
  claim is made for the capability.
- **Nothing about any model's general capability.** Attempt 3 is the same model,
  task and controller finishing zero percent of the work in 60 turns.
- **`project/run` was exercised ONCE in the canary.** The isolation rests on 91
  machine-backed tests and ten adversarial probes; the canary's own execution
  evidence is a single successful invocation.
- **Multi-operation evals were ZERO again.** `ops/eval` rose from 0.24 to 1.00,
  but no single evaluation combined two semantic operations. The
  compound-observation leverage attempt 1 showed has still not recurred.
- **No persistent SCI helper was defined**, so this run adds nothing to M4's
  helper-reconstruction evidence.
- **The guest cannot run the project's whole suite.** `project/run`'s
  demonstrated value here is a focused namespace, not a full toolchain.
- **One SIGKILL cycle**, at turn 7 of 8.
- **The execution counter is process-local**, and the replay claim is stated in
  the form that fact supports.

---

## 16. Preserved

```
js2-evidence/js2-canary.sqlite3
sha256:af2963f20c4403c90e392e7f9b4d752e9041cdcf87db700aaaa4d355d4576f14
```

`.gitignore` excludes `*.sqlite3` and that convention was not overridden.
Everything in `artifacts/js2-canary/` is derived from that file. Also preserved
and untracked: the three aborted attempts' journals (digested in
`artifacts/js2-canary/aborted-attempts.sha256`), the 148 MB pinned guest image,
the controller checkout at `26db106` (tracked-clean), and the target worktree
exactly as the run left it.

---

## 17. Out of scope, and stayed out

Network-enabled `project/run`, host execution fallback, worker pooling,
long-lived execution VMs, writable host mounts, model-selected mounts or
resource policy, package-install authority, multi-agent bounded execution,
shared SCI, Cedar, enterprise policy projection, MCP/A2A/ACP, a Jolt upstream
PR, bb4t convergence, a new memory architecture, a new scheduler, and M5 — none
were implemented. The two things evidence said were needed (§14) are recorded,
not built.

**STOP FOR REVIEW.**
