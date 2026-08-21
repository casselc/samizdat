;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.workflow-test
  "The loop as data: the workflow definition lives in the db, compiles through
  mycelium's checks, and the manifest-driven driver produces the same runs the
  hand-written loop did. Editing the stored definition changes the next run —
  that is the whole point."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.state :as state]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.store.workflows :as workflows]
            [samizdat.workflow :as workflow]
            [mycelium.workflow :as wf]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(defn- fence [m]
  {:content (str "```tool-call\n" (json/write-str m) "\n```")
   :finish-reason "stop"})

(defn- scripted
  "An llm/chat replacement that returns each response in turn, repeating the
  last one when the script runs out."
  [& responses]
  (let [remaining (atom responses)]
    (fn [& _]
      (let [[r & more] @remaining]
        (when (seq more) (reset! remaining more))
        r))))

;; --- the store --------------------------------------------------------------

(deftest workflow-store-roundtrip-and-versioning
  (with-db [c]
    (is (nil? (workflows/load-latest c "loop")))
    (is (= 1 (workflows/save! c "loop" "{:cells {}}")))
    (is (= 2 (workflows/save! c "loop" "{:cells {:a :b}}")))
    (let [w (workflows/load-latest c "loop")]
      (is (= 2 (:version w)))
      (is (= "{:cells {:a :b}}" (:edn w))))
    (is (= "{:cells {}}" (:edn (workflows/load-version c "loop" 1))))))

(deftest seeding-is-idempotent
  (with-db [c]
    (is (= 1 (:version (workflows/seed! c "loop" "manifests/loop.edn"))))
    (is (= 1 (:version (workflows/seed! c "loop" "manifests/loop.edn")))
        "a second seed does not stack versions")
    (is (some? (:edn (workflows/load-latest c "loop"))))))

;; --- the definition ---------------------------------------------------------

(deftest the-shipped-loop-definition-compiles-clean
  (let [def (workflow/read-definition (slurp (clojure.java.io/resource "manifests/loop.edn")))
        compiled (workflow/compile-loop def)]
    (is (some? compiled))
    (is (nil? (:mycelium/compile-warnings (:compiled-fsm compiled)))
        "every loop cell declares its effects")))

(deftest removing-the-journal-hop-fails-compile
  ;; The constraint is the mutation protocol's teeth: an agent edit that
  ;; routes a tool result around the journal must die at compile, not ship.
  (let [def (workflow/read-definition (slurp (clojure.java.io/resource "manifests/loop.edn")))
        ;; Route the tool path around the journal while keeping :journal
        ;; reachable from the no-call path, so the unreachable check cannot
        ;; catch it first — only the constraint can.
        broken (-> def
                   (assoc-in [:edges :dispatch] :arbiter)
                   (assoc-in [:edges :no-call] :journal))]
    (is (thrown-with-msg? Exception #"must-follow"
                          (workflow/compile-loop broken)))))

;; --- the driver -------------------------------------------------------------

(deftest a-scripted-run-ships-through-the-manifest
  (with-db [c]
    (with-redefs [llm/chat (scripted
                            (fence {:name "thesis"
                                    :args {:goal "solve the problem"
                                           :technique "direct"}})
                            (fence {:name "done"
                                    :args {:answer "the problem is solved directly"}}))]
      (let [r (workflow/run! {:conn c :config {:run {}}
                              :llm-adapter :a :llm-config {:max-tokens 16384}
                              :problem "solve the problem" :max-turns 10})]
        (is (= :completed (:status r)))
        (is (= "the problem is solved directly" (:answer r)))
        (let [turns (journal/branch-turns c (:run-id r) "B1")]
          (is (= ["thesis" "done"] (mapv :tool_name turns))))
        (is (= "completed" (:status (runs/get-run c (:run-id r)))))))))

(deftest the-turn-cap-exhausts-through-the-manifest
  (with-db [c]
    (with-redefs [llm/chat (scripted
                            (fence {:name "thesis"
                                    :args {:goal "keep going" :technique "loop"}}))]
      (let [r (workflow/run! {:conn c :config {:run {}}
                              :llm-adapter :a :llm-config {:max-tokens 16384}
                              :problem "never finishes" :max-turns 2})]
        (is (= :exhausted (:status r)))
        (is (some? (:residual r)))
        (is (= 2 (count (journal/branch-turns c (:run-id r) "B1"))))
        (is (= "failed" (:status (runs/get-run c (:run-id r)))))))))

(deftest editing-the-stored-definition-changes-the-next-run
  ;; The acceptance in one test: save a v2 of the loop from the REPL and the
  ;; next run behaves differently, no restart, no code change.
  (with-db [c]
    (with-redefs [llm/chat (scripted
                            (fence {:name "thesis"
                                    :args {:goal "g" :technique "t"}}))]
      ;; Seed v1, then write a v2 that routes every response down the no-call
      ;; path — a visible behavior change made purely by editing stored EDN.
      (workflows/seed! c "loop" "manifests/loop.edn")
      (let [v1 (edn/read-string (:edn (workflows/load-latest c "loop")))
            v2 (assoc-in v1 [:dispatches :parse]
                         '[[:provider-error (fn [d] (not (:ok (:call d))))]
                           [:no-call (fn [d] true)]
                           [:tool (fn [d] false)]])]
        (workflows/save! c "loop" (pr-str v2))
        (let [r (workflow/run! {:conn c :config {:run {}}
                                :llm-adapter :a :llm-config {:max-tokens 16384}
                                :problem "p" :max-turns 1})]
          (is (= :exhausted (:status r)))
          (is (= ["mechanics"]
                 (mapv :category (journal/branch-turns c (:run-id r) "B1")))
              (str "v2 sends every response down the no-call path, which"
                   " journals it as mechanics — v1 dispatches the same"
                   " response as a neutral thesis turn"))
          (is (some #(and (= "loop-workflow" (:kind %))
                          (str/includes? (str (:data %)) "2"))
                    (journal/events-since c (:run-id r) 0 100))
              "the run records which workflow version drove it"))))))

(deftest provider-failure-routes-through-the-manifest
  (with-db [c]
    (let [calls (atom 0)]
      (with-redefs [llm/chat (fn [& _]
                               (if (= 1 (swap! calls inc))
                                 (throw (ex-info "socket reset" {}))
                                 (fence {:name "done"
                                         :args {:answer "recovered and finished"}})))]
        (let [r (workflow/run! {:conn c :config {:run {}}
                                :llm-adapter :a :llm-config {:max-tokens 16384}
                                :problem "recovered and finished" :max-turns 5})]
          (is (= :completed (:status r)))
          (let [turns (journal/branch-turns c (:run-id r) "B1")]
            (is (= "__provider_error__" (:tool_name (first turns)))
                "the failed call is journalled like any turn")))))))

(deftest workflow-effects-are-fully-declared
  (let [def (workflow/read-definition (slurp (clojure.java.io/resource "manifests/loop.edn")))
        fx (wf/workflow-effects def)]
    (is (not-any? :undeclared (vals fx))
        (str "cells with undeclared effects: "
             (keep (fn [[k v]] (when (:undeclared v) k)) fx)))
    (is (:pure (get fx :parse)) "fence parsing is pure")
    (is (contains? (:effects (get fx :infer)) :net))))
