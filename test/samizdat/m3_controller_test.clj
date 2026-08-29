;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.m3-controller-test
  "Durable M3 authority, provenance, and read-only evidence gates."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.state :as state]
            [samizdat.api.control :as api-control]
            [samizdat.security.controller :as controller]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as evaluator-store]
            [samizdat.store.inference :as inference]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest migrations-after-v20-install-the-closed-world-records
  (with-db [c]
    (let [tables (set (db/table-names c))
          run-columns (set (map (comp keyword :name)
                                (db/fetch c ["PRAGMA table_info(runs)"])))
          turn-columns (set (map (comp keyword :name)
                                 (db/fetch c ["PRAGMA table_info(turns)"])))]
      (is (every? tables ["evaluator_bindings" "inference_epochs"
                          "budget_extensions"]))
      (is (contains? run-columns :terminal_reason))
      (is (contains? turn-columns :inference_epoch_id))
      (is (= 24 (:user_version (db/fetch-one c ["PRAGMA user_version"])))))))

(defn- budget-fixture [c]
  (let [rid (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1})]
    (runs/open-branch! c rid {:branch-id "B1"})
    (runs/open-branch! c rid {:branch-id "B2"})
    (runs/close-branch! c rid "B1" :exhausted "turn cap")
    (runs/close-branch! c rid "B2" :culled "dominated")
    rid))

(deftest budget-extension-is-opaque-monotonic-audited-and-idempotent
  (with-db [c]
    (let [rid (budget-fixture c)
          token "controller-test-token"
          authority (controller/authority
                     {:controller {:budget-token token
                                   :budget-ceiling 20
                                   :budget-principal "operator"}})
          ask {:run-id rid :request-id "raise-1" :new-max 12
               :reason "close but out of turns"}]
      (testing "request-shaped data and the raw token carry no authority"
        (doseq [forged [nil {:budget-token token} token
                        (controller/map->Authority
                         {:token-digest "unminted" :ceiling 20
                          :principal "forger"})]]
          (is (= :unauthorized
                 (:code (controller/extend-budget! forged c ask)))))
        (is (= 5 (:max_turns (runs/get-run c rid))))
        (is (empty? (runs/budget-extensions c rid))))
      (testing "one controller act raises, reopens only exhaustion, and audits"
        (let [first (controller/extend-budget! authority c ask)
              replay (controller/extend-budget! authority c ask)]
          (is (:ok first))
          (is (= ["B1"] (:reopened first)))
          (is (= 12 (:max_turns (runs/get-run c rid))))
          (is (= "active" (:status (runs/get-branch c rid "B1"))))
          (is (= "culled" (:status (runs/get-branch c rid "B2"))))
          (is (:replayed? replay))
          (is (= 1 (count (runs/budget-extensions c rid))))))
      (testing "the cap and configured token stay bounded"
        (is (= :not-monotonic
               (:code (controller/extend-budget!
                       authority c (assoc ask :request-id "raise-2")))))
        (is (= :over-ceiling
               (:code (controller/extend-budget!
                       authority c (assoc ask :request-id "raise-3"
                                          :new-max 21)))))
        (is (not (str/includes? (str authority) token)))
        (is (not (str/includes?
                  (str (runs/budget-extensions c rid)) token)))))))

(deftest public-resume-cannot-widen-and-public-extend-uses-trusted-config
  (with-db [c]
    (let [rid (budget-fixture c)
          config {:controller {:budget-token "http-controller"
                               :budget-ceiling 30
                               :budget-principal "http-operator"}
                  :llm {:provider :local}}]
      (let [r (api-control/resume! {:conn c :config config} rid
                                   {:max_turns 6})]
        (is (= 403 (:status r)))
        (is (= 5 (:max_turns (runs/get-run c rid))))
        (is (empty? (runs/budget-extensions c rid))))
      (let [r (api-control/extend!
               {:conn c :config config} rid
               {:request_id "http-raise-1" :max_turns 9
                :reason "operator approved"})]
        (is (nil? (:status r)))
        (is (= "extended" (get-in r [:body :status])))
        (is (= 9 (:max_turns (runs/get-run c rid))))
        (is (= "http-operator"
               (:principal (first (runs/budget-extensions c rid)))))))))

(deftest inference-epoch-is-fixed-before-provider-call-and-linked-to-the-turn
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 9 :beam-width 1})
          branch (state/new-branch {:id "B1" :problem "p"})
          observed (atom nil)
          ctx {:conn c :run-id rid
               :llm-config {:provider :stub :model "fixed-model"}}
          call (with-redefs [infer/complete-fn
                             (fn [_]
                               (fn [_]
                                 (reset! observed (inference/for-run c rid))
                                 {:ok true :response {:content "answer"}}))]
                 (turn/call-model ctx branch 7))
          epoch-id (:inference-epoch-id call)]
      (is (= 1 (count @observed))
          "the epoch exists before the provider seam is invoked")
      (is (= 7 (:turn (first @observed))))
      (is (= "stub" (:provider (first @observed))))
      (is (= "fixed-model" (:model (first @observed))))
      (journal/record-turn! c rid
                            {:branch-id "B1" :turn 7 :tool-name "done"
                             :args {} :result "ok" :category :neutral
                             :inference-epoch-id epoch-id})
      (is (= epoch-id (:inference_epoch_id
                       (first (journal/turns c rid))))))))

(deftest evidence-is-a-durable-read-only-projection
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 3 :beam-width 1})
          context {:context/profile :agent/project-read
                   :context/capabilities [:project/read]
                   :context/timeout-ms 30
                   :context/root "/tmp"
                   :context/coordinate "ctx"}]
      (evaluator-store/register-binding!
       c {:binding-id "bind:evidence" :run-id rid :work-id "work:evidence"
          :instance-id "inst:evidence" :spec-id "spec:evidence"
          :context-spec context :runtime "runtime:evidence"})
      (inference/begin!
       c {:id "epoch:evidence" :run-id rid :branch-id "B1" :turn 1
          :provider :stub :model "m" :binding-id "bind:evidence"
          :spec-id "spec:evidence" :runtime "runtime:evidence"})
      (let [before (db/fetch-one c ["SELECT COUNT(*) AS n FROM evaluator_evals"])
            evidence (evaluator-store/evidence-for-run c rid)
            after (db/fetch-one c ["SELECT COUNT(*) AS n FROM evaluator_evals"])]
        (is (= before after))
        (is (= "bind:evidence" (get-in evidence [:binding :binding-id])))
        (is (= :agent/project-read (get-in evidence [:binding :profile])))
        (is (= 0 (get-in evidence [:evaluations :total])))
        (is (= ["epoch:evidence"]
               (mapv :id (:inference-epochs evidence))))))))
