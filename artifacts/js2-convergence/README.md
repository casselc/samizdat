# JS2 convergence — supporting records

Frozen supporting evidence for `docs/JS2_CONVERGENCE_EVIDENCE.md`.
Result: **JS2 CONVERGENCE: PASS**.

Convergence executable oracle: `9391ce59b1771c22885742bd208f97dbe0a55d30`,
on upstream base `34a21ab1fb1063ad3eac7905f98a37ad5635427f`.

The frozen JS2 experiment (`js2-project-run-effcf7d @ 8edc570`) and the M4
records are separate, untouched, and not superseded by these. JS2's own
evidence is on this branch under `artifacts/js2-canary/` and
`docs/JS2_EVIDENCE.md`.

| File | What |
| --- | --- |
| `js2-gate.log` | the exact `bin/js2 test` at the frozen convergence oracle, exit 0 |
| `upstream-baseline.log` | the PRISTINE `34a21ab` suite on this host — the baseline the ordinary lane is measured against |
| `upstream-baseline-summary.txt` | its four failures and its summary line, extracted |
| `closure-preflight.log` | the controller's own verifiers on an UNTOUCHED converged target, before the smoke |
| `closure-preflight-full.txt` | that pre-flight's complete focused and closure output |
| `closure-baseline.edn` | the clean-target ClosureCoverageSignature the smoke's delta is computed against |
| `concurrency-probe.clj` | the §22 ownership probe's source |
| `concurrency-probe.txt` | its output on the frozen controller — A's machine, B's machine, A's exact cleanup targets, and the manager's table either side |
| `pre-run-coordinates.txt` | controller/target/Jolt/SCI/image SHAs and base digests, before the smoke |
| `run-request.json` | the frozen run request |
| `serve.sh` | the smoke launcher. Trusted controller configuration read once at process start; the budget token is READ from an untracked file, no secret is in this script |
| `serve-1.log` | controller life 1 — turns 1–19 |
| `serve-2.log` | controller life 2 — after the SIGKILL, turns 20–45 |
| `pre-interrupt-snapshot.sh` | what captured the durable state before the kill |
| `pre-interrupt.txt` | durable state captured BEFORE the kill, with the §21 precondition shown to hold |
| `pre-resume-state.txt` | the TRUE interruption point, read from the DB after the process died |
| `replay-proof.clj` | the offline recovery proof's source |
| `replay-proof.txt` | its output: the real durable history reconstructed in a fresh process, with the execution provider's invocation counter and the manager's table observed either side |
| `closure-preflight.sh` | the pre-flight that runs the controller's own verifiers and writes the baseline |
| `trajectory-stats.py` | what computed `trajectory.txt` |
| `trajectory.txt` | computed counters — turns, evals, ops, execution shapes, roundtrips, receipts |
| `verifier-envelopes.json` | the `done`'s ship-verify record (focused GREEN, closure GREEN, coverage signature and delta) beside the smoke's `project/run` results |
| `target.diff` | the complete final diff of the disposable target |
| `final-integrity.txt` | controller and Jolt unchanged; target diff; no machine left running |
| `journal-db.sha256` | digest pinning the untracked authoritative journal |

## The authoritative journal is not tracked here

`.gitignore` excludes `*.sqlite3`.

```
js2-converge-evidence/js2-converge.sqlite3
sha256:8487923e19a2264a634b7b62599790e4788a93f9488b87aa79e8bf8d9435fec3
```

Also preserved and untracked: the **first** smoke attempt's journal — the run
that found the late provider resolution and the supervisor-over-a-bounded-run
defect, and which is why both are fixed — the 148 MB digest-pinned guest
image, the controller checkout at `9391ce5` (tracked-clean), and the target
worktree exactly as the run left it.

## Reading the ordinary-suite numbers

Do not read `4 failures` as a regression. Upstream's own suite fails those
four on Linux at the commit this converged from, because its sandbox backend
resolves to `:none` there by its own decision. `upstream-baseline.log` is the
pristine measurement; `bin/js2` names those four and fails on a fifth.
