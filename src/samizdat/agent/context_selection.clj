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

(ns samizdat.agent.context-selection
  "Proactive context loading: the harness side of `samizdat-context-selection/1`.

  WHAT THIS CHANGES, AND WHAT IT DOES NOT. Today the system prompt carries
  `skills/render-catalog` — names and one-line descriptions — and the worker
  pulls a body with `skill load`. That stays exactly as it is and is what runs
  when nothing is configured: `skills-block` returns the catalogue unchanged
  unless a run supplies a decision. This namespace adds the option of ALSO
  injecting selected material up front, so an experiment can ask whether
  proactive loading helps.

  THE DECISION COMES FROM OUTSIDE, AND IS NOT TRUSTED. The harness does not
  score anything and does not talk to a judge. It reads a decision computed
  elsewhere and materialises it — but a decision is a list of IDS AND NOTHING
  ELSE that this code believes. Everything the model would read, and everything
  the budget is spent on, is resolved HERE:

    * the id is resolved against this run's own catalogue — a skill that this
      project actually has, a tool on THIS ROLE's surface, a manual entry in
      this image's manual. An id that resolves to nothing is dropped;
    * the CONTENT comes from the local resource, never from the decision. A
      decision carrying its own prose would be an outside party writing into
      the model's prompt;
    * the COST is recomputed from the resolved content. A decision that
      under-reported a cost could otherwise spend a budget it was not given;
    * the BUDGET is enforced here, in id order, and what does not fit is
      dropped and recorded.

  Scores rank; they never grant. A tool is `already-present` only when the
  role's surface actually carries it — claiming otherwise would let a decision
  assert that material is in the prompt when it is not.

  IDENTITY IS THE CANDIDATE ID. A decision names ids, never display names; two
  catalogue entries may share a name (three do in the evaluation set) and
  keying on it lets one shadow the other.

  KINDS. A decision may select three kinds of material, and each is
  materialised differently:

    :skill   the skill's full body, what `skill load <name>` would return;
    :tool    the tool's documentation entry, which the prompt already carries
             for every tool on the role's surface — so a selected tool is
             ALREADY PRESENT and is recorded as `already-present`, never
             injected twice;
    :manual  the operator-manual line for a var (name, group, summary), which
             the prompt does not otherwise carry.

  DUPLICATE LOADS. Injecting a skill body does not disable `skill load`: the
  tool still answers, and the worker may ask for something it already has. The
  injected block therefore NAMES what is already loaded so the worker need not
  ask, and `already-loaded?` lets a caller record a repeat load as a repeat
  rather than as new context. Context is counted twice on purpose — once as
  injected, once as loaded — because both are tokens the run paid for."
  (:require [clojure.string :as str]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.skills :as skills]
            [samizdat.manual :as manual]
            [samizdat.prompt :as prompt]))

(defn- known-tools
  "Every tool name this build has, through `roles/all-tool-names` — the same late,
  defensive resolve roles itself uses, because prompt assembly can run before the
  tool registry is loaded. nil there means UNKNOWN, and unknown withholds: a tool
  id resolves to nothing rather than being waved through."
  []
  (roles/all-tool-names))

(def seam-version
  "The contract this implements. Bump with the decision shape, never silently."
  "samizdat-context-selection/1")

(def ^:private cost-divisor
  "cost_load is `ceil(len(text)/4)` — MECHANISM, not policy, and deliberately not
  a runtime knob. It is the estimator the evaluation's catalog freeze records, and
  a budget only means anything if the harness measures in the same unit the
  selector measured in. A project that changed this number would silently be
  spending a different currency from the one its decision was computed in."
  4)

(defn cost-of
  "The cost_load of `text` under the frozen estimator."
  [text]
  (if (str/blank? (str text))
    0
    (long (Math/ceil (/ (double (count (str text))) (double cost-divisor))))))

