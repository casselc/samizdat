;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.evaluator
  "Append-only storage for bounded evaluator computations and receipts."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [samizdat.store.db :as db]))

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
  or binding id with different authority is a closed failure, never an upsert."
  [conn {:keys [binding-id run-id work-id instance-id spec-id context-spec runtime]
         :as record}]
  (doseq [[k v] {:binding-id binding-id :run-id run-id :work-id work-id
                 :instance-id instance-id :spec-id spec-id
                 :context-spec context-spec :runtime runtime}]
    (when (or (nil? v) (and (string? v) (str/blank? v)))
      (throw (ex-info (str "Evaluator binding needs " (name k)) {k v}))))
  (let [stored {:binding_id (str binding-id) :run_id (str run-id)
                :work_id (str work-id) :instance_id (str instance-id)
                :spec_id (str spec-id) :context_spec (encode context-spec)
                :runtime (str runtime)}]
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
                           :requested (dissoc record :context-spec)
                           :stored (select-keys row [:binding_id :run_id :work_id
                                                     :instance_id :spec_id :runtime])})))
        (db/execute! conn
                     ["INSERT INTO evaluator_bindings
                         (binding_id, run_id, work_id, instance_id, spec_id,
                          context_spec, runtime, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                      (:binding_id stored) (:run_id stored) (:work_id stored)
                      (:instance_id stored) (:spec_id stored)
                      (:context_spec stored) (:runtime stored) (db/now)])))
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

(defn begin!
  [conn {:keys [spec-id instance-id binding-id context-spec runtime source]}]
  (doseq [[k v] {:spec-id spec-id :instance-id instance-id
                 :binding-id binding-id :context-spec context-spec
                 :runtime runtime :source source}]
    (when (str/blank? (str v))
      (throw (ex-info (str "Evaluator evaluation needs " (name k)) {k v}))))
  (db/with-writer
    (let [n (:next (db/fetch-one conn
                                 ["SELECT COALESCE(MAX(binding_seq) + 1, 0) AS next
                                    FROM evaluator_evals WHERE binding_id = ?"
                                  binding-id]))]
      (db/execute! conn
                   ["INSERT INTO evaluator_evals
                       (spec_id, instance_id, binding_id, binding_seq,
                        context_spec, runtime, source, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    spec-id instance-id binding-id n context-spec runtime source (db/now)]))
    ;; last_insert_rowid() is CONNECTION-GLOBAL, so this retrieval MUST stay
    ;; inside the same db writer critical section as the INSERT above: hoisted
    ;; out, any interleaved writer on the shared conn would hand back its row.
    ;; Pinned by begin-last-insert-id-stays-inside-the-writer-section.
    (db/last-insert-id conn)))

(defn intent!
  [conn eval-id op args]
  (db/with-writer
    (open-eval! conn eval-id)
    (let [n (:next (db/fetch-one conn
                                 ["SELECT COALESCE(MAX(seq) + 1, 0) AS next
                                    FROM evaluator_receipts
                                   WHERE eval_id = ? AND phase = 'intent'" eval-id]))]
      (db/execute! conn
                   ["INSERT INTO evaluator_receipts
                       (eval_id, seq, phase, op, args, created_at)
                     VALUES (?, ?, 'intent', ?, ?, ?)"
                    eval-id n (encode op) (encode (vec args)) (db/now)])
      n)))

(defn outcome!
  [conn eval-id seqn outcome]
  (when (= (contains? outcome :result) (contains? outcome :error))
    (throw (ex-info "Evaluator outcome is exactly one of result or error"
                    {:eval-id eval-id :seq seqn})))
  (db/with-writer
    (open-eval! conn eval-id)
    (let [intent (db/fetch-one conn
                               ["SELECT op FROM evaluator_receipts
                                  WHERE eval_id = ? AND seq = ? AND phase = 'intent'"
                                eval-id seqn])]
      (when-not intent
        (throw (ex-info "Evaluator outcome has no preceding intent"
                        {:eval-id eval-id :seq seqn})))
      (db/execute! conn
                   ["INSERT INTO evaluator_receipts
                       (eval_id, seq, phase, op, result, error, created_at)
                     VALUES (?, ?, 'outcome', ?, ?, ?, ?)"
                    eval-id seqn (:op intent)
                    (when (contains? outcome :result) (encode (:result outcome)))
                    (when (contains? outcome :error) (str (:error outcome)))
                    (db/now)])))
  seqn)

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
  InferenceEpoch rows only."
  [conn run-id]
  (when-let [binding (binding-for-run conn run-id)]
    (let [binding-id (:binding_id binding)
          evals (history conn binding-id)
          epochs (db/fetch conn
                           ["SELECT id, branch_id, turn, provider, model,
                                    binding_id, spec_id, runtime, created_at
                              FROM inference_epochs WHERE run_id = ?
                              ORDER BY created_at, id"
                            (str run-id)])
          statuses (frequencies (map :status evals))
          ops (mapv (fn [row] (mapv :op (:receipts row)))
                    (filter #(= :completed (:status %)) evals))]
      {:binding {:binding-id binding-id
                 :instance-id (:instance_id binding)
                 :spec-id (:spec_id binding)
                 :runtime (:runtime binding)
                 :profile (get-in binding [:context_spec :context/profile])
                 :capabilities (get-in binding [:context_spec :context/capabilities])
                 :timeout-ms (get-in binding [:context_spec :context/timeout-ms])}
       :evaluations {:total (count evals)
                     :completed (get statuses :completed 0)
                     :failed (get statuses :failed 0)
                     :pending (get statuses :pending 0)}
       :operations {:per-evaluation (mapv count ops)
                    :multi-operation (count (filter #(< 1 (count %)) ops))
                    :order ops}
       :inference-epochs epochs})))
