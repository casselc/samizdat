;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent-turn-lease-test
  "Deterministic JS1 TurnLease cancellation and stale-effect boundaries."
  (:require [clojure.test :refer [deftest is]]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.resume :as resume]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.repl :as repl-tools]
            [samizdat.agent.tools.ship]
            [samizdat.agent.verify :as verify]
            [samizdat.engine.proc :as proc]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(def ^:dynamic *lease-test-eval-store* nil)

(deftest stale-turn-authority-is-refused-at-the-shared-tool-boundary
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        verifies (atom 0)]
    (is (base/revoke-turn-lease! lease :test))
    (with-redefs [verify/run-verify (fn [& _]
                                     (swap! verifies inc)
                                     {:green? true})]
      (let [r (base/dispatch-tool
               {:branch branch :turn-lease lease :tool-name "done"
                :config {:run {:verify-cmd "jolt -M:test"}}
                :args {:answer "stale answer"}})]
        (is (:stale-lease? r))
        (is (= :failure (:category r)))
        (is (nil? (:final-answer (:branch r))))
        (is (zero? @verifies)
            "a stale done never enters the method and launches no verification")))))

(deftest revocation-while-done-prepares-its-request-still-launches-no-verifier
  ;; This is the synchronized spawn permit, not the initial dispatch check:
  ;; done starts active, blocks in cheap diff derivation, and becomes stale
  ;; before verify/run-verify can be called.
  (let [branch (state/new-branch {:id "B1" :problem "answer p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        deriving (promise)
        release (promise)
        verifies (atom 0)]
    (with-redefs [proc/scope-supported? (constantly true)
                  gitdiff/changed-files
                  (fn [& _]
                    (deliver deriving true)
                    @release
                    ["src/x.clj" "test/x_test.clj"])
                  verify/run-verify
                  (fn [& _]
                    (swap! verifies inc)
                    {:green? true})]
      (let [work (future
                   (try
                     {:result
                      (base/dispatch-tool
                       {:run-id "run" :turn 1 :branch branch
                        :turn-lease lease :tool-name "done"
                        :root "/tmp" :git-baseline "HEAD"
                        :config {:run {:verify-focused? true}}
                        :args {:answer "answer p"}})}
                     (catch Throwable e {:error e})))]
        (is (= true (deref deriving 1000 ::not-deriving)))
        (is (base/revoke-turn-lease! lease :deadline))
        (deliver release true)
        (let [{:keys [error]} (deref work 1000 ::timeout)]
          (is (some? error))
          (is (= :stale (:samizdat.turn-lease/error (ex-data error))))
          (is (zero? @verifies) "revoked done launches no verifier"))))))

(deftest revocation-before-the-pre-verification-probe-launches-no-host-process
  ;; The pre-verification fence: done's probes — the execution-boundary
  ;; capability probe and the scoped git diff, which launches host git
  ;; processes — run under the same lease permit as the verifier launch.
  ;; A lease revoked after dispatch but before the probes must mean NOTHING
  ;; spawns for the dead turn.  Deterministic: the method blocks on a
  ;; barrier in the journal read that precedes the probes, revocation
  ;; lands, and the permit refuses before either probe is consulted.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "answer p" :max-turns 3
                                  :beam-width 1})
          branch (state/new-branch {:id "B1" :problem "answer p"})
          lease (base/mint-turn-lease rid "B1" 1)
          preparing (promise)
          release (promise)
          probes (atom 0)
          diffs (atom 0)
          verifies (atom 0)]
      (runs/open-branch! c rid {:branch-id "B1"})
      (with-redefs [proc/scope-supported? (fn [] (swap! probes inc) true)
                    gitdiff/changed-files (fn [& _]
                                            (swap! diffs inc)
                                            ["src/x.clj" "test/x_test.clj"])
                    verify/run-verify (fn [& _]
                                        (swap! verifies inc)
                                        {:green? true})
                    journal/corroborating-artifacts
                    (fn [& _]
                      (deliver preparing true)
                      @release
                      [])]
        (let [work (future
                     (try
                       {:result
                        (base/dispatch-tool
                         {:conn c :run-id rid :turn 1 :branch branch
                          :turn-lease lease :tool-name "done"
                          :root "/tmp" :git-baseline "HEAD"
                          :config {:run {:verify-focused? true}}
                          :args {:answer "answer p"}})}
                       (catch Throwable e {:error e})))]
          (is (= true (deref preparing 1000 ::not-preparing)))
          (is (base/revoke-turn-lease! lease :deadline))
          (deliver release true)
          (let [{:keys [error]} (deref work 1000 ::timeout)]
            (is (some? error))
            (is (= :stale (:samizdat.turn-lease/error (ex-data error))))
            (is (zero? @probes) "no post-revocation host capability probe")
            (is (zero? @diffs) "no post-revocation git process")
            (is (zero? @verifies) "and of course no verifier")))))))

