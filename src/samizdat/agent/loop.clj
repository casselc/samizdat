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

(ns samizdat.agent.loop
  "The branch loop: one turn is one model call, one tool call, one arbiter
  decision, and a journal append.

  Phase 3 runs a single branch. The beam in Phase 4 schedules many of these;
  nothing here assumes it is alone, which is why every write already carries a
  branch id.

  The order inside a turn is load-bearing. The tool runs before the arbiter, so
  a gate sees the state the turn produced rather than the state it started
  from. Predictions settle before new gates fire, so a gate cannot be credited
  with an outcome that preceded it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.phases :as phases]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.skills :as skills]
            [samizdat.prompt :as prompt]
            [samizdat.store.artifacts :as artifacts]
            [samizdat.store.failures :as failures]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.messages :as messages]
            [samizdat.store.runs :as runs]))

(defn max-result-chars
  "How much of one tool result the branch is shown, from gates.edn
  :context-budget. A project reading generated files needs a different number
  from one reading hand-written namespaces, which is why it is not a constant."
  []
  (:tool-result-chars (gates/threshold :context-budget)))

(defn system-prompt
  "The system prompt, with the template catalogue substituted in.

  The catalogue is generated rather than written into the file because it is
  pure data and would otherwise drift: before this, the only way the model
  learned which templates exist was to guess a name and read the list off the
  error, which meant a template it had not guessed was effectively invisible.

  The tool documentation IS hand written, because a prompt is prose and
  generated prose reads like it. `samizdat.prompt-test` asserts every name in
  `tools/tool-names` appears here, so a new tool cannot be added without being
  documented — that is what kept the whole Lean surface unreachable."
  []
  ;; Tier 2d-era seam, now selmer: {{templates}} stays until the coding
  ;; prompt replaces system.md outright; the skill catalogue is always in
  ;; the prompt but cheap — names and trigger descriptions only, never
  ;; bodies — so the model knows what it can `skill load` and WHEN,
  ;; without spending a turn to discover them.
  ;;
  ;; The TEXT comes through the :system chain (prompt-chain.edn, LR-7), so a
  ;; project can replace the shipped prompt outright or suppress it entirely.
  ;; First-present-wins: a level replaces, never concatenates. A suppressed
  ;; base is legitimate — a workflow's own :prompt then IS the instruction
  ;; set — so this renders empty rather than falling back to the shipped file.
  (prompt/render-str (or (prompt/layer :system) "")
    {:templates ""
     :skills (skills/render-catalog)}))

(defn judge-exemptions
  "The DO-NOT-FLAG list shipped to the audit and review judges. A var rather
  than a slurp inline so the digest can be attributable to it; re-read per
  digest, which is per run."
  []
  ;; Through the prompt seam, so a project can tune what its judges must not
  ;; flag without editing the harness for everyone.
  (prompt/prompt "judge-exemptions"))

(defn prompt-digest
  "A cheap fingerprint of the prompt and gate set a run used. AHE component
  observability: a pass-rate change should be attributable to a file."
  []
  (str (hash [(system-prompt) (gates/config) (judge-exemptions)])))

