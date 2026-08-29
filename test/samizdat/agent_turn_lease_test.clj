;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent-turn-lease-test
  "Deterministic TurnLease cancellation and stale-effect boundaries."
  (:require [clojure.test :refer [deftest is]]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.resume :as resume]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.repl :as repl-tools]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.agent.verify :as verify]
            [samizdat.security.verification-provider :as vprov]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as evaluator-store]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as workflow]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest stale-turn-authority-is-refused-at-the-shared-tool-boundary
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        verifies (atom 0)]
    (is (base/revoke-turn-lease! lease :test))
    (with-redefs [verify/run-verify (fn [& _]
                                     (swap! verifies inc)
                                     {:green? true})]
      (let [r (tools/run-tool
               {:run-id "run" :turn 1 :branch branch :turn-lease lease
                :tool-name "done" :config {:run {:verify-cmd "jolt -M:test"}}
                :args {:answer "stale answer"}})]
        (is (:stale-lease? r))
        (is (= :failure (:category r)))
        (is (nil? (:final-answer (:branch r))))
        (is (zero? @verifies))))))

(deftest permit-and-revocation-have-one-linearization-order
  ;; The promises are a test barrier only. Production initiation callbacks
  ;; append one intent or launch one child and must remain short.
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        entered (promise)
        release (promise)
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
                   (let [r (base/revoke-turn-lease! lease :deadline)]
                     (swap! order conj :revoke)
                     r))]
      (is (= ::blocked (deref revoke 25 ::blocked)))
      (deliver release true)
      (is (not= ::timeout (deref launch 1000 ::timeout)))
      (is (= true (deref revoke 1000 ::timeout)))
      (is (= [:launch :revoke] @order))
      (is (= :revoked (base/turn-lease-status lease))))))

(deftest revocation-during-done-preparation-launches-no-environment-probe
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        preparing (promise)
        release (promise)
        probes (atom 0)]
    (with-redefs [evaluator-store/history
                  (fn [& _]
                    (deliver preparing true)
                    @release
                    [{:receipts [{:op :project/edit :phase :done
                                  :args ["test/x_test.clj"]}]}])
                  vprov/focused-argv (constantly ["verify"])
                  vprov/available? (fn [] (swap! probes inc) true)
                  vprov/run (fn [& _] {:green? true :output ""})]
      (let [work (future
                   (tools/run-tool
                    {:conn :fake :run-id "run" :turn 1 :branch branch
                     :turn-lease lease
                     :evaluator/binding {:binding/id "bind:test"}
                     :evaluator/profile :agent/project-develop
                     :tool-name "done" :root "/tmp" :config {:run {}}
                     :args {:answer "answer"}}))]
        (is (= true (deref preparing 1000 ::not-preparing)))
        (is (base/revoke-turn-lease! lease :deadline))
        (deliver release true)
        (let [r (deref work 1000 ::timeout)]
          (is (not= ::timeout r))
          (is (= :failure (:category r)))
          (is (zero? @probes)))))))

(deftest a-permitted-long-effect-does-not-hold-the-lease-monitor
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
    (is (base/revoke-turn-lease! lease :deadline))
    (deliver release true)
    (is (= :done (deref work 1000 ::timeout)))
    (is (= :revoked (base/turn-lease-status lease)))))

(deftest revocation-during-bounded-eval-blocks-a-delayed-edit-before-intent
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        lease (base/mint-turn-lease "run" "B1" 1)
        entered (promise)
        release (promise)
        receipts (atom [])
        path (str (System/getProperty "java.io.tmpdir")
                  "/samizdat-stale-edit-" (random-uuid) ".txt")
        evaluate! (fn [_conn _binding _source opts]
                    (deliver entered true)
                    @release
                    ((:effect-permit! opts)
                     #(swap! receipts conj {:op :project/edit :args [path]}))
                    (spit path "stale write")
                    {:value :edited})]
    (try
      (with-redefs-fn
        {#'repl-tools/evaluator-var
         (fn [name] (when (= name "evaluate-recorded!") evaluate!))}
        (fn []
          (let [work (future
                       (tools/run-tool
                        {:run-id "run" :turn 1 :branch branch
                         :turn-lease lease
                         :evaluator/binding {:binding/id "test"}
                         :tool-name "eval"
                         :args {:code "(project/edit ...)"}}))]
            (is (= true (deref entered 1000 ::not-entered)))
            (is (base/revoke-turn-lease! lease :deadline))
            (deliver release true)
            (let [r (deref work 1000 ::timeout)]
              (is (not= ::timeout r))
              (is (= :failure (:category r)))
              (is (empty? @receipts))
              (is (not (.exists (java.io.File. path))))))))
      (finally
        (deliver release true)
        (when (.exists (java.io.File. path))
          (.delete (java.io.File. path)))))))

