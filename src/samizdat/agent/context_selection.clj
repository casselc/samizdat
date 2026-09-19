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

  THE DECISION COMES FROM OUTSIDE. The harness does not score anything and
  does not talk to a judge. It reads a decision that was computed elsewhere —
  ids, costs and an outcome — and materialises it. Scores rank; they never
  grant. Availability, permission and the budget were the selector's inputs and
  are re-checked here against what this run can actually see, because a
  decision computed against another catalogue must not smuggle in material this
  run's role may not have: `materialize` drops any id the run cannot resolve
  and records the drop.

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
            [samizdat.agent.skills :as skills]
            [samizdat.lexicon :as lexicon]))

(def seam-version
  "The contract this implements. Bump with the decision shape, never silently."
  "samizdat-context-selection/1")

(defn- entry
  "One resolved selection: the id, its kind, and the text to inject (nil when
  the material is already in the prompt or cannot be resolved here)."
  [dirs {:keys [id kind name] :as sel}]
  (case (some-> kind keyword)
    :skill (if-let [body (skills/load-skill dirs (or name id))]
             {:id id :kind :skill :status :injected :text body}
             {:id id :kind :skill :status :unresolved})
    :tool {:id id :kind :tool :status :already-present}
    :manual (if-let [line (:summary sel)]
              {:id id :kind :manual :status :injected
               :text (str (or name id) " — " line)}
              {:id id :kind :manual :status :unresolved})
    {:id id :kind (or kind :unknown) :status :unresolved}))

(defn materialize
  "Resolve a decision's selections against what THIS run can see.

  `decision`: {:seam-version .. :outcome .. :selected [{:id :kind :name :summary
  :cost-load} ..]}. Returns {:entries [..] :injected [..] :already-present [..]
  :unresolved [..] :injected-cost-load n}, where the cost is summed over what was
  actually injected — a dropped or already-present id contributes nothing, so the
  reported cost is what this prompt really carries."
  ([decision] (materialize skills/default-dirs decision))
  ([dirs decision]
   (let [entries (mapv #(merge (select-keys % [:cost-load])
                               (entry dirs %))
                       (:selected decision))
         by (group-by :status entries)]
     {:entries entries
      :injected (vec (:injected by))
      :already-present (vec (:already-present by))
      :unresolved (vec (:unresolved by))
      :injected-cost-load (reduce + 0 (keep :cost-load (:injected by)))})))

(defn render-block
  "The injected context block, or nil when nothing was injected. It names what
  is already loaded so the worker does not spend a turn re-loading it."
  [{:keys [injected already-present]}]
  (when (seq injected)
    (str "Context selected for this task and already loaded — do not load it again:\n"
         (str/join "\n" (for [{:keys [id]} injected] (str "- " id)))
         (when (seq already-present)
           (str "\nAlready in your prompt: "
                (str/join ", " (map :id already-present))))
         "\n\n"
         (str/join "\n\n" (keep :text injected)))))

(defn already-loaded?
  "Was `name` injected up front? Lets a `skill load` of the same material be
  recorded as a repeat rather than counted as new context."
  [{:keys [injected]} name]
  (boolean (some #(or (= name (:id %)) (= name (:name %))) injected)))

(defn skills-block
  "What the system prompt's `{{skills}}` is rendered with.

  With no decision this is `skills/render-catalog`, byte for byte — the default
  path is unchanged. With a decision it is the catalogue FOLLOWED BY the
  injected block: the catalogue stays because the worker may still load
  anything it wants on demand, and proactive loading is an addition, not a
  replacement."
  ([] (skills-block nil))
  ([decision] (skills-block skills/default-dirs decision))
  ([dirs decision]
   (let [catalog (skills/render-catalog dirs)]
     (if-not decision
       catalog
       (let [m (materialize dirs decision)
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
   "samizdat.context.injected_cost_load" (:injected-cost-load materialized)})
