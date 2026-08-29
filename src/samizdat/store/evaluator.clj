;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.evaluator
  "Append-only storage for bounded evaluator computations and receipts."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [samizdat.store.db :as db]
            [samizdat.store.inference :as inference]))

(defn canonical
  "Canonical receipt data. Host objects, lazy values and ambiguous numbers are
  refused rather than rendered into a receipt that cannot round-trip."
  [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x)) x
    (and (integer? x) (not (float? x))) x
    (vector? x) (mapv canonical x)
    (map? x) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                   (map (fn [[k v]] [(canonical k) (canonical v)])) x)
    :else (throw (ex-info "Non-canonical evaluator receipt value"
                          {:samizdat.evaluator/error :non-canonical
                           :value-type (str (type x))}))))

(defn- encode [x]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (canonical x))))

(defn- decode [x]
  (when x (edn/read-string x)))

(declare binding-for-run)

(defn register-binding!
  "Persist the complete controller-minted EvaluatorBinding before its run takes
  a first model turn.  Idempotent only for the exact same record; reusing a run
  or binding id with different authority is a closed failure, never an upsert.

  The record carries the binding's EXACT trusted-orientation bytes and their
  digest, so a later resume restores the bytes the run was actually oriented on
  and verifies them against the digest rather than re-rendering a prompt
  resource that may have drifted since."
  [conn {:keys [binding-id run-id work-id instance-id spec-id context-spec
                runtime orientation orientation-digest]
         :as record}]
  (doseq [[k v] {:binding-id binding-id :run-id run-id :work-id work-id
                 :instance-id instance-id :spec-id spec-id
                 :context-spec context-spec :runtime runtime
                 :orientation orientation :orientation-digest orientation-digest}]
    (when (or (nil? v) (and (string? v) (str/blank? v)))
      (throw (ex-info (str "Evaluator binding needs " (name k)) {k v}))))
  (let [stored {:binding_id (str binding-id) :run_id (str run-id)
                :work_id (str work-id) :instance_id (str instance-id)
                :spec_id (str spec-id) :context_spec (encode context-spec)
                :runtime (str runtime)
                :orientation orientation
                :orientation_digest orientation-digest}]
    (db/with-writer
      (if-let [row (or (db/fetch-one conn
                                     ["SELECT * FROM evaluator_bindings WHERE binding_id = ?"
                                      (str binding-id)])
                       (db/fetch-one conn
                                     ["SELECT * FROM evaluator_bindings WHERE run_id = ?"
                                      (str run-id)]))]
        (when-not (= stored (select-keys row (keys stored)))
          (throw (ex-info "Durable evaluator binding identity conflict"
                          {:samizdat.evaluator/error :binding-conflict
                           :requested (dissoc record :context-spec :orientation)
                           :stored (select-keys row [:binding_id :run_id :work_id
                                                     :instance_id :spec_id :runtime])})))
        (db/execute! conn
                     ["INSERT INTO evaluator_bindings
                         (binding_id, run_id, work_id, instance_id, spec_id,
                          context_spec, runtime, orientation, orientation_digest,
                          created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                      (:binding_id stored) (:run_id stored) (:work_id stored)
                      (:instance_id stored) (:spec_id stored)
                      (:context_spec stored) (:runtime stored)
                      (:orientation stored) (:orientation_digest stored)
                      (db/now)])))
    (binding-for-run conn run-id)))

(defn binding-for-run
  "The durable binding record for a run, with ContextSpec parsed as inert EDN."
  [conn run-id]
  (when-let [row (db/fetch-one conn
                               ["SELECT * FROM evaluator_bindings WHERE run_id = ?"
                                (str run-id)])]
    (update row :context_spec decode)))

(defn binding-by-id [conn binding-id]
  (when-let [row (db/fetch-one conn
                               ["SELECT * FROM evaluator_bindings WHERE binding_id = ?"
                                (str binding-id)])]
    (update row :context_spec decode)))

(defn- open-eval! [conn eval-id]
  (let [row (db/fetch-one conn ["SELECT * FROM evaluator_evals WHERE id = ?" eval-id])]
    (when-not row
      (throw (ex-info "Unknown evaluator evaluation" {:eval-id eval-id})))
    (when (db/fetch-one conn ["SELECT id FROM evaluator_completions WHERE eval_id = ?" eval-id])
      (throw (ex-info "Evaluator evaluation is already terminal" {:eval-id eval-id})))
    row))

(defn- registered-binding-run-id
  "The durable run id a binding is registered under, or nil when the binding has
  no durable row (pre-M3 / a raw store seam).  A durable binding row is the
  store's signal that a run is an M3 bounded run."
  [conn binding-id]
  (:run_id (db/fetch-one conn
                         ["SELECT run_id FROM evaluator_bindings WHERE binding_id = ?"
                          (str binding-id)])))

(defn- verify-eval-epoch!
  "Fail closed on a broken eval→InferenceEpoch causal link.

  When the binding is durably registered (M3 live dispatch), a non-nil epoch is
  REQUIRED: every evaluation under a durable binding must carry the
  InferenceEpoch of the model call that dispatched it, so a nil epoch is a
  closed failure rather than a silently-nullable provenance gap.

  A non-nil epoch must resolve to an InferenceEpoch whose durable identity is
  exactly this binding's — same binding id, spec id and runtime, and (when the
  binding has a durable run) the same run — so an unknown or foreign epoch is a
  closed failure, never a caller-spoofed or fabricated coordinate."
  [conn epoch-id {:keys [binding-id spec-id runtime]} required?]
  (when (and (nil? epoch-id) required?)
    (throw (ex-info "M3 evaluation needs its dispatch inference epoch"
                    {:samizdat.evaluator/error :missing-epoch
                     :binding-id binding-id})))
  (when epoch-id
    (let [epoch (inference/get-epoch conn epoch-id)]
      (when-not epoch
        (throw (ex-info "Evaluation references an unknown inference epoch"
                        {:samizdat.evaluator/error :unknown-epoch
                         :inference-epoch-id epoch-id})))
      (when-not (= (str binding-id) (:binding_id epoch))
        (throw (ex-info "Evaluation epoch is foreign to this binding"
                        {:samizdat.evaluator/error :foreign-epoch
                         :inference-epoch-id epoch-id
                         :epoch-binding-id (:binding_id epoch)
                         :binding-id binding-id})))
      (when-not (= (str spec-id) (:spec_id epoch))
        (throw (ex-info "Evaluation epoch carries a foreign spec"
                        {:samizdat.evaluator/error :foreign-epoch
                         :inference-epoch-id epoch-id
                         :epoch-spec-id (:spec_id epoch)
                         :spec-id spec-id})))
      (when-not (= (str runtime) (:runtime epoch))
        (throw (ex-info "Evaluation epoch carries a foreign runtime"
                        {:samizdat.evaluator/error :foreign-epoch
                         :inference-epoch-id epoch-id
                         :epoch-runtime (:runtime epoch)
                         :runtime runtime})))
      (when-let [run-id (registered-binding-run-id conn binding-id)]
        (when-not (= run-id (:run_id epoch))
          (throw (ex-info "Evaluation epoch belongs to a different run"
                          {:samizdat.evaluator/error :foreign-epoch
                           :inference-epoch-id epoch-id
                           :epoch-run-id (:run_id epoch)
                           :run-id run-id}))))
      epoch)))

