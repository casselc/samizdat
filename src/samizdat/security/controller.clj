;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.controller
  "Trusted, idempotent and audited run-budget extension authority."
  (:require [clojure.string :as str]
             [clojure.tools.logging :as log]
             [samizdat.prompt :as prompt]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(defn- hex [^bytes bs] (apply str (map #(format "%02x" (bit-and 0xff %)) bs)))
(defn- sha256 [s]
  (-> (java.security.MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes ^String s "UTF-8"))
      hex))

(defonce ^:private minted-token-digests (atom #{}))

(defrecord Authority [token-digest ceiling principal]
  Object
  (toString [_] (str "#samizdat.security.controller.Authority[" principal "]")))

(defmethod print-method Authority [a ^java.io.Writer w] (.write w (str a)))

(defn authority
  "Mint an opaque process-local authority from trusted config, or nil when no
  nonblank token is configured.  The token itself is never retained or
  returned."
  [{:keys [controller]}]
  (when-let [token (some-> (:budget-token controller) str str/trim not-empty)]
    (let [digest (sha256 token)]
      (swap! minted-token-digests conj digest)
      (->Authority digest (some-> (:budget-ceiling controller) long)
                   (or (:budget-principal controller) "human-controller")))))

(defn- authorized? [a]
  (and (instance? Authority a)
       (contains? @minted-token-digests (:token-digest a))))

(defn- refuse [code message & [data]]
  {:ok false :code code :message message :data (or data {})})

(defn- replay [row]
  {:ok true :replayed? true :run-id (:run_id row)
   :request-id (:request_id row) :principal (:principal row)
   :old-max (:old_max_turns row) :new-max (:new_max_turns row)
   :reason (:reason row) :extended-at (:created_at row) :reopened []})

(defn- same-request? [row run-id new-max]
  (and (= run-id (:run_id row)) (= new-max (:new_max_turns row))))

(defn extend-budget!
  "Raise max_turns under a minted authority.

  request requires :run-id, globally idempotent :request-id, positive
  monotonic :new-max and nonblank :reason.  Returns an :ok map or a structured
  refusal; cap/reopen/audit/event land in one storage transaction."
  [a conn {:keys [run-id request-id new-max reason principal]}]
  (if-not (authorized? a)
    (refuse :unauthorized "budget extension requires controller-minted authority")
    (let [bad (cond
                (or (not (string? run-id)) (str/blank? run-id)) :run-id
                (or (not (string? request-id)) (str/blank? request-id)) :request-id
                (not (and (integer? new-max) (pos? new-max))) :new-max
                (or (not (string? reason)) (str/blank? reason)) :reason)]
      (if bad
        (refuse :bad-request (str "invalid extension field: " (name bad)))
        (if-let [audit (runs/extension-audit conn request-id)]
          (if (same-request? audit run-id new-max)
            (replay audit)
            (refuse :request-conflict "one request id cannot name two extensions"
                    {:recorded {:run-id (:run_id audit)
                                :new-max (:new_max_turns audit)}}))
          (let [run (runs/get-run conn run-id)]
            (cond
              (nil? run) (refuse :unknown-run (str "no run " run-id))
              (or (contains? #{"completed" "aborted"} (:status run))
                  (:terminal_reason run))
              (refuse :terminal-run "a terminal run's budget cannot change")
              (<= new-max (:max_turns run))
               (refuse :not-monotonic
                       (prompt/render "controller-safety"
                                      {:extension-not-monotonic true})
                      {:current (:max_turns run)})
              (and (:ceiling a) (> new-max (:ceiling a)))
               (refuse :over-ceiling
                       (prompt/render "controller-safety"
                                      {:extension-over-ceiling true})
                      {:ceiling (:ceiling a)})
              :else
              (try
                (let [r (runs/extend-budget!
                         conn {:run-id run-id :request-id request-id
                               :principal (or principal (:principal a))
                               :old-max (:max_turns run) :new-max new-max
                               :reason reason})]
                  (log/info "budget extension" run-id (:max_turns run) "->" new-max
                            "request" request-id)
                  (assoc r :ok true :replayed? false))
                (catch Throwable e
                  (cond
                    (db/id-collision? e)
                    (if-let [row (runs/extension-audit conn request-id)]
                      (if (same-request? row run-id new-max)
                        (replay row)
                        (refuse :request-conflict
                                "one request id cannot name two extensions"))
                      (refuse :request-conflict "request id already exists"))
                    (= :stale (:budget/error (ex-data e)))
                     (refuse :concurrent-raise
                             (prompt/render "controller-safety"
                                            {:extension-concurrent true})
                            {:now (:max_turns (runs/get-run conn run-id))})
                    :else (throw e)))))))))))
