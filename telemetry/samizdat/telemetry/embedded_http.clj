;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-http
  "Borrow Oscope's read-only UI handlers on Samizdat's existing listener.

  This namespace owns neither a socket nor telemetry storage. It supplies the
  exact `/oscope` route boundary, DNS-rebinding guard, bounded synchronous
  admission, and a drainable lifecycle around the handlers backed by an open
  `samizdat.telemetry.embedded` owner."
  (:require [clojure.string :as str]
            [oscope.ui.events :as events]
            [oscope.ui.web :as web]
            [oscope.ui.workbench :as workbench]
            [otel.context :as context])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def default-capacity 8)
(def default-drain-timeout-ms 25)

(def ^:private text-headers
  {"Content-Type" "text/plain; charset=UTF-8"
   "Cache-Control" "no-store"
   "X-Content-Type-Options" "nosniff"})

(defn oscope-path?
  "True only for the owned mount and its slash-delimited descendants."
  [uri]
  (and (string? uri)
       (or (= "/oscope" uri) (str/starts-with? uri "/oscope/"))))

(defn- request-header [request header-name]
  (let [target (str/lower-case header-name)]
    (some (fn [[key value]]
            (when (= target
                     (str/lower-case
                      (if (keyword? key) (name key) (str key))))
              (str/trim (str value))))
          (:headers request))))

(defn- expected-authority [authority]
  (if (fn? authority) (authority) authority))

(defn- misdirected-response []
  {:status 421
   :headers (assoc text-headers "Connection" "close")
   :body "misdirected request\n"})

(defn- unavailable-response [reason]
  {:status 503
   :headers (assoc text-headers "Retry-After" "1" "Connection" "close")
   :body (str "oscope viewer " reason "\n")})

(defn- not-found-response []
  {:status 404 :headers text-headers :body "not found"})

(defn- acquire! [{:keys [lock state capacity]}]
  (locking lock
    (let [{:keys [phase active]} @state]
      (cond
        (not= :open phase) :closing
        (>= active capacity) :capacity
        :else (do (swap! state update :active inc) :acquired)))))

(defn- release! [{:keys [lock state drained]}]
  (locking lock
    (let [{:keys [phase active]} (swap! state update :active dec)]
      (when (and (= :closing phase) (zero? active))
        (.countDown drained)))))

(defn- dispatch [lifecycle request]
  (or ((:workbench-handler lifecycle) request)
      ((:events-handler lifecycle) request)
      ((:oscope-handler lifecycle) request)
      (not-found-response)))

(defn handle
  "Handle one owned `/oscope` request. Callers must check `oscope-path?`
  first; non-owned paths are deliberately not accepted here."
  [{:keys [authority] :as lifecycle} request]
  (let [expected (expected-authority authority)]
    (cond
      (or (nil? expected)
          (not= expected (request-header request "host")))
      (misdirected-response)

      :else
      (case (acquire! lifecycle)
        :closing (unavailable-response "is closing")
        :capacity (unavailable-response "capacity reached")
        :acquired
        (try
          ;; Querying the telemetry query store must not recursively create
          ;; telemetry about the viewer request itself.
          (context/with-instrumentation-suppressed
            (dispatch lifecycle request))
          (finally (release! lifecycle)))))))

(defn compose-handler
  "Mount one viewer lifecycle in front of the ordinary Samizdat handler.
  Similar-looking paths such as `/oscopes` fall through unchanged."
  [application-handler lifecycle]
  (fn [request]
    (if (oscope-path? (:uri request))
      (handle lifecycle request)
      (application-handler request))))

(defn start!
  "Create the handler-only lifecycle backed by an open embedded runtime.

  `authority` is the exact Host value accepted by Oscope and may be a thunk.
  Binary export is removed from the source and the visualization editor is
  not composed. No listener, receiver, worker pool, or OTel owner is started."
  ([runtime authority] (start! runtime authority {}))
  ([runtime authority {:keys [capacity drain-timeout-ms]
                       :or {capacity default-capacity
                            drain-timeout-ms default-drain-timeout-ms}}]
   (when-not (and (integer? capacity) (pos? capacity))
     (throw (ex-info "embedded Oscope viewer capacity must be positive" {})))
   (when-not (and (integer? drain-timeout-ms) (pos? drain-timeout-ms))
     (throw (ex-info "embedded Oscope drain timeout must be positive" {})))
   (let [source (dissoc (:source runtime) :export-command :export-admission)
         connection (get-in runtime [:oscope :connection])]
     (when-not (and (map? source) connection)
       (throw (ex-info "embedded Oscope viewer requires an open source and connection" {})))
     {:authority authority
      :capacity capacity
      :drain-timeout-ms drain-timeout-ms
      :state (atom {:phase :open :active 0})
      :lock (Object.)
      :drained (CountDownLatch. 1)
      :workbench-handler (workbench/handler connection)
      :events-handler (events/handler connection)
      :oscope-handler (web/handler source)})))

(defn stop!
  "Reject new viewer work, then wait a bounded time for admitted requests.
  A timeout returns retryable `:closing`; a later call can confirm `:closed`."
  [{:keys [lock state drained drain-timeout-ms]}]
  (let [already-closed?
        (locking lock
          (let [{:keys [phase active]} @state]
            (cond
              (= :closed phase) true
              (= :open phase)
              (do (swap! state assoc :phase :closing)
                  (when (zero? active) (.countDown drained))
                  false)
              :else false)))]
    (if already-closed?
      {:status :closed :phase :closed}
      (if (.await drained drain-timeout-ms TimeUnit/MILLISECONDS)
        (do (locking lock (swap! state assoc :phase :closed))
            {:status :closed :phase :closed})
        {:status :closing :phase :draining}))))

(defn status [lifecycle]
  (select-keys @(:state lifecycle) [:phase :active]))
