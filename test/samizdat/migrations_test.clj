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

(ns samizdat.migrations-test
  "The retained-record migrations (v12, v13), on their own.

  store-test already pins the migration machinery as a whole (statement
  counting, idempotency, rollback); this namespace pins what the new
  migrations specifically must give: a retained, append-only audit table
  whose idempotency key is structural — enforced by a UNIQUE index, not by
  caller discipline — and a retained terminal-refusal column on the runs
  row, both of which survive the events retention sweep. That survival is
  the difference between a durable record and a tail of one."
  (:require [clojure.test :refer [deftest testing is]]
            [jdbc.core :as jdbc]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.migrations :as migrations]
            [samizdat.store.runs :as runs]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest the-budget-extension-audit-table-migrates
  (with-db [c]
    (is (= (count migrations/migrations) (db/schema-version c))
        "v12 is applied and counted")
    (is (contains? (set (db/table-names c)) "budget_extensions")
        "the audit table exists")
    (testing "re-running migrations changes nothing"
      (let [v (db/schema-version c)]
        (db/migrate! c)
        (is (= v (db/schema-version c)))))
    (testing "the idempotency index is on request_id alone"
      ;; One request id names one extension act, ever — across runs, not
      ;; per run — so the index must cover the whole table.
      (is (= ["idx_budget_extensions_request"]
             (mapv :name
                   (jdbc/fetch c ["SELECT name FROM sqlite_master
                                    WHERE type = 'index'
                                      AND tbl_name = 'budget_extensions'
                                      AND name NOT LIKE 'sqlite_%'"])))))))

(deftest one-request-id-names-one-extension-structurally
  ;; Idempotency that lives in the schema cannot be forgotten by a caller.
  ;; The second insert with the same request_id must fail loudly (UNIQUE)
  ;; whatever the rows say — different runs, different caps, anything.
  (with-db [c]
    (let [r1 (runs/start-run! c {:problem "one" :max-turns 5 :beam-width 1})
          r2 (runs/start-run! c {:problem "two" :max-turns 10 :beam-width 1})]
      (jdbc/execute! c ["INSERT INTO budget_extensions
                           (run_id, request_id, principal, old_max_turns,
                            new_max_turns, reason, created_at)
                         VALUES (?, 'req-1', 'controller', 5, 10, 'why', ?)"
                        r1 (db/now)])
      (is (thrown-with-msg? Exception #"UNIQUE"
                            (jdbc/execute! c
                                           ["INSERT INTO budget_extensions
                                               (run_id, request_id, principal,
                                                old_max_turns, new_max_turns,
                                                reason, created_at)
                                             VALUES (?, 'req-1', 'controller',
                                                     10, 20, 'why', ?)"
                                            r2 (db/now)]))
          "even a DIFFERENT run cannot reuse a landed request id"))))

(deftest the-audit-is-retained-not-tailed
  ;; The events sweep (review2 #11) prunes finished runs' events after the
  ;; retention window. An extension audit that rode the events table would
  ;; be quietly deleted by the next run's start — which is why the audit is
  ;; its own table. The sweep must leave it alone.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1})]
      (runs/extend-budget! c {:run-id rid :request-id "req-retained"
                              :principal "controller" :old-max 5 :new-max 9
                              :reason "retention fixture"})
      (runs/finish-run! c rid :completed "done")
      ;; Age the run past the 24h window, then let the next start sweep.
      (db/execute! c ["UPDATE runs SET ended_at = ? WHERE id = ?"
                      (str (.minusSeconds (java.time.Instant/now) (* 48 3600)))
                      rid])
      (runs/start-run! c {:problem "next"})
      (is (zero? (:n (db/fetch-one c ["SELECT COUNT(*) AS n FROM events
                                       WHERE run_id = ?" rid])))
          "the run's events are gone with the window")
      (is (= 1 (count (runs/extension-audit-for-run c rid)))
          "and the budget audit is still there, entire"))))

(deftest the-terminal-reason-column-migrates
  ;; v13: the retained form of a terminal refusal. The events sweep prunes
  ;; a finished run's events after the window, so a refusal recorded only
  ;; as an event (the first turn-cancellation-fault shape) quietly
  ;; re-opened the run to resume once its tail was gone — while the stale
  ;; worker the fault names could still exist. The runs row is never
  ;; pruned; the refusal bit lives there.
  (with-db [c]
    (is (= (count migrations/migrations) (db/schema-version c))
        "v13 is applied and counted")
    (is (some #(= "terminal_reason" (str (:name %)))
              (jdbc/fetch c ["PRAGMA table_info(runs)"]))
        "the runs row can carry a retained terminal reason")
    (testing "re-running migrations changes nothing"
      (let [v (db/schema-version c)]
        (db/migrate! c)
        (is (= v (db/schema-version c)))))
    (testing "an unfaulted run reads NULL, not a refusal"
      (let [rid (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1})]
        (runs/finish-run! c rid :completed "done")
        (is (nil? (:terminal_reason (runs/get-run c rid)))
            "an ordinary finish leaves the refusal column empty")))))

(deftest the-audit-row-carries-the-act-not-the-credential
  ;; run/request/principal/old/new/reason/timestamp — the audit says who
  ;; and why, and must never have a column where a token could hide.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1})]
      (runs/extend-budget! c {:run-id rid :request-id "req-fields"
                              :principal "operator" :old-max 5 :new-max 7
                              :reason "close but out of budget"})
      (let [[row] (runs/extension-audit-for-run c rid)]
        (is (= rid (:run_id row)))
        (is (= "req-fields" (:request_id row)))
        (is (= "operator" (:principal row)))
        (is (= 5 (:old_max_turns row)))
        (is (= 7 (:new_max_turns row)))
        (is (= "close but out of budget" (:reason row)))
        (is (some? (:created_at row)) "the timestamp is part of the record")
        (is (= #{:id :run_id :request_id :principal :old_max_turns
                 :new_max_turns :reason :created_at}
               (set (keys row)))
            "no column exists that could carry a credential")))))
