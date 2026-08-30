# JS2 CONVERGENCE CLOSURE — evidence

**Result: PASS.**

A small correctness pass on top of the JS2 convergence, closing three defects
the convergence record either did not know about or explained wrongly. It
changes no contract, adds no capability, and moves no frozen ref.

| | |
| --- | --- |
| Branch | `js2-converge-closure-34a21ab` |
| Closure executable oracle | `6b8f5cceb723e7f5589103b0617fe1e69e9f242e` |
| Pushed executable oracle | `6b8f5cceb723e7f5589103b0617fe1e69e9f242e` (verified equal) |
| Upstream base | `34a21ab1fb1063ad3eac7905f98a37ad5635427f` |
| Prior convergence oracle | `9391ce59b1771c22885742bd208f97dbe0a55d30` |
| Jolt | `c8d9181e23cc37aa91a38fdcbd01c93917b1be50` (`js2-project-run`, unmoved) |
| SCI | `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53` |
| Chez | `10.4.1` |
| Guest image | `sha256:c5329b4b7ecb1ac816cb86c1f6e7f57737d5240a2306be84847c8279e80ad984` |
| Smoke run | `0e737c73-9d23-430b-bdef-01966c5a7ef2` |
| Supporting records | `artifacts/js2-convergence-closure/`, `artifacts/js2-convergence-closure.edn` |

## What this supersedes, and what it does not

This record **supersedes `docs/JS2_CONVERGENCE_EVIDENCE.md` for closure
purposes**. It does not edit it, and nothing in it is rewritten. That record
describes the bytes that ran at `9391ce5` and remains true about them; the
frozen JS2 experiment (`js2-project-run-effcf7d @ 8edc570`) and every M1–M4
record are likewise untouched. Every frozen branch listed in
`artifacts/js2-convergence-closure.edn` was re-verified on `origin` after the
last push of this milestone and none of them moved.

One claim in the prior record is **withdrawn here**: that the four
ordinary-suite failures were upstream's own Linux-sandbox failures. They were
not. See Finding A.

---

## Finding A — the project image was told the wrong working directory

`repl.image/start!` launched the project image with `:dir` set to the project
root and an environment built as `(secrets/scrub-env (into {} (System/getenv)))`
— a scrubbed copy of the **controller's own** environment. `bin/jolt` exports
`JOLT_PWD=$PWD`, `scrub-env` has no reason to remove it, and the Jolt launcher
prefers `JOLT_PWD` over the process cwd. So the child was handed the
controller's project root by an environment variable that outranked the `:dir`
it was actually started in, and resolved relative paths there.

The fix is one line of intent: the spawn sets `JOLT_PWD` to the canonical
project root, so the environment and the cwd agree and there is one answer to
*where am I*.

**Why this mattered more than a bug.** Four ordinary-suite tests failed, and
`bin/js2` carried a named allowlist explaining them as upstream's own
Linux-sandbox failures — `security.sandbox/backend-for` resolves to `:none`
off macOS, deliberately, and these tests assert sandboxed behaviour. That
explanation was plausible and wrong. All four assert the **run-root cwd
contract**, which holds under every backend including `:none`. The allowlist
was not a note; it was a load-bearing claim about a cause, and it was false
for as long as it stood, and it is exactly the kind of artifact that stops
anyone from looking again.

So it is **deleted**, not retired and not kept for compatibility. The ordinary
lane's gate is back to zero failures and zero errors.

| | tests | assertions | failures | errors |
| --- | --- | --- | --- | --- |
| Before, on the convergence oracle | 1860 | 7978 | **4** | 0 |
| After, on the closure oracle | 1864 | 8037 | **0** | 0 |

## Finding B — the poison was one slot, and timeouts are not one at a time

A timed-out execution poisons the provider until its cleanup is shown clean.
That state was a single slot holding a single invocation, which cannot
represent two overlapping timeouts:

```
A times out, poisons          poison = A
B times out, poisons          poison = B      <- A's uncertainty is gone
B's cleanup is clean, clears  poison = none   <- A's machine may still exist
a new execution starts on a provider that has an unresolved machine
and no memory of it
```

