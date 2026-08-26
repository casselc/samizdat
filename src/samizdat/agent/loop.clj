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
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.base :as tools-base]
            [samizdat.llm.client :as llm]
            [samizdat.llm.fence :as fence]
            [samizdat.agent.skills :as skills]
            [samizdat.llm.message :as message]
            [samizdat.store.artifacts :as artifacts]
            [samizdat.store.failures :as failures]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.messages :as messages]
            [samizdat.store.runs :as runs]))

(def max-result-chars 4000)

(declare js1-system-prompt)

(defn system-prompt
  "The system prompt, with the template catalogue substituted in.

  The catalogue is generated rather than written into the file because it is
  pure data and would otherwise drift: before this, the only way the model
  learned which templates exist was to guess a name and read the list off the
  error, which meant a template it had not guessed was effectively invisible.

  The tool documentation IS hand written, because a prompt is prose and
  generated prose reads like it. `samizdat.prompt-test` asserts every name in
  `tools/tool-names` appears here, so a new tool cannot be added without being
  documented — that is what kept the whole Lean surface unreachable.

  The 1-arity form dispatches on the context: a JS1-constrained ctx
  (tools-base/js1-profile?) gets the bounded generated prompt
  (js1-system-prompt) INSTEAD of this file — never this file plus a
  disclaimer. The generic prompt advertises the whole tool surface, and a
  JS1 model that read it spent its turns calling tools the gate could only
  refuse (the self-hosting canary failure, 2026-08-26). The zero-arity form
  always renders the generic prompt; prompt-digest and the prompt tests key
  on it."
  ([]
   (-> (slurp (io/resource "prompts/system.md"))
       ;; The SMT template catalogue left with the proof engines; the seam
       ;; stays until the coding prompt replaces this file outright.
       (str/replace "{{templates}}" "")
       ;; The skill catalogue is always in the prompt but cheap — names and
       ;; trigger descriptions only, never bodies — so the model knows what it
       ;; can `skill load` and WHEN, without spending a turn to discover them.
       (str/replace "{{skills}}" (skills/render-catalog))))
  ([ctx]
   (if (tools-base/js1-profile? ctx)
     (js1-system-prompt ctx)
     (system-prompt))))

(defn judge-exemptions
  "The DO-NOT-FLAG list shipped to the audit and review judges. A var rather
  than a slurp inline so the digest can be attributable to it; re-read per
  digest, which is per run."
  []
  (slurp (io/resource "prompts/judge-exemptions.md")))

(defn prompt-digest
  "A cheap fingerprint of the prompt and gate set a run used. AHE component
  observability: a pass-rate change should be attributable to a file."
  []
  (str (hash [(system-prompt) (gates/config) (judge-exemptions)])))

;; --- the bounded JS1 prompt --------------------------------------------------
;;
;; A JS1 run's model must never see the generic prompt: it advertises the
;; whole tool surface, and the JS1 gate (tools-base/phase-refusal) can only
;; refuse every one of those calls — the model orients by what the prompt
;; advertises, and the canary burned 47 turns on refused names. The bounded
;; prompt below REPLACES the generic one for a JS1-constrained ctx. Its two
;; authorities are derived, not written:
;;
;;   - the tool surface is rendered from tools-base/js1-tool-prompt-docs,
;;     keyed exactly over the gated vocabulary js1-allowed-tools, so the
;;     advertised tools ARE the dispatchable tools;
;;   - the project operations are rendered from the binding's effective
;;     authority via sandbox/capability-briefs — the one catalog doc and
;;     complete answer from — so the prompt cannot attest an operation the
;;     binding cannot dispatch.
;;
;; Nothing here is a resource file: the prompt is a pure function of the
;; gated vocabulary and the binding's ContextSpec, which is the whole point
;; — prompt authority cannot drift from dispatch authority.

(defn- sandbox-var
  "Resolve a samizdat.agent.sandbox var at call time, so this namespace stays
  loadable where the sandbox (and its SCI dependency) is absent — the same
  seam tools.repl uses. Returns the var, or nil."
  [var-name]
  (try
    (requiring-resolve (symbol "samizdat.agent.sandbox" var-name))
    (catch Throwable _ nil)))

(defn- js1-live-briefs
  "The binding's effective operation briefs, live-derived through
  sandbox/capability-briefs — the same catalog `doc` and `complete` serve.
  nil when live derivation is impossible in this process: the sandbox ns
  absent (no SCI — a state no real JS1 run reaches, since binding creation
  fails closed without it), or the binding is not a live one (a spec-only
  test fixture, say)."
  [binding]
  (when-let [f (sandbox-var "capability-briefs")]
    (try (seq (f binding))
         (catch Throwable _ nil))))

