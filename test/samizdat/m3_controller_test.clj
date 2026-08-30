;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.m3-controller-test
  "Durable M3 authority, provenance, and read-only evidence gates."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.resume :as resume]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.repl :as repl-tools]
            [samizdat.api.control :as api-control]
            [samizdat.api.runs :as api-runs]
            [samizdat.security.controller :as controller]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as evaluator-store]
            [samizdat.store.inference :as inference]
            [samizdat.store.journal :as journal]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as workflow]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest migrations-after-v20-install-the-closed-world-records
  (with-db [c]
    (let [tables (set (db/table-names c))
          run-columns (set (map (comp keyword :name)
                                (db/fetch c ["PRAGMA table_info(runs)"])))
          turn-columns (set (map (comp keyword :name)
                                 (db/fetch c ["PRAGMA table_info(turns)"])))
          binding-columns (set (map (comp keyword :name)
                                    (db/fetch c ["PRAGMA table_info(evaluator_bindings)"])))
          eval-columns (set (map (comp keyword :name)
                                 (db/fetch c ["PRAGMA table_info(evaluator_evals)"])))
          receipt-columns (set (map (comp keyword :name)
                                    (db/fetch c ["PRAGMA table_info(evaluator_receipts)"])))
          epoch-columns (set (map (comp keyword :name)
                                  (db/fetch c ["PRAGMA table_info(inference_epochs)"])))
          invocation-columns (set (map (comp keyword :name)
                                       (db/fetch c
                                         ["PRAGMA table_info(inference_invocations)"])))]
      (is (every? tables ["evaluator_bindings" "inference_epochs"
                          "inference_invocations" "budget_extensions"]))
      (is (contains? run-columns :terminal_reason))
      (is (contains? turn-columns :inference_epoch_id))
      ;; v25 M3 closure records: the durable binding's exact trusted-orientation
      ;; bytes + digest, the eval/receipt epoch linkage, and the epoch's
      ;; realization (adapter/config_digest) + closed_at.
      (is (contains? binding-columns :orientation))
      (is (contains? binding-columns :orientation_digest))
      (is (contains? eval-columns :inference_epoch_id))
      (is (contains? receipt-columns :inference_epoch_id))
      (is (contains? epoch-columns :adapter))
      (is (contains? epoch-columns :config_digest))
      (is (contains? epoch-columns :closed_at))
      ;; v26 final closure: the per-call InferenceInvocation, referenced by
      ;; turns, eval rows and receipts.
      (is (contains? invocation-columns :epoch_id))
      (is (contains? turn-columns :inference_invocation_id))
      (is (contains? eval-columns :inference_invocation_id))
      (is (contains? receipt-columns :inference_invocation_id))
      (is (= 26 (:user_version (db/fetch-one c ["PRAGMA user_version"])))))))

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
                                  (reset! observed [(inference/for-run c rid)
                                                    (inference/invocations-for-run
                                                     c rid)])
                                  {:ok true :response {:content "answer"}}))]
                  (turn/call-model ctx branch 7))
          epoch-id (:inference-epoch-id call)
          invocation-id (:inference-invocation-id call)]
      (is (= 1 (count (first @observed)))
          "the epoch exists before the provider seam is invoked")
      (is (= 7 (:turn (ffirst @observed))))
      (is (= "stub" (:provider (ffirst @observed))))
      (is (= "fixed-model" (:model (ffirst @observed))))
      ;; One PER-CALL invocation exists before the seam fires, under the
      ;; reusable epoch — the call's exact identity, not its realization's.
      (is (= 1 (count (second @observed))))
      (is (= epoch-id (:epoch_id (first (second @observed)))))
      (is (= invocation-id (:id (first (second @observed)))))
      (journal/record-turn! c rid
                            {:branch-id "B1" :turn 7 :tool-name "done"
                             :args {} :result "ok" :category :neutral
                             :inference-epoch-id epoch-id
                             :inference-invocation-id invocation-id})
      (is (= epoch-id (:inference_epoch_id
                        (first (journal/turns c rid)))))
      (is (= invocation-id (:inference_invocation_id
                            (first (journal/turns c rid))))))))

