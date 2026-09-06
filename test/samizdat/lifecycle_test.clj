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

(ns samizdat.lifecycle-test
  "The decision lifecycle store (issue #11, first slice): one transaction per
  transition, idempotent retries, compare-and-set freshness, a legal-successor
  table, a lease fence on dispatch, and a bounded read by durable sequence."
  (:require [clojure.test :refer [deftest testing is]]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [samizdat.store.db :as db]
            [samizdat.store.lifecycle :as lc]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(defn- propose! [c id]
  (lc/upsert-case! c {:case-id "case-1" :scenario-id "s80" :checkpoint-id "ck-1" :spec {:k 1}})
  (lc/transition! c {:decision-id id :event-id (str id "/proposed") :event-type "action-proposed"
                     :revision 0 :case-id "case-1" :evaluation-id "eval-1"
                     :action-id "request-check" :action-params {:check "s80"} :origin "forced"}))

(deftest migration-creates-the-lifecycle-tables
  (with-db [c]
    (is (every? (set (db/table-names c))
                ["pilot_cases" "pilot_decisions" "decision_events" "pilot_leases"]))))

(deftest a-case-is-immutable
  (with-db [c]
    (is (= :created (:status (lc/upsert-case! c {:case-id "x" :scenario-id "s" :checkpoint-id "k" :spec {:a 1}}))))
    (is (= :existing (:status (lc/upsert-case! c {:case-id "x" :scenario-id "s" :checkpoint-id "k" :spec {:a 1}}))))
    (is (thrown? Exception (lc/upsert-case! c {:case-id "x" :scenario-id "s" :checkpoint-id "k" :spec {:a 2}})))
    (is (= {:a 1} (:spec (lc/case-by-id c "x"))))))

(deftest a-retried-write-does-not-duplicate-the-event
  (with-db [c]
    (let [first-write (propose! c "d1")
          retry (lc/transition! c {:decision-id "d1" :event-id "d1/proposed" :event-type "action-proposed"
                                   :revision 0 :case-id "case-1" :evaluation-id "eval-1"})]
      (is (= :applied (:status first-write)))
      (is (= :duplicate (:status retry)))
      (is (= (:event-seq first-write) (:event-seq retry)))
      (is (= 1 (count (lc/decision-events c))))
      (is (= 1 (:revision (lc/decision c "d1")))))))

(deftest a-stale-revision-writes-nothing
  (with-db [c]
    (propose! c "d1")
    (lc/transition! c {:decision-id "d1" :event-id "d1/forced" :event-type "action-forced" :revision 1})
    (let [r (lc/transition! c {:decision-id "d1" :event-id "d1/forced-again" :event-type "action-forced" :revision 1})]
      (is (= :stale (:status r)))
      (is (= 2 (:expected r)))
      (is (= 2 (count (lc/decision-events c))))
      (is (= "chosen" (:state (lc/decision c "d1")))))))

(deftest an-illegal-successor-writes-nothing
  (with-db [c]
    (propose! c "d1")
    (let [r (lc/transition! c {:decision-id "d1" :event-id "d1/completed" :event-type "action-completed" :revision 1})]
      (is (= :illegal (:status r)))
      (is (= 1 (count (lc/decision-events c))))
      (is (= "proposed" (:state (lc/decision c "d1")))))
    (testing "a returned action is not a completed effect: completion needs dispatch first"
      (is (nil? (get-in lc/transitions ["chosen" "action-completed"])))
      (is (nil? (get-in lc/transitions ["authorized" "action-completed"]))))
    (testing "only the evaluator's event leaves a terminal execution state"
      (is (= "evaluated" (get-in lc/transitions ["completed" "outcome-evaluated"])))
      (is (= "evaluated" (get-in lc/transitions ["interrupted" "outcome-evaluated"])))
      (is (empty? (get lc/transitions "evaluated"))))))

(deftest dispatch-is-fenced-by-the-lease
  (with-db [c]
    (propose! c "d1")
    (lc/transition! c {:decision-id "d1" :event-id "d1/forced" :event-type "action-forced" :revision 1})
    (lc/transition! c {:decision-id "d1" :event-id "d1/authorized" :event-type "action-authorized" :revision 2})
    (testing "no lease: no dispatch intent"
      (is (= :unleased (:status (lc/transition! c {:decision-id "d1" :event-id "d1/intent" :event-type "dispatch-intent"
                                                   :revision 3 :lease-holder "orch-A"})))))
    (is (= :acquired (:status (lc/acquire-lease! c "case-1" "orch-A" 60000))))
    (testing "a second orchestrator cannot take a live lease"
      (is (= :held (:status (lc/acquire-lease! c "case-1" "orch-B" 60000)))))
    (testing "the holder records intent before the effect, then the dispatch"
      (is (= :applied (:status (lc/transition! c {:decision-id "d1" :event-id "d1/intent" :event-type "dispatch-intent"
                                                  :revision 3 :lease-holder "orch-A"}))))
      (is (= :unleased (:status (lc/transition! c {:decision-id "d1" :event-id "d1/dispatched-B" :event-type "action-dispatched"
                                                   :revision 4 :lease-holder "orch-B"}))))
      (is (= :applied (:status (lc/transition! c {:decision-id "d1" :event-id "d1/dispatched" :event-type "action-dispatched"
                                                  :revision 4 :lease-holder "orch-A"})))))
    (testing "an expired lease is taken over with a bumped revision"
      (jdbc/execute! c ["UPDATE pilot_leases SET expires_at = '2000-01-01T00:00:00Z' WHERE scope = 'case-1'"])
      (let [r (lc/acquire-lease! c "case-1" "orch-B" 60000)]
        (is (= :acquired (:status r)))
        (is (= 2 (:revision r)))
        (is (= "orch-A" (:took-over r)))))
    (testing "interrupted stays distinguishable from completed"
      (is (= :applied (:status (lc/transition! c {:decision-id "d1" :event-id "d1/interrupted" :event-type "action-interrupted"
                                                  :revision 5}))))
      (is (= "interrupted" (:state (lc/decision c "d1"))))
      (is (= :illegal (:status (lc/transition! c {:decision-id "d1" :event-id "d1/completed" :event-type "action-completed"
                                                  :revision 6})))))))

(deftest a-crash-between-the-event-and-the-state-update-leaves-neither
  ;; The event row and the current-state update are one transaction. Inject a
  ;; failure into the UPDATE that follows the INSERT and require that the
  ;; event did not survive on its own: a reader of the sequence and a reader
  ;; of state could otherwise disagree forever.
  (with-db [c]
    (propose! c "d1")
    (let [real jdbc/execute!
          boom (fn [conn q & more]
                 (when (and (vector? q) (re-find #"UPDATE pilot_decisions" (first q)))
                   (throw (ex-info "injected crash after the event insert" {})))
                 (apply real conn q more))]
      (with-redefs [jdbc/execute! boom]
        (is (thrown? Exception
                     (lc/transition! c {:decision-id "d1" :event-id "d1/forced" :event-type "action-forced" :revision 1}))))
      (is (= 1 (count (lc/decision-events c))) "the event insert was rolled back")
      (is (= "proposed" (:state (lc/decision c "d1"))))
      (is (= 1 (:revision (lc/decision c "d1"))))
      (testing "and the same event-id can be presented again afterwards"
        (is (= :applied (:status (lc/transition! c {:decision-id "d1" :event-id "d1/forced" :event-type "action-forced" :revision 1}))))))))

(deftest events-read-in-bounded-pages-by-sequence
  (with-db [c]
    (propose! c "d1")
    (propose! c "d2")
    (lc/transition! c {:decision-id "d1" :event-id "d1/forced" :event-type "action-forced" :revision 1})
    (lc/transition! c {:decision-id "d2" :event-id "d2/selected" :event-type "action-selected" :revision 1})
    (let [page1 (lc/decision-events c {:after 0 :limit 2})
          page2 (lc/decision-events c {:after (:event_seq (last page1)) :limit 2})
          page3 (lc/decision-events c {:after (:event_seq (last page2)) :limit 2})]
      (is (= [1 2] (map :event_seq page1)))
      (is (= [3 4] (map :event_seq page2)))
      (is (empty? page3))
      (is (= ["action-proposed" "action-forced"]
             (map :event_type (lc/decision-events c {:decision-id "d1"}))))
      (is (= {:check "s80"} (-> (lc/decision c "d1") :action_params))))))
