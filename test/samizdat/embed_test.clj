;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.embed-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing thrown-with-msg?]]
            [samizdat.agent.beam :as beam]
            [samizdat.embed :as embed]
            [samizdat.llm.client :as llm-client]
            [samizdat.llm.registry :as registry]
            [ring-chez.adapter :as ring-adapter]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.system :as system]
            [samizdat.userspace :as userspace]))

(defn- open-embedded []
  (with-redefs [llm-client/probe-llama-cpp (constantly nil)
                registry/adapter-for (fn [_] ::adapter)]
    (embed/open! {:db {:path ":memory:"}
                  :llm {:provider :local :base-url "http://provider.invalid/v1"
                        :model "fixture"}})))

(deftest open-migrates-and-exclusively-binds-a-project
  (let [embedded (open-embedded)]
    (try
      (is (pos? (db/schema-version (embed/connection embedded))))
      (is (userspace/bound?))
      (is (= ::adapter (:adapter embedded)))
      (is (thrown-with-msg? Throwable #"already open" (open-embedded)))
      (is (thrown-with-msg? Throwable #"owned" (userspace/bind! (:conn embedded))))
      (finally (embed/close! embedded 1000)))))

(deftest failed-served-server-start-releases-userspace-and-database-ownership
  (let [close-db db/close
        closed (atom 0)]
    (with-redefs [llm-client/probe-llama-cpp (constantly nil)
                  ring-adapter/run-server
                  (fn [& _] (throw (ex-info "bind failed" {})))
                  db/close (fn [conn] (swap! closed inc) (close-db conn))]
      (is (thrown-with-msg?
           Throwable #"bind failed"
           (system/start! (fn [_] {:status 200})
                          {:db {:path ":memory:"}
                           :http {:port 0}
                           :llm {:provider :local
                                 :base-url "http://provider.invalid/v1"
                                 :model "fixture"}})))
      (is (= 1 @closed) "the store opened before server startup was closed")
      (is (false? (system/started?)))
      (is (false? (userspace/bound?)))
      ;; The exclusive owner was released too, not merely detached.
      (let [embedded (open-embedded)]
        (is (= :closed (:status (embed/close! embedded 1000))))))))

(deftest an-instant-run-cannot-outrun-registration
  (let [embedded (open-embedded)]
    (try
      (with-redefs [beam/run! (fn [{:keys [on-start]}]
                                (let [run-id (str (random-uuid))]
                                  (on-start run-id)
                                  {:run-id run-id :status :completed}))]
        (let [{:keys [run-id future]} (embed/start-run!
                                       embedded {:problem "p"
                                                 :start-timeout-ms 1000})]
          (is (string? run-id))
          (is (= :completed (:status @future)))))
      (finally (embed/close! embedded 1000)))))

(deftest a-start-timeout-signals-the-not-yet-started-run
  (let [embedded (open-embedded)
        stopped (promise)]
    (try
      (with-redefs [beam/run! (fn [{:keys [abort]}]
                                (loop []
                                  (if @abort
                                    (do (deliver stopped true)
                                        {:status :aborted})
                                    (do (Thread/sleep 1) (recur)))))]
        (is (thrown-with-msg?
             Throwable #"did not start"
             (embed/start-run! embedded {:problem "p" :start-timeout-ms 1})))
        (is (= true (deref stopped 1000 false))))
      (finally (embed/close! embedded 1000)))))

(deftest durable-reads-interventions-and-wakeups-use-the-owned-store
  (let [embedded (open-embedded)
        conn (embed/connection embedded)
        channel (embed/subscribe embedded 8)]
    (try
      (let [run-id (runs/start-run! conn {:problem "p"})
            wakeup (first (async/alts!! [channel (async/timeout 1000)]))]
        (is (= run-id (:run-id wakeup)))
        (is (= 1 (:count (embed/journal-tail embedded run-id 0 10))))
        (is (= run-id (get-in (embed/get-run embedded run-id) [:run :id])))
        (is (= "pending"
               (get-in (embed/intervene! embedded run-id
                                         {:kind "message" :payload "inspect it"})
                       [:body :status])))
        (journal/note! conn run-id :fixture {:data {:ok true}})
        (is (= 3 (:count (embed/journal-tail embedded run-id 0 10)))))
      (finally
        (embed/unsubscribe! channel)
        (embed/close! embedded 1000)))))

(deftest abort-and-close-own-the-run-future-and-close-is-idempotent
  (let [embedded (open-embedded)
        entered (promise)]
    (with-redefs [beam/run! (fn [{:keys [conn problem abort on-start]}]
                              (let [run-id (runs/start-run! conn {:problem problem})]
                                (on-start run-id)
                                (deliver entered true)
                                (loop []
                                  (if @abort
                                    {:run-id run-id :status :aborted}
                                    (do (Thread/sleep 1) (recur))))))]
      (let [{:keys [run-id] :as run} (embed/start-run!
                                      embedded {:problem "p"
                                                :start-timeout-ms 1000})]
        @entered
        (testing "abort is both cooperative and durable"
          (is (true? (embed/abort! embedded run)))
          (is (= "aborted" (get-in (embed/get-run embedded run-id)
                                    [:run :status]))))
        (let [first-close (embed/close! embedded 1000)
              second-close (embed/close! embedded 1000)]
          (is (= :closed (:status first-close)))
          (is (empty? (:hung-run-ids first-close)))
          (is (= first-close second-close))
          (is (false? (userspace/bound?))))))))

(deftest close-signals-a-run-that-the-host-did-not-explicitly-abort
  (let [embedded (open-embedded)
        observed-abort (promise)]
    (with-redefs [beam/run! (fn [{:keys [abort on-start]}]
                              (on-start "closing-run")
                              (loop []
                                (if @abort
                                  (do (deliver observed-abort true)
                                      {:run-id "closing-run" :status :aborted})
                                  (do (Thread/sleep 1) (recur)))))]
      (embed/start-run! embedded {:problem "p" :start-timeout-ms 1000})
      (is (= :closed (:status (embed/close! embedded 1000))))
      (is (= true (deref observed-abort 0 false))))))

(deftest a-hung-run-keeps-resources-owned-until-close-is-retried
  (let [embedded (open-embedded)
        finish (promise)]
    (with-redefs [beam/run! (fn [{:keys [on-start]}]
                              (on-start "slow-run")
                              @finish
                              {:run-id "slow-run" :status :aborted})]
      (embed/start-run! embedded {:problem "p" :start-timeout-ms 1000})
      (let [first-close (embed/close! embedded 1)]
        (is (= :closing (:status first-close)))
        (is (= ["slow-run"] (:hung-run-ids first-close)))
        (is (userspace/bound?) "the exclusive lease remains held")
        (is (pos? (db/schema-version (:conn embedded)))
            "the store remains usable rather than being closed under the run"))
      (deliver finish true)
      (let [finished (embed/close! embedded 1000)]
        (is (= :closed (:status finished)))
        (is (false? (userspace/bound?)))
        (is (= finished (embed/close! embedded 1000))
            "the final closed result is idempotent")))))

(deftest a-cleanup-error-is-reported-and-retryable
  (let [embedded (open-embedded)
        close-db db/close
        attempts (atom 0)]
    (with-redefs [db/close (fn [conn]
                             (if (= 1 (swap! attempts inc))
                               (throw (ex-info "close failed" {}))
                               (close-db conn)))]
      (let [first-close (embed/close! embedded 1000)]
        (is (= :closing (:status first-close)))
        (is (= "close failed" (ex-message (first (:errors first-close)))))
        (is (userspace/bound?) "cleanup failure retains exclusive ownership"))
      (let [finished (embed/close! embedded 1000)]
        (is (= :closed (:status finished)))
        (is (= 2 @attempts))
        (is (false? (userspace/bound?)))))))