(deftest deadline-revokes-before-interrupt-and-quiescence-allows-next-turn
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
               @order))
        (is (= 1 (:timeouts after-timeout)))
        (is (:second-turn? after-next))
        (is (= 2 @calls))
        (is (= 1 @max-active))))))

(deftest bounded-workflow-enters-the-scheduler-at-width-one-with-fresh-leases
  (with-db [c]
    (let [minted (atom [])
          permits (atom 0)
          responses (atom
                     [{:content "```tool-call\n{\"name\":\"eval\",\"args\":{\"code\":\"(+ 1 2)\"}}\n```"
                       :finish-reason "stop"}
                      {:content "```tool-call\n{\"name\":\"done\",\"args\":{\"answer\":\"solved\"}}\n```"
                       :finish-reason "stop"}])
          original-mint base/mint-turn-lease
          fake-binding {:binding/id "bind:test" :instance/id "inst:test"
                        :work-id "work:test"
                        :spec {:spec/coordinate "spec:test"
                               :runtime-coordinate "runtime:test"
                               :context-spec {:context/profile
                                              :agent/project-develop}}
                        :trusted-orientation "bounded"}
          complete (fn [_]
                     (let [[r & more] @responses]
                       (reset! responses more)
                       {:ok true :response r}))
          evaluate! (fn [_conn _binding _code opts]
                      ((:effect-permit! opts) #(swap! permits inc))
                      {:value 3})
          done! (fn [{:keys [branch]}]
                  (assoc (base/ok (assoc branch :status :done
                                               :final-answer "solved")
                                  "done")
                         :done? true :control-event :done
                         :verified-green? true))]
      (with-redefs [base/mint-turn-lease
                    (fn [run-id branch-id turn]
                      (let [lease (original-mint run-id branch-id turn)]
                        (swap! minted conj lease)
                        lease))
                    workflow/bounded-binding
                    (fn [_conn _root _run-id _config] fake-binding)
                    infer/complete-fn (fn [_] complete)
                    ship/bounded-done done!]
        (with-redefs-fn
          {#'repl-tools/evaluator-var
           (fn [name] (when (= name "evaluate-recorded!") evaluate!))}
          (fn []
            (let [r (workflow/run!
                     {:conn c
                      :config {:run {:bounded {:profile :agent/project-develop}
                                     :beam-width 5 :max-turns 5}}
                      :llm-adapter :stub
                      :llm-config {:provider :stub :model "stub"}
                      :problem "solve" :max-turns 5 :beam-width 5})]
              (is (= :completed (:status r)))
              (is (= 1 (:beam_width (runs/get-run c (:run-id r)))))
              (is (= 2 (count @minted)))
              (is (= [[(:run-id r) "B1" 1] [(:run-id r) "B1" 2]]
                     (mapv (juxt :run-id :branch-id :turn) @minted)))
              (is (every? #(= :revoked (base/turn-lease-status %)) @minted))
              (is (= [:turn-completed :turn-completed]
                     (mapv #(-> % :state deref :reason) @minted)))
              (is (= 1 @permits)))))))))

(deftest an-unquiesced-worker-is-durably-terminal-and-gets-no-next-authority
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 3 :beam-width 1})
          branch (state/new-branch {:id "B1" :problem "p"})
          calls (atom 0)
          interrupted (promise)
          release (promise)
          finished (promise)
          advance (fn [_ b _]
                    (swap! calls inc)
                    (try
                      (try
                        (Thread/sleep 10000)
                        (catch Throwable _
                          (deliver interrupted true)
                          @release))
                      (assoc b :status :done :final-answer "stale delayed answer")
                      (finally (deliver finished true))))]
      (runs/open-branch! c rid {:branch-id "B1"})
      (try
        (with-redefs [beam/advance-branch advance]
          (let [error (try
                        (#'beam/advance-all
                         {:conn c :run-id rid :iterating-loop? true
                          :turn-deadline-ms 20 :turn-cancel-grace-ms 20}
                         [branch] 1)
                        nil
                        (catch Throwable e e))]
            (is (= true (deref interrupted 1000 ::not-interrupted)))
            (is (= :unquiesced (:samizdat.turn-lease/error (ex-data error))))
            (is (= "failed" (:status (runs/get-run c rid))))
            (is (= "turn-cancellation-fault"
                   (:terminal_reason (runs/get-run c rid))))
            (is (= 1 @calls))
            (is (some #(= "turn-cancellation-fault" (:kind %))
                      (journal/events-since c rid 0)))
            (is (not (resume/resumable? c rid)))))
        (finally
          (deliver release true)
          (is (= true (deref finished 1000 ::not-finished))))))))
