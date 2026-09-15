;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.pipelines-test
  "Bounded, independent destinations: a throwing or slow exporter drops or
  fails on its own queue and never blocks the other, shutdown is bounded,
  diagnostics are scalar."
  (:require [clojure.test :refer [deftest is testing]]
            [otel.exporter.memory :as memory]
            [otel.sdk :as otel-sdk]
            [otel.sdk.export :as export]
            [otel.sdk.tracer :as sdk]
            [otel.trace :as trace]
            [samizdat.telemetry.hook :as hook]
            [samizdat.telemetry.otel :as tel]))

(defrecord ThrowingExporter []
  export/SpanExporter
  (export-spans! [_ _] (throw (ex-info "collector down: secret=hunter2" {})))
  (flush-exporter! [_] true)
  (shutdown-exporter! [_] true))

(defrecord SlowExporter [delay-ms]
  export/SpanExporter
  (export-spans! [_ _] (Thread/sleep delay-ms) true)
  (flush-exporter! [_] true)
  (shutdown-exporter! [_] true))

(defn- emit! [n]
  (dotimes [i n]
    (tel/with-tool [_ (str "t" i) {"samizdat.tool.name" "x"}])))

(deftest one-dead-destination-does-not-block-the-other
  (tel/shutdown!)
  (let [mem (memory/exporter)]
    (tel/init! {:mode :dual :exporters {:local mem :langfuse (->ThrowingExporter)}
                :batch {:schedule-delay-ms 50}})
    (try
      (emit! 5)
      (let [flush (tel/flush!)
            st (tel/stats)]
        (is (= 5 (count (memory/spans mem))))
        (is (= 5 (get-in st [:local :exported-span-count])))
        (is (= 0 (get-in st [:local :failed-span-count])))
        (is (= 5 (get-in st [:langfuse :failed-span-count])))
        (is (= 0 (get-in st [:langfuse :exported-span-count])))
        (is (true? (get-in flush [:local :ok?])))
        (is (false? (get-in flush [:langfuse :ok?])))
        (is (not (re-find #"hunter2" (pr-str [flush st]))) "exporter errors never reach diagnostics"))
      (finally (tel/shutdown!)))))

(deftest queue-overflow-drops-and-counts
  (tel/shutdown!)
  (let [mem (memory/exporter)]
    (tel/init! {:mode :dual :exporters {:local mem :langfuse (->SlowExporter 400)}
                :batch {:max-queue-size 8 :max-export-batch-size 4 :schedule-delay-ms 100000}})
    (try
      (emit! 40)
      (let [st (tel/stats)]
        (is (= 32 (get-in st [:langfuse :dropped-count])))
        (is (= 32 (get-in st [:local :dropped-count])) "each queue is bounded on its own")
        (is (<= (get-in st [:langfuse :queue-size]) 8)))
      (finally
        (let [t0 (System/nanoTime)
              r (tel/shutdown!)
              ms (/ (- (System/nanoTime) t0) 1e6)]
          (is (map? r))
          (is (contains? r :langfuse))
          (is (< ms 5000) (str "shutdown bounded, took " ms " ms")))))))

(deftest shutdown-is-exactly-once-and-uninstalls
  (tel/shutdown!)
  (tel/init! {:mode :local :exporters {:local (memory/exporter)}})
  (let [r1 (tel/shutdown!)
        r2 (tel/shutdown!)]
    (is (map? r1))
    (is (nil? r2))
    (is (nil? (tel/stats)))
    (is (not (samizdat.telemetry.hook/installed?)))))

(deftest attach-dispatches-to-the-external-owner-only
  (tel/shutdown!)
  (let [calls (atom {:sdk-init 0 :provider 0 :get-tracer 0 :provider-shutdown 0
                     :flush 0 :stats 0 :shutdown 0})
        supplied-tracer trace/noop-tracer
        callbacks {:tracer supplied-tracer
                   :flush! #(do (swap! calls update :flush inc) {:flushed :external})
                   :stats #(do (swap! calls update :stats inc) {:queued 3})
                   :shutdown! #(do (swap! calls update :shutdown inc) {:stopped :external})}
        rt (with-redefs [otel-sdk/init!
                         (fn [& _]
                           (swap! calls update :sdk-init inc)
                           (throw (ex-info "SDK initialization is externally owned" {})))
                         sdk/tracer-provider
                         (fn [& _]
                           (swap! calls update :provider inc)
                           (throw (ex-info "provider construction is externally owned" {})))
                         sdk/get-tracer
                         (fn [& _]
                           (swap! calls update :get-tracer inc)
                           (throw (ex-info "tracer construction is externally owned" {})))]
             (tel/attach! callbacks))]
    (try
      (is (identical? supplied-tracer (:tracer rt)))
      (is (not (contains? rt :provider)))
      (is (not (contains? rt :pipelines)))
      (is (= 0 (:sdk-init @calls)))
      (is (= 0 (:provider @calls)))
      (is (= 0 (:get-tracer @calls)))
      (is (hook/installed?))
      (is (= :observed (hook/observe! :external-test {} (constantly :observed))))
      (is (identical? rt
                      (tel/attach! {:tracer :ignored
                                    :flush! #(throw (ex-info "replaced flush" {}))
                                    :stats #(throw (ex-info "replaced stats" {}))
                                    :shutdown! #(throw (ex-info "replaced shutdown" {}))})))
      (is (= {:queued 3} (tel/stats)))
      (is (= {:flushed :external} (tel/flush!)))
      (is (= {:flushed :external} (tel/flush!)))
      (is (= {:stopped :external}
             (with-redefs [sdk/shutdown!
                           (fn [& _]
                             (swap! calls update :provider-shutdown inc)
                             (throw (ex-info "must not shut down external provider" {})))]
               (tel/shutdown!))))
      (is (nil? (tel/shutdown!)))
      (is (= {:sdk-init 0 :provider 0 :get-tracer 0 :provider-shutdown 0
              :flush 2 :stats 1 :shutdown 1}
             @calls))
      (is (not (hook/installed?)))
      (finally
        (tel/shutdown!)
        (hook/uninstall!)))))

(deftest failed-attach-restores-publication-and-can-retry
  (tel/shutdown!)
  (let [initial-policy {:enabled? false :max-chars 31}
        initial-observer (fn [_ _ thunk] (thunk))
        partial-observer (fn [_ _ thunk] (thunk))
        boom (ex-info "hook install failed" {:stage :install})
        external-shutdowns (atom 0)]
    (reset! tel/content-policy initial-policy)
    (hook/install! initial-observer)
    (try
      (let [failure
            (with-redefs [tel/install!
                          (fn []
                            (hook/install! partial-observer)
                            (throw boom))]
              (try
                (tel/attach! {:tracer trace/noop-tracer
                              :flush! (constantly true)
                              :stats (constantly {})
                              :shutdown! #(swap! external-shutdowns inc)
                              :content {:enabled? true :max-chars 71}})
                (catch Throwable error error)))]
        (is (identical? boom failure))
        (is (nil? @tel/runtime))
        (is (= initial-policy @tel/content-policy))
        (is (identical? initial-observer @hook/observer))
        (is (zero? @external-shutdowns)
            "a failed attachment never assumes shutdown ownership")
        (let [rt (tel/attach! {:tracer trace/noop-tracer
                               :flush! (constantly :flushed)
                               :stats (constantly {:ready true})
                               :shutdown! #(do (swap! external-shutdowns inc)
                                               :stopped)
                               :content {:enabled? false}})]
          (is (identical? rt @tel/runtime))
          (is (hook/installed?))
          (is (= :flushed (tel/flush!)))
          (is (= :stopped (tel/shutdown!)))
          (is (= 1 @external-shutdowns))))
      (finally
        (tel/shutdown!)
        (hook/uninstall!)
        (reset! tel/content-policy
                {:enabled? false :max-chars tel/default-content-max-chars})))))

(deftest flush-and-stats-finish-before-concurrent-shutdown
  (doseq [[label callback-key operation expected]
          [[:flush :flush! tel/flush! :flushed]
           [:stats :stats tel/stats {:queued 1}]]]
    (testing (name label)
      (tel/shutdown!)
      (let [operation-entered (promise)
            release-operation (promise)
            shutdown-attempted (promise)
            shutdown-entered (promise)
            blocking-callback
            #(do (deliver operation-entered true)
                 @release-operation
                 expected)
            callbacks (assoc {:tracer trace/noop-tracer
                              :flush! (constantly :flushed)
                              :stats (constantly {:queued 1})
                              :shutdown! #(do (deliver shutdown-entered true)
                                              :stopped)}
                             callback-key blocking-callback)]
        (tel/attach! callbacks)
        (let [operation-result (future (operation))]
          (is (= true (deref operation-entered 5000 ::timeout)))
          (let [shutdown-result
                (future
                  (deliver shutdown-attempted true)
                  (tel/shutdown!))]
            (is (= true (deref shutdown-attempted 5000 ::timeout)))
            (is (= ::blocked (deref shutdown-entered 100 ::blocked))
                "shutdown callback cannot enter during flush/stats")
            (deliver release-operation true)
            (is (= expected (deref operation-result 5000 ::timeout)))
            (is (= :stopped (deref shutdown-result 5000 ::timeout)))
            (is (= true (deref shutdown-entered 5000 ::timeout)))))))))

(deftest attached-flush-and-stats-callbacks-may-reenter-the-runtime-lock
  (tel/shutdown!)
  (try
    (tel/attach! {:tracer trace/noop-tracer
                  :flush! #(vector :flush (tel/stats))
                  :stats (constantly :stats)
                  :shutdown! (constantly :stopped)})
    (is (= [:flush :stats]
           (deref (future (tel/flush!)) 5000 ::deadlock)))
    (tel/shutdown!)

    (tel/attach! {:tracer trace/noop-tracer
                  :flush! (constantly :flushed)
                  :stats #(vector :stats (tel/flush!))
                  :shutdown! (constantly :stopped)})
    (is (= [:stats :flushed]
           (deref (future (tel/stats)) 5000 ::deadlock)))
    (tel/shutdown!)

    (tel/attach! {:tracer trace/noop-tracer
                  :flush! #(vector :flush (tel/shutdown!))
                  :stats (constantly :stats)
                  :shutdown! (constantly :stopped)})
    (is (= [:flush :stopped]
           (deref (future (tel/flush!)) 5000 ::deadlock)))
    (is (nil? @tel/runtime))
    (finally
      (tel/shutdown!)
      (hook/uninstall!))))

(deftest init-still-constructs-one-internally-owned-runtime
  (tel/shutdown!)
  (let [mem (memory/exporter)
        provider-fn sdk/tracer-provider
        get-tracer-fn sdk/get-tracer
        calls (atom {:provider 0 :get-tracer 0})]
    (try
      (with-redefs [sdk/tracer-provider
                    (fn [opts]
                      (swap! calls update :provider inc)
                      (provider-fn opts))
                    sdk/get-tracer
                    (fn [provider opts]
                      (swap! calls update :get-tracer inc)
                      (get-tracer-fn provider opts))]
        (let [rt (tel/init! {:mode :local :exporters {:local mem}})]
          (is (some? (:provider rt)))
          (is (some? (:pipelines rt)))
          (is (= [:local] (:destinations rt)))
          (is (identical? rt (tel/init! {:mode :dual})))
          (is (= {:provider 1 :get-tracer 1} @calls))
          (is (map? (tel/flush!)))
          (is (map? (tel/shutdown!)))
          (is (nil? (tel/shutdown!)))
          (is (not (hook/installed?)))))
      (finally
        (tel/shutdown!)
        (hook/uninstall!)))))

(deftest failed-init-cleans-partial-ownership-and-can-retry
  (tel/shutdown!)
  (let [initial-policy {:enabled? false :max-chars 23}
        initial-observer (fn [_ _ thunk] (thunk))
        boom (ex-info "get-tracer failed" {:stage :tracer})
        pipeline-fn export/independent-batch-pipelines
        provider-fn sdk/tracer-provider
        provider-shutdown-fn sdk/shutdown!
        pipeline-shutdown-fn export/shutdown-pipelines!
        calls (atom {:pipeline-created 0 :provider-created 0 :get-tracer 0
                     :provider-cleanup 0 :pipeline-cleanup 0})]
    (reset! tel/content-policy initial-policy)
    (hook/install! initial-observer)
    (try
      (let [failure
            (with-redefs [export/independent-batch-pipelines
                          (fn [destinations]
                            (swap! calls update :pipeline-created inc)
                            (pipeline-fn destinations))
                          sdk/tracer-provider
                          (fn [opts]
                            (swap! calls update :provider-created inc)
                            (provider-fn opts))
                          sdk/get-tracer
                          (fn [& _]
                            (swap! calls update :get-tracer inc)
                            (throw boom))
                          sdk/shutdown!
                          (fn [provider]
                            (swap! calls update :provider-cleanup inc)
                            (provider-shutdown-fn provider))
                          export/shutdown-pipelines!
                          (fn [pipelines]
                            (swap! calls update :pipeline-cleanup inc)
                            (pipeline-shutdown-fn pipelines))]
              (try
                (tel/init! {:mode :local
                            :exporters {:local (memory/exporter)}
                            :content {:enabled? true :max-chars 99}})
                (catch Throwable error error)))]
        (is (identical? boom failure))
        (is (= {:pipeline-created 1 :provider-created 1 :get-tracer 1
                :provider-cleanup 1 :pipeline-cleanup 1}
               @calls))
        (is (nil? @tel/runtime))
        (is (= initial-policy @tel/content-policy))
        (is (identical? initial-observer @hook/observer))
        (hook/uninstall!)
        (let [rt (tel/init! {:mode :local :exporters {:local (memory/exporter)}})]
          (is (some? (:provider rt)))
          (is (hook/installed?))
          (is (map? (tel/shutdown!)))))
      (finally
        (tel/shutdown!)
        (hook/uninstall!)
        (reset! tel/content-policy
                {:enabled? false :max-chars tel/default-content-max-chars})))))

(deftest config-failure-before-resource-creation-leaves-state-untouched
  (tel/shutdown!)
  (let [initial-policy {:enabled? false :max-chars 29}
        initial-observer (fn [_ _ thunk] (thunk))
        boom (ex-info "destination config failed" {:stage :destinations})
        cleanup-calls (atom 0)]
    (reset! tel/content-policy initial-policy)
    (hook/install! initial-observer)
    (try
      (let [failure
            (with-redefs [tel/destinations (fn [& _] (throw boom))
                          sdk/shutdown! (fn [& _] (swap! cleanup-calls inc))
                          export/shutdown-pipelines! (fn [& _] (swap! cleanup-calls inc))]
              (try
                (tel/init! {:mode :local :content {:enabled? true}})
                (catch Throwable error error)))]
        (is (identical? boom failure))
        (is (zero? @cleanup-calls))
        (is (nil? @tel/runtime))
        (is (= initial-policy @tel/content-policy))
        (is (identical? initial-observer @hook/observer)))
      (finally
        (hook/uninstall!)
        (reset! tel/content-policy
                {:enabled? false :max-chars tel/default-content-max-chars})))))

(deftest throwing-external-shutdown-is-not-retried
  (tel/shutdown!)
  (let [calls (atom 0)]
    (tel/attach! {:tracer trace/noop-tracer
                  :flush! (constantly true)
                  :stats (constantly {})
                  :shutdown! #(do (swap! calls inc)
                                  (throw (ex-info "external stop failed" {})))})
    (is (thrown-with-msg? Throwable #"external stop failed" (tel/shutdown!)))
    (is (nil? (tel/shutdown!)))
    (is (= 1 @calls))
    (is (not (hook/installed?)))))

(deftest attached-tracer-uses-the-shared-content-policy
  (tel/shutdown!)
  (let [mem (memory/exporter)
        provider (sdk/tracer-provider {:processors [(export/simple-processor mem)]})
        tracer (sdk/get-tracer provider {:name "attached-content-test"})]
    (tel/attach! {:tracer tracer
                  :flush! #(sdk/force-flush! provider)
                  :stats (constantly {:owner :test})
                  :shutdown! #(sdk/shutdown! provider)
                  :content {:enabled? true :max-chars 64}})
    (tel/with-generation [sp "attached.content" {"samizdat.observation.mode" "live"
                                                   "langfuse.observation.input" "attached input"}]
      (tel/set-facts! sp {"langfuse.observation.output" "attached output"}))
    (tel/flush!)
    (let [attrs (:attributes (first (memory/spans mem)))]
      (is (= "attached input" (get attrs "langfuse.observation.input")))
      (is (= "attached output" (get attrs "langfuse.observation.output"))))
    (is (tel/content-enabled?))
    (is (= 64 (:max-chars @tel/content-policy)))
    (tel/shutdown!)
    (is (not (tel/content-enabled?)))
    (is (= tel/default-content-max-chars (:max-chars @tel/content-policy))))
  (with-redefs [tel/content-policy-from-env
                (constantly {:enabled? true :max-chars 17})]
    (tel/attach! {:tracer trace/noop-tracer
                  :flush! (constantly true)
                  :stats (constantly {})
                  :shutdown! (constantly true)})
    (is (= {:enabled? true :max-chars 17} @tel/content-policy))
    (tel/shutdown!)))

(deftest concurrent-init-and-attach-publish-one-runtime
  (tel/shutdown!)
  (let [mem (memory/exporter)
        provider-fn sdk/tracer-provider
        provider-entered (promise)
        release-provider (promise)
        attach-started (promise)
        provider-calls (atom 0)
        external-shutdowns (atom 0)]
    (with-redefs [sdk/tracer-provider
                  (fn [opts]
                    (swap! provider-calls inc)
                    (deliver provider-entered true)
                    @release-provider
                    (provider-fn opts))]
      (let [init-future (future
                          (tel/init! {:mode :local :exporters {:local mem}}))]
        (is (= true (deref provider-entered 5000 ::timeout)))
        (let [attach-future
              (future
                (deliver attach-started true)
                (tel/attach! {:tracer trace/noop-tracer
                              :flush! (constantly true)
                              :stats (constantly {})
                              :shutdown! #(swap! external-shutdowns inc)}))]
          (is (= true (deref attach-started 5000 ::timeout)))
          (deliver release-provider true)
          (let [init-rt (deref init-future 5000 ::timeout)
                attach-rt (deref attach-future 5000 ::timeout)]
            (is (not= ::timeout init-rt))
            (is (not= ::timeout attach-rt))
            (is (identical? init-rt attach-rt))
            (is (= 1 @provider-calls))
            (is (some? (:provider init-rt)))
            (is (nil? (:owner init-rt)))
            (is (= 0 @external-shutdowns))))))
    (tel/shutdown!)))

(deftest concurrent-external-shutdown-has-one-winner
  (tel/shutdown!)
  (let [start (promise)
        callback-entered (promise)
        release-callback (promise)
        calls (atom 0)]
    (tel/attach! {:tracer trace/noop-tracer
                  :flush! (constantly true)
                  :stats (constantly {})
                  :shutdown! #(do (swap! calls inc)
                                  (deliver callback-entered true)
                                  @release-callback
                                  :external-stopped)})
    (let [stop #(future @start (tel/shutdown!))
          a (stop)
          b (stop)]
      (deliver start true)
      (is (= true (deref callback-entered 5000 ::timeout)))
      (deliver release-callback true)
      (let [results [(deref a 5000 ::timeout) (deref b 5000 ::timeout)]]
        (is (= 1 (get (frequencies results) :external-stopped 0)))
        (is (= 1 (get (frequencies results) nil 0)))
        (is (= 1 @calls))
        (is (not (hook/installed?)))))))

(deftest concurrent-and-reentrant-internal-shutdown-has-one-winner
  (tel/shutdown!)
  (tel/init! {:mode :local :exporters {:local (memory/exporter)}})
  (let [shutdown-fn sdk/shutdown!
        start (promise)
        callback-entered (promise)
        release-callback (promise)
        calls (atom 0)
        reentrant-result (atom ::unset)]
    (with-redefs [sdk/shutdown!
                  (fn [provider]
                    (swap! calls inc)
                    (reset! reentrant-result (tel/shutdown!))
                    (deliver callback-entered true)
                    @release-callback
                    (shutdown-fn provider))]
      (let [stop #(future @start (tel/shutdown!))
            a (stop)
            b (stop)]
        (deliver start true)
        (is (= true (deref callback-entered 5000 ::timeout)))
        (deliver release-callback true)
        (let [results [(deref a 5000 ::timeout) (deref b 5000 ::timeout)]]
          (is (= 1 (count (filter map? results))))
          (is (= 1 (count (filter nil? results))))
          (is (= 1 @calls))
          (is (nil? @reentrant-result))
          (is (not (hook/installed?))))))))

(deftest mode-parsing
  (is (= :off (tel/parse-mode nil)))
  (is (= :off (tel/parse-mode "off")))
  (is (= :local (tel/parse-mode " LOCAL ")))
  (is (= :dual (tel/parse-mode "dual")))
  (is (thrown? Throwable (tel/parse-mode "cloud"))))

(deftest langfuse-destination-requires-headers-and-host
  (testing "no header env var -> refuse to build, and the message names the var, not a value"
    (is (thrown-with-msg? Throwable #"SAMIZDAT_TEST_UNSET_HEADERS must contain Authorization"
                          (tel/destinations :langfuse {:host "https://example.invalid"
                                                       :headers-env "SAMIZDAT_TEST_UNSET_HEADERS"}))))
  (is (thrown-with-msg? Throwable #"LANGFUSE_HOST" (tel/langfuse-traces-url nil)))
  (is (= "https://h.example/api/public/otel/v1/traces" (tel/langfuse-traces-url "https://h.example/"))))
