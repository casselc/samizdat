# RFC-000 — Index and provenance

The RFCs describe how samizdat is layered and why. They replace the
review-and-audit documents that used to live in `docs/`, which were a record of
five review passes rather than a description of the system.

| RFC | Layer |
|---|---|
| [RFC-001](RFC-001-core-layer.md) | The core layer — what `src/` is, and the base/userspace seam |
| [RFC-002](RFC-002-manifests-and-cells.md) | Manifest workflows and cells — the userspace layer |
| [RFC-003](RFC-003-security-model.md) | The security model — what contains the model, and what does not |

Each RFC ends with **Findings**: bugs and unwired behaviour that writing it
exposed. That section is the point of the exercise, not a postscript. A document
that only describes cannot tell you it is describing something broken.

## Provenance

About fifty comments in the code cite a numbered finding from a review pass —
`(review3 #11)`, `(a#4)`, `(code-review-2026-08 #6)`. Those citations are how a
later reader learns that a guard exists because of a specific failure rather
than a general worry, and that is worth more than the review documents
themselves were.

So the citations were rewritten to point here, and the findings they name are
listed below with what each one was. The full original write-ups are in git
history (`git log --diff-filter=D -- docs/`) if the one-line summary is not
enough.

Tags: `A-n` = the 2026-05 review, `CR1-n` = 2026-08 pass 1, `R2-n` = pass 2,
`R3-n` = pass 3.

### A — 2026-05

| tag | finding |
|---|---|
| A-1 | Shell policy bypass: a command could reach the shell without a permission decision. |
| A-2 | The human-approval flow had no write path — `grant!` was unreachable, so an `ask` could never be answered and a run blocked forever. |
| A-3 | The full suite errored on six tests from file-descriptor exhaustion; the `test` task raises `ulimit -n` because of this. |
| A-4 | `tasks/claim!` contradicted its own contract: a read-then-write pair let two branches both claim a task. Fixed by guarding inside the UPDATE — and see RFC-002 F3, which found the fix's granularity was still wrong. |
| A-5 | `lisp/scan` misreported `:balanced` for an escaped quote at end-of-input. |

### CR1 — 2026-08, pass 1

| tag | finding |
|---|---|
| CR1-1 | `base/missing` returned a bare string where callers expected a result map, NPE-ing the loop. |
| CR1-2 | Dead guard: the "usage cap wearing a rate limit's headers" branch could never fire, because the provider's asked-for wait was clamped before the check that needed it unclamped. |
| CR1-3 | An `active`-registry race stranded stale entries and let `abort!` rewrite finished runs. |
| CR1-4 | The LSP client let concurrent requests steal each other's frames. |
| CR1-5 | Eval-session namespaces were never removed — unbounded growth on a long-lived serve process. |
| CR1-6 | GUI poller lifecycle races and stale-run bleed. |

### R2 — 2026-08, pass 2

| tag | finding |
|---|---|
| R2-1 | `tasks/update!` re-opened the claim-race class one definition away from the fixed `claim!`. |
| R2-2 | The `message` tool sent the branch **map** as `:from`, storing 55–73 KB map dumps in `from_branch` and breaking the inbox's sender exclusion. |
| R2-3 | `migrate!` autocommitted every statement, so a crash mid-`ALTER` permanently bricked startup. |
| R2-4 | `abort!` had a transient window, and lifecycle `UPDATE`s ran with no status guards. |
| R2-6 | GUI: the inspector scroll reset to top on every state swap rather than on selection change. |
| R2-7 | GUI: a single-flight CAS silently dropped a newly selected branch's fetch — a permanent "loading…" on idle runs. |
| R2-8 | GUI: one failed run-list fetch disconnected the run being tailed. |
| R2-9 | LSP `client-for` had a get-then-create race that orphaned a duplicate clojure-lsp process. |
| R2-10 | LSP `diagnostics`: a concurrent same-uri erase returned a false "clean". |
| R2-11 | No retention anywhere — every high-volume table grew forever in one shared database file. |
| R2-13 | Five of thirty-four `gates.edn` keys had no reader. Now none do; see RFC-001 F2 for why checking this is harder than it looks. |
| R2-14 | `claim!` could resurrect a terminal unclaimed task. |
| R2-15 | Id-retry loops and FTS catches discarded the real failure cause, so a disk or lock error was reported as an id collision. |
| R2-17 | The LSP reader routed frames outside its `try`; one malformed frame killed the client permanently. |

### R3 — 2026-08, pass 3

| tag | finding |
|---|---|
| R3-1 | **Model-reachable arbitrary command execution on the verify path, with the unscrubbed parent environment.** Fixed: the child gets `secrets/scrubbed-process-env` and its output passes the redaction boundary. Compare RFC-003 F1, which is the same threat on a path that got neither guard. |
| R3-3 | `ring_chez` answered HTTP/1.1 but silently truncated chunked request bodies. |
| R3-4 | Unbounded request size, no read timeout, thread-per-connection. |
| R3-5 | Multibyte UTF-8 split across `recv` boundaries corrupted request bodies. |
| R3-6 | The arbiter and the state machine still referenced the removed proof-era tool surface. |
| R3-8 | Timeout kill escalation reached only the root process; trapped children survived. |
| R3-9 | Dead gate key `:sketch-duplicate-threshold` was still served by `/v1/harness/gates`. |
| R3-11 | `cells/resource-dir` was never called, so an AOT binary launched outside the project root silently registered zero cells. |
| R3-12 | Server/adapter conformance nits. |
| R3-13 | `control/watch` hardcoded branch `"B1"`. |
| R3-14 | `prefill-support?` had zero production callers; `chat-body` consulted a private twin, so the answer a caller could query and the answer acted on could drift. |

## What was removed

`docs/code-review.md`, `code-review-2026-08.md`, `-2.md`, `-3.md`, `-4.md`,
`src-audit-2026-08-4.md`, and `security.md`. The first five were review records
whose cited findings are above. `src-audit-2026-08-4.md` asked the question
RFC-001 now answers and its open items are carried into that RFC's findings.
`security.md` became RFC-003.
