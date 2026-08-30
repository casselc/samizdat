# JS2 — Current-Upstream Convergence + Concurrent Execution Hardening

**Result: JS2 CONVERGENCE: PASS.**

The M1–M4 + JS2 contract now runs on **current upstream** (`34a21ab`), whose
REPL/session/sandbox architecture is preserved rather than replaced; the
cross-run timeout-cleanup defect is fixed and proved fixed with a real
concurrent machine probe; the full JS2 gate is green; and a real
`:agent/project-execute` run on the converged controller reached trusted
terminal completion through a restart.

The frozen JS2 experiment is untouched.

---

## 1. Coordinates

| Coordinate | Value |
| --- | --- |
| **Convergence branch** | `js2-converge-34a21ab` |
| **Convergence executable oracle** | `9391ce59b1771c22885742bd208f97dbe0a55d30` |
| Convergence evidence oracle | recorded by the commit containing this file |
| **Current upstream base** | `34a21ab1fb1063ad3eac7905f98a37ad5635427f` |
| Frozen JS2 evidence oracle (untouched) | `8edc570b6db6e178244b2c000c60ef8644f47690` |
| Frozen JS2 executable oracle (untouched) | `26db106f6147f7a93375f891ce7edcd02965448d` |
| **JS2 Jolt oracle (unchanged, reused)** | `c8d9181e23cc37aa91a38fdcbd01c93917b1be50` |
| SCI | `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53` |
| Guest image | `sha256:c5329b4b7ecb1ac816cb86c1f6e7f57737d5240a2306be84847c8279e80ad984` |
| RuntimeCoordinate | `js2-rt/v1:` over the JS2 Jolt source and the capability catalog |
| M4 attempt-2 oracles (untouched) | `d035645` / `f811ea7` |

### Commits on the convergence branch

| Commit | What |
| --- | --- |
| `32b30f6` | the forward port: M1–M4 + JS2 contract onto current upstream, and invocation-owned cleanup |
| `f789b69` | the src scanner made independent of what ran before it |
| `2445b26` | the suite made hermetic enough to be its own closure gate, again |
| `64b8fac` | the execution provider resolved at load, not at the call that needs it |
| `9391ce5` | no supervisor stream over a bounded run |

### The historical claim this record corrects

