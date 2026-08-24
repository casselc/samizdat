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

(ns samizdat.agent.resume
  "Resume a crashed run by replaying its journal.

  UCLA's recovery is event-sourced replay plus a persisted run-start anchor,
  so a restart does not re-grant the budget. The anchor here is the turns
  table and the runs row: a resumed run continues at (last recorded turn)+1
  against the run's ORIGINAL max_turns, so a crash at hour N does not buy N
  more hours. state.clj promises that everything a gate needs is journalled;
  this namespace is what makes the promise true.

  Fidelity contract, per field:

  REPLAYS EXACTLY, from tables verbatim:
  - branch status, inactive-reason, created-at-turn, and thesis (branches row).
  - artifacts with claim-status / verdict / witness (artifacts rows).
  - failures are re-read fresh by the loop from the shared log, as always.
  - message history from the turns table: assistant_text is what the model
    said, result is what the harness answered, over the system prompt and the
    run's recorded problem (loop/initial-messages). Steer messages that fired
    pre-crash are not in the journal, so they are not replayed; that is good
    enough for the model to continue and is the accepted fidelity gap.

  REPLAYS CONSERVATIVELY, recomputed so no guard re-fires on its own past:
  - consecutive-failures, turns-since-progress, any-progress? from the turns
    category column through state/record-outcome, the same function the live
    loop uses. progress? is approximated as (= category :success) because the
    tool's own progress? flag is not journalled.
  - gate-history and open-predictions from gate_firings rows, so a gate that
    fired pre-crash counts against its budget after resume and unsettled
    predictions still settle at the next boundary.
  - notified-fractions from the rebuilt turn count via gates/crossed-fractions,
    so the turn-budget notice does not re-fire on fractions already crossed.
  - tiers-seen from rebuilt artifact tiers.
  - mechanics from the turns parse_error / auto_repaired columns; fence
    signals that are not journalled (truncation, multi-fence) reset to zero.
  - consecutive-policy-refusals replayed from the turns policy_refusal
    column, through record-outcome exactly as the live loop counts it.
  - phase from the banked sketch artifacts, phase-entered-turn from the first
    sketch's turn. A cap-forced build with no sketch resumes as :explore and
    re-fires the cap check — the re-entry can only grant extra explore turns,
    never fewer.

  DOES NOT REPLAY (known limits, accepted):
  - Lean: a resumed branch gets no Lean session and its Lean tools report the
    session unavailable. Lean sessions are process memory with a heavy startup
    cost; a crash cannot resurrect them.
  - The safe-state green snapshot is branch memory, not journalled (mark-green
    assocs into the branch map only), so a resumed branch starts a fresh empty
    Prolog session and the safe-state rung has no fallback until its next
    confirmation. In-process safe-state aborts are unaffected.
  - last-review / last-audit are branch memory; the done gate re-requires
    review/audit after a resume rather than trusting pre-crash state.
  - The claim registry is run-scoped memory; resume starts it empty, safe
    because the worst case is one duplicate slow verification.
  - :shared-served (which shared artifacts a branch was already told about)
    is branch memory; resume starts it empty, so each artifact-branch pair
    can journal one duplicate shared-artifact-hit after a resume.
  - :premises-served (which retrieved premises a branch was already handed,
    vf-3wg) is branch memory for the same reason; a resumed branch may be
    re-served one premise block.
  - the forced reframe (vf-9wx): :reframe-claim and :reframe-entered-turn are
    branch memory, so a branch that crashed mid-reframe resumes with neither
    the withheld approach nor the reprieve that goes with it. Deriving them
    would be easy and wrong — the last stuck firing's turn is in gate_firings
    and the last failed claim is in the turns args, but the two need not
    belong to the same reframe, and reinstating a withholding the harness
    cannot justify blocks work the branch is entitled to do. Erring toward the
    permissive direction is the right way to be wrong about a gate that
    REFUSES things.

    It largely self-heals: consecutive-failures replays, so the stuck gate
    re-fires at the branch's next boundary and re-enters the reframe with a
    claim it can justify. The residual gap is a branch whose max-stuck-hints
    budget is already spent, which loses the reprieve and can be culled for
    the failures that caused the reframe. Bounded by :reframe-grace, and the
    cull was always the documented backstop.
  - :critic scores and :fork-invited markers are branch memory; a resumed
     branch is re-scored at its next boundary, and may be re-invited to fork
     sooner than the cooldown would otherwise allow.
   - the per-turn :error that repeating-failure? compares is not replayed, so a
     branch that was looping on one identical failure gets one un-escalated
     answer after a resume before the harness can see the loop again. The turns
     table does hold the result text, but it holds the ESCALATED copy, which
     would not compare equal to the next clean one — replaying it would break
     the detection it was meant to restore.

    JS1 resume contract:
    - When the journal contains a :js1-binding-created event, the resume MUST
      reconstruct a JS1 binding with the same spec coordinate before continuing.
    - The event carries the EXACT reconstruction information (spec
      capabilities/bounds/timeout, spec coordinate, binding/instance ids,
      preset, and the versioned runtime coordinate); every field is verified
      against the re-minted binding, and any missing field or mismatched
      value fails closed rather than guessing.
    - If SCI (jolt.sandbox) is unavailable at resume time, the resume FAILS
      CLOSED — it throws rather than silently falling back to live eval in a
      context that was supposed to be sandboxed.  The model's prior turns ran
      inside SCI; switching to live eval mid-run would be a trust boundary
      violation.
    - Whole-history rebuild: the re-minted binding's instance is rebuilt
      from the binding's ENTIRE durable committed history
      (sandbox/rebuild-binding!) — every committed evaluation replayed, in
      the binding's durable total order, into ONE fresh SCI context, with
      zero real project operations executed (jolt.sandbox :replay mode
      throughout).  Definitions from prior turns SURVIVE the crash: a
      terminated process resumes with its helper fns and def'd state intact,
      reconstructed from receipts rather than re-run against the project.
      A pending record, a gap in the total order, or any spec/instance/
      binding/authority/runtime mismatch in the history fails closed —
      history that cannot be verified is not replayed."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.claims :as claims]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.loop :as branch-loop]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.base :as js1-base]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(defn- parse-json [s]
  (when s
    (try (json/read-str s :key-fn keyword) (catch Throwable _ s))))

