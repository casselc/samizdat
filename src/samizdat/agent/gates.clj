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

(ns samizdat.agent.gates
  "Gate definitions: the conditions under which the harness says something to
  the model, and what it expects to happen next.

  A gate is data. It has a precondition re-evaluated every tick, a message, a
  budget, and a prediction that a later turn settles deterministically. The
  arbiter picks at most one per boundary; nothing here decides to fire.

  Preconditions are re-evaluated rather than latched by one-shot counters,
  which is the behavior-tree property worth taking from Kelley (arXiv
  2404.07439): a condition that stopped holding should stop firing, and a
  counter cannot express that.

  Every gate declares a prediction because a gate that cannot say what should
  change is one whose effect nobody can check. Settling them is what makes the
  gate tally worth reading (AHE decision observability)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.agent.state :as state]
            [samizdat.agent.supervisor :as supervisor]))

(defn load-config
  "Gate thresholds from resources/gates.edn. Read through io/resource so the
  path works interpreted and inside an AOT binary."
  []
  (edn/read-string (slurp (io/resource "gates.edn"))))

(defonce ^:private config-cache (atom nil))

(defn config []
  (or @config-cache (reset! config-cache (load-config))))

(defn reload-config! [] (reset! config-cache (load-config)))

(defn threshold [k]
  (get-in (config) [k :value]))

(defn tool-vocab
  "The tool vocabulary `k` (:verification, :shipping, :file-write,
  :settle-called) from gates.edn. The vocabularies the gates read are
  runtime-tunable data, like the thresholds; the vocabulary test in
  agent-test walks every name against the registered run-tools (review3 #6)."
  [k]
  (get-in (config) [:tool-vocab k]))

(defn- prompt [name]
  (slurp (io/resource (str "prompts/" name ".md"))))