(deftest effect-permit-and-revocation-have-one-deterministic-order
  ;; Hold the permit's short initiation callback open and prove revocation
  ;; cannot linearize through it.  No long computation belongs in this extent;
  ;; the promises are a deterministic test barrier only.
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        entered (promise)
        release (promise)
        revoking (promise)
        order (atom [])
        ctx {:run-id "run" :turn 1 :branch branch :turn-lease lease}
        launch (future
                 (base/with-turn-lease-permit!
                  ctx
                  (fn []
                    (deliver entered true)
                    @release
                    (swap! order conj :launch))))]
    (is (= true (deref entered 1000 ::not-entered)))
    (let [revoke (future
                   (deliver revoking true)
                   (let [r (base/revoke-turn-lease! lease :deadline)]
                     (swap! order conj :revoke)
                     r))]
      (is (= true (deref revoking 1000 ::not-revoking)))
      (is (= ::blocked (deref revoke 25 ::blocked))
          "revocation waits only for the short initiation extent")
      (deliver release true)
      (is (not= ::timeout (deref launch 1000 ::timeout)))
      (is (= true (deref revoke 1000 ::timeout)))
      (is (= [:launch :revoke] @order))
      (is (= :revoked (base/turn-lease-status lease))))))

(deftest permitted-long-effect-does-not-hold-the-lease-monitor
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        running (promise)
        release (promise)
        work (future
               (base/run-with-turn-lease-permit!
                {:run-id "run" :turn 1 :branch branch :turn-lease lease}
                (fn []
                  (deliver running true)
                  @release
                  :done)))]
    (is (= true (deref running 1000 ::not-running)))
    (is (base/revoke-turn-lease! lease :deadline)
        "revocation linearizes while the already-permitted effect is running")
    (deliver release true)
    (is (= :done (deref work 1000 ::timeout)))
    (is (= :revoked (base/turn-lease-status lease)))))

(deftest a-lease-revoked-during-js1-eval-blocks-the-delayed-edit-before-receipt
  ;; Deterministic stand-in for evaluate-recorded!'s documented store seam:
  ;; source waits, then reaches project/edit's intent/effect boundary.
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        entered (promise)
        release (promise)
        receipts (atom [])
        path (str (System/getProperty "java.io.tmpdir")
                  "/samizdat-stale-edit-" (random-uuid) ".txt")
        store {:record-intent! (fn [_ _ intent]
                                 (swap! receipts conj intent)
                                 0)}
        evaluate! (fn [_conn _binding _source opts]
                     (deliver entered true)
                     @release
                     ((:effect-permit! opts)
                      (fn []
                        ((:record-intent! *lease-test-eval-store*)
                         nil 1 {:op :project/edit :args [path]})))
                     (spit path "stale write")
                     {:value :edited})
        fake-sandbox-var (fn [name]
                           (case name
                             "evaluate-recorded!" evaluate!
                             "*eval-store*" #'*lease-test-eval-store*
                             nil))]
    (try
      (binding [*lease-test-eval-store* store]
        (with-redefs-fn
          {#'repl-tools/sandbox-var fake-sandbox-var}
          (fn []
            (let [work (future
                         (base/dispatch-tool
                          {:run-id "run" :turn 1
                           :branch branch :turn-lease lease
                           :js1/binding {:binding/id "test"}
                           :tool-name "eval"
                           :args {:code "(project/edit ...)"}}))]
              (is (= true (deref entered 1000 ::not-entered)))
              (is (base/revoke-turn-lease! lease :deadline))
              (deliver release true)
              (let [r (deref work 1000 ::timeout)]
                (is (not= ::timeout r))
                (is (= :failure (:category r)))
                (is (empty? @receipts) "stale edit records no intent receipt")
                (is (not (.exists (java.io.File. path)))
                    "stale edit makes no file change"))))))
      (finally
        (deliver release true)
        (when (.exists (java.io.File. path))
          (.delete (java.io.File. path)))))))

