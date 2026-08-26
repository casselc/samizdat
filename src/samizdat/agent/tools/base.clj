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
;;

(ns samizdat.agent.tools.base
  "The tool multimethod and what every tool method shares: result helpers,
  missing-argument complaints, the :default method, tool-names, and the
  per-phase refusal the branch loop consults before dispatch. Tool groups
  require this namespace; nothing here requires a group back.

  JS1 profile: when the context carries a JS1 binding (the canonical
  signal — see js1-binding), the phase-refusal gate rejects every tool
  name not in js1-allowed-tools before dispatch.  The model sees eval,
  doc, complete, and done; file/shell/manifest/mutate etc. are refused
  as policy. The live REPL is never reached in a JS1 context — eval
  routes to the persistent SCI binding instead.

  The ctx's :js1/binding may be the Binding map itself or an ATOM holding
  it (js1-binding derefs either).  The atom is the wiring workflow/resume/
  beam use: a rollback or rebuild supersedes a Binding in the provider
  registry, and the eval tool refreshes the holder in place (see
  tools.repl) so the NEXT eval runs on the binding the registry currently
  publishes — the ctx map a tool receives is immutable, the cell it holds
  is not."
  (:require [clojure.string :as str]))

;; ─── Controller-owned turn authority ─────────────────────────────────────

(declare js1-profile?)

(defrecord TurnLease [id run-id branch-id turn state interrupt-token])

(defn mint-turn-lease
  "Mint the controller authority for exactly one scheduled branch turn.

  The lease is deliberately not data supplied to a tool call: the scheduler
  installs it at the top level of ctx, while model arguments remain under
  :args.  JS1 cannot construct one through its projected vocabulary.  `state`
  is the linearization cell.  Its monitor serializes effect-permit issuance
  with the one :active -> :revoked transition."
  [run-id branch-id turn]
  (->TurnLease (str (random-uuid)) run-id branch-id turn
               (atom {:status :active :permits-issued 0})
               (jolt.host/make-interrupt)))

(defn turn-lease?
  [x]
  (instance? TurnLease x))

(defn turn-lease-status
  "The lease's current terminal/active state keyword, or :invalid."
  [lease]
  (if (turn-lease? lease)
    (:status @(:state lease))
    :invalid))

(defn active-turn-lease?
  [lease]
  (= :active (turn-lease-status lease)))

(defn turn-lease-authorizes?
  "True only for the exact run/branch/turn coordinate the controller minted."
  [lease ctx]
  (and (active-turn-lease? lease)
       (= (:run-id lease) (:run-id ctx))
       (= (:branch-id lease) (get-in ctx [:branch :id]))
       (= (:turn lease) (:turn ctx))))

(defn revoke-turn-lease!
  "Atomically revoke a lease under the same monitor that issues effect permits.

  The first caller owns the transition and its reason; later callers observe
  the already-terminal state.  Returns true only for the active -> revoked
  linearization.  A permit already issued is an effect whose initiation
  linearized first; otherwise revocation wins and no later permit can issue.
  Interruption is intentionally a separate operation so the scheduler can
  (and does) revoke BEFORE interrupt."
  [lease reason]
  (when (turn-lease? lease)
    (let [state (:state lease)]
      (locking state
        (when (= :active (:status @state))
          (swap! state assoc :status :revoked :reason reason)
          true)))))

(defn interrupt-turn-lease!
  "Interrupt the Jolt token carried by a revoked lease.  This is the existing
  cooperative Jolt interruption path used by the SCI evaluator, not another
  executor/runtime."
  [lease]
  (when (and (turn-lease? lease) (:interrupt-token lease))
    (jolt.host/interrupt! (:interrupt-token lease))))

(defn turn-lease-token
  "The controller token passed to JS1's existing interruptible evaluator."
  [lease]
  (when (turn-lease? lease) (:interrupt-token lease)))

