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

(ns samizdat.telemetry.otel-test
  "The semantic wrappers against the in-memory exporter: attribute types,
  false/zero/absent, signed negatives, the Langfuse mirror, lifecycle spans
  through the hook, suppression, and TRACEPARENT parenting."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [otel.context :as ctx]
            [otel.exporter.memory :as memory]
            [otel.trace :as trace]
            [samizdat.store.db :as db]
            [samizdat.store.lifecycle :as lc]
            [samizdat.telemetry.contract :as contract]
            [samizdat.telemetry.hook :as hook]
            [samizdat.telemetry.otel :as tel]))

(def ^:dynamic *mem* nil)

(defn- with-runtime [mode f]
  (let [mem (memory/exporter)]
    (tel/shutdown!)
    (tel/init! {:mode mode :exporters {:local mem :langfuse mem}
                :batch {:schedule-delay-ms 50}})
    (try (binding [*mem* mem] (f))
         (finally (tel/shutdown!) (hook/uninstall!)))))

(use-fixtures :each (fn [f] (with-runtime :local f)))

(defn- exported []
  (tel/flush!)
  (memory/spans *mem*))

(defn- by-name [n] (first (filter #(= n (:name %)) (exported))))

(deftest execution-span-carries-typed-facts
  (tel/with-execution [sp "execution" {"samizdat.run.id" "r1"
                                       "samizdat.execution.id" "p1"
                                       "samizdat.execution.kind" "parent"
                                       "samizdat.seed" 12001
                                       "samizdat.observation.mode" "synthetic"}]
    (tel/record-outcome! sp {:worker-claim "fixed" :worker-claimed true :protocol-correct false
                             :infra-error false :task-complete false :terminal-status "claimed"})
    (tel/record-budgets! sp {:turns {:granted 20 :used 20 :remaining 0}
                             :wall {:granted_ms 305100 :used_ms 312431 :remaining_ms -7331}})
    (trace/set-attribute! sp "gen_ai.usage.output_tokens" 0))
  (let [s (by-name "execution")
        a (:attributes s)]
    (is (some? s))
    (is (= "agent" (get a "samizdat.observation.kind")))
    (is (= "agent" (get a "langfuse.observation.type")))
    (is (= "r1" (get a "langfuse.session.id")) "session falls back to run id")
    (is (= 12001 (get a "samizdat.seed")))
    (is (true? (get a "samizdat.worker.claimed")))
    (is (false? (get a "samizdat.protocol.correct")))
    (is (false? (get a "samizdat.infra.error")))
    (is (false? (get a "samizdat.task.complete")))
    (is (= 0 (get a "samizdat.budget.turns.remaining")))
    (is (= -7331 (get a "samizdat.budget.wall.remaining_ms")))
    (is (= 0 (get a "gen_ai.usage.output_tokens")))
    (is (= "parent" (get a "langfuse.trace.metadata.execution_kind")))
    (is (not (contains? a "langfuse.observation.level")) "no infra error, no ERROR level")
    (is (= :unset (get-in s [:status :code]) ) "a failed protocol verdict is not a span error")))

(deftest infra-error-is-a-level-not-a-score
  (tel/with-evaluator [sp "action.check" {"samizdat.evaluator.id" "s80/5"
                                          "samizdat.evaluator.status" "infra-error"
                                          "samizdat.evaluator.purpose" "operational"
                                          "samizdat.evaluator.exit" 2}]
    (tel/record-feedback! sp {:delivered true :status "infra-error" :sha256 "ab" :bytes 6063
                              :projection "verdict-and-log" :policy "inform@1"}))
  (let [s (by-name "action.check") a (:attributes s)]
    (is (= "evaluator" (get a "langfuse.observation.type")))
    (is (= "ERROR" (get a "langfuse.observation.level")))
    (is (= "infra-error" (get a "samizdat.feedback.status")))
    (is (= 6063 (get a "samizdat.feedback.bytes")))
    (is (true? (get a "samizdat.feedback.delivered")))
    (is (= "feedback.delivered" (:name (first (:events s)))))
    (is (not-any? #(re-find #"score" (str %)) (keys a)))))

(deftest unknown-and-ill-typed-facts-fall-back-to-strings
  (tel/with-tool [_ "tool" {"samizdat.tool.name" "run"
                            "samizdat.tool.seconds" 0.25
                            "samizdat.tool.output_chars" "lots"
                            "samizdat.private.thing" "x"}])
  (let [a (:attributes (by-name "tool"))]
    (is (= 0.25 (get a "samizdat.tool.seconds")))
    (is (= "lots" (get a "samizdat.x.samizdat.tool.output_chars")))
    (is (= "x" (get a "samizdat.x.samizdat.private.thing")))
    (is (not (contains? a "samizdat.private.thing")))))

(deftest lifecycle-seams-emit-spans-through-the-hook
  (let [conn (db/open! ":memory:")]
    (try
      (is (hook/installed?))
      (tel/with-execution [_ "execution" {"samizdat.execution.id" "e"}]
        (lc/upsert-case! conn {:case-id "c1" :scenario-id "s" :checkpoint-id "k" :spec {:a 1}})
        (lc/acquire-lease! conn "c1" "h" 60000)
        (lc/transition! conn {:decision-id "d1" :event-id "e1" :event-type "action-proposed"
                              :revision 0 :case-id "c1" :evaluation-id "ev" :lease-holder "h"})
        (lc/transition! conn {:decision-id "d1" :event-id "e1" :event-type "action-proposed"
                              :revision 0 :case-id "c1" :evaluation-id "ev" :lease-holder "h"}))
      (let [spans (exported)
            names (map :name spans)
            root (by-name "execution")
            ts (filter #(= "lifecycle.transition" (:name %)) spans)]
        (is (= ["lifecycle.upsert-case" "lifecycle.acquire-lease"
                "lifecycle.transition" "lifecycle.transition" "execution"]
               names))
        (is (every? #(= (get-in root [:span-context :span-id]) (:parent-span-id %))
                    (remove #(= "execution" (:name %)) spans))
            "lifecycle spans parent under the execution span")
        (is (= ["applied" "duplicate"] (map #(get-in % [:attributes "samizdat.lifecycle.status"]) ts)))
        (is (= [1 1] (map #(get-in % [:attributes "samizdat.lifecycle.revision"]) ts)))
        (is (= "lifecycle" (get-in (first ts) [:attributes "samizdat.execution.kind"])))
        (is (= "span" (get-in (first ts) [:attributes "langfuse.observation.type"]))))
      (finally (db/close conn)))))

(deftest hook-observer-failure-never-changes-the-store
  (let [conn (db/open! ":memory:")]
    (try
      (hook/install! (fn [_ _ _] (throw (ex-info "telemetry broke" {}))))
      (is (= :created (:status (lc/upsert-case! conn {:case-id "c" :scenario-id "s" :checkpoint-id "k" :spec {}}))))
      (is (= :existing (:status (lc/upsert-case! conn {:case-id "c" :scenario-id "s" :checkpoint-id "k" :spec {}}))))
      (finally (db/close conn)))))

(deftest suppressed-context-records-nothing
  (ctx/with-instrumentation-suppressed
    (tel/with-tool [_ "suppressed" {}]))
  (tel/with-tool [_ "visible" {}])
  (is (= ["visible"] (map :name (exported)))))

(deftest off-mode-installs-nothing
  (tel/shutdown!)
  (is (nil? (tel/init! {:mode :off})))
  (is (not (hook/installed?)))
  (is (nil? (tel/stats)))
  (tel/with-execution [sp "noop" {"samizdat.execution.id" "x"}]
    (is (not (trace/recording? sp)))))

(deftest inbound-traceparent-parents-the-root
  (let [c (otel.propagation/extract-context
           {"traceparent" "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"})]
    (ctx/with-context c
      (tel/with-execution [_ "child" {"samizdat.execution.id" "x"}]))
    (let [s (by-name "child")]
      (is (= "0af7651916cd43dd8448eb211c80319c" (get-in s [:span-context :trace-id])))
      (is (= "b7ad6b7169203331" (:parent-span-id s))))))

(deftest trace-metadata-is-mirrored-from-the-root-observation-only
  ;; Live finding (mapping/1): two evaluator.check children each mirrored
  ;; samizdat.cost.action_charged_s into langfuse.trace.metadata.*, and the
  ;; trace kept the last writer's value. Under mapping/2 only the agent
  ;; (root) span writes trace metadata; children keep the same fact in
  ;; their own observation metadata.
  (tel/with-execution [root "execution" {"samizdat.run.id" "r1"
                                         "samizdat.execution.kind" "continuation"
                                         "samizdat.observation.mode" "synthetic"}]
    (tel/with-evaluator [_ "action.check" {"samizdat.evaluator.id" "s80/5"
                                           "samizdat.evaluator.status" "passed"
                                           "samizdat.evaluator.purpose" "operational"
                                           "samizdat.cost.action_charged_s" 0.045}])
    (tel/with-evaluator [sp "action.check2" {"samizdat.evaluator.id" "s80/5"
                                             "samizdat.evaluator.purpose" "operational"}]
      (tel/set-facts! sp {"samizdat.cost.action_charged_s" 0.042
                          "samizdat.evaluator.status" "failed"}))
    (tel/record-budgets! root {:turns {:remaining 3}}))
  (let [r (:attributes (by-name "execution"))
        c1 (:attributes (by-name "action.check"))
        c2 (:attributes (by-name "action.check2"))]
    (is (= "continuation" (get r "langfuse.trace.metadata.execution_kind")))
    (is (= 3 (get r "langfuse.trace.metadata.budget_turns_remaining")) "set-facts! on the root still writes trace metadata")
    (is (= 0.045 (get c1 "samizdat.cost.action_charged_s")) "canonical key always kept")
    (is (= 0.045 (get c1 "langfuse.observation.metadata.cost_action_charged_s")))
    (is (= 0.042 (get c2 "langfuse.observation.metadata.cost_action_charged_s")) "set-facts! reads the span's kind")
    (is (= "failed" (get c2 "langfuse.observation.metadata.evaluator_status")))
    (doseq [a [c1 c2]]
      (is (not-any? #(clojure.string/starts-with? % "langfuse.trace.metadata.") (keys a))
          "a child never writes trace-level metadata"))))

(deftest observation-content-travels-only-in-synthetic-mode
  (tel/with-execution [_ "synthetic-exec" {"samizdat.run.id" "r1" "samizdat.observation.mode" "synthetic"}]
    (tel/with-generation [sp "gen" {"gen_ai.request.model" "stub-model"
                                    "samizdat.observation.mode" "synthetic"
                                    "langfuse.observation.input" "SYNTHETIC-IN"}]
      (tel/set-facts! sp {"langfuse.observation.output" "SYNTHETIC-OUT"})))
  (tel/with-execution [_ "live-exec" {"samizdat.run.id" "r2" "samizdat.observation.mode" "live"}]
    (tel/with-tool [sp "tool" {"samizdat.tool.name" "run"
                               "samizdat.observation.mode" "live"
                               "langfuse.observation.input" "LEAK-IN"}]
      (tel/set-facts! sp {"langfuse.observation.output" "LEAK-OUT"})))
  (tel/with-tool [sp "modeless" {"samizdat.tool.name" "run"
                                 "langfuse.observation.output" "LEAK-MODELESS"}])
  (let [g (:attributes (by-name "gen"))
        t (:attributes (by-name "tool"))
        m (:attributes (by-name "modeless"))]
    (is (= "SYNTHETIC-IN" (get g "langfuse.observation.input")))
    (is (= "SYNTHETIC-OUT" (get g "langfuse.observation.output")) "set-facts! reads the span's mode")
    (is (not (contains? g "samizdat.x.content.refused")))
    (doseq [a [t m]]
      (is (not-any? #(re-find #"LEAK" (str %)) (vals a)) "refused content never reaches the span")
      (is (not (contains? a "langfuse.observation.input")))
      (is (not (contains? a "langfuse.observation.output"))))
    (is (= "langfuse.observation.input,langfuse.observation.output" (get t "samizdat.x.content.refused"))
        "refusals accumulate across prepare and set-facts!")
    (is (= "langfuse.observation.output" (get m "samizdat.x.content.refused")) "absent mode refuses")))

(deftest prepare-is-pure-and-manifest-driven
  (let [a (tel/prepare :generation {"gen_ai.request.model" "stub-model"
                                    "gen_ai.usage.input_tokens" 12
                                    "samizdat.family.id" "fam"})]
    (is (= "generation" (get a "langfuse.observation.type")))
    (is (= "stub-model" (get a "langfuse.observation.model.name")))
    (is (= "fam" (get a "langfuse.session.id")))
    (is (= (contract/schema-version) (get a "samizdat.telemetry.schema")))))
