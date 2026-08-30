# JS2 — supporting records

Frozen supporting evidence for `docs/JS2_EVIDENCE.md` and
`artifacts/js2-canary.edn`. Result: **JS2: PASS**.

The JS1 M4 records are separate and frozen and are not superseded by these.
Attempt 1's are on this branch (`artifacts/m4-canary/`,
`artifacts/js1-m4-self-hosting-canary.edn`,
`docs/JS1_M4_SELF_HOSTING_EVIDENCE.md`); **attempt 2's are not**, because JS2
branched from the attempt-2 EXECUTABLE oracle `d035645` and its evidence
commit `f811ea7` comes after. They are on `js1-m4-attempt2-effcf7d`:

    git show f811ea7:artifacts/js1-m4-attempt-2.edn
    git show f811ea7:docs/JS1_M4_ATTEMPT_2_EVIDENCE.md

| File | What |
| --- | --- |
| `js2-gate.log` | the exact `bin/js2 test` at the frozen oracle, exit 0 |
| `closure-preflight.log` | the controller's own verifiers on an UNTOUCHED target, before the run |
| `closure-preflight-full.txt` | that pre-flight's complete focused and closure output |
| `closure-baseline.edn` | the clean-target ClosureCoverageSignature the run's delta is computed against |
| `pre-run-coordinates.txt` | controller/target/Jolt/SCI/image SHAs and base digests, before the run |
| `task.txt` | the run's `problem`, byte-identical to both M4 attempts |
| `serve-1.log` | controller life 1 — turns 1–7 |
| `serve-2.log` | controller life 2 — after the SIGKILL, turn 8 |
| `pre-interrupt.txt` | durable state captured BEFORE the kill, with the §22 precondition shown to hold |
| `pre-resume-state.txt` | the TRUE interruption point, read from the DB after the process died |
| `replay-proof.txt` | the OFFLINE recovery proof: the real durable history reconstructed in a fresh process, with the execution provider's invocation counter and the machine manager's table observed either side |
| `trajectory.txt` | computed counters — turns, evals, ops, leverage shapes, roundtrips, receipts |
| `verifier-envelopes.json` | the RFC-012 envelope for the single `done` (focused GREEN, closure GREEN) beside the run's `project/run` result |
| `target.diff` | the complete final diff of the disposable target |
| `final-integrity.txt` | controller and Jolt unchanged; target diff receipt-explained; no machine left running |
| `probes/adversarial.clj` | the §12 isolation probe source |
| `probes/isolation-probes.txt` | its output at the frozen oracle — ten attempts, tree unchanged after every one |
| `probes/timeout-probe.txt` | the §23 timeout canary: timeout reported, machine stopped and deleted by name, sweep clean, poison lifted, next invocation fresh |
| `provider-summary.txt` | per-call provider provenance, aggregated |
| `provider-proxy.log` | the raw log. Metadata only — no message content, no headers, no credentials. **Spans all four attempts**, since it appends |
| `js2-serve.sh` | the canary launcher — the trusted controller configuration, read once at process start and never from a run request or a model tool. The budget token is READ from an untracked file; no secret is in this script |
| `closure-preflight.sh` | the pre-flight that runs the controller's own verifiers against an untouched target and writes the baseline |
| `pre-interrupt-snapshot.sh` | what captured the durable state before the kill |
| `replay-proof.clj` | the offline recovery proof's source |
| `trajectory-stats.py` | what computed `trajectory.txt` |
| `run-request.json` | the frozen run request, `sha256:64e6e852…`, byte-identical to M4 attempt 2's |
| `journal-db.sha256` | digest pinning the untracked authoritative journal |
| `aborted-attempts.sha256` | digests pinning the three aborted attempts' journals |

## The authoritative journal is not tracked here

`.gitignore` excludes `*.sqlite3` and that convention was not overridden.

```
js2-evidence/js2-canary.sqlite3
sha256:af2963f20c4403c90e392e7f9b4d752e9041cdcf87db700aaaa4d355d4576f14
```

Everything here is derived from that file. Also preserved and untracked: the
three aborted attempts' journals, the 148 MB digest-pinned guest image, the
controller checkout at `26db106` (tracked-clean), and the target worktree
exactly as the run left it.

## The three aborted attempts

None are hidden; §13 of the evidence document explains each.

| Attempt | Turns | Class | Cause |
| --- | --- | --- | --- |
| 1 | 16 | `:execution-provider` | the host's open-file limit reached inside the guest |
| 2 | 16 | `:prompt` | the orientation named a command the guest toolchain does not have |
| 3 | 60 | `:model-behavior` | the model never called `project/run` and looped until the budget ran out |

Attempt 3 changed no code. The passing run used the same controller, task,
model and orientation, and needed no operator intervention.