(defn assert-active-turn-lease!
  "Diagnostic active-state assertion, not an effect permit.  A stale/missing
  lease is an authority failure.  Missing is allowed only for non-JS1 legacy
  callers; scheduled JS1 turns always carry a lease.  Effect boundaries must
  use with-turn-lease-permit! so revocation is serialized with initiation."
  [ctx]
  (let [lease (:turn-lease ctx)]
    (when (or (and (js1-profile? ctx) (nil? lease))
              (and (some? lease) (not (turn-lease-authorizes? lease ctx))))
      (throw (ex-info "Turn authority is absent or revoked; refusing stale model effect"
                      {:samizdat.turn-lease/error :stale
                       :lease/status (turn-lease-status lease)})))
    true))

(defn with-turn-lease-permit!
  "Linearize one short effect initiation with turn-lease revocation.

  For a supplied lease, its state monitor is held while authority is checked,
  an irrevocable permit number is issued, and `initiate` runs.  Revocation uses
  the same monitor, so exactly one ordering exists:

    * initiation/permit first: this effect was authorized and initiated; a
      later revocation does not retroactively cancel it, or
    * revocation first: initiation throws :stale and is never called.

  `initiate` MUST contain only the semantic launch boundary (for recorded JS1
  operations, the durable intent append), never the ensuing file/search/test
  computation.  Its return value is returned unchanged.  Legacy non-JS1 calls
  with no lease run `initiate` directly, preserving their old behavior."
  [ctx initiate]
  (let [lease (:turn-lease ctx)]
    (cond
      (nil? lease)
      (do
        (when (js1-profile? ctx)
          (throw (ex-info "Turn authority is absent; refusing model effect"
                          {:samizdat.turn-lease/error :stale
                           :lease/status :invalid})))
        (initiate))

      (not (turn-lease? lease))
      (throw (ex-info "Turn authority is invalid; refusing model effect"
                      {:samizdat.turn-lease/error :stale
                       :lease/status :invalid}))

      :else
      (let [state (:state lease)]
        (locking state
          (when-not (and (= :active (:status @state))
                         (= (:run-id lease) (:run-id ctx))
                         (= (:branch-id lease) (get-in ctx [:branch :id]))
                         (= (:turn lease) (:turn ctx)))
            (throw (ex-info "Turn authority is absent or revoked; refusing stale model effect"
                            {:samizdat.turn-lease/error :stale
                             :lease/status (:status @state)})))
          ;; This state transition, under the revoker's monitor, is the launch
          ;; linearization point.  The callback is deliberately still inside
          ;; the monitor so a durable intent cannot lag behind its permit.
          (swap! state update :permits-issued (fnil inc 0))
          (initiate))))))

(defn run-with-turn-lease-permit!
  "Run a long synchronous effect under a short lease launch permit.

  Permit issuance is the effect's linearized initiation.  `effect` starts
  immediately afterward but runs OUTSIDE the lease monitor, so revocation is
  never held behind verifier execution or another long computation."
  [ctx effect]
  (with-turn-lease-permit! ctx (constantly true))
  (effect))

;; ─── JS1 profile ─────────────────────────────────────────────────────────

