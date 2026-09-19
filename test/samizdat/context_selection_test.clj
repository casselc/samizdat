;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.context-selection-test
  "The harness side of samizdat-context-selection/1: the default path is
  unchanged, a decision injects selected material once, identity is the id, and
  nothing here scores or grants anything."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.context-selection :as cs]
            [samizdat.agent.skills :as skills]))

(def a-decision
  {:seam-version "samizdat-context-selection/1"
   :outcome :selected
   :policy :selector/2
   :selected [{:id "skill:repl-workflow" :kind :skill :name "repl-workflow" :cost-load 1399}
              {:id "tool:grep" :kind :tool :name "grep" :cost-load 120}
              {:id "manual:samizdat.agent.files/glob-project" :kind :manual
               :name "samizdat.agent.files/glob-project"
               :summary "find files by name under the project root" :cost-load 40}]})

(deftest with-no-decision-the-prompt-block-is-exactly-todays-catalogue
  (is (= (skills/render-catalog) (cs/skills-block))
      "no decision -> the catalogue, byte for byte")
  (is (= (skills/render-catalog) (cs/skills-block nil))))

(deftest a-decision-adds-to-the-catalogue-rather-than-replacing-it
  (let [block (cs/skills-block a-decision)]
    (is (str/includes? block (skills/render-catalog))
        "the catalogue survives: the worker may still load anything on demand")
    (is (str/includes? block "already loaded"))
    (is (str/includes? block "skill:repl-workflow"))))

(deftest each-kind-is-materialised-according-to-what-the-prompt-already-carries
  (let [m (cs/materialize a-decision)
        by-id (into {} (map (juxt :id identity)) (:entries m))]
    (testing "a skill body is injected"
      (is (= :injected (:status (by-id "skill:repl-workflow"))))
      (is (str/includes? (:text (by-id "skill:repl-workflow")) "REPL")))
    (testing "a tool's documentation is already in the prompt and is not injected twice"
      (is (= :already-present (:status (by-id "tool:grep")))))
    (testing "a manual line is injected as a line"
      (is (= :injected (:status (by-id "manual:samizdat.agent.files/glob-project"))))
      (is (str/includes? (:text (by-id "manual:samizdat.agent.files/glob-project"))
                         "find files by name")))))

(deftest injected-cost-counts-only-what-this-prompt-actually-carries
  (let [m (cs/materialize a-decision)]
    (is (= (+ 1399 40) (:injected-cost-load m))
        "the already-present tool contributes nothing")))

(deftest an-id-this-run-cannot-resolve-is-dropped-and-recorded
  (let [m (cs/materialize {:selected [{:id "skill:no-such-thing" :kind :skill
                                       :name "no-such-thing" :cost-load 999}]})]
    (is (empty? (:injected m)))
    (is (= 1 (count (:unresolved m))))
    (is (zero? (:injected-cost-load m)))
    (is (nil? (cs/render-block m)) "nothing resolved -> nothing injected")))

(deftest identity-is-the-id-so-a-shared-display-name-cannot-shadow
  (let [m (cs/materialize {:selected [{:id "skill:repl-workflow" :kind :skill
                                       :name "repl-workflow" :cost-load 10}
                                      {:id "adv:repl-workflow" :kind :skill
                                       :name "repl-workflow" :cost-load 20}]})]
    (is (= 2 (count (:injected m))) "two ids, two entries, even with one display name")
    (is (= #{"skill:repl-workflow" "adv:repl-workflow"} (set (map :id (:injected m)))))
    (is (= 30 (:injected-cost-load m)))))

(deftest a-repeat-load-can-be-recognised-as-a-repeat
  (let [m (cs/materialize a-decision)]
    (is (cs/already-loaded? m "repl-workflow"))
    (is (cs/already-loaded? m "skill:repl-workflow"))
    (is (not (cs/already-loaded? m "mycelium")))))

(deftest telemetry-is-metadata-only
  (let [m (cs/materialize a-decision)
        attrs (cs/telemetry-attrs a-decision m)]
    (is (= "samizdat-context-selection/1" (get attrs "samizdat.context.seam_version")))
    (is (= 3 (get attrs "samizdat.context.n_selected")))
    (is (= 2 (get attrs "samizdat.context.n_injected")))
    (is (= 1 (get attrs "samizdat.context.n_already_present")))
    (is (not-any? #(str/includes? (str %) "REPL") (vals attrs))
        "no body text leaves in telemetry")))
