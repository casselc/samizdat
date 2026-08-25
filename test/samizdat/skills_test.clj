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

(deftest frontmatter-parses-and-drives-the-catalogue
  (let [fm (skills/parse-frontmatter "---\nname: x\ndescription: Use when Y.\n---\n# T\nbody")]
    (is (= "Use when Y." (get-in fm [:meta :description])))
    (is (= "# T\nbody" (:body fm))))
  (let [fm (skills/parse-frontmatter "no frontmatter here\nmore")]
    (is (= {} (:meta fm)))
    (is (str/starts-with? (:body fm) "no frontmatter"))))

(deftest the-catalogue-is-always-renderable-with-triggers
  (let [r (skills/render-catalog)]
    (is (str/includes? r "mycelium"))
    (is (str/includes? r "Use when") "the description is a when-to-use trigger")
    (is (not (str/includes? r "defcell")) "the body is NOT in the always-on catalogue")))

(deftest a-skill-with-no-description-drops-from-the-catalogue-but-stays-loadable
  (is (nil? (#'skills/describe {:meta {} :body "# only a heading"} "h"))
      "no frontmatter description and only headings -> no trigger")
  (is (some? (#'skills/describe {:meta {} :body "a real line"} "h"))))

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

(deftest a-load-without-a-name-is-a-mechanics-complaint-with-a-skeleton
  ;; provenance CR1-1: base/missing was called with `branch` and its
  ;; complaint string returned RAW as the tool result — no :category, no
  ;; :branch — so tool-step threaded a nil branch into state/record-outcome,
  ;; which NPE'd on (update nil :turns-since-progress inc).
  (let [r (base/run-tool {:branch {:id "B1"} :tool-name "skill"
                          :args {:action "load"}})]
    (is (= :mechanics (:category r)) "a missing arg is malformed, not a failure")
    (is (map? (:branch r)) "the branch rides along")
    (is (str/includes? (:result r) "Missing required argument(s): name"))
    (is (str/includes? (:result r) "\"skill\"") "the skeleton names the tool")))
