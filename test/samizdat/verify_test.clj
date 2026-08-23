;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.verify-test
  "The ship gate's test rung: the pure decision that makes `done` a hard gate on
  a green test run, and the focused-command derivation that keeps the loop fast
  (karamazov-dvz follow-up: the worker loop must be test-driven, not one-shot)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.verify :as verify]))

(deftest test-file?-recognises-test-and-spec-paths
  (is (verify/test-file? "test/samizdat/foo_test.clj"))
  (is (verify/test-file? "src/samizdat/foo_spec.clj"))
  (is (verify/test-file? "test/samizdat/agent/decompose_test.clj"))
  (is (not (verify/test-file? "src/samizdat/agent/tools/knowledge.clj")))
  (is (not (verify/test-file? "resources/cells/feature.clj"))))

(deftest ns-from-test-path-maps-a-path-to-its-namespace
  (is (= "samizdat.agent.decompose-test"
         (verify/ns-from-test-path "test/samizdat/agent/decompose_test.clj")))
  (is (= "samizdat.knowledge-test"
         (verify/ns-from-test-path "test/samizdat/knowledge_test.clj")))
  (is (nil? (verify/ns-from-test-path "resources/skills/repl-workflow.md"))
      "a non-clj path has no namespace"))

(deftest focused-cmd-runs-only-the-changed-test-namespaces
  (let [cmd (verify/focused-cmd ["src/samizdat/agent/tools/knowledge.clj"
                                 "test/samizdat/knowledge_test.clj"])]
    (is (some? cmd))
    (is (str/includes? cmd "samizdat.knowledge-test") "targets the touched test ns")
    (is (not (str/includes? cmd "-M:test")) "does NOT run the whole suite")
    (is (str/includes? cmd "System/exit") "sets an exit code so red is detectable"))
  (testing "nothing focusable => nil (caller falls back to :verify-cmd)"
    (is (nil? (verify/focused-cmd ["src/samizdat/only_src.clj"])))
    (is (nil? (verify/focused-cmd [])))))

(deftest verify-block-is-inert-when-verify-is-off
  (is (nil? (verify/verify-block {:verify-on? false :result nil :changed nil :require-test? true}))))

(deftest verify-block-blocks-a-red-run-with-the-output
  (let [b (verify/verify-block {:verify-on? true
                                :result {:green? false :output "FAIL: 2 assertions failed\nat foo_test"}
                                :changed ["src/x.clj" "test/x_test.clj"]
                                :require-test? true})]
    (is (some? b))
    (is (str/includes? b "not green"))
    (is (str/includes? b "assertions failed") "feeds the failure output back")))

(deftest verify-block-blocks-a-timeout
  (let [b (verify/verify-block {:verify-on? true
                                :result {:green? false :timeout? true :output ""}
                                :changed ["test/x_test.clj"] :require-test? true})]
    (is (some? b))
    (is (str/includes? (str/lower-case b) "timed out"))))

(deftest verify-block-blocks-a-hollow-done-when-nothing-changed
  (let [b (verify/verify-block {:verify-on? true :result {:green? true :output ""}
                                :changed [] :require-test? false})]
    (is (some? b))
    (is (str/includes? (str/lower-case b) "changed no files"))))

(deftest verify-block-requires-a-test-when-tdd-strict
  (let [b (verify/verify-block {:verify-on? true :result nil ; not even run — the pre-check fires first
                                :changed ["src/samizdat/agent/tools/knowledge.clj"]
                                :require-test? true})]
    (is (some? b))
    (is (str/includes? (str/lower-case b) "test")))
  (testing "with require-test? off, a green src-only change ships"
    (is (nil? (verify/verify-block {:verify-on? true :result {:green? true :output ""}
                                    :changed ["src/x.clj"] :require-test? false})))))

(deftest verify-block-passes-a-green-tdd-change
  (is (nil? (verify/verify-block {:verify-on? true :result {:green? true :output ""}
                                  :changed ["src/samizdat/agent/tools/knowledge.clj"
                                            "test/samizdat/knowledge_test.clj"]
                                  :require-test? true}))))

(deftest verify-block-trusts-a-green-run-when-git-cannot-tell
  (is (nil? (verify/verify-block {:verify-on? true :result {:green? true :output ""}
                                  :changed nil :require-test? true}))))
