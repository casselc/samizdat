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

(ns samizdat.workflow-test
  "The loop as data: the workflow definition lives in the db, compiles through
  mycelium's checks, and the manifest-driven driver produces the same runs the
  hand-written loop did. Editing the stored definition changes the next run —
  that is the whole point."
  (:require [clojure.data.json :as json]
            [samizdat.agent.beam :as beam]
            [samizdat.cells :as cells]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.repl :as repl-tools]
            [samizdat.llm.client :as llm]
            [samizdat.repl :as repl]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.store.workflows :as workflows]
            [samizdat.workflow :as workflow]
            [mycelium.workflow :as wf]))

;; The loop cells now live in resources and load at runtime — nothing
;; registers them as a namespace side effect anymore. Load them before the
;; tests that inspect the definition directly (compile-loop loads them itself,
;; but workflow-effects-are-fully-declared reads them without compiling).
(use-fixtures :once (fn [f] (cells/load-cells!) (f)))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(defn- fence [m]
  {:content (str "```tool-call\n" (json/write-str m) "\n```")
   :finish-reason "stop"})

(defn- scripted
  "An llm/chat replacement that returns each response in turn, repeating the
  last one when the script runs out."
  [& responses]
  (let [remaining (atom responses)]
    (fn [& _]
      (let [[r & more] @remaining]
        (when (seq more) (reset! remaining more))
        r))))

;; --- the store --------------------------------------------------------------

(deftest workflow-store-roundtrip-and-versioning
  (with-db [c]
    (is (nil? (workflows/load-latest c "loop")))
    (is (= 1 (workflows/save! c "loop" "{:cells {}}")))
    (is (= 2 (workflows/save! c "loop" "{:cells {:a :b}}")))
    (let [w (workflows/load-latest c "loop")]
      (is (= 2 (:version w)))
      (is (= "{:cells {:a :b}}" (:edn w))))
    (is (= "{:cells {}}" (:edn (workflows/load-version c "loop" 1))))))

(deftest seeding-is-idempotent
  (with-db [c]
    (is (= 1 (:version (workflows/seed! c "loop" "manifests/loop.edn"))))
    (is (= 1 (:version (workflows/seed! c "loop" "manifests/loop.edn")))
        "a second seed does not stack versions")
    (is (some? (:edn (workflows/load-latest c "loop"))))))

;; --- the definition ---------------------------------------------------------

(deftest the-shipped-loop-definition-compiles-clean
  (let [def (workflow/read-definition (slurp (clojure.java.io/resource "manifests/loop.edn")))
        compiled (workflow/compile-loop def)]
    (is (some? compiled))
    (is (nil? (:mycelium/compile-warnings (:compiled-fsm compiled)))
        "every loop cell declares its effects")))

(deftest removing-the-journal-hop-fails-compile
  ;; The constraint is the mutation protocol's teeth: an agent edit that
  ;; routes a tool result around the journal must die at compile, not ship.
  (let [def (workflow/read-definition (slurp (clojure.java.io/resource "manifests/loop.edn")))
        ;; Route the tool path around the journal while keeping :journal
        ;; reachable from the no-call path, so the unreachable check cannot
        ;; catch it first — only the constraint can.
        broken (-> def
                   (assoc-in [:edges :dispatch] :arbiter)
                   (assoc-in [:edges :no-call] :journal))]
    (is (thrown-with-msg? Exception #"must-follow"
                          (workflow/compile-loop broken)))))

;; --- the driver -------------------------------------------------------------

(deftest a-scripted-run-ships-through-the-manifest
  (with-db [c]
    (with-redefs [llm/chat (scripted
                            (fence {:name "thesis"
                                    :args {:goal "solve the problem"
                                           :technique "direct"}})
                            (fence {:name "done"
                                    :args {:answer "the problem is solved directly"}}))]
      (let [r (workflow/run! {:conn c :config {:run {}}
                              :llm-adapter :a :llm-config {:max-tokens 16384}
                              :problem "solve the problem" :max-turns 10})]
        (is (= :completed (:status r)))
        (is (= "the problem is solved directly" (:answer r)))
        (let [turns (journal/branch-turns c (:run-id r) "B1")]
          (is (= ["thesis" "done"] (mapv :tool_name turns))))
        (is (= "completed" (:status (runs/get-run c (:run-id r)))))))))

