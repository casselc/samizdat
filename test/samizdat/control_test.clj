;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.control-test
  "Steering a running agent from the REPL. A human submits a directive against
  the run's db; the loop drains it at the next boundary and the arbiter injects
  it into the branch's next turn at priority zero, above every machine gate.
  The specification test drives a real run and asserts a REPL steer lands in
  the model's context."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.beam :as beam]
            [samizdat.control :as control]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.resume :as resume]
            [samizdat.agent.state :as state]
            [samizdat.api.control :as api-control]
            [samizdat.llm.client :as llm]
            [samizdat.security.controller :as controller]
            [samizdat.security.policy :as policy]
            [samizdat.store.db :as db]
            [samizdat.store.grants :as grants]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest steer-queues-a-message-directive
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (control/steer! c rid "wire truncate-middle into the shell tool")
      (let [[d] (interventions/pending c rid)]
        (is (= "message" (:kind d)))
        (is (str/includes? (str (:payload d)) "truncate-middle"))
        (is (= "pending" (:status d)))))))

(deftest list-and-run-scoped-viewers
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (control/steer! c rid "do the thing")
      (control/steer! c rid "then the other thing" {:branch-id "B1"})
      (is (= 2 (count (control/pending c rid))))
      (is (= ["do the thing" "then the other thing"]
             (mapv :payload (control/pending c rid)))))))

(deftest a-grant-intervention-is-applied-immediately
  ;; a#2 (docs/code-review.md): grants/grant! had no production caller, so
  ;; every deliberate :ask blocked a run forever — no endpoint, no tool, no
  ;; intervention kind wrote a grant. The human intervention surface is the
  ;; write path, and it applies on arrival rather than queueing for a
  ;; boundary, because the policy consults the grants table per command.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (testing "before the grant, the interpreter asks"
        (is (= :ask (:effect (policy/decide (grants/for-run c rid) "python3 x.py")))))
      (testing "a grant intervention writes the grant now"
        (let [r (api-control/intervene! c rid {:kind "grant"
                                               :payload {:pattern "python3 *"}})]
          (is (= "granted" (:status (:body r))))
          (is (= :allow (:effect (policy/decide (grants/for-run c rid) "python3 x.py"))))))
      (testing "a grant without a pattern is refused, not queued"
        (let [r (api-control/intervene! c rid {:kind "grant"})]
          (is (= 400 (:status r)))
          (is (str/includes? (str (get-in r [:body :error :message])) "pattern"))))
      (testing "the queued kinds still queue"
        (let [r (api-control/intervene! c rid {:kind "message" :payload "hi"})]
          (is (= "pending" (:status (:body r))))
          (is (= 1 (count (interventions/pending c rid)))))))))

;; --- the drain at the boundary ----------------------------------------------

(deftest a-pending-directive-is-drained-and-injected
  ;; A directive submitted before a turn boundary is drained by the loop, the
  ;; arbiter fires the human-directive gate at priority zero, and the payload
  ;; lands in the branch's next-turn message. Then it is resolved as applied.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          _ (runs/open-branch! c rid {:branch-id "B1"})
          ctx {:conn c :run-id rid :max-turns 10
               :llm-adapter :a :llm-config {:max-tokens 16384}}
          b (state/new-branch {:id "B1" :problem "p"})]
      (control/steer! c rid "STEER: add a docstring to truncate-middle")
      (with-redefs [llm/chat (fn [& _]
                               {:content "```tool-call\n{\"name\": \"task\", \"args\": {\"action\": \"list\"}}\n```"
                                :finish-reason "stop"})]
        (let [after (aloop/run-turn ctx b 1)
              last-msg (last (:messages after))]
          (testing "the directive text is injected into the next-turn message"
            (is (str/includes? (:content last-msg) "human has intervened"))
            (is (str/includes? (:content last-msg) "add a docstring to truncate-middle")))
          (testing "the directive is resolved as applied, not left pending"
            (is (empty? (interventions/pending c rid)))
            (is (= "applied" (:status (first (interventions/history c rid)))))))))))

