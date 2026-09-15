;; samizdat - a claim-first verification harness
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

(ns samizdat.telemetry.otel-test
  "The semantic wrappers against the in-memory exporter: attribute types,
  false/zero/absent, signed negatives, the Langfuse mirror, lifecycle spans
  through the hook, suppression, and TRACEPARENT parenting."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [otel.context :as ctx]
            [otel.exporter.memory :as memory]
            [otel.trace :as trace]
            [samizdat.telemetry.contract :as contract]
            [samizdat.telemetry.hook :as hook]
            [samizdat.telemetry.otel :as tel]))

(def ^:dynamic *mem* nil)

(defn- with-runtime [mode f]
  (let [mem (memory/exporter)]
    (tel/shutdown!)
    (tel/init! {:mode mode :exporters {:local mem :langfuse mem}
                :batch {:schedule-delay-ms 50}
                ;; The default content policy, stated rather than read from
                ;; the shell's environment.
                :content {:enabled? false}})
    (try (binding [*mem* mem] (f))
         (finally (tel/shutdown!) (hook/uninstall!)))))

(use-fixtures :each (fn [f] (with-runtime :local f)))

(defn- exported []
  (tel/flush!)
  (memory/spans *mem*))

(defn- by-name [n] (first (filter #(= n (:name %)) (exported))))

(deftest execution-span-carries-typed-facts
  (tel/with-execution [sp "execution" {"samizdat.run.id" "r1"
                                       "samizdat.execution.id" "p1"
                                       "samizdat.execution.kind" "parent"
                                       "samizdat.seed" 12001
                                       "samizdat.observation.mode" "synthetic"}]
    (tel/record-outcome! sp {:worker-claim "fixed" :worker-claimed true :protocol-correct false
                             :infra-error false :task-complete false :terminal-status "claimed"})
    (tel/record-budgets! sp {:turns {:granted 20 :used 20 :remaining 0}
                             :wall {:granted_ms 305100 :used_ms 312431 :remaining_ms -7331}})
    (trace/set-attribute! sp "gen_ai.usage.output_tokens" 0))
  (let [s (by-name "execution")
        a (:attributes s)]
    (is (some? s))
    (is (= "agent" (get a "samizdat.observation.kind")))
    (is (= "agent" (get a "langfuse.observation.type")))
    (is (= "r1" (get a "langfuse.session.id")) "session falls back to run id")
    (is (= 12001 (get a "samizdat.seed")))
    (is (true? (get a "samizdat.worker.claimed")))
    (is (false? (get a "samizdat.protocol.correct")))
    (is (false? (get a "samizdat.infra.error")))
    (is (false? (get a "samizdat.task.complete")))
    (is (= 0 (get a "samizdat.budget.turns.remaining")))
    (is (= -7331 (get a "samizdat.budget.wall.remaining_ms")))
    (is (= 0 (get a "gen_ai.usage.output_tokens")))
    (is (= "parent" (get a "langfuse.trace.metadata.execution_kind")))
    (is (not (contains? a "langfuse.observation.level")) "no infra error, no ERROR level")
    (is (= :unset (get-in s [:status :code]) ) "a failed protocol verdict is not a span error")))

(deftest infra-error-is-a-level-not-a-score
  (tel/with-evaluator [sp "action.check" {"samizdat.evaluator.id" "s80/5"
                                          "samizdat.evaluator.status" "infra-error"
                                          "samizdat.evaluator.purpose" "operational"
                                          "samizdat.evaluator.exit" 2}]
    (tel/record-feedback! sp {:delivered true :status "infra-error" :sha256 "ab" :bytes 6063
                              :projection "verdict-and-log" :policy "inform@1"}))
  (let [s (by-name "action.check") a (:attributes s)]
    (is (= "evaluator" (get a "langfuse.observation.type")))
    (is (= "ERROR" (get a "langfuse.observation.level")))
    (is (= "infra-error" (get a "samizdat.feedback.status")))
    (is (= 6063 (get a "samizdat.feedback.bytes")))
    (is (true? (get a "samizdat.feedback.delivered")))
    (is (= "feedback.delivered" (:name (first (:events s)))))
    (is (not-any? #(re-find #"score" (str %)) (keys a)))))

(deftest unknown-and-ill-typed-facts-fall-back-to-strings
  (tel/with-tool [_ "tool" {"samizdat.tool.name" "run"
                            "samizdat.tool.seconds" 0.25
                            "samizdat.tool.output_chars" "lots"
                            "samizdat.private.thing" "x"}])
  (let [a (:attributes (by-name "tool"))]
    (is (= 0.25 (get a "samizdat.tool.seconds")))
    (is (= "lots" (get a "samizdat.x.samizdat.tool.output_chars")))
    (is (= "x" (get a "samizdat.x.samizdat.private.thing")))
    (is (not (contains? a "samizdat.private.thing")))))

(deftest hook-observer-failure-never-changes-the-seam
  ;; The run seams (beam/run!, llm.client/chat, tools/run-tool, ...) all go
  ;; through hook/observe!; an observer that throws must leave the seam's
  ;; value and effects exactly as without one.
  (let [n (atom 0)]
    (hook/install! (fn [_ _ _] (throw (ex-info "telemetry broke" {}))))
    (is (= {:content "x"} (hook/observe! :model {:provider "p" :model "m"}
                                         (fn [] (swap! n inc) {:content "x"}))))
    (is (= 1 @n))))

(deftest suppressed-context-records-nothing
  (ctx/with-instrumentation-suppressed
    (tel/with-tool [_ "suppressed" {}]))
  (tel/with-tool [_ "visible" {}])
  (is (= ["visible"] (map :name (exported)))))

(deftest off-mode-installs-nothing
  (tel/shutdown!)
  (is (nil? (tel/init! {:mode :off})))
  (is (not (hook/installed?)))
  (is (nil? (tel/stats)))
  (tel/with-execution [sp "noop" {"samizdat.execution.id" "x"}]
    (is (not (trace/recording? sp)))))

(deftest inbound-traceparent-parents-the-root
  (let [c (otel.propagation/extract-context
           {"traceparent" "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"})]
    (ctx/with-context c
      (tel/with-execution [_ "child" {"samizdat.execution.id" "x"}]))
    (let [s (by-name "child")]
      (is (= "0af7651916cd43dd8448eb211c80319c" (get-in s [:span-context :trace-id])))
      (is (= "b7ad6b7169203331" (:parent-span-id s))))))

(deftest trace-metadata-is-mirrored-from-the-root-observation-only
  ;; Live finding (mapping/1): two evaluator.check children each mirrored
  ;; samizdat.cost.action_charged_s into langfuse.trace.metadata.*, and the
  ;; trace kept the last writer's value. Under mapping/2 only the agent
  ;; (root) span writes trace metadata; children keep the same fact in
  ;; their own observation metadata.
  (tel/with-execution [root "execution" {"samizdat.run.id" "r1"
                                         "samizdat.execution.kind" "continuation"
                                         "samizdat.observation.mode" "synthetic"}]
    (tel/with-evaluator [_ "action.check" {"samizdat.evaluator.id" "s80/5"
                                           "samizdat.evaluator.status" "passed"
                                           "samizdat.evaluator.purpose" "operational"
                                           "samizdat.cost.action_charged_s" 0.045}])
    (tel/with-evaluator [sp "action.check2" {"samizdat.evaluator.id" "s80/5"
                                             "samizdat.evaluator.purpose" "operational"}]
      (tel/set-facts! sp {"samizdat.cost.action_charged_s" 0.042
                          "samizdat.evaluator.status" "failed"}))
    (tel/record-budgets! root {:turns {:remaining 3}}))
  (let [r (:attributes (by-name "execution"))
        c1 (:attributes (by-name "action.check"))
        c2 (:attributes (by-name "action.check2"))]
    (is (= "continuation" (get r "langfuse.trace.metadata.execution_kind")))
    (is (= 3 (get r "langfuse.trace.metadata.budget_turns_remaining")) "set-facts! on the root still writes trace metadata")
    (is (= 0.045 (get c1 "samizdat.cost.action_charged_s")) "canonical key always kept")
    (is (= 0.045 (get c1 "langfuse.observation.metadata.cost_action_charged_s")))
    (is (= 0.042 (get c2 "langfuse.observation.metadata.cost_action_charged_s")) "set-facts! reads the span's kind")
    (is (= "failed" (get c2 "langfuse.observation.metadata.evaluator_status")))
    (doseq [a [c1 c2]]
      (is (not-any? #(clojure.string/starts-with? % "langfuse.trace.metadata.") (keys a))
          "a child never writes trace-level metadata"))))

(deftest observation-content-travels-only-in-synthetic-mode
  (tel/with-execution [_ "synthetic-exec" {"samizdat.run.id" "r1" "samizdat.observation.mode" "synthetic"}]
    (tel/with-generation [sp "gen" {"gen_ai.request.model" "stub-model"
                                    "samizdat.observation.mode" "synthetic"
                                    "langfuse.observation.input" "SYNTHETIC-IN"}]
      (tel/set-facts! sp {"langfuse.observation.output" "SYNTHETIC-OUT"})))
  (tel/with-execution [_ "live-exec" {"samizdat.run.id" "r2" "samizdat.observation.mode" "live"}]
    (tel/with-tool [sp "tool" {"samizdat.tool.name" "run"
                               "samizdat.observation.mode" "live"
                               "langfuse.observation.input" "LEAK-IN"}]
      (tel/set-facts! sp {"langfuse.observation.output" "LEAK-OUT"})))
  (tel/with-tool [sp "modeless" {"samizdat.tool.name" "run"
                                 "langfuse.observation.output" "LEAK-MODELESS"}])
  (let [g (:attributes (by-name "gen"))
        t (:attributes (by-name "tool"))
        m (:attributes (by-name "modeless"))]
    (is (= "SYNTHETIC-IN" (get g "langfuse.observation.input")))
    (is (= "SYNTHETIC-OUT" (get g "langfuse.observation.output")) "set-facts! reads the span's mode")
    (is (not (contains? g "samizdat.x.content.refused")))
    (doseq [a [t m]]
      (is (not-any? #(re-find #"LEAK" (str %)) (vals a)) "refused content never reaches the span")
      (is (not (contains? a "langfuse.observation.input")))
      (is (not (contains? a "langfuse.observation.output"))))
    (is (= "langfuse.observation.input,langfuse.observation.output" (get t "samizdat.x.content.refused"))
        "refusals accumulate across prepare and set-facts!")
    (is (= "langfuse.observation.output" (get m "samizdat.x.content.refused")) "absent mode refuses")))

(deftest prepare-is-pure-and-manifest-driven
  (let [a (tel/prepare :generation {"gen_ai.request.model" "stub-model"
                                    "gen_ai.usage.input_tokens" 12
                                    "samizdat.family.id" "fam"})]
    (is (= "generation" (get a "langfuse.observation.type")))
    (is (= "stub-model" (get a "langfuse.observation.model.name")))
    (is (= "fam" (get a "langfuse.session.id")))
    (is (= (contract/schema-version) (get a "samizdat.telemetry.schema")))))

(deftest harness-run-seams-emit-a-run-trace-through-the-hook
  ;; The M2 seams as the harness calls them (samizdat-m2-core.edn's surface),
  ;; driven directly through the hook: a run whose turn runs in a future (as
  ;; beam/advance-all does), one model call with usage, one tool, one steer.
  (let [problem "write a function that returns 42"
        r (hook/observe! :run {:provider :local :model "m" :problem problem
                               :max-turns 4 :beam-width 1}
            (fn []
              (hook/observe! :control-loop {:run-id "r9" :start-turn 1 :branches 1}
                (fn []
                  (hook/observe! :branch-open {:run-id "r9" :branch-id "B1" :parent-id nil :created-at-turn 0}
                                 (fn [] "B1"))
                  @(future
                     (hook/observe! :turn {:run-id "r9" :branch-id "B1" :turn 1}
                       (fn []
                         (hook/observe! :model {:provider :local :model "m" :messages 3 :max-tokens 64
                                                :prefill false :force-tool false}
                           (fn [] {:content "<tool>eval</tool>" :finish-reason "stop" :elapsed-ms 120
                                   :usage {:prompt-tokens 10 :completion-tokens 5 :total-tokens 15}}))
                         (hook/observe! :tool-selection {:turn 1 :content-chars 18}
                           (fn [] {:parsed {:name "eval" :args {}} :said "<tool>eval</tool>" :tape {}}))
                         (hook/observe! :tool {:branch-id "B1" :tool-name "eval"}
                           (fn [] {:result "42" :category :success :progress? true}))
                         (hook/observe! :steer {:branch-id "B1" :turn 1} (fn [] nil))
                         {:status :active})))
                  (hook/observe! :branch-close {:run-id "r9" :branch-id "B1" :status :done} (fn [] 1))
                  {:status :done}))
              {:status :done :run-id "r9" :answer "42"}))]
    (is (= "r9" (:run-id r)))
    (let [spans (exported)
          by (fn [n] (first (filter #(= n (:name %)) spans)))
          root (by "run")
          rid (get-in root [:span-context :span-id])
          tid (get-in root [:span-context :trace-id])
          a (fn [s k] (get-in s [:attributes k]))]
      (is (= #{"run" "run.rounds" "branch.open" "turn" "model.chat" "tool.selection" "tool" "steer" "branch.close"}
             (set (map :name spans))))
      (is (every? #(= tid (get-in % [:span-context :trace-id])) spans) "one trace, futures included")
      (is (= rid (:parent-span-id (by "run.rounds"))))
      (is (= (get-in (by "run.rounds") [:span-context :span-id]) (:parent-span-id (by "turn"))))
      (is (= (get-in (by "turn") [:span-context :span-id]) (:parent-span-id (by "model.chat"))))
      (is (= "agent" (a root "langfuse.observation.type")))
      (is (= "generation" (a (by "model.chat") "langfuse.observation.type")))
      (is (= "tool" (a (by "tool") "langfuse.observation.type")))
      (is (= "r9" (a root "samizdat.run.id")) "the run id is set from the result")
      (is (= "r9" (a root "langfuse.trace.metadata.run_id")) "and mirrored to trace metadata")
      (is (= "done" (a root "samizdat.run.status")))
      (is (true? (a root "samizdat.task.complete")))
      (is (= 64 (count (a root "samizdat.problem.sha256"))))
      (is (= (count problem) (a root "samizdat.problem.chars")))
      (is (not-any? #(clojure.string/includes? (pr-str (:attributes %)) "returns 42") spans)
          "no problem text travels")
      (is (= [10 5 15] (map #(a (by "model.chat") %)
                            ["gen_ai.usage.input_tokens" "gen_ai.usage.output_tokens" "gen_ai.usage.total_tokens"])))
      (is (= "stop" (a (by "model.chat") "gen_ai.response.finish_reason")))
      (is (nil? (a (by "model.chat") "gen_ai.usage.cache_hit_tokens")) "absent stays absent")
      (is (= "eval" (a (by "tool.selection") "samizdat.selection.tool")))
      (is (= "success" (a (by "tool") "samizdat.tool.category")))
      (is (= 2 (a (by "tool") "samizdat.tool.output_chars")))
      (is (false? (contains? (:attributes (by "steer")) "samizdat.steer.gate")) "a nil steer sets no gate")
      (is (= "done" (a (by "branch.close") "samizdat.branch.status")))
      (is (every? #(= "live" (a % "samizdat.observation.mode")) spans)))))

(defn- with-content-runtime
  "A fresh runtime with the content override set explicitly (never from the
  environment, so the suite is independent of the shell it runs in)."
  [policy f]
  (let [mem (memory/exporter)]
    (tel/shutdown!)
    (tel/init! {:mode :local :exporters {:local mem} :batch {:schedule-delay-ms 50}
                :content policy})
    (try (binding [*mem* mem] (f))
         (finally (tel/shutdown!) (hook/uninstall!)))))

(def ^:private branch-before
  {:id "B1" :status :active :inactive-reason nil :created-at-turn 0 :turns [] :phase :explore
   :messages [{:role "user" :content "USER-TEXT"}] :consecutive-failures 0 :turns-since-progress 0})

(def ^:private branch-after
  (-> branch-before
      (update :messages conj {:role "assistant" :content "MODEL-OUTPUT <tool>eval</tool>"}
              {:role "user" :content "TOOL-RESULT 42"})
      (assoc :turns [1] :status :active)))

(defn- drive-three-seams
  "Every harness seam as the hook receives it, nested as the beam nests them:
  the run opens a branch, runs rounds, one turn with a model call, its tool
  selection, the tool and a steer, closes the branch and answers."
  []
  (hook/observe! :run {:provider :local :model "m" :problem "PROBLEM-TEXT returns 42"
                       :max-turns 2 :beam-width 1}
    (fn []
      (hook/observe! :branch-open {:run-id "rc" :branch-id "B1" :parent-id nil :created-at-turn 0
                                   :problem "PROBLEM-TEXT returns 42"}
        (fn [] "B1"))
      (hook/observe! :control-loop {:run-id "rc" :start-turn 1 :branches 1 :branch-list [branch-before]}
        (fn []
          (hook/observe! :turn {:run-id "rc" :branch-id "B1" :turn 1 :branch branch-before}
            (fn []
              (hook/observe! :model {:provider :local :model "m" :messages 2 :max-tokens 64
                                     :prefill false :force-tool false
                                     :input [{:role "system" :content "SYSTEM-TEXT"}
                                             {:role "user" :content "USER-TEXT"}]}
                (fn [] {:content "MODEL-OUTPUT <tool>eval</tool>" :finish-reason "stop" :elapsed-ms 5
                        :usage {:prompt-tokens 3 :completion-tokens 2 :total-tokens 5}}))
              (hook/observe! :tool-selection {:turn 1 :content-chars 30 :content "MODEL-OUTPUT <tool>eval</tool>"
                                              :prefill "<tool>"}
                (fn [] {:parsed {:name "eval" :args {:expr "(+ 40 2)"}}
                        :signals {:no-fence false :truncated false}
                        :said "MODEL-OUTPUT <tool>eval</tool>"}))
              (hook/observe! :tool {:branch-id "B1" :tool-name "eval" :input {:expr "(+ 40 2)"}}
                (fn [] {:result "TOOL-RESULT 42" :category :success :progress? true}))
              (hook/observe! :steer {:branch-id "B1" :turn 1 :branch branch-before}
                (fn [] {:gate :verify-first :priority 2 :message "STEER-TEXT verify"
                        :prediction "PREDICTION-TEXT" :tool :eval :effect (fn [b] b)
                        :window 3 :passed-over [:cull]}))
              branch-after))
          {:status :done :run-id "rc" :answer "ANSWER-TEXT 42" :branches [branch-after]}))
      (hook/observe! :branch-close {:run-id "rc" :branch-id "B1" :status :done :reason "REASON-TEXT answered"}
        (fn [] 1))
      (hook/observe! :steer {:branch-id "B2" :turn 2 :branch branch-before} (fn [] nil))
      {:status :done :run-id "rc" :answer "ANSWER-TEXT 42"})))

(deftest content-override-off-by-default-carries-no-text
  ;; The :each fixture's runtime is the default policy. The seams hand the
  ;; text in; none of it may reach a span, and — unlike content a caller
  ;; states explicitly — there is no refusal marker either: the override
  ;; simply never produces the keys.
  (drive-three-seams)
  (let [spans (exported)
        blob (pr-str (map :attributes spans))]
    (is (= 10 (count spans)))
    (doseq [needle ["PROBLEM-TEXT" "SYSTEM-TEXT" "USER-TEXT" "MODEL-OUTPUT" "(+ 40 2)" "TOOL-RESULT" "ANSWER-TEXT"
                    "STEER-TEXT" "PREDICTION-TEXT" "REASON-TEXT"]]
      (is (not (clojure.string/includes? blob needle)) (str needle " must not travel")))
    (is (not-any? #(contains? (:attributes %) "langfuse.observation.input") spans))
    (is (not-any? #(contains? (:attributes %) "langfuse.observation.output") spans))
    (is (not-any? #(contains? (:attributes %) "samizdat.x.content.refused") spans))
    (is (not-any? #(contains? (:attributes %) "samizdat.content.truncated") spans))
    (is (every? #(= "off" (get-in % [:resource :attributes "samizdat.telemetry.content"])) spans)
        "the resource states the policy the process ran under")))

(deftest content-override-on-carries-model-bound-text-on-three-seams
  (with-content-runtime {:enabled? true}
    (fn []
      (drive-three-seams)
      (let [spans (exported)
            by (fn [n] (first (filter #(= n (:name %)) spans)))
            a (fn [s k] (get-in s [:attributes k]))
            root (by "run") gen (by "model.chat") tool (by "tool")
            steers (filter #(= "steer" (:name %)) spans)
            steer (first (filter #(= "B1" (a % "samizdat.branch.id")) steers))
            no-steer (first (filter #(= "B2" (a % "samizdat.branch.id")) steers))]
        (is (= 10 (count spans)))
        (is (every? #(= "on" (get-in % [:resource :attributes "samizdat.telemetry.content"])) spans))
        (testing "run: the problem in, the answer out (the trace's own I/O in Langfuse v4)"
          (is (= "PROBLEM-TEXT returns 42" (a root "langfuse.observation.input")))
          (is (= "ANSWER-TEXT 42" (a root "langfuse.observation.output")))
          (is (= 64 (count (a root "samizdat.problem.sha256"))) "the facts stay beside the text"))
        (testing "model.chat: the wire messages as JSON in, the content out"
          (is (= "[{\"role\":\"system\",\"content\":\"SYSTEM-TEXT\"},{\"role\":\"user\",\"content\":\"USER-TEXT\"}]"
                 (a gen "langfuse.observation.input")))
          (is (= "MODEL-OUTPUT <tool>eval</tool>" (a gen "langfuse.observation.output")))
          (is (= 3 (a gen "gen_ai.usage.input_tokens"))))
        (testing "tool: the model's args as JSON in, the (redacted) result out"
          (is (= "{\"expr\":\"(+ 40 2)\"}" (a tool "langfuse.observation.input")))
          (is (= "TOOL-RESULT 42" (a tool "langfuse.observation.output"))))
        (testing "branch.open: the branch's problem in, its id out"
          (is (= "{\"branch-id\":\"B1\",\"created-at-turn\":0,\"problem\":\"PROBLEM-TEXT returns 42\"}"
                 (a (by "branch.open") "langfuse.observation.input")))
          (is (= "{\"branch-id\":\"B1\"}" (a (by "branch.open") "langfuse.observation.output"))))
        (testing "run.rounds: the branches it starts from, the status and branches it ends with"
          (is (= "{\"start-turn\":1,\"branches\":[{\"id\":\"B1\",\"status\":\"active\",\"turns\":0,\"messages\":1,\"consecutive-failures\":0,\"turns-since-progress\":0,\"phase\":\"explore\"}]}"
                 (a (by "run.rounds") "langfuse.observation.input")))
          (is (clojure.string/starts-with? (a (by "run.rounds") "langfuse.observation.output")
                                           "{\"status\":\"done\",\"answer\":\"ANSWER-TEXT 42\",\"branches\":[{\"id\":\"B1\"")))
        (testing "turn: the branch and the message it starts from in; its state and the messages it appended out"
          (is (clojure.string/includes? (a (by "turn") "langfuse.observation.input")
                                        "\"last-message\":{\"role\":\"user\",\"content\":\"USER-TEXT\"}"))
          (is (clojure.string/includes? (a (by "turn") "langfuse.observation.output")
                                        "\"appended\":[{\"role\":\"assistant\",\"content\":\"MODEL-OUTPUT <tool>eval</tool>\"},{\"role\":\"user\",\"content\":\"TOOL-RESULT 42\"}]"))
          (is (not (clojure.string/includes? (a (by "turn") "langfuse.observation.output") "USER-TEXT"))
              "only what the turn appended, never the whole tape"))
        (testing "tool.selection: the model's reply (and prefill) in, the parsed call and signals out"
          (is (= "{\"content\":\"MODEL-OUTPUT <tool>eval</tool>\",\"prefill\":\"<tool>\"}"
                 (a (by "tool.selection") "langfuse.observation.input")))
          (is (= "{\"parsed\":{\"name\":\"eval\",\"args\":{\"expr\":\"(+ 40 2)\"}},\"signals\":{\"no-fence\":false,\"truncated\":false}}"
                 (a (by "tool.selection") "langfuse.observation.output")))
          (is (= "eval" (a (by "tool.selection") "samizdat.selection.tool"))))
        (testing "steer: the branch counters in, the realised decision out (never a gate's effect fn)"
          (is (clojure.string/includes? (a steer "langfuse.observation.input") "\"turn\":1"))
          (is (= "{\"steered\":true,\"gate\":\"verify-first\",\"priority\":2,\"message\":\"STEER-TEXT verify\",\"prediction\":\"PREDICTION-TEXT\",\"tool\":\"eval\",\"window\":3,\"passed-over\":[\"cull\"]}"
                 (a steer "langfuse.observation.output")))
          (is (= "{\"steered\":false}" (a no-steer "langfuse.observation.output")) "no steer says so, without a null gate")
          (is (not (clojure.string/includes? (a steer "langfuse.observation.input") "null")) "nil branch facts are pruned, not serialised as null"))
        (testing "branch.close: status and reason in, rows out"
          (is (= "{\"branch-id\":\"B1\",\"status\":\"done\",\"reason\":\"REASON-TEXT answered\"}"
                 (a (by "branch.close") "langfuse.observation.input")))
          (is (= "{\"rows\":1,\"closed\":true}" (a (by "branch.close") "langfuse.observation.output"))))
        (testing "every seam carries an input and an output"
          (doseq [sp spans]
            (is (contains? (:attributes sp) "langfuse.observation.input") (:name sp))
            (is (contains? (:attributes sp) "langfuse.observation.output") (:name sp))))
        (is (not-any? #(contains? (:attributes %) "samizdat.x.content.refused") spans))
        (is (not-any? #(contains? (:attributes %) "samizdat.content.truncated") spans) "nothing clipped")
        (is (every? #(= "live" (a % "samizdat.observation.mode")) spans) "still live, still marked")))))

(deftest content-override-clips-each-value-and-says-so
  (with-content-runtime {:enabled? true :max-chars 12}
    (fn []
      (drive-three-seams)
      (let [spans (exported)
            by (fn [n] (first (filter #(= n (:name %)) spans)))
            a (fn [s k] (get-in s [:attributes k]))]
        (is (= "PROBLEM-TEXT" (a (by "run") "langfuse.observation.input")))
        (is (= "ANSWER-TEXT " (a (by "run") "langfuse.observation.output")))
        (is (true? (a (by "run") "samizdat.content.truncated")))
        (is (= "[{\"role\":\"sy" (a (by "model.chat") "langfuse.observation.input")))
        (is (true? (a (by "model.chat") "samizdat.content.truncated")))
        (is (= 30 (a (by "model.chat") "samizdat.model.content_chars")) "chars describe the full value")
        (is (= 14 (a (by "tool") "samizdat.tool.output_chars")))
        (is (= "{\"rows\":1,\"c" (a (by "branch.close") "langfuse.observation.output")))
        (is (true? (a (by "branch.close") "samizdat.content.truncated")))))))

(deftest content-override-never-opens-historical-import
  (with-content-runtime {:enabled? true}
    (fn []
      (tel/with-execution [sp "import-exec" {"samizdat.run.id" "r1"
                                             "samizdat.observation.mode" "historical-import"
                                             "langfuse.observation.input" "LEAK-IN"}]
        (tel/set-facts! sp {"langfuse.observation.output" "LEAK-OUT"}))
      (let [a (:attributes (by-name "import-exec"))]
        (is (not-any? #(re-find #"LEAK" (str %)) (vals a)))
        (is (= "langfuse.observation.input,langfuse.observation.output" (get a "samizdat.x.content.refused")))))))

(deftest content-flag-and-policy-parsing
  (is (tel/parse-content-flag "on"))
  (is (tel/parse-content-flag " ON "))
  (doseq [v [nil "" "off" "1" "true" "yes" "ON!"]]
    (is (not (tel/parse-content-flag v)) (pr-str v)))
  (testing "shutdown! resets the policy"
    (with-content-runtime {:enabled? true :max-chars 7} (fn [] (is (tel/content-enabled?))))
    (is (not (tel/content-enabled?)))
    (is (= tel/default-content-max-chars (:max-chars @tel/content-policy)))))