(defn- js1-spec-capability-names
  "The bind-time ContextSpec's capability names (\"project/...\"), sorted —
  inert data read straight off the binding's spec map. Attenuation enters
  only through the spec and the JS1 tool surface offers no revocation path,
  so at open time this IS the effective set: the safe degraded rendering
  when the live catalog cannot be read in this process."
  [binding]
  (sort (map (fn [c] (str "project/" (name c)))
             (:capabilities (:spec binding)))))

(defn- js1-operations-block
  "The prompt's project-operations section. Derived per binding, so a
  narrower binding's prompt never inherits a wider binding's prose: the
  live catalog when readable, the inert spec's capability names otherwise,
  and a fail-closed statement when the run holds no binding at all (the
  eval tool will refuse; the prompt must not pretend otherwise)."
  [binding]
  (if (nil? binding)
    (str "No sandbox binding is wired to this run: `eval` refuses to evaluate"
         " and no project operations are available. That is a controller wiring"
         " fault — report it with `done`; do not probe for a wider surface.")
    (let [briefs (js1-live-briefs binding)]
      (if briefs
        (str "This binding's effective project operations — exactly what `doc` and"
             " `complete` report — are:\n\n"
             (str/join "\n"
                       (for [b briefs]
                         (str (:name b) " " (pr-str (:arglists b)) "\n    " (:doc b)
                              (when (:effect b)
                                (str "  Effect: " (clojure.core/name (:effect b)) "."))))))
        (let [names (js1-spec-capability-names binding)]
          (if (seq names)
            (str "This binding's project operations are: " (str/join ", " names) "."
                 " `doc` and `complete` report the same set; a name outside it is"
                 " not granted. (Per-operation documentation is unavailable in"
                 " this process.)")
            (str "This binding grants no project operations; `eval` computes over"
                 " the reviewed pure language only.")))))))

(defn js1-system-prompt
  "The bounded system prompt for a JS1-sandboxed context (:js1/profile set,
  preset :project/develop or any other bound preset) — what a JS1 run's
  model sees INSTEAD of the generic prompt.

  Generated, not templated: the tool section is tools-base's gated
  vocabulary (the only four tools dispatch accepts), and the operations
  section is the binding's own effective authority. The prompt teaches the
  persistent-EVALUATOR discipline (committed defs persist; a failed eval
  commits nothing; define helpers and reuse them) and the one-fence-per-turn
  mechanics, and it names nothing the ctx cannot do — no tool outside the
  gated four, no project operation outside the effective ContextSpec."
  [ctx]
  (let [binding (tools-base/js1-binding ctx)
        tools-block (str/join "\n"
                              (map #(get tools-base/js1-tool-prompt-docs %)
                                   (tools-base/js1-tool-vocabulary)))]
    (str
     "You are a Clojure developer working in a persistent, sandboxed evaluator"
     " — a JS1 SCI context bound to one authorized project root. You are NOT in"
     " the live harness image: there is no host process access and no network,"
     " and the project is reachable only through the bounded operations listed"
     " below, called from inside `eval` code. The four tools in this prompt are"
     " the COMPLETE surface: any other tool name is refused before dispatch,"
     " so never reach for a tool you do not see here."
     "\n\n## Each turn\n\n"
     "State your reasoning in prose, then emit exactly one tool call as a"
     " fenced block:\n\n"
     "```tool-call\n"
     "{\"name\": \"eval\", \"args\": {\"code\": \"(+ 1 2)\"}}\n"
     "```\n\n"
     "The harness runs it and returns the result. Then you go again. Keep"
     " every call's JSON small and valid: inside a JSON string every \" must"
     " be \\\" and every newline \\n — a large payload with unescaped quotes is"
     " the most common way a call fails to parse. Build big forms in small"
     " steps rather than one giant call."
     "\n\n## Tools\n\n"
     tools-block
     "\n\n## Project operations — ordinary calls inside `eval` code, never tools\n\n"
     (js1-operations-block binding)
     "\n\nAn operation not listed above is NOT granted to this binding and fails"
     " at dispatch; the list — not your priors — is the authority."
     "\n\n## The evaluator persists: define helpers, then reuse them\n\n"
     "A successful eval's definitions COMMIT, and every later eval sees them."
     " A failed eval commits nothing — names it defined before the error are"
     " rolled back.\n\n"
     "  1. eval {\"code\": \"(defn halve [n] (quot n 2))\"}   — defines halve; it persists\n"
     "  2. eval {\"code\": \"(halve 84)\"}                     — => 21, reusing the committed def\n"
     "  3. eval {\"code\": \"(def x 1) (no-such-fn)\"}         — fails; x does NOT exist afterwards\n\n"
     "Work in that rhythm: small evals, helper by helper, reusing what already"
     " committed instead of resending it. Let `doc` and `complete` answer what"
     " exists rather than guessing at names, and compose your committed"
     " helpers with the granted operations above."
     "\n\n## Finishing\n\n"
     "When the problem is answered — or answered as far as the run could take"
     " it — call `done`. Nothing you have not evaluated counts.")))