(deftest the-beam-drives-the-manifest-too
  ;; The fix for the review's biggest finding: the beam used to call
  ;; samizdat.agent.loop's steps directly and never touch a manifest, so
  ;; `:run :loop` was documented, parsed from HARNESS_LOOP, and read by
  ;; nothing on the production path — every POST /v1/runs got the factory
  ;; composition no matter what was configured, and critic/team/feature/
  ;; decompose ran only under this suite. The beam now compiles the per-turn
  ;; SLICE of the selected manifest and runs each branch through it.
  (with-db [c]
    (with-redefs [llm/chat (scripted
                            (fence {:name "thesis"
                                    :args {:goal "solve the problem"
                                           :technique "direct"}})
                            (fence {:name "done"
                                    :args {:answer "the problem is solved directly"}}))]
      (let [r (beam/run! {:conn c :config {:run {:beam-width 1}}
                          :llm-adapter :a :llm-config {:max-tokens 16384}
                          :problem "solve the problem" :max-turns 10 :beam-width 1})]
        (is (= :completed (:status r)))
        (is (= "the problem is solved directly" (:answer r)))
        (testing "the branch ran the manifest's per-turn chain"
          (is (= ["thesis" "done"]
                 (mapv :tool_name (journal/branch-turns c (:run-id r) "B1")))))
        (testing "the run records which loop drove it, like the other driver"
          (let [note (->> (journal/events-since c (:run-id r) 0)
                          (filter #(= "loop-workflow" (:kind %)))
                          first)]
            (is (some? note)
                "a beam run journals its :loop-workflow provenance")))))))

(deftest a-non-iterating-manifest-forces-beam-width-1
  ;; team/feature/decompose are whole-run workflows: one pass is the branch's
  ;; entire job, not one model call. Running five concurrently would multiply
  ;; the job rather than explore five lines of one, so the beam overrides the
  ;; requested width and says so in the run row.
  (with-db [c]
    (with-redefs [llm/chat (scripted (fence {:name "give_up"
                                             :args {:reason "stub"}}))]
      (let [r (beam/run! {:conn c :config {:run {:loop "team" :subtasks ["a"]}}
                          :llm-adapter :a :llm-config {:max-tokens 16384}
                          :problem "anything" :max-turns 4 :beam-width 5})]
        (is (= 1 (:beam_width (runs/get-run c (:run-id r))))
            "a whole-run manifest runs one branch regardless of the width asked for")))))

(deftest beam-driven-whole-run-subloops-run-their-own-tool-calls
  ;; The JS1 controller-review blocker, on the production path: the beam
  ;; mints a per-turn lease for (run, branch, turn). A whole-run manifest
  ;; runs the branch's ENTIRE job as one beam turn and fans out worker
  ;; subloops as different branches (W0, then the supervisor's W0r1) at
  ;; their own turn counts — and the B1/turn-1 lease used to ride the ctx
  ;; down into those subloops, refusing every tool call they made as stale.
  ;; The worker burned its whole turn budget on refusals and never even
  ;; managed to give up. The lease is a per-turn boundary; a manifest with
  ;; no per-turn iteration now carries none, and the subloops dispatch as
  ;; the legacy non-JS1 callers they are.
  (with-db [c]
    (with-redefs [llm/chat (scripted (fence {:name "give_up"
                                             :args {:reason "stub"}}))]
      (let [r (beam/run! {:conn c :config {:run {:loop "team" :subtasks ["a"]}}
                          :llm-adapter :a :llm-config {:max-tokens 16384}
                          :problem "anything" :max-turns 4 :beam-width 5})]
        (is (= :completed (:status r)))
        (doseq [bid ["W0" "W0r1"]]
          (let [turns (journal/branch-turns c (:run-id r) bid)]
            (is (= ["give_up"] (mapv :tool_name turns))
                (str bid " acts once and lands it"))
            (is (= ["neutral"] (mapv :category turns))
                (str bid "'s give_up lands as itself — not a stale-lease refusal"))
            (is (not-any? #(str/includes? (str (:result %))
                                          "Turn authority expired")
                          turns)
                (str "no tool call in " bid " was refused on the manager's lease"))))))))

(deftest js1-whole-run-workflows-are-refused-before-any-budget-spend
  ;; The other half of the blocker: JS1 is single-player AND single-loop.
  ;; A whole-run manifest's role/worker subloops would share the run's one
  ;; persistent SCI binding across branches, so the shape must die before
  ;; the run row exists and before the model is ever called — on BOTH
  ;; drivers, and on the resume path (resume_test covers that one).
  (with-db [c]
    (doseq [loop-nm ["team" "feature" "decompose"]]
      (testing (str "beam/run! refuses JS1 + " loop-nm " before any spend")
        (let [calls (atom 0)
              runs-before (count (db/fetch c ["SELECT id FROM runs"]))]
          (with-redefs [llm/chat (fn [& _]
                                   (swap! calls inc)
                                   (fence {:name "give_up"
                                           :args {:reason "x"}}))]
            (is (= :whole-run-workflow-not-supported
                   (try
                     (beam/run! {:conn c
                                 :config {:run {:loop loop-nm
                                                :js1/profile "single-player"
                                                :subtasks ["a"]}}
                                 :llm-adapter :a :llm-config {:max-tokens 16384}
                                 :problem "p" :max-turns 4 :beam-width 1})
                     nil
                     (catch Throwable e (:js1/error (ex-data e)))))
                "the whole-run shape, not the width — width is 1 here")
            (is (zero? @calls) "no model call was paid for")
            (is (= runs-before (count (db/fetch c ["SELECT id FROM runs"])))
                "no run row was opened")))))
    (testing "workflow/run! refuses the same shape just as early"
      (let [calls (atom 0)
            runs-before (count (db/fetch c ["SELECT id FROM runs"]))]
        (with-redefs [llm/chat (fn [& _] (swap! calls inc) nil)]
          (is (= :whole-run-workflow-not-supported
                 (try
                   (workflow/run! {:conn c
                                   :config {:run {:loop "team"
                                                  :js1/profile "single-player"
                                                  :subtasks ["a"]}}
                                   :llm-adapter :a :llm-config {}
                                   :problem "p"})
                   nil
                   (catch Throwable e (:js1/error (ex-data e))))))
          (is (zero? @calls))
          (is (= runs-before (count (db/fetch c ["SELECT id FROM runs"])))
              "no run row was opened"))))))

(deftest the-single-loop-guard-admits-iterating-loops
  ;; The guard exists to refuse a SHAPE, not the profile: JS1 on an
  ;; iterating single-branch loop is the supported form, and a non-JS1
  ;; whole-run workflow is nobody's business.
  (is (nil? (workflow/js1-assert-single-loop! true "loop" true)))
  (is (nil? (workflow/js1-assert-single-loop! false "team" false))
      "a non-JS1 whole-run workflow is untouched")
  (is (thrown? ExceptionInfo
               (workflow/js1-assert-single-loop! true "team" false))))

(deftest js1-workflow-entry-advances-an-authorized-eval
  ;; The dogfood regression, deterministically: workflow/run! with a JS1
  ;; profile used to drive the whole-run manifest directly with NO
  ;; :turn-lease in the ctx, so dispatch-tool stale-refused EVERY model tool
  ;; call ("Turn authority expired; this stale tool call was not
  ;; dispatched.") and the run could only burn its budget.  The JS1 entry
  ;; now hands the run to the beam scheduler at forced width 1 — the one
  ;; place the TurnLease lifecycle exists — so a scheduled turn's lease
  ;; authorizes the eval, its token reaches the sandbox seam, and the
  ;; recorded effect's durable intent launches under a lease permit.
  ;;
  ;; No SCI and no provider: the binding mint is redefined to an inert
  ;; binding map (what SCI-backed creation returns), and the eval tool's
  ;; sandbox resolution seam (repl-tools/sandbox-var, the same seam the
  ;; turn-lease suite uses) supplies a fake evaluate-recorded!.
  (with-db [c]
    (let [observed (atom [])
          permits (atom 0)
          fake-bind (fn [_conn run-id _config _root]
                      {:binding {:binding/id (str "bind:main:" run-id)
                                 :instance/id "inst:main"
                                 :work-id (str run-id)
                                 :spec {:preset :project/develop}}
                       :provider nil :profile "single-player"})
          fake-evaluate! (fn [_conn _binding code opts]
                           (swap! observed conj {:code code
                                                 :token (:token opts)})
                           ;; The recorded effect's intent boundary: issues
                           ;; the synchronized permit (and throws :stale if
                           ;; the turn's authority were gone).
                           ((:effect-permit! opts) (fn [] (swap! permits inc)))
                           {:value 3})]
      (with-redefs-fn {#'workflow/js1-binding fake-bind
                       #'repl-tools/sandbox-var
                       (fn [var-name]
                         (when (= var-name "evaluate-recorded!")
                           fake-evaluate!))}
        (fn []
          (with-redefs [llm/chat (scripted
                                  (fence {:name "eval"
                                          :args {:code "(+ 1 2)"}})
                                  (fence {:name "done"
                                          :args {:answer "the problem is solved directly"}}))]
            (let [r (workflow/run! {:conn c
                                    :config {:run {:js1/profile "single-player"}}
                                    :llm-adapter :a :llm-config {:max-tokens 16384}
                                    :problem "solve the problem" :max-turns 5})]
              (is (= :completed (:status r)))
              (is (= "the problem is solved directly" (:answer r)))
              (testing "the eval ADVANCED under the scheduled turn's lease"
                (let [turns (journal/branch-turns c (:run-id r) "B1")]
                  (is (= ["eval" "done"] (mapv :tool_name turns)))
                  (is (= ["neutral" "success"] (mapv :category turns))
                      "an authorized eval lands as itself (neutral) and done ships (success); the stale refusal was a :failure")
                  (is (not-any? #(str/includes? (str (:result %))
                                                "Turn authority expired")
                                turns)
                      "no call was stale-refused at dispatch-tool"))
                (is (= ["(+ 1 2)"] (mapv :code @observed))
                    "exactly one recorded eval reached the sandbox seam")
                (is (some? (:token (first @observed)))
                    "the eval carried the scheduled turn lease's interrupt token")
                (is (= 1 @permits)
                    "the recorded effect's intent launched under a lease permit"))
              (testing "the run record shows the scheduled single-branch shape"
                (let [run (runs/get-run c (:run-id r))]
                  (is (= 1 (:beam_width run)))
                  (is (= "completed" (:status run))))))))))))

(deftest js1-workflow-teardown-returns-the-real-outcome
  ;; The masking half of the regression: a JS1 run allocates no live-eval
  ;; session (:repl-session nil), and the old finally's
  ;; (repl/close-session nil) threw on (find-ns nil) — replacing the run's
  ;; actual outcome (here, an honest :exhausted) with an empty-message
  ;; exception from teardown.  close-session is now nil-safe by contract,
  ;; and a JS1 run that never ships RETURNS its exhaustion.
  (is (nil? (repl/close-session nil))
      "close-session is a no-op on nil — the JS1 run has no live-eval session")
  (let [s (repl/new-session)]
    (repl/close-session s)
    (is (nil? (repl/close-session s))
        "and still idempotent on an already-removed name"))
  (with-db [c]
    (let [fake-bind (fn [_conn run-id _config _root]
                      {:binding {:binding/id (str "bind:main:" run-id)
                                 :instance/id "inst:main"
                                 :work-id (str run-id)
                                 :spec {:preset :project/develop}}
                       :provider nil :profile "single-player"})
          fake-evaluate! (fn [_conn _binding _code opts]
                           ((:effect-permit! opts) (fn [] nil))
                           {:value 1})]
      (with-redefs-fn {#'workflow/js1-binding fake-bind
                       #'repl-tools/sandbox-var
                       (fn [var-name]
                         (when (= var-name "evaluate-recorded!")
                           fake-evaluate!))}
        (fn []
          (with-redefs [llm/chat (scripted
                                  (fence {:name "eval"
                                          :args {:code "(+ 1 1)"}}))]
            (let [r (workflow/run! {:conn c
                                    :config {:run {:js1/profile "single-player"}}
                                    :llm-adapter :a :llm-config {:max-tokens 16384}
                                    :problem "never finishes" :max-turns 2})]
              (is (= :exhausted (:status r))
                  "the run's real outcome is returned, not masked by teardown")
              (is (= 2 (count (journal/branch-turns c (:run-id r) "B1"))))
              (is (= "failed" (:status (runs/get-run c (:run-id r))))))))))))

(deftest the-turn-cap-exhausts-through-the-manifest
  (with-db [c]
    (with-redefs [llm/chat (scripted
                            (fence {:name "thesis"
                                    :args {:goal "keep going" :technique "loop"}}))]
      (let [r (workflow/run! {:conn c :config {:run {}}
                              :llm-adapter :a :llm-config {:max-tokens 16384}
                              :problem "never finishes" :max-turns 2})]
        (is (= :exhausted (:status r)))
        (is (some? (:residual r)))
        (is (= 2 (count (journal/branch-turns c (:run-id r) "B1"))))
        (is (= "failed" (:status (runs/get-run c (:run-id r)))))))))

(deftest editing-the-stored-definition-changes-the-next-run
  ;; The acceptance in one test: save a v2 of the loop from the REPL and the
  ;; next run behaves differently, no restart, no code change.
  (with-db [c]
    (with-redefs [llm/chat (scripted
                            (fence {:name "thesis"
                                    :args {:goal "g" :technique "t"}}))]
      ;; Seed v1, then write a v2 that routes every response down the no-call
      ;; path — a visible behavior change made purely by editing stored EDN.
      (workflows/seed! c "loop" "manifests/loop.edn")
      (let [v1 (edn/read-string (:edn (workflows/load-latest c "loop")))
            v2 (assoc-in v1 [:dispatches :parse]
                         '[[:provider-error (fn [d] (not (:ok (:call d))))]
                           [:no-call (fn [d] true)]
                           [:tool (fn [d] false)]])]
        (workflows/save! c "loop" (pr-str v2))
        (let [r (workflow/run! {:conn c :config {:run {}}
                                :llm-adapter :a :llm-config {:max-tokens 16384}
                                :problem "p" :max-turns 1})]
          (is (= :exhausted (:status r)))
          (is (= ["mechanics"]
                 (mapv :category (journal/branch-turns c (:run-id r) "B1")))
              (str "v2 sends every response down the no-call path, which"
                   " journals it as mechanics — v1 dispatches the same"
                   " response as a neutral thesis turn"))
          (is (some #(and (= "loop-workflow" (:kind %))
                          (str/includes? (str (:data %)) "2"))
                    (journal/events-since c (:run-id r) 0 100))
              "the run records which workflow version drove it"))))))