(defn shareable?
  "Whether a just-produced artifact belongs in the run's shared pool.

  The policy is gates.edn :share (drg-4026 #5) — which claim statuses count
  and whether relevance gates export — read at fire time, so a project
  retunes its sharing entry condition without a rebuild. The on/off flag
  stays in the run config: it is the diversity trade-off's off switch."
  [branch artifact share?]
  (let [{:keys [statuses require-relevance?]} (gates/threshold :share)]
    (boolean (and share?
                  (contains? statuses (:claim-status artifact))
                  (or (not require-relevance?)
                      (state/advances-thesis? branch (:claim artifact)))))))

(defn- truncate [s]
  (let [s (str s)]
    (if (> (count s) (max-result-chars))
      (str (subs s 0 (max-result-chars)) "\n… [truncated]")
      s)))

(defn initial-messages
  "The branch's opening messages. A workflow may hand a `prompt-suffix` — extra
  system guidance appended for that workflow — which is how a manifest injects
  its own instructions at the start (a review workflow adds review guidance on
  top of the base prompt, keeping the whole tool surface). nil/blank leaves the
  base prompt untouched."
  ([problem] (initial-messages problem nil))
  ([problem prompt-suffix]
   [{:role "system" :content (cond-> (system-prompt)
                               (not (str/blank? prompt-suffix))
                               (str "\n\n" prompt-suffix))}
    {:role "user" :content (str "## Problem\n\n" problem "\n\nIssue your first tool call.")}]))

(defn- context-block
  "What the harness adds to the branch's view before its next turn: the
  failures most like what it just tried, and — when sharing is on — the
  artifacts other branches confirmed that look most like it. Both FTS-ranked
  rather than the whole log, so the block stays small and stays relevant.

  Own-branch entries are excluded from both: a branch re-reading its own
  lemmas is noise, and the value of sharing is exactly the cross-branch hit.
  A shared artifact is journaled the FIRST time it enters this branch's
  context and never again: the block re-renders every turn, so per-serving
  events counted turns (86 events for a 15-row pool on one 28-turn run), not
  distinct sharing. Whether sharing earns the beam its width stays a question
  the journal can answer, now directly. Returns {:block :branch}; the branch
  carries the :shared-served ids the dedup reads."
  [conn run-id branch last-claim share?]
  (let [others #(remove (fn [e] (= (:branch_id e) (:id branch))) %)
        fhits (others (if (str/blank? last-claim)
                        (failures/recent conn run-id 5)
                        (failures/similar conn run-id last-claim 5)))
        ;; Fetched WIDE and then ranked down, not fetched at the display size.
        ;; Ranking a top-5 that is already all seeds just reorders seeds: a
        ;; completed run contributes its whole pool at turn 0 while the live
        ;; run's starts empty, and gen-20 served 67 seeded artifacts to 24 of
        ;; its own. Taking a wider slice is what lets an in-run lemma reach
        ;; the block at all; prefer-in-run then decides the order.
        shared-shown 5
        ahits (when share?
                (->> (if (str/blank? last-claim)
                       (artifacts/recent conn run-id (* 3 shared-shown))
                       (artifacts/similar conn run-id last-claim (* 3 shared-shown)))
                     others
                     artifacts/prefer-in-run
                     (take shared-shown)
                     vec))
        fresh (remove (comp (or (:shared-served branch) #{}) :id) ahits)]
    (doseq [a fresh]
      (journal/note! conn run-id :shared-artifact-hit
                     {:branch-id (:id branch)
                      :data {:claim (:claim a) :source-branch (:branch_id a)}}))
    (let [blocks (keep identity [;; The run's settled state, first and complete:
                                 ;; what is established and — the half nothing
                                 ;; carried before — what is RULED OUT. Read
                                 ;; from the artifacts table every turn, so it
                                 ;; cannot drift from the record, and cheap:
                                 ;; gen-20's whole confirmed set is under 400
                                 ;; tokens of claim text. Unlike the blocks
                                 ;; below it is not FTS-sampled, because the
                                 ;; value of a ledger is that a branch can
                                 ;; trust the absence of a line.
                                 (artifacts/render-ledger
                                  (journal/ledger conn run-id))
                                 ;; Breadcrumb index: kept memories surfaced as
                                 ;; ids + previews only, relevance-ranked by the
                                 ;; branch's last-claim, recent when blank. nil
                                 ;; on an empty store, so keep identity drops it.
                                 (knowledge/breadcrumb-index conn last-claim)
                                 ;; Unread mail from other branches on this run,
                                 ;; a bounded preview; nil when the inbox is
                                 ;; empty. Surfacing does not consume — the
                                 ;; message tool's inbox action marks read.
                                 (messages/render-inbox
                                  conn run-id (:id branch)
                                  (:inbox-lines (gates/threshold :context-budget)))
                                 (failures/render fhits)
                                 (artifacts/render ahits)])]
      {:block (when (seq blocks) (str/join "\n\n" blocks))
       :branch (update branch :shared-served (fnil into #{}) (map :id fresh))})))

;; --- one turn ---------------------------------------------------------------

(defn call-model
  "One model call for `branch`, through the injected inference seam.

  The mechanism moved to samizdat.agent.infer, where the tape is a value and
  `complete` is an argument — this is the branch-shaped wrapper the cells and
  the beam call. Same behaviour as before: one retry at a doubled budget when
  the response hit the token cap before emitting a tool call, and a provider
  failure returned as {:ok false :error} rather than thrown."
  [ctx branch]
  ((infer/complete-fn ctx) (infer/of-branch branch)))

(defn- settle-predictions!
  "Close out any prediction whose window has passed or whose expectation the
  branch just met. Deterministic; no model in the path."
  [conn branch turn tools-called before after]
  (let [{kept true closed false}
        (group-by (fn [p]
                    (nil? (arbiter/settle p {:current-turn turn
                                             :tools-called tools-called
                                             :branch-before before
                                             :branch-after after})))
                  (:open-predictions after))]
    (doseq [p closed]
      (journal/settle-gate! conn (:id p)
                            (arbiter/settle p {:current-turn turn
                                               :tools-called tools-called
                                               :branch-before before
                                               :branch-after after})
                            turn))
    (assoc after :open-predictions (vec kept))))

(defn phase-valve
  "The release valve for the explore prologue (vf-b25): a branch that cannot
  get a skeleton to elaborate must not be locked out of verification for the
  whole run, so at the cap the prologue is declared over and the branch told
  why. The message lands before the model call so the next response actually
  sees it."
  [branch turn]
  (cond-> branch
    (state/explore-cap-expired? branch (gates/threshold :explore-cap) turn)
    (-> (state/enter-phase turn)
        (state/add-message
         "user"
           ;; "prologue" only for a branch that has never left explore. Once a
           ;; reframe can send one back (vf-9wx) the same message on a re-entry
           ;; would be describing something that is not happening.
           ;; Tier 2d: the prose is prompts/explore-cap.md — runtime-editable,
           ;; the same seam every gate message reads through.
           (str "[harness] "
                (prompt/render "explore-cap"
                  {:lead (if (:reframe-entered-turn branch)
                           "Your re-planning budget is spent: "
                           "The explore prologue is over: ")
                   :cap (gates/threshold :explore-cap)}))
         {:turn turn}))))

(defn provider-error-step
  "A provider failure is not the branch's fault and must not count against it
  as a verification failure."
  [{:keys [conn run-id]} branch turn error]
  (log/warn "branch" (:id branch) "turn" turn "model call failed:" error)
  (journal/record-turn! conn run-id
                        {:branch-id (:id branch) :turn turn
                         :tool-name "__provider_error__" :result error
                         :category "neutral"})
  (state/add-message branch "user"
                     (str "[harness] The provider call failed: " error
                          " Try again.")
                     {:turn turn}))

(defn absorb-response
  "Fold the model's response into the branch.

  Two layers, deliberately separate. The TAPE half — parse the fence, append
  what the assistant actually said, clear the per-turn knobs — is
  `infer/absorb`, a pure function of a tape value that a probe drives without
  a branch anywhere in sight. The BRANCH half is the mechanics tally, which is
  bookkeeping about the branch rather than about the conversation, and which a
  probe deliberately does not touch: a bounce that parsed badly is not a
  branch that called badly."
  ([branch response] (absorb-response branch response nil))
  ([branch response turn]
   (let [{:keys [tape parsed signals said]}
         (infer/absorb (infer/of-branch branch) response turn)]
     {:parsed parsed
      :signals signals
      :said said
      :branch (-> (infer/into-branch branch tape)
                  (state/record-mechanics signals))})))

(defn no-call-step
  "No usable call. Say exactly what was wrong; a bare \"try again\" produces
  another identical attempt."
  [{:keys [conn run-id]} branch turn {:keys [parsed signals said response]}]
  (let [msg (cond
              (:truncated signals)
              (str "[harness] Your response hit the token limit before you"
                   " emitted a tool call. Think less and call a tool.")
              (nil? parsed)
              (str "[harness] No ```tool-call block in your response."
                   " Every turn must end with exactly one.")
              :else
              (str "[harness] Your tool-call block did not parse: "
                   (:parse-error parsed)))]
    ;; The response matters most on THIS path. A turn that produced no usable
    ;; call records nothing else about what the model did, and without the
    ;; text there is no way to tell a model that rambled from one that emitted
    ;; the wrong fence from one that answered in prose.
    ;; `mechanics`, not `failure`. The branch produced no claim, so there is
    ;; nothing here to hold against its line of inquiry — the same reasoning
    ;; as the provider-error path. The count is still kept and still bounds
    ;; the branch; see record-outcome.
    (journal/record-turn! conn run-id
                          {:branch-id (:id branch) :turn turn
                           :tool-name (or (:name parsed) "__no_call__")
                           :result msg :category "mechanics"
                           :parse-error (:parse-error parsed)
                           :auto-repaired (:auto-repaired? parsed)
                           :assistant-text said
                           :reasoning-text (:reasoning response)
                           ;; A turn that produced no usable call still cost
                           ;; tokens, and those are the ones worth counting.
                           :usage (:usage response)})
    (-> branch
        (state/record-outcome {:category :mechanics :progress? false})
        (state/add-message "user" msg {:turn turn})
        ;; And make the next request end mid-fence, so prose is not an
        ;; available reply. Telling the model to emit a fence is the
        ;; suggesting form; this is the withholding form, which is the one
        ;; that has ever worked — see arbiter/prefill-for. Bare, with no tool
        ;; named: nothing is being steered — the branch had a plan and failed
        ;; to act on it, and picking its next call for it would replace a
        ;; mechanics failure with the harness doing the reasoning.
        (assoc :prefill "```tool-call\n"))))

(defn apply-transitions
  "Apply the result-signal transitions the turn's result carries (drg-4026
  #3) — the claim-first state machine as a declarative table (phases.edn
  :transitions) instead of cond-> clauses in the executor. A table entry's
  key is a get-in path into the turn envelope; when it holds a truthy value
  each named effect applies. Effect names dispatch here to state fns, data
  cannot mutate the branch."
  [result artifact branch]
  (let [envelope {:result result :artifact artifact}]
    (reduce (fn [b effect]
              (case effect
                :mark-green    (state/mark-green b)
                :clear-reframe (state/clear-reframe b)
                b))
            branch
            (mapcat (fn [[path effects]]
                      (when (get-in envelope path) effects))
                    (phases/transitions)))))

(defn tool-step
  "Dispatch the parsed call: phase policy first, then the tool, then the
  branch bookkeeping the outcome demands. Returns {:branch :result :tool}."
  [ctx branch turn parsed]
  (let [tool (:name parsed)
        ;; Phase policy is consulted before dispatch: a refused call never
        ;; reaches a tool, and the refusal is journalled like any other turn
        ;; (vf-b25, vf-eaw). One place owns the refusals — tools/phase-refusal.
        refusal (tools/phase-refusal
                 (assoc ctx :branch branch :turn turn
                        :tool-name tool :args (:args parsed)))
        result (or refusal
                   (tools/run-tool (assoc ctx :branch branch :turn turn
                                          :tool-name tool :args (:args parsed))))
        branch (-> (:branch result)
                    ;; The tool and the claim ride along so the branch can
                    ;; remember what it was grinding when it failed — which is
                    ;; what the stuck gate withholds (vf-9wx).
                    (state/record-outcome
                     (assoc result :tool tool
                            :claim (get-in parsed [:args :claim])))
                   (state/add-turn {:turn turn :tool tool
                                    :category (:category result)
                                    ;; Kept for failures AND malformed calls,
                                    ;; only so repeating-failure? can see a
                                    ;; loop. The turns table holds the
                                    ;; authoritative result.
                                    :error (when (#{:failure :mechanics}
                                                  (:category result))
                                             (str (:result result)))}))
        ;; 29 of gen-20's 57 failures were four identical (tool, message)
        ;; pairs, and the harness answered the fifth exactly as it answered
        ;; the first. Say something different instead.
        result (if (state/repeating-failure? branch tool (str (:result result)))
                 (update result :result
                         #(str % "\n\n[harness] This exact call has now"
                               " failed this exact way more than once."
                               " Repeating it will fail again. Change"
                               " the call, or change technique — a"
                               " different tool, a smaller claim, or a"
                               " different encoding of the same one."))
                 result)
         branch (if-let [a (:artifact result)]
                   (state/add-artifact branch (assoc a :turn turn))
                   branch)
         ;; A green ship-verify is the green point the safe-state rung
         ;; rewinds to. No tool on the current surface emits :claim-status
         ;; artifacts (the proof engines that did are gone), so the old
         ;; :confirmed trigger keyed on a status that never occurred. Green
         ;; work also ends a reframe: the withheld approach could not have
         ;; produced it (vf-9wx). The signal→effect table itself is
         ;; phases.edn :transitions data (drg-4026 #3).
         branch (apply-transitions result (:artifact result) branch)]
    ;; A green verify marks the green point the safe-state rung falls back
    ;; to. The snapshot is the turn cursor: the journal is the store
    ;; checkpoint — append-only, and what resume replays from — so the
    ;; cursor is all the rung needs to name a rewindable state.
    {:branch branch :result result :tool tool}))

(defn journal-step!
  "The durable record of the turn: the turn row, any artifact (and its entry
  into the shared pool when it qualifies), any failure, any thesis. Side
  effects only; returns nil."
  [{:keys [conn run-id] :as ctx} branch turn {:keys [parsed result tool said response]}]
  (journal/record-turn! conn run-id
                        {:branch-id (:id branch) :turn turn
                         :tool-name tool :args (:args parsed)
                         :result (truncate (:result result))
                         :category (name (:category result))
                         :policy-refusal? (:policy-refusal? result)
                         :auto-repaired (:auto-repaired? parsed)
                         :assistant-text said
                         :reasoning-text (:reasoning response)
                         :usage (:usage response)})
  (when-let [a (:artifact result)]
    (journal/record-artifact! conn run-id
                              (assoc a :branch-id (:id branch) :turn turn))
    ;; Only confirmed, on-topic artifacts enter the shared pool — see
    ;; shareable?. The flag is the diversity trade-off's off switch.
    (when (shareable? branch a (get-in ctx [:config :run :share-artifacts?]))
      (artifacts/record! conn run-id
                         {:branch-id (:id branch) :turn turn
                          :kind (:kind a) :tier (:tier a)
                          :claim (:claim a) :code (:code a)})))
  (when-let [f (:failure result)]
    (failures/record! conn run-id
                      (assoc f :branch-id (:id branch) :turn turn
                             :tool-name tool)))
  (when-let [t (:thesis result)]
    (runs/set-thesis! conn run-id (:id branch) t))
  nil)

(defn- drain-directives!
  "Apply the human directives waiting at this boundary. The single-branch
  driver only sees the branch-scoped kinds: `message` and `review` become a
  :pending-directive the arbiter puts at priority zero; the scheduler-only
  kinds (cull/fork/pause/resume) belong to the beam and are rejected here with
  a reason rather than accepted silently. Returns the branch, possibly carrying
  a :pending-directive. Shares the interventions queue with the HTTP control
  surface, so a REPL steer and a UI steer are the same event."
  [conn run-id branch turn]
  (if-not (and conn run-id)
    branch
    (reduce
     (fn [b d]
       (case (:kind d)
         ("message" "review")
         (do (interventions/resolve! conn run-id (:id d) :applied nil turn)
             (assoc b :pending-directive d))
         "extend"
         b ;; handled by control/extend! against the runs row, not here
         (do (interventions/resolve! conn run-id (:id d) :rejected
                                     (str (:kind d) " applies to the beam scheduler,"
                                          " not a single-branch run")
                                     turn)
             b)))
     branch
     (interventions/pending conn run-id (:id branch)))))

(defn apply-effects
  "Apply the fired gate's branch-state effects (drg-4026 #4).

  A gate is data and cannot mutate the branch, so the small set of effect
  IMPLEMENTATIONS lives here; WHICH gate carries which effect is the gate's
  own :effect key in gates.edn, carried through the decision. Naming the
  effect rather than the gate means adding or renaming a state-changing
  gate is a data edit — loop.clj is not a dispatch table of gate names."
  [decision turn max-turns branch]
  (cond-> branch
    (= :notified-fractions (:effect decision))
    (assoc :notified-fractions (gates/crossed-fractions branch max-turns))

    (= :begin-reframe (:effect decision))
    (state/begin-reframe turn
                         (:last-failed-claim branch))))

(defn steer-step
  "Predictions settle, pending human directives drain, then the single
  boundary: at most one steer, chosen in priority (a human directive outranks
  every machine gate), plus the context block. Returns the branch ready for its
  next turn (or carrying the final answer when the turn shipped)."
  [{:keys [conn run-id max-turns] :as ctx} before branch turn {:keys [parsed result]}]
  (let [tool (:name parsed)
        branch (settle-predictions! conn branch turn [tool] before branch)
        branch (drain-directives! conn run-id branch turn)]
    (if (:done? result)
      (state/add-message branch "user" (truncate (:result result)) {:turn turn})
      ;; Coverage answers whether the safe-state rung's fallback is honest:
      ;; the green cursor still points into a turn log the journal can
      ;; replay up to.
      (let [coverage (state/snapshot-covers? branch)
            decision (arbiter/decide
                      {:branch branch
                       :max-turns max-turns
                       ;; How wide the beam already is, so the reproduction
                       ;; rung knows whether the run can afford offspring.
                       :branch-count (or (:branch-count ctx) 1)
                       :done-block (:done-block result)
                       :directive (or (:pending-directive branch)
                                      (:directive ctx))
                       :safe-state-coverage coverage})
            {ctx-block :block branch :branch}
            (context-block conn run-id branch
                           (get-in parsed [:args :claim])
                           (get-in ctx [:config :run :share-artifacts?]))
            body (str (truncate (:result result))
                      (when ctx-block (str "\n\n" ctx-block))
                      (when decision (str "\n\n---\n\n" (:message decision))))
            ;; Recorded exactly once. The row id is what a later turn settles,
            ;; so writing it twice would leave one firing permanently open.
            firing-id (when decision
                        (journal/record-gate!
                         conn run-id
                         {:branch-id (:id branch) :turn turn
                          :gate (:gate decision)
                          :priority (:priority decision)
                          :message (:message decision)
                          :prediction (:prediction decision)
                          :window (:window decision)}))]
        (when decision
          (log/debug "branch" (:id branch) "turn" turn
                     "gate" (:gate decision)
                     "passed over" (:passed-over decision)))
         ;; drg-4026 #4: any branch-state effect the fired gate carries is
         ;; keyed by the gate's own :effect (gates.edn data), applied by
         ;; apply-effects — not by gate names hard-coded here.
         (apply-effects decision turn max-turns
           (cond-> (-> branch
                     (dissoc :pending-directive)
                     (state/add-message "user" body {:turn turn}))
           decision (update :gate-history (fnil conj [])
                            {:gate (:gate decision) :turn turn})
           decision (update :open-predictions (fnil conj [])
                            {:id firing-id
                             :gate (:gate decision)
                             :prediction (:prediction decision)
                             :window (:window decision)
                             :turn turn})
           ;; Consumed by the NEXT call-model and cleared there, so a steer
           ;; forecloses prose on exactly the turn it steers and no later one. A
           ;; gate naming a forceable tool sets BOTH a prefill and a force-tool
           ;; spec; the adapter uses the prefill where the provider continues a
           ;; trailing assistant message (DeepSeek /beta) and falls back to native
           ;; tool_choice only where it does not (GLM) — tool_choice is rejected
           ;; by some providers' thinking mode, so it is the fallback, not the
           ;; default. A bare steer just prefills the fence.
           decision (assoc :force-tool (arbiter/force-tool-for decision)
                           :prefill (arbiter/prefill-for decision))))))))

(defn run-turn
  "Advance one branch by one turn. Returns the updated branch.

  A composition of the named steps above, in the load-bearing order the ns
  docstring states. The loop manifest composes the same steps as cells, so
  the beam (which calls this directly, see karamazov-ioo.20) and the
  manifest-driven driver share one implementation of every step."
  [ctx branch turn]
  (let [before branch
        branch (phase-valve branch turn)
        {:keys [ok response error]} (call-model ctx branch)]
    (if-not ok
      (provider-error-step ctx branch turn error)
      (let [{:keys [branch parsed signals said]} (absorb-response branch response turn)]
        (if (or (nil? parsed) (= "__parse_error__" (:name parsed)))
          (no-call-step ctx branch turn {:parsed parsed :signals signals
                                         :said said :response response})
          (let [{:keys [branch result tool]} (tool-step ctx branch turn parsed)]
            (journal-step! ctx branch turn {:parsed parsed :result result
                                            :tool tool :said said
                                            :response response})
            (steer-step ctx before branch turn {:parsed parsed :result result})))))))