(defn begin!
  "Open one evaluation under its binding.  `inference-epoch-id`, when present,
  is the InferenceEpoch of the model call whose tool dispatch produced this
  evaluation — the causal link from provider invocation to eval row."
  ([conn {:keys [spec-id instance-id binding-id context-spec runtime source
                 inference-epoch-id]}]
   (doseq [[k v] {:spec-id spec-id :instance-id instance-id
                  :binding-id binding-id :context-spec context-spec
                  :runtime runtime :source source}]
     (when (str/blank? (str v))
       (throw (ex-info (str "Evaluator evaluation needs " (name k)) {k v}))))
   ;; The eval row is the head of the causal chain: under a durable (M3)
   ;; binding its epoch is required, and any non-nil epoch must resolve to this
   ;; exact binding/run/spec/runtime before a row is written.
   (verify-eval-epoch! conn inference-epoch-id
                       {:binding-id binding-id :spec-id spec-id :runtime runtime}
                       (some? (registered-binding-run-id conn binding-id)))
   (db/with-writer
     (let [n (:next (db/fetch-one conn
                                  ["SELECT COALESCE(MAX(binding_seq) + 1, 0) AS next
                                     FROM evaluator_evals WHERE binding_id = ?"
                                   binding-id]))]
       (db/execute! conn
                    ["INSERT INTO evaluator_evals
                        (spec_id, instance_id, binding_id, binding_seq,
                         context_spec, runtime, source, inference_epoch_id,
                         created_at)
                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                     spec-id instance-id binding-id n context-spec runtime
                     source inference-epoch-id (db/now)]))
      ;; last_insert_rowid() is CONNECTION-GLOBAL, so this retrieval MUST stay
      ;; inside the same db writer critical section as the INSERT above: hoisted
      ;; out, any interleaved writer on the shared conn would hand back its row.
      ;; Pinned by begin-last-insert-id-stays-inside-the-writer-section.
      (db/last-insert-id conn))))

