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

(ns samizdat.judge-test
  "The finalization critic: the pure judge core, and the block-then-ship loop
  behavior on the `critic` manifest."
  (:require [clojure.data.json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.judge :as judge]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.workflow :as workflow]))

(deftest verdict-parsing-is-negation-aware-and-fails-open
  (is (= :complete   (judge/parse-verdict "VERDICT: COMPLETE\nlooks right")))
  (is (= :incomplete (judge/parse-verdict "VERDICT: INCOMPLETE\nmissing a test")))
  (is (= :incomplete (judge/parse-verdict "VERDICT: NOT COMPLETE"))
      "negation flips a bare COMPLETE")
  (is (= :abstain    (judge/parse-verdict "VERDICT: ABSTAIN")))
  (testing "a judge that cannot answer must never be able to block"
    (is (= :complete (judge/parse-verdict "")))
    (is (= :complete (judge/parse-verdict "the model rambled with no verdict line")))
    (is (= :complete (judge/parse-verdict nil))))
  (testing "the VERDICT line is found even when it is not first"
    (is (= :incomplete (judge/parse-verdict "Some preamble.\nVERDICT: INCOMPLETE")))))

(deftest findings-and-critique
  (is (= "- no test for X\n- Y unhandled"
         (judge/findings "VERDICT: INCOMPLETE\nFINDINGS:\n- no test for X\n- Y unhandled")))
  (is (nil? (judge/findings "VERDICT: COMPLETE")))
  (is (str/includes? (judge/critique-message :incomplete "- add a test") "add a test"))
  (is (str/includes? (judge/critique-message :abstain nil) "could not be confirmed")))

(deftest evidence-block-is-deterministic-facts
  (let [e (judge/evidence [{:tool_name "edit_file" :args {:path "a.clj"} :category "success"}
                           {:tool_name "shell" :args {:command "jolt -M:test"} :category "failure"}
                           {:tool_name "eval" :args {} :category "neutral"}])]
    (is (str/includes? e "tool calls: 3"))
    (is (str/includes? e "a.clj") "files written are named")
    (is (str/includes? e "FAILED: jolt -M:test") "a failed command is marked failed")))

(defn- scripted-chat
  "A model that always tries to `done`, and answers the critic's judge call
  with the verdicts in `verdicts`, in order (last one repeats)."
  [verdicts]
  (let [calls (atom 0)]
    (fn [_ _ messages & _]
      (let [content (str/join " " (map :content messages))]
        (if (str/includes? content "reviewer deciding")
          (let [n (swap! calls inc)
                v (nth verdicts (min (dec n) (dec (count verdicts))))]
            {:content (str "VERDICT: " v) :finish-reason "stop"})
          {:content "```tool-call\n{\"name\": \"done\", \"args\": {\"answer\": \"x\"}}\n```"
           :finish-reason "stop"})))))

(deftest critic-blocks-a-premature-done-then-ships
  (with-redefs [llm/chat (scripted-chat ["INCOMPLETE" "COMPLETE"])]
    (let [conn (db/open! ":memory:")
          r (workflow/run! {:conn conn :config {:run {:loop "critic"}}
                            :llm-adapter :a :llm-config {:max-tokens 16384}
                            :problem "p" :max-turns 8})]
      (is (= :completed (:status r)))
      (is (= "x" (:answer r)))
      (is (<= 2 (count (db/fetch conn ["SELECT rowid FROM events WHERE kind='critic'"])))
          "the critic ran at least twice — a block then a ship"))))

(deftest an-always-unhappy-critic-still-ships-eventually
  ;; A judge that never passes must not be able to wedge the loop.
  (with-redefs [llm/chat (scripted-chat ["INCOMPLETE"])]
    (let [conn (db/open! ":memory:")
          r (workflow/run! {:conn conn :config {:run {:loop "critic"}}
                            :llm-adapter :a :llm-config {:max-tokens 16384}
                            :problem "p" :max-turns 20})]
      (is (= :completed (:status r)) "bounded: it ships after the attempt cap"))))

(deftest diff-review-blocks-on-severity
  (testing "a critical or high finding blocks; a low one does not"
    (is (judge/blocking-findings "VERDICT: COMPLETE\nFINDINGS:\n[critical] leaks a key"))
    (is (judge/blocking-findings "VERDICT: COMPLETE\nFINDINGS:\n[high] off-by-one"))
    (is (nil? (judge/blocking-findings "VERDICT: COMPLETE\nFINDINGS:\n[low] rename")))
    (is (nil? (judge/blocking-findings "VERDICT: COMPLETE")))))

(defn- verdict-with-findings-chat
  "A model that dones, and whose judge returns the given raw replies in order."
  [replies]
  (let [calls (atom 0)]
    (fn [_ _ messages & _]
      (if (str/includes? (str/join " " (map :content messages)) "reviewer deciding")
        (let [n (swap! calls inc)]
          {:content (nth replies (min (dec n) (dec (count replies))) "VERDICT: COMPLETE")
           :finish-reason "stop"})
        {:content "```tool-call\n{\"name\": \"done\", \"args\": {\"answer\": \"x\"}}\n```"
         :finish-reason "stop"}))))

(deftest a-complete-verdict-with-a-critical-finding-still-blocks
  ;; The diff-review half of the unified judge: COMPLETE but the diff has a
  ;; critical defect -> undo the done and send it back; a clean pass ships.
  (with-redefs [llm/chat (verdict-with-findings-chat
                          ["VERDICT: COMPLETE\nFINDINGS:\n[critical] deletes user data"
                           "VERDICT: COMPLETE"])]
    (let [conn (db/open! ":memory:")
          r (workflow/run! {:conn conn :config {:run {:loop "critic"}}
                            :llm-adapter :a :llm-config {:max-tokens 16384}
                            :problem "p" :max-turns 8})]
      (is (= :completed (:status r)))
      (is (some #(= true (get-in % [:data :blocked]))
                (map #(update % :data (fn [d] (clojure.data.json/read-str (str d) :key-fn keyword)))
                     (db/fetch conn ["SELECT data FROM events WHERE kind='critic'"])))
          "at least one critic firing recorded a block on the diff"))))
