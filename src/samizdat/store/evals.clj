;; samizdat - a self-hosting agentic harness
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

(ns samizdat.store.evals
  "Durable receipt storage for bounded SCI evaluations — the JS1 record of a
  JS0 evaluation.

  jolt.sandbox (JS0) evaluates source in a persistent, capability-bounded SCI
  context and keeps the evaluation's receipts in an in-process atom: ordered
  {op args result|error} entries, one per observation or actuation, consumed
  in order at replay. This namespace is the durable version of that model,
  for callers that need the record to survive the process.

  The lifecycle mirrors how an evaluation actually happens:

    (begin! conn {:spec-id s :instance-id i :binding-id b
                  :coordinate c :runtime r :source src}) ; before anything runs
    (record-intent! conn id {:op op :args [...]})    ; before each effect
    (record-outcome! conn id n {:result v}|:error)   ; after it
    (complete! conn id {:status :completed :result m}) ; at the end

  Every evaluation is bound to a trusted evaluator identity — the
  :spec-id/:instance-id/:binding-id of the ContextSpec/binding that produced
  the coordinate — so a recovery caller can confirm a durable record is the
  one it thinks it is before reconciling it. `verify-binding!` is that gate:
  it fails closed when any identity field differs from what the caller holds,
  or when the row is a legacy row that carries no identity at all. The full
  ContextSpec is not duplicated here; only the three ids that name it.

  Two further fields pin the record to the exact runtime and total order:

    :runtime — the versioned RuntimeCoordinate string naming the
      Jolt/SCI/evaluator-protocol/language-surface/capability-catalog/
      receipt-protocol stack the evaluation ran under. Receipts are only
      meaningful under the runtime that produced them, so verification
      compares it exactly and a process upgraded past a record fails closed
      instead of replaying across the change. Opaque text here, like
      :coordinate.
    :binding_seq — the binding's durable total order, assigned by begin!
      as one more than the binding's previous maximum. `history` reads a
      binding's evaluations back in this order, which is the order
      whole-history rebuild replays them in.

  Append-only in the strict sense: no row here is ever updated or deleted.
  Settlement is a second row, so a crashed evaluation's record is exactly
  what the process managed to append. That is the incomplete-actuation
  semantics: an eval with no completion row is PENDING, and an intent with
  no outcome is an effect whose actuation state is unknown. `pending` and
  `unsettled-effects` exist so the caller can fail closed on both — a
  pending actuation may or may not have happened, and the honest answer is
  to refuse to pretend either way. complete! enforces the same rule from
  the inside: an evaluation with unsettled effects cannot be completed.

  Receipt payloads are structured canonical EDN, never transcript text:
  written from the receipt domain (nil, booleans, strings, exact integers,
  keywords, symbols, vectors, and maps sorted canonically by key — the same
  domain jolt.sandbox/inert defines, reimplemented here because samizdat's
  classpath does not carry SCI), printed unbounded, and read back with
  clojure.edn (which parses and never evaluates). What goes in as data
  comes back as data."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [samizdat.store.db :as db]))

;; --- canonical EDN ------------------------------------------------------------

(defn- canonical
  "Canonicalize a value into the receipt domain, or throw.

  Accepted: nil, boolean, string, exact integer, keyword, symbol, vector of
  canonical values, map with canonical keys and values (entries sorted
  canonically by pr-str of key). Everything else — floats, lazy seqs, atoms,
  functions, host objects — is refused rather than stringified, because a
  receipt that cannot round-trip as data is not a receipt."
  [x]
  (cond
    (nil? x) nil
    (boolean? x) x
    (string? x) x
    (keyword? x) x
    (symbol? x) x
    (and (integer? x) (not (float? x))) x
    (vector? x) (mapv canonical x)
    (map? x) (into {}
                   (map (fn [[k v]] [(canonical k) (canonical v)]))
                   (sort-by (comp pr-str key) x))
    :else (throw (ex-info "Non-canonical receipt value"
                          {:samizdat.store.evals/value-type (str (type x))
                           :samizdat.store.evals/value (pr-str x)}))))

(defn- ->edn
  "Canonical EDN text for a receipt-domain value. Unbounded printing: a
  truncated receipt would silently no longer be the receipt."
  [v]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (canonical v))))

