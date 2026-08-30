;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.surface-test
  "M4 attempt-2 hardening gate, items B, C, D and K.

  These assert over the ACTUAL ASSEMBLED model context — the system message a
  bounded branch opens with and the per-turn block the loop appends — not over
  the prompt resources in isolation. Attempt 1's violation was introduced by
  ASSEMBLY: every resource was fine on its own, and the bounded branch was
  still told to run `task create`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.observation :as observation]
            [samizdat.agent.state :as state]
            [samizdat.agent.surface :as surface]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.base :as base]
            [samizdat.prompt :as prompt]))

(def develop-binding
  "A durable bounded binding value, built by hand so this namespace never
  loads SCI: the surface is derived from the persisted ContextSpec, which is
  exactly the point — nothing here needs a live evaluator."
  {:spec {:context-spec
          {:context/profile :agent/project-develop
           :context/root "/tmp/target"
           :context/capabilities [:project/read :project/list :project/search
                                  :project/stat :project/edit]}}})

(def read-binding
  (assoc-in develop-binding [:spec :context-spec :context/capabilities]
            [:project/read :project/stat]))

(def ordinary-tool-universe
  "Every tool the ordinary lane dispatches, plus the multiword task forms. The
  audit asks: does this text tell the model to call any of these?"
  (vec (tools/tool-names)))

;; ═══════════════════════════════════════════════════════════════════════════
;; B. The assembled bounded context advertises only real authority.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest bounded-surface-is-derived-from-the-binding
  (testing "a develop binding's surface is its own capabilities, never a constant"
    (let [s (surface/of-binding develop-binding)]
      (is (= ["eval" "doc" "complete" "done"] (:top-level s)))
      (is (= ["project/read" "project/list" "project/search"
              "project/stat" "project/edit"]
             (:operation-names s)))))

  (testing "a narrowed binding narrows every derived claim"
    (let [s (surface/of-binding read-binding)]
      (is (= ["project/read" "project/stat"] (:operation-names s)))
      (is (not (contains? (set (:operation-names s)) "project/edit")))))

  (testing "ordinary branches keep the whole dispatch catalog"
    (let [s (surface/ordinary ordinary-tool-universe)]
      (is (surface/callable? s "shell"))
      (is (false? (:bounded? s))))))

