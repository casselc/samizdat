;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-serve
  "Explicit local-only server entry point backed by embedded Oscope/Durable.

  This launcher never selects the standalone OTLP or Langfuse exporters. The
  caller must name a non-secret Durable root with --durable-root or
  SAMIZDAT_EMBEDDED_DURABLE_ROOT; telemetry content defaults off."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jolt.host]
            [samizdat.core :as core]
            [samizdat.system :as system]
            [samizdat.telemetry.embedded :as embedded]))

(def durable-root-env "SAMIZDAT_EMBEDDED_DURABLE_ROOT")

;; The embedded lifecycle deliberately makes :closing and thrown attempts
;; retryable. A launcher must drive that contract, but never without a bound.
(def max-stop-attempts 100)
(def stop-retry-ms 25)

(defn- invalid-configuration! []
  (throw
   (ex-info
    (str "embedded telemetry requires --durable-root PATH or "
         durable-root-env)
    {:samizdat.telemetry.embedded-serve/error true
     :type ::invalid-configuration})))

(defn durable-root
  "Resolve the required non-secret Durable root. An explicit CLI flag wins;
  otherwise use SAMIZDAT_EMBEDDED_DURABLE_ROOT. Rejected values are not
  retained in exception data."
  [args]
  (let [args (vec args)
        from-cli? (= "--durable-root" (first args))
        root (cond
               (and from-cli? (= 2 (count args))) (second args)
               (empty? args) (jolt.host/getenv durable-root-env)
               :else nil)]
    (if (and (string? root) (not (str/blank? root)))
      root
      (invalid-configuration!))))

(defn pause-before-retry!
  "Launcher-owned bounded retry cadence; public only as a deterministic test
  seam."
  []
  (Thread/sleep stop-retry-ms))

(defn- safe-stop-result [result]
  (if (map? result)
    (select-keys result [:status :phase])
    {:status :unknown :phase :unknown}))

(defn- log-safely!
  "Lifecycle logging is observational: a backend or rendering failure must
  never skip cleanup or replace its deterministic terminal outcome."
  [log!]
  (try
    (log!)
    (catch Throwable _ nil)))

(defn- stop-capability-until-closed!
  "Drive one serialized stop capability to a confirmed close."
  [stop-attempt!]
  (loop [attempt 1]
    (let [result (try
                   (stop-attempt!)
                   (catch Throwable _ {:status :error :phase :unknown}))]
      (cond
        (= :closed (:status result)) result

        (< attempt max-stop-attempts)
        (do (pause-before-retry!) (recur (inc attempt)))

        :else
        (throw
         (ex-info
          "embedded telemetry did not confirm shutdown within its retry bound"
          {:samizdat.telemetry.embedded-serve/error true
           :type ::stop-not-closed
           :attempts max-stop-attempts
           :last-result (safe-stop-result result)}))))))

(defn stop-until-closed!
  "Retry the captured embedded lifecycle until it confirms :closed or the
  launcher bound is exhausted. Thrown attempts remain retryable. The terminal
  failure contains only bounded status, never paths, telemetry, or causes."
  [runtime]
  (stop-capability-until-closed! #(embedded/stop! runtime)))

(defn- log-stop-failure! [failure]
  (log-safely!
   (fn []
     (let [{:keys [attempts last-result application-stop]} (ex-data failure)]
       (if (= :failed application-stop)
         (log/error "embedded telemetry shutdown not closed after" attempts
                    "attempts; last result" (pr-str last-result)
                    "; application stop also failed")
         (log/error "embedded telemetry shutdown not closed after" attempts
                    "attempts; last result" (pr-str last-result)))))))

(defn- dual-stop-failure
  "Keep Durable-close failure primary while retaining only a bounded category
  for the application-stop failure. Never retain its throwable, message, data,
  path, or cause in the combined operator-visible failure."
  [embedded-failure]
  (ex-info
   "embedded telemetry did not close; application stop also failed"
   (assoc
    (select-keys
     (ex-data embedded-failure)
     [:samizdat.telemetry.embedded-serve/error
      :type :attempts :last-result])
    :application-stop :failed)))

(defn- terminal-stop
  "Wrap one bounded cleanup and share its first returned or thrown outcome.
  Every shutdown face observes the identical terminal result without driving
  application or embedded cleanup again."
  [stop!]
  (let [pending (Object.)
        outcome (atom pending)]
    (fn []
      (locking outcome
        (let [terminal @outcome]
          (if (identical? pending terminal)
            (try
              (let [result (stop!)]
                (reset! outcome {:result result})
                result)
              (catch Throwable failure
                (reset! outcome {:failure failure})
                (throw failure)))
            (if-let [failure (:failure terminal)]
              (throw failure)
              (:result terminal))))))))

(defn stop-owned!
  "Stop application ingress/resources before retiring the embedded SDK,
  query source, checkpoint, and Durable connection. Logs a bounded failure
  before rethrowing so Jolt's isolated shutdown-hook runner cannot hide it."
  [runtime]
  (log-safely!
   #(log/info "stopping Samizdat application before embedded telemetry"))
  (let [application-failure (try
                              (system/stop!)
                              nil
                              (catch Throwable failure failure))
        _ (log-safely!
           #(log/info "Samizdat application stop attempt completed; retiring embedded telemetry"))
        embedded-outcome (try
                           {:result (stop-until-closed! runtime)}
                           (catch Throwable failure {:failure failure}))]
    (if-let [embedded-failure (:failure embedded-outcome)]
      (let [terminal-failure (if application-failure
                               (dual-stop-failure embedded-failure)
                               embedded-failure)]
        (log-stop-failure! terminal-failure)
        (throw terminal-failure))
      (let [result (:result embedded-outcome)]
        (log-safely!
         #(log/info "embedded telemetry stopped"
                    (pr-str (safe-stop-result result))))
        (if application-failure
          (do
            (log-safely!
             #(log/error "Samizdat application stop failed; embedded telemetry closed"))
            (throw application-failure))
          result)))))

(defn- stop-incomplete-startup!
  "Retire the narrow retry capability returned by embedded/start!."
  [retry-stop!]
  (log-safely!
   #(log/info "retiring incomplete embedded telemetry startup"))
  (try
    (let [result (stop-capability-until-closed! retry-stop!)]
      (log-safely!
       #(log/info "incomplete embedded telemetry startup retired"
                  (pr-str (safe-stop-result result))))
      result)
    (catch Throwable failure
      (log-stop-failure! failure)
      (throw failure))))

(defn- start-owner! [args publish-stop!]
  (try
    (embedded/start! {:durable-root (durable-root args)
                      ;; Embedded mode is explicit and local-only;
                      ;; never consult content or exporter env.
                      :content {:enabled? false}})
    (catch Throwable failure
      (if-let [retry-stop! (:retry-stop! (ex-data failure))]
        (let [stop! (terminal-stop
                     #(stop-incomplete-startup! retry-stop!))]
          ;; Publish before driving the capability ourselves. If a signal is
          ;; already waiting, both faces serialize through this exact closure.
          (publish-stop! stop!)
          (stop!)
          (throw failure))
        (throw failure)))))

(defn run!
  "Start the local embedded owner and the ordinary Samizdat server.

  The bootstrap publishes `stop!` to its already-armed shutdown hook before
  application ingress opens. The finally covers normal return and throws; both
  faces share the same idempotent ordered cleanup."
  [args publish-stop!]
  (let [runtime (start-owner! args publish-stop!)
        stop! (terminal-stop #(stop-owned! runtime))]
    (try
      (when (publish-stop! stop!)
        (core/-main))
      (finally
        (stop!)))))
