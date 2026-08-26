;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.runs
  "Run and branch lifecycle.

  A run has an identity and a lifecycle independent of the HTTP request that
  started it, so `POST /v1/chat/completions` can block on one for OpenAI
  compatibility while `POST /v1/runs` starts one and returns. A branch is an
  entity with a durable id rather than a value threaded through the loop,
  because an intervention has to be able to name one."
  (:require [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]))

(defn start-run!
  "Open a run and return its id."
  [conn {:keys [problem provider model max-turns beam-width prompt-digest]}]
  (let [id (str (random-uuid))]
    ;; The retention sweep (review2 #11): events outlive their run's tail
    ;; window, then go — every durable table keeps the content.
    (journal/prune-finished!
     conn (str (.minusSeconds (java.time.Instant/now) (* 24 3600))))
    (db/with-writer
      (db/execute! conn
                     ["INSERT INTO runs (id, problem, status, provider, model, max_turns,
                                         beam_width, prompt_digest, started_at)
                       VALUES (?, ?, 'running', ?, ?, ?, ?, ?, ?)"
                      ;; The columns are NOT NULL DEFAULT '', and a DEFAULT does
                      ;; not apply to an explicitly-inserted NULL, so these
                      ;; coerce rather than relying on the schema.
                      id problem (if provider (name provider) "") (or model "")
                      (or max-turns 0) (or beam-width 1) (or prompt-digest "")
                      (db/now)]))
    (journal/note! conn id :run-started {:data {:problem problem :model model}})
    id))