(deftest a-run-that-finishes-in-the-start-window-leaves-no-active-entry
  ;; code-review-2026-08 #3: the run future's completion dissoc'd `active`
  ;; before the request thread had assoc'd it, stranding an entry that let
  ;; abort! rewrite a finished run's status to :aborted. Registration must
  ;; happen inside the run's own thread (on-start), so it can never land
  ;; after the completion dissoc.
  (with-db [c]
    (with-redefs [beam/run! (fn [{:keys [on-start]}]
                              (let [rid (str (random-uuid))]
                                (on-start rid)
                                {:run-id rid :status :completed}))]
      (let [r (api-control/start-run! {:conn c :config {:llm {:provider :local}}} {})
            rid (:run_id (:body r))]
        (is (= "running" (:status (:body r))))
        (let [gone? (loop [n 0]
                      (cond (nil? (get @api-control/active rid)) true
                            (< n 100) (do (Thread/sleep 10) (recur (inc n)))
                            :else false))]
          (is gone? "no stranded active entry after an instant run"))
        (is (= 409 (:status (api-control/abort! c rid)))
            "abort on a finished run refuses rather than rewriting status")))))

(deftest abort-refuses-when-the-run-won-the-finish-race
  ;; review2 #4: the transient window a#3 could not close — the run's own
  ;; :completed lands between abort!'s registry read and its finish-run!.
  ;; The store guard refuses the rewrite; abort! must answer 409 rather
  ;; than claim an abort that did not land.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (runs/finish-run! c rid :completed "done")
      ;; simulate the in-window registry entry the run's thread has not
      ;; dissoc'd yet
      (swap! api-control/active assoc rid {:abort (atom false)})
      (try
        (is (= 409 (:status (api-control/abort! c rid))))
        (is (= "completed" (:status (runs/get-run c rid))))
        (finally (swap! api-control/active dissoc rid))))))

(deftest an-unknown-intervention-kind-is-a-400-not-a-500
  ;; review3 #12: submit! throws on an unknown kind, and intervene! let it
  ;; fly through to the server's catch-all 500. A bad request is the
  ;; client's to fix; the API should say 400 and name the known kinds.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (let [r (api-control/intervene! c rid {:kind "explode" :payload "x"})]
        (is (= 400 (:status r)) "refused with a client error")
        (is (str/includes? (str (get-in r [:body :error :message])) "kind")
            "the message says what was wrong")
        (is (empty? (interventions/pending c rid))
            "nothing was queued")))))

;; --- JS1 budget authority ----------------------------------------------------

(defn- authority-for
  "A minted handle from a config shape, or nil when unconfigured."
  ([]
   (authority-for {:budget-token "op-budget-token" :budget-ceiling 50}))
  ([controller] (controller/authority {:controller controller})))

(defn- budget-fixture
  "A run in the shape extension is for: budget 5, B1 exhausted, B2 culled,
  row interrupted (the process died)."
  [c]
  (let [rid (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1})]
    (runs/open-branch! c rid {:branch-id "B1"})
    (runs/open-branch! c rid {:branch-id "B2"})
    (runs/close-branch! c rid "B1" :exhausted "turn cap")
    (runs/close-branch! c rid "B2" :culled "dominated")
    (runs/reconcile-orphans! c)
    rid))

(defn- nothing-changed?
  "The run still reads max 5, B1 still exhausted, and no audit row exists —
  the shared assertion under every refusal."
  [c rid]
  (and (= 5 (:max_turns (runs/get-run c rid)))
       (= "exhausted" (:status (runs/get-branch c rid "B1")))
       (empty? (runs/extension-audit-for-run c rid))))