(deftest deadline-revokes-before-interrupt-and-cooperative-exit-allows-next-turn
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        calls (atom 0)
        active (atom 0)
        max-active (atom 0)
        order (atom [])
        original-revoke base/revoke-turn-lease!
        original-interrupt base/interrupt-turn-lease!
        advance (fn [_ b _]
                  (let [n (swap! calls inc)]
                    (swap! active inc)
                    (swap! max-active max @active)
                    (try
                      (if (= 1 n)
                        (do (Thread/sleep 10000) b)
                        (assoc b :second-turn? true))
                      (finally (swap! active dec)))))
        ctx {:run-id "run" :iterating-loop? true
             :turn-deadline-ms 20 :turn-cancel-grace-ms 500}]
    (with-redefs [beam/advance-branch advance
                  base/revoke-turn-lease!
                  (fn [lease reason]
                    (let [r (original-revoke lease reason)]
                      (swap! order conj [:revoke (base/turn-lease-status lease)])
                      r))
                  base/interrupt-turn-lease!
                  (fn [lease]
                    (swap! order conj [:interrupt (base/turn-lease-status lease)])
                    (original-interrupt lease))]
      (let [after-timeout (first (#'beam/advance-all ctx [branch] 1))
            after-next (first (#'beam/advance-all
                               (assoc ctx :turn-deadline-ms 500)
                               [after-timeout] 2))]
        (is (= [[:revoke :revoked] [:interrupt :revoked]
                [:revoke :revoked]]
               @order)
            "deadline revokes before interrupt; completed next lease is revoked too")
        (is (= 1 (:timeouts after-timeout)))
        (is (not (:turn-cancellation-fault? after-timeout)))
        (is (:second-turn? after-next)
            "confirmed quiescence permits one fresh authoritative turn")
        (is (= 2 @calls))
        (is (= 1 @max-active) "the fresh turn never overlaps the cancelled one")))))

(deftest an-unquiesced-delayed-result-fails-the-run-with-no-next-authority
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 3 :beam-width 1})
          branch (state/new-branch {:id "B1" :problem "p"})
          calls (atom 0)
          active (atom 0)
          max-active (atom 0)
          interrupted (promise)
          release (promise)
          finished (promise)
          advance (fn [_ b _]
                    (swap! calls inc)
                    (swap! active inc)
                    (swap! max-active max @active)
                    (try
                      (try
                        (Thread/sleep 10000)
                        (catch Throwable _
                          ;; Observe cancellation, then deliberately retain a
                          ;; result beyond the bounded grace.
                          (deliver interrupted true)
                          @release))
                      (assoc b :status :done :final-answer "stale delayed answer")
                      (finally
                        (swap! active dec)
                        (deliver finished true))))]
      (runs/open-branch! c rid {:branch-id "B1"})
      (try
        (with-redefs [beam/advance-branch advance]
          (let [r (beam/run-rounds
                   {:conn c :run-id rid :max-turns 3
                    :iterating-loop? true
                    :turn-deadline-ms 20 :turn-cancel-grace-ms 20}
                   [branch] 1)]
            (is (= true (deref interrupted 1000 ::not-interrupted)))
            (is (= :cancellation-fault (:status r)))
            (is (= :unquiesced-turn-worker (get-in r [:fault :kind])))
            (is (= "failed" (:status (runs/get-run c rid))))
            (is (= 1 @calls) "the scheduler minted no next-turn authority")
            (is (= 1 @max-active) "no turn worker overlap occurred")
            (is (nil? (:final-answer (first (:branches r))))
                "the delayed result is not accepted")
            (is (some #(= "turn-cancellation-fault" (:kind %))
                      (journal/events-since c rid 0))
                "the fail-closed reason is durable")
            (is (not (resume/resumable? c rid))
                "H1: the fault durably refuses fresh authority while the
                 stale worker may exist — the run is terminal for resume")))
        (finally
          (deliver release true)
          (is (= true (deref finished 1000 ::not-finished))
              "the deliberately delayed test worker is eventually reaped"))))))
