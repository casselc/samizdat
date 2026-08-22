;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry-test
  "The run-health digest the supervisor introspects on — pure over journal rows."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.telemetry :as telemetry]))

(defn- row [branch turn tool cat]
  {:branch_id branch :turn turn :tool_name tool :category cat})

(deftest branch-health-counts-turns-mechanics-and-shipped
  (let [rows [(row "W0" 1 "read_file" "neutral")
              (row "W0" 2 "__no_call__" "mechanics")
              (row "W0" 3 "done" "success")
              (row "W1" 1 "__no_call__" "mechanics")
              (row "W1" 2 "__no_call__" "mechanics")]
        h (telemetry/branch-health rows)]
    (is (= 3 (get-in h ["W0" :turns])))
    (is (= 1 (get-in h ["W0" :mechanics])))
    (is (true? (get-in h ["W0" :shipped?])))
    (is (= 2 (get-in h ["W1" :mechanics])))
    (is (false? (get-in h ["W1" :shipped?])))
    (is (== 1.0 (get-in h ["W1" :mechanics-rate])))))

(deftest signals-flags-nothing-shipped
  (let [facts {:results [{:status :exhausted} {:status :abandoned}] :revision 0}]
    (is (some #(str/includes? % "NO IMPLEMENTOR SHIPPED")
              (telemetry/signals facts {})))))

(deftest signals-flags-thrash-reviewer-bounce-and-revising
  (let [facts {:results [{:status :done}] :review :revise :revision 2}
        health {"W0" {:turns 6 :mechanics 3 :mechanics-rate 0.5 :shipped? true}}
        sigs (telemetry/signals facts health)]
    (is (some #(str/includes? % "THRASH") sigs))
    (is (some #(str/includes? % "REVIEWER BOUNCED") sigs))
    (is (some #(str/includes? % "REVISING") sigs))))

(deftest signals-quiet-on-a-healthy-run
  (let [facts {:results [{:status :done} {:status :done}] :review :pass :revision 0}
        health {"W0" {:turns 3 :mechanics 0 :mechanics-rate 0.0 :shipped? true}}]
    (is (empty? (telemetry/signals facts health)))))

(deftest digest-renders-outcomes-decisions-and-signals
  (let [d (telemetry/digest {:results [{:status :done} {:status :exhausted}]
                             :review :pass :critic :ship :revision 1}
                            [(row "W0" 1 "done" "success")])]
    (is (str/includes? d "1/2 shipped"))
    (is (str/includes? d "Reviewer: pass"))
    (is (str/includes? d "Critic: ship"))
    (is (str/includes? d "revision 1"))))
