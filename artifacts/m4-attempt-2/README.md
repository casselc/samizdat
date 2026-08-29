# JS1 M4 attempt 2 — supporting records

Frozen supporting evidence for `docs/JS1_M4_ATTEMPT_2_EVIDENCE.md` and
`artifacts/js1-m4-attempt-2.edn`. Result: **M4 ATTEMPT 2: PASS**.

Attempt 1 is a separate, frozen record (`artifacts/m4-canary/`,
`docs/JS1_M4_SELF_HOSTING_EVIDENCE.md`) and is not superseded by this one.

| File | What |
| --- | --- |
| `m3-gate.log` | the exact M1–M3 gate at the frozen attempt-2 coordinate, exit 0 |
| `closure-preflight-full.txt` | the closure verifier RED on an UNTOUCHED target, before the hermetic fix — the pre-flight that stopped a false FAIL |
| `pre-run-coordinates.txt` | controller/target SHAs and base digests, before the run |
| `task.txt` | the run's `problem`, identical to attempt 1 |
| `serve-1.log` | controller life 1 — turns 1–16 |
| `serve-2.log` | controller life 2 — after SIGKILL 1, turns 17–27 |
| `serve-3.log` | controller life 3 — after SIGKILL 2, turns 28–50 |
| `pre-interrupt.txt` | durable state captured before SIGKILL 1 |
| `pre-resume-state.txt` | the TRUE interruption point, read from the DB after the process died |
| `pre-interrupt-2.txt` | durable state before SIGKILL 2, with the helper already defined |
| `sci-context-lifecycle.json` | allocated → reconstructed → reconstructed, with supersedes and replay counts |
| `verifier-envelopes.json` | the RFC-012 envelope for the single `done`: focused GREEN and closure GREEN |
| `target.diff` | the complete final diff of the disposable target |
| `trajectory.txt` | computed counters, epochs, receipts, interventions |
| `final-integrity.txt` | controller unchanged; target diff receipt-explained |
| `provider-proxy.log` | per-call endpoint provenance. Metadata only — no message content, no headers, no credentials |
| `journal-db.sha256` | digest pinning the untracked authoritative journal |

## The authoritative journal is not tracked here

`.gitignore` excludes `*.sqlite3` and that convention was not overridden.

```
m4a2-evidence/m4a2-canary.sqlite3
sha256:0ed5230773588f24c83be2be3e0ae0304d14d01148cd8d48a06cc3a5d589dc45
```

Everything here is derived from that file. Also preserved and untracked: the
controller checkout at `d035645` (tracked-clean) and the target worktree
exactly as the run left it.