The poison is now a **set** of `{invocation -> evidence}`. Each invocation
poisons and resolves only its own entry; `unresolved-poison` names them; a new
execution is refused while any entry remains; and no invocation can clear
another's. A clean cleanup vouches for the machine it stopped and says nothing
about an execution failing beside it.

## Finding C — a set difference is not ownership

Cleanup after a timeout deleted the machine the manager's banner named, and
failing that, the single machine that had appeared since a baseline read
immediately before the spawn. The fallback is wrong under concurrency, and
wrong in the direction that destroys somebody else's work:

```
A reads its baseline (empty) and spawns
B spawns after A's baseline was taken
A times out; A's own machine never registered, or is already gone
A has no banner id
the table now holds exactly one machine A did not see: vm-B
```

The difference is `{vm-B}` — exactly one candidate, which is what the rule
required — and it belongs to B. The heuristic was most confident precisely
when it was alone in the world with another run's healthy VM.

Ownership now has **one source**: the manager's own startup banner. Without
it, ownership is `::unknown`, **nothing is deleted**, `:cleanup/clean?` is
false, and the invocation stays poisoned. The baseline and the table are still
gathered and still travel with the result as `:cleanup/candidates` — as
*evidence* that these appeared while this invocation ran, never as authority
to kill them. A provider that refuses further executions is a problem an
operator can see; a run that deleted another run's machine is a problem nobody
sees until the other run reports nonsense.

### A note on what the two-timeout test could and could not stage

The literal interleaving in Finding B — A poisons, B poisons over it, B
resolves, a new execution starts — **cannot occur once the fence is in
place**, because a new execution is refused while any entry is unresolved.
That is the fix working, and it means the honest test is not the one that
forces the impossible sequence. The tests instead drive two REAL concurrent
timeouts and observe the set directly: both entries present and separate, one
resolvable without disturbing the other, and the lane still fenced while the
survivor stands.

---

## The gate

`bin/js2 test` at the frozen closure oracle, tracked-clean, exit **0**. Full
log: `artifacts/js2-convergence-closure/js2-gate.log`.

| Lane | tests | assertions | failures | errors |
| --- | --- | --- | --- | --- |
| authority, provenance, lease and surface closure | 60 | 332 | 0 | 0 |
| pinned bounded evaluator | 52 | 428 | 0 | 0 |
| project + verification environments (machine-backed) | 98 | 851 | 0 | 0 |
| concurrent execution ownership (machine-backed) | 6 | 87 | 0 | 0 |
| ordinary suite | 1864 | 8037 | 0 | 0 |

Pinned and checked before any of it runs: Samizdat cleanliness, Jolt SHA and
cleanliness, SCI SHA, version and cleanliness, Chez, and the guest image by
digest.

New machine-backed tests, all of which boot real machines:

- `two-simultaneous-timeouts-each-clean-up-only-themselves` — two real
  concurrent timeouts, disjoint ownership, both entries resolved, table clean.
- `the-poison-set-holds-every-unresolved-invocation-separately` — both banners
  suppressed so both timeouts stay genuinely unresolved; resolve one and the
  other survives and still fences; resolve the second and the set empties.
- `a-timeout-without-a-banner-refuses-to-touch-another-runs-machine` — the
  Finding C race exactly: B is really running, A's banner is suppressed, A
  acts on **nothing**, reports `:owned ::unknown`, stays poisoned, and B
  completes untouched.
- `ownership-comes-from-the-manager-or-it-does-not-come-at-all` — replaces the
  old test that asserted the set-difference fallback.

Kept unchanged: `concurrent-executions-clean-up-only-their-own-machines` and
`two-successful-concurrent-executions-do-not-interfere`.

---

## The smoke — real model, real execution, real restart

Run from the **pushed** oracle, never from unpushed code. Immutable controller
checkout, separate disposable target, real provider, `:agent/project-execute`,
SmolVM `project/run`, progressive controller-owned `done`, budget 60. No
manual source edits at any point.