(deftest extension-requires-an-authority-that-cannot-be-forged
  ;; The gate is a dedicated opaque handle minted from trusted config.
  ;; Everything a request, tool call, or model turn could construct — a
  ;; flag, a map shaped like the config, the token STRING itself — is
  ;; refused, because none of them is the handle. There is no comparison
  ;; to influence; possession is the authority.
  (with-db [c]
    (let [rid (budget-fixture c)]
      (testing "nil (nothing configured) is refused"
        (let [r (controller/extend-budget!
                 nil c {:run-id rid :request-id "req-a1" :new-max 20
                        :reason "no authority"})]
          (is (false? (:ok r)))
          (is (= :unauthorized (:code r)))
          (is (nothing-changed? c rid))))
      (testing "an EDN trusted-flag is refused"
        (let [r (controller/extend-budget!
                 {:trusted true} c {:run-id rid :request-id "req-a2"
                                    :new-max 20 :reason "forged flag"})]
          (is (= :unauthorized (:code r)))
          (is (nothing-changed? c rid))))
      (testing "a map carrying the RIGHT token is still refused — data is
                 not the handle"
        (let [r (controller/extend-budget!
                 {:budget-token "op-budget-token"} c
                 {:run-id rid :request-id "req-a3" :new-max 20
                  :reason "forged config"})]
          (is (= :unauthorized (:code r)))
          (is (nothing-changed? c rid))))
      (testing "the raw token string is refused"
        (let [r (controller/extend-budget!
                 "op-budget-token" c {:run-id rid :request-id "req-a4"
                                      :new-max 20 :reason "token as handle"})]
          (is (= :unauthorized (:code r)))
          (is (nothing-changed? c rid))))
      (testing "the refusal never echoes what was presented"
        (let [r (controller/extend-budget!
                 {:budget-token "op-budget-token"} c
                 {:run-id rid :request-id "req-a5" :new-max 20
                  :reason "forged"})]
          (is (not (str/includes? (str r) "op-budget-token")))))
      (testing "the minted handle is the one thing that works"
        (let [r (controller/extend-budget!
                 (authority-for) c {:run-id rid :request-id "req-a6"
                                    :new-max 20 :reason "legitimate"})]
          (is (true? (:ok r)))
          (is (= 20 (:new-max r)))
          (is (= ["B1"] (:reopened r))
              "the exhausted branch reopened, the culled one did not"))))))

(deftest a-record-of-the-right-type-with-an-unminted-digest-is-refused
  ;; H2: instance? is not verification. The Authority constructors are
  ;; ordinary vars, so in-process code CAN build a record of the right
  ;; type; what it cannot do is register a digest without the configured
  ;; token. The gate checks the config-derived mint set, so both a
  ;; garbage digest and a nil digest are refused exactly like a map.
  (with-db [c]
    (let [rid (budget-fixture c)]
      (testing "a hand-built record carrying a plausible digest is refused"
        (let [forged (controller/map->Authority
                      {:token-digest "deadbeefcafe" :ceiling nil
                       :principal "forger"})
              r (controller/extend-budget!
                 forged c {:run-id rid :request-id "req-f1" :new-max 20
                           :reason "right type, wrong provenance"})]
          (is (= :unauthorized (:code r)))
          (is (not (str/includes? (str r) "deadbeefcafe"))
              "the refusal does not echo the presented digest")
          (is (nothing-changed? c rid))))
      (testing "a hand-built record with no digest at all is refused"
        (let [r (controller/extend-budget!
                 (controller/map->Authority
                  {:token-digest nil :ceiling nil :principal "forger"})
                 c {:run-id rid :request-id "req-f2" :new-max 20
                    :reason "no digest"})]
          (is (= :unauthorized (:code r)))
          (is (nothing-changed? c rid))))
      (testing "a digest minted from config still passes"
        (let [r (controller/extend-budget!
                 (authority-for) c {:run-id rid :request-id "req-f3"
                                    :new-max 20 :reason "real mint"})]
          (is (true? (:ok r))))))))

