;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.feature-test
  "The feature loop: an outer state machine (plan -> implement -> review ->
  critique -> supervise -> route) delegating each stage to a role. These tests
  drive the state machine with a role-dispatching mock and stub the judge's
  content heuristics (tested in judge-test), so they exercise the WIRING —
  ship, the reviewer's revise bounce, and the supervisor's escalation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.judge :as judge]
            [samizdat.engine.proc :as proc]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.workflow :as workflow]))

;; Ground truth (step 3): a done with no diff is not a completed feature. By
;; default these tests simulate a run that DID change files, so the wiring tests
;; below exercise the ship/revise paths; the hollow-path tests redef this to [].
(use-fixtures :each
  (fn [t]
    (with-redefs [gitdiff/changed-files (constantly ["src/example.clj"])]
      (t))))

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
                                              :max-revisions 1 :max-revisions-hard 2}}})]
      (testing "an unsatisfiable reviewer keeps the loop solving, then the runaway guard abandons honestly (it never claims a solution it didn't reach)"
        (is (= :abandoned (:status r))))
      (testing "each revise round re-implemented on a versioned branch"
        (let [b (branch-ids conn)]
          (is (contains? b "W0"))     ; round 0
          (is (contains? b "W0v1"))   ; revise round 1
          (is (contains? b "W0v2"))   ; revise round 2, then the runaway guard trips
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
                                              :max-revisions 1 :max-revisions-hard 1}}
                               :max-turns 3})]
      (testing "a revise round happened despite the reviewer passing"
        (is (contains? (branch-ids conn) "W0v1")))
      (testing "and since the implementors never shipped, it ends unsolved, not falsely completed"
        (is (= :abandoned (:status r)))))))

(deftest a-crashing-stage-does-not-kill-the-run-and-surfaces-to-the-supervisor
  ;; critique used to throw an unbound-var and take the whole run down before the
  ;; supervisor stage ran. Now a stage that crashes is recorded, fails soft, and
  ;; the run reaches the supervisor with the crash in its telemetry to plan on.
  (let [seen-digest (atom nil)
        base (roles {:review :pass})]
    (with-redefs [judge/deterministic-block (fn [& _] (throw (ex-info "boom in the judge" {})))
                  judge/parse-verdict (constantly :complete)
                  judge/blocking-findings (constantly nil)
                  llm/chat (fn [a b messages & r]
                             (when (str/includes? (str/join " " (map :content messages))
                                                  "Your role: supervisor")
                               (reset! seen-digest (str/join " " (map :content messages))))
                             (apply base a b messages r))]
      (let [conn (db/open! ":memory:")
            r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]}}})]
        (is (= :completed (:status r)) "the run survived the crashing critique")
        (is (some? @seen-digest) "it reached the supervisor despite the crash")
        (is (str/includes? @seen-digest "STAGE CRASHED")
            "the supervisor was shown the crash to plan around")))))

(deftest supervisor-stop-means-give-up-and-abandons-unsolved
  ;; STOP is the supervisor's last resort — it concluded the loop can't solve the
  ;; task. The run ends UNSOLVED (abandoned), it does NOT ship the work as done,
  ;; and it stops iterating at once (no further revise round).
  (with-redefs [judge/deterministic-block (constantly nil)
                judge/parse-verdict (constantly :complete)
                judge/blocking-findings (constantly nil)
                llm/chat (roles {:review :revise :stop true})]
    (let [conn (db/open! ":memory:")
          r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                              :max-revisions 3}}})]
      (is (= :abandoned (:status r)) "STOP ends unsolved, not shipped")
      (is (nil? (:answer r)) "no answer is presented for an unsolved task")
      (testing "it gave up at once — no versioned revise branch"
        (is (not (contains? (branch-ids conn) "W0v1")))))))

(deftest per-role-models-reach-each-role
  ;; karamazov-reo: implementor on one model, supervisor on another, reviewer on
  ;; the run default. The captured :provider per role proves each role's sub-loop
  ;; ran on its assigned model.
  (let [seen (atom {})
        base (roles {:review :pass})]
    (with-redefs [judge/deterministic-block (constantly nil)
                  judge/parse-verdict (constantly :complete)
                  judge/blocking-findings (constantly nil)
                  llm/chat (fn [adapter cfg messages & r]
                             (let [c (str/join " " (map :content messages))
                                   role (cond
                                          (str/includes? c "Your role: reviewer") :reviewer
                                          (str/includes? c "Your role: supervisor") :supervisor
                                          (str/includes? c "Your role: implementor") :implementor
                                          :else :critic)]
                               (swap! seen update role (fnil conj #{}) (:provider cfg)))
                             (apply base adapter cfg messages r))]
      (let [conn (db/open! ":memory:")]
        (workflow/run! {:conn conn
                        :config {:run {:loop "feature" :subtasks ["alpha"]
                                       :role-models {:implementor {:provider "deepseek"}
                                                     :supervisor {:provider "glm"}}}}
                        :llm-adapter :a
                        :llm-config {:provider :openai :model "gpt-4o" :max-tokens 16384}
                        :problem "the feature" :max-turns 4})
        (is (contains? (:implementor @seen) :deepseek) "implementor ran on its assigned model")
        (is (contains? (:supervisor @seen) :glm) "supervisor ran on its assigned model")
        (is (contains? (:reviewer @seen) :openai) "the unconfigured reviewer kept the run default")))))

(deftest hollow-work-is-never-shipped-completed-it-keeps-solving
  ;; step 3: the DeepSeek dogfood shipped an empty diff as "completed" (reviewer
  ;; passed, supervisor STOP). Ground truth: a done that changed no files is not
  ;; a solution. The loop does NOT ship it — it keeps solving (revising with a
  ;; "you changed no files" nudge). Here the workers never edit anything, so it
  ;; eventually hits the safety backstop and abandons honestly — never completed.
  (with-redefs [judge/deterministic-block (constantly nil)
                judge/parse-verdict (constantly :complete)
                judge/blocking-findings (constantly nil)
                gitdiff/changed-files (constantly [])       ; nothing ever changes
                llm/chat (roles {:review :pass})]           ; reviewer would pass, but ground truth overrides
    (let [conn (db/open! ":memory:")
          r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                              :max-revisions 1 :max-revisions-hard 2}}})]
      (is (not= :completed (:status r)) "an empty diff is never reported completed")
      (testing "it kept solving before giving up (revised, did not abandon on the first empty round)"
        (is (contains? (branch-ids conn) "W0v1")))
      (is (= :abandoned (:status r)) "only the runaway guard ends it, honestly unsolved"))))

