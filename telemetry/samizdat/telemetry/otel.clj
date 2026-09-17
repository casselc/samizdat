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

  Loaded ONLY under the :telemetry alias (Jolt 0.8.6, casselc/otel 88503a6);
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
  Content override (SAMIZDAT_TELEMETRY_CONTENT=on): by default no prompt,
  model output or tool result travels on a live span (docs/DATA-GOVERNANCE.md;
  mapping/2 carries content in synthetic mode only). The override opens live
  mode too, marks the resource samizdat.telemetry.content=on, and clips each
  value to SAMIZDAT_TELEMETRY_CONTENT_MAX_CHARS (default 32768) with
  samizdat.content.truncated. What travels is only text the model already
  sees or produced — messages after the RFC-003 redaction, tool arguments and
  results after the canonical known-value redaction boundary, the model's own
  content — never an env value.
  A process-scoped TRACEPARENT env var parents the root span under the caller
  (a lifecycle_cli process under the Python execution span)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
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

(def ^{:doc "Current internally owned or externally attached runtime, or nil."}
  runtime (atom nil))

;; --- content override --------------------------------------------------------

(def default-content-max-chars 32768)

(def ^{:doc "{:enabled? bool :max-chars n}; set by init! from the env or its
  :content option, reset by shutdown!. Off by default."}
  content-policy (atom {:enabled? false :max-chars default-content-max-chars}))

(defn content-enabled? [] (true? (:enabled? @content-policy)))

(defn parse-content-flag
  "SAMIZDAT_TELEMETRY_CONTENT: only the literal on (any case) enables."
  [s]
  (= "on" (some-> s str/trim str/lower-case)))

(defn- clip
  "[value truncated?]: `s` cut to the policy's per-value limit."
  [^String s]
  (let [n (:max-chars @content-policy)]
    (if (and (int? n) (pos? n) (> (count s) n))
      [(subs s 0 n) true]
      [s false])))

(defn- prune-nils
  "Structured content without its nil-valued map entries, recursively. In
  the harness an absent value and nil mean the same thing (no parent, no
  answer, no inactive reason), so a viewer gains nothing from `null`."
  [x]
  (cond (map? x) (into {} (keep (fn [[k v]] (when (some? v) [k (prune-nils v)]))) x)
        (sequential? x) (mapv prune-nils x)
        :else x))

(defn- content-text
  "The string form a viewer gets: strings verbatim, structures as JSON with
  nil entries pruned (falling back to pr-str for anything JSON cannot encode)."
  [x]
  (cond (nil? x) nil
        (string? x) x
        :else (try (json/write-str (prune-nils x) :escape-slash false) (catch Throwable _ (pr-str x)))))

(defn content-attrs
  "The content keys for one span when the override is on, else {}: input
  and/or output under the Langfuse observation keys, plus the truncation
  marker when either was clipped. nil values are omitted, never stringified."
  [{:keys [input output]}]
  (if-not (content-enabled?)
    {}
    (let [[in in-cut] (some-> (content-text input) clip)
          [out out-cut] (some-> (content-text output) clip)]
      (cond-> {}
        in (assoc "langfuse.observation.input" in)
        out (assoc "langfuse.observation.output" out)
        (or in-cut out-cut) (assoc "samizdat.content.truncated" true)))))

;; --- attribute preparation ---------------------------------------------------

(defn- mirror-langfuse
  "Add the Langfuse view beside the canonical keys. Derived from the manifest,
  never hand-mapped here. `kind` and `mode` are fallbacks for attributes
  set after the span started (set-facts!); the attrs win when present.
  Trace-level metadata is mirrored only from the root observation kind;
  other kinds mirror the same attribute into observation metadata
  (mapping/2, so child observations never clobber one trace slot)."
  ([attrs] (mirror-langfuse attrs nil nil))
  ([attrs kind mode]
   (let [m (contract/langfuse-mapping)
         kind (or (get attrs (:observation-type-source m)) kind)
         mode (or (get attrs (:mode-source m)) mode)
         root? (= kind (:root-observation-kind m))
         session (some #(get attrs %) (:session-sources m))
         model (some->> (:model-source m) (get attrs))
         infra? (or (= "infra-error" (get attrs "samizdat.evaluator.status"))
                    (true? (get attrs "samizdat.infra.error")))]
     (cond-> (reduce (fn [acc [canonical mirror]]
                       (if (contains? attrs canonical) (assoc acc mirror (get attrs canonical)) acc))
                     attrs
                     (concat (if root? (:trace-metadata m) (:trace-metadata-on-observation m))
                             (:observation-metadata m)))
       (contains? attrs (:observation-type-source m))
       (assoc (:observation-type-key m) (get attrs (:observation-type-source m)))
       session (assoc (:session-id-key m) session)
       model (assoc (:model-key m) model)
       infra? (assoc (:level-key m) "ERROR")))))

(defn- span-attribute
  "Read an attribute already set on an SDK span (nil for non-recording or
  API spans, which carry no state)."
  [span k]
  (try (some-> (:state span) deref :attributes (get k))
       (catch Throwable _ nil)))

(defn prepare
  "Contract-normalise `attrs` (unknown keys -> samizdat.x.* strings), stamp the
  observation kind, apply the content policy and mirror the Langfuse keys.
  Pure."
  [kind attrs]
  (let [[attrs content] (contract/split-content attrs)
        attrs (-> (assoc attrs "samizdat.observation.kind" (name kind)
                         "samizdat.telemetry.schema" (contract/schema-version))
                  contract/normalize
                  mirror-langfuse)]
    (contract/apply-content attrs content (get attrs "samizdat.observation.mode")
                            (content-enabled?))))

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
  "Set contract attributes on an active span. The span's own kind and mode
  decide the trace-metadata scope and the content policy."
  [span attrs]
  (let [[attrs content] (contract/split-content attrs)
        kind (span-attribute span "samizdat.observation.kind")
        mode (or (get attrs "samizdat.observation.mode")
                 (span-attribute span "samizdat.observation.mode"))
        attrs (contract/apply-content (mirror-langfuse (contract/normalize attrs) kind mode)
                                      content mode (content-enabled?))
        refused-key contract/content-refused-key
        already (span-attribute span refused-key)
        attrs (if (and already (contains? attrs refused-key))
                (assoc attrs refused-key
                       (str/join "," (sort (distinct (concat (str/split already #",")
                                                             (str/split (get attrs refused-key) #","))))))
                attrs)]
    (trace/set-attributes! span attrs))
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


;; --- harness run observer (samizdat-observability-run-83eb99a.edn) ---------

(defn- sha256-hex [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- kw-name [x] (cond (keyword? x) (name x) (nil? x) nil :else (str x)))

(def run-kinds
  "hook kind -> {:kind observation kind, :name span name}. Facts come from the
  seam's arguments (start) and its return value (end); text does only under
  the content override (content-attrs): see run-content-in / run-content-out
  for what each seam offers as its input and output."
  {:run            {:kind :agent      :name "run"}
   :control-loop   {:kind :span       :name "run.rounds"}
   :turn           {:kind :span       :name "turn"}
   :branch-open    {:kind :span       :name "branch.open"}
   :branch-close   {:kind :span       :name "branch.close"}
   :model          {:kind :generation :name "model.chat"}
   :tool-selection {:kind :span       :name "tool.selection"}
   :tool           {:kind :tool       :name "tool"}
   :steer          {:kind :span       :name "steer"}})

(defn- branch-summary
  "The branch facts a viewer wants beside its transcript: identity, state,
  and the counters the gates read. Never the tape."
  [b]
  (when (map? b)
    {:id (:id b) :status (:status b) :inactive-reason (:inactive-reason b)
     :turns (count (:turns b)) :messages (count (:messages b))
     :consecutive-failures (:consecutive-failures b)
     :turns-since-progress (:turns-since-progress b)
     :phase (:phase b)}))

(defn- run-content-in
  "What each seam offers as the span's input under the content override.
  Only text the model was given or produced, and the harness's own decisions
  about it: the problem, the tape, a reply, a tool call, a steer."
  [kind attrs]
  (case kind
    :run {:input (:problem attrs)}
    :control-loop {:input {:start-turn (:start-turn attrs)
                           :branches (mapv branch-summary (:branch-list attrs))}}
    :turn (let [b (:branch attrs)]
            {:input (assoc (branch-summary b) :turn (:turn attrs)
                           ;; the message the turn starts from
                           :last-message (peek (vec (:messages b))))})
    :branch-open {:input {:branch-id (:branch-id attrs) :parent-id (:parent-id attrs)
                          :created-at-turn (:created-at-turn attrs) :problem (:problem attrs)}}
    :branch-close {:input {:branch-id (:branch-id attrs) :status (:status attrs) :reason (:reason attrs)}}
    :model {:input (:input attrs)}
    :tool-selection {:input (cond-> {:content (:content attrs)}
                              (:prefill attrs) (assoc :prefill (:prefill attrs)))}
    :tool {:input (:input attrs)}
    :steer {:input (assoc (branch-summary (:branch attrs)) :turn (:turn attrs))}
    nil))

(defn- run-content-out
  "What each seam offers as the span's output: its return value, reduced to
  the text and decisions in it. `attrs` is the start-side map, so a turn can
  report only the messages it appended."
  [kind attrs v]
  (case kind
    :run (when (map? v) {:output (:answer v)})
    :control-loop (when (map? v)
                    {:output {:status (:status v) :answer (:answer v)
                              :branches (mapv branch-summary (:branches v))}})
    :turn (when (map? v)
            (let [before (count (:messages (:branch attrs)))
                  after (vec (:messages v))]
              {:output (assoc (branch-summary v)
                              :appended (if (<= before (count after)) (subvec after before) after))}))
    :branch-open {:output (when v {:branch-id v})}
    :branch-close {:output {:rows v :closed (and (number? v) (pos? v))}}
    :model (when (map? v) {:output (:content v)})
    :tool-selection (when (map? v) {:output {:parsed (:parsed v) :signals (:signals v)}})
    :tool (when (map? v) {:output (:result v)})
    :steer {:output (if (map? v)
                      ;; the realised decision; a gate's effect may be a fn, which is not text
                      (into {:steered true} (remove (comp fn? val))
                            (select-keys v [:gate :priority :message :prediction :tool :effect :window :passed-over]))
                      ;; no gate fired: say so rather than leave a null gate
                      {:steered false})}
    nil))

(defn- run-start-attrs [kind attrs]
  (let [common {"samizdat.execution.kind" "run"
                "samizdat.observation.mode" "live"
                "samizdat.run.id" (:run-id attrs)
                "samizdat.branch.id" (:branch-id attrs)
                "samizdat.turn" (:turn attrs)}]
    (merge common
           (content-attrs (when (content-enabled?) (run-content-in kind attrs)))
           (case kind
             :run {"samizdat.run.provider" (kw-name (:provider attrs))
                   "gen_ai.request.model" (:model attrs)
                   "samizdat.run.max_turns" (:max-turns attrs)
                   "samizdat.run.beam_width" (:beam-width attrs)
                   "samizdat.problem.sha256" (some-> (:problem attrs) str sha256-hex)
                   "samizdat.problem.chars" (some-> (:problem attrs) str count)}
             :control-loop {"samizdat.run.start_turn" (:start-turn attrs)
                            "samizdat.run.branches" (:branches attrs)}
             :branch-open {"samizdat.branch.parent_id" (:parent-id attrs)
                           "samizdat.branch.created_at_turn" (:created-at-turn attrs)}
             :branch-close {"samizdat.branch.status" (kw-name (:status attrs))}
             :model {"samizdat.model.provider" (kw-name (:provider attrs))
                     "gen_ai.request.model" (:model attrs)
                     "samizdat.model.messages" (:messages attrs)
                     "samizdat.model.max_tokens" (:max-tokens attrs)
                     "samizdat.model.prefill" (:prefill attrs)
                     "samizdat.model.force_tool" (:force-tool attrs)}
             :tool-selection {"samizdat.model.content_chars" (:content-chars attrs)}
             :tool {"samizdat.tool.name" (:tool-name attrs)}
             {}))))

(declare run-end-facts)

(defn- run-end-attrs [kind attrs v]
  (merge (content-attrs (when (content-enabled?) (run-content-out kind attrs v)))
         (run-end-facts kind v)))

(defn- run-end-facts [kind v]
  (case kind
    :run (when (map? v) {"samizdat.run.id" (:run-id v)
                         "samizdat.run.status" (kw-name (:status v))
                         "samizdat.task.complete" (some? (:answer v))})
    :control-loop (when (map? v) {"samizdat.run.status" (kw-name (:status v))})
    :turn (when (map? v) {"samizdat.branch.status" (kw-name (:status v))})
    :model (when (map? v)
             (let [u (:usage v)]
               {"gen_ai.response.finish_reason" (:finish-reason v)
                "samizdat.model.elapsed_ms" (:elapsed-ms v)
                "samizdat.model.content_chars" (some-> (:content v) str count)
                "gen_ai.usage.input_tokens" (:prompt-tokens u)
                "gen_ai.usage.output_tokens" (:completion-tokens u)
                "gen_ai.usage.total_tokens" (:total-tokens u)
                "gen_ai.usage.cache_hit_tokens" (:cache-hit-tokens u)}))
    :tool-selection (when (map? v)
                      {"samizdat.selection.tool" (some-> (get-in v [:parsed :name]) kw-name)
                       "samizdat.selection.parsed" (some? (:parsed v))
                       "samizdat.selection.said_chars" (some-> (:said v) str count)})
    :tool (when (map? v)
            {"samizdat.tool.category" (kw-name (:category v))
             "samizdat.tool.progress" (boolean (:progress? v))
             "samizdat.tool.output_chars" (some-> (:result v) str count)})
    :steer {"samizdat.steer.gate" (some-> (:gate v) kw-name)
            "samizdat.steer.priority" (:priority v)
            "samizdat.steer.passed_over" (some-> (:passed-over v) count)}
    nil))

(defn run-observer
  "One span per harness seam: the root `run` (agent) and its rounds, turns,
  branch open/close, model calls (generation, with usage), tool selection,
  tools and steers. Branch turns run in futures, which inherit the dynamic
  context, so every span parents under the run. Facts only."
  [kind attrs thunk]
  (let [{k :kind nm :name} (run-kinds kind)]
    (with-observation [sp k nm (run-start-attrs kind attrs)]
      (let [v (thunk)]
        (when-let [facts (run-end-attrs kind attrs v)]
          (set-facts! sp facts))
        ;; The one line that lets an operator find the run in a viewer:
        ;; identities only, on stderr with the rest of the harness log.
        (when (and (= :run kind) (map? v))
          (log/info "telemetry run" (:run-id v) "trace" (:trace-id (trace/span-context-of sp))))
        v))))

(defn observer
  "The one function the hook seam receives: lifecycle kinds go to the store
  observer, harness kinds to the run observer. Unknown kinds just run."
  [kind attrs thunk]
  (cond (contains? run-kinds kind) (run-observer kind attrs thunk)
        :else (lifecycle-observer kind attrs thunk)))

(defn install! [] (hook/install! observer))
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

(defn resource-attributes
  ([opts] (resource-attributes opts @content-policy))
  ([{:keys [service-name extra]} policy]
   (merge {"service.name" (or service-name "samizdat")
           "samizdat.telemetry.schema" (contract/schema-version)
           "samizdat.import.mapping_version" (contract/mapping-version)
           contract/content-marker-key (if (:enabled? policy) "on" "off")}
          extra)))

(defn content-policy-from-env
  "The override as the environment states it; :max-chars falls back to the
  default when unset or not a positive integer."
  []
  (let [n (some-> (getenv "SAMIZDAT_TELEMETRY_CONTENT_MAX_CHARS") str/trim not-empty parse-long)]
    {:enabled? (parse-content-flag (getenv contract/content-override-env))
     :max-chars (if (and (int? n) (pos? n)) n default-content-max-chars)}))

(defn- normalized-content-policy [content]
  (merge {:enabled? false :max-chars default-content-max-chars}
         (if (nil? content) (content-policy-from-env) content)))

(defn attach!
  "Attach Samizdat's observer to an externally owned SDK runtime without
  constructing or initializing a provider. Required options are an existing
  `:tracer` and `:flush!`, `:stats`, and `:shutdown!` callbacks owned by the
  caller. Samizdat dispatches lifecycle calls to those callbacks but otherwise
  owns only hook installation. Optional `:content` has the same normalization
  and environment fallback as `init!`. Idempotent like `init!`: while any
  runtime is active, a later attach returns it unchanged."
  [{:keys [tracer flush! stats shutdown! install-hook? content]
    :or {install-hook? true}}]
  (locking runtime
    (or @runtime
        (do
          (when-not tracer
            (throw (ex-info "telemetry attach requires an existing :tracer" {})))
          (doseq [[k f] [[:flush! flush!] [:stats stats] [:shutdown! shutdown!]]]
            (when-not (fn? f)
              (throw (ex-info (str "telemetry attach requires a " k " callback")
                              {:callback k}))))
          (let [policy (normalized-content-policy content)
                previous-runtime @runtime
                previous-policy @content-policy
                previous-observer @hook/observer
                rt {:owner :external
                    :tracer tracer
                    :flush-callback flush!
                    :stats-callback stats
                    :shutdown-callback shutdown!}]
            (try
              (reset! content-policy policy)
              (reset! runtime rt)
              (when (:enabled? policy)
                (log/warn "telemetry content override ON for externally owned SDK; clipped at"
                          (:max-chars policy) "chars"))
              (when install-hook? (install!))
              rt
              (catch Throwable error
                ;; An external provider remains caller-owned. Roll back only
                ;; Samizdat publication, including a partially installed hook.
                (reset! runtime previous-runtime)
                (reset! content-policy previous-policy)
                (reset! hook/observer previous-observer)
                (throw error))))))))

(defn init!
  "Start the runtime. Returns the runtime map (or nil for :off). Idempotent:
  a second call returns the existing runtime. Options: :mode (else env),
  :exporters {name exporter}, :batch, :service-name, :extra (resource attrs),
  :host (else LANGFUSE_HOST), :endpoint, :headers-env, :install-hook? (true)."
  ([] (init! {}))
  ([{:keys [mode install-hook? content] :or {install-hook? true} :as opts}]
   (locking runtime
     (or @runtime
         (let [mode (or mode (parse-mode (getenv "SAMIZDAT_TELEMETRY")))]
           (when-not (= :off mode)
             (let [policy (normalized-content-policy content)
                   previous-policy @content-policy
                   previous-observer @hook/observer
                   pipelines* (atom nil)
                   provider* (atom nil)]
               (try
                 (let [;; With content on, a batch is bounded by value count x
                       ;; clip size (8 x 2 x 32768 chars = 512 KiB), under the
                       ;; local receiver's 1 MiB request limit; an explicit
                       ;; :batch wins.
                       opts (cond-> opts
                              (nil? (:host opts)) (assoc :host (getenv "LANGFUSE_HOST"))
                              (:enabled? policy) (update :batch #(merge {:max-export-batch-size 8} %)))
                       dests (destinations mode opts)
                       pipelines (export/independent-batch-pipelines dests)
                       _ (reset! pipelines* pipelines)
                       provider (sdk/tracer-provider
                                 {:resource
                                  (res/merge-resources
                                   (res/default-resource)
                                   (res/resource (resource-attributes opts policy)))
                                  :processors [pipelines]})
                       _ (reset! provider* provider)
                       tracer (sdk/get-tracer provider {:name scope-name :version scope-version})
                       rt {:mode mode
                           :destinations (vec (sort (keys dests)))
                           :provider provider
                           :pipelines pipelines
                           :tracer tracer}]
                   ;; Publish only after every owned resource and the tracer
                   ;; exist. The runtime lock keeps this transition atomic with
                   ;; attach/init/shutdown and hook installation.
                   (reset! content-policy policy)
                   (reset! runtime rt)
                   (when (:enabled? policy)
                     (log/warn "telemetry content override ON: prompts, model outputs and tool results"
                               "travel to" (pr-str (:destinations rt)) "clipped at"
                               (:max-chars policy) "chars"))
                   (when install-hook? (install!))
                   rt)
                 (catch Throwable error
                   ;; No partial state is visible while this lock is held.
                   ;; Both ownership faces are terminal/idempotent; attempt each
                   ;; exactly once and preserve the construction failure.
                   (reset! runtime nil)
                   (reset! content-policy previous-policy)
                   (reset! hook/observer previous-observer)
                   (if-let [provider @provider*]
                     ;; The provider owns and retires its processor pipeline.
                     (try (sdk/shutdown! provider) (catch Throwable _ nil))
                     (when-let [pipelines @pipelines*]
                       (try (export/shutdown-pipelines! pipelines)
                            (catch Throwable _ nil))))
                   (throw error))))))))))

(defn stats
  "Bounded scalar per-destination diagnostics; never exporter errors."
  []
  (locking runtime
    (when-let [rt @runtime]
      (if-let [stats-callback (:stats-callback rt)]
        (stats-callback)
        (export/pipeline-stats (:pipelines rt))))))

(defn flush! []
  (locking runtime
    (when-let [rt @runtime]
      (if-let [flush-callback (:flush-callback rt)]
        (flush-callback)
        (export/force-flush-pipelines! (:pipelines rt))))))

(defn shutdown!
  "Flush and stop every destination (each exactly once, all attempted), then
  uninstall the hook. Internally owned runtimes return per-destination results;
  attached runtimes return the external shutdown callback's result. Returns nil
  when no runtime remains."
  []
  (when-let [rt (locking runtime
                  (when-let [rt @runtime]
                    ;; Retire all Samizdat state before entering owner code.
                    ;; Re-entrant/concurrent shutdown therefore sees nil, and
                    ;; hook installation cannot race after retirement.
                    (reset! runtime nil)
                    (uninstall!)
                    (reset! content-policy
                            {:enabled? false :max-chars default-content-max-chars})
                    rt))]
    (if-let [shutdown-callback (:shutdown-callback rt)]
      (ctx/with-instrumentation-suppressed (shutdown-callback))
      (let [r (ctx/with-instrumentation-suppressed
                (try (sdk/shutdown! (:provider rt)) (catch Throwable _ nil))
                (export/shutdown-pipelines! (:pipelines rt)))]
        r))))

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