(deftest the-handle-prints-without-its-substance
  ;; str and pr of the authority carry the principal label only — never
  ;; the token or its digest — so a handle that wanders into a log line
  ;; leaks nothing replayable.
  (let [a (authority-for)]
    (is (not (str/includes? (str a) "op-budget-token")))
    (is (not (str/includes? (pr-str a) "op-budget-token")))
    (is (re-find #"controller.Authority" (str a)))))

(deftest extension-is-idempotent-by-request-id
  (with-db [c]
    (let [rid (budget-fixture c)
          a (authority-for)
          ask (fn [id new-max]
                (controller/extend-budget!
                 a c {:run-id rid :request-id id :new-max new-max
                      :reason "one lemma away"}))]
      (let [first (ask "req-b1" 20)
            again (ask "req-b1" 20)]
        (is (true? (:ok first)))
        (is (false? (:replayed? first)) "the first application is not a replay")
        (is (true? (:replayed? again)) "the retry answers from the record")
        (is (= (:new-max first) (:new-max again))
            "with the same recorded outcome")
        (is (= 1 (count (runs/extension-audit-for-run c rid)))
            "and exactly one audit row exists"))
      (testing "a replay after further extension still answers from the record"
        (ask "req-b2" 30)
        (let [late (ask "req-b1" 20)]
          (is (true? (:replayed? late)))
          (is (= 20 (:new-max late)) "the FIRST request's outcome, not the row")))
      (testing "one id cannot be re-spent on a different extension"
        (let [r (ask "req-b1" 40)]
          (is (= :request-conflict (:code r)))
          (is (= 30 (:max_turns (runs/get-run c rid)))
              "the row kept the extension that actually landed"))))))

(deftest extension-refusals-name-the-policy-broken
  (with-db [c]
    (let [rid (budget-fixture c)
          a (authority-for)
          ask (fn [id new-max run]
                (controller/extend-budget!
                 a c {:run-id run :request-id id :new-max new-max
                      :reason "why"}))]
      (testing "not a raise: equal and lower are both refused"
        (is (= :not-monotonic (:code (ask "req-c1" 5 rid))))
        (is (= :not-monotonic (:code (ask "req-c2" 3 rid))))
        (is (nothing-changed? c rid)))
      (testing "past the minted ceiling is refused; the ceiling itself passes"
        (is (= :over-ceiling (:code (ask "req-c3" 51 rid)))
            "51 > ceiling 50")
        (let [r (ask "req-c4" 50 rid)]
          (is (true? (:ok r)) "the ceiling is a maximum, not an off-by-one"))
          (is (= 50 (:max_turns (runs/get-run c rid)))))
      (testing "an unknown run is refused, not created"
        (is (= :unknown-run (:code (ask "req-c5" 20 "no-such-run")))))
      (testing "terminal runs keep their budget as part of their record"
        (let [done (runs/start-run! c {:problem "q" :max-turns 5
                                       :beam-width 1})]
          (runs/finish-run! c done :completed "shipped")
          (is (= :terminal-run (:code (ask "req-c6" 20 done))))
          (is (= 5 (:max_turns (runs/get-run c done))))))
      (testing "a malformed request is refused before anything is read"
        ;; A fresh fixture: this deftest's own successful ceiling extension
        ;; already moved the earlier run, so "nothing changed" needs a run
        ;; that really is untouched.
        (let [fresh (budget-fixture c)
              bad (controller/extend-budget!
                   a c {:run-id fresh :request-id "" :new-max 20
                        :reason "why"})]
          (is (= :bad-request (:code bad)))
          (is (nothing-changed? c fresh)))
        (let [fresh2 (budget-fixture c)
              bad (controller/extend-budget!
                   a c {:run-id fresh2 :request-id "req-c7" :new-max :lots
                        :reason "why"})]
          (is (= :bad-request (:code bad)))
          (is (nothing-changed? c fresh2)))))))

(deftest extension-refuses-a-cancellation-faulted-run
  ;; A run failed closed over an unquiesced turn worker is terminal for
  ;; budget exactly as it is terminal for resume: the retained
  ;; terminal_reason (v13) is the refusal, and a raise must not reopen its
  ;; exhausted branches or write an audit row over a worker nobody can
  ;; prove has ended. An ordinary failed run — the process died, the row
  ;; carries no terminal_reason — extends exactly as it resumes.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 5 :beam-width 1})
          a (authority-for)]
      (runs/open-branch! c rid {:branch-id "B1"})
      (runs/close-branch! c rid "B1" :exhausted "turn cap")
      (runs/finish-run-cancellation-fault! c rid)
      (let [r (controller/extend-budget!
               a c {:run-id rid :request-id "req-g1" :new-max 20
                    :reason "one lemma away"})]
        (is (false? (:ok r)))
        (is (= :terminal-run (:code r)))
        (is (nothing-changed? c rid)
            "no cap raise, no branch reopened, no audit row"))
      (testing "an ordinary failed run still extends"
        (let [ordinary (runs/start-run! c {:problem "q" :max-turns 5
                                           :beam-width 1})]
          (runs/open-branch! c ordinary {:branch-id "B1"})
          (runs/close-branch! c ordinary "B1" :exhausted "turn cap")
          (runs/finish-run! c ordinary :failed nil)
          (let [r (controller/extend-budget!
                   a c {:run-id ordinary :request-id "req-g2" :new-max 20
                        :reason "the process died; the budget question is open"})]
            (is (true? (:ok r)))
            (is (= ["B1"] (:reopened r)))
            (is (= 20 (:max_turns (runs/get-run c ordinary))))))))))