(deftest a-real-diff-still-ships-as-completed
  (with-redefs [judge/deterministic-block (constantly nil)
                judge/parse-verdict (constantly :complete)
                judge/blocking-findings (constantly nil)
                gitdiff/changed-files (constantly ["src/store/knowledge.clj"])
                llm/chat (roles {:review :pass})]
    (let [conn (db/open! ":memory:")
          r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]}}})]
      (is (= :completed (:status r)) "real changes + a pass ships completed"))))

(deftest soft-cap-notifies-the-supervisor-not-auto-abandon
  ;; The cap is a SOFT stop: at it the supervisor is notified via telemetry and
  ;; decides for itself — the loop does not abandon just for reaching it.
  (let [caps (atom [])
        base (roles {:review :revise})]     ; keeps bouncing, so the loop revises
    (with-redefs [judge/deterministic-block (constantly nil)
                  judge/parse-verdict (constantly :complete)
                  judge/blocking-findings (constantly nil)
                  llm/chat (fn [a b messages & r]
                             (when (str/includes? (str/join " " (map :content messages))
                                                  "Your role: supervisor")
                               (swap! caps conj (str/join " " (map :content messages))))
                             (apply base a b messages r))]
      (let [conn (db/open! ":memory:")]
        (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                          :max-revisions 1 :max-revisions-hard 4}}})
        (is (some #(str/includes? % "REVISION CAP REACHED") @caps)
            "at the soft cap the supervisor is told, and asked to decide")
        (is (contains? (branch-ids conn) "W0v2")
            "and the loop continued PAST the soft cap of 1 rather than abandoning at it")))))

(deftest tests-gate-must-pass-to-complete
  (testing "failing tests block completion — gate 2 is real"
    (with-redefs [judge/deterministic-block (constantly nil)
                  judge/parse-verdict (constantly :complete)
                  judge/blocking-findings (constantly nil)
                  gitdiff/changed-files (constantly ["src/x.clj"])
                  proc/run (constantly {:exit 1 :out "1 test FAILED"})
                  llm/chat (roles {:review :pass})]
      (let [conn (db/open! ":memory:")
            r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                               :verify-cmd "run-tests"
                                               :max-revisions 1 :max-revisions-hard 1}}})]
        (is (not= :completed (:status r)) "review passed but the tests fail, so not completed"))))
  (testing "both gates green completes"
    (with-redefs [judge/deterministic-block (constantly nil)
                  judge/parse-verdict (constantly :complete)
                  judge/blocking-findings (constantly nil)
                  gitdiff/changed-files (constantly ["src/x.clj"])
                  proc/run (constantly {:exit 0 :out "ok"})
                  llm/chat (roles {:review :pass})]
      (let [conn (db/open! ":memory:")
            r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                               :verify-cmd "run-tests"}}})]
        (is (= :completed (:status r)) "real diff + review pass + tests pass = completed")))))

(deftest supervisor-can-switch-the-implement-approach-mid-run
  ;; self-healing: the supervisor decides the fan-out isn't working and switches
  ;; this run's implement stage to the decompose loop with a SWITCH: line. The
  ;; next round routes through :decompose/run instead of the fan-out.
  (let [architect-json (str "{\"decision\":\"decompose\",\"subtasks\":"
                            "[{\"name\":\"a\",\"description\":\"do a\"}]}")
        mock (fn [_ _ messages & _]
               (let [c (str/join " " (map :content messages))]
                 (cond
                   (str/includes? c "Your role: reviewer")
                   (done-call "PASS: the implementors covered the feature; nothing to send back")

                   (str/includes? c "Your role: supervisor")
                   (if (str/includes? c "revision 0")
                     (done-call "SWITCH: decompose\nthe fan-out is stuck; try decompose")
                     (done-call "CONTINUE: the decompose approach is converging, nothing to change"))

                   (str/includes? c "architect diagnosing")
                   {:content architect-json :finish-reason "stop"}

                   (str/includes? c "## Problem")
                   (done-call (str "handled " (str/trim (or (second (re-find #"## Problem\s+(.+)" c)) "it"))))

                   :else {:content "COMPLETE" :finish-reason "stop"})))]
    (with-redefs [judge/deterministic-block (constantly nil)
                  judge/parse-verdict (constantly :complete)
                  judge/blocking-findings (constantly nil)
                  gitdiff/baseline (constantly "HEAD")
                  gitdiff/changed-files (constantly ["src/x.clj"])
                  llm/chat mock]
      (let [conn (db/open! ":memory:")
            r (run-feature conn {:config {:run {:loop "feature" :subtasks ["alpha"]
                                               :max-revisions-hard 3}}})
            branches (branch-ids conn)]
        (testing "round 0 ran the fan-out"
          (is (contains? branches "W0")))
        (testing "after SWITCH: decompose, the next round ran the decompose loop"
          (is (contains? branches "DT") "the decompose root attempt ran"))))))