(deftest unchanged-epochs-are-reused-and-a-provider-switch-closes-the-old-one
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 9 :beam-width 1})
          branch (state/new-branch {:id "B1" :problem "p"})
          complete (fn [_] {:ok true :response {:content "answer"}})
          call (fn [config turn]
                 (with-redefs [infer/complete-fn (fn [_] complete)]
                   (turn/call-model {:conn c :run-id rid :llm-config config}
                                    branch turn)))]
      (let [one (call {:provider :stub :model "m"} 1)
            two (call {:provider :stub :model "m"} 2)
            switched (call {:provider :other :model "m"} 3)
            epochs (inference/for-run c rid)
            invocations (inference/invocations-for-run c rid)]
        (is (= (:inference-epoch-id one) (:inference-epoch-id two)))
        (is (not= (:inference-epoch-id two) (:inference-epoch-id switched)))
        (is (= 2 (count epochs)))
        (is (some? (:closed_at (first epochs))))
        (is (nil? (:closed_at (second epochs))))
        ;; The epoch is the REUSABLE realization; the invocation is PER CALL:
        ;; three calls, the unchanged two sharing one epoch, the switched call
        ;; under the successor epoch, and three distinct invocations.
        (is (= 3 (count invocations)))
        (is (= [(:inference-epoch-id one)
                (:inference-epoch-id one)
                (:inference-epoch-id switched)]
               (mapv :epoch_id invocations))
            "unchanged calls share one epoch; the switch names its successor")
        (is (= 3 (count (distinct (map :id invocations))))
            "no two calls share an invocation")
        (is (= [(:inference-invocation-id one)
                (:inference-invocation-id two)
                (:inference-invocation-id switched)]
               (mapv :id invocations)))))))

(deftest epoch-flows-through-tool-dispatch-evaluation-and-receipts
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 3 :beam-width 1})
          branch (state/new-branch {:id "B1" :problem "p"})
          epoch (inference/begin! c {:id "epoch:dispatch" :run-id rid
                                     :branch-id "B1" :turn 1 :provider :stub
                                     :model "m" :binding-id "bind:dispatch"
                                     :spec-id "spec:dispatch" :runtime "runtime"})
          invocation (inference/invoke! c {:id "invocation:dispatch"
                                           :epoch-id (:id epoch)
                                           :run-id rid :branch-id "B1"
                                           :turn 1})
          binding {:binding/id "bind:dispatch"}
          evaluate! (fn [conn binding source opts]
                      (let [eval-id (evaluator-store/begin!
                                      conn {:spec-id "spec:dispatch" :instance-id "inst:dispatch"
                                            :binding-id (:binding/id binding) :context-spec "ctx"
                                            :runtime "runtime" :source source
                                            :inference-epoch-id (:inference-epoch-id opts)
                                            :inference-invocation-id
                                            (:inference-invocation-id opts)})
                            seqn ((:effect-permit! opts)
                                  #(evaluator-store/intent! conn eval-id :project/read
                                                            ["x"] (:inference-epoch-id opts)
                                                            (:inference-invocation-id opts)))]
                        (evaluator-store/outcome! conn eval-id seqn {:result "x"}
                                                  (:inference-epoch-id opts)
                                                  (:inference-invocation-id opts))
                        (evaluator-store/complete! conn eval-id :completed {:value "x"})
                        {:value "x"}))]
      (with-redefs-fn {#'repl-tools/evaluator-var
                       (fn [name] (when (= name "evaluate-recorded!") evaluate!))}
        (fn []
          (let [r (tools/run-tool {:conn c :run-id rid :turn 1 :branch branch
                                   :turn-lease (base/mint-turn-lease rid "B1" 1)
                                   :evaluator/binding binding :tool-name "eval"
                                   :inference-epoch-id (:id epoch)
                                   :inference-invocation-id (:id invocation)
                                   :args {:code "(project/read \"x\")"}})
                eval-row (first (evaluator-store/history c "bind:dispatch"))]
            (is (= :neutral (:category r)))
            (is (= (:id epoch) (:inference_epoch_id eval-row)))
            (is (= (:id invocation) (:inference_invocation_id eval-row)))
            (is (= (:id epoch) (get-in eval-row [:receipts 0 :inference-epoch-id])))
            (is (= (:id invocation)
                   (get-in eval-row [:receipts 0 :inference-invocation-id])))
            (is (= :project/read (get-in eval-row [:receipts 0 :op])))))))))

(deftest a-human-extend-directive-uses-the-controller-transaction
  (with-db [c]
    (let [rid (budget-fixture c)
          _ (interventions/submit! c rid {:kind "extend" :payload {:max_turns 12}})
          r (#'beam/drain-directives! {:conn c :run-id rid
                                       :config {:controller {:budget-token "secret"
                                                             :budget-ceiling 20}}}
                                      [(state/new-branch {:id "B1" :problem "p"})]
                                      (interventions/pending c rid) 1)]
      (is (= 12 (:max-turns r)))
      (is (= "applied" (:status (first (interventions/history c rid)))))
      (is (= 12 (:max_turns (runs/get-run c rid))))
      (is (= 1 (count (runs/budget-extensions c rid)))))))