(defn shareable?
  "Whether a just-produced artifact belongs in the run's shared pool.

  Engine-confirmed is the entry condition that separates this from UCLA's
  self-reported results, and relevance is the second one. An artifact that
  engages neither the branch's thesis nor the problem cost its own branch a
  turn and nothing more; exported, it becomes every branch's context. Run
  0d0c3560 shipped `Diagnostic: between(-1,1,X) succeeds for X = -1,0,1.` to
  three siblings four turns in (vf-8fl)."
  [branch artifact share?]
  (boolean (and share?
                (= :confirmed (:claim-status artifact))
                (state/advances-thesis? branch (:claim artifact)))))

(defn- truncate [s]
  (let [s (str s)]
    (if (> (count s) max-result-chars)
      (str (subs s 0 max-result-chars) "\n… [truncated]")
      s)))

(defn initial-messages
  "The branch's opening messages. A workflow may hand a `prompt-suffix` — extra
  system guidance appended for that workflow — which is how a manifest injects
  its own instructions at the start (a review workflow adds review guidance on
  top of the base prompt, keeping the whole tool surface). nil/blank leaves the
  base prompt untouched.

  The third argument is the run ctx. A JS1-constrained ctx replaces the base
  prompt with the bounded js1-system-prompt OUTRIGHT — not generic plus a
  trailing disclaimer: the disclaimer left the old tool surface advertised
  above it, and the model read the surface, not the disclaimer. A
  prompt-suffix is trusted workflow guidance and is still appended, after the
  bounded prompt as it would be after the generic one. A nil ctx is the
  generic behavior, byte for byte."
  ([problem] (initial-messages problem nil nil))
  ([problem prompt-suffix] (initial-messages problem prompt-suffix nil))
  ([problem prompt-suffix ctx]
   [{:role "system" :content (cond-> (system-prompt ctx)
                                (not (str/blank? prompt-suffix))
                                (str "\n\n" prompt-suffix))}
    {:role "user" :content (str "## Problem\n\n" problem "\n\nIssue your first tool call.")}]))

