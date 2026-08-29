;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evaluator-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as evaluator]
            [samizdat.store.inference :as inference]
            [samizdat.store.runs :as runs]))

(def ^:private eval-identity
  {:spec-id "spec" :instance-id "instance" :binding-id "binding"
   :context-spec "context" :runtime "runtime"})

(defn- m3-fixture
  "Open a real run, register a durable (M3) evaluator binding under it, and
  mint one InferenceEpoch, returning [binding-id epoch-id].  The run exists
  because both the binding and the epoch carry a run_id that REFERENCES runs(id).
  The epoch's durable identity matches the eval identity (spec \"spec\", runtime
  \"runtime\") so a valid chain hands it straight through."
  [conn binding-id epoch-id]
  (let [rid (runs/start-run! conn {:problem "p" :max-turns 3})]
    (evaluator/register-binding!
     conn {:binding-id binding-id :run-id rid :work-id (subs binding-id 5)
           :instance-id (str "inst:" (subs binding-id 5)) :spec-id "spec"
           :context-spec {:context/coordinate "ctx"} :runtime "runtime"
           :orientation "SYSTEM / TRUSTED SURFACE" :orientation-digest "sha256:f"})
    (inference/begin!
     conn {:id epoch-id :run-id rid :branch-id "B1" :turn 1
           :provider :stub :model "m" :binding-id binding-id
           :spec-id "spec" :runtime "runtime"})
    [binding-id epoch-id]))

(defn- epoch-error [thunk]
  (:samizdat.evaluator/error
   (ex-data (try (thunk) nil (catch Throwable e e)))))

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

(deftest epoch-causal-chain-accepts-a-valid-chain
  ;; The exact M3 shape: the dispatch epoch rides the eval row and both receipt
  ;; rows, and every link resolves to the one binding/run.  The folded receipts
  ;; surface the epoch end to end.
  (let [conn (db/open! ":memory:")]
    (try
      (let [[binding-id epoch-id]
            (m3-fixture conn "bind:valid" "epoch:valid")
            ev (assoc eval-identity :binding-id binding-id)
            eval-id (evaluator/begin! conn (assoc ev :source "x"
                                                   :inference-epoch-id epoch-id))
            seqn (evaluator/intent! conn eval-id :project/read ["x"] epoch-id)]
        (evaluator/outcome! conn eval-id seqn {:result "x"} epoch-id)
        (evaluator/complete! conn eval-id :completed {:value "x"})
        (let [row (evaluator/load-eval conn eval-id)]
          (is (= epoch-id (:inference_epoch_id row)))
          (is (= [epoch-id] (mapv :inference-epoch-id (:receipts row))))
          (is (= :project/read (get-in row [:receipts 0 :op])))))
      (finally (db/close conn)))))

(deftest epoch-causal-chain-refuses-a-nil-epoch-under-a-durable-binding
  ;; A durable (M3) binding has registered an epoch stream; an eval whose
  ;; dispatch omitted the epoch is a nullable provenance gap and fails closed,
  ;; never records nil.
  (let [conn (db/open! ":memory:")]
    (try
      (let [[binding-id _] (m3-fixture conn "bind:nil" "epoch:nil")
            ev (assoc eval-identity :binding-id binding-id)]
        (is (= :missing-epoch
               (epoch-error #(evaluator/begin! conn (assoc ev :source "x"))))))
      (finally (db/close conn)))))

(deftest epoch-causal-chain-refuses-a-foreign-epoch
  ;; An epoch that resolves to a different binding/run is a fabricated
  ;; coordinate, refused before a row is written.
  (let [conn (db/open! ":memory:")]
    (try
      (let [[binding-id _] (m3-fixture conn "bind:foreign" "epoch:foreign")
            _ (m3-fixture conn "bind:other" "epoch:other")
            ev (assoc eval-identity :binding-id binding-id)]
        (testing "an epoch from another binding/run"
          (is (= :foreign-epoch
                 (epoch-error #(evaluator/begin!
                                conn (assoc ev :source "x"
                                             :inference-epoch-id "epoch:other"))))))
        (testing "an epoch that does not exist at all"
          (is (= :unknown-epoch
                 (epoch-error #(evaluator/begin!
                                conn (assoc ev :source "x"
                                             :inference-epoch-id "epoch:ghost")))))))
      (finally (db/close conn)))))

(deftest epoch-causal-chain-refuses-divergence-on-intent-and-outcome
  ;; Once an eval row fixes the epoch, the intent and the outcome must repeat
  ;; it exactly — a divergent or nil epoch on either is a caller-spoofed break
  ;; in the chain and fails closed.
  (let [conn (db/open! ":memory:")]
    (try
      (let [[binding-id epoch-id]
            (m3-fixture conn "bind:mismatch" "epoch:mismatch")
            _ (m3-fixture conn "bind:mismatch2" "epoch:mismatch2")
            ev (assoc eval-identity :binding-id binding-id)
            eval-id (evaluator/begin! conn (assoc ev :source "x"
                                                   :inference-epoch-id epoch-id))]
        (testing "intent with a foreign epoch"
          (is (= :epoch-divergence
                 (epoch-error #(evaluator/intent! conn eval-id :project/read
                                                  ["x"] "epoch:mismatch2")))))
        (testing "intent with a nil epoch"
          (is (= :epoch-divergence
                 (epoch-error #(evaluator/intent! conn eval-id :project/read
                                                  ["x"] nil)))))
        (let [seqn (evaluator/intent! conn eval-id :project/read ["x"] epoch-id)]
          (testing "outcome with a foreign epoch"
            (is (= :epoch-divergence
                   (epoch-error #(evaluator/outcome! conn eval-id seqn
                                                    {:result "x"} "epoch:mismatch2")))))
          (testing "outcome with a nil epoch"
            (is (= :epoch-divergence
                   (epoch-error #(evaluator/outcome! conn eval-id seqn
                                                    {:result "x"} nil)))))))
      (finally (db/close conn)))))

(deftest pre-m3-raw-seams-still-record-nil-epochs
  ;; Compatibility: a binding with no durable row (the pre-M3 raw store seam)
  ;; records nil epochs exactly as before — begin!/intent!/outcome! 3-arity —
  ;; so historical rows and replay keep loading.
  (let [conn (db/open! ":memory:")]
    (try
      (let [eval-id (evaluator/begin! conn (assoc eval-identity :source "x"))
            seqn (evaluator/intent! conn eval-id :project/read ["x"])]
        (evaluator/outcome! conn eval-id seqn {:result "x"})
        (is (= [{:seq 0 :op :project/read :args ["x"] :phase :done :result "x"}]
               (:receipts (evaluator/load-eval conn eval-id))))
        (is (nil? (:inference_epoch_id (evaluator/load-eval conn eval-id)))))
      (finally (db/close conn)))))