(deftest provider-failure-routes-through-the-manifest
  (with-db [c]
    (let [calls (atom 0)]
      (with-redefs [llm/chat (fn [& _]
                               (if (= 1 (swap! calls inc))
                                 (throw (ex-info "socket reset" {}))
                                 (fence {:name "done"
                                         :args {:answer "recovered and finished"}})))]
        (let [r (workflow/run! {:conn c :config {:run {}}
                                :llm-adapter :a :llm-config {:max-tokens 16384}
                                :problem "recovered and finished" :max-turns 5})]
          (is (= :completed (:status r)))
          (let [turns (journal/branch-turns c (:run-id r) "B1")]
            (is (= "__provider_error__" (:tool_name (first turns)))
                "the failed call is journalled like any turn")))))))

(deftest workflow-effects-are-fully-declared
  (let [def (workflow/read-definition (slurp (clojure.java.io/resource "manifests/loop.edn")))
        fx (wf/workflow-effects def)]
    (is (not-any? :undeclared (vals fx))
        (str "cells with undeclared effects: "
             (keep (fn [[k v]] (when (:undeclared v) k)) fx)))
    (is (:pure (get fx :parse)) "fence parsing is pure")
    (is (contains? (:effects (get fx :infer)) :net))))

(deftest role-ctx-assigns-a-per-role-model
  (let [base {:config {:run {:role-models {:supervisor {:provider "glm" :model "glm-5.3"}
                                           :implementor {:provider :deepseek}}}}
              :llm-adapter :base-adapter
              :llm-config {:provider :openai :model "gpt-4o"}}]
    (testing "a configured role gets its own provider + model + adapter"
      (let [c (workflow/role-ctx base :supervisor)]
        (is (= :glm (get-in c [:llm-config :provider])))
        (is (= "glm-5.3" (get-in c [:llm-config :model])))
        (is (not= :base-adapter (:llm-adapter c)) "the adapter is swapped too")))
    (testing "a role configured with only a provider takes that provider's default model"
      (is (= "deepseek-v4-flash" (get-in (workflow/role-ctx base :implementor)
                                         [:llm-config :model]))))
    (testing "an unconfigured role keeps the run's default model and adapter"
      (let [c (workflow/role-ctx base :reviewer)]
        (is (= :openai (get-in c [:llm-config :provider])))
        (is (= :base-adapter (:llm-adapter c)))))))

(deftest catalog-lists-every-workflow-with-a-description
  ;; self-healing: the supervisor can only switch to / tune a workflow it knows
  ;; exists. The catalog is that discoverable menu.
  (let [conn (db/open! ":memory:")
        by-name (into {} (map (juxt :name identity)) (workflow/catalog conn))]
    (is (contains? by-name "feature"))
    (is (contains? by-name "team"))
    (is (contains? by-name "decompose"))
    (is (contains? by-name "loop"))
    (is (str/includes? (:description (by-name "decompose")) "Decompose")
        "each carries its :description")
    (is (str/includes? (workflow/render-catalog conn) "decompose")
        "and renders as a text menu for the supervisor")))