The task was the genuinely unsolved `truncate-middle` totality defect, still
present at the closure oracle. Sixteen turns, thirteen evaluations:

| op | n |
| --- | --- |
| `:project/read` | 3 |
| `:project/stat` | 3 |
| `:project/edit` | 3 |
| `:project/run` | 4 |

Zero multi-operation evaluations; one model roundtrip between each edit and
its next execution. The model wrote the fix, wrote tests for it, ran them in
the guest, got exit 1, corrected, and ran again to green — which is what a
development environment is for. Its final diff is
`artifacts/js2-convergence-closure/target.diff` and touches only the two files
the task named.

### The pre-kill snapshot, and why this one is not one line

The convergence run's `pre-interrupt.txt` contains the run-id and nothing
else. The script was `set -eu` with `curl -m 30` as its second statement,
against a controller that was at that moment inside a 900-second closure
verification; curl timed out, `set -e` did what it is for, and nothing checked
afterwards that the file said anything. That record is **not rewritten** — the
convergence recovery result is supported by its durable DB and its offline
replay proof regardless.

This one reads the **DB**, which does not care whether the server is
answering; guards every section so one failure cannot silence the rest; and
then **verifies the file before a signal may be sent** — twenty required
fields and six required sections, and a non-zero exit if any is missing. It
printed:

```
snapshot: 54 lines, 11908 bytes -> pre-kill.txt
precondition: edits=2 runs=1 pending=0
PRECONDITION MET: safe to SIGKILL
```

The complete §18 field list is in `pre-kill.txt`: run-id, status, last
completed turn, pending eval count, binding id, instance id, spec coordinate,
RuntimeCoordinate, ContextSpec coordinate, orientation digest, durable budget,
edit and run outcome counts, the edit and run receipt summaries, target
digests and mtimes, the manager table, the inference epoch and invocation
counts, and the SCI context lifecycle identity.

Then SIGKILL — `kill -9` to the Chez/Jolt controller process, recorded in
`kill-record.txt` with the pid, the time, its liveness five seconds later, and
the manager table.

**Two different moments, named as two.** The snapshot was captured and
verified at turn 11 / 9 evaluations / 2 edits / 1 run. The branch kept working
in the seconds it took to verify and signal, so the **true interruption
point** — read from the DB after the process was dead, in
`pre-resume-state.txt` — is turn 13 / 11 evaluations / 3 edits / 2 runs, with
zero pending. The replay is measured against the true point, not the snapshot.

### The restart

A fresh controller process, same DB, same target, same run, resumed. The
journal records the whole thing without being asked to:

```
event  2  {"context-id":"0a293924-…","phase":"allocated"}
event 31  {"context-id":"7893c035-…","supersedes":"0a293924-…",
           "replayed-evaluations":11,"phase":"reconstructed"}
```

Same binding id, same instance id, **one** binding for the run — no authority
re-minted — and a **fresh** SCI context that supersedes the dead one after
replaying exactly the 11 evaluations that were durable. The branch then
continued: eval 12, a further `project/run`, and `done`.

### The offline replay proof

`replay-proof.clj`, in a fresh process with no controller running, reconstructs
the run's real durable history — 13 evaluations carrying **3 edit receipts and
4 run receipts** — and observes the world either side. Full output:
`replay-proof.txt`.

| observation | before | after |
| --- | --- | --- |
| execution-provider invocation count | **0** | **0** |
| manager table | `No machines found` | `No machines found` |
| `src/samizdat/util.clj` digest | `1e893dd1…` | `1e893dd1…` |
| `src/samizdat/util.clj` mtime | `1788110061` | `1788110061` |
| `test/samizdat/util_test.clj` digest | `53a04423…` | `53a04423…` |
| `test/samizdat/util_test.clj` mtime | `1788110014` | `1788110014` |

Binding id unchanged, orientation digest unchanged, RuntimeCoordinate and
ContextSpec coordinate identical, SCI context fresh, 177 ms. **A replayed edit
performs no write and a replayed run performs no execution** — the invocation
counter is process-local, so zero in a fresh process is a fact rather than an
interpretation, and the target's mtimes are the same claim made by the
filesystem.