(deftest bounded-noniterating-workflows-are-refused-before-start-or-resume
  (with-db [c]
    (let [compile (fn [& _] {:compiled :ignored :iterating? false})]
      (with-redefs [workflow/compile-turn-loop compile]
        (let [e (try (beam/run! {:conn c :config {:run {:bounded {:profile :agent/project-read}}}
                                 :llm-config {:provider :stub} :problem "p"})
                     nil (catch Throwable x x))]
          (is (= :bounded-noniterating-workflow
                 (:samizdat.evaluator/error (ex-data e))))
          (is (empty? (runs/list-runs c 10))
               "refusal happens before creating a resumable run"))
        (let [rid (runs/start-run! c {:problem "p" :max-turns 3 :beam-width 1})]
          (evaluator-store/register-binding!
           c {:binding-id "bind:resume-refusal" :run-id rid :work-id "work"
              :instance-id "inst" :spec-id "spec" :context-spec {:context/coordinate "ctx"}
              :runtime "runtime" :orientation "trusted" :orientation-digest "digest"})
          (runs/finish-run! c rid :failed "interrupted")
          (let [e (try (resume/resume! {:conn c :config {} :run-id rid})
                       nil (catch Throwable x x))]
            (is (= :bounded-noniterating-workflow
                   (:samizdat.evaluator/error (ex-data e))))
            (is (= "failed" (:status (runs/get-run c rid)))
                "refusal does not revive the interrupted run")))))))

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
          :context-spec context :runtime "runtime:evidence"
          :orientation "SYSTEM / TRUSTED SURFACE\n- eval\n"
          :orientation-digest "sha256:evidence"})
      (inference/begin!
       c {:id "epoch:evidence" :run-id rid :branch-id "B1" :turn 1
          :provider :stub :model "m" :binding-id "bind:evidence"
          :spec-id "spec:evidence" :runtime "runtime:evidence"})
      (inference/invoke!
       c {:id "invocation:evidence" :epoch-id "epoch:evidence"
          :run-id rid :branch-id "B1" :turn 1})
      (let [before (db/fetch-one c ["SELECT COUNT(*) AS n FROM evaluator_evals"])
            evidence (evaluator-store/evidence-for-run c rid)
            after (db/fetch-one c ["SELECT COUNT(*) AS n FROM evaluator_evals"])]
        (is (= before after))
        (is (= "bind:evidence" (get-in evidence [:binding :binding-id])))
        (is (= :agent/project-read (get-in evidence [:binding :profile])))
        (is (= "sha256:evidence" (get-in evidence [:binding :orientation-digest]))
            "evidence names the digest of the persisted orientation, not the bytes")
        (is (= 0 (get-in evidence [:evaluations :total])))
        (is (= ["epoch:evidence"]
               (mapv :id (:inference-epochs evidence))))
        (is (= ["invocation:evidence"]
               (mapv :id (:inference-invocations evidence))))
        (is (= evidence (:evaluator (api-runs/get-run c rid)))
            "the run API exposes the same read-only durable projection")))))

