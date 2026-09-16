;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-serve-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.host]
            [samizdat.core :as core]
            [samizdat.system :as system]
            [samizdat.telemetry.embedded :as embedded]
            [samizdat.telemetry.embedded-http :as embedded-http]
            [samizdat.telemetry.embedded-serve :as serve]))

(deftest durable-root-is-explicit-and-file-safe
  (testing "the CLI flag wins without consulting telemetry or credential env"
    (let [lookups (atom [])]
      (with-redefs [jolt.host/getenv (fn [name]
                                      (swap! lookups conj name)
                                      (when (= name serve/durable-root-env)
                                        "/from-env"))]
        (is (= "/from-cli"
               (serve/durable-root ["--durable-root" "/from-cli"])))
        (is (empty? @lookups)))))
  (testing "the dedicated non-secret environment option is the fallback"
    (with-redefs [jolt.host/getenv #(when (= % serve/durable-root-env)
                                     "/from-env")]
      (is (= "/from-env" (serve/durable-root [])))))
  (doseq [args [[] ["--durable-root"] ["--unknown" "value"]]]
    (with-redefs [jolt.host/getenv (constantly nil)]
      (let [failure (try (serve/durable-root args)
                         (catch Throwable error error))]
        (is (= :samizdat.telemetry.embedded-serve/invalid-configuration
               (:type (ex-data failure))))))))

