;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.inference
  "Append-only InferenceEpoch provenance.  An epoch is fixed immediately
  before one provider invocation; turn settlement merely references it.

  An epoch is REAL: it names the safe realization it covers — provider, model,
  adapter and a digest of the nonsecret llm config — and is REUSED by every
  later call of the same run whose realization is unchanged.  The moment the
  realization changes (a provider or model switch, a config edit, typically at
  a resume) the open epoch is closed and a new one begins, so the epoch stream
  is the durable record of every realization change a run actually made and no
  timestamp inference is ever needed to say which calls shared a realization."
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
