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
            [samizdat.agent.state :as state]
            [samizdat.api.control :as api-control]
            [samizdat.api.runs :as api-runs]
            [samizdat.llm.client :as llm]
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

(deftest a-negative-limit-is-not-a-disguised-no-limit
  ;; review3 #12: a negative limit went straight into SQL LIMIT, and SQLite
  ;; reads LIMIT -1 as no limit at all — so ?limit=-1 answered with the whole
  ;; table while looking like a tighter ask. The API edge clamps it to zero.
  (with-db [c]
    (dotimes [_ 3] (runs/start-run! c {:problem "p"}))
    (testing "a negative limit returns nothing, not everything"
      (is (= 0 (count (:runs (api-runs/list-runs c -1))))))
    (testing "a positive limit still bounds"
      (is (= 2 (count (:runs (api-runs/list-runs c 2))))))))

(deftest watch-follows-the-run-not-a-hardcoded-branch
  ;; review3 #13: watch read branch-turns for "B1" and only "B1", so on a
  ;; beam run — 5 branches by default — the supervisor's window showed one
  ;; arm of the run and the other four were invisible, including whatever
  ;; branch was actually doing the work. Default to the run's last active
  ;; branch; an explicit id still narrows.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (runs/open-branch! c rid {:branch-id "B1"})
      (runs/open-branch! c rid {:branch-id "B2"})
      (journal/record-turn! c rid {:branch-id "B1" :turn 1
                                   :tool-name "shell" :result "b1-work"
                                   :category :success})
      (journal/record-turn! c rid {:branch-id "B2" :turn 1
                                   :tool-name "shell" :result "b2-work"
                                   :category :success})
      (testing "default follows the last active branch"
        (is (= ["b2-work"] (mapv :result (control/watch c rid)))))
      (testing "an explicit branch still narrows"
        (is (= ["b1-work"] (mapv :result (control/watch c rid 8 "B1"))))))))
