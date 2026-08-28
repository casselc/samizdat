;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.embed
  "In-process ownership facade for hosts that do not want Samizdat's HTTP server.

  One facade owns the process-global userspace binding at a time. Runs execute
  on host-owned futures recorded in the facade, so close! can signal every run,
  join for a caller-supplied bound, and only then release userspace and SQLite."
  (:require [samizdat.agent.beam :as beam]
            [samizdat.api.control :as control]
            [samizdat.api.runs :as api-runs]
            [samizdat.events :as events]
            [samizdat.llm.registry :as registry]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [samizdat.system :as system]
            [samizdat.userspace :as userspace]))

(defonce ^:private current (atom nil))

(defn- positive-timeout [value key]
  (if (and (number? value) (pos? value))
    (long value)
    (throw (ex-info (str (name key) " must be a positive number") {key value}))))

(defn- assert-open! [embedded]
  (when-not (and (identical? embedded @current)
                 (= :open @(:state embedded)))
    (throw (ex-info "embedded Samizdat lifecycle is not open" {})))
  embedded)

(defn open!
  "Configure and open Samizdat without starting its HTTP server.

  `overrides` has the same shape as samizdat.config/load-config. Provider state,
  database migration, project policy binding, and adapter selection are ready
  when this returns. A second embedded lifecycle fails closed."
  ([] (open! nil))
  ([overrides]
   (let [reservation (Object.)]
     (when-not (compare-and-set! current nil reservation)
       (throw (ex-info "an embedded Samizdat lifecycle is already open" {})))
     (let [conn* (atom nil)
           owner (Object.)
           claimed? (atom false)]
       (try
         (when (system/started?)
           (throw (ex-info "the served Samizdat system is already running" {})))
         (let [config (system/prepare-config! overrides)
               conn (db/open! (get-in config [:db :path]))
               _ (reset! conn* conn)
               _ (system/bind-project! owner conn)
               _ (reset! claimed? true)
               embedded {:config config
                         :conn conn
                         :adapter (registry/adapter-for
                                   (get-in config [:llm :provider]))
                         :owner owner
                         :state (atom :open)
                         :runs (atom {})
                         :lock (Object.)
                         :db-closed? (atom false)
                         :close-result (atom nil)}]
           (reset! current embedded)
           embedded)
         (catch Throwable e
           (when @claimed?
             (try (userspace/release! owner) (catch Throwable _ nil)))
           (when-let [conn @conn*]
             (try (db/close conn) (catch Throwable _ nil)))
           (compare-and-set! current reservation nil)
           (throw e)))))))

(defn config [embedded]
  (:config (assert-open! embedded)))

(defn connection [embedded]
  (:conn (assert-open! embedded)))

(defn start-run!
  "Start one real beam run on a facade-owned future.

  `opts` is beam/run!'s run-specific map and must include :problem and a
  caller-chosen positive :start-timeout-ms. Returns {:run-id :abort :future};
  dereference :future for the beam result. A timeout signals abort and throws,
  while the facade retains the future for bounded close."
  [embedded {:keys [start-timeout-ms on-start] :as opts}]
  (let [timeout-ms (positive-timeout start-timeout-ms :start-timeout-ms)
        started (promise)
        abort (atom false)
        launch-id (str (random-uuid))
        future*
        (locking (:lock embedded)
          (assert-open! embedded)
          (swap! (:runs embedded) assoc launch-id
                 {:launch-id launch-id :abort abort :started started})
          (let [f (future
                    (try
                      (beam/run!
                       (-> opts
                           (dissoc :start-timeout-ms)
                           (assoc :conn (:conn embedded)
                                  :config (:config embedded)
                                  :llm-adapter (:adapter embedded)
                                  :llm-config (get-in embedded [:config :llm])
                                  :abort abort
                                  :on-start
                                  (fn [run-id]
                                    (deliver started {:run-id run-id})
                                    (when on-start (on-start run-id))))))
                      (catch Throwable e
                        (deliver started {:error e})
                        (throw e))
                      (finally
                        (swap! (:runs embedded) dissoc launch-id))))]
            ;; An instant run may already have removed itself. Never resurrect
            ;; that completed entry while installing its future handle.
            (swap! (:runs embedded)
                   (fn [active]
                     (if (contains? active launch-id)
                       (assoc-in active [launch-id :future] f)
                       active)))
            f))
        outcome (deref started timeout-ms ::start-timeout)]
    (cond
      (= ::start-timeout outcome)
      (do (reset! abort true)
          (throw (ex-info "embedded run did not start before its timeout"
                          {:timeout-ms timeout-ms :abort abort :future future*})))

      (:error outcome) (throw (:error outcome))

      :else {:run-id (:run-id outcome) :abort abort :future future*})))

