;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.team-test
  "Multi-agent fan-out: the team manifest runs a worker per sub-task in
  parallel and joins their answers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.workflow :as workflow]))

(defn- worker-dones-its-task
  "A worker that immediately ships an answer engaging its own sub-task, so the
  done-gate accepts it."
  [_ _ messages & _]
  (let [content (str/join " " (map :content messages))
        prob (or (second (re-find #"## Problem\s+(\w+)" content)) "task")]
    {:content (str "```tool-call\n{\"name\":\"done\",\"args\":{\"answer\":\"handled "
                   prob "\"}}\n```")
     :finish-reason "stop"}))

(deftest team-fans-out-a-worker-per-subtask-and-joins
  (with-redefs [llm/chat worker-dones-its-task]
    (let [conn (db/open! ":memory:")
          r (workflow/run! {:conn conn
                            :config {:run {:loop "team" :subtasks ["alpha" "beta"]}}
                            :llm-adapter :a :llm-config {:max-tokens 16384}
                            :problem "the feature" :max-turns 6})]
      (is (= :completed (:status r)))
      (testing "each sub-task ran on its own worker branch on the shared run"
        (is (= #{"W0" "W1"}
               (set (map :branch_id
                         (db/fetch conn ["SELECT DISTINCT branch_id FROM turns"]))))))
      (testing "the manager joins both workers' answers"
        (is (str/includes? (:answer r) "alpha"))
        (is (str/includes? (:answer r) "beta"))
        (is (str/includes? (:answer r) "2 workers"))))))

(deftest team-with-no-subtasks-is-one-worker-on-the-whole-problem
  (with-redefs [llm/chat worker-dones-its-task]
    (let [conn (db/open! ":memory:")
          r (workflow/run! {:conn conn :config {:run {:loop "team"}}
                            :llm-adapter :a :llm-config {:max-tokens 16384}
                            :problem "solo" :max-turns 6})]
      (is (= :completed (:status r)))
      (is (str/includes? (:answer r) "1 worker")))))
