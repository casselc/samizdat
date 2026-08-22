;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.skills-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [samizdat.agent.skills :as skills]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.skills]))

(deftest the-bundled-mycelium-skill-is-discovered
  (let [cat (skills/catalog)]
    (is (some #(= "mycelium" (:name %)) cat) "mycelium is in the catalogue")
    (is (every? (comp seq :description) cat) "every skill has a description")))

(deftest load-returns-content-and-nil-for-a-miss
  (is (str/includes? (skills/load-skill "mycelium") "defcell"))
  (is (nil? (skills/load-skill "no-such-skill"))))

(deftest the-skill-tool-lists-and-loads
  (let [lst (base/run-tool {:branch {:id "B1"} :tool-name "skill" :args {:action "list"}})]
    (is (str/includes? (:result lst) "mycelium")))
  (let [ld (base/run-tool {:branch {:id "B1"} :tool-name "skill"
                           :args {:action "load" :name "mycelium"}})]
    (is (= :neutral (:category ld)) "loading a skill is neutral bookkeeping")
    (is (str/includes? (:result ld) "manifest")))
  (let [miss (base/run-tool {:branch {:id "B1"} :tool-name "skill"
                             :args {:action "load" :name "nope"}})]
    (is (= :mechanics (:category miss)) "an unknown skill is a malformed call")))
