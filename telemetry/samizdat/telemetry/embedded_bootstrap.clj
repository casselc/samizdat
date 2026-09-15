;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-bootstrap
  "Signal-safe bootstrap for the explicit embedded telemetry launcher.

  Keep this namespace's static dependency surface deliberately tiny. The first
  application action registers Jolt's centralized shutdown hook, before a
  dynamic require can load Oscope, chDB, or any of their native worker pools."
  (:require [jolt.host]))

(def publication-wait-ms 120000)

(defn lifecycle-cell
  "A private handoff between the early shutdown hook and the eventual owner."
  []
  {:state (atom {:phase :starting :shutdown-requested? false})
   :lock (Object.)
   :published (java.util.concurrent.CountDownLatch. 1)
   :cleaned (java.util.concurrent.CountDownLatch. 1)})

(defn wait-for-publication!
  "Bounded wait seam used when a signal arrives while the owner is starting."
  [{:keys [published]}]
  (.await published publication-wait-ms
          java.util.concurrent.TimeUnit/MILLISECONDS))

(defn wait-for-cleanup!
  "Bounded wait seam used when another face already owns cleanup."
  [{:keys [cleaned]}]
  (.await cleaned publication-wait-ms
          java.util.concurrent.TimeUnit/MILLISECONDS))

(defn publish-cleanup!
  "Publish the exact owner cleanup. Returns false when shutdown arrived during
  construction, so the launcher must not open application ingress."
  [{:keys [state lock published]} stop!]
  (let [continue?
        (locking lock
          (let [{:keys [phase shutdown-requested?]} @state]
            (when-not (= :starting phase)
              (throw (ex-info "embedded bootstrap owner publication is no longer valid"
                              {:type ::invalid-publication :phase phase})))
            (reset! state {:phase :ready
                           :shutdown-requested? shutdown-requested?
                           :stop! stop!})
            (not shutdown-requested?)))]
    (.countDown published)
    continue?))

(defn startup-failed!
  "Wake a hook waiting on construction without retaining the startup error."
  [{:keys [state lock published]}]
  (locking lock
    (when (= :starting (:phase @state))
      (reset! state {:phase :startup-failed
                     :shutdown-requested? (:shutdown-requested? @state)})))
  (.countDown published)
  nil)

(defn cleanup!
  "Request shutdown and invoke the published cleanup at most once.

  A hook that arrives during construction waits only for the documented bound.
  Concurrent hook/finally callers share the same terminal result."
  [{:keys [state lock cleaned] :as cell}]
  (let [phase
        (locking lock
          (swap! state assoc :shutdown-requested? true)
          (:phase @state))]
    (when (= :starting phase)
      (wait-for-publication! cell))
    (let [{:keys [winner? wait? stop! terminal]}
          (locking lock
            (case (:phase @state)
              :ready
              (let [stop! (:stop! @state)]
                (swap! state assoc :phase :cleaning)
                {:winner? true :stop! stop!})

              :cleaning {:wait? true}
              :closed {:terminal (:terminal @state)}
              :cleanup-failed {:terminal (:terminal @state)}
              ;; A startup failure or publication timeout has no acquired
              ;; owner to clean. The launcher thread owns any later result.
              nil))]
      (cond
        winner?
        (try
          (let [result (stop!)]
            (locking lock
              (reset! state {:phase :closed
                             :shutdown-requested? true
                             :terminal result}))
            (.countDown cleaned)
            result)
          (catch Throwable failure
            (locking lock
              (reset! state {:phase :cleanup-failed
                             :shutdown-requested? true
                             :terminal failure}))
            (.countDown cleaned)
            (throw failure)))

        wait?
        (let [completed? (wait-for-cleanup! cell)
              {:keys [phase terminal]} (locking lock @state)]
          (case phase
            :closed terminal
            :cleanup-failed (throw terminal)
            (if completed?
              (throw
               (ex-info "embedded cleanup completed without a terminal result"
                        {:type ::invalid-cleanup-result :phase phase}))
              (throw
               (ex-info "timed out waiting for embedded cleanup"
                        {:type ::cleanup-wait-timeout
                         :wait-ms publication-wait-ms})))))

        (instance? Throwable terminal) (throw terminal)
        :else terminal))))

(defn resolve-launcher!
  "Dynamic boundary: no embedded dependency loads before the hook is armed."
  []
  (requiring-resolve 'samizdat.telemetry.embedded-serve/run!))

(defn run! [args]
  (let [cell (lifecycle-cell)
        hook #(cleanup! cell)]
    ;; First application action. Registration both records the callback and
    ;; arms Jolt's centralized INT/HUP/TERM sigwait owner.
    (jolt.host/add-shutdown-hook hook)
    (try
      ((resolve-launcher!) args #(publish-cleanup! cell %))
      (catch Throwable failure
        (startup-failed! cell)
        (throw failure))
      (finally
        (cleanup! cell)))))

(defn -main [& args]
  (run! args))