(defn- edn->value
  "Read a stored EDN column back into data. clojure.edn parses and never
  evaluates, which is what makes loading receipts safe."
  [s]
  (when s (edn/read-string s)))

;; --- writes -------------------------------------------------------------------

(defn- open-eval!
  "The evals row for id, throwing when there is no such evaluation. Also
  throws when the evaluation already has a terminal record — the record of
  an evaluation is not rewritten, so receipts can only be appended while
  the evaluation is pending."
  [conn id]
  (let [row (db/fetch-one conn ["SELECT * FROM evals WHERE id = ?" id])]
    (when-not row
      (throw (ex-info "no such evaluation" {:eval-id id})))
    (when (db/fetch-one conn ["SELECT id FROM eval_completions WHERE eval_id = ?" id])
      (throw (ex-info "evaluation already has a terminal record; a settled record is not rewritten"
                      {:eval-id id})))
    row))

(defn begin!
  "Record the intent to evaluate `source` under the canonical
  evaluator/context `coordinate` (the string jolt.sandbox/canonical-coordinate
  produces) and the versioned `runtime` coordinate (the string naming the
  Jolt/SCI/protocol stack), bound to a trusted evaluator identity: the
  `:spec-id`, `:instance-id`, and `:binding-id` of the ContextSpec/binding
  that produced the coordinate. All six fields are required and must be
  non-blank. Returns the evaluation id.

  The row also claims the binding's next `binding_seq` — 0 for the binding's
  first evaluation, else one more than its current maximum — so the binding's
  evaluations form a durable total order in registration order. The
  (binding_id, binding_seq) unique index makes the order structural: a
  duplicate is rejected by the database, not by convention. A refused begin
  is validated before the insert, so it consumes no sequence number.

  The record is pending from this moment: until `complete!` appends a
  terminal row, `pending` reports it."
  [conn {:keys [spec-id instance-id binding-id coordinate runtime source]}]
  (doseq [[k v] {:spec-id spec-id
                 :instance-id instance-id
                 :binding-id binding-id
                 :coordinate coordinate
                 :runtime runtime
                 :source source}]
    (when (str/blank? (str v))
      (throw (ex-info (str "an evaluation needs " (name k)) {k v}))))
  (db/with-writer
    (let [n (:next (db/fetch-one conn ["SELECT COALESCE(MAX(binding_seq) + 1, 0) AS next
                                        FROM evals WHERE binding_id = ?"
                                       (str binding-id)]))]
      (db/execute! conn ["INSERT INTO evals (spec_id, instance_id, binding_id, binding_seq, coordinate, runtime, source, created_at)
                          VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                         (str spec-id) (str instance-id) (str binding-id) n
                         (str coordinate) (str runtime) (str source) (db/now)]))
    (db/last-insert-id conn)))

(defn record-intent!
  "Record the intent to run one effect — `op` (a keyword, symbol, or string
  operation id) with `args` (a vector of receipt-domain values) — BEFORE it
  executes. Returns the effect's seq: effects are numbered 0, 1, 2, ... in
  the order their intents are recorded, which is the order replay consumes
  them in."
  [conn eval-id {:keys [op args]}]
  (when (or (nil? op) (and (string? op) (str/blank? op)))
    (throw (ex-info "an effect intent needs an op" {:eval-id eval-id})))
  (db/with-writer
    (open-eval! conn eval-id)
    (let [n (:next (db/fetch-one conn ["SELECT COALESCE(MAX(seq) + 1, 0) AS next
                                        FROM eval_receipts
                                        WHERE eval_id = ? AND phase = 'intent'"
                                       eval-id]))]
      (db/execute! conn ["INSERT INTO eval_receipts (eval_id, seq, phase, op, args, created_at)
                          VALUES (?, ?, 'intent', ?, ?, ?)"
                         eval-id n (->edn op) (->edn (vec (or args []))) (db/now)])
      n)))

