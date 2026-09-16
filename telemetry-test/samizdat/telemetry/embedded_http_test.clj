;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-http-test
  (:require [clojure.test :refer [deftest is testing]]
            [oscope.ui.events :as events]
            [oscope.ui.web :as web]
            [oscope.ui.workbench :as workbench]
            [otel.context :as context]
            [samizdat.telemetry.embedded-http :as http]))

;; Capture immediately after loading the borrowed adapter, before the runner
;; requires or executes any later test namespace. This proves this namespace's
;; dependency closure rather than depending on suite execution order.
(def ^:private forbidden-namespaces-after-adapter-load
  (into {}
        (map (fn [symbol] [symbol (find-ns symbol)]))
        '[oscope.server oscope.otlp oscope.embedded.viewer
          oscope.ui.visualization-editor]))

(defn- start-viewer
  ([handlers] (start-viewer handlers {}))
  ([{:keys [workbench events oscope]
     :or {workbench (constantly nil)
          events (constantly nil)
          oscope (constantly nil)}} options]
   (with-redefs [workbench/handler (fn [_] workbench)
                 events/handler (fn [_] events)
                 web/handler (fn [source]
                               (is (nil? (:export-command source)))
                               (is (nil? (:export-admission source)))
                               oscope)]
     (http/start! {:source {:load-command identity
                            :export-command ::forbidden
                            :export-admission ::forbidden}
                   :oscope {:connection ::connection}}
                  "127.0.0.1:8080" options))))

(defn- request [uri]
  {:request-method :get :uri uri :headers {"Host" "127.0.0.1:8080"}})

(deftest exact-prefix-routing-and-fallback
  (let [application-calls (atom [])
        viewer (start-viewer
                {:workbench #(when (= "/oscope/telemetry" (:uri %))
                               {:status 210 :body "workbench"})
                 :events #(when (= "/oscope/events" (:uri %))
                            {:status 211 :body "events"})
                 :oscope #(when (= "/oscope" (:uri %))
                            {:status 212 :body "charts"})})
        handler (http/compose-handler
                 (fn [req]
                   (swap! application-calls conj (:uri req))
                   {:status 299 :body "application"})
                 viewer)]
    (is (= 212 (:status (handler (request "/oscope")))))
    (is (= 210 (:status (handler (request "/oscope/telemetry")))))
    (is (= 211 (:status (handler (request "/oscope/events")))))
    (is (= 404 (:status (handler (request "/oscope/unknown")))))
    (is (= 404 (:status (handler (request "/oscope/visualizations")))))
    (is (= 299 (:status (handler (request "/oscopes")))))
    (is (= 299 (:status (handler (request "/v1/traces")))))
    (is (= ["/oscopes" "/v1/traces"] @application-calls))
    (is (= {:status :closed :phase :closed} (http/stop! viewer)))))

(deftest host-guard-matches-oscope-contract
  (let [viewer (start-viewer {:oscope (constantly {:status 200})})]
    (doseq [headers [{} {"host" "localhost:8080"}
                     {:Host "127.0.0.1:8081"}]]
      (is (= {:status 421
              :headers {"Content-Type" "text/plain; charset=UTF-8"
                        "Cache-Control" "no-store"
                        "X-Content-Type-Options" "nosniff"
                        "Connection" "close"}
              :body "misdirected request\n"}
             (http/handle viewer (assoc (request "/oscope")
                                        :headers headers)))))
    (is (= 200 (:status (http/handle viewer (request "/oscope")))))
    (http/stop! viewer)))

(deftest binary-export-is-disabled-at-the-borrowed-boundary
  (with-redefs [workbench/handler (fn [_] (constantly nil))
                events/handler (fn [_] (constantly nil))]
    (let [viewer (http/start!
                  {:source {:load-command (fn [& _]
                                            (throw (AssertionError.
                                                    "export must not query")))
                            :export-command (fn [& _]
                                              (throw (AssertionError.
                                                      "export must be stripped")))}
                   :oscope {:connection ::connection}}
                  "127.0.0.1:8080")
          response (http/handle viewer (request "/oscope/export"))]
      (is (= 404 (:status response)))
      (is (= "raw export is unavailable" (:body response)))
      (http/stop! viewer))))

(deftest viewer-work-is-suppressed-and-caller-context-is-restored
  (let [observations (atom [])
        viewer (start-viewer
                {:oscope (fn [_]
                           (swap! observations conj
                                  (context/instrumentation-suppressed?))
                           {:status 200})})]
    (is (false? (context/instrumentation-suppressed?)))
    (is (= 200 (:status (http/handle viewer (request "/oscope")))))
    (is (= [true] @observations))
    (is (false? (context/instrumentation-suppressed?)))
    (http/stop! viewer)))

(deftest capacity-closing-and-drain-are-causal
  (let [entered (promise)
        release (promise)
        viewer (start-viewer
                {:oscope (fn [_]
                           (deliver entered true)
                           @release
                           {:status 200})}
                {:capacity 1 :drain-timeout-ms 5000})
        admitted (future (http/handle viewer (request "/oscope")))]
    (is (= true (deref entered 1000 ::timeout)))
    (let [full (http/handle viewer (request "/oscope"))]
      (is (= 503 (:status full)))
      (is (= "oscope viewer capacity reached\n" (:body full))))
    (let [stopping (future (http/stop! viewer))]
      (loop [attempt 0]
        (when (and (< attempt 100)
                   (not= :closing (:phase (http/status viewer))))
          (Thread/sleep 1)
          (recur (inc attempt))))
      (is (= :closing (:phase (http/status viewer))))
      (let [closing (http/handle viewer (request "/oscope"))]
        (is (= 503 (:status closing)))
        (is (= "oscope viewer is closing\n" (:body closing))))
      (is (= ::waiting (deref stopping 10 ::waiting)))
      (deliver release true)
      (is (= 200 (:status (deref admitted 1000 ::timeout))))
      (is (= {:status :closed :phase :closed}
             (deref stopping 1000 ::timeout)))
      (is (= {:phase :closed :active 0} (http/status viewer)))
      (is (= {:status :closed :phase :closed} (http/stop! viewer))))))

(deftest a-timed-out-drain-is-retryable
  (let [entered (promise)
        release (promise)
        viewer (start-viewer
                {:oscope (fn [_] (deliver entered true) @release {:status 200})}
                {:drain-timeout-ms 1})
        admitted (future (http/handle viewer (request "/oscope")))]
    (is (= true (deref entered 1000 ::timeout)))
    (is (= {:status :closing :phase :draining} (http/stop! viewer)))
    (deliver release true)
    (is (= 200 (:status (deref admitted 1000 ::timeout))))
    (is (= {:status :closed :phase :closed} (http/stop! viewer)))))

(deftest the-borrowed-surface-loads-no-receiver-or-second-listener
  (is (= {'oscope.server nil
          'oscope.otlp nil
          'oscope.embedded.viewer nil
          'oscope.ui.visualization-editor nil}
         forbidden-namespaces-after-adapter-load)))
