;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.branch-error-test
  "When a branch dies, the journal must say WHAT failed.

  ws-opt trial 2 (2026-09-22) died on turn 5 and left one line: `branch error:
  execution error`. No class, no cause, no failing node. At beam width 1 the
  branch was the run, so the run ended there with nothing to act on.

  The cause was that `advance-all` recorded `(ex-message r)` of the OUTERMOST
  exception - and a wrapped cell error's outermost message is exactly that
  generic string. `unwrap-round-error` already existed for the ROUND path and
  was never applied to the BRANCH path.

  These replay the recorded turn-5 tool call through the beam with a fake
  provider and a failure injected after the tool returns, and assert the real
  cause reaches the journal."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.beam :as beam]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.workflow :as workflow]))

;; The turn-5 call as the model actually wrote it, from
;; records/ws-opt-trial-2/run-reconstruction.json.
(def ^:private turn-5-args
  {:action "create"
   :title "Add :upper option to render-row and render-report"
   :body "Add an `:upper` option to `render-row` in src/ws_opt/render.clj"
   :type "task"
   :claim true})

(defn- fenced [name args]
  {:content (str "working on it\n```tool-call\n"
                 (json/write-str {:name name :args args})
                 "\n```")
   :finish-reason "stop"})

(defn- branch-errors [c]
  ;; branch_id and turn are columns; the rest is the JSON data blob.
  (->> (db/fetch c ["SELECT branch_id, turn, data FROM events
                       WHERE kind = 'branch-error'"])
       (mapv (fn [r] (merge {:branch-id (:branch_id r) :turn (:turn r)}
                            (json/read-str (str (:data r)) :key-fn keyword))))))

(defn- run-with [injected]
  (let [c (db/open! ":memory:")
        calls (atom 0)]
    (with-redefs [llm/chat (fn [& _]
                             (swap! calls inc)
                             (fenced "task" turn-5-args))
                  ;; The failure lands where the live one did: the turn ran,
                  ;; the tool ran, and the branch died on the way back out.
                  ;; `note-schema-warnings!` is the first thing `advance-branch`
                  ;; calls once the compiled turn returns, so throwing there
                  ;; puts the exception on exactly the path that produced
                  ;; "execution error".
                  workflow/note-schema-warnings! (fn [& _] (throw injected))]
      (try
        (beam/run! {:conn c :config {:run {:width 1}} :llm-adapter :a
                    :llm-config {:max-tokens 100} :problem "p" :max-turns 2})
        (catch Throwable _ nil)))
    c))

(deftest the-cause-chain-survives-into-the-journal
  (let [boom (ex-info "execution error"
                      {:last-state-id :run-turn
                       :error (ex-info "inner cell failed"
                                       {:tool "task"}
                                       (IllegalStateException. "the real cause"))})
        c (run-with boom)]
    (try
      (let [errs (branch-errors c)]
        (is (seq errs) "a branch-error note was written at all")
        (let [e (first errs)]
          (testing "it says which run, branch, turn and operation"
            (is (some? (:branch-id e)))
            (is (some? (:turn e)))
            (is (= "turn" (:operation e))))
          (testing "it carries the REAL cause, not the wrapper's generic message"
            (is (not= "execution error" (:message e))
                "the outermost message is what made the live failure unreadable")
            (is (str/includes? (str (:causes e)) "the real cause"))
            (is (str/includes? (str (:causes e)) "IllegalStateException")))
          (testing "it names the failing node"
            (is (str/includes? (str (:node e)) "run-turn")))
          (testing "the trace is recorded, or its absence is"
            (is (contains? e :trace))
            (is (contains? e :trace-empty?)))))
      (finally (db/close c)))))

(deftest ex_data_is_reduced_to_keys_so_prompts_cannot_leak
  (let [boom (ex-info "execution error"
                      {:error (ex-info "inner" {:prompt "SECRET-PROMPT-TEXT"
                                                :api-key "sk-SHOULD-NOT-APPEAR"})})
        c (run-with boom)]
    (try
      (let [e (first (branch-errors c))
            dumped (str e)]
        (is (some? e))
        (is (not (str/includes? dumped "SECRET-PROMPT-TEXT"))
            "ex-data values are never journaled")
        (is (not (str/includes? dumped "sk-SHOULD-NOT-APPEAR")))
        (is (str/includes? (str (:ex-data-keys e)) "prompt")
            "the SHAPE is still visible, so a reader knows what the cell carried"))
      (finally (db/close c)))))

(deftest a-plain-exception-is-recorded-too
  (let [c (run-with (RuntimeException. "no wrapper at all"))]
    (try
      (let [e (first (branch-errors c))]
        (is (some? e))
        (is (str/includes? (str (:message e)) "no wrapper at all"))
        (is (str/includes? (str (:type e)) "RuntimeException")))
      (finally (db/close c)))))

;; The defect the recording above was built to catch, pinned as behaviour.
;;
;; `samizdat.agent.loop` reads (get-in parsed [:args :claim]) from EVERY tool
;; call and uses it as the FTS similarity query for the context block. That
;; slot is shared by all tools, so a tool that means something else by `claim`
;; - a boolean "create and claim it" - reached str/blank? as a non-string and
;; threw ClassCastException "string-length: true is not a string", closing the
;; branch. The live symptom was "branch error: execution error" with the cause
;; discarded, and it looked intermittent because it only fired on the turns
;; where the model actually passed the boolean.
;;
;; Two things are pinned: the loop no longer trusts every tool to spell
;; :claim as a query string, and the task tool no longer occupies that slot.
(deftest a-non-string-claim-argument-does-not-kill-the-branch
  (doseq [v [true false 1]]
    (let [c (db/open! ":memory:")]
      (with-redefs [llm/chat (fn [& _] (fenced "task" {:action "create" :title "t" :claim v}))]
        (try
          (beam/run! {:conn c :config {:run {:width 1}} :llm-adapter :a
                      :llm-config {:max-tokens 100} :problem "p" :max-turns 2})
          (catch Throwable _ nil)))
      (is (empty? (db/fetch c ["SELECT data FROM events WHERE kind='branch-error'"]))
          (str "a :claim of " (pr-str v) " closed the branch"))
      (db/close c))))

(deftest claim-now-takes-the-task-through-the-whole-loop
  (let [c (db/open! ":memory:")]
    (with-redefs [llm/chat (fn [& _] (fenced "task" {:action "create" :title "t" :claim_now true}))]
      (try
        (beam/run! {:conn c :config {:run {:width 1}} :llm-adapter :a
                    :llm-config {:max-tokens 100} :problem "p" :max-turns 2})
        (catch Throwable _ nil)))
    (is (empty? (db/fetch c ["SELECT data FROM events WHERE kind='branch-error'"])))
    (is (seq (db/fetch c ["SELECT id FROM tasks WHERE status='in_progress'"]))
        "claim_now created the task but did not claim it")
    (db/close c)))
