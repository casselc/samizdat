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

(ns samizdat.telemetry.contract
  "The telemetry semantic contract, read from
  resources/samizdat/telemetry/attribute-manifest.edn and validated on load.

  Dependency-free on purpose: this namespace is what the Python adapter, the
  importer, the Jolt wrappers (samizdat.telemetry.otel, under the :telemetry
  alias) and the typed-column compiler (jolt-otel-clickhouse, run outside
  this repository) all agree on. It decides nothing about the pilots — budgets,
  legality, completion and decision-time availability stay with the journal
  and the evidence records (`:authority` says whose fact each attribute is);
  it only says how a fact is spelled once someone who is entitled to state it
  does so.

  `normalize` is fail-open: a value that does not fit its declared type or
  domain is kept under the `samizdat.x.` string fallback and reported, never
  thrown away and never coerced into a lie. nil is the only 'absent'; false,
  0, negatives and \"\" are values."
  (:require ;; the java.time.* host shim, before data.json — see samizdat.store.journal
            [jolt.time]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def resource-path "samizdat/telemetry/attribute-manifest.edn")

(def types #{:string :boolean :int64 :double})
(def promotable-types #{:string :boolean :int64})
(def langfuse-targets #{:trace-metadata :observation-metadata :usage :session
                        :type :model :none})
(def missing-semantics #{:unavailable :not-applicable})
(def authorities #{:journal :evidence :runtime :import :telemetry})

(defn- manifest-error
  "The exception for a manifest that does not validate; every message is a
  developer-facing stack-trace string and is raised under `throw` at the call
  site so samizdat.base-test sees it as such."
  [msg data]
  (ex-info (str "telemetry manifest: " msg) (assoc data :samizdat.telemetry/error true)))

(defn- validate-attribute [{:keys [key type domain authority promote? langfuse missing] :as a}]
  (when-not (and (string? key) (not (str/blank? key)))
    (throw (manifest-error "attribute key must be a non-blank string" {:attribute a})))
  (when-not (contains? types type)
    (throw (manifest-error "attribute type is not supported" {:key key :type type})))
  (when-not (contains? authorities authority)
    (throw (manifest-error "attribute authority is not recognised" {:key key :authority authority})))
  (when-not (boolean? promote?)
    (throw (manifest-error ":promote? must be true or false" {:key key})))
  (when (and promote? (not (contains? promotable-types type)))
    (throw (manifest-error "only string, boolean and int64 attributes may be promoted" {:key key :type type})))
  (when-not (contains? langfuse-targets langfuse)
    (throw (manifest-error "langfuse target is not recognised" {:key key :langfuse langfuse})))
  (when-not (contains? missing-semantics missing)
    (throw (manifest-error "missing semantics not recognised" {:key key :missing missing})))
  (when (some? domain)
    (when-not (and (vector? domain) (seq domain) (every? string? domain)
                   (= (count domain) (count (set domain))))
      (throw (manifest-error "domain must be a non-empty vector of distinct strings" {:key key})))
    (when-not (= :string type)
      (throw (manifest-error "only string attributes may declare a domain" {:key key}))))
  a)

(defn validate
  "Validate a manifest value; returns it or throws ex-info with
  :samizdat.telemetry/error true."
  [{:keys [schema mapping-version attributes observation-kinds session-keys
           fallback-prefix max-promoted] :as manifest}]
  (when-not (= "samizdat-telemetry/1" schema)
    (throw (manifest-error "unsupported schema" {:schema schema})))
  (when-not (string? mapping-version) (throw (manifest-error "mapping-version must be a string" {})))
  (when-not (and (string? fallback-prefix) (str/ends-with? fallback-prefix "."))
    (throw (manifest-error "fallback-prefix must end with a dot" {:fallback-prefix fallback-prefix})))
  (when-not (and (vector? attributes) (seq attributes))
    (throw (manifest-error "attributes must be a non-empty vector" {})))
  (run! validate-attribute attributes)
  (let [keys* (mapv :key attributes)
        dupes (->> (frequencies keys*) (filter (fn [[_ n]] (> n 1))) (map first) sort vec)]
    (when (seq dupes) (throw (manifest-error "duplicate attribute keys" {:keys dupes})))
    (when (some #(str/starts-with? % fallback-prefix) keys*)
      (throw (manifest-error "no declared attribute may live under the fallback prefix" {})))
    (let [promoted (filterv :promote? attributes)]
      (when-not (and (integer? max-promoted) (pos? max-promoted))
        (throw (manifest-error "max-promoted must be a positive integer" {})))
      (when (> (count promoted) max-promoted)
        (throw (manifest-error "too many promoted attributes" {:count (count promoted) :max max-promoted}))))
    (let [kind (first (filter #(= "samizdat.observation.kind" (:key %)) attributes))]
      (when-not (and kind (= :type (:langfuse kind)) (= observation-kinds (:domain kind)))
        (throw (manifest-error "samizdat.observation.kind must be the :type-targeted attribute whose domain is :observation-kinds" {}))))
    (let [by-key (into {} (map (juxt :key identity)) attributes)]
      (when-not (and (vector? session-keys) (seq session-keys))
        (throw (manifest-error "session-keys must be a non-empty vector" {})))
      (doseq [k session-keys]
        (when-not (= :string (:type (get by-key k)))
          (throw (manifest-error "session key must be a declared string attribute" {:key k}))))))
  manifest)

(defn load-manifest
  "Read and validate the manifest resource (or an explicit EDN string)."
  ([] (let [r (io/resource resource-path)]
        (when-not r (throw (manifest-error "manifest resource not found" {:resource resource-path})))
        (load-manifest (slurp r))))
  ([edn-string] (validate (edn/read-string edn-string))))

(def manifest (delay (load-manifest)))

(defn attributes
  "key -> attribute entry."
  ([] (attributes @manifest))
  ([m] (into {} (map (juxt :key identity)) (:attributes m))))

(defn promoted
  "The closed set of promoted keys, in manifest order."
  ([] (promoted @manifest))
  ([m] (mapv :key (filter :promote? (:attributes m)))))

(defn schema-version ([] (:schema @manifest)) ([m] (:schema m)))
(defn mapping-version ([] (:mapping-version @manifest)) ([m] (:mapping-version m)))

;; --- normalisation ------------------------------------------------------

(defn- key-string [k]
  (cond (string? k) k
        (keyword? k) (if-let [n (namespace k)] (str n "/" (name k)) (name k))
        :else (str k)))

(defn- coerce
  "[ok? value] for one declared attribute."
  [{:keys [type domain]} v]
  (case type
    :string  (cond (string? v) (if (or (nil? domain) (some #(= v %) domain)) [true v] [false v])
                   (keyword? v) (coerce {:type :string :domain domain} (name v))
                   :else [false v])
    :boolean (if (boolean? v) [true v] [false v])
    :int64   (if (and (integer? v) (not (boolean? v))) [true (long v)] [false v])
    :double  (if (and (number? v) (not (boolean? v))) [true (double v)] [false v])))

(defn normalize-result
  "Normalise an attribute map against the manifest.

  Returns {:attributes {key value} :errors [{:key .. :reason ..}]}.
  nil values are omitted; declared keys are coerced to their type; a value
  that fails its type or domain, and any undeclared key, lands as a string
  under the fallback prefix (`samizdat.x.<key>`) so nothing is silently lost
  and nothing undeclared can masquerade as a contract attribute."
  ([attrs] (normalize-result @manifest attrs))
  ([m attrs]
   (let [decl (attributes m)
         prefix (:fallback-prefix m)]
     (reduce
      (fn [acc [k v]]
        (let [k (key-string k)]
          (cond
            (nil? v) acc
            (str/starts-with? k prefix)
            (-> acc (assoc-in [:attributes k] (str v)))
            :else
            (if-let [a (get decl k)]
              (let [[ok? v*] (coerce a v)]
                (if ok?
                  (assoc-in acc [:attributes k] v*)
                  (-> acc
                      (assoc-in [:attributes (str prefix k)] (str v))
                      (update :errors conj {:key k :reason (if (:domain a) :domain-or-type :type)}))))
              (-> acc
                  (assoc-in [:attributes (str prefix k)] (str v))
                  (update :errors conj {:key k :reason :undeclared}))))))
      {:attributes {} :errors []}
      attrs))))

(defn normalize
  ([attrs] (:attributes (normalize-result attrs)))
  ([m attrs] (:attributes (normalize-result m attrs))))

;; --- Langfuse mapping ---------------------------------------------------

(defn- short-name [k]
  (let [prefix "samizdat."]
    (str/replace (if (str/starts-with? k prefix) (subs k (count prefix)) k) "." "_")))

(def root-observation-kind
  "The observation kind that owns the trace: trace-level metadata is mirrored
  from this kind only. Every other kind mirrors the same attribute into its
  own observation metadata, so two child observations carrying different
  values (two evaluator checks with different charged seconds, say) never
  race for one trace-level slot (mapping/2)."
  "agent")

(def content-keys
  "Langfuse observation content. Never carried by live or historical
  telemetry (docs/DATA-GOVERNANCE.md excludes prompts, tool arguments and
  file contents); allowed only in synthetic mode, where the content is stub
  text that qualifies the input/output path itself."
  ["langfuse.observation.input" "langfuse.observation.output"])

(def content-allowed-modes
  "Modes that carry content by default, with no operator action."
  #{"synthetic"})

(def content-override-modes
  "Modes an operator may ADDITIONALLY open with the content override
  (SAMIZDAT_TELEMETRY_CONTENT=on): live runs only. A historical import never
  carries content whatever the override says — its sources are transcripts
  the import is forbidden to upload."
  #{"live"})

(def content-override-env
  "Environment variable that turns the override on (\"on\"; anything else,
  or absent, is off). Read once at runtime init, never per span."
  "SAMIZDAT_TELEMETRY_CONTENT")

(def content-marker-key
  "Resource attribute stating the policy the process ran under (\"off\" |
  \"on\"), so a trace carrying prompts is distinguishable from one that
  refused them by policy rather than by absence."
  "samizdat.telemetry.content")

(def content-refused-key
  "Fallback attribute naming the content keys that were dropped (key names
  only, never the values)."
  "samizdat.x.content.refused")

(defn content-allowed?
  "May observation content travel under `mode` (a samizdat.observation.mode
  value)? Unknown or absent modes refuse. With `override?` true the
  override modes are allowed too; the default policy never needs it."
  ([mode] (content-allowed? mode false))
  ([mode override?]
   (or (contains? content-allowed-modes mode)
       (and (true? override?) (contains? content-override-modes mode)))))

(defn split-content
  "Separate content keys from `attrs` before normalisation, so a refused
  input/output can never be stringified into the fallback map.
  Returns [attrs-without-content {content-key string-value}]."
  [attrs]
  (reduce (fn [[rest content] k]
            (let [k* (key-string k) v (get attrs k)]
              (if (and (some #{k*} content-keys) (some? v))
                [(dissoc rest k) (assoc content k* (str v))]
                [rest content])))
          [attrs {}]
          (keys attrs)))

(defn apply-content
  "Add `content` back to normalised `attrs` when `mode` allows it (under the
  operator override when `override?`); otherwise drop it and name the
  refused keys under `content-refused-key`."
  ([attrs content mode] (apply-content attrs content mode false))
  ([attrs content mode override?]
   (cond
     (empty? content) attrs
     (content-allowed? mode override?) (merge attrs content)
     :else (assoc attrs content-refused-key (str/join "," (sort (keys content)))))))

(defn langfuse-mapping
  "Derived, never hand-written: how each canonical attribute is mirrored for
  Langfuse. Canonical keys are always kept; the mirror is additive."
  ([] (langfuse-mapping @manifest))
  ([m]
   (let [attrs (:attributes m)
         by-target (group-by :langfuse attrs)]
     {:mapping-version (:mapping-version m)
      :telemetry-schema (:schema m)
      :observation-type-key "langfuse.observation.type"
      :observation-type-source "samizdat.observation.kind"
      :observation-types (vec (:observation-kinds m))
      :root-observation-kind root-observation-kind
      :trace-metadata-scope "root-observation"
      :mode-source "samizdat.observation.mode"
      :content-keys content-keys
      :content-allowed-modes (vec (sort content-allowed-modes))
      :content-override {:env content-override-env
                         :modes (vec (sort content-override-modes))
                         :marker content-marker-key}
      :content-refused-key content-refused-key
      :session-id-key "langfuse.session.id"
      :session-sources (vec (:session-keys m))
      :model-key "langfuse.observation.model.name"
      :model-source (some->> (:model by-target) first :key)
      :usage-keys (vec (sort (map :key (:usage by-target))))
      :level-key "langfuse.observation.level"
      :status-message-key "langfuse.observation.status_message"
      ;; Only infrastructure failure is an ERROR level; a failed verdict is a
      ;; verdict, and never a score (Decision 6).
      :level-rules [{:when {"samizdat.evaluator.status" "infra-error"} :level "ERROR"}
                    {:when {"samizdat.infra.error" true} :level "ERROR"}]
      :trace-metadata (into (sorted-map)
                            (map (fn [a] [(:key a) (str "langfuse.trace.metadata." (short-name (:key a)))]))
                            (:trace-metadata by-target))
      ;; the same attributes on a non-root observation: observation-scoped
      :trace-metadata-on-observation
      (into (sorted-map)
            (map (fn [a] [(:key a) (str "langfuse.observation.metadata." (short-name (:key a)))]))
            (:trace-metadata by-target))
      :observation-metadata (into (sorted-map)
                                  (map (fn [a] [(:key a) (str "langfuse.observation.metadata." (short-name (:key a)))]))
                                  (:observation-metadata by-target))})))

;; --- typed-column fragments (jolt-otel-clickhouse v3 shape) ---------------

(defn typed-column-fragments
  "The promoted set as jolt-otel-clickhouse span-attribute fragments. Compiled
  by otel.exporter.chdb.attribute-manifest/compile-manifest OUTSIDE this
  repository (see docs/telemetry.md); here it is data only."
  ([] (typed-column-fragments @manifest))
  ([m]
   (mapv (fn [a] {:signal :spans :table "otel_traces" :location :span-attributes
                  :key (:key a) :type (:type a)})
         (filter :promote? (:attributes m)))))

;; --- canonical JSON rendering ------------------------------------------

(defn- canonical [v]
  (cond (map? v) (into (sorted-map) (map (fn [[k x]] [(key-string k) (canonical x)])) v)
        (sequential? v) (mapv canonical v)
        (keyword? v) (name v)
        (set? v) (mapv canonical (sort v))
        :else v))

(defn render-json
  "Canonical JSON (sorted keys, keywords as names, no insignificant
  whitespace) so a rendering is byte-stable across runs and machines."
  [v]
  (json/write-str (canonical v) :escape-slash false))

(defn manifest-json ([] (manifest-json @manifest)) ([m] (render-json m)))
(defn mapping-json ([] (mapping-json @manifest)) ([m] (render-json (langfuse-mapping m))))
(defn fragments-json ([] (fragments-json @manifest)) ([m] (render-json {:schema (:schema m) :fragments (typed-column-fragments m)})))

(defn -main
  "jolt telemetry-manifest OUT-DIR — render attribute-manifest.json,
  langfuse-mapping.json and typed-columns.json for consumers."
  [& [out-dir]]
  (when (str/blank? out-dir)
    (binding [*out* *err*] (println "usage: telemetry-manifest OUT-DIR"))
    (jolt.host/exit 2))
  (let [m @manifest]
    (io/make-parents (io/file out-dir "x"))
    (doseq [[f s] {"attribute-manifest.json" (manifest-json m)
                   "langfuse-mapping.json" (mapping-json m)
                   "typed-columns.json" (fragments-json m)}]
      (spit (io/file out-dir f) (str s "\n")))
    (println (str "rendered " (count (:attributes m)) " attributes, "
                  (count (promoted m)) " promoted -> " out-dir))))