(def ^:private js1-allowed-tools
  "The EXACT set of tool names a JS1-profiled context may dispatch.

  eval, doc, complete — sandboxed REPL via the persistent SCI binding.
  done — the only controller-event tool the model may emit.

  Everything else (file, shell, manifest, mutate, lsp, ship, knowledge,
  journal, messages, tasks, skills, introspect) is rejected before
  dispatch by phase-refusal.  The list is deliberately minimal and
  closed: adding a name here is a trust decision that grants the model
  a new capability path inside the sandbox."
  #{"eval" "doc" "complete" "done"})

(defn js1-binding
  "The ONE canonical JS1 signal: the controller-minted sandbox binding in
  the ctx.  Tool restriction (phase-refusal below) and eval routing (the
  repl tools) both derive their decision from this accessor — never from
  :js1/profile, which is a display/journal label only.  One signal means
  the two gates can never disagree about whether a context is sandboxed.

  Accepts the binding as a plain map (legacy/test wiring) or as the atom
  holder the run drivers install: a failed recorded evaluation rolls the
  instance back, the rollback publishes a fresh binding into the provider
  registry, and the holder is reset to it — so this always derefs to the
  binding the registry currently publishes."
  [ctx]
  (let [b (:js1/binding ctx)]
    (cond
      (nil? b) nil
      (map? b) b
      :else (deref b))))

(defn update-js1-binding!
  "Install `binding` as the ctx's held JS1 binding.  No-op when the ctx
  holds a plain map (an immutable ctx cannot be refreshed from inside a
  tool; a driver that wants refreshable bindings wires the atom holder).
  Returns `binding` so callers can thread it."
  [ctx binding]
  (let [b (:js1/binding ctx)]
    (when (and b (not (map? b)))
      (reset! b binding)))
  binding)

(defn js1-provider
  "The controller-owned provider the binding was minted from, when the
  driver wired one.  Re-binding through it is how a superseded binding is
  re-acquired after a rollback: bind! is idempotent per work-id and returns
  the registry's CURRENT binding."
  [ctx]
  (:js1/provider ctx))

(defn js1-profile?
  "True when the context is JS1-constrained.

  The canonical signal is the binding (js1-binding).  A ctx whose
  :js1/profile flag is set but whose binding is nil is inconsistent —
  and is still treated as JS1-constrained, so it stays tool-restricted
  and the repl tools refuse it rather than falling through to live eval.
  Set only by trusted config or workflow, never by model input."
  [ctx]
  (boolean (or (js1-binding ctx) (some? (:js1/profile ctx)))))

(defn js1-allowed?
  "True when tool-name is in the JS1 allowed vocabulary."
  [tool-name]
  (contains? js1-allowed-tools tool-name))

(defn js1-tool-vocabulary
  "The sorted list of tool names the JS1 profile permits.
  For diagnostics and prompt rendering only."
  []
  (sort js1-allowed-tools))

(defn js1-assert-single-branch!
  "Refuse a JS1-profiled run at multi-branch width, BEFORE any model work.

  A JS1 binding is one persistent :main instance per work-id, and the
  beam's branches would all evaluate into it: one branch's (def x) is
  another branch's surprise, and a whole-history rebuild replays one
  binding's record while the other branches wrote interleaved evals into
  the same instance.  That is not a capability boundary that can be
  enforced mid-run; it is a shape that must not start.  The guard throws
  {:js1/error :multi-branch-not-supported} at the drivers' entry points,
  before a run row is opened, a branch is spawned, or a provider call is
  made — JS1 is single-player by construction."
  [js1-active? width]
  (when (and js1-active? (> (long width) 1))
    (throw (ex-info (str "JS1 profile cannot run a multi-branch run (width "
                         width "): a JS1 binding is one single-player SCI"
                         " instance per work-id")
                    {:js1/error :multi-branch-not-supported
                     :beam-width width}))))

;; ─── Tool multimethod ─────────────────────────────────────────────────────

(defmulti run-tool
  (fn [ctx] (:tool-name ctx)))

(defn ok [branch result & {:as extra}]
  (merge {:result result :category :neutral :progress? false :branch branch} extra))

(defn fail [branch result & {:as extra}]
  (merge {:result result :category :failure :progress? false :branch branch} extra))

(defn turn-lease-refusal
  "A result-map refusal at the one model-tool dispatch boundary, or nil.

  A supplied stale/invalid lease always refuses.  A scheduled JS1 context must
  also have a lease; omission is a controller wiring fault and never a route
  around the guard.  Legacy non-JS1 direct tool callers remain unchanged."
  [{:keys [branch turn-lease] :as ctx}]
  (when (or (and (js1-profile? ctx) (nil? turn-lease))
            (and (some? turn-lease)
                 (not (turn-lease-authorizes? turn-lease ctx))))
    (fail branch
          "Turn authority expired; this stale tool call was not dispatched."
          :stale-lease? true
          :lease-status (turn-lease-status turn-lease))))

(defn dispatch-tool
  "Early shared refusal boundary for model-issued tools.  Policy remains in
  phase-refusal; authority is checked immediately before multimethod dispatch.
  This is intentionally not the effect fence: eval operations and done verify
  launches obtain synchronized permits at their semantic boundaries."
  [ctx]
  (or (turn-lease-refusal ctx)
      (run-tool ctx)))

(defn malformed
  "A call the harness could not act on because its arguments were wrong.

  NOT a failure. The branch produced no claim and tested nothing, so there is
  no evidence here about its line of inquiry — the same reasoning `unavailable`
  makes about an engine outage and the branch loop makes about a malformed
  fence. Charging it to the counter that decides whether a branch lives is the
  vf-jki mistake, and this is the fifth place it turned up: fences,
  expectedVerdict, proof_start, outages, and argument shape.

  `:mechanics` rather than `:neutral`, deliberately: the count is still kept
  and still bounds a branch looping on malformed calls, which is real spend.
  It just stops being read as substance."
  [branch result]
  {:result result :category :mechanics :progress? false :branch branch})

(defn unavailable
  "An external capability could not be reached. Not the branch's fault, so not
  its failure: the failure counter neither rises nor resets, and
  turns-since-progress still ticks because nothing was established."
  [branch capability e]
  (ok branch (str capability " is unavailable: " (ex-message e))))

(defn arg [ctx k] (get-in ctx [:args k]))

(defn missing
  "The complaint for absent required arguments, WITH the call it wanted.

  This used to be a bare list of names. gen-20 B1 called `proof_start` without
  its arguments five times — three producing the byte-identical message — and
  was culled for it; a model that did not understand the call the first time
  learns nothing from being told the same names again. The skeleton costs
  nothing and needs no schema registry, because the tool name and the keys it
  requires are exactly what this function is already handed."
  [ctx & ks]
  (let [absent (remove #(let [v (arg ctx %)]
                            (and (some? v) (not (and (string? v) (str/blank? v)))))
                         ks)]
    (when (seq absent)
      (str "Missing required argument(s): " (str/join ", " (map name absent)) "."
           "\n\nA call to `" (:tool-name ctx) "` looks like:\n"
           "```tool-call\n"
           "{\"name\": \"" (:tool-name ctx) "\", \"args\": {"
           (str/join ", " (for [k ks]
                            (str "\"" (name k) "\": \"<" (name k) ">\"")))
           "}}\n```"))))

