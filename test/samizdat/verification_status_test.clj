;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.verification-status-test
  "A run's lifecycle status and its assurance are different questions.

  ws-opt trial 3 (2026-09-22) completed with the ship gate skipped: the fixture
  was not a git checkout, so `changed` was nil, `:verify-unknown` was `:trust`,
  and `done` shipped. That is the configured contract behaving correctly - but
  the run row said `completed`, exactly as a run whose suite passed would, and
  the only way to tell them apart was to go read `ship-verify` journal events.

  So `status` stays a lifecycle fact and `verification` carries the evidence
  beside it."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(defn- a-running-run [c]
  (runs/start-run! c {:problem "p" :provider "local" :model "m"
                      :max-turns 4 :beam-width 1}))

(deftest verification_is_recorded_beside_the_status_not_inside_it
  (let [c (db/open! ":memory:")
        rid (a-running-run c)]
    (runs/finish-run! c rid :completed "the answer"
                      {:status "skipped" :reason :no-git-baseline
                       :check "jolt -M:test"})
    (let [r (runs/get-run c rid)
          v (runs/verification-of r)]
      (testing "the lifecycle status is untouched"
        (is (= "completed" (str (:status r))))
        (is (= "the answer" (:final_answer r))))
      (testing "the assurance is a separate, structured fact"
        (is (= "skipped" (:status v)))
        (is (= "no-git-baseline" (:reason v)))
        (is (= "jolt -M:test" (:check v))
            "a reader can tell WHAT was not verified, not only that something was not")))
    (db/close c)))

(deftest all_three_statuses_survive_the_round_trip
  (doseq [[st reason] [["passed" :green] ["failed" :red] ["skipped" :nothing-changed]]]
    (let [c (db/open! ":memory:")
          rid (a-running-run c)]
      (runs/finish-run! c rid :completed "a" {:status st :reason reason})
      (is (= st (:status (runs/verification-of (runs/get-run c rid))))
          (str st " must not be collapsed into another status"))
      (db/close c))))

(deftest a_run_with_no_evidence_is_not_a_run_that_was_checked
  (testing "the 4-arity writes nothing, and nil is not {:status skipped}"
    (let [c (db/open! ":memory:")
          rid (a-running-run c)]
      (runs/finish-run! c rid :completed "a")
      (is (nil? (runs/verification-of (runs/get-run c rid)))
          "no evidence recorded is its own state, distinct from a gate that ran")
      (db/close c))))

(deftest an_unrecognised_status_is_never_read_as_a_pass
  (let [c (db/open! ":memory:")
        rid (a-running-run c)]
    (runs/finish-run! c rid :completed "a" {:status "probably-fine"})
    (is (= "unknown" (:status (runs/verification-of (runs/get-run c rid))))
        "an unknown status is recorded as unknown, not dropped and not coerced")
    (db/close c)))

(deftest the_run_finished_event_carries_the_same_evidence
  ;; finish-run! writes both from the SAME value, so these two cannot
  ;; disagree. That is true of this write only - the ship-verify event is
  ;; computed separately in ship.clj; see verification-integration-test.
  (let [c (db/open! ":memory:")
        rid (a-running-run c)]
    (runs/finish-run! c rid :completed "a"
                      {:status "skipped" :reason :no-git-baseline})
    (let [row (db/fetch-one c ["SELECT data FROM events
                                 WHERE run_id = ? AND kind = 'run-finished'" rid])
          d (json/read-str (str (:data row)) :key-fn keyword)]
      (is (= "skipped" (get-in d [:verification :status])))
      (is (= "no-git-baseline" (get-in d [:verification :reason]))))
    (db/close c)))

(deftest finishing_a_terminal_run_again_changes_nothing
  ;; finish-run! is terminal-only-from-running; the verification write must not
  ;; give a second caller a way around that.
  (let [c (db/open! ":memory:")
        rid (a-running-run c)]
    (runs/finish-run! c rid :completed "a" {:status "skipped" :reason :no-git-baseline})
    (is (zero? (runs/finish-run! c rid :completed "b" {:status "passed" :reason :green})))
    (is (= "skipped" (:status (runs/verification-of (runs/get-run c rid))))
        "a late second finish must not overwrite recorded assurance")
    (db/close c)))
