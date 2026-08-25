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

(ns samizdat.agent.arbiter
  "One arbiter per boundary, emitting at most one message.

  The TypeScript harness injects its milestone prompt, its emergency-review
  prompt and its stuck hint from three independent conditionals, so a branch
  can receive three messages before a single turn, each pushing a different
  direction. dirge found up to five and replaced the chain with one arbiter
  choosing in strict priority. That is the behavior-tree fallback node, and it
  is the single highest-value structural change available here.

  Two properties follow and both are asserted in the tests.

  Exactly one steer per boundary, always. Not zero-or-one per gate.

  A gate that fires records what it expects to happen next, and a later turn
  settles that prediction from the journal with no model in the path. A gate
  whose predictions never settle is not steering anything, and that is
  measurable rather than arguable."
  (:require [samizdat.agent.gates :as gates]
            [samizdat.agent.state :as state]))

(defn eligible
  "Every gate whose precondition holds and whose budget is not spent, in
  priority order. Exposed so a test can assert what the arbiter passed over,
  not only what it chose."
  [ctx]
  (->> gates/gates
       (filter (fn [g] (and ((:when g) ctx)
                            (not (gates/budget-exceeded? g (:branch ctx))))))
       (sort-by :priority)))

(defn decide
  "Pick at most one steer for this boundary.

  Returns nil when nothing applies, or {:gate :message :prediction :window
  :priority :passed-over}. `:passed-over` names the gates that also held, which
  is what makes the gate tally's co-occurrence column readable — a gate that
  only ever fires alone tells you something different from one that is
  perpetually outranked."
  [ctx]
  (let [candidates (eligible ctx)]
    (when-let [chosen (first candidates)]
      {:gate (:gate chosen)
       :priority (:priority chosen)
       :message ((:message chosen) ctx)
       :prediction ((:prediction chosen) ctx)
       ;; The tool this gate's prediction names, when it names exactly one.
       ;; Carried so the steer can be prefilled instead of merely asked for.
       :tool (:tool chosen)
       :window (:window chosen)
       :passed-over (mapv :gate (rest candidates))})))

(defn prefill-for
  "The partial assistant text that forecloses a prose answer on the steered
  turn, or nil when no gate fired.

  Gate predictions settled 4 met to 22 unmet in gen-19 and 9 to 27 in gen-20.
  Across both runs the gates that changed behaviour were the ones that
  WITHHELD something — the audit's refusals redirected work — while the ones
  that merely suggested did not: milestone and tier-escalation each went
  0-for-4. Ending the request mid-fence is the withholding form of a
  suggestion; the model cannot reply in prose because it is already inside a
  tool call.

  A gate that names one tool gets that name too, which is the cheapest fix for
  a prediction like \"the branch runs a slow-tier check\" — not a callable
  thing. A gate naming a choice gets the bare fence: forcing one of two would
  be the harness deciding rather than steering."
  [decision]
  (when decision
    (if-let [t (:tool decision)]
      (str "```tool-call\n{\"name\": \"" t "\"")
      "```tool-call\n")))

(def ^:private forceable
  "Schemas for the tools a gate may FORCE via native tool_choice, from
  gates.edn :forceable-tools (tier 3b: data). Only the terminal tools —
  forcing a mid-task tool would be the harness deciding rather than
  steering. Minimal: enough for the provider to accept the function."
  (:forceable-tools (gates/config)))

(defn force-tool-for
  "The native-tool spec to FORCE for a decision naming a forceable tool, or nil.

  The provider-agnostic force (native tool_choice), which supersedes the prefill
  for tool-naming gates: a prefill only lands where the provider continues a
  trailing assistant message (DeepSeek /beta), and a gate that must land a done
  on GLM cannot rely on that. See samizdat.llm.adapter.openai."
  [decision]
  (when-let [t (:tool decision)]
    (get forceable t)))

;; --- settling predictions ---------------------------------------------------

