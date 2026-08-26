;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.reflect-test
  "What a finished task leaves behind about the PROJECT.

  Everything else the harness distils is the harness watching itself. This is
  the half that was missing: across the live runs the implementor made 46 turns
  and zero `remember` calls, so every session started knowing nothing about the
  codebase and spent its first turns rediscovering the layout."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.reflect :as reflect]
            [samizdat.store.db :as db]
            [samizdat.store.knowledge :as knowledge]))

(def ^:private conn (atom nil))
(use-fixtures :each (fn [f] (reset! conn (db/open! ":memory:")) (f) (db/close @conn)))

;; --- the half that CAN be tested --------------------------------------------

(deftest every-fact-the-harness-means-to-hand-over-reaches-the-prompt
  ;; dirge guards exactly this in CI (compaction_recall.rs), and the reasoning
  ;; carries over: nobody can unit-test what a model chooses to write, but a
  ;; fact that never ARRIVED is a harness bug — a truncation or a window
  ;; mistake silently starving the reflector — and that is testable.
  (let [turns [{:turn 1 :tool_name "shell" :args "{:command \"jolt -A:test\"}"
                :result "Ran 5 tests, 0 failures" :category "success"}
               {:turn 2 :tool_name "shell" :args "{:command \"find . | head\"}"
                :result "Command needs approval: not on the allow list" :category "neutral"}
               {:turn 3 :tool_name "edit_file" :args "{:path \"src/calc/core.clj\"}"
                :result "Edited src/calc/core.clj" :category "success"}]
        memories [{:id "k-abc123" :kind "semantic" :content "tests live under test/"}]
        p (reflect/build-prompt {:problem "implement the calculator"
                                 :turns turns :memories memories})]
    (testing "the task, every command, every outcome"
      (is (str/includes? p "implement the calculator"))
      (is (str/includes? p "jolt -A:test"))
      (is (str/includes? p "find . | head"))
      (is (str/includes? p "src/calc/core.clj")))
    (testing "the error text, because the gotchas worth recording live in it"
      (is (str/includes? p "not on the allow list"))
      (is (str/includes? p "Ran 5 tests")))
    (testing "and the memories it is being asked to check"
      (is (str/includes? p "k-abc123"))
      (is (str/includes? p "tests live under test/")))))

;; --- parsing ----------------------------------------------------------------

(deftest an-empty-section-is-not-a-memory
  ;; A model told to leave a section empty writes "None." or a dash or echoes
  ;; the bracketed instruction. Recording those as facts would fill the store
  ;; with lines that cost every future session tokens and say nothing.
  (let [s (reflect/parse-sections
           "## OVERVIEW\nNone.\n\n## FACTS\n- tests live under test/\n\n## RULES\n-\n\n## GOTCHAS\n[One per line]\n")]
    (is (= {"FACTS" ["tests live under test/"]} s))))

(deftest sections-map-to-kinds-and-gotchas-are-rules-not-episodes
  ;; `find | head is refused here` holds next time; it is not a thing that
  ;; happened once.
  (let [s (reflect/parse-sections
           "## OVERVIEW\nA calculator library.\n## FACTS\nbuilt with deps.edn\n## GOTCHAS\n`find | head` is refused\n")
        written (reflect/record! @conn {:run-id "r1"} s)
        kinds (into {} (map (juxt :id (comp :kind #(knowledge/get-by-id @conn %) :id))) written)]
    (is (= 3 (count written)))
    (is (= #{"overview" "semantic" "procedural"} (set (vals kinds))))))

(deftest there-is-at-most-one-overview
  ;; It is the orientation note, not a pile of them — capped by construction
  ;; through a fixed pattern key rather than by asking the model nicely.
  (reflect/record! @conn {:run-id "r1"} {"OVERVIEW" ["A calculator library."]})
  (reflect/record! @conn {:run-id "r2"} {"OVERVIEW" ["Actually an expression evaluator."]})
  (is (= 1 (count (filter #(= "overview" (:kind %)) (knowledge/recent @conn 20))))))

(deftest the-same-fact-twice-is-corroborated-not-duplicated
  (reflect/record! @conn {:run-id "r1"} {"FACTS" ["tests live under test/"]})
  (reflect/record! @conn {:run-id "r2"} {"FACTS" ["tests live under test/"]})
  (let [rows (filter #(= "semantic" (:kind %)) (knowledge/recent @conn 20))]
    (is (= 1 (count rows)))
    (is (= 2 (:corroborations (first rows))))))

(deftest flagging-a-stale-memory-counts-against-it
  ;; The implementor is the only role positioned to notice that a memory it was
  ;; handed is wrong, so its word counts against that memory's record.
  (let [id (knowledge/remember! @conn {:content "tests live under spec/" :kind "semantic"})]
    (reflect/record! @conn {:run-id "r1"}
                     {"WRONG" [(str id " — the tests moved to test/")]})
    (is (= 1 (:failure_count (knowledge/get-by-id @conn id))))))

(deftest a-correction-naming-an-unknown-id-is-ignored
  (is (some? (reflect/record! @conn {:run-id "r1"} {"WRONG" ["k-nosuch — invented"]}))))
