;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.planner-test
  "The pure planner: build the split prompt, parse the model's part list."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.planner :as planner]))

(deftest plan-prompt-carries-the-problem-and-the-bound
  (let [p (planner/plan-prompt "add a CSV exporter" 3)]
    (is (str/includes? p "add a CSV exporter"))
    (is (str/includes? p "3"))
    (is (str/includes? p "parallel"))))

(deftest parse-plan-reads-a-bullet-list
  (is (= ["parse the header" "stream the rows" "write the footer"]
         (planner/parse-plan "- parse the header\n- stream the rows\n- write the footer" 4))))

(deftest parse-plan-reads-numbered-and-starred-lists
  (is (= ["a" "b"] (planner/parse-plan "1. a\n2) b" 4)))
  (is (= ["a" "b"] (planner/parse-plan "* a\n* b" 4))))

(deftest parse-plan-ignores-prose-around-the-list
  (is (= ["do X" "do Y"]
         (planner/parse-plan "Here is the split:\n\n- do X\n- do Y\n\nThat covers it." 4))))

(deftest parse-plan-bounds-to-max-parts
  (is (= ["a" "b"] (planner/parse-plan "- a\n- b\n- c\n- d" 2))))

(deftest parse-plan-is-nil-when-there-is-no-list
  (testing "no bullets -> nil, so the caller keeps the whole problem as one worker"
    (is (nil? (planner/parse-plan "This is one indivisible task." 4)))
    (is (nil? (planner/parse-plan nil 4)))
    (is (nil? (planner/parse-plan "" 4)))))

(deftest parse-plan-drops-blank-and-whitespace-bullets
  (is (= ["real"] (planner/parse-plan "- real\n- \n-    " 4))))