(defn- fired-count [branch gate]
  (count (filter #(= gate (:gate %)) (:gate-history branch))))

;; --- gate definitions -------------------------------------------------------
;;
;; Priority is ascending: 0 is highest. The order encodes what matters when two
;; conditions hold at once, and it is the whole content of the arbiter.
;;
;; Tier 3a: the table below is the hand-written half. Gates that have moved
;; to data live in gates.edn under :gates, compiled by compile-gate into
;; this same closure shape (:reflection and :prologue-cap are the pilots);
;; the merge happens below the vector.

(def ^:private closure-gates
  [{:gate :human-directive
    :priority 0
    :budget nil
    :doc "A human told the branch to do something. Outranks every machine gate,
          which is dirge PR 717's finding arriving as a design property rather
          than a bug fix."
    :when (fn [{:keys [directive]}] (some? directive))
    :message (fn [{:keys [directive]}]
               (str "**A human has intervened in this run.**\n\n"
                    (:payload directive)
                    "\n\nThis takes precedence over anything the harness has told"
                    " you. Act on it on this turn."))
    :prediction (fn [_] "the branch acts on the directive on the next turn")
    :window 1}

   {:gate :done-blocked
    :priority 1
    :budget :max-done-blocks
    :doc "done was called without the evidence it requires. The message says
          which gate is unmet, because 'not yet' with no reason produces
          another identical attempt."
    :when (fn [{:keys [done-block]}] (some? done-block))
    :message (fn [{:keys [done-block]}] done-block)
    :prediction (fn [_] "the branch supplies the missing evidence or gives up")
    :window 3}

   {:gate :safe-state
    :priority 2
    :budget :max-safe-state-aborts
    :doc "DS1's third failure rung. The branch has failed repeatedly since a
          green ship-verify, so what it changed since is the suspect, not
          the approach. Advisory by default: it names the fallback rather
          than performing it, because a restore that is not fully covered
          produces a session that never existed."
    :when (fn [{:keys [branch]}]
            (state/safe-state-due? branch (threshold :cull-threshold)
                                   (threshold :safe-state-multiple)))
    :message (fn [{:keys [branch safe-state-coverage]}]
               (str (prompt "safe-state")
                    "\n\nYour last green test run was at turn "
                     (:green-snapshot branch) ". "
                    (if (:ok safe-state-coverage)
                      (str "Every turn since is journalled, so replaying up to"
                           " that point is a state the branch actually occupied"
                           " — you can retrace it by hand turn by turn.")
                      (str "The turn log no longer reaches that point: "
                           (:reason safe-state-coverage)
                           " Start from what the journal still shows, or ship"
                           " what you have."))))
    :prediction (fn [_] "the branch changes technique, or ships what it has")
    :window 3}

   {:gate :last-call
    ;; 2.5: below safe-state (a branch just told it cannot ship is not forced to
    ;; ship) and above wind-down, so in the final turns the hard force wins over
    ;; the soft nudge. Fractional to slot between the two without renumbering the
    ;; ladder; priorities need only be distinct (gate-config-is-coherent).
    :priority 2.5
    :budget :max-last-call-steers
    :tool "done"
    :doc "The branch is in its last turns and still has not shipped. wind-down
          already asked it to; this FORCES the attempt — a done-tool prefill, so
          the model must continue inside a done call rather than exploring or
          rambling for its final turns. A live team run (karamazov-pab) had every
          worker exhaust its cap with no done or give_up; a forced partial that
          the reviewer/critic can judge beats a silent exhaustion the supervisor
          has to re-task blind. If the branch truly has nothing, the done-gate
          refuses it and the done-blocked rung takes over toward giving up."
    :when (fn [{:keys [branch max-turns]}]
            (and (state/active? branch)
                 max-turns
                 (>= (state/turn-count branch)
                     (- max-turns (threshold :last-call-window)))))
    :message (fn [{:keys [branch max-turns]}]
               (str (prompt "last-call")
                    "\n\nYou are at turn " (state/turn-count branch)
                    " of " max-turns " — your last turns. Ship your best partial "
                    "now with done."))
    :prediction (fn [_] "the branch ships")
    :window 2}

   {:gate :wind-down
    :priority 3
    :budget :max-wind-down-steers
    :doc "The branch has passed the fraction of the turn cap reserved for
          shipping. UCLA's harness reserves a deadline tail for assembly and
          shipping, and a run with no such tail spends its whole budget
          exploring and ships nothing. This steer outranks every nudge rung so
          a branch near its cap is told to ship before it is nagged to do more,
          but it never outranks done-blocked or safe-state: a branch that has
          just been told it cannot ship is not being asked to ship."
    :when (fn [{:keys [branch max-turns]}]
            (and (state/active? branch)
                 (>= (state/turn-count branch)
                     (* (threshold :wind-down-fraction) (max 1 max-turns)))))
    :message (fn [{:keys [branch max-turns]}]
               (str (prompt "wind-down")
                    "\n\nYou are at turn " (state/turn-count branch)
                    " of " max-turns "."))
    :prediction (fn [_] "the branch ships")
    :window 3}

   {:gate :emergency-review
    :priority 4
    :budget :max-emergency-reviews
    :doc "At the cull threshold but holding a recent confirmation. Rather than
          culling the branch that produced the most, tell it to ship what it
          has or change approach. Guarded since a live run re-fired it on
          three consecutive boundaries: the precondition persists while the
          branch is busy complying, which is what re-fire guards are for."
    :when (fn [{:keys [branch]}]
            (and (>= (:consecutive-failures branch) (threshold :cull-threshold))
                 (state/confirmed-in-last branch (threshold :cull-recent-window))))
    :message (fn [_] (prompt "emergency-review"))
    :prediction (fn [_] "the branch ships or changes technique")
    :window 3}

   {:gate :milestone
    :priority 5
    :budget :max-milestone-nudges
    :doc "First confirmed artifact on this branch that engages what the branch
          is working on. Runs that do not ship at this moment usually fail:
          after a confirmation the instinct is to push for more, and that
          usually loses the verified result. Relevance-filtered because the
          gate congratulated a branch for verifying that between/3 works
          (vf-8fl), which is the same failure the zebra run produced."
    :when (fn [{:keys [branch]}] (state/has-relevant-confirmed? branch))
    :message (fn [_] (prompt "milestone"))
    :prediction (fn [_] "the branch ships within two turns")
    :window 2}

   {:gate :branch-out
    :priority 7
    :budget :max-branch-outs
    :doc "A branch that just confirmed something, with room left in the beam.

          This is the reproduction half of the loop. The beam had selection
          (critic scores, Pareto retention) and no variation: `branch_theses`
          existed, the scheduler invited forks, and across every live run
          the count of forked children was zero — seven invitations went out
          in one run and every one was declined. An invitation the model may
          decline is not a mechanism; a gate with a settled prediction is.

          Fires on evidence rather than on hope: a confirmation is the
          fitness signal, so the branch worth reproducing from is the one
          that just proved something ABOUT THE PROBLEM — a fork costs another
          engine process and another model call per turn, and is not worth
          spending on a branch that confirmed its own tooling. Silent once the
          run is winding down, because a new line that late cannot finish."
    :when (fn [{:keys [branch branch-count max-turns]}]
            (let [last-fired (->> (:gate-history branch)
                                  (filter #(= :branch-out (:gate %)))
                                  (map :turn)
                                  (reduce max -1000))]
              (and (state/has-relevant-confirmed? branch)
                   (< (or branch-count 0) (threshold :max-total-branches))
                   ;; Not while the branch is still acting on the last ask.
                   (>= (- (state/turn-count branch) last-fired)
                       (threshold :branch-out-cooldown))
                   (< (state/turn-count branch)
                      (* (threshold :wind-down-fraction) (max 1 (or max-turns 40)))))))
    :message (fn [_] (prompt "branch-out"))
    :prediction (fn [_] "the branch calls branch_theses")
    ;; The one tool this prediction names, so the steer can be prefilled
    ;; rather than suggested. Only set where the prediction is
    ;; unambiguous: a gate predicting "review or done" must not have the
    ;; harness pick for the branch.
    :tool "branch_theses"
    :window 3}

   {:gate :repopulate
    :priority 6
    :budget :max-repopulates
    :doc "The beam has fallen below its target width and this branch is the
          strongest survivor. The scheduler marks the branch (it is the only
          thing that knows the alive count, the target, and who is strongest);
          this rung is what actually asks.

          It was an invitation appended straight from the scheduler until it
          became this. That carried no prediction, settled nothing, and showed
          up in no gate tally: gen-17 sent 12 in its first 120 turns and 9 were
          declined, which nobody could see without counting branch-opened
          events by hand. It was also a second harness voice on a boundary that
          had already had its one steer.

          Ranked just above branch-out. Both ask for offspring, but this one
          fires because the beam is dying rather than because a branch is
          thriving, and refilling an empty slot is the more urgent of the two."
    :when (fn [{:keys [branch]}]
            (when-let [due (:repopulate-due branch)]
              (let [last-fired (->> (:gate-history branch)
                                    (filter #(= :repopulate (:gate %)))
                                    (map :turn)
                                    (reduce max -1000))]
                ;; Only for a mark the branch has not already been asked about,
                ;; so a mark that survives on the branch does not re-fire every
                ;; boundary while it is busy complying.
                (> due last-fired))))
    :message (fn [_] (prompt "repopulate"))
    :prediction (fn [_] "the branch calls branch_theses")
    ;; The one tool this prediction names, so the steer can be prefilled
    ;; rather than suggested. Only set where the prediction is
    ;; unambiguous: a gate predicting "review or done" must not have the
    ;; harness pick for the branch.
    :tool "branch_theses"
    :window 3}

   {:gate :stuck
    :priority 8
    :budget :max-stuck-hints
    :doc "Consecutive failed or repetitive verifications. Keyed on failure,
          which is why the progress gate below exists as well.

          The one gate that changes branch state rather than only speaking.
          It used to append a hint and nothing else, and settled 0 met across
          every generation that recorded it — for two reasons, both fixed
          together. It fired at cull-threshold, so the advice to change course
          arrived on the turn the branch became killable for not having
          changed it (vf-31m); stuck-threshold is now strictly lower and the
          firing opens a grace window. And it merely suggested, where this
          harness's one reliably-working gate is the one that WITHHOLDS
          (vf-49o): the loop answers a firing by calling state/begin-reframe,
          after which re-verifying the failing approach is refused on every
          engine until the branch puts a different one on record.

          A gate is data and cannot mutate the branch, so the effect lives in
          the loop beside the turn-budget bookkeeping. The ordering that makes
          it work is the beam's: every branch advances before any is culled,
          so the reprieve is in place by the time retention is decided on the
          same turn."
    :when (fn [{:keys [branch]}]
            (>= (:consecutive-failures branch) (threshold :stuck-threshold)))
    :message (fn [{:keys [branch]}]
               (str (prompt "stuck")
                    (when-let [c (:last-failed-claim branch)]
                      (str "\n\n**Withheld**, until you put a different approach"
                           " on record:\n\n> " c "\n\nThat claim will not reach an"
                           " engine — any engine — while this stands. Everything"
                           " else is open, including a smaller piece of the same"
                           " goal. The next " (threshold :reframe-grace) " turns are"
                           " yours to change course in; you will not be culled for"
                           " the failures that led here."))
                    ;; The reflexion log: everything this branch has already
                    ;; tried and abandoned (the newest is the withheld claim
                    ;; above), so it diverges instead of circling back to a dead
                    ;; end it has forgotten.
                    (when-let [earlier (seq (butlast (:abandoned branch)))]
                      (str "\n\nApproaches you have already tried and abandoned"
                           " — do not retry these:\n"
                           (str/join "\n" (map #(str "- " %) earlier))))))
    :prediction (fn [_] "the branch retracts, decomposes, or changes technique")
    :window 3}

   {:gate :progress-stalled
    :priority 10
    :budget :max-stall-nudges
    :doc "Turns passing with no progress event, after the branch has shown it
          can make progress. Arms only after the first, so exploration is never
          nudged."
    :when (fn [{:keys [branch]}]
            (and (:any-progress? branch)
                 (>= (:turns-since-progress branch)
                     (threshold :progress-stall-threshold))))
    :message (fn [{:keys [branch]}]
               (str (prompt "progress-stalled")
                    "\n\nNothing has advanced in " (:turns-since-progress branch)
                    " turns."))
    :prediction (fn [_] "the branch produces a new confirmed artifact or discharges a sub-claim")
    :window 3}

   {:gate :studying
    :priority 11
    :budget :max-studying-nudges
    :doc "The supervisor's stall: the branch shipped something, then lapsed into
          inspecting — read/grep/eval turn after turn, all succeeding, changing
          nothing. The failure gates key on errors (there are none) and the
          progress gate keys on confirmed artifacts (a coding run makes none),
          so this is the case both are blind to. It watches the branch's own
          turn pattern instead — see samizdat.agent.supervisor, which is also
          what a multi-agent supervisor sub-loop reads. Coding-tunable, since it
          reads tool mechanics, not verification."
    :when (fn [{:keys [branch]}]
            (and (state/active? branch)
                 (supervisor/over-studying? (tool-vocab :shipping)
                                            (:turns branch)
                                            (threshold :studying-turns))))
    :message (fn [{:keys [branch]}]
               (supervisor/stall-nudge (tool-vocab :shipping)
                                       (:turns branch)
                                       (threshold :studying-turns)))
    :prediction (fn [_] "the branch commits a change, runs a test, or ships")
    :window 3}

   ;; The :tier-escalation gate lived here: artifacts exist but only from the
   ;; fast tier, push for a cross-checked one. Its slow tier left with the
   ;; proof engines; the coding tool set re-adds it when it defines what a
   ;; slow-tier check is (tests passing vs a review passing, say).

   {:gate :turn-budget
    :priority 12
    :budget nil
    :doc "The turn cap was enforced but invisible to the model, so it could not
          budget against it (dirge PR 738)."
    ;; Reads the branch's own record of which fractions it has been told
    ;; about. Taking it from the context instead meant the caller had to
    ;; remember to thread it back, and on the first live run it did not — the
    ;; gate re-fired on every turn past 60%.
    :when (fn [{:keys [branch max-turns]}]
            (let [used (/ (double (state/turn-count branch)) (max 1 max-turns))
                  notified (:notified-fractions branch #{})]
              (boolean (some #(and (>= used %) (not (contains? notified %)))
                             (threshold :turn-budget-notices)))))
    :message (fn [{:keys [branch max-turns]}]
               (str "**Turn budget**: you have used " (state/turn-count branch)
                    " of " max-turns " turns. Land what you can verify rather"
                    " than opening a new line of attack."))
    :prediction (fn [_] "the branch converges rather than starting something new")
    :window 3}])

;; --- data-driven gates (tier 3a) ---------------------------------------------
;;
;; The steer policy as data: gates.edn :gates entries carry :when as EDN
;; forms, compiled HERE at load into the closure shape above — the manifest
;; :dispatches are the precedent (EDN predicates evaluated at compile time).
;; The form sees exactly the context keys the loop passes; anything else
;; fails to compile at load, which is the fail-fast. Inside the compiled fn
;; the accessors are ordinary calls, so (threshold k) reads the config atom
;; at FIRE time — tuning a threshold stays runtime-editable; only the form
;; structure compiles at load.

(defn- compile-when
  [form]
  (eval `(fn [~'ctx]
           (let [~'directive            (get ~'ctx :directive)
                 ~'done-block           (get ~'ctx :done-block)
                 ~'branch               (get ~'ctx :branch)
                 ~'max-turns            (get ~'ctx :max-turns)
                 ~'branch-count         (get ~'ctx :branch-count)
                 ~'safe-state-coverage  (get ~'ctx :safe-state-coverage)]
             ~form))))

(defn- compile-message
  "A prompts/ file plus an optional suffix, with {{turn-count}} and
  {{max-turns}} interpolated at fire time — the same {{...}} convention as
  every other prompt seam."
  [{:keys [message-file message-suffix]}]
  (fn [{:keys [branch max-turns]}]
    (let [subst (fn [s] (-> (str s)
                            (str/replace "{{turn-count}}"
                                         (str (state/turn-count branch)))
                            (str/replace "{{max-turns}}" (str max-turns))))]
      (str (some-> message-file prompt subst)
           (some-> message-suffix subst)))))

(defn- compile-gate
  [entry]
  (assoc entry
         :when (compile-when (:when entry))
         :message (compile-message entry)
         :prediction (let [p (:prediction entry)] (fn [_] p))))

(def gates
  "The full table: the hand-written closures plus the data-driven gates from
  gates.edn, one arbiter-visible shape. Priorities, not table order, decide
  arbitration."
  (into [] (concat closure-gates (mapv compile-gate (:gates (config))))))

(def by-name (into {} (map (juxt :gate identity)) gates))

(defn crossed-fractions
  "Which turn-budget notice thresholds this branch has now passed. The loop
  folds these into the branch so the gate stops re-firing."
  [branch max-turns]
  (let [used (/ (double (state/turn-count branch)) (max 1 max-turns))]
    (set (filter #(>= used %) (threshold :turn-budget-notices)))))

(defn budget-exceeded?
  "Whether this gate has already fired as often as it may."
  [gate branch]
  (when-let [k (:budget gate)]
    (>= (fired-count branch (:gate gate)) (threshold k))))

(defn describe
  "The gate table, for docs and for /v1/harness/gates."
  []
  (for [g gates]
    {:gate (:gate g) :priority (:priority g)
     :budget (:budget g)
     :budget-kind (some-> (:budget g) (#(get-in (config) [% :kind])))
     :doc (str/replace (str/trim (:doc g)) #"\s+" " ")}))