(defn abort!
  "Signal a run and durably mark it aborted if its row is still running."
  [embedded {:keys [run-id abort]}]
  (locking (:lock embedded)
    (assert-open! embedded)
    (reset! abort true)
    (pos? (runs/finish-run! (:conn embedded) run-id :aborted nil))))

(defn intervene!
  "Submit one control intervention against an embedded run."
  [embedded run-id body]
  (locking (:lock embedded)
    (control/intervene! (:conn (assert-open! embedded)) run-id body)))

(defn get-run
  "The durable run read model, or nil when `run-id` does not exist."
  [embedded run-id]
  (locking (:lock embedded)
    (api-runs/get-run (:conn (assert-open! embedded)) run-id)))

(defn journal-tail
  "Durable journal events after `cursor`, bounded by `limit`."
  [embedded run-id cursor limit]
  (locking (:lock embedded)
    (api-runs/journal-tail (:conn (assert-open! embedded)) run-id cursor limit)))

(defn subscribe
  "Subscribe to lossy process-wide wakeups. Recover content with journal-tail."
  ([embedded]
   (locking (:lock embedded)
     (assert-open! embedded)
     (events/subscribe)))
  ([embedded buffer-size]
   (locking (:lock embedded)
     (assert-open! embedded)
     (events/subscribe buffer-size))))

(defn unsubscribe! [channel]
  (events/unsubscribe! channel))

(defn- remaining-ms [deadline]
  (max 0 (- deadline (System/currentTimeMillis))))

(defn close!
  "Abort all active runs, bounded-join them, then release userspace and SQLite.

  `timeout-ms` is one caller-chosen bound shared by all joins, not a fresh bound
  per run. If any run remains live, returns retryable
  {:status :closing :hung-run-ids [...]} and retains every owned resource.
  Once every run terminates, cleanup completes and later calls idempotently
  return the final {:status :closed ...} result."
  [embedded timeout-ms]
  (let [timeout-ms (positive-timeout timeout-ms :timeout-ms)]
    (locking (:lock embedded)
      (if-let [result @(:close-result embedded)]
        result
        (do
          (when-not (identical? embedded @current)
            (throw (ex-info "embedded lifecycle does not own this process" {})))
          (reset! (:state embedded) :closing)
          (let [active (vals @(:runs embedded))
                _ (doseq [{:keys [abort]} active] (reset! abort true))
                deadline (+ (System/currentTimeMillis) timeout-ms)
                hung (reduce (fn [ids {:keys [launch-id future started]}]
                               (try
                                 (let [result (deref future (remaining-ms deadline)
                                                     ::hung)]
                                   (if (= ::hung result)
                                     (conj ids (or (:run-id (deref started 0 {}))
                                                   launch-id))
                                     ids))
                                 (catch Throwable e
                                   ;; The run failed but it did terminate. Keep
                                   ;; closing resources; its future still joined.
                                   ids)))
                             [] active)
                hung (vec (remove nil? hung))]
            (if (seq hung)
              ;; A bounded wait is not permission to close a database underneath
              ;; a still-running beam. Keep the lease and connection, and let the
              ;; host retry close after the reported runs reach a boundary.
              {:status :closing :hung-run-ids hung :errors []}
              (let [errors (atom [])]
                ;; No run can read userspace now. Close SQLite while its lease is
                ;; still held, so a close failure remains retryable and prevents
                ;; another lifecycle from binding over this half-closed owner.
                (when-not @(:db-closed? embedded)
                  (try
                    (db/close (:conn embedded))
                    (reset! (:db-closed? embedded) true)
                    (catch Throwable e (swap! errors conj e))))
                (when (empty? @errors)
                  (try (userspace/release! (:owner embedded))
                       (catch Throwable e (swap! errors conj e))))
                (if (seq @errors)
                  {:status :closing :hung-run-ids [] :errors @errors}
                  (do
                    (reset! (:state embedded) :closed)
                    (compare-and-set! current embedded nil)
                    (let [result {:status :closed
                                  :hung-run-ids []
                                  :errors []}]
                      (reset! (:close-result embedded) result)
                      result)))))))))))
