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

(ns samizdat.telemetry.otel
  "Semantic OpenTelemetry wrappers over casselc/otel for the Samizdat contract.

  Loaded ONLY under the :telemetry alias (Jolt 0.8.3, casselc/otel 87d3ac1);
  the stock build never requires this namespace and never depends on otel.
  Nothing here decides anything: it takes facts a caller is entitled to state
  (the contract's `:authority`), normalises them through
  samizdat.telemetry.contract, mirrors the Langfuse view centrally, and hands
  spans to independently bounded export pipelines.

  Modes (SAMIZDAT_TELEMETRY): off | local | langfuse | dual.
    local    -> SAMIZDAT_OTEL_LOCAL_ENDPOINT (default http://127.0.0.1:4318),
                the standalone oscope receiver
    langfuse -> LANGFUSE_HOST + '/api/public/otel/v1/traces' with headers taken
                from the env var NAMED by SAMIZDAT_LANGFUSE_OTLP_HEADERS_ENV
                (default SAMIZDAT_LANGFUSE_OTLP_HEADERS), value in
                OTEL_EXPORTER_OTLP_HEADERS syntax. The value is read once,
                handed to the exporter, and never logged, echoed or attached to
                any span, event or diagnostic.
    dual     -> both, each on its own bounded queue and worker.
  A process-scoped TRACEPARENT env var parents the root span under the caller
  (a lifecycle_cli process under the Python execution span)."
  (:require [clojure.string :as str]
            [otel.context :as ctx]
            [otel.exporter.memory :as memory]
            [otel.exporter.otlp :as otlp]
            [otel.propagation :as propagation]
            [otel.resource :as res]
            [otel.sdk.export :as export]
            [otel.sdk.tracer :as sdk]
            [otel.trace :as trace]
            [samizdat.telemetry.contract :as contract]
            [samizdat.telemetry.hook :as hook]))

(def scope-name "samizdat.telemetry")
(def scope-version (contract/schema-version))
(def default-local-endpoint "http://127.0.0.1:4318")
(def langfuse-traces-path "/api/public/otel/v1/traces")
(def default-headers-env "SAMIZDAT_LANGFUSE_OTLP_HEADERS")

(def ^{:doc "Current runtime: {:provider :tracer :pipelines :mode :destinations} or nil."}
  runtime (atom nil))

;; --- attribute preparation ---------------------------------------------------

(defn- mirror-langfuse
  "Add the Langfuse view beside the canonical keys. Derived from the manifest,
  never hand-mapped here."
  [attrs]
  (let [m (contract/langfuse-mapping)
        kind (get attrs "samizdat.observation.kind")
        session (some #(get attrs %) (:session-sources m))
        model (some->> (:model-source m) (get attrs))
        infra? (or (= "infra-error" (get attrs "samizdat.evaluator.status"))
                   (true? (get attrs "samizdat.infra.error")))]
    (cond-> (reduce (fn [acc [canonical mirror]]
                      (if (contains? attrs canonical) (assoc acc mirror (get attrs canonical)) acc))
                    attrs
                    (concat (:trace-metadata m) (:observation-metadata m)))
      kind (assoc (:observation-type-key m) kind)
      session (assoc (:session-id-key m) session)
      model (assoc (:model-key m) model)
      infra? (assoc (:level-key m) "ERROR"))))

(defn prepare
  "Contract-normalise `attrs` (unknown keys -> samizdat.x.* strings), stamp the
  observation kind and mirror the Langfuse keys. Pure."
  [kind attrs]
  (-> (assoc attrs "samizdat.observation.kind" (name kind)
             "samizdat.telemetry.schema" (contract/schema-version))
      contract/normalize
      mirror-langfuse))

(defn- tracer [] (:tracer @runtime))

(defn tracer-or-noop
  "The runtime tracer, or the API no-op tracer when telemetry is off or the
  current context suppresses instrumentation (export and receiver work, so
  telemetry never traces itself)."
  []
  (if (ctx/instrumentation-suppressed?)
    trace/noop-tracer
    (or (tracer) trace/noop-tracer)))

(defn set-facts!
  "Set contract attributes on an active span (kind-less: facts only)."
  [span attrs]
  (trace/set-attributes! span (mirror-langfuse (contract/normalize attrs)))
  span)

;; --- semantic wrappers -------------------------------------------------------

(defmacro with-observation
  "(with-observation [sp :evaluator \"action.check\" {...attrs}] body)"
  [[sym kind span-name attrs] & body]
  `(trace/with-span [~sym (tracer-or-noop) ~span-name
                     {:attributes (prepare ~kind ~attrs)}]
     ~@body))

(defmacro with-execution [[sym span-name attrs] & body]
  `(with-observation [~sym :agent ~span-name ~attrs] ~@body))
(defmacro with-generation [[sym span-name attrs] & body]
  `(with-observation [~sym :generation ~span-name ~attrs] ~@body))
(defmacro with-tool [[sym span-name attrs] & body]
  `(with-observation [~sym :tool ~span-name ~attrs] ~@body))
(defmacro with-evaluator [[sym span-name attrs] & body]
  `(with-observation [~sym :evaluator ~span-name ~attrs] ~@body))

(defn record-feedback!
  "Delivered-feedback facts: what was delivered (digest, size, projection,
  policy) and the STATUS it carried. Never the text."
  [span {:keys [delivered status sha256 bytes projection policy]}]
  (set-facts! span {"samizdat.feedback.delivered" delivered
                    "samizdat.feedback.status" status
                    "samizdat.feedback.sha256" sha256
                    "samizdat.feedback.bytes" bytes
                    "samizdat.feedback.projection" projection
                    "samizdat.feedback.policy" policy})
  (trace/add-event! span "feedback.delivered"
                    (contract/normalize {"samizdat.feedback.status" status
                                         "samizdat.feedback.bytes" bytes})))

(defn record-outcome!
  "Distinct outcome facts. Worker claim, protocol verdict, infra status and
  task completion are four different things and stay four attributes."
  [span {:keys [worker-claim worker-claimed protocol-correct infra-error task-complete
                completion-rule terminal-status terminal-reason suite-passed]}]
  (set-facts! span {"samizdat.worker.claim" worker-claim
                    "samizdat.worker.claimed" worker-claimed
                    "samizdat.protocol.correct" protocol-correct
                    "samizdat.infra.error" infra-error
                    "samizdat.task.complete" task-complete
                    "samizdat.completion.rule" completion-rule
                    "samizdat.terminal.status" terminal-status
                    "samizdat.terminal.reason" terminal-reason
                    "samizdat.suite.passed" suite-passed})
  (when (true? infra-error) (trace/set-status! span :error "infrastructure error"))
  span)

(defn record-budgets!
  "Signed budget facts as the journal states them. `budgets` is
  {:turns {:granted :used :remaining} :requests {...} :completion_tokens {...}
   :wall {:granted_ms :used_ms :remaining_ms}}. Negative remainders are kept."
  [span budgets]
  (set-facts! span
              (into {}
                    (for [[dim m] budgets [k v] m]
                      [(str "samizdat.budget." (name dim) "." (name k)) v]))))

;; --- lifecycle observer (the hook seam) ------------------------------------

(defn- lifecycle-attrs [kind attrs]
  {"samizdat.lifecycle.op" (name kind)
   "samizdat.lifecycle.case_id" (or (:case-id attrs) (:scope attrs))
   "samizdat.lifecycle.decision_id" (:decision-id attrs)
   "samizdat.lifecycle.event_type" (some-> (:event-type attrs) name)
   "samizdat.lifecycle.revision" (:revision attrs)
   "samizdat.execution.kind" "lifecycle"})

(defn lifecycle-observer
  "The function installed into samizdat.telemetry.hook: one span per store
  seam, named lifecycle.<op>, carrying ids and the store's own result status.
  The thunk's value and exception pass through untouched (hook guarantees)."
  [kind attrs thunk]
  (with-observation [sp :span (str "lifecycle." (name kind)) (lifecycle-attrs kind attrs)]
    (let [v (thunk)]
      (when (map? v)
        (set-facts! sp {"samizdat.lifecycle.status" (some-> (:status v) name)
                        "samizdat.lifecycle.revision" (:revision v)
                        "samizdat.lifecycle.state" (some-> (:state v) str)}))
      v)))

(defn install! [] (hook/install! lifecycle-observer))
(defn uninstall! [] (hook/uninstall!))

;; --- SDK setup ---------------------------------------------------------------

(defn- getenv [k] (jolt.host/getenv k))

(defn parse-mode [s]
  (case (some-> s str/trim str/lower-case)
    (nil "" "off" "disabled" "0" "false") :off
    "local" :local
    "langfuse" :langfuse
    "dual" :dual
    (throw (ex-info "SAMIZDAT_TELEMETRY must be off|local|langfuse|dual" {:value s}))))

(defn langfuse-traces-url [host]
  (when (str/blank? host) (throw (ex-info "LANGFUSE_HOST is required for langfuse/dual" {})))
  (str (str/replace host #"/+$" "") langfuse-traces-path))

(defn- langfuse-exporter
  "Build the Langfuse exporter. The header value is consumed here and nowhere
  else; the exporter is the only object that holds it."
  [{:keys [host headers-env]}]
  (let [env-name (or headers-env (getenv "SAMIZDAT_LANGFUSE_OTLP_HEADERS_ENV") default-headers-env)
        raw (getenv env-name)
        headers (otlp/parse-headers raw)]
    (when-not (contains? headers "Authorization")
      (throw (ex-info (str "Langfuse headers env var " env-name
                           " must contain Authorization=Basic ... and x-langfuse-ingestion-version=4")
                      {:env env-name})))
    (otlp/exporter {:traces-url (langfuse-traces-url host)
                    :headers (merge {"x-langfuse-ingestion-version" "4"} headers)
                    :environment? false
                    :timeout-ms 10000})))

(defn- local-exporter [{:keys [endpoint]}]
  (otlp/exporter {:endpoint (or endpoint (getenv "SAMIZDAT_OTEL_LOCAL_ENDPOINT") default-local-endpoint)
                  :environment? false
                  :timeout-ms 5000
                  :max-retries 1}))

(def default-batch {:max-queue-size 2048 :max-export-batch-size 256 :schedule-delay-ms 1000})

(defn destinations
  "mode -> {name {:exporter e :config c}}. `overrides` lets tests inject
  exporters (e.g. memory) per destination name."
  [mode {:keys [exporters batch] :as opts}]
  (let [mk (fn [n f] (or (get exporters n) (f opts)))
        cfg (merge default-batch batch)]
    (case mode
      :off {}
      :local {:local {:exporter (mk :local local-exporter) :config cfg}}
      :langfuse {:langfuse {:exporter (mk :langfuse langfuse-exporter) :config cfg}}
      :dual {:local {:exporter (mk :local local-exporter) :config cfg}
             :langfuse {:exporter (mk :langfuse langfuse-exporter) :config cfg}})))

(defn resource-attributes [{:keys [service-name extra]}]
  (merge {"service.name" (or service-name "samizdat")
          "samizdat.telemetry.schema" (contract/schema-version)
          "samizdat.import.mapping_version" (contract/mapping-version)}
         extra))

(defn init!
  "Start the runtime. Returns the runtime map (or nil for :off). Idempotent:
  a second call returns the existing runtime. Options: :mode (else env),
  :exporters {name exporter}, :batch, :service-name, :extra (resource attrs),
  :host (else LANGFUSE_HOST), :endpoint, :headers-env, :install-hook? (true)."
  ([] (init! {}))
  ([{:keys [mode install-hook?] :or {install-hook? true} :as opts}]
   (or @runtime
       (let [mode (or mode (parse-mode (getenv "SAMIZDAT_TELEMETRY")))]
         (when-not (= :off mode)
           (let [opts (cond-> opts (nil? (:host opts)) (assoc :host (getenv "LANGFUSE_HOST")))
                 dests (destinations mode opts)
                 pipelines (export/independent-batch-pipelines dests)
                 provider (sdk/tracer-provider
                           {:resource (res/merge-resources (res/default-resource)
                                                           (res/resource (resource-attributes opts)))
                            :processors [pipelines]})
                 rt {:mode mode
                     :destinations (vec (sort (keys dests)))
                     :provider provider
                     :pipelines pipelines
                     :tracer (sdk/get-tracer provider {:name scope-name :version scope-version})}]
             (reset! runtime rt)
             (when install-hook? (install!))
             rt))))))

(defn stats
  "Bounded scalar per-destination diagnostics; never exporter errors."
  []
  (when-let [rt @runtime] (export/pipeline-stats (:pipelines rt))))

(defn flush! []
  (when-let [rt @runtime] (export/force-flush-pipelines! (:pipelines rt))))

(defn shutdown!
  "Flush and stop every destination (each exactly once, all attempted), then
  uninstall the hook. Returns {destination {:ok? ...}} or nil."
  []
  (when-let [rt @runtime]
    (uninstall!)
    (let [r (ctx/with-instrumentation-suppressed
              (try (sdk/shutdown! (:provider rt)) (catch Throwable _ nil))
              (export/shutdown-pipelines! (:pipelines rt)))]
      (reset! runtime nil)
      r)))

;; --- inbound context -------------------------------------------------------

(defn inbound-context
  "Context extracted from the TRACEPARENT/TRACESTATE environment (the carrier
  a parent process hands a lifecycle_cli run), or nil when absent/invalid."
  []
  (let [tp (getenv "TRACEPARENT")]
    (when-not (str/blank? tp)
      (let [c (propagation/extract-context (cond-> {"traceparent" tp}
                                             (getenv "TRACESTATE") (assoc "tracestate" (getenv "TRACESTATE"))))]
        (when (trace/valid? (trace/span-context-of (trace/span-from-context c))) c)))))

(defmacro with-inbound-context
  "Run body under the inbound context when one exists, else unchanged."
  [& body]
  `(if-let [c# (inbound-context)]
     (ctx/with-context c# ~@body)
     (do ~@body)))
