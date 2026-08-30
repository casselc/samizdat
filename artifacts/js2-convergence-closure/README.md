# JS2 convergence closure — supporting records

Frozen supporting evidence for `docs/JS2_CONVERGENCE_CLOSURE_EVIDENCE.md`.
Result: **JS2 CONVERGENCE CLOSURE: PASS**.

Closure executable oracle: `6b8f5cceb723e7f5589103b0617fe1e69e9f242e`, on
upstream base `34a21ab1fb1063ad3eac7905f98a37ad5635427f`.

This record SUPERSEDES the prior convergence PASS for closure purposes. It
does not edit it. `docs/JS2_CONVERGENCE_EVIDENCE.md` and
`artifacts/js2-convergence/` describe the bytes that ran at `9391ce5` and
stay exactly as they were; so do the frozen JS2 experiment
(`js2-project-run-effcf7d @ 8edc570`) and the M1–M4 records.

| File | What |
| --- | --- |
| `js2-gate.log` | the exact `bin/js2 test` at the frozen closure oracle, exit 0, every lane green |
| `closure-preflight.sh` | the pre-flight that runs the controller's own verifiers on an UNTOUCHED target and writes the baseline |
| `closure-preflight.log` | its verdict under the CLOSURE executable |
| `closure-preflight-full.txt` | that pre-flight's complete focused and closure output |
| `closure-baseline.edn` | the clean-target ClosureCoverageSignature the smoke's delta is computed against |
| `pre-run-coordinates.txt` | controller/target/Jolt/SCI/image SHAs and digests, before the smoke |
| `run-request.json` | the frozen run request |
| `run-start.json` | the run id, read from the journal (see the note in the file) |
| `run-id.txt` | that id alone, for the scripts |
| `serve.sh` | the smoke launcher. Trusted controller configuration read once at process start; the budget token is READ from an untracked file, no secret is in this script |
| `serve-1.log` | controller life 1 — start through the SIGKILL |
| `serve-2.log` | controller life 2 — the resume through completion |
| `watch.sh` | the live journal reader used to wait for the §22 pre-kill precondition |
| `pre-kill-snapshot.sh` | what captured the pre-kill state, and — this is the part the convergence run did not have — what VERIFIES it before a signal may be sent |
| `pre-kill.txt` | the COMPLETE §18 snapshot, captured and checked before the kill |
| `kill-record.txt` | the SIGKILL itself: pid, time, liveness after, manager table |
| `pre-resume-state.txt` | the TRUE interruption point, read from the DB after the process died |
| `resume-1.json` | the resume request's answer |
| `replay-proof.clj` | the offline recovery proof's source |
| `replay-proof.txt` | its output: the real durable history reconstructed in a fresh process, with the execution provider's invocation counter, the manager's table, and the target's digests and mtimes observed either side |
| `trajectory-stats.py` | what computed `trajectory.txt` |
| `trajectory.txt` | computed counters — turns, evals, ops, execution shapes, roundtrips, receipts, the full SCI context lifecycle |
| `verifier-envelopes.json` | the `done`'s ship-verify record (focused GREEN, closure GREEN, coverage signature and delta) beside the smoke's `project/run` receipts |
| `target.diff` | the complete final diff of the disposable target |
| `final-integrity.txt` | controller, Jolt and SCI unchanged; target diff; no machine and no controller left running |
| `journal-db.sha256` | digest pinning the untracked authoritative journal |
| `push-c.txt` | the push freeze record: PUSH C's own SHAs, which its commit could not contain, plus origin and the Jolt oracle read back afterwards |

## The authoritative journal is not tracked here

`.gitignore` excludes `*.sqlite3`.

```
js2-closure-evidence/js2-closure.sqlite3
sha256:109bbd14c6a54b771996d8c7ea1dbfdc1237500cb8f2d4b782033e3d0c5cdea0
```

Also preserved and untracked: the controller checkout at `6b8f5cc`
(tracked-clean, zero untracked), the disposable target worktree exactly as the
run left it, and the digest-pinned guest image — which is the SAME file the
convergence run used, referenced by path and pinned by digest rather than
copied, at `js2-converge-evidence/worker-image.tar`.

## Reading the ordinary-suite numbers

The previous record asked you not to read `4 failures` as a regression, and
explained them as upstream's own Linux-sandbox failures. **That explanation
was wrong**, and this record is where it is withdrawn. All four assert the
run-root cwd contract, which holds under every sandbox backend including
`:none`; they failed because the project image was handed the controller's
`JOLT_PWD`. The ordinary lane is now 0 failures / 0 errors and `bin/js2` has
no allowlist at all.

## Two different suite counts, and why neither is wrong

The host ordinary suite reports **1864 tests / 8037 assertions**. The
controller's isolated closure verifier reports **1864 tests / 7691
assertions** on the clean target and **7697** at completion. Same tests,
different assertion totals, because a handful of assertions are conditional on
what the environment provides and the bwrap verification environment provides
less of it. They are not comparable numbers and this record never subtracts
one from the other; the coverage delta is computed against the baseline taken
by the SAME verifier.
