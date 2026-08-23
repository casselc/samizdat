# Code review — 2026-08

Scope: full project review — bugs, unwired code, structural issues.
`src/samizdat` (74 files incl. tools), `gui/samizdat/gui` (9), `vendor/`
(trust-boundary only), `resources/` (cells, manifests, gates, prompts).

Method: chiasmus call-graph phases (dead code, cycles, layer violations) →
manual read of every unassigned src file → four parallel deep-dive passes
(store layer, agent engine, tools/cells/manifests, GUI) → `jolt test`.

Outcome: 7 issues found; all fixed test-first in this pass. Suite went
954/3020 (baseline) → **963 tests, 3044 assertions, 0 failures, 0 errors.**
Every fix carries a regression test that fails against the old code.

Deep-dive caveat: the store-layer and agent-engine passes hit their turn
caps before reporting; their scopes were covered instead by the main-thread
reads below. The tools and GUI passes completed.

## Issues (severity-ranked; all FIXED)

### 1. HIGH — `base/missing` misuses returned a bare string as the tool result, NPE-ing the loop — FIXED

`src/samizdat/agent/tools/skills.clj:35` and `manifest.clj:89,102-103`:

```clojure
(nil? name) (base/missing branch :name)
```

`base/missing` takes the CTX (it reads `:tool-name` for its skeleton) and
returns a complaint STRING meant to be handed to `base/malformed`. Called
with `branch` and returned raw, the string REPLACED the whole result map:
no `:category`, no `:branch`. `tool-step` (`agent/loop.clj:404`) then
threads `(:branch result)` = nil into `state/record-outcome`
(`agent/state.clj:515`), where `(update nil :turns-since-progress inc)`
throws — an NPE that kills the branch's turn on a merely-malformed tool
call. CONFIRMED BUG by direct read of all four frames.

Fix: `(base/malformed branch (base/missing ctx :name))` at all four sites.
Tests: `skills_test` / `manifest_test` — a load/show/save missing its name
now yields `:category :mechanics`, the branch riding along, and a skeleton
naming the tool.

### 2. MEDIUM — dead guard: the "usage cap wearing a rate limit" branch could never fire — FIXED

`src/samizdat/llm/client.clj:75-90` vs `client.clj:212-224`. `retry-after-ms`
clamped every server-asked wait to `max-backoff-ms` (60 000 ms) before
returning; the `chat` loop then compared that clamped value against
`max-in-run-retry-wait-ms` (300 000 ms):

```clojure
(when-let [server-wait (retry-after-ms (:headers result))]
  (> server-wait max-in-run-retry-wait-ms))
```

`min 60000` can never exceed 300 000, so the guard was unreachable: a 429
whose `x-ratelimit-reset-tokens` says 40 minutes — exactly the "usage cap
in a rate-limit's clothing" the docstring names — was retried after a 60 s
backoff instead of being treated as fatal, burning the run's budget against
a wall that will not move.

Fix: `retry-after-ms` now returns the provider's ask UNCLAMPED; the
`max-backoff-ms` ceiling moved to `backoff-ms`, the place that decides what
we actually sleep. Tests: unclamped values asserted, sleep still bounded,
and a 1-hour reset now throws the cap exception instead of sleeping.

### 3. MEDIUM — `active`-registry race stranded stale entries and let `abort!` rewrite finished runs — FIXED

`src/samizdat/api/control.clj` (start-run!, resume!). The run future's
completion `dissoc`'d `active` before the request thread — which registered
only after `(deref promised 30000 nil)` — had `assoc`'d it. A run finishing
inside that window left an entry no one would ever remove; `abort!` then
found the stale entry, reset a dead abort flag, and called
`runs/finish-run! conn run-id :aborted` on an already-terminal run.

Fix: registration moved INSIDE the run's own thread (start-run! via
`:on-start`, resume! at the top of the future body), so the assoc can never
land after the completion dissoc. The write-only `:future` key is gone from
the entries. Test: an instant-completing run leaves no active entry, and
`abort!` on it answers 409 instead of rewriting status.

### 4. MEDIUM — LSP client: concurrent requests stole each other's frames — FIXED

`src/samizdat/lsp/client.clj`. Every `request!` caller read the shared
stream itself, so with `:beam-width 5` two branches calling the `lsp` tool
in the same window could consume each other's responses (discarding them —
responses carry no `:method`, so `store-diagnostics!` dropped them) and
interleave bytes mid-frame on one `BufferedInputStream`. Compounding it,
`read-frame-bounded` was `(deref (future ...) timeout ::timeout)` — every
timeout abandoned a reader still blocked in `.read`, and `diagnostics`
spawned one such lingering reader per 250 ms iteration when the server was
quiet, each stealing bytes from the next request.

Fix: one dedicated reader thread per client (`start-reader!`) reads every
frame and routes it by id — responses deliver the promise their `request!`
is parked on, notifications go to the diagnostics store, and EOF releases
every waiter with `::closed`. `diagnostics` now just waits on the store; no
caller touches the stream. Test: two concurrent requests answered OUT OF
ORDER each get their own response.

