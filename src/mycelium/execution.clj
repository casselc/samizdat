(ns mycelium.execution
  "Provider-neutral semantic execution events around Maestro.

   Events contain only static graph metadata and portable execution identity;
   workflow resources and data never cross the instrumentation seam."
  (:require [maestro.core :as fsm]))

(def ^:dynamic *execution-id*
  "Execution identity bound only while Maestro evaluates a Mycelium workflow."
  nil)

(def ^:dynamic *execution-active?*
  "Shared cancellation guard for callbacks spawned by an async execution."
  nil)

(def ^:dynamic *execution-cancelled?*
  "Cancellation query for the currently executing Maestro task."
  (constantly false))

(defn workflow-event!
  "Stable no-op lifecycle join point for an inert compiler aspect.

   `event` is a bounded map containing :graph-id, :execution-id, :kind and
   :phase. Invoke events additionally carry the deterministic static :graph
   artifact. Terminal events (`:return`, `:throw`, or `:cancel`) never carry
   workflow data, resources, or errors."
  [event]
  event)

(defn edge-event!
  "Stable no-op edge join point. The event contains only execution identity
   and the selected portable edge key; predicate input and workflow data never
   cross this boundary."
  [event]
  event)

(defn- event-base [compiled-workflow kind]
  {:schema 1
   :graph-id (:graph-id compiled-workflow)
   :execution-id (str (random-uuid))
   :kind kind})

(defn- emit! [base phase]
  (workflow-event! (assoc base :phase phase)))

(defn- await [result]
  (if (future? result) @result result))

(defn run-sync
  "Runs one compiled workflow and emits exactly one invoke and one terminal
   lifecycle event. The semantic boundary receives no resources or data."
  [compiled-workflow resources state kind]
  (let [base (event-base compiled-workflow kind)]
    (workflow-event! (assoc base :phase :invoke :graph (:graph compiled-workflow)))
    (binding [*execution-id* (:execution-id base)]
      (try
        (let [result (await (fsm/run (:compiled-fsm compiled-workflow)
                                     resources state))]
          (emit! base :return)
          result)
        (catch Throwable error
          (emit! base :throw)
          (throw error))))))

(defn run-async
  "Asynchronously runs one compiled workflow with the same lifecycle contract
   as run-sync. The terminal event is emitted by the actual Maestro execution
   future, so cancellation retains the executor's original ownership. A task
   cancelled before its body starts never becomes an accepted execution and
   therefore emits no lifecycle events."
  [compiled-workflow resources state kind]
  (let [base (event-base compiled-workflow kind)]
    (let [around-execute
          (fn [run cancelled?]
            ;; Bind inside Maestro's execution future: dynamic bindings are
            ;; not assumed to propagate across Jolt carriers.
            (let [active? (atom true)]
              (binding [*execution-id* (:execution-id base)
                        *execution-active?* active?
                        *execution-cancelled?* cancelled?]
                (when-not (cancelled?)
                  (try
                    (workflow-event!
                     (assoc base :phase :invoke
                            :graph (:graph compiled-workflow)))
                    (let [result (run)]
                      (reset! active? false)
                      (emit! base (if (cancelled?) :cancel :return))
                      result)
                    (catch Throwable error
                      (reset! active? false)
                      (emit! base (if (or (cancelled?)
                                          (instance? InterruptedException error))
                                    :cancel
                                    :throw))
                      (throw error)))))))
          fsm (assoc-in (:compiled-fsm compiled-workflow)
                        [:opts :around-execute]
                        around-execute)]
      (fsm/run-async fsm resources state))))
