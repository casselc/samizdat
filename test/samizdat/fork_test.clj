;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.fork-test
  "What a forked branch inherits (LR-1).

  Every child used to open on a fresh [system, problem] tape, so a fork threw
  away everything its parent had learned — while the crossover block's own
  prose claimed the child carried its parent's history. These pin the split:
  the CONVERSATION is inherited, every gate counter is not."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.agent.state :as state]
            [samizdat.tape :as tape]))

(defn- parent-branch
  "A parent mid-run: eight messages over three turns, and a gate record that
  says it was doing badly."
  []
  (-> (state/new-branch {:id "B1" :problem "solve it"
                         :messages [{:role "system" :content "SYS"}
                                    {:role "user" :content "## Problem"}]})
      (state/add-message "assistant" "turn 1 reasoning" {:turn 1})
      (state/add-message "user" "result 1" {:turn 1})
      (state/add-turn {:turn 1 :tool "eval" :category :success})
      (state/add-message "assistant" "turn 2 reasoning" {:turn 2})
      (state/add-message "user" "result 2" {:turn 2})
      (state/add-turn {:turn 2 :tool "shell" :category :failure :error "boom"})
      (state/add-message "assistant" "turn 3 reasoning" {:turn 3})
      (state/add-message "user" "result 3" {:turn 3})
      (state/add-turn {:turn 3 :tool "shell" :category :failure :error "boom"})
      (assoc :consecutive-failures 2
             :turns-since-progress 5
             :any-progress? true
             :abandoned ["the greedy approach"]
             :artifacts [{:claim "a bound" :claim-status :confirmed :turn 2}]
             :mechanics {:calls 9 :parse-errors 3})))

(deftest a-child-inherits-the-whole-conversation-by-default
  (let [p (parent-branch)
        c (state/fork-branch p {:id "B1.2" :turn 4})]
    (is (= (:messages p) (:messages c))
        "the conversation is what a fork is FOR — re-deriving it from the problem was the bug")
    (is (= "B1" (:parent-id c)))
    (is (= 8 (:forked-at c)) "the branch point, without which the tree edge is lossy")
    (is (= "solve it" (:problem c)))))

(deftest a-child-can-branch-an-older-turn
  (let [p (parent-branch)
        c (state/fork-branch p {:id "B1.2" :depth 4 :turn 4})]
    (is (= 4 (tape/depth (:messages c))))
    (is (= (take 4 (:messages p)) (:messages c))
        "the parent as it was before the mistake, not from zero")
    (is (= 8 (tape/depth (:messages p))) "and the parent is untouched")
    (is (= 4 (:forked-at c)))))

(deftest the-turn-log-follows-the-messages-it-covers
  (testing "a full inherit carries every turn"
    (let [c (state/fork-branch (parent-branch) {:id "B1.2" :turn 4})]
      (is (= [1 2 3] (mapv :turn (:turns c))))))
  (testing "a truncated inherit carries only the turns still on the tape"
    ;; Matched by the stamps add-message writes, not by position: a provider
    ;; error or a no-call turn appends messages without appending a turn row.
    (let [c (state/fork-branch (parent-branch) {:id "B1.2" :depth 4 :turn 4})]
      (is (= [1] (mapv :turn (:turns c)))
          "turn 1 is on the inherited tape; turns 2 and 3 were truncated away")))
  (testing "a fork with no inherited tape has no turn log"
    (let [c (state/fork-branch (parent-branch) {:id "B1.2" :depth 2 :turn 4})]
      (is (= [] (:turns c))))))

(deftest a-child-is-never-charged-its-parents-failures
  (let [p (parent-branch)
        c (state/fork-branch p {:id "B1.2" :turn 4})]
    (testing "every gate counter starts clean"
      (is (= 0 (:consecutive-failures c)))
      (is (= 0 (:consecutive-mechanics-failures c)))
      (is (= 0 (:turns-since-progress c)))
      (is (false? (:any-progress? c)))
      (is (= [] (:abandoned c)))
      (is (= [] (:artifacts c)))
      (is (= {:calls 0 :parse-errors 0 :auto-repairs 0
              :unknown-tools 0 :truncations 0 :multi-fences 0}
             (:mechanics c))))
    (testing "and it gets a full phase budget rather than its parent's spent one"
      (is (= 4 (:phase-entered-turn c)))
      (is (= 4 (:created-at-turn c))))))

(deftest a-thesis-rides-along-when-one-is-given
  (let [c (state/fork-branch (parent-branch)
                             {:id "B1.2" :turn 4 :thesis {:goal "try Z3"}})]
    (is (= {:goal "try Z3"} (:thesis c))))
  (is (nil? (:thesis (state/fork-branch (parent-branch) {:id "B1.2" :turn 4})))))

(deftest a-stale-depth-degrades-rather-than-erroring
  (let [p (parent-branch)]
    (is (= (:messages p) (:messages (state/fork-branch p {:id "c" :depth 999 :turn 1})))
        "a depth past the parent's length cannot lengthen the tape")
    (is (= (:messages p) (:messages (state/fork-branch p {:id "c" :depth nil :turn 1}))))))

(deftest the-parent-is-a-value-and-cannot-see-its-children
  (let [p (parent-branch)
        c (-> (state/fork-branch p {:id "B1.2" :turn 4})
              (state/add-message "assistant" "child only" {:turn 4}))]
    (is (= 8 (tape/depth (:messages p))))
    (is (= 9 (tape/depth (:messages c))))
    (is (not-any? #(= "child only" (:content %)) (:messages p))
        "fork isolation is what makes branching cost nothing")))
