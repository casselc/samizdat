;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.decompose
  "Decompose-on-stuck (karamazov-ioo.15). A task is attempted DIRECTLY first;
  only when it cannot pass its contract after a bounded number of tries is it
  split. 'Too big' is discovered as 'can't pass its tests', not estimated up
  front — the evidence-based approach code-graph-agent uses, and the one that
  turns 'a weak model can't do this' into 'it can do these three small pieces'.

  When a unit is stuck, an ARCHITECT call chooses:
    - :decompose      the unit does several distinct things — split into 2..N
                      single-responsibility sub-units (each becomes its own
                      task with a signature + description; its implementor writes
                      its own tests, TDD). The parent then becomes a thin
                      assembly that composes them, verified against its OWN,
                      preserved tests.
    - :fresh-approach the unit does one thing but the implementation keeps
                      missing — a hint to try a different angle, no split.

  Pure here — the architect prompt and the decision parsing; the orchestration
  (attempt, recurse, assemble, depth cap) lives in cells/decompose.clj. Same
  split as planner.clj vs cells/team.clj."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def max-depth
  "How deep the split recursion may go before a stuck unit is a hard failure
  rather than split again. Kept shallow: each level multiplies sub-agents, and a
  unit that still won't pass its tests three levels down is not a size problem."
  3)

(def default-max-parts 4)

(defn architect-prompt
  "Ask the architect to diagnose a stuck unit and choose to split it or hint a
  fresh approach. Fed the EVIDENCE — the contract, the tests, the last attempt,
  the failure, how many tries and how deep — so the choice is grounded in why it
  failed, not a guess about its size."
  [{:keys [problem contract tests]}
   {:keys [attempts last-answer last-failure depth]}]
  (str
   "You are an architect diagnosing a stuck implementation. A unit of work has "
   "been attempted " (or attempts "several") " times and still cannot pass its "
   "tests. Decide how to recover.\n\n"
   "## The unit\n\n" problem "\n"
   (when (seq (str contract)) (str "\n### Its contract\n" contract "\n"))
   (when (seq (str tests)) (str "\n### Its tests (the spec it must satisfy)\n" tests "\n"))
   (when (seq (str last-answer)) (str "\n### The most recent attempt\n" last-answer "\n"))
   (when (seq (str last-failure)) (str "\n### Why it failed\n" last-failure "\n"))
   "\n## Your choice\n\n"
   "Pick ONE:\n\n"
   "1. DECOMPOSE — the unit is doing MORE THAN ONE distinct thing. List its "
   "responsibilities; if there is more than one, split it into 2 to "
   default-max-parts " single-responsibility sub-units. Each sub-unit is small "
   "(a function or two, testable on its own) and does exactly one thing. The "
   "parent will become a thin piece that composes them, so the sub-units must "
   "cover the whole job between them.\n"
   "2. FRESH_APPROACH — the unit does ONE thing, but the attempts keep taking a "
   "wrong strategy. Give a one-paragraph hint at a different angle; the next "
   "attempt sees it and starts over. Use this when splitting would not actually "
   "reduce the complexity.\n\n"
   (when (>= (or depth 0) (dec max-depth))
     (str "This is the LAST recovery round — the depth budget is nearly spent, "
          "so further splitting will be rejected. You MUST choose "
          "FRESH_APPROACH.\n\n"))
   "Return ONLY this JSON, nothing else:\n"
   "{\"decision\": \"decompose\" | \"fresh_approach\",\n"
   " \"reason\": \"one sentence\",\n"
   " \"subtasks\": [ {\"name\": \"a-short-kebab-name\",\n"
   "                 \"description\": \"one paragraph: what this sub-unit must do\"} ],\n"
   " \"hint\": \"one paragraph (only for fresh_approach)\"}"))

(defn- normalize-subtask [m]
  (let [name (some-> (or (get m "name") (get m :name)) str str/trim not-empty)
        desc (some-> (or (get m "description") (get m :description)) str str/trim not-empty)]
    (when (and name desc) {:name name :description desc})))

(defn child-node
  "A sub-unit node from the parent and an architect subtask spec. Its id encodes
  lineage; its problem is the subtask's one-paragraph contract."
  [parent {:keys [name description]}]
  {:id (str (:id parent) "/" name)
   :name name
   :problem description
   :parent (:id parent)})

