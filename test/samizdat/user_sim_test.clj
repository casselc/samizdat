;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.user-sim-test
  "The simulated user (karamazov-a6mj.3): when nobody is attached to a run
  and the operator supplied a `:run :user-context`, `ask_human` is answered
  by the :user role from that context and nothing else — thinkingbox's
  user-LLM, which answers only from author-supplied ground truth, copies
  entities verbatim, says it does not know otherwise, and asks one combined
  question back. It makes 'ask, do not guess' viable in an autonomous run,
  and it makes asking testable.

  Beside it, the ship rung the same model implies: an answer whose last line
  asks the reader something is not `done`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.ask :as ask]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.approval :as approval]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(def ^:private refuse-policy {:mode :refuse :wait-ms 1000 :on-timeout :deny})

(defn- ask [conn rid cfg args]
  (tools/run-tool {:tool-name "ask_human" :args args
                   :branch (assoc (state/new-branch {:id "B1" :problem "add a store"})
                                  :messages [{:role "system" :content "s"}
                                             {:role "user" :content "add a store"}
                                             {:role "assistant" :content "Which store did you want?"}])
                   :conn conn :run-id rid :branch-id "B1"
                   :config {:run cfg} :llm-adapter :a :llm-config {:model "m"}}))

;; --- ask_human, answered from the context ---------------------------------------

(deftest with-a-user-context-and-nobody-attached-the-simulated-user-answers
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "add a store"})
        seen (atom nil)]
    (try
      (with-redefs [approval/policy (constantly refuse-policy)
                    llm/chat (fn [_ _ messages & _]
                               (reset! seen messages)
                               {:content "sqlite" :usage {:total_tokens 12}})]
        (let [r (ask conn rid {:user-context "You want the sqlite store, under .samizdat/."}
                      {:questions [{:question "which store?" :options ["sqlite" "postgres"]}]})]
          (testing "the branch reads the answer beside its question"
            (is (= :neutral (:category r)) "asking establishes nothing, simulated or not")
            (is (str/includes? (:result r) "which store?"))
            (is (str/includes? (:result r) "sqlite")))
          (testing "and is told a simulated user answered, not a person"
            (is (re-find #"(?i)simulated" (:result r)) (:result r)))
          (testing "the user model saw the context, the transcript and the question — and the rules"
            (let [text (str/join "\n" (map :content @seen))]
              (is (str/includes? text "You want the sqlite store"))
              (is (str/includes? text "Which store did you want?") "the transcript")
              (is (str/includes? text "which store?") "the question")
              (is (re-find #"(?i)I don't know" text) "the rule for what the context does not cover")))
          (testing "the exchange is on the record as simulated"
            (let [[note] (journal/notes conn rid :simulated-user)]
              (is (some? note))
              (is (= ["which store?"] (mapv :question (:questions note))))
              (is (= "sqlite" (:answer note))))
            (is (= 1 (:side-calls (journal/run-usage conn rid)))
                "billed as a side call, so the run's token budget sees it"))))
      (finally (db/close conn)))))

(deftest without-a-user-context-ask-human-is-refused-as-before
  (let [called (atom false)]
    (with-redefs [approval/policy (constantly refuse-policy)
                  llm/chat (fn [& _] (reset! called true) {:content "x"})]
      (let [r (ask nil nil {} {:questions [{:question "which?"}]})]
        (is (= :mechanics (:category r)))
        (is (str/includes? (str/lower-case (:result r)) "no human"))
        (is (not @called))))))

(deftest a-real-person-outranks-the-simulated-user
  ;; :block means somebody configured a person. The context is for when
  ;; there is nobody; it must not answer over a person's head.
  (let [called (atom false)]
    (with-redefs [approval/policy (constantly {:mode :block :wait-ms 5000 :on-timeout :deny})
                  llm/chat (fn [& _] (reset! called true) {:content "x"})]
      (let [result (future (ask nil "r-person" {:user-context "sqlite"} {:questions [{:question "which?"}]}))]
        (future (loop [] (if-let [p (first (approval/pending "r-person"))]
                           (approval/decide! (:id p) {:decision :answer :answers ["postgres"]})
                           (do (Thread/sleep 20) (recur)))))
        (let [r (deref result 5000 ::timeout)]
          (is (not= ::timeout r))
          (is (str/includes? (:result r) "postgres") "the person's answer")
          (is (not @called) "and the user model was never asked"))))))

(deftest the-simulated-user-that-cannot-answer-is-not-a-failure
  (with-redefs [approval/policy (constantly refuse-policy)
                llm/chat (fn [& _] (throw (ex-info "provider down" {})))]
    (let [r (ask nil nil {:user-context "sqlite"} {:questions [{:question "which?"}]})]
      (is (= :neutral (:category r)) "an outside capability that could not be reached is not the branch's fault")
      (is (re-find #"(?i)decide it yourself|nobody|could not" (:result r)) (:result r)))))

(deftest the-user-prompt-carries-the-rules-of-the-game
  (let [p (ask/user-prompt {:context "Name: Alex. Deadline: Friday."
                            :transcript "assistant: when is it due?"
                            :questions [{:question "when is it due?" :options []}
                                        {:question "who for?" :options ["Alex" "Sam"]}]})]
    (is (str/includes? p "Name: Alex. Deadline: Friday."))
    (is (str/includes? p "when is it due?"))
    (is (str/includes? p "who for?"))
    (is (str/includes? p "Alex") "options are shown so a copy-only answer can pick one")
    (is (re-find #"(?i)verbatim" p) "copy-only entities")
    (is (re-find #"(?i)I don't know" p) "what to say when the context does not cover it")
    (is (re-find #"(?i)never (do|perform) the assistant" p) "the role guardrail")))

;; --- the ship rung: an answer that asks the reader is not done -------------------

(deftest asks-the-reader-fires-on-a-last-line-question-to-the-reader-only
  (testing "fires"
    (doseq [a ["Added the store.\n\nShall I also update the README?"
               "Done. Do you want the migration squashed?"
               "The parser is fixed.\nLet me know if you want the tests moved too."
               "Which store did you mean — sqlite or postgres?"
               "Built it. Please confirm the port before I wire the client."]]
      (is (ship/asks-the-reader? a) a)))
  (testing "does not fire"
    (doseq [a ["Added the store; the suite is green."
               "Why did it fail? The port was taken. Fixed by reading it from the env."
               "The `?` operator returns nil on a miss, so the caller checks for it."
               "I could not verify the frame on this host: no window server."
               "Is the clamp right? I checked: yes, the bird stays above y=0."
               "You can run it with `jolt -M:run`."
               ""]]
      (is (not (ship/asks-the-reader? a)) a))))

(deftest done-is-refused-when-the-answer-ends-by-asking-the-reader
  (let [ship (fn [answer]
               (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "add a sqlite store"})
                                :tool-name "done" :turn 3 :root "/tmp" :git-baseline "HEAD"
                                :config {:run {}}
                                :args {:answer answer}}))]
    (with-redefs [gitdiff/changed-files (fn [_ _] ["src/x.clj" "test/x_test.clj"])]
      (let [r (ship "Added the sqlite store under .samizdat/. Shall I also add postgres?")]
        (is (not (:done? r)))
        (is (re-find #"(?i)ask_human" (:result r)) "the refusal names the tool that asks"))
      (let [r (ship "Added the sqlite store under .samizdat/; the suite is green.")]
        (is (:done? r) (:result r))))))