### 5. LOW — eval-session namespaces were never removed — FIXED

`src/samizdat/repl.clj` — every run's `:repl-session` (one per run,
`workflow.clj:252`) created a `samizdat.repl.session-N` namespace that
nothing ever removed; the agent's defs persisted in it. Unbounded growth on
a process designed to run for weeks.

Fix: `repl/close-session` (idempotent `remove-ns`), called from the run
driver's `finally` in `workflow.clj` so the namespace dies with the run on
every exit path. Test: session namespace is gone after close.

### 6. LOW — GUI: poller lifecycle races and stale-run bleed — FIXED

`gui/samizdat/gui/api.clj` + `core.clj` + `glpane.clj`. Four related
defects, CONFIRMED by the GUI deep-dive pass:

- An in-flight fetch completing after `stop!` still delivered its
  callbacks, folding the OLD run's events into the graph of a newly
  selected run. The loop now rechecks `@running` between fetch and
  delivery.
- One throwing callback killed the poller future silently — the header kept
  saying "tailing" over a frozen graph. Callbacks are guarded.
- `refresh-branch-log!` had no in-flight guard, so a fetch slower than the
  1.5 s poll interval stacked another doomed request every tick (45 s
  timeout × 1.5 s refire). Now one at a time via CAS.
- `connect-to!` (stop-old / reset-new on `@poller`) could interleave across
  the GTK thread and background futures, orphaning a poller with no `stop!`
  handle anywhere. Serialized on a lock; a run switch also clears the hover
  mark so the status line stops describing a node from the previous run,
  and the unrealize hook now removes its widget address from the wired set
  (GLib recycles addresses; a recycled-address GtkGLArea that looked
  "already wired" got no hook and left `request-render!` poking freed
  memory).

Tests: a batch landing after stop is not delivered; a throwing callback
does not kill the poller.

### 7. LOW — dead code removed

- `gui.core/clip` — defined, documented, never called anywhere (verified
  repo-wide grep). Its "deliberately does not truncate" contract was
  already honored by every call site passing text through unchanged.
- `gui.core` `:run-status` mirror — written by `refresh-runs!`, read by
  nothing.
- `gui.api/health`, `gui.api/run-detail` — client fns with no callers (the
  SERVER's `/health` and `GET /v1/runs/:id` endpoints are wired and stay).
- `glpane/hovered` — reader with no callers; replaced by `clear-hover!`,
  which has a caller (run switch).

## Clean areas (main-thread reads)

- `samizdat.server` — route table complete; every handler wired; 404/500
  paths correct; status-code discipline consistent.
- `samizdat.system` — start/stop lifecycle best-effort per resource;
  orphan-reconciliation timing sound.
- `samizdat.engine.proc` — bounded waits, SIGTERM→SIGKILL reap intact.
- `samizdat.events` — sliding-buffer mult; no backpressure onto the loop.
- `samizdat.llm.fence` — opener-anchored fence extraction, control-char
  repair, XML rung: no defects found; failure modes documented from real runs.
- `samizdat.llm.message` — compaction/ledger stripping consistent; wire-shape
  normalization correct.
- `samizdat.config` — layering defaults < project < overrides correct;
  redaction masks api-key.
- `samizdat.lisp` — escaped-quote scan fix (a#5) present and correct.
- `samizdat.mutation` — checkpoint→reload→validate→soak→commit/rollback;
  registry snapshot/restore pairs correctly in `finally`.
- `samizdat.api.runs` / `samizdat.api.openai` — read model consistent;
  the `claim_status` (DB rows, strings) vs `:claim-status` (in-memory,
  keywords) split is intentional conversion, not a mismatch (resume.clj:126
  is the bridge).
- `samizdat.control` (REPL steering) — wired through the same interventions
  queue as HTTP; all fns used or user-facing by design.
- `samizdat.core` — entry point clean; warm-tls! never throws; shutdown
  hooks registered.
- `samizdat.workflow` — manifest seeding/compile path consistent; catalog
  merges factory + stored correctly.

## Prior-review verification (docs/code-review.md, 2026-05)

- #1 CRITICAL shell-policy bypass: quote-aware `shell-split` present
  (policy.clj:79-123); per-segment deny candidates present
  (policy.clj:246-251); deny side widened, allow side raw. Fix intact.
- #2 grant write path: `intervene!` kind "grant" → `grants/grant!`
  (api/control.clj). Fix intact.
- #4 claim race: guarded UPDATE with `(run_id IS NULL OR run_id = ?)` and
  `closed_at = NULL` (tasks.clj). Fix intact.
- #5 lisp escaped quote: `escaped?` backslash-run counter present.
- #3 fd limit: suite now runs green without intervention at the raised
  limit baked into the `test` task.

## Structural analysis summary

- Dead code: 7 candidates — 6 are `samizdat.server` handlers dispatched via
  the `routes` data table (false positives, dispatch is by var reference);
  1 was `gui.core/clip` (issue #7, removed).
- Cycles: `decompose-node`/`solve` only — intentional tree recursion.
- Layer violations: none.
