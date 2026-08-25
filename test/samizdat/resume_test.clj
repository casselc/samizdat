;; samizdat - a claim-first verification harness
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

(ns samizdat.resume-test
  "JS1 resume: the fail-closed ladder, deterministically.

  The whole-history reconstruction itself needs SCI, so its positive path
  is exercised for real across an OS-process boundary by
  samizdat.js1-boundary-test.  What CAN be asserted here, with no SCI on
  the classpath (the `jolt -M:test` suite shape), is every rung of the
  ladder that refuses:

  - a run with no JS1 journal event resumes with no JS1 dependency at all;
  - a JS1-profiled run whose sandbox cannot load fails CLOSED — never a
    live-eval fallthrough (under the plain suite SCI is genuinely absent,
    so :sandbox-unavailable is exercised directly);
  - journal info that lacks any reconstruction field is refused as data,
    before SCI is even required — corrupt data must not be laundered by an
    environment difference;
  - the journal round trip of the exact reconstruction information
    (workflow/js1-binding-journal-data out, resume's normalization in)
    preserves everything reconstruction needs;
  - a JS1 event on a multi-branch run is refused before the run is marked
    running again."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.resume :as resume]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.ship]
            [samizdat.engine.proc :as proc]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as wf]))

(defn- with-db [f]
  (let [c (db/open! ":memory:")]
    (try (f c) (finally (db/close c)))))