(deftest the-token-never-reaches-any-record
  ;; The authority is configured, used, and audited; the token itself
  ;; appears in no audit row, no journal event, and no result map. The
  ;; audit says who and why — that is the whole point of it saying
  ;; anything at all. The DIGEST is held to the same rule: it is a
  ;; capability coordinate, not evidence.
  (with-db [c]
    (let [rid (budget-fixture c)
          a (authority-for {:budget-token "never-journal-me"
                            :budget-principal "night-operator"})
          digest (#'controller/sha256 "never-journal-me")
          r (controller/extend-budget!
             a c {:run-id rid :request-id "req-d1" :new-max 20
                  :reason "close"})]
      (is (true? (:ok r)))
      (let [audits (runs/extension-audit-for-run c rid)
            events (journal/events-since c rid 0)]
        (is (not (str/includes? (str audits) "never-journal-me")))
        (is (not (str/includes? (str events) "never-journal-me")))
        (is (not (str/includes? (str r) "never-journal-me")))
        (is (not (str/includes? (str audits) digest))
            "the digest is in no audit row")
        (is (not (str/includes? (str events) digest))
            "the digest is in no journal event")
        (is (not (str/includes? (str r) digest))
            "the digest is in no result map")
        (is (= "night-operator" (:principal (first audits)))
            "the principal label is recorded in its place")))))

(deftest repl-extend-routes-through-the-controller
  ;; The REPL supervisor path and the controller path are the same path:
  ;; ctl/extend! without a handle is as closed as the API, with a handle
  ;; it is the audited act. There is no second, freer entry point.
  (with-db [c]
    (let [rid (budget-fixture c)]
      (testing "without the authority it refuses"
        (let [r (control/extend! c nil rid 20
                                 {:request-id "req-e1" :reason "no handle"})]
          (is (= :unauthorized (:code r)))
          (is (nothing-changed? c rid))))
      (testing "with it, the extension is the audited act"
        (let [r (control/extend! c (authority-for) rid 20
                                 {:request-id "req-e2"
                                  :reason "close but out of budget"})]
          (is (true? (:ok r)))
          (is (= 20 (:max_turns (runs/get-run c rid))))
          (is (= 1 (count (runs/extension-audit-for-run c rid)))))))))

(deftest the-resume-api-cannot-raise-the-budget
  ;; The public surface must not grant what only the controller holds: a
  ;; resume body asking for more budget is refused before any future is
  ;; spawned, and an at-or-below ask (or none at all) resumes under the
  ;; recorded cap exactly as before — the non-JS1 behavior preserved.
  (with-db [c]
    (let [rid (budget-fixture c)
          resumed (atom nil)
          wait-for (fn [pred]
                     (loop [n 0]
                       (cond (pred) true
                             (< n 300) (do (Thread/sleep 10) (recur (inc n)))
                             :else false)))]
      (testing "a widening ask is a 403 refusal, not a resume"
        (let [r (api-control/resume! {:conn c :config {:llm {:provider :local}}} rid
                                     {:max_turns 500})]
          (is (= 403 (:status r)))
          (is (str/includes? (str (get-in r [:body :error :message]))
                             "trusted-controller"))
          (is (= 5 (:max_turns (runs/get-run c rid)))
              "the recorded budget is untouched")
          (is (nil? (get @api-control/active rid))
              "and no background resume was spawned")))
      (testing "garbage max_turns is a 400, not a 500"
        (is (= 400 (:status (api-control/resume! {:conn c :config {:llm {:provider :local}}} rid
                                                 {:max_turns "a lot"})))))
      (testing "a nonpositive ask is a 400, not a silent no-op"
        ;; H2: 0 or -5 used to fall past the 403 ladder into the resume
        ;; path, where it was ignored — a caller could believe it had
        ;; bounded the resume when nothing was bounded.
        (doseq [ask [0 -5 "0"]]
          (let [r (api-control/resume! {:conn c :config {:llm {:provider :local}}} rid
                                       {:max_turns ask})]
            (is (= 400 (:status r)) (str "ask " (pr-str ask) " is refused"))
            (is (str/includes? (str (get-in r [:body :error :message]))
                               "positive integer"))))
        (is (nil? (get @api-control/active rid))
            "no background resume was spawned by a refused ask"))
      (testing "an at-or-below ask resumes under the recorded cap"
        (with-redefs [resume/resume!
                      (fn [m] (reset! resumed m)
                        {:status :completed :run-id (:run-id m)})]
          (let [r (api-control/resume! {:conn c :config {:llm {:provider :local}}} rid
                                       {:max_turns 5})]
            (is (= "resuming" (:status (:body r))))
            (is (= 5 (:max_turns (:body r)))
                "the reported budget is the recorded one")
            (is (wait-for #(some? @resumed))
                "the resume itself did run"))
          (is (not (contains? @resumed :max-turns))
              "the resume was not handed an extension parameter"))))))

(deftest the-resume-api-refuses-a-cancellation-faulted-run
  ;; H1 at the API edge: a run failed over an unquiesced turn worker is
  ;; terminal for resume — its stale worker may still exist, so fresh
  ;; authority must never be minted for it.  The durable
  ;; :turn-cancellation-fault event grounds the refusal (a process restart
  ;; changes nothing), and the surface answers 409 before any future runs.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 3 :beam-width 1})]
      (runs/open-branch! c rid {:branch-id "B1"})
      (journal/note! c rid :turn-cancellation-fault
                     {:turn 1 :data {:branches ["B1"] :grace-ms 20
                                     :reason "unquiesced worker"}})
      (runs/finish-run! c rid :failed nil)
      (let [r (api-control/resume! {:conn c :config {:llm {:provider :local}}}
                                   rid {})]
        (is (= 409 (:status r)))
        (is (nil? (get @api-control/active rid))
            "no resume future was spawned")
        (is (= "failed" (:status (runs/get-run c rid)))
            "the row was never marked running")))))
