;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.planner
  "Splitting one feature into independent sub-tasks so a team can fan out over
  them. Pure here — build the split prompt, parse the model's part list — so it
  is testable without a provider; the LLM call lives in the :team/plan cell.

  This is the flat, one-level decomposition. Recursive decomposition with
  per-part contracts (karamazov-ioo.15) builds on the same parse."
  (:require [clojure.string :as str]))

(def default-max-parts
  "Cap on the fan-out width when the planner splits a task itself. Config
  :run :max-subtasks overrides. Kept small: parallel workers share one
  workspace, and a wide split multiplies collision risk faster than it buys
  speed."
  4)

(defn plan-prompt
  "Ask the model to split `problem` into at most `max-parts` independent parts,
  one per line as a bullet list and nothing else — the shape parse-plan reads."
  [problem max-parts]
  (str "You are splitting a coding task into independent parts so several "
       "agents can work them in parallel.\n\n"
       "## Task\n\n" problem "\n\n"
       "Break this into at most " max-parts " parts that can be built "
       "independently and in parallel — each a self-contained piece of the work "
       "with as little overlap as possible. If the task is small enough for one "
       "agent, return a single part.\n\n"
       "Return ONLY the parts, one per line, each starting with \"- \" and "
       "phrased as a concrete instruction. No preamble, no numbering, no "
       "explanation."))

(def ^:private bullet-re
  ;; A markdown bullet or a numbered/parenthesized item: "- x", "* x", "1. x",
  ;; "2) x". Captures the text after the marker.
  #"(?m)^[ \t]*(?:[-*]|\d+[.)])[ \t]+(.+?)[ \t]*$")

(defn parse-plan
  "The sub-tasks in a planner `reply`: bullet/numbered lines, trimmed, blanks
  dropped, bounded to `max-parts`. nil when the reply has no list at all — the
  caller then keeps the whole problem as a single worker rather than fanning
  out over garbage."
  [reply max-parts]
  (when reply
    (let [parts (->> (re-seq bullet-re reply)
                     (map (comp str/trim second))
                     (remove str/blank?)
                     (take max-parts)
                     vec)]
      (when (seq parts) parts))))
