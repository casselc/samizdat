;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.idempotency-test
  "Idempotent run creation against the real store.

  The API answers 503 when the start deadline passes and records that such a run
  may start anyway, so a caller cannot tell what happened. These are the
  properties that make the recovery safe: a key is claimed exactly once, a race
  has one winner, reuse with different inputs is refused rather than answered
  with the wrong run, and the claim survives a reopen because it is a row."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(deftest a-key-is-claimed-once-and-then-answers-with-the-run
  (let [conn (db/open! ":memory:")
        k "exp-2026-09-19|fx-01|0|B_selector2"]
    (is (true? (:claimed (runs/claim-idempotency-key! conn k "digest-a")))
        "the first caller claims it and starts the run")
    (let [rid (runs/start-run! conn {:problem "p"})]
      (runs/bind-idempotency-run! conn k rid)
      (let [again (runs/claim-idempotency-key! conn k "digest-a")]
        (is (false? (:claimed again)) "the second caller does not start another")
        (is (= rid (:run-id again)) "it is told which run already exists")
        (is (= rid (runs/idempotency-run conn k)))))))

(deftest the-same-key-with-different-inputs-is-a-conflict
  (let [conn (db/open! ":memory:")]
    (runs/claim-idempotency-key! conn "k" "digest-a")
    (let [c (runs/claim-idempotency-key! conn "k" "digest-b")]
      (is (true? (:conflict c)) "reuse with different inputs is refused")
      (is (= "digest-a" (:digest c)) "and the digest it was first used with is reported"))))

(deftest concurrent-claims-have-exactly-one-winner
  (let [conn (db/open! ":memory:")
        results (atom [])
        threads (doall (repeatedly 8 #(Thread. (fn []
                                                 (swap! results conj
                                                        (runs/claim-idempotency-key!
                                                         conn "race" "d"))))))]
    (doseq [t threads] (.start t))
    (doseq [t threads] (.join t))
    (is (= 8 (count @results)))
    (is (= 1 (count (filter :claimed @results)))
        "exactly one caller starts the run; the rest are told it exists")))

(deftest a-crash-between-claiming-and-starting-is-pending-not-replayable
  (let [conn (db/open! ":memory:")]
    ;; the caller claims the key and then dies: no run was ever bound
    (is (true? (:claimed (runs/claim-idempotency-key! conn "crashy" "d"))))
    (let [again (runs/claim-idempotency-key! conn "crashy" "d")]
      (is (false? (:claimed again)))
      (is (true? (:pending again)) "pending, because there is no run to replay")
      (is (nil? (:run-id again)) "and no run id is invented"))))

(deftest the-original-claimant-is-fenced-out-after-a-reclaim
  (let [conn (db/open! ":memory:")
        ;; the original claimant: alive, just slow — it claims and is then paused
        first-claim (runs/claim-idempotency-key! conn "k" "d")
        ;; somebody decides it is gone, reclaims, and starts a run
        taken (runs/reclaim-idempotency-key! conn "k" "d")
        their-run (runs/start-run! conn {:problem "the replacement"})]
    (is (true? (:claimed first-claim)))
    (is (true? (:reclaimed taken)))
    (is (true? (runs/bind-idempotency-run! conn "k" their-run (:owner taken)))
        "the reclaimer binds its run")
    ;; the original wakes up and tries to bind the run IT started
    (let [its-run (runs/start-run! conn {:problem "the original"})]
      (is (false? (runs/bind-idempotency-run! conn "k" its-run (:owner first-claim)))
          "the original no longer owns the key and must not overwrite the binding")
      (is (= their-run (runs/idempotency-run conn "k"))
          "the binding still names the reclaimer's run"))))

(deftest a-second-crash-after-a-reclaim-is-recoverable
  (let [conn (db/open! ":memory:")]
    (runs/claim-idempotency-key! conn "twice" "d")
    (let [second-owner (runs/reclaim-idempotency-key! conn "twice" "d")]
      (is (true? (:reclaimed second-owner)))
      ;; that caller dies too, still without starting anything
      (let [third (runs/reclaim-idempotency-key! conn "twice" "d")]
        (is (true? (:reclaimed third))
            "ownership can move again: a set-once column would have stranded the key")
        (is (not= (:owner second-owner) (:owner third)))
        (let [rid (runs/start-run! conn {:problem "finally"})]
          (is (true? (runs/bind-idempotency-run! conn "twice" rid (:owner third))))
          (is (false? (runs/bind-idempotency-run! conn "twice" rid (:owner second-owner)))
              "and the previous owner is fenced out too"))))))

(deftest a-bound-key-cannot-be-reclaimed
  (let [conn (db/open! ":memory:")
        c (runs/claim-idempotency-key! conn "bound" "d")
        rid (runs/start-run! conn {:problem "p"})]
    (runs/bind-idempotency-run! conn "bound" rid (:owner c))
    (let [r (runs/reclaim-idempotency-key! conn "bound" "d")]
      (is (false? (:reclaimed r)))
      (is (= rid (:run-id r))))))

(deftest a-pending-claim-can-be-reclaimed-by-exactly-one-caller
  (let [conn (db/open! ":memory:")]
    (runs/claim-idempotency-key! conn "abandoned" "d")
    (let [results (atom [])
          threads (doall (repeatedly 4 #(Thread. (fn []
                                                   (swap! results conj
                                                          (runs/reclaim-idempotency-key!
                                                           conn "abandoned" "d"))))))]
      (doseq [t threads] (.start t))
      (doseq [t threads] (.join t))
      (is (= 1 (count (filter :reclaimed @results)))
          "one caller takes over the abandoned claim; the rest do not"))))

(deftest a-pending-claim-survives-a-reopen
  (let [path (str (System/getProperty "java.io.tmpdir") "/idem-pending-" (random-uuid) ".db")
        conn (db/open! path)]
    (runs/claim-idempotency-key! conn "pending-durable" "d")
    (db/close conn)
    (let [reopened (db/open! path)
          again (runs/claim-idempotency-key! reopened "pending-durable" "d")]
      (is (true? (:pending again))
          "a crash before starting leaves a durable pending state, not a clean slate")
      (is (nil? (runs/idempotency-run reopened "pending-durable")))
      (db/close reopened))))

(deftest a-claim-is-durable
  (let [path (str (System/getProperty "java.io.tmpdir") "/idem-" (random-uuid) ".db")
        conn (db/open! path)
        rid (runs/start-run! conn {:problem "p"})]
    (let [c (runs/claim-idempotency-key! conn "durable" "d")]
      (runs/bind-idempotency-run! conn "durable" rid (:owner c)))
    (db/close conn)
    (let [reopened (db/open! path)]
      (is (= rid (runs/idempotency-run reopened "durable"))
          "the claim outlives the process, which is the point of a row")
      (is (false? (:claimed (runs/claim-idempotency-key! reopened "durable" "d")))
          "and a restarted client is told the run exists rather than starting a second")
      (db/close reopened))))