(deftest telemetry-derives-exact-operations-per-invocation-and-model-turn
  (with-db [c]
    ;; Two model calls (two invocations) share one epoch; each dispatches one
    ;; evaluation.  The projection must attribute each committed operation to
    ;; its exact invocation and that invocation's model turn — derived from
    ;; durable rows, read-only.
    (let [rid (runs/start-run! c {:problem "p" :max-turns 3 :beam-width 1})
          context {:context/profile :agent/project-read
                   :context/capabilities [:project/read]
                   :context/timeout-ms 30
                   :context/root "/tmp"
                   :context/coordinate "ctx"}]
      (evaluator-store/register-binding!
       c {:binding-id "bind:telemetry" :run-id rid :work-id "work:telemetry"
          :instance-id "inst:telemetry" :spec-id "spec:telemetry"
          :context-spec context :runtime "runtime:telemetry"
          :orientation "SYSTEM / TRUSTED SURFACE\n- eval\n"
          :orientation-digest "sha256:telemetry"})
      (let [epoch (inference/begin!
                   c {:id "epoch:telemetry" :run-id rid :branch-id "B1"
                      :turn 1 :provider :stub :model "m"
                      :binding-id "bind:telemetry" :spec-id "spec:telemetry"
                      :runtime "runtime:telemetry"})
            invocations [(inference/invoke!
                          c {:id "invocation:t1" :epoch-id (:id epoch)
                             :run-id rid :branch-id "B1" :turn 1})
                         (inference/invoke!
                          c {:id "invocation:t2" :epoch-id (:id epoch)
                             :run-id rid :branch-id "B1" :turn 2})]
            commit (fn [inv op path]
                     (let [eval-id (evaluator-store/begin!
                                    c {:spec-id "spec:telemetry"
                                       :instance-id "inst:telemetry"
                                       :binding-id "bind:telemetry"
                                       :context-spec "ctx"
                                       :runtime "runtime:telemetry"
                                       :source "(read)"
                                       :inference-epoch-id (:id epoch)
                                       :inference-invocation-id (:id inv)})
                           seqn (evaluator-store/intent!
                                 c eval-id op [path] (:id epoch) (:id inv))]
                       (evaluator-store/outcome! c eval-id seqn
                                                 {:result path} (:id epoch)
                                                 (:id inv))
                       (evaluator-store/complete! c eval-id :completed
                                                  {:value path})))]
        (commit (first invocations) :project/read "a")
        (commit (second invocations) :project/read "b")
        (let [evidence (evaluator-store/evidence-for-run c rid)
              per-invocation (get-in evidence [:operations :per-invocation])
              per-model-turn (get-in evidence [:operations :per-model-turn])
              receipts (mapcat identity
                               (get-in evidence [:operations :receipts]))]
          ;; One reusable epoch, two exact per-call invocations.
          (is (= 1 (count (:inference-epochs evidence))))
          (is (= 2 (count (:inference-invocations evidence))))
          (is (every? #(= (:id epoch) (:epoch_id %))
                      (:inference-invocations evidence)))
          ;; Every receipt names its exact invocation.
          (is (= ["invocation:t1" "invocation:t2"]
                 (sort (map :inference-invocation-id receipts))))
          ;; Exact operations per invocation, and through the invocation to
          ;; its model turn.
          (is (= [{:invocation-id "invocation:t1" :epoch-id (:id epoch)
                   :branch-id "B1" :turn 1 :operations [:project/read]}
                  {:invocation-id "invocation:t2" :epoch-id (:id epoch)
                   :branch-id "B1" :turn 2 :operations [:project/read]}]
                 per-invocation))
          (is (= [{:branch-id "B1" :turn 1 :operations [:project/read]}
                  {:branch-id "B1" :turn 2 :operations [:project/read]}]
                 per-model-turn)))))))

(deftest a-zero-branch-exposed-run-resumes-through-the-production-path
  ;; The exposed-run/no-branch crash window: `run!` creates the run row (and
  ;; for a bounded run persists the binding) BEFORE any branch exists, so a
  ;; process death in that window leaves a resumable run with zero branches —
  ;; and a resume of it reached the scheduler with an empty beam, which
  ;; exhausted at turn 1 and failed the run without ever taking a turn.
  ;; Closing the window: the resume path opens the run's initial branch set
  ;; exactly as the start path would have, and hands the scheduler somebody to
  ;; advance.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "exposed zero-turn run"
                                  :max-turns 3 :beam-width 2})]
      (is (resume/resumable? c rid))
      (is (empty? (runs/branches c rid)) "the crash window is what it claims")
      (with-redefs [beam/run-rounds (fn [ctx branches start-turn]
                                      {:status :continued :run-id (:run-id ctx)
                                       :branches branches :start-turn start-turn})]
        (let [r (resume/resume! {:conn c :config {} :llm-adapter :a
                                 :llm-config {} :run-id rid})]
          (is (= rid (:run-id r)))
          (is (= 1 (:start-turn r)) "a zero-turn run resumes at turn 1")
          (is (= ["B1" "B2"] (mapv :id (:branches r)))
              "the initial width-many branch set was opened by the resume")
          (is (= ["B1" "B2"] (mapv :id (runs/branches c rid)))
              "and it is durable: the branch rows exist now")
          (is (some #(str/includes? (str (:content %))
                                    "exposed zero-turn run")
                    (:messages (first (:branches r))))
              "the reopened branch opens on the run's problem"))
        (testing "re-resuming a still-zero-turn run is idempotent at the row level"
          (let [r2 (resume/resume! {:conn c :config {} :llm-adapter :a
                                    :llm-config {} :run-id rid})]
            (is (= ["B1" "B2"] (mapv :id (runs/branches c rid))))))))))
