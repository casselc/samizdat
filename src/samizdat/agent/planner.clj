;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.planner
  "Splitting one feature into independent sub-tasks so a team can fan out over
  them. Pure here — build the split prompt, parse the model's part list — so it
  is testable without a provider; the LLM call lives in the :team/plan cell.

  This is the flat, one-level decomposition. Recursive decomposition with
  per-part contracts (karamazov-ioo.15) builds on the same parse."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.prompt :as prompt]))

(defn default-max-parts
  "Cap on the fan-out width when the planner splits a task itself, from
  gates.edn :planner-max-parts. Config :run :max-subtasks still overrides per
  run. Kept small: parallel workers share one workspace, and a wide split
  multiplies collision risk faster than it buys speed — which is a judgement
  about a PROJECT's workspace, so it belongs in that project's policy."
  []
  (gates/threshold :planner-max-parts))

(defn plan-prompt
  "The split prompt: prompts/planner.md rendered for `problem` and
  `max-parts`.

  It used to be built with `str` here, which made this the one prompt in the
  harness a project could not edit — every other one goes through the prompt
  seam. The shape it asks for is what `parse-plan` reads, so the two travel
  together: change the requested format in the template and change the parser."
  [problem max-parts]
  (prompt/render "planner" {:problem problem :max-parts max-parts}))

(def ^:private bullet-re
  ;; A markdown bullet or a numbered/parenthesized item: "- x", "* x", "1. x",
  ;; "2) x". Captures the text after the marker.
  #"^[ \t]*(?:[-*]|\d+[.)])[ \t]+(.+?)[ \t]*$")

(defn parse-plan
  "The sub-tasks in a planner `reply`: the LAST contiguous block of
  bullet/numbered lines, trimmed, blanks dropped, bounded to `max-parts`. nil
  when the reply has no list at all — the caller then keeps the whole problem
  as a single worker rather than fanning out over garbage.

  The last block, not every bullet in the reply: the prompt asks for the parts
  and nothing else, but a model that thinks out loud first often thinks in
  bullets too, and harvesting those handed four workers fragments of the
  planner's own musing while (take max-parts) dropped the actual answer
  (karamazov-6a3). The answer a model formats after its reasoning is the
  answer, and it is the thing the prompt told it to END with."
  [reply max-parts]
  (when reply
    (let [blocks (->> (str/split-lines reply)
                      (map #(some-> (re-matches bullet-re %) second str/trim))
                      (partition-by nil?)
                      (remove (comp nil? first))
                      (map #(vec (remove str/blank? %))))
          parts (->> (or (last (filter seq blocks)) [])
                     (take max-parts)
                     vec)]
      (when (seq parts) parts))))
