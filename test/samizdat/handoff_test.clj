;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.handoff-test
  "What a branch is told about a turn that did not finish (karamazov-o4wm.2).
  Pure: the messages are a function of what the journal holds about the turn
  — a row, a dispatch note, or nothing — and of why it stopped."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.handoff :as handoff]))

(def ^:private call "```tool-call\n{\"name\": \"shell\", \"args\": {\"cmd\": \"git push\"}}\n```")

(deftest an-in-flight-mutating-call-is-handed-back-as-unknown
  (let [msgs (handoff/messages {:why :deadline :seconds 900 :turn 7
                                :dispatch {:tool "shell" :args {:cmd "git push"} :said call}
                                :read-only #{"read_file"}})]
    (is (= ["assistant" "user"] (mapv :role msgs))
        "the call it made, then what the harness knows — the tape keeps its shape")
    (is (= call (:content (first msgs))) "the branch sees exactly what it called")
    (let [u (:content (second msgs))]
      (is (str/includes? u "`shell`"))
      (is (str/includes? u "900"))
      (is (str/includes? u "not known whether") "the uncertain-effect wording, verbatim")
      (is (not (str/includes? u "nothing changed"))))))

(deftest an-in-flight-read-is-handed-back-as-no-effect
  (let [[_ u] (handoff/messages {:why :deadline :seconds 900 :turn 7
                                 :dispatch {:tool "read_file" :args {:path "a"} :said call}
                                 :read-only #{"read_file"}})]
    (is (str/includes? (:content u) "nothing changed"))
    (is (not (str/includes? (:content u) "not known whether")))))

(deftest a-crash-is-worded-as-a-restart
  (let [[_ u] (handoff/messages {:why :crash :turn 7
                                 :dispatch {:tool "shell" :args {} :said call}
                                 :read-only #{}})]
    (is (str/includes? (:content u) "restarted"))
    (is (not (str/includes? (:content u) "deadline")))))

(deftest a-completed-then-abandoned-turn-replays-its-row
  ;; The tool ran and was recorded; the deadline landed after. Interruption
  ;; is not rollback: the branch gets the result it earned, and is told the
  ;; turn stopped there.
  (let [msgs (handoff/messages {:why :deadline :seconds 900 :turn 7
                                :row {:assistant_text "calling" :result "did it"
                                      :tool_name "write_file"}})]
    (is (= ["assistant" "user"] (mapv :role msgs)))
    (is (= "calling" (:content (first msgs))))
    (is (str/starts-with? (:content (second msgs))
                          "<tool_result tool=\"write_file\">\ndid it\n</tool_result>")
        "framed as the live turn would have framed it")
    (is (str/includes? (:content (second msgs)) "completed"))
    (is (= 7 (:turn (second msgs))) "stamped, so compaction can digest it from its row")))

(deftest nothing-known-nothing-invented
  (testing "a crash with no dispatch on record leaves the tape alone"
    (is (= [] (handoff/messages {:why :crash :turn 3 :read-only #{}}))))
  (testing "a deadline with nothing on record keeps the plain deadline message"
    (let [[m :as msgs] (handoff/messages {:why :deadline :seconds 30 :turn 3})]
      (is (= 1 (count msgs)))
      (is (= "user" (:role m)))
      (is (str/includes? (:content m) "30")))))

(deftest clip-args-keeps-the-shape-and-cuts-the-bulk
  (is (= {:path "a" :content "xxxxxxxxxx… [40 more chars]"}
         (handoff/clip-args {:path "a" :content (apply str (repeat 50 "x"))} 10)))
  (is (= {:n 3 :s "ok"} (handoff/clip-args {:n 3 :s "ok"} 10)) "short values untouched")
  (is (nil? (handoff/clip-args nil 10))))
