;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-test
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [oscope.embedded :as oscope]
            [otel.sdk :as sdk]
            [samizdat.telemetry.embedded :as embedded]
            [samizdat.telemetry.hook :as hook]
            [samizdat.telemetry.otel :as tel]))

(deftest owner-builds-local-durable-runtime-and-delegates-lifecycle
  (let [calls (atom [])
        db-spec* (atom nil)
        oscope-lifecycle {:source ::source :sdk-handle ::sdk}]
    (with-redefs [local-posix/local-backend
                  (fn [root] (swap! calls conj [:backend root]) ::backend)
                  durable/writer-dbspec
                  (fn [spec] (reset! db-spec* spec) spec)
                  oscope/start!
                  (fn [options]
                    (swap! calls conj [:start options])
                    oscope-lifecycle)
                  oscope/force-flush!
                  (fn [lifecycle]
                    (swap! calls conj [:flush lifecycle]) true)
                  oscope/status
                  (fn [lifecycle]
                    (swap! calls conj [:status lifecycle]) {:phase :open})
                  oscope/stop!
                  (fn [lifecycle]
                    (swap! calls conj [:stop lifecycle])
                    {:status :closed :phase :closed})
                  sdk/tracer
                  (fn [& args] (swap! calls conj [:tracer args]) ::tracer)
                  tel/content-policy-from-env
                  (fn [] (throw (AssertionError. "must not read content env")))]
      (let [lifecycle
            (embedded/start! {:durable-root "/durable"
                              :durable {:owner "stable-owner"
                                        :database "stable-db"
                                        :scratch-parent "/scratch"
                                        :lease-ttl-ms 45000
                                        :instance "must-not-be-reused"}
                              :content {:enabled? false}})]
        (try
          (is (= ::backend (:backend @db-spec*)))
          (is (= "stable-owner" (:owner @db-spec*)))
          (is (= "stable-db" (:database @db-spec*)))
          (is (= "/scratch" (:scratch-parent @db-spec*)))
          (is (= 45000 (:lease-ttl-ms @db-spec*)))
          (is (not= "must-not-be-reused" (:instance @db-spec*)))
          (is (.startsWith (:instance @db-spec*) "stable-owner-"))
          (is (= ::source (:source lifecycle)))
          (is (= :external (:owner @tel/runtime)))
          (is (true? (embedded/flush! lifecycle)))
          (is (= {:phase :open} (embedded/status lifecycle)))
          (let [stopped (embedded/stop! lifecycle)]
            (is (= {:status :closed :phase :closed} stopped))
            (is (= stopped (embedded/stop! lifecycle))))
          (is (= 1 (count (filter #(= :stop (first %)) @calls))))
          (is (not (hook/installed?)))
          (is (nil? (find-ns 'oscope.server)))
          (finally
            (tel/shutdown!)))))))

(deftest concurrent-start-has-one-owner-and-one-winner
  (let [ready-a (promise)
        ready-b (promise)
        start-gate (promise)
        starts (atom 0)]
    (with-redefs [local-posix/local-backend (constantly ::backend)
                  durable/writer-dbspec identity
                  oscope/start! (fn [& _]
                                  (swap! starts inc)
                                  {:source ::source})
                  oscope/stop! (constantly {:status :closed :phase :closed})
                  sdk/tracer (constantly ::tracer)]
      (letfn [(attempt [ready]
                (deliver ready true)
                @start-gate
                (try
                  {:lifecycle
                   (embedded/start! {:durable-root "/durable"
                                     :content {:enabled? false}})}
                  (catch Throwable error {:error error})))]
        (let [attempt-a (future (attempt ready-a))
              attempt-b (future (attempt ready-b))]
          (try
            (is (= true (deref ready-a 5000 ::timeout)))
            (is (= true (deref ready-b 5000 ::timeout)))
            (deliver start-gate true)
            (let [results [(deref attempt-a 5000 ::timeout)
                           (deref attempt-b 5000 ::timeout)]
                  winners (keep :lifecycle results)
                  failures (keep :error results)]
              (is (= 1 (count winners)))
              (is (= 1 (count failures)))
              (is (= :samizdat.telemetry.embedded/owner-active
                     (:type (ex-data (first failures)))))
              (is (= 1 @starts))
              (is (identical? (:attached-runtime (first winners))
                              @tel/runtime))
              (is (= :closed (:status (embedded/stop! (first winners))))))
            (finally
              (deliver start-gate true)
              (tel/shutdown!))))))))

(deftest closing-and-thrown-stop-attempts-remain-retryable
  (let [attempts (atom [{:status :closing :phase :persisting}
                        (ex-info "retryable close failure" {:attempt 2})
                        {:status :closed :phase :closed}
                        {:status :closed :phase :closed}])
        calls (atom 0)
        starts (atom 0)]
    (with-redefs [local-posix/local-backend (constantly ::backend)
                  durable/writer-dbspec identity
                  oscope/start! (fn [& _]
                                  (swap! starts inc)
                                  {:source ::source})
                  oscope/stop!
                  (fn [_]
                    (swap! calls inc)
                    (let [result (first @attempts)]
                      (swap! attempts subvec 1)
                      (if (instance? Throwable result)
                        (throw result)
                        result)))
                  sdk/tracer (constantly ::tracer)]
      (let [lifecycle (embedded/start! {:durable-root "/durable"
                                        :content {:enabled? false}})]
        (try
          (is (= {:status :closing :phase :persisting}
                 (embedded/stop! lifecycle)))
          (let [blocked (try
                          (embedded/start! {:durable-root "/durable"
                                            :content {:enabled? false}})
                          (catch Throwable error error))]
            (is (= :samizdat.telemetry.embedded/owner-active
                   (:type (ex-data blocked))))
            (is (= 1 @starts)))
          (is (thrown-with-msg? Throwable #"retryable close failure"
                                (embedded/stop! lifecycle)))
          (let [closed (embedded/stop! lifecycle)]
            (is (= {:status :closed :phase :closed} closed))
            (is (= closed (embedded/stop! lifecycle))))
          (let [replacement (embedded/start! {:durable-root "/durable"
                                              :content {:enabled? false}})]
            (is (= 2 @starts))
            (is (= :closed (:status (embedded/stop! replacement)))))
          (is (= 4 @calls))
          (finally
            (tel/shutdown!)))))))

(deftest concurrent-stop-attempts-serialize-and-cache-only-closed
  (let [entered (promise)
        release-first (promise)
        calls (atom 0)]
    (with-redefs [local-posix/local-backend (constantly ::backend)
                  durable/writer-dbspec identity
                  oscope/start! (constantly {:source ::source})
                  oscope/stop!
                  (fn [_]
                    (case (swap! calls inc)
                      1 (do (deliver entered true)
                            @release-first
                            {:status :closing :phase :persisting})
                      2 {:status :closed :phase :closed}
                      (throw (AssertionError. "closed result was not cached"))))
                  sdk/tracer (constantly ::tracer)]
      (let [lifecycle (embedded/start! {:durable-root "/durable"
                                        :content {:enabled? false}})
            first-stop (future (embedded/stop! lifecycle))]
        (try
          (is (= true (deref entered 5000 ::timeout)))
          (let [second-stop (future (embedded/stop! lifecycle))]
            (is (= ::blocked (deref second-stop 100 ::blocked)))
            (deliver release-first true)
            (is (= #{{:status :closing :phase :persisting}
                     {:status :closed :phase :closed}}
                   #{(deref first-stop 5000 ::timeout)
                     (deref second-stop 5000 ::timeout)}))
            (is (= {:status :closed :phase :closed}
                   (embedded/stop! lifecycle)))
            (is (= 2 @calls)))
          (finally
            (tel/shutdown!)))))))

(deftest failed-attach-exposes-only-a-retryable-stop-capability
  (doseq [[label first-stop]
          [[:closing {:status :closing :phase :persisting
                      :errors [{:private "must not escape"}] }]
           [:thrown (ex-info "private cleanup failure" {:path "/private"})]]]
    (testing (name label)
      (let [attach-error (ex-info "attach failed" {:stage :attach})
            attempts (atom [first-stop {:status :closed :phase :closed}])
            calls (atom 0)]
        (with-redefs [local-posix/local-backend (constantly ::backend)
                      durable/writer-dbspec identity
                      oscope/start! (constantly {:source ::source})
                      oscope/stop!
                      (fn [_]
                        (swap! calls inc)
                        (let [result (first @attempts)]
                          (swap! attempts subvec 1)
                          (if (instance? Throwable result)
                            (throw result)
                            result)))
                      sdk/tracer (constantly ::tracer)
                      tel/attach! (fn [& _] (throw attach-error))]
          (let [failure (try
                          (embedded/start! {:durable-root "/durable"
                                            :content {:enabled? false}})
                          (catch Throwable error error))
                data (ex-data failure)
                retry-stop! (:retry-stop! data)]
            (is (= :samizdat.telemetry.embedded/attach-cleanup-incomplete
                   (:type data)))
            (is (identical? attach-error (.getCause failure)))
            (is (= (if (= :closing label)
                     {:status :closing :phase :persisting}
                     {:status :error :phase :unknown})
                   (:cleanup data)))
            (is (= #{:samizdat.telemetry.embedded/error :type :cleanup
                     :retry-stop!}
                   (set (keys data))))
            (is (fn? retry-stop!))
            (let [closed (retry-stop!)]
              (is (= {:status :closed :phase :closed} closed))
              (is (= closed (retry-stop!))))
            (is (= 2 @calls))))))))

(deftest a-stale-closed-owner-cannot-stop-a-new-runtime
  (let [owners (atom [{:source ::old} {:source ::new}])
        stopped (atom [])]
    (with-redefs [local-posix/local-backend (constantly ::backend)
                  durable/writer-dbspec identity
                  oscope/start! (fn [& _]
                                  (let [owner (first @owners)]
                                    (swap! owners subvec 1)
                                    owner))
                  oscope/stop! (fn [owner]
                                 (swap! stopped conj owner)
                                 {:status :closed :phase :closed})
                  sdk/tracer (constantly ::tracer)]
      (let [old (embedded/start! {:durable-root "/durable"
                                  :content {:enabled? false}})]
        (try
          (is (= :closed (:status (embedded/stop! old))))
          (let [current (embedded/start! {:durable-root "/durable"
                                          :content {:enabled? false}})
                current-runtime @tel/runtime
                race (promise)
                stale-stops [(future @race (embedded/stop! old))
                             (future @race (embedded/stop! old))]]
            (deliver race true)
            (is (= [{:status :closed :phase :closed}
                    {:status :closed :phase :closed}]
                   (mapv #(deref % 5000 ::timeout) stale-stops)))
            (is (identical? current-runtime @tel/runtime))
            (is (= [{:source ::old}] @stopped))
            (is (nil? (embedded/flush! old)))
            (is (identical? current-runtime @tel/runtime))
            (is (= :closed (:status (embedded/stop! current))))
            (is (= [{:source ::old} {:source ::new}] @stopped)))
          (finally
            (tel/shutdown!)))))))

(defn- delete-tree! [root]
  (when (and root (.exists root))
    (doseq [file (reverse (file-seq root))]
      (java.nio.file.Files/deleteIfExists (.toPath file)))))

(defn- closed-source-error [source]
  (try
    ((:load-command source)
     :after-stop
     {:signal :spans :field :span-name :window :15m :limit 10})
    nil
    (catch Throwable error error)))

(deftest real-native-round-trip-when-runtime-supports-durable-wal
  (let [directory (java.nio.file.Files/createTempDirectory
                   "samizdat-embedded-telemetry-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        root (java.io.File. (str directory))
        durable-root (str (.resolve directory "telemetry-store"))
        options {:durable-root durable-root
                 :durable {:scratch-parent (str directory)
                           :lease-ttl-ms 30000}
                 :sdk-options {:service-name "samizdat-embedded-test"
                               :processor :simple
                               :metrics? false
                               :runtime-metrics? false
                               :logs? false}}
        lifecycles* (atom [])]
    (try
      (let [lifecycle (embedded/start! options)
            source (:source lifecycle)]
        (swap! lifecycles* conj lifecycle)
        (testing "Oscope is the single SDK owner and Samizdat only attaches"
          (is (= :external (:owner @tel/runtime)))
          (is (hook/installed?))
          (is (identical? (sdk/tracer-provider)
                          (:tracer-provider
                           (:sdk-handle (:oscope lifecycle))))))

        (is (= {:status :ok}
               (hook/observe! :embedded-test
                              {:case-id "stage-3"}
                              (constantly {:status :ok}))))
        (is (true? (embedded/flush! lifecycle)))
        (let [rows (get-in
                    ((:load-command source)
                     :embedded-test-span
                     {:signal :spans :field :span-name
                      :window :15m :limit 20})
                    [:table :rows])]
          (is (some #(= "lifecycle.embedded-test" (:value %)) rows)))

        (is (= :open (:phase (embedded/status lifecycle))))
        (let [stopped (embedded/stop! lifecycle)]
          (is (= {:status :closed :phase :closed} stopped))
          (is (= stopped (embedded/stop! lifecycle)))
          (is (not (hook/installed?)))
          (is (= :closed (:phase (oscope/status (:oscope lifecycle)))))
          (let [error (closed-source-error source)]
            (is (instance? clojure.lang.ExceptionInfo error))
            (is (= :oscope.live/closed (:type (ex-data error))))))

        (testing "a fresh instance recovers and queries the same Durable root"
          (let [reopened (embedded/start! options)
                reopened-source (:source reopened)]
            (swap! lifecycles* conj reopened)
            (is (not= (:instance (:db-spec lifecycle))
                      (:instance (:db-spec reopened))))
            (let [rows (get-in
                        ((:load-command reopened-source)
                         :embedded-test-reopened
                         {:signal :spans :field :span-name
                          :window :15m :limit 20})
                        [:table :rows])]
              (is (some #(= "lifecycle.embedded-test" (:value %)) rows)))
            (is (= {:status :closed :phase :closed}
                   (embedded/stop! reopened)))
            (is (= :closed (:phase (oscope/status (:oscope reopened))))))))
      (finally
        (doseq [lifecycle @lifecycles*]
          (try (embedded/stop! lifecycle) (catch Throwable _ nil)))
        (try (tel/shutdown!) (catch Throwable _ nil))
        (delete-tree! root)
        (is (not (.exists root)))))))