(defn- strip-kind
  "`skill:repl-workflow` -> `repl-workflow`. The id's prefix is its kind, and
  the remainder is the local name to resolve — the decision's own `:name`, if
  it carries one, is not consulted."
  [id]
  (let [s (str id)
        i (.indexOf s ":")]
    (if (neg? i) s (subs s (inc i)))))

(defn- id-kind
  "The kind an id declares, from its prefix."
  [id]
  (let [s (str id)
        i (.indexOf s ":")]
    (when (pos? i) (keyword (subs s 0 i)))))

(defn- entry
  "Resolve ONE id against what this run can see. `surface` is the role's tool
  surface (a set, or :all); `dirs` the project's skill roots.

  Returns {:id :name :kind :status :text :cost-load}, where the text and the
  cost come from the local resource and never from the decision."
  [dirs surface id]
  (let [nm (strip-kind id)
        base {:id id :name nm :kind (or (id-kind id) :unknown)}]
    (case (id-kind id)
      :skill (if-let [body (skills/load-skill dirs nm)]
               (assoc base :status :injected :text body :cost-load (cost-of body))
               (assoc base :status :unresolved :reason :no-such-skill))
      :tool (cond
              (not (contains? (set (map str (known-tools))) nm))
              (assoc base :status :unresolved :reason :no-such-tool)
              (not (or (= :all surface) (contains? (set (map str surface)) nm)))
              (assoc base :status :unresolved :reason :not-on-role-surface)
              :else (assoc base :status :already-present :cost-load 0))
      :manual (if-let [m (manual/find-entry nm)]
                (let [line (str (:name m) " — " (:summary m))]
                  (assoc base :status :injected :text line :cost-load (cost-of line)))
                (assoc base :status :unresolved :reason :no-such-manual-entry))
      (assoc base :status :unresolved :reason :unknown-kind))))

