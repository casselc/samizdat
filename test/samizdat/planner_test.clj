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

(deftest parse-plan-reads-the-answer-not-the-thinking
  ;; karamazov-6a3, observed live: the prompt says "no preamble", but GLM
  ;; opened with an analysis monologue whose OWN bullets came first — a list
  ;; of stack nouns it was reasoning about — and the parser harvested those,
  ;; then (take max-parts) dropped the real parts list entirely. Four workers
  ;; were tasked with fragments of the planner's musing. The answer a model
  ;; formats last is the answer; the last contiguous bullet block is what the
  ;; prompt asked it to end with.
  (testing "preamble bullets are not the plan — the final block is"
    (is (= ["build the storage layer" "build the handlers"]
           (planner/parse-plan
            (str "Let me think about the stack first:\n"
                 "- jolt (build tool? something like Leiningen)\n"
                 "- ring-chez-adapter — hmm, Chez Scheme?\n"
                 "\nOK. The split:\n"
                 "- build the storage layer\n"
                 "- build the handlers")
            4))))
  (testing "max-parts bounds the FINAL block, not the whole reply"
    (is (= ["a" "b"]
           (planner/parse-plan
            "- noise one\n- noise two\n- noise three\n\nParts:\n- a\n- b" 2))))
  (testing "a reply that is one contiguous list still parses whole"
    (is (= ["x" "y" "z"] (planner/parse-plan "- x\n- y\n- z" 4)))))
