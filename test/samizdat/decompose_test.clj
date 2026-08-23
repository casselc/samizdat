;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.decompose-test
  "The pure decompose-on-stuck core: the architect prompt and decision parsing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.decompose :as dec]))

(deftest architect-prompt-carries-the-evidence
  (let [p (dec/architect-prompt {:problem "gate the remember tool"
                                 :tests "(deftest ...)"}
                                {:attempts 3 :last-failure "AssertionError: expected refusal"
                                 :depth 0})]
    (is (str/includes? p "gate the remember tool"))
    (is (str/includes? p "AssertionError"))
    (is (str/includes? p "DECOMPOSE"))
    (is (str/includes? p "FRESH_APPROACH"))))

(deftest architect-prompt-forces-fresh-approach-at-the-depth-edge
  (let [p (dec/architect-prompt {:problem "x"} {:depth (dec dec/max-depth)})]
    (is (str/includes? p "MUST choose FRESH_APPROACH"))))

(deftest parse-decision-reads-a-decompose
  (let [d (dec/parse-decision
           "{\"decision\":\"decompose\",\"reason\":\"two jobs\",\"subtasks\":[
              {\"name\":\"detect-completion\",\"description\":\"match the content against completion words\"},
              {\"name\":\"gate-on-diff\",\"description\":\"refuse when the tree changed nothing\"}]}"
           0)]
    (is (= :decompose (:kind d)))
    (is (= ["detect-completion" "gate-on-diff"] (mapv :name (:subtasks d))))
    (is (every? :description (:subtasks d)))))

(deftest parse-decision-reads-a-fresh-approach
  (let [d (dec/parse-decision
           "{\"decision\":\"fresh_approach\",\"reason\":\"wrong strategy\",\"hint\":\"regex, not substring\"}")]
    (is (= :fresh-approach (:kind d)))
    (is (= "regex, not substring" (:hint d)))))

(deftest parse-decision-honours-the-depth-budget
  (testing "a decompose too deep degrades to a fresh approach, not a split"
    (let [d (dec/parse-decision
             "{\"decision\":\"decompose\",\"subtasks\":[{\"name\":\"a\",\"description\":\"x\"}]}"
             (dec dec/max-depth))]
      (is (= :fresh-approach (:kind d)) "no split at the depth edge"))))

(deftest parse-decision-degrades-a-subtaskless-decompose
  (let [d (dec/parse-decision "{\"decision\":\"decompose\",\"subtasks\":[]}" 0)]
    (is (= :fresh-approach (:kind d)))))

(deftest parse-decision-is-nil-on-junk
  (is (nil? (dec/parse-decision "no json here" 0)))
  (is (nil? (dec/parse-decision "" 0))))

(deftest parse-decision-ignores-prose-around-the-json
  (let [d (dec/parse-decision
           "Here is my call:\n{\"decision\":\"fresh_approach\",\"hint\":\"try recursion\"}\nDone.")]
    (is (= :fresh-approach (:kind d)))
    (is (= "try recursion" (:hint d)))))