The frozen JS2 evidence records `upstream-base effcf7d…` with
`upstream-moved? false`. That was true when JS2 branched and is **not** a
reliable description of upstream at JS2 finalization time: upstream has since
landed `34a21ab` ("adopt vis; make the REPL a session and roles their own
world; confine eval to a sandboxed project image"), 163 files and ~11,800
insertions.

The frozen record is **not edited**. What it says is corrected here instead:

> **Frozen JS2 PASS is a PASS on the `effcf7d`-derived experimental lineage.
> It had not established current-upstream convergence. This task performs
> that convergence.**

---

## 2. The seam map

| Upstream concept | Bounded/JS2 concept | Integration decision |
| --- | --- | --- |
| `repl.image` — project-rooted REPL in its own process | bounded persistent SCI | **Retained, and never reached by a bounded call.** The bounded question is asked *before* any image routing. |
| `repl.route` — per-role choice of which image an eval reaches | bounded evaluator dispatch | Retained for the ordinary lane. A bounded eval reaches no image: routing it through one would hand a bounded branch the ordinary lane's authority through a config table. |
| `security.sandbox` — OS confinement for the project image | JS2 SmolVM hard boundary | Retained. Distinct layers: it confines the ORDINARY REPL; the SmolVM machine is where a MODEL's argv runs. `:auto` resolves to `:none` on Linux by upstream's own decision (see §7). |
| `agent.source` — syntax vetting and repair | bounded eval source | **Reused, below the authority boundary.** A bounded eval passes the same gate; it reads and repairs the model's own text and grants nothing. The vetted source is what is evaluated *and* recorded, so replay stays exact. |
| `hashline` / `patch` — anchored mutation UX | `(project/edit path digest old new)` | Bounded mutation stays a semantic operation with its digest anchor, one-occurrence rule, confinement, receipt and zero-write replay. `patch` is an ordinary top-level tool and is not bounded authority. |
| `tools/plan`, `state/planned?`, `:repl-needs-a-plan` | bounded surface (eval/doc/complete/done) | **Bounded lane exempted, in phases.edn.** See §3 — it would otherwise withhold the agent permanently. |
| `agent.roles` — per-role tool catalogue | authority-derived bounded surface | Retained. A bounded branch carries no `:role`, and a branch with no role is unrestricted, so the bounded surface stays derived from its ContextSpec alone. |
| `agent.oversight` — parallel supervisor stream | controller-owned progressive `done` | **Not started over a bounded run.** See §4. |
| `tools/retrying` — transient-failure retry | bounded `done` (a ControlEvent) | Bounded `done` now dispatches *under* the retry wrapper rather than beside it, so it gets the same handling as every other tool. `done` is not on `:retry-safe-tools` and must not be. |
| `VerificationEnvironment` (bwrap / SmolVM) | acceptance evidence | Forward-ported unchanged. The smoke verifies through bwrap. |
| `ProjectExecutionEnvironment` (SmolVM) | development evidence | Forward-ported, **with the cleanup correction** of §5. |

---

## 3. The convergence break, and it was total

Upstream refuses `eval` until a branch has named the files it intends to
change (`phases.edn :repl-needs-a-plan`). Its own comment is explicit that
this is *not* a blanket eval withhold — "a branch that has said what it is
doing may use the REPL freely" — and in the ordinary lane, with thirty tools
and a `plan` tool to satisfy it, that is exactly right.

In the bounded lane `eval` **is** the surface. Every observation, every
mutation and every execution goes through it, and there is no `plan` tool to
lift the refusal with. The rule would have withheld the agent permanently and
unliftably.

The bounded lane is exempted in `phases.edn`, where the policy lives, with the
reasoning beside it. What the rule wants — a hypothesis before exploration —
is served more strongly there anyway: `done` is refused unless the run's own
edit receipts name a test the controller can verify, and then the whole suite
has to be green too.

Pinned by `surface-test/the-repl-session-contract-does-not-withhold-the-bounded-lane`,
which also asserts the rule still stands for an ordinary unplanned branch.

---

## 4. Three more defects the convergence surfaced

**The execution provider resolved too late** (`64b8fac`). `project/run`
reported "No project execution provider is available" in a process where it
was available: the resolution ran lazily, on a branch worker thread, failed
there, and its exception was swallowed by the fail-closed path. The model was
told its capability was missing, went looking for a `shell` runner it does not
have, and spent its budget on it. It is a static require now — this namespace
already loads only in the bounded lane, so the provider is no more optional
than the sandbox is. A dependency that must be present is better missing at
load than at the one call that needed it.

**The supervisor stream over a bounded run** (`9391ce5`). Upstream opens a
parallel supervisor stream over every run. It is an ordinary role with an
ordinary tool surface; a bounded run has neither. Measured: a smoke that
completed its bounded work in 27 turns carried **63 further turn slots** of
supervisor calls — `shell`, `fetch_turn`, `introspect`, `cells`, `intervene`,
`prompt` — every one refused, and none able to be otherwise. Guarded the way
the same ctx assembly two lines away already guards the REPL session. The
final smoke ran with **one branch**.

**A suite that could not be its own closure gate** (`f789b69`, `2445b26`).
The pre-flight found the target's suite RED with 17 errors inside the
acceptance verifier, none about the target — the same failure mode M4 fixed in
`d035645`, arriving again through upstream's new work. Thirteen were
`Cannot run program "jolt"`: upstream's project image starts by bare name
against the child's PATH, which the acceptance sandbox does not carry. Four
were `Invalid token: ::timeout` from base-test's source scanner, which depends
on the reader resolving auto-resolved keywords against whatever namespace
happens to be current. Both are now test hygiene: a test that cannot start
what it observes says so, and the scanner reads under an explicit `*ns*` with
a fallback. Neither weakens an assertion.

---

## 5. Concurrent timeout cleanup — the required fix

JS2's cleanup swept **every** ephemeral machine the manager's table held.
Indistinguishable from correct while exactly one execution runs at a time,
which is all the JS2 canary ever did. Cross-run interference the moment a
server has two: run A timing out would stop and delete run B's still-running
machine.

Ownership is now established two ways, in order, and never by assumption:

1. **The manager's own word** — its startup banner names the machine it
   started for this spawn. Not an inference; when present, nothing else is
   consulted.
2. **The set difference**, as a bounded fallback — a baseline of the table is
   read immediately *before* every spawn, so everything in it belongs to
   somebody else and is never a candidate. Exactly one new machine is
   accepted.

Two candidates is a coincidence with two candidates, not ownership. Cleanup
then does **nothing**, reports `:cleanup/clean? false` with the candidates
named, and the poison is never lifted — the lane fails closed with the
surviving state visible. A provider that refuses further executions is a
problem an operator can see; a run that deleted another run's machine is a
problem nobody sees until the other run reports nonsense.

`:cleanup/clean?` means *none of ours remains*, not *the table is empty*.
Each result now names its own machine (`:machine`), which is what makes the
claim checkable.

`--name` is not available: the manager ignores it in foreground mode, which is
what an ephemeral run uses. The banner is the strongest ownership signal the
measured manager offers.

### The concurrency probe (§22), on the frozen converged controller

```
manager table BEFORE: "No machines found"
B is running; starting A with a 12s deadline

A  invocation: 2   machine: vm-5c84bc5c
A  status: :timeout   disposition: :hard-cleaned
A  cleanup owned:  ["vm-5c84bc5c"]
A  cleanup acted:  ["stop vm-5c84bc5c" "delete vm-5c84bc5c"]
A  cleanup clean?: true
A  poisoned after: false

B  invocation: 1   machine: vm-c4568dd5
B  status: :completed   exit: 0   disposition: :terminated
B  stdout: "B-COMPLETED"

A machine == B machine?                    false
B's machine among A's cleanup targets?     false
manager table AFTER: "No machines found"
A's machine still in the table?            false
```

Also pinned by three machine-backed tests in its own gate lane
(`bin/js2 concurrency`): the timeout/ownership case above, two successful
concurrent executions that do not interfere, and the pure ownership rule
including the ambiguous refusal.

**No global serialization was introduced.** Executions run concurrently; only
the cleanup is scoped.

---

## 6. The gate — GREEN

`bin/js2 test` from the tracked-clean controller at `9391ce5`, exit 0:

```
authority, provenance, lease and surface closure:      60 tests,  332 assertions, 0 failures, 0 errors
pinned bounded evaluator:                              52 tests,  428 assertions, 0 failures, 0 errors
execution environments (machine-backed):               94 tests,  798 assertions, 0 failures, 0 errors
concurrent execution ownership (machine-backed):        3 tests,   37 assertions, 0 failures, 0 errors
ordinary suite:                                      1860 tests, 7978 assertions, 4 failures, 0 errors
```

It pins Samizdat cleanliness, the JS2 Jolt SHA and cleanliness, the SCI SHA,
version and cleanliness, Chez, and the guest image **by digest**. The
historical `bin/js1-*` gates are unchanged.

---

## 7. The ordinary suite is measured against upstream, not against green

**Current upstream's own suite is not green on Linux, at the commit this
converged from.** `security.sandbox/backend-for` resolves to `:none` on
anything that is not macOS — deliberately, by its own docstring: "shipping an
unverified bubblewrap invocation would be a sandbox that reads as protection
without having been shown to be one". Four of upstream's tests assert the
sandboxed behaviour that backend would provide.

Pristine `34a21ab`, this host: **1658 tests / 6532 assertions / 4 failures**.

| | tests | assertions | failures | errors |
| --- | --- | --- | --- | --- |
| pristine upstream `34a21ab` | 1658 | 6532 | 4 | 0 |
| converged `9391ce5` | 1860 | 7978 | **4** | **0** |

The same four, and no others: `eval-resolves-relative-paths-against-the-run-root`,
`eval-cannot-read-harness-source-from-a-project-run`, and two assertions in
`a-sandboxed-image-works-on-the-project-and-cannot-leave-it`. `bin/js2` names
them explicitly and **fails the lane on a fifth**. Implementing the Linux
sandbox backend is a different piece of work from converging JS2, and it is
not attempted here.

---

## 8. The convergence smoke

| Field | Value |
| --- | --- |
| run-id | `b0dc54b5-706b-45da-b9de-1d4170346768` |
| controller | `samizdat-controller-conv` @ `9391ce5`, 0 tracked modifications |
| target | `samizdat-target-conv` @ `9391ce5`, disposable |
| profile | `:agent/project-execute`, caps `#{edit list read run search stat}` |
| evaluation ceiling | 660000 ms (derived from the capability set) |
| orientation digest | `sha256:3b4b860eb2312f62646964d9cc1acae8e52bf15bf9275809cba3273528047fe8` |
| budget | 60, never extended, ended at turn **45** |
| provider / model | `:openai` → local router → `fireworks.kimi-k2p7-code`, ONE epoch, 0 provider errors |
| development environment | SmolVM ProjectExecutionEnvironment |
| acceptance environment | bwrap VerificationEnvironment `js1-ve/v1:bab68fdf…` |
| branches | **1** |
| terminal | **completed**, B1 `done`, `2026-08-30T09:06:29.509Z` |

**The task** is the same `truncate-middle` totality defect M4 and JS2 used,
verified still unsolved and byte-identical on the convergence branch
(`9dbc01d2…`, `6263ca92…`). §20 permits a different task; holding it constant
keeps model behaviour from confounding the only question the smoke asks —
*did the contract survive?* — and this smoke is not a comparative experiment.

Trajectory: 45 turns, 44 eval + 1 done, 38 evaluations (25 completed, 13
failed), 34 semantic operations — 12 read, 6 stat, 1 search, 10 edit, **5
project/run** — ops/eval 0.89, 0 multi-operation evals, 5 execution-bearing
evals of which 2 were `run+analyze+branch`, 1–2 model roundtrips between an
edit and its test evidence, 0 operator interventions, 0 budget extensions.

**Verification:** focused GREEN, closure GREEN, one `done` attempt.

---

## 9. Restart and replay (§21)

One real SIGKILL at a safe point (0 pending evaluations) with **4 committed
edit receipts and 1 completed execution receipt** in durable history, then a
new process against the same DB and target and a resume of the same run.

| | Value |
| --- | --- |
| interrupted after turn | 19 |
| resumed at turn | 20 |
| evaluations replayed | 10 |
| reconstruction | 137 ms |
| binding / instance / capabilities / orientation digest | all identical |
| RuntimeCoordinate | `js2-rt/v1:` unchanged |
| SCI context | `0ff58a82…` → **fresh** `c260993c…`, supersedes recorded |
| **execution-provider invocations, before → after replay** | **0 → 0** |
| manager table, before → after replay | `No machines found` → `No machines found` |
| target digests across replay | unchanged |
| target **mtimes** across replay | unchanged |
| durable budget | 60 → 60 |
| work continued | yes, to `done` at turn 45 |

Proved twice: live, by the resumed run, and offline
(`artifacts/js2-convergence/replay-proof.txt`) against a **copy** of the real
durable history, so the smoke's own journal stays pristine. The counter is
process-local, and the claim is stated in the form that fact supports.

---

## 10. Closure coverage (§15)

| | tests | assertions | failures | errors |
| --- | --- | --- | --- | --- |
| clean-target baseline (isolated, converged tree) | 1860 | 7686 | 0 | 0 |
| final closure (isolated) | 1860 | **7700** | 0 | 0 |
| **delta** | 0 | **+14** | 0 | 0 |
| host-side ordinary gate | 1860 | 7978 | 4 (upstream's) | 0 |

`decreased? false`, `same-suite? true`, admissible, no warnings. The signature
still records tests, assertions, failures, errors and the suite, verifier and
input coordinates, and still fails closed on an unreadable summary, zero tests,
or a summary that contradicts its verdict.

---

## 11. The known SmolVM verification-provider defects (§16)

Frozen JS2 recorded two. Their status after convergence:

| Defect | Status |
| --- | --- |
| Host open-file-limit coupling | **Still known.** Not fixed. JS2's `:host/nofile` floor mechanism exists and could be reused, but that provider is not exercised for acceptance here and this task produces no evidence about it. |
| `closure-argv` cannot run the project suite in the guest (`bb -M:test` invalid; no resolved dependencies) | **Still known.** Not fixed. |

**Fail-closed behaviour is preserved and is what makes the second survivable.**
A closure verifier that cannot load the code emits no parseable summary, and
the ClosureCoverageSignature refuses exactly that
(`:closure-summary-unparseable`): `done` is refused rather than accepted on a
verdict nobody could read. A user selecting a broken SmolVM closure verifier
gets a refusal, never a false GREEN.

---

## 12. What this does not establish

- **The four upstream failures are real** and remain. This record shows the
  convergence added none; it does not show upstream's Linux sandbox works,
  because it does not exist yet.
- **One smoke, one restart, one model.** The smoke asks whether the contract
  survived, not how well any model performs; nothing here is a comparative
  result and no trajectory claim is made against JS2's or M4's numbers.
- **Multi-operation evals were ZERO again** — the third run in a row.
- **`project/run` was exercised 5 times in the smoke**, and 3 of the 5
  execution-bearing evals returned the result without branching on it.
- **The concurrency evidence is one A/B pair** plus three tests. It does not
  characterise behaviour at higher concurrency, and no worker pooling exists
  to characterise.
- **The two SmolVM verification-provider defects are unfixed**, and the
  reasoning for leaving them is scope, not evidence that they are harmless.

---

## 13. Preserved

```
js2-converge-evidence/js2-converge.sqlite3
sha256:8487923e19a2264a634b7b62599790e4788a93f9488b87aa79e8bf8d9435fec3
```

`.gitignore` excludes `*.sqlite3`. Everything in `artifacts/js2-convergence/`
derives from that file. Also preserved and untracked: the first smoke attempt's
journal (which found the provider and supervisor defects), the pristine
upstream baseline run, the 148 MB digest-pinned guest image, the controller
checkout at `9391ce5` (tracked-clean), and the target worktree as the run left
it.

The frozen JS2 branches, their journals and the M4 records are untouched,
unrebased and unmoved.

**STOP FOR REVIEW.**