(defn intent!
  ([conn eval-id op args]
   (intent! conn eval-id op args nil))
  ([conn eval-id op args inference-epoch-id]
   (db/with-writer
     (open-eval! conn eval-id)
     (let [eval-epoch (:inference_epoch_id
                       (db/fetch-one conn
                                     ["SELECT inference_epoch_id FROM evaluator_evals
                                        WHERE id = ?" eval-id]))]
       ;; The intent's epoch must be exactly the eval's: a caller cannot write a
       ;; divergent (or nil) epoch onto the middle of an established chain.
       (when-not (= eval-epoch inference-epoch-id)
         (throw (ex-info "Intent epoch diverges from its evaluation's epoch"
                         {:samizdat.evaluator/error :epoch-divergence
                          :eval-id eval-id :eval-epoch eval-epoch
                          :intent-epoch inference-epoch-id})))
       (let [n (:next (db/fetch-one conn
                                    ["SELECT COALESCE(MAX(seq) + 1, 0) AS next
                                       FROM evaluator_receipts
                                      WHERE eval_id = ? AND phase = 'intent'" eval-id]))]
         (db/execute! conn
                      ["INSERT INTO evaluator_receipts
                          (eval_id, seq, phase, op, args, inference_epoch_id,
                           created_at)
                        VALUES (?, ?, 'intent', ?, ?, ?, ?)"
                       eval-id n (encode op) (encode (vec args))
                       inference-epoch-id (db/now)])
         n)))))

(defn outcome!
  ([conn eval-id seqn outcome]
   (outcome! conn eval-id seqn outcome nil))
  ([conn eval-id seqn outcome inference-epoch-id]
   (when (= (contains? outcome :result) (contains? outcome :error))
     (throw (ex-info "Evaluator outcome is exactly one of result or error"
                     {:eval-id eval-id :seq seqn})))
   (db/with-writer
     (open-eval! conn eval-id)
     (let [intent (db/fetch-one conn
                                ["SELECT op, inference_epoch_id
                                   FROM evaluator_receipts
                                   WHERE eval_id = ? AND seq = ? AND phase = 'intent'"
                                 eval-id seqn])]
       (when-not intent
         (throw (ex-info "Evaluator outcome has no preceding intent"
                         {:eval-id eval-id :seq seqn})))
       ;; The outcome's epoch must be exactly the intent's (and therefore the
       ;; eval's): a caller cannot write a divergent epoch onto the tail of an
       ;; established chain.
       (when-not (= (:inference_epoch_id intent) inference-epoch-id)
         (throw (ex-info "Outcome epoch diverges from its intent's epoch"
                         {:samizdat.evaluator/error :epoch-divergence
                          :eval-id eval-id :seq seqn
                          :intent-epoch (:inference_epoch_id intent)
                          :outcome-epoch inference-epoch-id})))
       (db/execute! conn
                    ["INSERT INTO evaluator_receipts
                        (eval_id, seq, phase, op, result, error,
                         inference_epoch_id, created_at)
                      VALUES (?, ?, 'outcome', ?, ?, ?, ?, ?)"
                     eval-id seqn (:op intent)
                     (when (contains? outcome :result) (encode (:result outcome)))
                     (when (contains? outcome :error) (str (:error outcome)))
                     inference-epoch-id (db/now)])))
   seqn))

(defn unsettled [conn eval-id]
  (mapv (fn [row] {:seq (:seq row) :op (decode (:op row))
                   :args (decode (:args row))})
        (db/fetch conn
                  ["SELECT seq, op, args FROM evaluator_receipts
                     WHERE eval_id = ? AND phase = 'intent'
                       AND seq NOT IN
                           (SELECT seq FROM evaluator_receipts
                             WHERE eval_id = ? AND phase = 'outcome')
                     ORDER BY seq" eval-id eval-id])))

(defn complete!
  [conn eval-id status result]
  (when-not (#{:completed :failed} status)
    (throw (ex-info "Invalid evaluator completion status" {:status status})))
  (db/with-writer
    (open-eval! conn eval-id)
    (when-let [pending (seq (unsettled conn eval-id))]
      (throw (ex-info "Unsettled evaluator intent blocks completion"
                      {:samizdat.evaluator/error :unsettled-intent
                       :receipts pending})))
    (db/execute! conn
                 ["INSERT INTO evaluator_completions
                     (eval_id, status, result, created_at) VALUES (?, ?, ?, ?)"
                  eval-id (name status) (when (some? result) (encode result)) (db/now)]))
  true)

(defn- fold-receipt [rows]
  (let [intent (first (filter #(= "intent" (:phase %)) rows))
        outcome (first (filter #(= "outcome" (:phase %)) rows))]
    (cond-> {:seq (:seq intent) :op (decode (:op intent))
             :args (decode (:args intent))
             :phase (if outcome (if (:error outcome) :error :done) :intent)}
      (:inference_epoch_id intent) (assoc :inference-epoch-id
                                           (:inference_epoch_id intent))
      (and outcome (:result outcome)) (assoc :result (decode (:result outcome)))
      (and outcome (:error outcome)) (assoc :error (:error outcome)))))

(defn load-eval [conn eval-id]
  (when-let [row (db/fetch-one conn ["SELECT * FROM evaluator_evals WHERE id = ?" eval-id])]
    (let [terminal (db/fetch-one conn
                                 ["SELECT * FROM evaluator_completions WHERE eval_id = ?"
                                  eval-id])
          receipts (db/fetch conn
                             ["SELECT * FROM evaluator_receipts
                                WHERE eval_id = ? ORDER BY seq, id" eval-id])]
      (assoc row
             :status (if terminal (keyword (:status terminal)) :pending)
             :result (decode (:result terminal))
             :receipts (mapv (fn [[_ rs]] (fold-receipt rs))
                             (sort-by key (group-by :seq receipts)))))))

(defn history [conn binding-id]
  (mapv #(load-eval conn (:id %))
        (db/fetch conn
                  ["SELECT id FROM evaluator_evals WHERE binding_id = ?
                     ORDER BY binding_seq" binding-id])))

(defn pending [conn]
  (db/fetch conn
            ["SELECT e.* FROM evaluator_evals e
               WHERE NOT EXISTS
                 (SELECT 1 FROM evaluator_completions c WHERE c.eval_id = e.id)
                ORDER BY e.id"]))

(defn evidence-for-run
  "Read-only evaluator/inference evidence for telemetry and APIs.

  This function never allocates SCI, replays source, or invokes a semantic
  operation.  It is a projection of durable binding, completion, receipt and
  InferenceEpoch rows only — including each receipt's exact operation/argument
  signature and epoch linkage, which is what the read-only bounded telemetry
  is computed from."
  [conn run-id]
  (when-let [binding (binding-for-run conn run-id)]
    (let [binding-id (:binding_id binding)
          evals (history conn binding-id)
          epochs (db/fetch conn
                           ["SELECT id, branch_id, turn, provider, model, adapter,
                                    config_digest, closed_at, binding_id, spec_id,
                                    runtime, created_at
                              FROM inference_epochs WHERE run_id = ?
                              ORDER BY rowid"
                            (str run-id)])
          statuses (frequencies (map :status evals))
          completed (filter #(= :completed (:status %)) evals)
          ;; The exact causal signature of every committed operation: op, args,
          ;; and the epoch of the model call that dispatched it.  Counts stay
          ;; :order-only beside this for compatibility.
          receipts (mapv (fn [row]
                           (mapv #(select-keys % [:op :args :phase
                                                  :inference-epoch-id])
                                  (:receipts row)))
                         completed)
          ops (mapv (fn [row-ops] (mapv :op row-ops)) receipts)]
      {:binding {:binding-id binding-id
                 :instance-id (:instance_id binding)
                 :spec-id (:spec_id binding)
                 :runtime (:runtime binding)
                 ;; The digest of the persisted trusted-orientation bytes, not
                 ;; the bytes: evidence names what a resume would restore, it
                 ;; does not re-deliver the orientation.
                 :orientation-digest (:orientation_digest binding)
                 :profile (get-in binding [:context_spec :context/profile])
                 :capabilities (get-in binding [:context_spec :context/capabilities])
                 :timeout-ms (get-in binding [:context_spec :context/timeout-ms])}
       :evaluations {:total (count evals)
                     :completed (get statuses :completed 0)
                     :failed (get statuses :failed 0)
                     :pending (get statuses :pending 0)}
       :operations {:per-evaluation (mapv count ops)
                    :multi-operation (count (filter #(< 1 (count %)) ops))
                    :order ops
                    :receipts receipts}
       :inference-epochs epochs})))