(defn- artifact-map
  "An artifacts row back into the map the tools conj onto the branch."
  [a]
  {:turn (:turn a)
   :kind (keyword (:kind a))
   :claim (:claim a)
   :code (:code a)
   :verdict (some-> (:verdict a) keyword)
   :witness (parse-json (:witness a))
   :claim-status (keyword (:claim_status a))
   :tier (keyword (:tier a))})

(defn- messages-from-turns
  "The message history a continuing model needs: what it said, and what the
  harness answered, over the system prompt and the problem."
  [problem turns]
  (reduce (fn [msgs t]
            (cond-> msgs
              (seq (:assistant_text t))
              (conj {:role "assistant" :content (:assistant_text t)})
              (seq (:result t))
              (conj {:role "user" :content (:result t)})))
          (branch-loop/initial-messages problem)
          turns))

(defn- rebuild-branch
  "Reconstruct one branch's working map from the journal rows for its run.

  The green snapshot is not journalled, so a resumed branch always starts
  the safe-state rule from its 'otherwise' arm."
  [run branch-row turns artifacts firings max-turns]
  (let [branch-id (:id branch-row)
        branch-turns (get turns branch-id [])
        ;; The phase is rebuilt from the banked sketch artifacts: a sketch on
        ;; record means the branch left explore, and its turn is the phase
        ;; entry. A cap-forced build with no sketch resumes as :explore and
        ;; re-fires the cap check on the next turn — conservative, and the
        ;; re-entry can only grant extra explore turns, never fewer.
        artifact-maps (mapv artifact-map (get artifacts branch-id []))
        first-sketch (some #(when (= :sketch (:claim-status %)) (:turn %))
                           artifact-maps)
        base (-> (state/new-branch {:id branch-id
                                    :parent-id (:parent_id branch-row)
                                    :problem (:problem run)
                                    :created-at-turn (:created_at_turn branch-row)
                                    :messages (messages-from-turns (:problem run)
                                                                   branch-turns)})
                 (assoc :status (keyword (:status branch-row))
                        :inactive-reason (:inactive_reason branch-row)
                        :thesis (parse-json (:thesis branch-row))
                        :artifacts artifact-maps
                        :phase (if first-sketch :build :explore)
                        :phase-entered-turn (or first-sketch
                                                (:created_at_turn branch-row))
                        :gate-history (mapv (fn [f]
                                              {:gate (keyword (:gate f))
                                               :turn (:turn f)})
                                            (get firings branch-id []))
                        :open-predictions (mapv (fn [f]
                                                  {:id (:id f)
                                                   :gate (keyword (:gate f))
                                                   :prediction (:prediction f)
                                                   :window (:window f)
                                                   :turn (:turn f)})
                                                (remove #(:outcome %)
                                                        (get firings branch-id [])))))
        ;; The counters, replayed through the same function the live loop
        ;; uses. progress? is approximated from the category because the
        ;; tool's own progress? flag is not journalled.
        branch (reduce (fn [b t]
                         (let [cat (some-> (:category t) keyword)]
                           (-> b
                               (state/add-turn {:turn (:turn t)
                                                :tool (:tool_name t)
                                                :category cat})
                               (state/record-outcome {:category cat
                                                      :progress? (= :success cat)
                                                      :policy-refusal? (pos? (or (:policy_refusal t) 0))}))))
                       base
                       branch-turns)
        branch (assoc branch
                      :tiers-seen (set (keep :tier (:artifacts branch)))
                      :mechanics {:calls (count branch-turns)
                                  :parse-errors (count (filter :parse_error branch-turns))
                                  :auto-repairs (count (filter #(pos? (:auto_repaired %))
                                                               branch-turns))
                                  :unknown-tools 0 :truncations 0 :multi-fences 0}
                      ;; The fractions already crossed are told, so the
                      ;; turn-budget notice does not re-fire on them.
                      :notified-fractions (gates/crossed-fractions branch max-turns))]
    branch))

