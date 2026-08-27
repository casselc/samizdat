;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evaluator-store-test
  (:require [clojure.test :refer [deftest is]]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as evaluator]))

(def ^:private eval-identity
  {:spec-id "spec" :instance-id "instance" :binding-id "binding"
   :context-spec "context" :runtime "runtime"})

(deftest evaluator-history-is-append-only-and-pending-is-absence
  (let [conn (db/open! ":memory:")]
    (try
      (is (every? (set (db/table-names conn))
                  ["evaluator_evals" "evaluator_receipts" "evaluator_completions"]))
      (let [eval-id (evaluator/begin! conn (assoc eval-identity :source "(project/read \"x\")"))]
        (is (= :pending (:status (evaluator/load-eval conn eval-id))))
        (let [seqn (evaluator/intent! conn eval-id :project/read ["x"])]
          (is (thrown? Exception
                       (evaluator/complete! conn eval-id :completed {:value "x"})))
          (evaluator/outcome! conn eval-id seqn {:result "x"})
          (evaluator/complete! conn eval-id :completed {:value "x"})
          (is (= [{:seq 0 :op :project/read :args ["x"]
                   :phase :done :result "x"}]
                 (:receipts (evaluator/load-eval conn eval-id))))
          (is (thrown? Exception
                       (evaluator/outcome! conn eval-id seqn {:result "changed"})))
          (is (= [0] (mapv :binding_seq (evaluator/history conn "binding"))))))
      (finally (db/close conn)))))

(deftest begin-last-insert-id-stays-inside-the-writer-section
  ;; db serializes ALL connection access through one global `locking` monitor
  ;; (db/with-conn), and jolt monitors are per-thread reentrant — begin!
  ;; itself relies on that, nesting with-conn reads inside its with-writer.
  ;; last_insert_rowid() answers for the CONNECTION, not the statement, so
  ;; begin!'s INSERT and its last-insert-id read must hold the monitor
  ;; continuously: hoisted out of the section, any writer that lands between
  ;; them hands begin! back somebody else's row id.
  ;;
  ;; This parks thread A inside last-insert-id (post-INSERT, pre-return) and
  ;; proves a second writer cannot even enter its section until A releases —
  ;; and that the id A returns names A's own row.
  (let [conn (db/open! ":memory:")]
    (try
      (let [entered (promise)
            release (promise)
            park-first (atom true)
            original db/last-insert-id]
        (with-redefs [db/last-insert-id
                      (fn [c]
                        (when (compare-and-set! park-first true false)
                          (deliver entered :inside)
                          (deref release 15000 ::release-timeout))
                        (original c))]
          (let [f-a (future (evaluator/begin! conn (assoc eval-identity :source "A")))]
            (is (= :inside (deref entered 15000 ::entered-timeout))
                "writer A reached the last-insert-id read inside begin!")
            ;; A is now parked between INSERT and last_insert_rowid().
            (let [f-b (future (evaluator/begin! conn (assoc eval-identity :source "B")))]
              (Thread/sleep 150) ;; let B run up against the monitor
              (is (= ::not-done (deref f-b 500 ::not-done))
                  "writer B stays out of its section while A holds the monitor through last-insert-id")
              (deliver release :go)
              (let [id-a (deref f-a 15000 ::a-timeout)
                    id-b (deref f-b 15000 ::b-timeout)]
                (is (not= id-a id-b) "concurrent begins never share an id")
                (is (= "A" (:source (evaluator/load-eval conn id-a)))
                    "the id begin! returned is the id of ITS OWN insert")
                (is (= "B" (:source (evaluator/load-eval conn id-b)))))))))
      (finally (db/close conn)))))

(deftest begin-last-insert-id-returns-own-row-under-concurrency
  ;; The stress half: many writers racing on the one shared connection, every
  ;; returned id checked against the row it actually names.
  (let [conn (db/open! ":memory:")]
    (try
      (let [tasks (mapv (fn [t]
                          (future
                            (mapv (fn [j]
                                    (let [src (str "(thread-" t "-eval-" j ")")]
                                      [(evaluator/begin! conn (assoc eval-identity :source src))
                                       src]))
                                  (range 25))))
                        (range 8))
            pairs (into [] (mapcat #(deref % 60000 ::timeout)) tasks)]
        (is (= 200 (count pairs)) "no writer lost or duplicated its begin!")
        (is (= 200 (count (distinct (map first pairs))))
            "every returned id is unique")
        (doseq [[id src] pairs]
          (is (= src (:source (evaluator/load-eval conn id)))
              "every id names the row its own begin! inserted")))
      (finally (db/close conn)))))