(defn materialize
  "Resolve a decision's ids against what THIS run can see, and spend the budget.

  `decision`: {:seam-version .. :outcome .. :selected [{:id ..} ..] :budget n}.
  Only `:id` is read from each selection; a `:name`, `:summary` or `:cost-load`
  the decision carries is ignored, because the content and the cost are the
  harness's to determine.

  Returns {:entries :injected :already-present :unresolved :dropped-over-budget
  :injected-cost-load :budget}. Injection walks the resolved entries in ID
  ORDER — the same order-independent rule the selector uses — and stops adding
  when the next body would exceed the budget, recording what it dropped."
  ([decision] (materialize skills/default-dirs :all decision))
  ([dirs surface decision]
   (let [budget (:budget decision)
         resolved (mapv #(entry dirs surface (:id %)) (:selected decision))
         by (group-by :status resolved)
         [kept over]
         (reduce (fn [[kept over used] e]
                   (let [c (long (or (:cost-load e) 0))]
                     (if (or (nil? budget) (<= (+ used c) (long budget)))
                       [(conj kept e) over (+ used c)]
                       [kept (conj over (assoc e :status :dropped-over-budget)) used])))
                 [[] [] 0]
                 (sort-by :id (:injected by)))]
     {:entries (vec (concat kept over (:already-present by) (:unresolved by)))
      :injected (vec kept)
      :already-present (vec (:already-present by))
      :unresolved (vec (:unresolved by))
      :dropped-over-budget (vec over)
      :budget budget
      :injected-cost-load (reduce + 0 (keep :cost-load kept))})))

(defn render-block
  "The injected context block, or nil when nothing was injected.

  The prose lives in resources/prompts/context-selected.md, not here: every
  sentence the model reads has to be editable without a rebuild, and base_test
  enforces it. This function supplies the data — which ids were injected, which
  were already in the prompt, and the bodies — and the template says it."
  [{:keys [injected already-present]}]
  (when (seq injected)
    (prompt/render "context-selected"
                   {:injected (mapv :id injected)
                    :already-present (mapv :id already-present)
                    :bodies (str/join "\n\n" (keep :text injected))})))

(defn already-loaded?
  "Was `name` injected up front? Answers for the id or the display name, because
  the worker types the name at `skill load` while the decision names ids."
  [{:keys [injected]} name]
  (boolean (some #(or (= (str name) (str (:id %))) (= (str name) (str (:name %)))) injected)))

;; --- load accounting ---------------------------------------------------------
;;
;; The point of injecting context is to stop the worker paying for it again, so
;; the experiment's context measure is only honest if the repeats are actually
;; counted. A helper that *could* recognise a repeat proves nothing on its own:
;; the tool has to call it. `record-load!` is what the `skill` tool calls on
;; every successful load, and this ledger is what the run reports.

(defonce ^:private ledger (atom {}))

(defn reset-accounting!
  "Start a run's accounting. `materialized` is what the prompt was built with,
  so a later load can be classified against it."
  [run-id materialized]
  (swap! ledger assoc run-id {:materialized materialized :loads []})
  nil)

(defn record-load!
  "Record one completed `skill load`. Returns the recorded entry.

  A load of material that was injected up front is a REPEAT: the tokens are
  paid a second time, so its cost is counted again and flagged, rather than
  being quietly dropped because the material was 'already there'."
  [run-id name body]
  (let [m (get-in @ledger [run-id :materialized])
        repeat? (and m (already-loaded? m name))
        e {:name (str name) :cost-load (cost-of body) :repeat repeat?}]
    (swap! ledger update run-id
           (fn [r] (update (or r {:materialized m :loads []}) :loads conj e)))
    e))

(defn accounting
  "What this run actually paid for context: what the prompt carried up front,
  what the worker loaded afterwards, and how much of that was a repeat of
  material it had already been given.

  `context-consumed-cost-load` is injected + loaded, repeats included, because
  both are tokens the run paid."
  [run-id]
  (let [{:keys [materialized loads]} (get @ledger run-id)
        injected (long (or (:injected-cost-load materialized) 0))
        loaded (reduce + 0 (map :cost-load loads))
        repeats (filter :repeat loads)]
    {:run-id run-id
     :injected-cost-load injected
     :loaded-cost-load loaded
     :repeat-load-cost-load (reduce + 0 (map :cost-load repeats))
     :n-loads (count loads)
     :n-repeat-loads (count repeats)
     :loads (vec loads)
     :context-consumed-cost-load (+ injected loaded)}))

(defn skills-block
  "What the system prompt's `{{skills}}` is rendered with.

  With no decision this is `skills/render-catalog`, byte for byte — the default
  path is unchanged. With a decision it is the catalogue FOLLOWED BY the
  injected block: the catalogue stays because the worker may still load
  anything it wants on demand, and proactive loading is an addition, not a
  replacement."
  ([] (skills-block nil))
  ([decision] (skills-block skills/default-dirs :all decision))
  ([dirs surface decision]
   (let [catalog (skills/render-catalog dirs)]
     (if-not decision
       catalog
       (let [m (materialize dirs surface decision)
             block (render-block m)]
         (if block (str catalog "\n\n" block) catalog))))))

(defn telemetry-attrs
  "Metadata only, matching the Python seam's export: versions, outcome, counts
  and costs. No candidate text, no task text, no scores."
  [decision materialized]
  {"samizdat.context.seam_version" (or (:seam-version decision) seam-version)
   "samizdat.context.outcome" (some-> (:outcome decision) clojure.core/name)
   "samizdat.context.policy" (some-> (:policy decision) clojure.core/name)
   "samizdat.context.n_selected" (count (:selected decision))
   "samizdat.context.n_injected" (count (:injected materialized))
   "samizdat.context.n_already_present" (count (:already-present materialized))
   "samizdat.context.n_unresolved" (count (:unresolved materialized))
   "samizdat.context.n_dropped_over_budget" (count (:dropped-over-budget materialized))
   "samizdat.context.injected_cost_load" (:injected-cost-load materialized)
   "samizdat.context.budget_cost_load" (:budget materialized)})