(defn finish-run!
  "Terminal only from 'running', decided by the ROW rather than the caller
  (review2 #4): abort!'s transient window could overwrite a run that completed
  between its registry read and this write. Returns rows written; 0 means the
  run was already terminal and nothing changed, the journal says nothing."
  [conn run-id status final-answer]
  (let [n (db/with-writer
            (db/execute! conn
                         ["UPDATE runs SET status = ?, final_answer = ?, ended_at = ?
                            WHERE id = ? AND status = 'running'"
                          (name status) final-answer (db/now) run-id])
            (db/change-count conn))]
    (when (pos? n)
      (journal/note! conn run-id :run-finished {:data {:status status}}))
    n))

(defn finish-run-cancellation-fault!
  "Fail the run closed over an unquiesced turn worker, durably.

  Same terminal guard as finish-run! ('running' only, decided by the row),
  but the ONE UPDATE also sets terminal_reason: the resume refusal this
  failure grounds must survive the events retention sweep
  (journal/prune-finished!), and the runs row is never pruned. Two separate
  writes would leave a crash window in which the row read 'failed' with the
  refusal marker missing; one statement has no window. Journals
  :run-finished exactly as finish-run! does when the row lands. The fault's
  DETAILS (which branches, the grace) remain a :turn-cancellation-fault
  event for as long as the tail survives; the refusal bit does not expire
  with it. Returns rows written."
  [conn run-id]
  (let [n (db/with-writer
            (db/execute! conn
                         ["UPDATE runs SET status = 'failed', ended_at = ?,
                                           terminal_reason = 'turn-cancellation-fault'
                           WHERE id = ? AND status = 'running'"
                          (db/now) run-id])
            (db/change-count conn))]
    (when (pos? n)
      (journal/note! conn run-id :run-finished {:data {:status :failed}}))
    n))

(defn reconcile-orphans!
  "Mark every run still claiming to be running as interrupted. Returns how many.

  `status = 'running'` is written once when the beam opens and revisited only
  when it finishes, so a process that dies mid-run leaves the row asserting
  forever: it shows up in the run list and in any 'is anything active' check,
  and nothing in the row itself says otherwise. gen-18 and gen-11 both sat that
  way for days, and the second was filed as its own bug before anyone noticed
  it was the same defect — which is the argument for reconciling rather than
  correcting rows by hand each time.

  Sound only AT STARTUP, and that is the whole trick. In general a row cannot
  tell a live run from a dead one. But the beam only ever runs in this process,
  so at the moment this process comes up, nothing is running by construction
  and every such row is a leftover.

  `interrupted` rather than `failed` or `completed`: the run neither errored nor
  finished, and recording either would be a lie about what happened. `ended_at`
  is set because a run with no end time makes every duration computed from it
  wrong."
  [conn]
  (let [orphans (db/fetch conn ["SELECT id FROM runs WHERE status = 'running'"])]
    (doseq [{:keys [id]} orphans]
      (db/with-writer
        (db/execute! conn
                     ["UPDATE runs SET status = 'interrupted', ended_at = ?
                        WHERE id = ? AND status = 'running'"
                      (db/now) id]))
      (journal/note! conn id :run-finished
                     {:data {:status "interrupted"
                             :reason "no process was running it when the server started"}}))
    (count orphans)))

(defn mark-running!
  "Put a run back into 'running' and clear its end time.

  For resume. Before crashed runs were reconciled, a resumable row still read
  'running' from its original start, so nothing had to set this and nothing
  did. Reconciling to 'interrupted' made the omission visible: gen-19 resumed,
  took turns, and its row still said interrupted.

  Not cosmetic. `stalled?` only reports on a run whose status is 'running', so
  a resumed run that wedges would be invisible to the one check built to notice
  precisely that — and ended_at has to go with it, or the run carries an end
  time earlier than half its turns."
  [conn run-id]
  (db/with-writer
    (db/execute! conn
                 ["UPDATE runs SET status = 'running', ended_at = NULL
                    WHERE id = ? AND status NOT IN ('completed','aborted')"
                  run-id])
    (db/change-count conn)))

(defn get-run [conn run-id]
  (db/fetch-one conn ["SELECT * FROM runs WHERE id = ?" run-id]))

(defn last-progress-at
  "When this run last wrote anything to its journal, or nil if it never has.

  Every meaningful step emits an event, so this is the run's heartbeat without
  a heartbeat column: the id index gives it in one indexed row lookup, and
  ordering by id rather than created_at avoids trusting a clock for
  monotonicity."
  [conn run-id]
  (:created_at (db/fetch-one conn ["SELECT created_at FROM events WHERE run_id = ?
                                    ORDER BY id DESC LIMIT 1" run-id])))

(defn stalled?
  "True when a run still claims to be running but has been silent longer than
  `threshold-ms`.

  gen-11 crashed mid-round and sat at status 'running' with no events for the
  rest of the night, and nothing could tell that from a healthy slow round —
  both look like a row saying `running`. Silence alone is not enough (a Lean
  tactic legitimately buys minutes of it), so the threshold belongs above the
  turn deadline; silence plus a status nobody will ever update is the signal.

  Only ever reports on a RUNNING run. A finished one is quiet because it is
  over, which is not the same fault and must not raise the same alarm."
  [conn run-id threshold-ms]
  (let [r (get-run conn run-id)]
    (boolean
     (and r
          (= "running" (:status r))
          (when-let [t (or (last-progress-at conn run-id) (:started_at r))]
            (try
              (> (- (System/currentTimeMillis)
                    (.toEpochMilli (java.time.Instant/parse t)))
                 threshold-ms)
              ;; An unparseable timestamp is a storage fault, not evidence the
              ;; run is wedged. Say nothing rather than cry wolf.
              (catch Throwable _ false)))))))

(defn list-runs
  ([conn] (list-runs conn 50))
  ([conn limit]
   (db/fetch conn ["SELECT * FROM runs ORDER BY started_at DESC LIMIT ?" limit])))

(defn open-branch!
  [conn run-id {:keys [branch-id parent-id created-at-turn]}]
  (db/with-writer
    (db/execute! conn
                   ["INSERT INTO branches (id, run_id, parent_id, status, created_at_turn)
                     VALUES (?, ?, ?, 'active', ?)"
                    branch-id run-id parent-id (or created-at-turn 0)]))
  (journal/note! conn run-id :branch-opened
                 {:branch-id branch-id :data {:parent parent-id}})
  branch-id)

(defn close-branch!
  "Only from 'active' (review2 #4): a late close used to overwrite a closed
  branch's status and inactive_reason — the column the cull-honesty work
  exists to keep truthful. Returns rows written."
  [conn run-id branch-id status reason]
  (let [n (db/with-writer
            (db/execute! conn
                         ["UPDATE branches SET status = ?, inactive_reason = ?
                            WHERE run_id = ? AND id = ? AND status = 'active'"
                          (name status) reason run-id branch-id])
            (db/change-count conn))]
    (when (pos? n)
      (journal/note! conn run-id :branch-closed
                     {:branch-id branch-id :data {:status status :reason reason}}))
    n))

(defn extension-audit
  "The retained budget-extension audit row for `request-id`, or nil.

  The UNIQUE index makes request ids one-per-act, so this is also the
  idempotency probe: a row here means the request already landed, and the
  recorded outcome — not a second application — is the answer to a replay."
  [conn request-id]
  (db/fetch-one conn ["SELECT * FROM budget_extensions WHERE request_id = ?"
                      request-id]))

(defn extension-audit-for-run
  "Every retained budget-extension row for a run, oldest first: the run's
  durable budget history. Unlike events, these rows are never pruned —
  they ARE the record, not a tail of it."
  [conn run-id]
  (db/fetch conn ["SELECT * FROM budget_extensions WHERE run_id = ?
                   ORDER BY id" run-id]))

(defn reopen-branch!
  "An exhausted branch back to active — the budget-extension path. Branches
  closed for cause (culled, abandoned, done) are never reopened; exhaustion
  is the one closing reason that names the budget rather than the branch."
  [conn run-id branch-id]
  (db/with-writer
    (db/execute! conn ["UPDATE branches SET status = 'active', inactive_reason = NULL
                        WHERE run_id = ? AND id = ? AND status = 'exhausted'"
                       run-id branch-id]))
  (journal/note! conn run-id :branch-reopened {:branch-id branch-id}))

(defn extend-budget!
  "Raise a run's turn cap as ONE durable transaction: the runs row, the
  reopen of exactly the branches closed `exhausted`, and an append-only
  audit row land together or not at all.

  This is the atomic WRITE, not the policy. Callers settle everything this
  function assumes — the opaque controller authority, that the request id
  is unused, that the raise is monotonic and within the ceiling, that the
  run exists and is not terminal (samizdat.security.controller owns that
  ladder); the store adds the two guards a row can keep for itself:

  - old-max: the UPDATE only lands while the row still reads the cap the
    caller saw, so a concurrent raise loses here (as :stale) rather than
    double-applying;
  - status NOT IN ('completed','aborted') AND terminal_reason IS NULL:
    terminal rows never move again (review2 #4's rule), extension
    included — and terminal_reason is the retained terminal refusal (v13:
    failed closed over an unquiesced turn worker), so the backstop refuses
    exactly the rows resume refuses, while an ordinary failed row — NULL
    reason, the process simply died — still extends.

  The audit row is inserted FIRST, so a request-id collision from a racing
  writer rolls the whole transaction back and surfaces as a UNIQUE error
  the caller reads as a replay. Throws {:budget/error ...} on a lost
  guard; returns the change as a map for the audit trail's caller."
  [conn {:keys [run-id request-id principal old-max new-max reason]}]
  (db/with-transaction conn
    ;; Reserve the request id before touching anything: if another writer
    ;; already landed this exact request, the UNIQUE index throws here and
    ;; the ROLLBACK undoes nothing that happened.
    (db/execute! conn
                 ["INSERT INTO budget_extensions
                     (run_id, request_id, principal, old_max_turns,
                      new_max_turns, reason, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)"
                  run-id request-id principal old-max new-max reason (db/now)])
    (db/execute! conn
                 ["UPDATE runs SET max_turns = ?
                     WHERE id = ? AND max_turns = ?
                       AND status NOT IN ('completed','aborted')
                       AND terminal_reason IS NULL"
                  new-max run-id old-max])
    (when (zero? (db/change-count conn))
      (throw (ex-info (str "budget extension of " run-id " lost its guard: "
                           "the row no longer reads max_turns " old-max)
                      {:budget/error :stale
                       :run-id run-id :expected-old old-max})))
    ;; Exhaustion is the one closing reason that names the budget rather
    ;; than the branch, so more budget reopens exactly those — culled,
    ;; abandoned, and done branches stay closed whatever the cap does.
    (let [exhausted (mapv :id (db/fetch
                               conn
                               ["SELECT id FROM branches
                                  WHERE run_id = ? AND status = 'exhausted'"
                                run-id]))]
      (doseq [b exhausted]
        (reopen-branch! conn run-id b))
      ;; Journaled inside the transaction, so the narrative cannot claim an
      ;; extension that rolled back. The data names the act and the audit
      ;; key; it never carries the authority token (there is nothing a
      ;; token could add — the audit row already says who and why).
      (journal/note! conn run-id :budget-extended
                     {:data {:old old-max :new new-max
                             :request-id request-id :principal principal}})
      {:run-id run-id :request-id request-id :principal principal
       :old-max old-max :new-max new-max :reopened exhausted})))

(defn set-thesis!
  "The branch's current structural plan. Overwriting is allowed — committing to
  a different route is a legitimate move — and the change is journalled."
  [conn run-id branch-id thesis]
  (db/with-writer
    (db/execute! conn
                   ["UPDATE branches SET thesis = ? WHERE run_id = ? AND id = ?"
                    (json/write-str thesis) run-id branch-id]))
  (journal/note! conn run-id :thesis {:branch-id branch-id :data thesis}))

(defn branches [conn run-id]
  (db/fetch conn ["SELECT * FROM branches WHERE run_id = ? ORDER BY id" run-id]))

(defn get-branch [conn run-id branch-id]
  (db/fetch-one conn ["SELECT * FROM branches WHERE run_id = ? AND id = ?"
                        run-id branch-id]))