(defn record-outcome!
  "Record the outcome of the effect whose intent is (eval-id, seq) — exactly
  one of :result (a receipt-domain value; nil is a real result) or :error
  (a message). The intent must already exist and not already be settled: an
  outcome without an intent would be an effect nobody recorded running, and
  a second outcome would rewrite one."
  [conn eval-id seqn {:keys [result error] :as outcome}]
  (when (= (contains? outcome :result) (contains? outcome :error))
    (throw (ex-info "an outcome is exactly one of :result or :error"
                    {:eval-id eval-id :seq seqn})))
  (db/with-writer
    (open-eval! conn eval-id)
    (let [intent (db/fetch-one conn ["SELECT * FROM eval_receipts
                                      WHERE eval_id = ? AND seq = ? AND phase = 'intent'"
                                     eval-id seqn])]
      (when-not intent
        (throw (ex-info "no intent recorded for this effect; intent precedes outcome"
                        {:eval-id eval-id :seq seqn})))
      (when (db/fetch-one conn ["SELECT id FROM eval_receipts
                                 WHERE eval_id = ? AND seq = ? AND phase = 'outcome'"
                                eval-id seqn])
        (throw (ex-info "effect already has an outcome; a receipt is not rewritten"
                        {:eval-id eval-id :seq seqn})))
      (db/execute! conn ["INSERT INTO eval_receipts (eval_id, seq, phase, op, result, error, created_at)
                          VALUES (?, ?, 'outcome', ?, ?, ?, ?)"
                         eval-id seqn (:op intent)
                         (when (contains? outcome :result) (->edn result))
                         (when (contains? outcome :error) (str error))
                         (db/now)]))
    seqn))

(defn- unsettled-seqs
  [conn eval-id]
  (mapv :seq (db/fetch conn ["SELECT seq FROM eval_receipts
                              WHERE eval_id = ? AND phase = 'intent'
                                AND seq NOT IN (SELECT seq FROM eval_receipts
                                                WHERE eval_id = ? AND phase = 'outcome')
                              ORDER BY seq"
                             eval-id eval-id])))

(defn complete!
  "Append the terminal record for a pending evaluation: `status` is
  :completed or :failed, and `result` is receipt-domain result metadata
  (nil allowed when there is nothing structured to say). Returns true.

  Refuses while any of the evaluation's effects is still an unsettled
  intent: completing would claim the record is whole when an actuation's
  outcome is unknown. Such an evaluation stays pending instead, which is
  the fail-closed answer — see `unsettled-effects`."
  [conn eval-id {:keys [status result]}]
  (when-not (#{:completed :failed} status)
    (throw (ex-info "terminal status is :completed or :failed"
                    {:eval-id eval-id :status status})))
  (db/with-writer
    (open-eval! conn eval-id)
    (let [open (unsettled-seqs conn eval-id)]
      (when (seq open)
        (throw (ex-info "cannot complete: effects were recorded and never settled, so their actuation state is unknown; leave the record pending and fail closed"
                        {:eval-id eval-id :unsettled-seqs open}))))
    (db/execute! conn ["INSERT INTO eval_completions (eval_id, status, result, created_at)
                        VALUES (?, ?, ?, ?)"
                       eval-id (name status)
                       (when (some? result) (->edn result))
                       (db/now)])
    true))

;; --- reads --------------------------------------------------------------------

(defn pending
  "Evaluations with no terminal record, oldest first — every record a caller
  must fail closed on, because their actuations may or may not have run.
  Bounded by `limit` (default 100); rows are the raw evals table."
  ([conn] (pending conn 100))
  ([conn limit]
   (db/fetch conn ["SELECT e.* FROM evals e
                    WHERE NOT EXISTS (SELECT 1 FROM eval_completions c
                                      WHERE c.eval_id = e.id)
                    ORDER BY e.id LIMIT ?"
                   (long limit)])))

(defn completed
  "Completed evaluation records in strict sequence (insertion order, not
  completion order), each with its terminal :status (keyword) and :result
  metadata read back as data. Receipt bodies are not in this projection —
  `load-eval` carries them. Bounded by `limit` (default 100)."
  ([conn] (completed conn 100))
  ([conn limit]
   (mapv (fn [row]
           (-> row
               (update :status keyword)
               (update :result edn->value)))
          (db/fetch conn ["SELECT e.id, e.spec_id, e.instance_id, e.binding_id,
                                  e.binding_seq, e.coordinate, e.runtime, e.source,
                                  e.created_at,
                                  c.status, c.result, c.created_at AS completed_at
                           FROM evals e
                           JOIN eval_completions c ON c.eval_id = e.id
                           ORDER BY e.id LIMIT ?"
                          (long limit)]))))