(def ^:private sci-present?
  ;; The same quiet probe sandbox-test uses: under `jolt -M:test` the SCI
  ;; roots are absent and this is false, which is the environment these
  ;; assertions are written against.
  (try (require 'samizdat.agent.sandbox) true
       (catch Throwable _ false)))

(def ^:private fake-binding
  "A structurally faithful Binding map for the journal round-trip: the
  fields js1-binding-journal-data reads, in the shapes a real binding
  carries (spec coordinate keyword keys, sorted capability keywords,
  bounds map, timeout)."
  {:binding/id "bind:main:42"
   :instance/id "inst:main"
   :work-id "42"
   :spec {:preset :project/develop
          :spec/coordinate "js1:abcdef"
          :capabilities [:project/edit :project/list :project/read
                         :project/search :project/stat]
          :bounds {:max-read-chars 60000 :max-list-entries 1000
                   :max-search-results 500 :search-max-chars 500000}
          :timeout-ms 30000}})

(defn- journal-js1-event!
  "Journal a :js1-binding-created event exactly the way workflow/run! does,
  through the real journal, with real serialization."
  ([c rid]
   (journal-js1-event! c rid (wf/js1-binding-journal-data
                              "single-player" fake-binding
                              "js1-rt/v1:fake")))
  ([c rid data]
   (journal/note! c rid :js1-binding-created {:data data})
   rid))

(deftest a-non-js1-run-resumes-with-no-js1-dependency
  ;; The absence path must not so much as require the sandbox namespace:
  ;; an unprofiled run cannot start failing on a dependency it never used.
  (with-db
    (fn [c]
      (let [rid (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1})]
        (is (nil? (@#'resume/reconstruct-run-js1-binding! c rid "/tmp"))
            "no journal event, no reconstruction, no SCI require")))))

(deftest red-resume-rebuilt-context-done-verifies-in-the-absolute-target-root
  ;; Regression for the first live dogfood: reconstruction and the GREEN edit
  ;; succeeded, but resume dropped :root when it handed the rebuilt branch to
  ;; beam/run-rounds. done therefore reached scope-run with :dir "".  Drive the
  ;; real done/verify structured boundary from the resumed ctx and capture the
  ;; primitive request; no provider or process is involved.
  (with-db
    (fn [c]
      (let [target "/home/dogfood-user/.local/share/samizdat/js1-dogfood/run/target"
            rid (runs/start-run! c {:problem "repair dogfood green"
                                    :max-turns 5 :beam-width 1})
            seen (atom nil)
            green {:pid 1 :exit 0 :timed-out false
                   :out "GREEN" :out-status :complete
                   :err "" :err-status :complete}]
        (runs/open-branch! c rid {:branch-id "B1"})
        ;; Durable RED is what makes this a recovery path rather than merely a
        ;; ctx-construction test. resume! below rebuilds the branch solely from
        ;; the run store/journal; the live dogfood test supplies the OS-process
        ;; boundary for the same assertion.
        (journal/note! c rid :ship-verify
                       {:branch-id "B1"
                        :data {:green false :blocked true :exit 1
                               :kind :fallback}})
        (binding [proc/*scope-run*
                  (fn [request]
                    (if (= ["samizdat-scope-capability-probe"] (:cmd request))
                      {}
                      (do (reset! seen request) green)))]
          (with-redefs [gitdiff/changed-files
                        (fn [root _baseline]
                          (is (= target root) "git diff receives the resumed target")
                          ["src/dogfood.clj"])
                        beam/run-rounds
                        (fn [ctx branches start-turn]
                          (let [done (base/run-tool
                                      (assoc ctx
                                             :branch (first branches)
                                             :tool-name "done"
                                             :turn start-turn
                                             :args {:answer "repair dogfood green"}))]
                            (is (:done? done) "the fake GREEN verifier permits done")
                            {:status :completed :run-id rid}))]
            (is (= :completed
                   (:status
                    (resume/resume!
                     {:conn c :run-id rid
                      :config {:run {:root target
                                     :verify-cmd "./verify-policy"
                                     :verify-timeout-ms 4321
                                     :require-test? false}}
                      :llm-adapter :unused :llm-config {}}))))))
        (is (some? @seen) "done reached the structured process primitive")
        (is (= ["sh" "-c" "./verify-policy"] (:cmd @seen)))
        (is (= target (:dir @seen)))
        (is (.isAbsolute (java.io.File. (:dir @seen)))
            "the verifier cwd is the absolute target, not source/model data")))))

(deftest resume-and-beam-roots-come-from-controller-config-with-cwd-default
  (with-db
    (fn [c]
      (let [cwd (System/getProperty "user.dir")
            configured "/home/operator/persistent-target"
            roots (atom [])
            rid (runs/start-run! c {:problem "p" :max-turns 1 :beam-width 1})]
        (runs/open-branch! c rid {:branch-id "B1"})
        (with-redefs [beam/run-rounds
                      (fn [ctx _branches _start-turn]
                        (swap! roots conj (:root ctx))
                        {:status :exhausted :run-id (:run-id ctx)})]
          (resume/resume! {:conn c :run-id rid :config {}
                           :llm-adapter :unused :llm-config {}})
          (beam/run! {:conn c :config {}
                      :llm-adapter :unused :llm-config {}
                      :problem "p" :max-turns 1 :beam-width 1})
          (beam/run! {:conn c :config {:run {:root configured}}
                      :llm-adapter :unused :llm-config {}
                      ;; Deliberately unrelated model-facing text: it cannot
                      ;; become the target root.
                      :problem "model says work somewhere else"
                      :max-turns 1 :beam-width 1}))
        (is (= [cwd cwd configured] @roots)
            "drivers default to controller cwd and honor only trusted run config")))))

(deftest js1-resume-fails-closed-on-sci-unavailability
  (with-db
    (fn [c]
      (let [rid (journal-js1-event!
                 c (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1}))]
        (try
          (resume/resume! {:conn c :run-id rid :config {}
                           :llm-adapter :a :llm-config {}})
          (is false "resume! must not return for a JS1-profiled run")
          (catch Throwable e
            (let [code (:js1/error (ex-data e))]
              (is (contains? #{:sandbox-unavailable :runtime-mismatch
                               :history-invalid :binding-id-mismatch} code)
                  (str "a JS1-profiled resume fails closed, got: " code))
              (if sci-present?
                ;; With SCI on the roots the fabricated runtime coordinate
                ;; is the first thing that can be wrong — still closed.
                (is (contains? #{:runtime-mismatch :binding-id-mismatch
                                 :history-invalid} code)
                    "with SCI present, the fabricated runtime coordinate is refused")
                ;; The suite shape: the sandbox truly cannot load, and the
                ;; refusal says so rather than guessing a mismatch.
                (is (= :sandbox-unavailable code)
                    "without SCI the refusal names the sandbox, not a guess")))))))))

(deftest js1-resume-refuses-incomplete-journal-info-as-data
  ;; Corrupt journal data is refused before SCI is even required, so no
  ;; environment difference can launder it into a reconstructed binding.
  (let [incomplete
        {:spec-coordinate "js1:x" :runtime-coordinate "js1-rt/v1:y"
         :binding-id "bind:main:1" :instance-id "inst:main"
         :preset "project/develop" :capabilities ["project/read"]
         ;; no :bounds, no :timeout-ms
         }
        thrown-code (fn [info]
                      (try
                        (resume/reconstruct-js1-binding! nil info "/tmp")
                        nil
                        (catch Throwable e (:js1/error (ex-data e)))))]
    (is (= :reconstruction-info-missing (thrown-code incomplete))
        "missing bounds/timeout are refused as data")
    (is (= :reconstruction-info-missing
           (thrown-code (assoc incomplete :bounds {:max-read-chars 60000}
                               :timeout-ms 30000
                               :spec-coordinate "")))
        "a blank spec coordinate is refused, not defaulted")
    (is (= :reconstruction-info-missing
           (thrown-code (assoc incomplete :bounds {:max-read-chars 60000}
                               :timeout-ms 0)))
        "a non-positive timeout is refused")
    (is (= :reconstruction-info-missing
           (thrown-code (assoc incomplete :timeout-ms 30000
                               :bounds {:max-read-chars "many"})))
        "a non-integer bound is refused")
    (is (= :reconstruction-info-missing (thrown-code "not a map"))
        "non-map journal data is refused")))

(deftest journal-round-trips-exact-js1-reconstruction-info
  ;; The production writer (workflow) and the production reader (resume)
  ;; agree through the REAL journal serialization, not just in memory.
  (with-db
    (fn [c]
      (let [rid (journal-js1-event!
                 c (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1}))]
        (is (= 1 (count (filter #(= "js1-binding-created" (:kind %))
                                (journal/events-since c rid 0))))
            "the event landed")
        (let [raw (some #(when (= "js1-binding-created" (:kind %)) %)
                        (journal/events-since c rid 0))
              ;; The exact parse resume performs on the JSON column.
              parsed (try
                       (json/read-str (:data raw) :key-fn keyword)
                       (catch Throwable _ ::unparseable))]
          (is (not= ::unparseable parsed) "the journaled data is JSON")
          (let [norm (@#'resume/normalize-js1-info parsed)]
            (is (= "bind:main:42" (:binding-id norm)))
            (is (= "inst:main" (:instance-id norm)))
            (is (= :project/develop (:preset norm))
                "the preset keyword survives the JSON round trip")
            (is (= "js1:abcdef" (:spec-coordinate norm)))
            (is (= "js1-rt/v1:fake" (:runtime-coordinate norm)))
            (is (= [:project/edit :project/list :project/read
                    :project/search :project/stat]
                   (:capabilities norm))
                "capabilities read back as full-name keywords, sorted")
            (is (= {:max-read-chars 60000 :max-list-entries 1000
                    :max-search-results 500 :search-max-chars 500000}
                   (:bounds norm))
                "bounds keys re-keyword through the round trip")
            (is (= 30000 (:timeout-ms norm)))))))))

(deftest js1-resume-refuses-a-multi-branch-run-before-any-work
  (with-db
    (fn [c]
      (let [rid (journal-js1-event!
                 c (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 3}))
            _ (runs/finish-run! c rid :failed nil)]
        (is (resume/resumable? c rid) "sanity: the fixture is resumable")
        (try
          (resume/resume! {:conn c :run-id rid :config {}
                           :llm-adapter :a :llm-config {}})
          (is false "a JS1 event on a width-3 run must not resume")
          (catch Throwable e
            (is (= :multi-branch-not-supported (:js1/error (ex-data e)))
                "refused as the single-player shape violation it is")
            (is (= "failed" (:status (runs/get-run c rid)))
                "and refused BEFORE the run is marked running again")))))))

(deftest the-single-branch-guard-admits-width-one
  ;; The guard exists to refuse a SHAPE, not the profile: a plain
  ;; single-branch JS1 run is exactly what the profile is for.
  (is (nil? (base/js1-assert-single-branch! true 1)))
  (is (nil? (base/js1-assert-single-branch! false 5))
      "a non-JS1 beam at any width is untouched")
  (is (thrown? ExceptionInfo (base/js1-assert-single-branch! true 2))))