(defn phase-refusal
  "The one place that owns per-phase tool policy, consulted by the branch loop
  BEFORE run-tool dispatch. Returns a result map refusing the call, or nil
  when it may proceed.

  The proof harness's explore/build policy (withhold Lean until a sketch,
  withhold sketch once building) left with its tool surface. The seam stays —
  the loop still asks — and the coding loop's phase policy plugs back in here
  when the loop-as-manifest work defines it. Any refusal returned from here
  must carry `:policy-refusal? true` so the cull record can tell a declined
  call from a malformed fence.

  JS1 gate: when the ctx is JS1-constrained (js1-profile?, derived from
  the canonical binding signal), the tool name is checked against the
  closed JS1 vocabulary. A refused tool returns a :policy-refusal? result
  explaining the JS1 constraint. This is the single enforcement point; no
  JS1 tool method needs its own guard."
  [ctx]
  (when (js1-profile? ctx)
    (let [tn (:tool-name ctx)]
      (when-not (js1-allowed? tn)
        (fail (:branch ctx)
              (str "Tool `" tn "` is not available in this context."
                   " JS1 profile permits only: "
                   (str/join ", " (js1-tool-vocabulary)) ".")
              :policy-refusal? true)))))


;; --- unknown ----------------------------------------------------------------

(defmethod run-tool :default [{:keys [branch tool-name]}]
  (fail (update-in branch [:mechanics :unknown-tools] inc)
        (str "No tool named `" tool-name "`. Available: "
             (str/join ", " (sort (remove #{:default} (keys (methods run-tool)))))
             ".")))

(defn tool-names []
  (sort (remove keyword? (keys (methods run-tool)))))
