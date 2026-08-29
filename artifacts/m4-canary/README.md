# JS1 M4 self-hosting canary — supporting records

Frozen supporting evidence for `docs/JS1_M4_SELF_HOSTING_EVIDENCE.md` and
`artifacts/js1-m4-self-hosting-canary.edn`.

Result: **M4: FAIL**, first causal failure class `:model-behavior`.

| File | What it is |
| --- | --- |
| `m3-gate.log` | full `bin/js1-m3 test` output — the exact M3 pre-canary gate, exit 0 |
| `pre-run-coordinates.txt` | controller/target SHAs, clean status and base digests, captured before the run |
| `task.txt` | the run's `problem`, verbatim |
| `serve-1.log` | controller process life 1 — provider `:local`, model `openrouter.z-ai/glm-5.3`, turns 1–17 |
| `serve-2.log` | controller process life 2 — after crash 1, provider `:openai`, same model, turns 18–23 |
| `serve-3.log` | controller process life 3 — after crash 2, model `fireworks.kimi-k2p7-code`, turns 24–120 |
| `pre-interrupt.txt` | durable state captured immediately before the first SIGKILL (after turn 17) |
| `pre-interrupt-2.txt` | durable state captured immediately before the second SIGKILL (after turn 23) |
| `provider-proxy.log` | per-call endpoint provenance: request size, message count, roles, body keys, upstream status, latency, error body. **Metadata only** — no message content, no headers, no credentials |
| `verifier-envelopes.json` | the four RFC-012 run envelopes from the controller's bwrap VerificationEnvironment — all four RED |
| `target.diff` | the complete final diff of the disposable target worktree |
| `util-after-third-edit.clj` | `src/samizdat/util.clj` captured right after the agent rewrote it wholesale from memory — the first causal failure |
| `trajectory.txt` | computed trajectory counters, epochs, invocations, interventions, edit receipts |
| `journal-db.sha256` | digest pinning the untracked authoritative journal (below) |

## The authoritative journal is not tracked here

The run's SQLite journal is the authoritative detailed record, but this
repository's `.gitignore` excludes `*.sqlite3` and that convention is not
overridden for evidence. It is preserved on the machine that ran the canary:

```
/home/chuck/opencode/src/m4-evidence/m4-canary.sqlite3
sha256:fd67ab0a91a5ab177c48a6dc1ccd044135c8f0342d4c46d2babf171ac543230e
```

Everything in this directory is derived from that file and can be recomputed
from it. Alongside it, also preserved and untracked: the controller checkout at
`64effe15` (tracked-clean, unchanged) and the disposable target worktree left
exactly as the run left it.
