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

(ns samizdat.manifest-test
  "Multiple named loop manifests: config selects which drives a run, and the
  manifest tool lists/shows/saves them behind a real compile."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.manifest]
            [samizdat.store.db :as db]
            [samizdat.store.workflows :as workflows]
            [samizdat.workflow :as wf]))

(defn- with-db [f]
  (let [conn (db/open! ":memory:")]
    (f conn)))

(deftest active-loop-name-comes-from-config
  (is (= "loop" (wf/active-loop-name {})))
  (is (= "loop" (wf/active-loop-name {:run {}})))
  (is (= "critic" (wf/active-loop-name {:run {:loop "critic"}}))))

(deftest the-default-loop-seeds-and-compiles
  (with-db
    (fn [conn]
      (let [{:keys [name version compiled]} (wf/load-loop! conn)]
        (is (= "loop" name))
        (is (= 1 version))
        (is (some? compiled) "the factory loop compiles")))))

(deftest a-named-manifest-with-no-resource-and-no-row-is-an-error
  (with-db
    (fn [conn]
      (is (thrown? Exception (wf/load-loop! conn "does-not-exist"))))))

(deftest saving-a-manifest-validates-then-stores
  (with-db
    (fn [conn]
      (let [good (slurp (io/resource "manifests/loop.edn"))]
        (testing "a manifest that compiles is stored and then loads by name"
          (let [r (base/run-tool {:branch {:id "B1"} :conn conn :tool-name "manifest"
                                  :args {:action "save" :name "loop2" :edn good}})]
            (is (= :neutral (:category r)))
            (is (= 1 (:version (workflows/load-latest conn "loop2"))))
            (is (= "loop2" (:name (wf/load-loop! conn "loop2"))))))
        (testing "a manifest that cannot compile is refused, not stored"
          (let [r (base/run-tool {:branch {:id "B1"} :conn conn :tool-name "manifest"
                                  :args {:action "save" :name "bad"
                                         :edn "{:cells {:x :no-such-cell}}"}})]
            (is (= :failure (:category r)))
            (is (nil? (workflows/load-latest conn "bad")) "nothing broken was stored")))))))

(deftest a-composed-manifest-registers-and-compiles-its-sub-loops
  (with-db
    (fn [conn]
      (let [loaded (wf/load-loop! conn "orchestrator")]
        (is (= "orchestrator" (:name loaded)))
        (is (some? (:compiled loaded)) "the top level compiles once its sub-loops are registered")
        (is (= {:loop/worker "worker"} (:subworkflows (:definition loaded)))
            "it declares the worker sub-loop")))))

(deftest a-manifest-can-inject-its-own-prompt
  (with-db
    (fn [conn]
      (let [loaded (wf/load-loop! conn "review")]
        (is (some? (:compiled loaded)) "the review workflow compiles with a :prompt")
        (is (= "review" (:prompt (:definition loaded))))
        (is (str/includes? (wf/workflow-prompt (:definition loaded)) "CODE REVIEW")
            "the manifest's prompt resource is resolved")
        (is (nil? (wf/workflow-prompt {:cells {}}))
            "a manifest with no :prompt injects nothing")))))

(deftest list-and-show-round-trip
  (with-db
    (fn [conn]
      (wf/load-loop! conn)                                  ; seed "loop"
      (let [lst (base/run-tool {:branch {:id "B1"} :conn conn :tool-name "manifest"
                                :args {:action "list"}})]
        (is (re-find #"loop.*factory" (:result lst))))
      (let [shown (base/run-tool {:branch {:id "B1"} :conn conn :tool-name "manifest"
                                  :args {:action "show" :name "loop"}})]
        (is (re-find #":cells" (:result shown)) "shows the manifest as data")))))