(defn solve
  "Recursive decompose-on-stuck for one node. Pure control flow over injected
  ops, so the recursion is testable without a model or a worker; cells/decompose
  provides the real ops.

  ops:
    :attempt  (fn [node] -> {:passed? bool :answer .. :failure ..}) — build the
              unit directly (worker loop) and verify it against its tests.
    :recover  (fn [node evidence] -> decision|nil) — the architect call on a
              stuck unit (evidence carries :last-answer :last-failure :depth).
    :fan      (fn [thunks] -> results) — run sub-unit solves (parallel or not).

  Returns {:status :landed|:failed :answer .. :node .. :children [..]}. A landed
  node is a stable subassembly: it passed its own tests, so a parent that
  composes it never re-litigates it."
  [node depth {:keys [attempt recover fan] :as ops}]
  (let [r (attempt node)]
    (cond
      (:passed? r)
      {:status :landed :answer (:answer r) :node node}

      ;; No split past the budget — a unit still failing this deep is not a size
      ;; problem, so it is a hard failure surfaced upward (no silent give-up).
      (>= depth max-depth)
      {:status :failed :reason "depth exhausted" :node node :answer (:answer r)}

      :else
      (let [decision (recover node {:last-answer (:answer r) :last-failure (:failure r)
                                    :depth depth})]
        (case (:kind decision)
          :fresh-approach
          (let [r2 (attempt (assoc node :hint (:hint decision)))]
            (if (:passed? r2)
              {:status :landed :answer (:answer r2) :node node}
              {:status :failed :reason "fresh approach did not land"
               :node node :answer (:answer r2)}))

          :decompose
          (let [children (:subtasks decision)
                results (fan (mapv (fn [c]
                                     #(solve (child-node node c) (inc depth) ops))
                                   children))]
            (if-not (every? #(= :landed (:status %)) results)
              {:status :failed :reason "a sub-unit did not land"
               :node node :children results}
              ;; Assemble: the sub-units landed against their own tests; re-attempt
              ;; the parent as the thin composition that ties them together, judged
              ;; only against the parent's OWN, preserved tests.
              (let [asm (attempt (assoc node :assembly true
                                        :child-answers (mapv :answer results)))]
                (if (:passed? asm)
                  {:status :landed :answer (:answer asm) :node node :children results}
                  {:status :failed :reason "assembly did not land"
                   :node node :children results}))))

          ;; no usable decision — fail the unit rather than guess
          {:status :failed :reason "no recovery" :node node :answer (:answer r)})))))

(defn parse-decision
  "The architect's decision from its reply. Returns
    {:kind :decompose :reason .. :subtasks [{:name :description} ...]}
    {:kind :fresh-approach :reason .. :hint ..}
  or nil when the reply carries no usable JSON decision (the caller then treats
  a nil as 'no recovery' and fails the unit — better than guessing). `depth` >=
  max-depth-1 forces :fresh-approach even if the model asked to split, so the
  depth budget is honoured whatever the model returns."
  ([reply] (parse-decision reply 0))
  ([reply depth]
   (let [m (try (json/read-str (or (re-find #"(?s)\{.*\}" (str reply)) ""))
                (catch Throwable _ nil))]
     (when (map? m)
       (let [decision (some-> (get m "decision") str str/lower-case str/trim)
             reason (some-> (get m "reason") str)
             subtasks (->> (get m "subtasks") (keep normalize-subtask) vec)
             hint (some-> (get m "hint") str str/trim not-empty)
             ;; the depth guard: at the budget's edge a split is not allowed, so
             ;; a decompose degrades to a fresh approach.
             want-split? (and (= decision "decompose")
                              (seq subtasks)
                              (< depth (dec max-depth)))]
         (cond
           want-split? {:kind :decompose :reason reason :subtasks subtasks}
           (or hint (= decision "fresh_approach"))
           {:kind :fresh-approach :reason reason
            :hint (or hint "Try a different implementation strategy.")}
           ;; a decompose we cannot honour (too deep, or no subtasks) still means
           ;; "one thing, wrong strategy" — degrade to a fresh approach.
           (= decision "decompose") {:kind :fresh-approach :reason reason
                                     :hint "Try a different implementation strategy."}
           :else nil))))))
