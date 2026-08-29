;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.inference
  "Append-only InferenceEpoch and InferenceInvocation provenance.

  An EPOCH is REAL and REUSABLE: it names the safe realization it covers —
  provider, model, adapter and a digest of the nonsecret llm config — and is
  REUSED by every later call of the same run whose realization is unchanged.
  The moment the realization changes (a provider or model switch, a config
  edit, typically at a resume) the open epoch is closed and a new one begins,
  so the epoch stream is the durable record of every realization change a run
  actually made and no timestamp inference is ever needed to say which calls
  shared a realization.

  An INVOCATION is PER-CALL: one row per provider call, minted under the
  call's epoch immediately before the provider seam fires.  Turns, eval rows
  and intent/outcome receipts reference the invocation — and through it the
  epoch — so every durable effect names the exact call that produced it.
  Calls that share an epoch share its realization; they never share an
  invocation."
  (:require [clojure.string :as str]
             [samizdat.store.db :as db]))

(declare get-epoch)

(defn- required! [{:keys [id run-id branch-id turn]}]
  (doseq [[k v] {:id id :run-id run-id :branch-id branch-id :turn turn}]
    (when (or (nil? v) (str/blank? (str v)))
      (throw (ex-info (str "InferenceEpoch needs " (name k)) {k v})))))

(defn begin!
  "Append one epoch row verbatim.  Callers that want realization reuse must go
  through ensure!; begin! is the honest primitive beneath it and the test seam
  above it."
  [conn {:keys [id run-id branch-id turn provider model adapter config-digest
                binding-id spec-id runtime]}]
  (required! {:id id :run-id run-id :branch-id branch-id :turn turn})
  (db/with-writer
    (db/execute! conn
                 ["INSERT INTO inference_epochs
                     (id, run_id, branch_id, turn, provider, model, adapter,
                      config_digest, binding_id, spec_id, runtime, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                  (str id) (str run-id) (str branch-id) (long turn)
                  (if provider (name provider) "") (or model "")
                  (or adapter "") (or config-digest "")
                  binding-id spec-id runtime (db/now)]))
  (get-epoch conn id))

(defn get-epoch [conn id]
  (db/fetch-one conn ["SELECT * FROM inference_epochs WHERE id = ?" (str id)]))

(defn for-run
  [conn run-id]
  (db/fetch conn ["SELECT * FROM inference_epochs WHERE run_id = ?
                    ORDER BY rowid" (str run-id)]))

(defn open-epochs
  "The run's epochs that no realization change has closed."
  [conn run-id]
  (db/fetch conn ["SELECT * FROM inference_epochs
                    WHERE run_id = ? AND closed_at IS NULL
                    ORDER BY rowid" (str run-id)]))

(defn close!
  "Close the run's open epochs.  Idempotent; returns the ids closed by THIS
  call."
  [conn run-id]
  (mapv :id
        (db/with-writer
          (let [open (db/fetch conn ["SELECT id FROM inference_epochs
                                      WHERE run_id = ? AND closed_at IS NULL"
                                     (str run-id)])]
            (when (seq open)
              (db/execute! conn ["UPDATE inference_epochs SET closed_at = ?
                                   WHERE run_id = ? AND closed_at IS NULL"
                                 (db/now) (str run-id)]))
            open))))

(defn- realization-match?
  "Whether `epoch` (an open row) covers exactly this safe realization."
  [epoch provider model adapter config-digest]
  (and (= (if provider (name provider) "") (:provider epoch))
       (= (str (or model "")) (:model epoch))
       (= (str (or adapter "")) (:adapter epoch))
       (= (str (or config-digest "")) (:config_digest epoch))))

(defn ensure!
  "The epoch a provider call runs under.

  Returns the run's still-open epoch when its safe realization — provider,
  model, adapter and nonsecret config digest — is unchanged, so unchanged
  calls share one durable epoch.  Otherwise closes the open epoch (if any) and
  appends a fresh one fixed before the caller's provider invocation, exactly
  like begin!.

  One transaction decides reuse/close/open: two concurrent calls of one
  realization cannot mint two epochs, and a realization change is closed
  before its successor exists."
  [conn {:keys [id run-id branch-id turn provider model adapter config-digest
                binding-id spec-id runtime]
         :as request}]
  (required! request)
  (db/with-writer
    (or (some #(when (realization-match? % provider model adapter config-digest)
                 %)
              (db/fetch conn ["SELECT * FROM inference_epochs
                                WHERE run_id = ? AND closed_at IS NULL
                                ORDER BY rowid" (str run-id)]))
        (do (close! conn run-id)
            (db/execute! conn
                         ["INSERT INTO inference_epochs
                             (id, run_id, branch_id, turn, provider, model,
                              adapter, config_digest, binding_id, spec_id,
                              runtime, created_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                          (str id) (str run-id) (str branch-id) (long turn)
                          (if provider (name provider) "") (or model "")
                          (or adapter "") (or config-digest "")
                          binding-id spec-id runtime (db/now)])
            (get-epoch conn id)))))

;; --- per-call InferenceInvocation --------------------------------------------

(defn get-invocation
  "The invocation row, or nil.  Callers that need fail-closed resolution go
  through samizdat.store.evaluator's chain validation, which owns the
  eval/receipt side of the contract."
  [conn id]
  (db/fetch-one conn ["SELECT * FROM inference_invocations WHERE id = ?"
                      (str id)]))

(defn- invocation-required!
  [{:keys [id epoch-id run-id branch-id turn]}]
  (doseq [[k v] {:id id :epoch-id epoch-id :run-id run-id
                 :branch-id branch-id :turn turn}]
    (when (or (nil? v) (str/blank? (str v)))
      (throw (ex-info (str "InferenceInvocation needs " (name k)) {k v})))))

(defn invoke!
  "Mint one InferenceInvocation: the exact identity of ONE provider call,
  under the run's epoch that call runs in.  Fails closed when the epoch does
  not exist — an invocation may not reference a fabricated realization."
  [conn {:keys [id epoch-id run-id branch-id turn] :as request}]
  (invocation-required! request)
  (db/with-writer
    (let [epoch (get-epoch conn epoch-id)]
      (when-not epoch
        (throw (ex-info "InferenceInvocation references an unknown epoch"
                        {:samizdat.inference/error :unknown-epoch
                         :inference-epoch-id (str epoch-id)})))
      (db/execute! conn
                   ["INSERT INTO inference_invocations
                       (id, epoch_id, run_id, branch_id, turn, created_at)
                     VALUES (?, ?, ?, ?, ?, ?)"
                    (str id) (str epoch-id) (str run-id) (str branch-id)
                    (long turn) (db/now)])))
  (get-invocation conn id))

(defn invocations-for-run
  "The run's invocation stream, insertion ordered."
  [conn run-id]
  (db/fetch conn ["SELECT * FROM inference_invocations WHERE run_id = ?
                    ORDER BY rowid" (str run-id)]))
