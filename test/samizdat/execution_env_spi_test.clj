;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.execution-env-spi-test
  "Execution-environment EDN SPI conformance for the M2 adapter (RFC-012).

  Three layers are pinned here, and keeping them apart is the point:

  1. THE ENVELOPE RENDER GRAMMAR — how envelope bytes are spelled — is
     pinned by the byte-identical fixture body under
     test/samizdat/fixtures/spi-v1/: one canonical EDN file per envelope,
     each beside its golden sha256sum-format digest, copied byte for byte
     from the bbagent ecosystem's committed fixture directory. This suite
     carries its OWN implementation of the render rules (below) and must
     render the ported evidence inputs to those exact bytes; a difference
     means the two repositories no longer keep one grammar.

  2. THE CANONICAL EDN COORDINATE GRAMMAR ([:bb4t.coordinate/v1 kind tree]
     over the tagged tree) is what this repository's envelope coordinate
     SLOTS speak — the same grammar and kind (:bb4t/execution-environment)
     bb4t's semantic execution layer uses, kept on this side by
     samizdat.security.canonical-edn and cross-pinned by
     samizdat.canonical-edn-test's shared golden vectors. It is
     deliberately NOT the SPI's own [:spi.coordinate/v1 ...] coordinate
     algorithm (the one the fixture describe envelopes carry): the two are
     domain-separated by design, and a test below pins that they never
     collide over the same description.

  3. THE ADAPTER'S OWN ENVELOPES — describe, availability/refusal, and the
     verify run envelope the ship gate journals — must hold their kinds'
     exact key sets, stay inert, and follow the shared status/exit/error
     and either/or rules wherever the grammar is not at issue.

  What is honestly NOT claimed: samizdat has no replay execution path
  (the replay envelope kind is pinned by fixtures and by the invocation
  counter's rules, which a future replay will depend on); refusal
  catalogues are per-environment (samizdat's refusal points are not
  bbagent's, and the shared surface is the :spi.refusal/ namespace plus
  the shape); :project-changed cannot occur on this side (the input
  coordinate is taken over a throwaway staged copy), though the rule set
  that defines it is kept by the fixtures."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.engine.proc :as proc]
            [samizdat.security.canonical-edn :as cedn]
            [samizdat.security.verification-env :as ve]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

;; ─────────────────────────────────────────────────────────────────────────────
;; The fixture body: byte-identical shared goldens, sha256sum -c format.
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private fixture-directory "test/samizdat/fixtures/spi-v1")

(defn- fixture-text [name]
  (slurp (str fixture-directory "/" name)))

