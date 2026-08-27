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
