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
            [samizdat.store.userspace :as us]
            [samizdat.workflow :as wf]))

(defn- with-db [f]
  (let [conn (db/open! ":memory:")]
    (f conn)))

(deftest factory-manifest-names-match-what-ships
  ;; wf/catalog used to glob a cwd-relative "resources/manifests", which found
  ;; nothing from a built binary or a process started elsewhere and silently
  ;; served a catalogue with the factory half missing (the provenance R3-11 bug in
  ;; a second place). It now resolves an enumerated list through io/resource,
  ;; which cannot drift on its own — so pin the list against the directory.
  (let [on-disk (->> (file-seq (io/file "resources/manifests"))
                     (filter #(str/ends-with? (.getName %) ".edn"))
                     (map #(str/replace (.getName %) #"\.edn$" ""))
                     set)
        catalogued (set (map :name (wf/catalog nil)))]
    (is (seq on-disk) "the manifests dir is readable from the test's cwd")
    (is (= on-disk catalogued)
        (str "wf/catalog and resources/manifests disagree; missing from the"
             " catalogue: " (sort (remove catalogued on-disk))
             ", catalogued but absent from disk: "
             (sort (remove on-disk catalogued))))))

(deftest turn-manifest-is-one-turn-of-the-loop
  ;; The beam drives the per-TURN slice of a manifest, derived rather than
  ;; maintained as a second file: edges back to :start and edges into
  ;; :loop/finish become :end, and the finish node goes away. This is what
  ;; lets one driver serve both the scheduler and the single-branch path, and
  ;; what finally makes :run :loop reach a production run.
  (let [def' (wf/read-definition (slurp (io/resource "manifests/loop.edn")))
        turn (wf/turn-manifest def')]
    (testing "the back edge terminates the turn"
      (is (= :end (:continue (:route (:edges turn))))))
    (testing "the slice is ONE turn — no path through it returns to :start"
      ;; Stated as acyclicity rather than as a literal route map. The map
      ;; version pinned {:done :end :abandoned :end :exhausted :end} and so
      ;; failed the moment an ending was routed through a legitimate extra
      ;; node (:distil) on its way out — a test that breaks on a correct
      ;; change is a test that gets edited to match, which is no test. What
      ;; the rewrite actually has to guarantee is that the slice terminates.
      (let [targets (fn [to] (if (map? to) (vals to) [to]))
            walk (fn walk [node seen]
                   (cond
                     (= :end node) true
                     (contains? seen node) false
                     :else (every? #(walk % (conj seen node))
                                   (targets (get (:edges turn) node)))))]
        (is (walk :start #{})
            "every path from :start reaches :end without revisiting a node")))
    (testing "the finish node is dropped, not orphaned"
      (is (contains? (:cells def') :finish))
      (is (not (contains? (:cells turn) :finish)))
      (is (not (contains? (:edges turn) :finish))))
    (testing "the per-turn chain is untouched"
      (is (= (:infer (:edges def')) (:infer (:edges turn))))
      (is (= (:parse (:edges def')) (:parse (:edges turn))))
      (is (= (:dispatches def') (:dispatches turn)))
      (is (= (:constraints def') (:constraints turn))))))

(deftest every-shipped-manifest-has-a-compilable-turn-slice
  ;; The rewrite must leave a graph mycelium still accepts — reachable nodes,
  ;; covered dispatches, satisfied constraints — for every manifest, not just
  ;; the factory loop. A slice that fails to compile is a run that cannot
  ;; start, and the beam compiles this before POST /v1/runs answers.
  (doseq [nm ["loop" "critic" "review" "worker" "reviewer" "supervisor"
              "orchestrator" "team" "feature" "decompose"]]
    (testing nm
      (let [d (wf/read-definition (slurp (io/resource (wf/manifest-resource nm))))]
        (is (some? (wf/compile-loop (wf/turn-manifest d)))
            (str nm "'s turn slice does not compile"))))))

(deftest iterating-classification-decides-width-and-deadline
  ;; A pass through the slice is one model call only when the slice contains
  ;; :llm/infer AND loops back to start. Both halves matter: orchestrator
  ;; loops back to a start node that is an entire nested worker run, and
  ;; scheduling that as a "turn" would run five whole runs at once under a
  ;; 900s deadline meant for one provider call.
  (let [iterating? (fn [nm]
                     (wf/iterating?
                      (wf/read-definition
                       (slurp (io/resource (wf/manifest-resource nm))))))]
    (doseq [nm ["loop" "critic" "review" "worker" "reviewer" "supervisor"]]
      (is (true? (iterating? nm)) (str nm " is a per-turn loop")))
    (doseq [nm ["team" "feature" "decompose" "orchestrator"]]
      (is (false? (iterating? nm)) (str nm " is a whole-run workflow")))))

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
            (is (= 1 (:version (us/load-latest conn :manifest "loop2"))))
            (is (= "loop2" (:name (wf/load-loop! conn "loop2"))))))
        (testing "a manifest that cannot compile is refused, not stored"
          (let [r (base/run-tool {:branch {:id "B1"} :conn conn :tool-name "manifest"
                                  :args {:action "save" :name "bad"
                                         :edn "{:cells {:x :no-such-cell}}"}})]
            (is (= :failure (:category r)))
            (is (nil? (us/load-latest conn :manifest "bad")) "nothing broken was stored")))))))

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

(deftest show-and-save-missing-their-name-are-mechanics-complaints
  ;; provenance CR1-1, same shape as the skill tool: base/missing was
  ;; handed `branch` instead of ctx and its string returned raw, dropping
  ;; :category/:branch from the result map.
  (with-db
    (fn [conn]
      (let [show (base/run-tool {:branch {:id "B1"} :conn conn :tool-name "manifest"
                                 :args {:action "show"}})
            save (base/run-tool {:branch {:id "B1"} :conn conn :tool-name "manifest"
                                 :args {:action "save" :edn "{:cells {}"}})]
        (is (= :mechanics (:category show)))
        (is (map? (:branch show)))
        (is (= :mechanics (:category save)))
        (is (str/includes? (:result save) "Missing required argument(s): name"))
        (is (str/includes? (:result save) "\"manifest\"") "the skeleton names the tool")))))
