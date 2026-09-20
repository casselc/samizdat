;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.idempotency-test
  "Idempotent run creation against the real store.

  The API answers 503 when the start deadline passes and records that such a run
  may start anyway, so a caller cannot tell what happened. These are the
  properties that make the recovery safe: a key is claimed exactly once, a race
  has one winner, reuse with different inputs is refused rather than answered
  with the wrong run, and the claim survives a reopen because it is a row."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.agent.beam :as beam]
            [samizdat.api.control :as api-control]
            [samizdat.prompt :as prompt]
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
      ;; A reclaim is a read of the CURRENT owner followed by a compare-and-swap on it.
      ;; Two concurrent callers can therefore both succeed: the second reads the owner the
      ;; first just installed and swaps it again. That is ownership moving twice, which is
      ;; what the design intends - "a caller that crashes AFTER reclaiming is as
      ;; recoverable as the first one was" - and asserting exactly one winner was asserting
      ;; a timing accident. It passed until the machine was busy enough to interleave.
      (let [winners (filter :reclaimed @results)]
        (is (<= 1 (count winners)) "somebody takes over the abandoned claim")
        (is (apply distinct? (map :owner winners))
            "and each winner holds a DISTINCT token, so the last swap is the live owner")
        ;; The property that actually matters is not how many reclaimed, but how many can
        ;; then start work. Only the current owner passes begin-execution!, which is the
        ;; fence - so a second reclaim winner cannot become a second execution.
        (let [began (filter #(runs/begin-execution! conn "abandoned" (:owner %)
                                                    (str "e-" (:owner %)))
                            winners)]
          (is (= 1 (count began))
              "exactly one of them may begin execution, whatever the reclaim race did"))))))

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

;; --- the fence is at the START of work, not at the bind --------------------
;;
;; These cover the counterexample the Quint model produced (spec/idempotency.qnt
;; in the jev-eval work product). A claimant that is merely PAUSED, not dead, has
;; its key reclaimed; with the fence at bind time it went on to select a workflow
;; and run a whole beam, discovering the reclaim only when it tried to bind. That
;; prevents a duplicate binding while permitting duplicate WORK and untracked
;; spending. `begin-execution!` moves the fence ahead of every provider call.

(deftest a-key-whose-execution-began-is-never-reclaimed
  (let [conn (db/open! ":memory:")
        c (runs/claim-idempotency-key! conn "started" "d")]
    (is (true? (runs/begin-execution! conn "started" (:owner c) "e1"))
        "the owner may begin work exactly once")
    (let [r (runs/reclaim-idempotency-key! conn "started" "d")]
      (is (false? (:reclaimed r))
          "work may have begun, so a takeover would be a SECOND execution")
      (is (true? (:unresolved r)))
      (is (= "e1" (:exec-id r))))))

(deftest no-work-started-is-distinguished-from-outcome-unknown
  (testing "claimed, nothing begun: recoverable"
    (let [conn (db/open! ":memory:")]
      (runs/claim-idempotency-key! conn "a" "d")
      (let [again (runs/claim-idempotency-key! conn "a" "d")]
        (is (true? (:pending again)))
        (is (nil? (:unresolved again))))
      (is (true? (:reclaimed (runs/reclaim-idempotency-key! conn "a" "d"))))))
  (testing "execution begun, no run bound: outcome unknown, blocked"
    (let [conn (db/open! ":memory:")
          c (runs/claim-idempotency-key! conn "b" "d")]
      (runs/begin-execution! conn "b" (:owner c) "e")
      (let [again (runs/claim-idempotency-key! conn "b" "d")]
        (is (true? (:unresolved again)))
        (is (nil? (:pending again)))
        (is (= "e" (:exec-id again))))
      (is (= {:exec-id "e" :exec-started-at (:exec-started-at
                                             (runs/unresolved-execution conn "b"))}
             (runs/unresolved-execution conn "b"))))))

(deftest only-one-caller-can-begin-execution
  ;; four threads holding the same token: the exec_id guard admits exactly one,
  ;; so a retry storm cannot produce four runs for one key.
  (let [conn (db/open! ":memory:")
        c (runs/claim-idempotency-key! conn "race" "d")
        wins (atom 0)
        threads (doall (for [i (range 4)]
                         (Thread. (fn []
                                    (when (runs/begin-execution!
                                           conn "race" (:owner c) (str "e" i))
                                      (swap! wins inc))))))]
    (doseq [t threads] (.start t))
    (doseq [t threads] (.join t))
    (is (= 1 @wins) "exactly one execution identity is recorded")))

(deftest a-fenced-claimant-is-not-merely-unbound-it-starts-nothing
  ;; The counterexample driven through the REAL handler with a paused claimant.
  ;; The pause is placed exactly where the model puts it: between this request's
  ;; claim and its start of work. `beam/run!` must never be entered - not aborted
  ;; after the fact, never entered - because entering it is the spend.
  (let [c (db/open! ":memory:")
        beam-calls (atom 0)
        real-begin runs/begin-execution!]
    (try
      (with-redefs [beam/run! (fn [{:keys [on-start]}]
                                (swap! beam-calls inc)
                                (let [rid (runs/start-run! c {:problem "p"})]
                                  (on-start rid)
                                  {:status :completed :run-id rid}))
                    ;; the claimant is paused here; a reclaim lands in the window
                    runs/begin-execution! (fn [conn key owner exec-id]
                                            (runs/reclaim-idempotency-key! conn key "other")
                                            (real-begin conn key owner exec-id))]
        (let [r (api-control/start-run!
                 {:conn c :config {:llm {:provider :local :model "m"}}}
                 {:problem "p" :idempotency_key "k"})]
          (is (= 409 (:status r)))
          (is (= "idempotency_fenced" (get-in r [:body :error :type])))
          (is (zero? @beam-calls)
              "the fenced claimant made no provider call, not even workflow selection")
          (is (nil? (runs/idempotency-run c "k"))
              "and bound nothing")))
      (finally (db/close c)))))

(deftest an-unresolved-execution-blocks-the-handler-even-with-reclaim-requested
  ;; `reclaim_idempotency` is the caller asserting the earlier attempt started
  ;; nothing. Once an execution identity exists that assertion is false, and the
  ;; request is refused rather than granted a second execution.
  (let [c (db/open! ":memory:")]
    (try
      (let [body {:problem "p" :idempotency_key "u" :reclaim_idempotency true}
            digest (str (hash ["p" nil nil nil nil]))
            claim (runs/claim-idempotency-key! c "u" digest)]
        (runs/begin-execution! c "u" (:owner claim) "e-live")
        (let [r (api-control/start-run!
                 {:conn c :config {:llm {:provider :local :model "m"}}} body)]
          (is (= 409 (:status r)))
          (is (= "idempotency_unresolved" (get-in r [:body :error :type])))
          (is (= "e-live" (get-in r [:body :exec_id])))))
      (finally (db/close c)))))

(deftest the-refusal-sentences-live-in-templates
  ;; Every word a caller reads has to be editable without a rebuild, same as the
  ;; model-facing prose. These two are refusals a caller acts on.
  (is (re-find #"reconcile" (prompt/prompt "idempotency-unresolved")))
  (is (re-find #"started nothing" (prompt/prompt "idempotency-fenced"))))
