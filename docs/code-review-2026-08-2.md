# Code review — 2026-08 (second pass)

Scope: full-project re-review the same day commit a569705 fixed the seven
findings of `docs/code-review-2026-08.md`. Bugs, unwired code, structural
issues across `src/samizdat` (74 files incl. tools), `gui/samizdat/gui` (9),
`vendor/` (trust boundary only), `resources/` (cells, manifests, gates,
prompts).

Method: chiasmus call-graph phases (dead code, cycles — snapshot
`review-2026-08b` saved for next cycle's diff) → main-thread reads → four
parallel deep-dive passes (store layer, agent engine, tools/cells, GUI).
The store and GUI passes completed; the engine and tools passes hit their
turn caps mid-investigation (engine: second consecutive review), and every
lead they left dangling was verified or refuted on the main thread —
notably the `messages :from` finding, confirmed empirically against the
live DB.

Baseline: `jolt test` → **963 tests, 3044 assertions, 0 failures,
0 errors.**

Outcome: 17 numbered findings (2 HIGH, 9 MEDIUM, 6 LOW) plus INFO notes.
None fixed yet — this document is the fix list for the next round.

## Issues

### 1. HIGH — `tasks/update!` re-opens the claim-race class one def over from the fixed `claim!`

`src/samizdat/store/tasks.clj:105-127`. `claim!`'s own docstring
(tasks.clj:131-135) names the failure mode: "a read-then-write pair is two
lock acquisitions and two beam branches whose reads both saw the unclaimed
row could both write (a#4)". `update!` is exactly that pair:

```clojure
(let [t (get-task conn id)]          ; read, outside any lock
  ...
  (db/with-writer
    (db/execute! conn ["UPDATE tasks SET title = ?, ..., run_id = ?, ...
                        WHERE id = ?" ...])))
```

The UPDATE guards on `WHERE id = ?` only and writes **every** column from
the stale row, including `run_id` and `status`. Interleaving: branch A (run
R1) edits a priority — its `get-task` reads `run_id nil`; branch B claims
the task for R2 (guarded UPDATE lands); A's UPDATE then writes
`run_id = (or nil (:run_id stale))` = nil and `status "open"` — silently
erasing R2's claim. The task returns to every run's board; a third run
re-claims it; R2 later closes a task another run holds. Trigger is normal
beam operation (`:beam-width 5`); the same window loses arbitrary field
edits between two concurrent `update!`s, and a stale `close!` (which calls
`update!`) reverts a claim the same way. Blast radius: the shared task
board across all live runs.

Fix: move the `get-task` inside the existing `db/with-writer` (the global
lock then makes read+write atomic against every other writer, `claim!`
included), and/or stop writing columns the caller didn't pass.

### 2. HIGH — the `message` tool sends the branch *map* as `:from`; `from_branch` stores 55–73 KB map dumps

`src/samizdat/agent/tools/messages.clj:41`:

```clojure
(messages/send! conn {:run-id run-id :from branch :to to :body body})
```

`branch` here is the branch state map (assoc'd by `tool-step`,
agent/loop.clj:402), and the store inserts `(str from)`
(store/messages.clj:57). Confirmed against the live DB:

```
{:id "W2v1", :parent-id nil, :problem "Maybe update changelo…  len 54991
{:id "W0",  :parent-id nil, :problem "In src/samizdat/store/k… len 54269
{:id "W1",  :parent-id nil, :problem "src/samizdat/store/mess… len 72962
```

Three rows, ~180 KB of branch-state dumps in a durable table. Worse than
bloat, the mailbox semantics break: `inbox` excludes the sender with
`from_branch != (str branch)` (messages.clj:76-79) — the map repr at send
time never equals the repr at read time (branch state mutates every turn),
so **the sender sees their own broadcasts**, and the context-block preview
(`render-inbox`, called with `(:id branch)` at agent/loop.clj:180) can
never match either — it shows a 60-char map dump as the sender id and
includes the sender's own mail. The store's tests pass because they pass
string ids directly; the tool layer is where the map leaks in.

Fix: `:from (:id branch)` in tools/messages.clj:41. Add a tool-layer test
asserting `from_branch` is the branch id. (Cleaning the three live rows is
optional; `UPDATE messages SET from_branch = substr(from_branch,1,8)`-ish
or just delete them.)

### 3. MEDIUM — `migrate!` autocommits every statement; a crash mid-ALTER permanently bricks startup

`src/samizdat/store/db.clj:136-146`. No `BEGIN`/`COMMIT` around a
migration's statements; `user_version` is bumped only after the last one.
Migrations v2/v4/v5 are `ALTER TABLE turns ADD COLUMN …` (no idempotent
form exists). A process death between an ALTER and the version bump leaves
`user_version` stale, so the next boot re-runs the ALTER → `duplicate
column name` → `open!` throws → the server never starts again until
someone hand-edits `PRAGMA user_version`. Every subsequent boot repeats
the failure deterministically. Sub-point (LOW): db.clj:135 — a `user_version`
newer than the compiled vector (newer DB, older binary) silently applies
nothing and returns, masking version skew.

Fix: wrap each migration in a transaction (SQLite DDL is transactional),
bumping `user_version` inside it; warn on version skew.

### 4. MEDIUM — `abort!` transient window, and lifecycle UPDATEs with no status guards

`src/samizdat/api/control.clj:143-149` does `(get @active run-id)` →
`reset!` → `runs/finish-run!`, and `finish-run!`
(store/runs.clj:50-56) is an unconditional `UPDATE … WHERE id = ?`. The
morning fix (a#3) killed the stranded-entry variant; the transient variant
remains: a run completing between the `get` and its own `:completed` finish
still gets rewritten to `:aborted`/NULL. Siblings with the same shape:
`mark-running!` (runs.clj:105-107, guarded today only by
`control/resumable?`), `close-branch!` (runs.clj:168-171 — a late close
overwrites `inactive_reason`), and `interventions/resolve!` (:85-88 — no
`status = 'pending'` guard; a double-resolve overwrites the first
disposition, and the run-id feeds only the journal note, so a resolve
under the wrong run journals into the wrong run).

Fix: guard at the store — `finish-run!`/`mark-running!` with
`AND status = 'running'`, `resolve!` with `AND status = 'pending' AND
run_id = ?` — and have `abort!` answer 409 when the guarded UPDATE matches
0 rows. Precedent in-file: `reconcile-orphans!` (runs.clj:84) already
guards `AND status = 'running'`.

### 5. MEDIUM — GUI: background futures call `gtk_gl_area_queue_render` off the main thread

`gui/samizdat/gui/glpane.clj:245-247` via core.clj:123, 154, 171. In the
pinned glimmer-gl, `queue-render` is a bare FFI binding — no marshaling —
while glimmer-gtk itself marshals reactive re-render through `g_idle_add`
precisely because "calling GTK — off the main thread … AppKit rejects on
macOS". The poller future and branch-log future hit it every cycle/batch
for as long as a run is tailed: undefined behavior, intermittent,
load-dependent, worst on this project's own macOS dev machines. The
background call can't simply be deleted — `graph-pane` is deliberately
deref-free, so it is the *only* repaint path for new events; it needs to
be marshaled (hop through the existing `schedule`/`g_idle_add`).

### 6. MEDIUM — GUI: inspector scroll resets to top on every state swap, not just selection changes

`gui/samizdat/gui/core.clj:587`: `[:scrolled {:vexpand true :scroll-top
(str selected)}]`. glimmer-gtk's `scrolled-spec :apply` resets the
adjustment to 0.0 whenever the prop is *present* — the value is ignored —
and the reconciler applies the full prop map on every re-render. `state`
is a single cell swapped by every poller batch (≤1.5 s), every hover
boundary, every keystroke in the interjection draft. Reading a long turn
log — the panel's entire purpose — fights the user back to the top
continuously mid-run.

Fix: include `:scroll-top` only when the selection actually changed
(memoize last value, omit the key otherwise).

### 7. MEDIUM — GUI: the single-flight CAS silently drops a newly selected branch's fetch — stuck "loading…" forever on idle runs

`gui/samizdat/gui/core.clj:112` (introduced by this morning's fix (c)):
when the CAS fails the request is discarded — no queue, no dirty bit. The
in-flight fetch's completion guard discards its own result because the
selection changed, and the only re-trigger is a poller event *for that
branch*, which never arrives on a finished run. Select node A (45 s socket
timeout — a long busy window), click B: B's fetch dropped, A's result
discarded, B shows the dim "loading full turn results…" text forever.

Fix: on CAS failure record a pending selection; the `finally` re-runs
`refresh-branch-log!` when one is set.

### 8. MEDIUM — GUI: one failed run-list fetch disconnects the run being tailed

`gui/samizdat/gui/core.clj:185-194`. `api/list-runs` returns `{:ok false}`
(not a throw) on a dead/busy server, so `runs = []`; `current` is then not
in the (empty) list, the `when-not` fires, and `connect-to! nil` runs —
stop! on the live poller, graph and selection wiped, header to "no run".
Worst instance: `start-new-run!` connects to the new run then calls
`refresh-runs!` — one transient list failure right after a successful
start disconnects a run the user just created while it consumes provider
spend.

Fix: gate the fallback on `(seq runs)` — an empty list is "don't know",
not "no runs".

### 9. MEDIUM — lsp `client-for` get-then-create race orphans a duplicate clojure-lsp process

`src/samizdat/lsp/client.clj:166-170`: `(or (get @clients root) (let [c
(start! root)] (swap! …)))`. `start!` spawns a process and completes an
`initialize` handshake — a seconds-wide window. Two beam branches racing
the first `lsp` call for the same root each spawn a server; the loser is
never registered, never shut down, its reader thread parked forever. Fix:
compute-once per root (a per-root delay/promise inside the atom, or create
under a lock).

### 10. MEDIUM — lsp `diagnostics` concurrent same-uri erase returns a false "clean"

`src/samizdat/lsp/client.clj:208-226`: dissoc the uri → `didChange` →
poll the store up to 5 s → else `[]`. Two callers on the same uri race:
one caller's dissoc can erase the push the other is waiting for, and the
5 s-timeout `[]` is indistinguishable from "no diagnostics" — which is
exactly what the gates read as clean. Fix: version-keyed diagnostics store
or a per-uri latch; don't let a race fold into "clean".

### 11. MEDIUM — zero retention anywhere: every high-volume table grows forever in one shared DB file

Repo-wide sweep: the only DELETEs in the system are `artifacts/retract!`
and `knowledge/forget!`. Nothing prunes `events` — a durable duplicate of
every turn/artifact/failure/gate/note write whose only readers are the
live tail and `last-progress-at`; once a run ends, its events rows have no
reader at all. `turns` carries `assistant_text`/`reasoning_text` (the
journal's own docstring cites 5.5 MB per run); `failures`,
`shared_artifacts` (+ FTS shadows), `gate_firings`, `messages`,
`interventions`, `grants` all accumulate per run in the one process-lifetime
DB. Endgame: disk exhaustion → every INSERT throws → all branches die at
once with the least informative error the project's own literature knows.
Classified LIKELY not CONFIRMED only because "the file is the archive"
may be a deliberate stance — nothing in the repo states it.

Fix direction: prune `events` per finished run (the durable tables already
hold the content) and document the retention stance for the rest.

### 12. MEDIUM — UNWIRED: the claims registry is threaded through ctx and read by nothing

`src/samizdat/agent/claims.clj` (new-registry/try-claim!/complete!/release!)
is created into ctx by `beam.clj:788` and `resume.clj:267`
(`:claims (claims/new-registry)`) and carried through every cell — and no
code in src/ or resources/ ever reads `:claims`; the only callers of the
API are its own tests (agent_test.clj:423-442). Cross-branch claim mutexes
(artifacts.clj's consensus machinery) are handled elsewhere; this is ~60
lines of tested scaffolding wired into the hot path but serving nothing.

Fix: wire it (a `claim` tool for branches to coordinate) or drop it from
ctx and the tree.

### 13. LOW — UNWIRED: 5 of 34 gates.edn keys have no reader anywhere

`resources/gates.edn` keys with zero references in src/, gui/, test/, or
resources/cells/ (each verified by word-boundary grep across the repo):

- `:fast-verify-edit-threshold` — its only appearance outside gates.edn
  is a key list in agent_test.clj:209; no production reader.
- `:fork-invite-floor`, `:max-tier-escalations`, `:prediction-window`,
  `:stale-approach-threshold` — nothing anywhere.

Each carries a :doc describing a gate that therefore does not exist in
code (e.g. `:stale-approach-threshold` documents the vf-9wx sameness
check; whatever implements that reads a different key or a constant).
Either the reader was dropped in a refactor or the gate was never landed.
Also stale: gates.clj:449's docstring calls the HTTP surface
"/v1/harness/state" — the route is `/v1/harness/gates`.

### 14. LOW — `claim!` can resurrect a terminal unclaimed task

`store/tasks.clj:139-141` guards the claim race but not terminality: a
backlog task closed `done` while never claimed still has `run_id NULL`, so
a claim flips it to `in_progress` with `closed_at = NULL` — completed work
reappears on the claiming run's board. The 2026-05 fix anticipated
claiming "a previously closed task" (its `closed_at = NULL` in SET), so
this may be intended; if not, the guard wants `AND closed_at IS NULL`.

### 15. LOW — id-retry loops and FTS catches discard the real failure cause

`messages/send!` (:51-65), `knowledge/remember!` (:46-59),
`tasks/create!` (:86-100): the insert retry catches `Exception`
(`Throwable` in tasks) and reduces every failure — ENOSPC, lock timeout,
FK violation — to a generic "could not allocate a(n) … id" after 5 blind
retries, original exception dropped, nothing logged. `failures/similar`
and `artifacts/similar` (`(catch Throwable _ [])`) leave a persistent FTS
fault rendering every similarity query silently empty for the run's life
with zero log lines. Fix: catch the PK collision specifically (or at
least log the cause); one `log/warn` in each `similar` catch.

### 16. LOW — GUI: the poller is never stopped when the window closes

`gui/samizdat/gui/core.clj:630-632` — no close/destroy hook anywhere in
gui/. After the last window closes the poller future keeps cycling
(Thread/sleep loop) hitting the server every ≤30 s; and in the post-close
window a poller `swap!` renders inline on the poller thread (marshaling
only happens while the GTK loop is running) against a destroyed widget
tree. Fix: a `close-request` handler (or shutdown wrapper around `ui/run`)
invoking the poller's `stop!`.

### 17. LOW — lsp reader routes frames outside its try; one malformed frame kills the client permanently

`src/samizdat/lsp/client.clj:102-106` — `read-frame` is inside the try,
but the routing (`deliver`/`store-diagnostics!`) is not: a throw there
kills the reader thread silently with no waiter release (both branches
are total today, so this is latent), and there is no restart path — a
client that dies stays dead for the process lifetime while `client-for`
keeps handing it out. Fix: wrap the whole loop body; optionally mark the
client dead on reader exit so `client-for` restarts it.

## INFO notes (no immediate action expected)

- `messages/mark-read!` has no run/recipient predicate — unreachable today
  (sole caller passes ids from its own inbox fetch); latent guard gap.
- `artifacts/retract!` deletes *all* shared rows matching the claim text,
  including sibling branches' independent confirmations, while their own
  rows and the ledger keep saying `confirmed` — conservative, documented,
  but a shared-pool/ledger disagreement.
- db.clj internals (`migrate!`, `schema-version`, `table-names`,
  `fts5-available?`) bypass the `with-conn` lock — startup/test paths only.
- `PRAGMA foreign_keys` is never set ON, so every `REFERENCES` in the
  schema is inert (nothing deletes parents today; app code checks them).
- journal row + event row are two lock acquisitions; a crash between them
  leaves a committed turn with no tail event (durable tables unaffected).
- `knowledge/recall` builds `"%query%"` with no ESCAPE — model-supplied
  `%`/`_` match beyond literal text (parameterized; not injection).
- GUI residual TOCTOU in the fixed poll loop is now microseconds
  (api.clj:190-191); a generation counter would close it fully.
- `watch-unrealize!` stacks a duplicate signal handler per
  unrealize/realize cycle of the same widget (idempotent handlers —
  growth only); its handler-id 0 "silent failure" case is never checked.
- `unmount!` wipes state unconditionally instead of only for the dying
  widget — safe under the current reconciler ordering, fragile by design.
- Graph layout `depth` is O(N²) non-tail recursion recomputed on every
  render *and every pointer motion* (~700 artifacts ≈ 250k recursions per
  event); memoize or iterate.
- `style/node-verts` 3-arg arity has no callers (leftover in a
  dead-code-removal commit).
- `GET /v1/interventions/kinds` has no consumer anywhere in-tree.
- `list-runs` defaults to `limit 50` server-side; the GUI never passes a
  limit, so the run picker silently sees only the newest 50.
- repl.clj:51's docstring cites "code-review-2026-08 #6"; the review doc
  numbers the eval-session leak #5.
- graph.clj `fold` docstring stale (run switch re-folds incrementally, not
  from scratch); mathtext.clj:65 `[#"\\blcm\\b" "lcm"]` is an identity
  replacement; textview's `set-text … -1` truncates at an embedded NUL.

## Prior-review verification

**docs/code-review-2026-08.md (this morning, a569705) — all seven fixes
present:**

- #1 `base/missing` wrapped in `base/malformed` at all four sites
  (skills.clj:39, manifest.clj:92,105-106). Intact. A repo-wide shape
  sweep this pass confirms every `defmethod base/run-tool` returns through
  `base/ok`/`base/malformed`/`base/fail` — no remaining raw-result paths.
- #2 retry-cap guard: `retry-after-ms` unclamped, ceiling moved into
  `backoff-ms` (client.clj:75-118), cap check reads the real ask (:219-233).
  Intact.
- #3 active-registry race: registration inside the run's own thread
  (control.clj:109, resume :183), `:future` key gone, `abort!` 409 path
  (:150-155). Intact — the *transient* variant is new issue 4 above.
- #4 lsp frame stealing: dedicated reader thread, promise-parked requests,
  `send-frame!` locking the stream. Intact (issues 9/10/17 are new
  neighbors, not regressions of it).
- #5 eval-session leak: `repl/close-session` idempotent, called from
  `workflow.clj`'s `finally` (:274-278). Intact.
- #6 GUI poller races: all six sub-fixes present; five correct — the
  single-flight CAS sub-fix is correct as far as it goes but introduces
  issue 7 above.
- #7 dead code: stays removed (repo-wide grep: `clip`, `:run-status`,
  `gui.api/health`, `gui.api/run-detail`, `glpane/hovered` — zero hits
  outside docs).

**docs/code-review.md (2026-05) — all five fixes intact:** quote-aware
`shell-split` + widened deny side (policy.clj:79,127-128); grant path via
`intervene!` → `grants/grant!`; claim-race guard
`(run_id IS NULL OR run_id = ?)` (tasks.clj:141) — re-verified line by
line this pass, with its regression test; lisp escaped-quote scan; fd
limit baked into the test task.

## Structural analysis summary

- Dead code: the six `samizdat.server` handlers dispatched via the routes
  data table (known false positives) — nothing new at the call-graph
  layer. New non-graph dead code found by hand: the claims registry
  (issue 12), five gates.edn keys (issue 13), `style/node-verts` 3-arg
  arity, `/v1/interventions/kinds`.
- Cycles: `decompose-node`/`solve` only — intentional tree recursion.
- Snapshot `review-2026-08b` saved (chiasmus cache) for next cycle's diff.
- Single-writer DB discipline verified clean repo-wide; SQL injection
  sweep clean (every value bound; FTS queries tokenized before quoting).

## Clean areas

- Store layer SQL/discipline overall (issues above are specific, not
  systemic); `events.clj`; `workflows.clj`; `grants.clj`; `migrations.clj`
  content and ordering.
- `samizdat.workflow` (re-read fully this pass), cells wiring —
  `resources/prompts/roles/{implementor,reviewer,supervisor}.md` all
  exist and are consumed by decompose/team/feature cells (a late
  subagent suspicion that the roles directory was empty was refuted).
- Tool result shapes: every `defmethod base/run-tool` returns through the
  base helpers; arg-validation sweep clean.
- GUI api/input/newrun/textview/mathtext (style.clj except the dead
  arity); endpoint cross-check — every GUI call has a matching route with
  matching method and response shape.
- LLM core (client/message/fence/adapter/registry), security engine,
  engine.proc, config, lisp, mutation, control, server, system — covered
  by this morning's full reads, unchanged since a569705 except where
  noted above.