(defn- progressed? [before after]
  (or (> (count (state/confirmed-artifacts after))
         (count (state/confirmed-artifacts before)))
      (> (count (:artifacts after)) (count (:artifacts before)))))

;; The tool names each gate's settle reads as compliance (review3 #6). Tier
;; 1a: the table lives in gates.edn as :tool-vocab :settle-called — data,
;; not inline strings in a case, so a test can walk it and assert every name
;; resolves to a registered run-tool: a settle name the loop can never
;; dispatch is a gate whose prediction silently narrows to :unmet. The
;; proof-era names (verify_template, review, audit, retract_rule, add_rule,
;; sketch) came out with their tool surface; what remains is the live
;; vocabulary.

(defn settle-called-names
  "Every tool name any settle rule reads as compliance, deduped. Exposed for
  the vocabulary test (review3 #6)."
  []
  (distinct (mapcat seq (vals (gates/tool-vocab :settle-called)))))

(defn settle
  "Decide whether an open prediction came true, given what the branch did.

  Deterministic and cheap: the questions are all of the form 'did the branch
  call one of these tools' or 'did it produce something new'. No model is
  consulted, because a judge asked to grade the harness's own steering is a
  judge with an opinion about the harness.

  Returns :met, :unmet, or nil for still-open."
  [{:keys [gate turn window]} {:keys [current-turn tools-called branch-before branch-after]}]
  (let [settle-called (gates/tool-vocab :settle-called)
        expired? (>= (- current-turn turn) window)
        tools-met? (fn [g] (boolean (some (get settle-called g) tools-called)))]
    (cond
      (case gate
        ;; Now that the gate withholds rather than suggests (vf-49o), what
        ;; counts as compliance is what the branch DOES about the refused
        ;; approach: a new plan, a restated thesis — or a result the withheld
        ;; approach could not have produced, whatever tool produced it. The
        ;; last clause matters because the reframe is claim-scoped, and
        ;; complying looks exactly like verifying something else successfully.
        :stuck (or (tools-met? :stuck)
                   (progressed? branch-before branch-after)
                   ;; A verification the harness ACCEPTED while the reframe
                   ;; stood. The withheld claim is refused for as long as the
                   ;; reframe lasts, so a call that got through is on a
                   ;; different one by construction — which is precisely what
                   ;; the refusal asked for, and it banks nothing, so neither
                   ;; progressed? nor any named tool can see it.
                   ;;
                   ;; Without this the gate records a miss against its own
                   ;; success. Watched it happen on a live knights-3 run
                   ;; (2026-08-18): B2 was withheld "A is a knave, B is a
                   ;; knight, and C is a knave", dropped it, opened a proof on
                   ;; something else, and the firing settled unmet. Since the
                   ;; met-rate is the only evidence any of this works, a
                   ;; blind spot there would have re-measured the same
                   ;; misleading zero vf-31m exists to correct.
                   ;;
                   ;; The MECHANICS counter, not the policy one. Every call
                   ;; the harness declines increments it and every call it
                   ;; accepts clears it, so it covers refusals the phase policy
                   ;; never sees. Keying on the policy counter alone produced a
                   ;; false met on gen-31 B2.3 (2026-08-18): the branch's next
                   ;; turn was declined with "That statement is already proved
                   ;; ... in different words", which is :mechanics but not a
                   ;; policy refusal, and the gate was credited with compliance
                   ;; that had not happened. A settling that can be wrong in
                   ;; THIS direction is worse than one that is blind, because
                   ;; the met-rate is the only evidence the reframe works.
                   ;; Known soft spot: eval and shell carry no claim and are
                   ;; never refused, so they read as compliance on their own.
                    (and (:reframe-entered-turn branch-after)
                         (some (set tools-called) (gates/tool-vocab :verification))
                         (zero? (or (:consecutive-mechanics-failures branch-after) 0))))
        (:prologue-cap :progress-stalled) (progressed? branch-before branch-after)
        :human-directive true
        (tools-met? gate))
      :met

      expired? :unmet
      :else nil)))