(defn resumable?
  "A run can be resumed when it exists and has not reached a terminal state.

  An aborted run STAYS aborted: abort is a person saying stop, and a resume is
  not a person changing their mind. A completed run shipped; the answer is the
  record. Only a run still in flight — status 'running', or 'failed' from an
  exhausted process that never got to tear down — is resumable."
  [conn run-id]
  (when-let [r (runs/get-run conn run-id)]
    (not (contains? #{"completed" "aborted"} (:status r)))))

(defn- journal-preset
  "The journal stores event data as JSON, so the preset keyword reads back
   as a STRING: \":project/develop\" under data.json's colon form, or just
   \"develop\" under jolt's port, which writes keyword values via name and
   drops the namespace.  Convert both shapes back to the keyword bind!
   expects — the closed preset catalog lives in the fixed project namespace
   — or nil so the caller's default applies.  bind! still fails closed on
   anything that is not a key of that catalog."
  [v]
  (cond
    (nil? v) nil
    (keyword? v) v
    (string? v) (let [s (if (str/starts-with? v ":") (subs v 1) v)]
                  (if (str/includes? s "/")
                    (keyword s)
                    (keyword "project" s)))
    :else v))

(defn- journal-capability
  "A journaled capability name back to a keyword.  Capabilities are
   journaled as full name strings (\"project/read\"); tolerate the
   name-only form jolt's data.json port can produce for keywords
   (\"read\"), same as journal-preset."
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [s (if (str/starts-with? v ":") (subs v 1) v)]
                  (if (str/includes? s "/")
                    (keyword s)
                    (keyword "project" s)))
    :else v))

(defn- js1-fail!
  [code msg data]
  (throw (ex-info msg (assoc data :js1/error code))))

(defn- work-id-from-binding-id
  "The work-id encoded in a durable binding id (\"bind:<key>:<work-id>\")."
  [binding-id]
  (let [s (str binding-id)]
    (subs s (inc (str/last-index-of s ":")))))

(defn- normalize-js1-info
  "Journaled JS1 reconstruction info into canonical shapes, or a
   :reconstruction-info-missing failure.  Validated BEFORE anything is
   allocated or required: corrupt journal data is refused on its own,
   whatever the sandbox's availability."
  [info]
  (when-not (map? info)
    (js1-fail! :reconstruction-info-missing
               "JS1 resume: journal event data is not a map"
               {:info (pr-str info)}))
  (let [missing (into []
                      (comp
                        (filter #(let [v (get info %)]
                                   (or (nil? v) (str/blank? (str v)))))
                        (map name))
                      [:spec-coordinate :runtime-coordinate :binding-id
                       :instance-id :preset :capabilities :bounds :timeout-ms])]
    (when (seq missing)
      (js1-fail! :reconstruction-info-missing
                 (str "JS1 resume: journal event lacks exact reconstruction"
                      " information: " (str/join ", " missing))
                 {:missing (vec missing)})))
  (let [caps (cond
               (vector? (:capabilities info)) (:capabilities info)
               (set? (:capabilities info)) (vec (:capabilities info))
               :else (js1-fail! :reconstruction-info-missing
                                "JS1 resume: journaled capabilities are not a collection"
                                {:capabilities (pr-str (:capabilities info))}))
        bounds (if (map? (:bounds info))
                 (into {} (map (fn [[k v]] [(keyword (name k)) v]))
                       (:bounds info))
                 (js1-fail! :reconstruction-info-missing
                            "JS1 resume: journaled bounds are not a map"
                            {:bounds (pr-str (:bounds info))}))
        timeout (:timeout-ms info)]
    (when-not (and (integer? timeout) (pos? timeout))
      (js1-fail! :reconstruction-info-missing
                 "JS1 resume: journaled timeout-ms is not a positive integer"
                 {:timeout-ms (pr-str timeout)}))
    (doseq [[k v] bounds]
      (when-not (and (integer? v) (pos? v))
        (js1-fail! :reconstruction-info-missing
                   (str "JS1 resume: journaled bound " (name k)
                        " is not a positive integer")
                   {:bound k :value (pr-str v)})))
    {:profile (:profile info)
     :binding-id (str (:binding-id info))
     :instance-id (str (:instance-id info))
     :preset (journal-preset (:preset info))
     :spec-coordinate (str (:spec-coordinate info))
     :capabilities (vec (sort-by str (mapv journal-capability caps)))
     :bounds bounds
     :timeout-ms timeout
     :runtime-coordinate (str (:runtime-coordinate info))}))

(defn reconstruct-js1-binding!
  "Fail-closed JS1 binding reconstruction for resume, from the journal's
   EXACT binding information and the binding's WHOLE durable committed
   history.

   `conn` is the trusted connection the durable-eval store reads (the
   run database in production; a controller/harness may bind the
   sandbox's *eval-store* adapter instead).  `info` is the parsed
   :js1-binding-created event data.  `root` is the trusted project root
   the re-minted binding is confined to.

   The ladder, each rung failing closed with {:js1/error ...}:

   1. The journaled info is validated as data (normalize-js1-info) —
      before SCI is even required, because corrupt journal data must be
      refused on its own.
   2. samizdat.agent.sandbox must load.  A run that evaluated inside SCI
      cannot resume on live eval: that would be a trust boundary
      violation, not a degradation.
   3. This process's RuntimeCoordinate must equal the journaled one.
      Receipts are only meaningful under the runtime that produced them;
      an upgraded runtime fails closed instead of replaying across the
      change.
   4. A new binding is minted from the trusted preset + the EXACT
      journaled capabilities/bounds/timeout — not from defaults, which a
      harness upgrade could have moved.
   5. The new binding's identity and spec are verified field by field
      against the journal: binding id, instance id, spec coordinate (the
      self-certifying content address over preset/root/capabilities/
      bounds/timeout), and the explicit capabilities/bounds/timeout.
   6. rebuild-binding! replays EVERY committed evaluation of the binding,
      in the binding's durable total order, into ONE fresh SCI context —
      zero real project operations, definitions restored from receipts.
      A pending record (an unsettled effect's actuation state is
      unknown), a gap in the total order, a spec/instance/binding/
      authority/runtime mismatch in any record, or a replay whose result
      does not reproduce the durable completion all fail closed.

   Returns {:binding rebuilt-binding :provider provider :profile name};
   the SCI state (definitions the model made with eval) is the committed
   history, replayed — not lost."
  [conn info root]
  (let [info (normalize-js1-info info)]
    (try
      (require 'samizdat.agent.sandbox)
      (catch Throwable e
        (js1-fail! :sandbox-unavailable
                   (str "JS1 resume failed: SCI/sandbox unavailable. "
                        "The run was JS1-profiled but the sandbox cannot be "
                        "constructed. Refusing to resume with live eval.")
                   {:original (ex-message e)})))
    (let [provider-fn (resolve 'samizdat.agent.sandbox/provider)
          bind-fn     (resolve 'samizdat.agent.sandbox/bind!)
          rt-fn       (resolve 'samizdat.agent.sandbox/runtime-coordinate)
          rebuild-fn  (resolve 'samizdat.agent.sandbox/rebuild-binding!)]
      (when (or (nil? provider-fn) (nil? bind-fn) (nil? rt-fn)
                (nil? rebuild-fn))
        (js1-fail! :sandbox-unavailable
                   "JS1 resume failed: the sandbox API is incomplete in this process"
                   {}))
      ;; 3. The runtime coordinate gates everything below: receipts are
      ;; only meaningful under the runtime that produced them.
      (let [current-runtime (rt-fn)]
        (when (not= current-runtime (:runtime-coordinate info))
          (js1-fail! :runtime-mismatch
                     (str "JS1 resume: runtime coordinate mismatch. The run"
                          " evaluated under a different runtime stack; its"
                          " receipts cannot be replayed here.")
                     {:journaled (:runtime-coordinate info)
                      :current current-runtime})))
      ;; 4. Mint from the trusted preset + the exact journaled authority.
      ;;    A bind! or spec error here (unknown preset, over-request) is a
      ;;    sandbox-domain failure and keeps its own diagnostics.
      (let [prov (provider-fn {:root root})
            work-id (work-id-from-binding-id (:binding-id info))
            binding (bind-fn prov work-id
                             (-> {:preset (:preset info)
                                  :root root
                                  :instance/key :main}
                                 (into (:bounds info))
                                 (assoc :capabilities (set (:capabilities info))
                                        :timeout-ms (:timeout-ms info))))
            spec (:spec binding)
            expect (fn [code what actual expected]
                     (when (not= actual expected)
                       (js1-fail! code
                                  (str "JS1 resume: reconstructed binding "
                                       what " does not match the journal")
                                  {:expected expected :actual actual})))
            _ (expect :binding-id-mismatch "binding id"
                      (:binding/id binding) (:binding-id info))
            _ (expect :instance-id-mismatch "instance id"
                      (:instance/id binding) (:instance-id info))
            _ (expect :coordinate-mismatch "spec coordinate"
                      (:spec/coordinate spec) (:spec-coordinate info))
            _ (expect :capability-mismatch "capabilities"
                      (vec (:capabilities spec)) (:capabilities info))
            _ (expect :bounds-mismatch "bounds"
                      (:bounds spec) (:bounds info))
            _ (expect :timeout-mismatch "timeout"
                      (:timeout-ms spec) (:timeout-ms info))
            ;; 6. Whole-history rebuild: ONE fresh SCI context carrying
            ;;    every committed definition, or a closed failure.
            rebuilt (try
                      (rebuild-fn conn binding)
                      (catch Throwable e
                        (let [d (ex-data e)]
                          (if (:js1/error d)
                            (throw e)
                            (throw (ex-info
                                    (str "JS1 resume: the binding's durable"
                                         " history failed whole-history"
                                         " validation: " (ex-message e))
                                    (-> (or d {})
                                        (assoc :js1/error :history-invalid)
                                        (assoc :sandbox-error
                                               (:samizdat.sandbox/error d)))
                                    e))))))]
        (log/info "JS1 binding reconstructed for work" work-id
                  "spec" (:spec-coordinate info)
                  "- whole committed history replayed into one SCI context")
        {:binding rebuilt :provider prov :profile (:profile info)}))))

(defn- reconstruct-run-js1-binding!
  "The journal-driven wrapper around reconstruct-js1-binding!: finds the
   run's :js1-binding-created event, parses its data, and delegates.
   Returns the reconstruction map, or nil for a non-JS1 run (in which
   case SCI is never required — an unprofiled resume must not depend on
   it)."
  [conn run-id root]
  (let [all-events (journal/events-since conn run-id 0)
        js1-events (filter
                    #(and (= "js1-binding-created" (:kind %))
                          (some? (:data %)))
                    all-events)]
    (when-let [evt (first js1-events)]
      ;; :data is JSON text from the events table; parse it.
      (reconstruct-js1-binding! conn (parse-json (:data evt)) root))))

(defn resume!
  "Rebuild a run's branches from the journal and continue the beam's round
   loop at the round after the last recorded turn, under the run's ORIGINAL
   max-turns.

   `:max-turns` is the one exception to the anchor rule, and it is explicit:
   when passed AND larger than the recorded budget, the runs row is raised to
   it and branches closed as `exhausted` reopen — they closed because the
   budget ran out, not for cause, so more budget un-closes them. Culled,
   abandoned, and done branches stay closed. The extension is journaled
   (budget-extended, branch-reopened) and the rows are updated BEFORE the
   branches are read back, so a crash mid-extension replays correctly. A
   crash still cannot re-grant budget; only a caller asking for more can.

   Pending interventions are already in their table; the existing
   pending-directives drain picks them up at the first resumed boundary — this
   function does not reimplement that path.

    JS1 fail-closed: if the journal contains a :js1-binding-created event,
    the resume reconstructs a JS1 binding, verifies every journaled
    reconstruction field (spec capabilities/bounds/timeout, coordinates,
    identity), and replays the binding's WHOLE durable committed history
    into one fresh SCI context — so the model's definitions survive the
    crash.  If SCI is unavailable, the runtime/spec/identity mismatch, or
    the durable history is malformed or unsettled, the resume THROWS
    rather than falling back to live eval — the model's prior turns ran
    inside SCI, and switching to live eval would be a trust boundary
    violation.  A JS1 journal event on a multi-branch run is likewise
    refused before any work: JS1 is single-player by construction.

    Returns the beam/run-rounds result. Throws when the run is not resumable."
  [{:keys [conn config llm-adapter llm-config run-id abort max-turns]}]
  (let [run (runs/get-run conn run-id)]
    (when-not (resumable? conn run-id)
      (throw (ex-info (str "run " run-id " is not resumable (status "
                           (:status run) ")")
                       {:run-id run-id :status (:status run)})))
    ;; JS1 is single-player: a journaled JS1 binding on a width-N run
    ;; (however it got there) is refused BEFORE the run is marked running
    ;; again, before branches are rebuilt, before any model work.
    (when (and (some #(= "js1-binding-created" (:kind %))
                     (journal/events-since conn run-id 0))
               (> (or (:beam_width run) 1) 1))
      (js1-base/js1-assert-single-branch! true (:beam_width run)))
    ;; The row said 'interrupted' (or 'failed'); it is about to be running
    ;; again, and stalled? only watches runs whose status says so.
    (runs/mark-running! conn run-id)
    (when (and max-turns (> max-turns (:max_turns run)))
      (runs/extend-budget! conn run-id max-turns)
      (doseq [b (runs/branches conn run-id)
              :when (= "exhausted" (:status b))]
        (runs/reopen-branch! conn run-id (:id b))))
    (let [max-turns (max (or max-turns 0) (:max_turns run))
          width (:beam_width run)
          turn-rows (journal/turns conn run-id)
          turns (group-by :branch_id turn-rows)
          artifacts (group-by :branch_id (journal/artifacts conn run-id))
          firings (group-by :branch_id (journal/gate-firings conn run-id))
          sessions (atom [])
          ;; JS1 reconstruction: fail-closed on unavailable SCI, runtime/
          ;; spec/identity mismatch, or malformed/unsettled history; the
          ;; whole committed durable history replays into ONE fresh SCI
          ;; context, so the model's definitions survive the crash.
          root (or (get-in config [:run :root]) (System/getProperty "user.dir"))
          js1 (reconstruct-run-js1-binding! conn run-id root)
          ctx {:conn conn :run-id run-id :config config :problem (:problem run)
               :llm-adapter llm-adapter :llm-config llm-config
               :max-turns max-turns :beam? (> width 1) :beam-width width
               :sessions sessions
               :abort abort
               ;; JS1 profile flags — reconstructed from journal, never
               ;; from model input.  :repl-session is nil when JS1 is
               ;; active; the sandbox binding is the eval target.  The
               ;; binding installs as a refreshable holder so a rollback
               ;; between turns can be absorbed (tools.base/js1-binding).
               :js1/profile (:profile js1)
               :js1/binding (when-let [b (:binding js1)] (atom b))
               :js1/provider (:provider js1)
               ;; One claim registry per run: two branches reaching the same
               ;; claim share one slow verification instead of racing it.
               :claims (claims/new-registry)}
          branches (mapv (fn [row]
                           (rebuild-branch run row turns artifacts firings
                                           max-turns))
                         (runs/branches conn run-id))
          ;; The anchor: rounds completed are the max turn in the journal, so
          ;; the loop continues one past it. max-turns is the ORIGINAL budget.
          start-turn (inc (reduce max 0 (map :turn turn-rows)))]
      (beam/run-rounds ctx branches start-turn))))
