(ns samizdat.aspect-manifest-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [maestro.core :as fsm]
            [mycelium.cell :as cell]
            [mycelium.compose :as compose]
            [mycelium.core :as myc]
            [mycelium.execution :as execution]
            [mycelium.middleware :as middleware]
            [mycelium.workflow :as wf]
            [samizdat.instrumentation :as instrumentation]))

(def ^:private manifest-resources
  ["META-INF/jolt/aspects/samizdat-m2-core.edn"
   "META-INF/jolt/aspects/samizdat-m2-embed.edn"
   "META-INF/jolt/aspects/samizdat-m2-http.edn"
   "META-INF/jolt/aspects/samizdat-m2-mycelium.edn"
   "META-INF/jolt/aspects/samizdat-m2-experience.edn"])

(defn- qualified-symbol? [value]
  (and (symbol? value) (some? (namespace value))))

(defn- valid-selector? [match]
  (and (pos-int? (:arity match))
       (or (and (= #{:entry :arity} (set (keys match)))
                (qualified-symbol? (:entry match)))
           (and (= #{:ns :call :arity} (set (keys match)))
                (symbol? (:ns match))
                (qualified-symbol? (:call match))))))

(deftest library-supplies-inert-instrumentation-contracts
  (doseq [resource-name manifest-resources]
    (testing resource-name
      (let [resource (io/resource resource-name)
            manifest (some-> resource slurp edn/read-string)
            compatibility-id
            (if (= resource-name
                   "META-INF/jolt/aspects/samizdat-m2-mycelium.edn")
              instrumentation/mycelium-compatibility-id
              instrumentation/compatibility-id)]
        (is (some? resource))
        (is (= 1 (:schema manifest)))
        (is (= 'yogthos/samizdat (get-in manifest [:library :id])))
        (is (= compatibility-id (get-in manifest [:library :version])))
        (is (seq (:aspects manifest)))
        (doseq [aspect (:aspects manifest)]
          (is (keyword? (:id aspect)))
          (is (valid-selector? (:match aspect)))
          (is (keyword? (:advice-role aspect)))
          (is (= 1 (get-in aspect [:expect :matches]))))))))

(deftest semantic-join-points-match-exact-source-boundaries
  (let [read-manifest #(some-> % io/resource slurp edn/read-string)
        core (read-manifest "META-INF/jolt/aspects/samizdat-m2-core.edn")
        embed (read-manifest "META-INF/jolt/aspects/samizdat-m2-embed.edn")
        mycelium (read-manifest "META-INF/jolt/aspects/samizdat-m2-mycelium.edn")
        aspects (concat (:aspects core) (:aspects embed) (:aspects mycelium))
        by-id (into {} (map (juxt :id identity))
                    aspects)
        match #(get-in by-id [% :match])
        role #(get-in by-id [% :advice-role])]
    (is (= 9 (count (:aspects core))))
    (is (= 12 (count aspects)))
    (is (= (count aspects) (count by-id)) "aspect ids are unique")
    (is (= #{:samizdat.agent.beam/control-loop
             :samizdat.store.runs/branch-open
             :samizdat.store.runs/branch-close
             :samizdat.agent.beam/turn
             :samizdat.agent.infer/model
             :samizdat.agent.infer/tool-selection
             :samizdat.agent.loop/tool
             :samizdat.agent.arbiter/steer
             :samizdat.llm.client/http-post
             :samizdat.embed/beam-run
             :mycelium.workflow/lifecycle
             :mycelium.workflow/edge-decision}
           (set (keys by-id))))
    (is (= {:entry 'samizdat.agent.beam/run! :arity 1}
           (match :samizdat.embed/beam-run)))
    (is (= :samizdat/run (role :samizdat.embed/beam-run)))
    (is (= {:entry 'samizdat.agent.beam/run-rounds :arity 3}
           (match :samizdat.agent.beam/control-loop)))
    (is (= :samizdat/control-loop
           (role :samizdat.agent.beam/control-loop)))
    (is (= {:entry 'samizdat.store.runs/open-branch! :arity 3}
           (match :samizdat.store.runs/branch-open)))
    (is (= :samizdat/branch-open
           (role :samizdat.store.runs/branch-open)))
    (is (= {:entry 'samizdat.store.runs/close-branch! :arity 5}
           (match :samizdat.store.runs/branch-close)))
    (is (= :samizdat/branch-close
           (role :samizdat.store.runs/branch-close)))
    (is (= {:entry 'samizdat.agent.beam/advance-branch :arity 3}
           (match :samizdat.agent.beam/turn)))
    (is (= :samizdat/turn (role :samizdat.agent.beam/turn)))
    (is (= {:entry 'samizdat.llm.client/chat :arity 4}
           (match :samizdat.agent.infer/model)))
    (is (= :samizdat/model (role :samizdat.agent.infer/model)))
    (is (= {:entry 'samizdat.agent.infer/absorb :arity 3}
           (match :samizdat.agent.infer/tool-selection)))
    (is (= :samizdat/tool-selection
           (role :samizdat.agent.infer/tool-selection)))
    (is (= {:entry 'samizdat.agent.tools/run-tool :arity 1}
           (match :samizdat.agent.loop/tool)))
    (is (= :samizdat/tool (role :samizdat.agent.loop/tool)))
    (is (= {:entry 'samizdat.agent.arbiter/decide :arity 1}
           (match :samizdat.agent.arbiter/steer)))
    (is (= :samizdat/steer (role :samizdat.agent.arbiter/steer)))
    (is (= {:ns 'samizdat.llm.client
            :call 'jolt.http-client/post
            :arity 2}
           (match :samizdat.llm.client/http-post)))
    (is (= :http/client (role :samizdat.llm.client/http-post)))
    (is (= {:entry 'mycelium.execution/workflow-event! :arity 1}
           (match :mycelium.workflow/lifecycle)))
    (is (= :mycelium/workflow
           (role :mycelium.workflow/lifecycle)))
    (is (= {:entry 'mycelium.execution/edge-event! :arity 1}
           (match :mycelium.workflow/edge-decision)))
    (is (= :mycelium/edge-decision
           (role :mycelium.workflow/edge-decision)))))

(def ^:private serial-worker
  {:cells (array-map :finish :worker/finish
                     :start :worker/start
                     :choose :worker/choose)
   :edges (array-map :choose (array-map :retry :start :done :finish)
                     :finish :end
                     :start :choose)
   :dispatches {:choose [[:done '(fn [data] (:done data))]
                         [:retry '(fn [_data] true)]]}})

(deftest mycelium-graph-artifact-is-deterministic-and-provider-neutral
  (let [same-graph (-> serial-worker
                       (assoc :cells (into (sorted-map) (:cells serial-worker)))
                       (assoc :edges (into (sorted-map) (:edges serial-worker))))
        graph (myc/graph-artifact serial-worker)
        same-graph-artifact (myc/graph-artifact same-graph)]
    (is (= graph same-graph-artifact))
    (is (= (:graph-id graph) (:graph-id same-graph-artifact)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:graph-id graph)))
    (is (= 1 (:schema graph)))
    (is (= {:node :start :cell :worker/start} (:entry graph)))
    (is (= [{:node :finish :cell :worker/finish}] (:terminals graph)))
    (is (= [{:node :choose :cell :worker/choose}
            {:node :finish :cell :worker/finish}
            {:node :start :cell :worker/start}]
           (:nodes graph)))
    (is (= #{[:start :always :choose]
             [:choose :done :finish]
             [:choose :retry :start]
             [:finish :always :end]}
           (set (map :edge-key (:edges graph)))))
    (is (not (re-find #"provider|predicate|function"
                      (pr-str graph)))))
  (testing "pre-compilation publishes the same graph beside runtime state"
    (with-redefs [myc/compile-workflow (fn [_ _] {:fsm {}})]
      (is (= (myc/graph-artifact serial-worker)
             (:graph (myc/pre-compile serial-worker)))))))

(deftest mycelium-graph-artifact-bounds-degenerate-topologies
  (testing "an empty or start-less definition invents no entry or terminal"
    (is (= {:schema 1 :entry nil :terminals [] :nodes [] :edges []}
           (dissoc (myc/graph-artifact {}) :graph-id)))
    (is (nil? (:entry (myc/graph-artifact
                       {:cells {:only :worker/only}
                        :edges {:only :only}}))))
    (is (empty? (:terminals (myc/graph-artifact
                             {:cells {:only :worker/only}
                              :edges {:only :only}})))))
  (testing "a terminal-less cycle remains a cycle"
    (let [graph (myc/graph-artifact
                 {:cells {:start :worker/a :again :worker/b}
                  :edges {:start :again :again :start}})]
      (is (= [] (:terminals graph)))
      (is (= #{[:start :always :again] [:again :always :start]}
             (set (map :edge-key (:edges graph)))))))
  (testing "synthetic joins have a stable provider-neutral cell identity"
    (let [graph (myc/graph-artifact
                 {:cells {:start :worker/start}
                  :joins {:fan-in {:cells [:left :right]}}
                  :edges {:start :fan-in :fan-in :end}})]
      (is (= {:node :start :cell :worker/start} (:entry graph)))
      (is (= [{:node :fan-in :cell :mycelium/join}]
             (:terminals graph)))
      (is (= {:node :fan-in :cell :mycelium/join}
             (first (:nodes graph)))))))

(deftest normalization-is-shared-by-compile-and-graph-projection
  (let [raw {:cells {:start :worker/start :finish :worker/finish}
             :pipeline [:start :finish]}
        compile-arg (atom nil)
        graph-arg (atom nil)]
    (with-redefs [myc/compile-workflow
                  (fn [definition _opts]
                    (reset! compile-arg definition)
                    {:fsm {}})
                  myc/graph-artifact
                  (fn [definition]
                    (reset! graph-arg definition)
                    {:schema 1 :graph-id "sha256:test"})]
      (myc/pre-compile raw))
    (is (identical? @compile-arg @graph-arg)
        "pre-compile passes one normalized value to compiler and projector")
    (is (identical? @compile-arg (wf/normalize-workflow @compile-arg))
        "normalization is idempotent by identity")
    (is (nil? (:pipeline @compile-arg)))
    (is (= {:start :finish :finish :end} (:edges @compile-arg)))))

(deftest graph-artifact-matches-pipeline-and-error-group-compilation
  (defmethod cell/cell-spec :aspect/parity-cell [_]
    {:id :aspect/parity-cell
     :handler (fn [_ data] data)
     :schema {:input :map :output :map}})
  (testing "pipeline expansion is the graph the compiler receives"
    (let [definition {:cells {:start :aspect/parity-cell
                              :finish :aspect/parity-cell}
                      :pipeline [:start :finish]}
          normalized (wf/normalize-workflow definition)
          compiled (myc/pre-compile definition)
          graph (:graph compiled)]
      (is (= #{[:start :always :finish] [:finish :always :end]}
             (set (map :edge-key (:edges graph)))))
      (is (= [[:mycelium.workflow/finish] [::fsm/end]]
             (mapv #(mapv first %)
                   [(get-in compiled [:compiled-fsm :fsm ::fsm/start :dispatches])
                    (get-in compiled [:compiled-fsm :fsm
                                      :mycelium.workflow/finish :dispatches])])))
      (is (= graph (wf/graph-artifact normalized)))))
  (testing "injected error routing appears in both normalized edges and graph"
    (let [definition
          {:cells {:start :aspect/parity-cell :recover :aspect/parity-cell}
           :edges {:start :end :recover :end}
           :error-groups {:main {:cells [:start] :on-error :recover}}}
          normalized
          (wf/normalize-workflow
           definition)
          compiled (myc/pre-compile definition)
          graph (:graph compiled)]
      (is (= {:done :end :on-error :recover}
             (get-in normalized [:edges :start])))
      (is (= #{[:start :done :end]
               [:start :on-error :recover]
               [:recover :always :end]}
             (set (map :edge-key (:edges graph)))))
      (is (= #{::fsm/end :mycelium.workflow/recover}
             (set (map first (get-in compiled
                                     [:compiled-fsm :fsm ::fsm/start
                                      :dispatches])))))
      (is (= graph (wf/graph-artifact normalized))))))

(deftest legacy-dispatch-pairs-are-preserved-and-mycelium-reports-only-selection
  (let [done? (fn [data] (if (:done data) :truth-token false))
        retry? (constantly true)
        legacy (wf/compile-edges
                {:done :finish :retry :start}
                [[:done done?] [:retry retry?]])]
    (is (= 2 (count (first legacy))))
    (is (identical? done? (second (first legacy))))
    (is (= :mycelium.workflow/finish (ffirst legacy)))
    (testing "Maestro compilation retains its public pair shape"
      (let [compiled (fsm/compile
                      {:fsm {::fsm/start
                             {:handler (fn [_ data] data)
                              :dispatches [[::fsm/end done?]]}}})]
        (is (= 2 (count (first (get-in compiled [:fsm ::fsm/start :dispatches])))))))
    (testing "the private Mycelium arity emits only the chosen edge reference"
      (let [selected (atom [])
            dispatches (#'wf/compile-instrumented-edges
                        :choose
                        {:done :finish :retry :start}
                        [[:done done?] [:retry retry?]])]
        (with-redefs [execution/edge-event!
                      (fn [event]
                        (swap! selected conj event)
                        event)]
          (binding [execution/*execution-id* "execution-test"]
            (is (= :truth-token
                   ((second (first dispatches)) {:done true})))
            (is (= [{:schema 1 :execution-id "execution-test"
                     :edge-key [:choose :done :finish]}]
                   @selected))
            (reset! selected [])
            (is (false? ((second (first dispatches)) {:done false})))
            (is (true? ((second (second dispatches)) {:done false})))
            (is (= [{:schema 1 :execution-id "execution-test"
                     :edge-key [:choose :retry :start]}]
                   @selected))))))))

(defn- register-event-cell! []
  (defmethod cell/cell-spec :aspect/event-cell [_]
    {:id :aspect/event-cell
     :handler (fn [_ data] (assoc data :html "ok"))
     :schema {:input :map :output :map}}))

(defn- register-async-event-cell! []
  (defmethod cell/cell-spec :aspect/async-event-cell [_]
    {:id :aspect/async-event-cell
     :async? true
     :handler (fn [_ data callback _error]
                (future (callback (assoc data :html "ok"))))
     :schema {:input :map :output :map}}))

(defn- lifecycle-pairs [events]
  (->> events
       (group-by :execution-id)
       vals
       (map #(sort-by (fn [event] (if (= :invoke (:phase event)) 0 1)) %))))

(deftest all-execution-paths-cross-the-bounded-lifecycle-event-seam
  (register-event-cell!)
  (let [definition {:cells {:start :aspect/event-cell}
                    :edges {:start :end}}
        compiled (myc/pre-compile definition)
        events (atom [])
        edge-events (atom [])]
    (with-redefs [execution/workflow-event!
                  (fn [event]
                    (swap! events conj event)
                    event)
                  execution/edge-event!
                  (fn [event]
                    (swap! edge-events conj event)
                    event)]
      (is (= "ok" (:html (myc/run-compiled compiled {} {}))))
      (is (= "ok" (:html @(myc/run-compiled-async compiled {} {}))))
      (is (= "ok" (:html (myc/resume-compiled
                           compiled {} {:mycelium/resume ::fsm/start}))))
      (is (= "ok" (:html
                    ((middleware/workflow-handler
                      compiled {:resources {} :output-fn identity}) {}))))
      (let [child (compose/workflow->cell
                   :aspect/child definition {:input :map :output :map})]
        (is (= "ok" (:html ((:handler child) {} {}))))))
    (is (= #{:sync :async :resume :ring :composed}
           (set (map :kind @events))))
    (is (= 10 (count @events)))
    (is (= 5 (count (set (map :execution-id @events)))))
    (doseq [pair (lifecycle-pairs @events)]
      (is (= [:invoke :return] (mapv :phase pair)))
      (is (= 1 (count (set (map :graph-id pair)))))
      (is (= #{:schema :graph-id :execution-id :kind :phase :graph}
             (set (keys (first pair)))))
      (is (= #{:schema :graph-id :execution-id :kind :phase}
             (set (keys (second pair))))))
    (is (every? #(= (:graph-id compiled) (:graph-id %)) @events))
    (is (= (set (map :execution-id @events))
           (set (map :execution-id @edge-events))))
    (is (= 5 (count @edge-events)))
    (is (every? #(= #{:schema :execution-id :edge-key} (set (keys %)))
                @edge-events))))

(deftest concurrent-executions-keep-edge-events-correlated
  (register-async-event-cell!)
  (let [compiled (myc/pre-compile
                  {:cells {:start :aspect/async-event-cell}
                   :edges {:start :end}})
        lifecycle (atom [])
        edges (atom [])]
    (with-redefs [execution/workflow-event!
                  (fn [event] (swap! lifecycle conj event) event)
                  execution/edge-event!
                  (fn [event] (swap! edges conj event) event)]
      (let [a (myc/run-compiled-async compiled {} {})
            b (myc/run-compiled-async compiled {} {})]
        @a @b))
    (let [ids (set (map :execution-id @lifecycle))]
      (is (= 2 (count ids)))
      (is (= ids (set (map :execution-id @edges))))
      (is (= [1 1] (sort (map count (vals (group-by :execution-id @edges)))))
          "each selected edge remains attached to exactly one execution"))))

(deftest cancelling-async-execution-terminates-its-semantic-stream
  (let [entered (promise)
        release (promise)
        completed (atom 0)]
    (defmethod cell/cell-spec :aspect/cancellable-cell [_]
      {:id :aspect/cancellable-cell
       :async? true
       :handler (fn [_ data callback _error]
                  (future
                    (deliver entered true)
                    @release
                    (swap! completed inc)
                    (callback (assoc data :completed true))))
       :schema {:input :map :output :map}})
    (let [compiled (myc/pre-compile
                    {:cells {:start :aspect/cancellable-cell}
                     :edges {:start :end}})
          lifecycle (atom [])
          edges (atom [])]
      (with-redefs [execution/workflow-event!
                    (fn [event] (swap! lifecycle conj event) event)
                    execution/edge-event!
                    (fn [event] (swap! edges conj event) event)]
        (let [execution (myc/run-compiled-async compiled {} {})]
          @entered
          (is (true? (future-cancel execution)))
          (deliver release true)
          (Thread/sleep 20)))
      (is (= 1 @completed)
          "existing async handlers retain Maestro's cancellation behavior")
      (is (= [:invoke :cancel] (mapv :phase @lifecycle)))
      (is (empty? @edges)
          "a cancelled execution cannot report later semantic progress"))))

(deftest cancellation-state-wins-when-running-code-ignores-interruption
  (let [entered (promise)
        release? (atom false)]
    (defmethod cell/cell-spec :aspect/noninterruptible-cell [_]
      {:id :aspect/noninterruptible-cell
       :handler (fn [_ data]
                  (deliver entered true)
                  (while (not @release?)
                    (Thread/yield))
                  (assoc data :completed true))
       :schema {:input :map :output :map}})
    (let [compiled (myc/pre-compile
                    {:cells {:start :aspect/noninterruptible-cell}
                     :edges {:start :end}})
          lifecycle (atom [])
          edges (atom [])]
      (with-redefs [execution/workflow-event!
                    (fn [event] (swap! lifecycle conj event) event)
                    execution/edge-event!
                    (fn [event] (swap! edges conj event) event)]
        (let [execution (myc/run-compiled-async compiled {} {})]
          @entered
          (is (true? (future-cancel execution)))
          (reset! release? true)
          (Thread/sleep 20)))
      (is (= [:invoke :cancel] (mapv :phase @lifecycle)))
      (is (empty? @edges)
          "cancelled task state suppresses progress even without interruption"))))

(deftest pre-start-cancellation-cannot-publish-an-open-lifecycle
  (register-event-cell!)
  (let [compiled (myc/pre-compile
                  {:cells {:start :aspect/event-cell}
                   :edges {:start :end}})
        lifecycle (atom [])
        cancellations (atom 0)]
    (with-redefs [execution/workflow-event!
                  (fn [event] (swap! lifecycle conj event) event)]
      (dotimes [_ 100]
        (when (future-cancel (myc/run-compiled-async compiled {} {}))
          (swap! cancellations inc)))
      (Thread/sleep 20))
    (is (pos? @cancellations)
        "the probe exercised cancellation before at least one task settled")
    (let [by-execution (vals (group-by :execution-id @lifecycle))]
      (is (every? #(= 2 (count %)) by-execution)
          "every published invoke has exactly one terminal")
      (is (every? #(= :invoke (:phase (first %))) by-execution))
      (is (every? #{:return :cancel}
                  (map #(-> % second :phase) by-execution))))))

(deftest schema-rejected-attempts-do-not-start-an-execution-lifecycle
  (register-event-cell!)
  (let [compiled (myc/pre-compile
                  {:input-schema [:map [:required :string]]
                   :cells {:start :aspect/event-cell}
                   :edges {:start :end}})
        events (atom [])]
    (with-redefs [execution/workflow-event!
                  (fn [event] (swap! events conj event) event)]
      (is (:mycelium/input-error (myc/run-compiled compiled {} {})))
      (is (:mycelium/input-error @(myc/run-compiled-async compiled {} {}))))
    (is (empty? @events)
        "the lifecycle seam describes accepted executions, not validation attempts")))

(deftest thrown-executions-emit-a-private-terminal-fixture
  (defmethod cell/cell-spec :aspect/throwing-cell [_]
    {:id :aspect/throwing-cell
     :handler (fn [_ _] (throw (ex-info "private failure" {:secret "hidden"})))
     :schema {:input :map :output :map}})
  (let [compiled (myc/pre-compile
                  {:cells {:start :aspect/throwing-cell}
                   :edges {:start :end}})
        events (atom [])]
    (with-redefs [execution/workflow-event!
                  (fn [event] (swap! events conj event) event)]
      (is (thrown? Throwable (myc/run-compiled compiled {} {}))))
    (is (= [:invoke :throw] (mapv :phase @events)))
    (is (= #{:schema :graph-id :execution-id :kind :phase}
           (set (keys (second @events)))))
    (is (not (re-find #"private failure|hidden" (pr-str (second @events)))))))

(deftest experience-join-points-match-exact-source-boundaries
  ;; The closed-domain decision surface (ADR-002 section 5): every aspect
  ;; names a var that exists at that arity, the roles are the five the
  ;; contract publishes, and the ids do not collide with the other manifests.
  (let [read-manifest #(some-> % io/resource slurp edn/read-string)
        experience (read-manifest "META-INF/jolt/aspects/samizdat-m2-experience.edn")
        others (mapcat (comp :aspects read-manifest)
                       ["META-INF/jolt/aspects/samizdat-m2-core.edn"
                        "META-INF/jolt/aspects/samizdat-m2-embed.edn"
                        "META-INF/jolt/aspects/samizdat-m2-http.edn"
                        "META-INF/jolt/aspects/samizdat-m2-mycelium.edn"])
        by-id (into {} (map (juxt :id identity)) (:aspects experience))
        match #(get-in by-id [% :match])
        role #(get-in by-id [% :advice-role])]
    (is (= 5 (count (:aspects experience))))
    (is (not-any? (set (keys by-id)) (map :id others))
        "experience aspect ids are distinct from every other manifest's")
    (is (= #{:samizdat/decision-domain :samizdat/candidate-score :samizdat/transition
             :samizdat/verification :samizdat/artifact}
           (set (map :advice-role (:aspects experience)))))
    (is (= {:entry 'samizdat.decide/authorize :arity 2}
           (match :samizdat.decide/decision-domain)))
    (is (= :samizdat/decision-domain (role :samizdat.decide/decision-domain)))
    (is (= {:entry 'samizdat.decide/decide :arity 1}
           (match :samizdat.decide/candidate-score)))
    (is (= {:entry 'samizdat.decide/revalidate :arity 3}
           (match :samizdat.decide/transition)))
    (is (= {:entry 'samizdat.store.journal/settle-gate! :arity 4}
           (match :samizdat.store.journal/verification)))
    (is (= {:entry 'samizdat.store.journal/record-artifact! :arity 3}
           (match :samizdat.store.journal/artifact)))
    (testing "every entry resolves to a var whose arglists include the declared arity"
      (doseq [{:keys [match]} (:aspects experience)]
        (let [v (do (require (symbol (namespace (:entry match))))
                    (resolve (:entry match)))
              arities (set (map count (:arglists (meta v))))]
          (is (some? v) (str (:entry match) " resolves"))
          (is (contains? arities (:arity match))
              (str (:entry match) " has arity " (:arity match))))))))
