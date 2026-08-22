;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.feature-test
  "The feature loop: an outer state machine (plan -> implement -> review ->
  critique -> supervise -> route) delegating each stage to a role. These tests
  drive the state machine with a role-dispatching mock and stub the judge's
  content heuristics (tested in judge-test), so they exercise the WIRING —
  ship, the reviewer's revise bounce, and the supervisor's escalation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.judge :as judge]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.workflow :as workflow]))

(defn- done-call [answer]
  {:content (str "```tool-call\n{\"name\":\"done\",\"args\":{\"answer\":\""
                 answer "\"}}\n```")
   :finish-reason "stop"})

(defn- review-answer
  "A substantive review verdict — PASS/REVISE on the first line, then reasons
  that name the feature/implementors so the done-gate accepts it as engaging the
  review problem (a one-word 'revise' gets blocked as engaging nothing)."
  [review]
  (case review
    :pass  "PASS: the implementors' changes implement the feature and the tests pass; nothing to send back."
    :revise "REVISE: the implementors' work does not satisfy the feature; the changes touch the wrong area and must be redone."))

(defn- roles
  "One redef playing every role by the prompt it sees: the reviewer ships
  PASS/REVISE, an implementor builds its part (or, when :exhaust, never calls a
  tool so it hits the turn cap), the critic's judge reply is ignored (stubbed)."
  [{:keys [review exhaust stop]}]
  (fn [_ _ messages & _]
    (let [c (str/join " " (map :content messages))]
      (cond
        (str/includes? c "Your role: reviewer")
        (done-call (review-answer review))        ; PASS/REVISE on the first line

        (str/includes? c "Your role: supervisor")
        ;; the supervisor READS the telemetry and DECIDES — it revises when the
        ;; digest flags that nothing shipped, else it lets the loop proceed.
        (cond
          stop (done-call "STOP: further revise rounds are not converging; ship what the implementors produced and end.")
          (str/includes? c "NO IMPLEMENTOR SHIPPED")
          (done-call "REVISE: no implementor shipped; re-run the implement round with tighter guidance.")
          :else (done-call "CONTINUE: the implementors shipped and the reviewer passed; the loop is converging, no adjustment needed."))

        (str/includes? c "Your role: implementor")
        (if exhaust
          {:content "still working, no tool call yet" :finish-reason "stop"}
          (done-call (str "built " (str/trim (or (second (re-find #"## Problem\s+(\S+)" c))
                                                 "part")))))

        :else {:content "COMPLETE" :finish-reason "stop"}))))

(defn- branch-ids [conn]
  (set (map :branch_id (db/fetch conn ["SELECT DISTINCT branch_id FROM turns"]))))

(defn- run-feature [conn extra]
  (workflow/run! (merge {:conn conn
                         :llm-adapter :a :llm-config {:max-tokens 16384}
                         :problem "the feature" :max-turns 4}
                        extra)))

(deftest feature-flows-plan-implement-review-critique-ship
  (with-redefs [judge/deterministic-block (constantly nil)
                judge/parse-verdict (constantly :complete)
                judge/blocking-findings (constantly nil)
                llm/chat (roles {:review :pass})]
    (let [conn (db/open! ":memory:")
          r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha" "beta"]}}})]
      (is (= :completed (:status r)))
      (testing "the join carries both implementors' parts"
        (is (str/includes? (:answer r) "alpha"))
        (is (str/includes? (:answer r) "beta")))
      (testing "each role ran on its own branch: implementors W0/W1 + reviewer R0"
        (let [b (branch-ids conn)]
          (is (contains? b "W0"))
          (is (contains? b "W1"))
          (is (contains? b "R0")))))))

(deftest feature-reviewer-revise-loops-back-to-implement-bounded
  (with-redefs [judge/deterministic-block (constantly nil)
                judge/parse-verdict (constantly :complete)
                judge/blocking-findings (constantly nil)
                llm/chat (roles {:review :revise})]     ; reviewer always bounces
    (let [conn (db/open! ":memory:")
          r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                              :max-revisions 2}}})]
      (testing "it ships anyway once the revision cap is hit"
        (is (= :completed (:status r))))
      (testing "each revise round re-implemented on a versioned branch"
        (let [b (branch-ids conn)]
          (is (contains? b "W0"))     ; round 0
          (is (contains? b "W0v1"))   ; revise round 1
          (is (contains? b "W0v2"))   ; revise round 2, then ship at cap
          (is (not (contains? b "W0v3"))))))))

(deftest supervisor-reasons-over-telemetry-and-forces-a-round
  ;; Reviewer PASSes, so without the supervisor the run would ship round 0. The
  ;; implementors exhaust (ship nothing); the supervisor reads that in the
  ;; run-health digest ("NO IMPLEMENTOR SHIPPED") and DECIDES to REVISE — the
  ;; loop introspecting and steering itself, not a hard-coded rule.
  (with-redefs [judge/deterministic-block (constantly nil)
                judge/parse-verdict (constantly :complete)
                judge/blocking-findings (constantly nil)
                llm/chat (roles {:review :pass :exhaust true})]
    (let [conn (db/open! ":memory:")
          r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                              :max-revisions 1}}
                               :max-turns 3})]
      (is (= :completed (:status r)))
      (testing "a revise round happened despite the reviewer passing"
        (is (contains? (branch-ids conn) "W0v1"))))))

(deftest supervisor-stop-ends-the-run-even-mid-revise
  ;; The reviewer keeps saying REVISE (would loop to the cap), but the supervisor
  ;; decides STOP — so the run ships round 0's work and ends, no revise round.
  (with-redefs [judge/deterministic-block (constantly nil)
                judge/parse-verdict (constantly :complete)
                judge/blocking-findings (constantly nil)
                llm/chat (roles {:review :revise :stop true})]
    (let [conn (db/open! ":memory:")
          r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                              :max-revisions 3}}})]
      (is (= :completed (:status r)))
      (testing "STOP shipped at once — no versioned revise branch"
        (is (not (contains? (branch-ids conn) "W0v1")))))))