(defn orient-messages
  "Enforce the prompt boundary at the one seam where the run's ctx and the
  outgoing request meet.

  A JS1 branch can be opened by a driver that built its opening messages
  without the ctx in hand (the one-arity initial-messages call sites), so
  the generic prompt — advertising tools the JS1 gate can only refuse —
  would otherwise reach the model. For a JS1-constrained ctx this replaces
  the system message with the bounded prompt derived from the ctx's
  effective authority (adding one when the conversation has none), so what
  the model sees is always exactly the closed surface it may call. The
  substitution is a pure function of the ctx, identical every turn: a branch
  already opened on the bounded prompt re-renders to the same content.
  Non-JS1 contexts return the messages untouched.

  One observability note: the runs-row prompt-digest still hashes the generic
  prompt for a JS1 run — it is computed before the binding exists. The
  bounded prompt is derived from code plus the capability catalog, so its
  provenance is the code revision and the journaled :js1-binding-created
  data, not the digest."
  [ctx messages]
  (if (tools-base/js1-profile? ctx)
    (let [prompt (system-prompt ctx)
          messages (vec messages)]
      (if (= "system" (:role (first messages)))
        (assoc messages 0 {:role "system" :content prompt})
        (into [{:role "system" :content prompt}] messages)))
    messages))

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
                                 (messages/render-inbox conn run-id (:id branch))
                                 (failures/render fhits)
                                 (artifacts/render ahits)])]
      {:block (when (seq blocks) (str/join "\n\n" blocks))
       :branch (update branch :shared-served (fnil into #{}) (map :id fresh))})))

;; --- one turn ---------------------------------------------------------------

(def ^:private max-call-attempts
  "One retry, then the turn is spent. Unbounded escalation here would let a
  single turn eat a branch's whole budget, and a model that has not reached a
  tool call in twice its cap is not one token short."
  2)

(defn- truncated-without-call?
  "The response ran out of tokens before it emitted a usable tool call.

  fence/signals already separates this from `:no-fence` and its docstring says
  what to do about it — 'the fix is more tokens, not more steering' — but the
  loop steered anyway and forfeited the turn. gen-12 opened with three of these
  in a single round; gen-11 spent 12% of its turns this way against gen-10's
  4%. Truncation that still carried a call is a complete turn and is left
  alone.

  Takes the prefill for the same reason the parser does: a prefilled response
  begins mid-fence, so parsing it without the opener finds no call and would
  bill the branch a retry for a turn that had in fact issued one."
  [response prefill]
  (let [parsed (fence/parse-tool-call (:content response) {:prefill prefill})]
    (and (:truncated (fence/signals response parsed))
         (or (nil? parsed) (= "__parse_error__" (:name parsed))))))

(defn call-model
  "One model call, retried once at a doubled budget when the first response hit
  the token cap before emitting a tool call.

  Same sizing as the judge's: double the configured budget rather than repeat
  it, since a response that ran out of room needs room, and repeating the call
  at the same cap reproduces the same truncation.

  The messages pass through orient-messages first: for a JS1-constrained ctx
  the model is always shown the bounded prompt, even when the branch was
  opened by a driver that had no ctx in hand."
  [ctx branch]
  (loop [attempt 1]
    (let [budget (when-let [base (:max-tokens (:llm-config ctx))]
                   (* base (bit-shift-left 1 (dec attempt))))
          r (try
              {:ok true
               :response (llm/chat (:llm-adapter ctx) (:llm-config ctx)
                                   ;; Older turns go as a digest of what they
                                   ;; tried once the history is long; the
                                   ;; branch's own message list is untouched,
                                   ;; so the journal and a resume still hold
                                   ;; everything. Below the threshold this
                                   ;; returns the messages unchanged.
                                   (message/compact (orient-messages
                                                     ctx (:messages branch))
                                                    (:turns branch))
                                   (cond-> {}
                                     budget (assoc :max-tokens budget)
                                     ;; Set by the previous turn's steer. The
                                     ;; adapter drops it if the provider cannot
                                     ;; continue a trailing assistant message,
                                     ;; so this is a hint, never a requirement.
                                     (:prefill branch)
                                     (assoc :prefill (:prefill branch))
                                     ;; A gate forcing a specific tool: sent as a
                                     ;; native tool_choice, honoured on every
                                     ;; OpenAI-compatible provider (GLM included).
                                     (:force-tool branch)
                                     (assoc :force-tool (:force-tool branch))))}
              (catch Throwable e
                {:ok false :error (ex-message e)}))]
      (if (and (:ok r)
               (< attempt max-call-attempts)
               (truncated-without-call? (:response r) (:prefill branch)))
        (do (when (and (:conn ctx) (:run-id ctx))
              (journal/note! (:conn ctx) (:run-id ctx) :turn-retry
                             {:branch-id (:id branch)
                              :data {:reason "truncated before any tool call"
                                     :budget budget}}))
            (recur (inc attempt)))
        r))))

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
    (-> (state/enter-build turn)
        (state/add-message
         "user"
         ;; "prologue" only for a branch that has never left explore. Once a
         ;; reframe can send one back (vf-9wx) the same message on a re-entry
         ;; would be describing something that is not happening.
         (str "[harness] "
              (if (:reframe-entered-turn branch)
                "Your re-planning budget is spent: "
                "The explore prologue is over: ")
              (gates/threshold :explore-cap)
              " turns without a sketch on record. You are in the"
              " BUILD phase — Lean verification is available and"
              " `sketch` is not. The way forward is to prove your"
              " claims directly.")))))

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
                          " Try again.")))

