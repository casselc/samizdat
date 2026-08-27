;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evaluator-store-test
  (:require [clojure.test :refer [deftest is]]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as evaluator]))

(deftest evaluator-history-is-append-only-and-pending-is-absence
  (let [conn (db/open! ":memory:")
        identity {:spec-id "spec" :instance-id "instance" :binding-id "binding"
                  :context-spec "context" :runtime "runtime"}]
    (try
      (is (every? (set (db/table-names conn))
                  ["evaluator_evals" "evaluator_receipts" "evaluator_completions"]))
      (let [eval-id (evaluator/begin! conn (assoc identity :source "(project/read \"x\")"))]
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
