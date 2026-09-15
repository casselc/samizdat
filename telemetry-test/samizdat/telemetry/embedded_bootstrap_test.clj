;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-bootstrap-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jolt.host]
            [samizdat.core :as core]
            [samizdat.system :as system]
            [samizdat.telemetry.embedded :as embedded]
            [samizdat.telemetry.embedded-serve :as serve]
            [samizdat.telemetry.embedded-bootstrap :as bootstrap]))

(deftest bootstrap-statically-requires-only-the-host
  (let [ns-form (read-string
                 (slurp (io/resource
                         "samizdat/telemetry/embedded_bootstrap.clj")))
        require-clause (some #(when (and (seq? %) (= :require (first %))) %)
                             ns-form)]
    (is (= '(:require [jolt.host]) require-clause))))

(deftest hook-is-armed-before-the-embedded-launcher-resolves
  (let [events (atom [])
        hook (atom nil)
        stops (atom 0)]
    (with-redefs [jolt.host/add-shutdown-hook
                  (fn [f] (swap! events conj :armed) (reset! hook f))
                  bootstrap/resolve-launcher!
                  (fn []
                    (swap! events conj :resolved)
                    (fn [_ publish!]
                      (swap! events conj :launcher)
                      (is (true? (publish! #(swap! stops inc))))
                      :returned))]
      (is (= :returned (bootstrap/run! ["--durable-root" "/durable"])))
      (is (= [:armed :resolved :launcher] @events))
      (is (= 1 @stops))
      (@hook)
      (is (= 1 @stops) "normal return and the hook share one cleanup"))))

(deftest signal-during-construction-prevents-ingress-and-cleans-on-publication
  (let [hook (atom nil)
        resolver-entered (promise)
        allow-publication (promise)
        hook-waiting (promise)
        stops (atom 0)
        ingress (atom 0)
        real-wait bootstrap/wait-for-publication!]
    (with-redefs [jolt.host/add-shutdown-hook #(reset! hook %)
                  bootstrap/wait-for-publication!
                  (fn [cell]
                    (deliver hook-waiting true)
                    (real-wait cell))
                  bootstrap/resolve-launcher!
                  (fn []
                    (deliver resolver-entered true)
                    (fn [_ publish!]
                      @allow-publication
                      (if (publish! #(swap! stops inc))
                        (swap! ingress inc)
                        :shutdown-before-ingress)))]
      (let [running (future (bootstrap/run! []))]
        @resolver-entered
        (let [signaling (future (@hook))]
          @hook-waiting
          (deliver allow-publication true)
          (is (= :shutdown-before-ingress @running))
          @signaling
          (is (= 1 @stops))
          (is (zero? @ingress)))))))

(deftest publication-wait-is-bounded-and-late-owner-still-cleans
  (let [cell (bootstrap/lifecycle-cell)
        waits (atom 0)
        stops (atom 0)]
    (with-redefs [bootstrap/wait-for-publication!
                  (fn [_]
                    (swap! waits inc)
                    false)]
      (is (nil? (bootstrap/cleanup! cell)))
      (is (= 1 @waits))
      (is (false? (bootstrap/publish-cleanup!
                   cell #(swap! stops inc))))
      (is (= 1 (bootstrap/cleanup! cell)))
      (is (= 1 @stops)))))

(deftest signal-observes-startup-failure-without-inventing-cleanup
  (let [hook (atom nil)
        resolver-entered (promise)
        release-failure (promise)
        hook-waiting (promise)
        original (ex-info "startup failed" {:private "/path"})
        real-wait bootstrap/wait-for-publication!]
    (with-redefs [jolt.host/add-shutdown-hook #(reset! hook %)
                  bootstrap/wait-for-publication!
                  (fn [cell]
                    (deliver hook-waiting true)
                    (real-wait cell))
                  bootstrap/resolve-launcher!
                  (fn []
                    (deliver resolver-entered true)
                    (fn [& _]
                      @release-failure
                      (throw original)))]
      (let [running (future (try (bootstrap/run! [])
                                 (catch Throwable failure failure)))]
        @resolver-entered
        (let [signaling (future (@hook))]
          @hook-waiting
          (deliver release-failure true)
          (is (identical? original @running))
          (is (nil? @signaling)))))))

(deftest signal-after-publication-shares-exactly-one-cleanup
  (let [hook (atom nil)
        published (promise)
        release-launcher (promise)
        stops (atom 0)]
    (with-redefs [jolt.host/add-shutdown-hook #(reset! hook %)
                  bootstrap/resolve-launcher!
                  (fn []
                    (fn [_ publish!]
                      (is (true? (publish! #(swap! stops inc))))
                      (deliver published true)
                      @release-launcher
                      :returned))]
      (let [running (future (bootstrap/run! []))]
        @published
        (is (= 1 (@hook)))
        (deliver release-launcher true)
        (is (= :returned @running))
        (is (= 1 @stops))))))

(deftest wired-bootstrap-preserves-core-failure-after-ordered-cleanup
  (let [calls (atom [])
        core-failure (ex-info "core failed" {:stage :core})]
    (with-redefs [jolt.host/add-shutdown-hook
                  (fn [_] (swap! calls conj :armed))
                  bootstrap/resolve-launcher! (constantly serve/run!)
                  embedded/start! (fn [_]
                                    (swap! calls conj :embedded-start)
                                    ::runtime)
                  embedded/stop! (fn [_]
                                   (swap! calls conj :embedded-stop)
                                   {:status :closed :phase :closed})
                  system/stop! (fn [] (swap! calls conj :system-stop))
                  core/-main (fn [& _]
                               (swap! calls conj :core-main)
                               (throw core-failure))]
      (let [failure (try
                      (bootstrap/run! ["--durable-root" "/durable"])
                      (catch Throwable error error))]
        (is (identical? core-failure failure))
        (is (= [:armed :embedded-start :core-main
                :system-stop :embedded-stop]
               @calls))))))

(deftest wired-bootstrap-shares-one-concurrent-terminal-cleanup-failure
  (let [hook (atom nil)
        stop-entered (promise)
        release-stop (promise)
        stop-attempts (atom 0)
        system-stops (atom 0)
        logs (atom [])]
    (with-redefs [jolt.host/add-shutdown-hook #(reset! hook %)
                  bootstrap/resolve-launcher! (constantly serve/run!)
                  embedded/start! (constantly ::runtime)
                  embedded/stop! (fn [_]
                                   (swap! stop-attempts inc)
                                   (deliver stop-entered true)
                                   @release-stop
                                   {:status :closing
                                    :phase :persisting
                                    :private-path "/must-not-escape"})
                  serve/max-stop-attempts 1
                  serve/pause-before-retry! (constantly nil)
                  system/stop! #(swap! system-stops inc)
                  core/-main (constantly :returned)
                  clojure.tools.logging/log* (fn [& args]
                                               (swap! logs conj args))]
      (let [running (future (try (bootstrap/run! ["--durable-root" "/durable"])
                                 (catch Throwable failure failure)))]
        (is (= true (deref stop-entered 5000 ::timeout)))
        (let [signaling (future (try (@hook)
                                     (catch Throwable failure failure)))]
          (deliver release-stop true)
          (let [run-failure (deref running 5000 ::timeout)
                signal-failure (deref signaling 5000 ::timeout)]
            (is (= :samizdat.telemetry.embedded-serve/stop-not-closed
                   (:type (ex-data run-failure))))
            (is (identical? run-failure signal-failure))
            (is (identical? run-failure
                            (try (@hook)
                                 (catch Throwable failure failure))))
            (is (= 1 @stop-attempts))
            (is (= 1 @system-stops))
            (is (= 3 (count @logs)))
            (is (not (.contains (pr-str (ex-data run-failure))
                                "/must-not-escape")))
            (is (not (.contains (pr-str @logs) "/must-not-escape")))))))))

(deftest wired-bootstrap-shares-one-concurrent-successful-cleanup
  (let [hook (atom nil)
        stop-entered (promise)
        release-stop (promise)
        stop-attempts (atom 0)
        system-stops (atom 0)
        terminal {:status :closed :phase :closed}]
    (with-redefs [jolt.host/add-shutdown-hook #(reset! hook %)
                  bootstrap/resolve-launcher! (constantly serve/run!)
                  embedded/start! (constantly ::runtime)
                  embedded/stop! (fn [_]
                                   (swap! stop-attempts inc)
                                   (deliver stop-entered true)
                                   @release-stop
                                   terminal)
                  system/stop! #(swap! system-stops inc)
                  core/-main (constantly :returned)]
      (let [running (future (bootstrap/run! ["--durable-root" "/durable"]))]
        (is (= true (deref stop-entered 5000 ::timeout)))
        (let [signaling (future (@hook))]
          (deliver release-stop true)
          (is (= :returned (deref running 5000 ::timeout)))
          (is (identical? terminal (deref signaling 5000 ::timeout)))
          (is (identical? terminal (@hook)))
          (is (= 1 @stop-attempts))
          (is (= 1 @system-stops)))))))

(deftest wired-bootstrap-memoizes-incomplete-startup-exhaustion
  (let [hook (atom nil)
        attempts (atom 0)
        ingress (atom 0)
        logs (atom [])
        startup-failure
        (ex-info "attachment failed"
                 {:type ::incomplete-startup
                  :retry-stop!
                  (fn []
                    (swap! attempts inc)
                    (throw (ex-info "private cleanup cause"
                                    {:path "/private/telemetry"})))})]
    (with-redefs [jolt.host/add-shutdown-hook #(reset! hook %)
                  bootstrap/resolve-launcher! (constantly serve/run!)
                  embedded/start! (fn [_] (throw startup-failure))
                  serve/max-stop-attempts 2
                  serve/pause-before-retry! (constantly nil)
                  core/-main (fn [& _] (swap! ingress inc))
                  system/stop! (fn [] (throw (AssertionError.
                                              "system never started")))
                  clojure.tools.logging/log* (fn [& args]
                                               (swap! logs conj args))]
      (let [failure (try
                      (bootstrap/run! ["--durable-root" "/durable"])
                      (catch Throwable error error))
            hook-failure (try (@hook)
                              (catch Throwable error error))
            rendered (str (pr-str (ex-data failure)) (pr-str @logs))]
        (is (= :samizdat.telemetry.embedded-serve/stop-not-closed
               (:type (ex-data failure))))
        (is (identical? failure hook-failure))
        (is (= 2 @attempts))
        (is (zero? @ingress))
        (is (= 2 (count @logs)))
        (is (nil? (.getCause failure)))
        (is (not (.contains rendered "/private/telemetry")))
        (is (not (.contains rendered "private cleanup cause")))))))

(deftest cleanup-wait-timeout-is-explicit-and-does-not-invent-a-result
  (let [cell (bootstrap/lifecycle-cell)
        stop-entered (promise)
        release-stop (promise)
        terminal {:status :closed :phase :closed}]
    (is (true? (bootstrap/publish-cleanup!
                cell (fn []
                       (deliver stop-entered true)
                       @release-stop
                       terminal))))
    (let [winner (future (bootstrap/cleanup! cell))]
      (is (= true (deref stop-entered 5000 ::timeout)))
      (with-redefs [bootstrap/wait-for-cleanup! (constantly false)]
        (let [failure (try (bootstrap/cleanup! cell)
                           (catch Throwable error error))]
          (is (= :samizdat.telemetry.embedded-bootstrap/cleanup-wait-timeout
                 (:type (ex-data failure))))
          (is (= #{:type :wait-ms} (set (keys (ex-data failure)))))))
      (deliver release-stop true)
      (is (= terminal (deref winner 5000 ::timeout)))
      (is (= terminal (bootstrap/cleanup! cell))))))

(deftest incomplete-startup-publishes-and-retires-its-retry-capability
  (let [hook (atom nil)
        start-entered (promise)
        release-start (promise)
        hook-waiting (promise)
        attempts (atom [{:status :closing :phase :persisting}
                        (ex-info "private cleanup cause" {:path "/private"})
                        {:status :closed :phase :closed}])
        calls (atom 0)
        ingress (atom 0)
        logs (atom [])
        real-wait bootstrap/wait-for-publication!
        retry-stop! (fn []
                      (swap! calls inc)
                      (let [outcome (first @attempts)]
                        (swap! attempts subvec 1)
                        (if (instance? Throwable outcome)
                          (throw outcome)
                          outcome)))
        startup-failure (ex-info "attachment failed"
                                 {:type ::incomplete-startup
                                  :retry-stop! retry-stop!})]
    (with-redefs [jolt.host/add-shutdown-hook #(reset! hook %)
                  bootstrap/wait-for-publication!
                  (fn [cell]
                    (deliver hook-waiting true)
                    (real-wait cell))
                  bootstrap/resolve-launcher! (constantly serve/run!)
                  embedded/start! (fn [_]
                                    (deliver start-entered true)
                                    @release-start
                                    (throw startup-failure))
                  serve/pause-before-retry! (constantly nil)
                  core/-main (fn [& _] (swap! ingress inc))
                  system/stop! (fn [] (throw (AssertionError.
                                              "system never started")))
                  clojure.tools.logging/log* (fn [& args]
                                               (swap! logs conj args))]
      (let [running (future (try (bootstrap/run! ["--durable-root" "/durable"])
                                 (catch Throwable failure failure)))]
        (is (= true (deref start-entered 5000 ::timeout)))
        (let [signaling (future (@hook))]
          (is (= true (deref hook-waiting 5000 ::timeout)))
          (deliver release-start true)
          (is (identical? startup-failure (deref running 5000 ::timeout)))
          (is (= {:status :closed :phase :closed}
                 (deref signaling 5000 ::timeout)))
          (is (= 3 @calls))
          (is (zero? @ingress))
          (is (nil? (.getCause startup-failure)))
          (is (not (.contains (pr-str @logs) "/private")))
          (is (not (.contains (pr-str @logs) "private cleanup cause"))))))))