(defn unsettled-effects
  "The effects of one evaluation recorded as intended and never settled,
  in seq order — actuations whose effect on the world is unknown. This is
  the per-effect fail-closed signal: a caller reconciling a pending record
  must assume each of these MAY have happened."
  [conn eval-id]
  (->> (db/fetch conn ["SELECT seq, op, args FROM eval_receipts
                        WHERE eval_id = ? AND phase = 'intent'
                          AND seq NOT IN (SELECT seq FROM eval_receipts
                                          WHERE eval_id = ? AND phase = 'outcome')
                        ORDER BY seq"
                       eval-id eval-id])
       (mapv (fn [row]
               {:seq (:seq row)
                :op (edn/read-string (:op row))
                :args (edn/read-string (:args row))}))))

(defn- fold-receipt
  "One effect's intent and (absent while unsettled) outcome rows folded into
  a single receipt entry. :phase is :done or :error once settled, and stays
  :intent while the effect's actuation state is unknown."
  [rows]
  (let [intent (first (filter #(= "intent" (:phase %)) rows))
        outcome (first (filter #(= "outcome" (:phase %)) rows))]
    (cond-> {:seq (:seq intent)
             :op (edn/read-string (:op intent))
             :args (edn/read-string (:args intent))
             :phase (if outcome (if (:error outcome) :error :done) :intent)
             :intended-at (:created_at intent)}
      outcome (assoc :settled-at (:created_at outcome))
      (and outcome (:result outcome)) (assoc :result (edn/read-string (:result outcome)))
      (and outcome (:error outcome)) (assoc :error (:error outcome)))))

(defn load-eval
  "The full durable record of one evaluation, or nil when there is no such
  id: the evals row plus :status (:pending, :completed, or :failed),
  :result metadata read back as data, :completed_at, and :receipts — the
  ordered structured receipt entries as data (never transcript text), in
  strict seq order, each carrying its :phase so an unsettled intent is
  visible for what it is."
  [conn eval-id]
  (when-let [row (db/fetch-one conn ["SELECT * FROM evals WHERE id = ?" eval-id])]
    (let [completion (db/fetch-one conn ["SELECT * FROM eval_completions
                                          WHERE eval_id = ?" eval-id])
          rows (db/fetch conn ["SELECT * FROM eval_receipts
                                WHERE eval_id = ? ORDER BY seq, id" eval-id])]
      (assoc row
             :status (if completion (keyword (:status completion)) :pending)
             :result (edn->value (:result completion))
             :completed_at (:created_at completion)
              :receipts (mapv (fn [[_ receipt-rows]] (fold-receipt receipt-rows))
                              (sort-by key (group-by :seq rows)))))))

(defn history
  "Every evaluation recorded for `binding-id`, in the binding's durable total
  order (binding_seq ascending) — the sequence whole-history rebuild replays.
  Each entry is the full `load-eval` projection: the identity fields,
  :binding_seq, :runtime, :status (:pending included, so an unfinished record
  is visible for what it is), :result read back as data, and the ordered
  structured :receipts. Completed, failed, and pending rows all appear; what
  may be replayed is the caller's semantic decision. Returns [] for a binding
  with no recorded evaluations."
  [conn binding-id]
  (mapv (fn [row] (load-eval conn (:id row)))
        (db/fetch conn ["SELECT id FROM evals WHERE binding_id = ?
                         ORDER BY binding_seq"
                        (str binding-id)])))

(defn verify-binding!
  "Fail closed unless the durable evaluation names exactly the trusted
   EvaluatorSpec, EvaluatorInstance, EvaluatorBinding, authority coordinate,
   and runtime coordinate supplied by the controller. Legacy/partial records
   are not resumable."
  [conn eval-id {:keys [spec-id instance-id binding-id coordinate runtime]}]
  (let [row (load-eval conn eval-id)
        expected {:spec_id (str spec-id)
                  :instance_id (str instance-id)
                  :binding_id (str binding-id)
                  :coordinate (str coordinate)
                  :runtime (str runtime)}
        actual (select-keys row (keys expected))]
    (when-not row
      (throw (ex-info "evaluation record is missing" {:eval-id eval-id})))
    (when (or (some #(str/blank? (str (get actual %)))
                    (keys expected))
              (not= expected actual))
      (throw (ex-info "evaluator binding does not match durable evaluation"
                      {:eval-id eval-id :expected expected :actual actual})))
    row))
