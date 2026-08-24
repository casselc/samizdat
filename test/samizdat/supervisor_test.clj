;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.supervisor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.supervisor :as sup]))

;; The vocabulary this test passes in is the one gates.edn carries as
;; :tool-vocab :shipping; kept literal here so supervisor_test fails on its
;; own terms, not through the config layer.
(def shipping #{"write_file" "edit_file" "shell" "reload_cells" "manifest"
                "done" "give_up" "thesis" "branch_theses"})

(deftest over-studying-detection
  (let [shipped-then-read (vec (concat [{:tool "write_file"}] (repeat 10 {:tool "read_file"})))]
    (testing "shipped once, then N inspection turns -> studying"
      (is (sup/over-studying? shipping shipped-then-read 10)))
    (testing "a recent ship clears it"
      (is (not (sup/over-studying? shipping
                (vec (concat (repeat 10 {:tool "grep"}) [{:tool "shell"}])) 10))))
    (testing "opening exploration (never shipped) is not nagged"
      (is (not (sup/over-studying? shipping (vec (repeat 20 {:tool "read_file"})) 10))))
    (testing "too few turns to judge"
      (is (not (sup/over-studying? shipping (vec (repeat 3 {:tool "read_file"})) 10))))))

(deftest the-nudge-names-what-it-was-cycling
  (let [turns (vec (concat [{:tool "edit_file"}]
                           (repeat 5 {:tool "read_file"}) (repeat 5 {:tool "eval"})))]
    (is (= ["read_file" "eval"] (sup/recent-studying-tools shipping turns 10)))
    (is (str/includes? (sup/stall-nudge shipping turns 10) "read_file"))
    (is (str/includes? (sup/stall-nudge shipping turns 10) "Commit"))))
