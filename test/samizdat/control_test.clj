;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.control-test
  "Steering a running agent from the REPL. A human submits a directive against
  the run's db; the loop drains it at the next boundary and the arbiter injects
  it into the branch's next turn at priority zero, above every machine gate.
  The specification test drives a real run and asserts a REPL steer lands in
  the model's context."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.control :as control]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.state :as state]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.runs :as runs]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest steer-queues-a-message-directive
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (control/steer! c rid "wire truncate-middle into the shell tool")
      (let [[d] (interventions/pending c rid)]
        (is (= "message" (:kind d)))
        (is (str/includes? (str (:payload d)) "truncate-middle"))
        (is (= "pending" (:status d)))))))

(deftest list-and-run-scoped-viewers
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (control/steer! c rid "do the thing")
      (control/steer! c rid "then the other thing" {:branch-id "B1"})
      (is (= 2 (count (control/pending c rid))))
      (is (= ["do the thing" "then the other thing"]
             (mapv :payload (control/pending c rid)))))))

;; --- the drain at the boundary ----------------------------------------------

(deftest a-pending-directive-is-drained-and-injected
  ;; A directive submitted before a turn boundary is drained by the loop, the
  ;; arbiter fires the human-directive gate at priority zero, and the payload
  ;; lands in the branch's next-turn message. Then it is resolved as applied.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          _ (runs/open-branch! c rid {:branch-id "B1"})
          ctx {:conn c :run-id rid :max-turns 10
               :llm-adapter :a :llm-config {:max-tokens 16384}}
          b (state/new-branch {:id "B1" :problem "p"})]
      (control/steer! c rid "STEER: add a docstring to truncate-middle")
      (with-redefs [llm/chat (fn [& _]
                               {:content "```tool-call\n{\"name\": \"task\", \"args\": {\"action\": \"list\"}}\n```"
                                :finish-reason "stop"})]
        (let [after (aloop/run-turn ctx b 1)
              last-msg (last (:messages after))]
          (testing "the directive text is injected into the next-turn message"
            (is (str/includes? (:content last-msg) "human has intervened"))
            (is (str/includes? (:content last-msg) "add a docstring to truncate-middle")))
          (testing "the directive is resolved as applied, not left pending"
            (is (empty? (interventions/pending c rid)))
            (is (= "applied" (:status (first (interventions/history c rid)))))))))))