### Closure coverage

Both baselines taken by the controller's own verifier under the closure
executable, on an untouched target.

| | tests | assertions | failures | errors | green |
| --- | --- | --- | --- | --- | --- |
| clean-target baseline | 1864 | 7691 | 0 | 0 | yes |
| at completion | 1864 | 7697 | 0 | 0 | yes |
| delta | 0 | +6 | 0 | 0 | not decreased, same suite |

- suite `js1-ve/v1:bab68fdf5a128ea0094ce345680d8eb0101792a0e703f8a29bfc28e4ad82a861`
- verifier `sha256:e3fd1b7d73e88ec4f88e482c61912524bd48acbbe6ed3cd319a4c4ca387fff2f`
- baseline input `sha256:d009bb70afa2a1ca17fd8de16a7ff6fbb1b0fadeb943a2176ffb66e1f12681d5`
- final input `sha256:cdf0c42f6463e4ab0555e771fa7c7ec2efe103d596e4ceced44f27508665e3c9`

Focused GREEN (1 test, 10 assertions, exit 0) and closure GREEN, both in the
`done`'s ship-verify envelope in `verifier-envelopes.json`.

**These are isolated closure counts and are never compared with the host
ordinary suite's 8037.** Different verifiers in different environments count
different conditional assertions; the delta is computed against a baseline
taken by the same verifier, which is the only comparison that means anything.

### Integrity afterwards

Controller `6b8f5cc` with zero tracked modifications and zero untracked files;
Jolt and SCI clean; the target changed only `src/samizdat/util.clj` and
`test/samizdat/util_test.clj`; no machine left running; no controller process
left running.

---

## What did not change

- **TurnLease and execution semantics.** Untouched. Permits, receipts,
  intent-before / outcome-after, and exact replay are as the convergence left
  them.
- **`project/run` is development evidence, never acceptance.** `done` is
  settled by the controller's own focused and closure verifiers. A model's own
  green run persuades nobody, and in this smoke it did not: the controller ran
  its verifiers anyway.
- **Ordinary upstream oversight stays off over bounded runs.** The guard is
  correct and unchanged. The **documentation claim is narrowed**: ordinary
  upstream oversight is incompatible with the bounded surface and is therefore
  not run there — *not* that verification replaces everything a supervisor
  does. Verification settles exactly one question, whether `done` is earned. A
  supervisor also watches for a run going nowhere, spending its budget badly,
  or repeating itself, and nothing here covers that. An authority-compatible
  bounded oversight mechanism is separate work and is not in this milestone.

## Push records

Pushing is part of completion, so each push is recorded with the remote SHA
read back from `origin` afterwards.

| | what | local | remote | equal |
| --- | --- | --- | --- | --- |
| PUSH A | branch creation, before any closure work | `3ca9bd63b2f840b8cf23b0f5b2f5c67ac722755a` | `3ca9bd63b2f840b8cf23b0f5b2f5c67ac722755a` | yes |
| PUSH B | the implementation, tracked-clean and gated | `6b8f5cceb723e7f5589103b0617fe1e69e9f242e` | `6b8f5cceb723e7f5589103b0617fe1e69e9f242e` | yes |
| PUSH C | this evidence | see below | see below | yes |

PUSH C's SHAs are recorded in `artifacts/js2-convergence-closure/push-c.txt`,
written by the commit that follows this one — a commit cannot contain its own
hash.

No force push was used, nothing was merged to `main`, no frozen branch was
rebased, amended, moved or rewritten.

## Out of scope

JS3; bounded supervisor/oversight; a Linux sandbox backend for ordinary
`repl.image`; worker pooling; long-lived SmolVM workers; network authority;
package-install authority; multi-agent shared SCI; Cedar; MCP/A2A/ACP; bb4t
convergence; broad Jolt convergence; SmolVM verification redesign; a new
scheduler; a new memory architecture.
