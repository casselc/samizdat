;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.inference
  "Append-only InferenceEpoch provenance.  An epoch is fixed immediately
  before one provider invocation; turn settlement merely references it."
  (:require [clojure.string :as str]
            [samizdat.store.db :as db]))

(declare get-epoch)

(defn begin!
  [conn {:keys [id run-id branch-id turn provider model
                binding-id spec-id runtime]}]
  (doseq [[k v] {:id id :run-id run-id :branch-id branch-id :turn turn}]
    (when (or (nil? v) (str/blank? (str v)))
      (throw (ex-info (str "InferenceEpoch needs " (name k)) {k v}))))
  (db/with-writer
    (db/execute! conn
                 ["INSERT INTO inference_epochs
                     (id, run_id, branch_id, turn, provider, model,
                      binding_id, spec_id, runtime, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                  (str id) (str run-id) (str branch-id) (long turn)
                  (if provider (name provider) "") (or model "")
                  binding-id spec-id runtime (db/now)]))
  (get-epoch conn id))

(defn get-epoch [conn id]
  (db/fetch-one conn ["SELECT * FROM inference_epochs WHERE id = ?" (str id)]))

(defn for-run [conn run-id]
  (db/fetch conn ["SELECT * FROM inference_epochs WHERE run_id = ?
                    ORDER BY created_at, id" (str run-id)]))
