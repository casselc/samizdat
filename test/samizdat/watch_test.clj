;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.watch-test
  "The supervisor as a watcher: a thread that observes a run WHILE it runs.

  The supervisor role is a manifest node, so it runs between rounds, in
  sequence, and only in the workflows that wire it. A run losing every turn to
  empty provider replies reaches no round boundary quickly and the node never
  gets a look — which is the case that motivated this."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.session :as session]
            [samizdat.store.db :as db]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.runs :as runs]
            [samizdat.watch :as watch]))

(use-fixtures :each (fn [f] (session/reset!) (f) (session/reset!)))

(defn- with-run [f]
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (try (f c rid) (finally (db/close c)))))

(defn- struggling! []
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (dotimes [_ 4] (session/observe! [:provider :empty-reply])
                 (session/observe-turn! {:tool "__provider_error__" :category :neutral
                                         :signals {}})))

(deftest it-speaks-through-the-same-queue-a-human-uses
  ;; The design decision that matters. RFC-006's rule — a directive lands on a
  ;; turn boundary, because a branch mid-turn holds a ledger it read before the
  ;; change — is not a rule the harness's own observer gets to skip. So it
  ;; submits a directive and the driver drains it exactly as it drains a
  ;; person's, with the same guards and the same record.
  (with-run
    (fn [c rid]
      (struggling!)
      (let [raised (watch/pass! {:conn c :run-id rid} (atom #{}))
            pending (interventions/pending c rid)]
        (is (seq raised))
        (is (= 1 (count pending)))
        (is (= "message" (:kind (first pending)))
            "it observes and advises; deciding a run should STOP is a
             judgement with a cost, and belongs to a person or the supervisor
             role, not to a threshold that fired")
        (is (= "watch" (:issued_by (first pending)))
            "distinguishable from a human's directive in the record, and
             otherwise identical")))))

(deftest a-finding-is-raised-once-per-run
  ;; An observer that says the same thing every four seconds is noise a branch
  ;; learns to ignore, which is worse than silence.
  (with-run
    (fn [c rid]
      (struggling!)
      (let [seen (atom #{})]
        (is (seq (watch/pass! {:conn c :run-id rid} seen)))
        (is (empty? (watch/pass! {:conn c :run-id rid} seen)))
        (is (empty? (watch/pass! {:conn c :run-id rid} seen)))
        (is (= 1 (count (interventions/pending c rid))))))))

(deftest it-is-bounded-because-every-word-costs-the-branch-a-turn
  (with-run
    (fn [c rid]
      ;; Enough distinct high-severity findings to exceed the cap.
      (dotimes [_ 10] (session/observe-turn! {:tool "eval" :category :mechanics
                                              :signals {:parse-error true
                                                        :truncated true}}))
      (dotimes [_ 4] (session/observe! [:provider :empty-reply]))
      (dotimes [_ 3] (session/observe! [:verify :skipped]))
      (let [seen (atom #{})]
        (watch/pass! {:conn c :run-id rid} seen)
        (is (<= (count (interventions/pending c rid)) 3)
            "an observer with no budget can spend the whole run explaining why
             the run is going badly")))))

(deftest a-healthy-run-is-left-alone
  (with-run
    (fn [c rid]
      (dotimes [_ 12] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
      (is (empty? (watch/pass! {:conn c :run-id rid} (atom #{}))))
      (is (empty? (interventions/pending c rid))))))

(deftest only-severe-findings-interrupt
  ;; Most findings are worth SEEING and only some are worth a turn: a
  ;; supervisor reading a block can weigh a medium finding, while a branch
  ;; mid-task handed one is just distracted.
  (with-run
    (fn [c rid]
      (dotimes [_ 10] (session/observe-turn! {:tool "eval" :category :success
                                              :signals {:auto-repaired true}}))
      (let [fs (session/findings)]
        (is (some #(= :calls-need-repair (:kind %)) fs) "the finding exists")
        (is (every? #(not= :high (:severity %))
                    (filter #(= :calls-need-repair (:kind %)) fs)))
        (is (empty? (watch/pass! {:conn c :run-id rid} (atom #{})))
            "and it is not worth interrupting for")))))

(deftest the-message-says-what-it-rules-out
  ;; A branch told only that turns are being wasted will reword something,
  ;; which is the expensive wrong move.
  (with-run
    (fn [c rid]
      (struggling!)
      (watch/pass! {:conn c :run-id rid} (atom #{}))
      (let [text (str (:payload (first (interventions/pending c rid))))]
        (is (str/includes? text "not more steering"))
        (is (str/includes? text "budget")))))) 

(deftest a-watcher-that-throws-does-not-take-the-run-with-it
  ;; It is an observer; its failure must cost the run nothing.
  (with-run
    (fn [c rid]
      (struggling!)
      (with-redefs [interventions/submit! (fn [& _] (throw (ex-info "boom" {})))]
        (let [stop (watch/start! {:conn c :run-id rid})]
          (is (fn? stop))
          (Thread/sleep 50)
          (is (nil? (stop))) "stopping is idempotent and never throws")))))

(deftest a-run-with-no-conn-gets-a-no-op-watcher
  ;; A unit test or a REPL call has nowhere to submit a directive, and must not
  ;; pay for a thread to discover that.
  (let [stop (watch/start! {})]
    (is (fn? stop))
    (is (nil? (stop)))))

(deftest the-watcher-steers-and-never-tunes
  ;; The split is not tidiness. A nudge is wrong for one turn and the branch
  ;; reads the next one; a userspace edit is wrong for every run until somebody
  ;; changes it back. So they have different evidence bars, and the thread that
  ;; can only see ONE run is the one restricted to the reversible instrument.
  (with-run
    (fn [c rid]
      (struggling!)
      (watch/pass! {:conn c :run-id rid} (atom #{}))
      (is (every? #(= "message" (:kind %)) (interventions/pending c rid))
          "advisory only — no cull, no pause, no fork")
      (is (empty? (db/fetch c ["SELECT * FROM userspace"]))
          "and nothing was tuned: the watcher sees one run, which is not
           enough evidence to change the loop for every future run"))))