(defn absorb-response
  "Fold the model's response into the branch: parse the fence, record the
  mechanics signals, and append what the assistant actually said — opener
  included, because storing the bare completion would leave a turn beginning
  mid-fence in the transcript, misrepresenting the format back to the model
  on every later turn."
  [branch response]
  (let [content (:content response)
        ;; The prefill the request ended with, if any. Without it the response
        ;; starts mid-fence and parses as a no-call — the very failure the
        ;; prefill exists to prevent.
        prefill (:prefill branch)
        parsed (fence/parse-tool-call content {:prefill prefill})
        signals (fence/signals response parsed)
        said (fence/reattach content prefill)]
    {:parsed parsed
     :signals signals
     :said said
     :branch (-> branch
                 ;; Cleared here, not where it was set: one steer forecloses
                 ;; prose on one turn. Leaving it would make every later turn
                 ;; start inside a fence — or, for force-tool, force the same
                 ;; terminal call every turn after.
                 (dissoc :prefill :force-tool)
                 (state/add-message "assistant" said)
                 (state/record-mechanics signals))}))

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
        (state/add-message "user" msg)
        ;; And make the next request end mid-fence, so prose is not an
        ;; available reply. Telling the model to emit a fence is the
        ;; suggesting form; this is the withholding form, which is the one
        ;; that has ever worked — see arbiter/prefill-for. Bare, with no tool
        ;; named: nothing is being steered — the branch had a plan and failed
        ;; to act on it, and picking its next call for it would replace a
        ;; mechanics failure with the harness doing the reasoning.
        (assoc :prefill "```tool-call\n"))))

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
                    ;; Early shared refusal before ANY model tool method.  It
                    ;; avoids needless stale work but is not the effect fence:
                    ;; eval operations and done verification obtain a permit
                    ;; at their actual semantic launch boundaries.
                    (tools-base/dispatch-tool
                     (assoc ctx :branch branch :turn turn
                            :tool-name tool :args (:args parsed))))
        branch (-> (:branch result)
                   ;; Any attempt at an engine clears the search counter,
                   ;; including one that fails — trying is what the refusal
                   ;; asks for, not succeeding.
                   (cond-> (contains? state/verification-tools tool)
                     (dissoc :searches-since-attempt))
                   ;; The tool and the claim ride along so the branch can
                   ;; remember what it was grinding when it failed — which is
                   ;; what the stuck gate withholds (vf-9wx).
                   (state/record-outcome
                    (assoc result :tool tool
                           :claim (or (get-in parsed [:args :claim])
                                      (when (#{"proof_start" "proof_step"} tool)
                                        (get-in branch [:proof :claim])))))
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
                 (cond-> (state/add-artifact branch (assoc a :turn turn))
                   ;; A banked sketch is the way out of the explore prologue:
                   ;; from the turn it lands, verification is open (vf-b25).
                   (= :sketch (:claim-status a))
                   (state/enter-build turn)
                   ;; And anything banked at all ends a reframe: the withheld
                   ;; approach could not have produced it (vf-9wx).
                   (:reframe-entered-turn branch)
                   (state/clear-reframe))
                 branch)]
    ;; A confirmation used to mark the green point the safe-state rung falls
    ;; back to. With no engine session there is nothing to snapshot; the
    ;; coding analogue (a store checkpoint) re-arms this rung when the
    ;; mutation protocol lands.
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
      (state/add-message branch "user" (truncate (:result result)))
      ;; No green snapshots are taken without an engine session, so
      ;; safe-state-due? stays false and the safe-state gate stays dormant
      ;; until the store-checkpoint version arrives.
      (let [coverage nil
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
        (cond-> (-> branch
                    (dissoc :pending-directive)
                    (state/add-message "user" body))
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
                          :prefill (arbiter/prefill-for decision))
          ;; Record which budget notices have been delivered, or the gate
          ;; cannot tell "happened" from "happened and I already reacted".
          (= :turn-budget (:gate decision))
          (assoc :notified-fractions
                 (gates/crossed-fractions branch max-turns))
          ;; The stuck gate is the only one that changes branch state rather
          ;; than only speaking (vf-49o). A gate is data and cannot mutate the
          ;; branch, so its effect is applied here.
          (= :stuck (:gate decision))
          (state/begin-reframe turn
                               (:last-failed-claim branch)
                               (:last-failed-tool branch)))))))

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
      (let [{:keys [branch parsed signals said]} (absorb-response branch response)]
        (if (or (nil? parsed) (= "__parse_error__" (:name parsed)))
          (no-call-step ctx branch turn {:parsed parsed :signals signals
                                         :said said :response response})
          (let [{:keys [branch result tool]} (tool-step ctx branch turn parsed)]
            (journal-step! ctx branch turn {:parsed parsed :result result
                                            :tool tool :said said
                                            :response response})
            (steer-step ctx before branch turn {:parsed parsed :result result})))))))