(defn- fixture-files [suffix]
  (->> (fs/list-dir fixture-directory)
       (map str)
       (filter #(str/ends-with? % suffix))
       sort))

(deftest the-fixture-body-matches-its-golden-digests
  ;; The same check `sha256sum -c` makes in the other repository's fixture
  ;; directory, run here so the two bodies cannot drift apart unnoticed.
  ;; The digests are that side's committed goldens; the files are
  ;; byte-identical copies of them.
  (is (= 11 (count (fixture-files ".edn")))
      "the shared fixture body has eleven envelopes")
  (doseq [sha (fixture-files ".sha256")]
    (testing sha
      (let [[digest name] (str/split (str/trim (slurp sha)) #"  ")]
        (is (= digest (cedn/sha-256 (slurp (str fixture-directory "/" name))))
            (str name " drifted from the shared golden digest"))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; This side's implementation of the SPI render grammar. Written from the
;; normative rules (RFC-012 §render), not lifted: independence is what makes
;; byte-identity an agreement rather than a tautology. SHA-256 itself is
;; shared mechanism (cedn/sha-256) — the rendering is the contract.
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private name-characters
  "Characters a keyword or symbol name may be spelled with; anything else
  cannot round-trip, so it is refused rather than printed."
  #"^[A-Za-z0-9*+!_?$%&<>=./#-]*$")

(defn- render-fail! [reason value]
  (throw (ex-info "Value is outside the SPI render domain"
                  {:spi.conformance/error reason
                   :value/type (some-> value class .getName)})))

(defn- named-ok? [text]
  (or (nil? text)
      (and (string? text) (re-matches name-characters text))))

(defn- readable [^Object value]
  (binding [*print-length* nil
            *print-level* nil
            *print-readably* true
            *print-dup* false]
    (pr-str value)))

(declare spi-render)

(defn- rendered-map-entries [value]
  (->> value
       (map (fn [entry]
              [(spi-render (key entry))
               (str (spi-render (key entry)) " " (spi-render (val entry)))]))
       (sort-by first)
       (map second)
       (str/join ", ")))

(defn spi-render
  "The canonical rendering of an inert value, per the shared rules: maps
  sorted ascending by rendered key text, sets by rendered element text,
  vectors and lists in order, elements joined \", \", map entries \" \",
  scalars as plain readable EDN. Everything alive or ambiguous — floats,
  records, metadata, unspellable names, objects — is refused."
  [value]
  (when (and (instance? clojure.lang.IMeta value) (seq (meta value)))
    (render-fail! :metadata value))
  (cond
    (nil? value) "nil"
    (boolean? value) (str value)
    (integer? value) (str value)
    (string? value) (readable value)
    (char? value) (readable value)
    (keyword? value)
    (let [namespace (namespace value) name (name value)]
      (when-not (and (named-ok? namespace) (named-ok? name))
        (render-fail! :keyword-unspellable value))
      (str value))
    (symbol? value)
    (let [namespace (namespace value) name (name value)]
      (when-not (and (named-ok? namespace) (named-ok? name))
        (render-fail! :symbol-unspellable value))
      (str value))
    (record? value) (render-fail! :record value)
    (map? value) (str "{" (rendered-map-entries value) "}")
    (vector? value) (str "[" (str/join ", " (mapv spi-render value)) "]")
    (set? value) (str "#{" (->> value (map spi-render) sort (str/join ", ")) "}")
    (sequential? value) (str "(" (str/join ", " (mapv spi-render value)) ")")
    :else (render-fail! :unsupported-type value)))

(defn spi-coordinate
  "The SPI's own coordinate algorithm: sha256 over the canonical rendering
  of [:spi.coordinate/v1 kind payload]. Domain-separated from the bb4t
  canonical grammar by its tag — pinned below, never assumed."
  [kind payload]
  (when-not (qualified-keyword? kind)
    (render-fail! :kind-not-qualified kind))
  (str "sha256:" (cedn/sha-256 (spi-render [:spi.coordinate/v1 kind payload]))))

(defn spi-environment-coordinate [description]
  (spi-coordinate :spi.environment/description description))

;; ─────────────────────────────────────────────────────────────────────────────
;; The envelope rule set, as this side keeps it. `spi-validate` enforces the
;; full fixture-side contract (including the describe recompute rule over the
;; SPI coordinate grammar and the fixture catalogue's closed refusal set).
;; `shape-validate` enforces every rule that is grammar-independent — what
;; samizdat's own envelopes must hold — with coordinate slots opaque
;; sha256 strings and refusal categories checked for the shared namespace
;; rather than one environment's catalogue.
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private envelope-version 1)

(def ^:private envelope-kinds
  #{:spi.environment/describe
    :spi.environment/availability
    :spi.execution/run
    :spi.execution/replay})

(def ^:private fixture-refusal-categories
  "The other ecosystem's executor's own refusal points — the closed set its
  fixtures and keeper enforce on ITS side. Samizdat's refusal points are
  different by design (RFC-012); the shared surface is the namespace and
  the shape."
  #{:spi.refusal/manager-unavailable
    :spi.refusal/manager-unmeasured
    :spi.refusal/guest-image-unusable
    :spi.refusal/guest-image-digest-mismatch
    :spi.refusal/project-identity
    :spi.refusal/unknown})

(def ^:private worker-statuses #{:completed :timeout :worker-failure})

(def ^:private run-statuses (conj worker-statuses :project-changed))

(def ^:private dispositions #{:terminated})

(def ^:private coordinate-pattern #"^sha256:[0-9a-f]{64}$")

(def ^:private frame-keys #{:spi/version :spi/kind})

(def ^:private kind-keys
  {:spi.environment/describe
   {:required #{:environment/description :environment/coordinate}
    :optional #{}}

   :spi.environment/availability
   {:required #{:environment/available?}
    ;; Which of the pair belongs is the either/or rule, not the key set.
    :optional #{:environment/coordinate :environment/refusal}}

   :spi.execution/run
   {:required #{:run/invocation-index :run/attribution :run/input
                :output/status :output/stdout :output/stderr
                :output/duration-ms :run/disposition}
    :optional #{:output/exit :output/process :output/error}}

   :spi.execution/replay
   {:required #{:replay/invocation-index :replay/invocation-count}
    :optional #{}}})

(defn- rule-fail! [message data]
  (throw (ex-info message (assoc data :spi.conformance/error :envelope-invalid))))

(defn- frame! [envelope]
  (when-not (and (map? envelope)
                 (= envelope-version (:spi/version envelope))
                 (contains? envelope-kinds (:spi/kind envelope)))
    (rule-fail! "Envelope frame is wrong" {:envelope envelope})))

(defn- exact-keys! [envelope {:keys [required optional]}]
  (let [present (set (keys envelope))
        missing (not-empty (vec (remove present required)))
        extra (not-empty (vec (remove (into required optional) present)))]
    (when (or missing extra)
      (rule-fail! "Envelope key set is not its kind's"
                  {:spi/kind (:spi/kind envelope)
                   :missing missing :extra extra})))
  envelope)

(defn- positive-integer! [envelope key]
  (when-not (and (integer? (get envelope key)) (pos? (get envelope key)))
    (rule-fail! (str "Expected a positive integer at " key)
                {:spi/kind (:spi/kind envelope) key (get envelope key)})))

(defn- non-negative-integer! [envelope key]
  (when-not (and (integer? (get envelope key)) (not (neg? (get envelope key))))
    (rule-fail! (str "Expected a non-negative integer at " key)
                {:spi/kind (:spi/kind envelope) key (get envelope key)})))

(defn- coordinate-slot! [envelope key]
  (when-not (and (string? (get envelope key))
                 (re-matches coordinate-pattern (get envelope key)))
    (rule-fail! (str "Expected a sha256 coordinate at " key)
                {:spi/kind (:spi/kind envelope) key (get envelope key)})))

(defn- refusal-shape! [refusal]
  (when-not (and (map? refusal)
                 (= #{:refusal/category :refusal/reason} (set (keys refusal)))
                 (keyword? (:refusal/category refusal))
                 (string? (:refusal/reason refusal))
                 (seq (:refusal/reason refusal)))
    (rule-fail! "A refusal names a category and a reason" {:refusal refusal}))
  refusal)

(defn- availability-either-or! [envelope]
  (let [available? (:environment/available? envelope)]
    (when-not (boolean? available?)
      (rule-fail! "Availability must be a boolean" {:spi/kind (:spi/kind envelope)}))
    (if available?
      (do (coordinate-slot! envelope :environment/coordinate)
          (when (contains? envelope :environment/refusal)
            (rule-fail! "An available environment carries no refusal"
                        {:spi/kind (:spi/kind envelope)})))
      (do (refusal-shape! (:environment/refusal envelope))
          (when (contains? envelope :environment/coordinate)
            (rule-fail! "A refused environment carries no coordinate"
                        {:spi/kind (:spi/kind envelope)})))))
  envelope)

(defn- stream-shape! [envelope key]
  (let [stream (get envelope key)]
    (when-not (and (map? stream)
                   (= #{:stream/text :stream/bytes :stream/truncated?}
                      (set (keys stream)))
                   (string? (:stream/text stream))
                   (integer? (:stream/bytes stream))
                   (not (neg? (:stream/bytes stream)))
                   (boolean? (:stream/truncated? stream)))
      (rule-fail! "A stream is text, a true byte count, and a flag"
                  {:spi/kind (:spi/kind envelope) key stream})))
  envelope)

(defn- input-shape! [envelope]
  (let [input (:run/input envelope)]
    (when-not (map? input)
      (rule-fail! "A run names its input or admits it moved"
                  {:spi/kind (:spi/kind envelope) :run/input input}))
    (cond
      (= #{:input/coordinate} (set (keys input)))
      (when-not (re-matches coordinate-pattern (:input/coordinate input))
        (rule-fail! "An input coordinate is a sha256 string"
                    {:spi/kind (:spi/kind envelope) :run/input input}))

      (= #{:input/stability} (set (keys input)))
      (when-not (= :input/project-changed (:input/stability input))
        (rule-fail! "Unknown input stability" {:run/input input}))

      :else (rule-fail! "A run input is :input/coordinate or :input/stability"
                        {:run/input input})))
  envelope)

(defn- attribution-shape! [envelope]
  (let [attribution (:run/attribution envelope)]
    (when-not (and (map? attribution)
                   (= #{:environment/coordinate :environment/type}
                      (set (keys attribution)))
                   (keyword? (:environment/type attribution)))
      (rule-fail! "A run is attributed by coordinate and type"
                  {:run/attribution attribution})))
  (coordinate-slot! (:run/attribution envelope) :environment/coordinate)
  envelope)

(defn- run-semantics! [envelope]
  (let [status (:output/status envelope)]
    (when-not (contains? run-statuses status)
      (rule-fail! "Unknown run status" {:output/status status}))
    (positive-integer! envelope :run/invocation-index)
    (non-negative-integer! envelope :output/duration-ms)
    (when-not (contains? dispositions (:run/disposition envelope))
      (rule-fail! "Unknown disposition" {:run/disposition (:run/disposition envelope)}))
    (attribution-shape! envelope)
    (input-shape! envelope)
    (let [changed? (= :project-changed status)
          says-changed? (= :input/project-changed
                           (:input/stability (:run/input envelope)))]
      (when (not= changed? says-changed?)
        (rule-fail! "A changed project and a moved input are the same fact"
                    {:output/status status :run/input (:run/input envelope)})))
    (stream-shape! envelope :output/stdout)
    (stream-shape! envelope :output/stderr)
    (if (= :completed status)
      (when-not (integer? (:output/exit envelope))
        (rule-fail! "A completed run carries its exit"
                    {:spi/kind (:spi/kind envelope)}))
      (when (contains? envelope :output/exit)
        (rule-fail! "Only a completed run carries an exit"
                    {:output/status status})))
    (if (= :project-changed status)
      (let [process (:output/process envelope)]
        (when-not (and (map? process)
                       (contains? worker-statuses (:process/status process)))
          (rule-fail! "A changed project demotes its process outcome"
                      {:output/process process})))
      (when (contains? envelope :output/process)
        (rule-fail! "Only a changed project demotes its process outcome"
                    {:spi/kind (:spi/kind envelope)})))
    (when (contains? envelope :output/error)
      (when-not (and (string? (:output/error envelope))
                     (= :worker-failure status))
        (rule-fail! "A run error belongs to a worker failure"
                    {:output/status status}))))
  envelope)

(defn- replay-semantics! [envelope]
  (positive-integer! envelope :replay/invocation-index)
  (non-negative-integer! envelope :replay/invocation-count)
  (when (> (:replay/invocation-index envelope)
           (:replay/invocation-count envelope))
    (rule-fail! "A replay's recorded index cannot exceed the invocation count"
                {:replay/invocation-index (:replay/invocation-index envelope)
                 :replay/invocation-count (:replay/invocation-count envelope)}))
  envelope)

(defn shape-validate
  "Every envelope rule that does not depend on which coordinate grammar a
  slot speaks: frame, exact key set, inertness, and each kind's semantics
  with coordinate slots as opaque sha256 strings and the refusal category
  checked for the shared :spi.refusal/ namespace."
  [envelope]
  (frame! envelope)
  (let [{:keys [required optional]} (kind-keys (:spi/kind envelope))]
    (exact-keys! (doto envelope (spi-render))
                 {:required (into frame-keys required) :optional optional}))
  (case (:spi/kind envelope)
    :spi.environment/describe
    (do (when-not (and (map? (:environment/description envelope))
                       (seq (:environment/description envelope)))
          (rule-fail! "A description is a non-empty map" {:envelope envelope}))
        (coordinate-slot! envelope :environment/coordinate))
    :spi.environment/availability
    (do (availability-either-or! envelope)
        (when-let [category (get-in envelope
                                   [:environment/refusal :refusal/category])]
          (when-not (= "spi.refusal" (namespace category))
            (rule-fail!
             "A refusal category lives in the shared :spi.refusal namespace"
             {:refusal/category category}))))
    :spi.execution/run (run-semantics! envelope)
    :spi.execution/replay (replay-semantics! envelope))
  envelope)

(defn spi-validate
  "The FULL fixture-side rule set — what the other repository's keeper
  enforces on its own envelopes — including the describe recompute rule
  over the SPI coordinate grammar and the fixture catalogue's closed
  refusal set. Used on the shared fixtures and on forged negatives."
  [envelope]
  (shape-validate envelope)
  (case (:spi/kind envelope)
    :spi.environment/describe
    (let [actual (:environment/coordinate envelope)
          expected (spi-environment-coordinate
                    (:environment/description envelope))]
      (when-not (= expected actual)
        (rule-fail! "The coordinate does not name the description it sits beside"
                    {:environment/coordinate actual :expected expected})))
    :spi.environment/availability
    (when-let [category (get-in envelope
                                [:environment/refusal :refusal/category])]
      (when-not (contains? fixture-refusal-categories category)
        (rule-fail! "A refusal names a known category"
                    {:refusal/category category})))
    nil)
  envelope)

(defn- run-envelope [fields]
  (shape-validate
   (assoc (into {} (remove (fn [[_ value]] (nil? value))) fields)
          :spi/version envelope-version
          :spi/kind :spi.execution/run)))

(defn replay-envelope [invocation-index invocation-count]
  (shape-validate {:spi/version envelope-version
                   :spi/kind :spi.execution/replay
                   :replay/invocation-index invocation-index
                   :replay/invocation-count invocation-count}))

;; ─────────────────────────────────────────────────────────────────────────────
;; The ported evidence inputs. Every value below is visible in the fixture
;; bytes it must render to — stream texts, byte counts, durations, indexes,
;; coordinates — so the fixtures themselves are the source of truth.
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private hex64 (apply str (repeat 4 "0123456789abcdef")))
(def ^:private stub-input-coordinate (str "sha256:" hex64))

(def ^:private stub-description
  {:executor/type :test/stub
   :executor/network :none
   :executor/version "0.0.0-test"})

(def ^:private stub-reference
  {:environment/coordinate (spi-environment-coordinate stub-description)
   :environment/type :test/stub})

(def ^:private completed-result
  {:status :completed :exit 0 :duration-ms 12
   :stdout "ok\n" :stdout/bytes 3 :stdout/truncated? false
   :stderr "" :stderr/bytes 0 :stderr/truncated? false
   :worker/disposition :terminated
   :project/input-stable? true
   :project/input-coordinate stub-input-coordinate})

(def ^:private timeout-result
  {:status :timeout :duration-ms 4003
   :stdout "" :stdout/bytes 0 :stdout/truncated? false
   :stderr "" :stderr/bytes 0 :stderr/truncated? false
   :worker/disposition :terminated
   :project/input-stable? true
   :project/input-coordinate stub-input-coordinate})

(def ^:private worker-failure-result
  {:status :worker-failure :duration-ms 61
   :stdout "" :stdout/bytes 0 :stdout/truncated? false
   :stderr "bbagent-worker: prelude failed: no/such/dir\n"
   :stderr/bytes 44 :stderr/truncated? false
   :worker/disposition :terminated
   :worker/error "bbagent-worker: prelude failed: no/such/dir"
   :project/input-stable? true
   :project/input-coordinate stub-input-coordinate})

(def ^:private project-changed-result
  {:status :completed :exit 0 :duration-ms 4021
   :stdout "changed mid-run\n" :stdout/bytes 16 :stdout/truncated? false
   :stderr "" :stderr/bytes 0 :stderr/truncated? false
   :worker/disposition :terminated
   :project/input-stable? false})

(def ^:private truncated-result
  {:status :completed :exit 0 :duration-ms 812
   :stdout "yes: abcdefghij\n" :stdout/bytes 220000 :stdout/truncated? true
   :stderr "yes: ABCDEFGHIJ\n" :stderr/bytes 220000 :stderr/truncated? true
   :worker/disposition :terminated
   :project/input-stable? true
   :project/input-coordinate stub-input-coordinate})

(defn- worker-run-envelope
  "The projection of a worker result into a run envelope, per the ported
  result rules: stable input keeps status, exit and input coordinate; a
  moved project becomes :project-changed, drops the coordinate and demotes
  its process outcome; an exit survives only when the workload exited; an
  unknown worker status fails closed."
  [result attribution index]
  (let [status (:status result)]
    (when-not (contains? worker-statuses status)
      (rule-fail! "Worker result has an unknown status" {:status status}))
    (let [stable? (true? (:project/input-stable? result))
          exit (when (contains? result :exit) (:exit result))
          stream (fn [name]
                   {:stream/text (str (get result (keyword name)))
                    :stream/bytes (get result (keyword name "bytes"))
                    :stream/truncated? (boolean (get result
                                                    (keyword name "truncated?")))})]
      (run-envelope
       {:run/invocation-index index
        :run/attribution attribution
        :run/input (if stable?
                     {:input/coordinate (:project/input-coordinate result)}
                     {:input/stability :input/project-changed})
        :output/status (if stable? status :project-changed)
        :output/exit (when (and stable? (some? exit)) exit)
        :output/process (when-not stable?
                          (cond-> {:process/status status}
                            (some? exit) (assoc :process/exit exit)))
        :output/stdout (stream "stdout")
        :output/stderr (stream "stderr")
        :output/duration-ms (:duration-ms result)
        :run/disposition (:worker/disposition result)
        :output/error (when (= :worker-failure status)
                        (some-> (:worker/error result) str not-empty))}))))

(defn- conforms
  "The conformance relation, three ways: this side's renderer produces the
  fixture's exact bytes from the input, the envelope passes the full
  fixture-side rule set, and the parsed fixture re-renders to itself."
  [name envelope]
  (testing (str name " renders byte-identically to the shared fixture")
    (is (= (fixture-text name) (str (spi-render envelope) "\n"))
        (pr-str envelope)))
  (testing (str name " passes the full fixture-side rule set")
    (is (spi-validate envelope)))
  (testing (str name " round-trips through EDN to itself")
    (is (= (fixture-text name)
           (str (spi-render (edn/read-string (fixture-text name))) "\n")))))

;; ─── describe ────────────────────────────────────────────────────────────────

(deftest the-render-grammar-is-order-free-and-inert-only
  (is (= "{:a 1, :b {:x \"s\", :y [1, 2]}, :c #{:p, :q}}"
         (spi-render {:b {:y [1 2] :x "s"} :a 1 :c #{:q :p}})
         (spi-render (into (sorted-map) {:a 1 :c #{:p :q} :b {:x "s" :y [1 2]}}))))
  (is (= "\"ok\\n\"" (spi-render "ok\n"))
      "a newline inside a string is bytes on the wire, not a line break")
  (is (= "#{:p, :q}" (spi-render #{:p :q})))
  (is (= "(1, 2)" (spi-render '(1 2))))
  (doseq [value [1.5 (Object.) (with-meta [1] {:m 1}) (keyword "not a name")]]
    (is (thrown? clojure.lang.ExceptionInfo (spi-render value)))))

(deftest the-stub-and-smolvm-describe-fixtures-conform
  (conforms "describe-stub.edn"
            (shape-validate {:spi/version 1 :spi/kind :spi.environment/describe
                             :environment/description stub-description
                             :environment/coordinate
                             (spi-environment-coordinate stub-description)}))
  ;; The smolvm description map is fully visible in the fixture bytes; its
  ;; coordinate must be recomputable from it by this side's implementation.
  (let [envelope (edn/read-string (fixture-text "describe-smolvm.edn"))]
    (conforms "describe-smolvm.edn" envelope)
    (is (= (spi-environment-coordinate (:environment/description envelope))
           (:environment/coordinate envelope)))))

(deftest a-forged-describe-envelope-is-refused
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"does not name the description"
       (spi-validate
        (-> (edn/read-string (fixture-text "describe-stub.edn"))
            (assoc :environment/coordinate
                   "sha256:0000000000000000000000000000000000000000000000000000000000000000")))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"key set"
       (spi-validate
        (-> (edn/read-string (fixture-text "describe-stub.edn"))
            (assoc :environment/extra 1))))))

;; ─── refusal / availability ──────────────────────────────────────────────────

(deftest the-availability-and-refusal-fixtures-conform
  (conforms "availability-available.edn"
            (shape-validate {:spi/version 1 :spi/kind :spi.environment/availability
                             :environment/available? true
                             :environment/coordinate
                             (:environment/coordinate stub-reference)}))
  (conforms "availability-refused-manager.edn"
            (shape-validate
             {:spi/version 1 :spi/kind :spi.environment/availability
              :environment/available? false
              :environment/refusal
              {:refusal/category :spi.refusal/manager-unavailable
               :refusal/reason
               "No machine manager is available to run project commands"}}))
  (conforms "availability-refused-image-digest.edn"
            (shape-validate
             {:spi/version 1 :spi/kind :spi.environment/availability
              :environment/available? false
              :environment/refusal
              {:refusal/category :spi.refusal/guest-image-digest-mismatch
               :refusal/reason
               "The guest image archive does not match the digest this host pinned"}})))

(deftest availability-says-exactly-one-thing
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"carries no refusal"
       (shape-validate
        {:spi/version 1 :spi/kind :spi.environment/availability
         :environment/available? true
         :environment/coordinate stub-input-coordinate
         :environment/refusal
         {:refusal/category :spi.refusal/unknown :refusal/reason "both"}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"carries no coordinate"
       (shape-validate
        {:spi/version 1 :spi/kind :spi.environment/availability
         :environment/available? false
         :environment/refusal
         {:refusal/category :spi.refusal/unknown :refusal/reason "why"}
         :environment/coordinate stub-input-coordinate}))))

;; ─── verify: the run fixtures ─────────────────────────────────────────────────

(deftest the-run-fixtures-conform-through-the-ported-projection
  (conforms "run-completed.edn" (worker-run-envelope completed-result stub-reference 1))
  (conforms "run-timeout.edn" (worker-run-envelope timeout-result stub-reference 2))
  (conforms "run-worker-failure.edn"
            (worker-run-envelope worker-failure-result stub-reference 3))
  (conforms "run-project-changed.edn"
            (worker-run-envelope project-changed-result stub-reference 4))
  (conforms "run-truncated.edn"
            (worker-run-envelope truncated-result stub-reference 5)))

(deftest an-exit-survives-only-when-the-workload-exited
  (is (nil? (:output/exit (worker-run-envelope timeout-result stub-reference 1))))
  (is (nil? (:output/exit
             (worker-run-envelope worker-failure-result stub-reference 1))))
  (is (= 0 (:output/exit (worker-run-envelope completed-result stub-reference 1))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Only a completed run"
       (run-envelope
        {:run/invocation-index 1
         :run/attribution stub-reference
         :run/input {:input/coordinate stub-input-coordinate}
         :output/status :timeout
         :output/exit 124
         :output/stdout {:stream/text "" :stream/bytes 0 :stream/truncated? false}
         :output/stderr {:stream/text "" :stream/bytes 0 :stream/truncated? false}
         :output/duration-ms 1
         :run/disposition :terminated}))))

(deftest a-changed-project-cannot-carry-a-coordinate-or-look-anchored
  (let [envelope (worker-run-envelope project-changed-result stub-reference 1)]
    (is (= :project-changed (:output/status envelope)))
    (is (= {:input/stability :input/project-changed} (:run/input envelope)))
    (is (nil? (:output/exit envelope)))
    (is (= {:process/status :completed :process/exit 0}
           (:output/process envelope))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"same fact"
       (run-envelope
        (assoc (worker-run-envelope project-changed-result stub-reference 1)
               :run/input {:input/coordinate stub-input-coordinate})))))

(deftest truncated-streams-report-the-true-size
  (let [envelope (worker-run-envelope truncated-result stub-reference 1)]
    (is (= 220000 (-> envelope :output/stdout :stream/bytes)))
    (is (= 220000 (-> envelope :output/stderr :stream/bytes)))
    (is (true? (-> envelope :output/stdout :stream/truncated?)))))

;; ─── replay-invocation ────────────────────────────────────────────────────────

(deftest the-replay-fixture-conforms-and-the-counter-rules-hold
  (conforms "replay-restored.edn" (replay-envelope 1 1))
  (testing "a faithful replay never moves past the counter that witnessed it"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot exceed"
                          (replay-envelope 2 1))))
  (testing "an invocation index starts at one"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (replay-envelope 0 0)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; The M2 adapter's own envelopes: describe, refusal, verify.
;; Coordinate slots here speak the bb4t canonical grammar
;; (:bb4t/execution-environment) — the seam bb4t's own describe uses — and
;; that is pinned as a deliberate choice below, not left to look accidental.
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private fixture-set
  (delay
   (or (some-> (io/resource "samizdat/fixtures/execution_env_edn.edn")
               slurp edn/read-string)
       (throw (ex-info "the EDN SPI fixture set is missing from the classpath"
                       {:fixture "samizdat/fixtures/execution_env_edn.edn"})))))

(def ^:private description-golden
  (delay (some #(when (= :environment-description-shape (:fixture/id %)) %)
               (:regression-vectors @fixture-set))))

(deftest the-environment-description-is-the-golden-shape
  ;; The live description is pinned to the shipped golden: any change to it
  ;; in src moves the digest and this fails, which is what a
  ;; cross-repository name is for.
  (let [golden @description-golden]
    (is (some? golden) "the description golden vector is missing")
    (is (map? ve/environment-description))
    (is (= (:value golden) ve/environment-description)
        "the adapter's description is no longer the fixture's shape")
    (is (= (:coordinate golden) (ve/environment-coordinate))
        "the description's digest moved relative to the shipped golden"))
  (is (keyword? (:executor/type ve/environment-description)))
  (is (= :verify-only (:executor/mode ve/environment-description)))
  (is (= #{:describe :verify} (:executor/operations ve/environment-description))
      "verify-only: no third operation exists to list"))

(deftest describe-envelope-holds-its-kind-and-cannot-misattribute
  (let [envelope (ve/describe-envelope)]
    (is (shape-validate envelope))
    (is (= 1 (:spi/version envelope)))
    (is (= (:environment/coordinate envelope) (ve/environment-coordinate))
        "the coordinate beside the description is not the one recomputed from it")
    (testing "a description that cannot say what implements it is refused"
      (with-redefs [ve/environment-description {:executor/mode :verify-only}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names its type"
                              (ve/describe-envelope)))))))

(deftest every-controller-refusal-is-catalogued-and-inert
  (doseq [reason [:not-linux :no-bwrap :no-prlimit :sandbox-unavailable
                  :no-verifier-executable :no-verifiable-test :something-else]]
    (testing (str reason)
      (let [envelope (ve/refusal-envelope reason)
            category (get-in envelope [:environment/refusal :refusal/category])
            reason-text (get-in envelope
                                [:environment/refusal :refusal/reason])]
        (is (shape-validate envelope))
        (is (re-matches #":spi.refusal/[a-z-]+" (str category)))
        (is (string? reason-text))
        (is (seq reason-text))
        (is (not (str/includes? reason-text "/"))
            "a refusal reason carried a host path across the boundary")
        ;; The mapping is decided once: the same reason reaches the same
        ;; category every time it is asked.
        (is (= category
               (get-in (ve/refusal-envelope reason)
                       [:environment/refusal :refusal/category])))
        (if (= reason :something-else)
          (is (= :spi.refusal/unknown category)
              "an uncatalogued reason must refuse as unknown, not be guessed at")
          (is (not= :spi.refusal/unknown category)))))))

(deftest availability-answers-exactly-one-payload
  (with-redefs [ve/available? (fn [] true)]
    (let [envelope (ve/availability-envelope)]
      (is (true? (:environment/available? envelope)))
      (is (= (ve/environment-coordinate)
             (:environment/coordinate envelope)))
      (is (not (contains? envelope :environment/refusal)))
      (is (shape-validate envelope))))
  (with-redefs [ve/available? (fn [] false)
                ve/unavailable-reason (fn [] :no-bwrap)]
    (let [envelope (ve/availability-envelope)]
      (is (false? (:environment/available? envelope)))
      (is (not (contains? envelope :environment/coordinate)))
      (is (shape-validate envelope)))))

(def ^:private synthetic-attribution
  {:environment/coordinate (ve/environment-coordinate)
   :environment/type :samizdat/bwrap-verification-env})

(defn- synthetic-run [over]
  (merge {:invocation-index 7
          :timeout? false
          :exit 0
          :duration-ms 5
          :input-coordinate "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          :attribution synthetic-attribution
          :stdout {:text "ok\n" :bytes 3 :truncated? false}
          :stderr {:text "" :bytes 0 :truncated? false}}
         over))

(deftest verify-envelope-projects-each-status-honestly
  (testing "a completed run carries its exit"
    (let [envelope (ve/verify-envelope (synthetic-run {}))]
      (is (some? envelope))
      (is (shape-validate envelope))
      (is (= :completed (:output/status envelope)))
      (is (= 0 (:output/exit envelope)))
      (is (= 7 (:run/invocation-index envelope)))
      (is (= synthetic-attribution (:run/attribution envelope)))
      (is (= {:input/coordinate
              "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
             (:run/input envelope)))
      (is (= {:stream/text "ok\n" :stream/bytes 3 :stream/truncated? false}
             (:output/stdout envelope)))))
  (testing "a timed-out run invents no exit"
    (let [envelope (ve/verify-envelope (synthetic-run {:timeout? true}))]
      (is (= :timeout (:output/status envelope)))
      (is (not (contains? envelope :output/exit)))))
  (testing "a spawn failure carries the fixed authored error, never the exception"
    (let [envelope (ve/verify-envelope
                    (synthetic-run {:exit nil
                                    :spawn-failure "boom /host/path"}))]
      (is (= :worker-failure (:output/status envelope)))
      (is (= "verification environment run failed" (:output/error envelope)))
      (is (not (str/includes? (pr-str envelope) "/host/path")))))
  (testing "an absent exit and a present-but-nil exit are the same refusal"
    (is (= (ve/verify-envelope (synthetic-run {:exit nil}))
           (ve/verify-envelope (dissoc (synthetic-run {}) :exit)))))
  (testing "a result that never spawned has no envelope"
    (is (nil? (ve/verify-envelope {:green? false :unavailable? true
                                   :reason :no-bwrap :output "refused"})))
    (is (nil? (ve/verify-envelope {:green? false :timeout? false :output "stage"})))
    (is (nil? (ve/verify-envelope "not even a map"))))
  (testing "an invocation index starts at one"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"starts at one"
                          (ve/verify-envelope
                           (synthetic-run {:invocation-index 0}))))))

(deftest the-adapter-s-envelopes-are-inert-under-this-sides-keeper
  (doseq [envelope [(ve/describe-envelope)
                    (ve/availability-envelope)
                    (ve/refusal-envelope :sandbox-unavailable)
                    (ve/verify-envelope (synthetic-run {}))
                    (ve/verify-envelope (synthetic-run {:timeout? true}))]]
    (is (vector? (cedn/canonical-tree envelope))
        "an envelope the adapter emits is not inert canonical EDN")))

;; ─── the invocation counter ──────────────────────────────────────────────────

(def ^:private benign-test-ns
  "(ns ve.spi-test
  (:require [clojure.test :refer [deftest is]]))
(deftest one-is-one (is (= 1 1)))
")

(defn- seed-project! [root]
  (fs/create-dirs (str root "/src/ve"))
  (fs/create-dirs (str root "/test/ve"))
  (spit (str root "/deps.edn") "{:paths [\"src\" \"test\"]}\n")
  (spit (str root "/src/ve/core.clj") "(ns ve.core)\n"))

(defn- with-project [f]
  (let [root (str (fs/create-temp-dir {:prefix "spi-project-"}))]
    (try
      (seed-project! root)
      (spit (str root "/test/ve/spi_test.clj") benign-test-ns)
      (f root)
      (finally (fs/delete-tree root)))))

(deftest the-invocation-counter-moves-only-for-real-spawns
  (testing "a refused request never claims an index"
    (with-redefs [ve/available? (fn [] false)
                  ve/unavailable-reason (fn [] :not-linux)]
      (let [spawned (atom 0)
            before (ve/invocation-count)]
        (with-redefs [proc/run (fn [& _] (swap! spawned inc) {:exit 0})]
          (let [r (ve/run "/nonexistent" ["test/ve/spi_test.clj"] 1000)]
            (is (not (:green? r)))
            (is (:unavailable? r))
            (is (zero? @spawned) "nothing was spawned for a refused request")
            (is (= before (ve/invocation-count))
                "a refusal moved the invocation counter"))))))
  (testing "an unresolvable verifier refuses without claiming"
    (with-redefs [ve/available? (fn [] true)
                  ve/resolve-verifier (fn [] nil)]
      (let [before (ve/invocation-count)]
        (with-project
         (fn [root]
           (let [r (ve/run root ["test/ve/spi_test.clj"] 1000)]
             (is (not (:green? r)))
             (is (= before (ve/invocation-count)))))))))
  (testing "a staging failure never claims an index"
    (with-redefs [ve/available? (fn [] true)
                  ve/resolve-verifier (fn [] "/nonexistent/jolt")]
      (with-project
       (fn [root]
         (let [before (ve/invocation-count)]
           (with-redefs [fs/create-temp-dir
                         (fn [& _] (throw (ex-info "no temp dir" {})))]
             (let [r (ve/run root ["test/ve/spi_test.clj"] 1000)]
               (is (not (:green? r)))
               (is (nil? (:invocation-index r)))
               (is (= before (ve/invocation-count))))))))))
  (testing "the counter is claimed immediately before the spawn, and the
            index read when the run returns is that run's index"
    (with-redefs [ve/available? (fn [] true)
                  ve/resolve-verifier (fn [] "/nonexistent/jolt")]
      (with-project
       (fn [root]
         (let [at-spawn (atom nil)
               before (ve/invocation-count)]
           (with-redefs [proc/run
                         (fn [& _]
                           ;; What the environment's counter says the
                           ;; moment the spawn is attempted: claimed already.
                           (reset! at-spawn (ve/invocation-count))
                           {:exit 0})]
             (let [r (ve/run root ["test/ve/spi_test.clj"] 60000)]
               (is (pos? (:invocation-index r)))
               (is (= (inc before) (:invocation-index r) (ve/invocation-count)
                      @at-spawn)
                   "the index must be claimed before the spawn and be the
                   counter's value when the run returns")
               (is (:green? r))
               (is (some? (ve/verify-envelope r))
                   "a spawned run has an envelope a replay could name")))))))))

;; ─── the private-copy coordinate (RFC-012) ───────────────────────────────────

(defn- seed-workspace! [root]
  (fs/create-dirs (str root "/src/ve"))
  (fs/create-dirs (str root "/test/ve"))
  (fs/create-dirs (str root "/.git/objects"))
  (fs/create-dirs (str root "/nested/target"))
  (spit (str root "/deps.edn") "{:paths [\"src\" \"test\"]}\n")
  (spit (str root "/src/ve/core.clj") "(ns ve.core)\n(defn two [] 2)\n")
  (spit (str root "/test/ve/core_test.clj") "(ns ve.core-test)\n")
  (spit (str root "/.git/config") "[core]\n")
  (spit (str root "/nested/target/junk") "build artifact\n")
  (fs/create-sym-link (str root "/a-link") "deps.edn"))

(defn- workspace-fixture []
  (let [root (str (fs/create-temp-dir {:prefix "spi-ws-"}))]
    (seed-workspace! root)
    root))

(deftest the-manifest-names-what-the-verifier-would-see
  (let [root (workspace-fixture)]
    (try
      (let [manifest (ve/workspace-manifest root)
            entries (vec (:workspace/entries manifest))
            paths (mapv :path entries)]
        (is (= [".cache" ".cpcache" ".git" "node_modules" "target"]
               (:workspace/exclusions manifest))
            "the copy's exclusions are recorded, sorted")
        (is (= paths (sort paths)) "entries are sorted ascending by path")
        (is (= "a-link" (first paths)))
        (is (= {:path "a-link" :kind :link :target "deps.edn"}
               (first entries))
            "a link is recorded as a link, its target read never followed")
        (is (not (some #(str/starts-with? % ".git") paths))
            "an excluded name at the root is not in the manifest")
        (is (not (some #(str/starts-with? % "nested/target") paths))
            "an excluded name at depth is not in the manifest")
        (is (= {:path "nested" :kind :directory}
               (some #(when (= "nested" (:path %)) %) entries)))
        (is (= {:path "src" :kind :directory}
               (some #(when (= "src" (:path %)) %) entries)))
        (is (= {:path "src/ve/core.clj" :kind :file :bytes 29
                :digest (str "sha256:"
                             (cedn/sha-256-path (str root "/src/ve/core.clj")))}
               (some #(when (= "src/ve/core.clj" (:path %)) %) entries))
            "a file carries its true byte size and content digest"))
      (finally (fs/delete-tree root)))))

(deftest the-private-copy-names-the-same-input-as-the-authoritative-tree
  ;; THE private-copy property: the manifest of any tree equals the manifest
  ;; of its private copy, because the copy applies the same name-level
  ;; exclusions. The verifier ran against the copy; the coordinate a second
  ;; repository checks therefore names the same thing the root would.
  (let [root (workspace-fixture)]
    (try
      (let [stage (str (fs/create-temp-dir {:prefix "samizdat-verify-"}))]
        (try
          (ve/build-environment stage root ["test/ve/core_test.clj"])
          (is (fs/exists? (str stage "/workspace/deps.edn")
                          {:nofollow-links true}))
          (is (fs/sym-link? (str stage "/workspace/a-link"))
              "a link is recreated as a link in the copy, never followed")
          (is (= (ve/input-coordinate root)
                 (ve/input-coordinate (str stage "/workspace")))
              "the copy's coordinate names a different input than the root's")
          (finally (fs/delete-tree stage))))
      (finally (fs/delete-tree root)))))

(deftest the-input-coordinate-follows-bytes-not-names
  (testing "byte-identical trees name one input; a one-byte difference names another"
    (let [one (workspace-fixture)
          two (workspace-fixture)]
      (try
        (is (= (ve/input-coordinate one) (ve/input-coordinate two))
            "byte-identical trees named different inputs")
        (spit (str two "/src/ve/core.clj") "(ns ve.core)\n(defn two [] 3)\n")
        (is (not= (ve/input-coordinate one) (ve/input-coordinate two))
            "a one-byte difference named the same input")
        (finally (fs/delete-tree one) (fs/delete-tree two)))))
  (testing "writing under an excluded name does not move the input"
    (let [root (workspace-fixture)]
      (try
        (let [before (ve/input-coordinate root)]
          (spit (str root "/.git/newly-added") "x")
          (spit (str root "/nested/target/junk") "different junk\n")
          (is (= before (ve/input-coordinate root))
              "an excluded name moved the input coordinate"))
        (finally (fs/delete-tree root))))))

;; ─── the journal envelope ─────────────────────────────────────────────────────

(deftest the-journals-ship-verify-row-carries-envelope-data-that-survives
  ;; The ship gate journals the run envelope (and each refusal envelope)
  ;; into its :ship-verify rows. The journal writes values as JSON,
  ;; degrading to pr-str with a warning when data.json cannot write a
  ;; value; envelopes must take the ordinary path and read back parseable.
  ;; (Key spelling in the JSON is this runtime's data.json behaviour —
  ;; namespaced keywords serialize by name — which is the journal's
  ;; business, not the envelope's; the VALUES must survive.)
  (let [conn (db/open! ":memory:")]
    (try
      (let [rid (runs/start-run! conn {:problem "spi conformance" :beam-width 1})
            run-envelope (ve/verify-envelope (synthetic-run {}))
            refusal-envelope (ve/refusal-envelope :no-verifiable-test)
            row (fn [data] {:branch-id "B1" :turn 3 :data data})]
        (is (str/includes? (json/write-str run-envelope)
                           (:environment/coordinate synthetic-attribution))
            "the run envelope is not writable as ordinary JSON")
        (is (str/includes? (json/write-str refusal-envelope)
                           "nothing-verifiable")
            "the refusal envelope is not writable as ordinary JSON")
        (journal/note! conn rid :ship-verify
                       (row {:ran true :green true :timeout false
                             :blocked false
                             :verify-env (ve/coordinate)
                             :envelope run-envelope}))
        (journal/note! conn rid :ship-verify
                       (row {:ran false :blocked true
                             :why :no-test-among-changed
                             :refusal refusal-envelope}))
        (let [rows (->> (journal/events-since conn rid 0)
                        (filter #(= "ship-verify" (:kind %))))
              [ran refused] (map #(json/read-str (:data %)) rows)]
          (is (= 2 (count rows))
              "the run-started row is not a ship-verify row")
          (is (= "B1" (:branch_id (first rows)))
              "the row's own branch column")
          (is (= (:environment/coordinate synthetic-attribution)
                 (get-in ran ["envelope" "attribution" "coordinate"]))
              "the journaled envelope's attribution did not survive the record")
          (is (= 7 (get-in ran ["envelope" "invocation-index"])))
          (is (= (ve/coordinate) (get-in ran ["verify-env"])))
          (is (= "availability" (get-in refused ["refusal" "kind"])))
          (is (= "nothing-verifiable"
                 (get-in refused ["refusal" "refusal" "category"]))))
        ;; And the whole row is inert canonical EDN, so a coordinate can be
        ;; taken over what the record actually holds.
        (is (vector?
             (cedn/canonical-tree
              {:ran true :green true :timeout false :blocked false
               :verify-env (ve/coordinate) :envelope run-envelope}))))
      (finally (try (db/close conn) (catch Throwable _ nil))))))

;; ─── the honest divergences ──────────────────────────────────────────────────

(deftest the-two-coordinate-grammars-are-domain-separated-by-design
  ;; Samizdat's envelope coordinate slots speak the bb4t canonical grammar —
  ;; the seam bb4t's own execution describe computes and a bb4t keeper can
  ;; check. The SPI's own coordinate grammar (the one the fixture describe
  ;; envelopes carry) is a DIFFERENT domain: over the same description the
  ;; two must never agree, or one of them is not naming what it claims.
  (let [description ve/environment-description]
    (is (= (cedn/coordinate :bb4t/execution-environment description)
           (ve/environment-coordinate)))
    (is (not= (spi-environment-coordinate description)
              (ve/environment-coordinate))
        "the SPI coordinate and the bb4t coordinate collided over one description")
    (is (re-matches #"sha256:[0-9a-f]{64}"
                    (spi-environment-coordinate description)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (ve/environment-coordinate)))))

(deftest refusal-catalogues-are-per-environment-while-the-namespace-is-shared
  ;; Samizdat's refusal points are its own (a Linux-only sandbox refuses for
  ;; reasons a VM manager never has); the shared surface is the
  ;; :spi.refusal/ namespace and the refusal shape, pinned above. This pins
  ;; the difference rather than letting it look like an oversight.
  (let [samizdat-categories
        (into #{}
              (map #(get-in (ve/refusal-envelope %)
                            [:environment/refusal :refusal/category]))
              [:not-linux :no-bwrap :no-prlimit :sandbox-unavailable
               :no-verifier-executable :no-verifiable-test])]
    (is (= 6 (count samizdat-categories))
        "each controller reason catalogues distinctly")
    (is (contains? fixture-refusal-categories :spi.refusal/unknown)
        "the unknown bucket is shared")
    (is (empty? (cset/intersection samizdat-categories
                                   (disj fixture-refusal-categories
                                         :spi.refusal/unknown)))
        "samizdat borrowed a specific refusal category verbatim; catalogues
        are per-environment or they mean nothing")))