(deftest bounded-context-never-names-an-unavailable-tool
  (let [s (surface/of-binding develop-binding)]
    (testing "the tools attempt 1 was wrongly told about are refused by the audit"
      (doseq [forbidden ["task create" "task claim" "shell" "read_file"
                         "edit_file" "write_file"]]
        (is (seq (surface/unavailable-mentions
                  s ordinary-tool-universe
                  (str "You should use `" forbidden "` now.")))
            (str "audit must catch " (pr-str forbidden)))))

    (testing "task-none.md — the exact resource attempt 1 injected — is refused"
      (let [bad (surface/unavailable-mentions s ordinary-tool-universe
                                              (prompt/prompt "task-none"))]
        (is (some #{"task"} bad)
            "task-none.md tells the model to run `task create`; a bounded
             surface has no task tool, so assembling it is the F-1 bug")))

    (testing "the bounded lane's own surface is not flagged against itself"
      (is (empty? (surface/unavailable-mentions
                   s ordinary-tool-universe
                   "Call `eval` with code, then `done`.")))
      (is (empty? (surface/unavailable-mentions
                   s ordinary-tool-universe
                   "Inside eval: (project/read \"src/a.clj\")"))))))

(deftest gates-cannot-steer-a-branch-to-a-tool-it-lacks
  (testing "a gate naming a tool outside the surface is never eligible"
    (let [bounded (surface/of-binding develop-binding)
          ;; A real branch shape, and one late enough in its budget that the
          ;; last-call / wind-down family of gates is genuinely eligible —
          ;; those are the gates that name give_up and done.
          branch (assoc (state/new-branch {:id "B1" :problem "p"})
                        :current-turn 58)
          ctx {:surface bounded :branch branch :max-turns 60
               :branch-count 1}
          named (->> (arbiter/eligible ctx) (keep :tool) set)]
      (is (every? #(surface/callable? bounded %) named)
          (str "a bounded branch was offered gates naming " (pr-str named)
               " but may only call " (pr-str (:top-level bounded))))
      (is (not (contains? named "give_up")))
      (is (not (contains? named "branch_theses")))))

  (testing "the gate table really does name tools a bounded branch lacks"
    ;; Asserted against the TABLE, not against which gates happen to fire at
    ;; some turn: the invariant is about what could ever be offered, and a
    ;; control that depends on one gate's :when predicate proves nothing when
    ;; that predicate changes.
    (let [bounded (surface/of-binding develop-binding)
          all-tools (->> (gates/gates) (keep :tool) set)
          outside (remove #(surface/callable? bounded %) all-tools)]
      (is (seq outside)
          "if no gate named an unavailable tool there would be nothing to fix")
      (is (some #{"give_up"} outside))))

  (testing "no surface in ctx means no filtering — existing callers unchanged"
    (let [branch (assoc (state/new-branch {:id "B1" :problem "p"})
                        :current-turn 58)
          ctx {:branch branch :max-turns 60 :branch-count 1}]
      (is (= (->> (arbiter/eligible ctx) (mapv :gate))
             (->> (arbiter/eligible
                   (assoc ctx :surface (surface/ordinary ordinary-tool-universe)))
                  (mapv :gate)))))))


;; ═══════════════════════════════════════════════════════════════════════════
;; The CURRENT-UPSTREAM seams. Everything above was written against the tool
;; surface as it stood; upstream has since added tools (plan, patch,
;; websearch, intervene), a role catalogue, and a REPL-session contract with
;; teeth. Each is a fresh chance to tell a bounded branch about something it
;; cannot call, or to withhold something it can.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest the-new-upstream-tools-are-audited-like-every-other
  ;; The audit universe is (tools/tool-names), so a tool added upstream joins
  ;; it without anyone remembering to. This asserts that it actually did —
  ;; a universe that silently stopped covering the new tools would make every
  ;; assertion above vacuous.
  (let [s (surface/of-binding develop-binding)]
    (doseq [added ["plan" "patch" "websearch" "intervene"]]
      (is (some #{added} ordinary-tool-universe)
          (str added " must be in the audited universe"))
      (is (not (surface/callable? s added))
          (str "a bounded branch cannot call " added))
      (is (seq (surface/unavailable-mentions
                s ordinary-tool-universe
                (str "Use `" added "` to record what you intend to change.")))
          (str "audit must catch " (pr-str added))))))

(deftest the-repl-session-contract-does-not-withhold-the-bounded-lane
  ;; THE CONVERGENCE BREAK THIS EXISTS FOR. Upstream refuses `eval` until a
  ;; branch has named the files it intends to change (phases.edn
  ;; :repl-needs-a-plan). That is a good rule in a lane with thirty tools,
  ;; where a branch can plan and then explore freely. In the bounded lane
  ;; `eval` IS the surface — every observation, mutation and execution goes
  ;; through it — and there is no `plan` tool to lift the refusal with, so
  ;; the rule would withhold the agent permanently and unliftably.
  (let [unplanned (state/new-branch {:id "B1" :problem "p"})]
    (testing "an ordinary unplanned branch is still refused — the rule stands"
      (let [r (base/phase-refusal {:branch unplanned :tool-name "eval"})]
        (is (some? r))
        (is (:policy-refusal? r))))

    (testing "a bounded branch is not, by binding or by profile"
      (is (nil? (base/phase-refusal {:branch unplanned :tool-name "eval"
                                     :evaluator/binding develop-binding})))
      (is (nil? (base/phase-refusal {:branch unplanned :tool-name "eval"
                                     :evaluator/profile :agent/project-develop}))))

    (testing "and nothing else the bounded lane can call is withheld from it"
      (doseq [tool ["doc" "complete" "done"]]
        (is (nil? (base/phase-refusal {:branch unplanned :tool-name tool
                                       :evaluator/binding develop-binding}))
            (str tool " must reach the bounded lane"))))))

(deftest the-oversight-stream-does-not-run-over-a-bounded-run
  ;; Upstream opens a parallel supervisor stream over a running run. It is an
  ;; ORDINARY role with an ordinary tool surface; a bounded run has neither,
  ;; so every call it makes is refused — 63 wasted turn slots in the JS2
  ;; convergence smoke, on a bounded run that had already completed in 27.
  ;;
  ;; Asserted through the same predicate the guard uses, because the stream
  ;; itself needs a live conn, run and adapter to start and this namespace
  ;; loads without any of them.
  (testing "a bounded ctx is recognised by binding and by profile"
    (is (base/bounded? {:evaluator/binding develop-binding}))
    (is (base/bounded? {:evaluator/profile :agent/project-develop}))
    (is (some? (base/bounded-binding {:evaluator/binding develop-binding}))))
  (testing "and an ordinary one is not"
    (is (not (base/bounded? {})))
    (is (nil? (base/bounded-binding {}))))
  (testing "the supervisor's own tools are outside a bounded surface"
    (let [s (surface/of-binding develop-binding)]
      (doseq [tool ["shell" "fetch_turn" "introspect" "cells" "intervene"]]
        (is (not (surface/callable? s tool))
            (str "a bounded branch cannot call " tool
                 " — which is why a supervisor over a bounded run can do nothing"))))))

(deftest a-bounded-branch-has-no-role-so-the-role-surface-does-not-narrow-it
  ;; Upstream's role surface refuses any tool outside the role's catalogue.
  ;; A bounded branch carries no :role — roles are the ordinary lane's
  ;; division of labour — and a branch with no role is unrestricted, which is
  ;; what keeps the bounded surface derived from its ContextSpec alone.
  (let [branch (state/new-branch {:id "B1" :problem "p"})]
    (is (nil? (:role branch)))
    (doseq [tool ["eval" "doc" "complete" "done"]]
      (is (nil? (base/phase-refusal {:branch branch :tool-name tool
                                     :evaluator/binding develop-binding}))))))

;; C and D — the trusted orientation's own assertions — live in
;; samizdat.evaluator-test, because rendering it requires the pinned bounded
;; runtime and this namespace must stay loadable with no SCI on the classpath.

;; ═══════════════════════════════════════════════════════════════════════════
;; K. Repeated unchanged observation, derived from receipts.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- r [op args result] {:op op :args args :result result :phase :done})

(deftest repeated-unchanged-observation-is-receipt-derived
  (testing "identical observation with an identical result counts"
    (let [rs (repeat 4 (r :project/read ["a.clj"] "SAME"))
          [hit] (observation/repeated-unchanged rs 4)]
      (is (= 4 (:count hit)))
      (is (= "a.clj" (:path hit)))))

  (testing "a changed result is not a repeat"
    (is (empty? (observation/repeated-unchanged
                 [(r :project/read ["a.clj"] "ONE")
                  (r :project/read ["a.clj"] "TWO")
                  (r :project/read ["a.clj"] "THREE")]
                 2))))

  (testing "an intervening mutation of that path resets the count"
    (is (empty? (observation/repeated-unchanged
                 [(r :project/read ["a.clj"] "SAME")
                  (r :project/read ["a.clj"] "SAME")
                  (r :project/edit ["a.clj" "sha256:x" "new"] {:path "a.clj"})
                  (r :project/read ["a.clj"] "SAME")]
                 3))
        "a read after a write to the same file is legitimate"))

  (testing "a mutation of a DIFFERENT path resets too — JS2 §3A"
    ;; M4 reset only signatures naming the mutated path. That is too
    ;; optimistic in the direction that matters: a write BENEATH a listed or
    ;; searched directory invalidates that list or search while naming a
    ;; different path, and no receipt stream can say which writes those are
    ;; without filesystem dependency tracking, which JS2 deliberately does not
    ;; build. Any successful mutation now clears everything. The cost is a
    ;; suppressed warning; a feedback signal is allowed to be wrong in that
    ;; direction and in no other.
    (is (empty? (observation/repeated-unchanged
                 [(r :project/read ["a.clj"] "SAME")
                  (r :project/read ["a.clj"] "SAME")
                  (r :project/edit ["b.clj" "sha256:x" "new"] {:path "b.clj"})
                  (r :project/read ["a.clj"] "SAME")]
                 3))))

  (testing "different arguments are different observations"
    (is (empty? (observation/repeated-unchanged
                 [(r :project/read ["a.clj"] "SAME")
                  (r :project/read ["b.clj"] "SAME")]
                 2))))

  (testing "mutations are never counted as re-observation"
    (is (empty? (observation/repeated-unchanged
                 (repeat 5 (r :project/edit ["a.clj" "sha256:x" "n"] {}))
                 2))))

  (testing "an errored receipt observed nothing"
    (is (empty? (observation/repeated-unchanged
                 (repeat 5 (assoc (r :project/read ["a.clj"] "SAME")
                                  :phase :error))
                 2))))

  (testing "the finding carries evidence and renders its coordinate"
    (let [f (observation/finding (repeat 5 (r :project/read ["util.clj"] "S"))
                                 {:threshold 4
                                  :detail "you already observed {{coordinate}}"})]
      (is (= :repeated-unchanged-observation (:kind f)))
      (is (str/includes? (:detail f) "util.clj"))
      (is (= 5 (get-in f [:evidence :repeated-observations])))))

  (testing "below threshold there is no finding at all"
    (is (nil? (observation/finding (repeat 2 (r :project/read ["a"] "S"))
                                   {:threshold 4 :detail "x"})))))