(deftest delegated-core-run-leaves-shutdown-to-its-owner
  (let [calls (atom [])]
    (with-redefs [system/start! (fn [handler]
                                 (swap! calls conj [:start handler]))
                  system/config (constantly {:http {:port 8080}
                                             :nrepl {:port 7888}
                                             :llm {:provider :local
                                                   :model "test"}})
                  core/warm-tls! (fn [_] (swap! calls conj :warm) :ok)
                  core/start-nrepl! (fn [port]
                                      (swap! calls conj [:nrepl port]))
                  jolt.host/add-shutdown-hook (fn [_]
                                                (swap! calls conj :hook))
                  jolt.host/park-until-interrupt #(swap! calls conj :park)
                  system/stop! #(swap! calls conj :system-stop)]
      (core/run! ::handler {:own-shutdown? false})
      (is (= [[:start ::handler] :warm [:nrepl 7888] :park] @calls)))))

(deftest launch-defaults-content-off-and-cleans-up-in-owner-order
  (let [calls (atom [])
        published-stop (atom nil)
        runtime {:runtime ::embedded}]
    (with-redefs [embedded/start! (fn [options]
                                    (swap! calls conj [:embedded-start options])
                                    runtime)
                  embedded/stop! (fn [actual]
                                   (swap! calls conj [:embedded-stop actual])
                                   {:status :closed :phase :closed})
                  embedded-http/start! (fn [actual _]
                                         (swap! calls conj [:viewer-start actual])
                                         ::viewer)
                  embedded-http/compose-handler (fn [_ actual]
                                                  (is (= ::viewer actual))
                                                  ::handler)
                  embedded-http/stop! (fn [actual]
                                        (swap! calls conj [:viewer-stop actual])
                                        {:status :closed :phase :closed})
                  core/record-exit! (fn [] (swap! calls conj [:record-exit]))
                  system/stop! (fn [] (swap! calls conj [:system-stop]) :stopped)
                  core/run! (fn [handler options]
                               (swap! calls conj [:core-run handler options])
                               :returned)]
      (is (= :returned
             (serve/run! ["--durable-root" "/durable"]
                         (fn [stop!]
                           (reset! published-stop stop!)
                           true))))
      (is (= [[:embedded-start {:durable-root "/durable"
                                :content {:enabled? false}}]
              [:viewer-start runtime]
              [:core-run ::handler {:own-shutdown? false}]
              [:record-exit]
              [:system-stop]
              [:viewer-stop ::viewer]
              [:embedded-stop runtime]]
             @calls))
      (is (fn? @published-stop))
      (reset! calls [])
      (@published-stop)
      (is (empty? @calls)
          "the hook and finally share one confirmed-close result"))))

(deftest cleanup-retries-only-until-a-confirmed-close
  (let [outcomes (atom [{:status :closing :phase :persisting}
                        (ex-info "private failure" {:path "/private"})
                        {:status :closed :phase :closed}])
        pauses (atom 0)]
    (with-redefs [embedded/stop! (fn [_]
                                   (let [outcome (first @outcomes)]
                                     (swap! outcomes subvec 1)
                                     (if (instance? Throwable outcome)
                                       (throw outcome)
                                       outcome)))
                  serve/pause-before-retry! #(swap! pauses inc)]
      (is (= {:status :closed :phase :closed}
             (serve/stop-until-closed! ::runtime)))
      (is (= 2 @pauses)))))

(deftest a-viewer-drain-failure-never-retires-its-query-source
  (let [embedded-stops (atom 0)]
    (with-redefs [core/record-exit! (constantly nil)
                  system/stop! (constantly :stopped)
                  embedded-http/stop! (constantly
                                       {:status :closing :phase :draining})
                  embedded/stop! (fn [_] (swap! embedded-stops inc))
                  serve/max-stop-attempts 1
                  serve/pause-before-retry! (constantly nil)]
      (let [failure (try (serve/stop-owned! ::runtime ::viewer)
                         (catch Throwable error error))]
        (is (= :samizdat.telemetry.embedded-serve/stop-not-closed
               (:type (ex-data failure))))
        (is (= {:status :closing :phase :draining}
               (:last-result (ex-data failure))))
        (is (zero? @embedded-stops)
            "the source remains open rather than closing under a reader")))))

(deftest core-failure-still-stops-system-before-the-owner
  (let [calls (atom [])
        core-failure (ex-info "core failed" {:stage :core})]
    (with-redefs [embedded/start! (constantly ::runtime)
                  embedded/stop! (fn [_]
                                   (swap! calls conj :embedded-stop)
                                   {:status :closed :phase :closed})
                  system/stop! (fn [] (swap! calls conj :system-stop))
                  embedded-http/start! (fn [& _] ::viewer)
                  embedded-http/compose-handler (fn [& _] ::handler)
                  embedded-http/stop! (constantly {:status :closed :phase :closed})
                  core/record-exit! (constantly nil)
                  core/run! (fn [& _]
                               (swap! calls conj :core-run)
                               (throw core-failure))]
      (let [failure (try (serve/run! ["--durable-root" "/durable"]
                                     (constantly true))
                         (catch Throwable error error))]
        (is (identical? core-failure failure))
        (is (= [:core-run :system-stop :embedded-stop] @calls))))))

(deftest viewer-construction-failure-retires-owner-before-ingress
  (let [calls (atom [])
        published (atom nil)
        startup-failure (ex-info "viewer failed" {:private "/path"})]
    (with-redefs [embedded/start! (constantly ::runtime)
                  embedded-http/start! (fn [& _] (throw startup-failure))
                  core/record-exit! (fn [] (swap! calls conj :record-exit))
                  core/run! (fn [& _] (swap! calls conj :ingress))
                  system/stop! (fn [] (swap! calls conj :system-stop))
                  embedded/stop! (fn [_]
                                   (swap! calls conj :embedded-stop)
                                   {:status :closed :phase :closed})]
      (let [failure (try
                      (serve/run! ["--durable-root" "/durable"]
                                  (fn [stop!]
                                    (reset! published stop!)
                                    true))
                      (catch Throwable error error))]
        (is (identical? startup-failure failure))
        (is (= [:record-exit :system-stop :embedded-stop] @calls))
        (is (fn? @published))
        (@published)
        (is (= [:record-exit :system-stop :embedded-stop] @calls))))))

(deftest application-stop-failure-still-retires-the-embedded-owner
  (let [calls (atom [])
        logs (atom [])
        system-failure (ex-info "private application stop failure"
                                {:path "/private/application"})]
    (with-redefs [system/stop! (fn []
                                (swap! calls conj :system-stop)
                                (throw system-failure))
                  embedded/stop! (fn [_]
                                   (swap! calls conj :embedded-stop)
                                   {:status :closed :phase :closed})
                  clojure.tools.logging/log* (fn [& args]
                                               (swap! logs conj args))]
      (let [failure (try (serve/stop-owned! ::runtime)
                         (catch Throwable error error))]
        (is (identical? system-failure failure))
        (is (= [:system-stop :embedded-stop] @calls))
        (is (= 4 (count @logs)))
        (is (not (.contains (pr-str @logs) "/private/application")))
        (is (not (.contains (pr-str @logs)
                            "private application stop failure")))))))

(deftest dual-stop-failure-prefers-sanitized-embedded-close-failure
  (let [calls (atom [])
        logs (atom [])
        system-failure (ex-info "private application stop failure"
                                {:path "/private/application"})]
    (with-redefs [system/stop! (fn []
                                (swap! calls conj :system-stop)
                                (throw system-failure))
                  embedded/stop! (fn [_]
                                   (swap! calls conj :embedded-stop)
                                   {:status :closing
                                    :phase :persisting
                                    :path "/private/durable"})
                  serve/max-stop-attempts 1
                  serve/pause-before-retry! (constantly nil)
                  clojure.tools.logging/log* (fn [& args]
                                               (swap! logs conj args))]
      (let [failure (try (serve/stop-owned! ::runtime)
                         (catch Throwable error error))
            rendered (str (pr-str (ex-data failure)) (pr-str @logs))]
        (is (= :samizdat.telemetry.embedded-serve/stop-not-closed
               (:type (ex-data failure))))
        (is (= :failed (:application-stop (ex-data failure))))
        (is (= {:status :closing :phase :persisting}
               (:last-result (ex-data failure))))
        (is (nil? (.getCause failure)))
        (is (= [:system-stop :embedded-stop] @calls))
        (is (= 3 (count @logs)))
        (is (not (.contains rendered "/private/application")))
        (is (not (.contains rendered "private application stop failure")))
        (is (not (.contains rendered "/private/durable")))))))

(deftest logging-failure-never-skips-owned-cleanup
  (let [calls (atom [])
        terminal {:status :closed :phase :closed}]
    (with-redefs [system/stop! #(swap! calls conj :system-stop)
                  embedded/stop! (fn [_]
                                   (swap! calls conj :embedded-stop)
                                   terminal)
                  clojure.tools.logging/log* (fn [& _]
                                               (throw
                                                (ex-info "logger failed"
                                                         {:path "/private/log"})))]
      (is (identical? terminal (serve/stop-owned! ::runtime)))
      (is (= [:system-stop :embedded-stop] @calls)))))

(deftest logging-failure-never-masks-terminal-cleanup-failure
  (let [attempts (atom 0)]
    (with-redefs [system/stop! (constantly :stopped)
                  embedded/stop! (fn [_]
                                   (swap! attempts inc)
                                   {:status :closing :phase :persisting})
                  serve/max-stop-attempts 1
                  serve/pause-before-retry! (constantly nil)
                  clojure.tools.logging/log* (fn [& _]
                                               (throw
                                                (ex-info "logger failed"
                                                         {:path "/private/log"})))]
      (let [failure (try (serve/stop-owned! ::runtime)
                         (catch Throwable error error))]
        (is (= :samizdat.telemetry.embedded-serve/stop-not-closed
               (:type (ex-data failure))))
        (is (= 1 @attempts))
        (is (not (.contains (pr-str (ex-data failure)) "/private/log")))))))

(deftest bounded-cleanup-failure-is-sanitized-and-operator-visible
  (let [logged (atom nil)]
    (with-redefs [embedded/stop! (constantly
                                  {:status :closing :phase :persisting
                                   :errors [{:path "/private"}]})
                  system/stop! (constantly :stopped)
                  serve/pause-before-retry! (constantly nil)
                  serve/max-stop-attempts 2
                  clojure.tools.logging/log* (fn [& args] (reset! logged args))]
      (let [failure (try (serve/stop-owned! ::runtime)
                         (catch Throwable error error))]
        (is (= :samizdat.telemetry.embedded-serve/stop-not-closed
               (:type (ex-data failure))))
        (is (= {:status :closing :phase :persisting}
               (:last-result (ex-data failure))))
        (is (= #{:samizdat.telemetry.embedded-serve/error :type
                 :attempts :last-result}
               (set (keys (ex-data failure)))))
        (is (not (.contains (pr-str (ex-data failure)) "/private")))
        (is (some? @logged))))))

(deftest incomplete-startup-exhaustion-retains-only-bounded-status
  (let [logs (atom [])
        ingress (atom 0)
        attempts (atom 0)
        startup-failure
        (ex-info "attachment failed"
                 {:retry-stop!
                  (fn []
                    (swap! attempts inc)
                    (throw (ex-info "private cleanup cause"
                                    {:path "/private/telemetry"})))}
                 (ex-info "private attachment cause"
                          {:credential "must-not-escape"}))]
    (with-redefs [embedded/start! (fn [_] (throw startup-failure))
                  serve/pause-before-retry! (constantly nil)
                  serve/max-stop-attempts 2
                  core/-main (fn [& _] (swap! ingress inc))
                  system/stop! (fn [] (throw (AssertionError.
                                              "system never started")))
                  clojure.tools.logging/log* (fn [& args]
                                               (swap! logs conj args))]
      (let [failure (try
                      (serve/run! ["--durable-root" "/durable"]
                                  (constantly true))
                      (catch Throwable error error))
            rendered (str (pr-str (ex-data failure)) (pr-str @logs))]
        (is (= :samizdat.telemetry.embedded-serve/stop-not-closed
               (:type (ex-data failure))))
        (is (= {:status :error :phase :unknown}
               (:last-result (ex-data failure))))
        (is (nil? (.getCause failure)))
        (is (= 2 @attempts))
        (is (zero? @ingress))
        (is (not (.contains rendered "/private/telemetry")))
        (is (not (.contains rendered "private cleanup cause")))
        (is (not (.contains rendered "private attachment cause")))
        (is (not (.contains rendered "must-not-escape")))))))
