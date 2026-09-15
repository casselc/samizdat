;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded
  "Explicit local-only Oscope/chDB ownership for Samizdat telemetry.

  Nothing loads or starts this namespace by default. A caller supplies the
  Durable local root and may tune its non-secret writer policy. Oscope owns the
  sole process OTel SDK and its in-process chDB exporter; Samizdat attaches its
  existing hook observer to that tracer and delegates lifecycle operations back
  to Oscope. There is no listener and no remote/Langfuse pipeline here."
  (:require [clojure.string :as str]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [oscope.embedded :as oscope]
            [otel.sdk :as sdk]
            [samizdat.telemetry.otel :as tel]))

(def default-owner "samizdat")
(def default-database "default")
(def default-lease-ttl-ms 30000)

(def ^{:private true
       :doc "The nonterminal embedded Oscope owner, independent of tel/runtime."}
  active-owner (atom nil))

(def ^:private default-sdk-options
  {:service-name "samizdat"
   :processor :simple
   :metrics? false
   :runtime-metrics? false
   :logs? false})

(def ^:private default-content-policy
  {:enabled? false :max-chars tel/default-content-max-chars})

(defn- durable-dbspec [root options]
  (when-not (and (string? root) (not (str/blank? root)))
    (throw (ex-info "embedded telemetry requires a nonblank :durable-root" {})))
  ;; The backend/root names the logical store. owner plus the fresh process
  ;; instance fence its lease; database only seeds a new head. Durable creates
  ;; a unique scratch directory beneath the supplied parent.
  (let [options (-> (merge {:owner default-owner
                            :database default-database
                            :scratch-parent (System/getProperty "java.io.tmpdir")
                            :lease-ttl-ms default-lease-ttl-ms}
                           options)
                    (dissoc :backend :namespace-backend :object-id :instance
                            :vendor :read-only?))]
    (durable/writer-dbspec
     (assoc options
            :backend (local-posix/local-backend root)
            :instance (str (:owner options) "-" (random-uuid))))))

(defn- stop-attempt!
  [{:keys [oscope stop-lock closed-result] :as owner}]
  (locking tel/runtime
    (locking stop-lock
      (or @closed-result
          (let [result (oscope/stop! oscope)]
            ;; :closing is explicitly retryable, as is a thrown attempt. Only
            ;; a confirmed close retires this generation and is memoized.
            (when (= :closed (:status result))
              (reset! closed-result result)
              (compare-and-set! active-owner owner nil))
            result)))))

(defn- cleanup-status [result]
  (if (map? result)
    (select-keys result [:status :phase])
    {:status :unknown :phase :unknown}))

(defn- attach-cleanup-error [attach-error owner cleanup]
  (ex-info
   (str "embedded telemetry attachment failed; Oscope cleanup remains "
        "retryable and callers must invoke :retry-stop! until it returns :closed")
   {:samizdat.telemetry.embedded/error true
    :type ::attach-cleanup-incomplete
    :cleanup (cleanup-status cleanup)
    ;; A narrow capability only: do not expose lifecycle, db-spec, paths,
    ;; exceptions, or telemetry values at this boundary.
    :retry-stop! #(stop-attempt! owner)}
   attach-error))

(defn start!
  "Start one explicit local embedded telemetry owner.

  Required: `:durable-root`, the local Durable object-store directory.
  Optional: `:durable` writer policy, `:sdk-options`, and explicit `:content`.
  Content defaults off without consulting the environment. Returns a lifecycle
  containing the Oscope query `:source`; use flush!, status, and stop! below.
  Fails rather than replacing an already active Samizdat telemetry runtime."
  [{:keys [durable-root durable sdk-options content]}]
  (when-not (or (nil? durable) (map? durable))
    (throw (ex-info "embedded telemetry :durable must be a map" {})))
  (locking tel/runtime
    (when @active-owner
      (throw (ex-info "an embedded telemetry owner is still active"
                      {:samizdat.telemetry.embedded/error true
                       :type ::owner-active})))
    (when @tel/runtime
      (throw (ex-info "Samizdat telemetry is already active" {})))
    (let [db-spec (durable-dbspec durable-root durable)
          oscope-lifecycle
          (oscope/start! {:db-spec db-spec
                          :sdk-options (merge default-sdk-options sdk-options)})
          owner {:oscope oscope-lifecycle
                 :stop-lock (Object.)
                 :closed-result (atom nil)}]
      ;; Register Oscope ownership before hook attachment. A failed attachment
      ;; may retire tel/runtime, but this generation remains authoritative until
      ;; a serialized stop attempt confirms :closed.
      (reset! active-owner owner)
      (try
        (let [attached
              (tel/attach!
               {:tracer (sdk/tracer tel/scope-name {:version tel/scope-version})
                :flush! #(oscope/force-flush! oscope-lifecycle)
                :stats #(oscope/status oscope-lifecycle)
                :shutdown! #(stop-attempt! owner)
                ;; Local-only startup never reads content or header env vars.
                :content (or content default-content-policy)})
              lifecycle {:oscope oscope-lifecycle
                         :source (:source oscope-lifecycle)
                         :db-spec db-spec
                         :attached-runtime attached
                         :owner-state owner}]
          lifecycle)
        (catch Throwable attach-error
          ;; attach! is transactional. Oscope was already acquired, however,
          ;; so make one serialized retirement attempt. A retryable failure is
          ;; returned as a narrow cleanup capability rather than orphaning the
          ;; lifecycle or spinning forever inside start!.
          (let [cleanup
                (try
                  (stop-attempt! owner)
                  (catch Throwable _ {:status :error :phase :unknown}))]
            (when-not (= :closed (:status cleanup))
              (throw (attach-cleanup-error attach-error owner cleanup))))
          (throw attach-error))))))

(defn flush!
  "Flush the attached Samizdat runtime into its local chDB owner."
  [{:keys [attached-runtime owner-state]}]
  (locking tel/runtime
    (when (and (identical? owner-state @active-owner)
               (identical? attached-runtime @tel/runtime))
      (tel/flush!))))

(defn status
  "Return Oscope's bounded lifecycle status (no paths or telemetry values)."
  [{:keys [oscope]}]
  (oscope/status oscope))

(defn stop!
  "Uninstall Samizdat's hook, then let Oscope stop SDK, source, checkpoint, and
  Durable connection in its authoritative order. :closing and thrown attempts
  remain retryable; only :closed is memoized. A stale lifecycle cannot stop a
  later telemetry runtime."
  [{:keys [attached-runtime owner-state]}]
  (locking tel/runtime
    (or @(:closed-result owner-state)
        (if (and (identical? owner-state @active-owner)
                 (identical? attached-runtime @tel/runtime))
          (or (tel/shutdown!)
              (stop-attempt! owner-state))
          ;; A stale handle may advance only the Oscope lifecycle captured in
          ;; its own state; it never dispatches through whichever tel/runtime
          ;; happens to be current.
          (stop-attempt! owner-state)))))
