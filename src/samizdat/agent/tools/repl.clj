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

(ns samizdat.agent.tools.repl
  "The REPL tools: eval, doc, complete.

   In a JS1-profiled context (ctx carries a JS1 binding — the canonical
   signal shared with the phase-refusal gate), these three tools route
   through the persistent SCI sandbox binding instead of the live harness
   image.  The model's `eval` lands in SCI, not in the JVM process, and is
   durably recorded (intent before each effect, outcome after).
   `doc` and `complete` are HOST-DERIVED safe capability discovery over
   the binding's effective authority (samizdat.agent.sandbox/operation-doc
   and complete-capability): no resolve/find-ns/ns-publics/meta form is
   ever evaluated inside the sandbox, and untrusted symbol/prefix text is
   matched as inert data, never spliced into evaluated source.

    When no JS1 binding is present the tools fall through to the existing
    live-REPL path (samizdat.repl), preserving every non-JS1 workflow.

    The live REPL is NEVER reached in a JS1 context: routing consults the
    same signal the tool gate consults, and a JS1 context whose binding is
    missing is refused outright instead of falling through to live eval.

    Commit-only rollback: a failed recorded evaluation supersedes the
    binding in the provider registry, so after any sandbox-domain
    evaluation error the eval tool re-acquires the current binding and
    installs it into the ctx's holder (base/update-js1-binding!) — the
    NEXT eval works.  An observed :stale-binding is retried once on the
    fresh binding."
  (:require [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.repl :as repl]))

;; --- JS1 sandbox path --------------------------------------------------------

(defn- sandbox-var
  "Resolve a samizdat.agent.sandbox var at call time, so this namespace
   stays loadable where the sandbox (and its SCI dependency) is absent —
   non-JS1 workflows pay no dependency cost.  Returns the var, or nil
   when the sandbox namespace is unavailable."
  [var-name]
  (try
    (requiring-resolve (symbol "samizdat.agent.sandbox" var-name))
    (catch Throwable _ nil)))

(defn- js1-route
  "Resolve eval/doc/complete routing from the ONE canonical JS1 signal,
   the same accessor the phase-refusal gate reads (base/js1-binding).

   Returns the binding for a JS1 context, ::unbound for a JS1 context
   whose binding is missing — impossible when workflow/resume wired the
   ctx, and refused rather than routed to the live REPL — or nil for a
   plain non-JS1 context."
  [ctx]
  (cond
    (some? (base/js1-binding ctx)) (base/js1-binding ctx)
    (base/js1-profile? ctx) ::unbound
    :else nil))

(defn- js1-refusal
  "The fail-closed result for a JS1 context that cannot be routed to its
  sandbox.  Never a live-eval fallthrough: authority was fixed at bind
  time, so a missing binding is a wiring fault to surface, not a seam
  to route around."
  [branch]
  (base/fail branch
             (str "This is a JS1-sandboxed context but its sandbox binding"
                  " is missing; refusing to evaluate outside the sandbox.")))

(defn- js1-reacquire
  "Re-acquire the CURRENT binding for the held one's work-id from the
  ctx's provider.  bind! is idempotent per work-id — equal key and spec
  return the registry's present binding — and a rollback/rebuild is
  exactly a registry publication of a fresh instance under the same
  identity, so this is the sanctioned way back to a usable binding.
  Reconstructs the bind options from the held binding's own spec, so the
  coordinate matches and the call cannot widen anything.  Returns the
  fresh binding, or nil when there is no provider or no sandbox."
  [ctx stale]
  (when-let [provider (base/js1-provider ctx)]
    (when-let [bind-fn (sandbox-var "bind!")]
      (let [spec (:spec stale)
            opts (-> {:preset (:preset spec)
                      :root (:root spec)
                      :instance/key (:instance/key stale)}
                     (into (:bounds spec))
                     (assoc :capabilities (set (:capabilities spec))
                            :timeout-ms (:timeout-ms spec)))]
        (bind-fn provider (:work-id stale) opts)))))

(defn- js1-eval-once
  "One recorded evaluation attempt against `binding`.  Returns the repl
  result shape plus, on a sandbox-domain failure, the structured error
  data so the caller can decide whether the binding was superseded."
  [ctx conn binding code]
  (try
    (let [evaluate! (sandbox-var "evaluate-recorded!")
          lease (:turn-lease ctx)
          ;; The lease's token travels so a revoked turn interrupts its own
          ;; evaluation.  It composes UNDER the spec's ceiling inside the
          ;; sandbox (evaluate-state!): with both present the ceiling timer
          ;; interrupts THIS token at the spec's :timeout-ms, so the lease
          ;; can never stretch an evaluation past the bind-time ceiling.
          opts (cond-> {}
                 (base/turn-lease-token lease)
                 (assoc :token (base/turn-lease-token lease))
                 lease
                 ;; The sandbox invokes this around record-intent!, its actual
                 ;; semantic operation-launch boundary.  The host operation
                 ;; and outcome persistence run after the monitor is released.
                 (assoc :effect-permit!
                        (fn [initiate]
                          (base/with-turn-lease-permit! ctx initiate))))]
      (if (nil? evaluate!)
        {:ok false
         :error "JS1 sandbox is unavailable; refusing live-eval fallback"
         :error-type "sandbox-unavailable"
         :out nil}
        ;; evaluate-recorded! already owns SCI evaluation, rollback and Jolt
        ;; cooperative interruption.  The trusted permit callback only fences
        ;; each operation's durable intent; it never surrounds SCI evaluation.
        (let [result (evaluate! conn binding code opts)]
          {:ok true :value (pr-str (:value result)) :out nil})))
    (catch ExceptionInfo e
      (let [d (ex-data e)]
        (if (:samizdat.sandbox/error d)
          {:ok false
           :error (ex-message e)
           :error-type (str (:samizdat.sandbox/error d))
           :sandbox-error (:samizdat.sandbox/error d)
           :out nil}
          {:ok false :error (ex-message e)
           :error-type (str (type e)) :out nil})))
    (catch Throwable e
      {:ok false :error (or (ex-message e) (str e))
       :error-type (str (type e)) :out nil})))

(defn- js1-eval-result
  "Evaluate `code` in the ctx's JS1 binding, appending a durable JS1
  record via the store adapter (evaluate-recorded!).  Returns the same
  shape as repl/eval-code: {:ok true/false, :value/:error, :out}.

   The interrupt ceiling is the spec's :timeout-ms, fixed at bind time —
   a model-supplied timeout is not an option here, exactly as it cannot
   select any other authority.  When a turn lease supplies its token, the
   ceiling still governs: the sandbox interrupts the provided token at the
   ceiling rather than trusting it (see sandbox/evaluate-state!), so the
   lease composes under the bind-time authority, never over it.

  Commit-only state means a FAILED evaluation rolled the instance back
  and published a fresh binding into the provider registry: the binding
  this call held is superseded, and the next evaluate-recorded! on it
  would be refused as :stale-binding.  So after any FAILED recorded
  evaluation the tool re-acquires the current binding and installs it
  into the ctx's holder (update-js1-binding!) — the next eval works.
  The re-acquire is best-effort: it cannot widen anything (the options
  come from the held binding's own spec), and a refresh that itself
  fails must not mask the evaluation's real error.  An observed
  :stale-binding (a supersession this harness did not witness, e.g. a
  concurrent rebuild) additionally retries the evaluation ONCE on the
  fresh binding, because the model asked for an evaluation, not for an
  error about wiring it cannot see."
  [ctx code]
  (let [conn (:conn ctx)
        binding (base/js1-binding ctx)
        refresh! (fn [stale]
                   (try
                     (when-let [fresh (js1-reacquire ctx stale)]
                       (base/update-js1-binding! ctx fresh))
                     (catch Throwable _ nil)))]
    (loop [binding binding, retried? false]
      (let [r (js1-eval-once ctx conn binding code)]
        (cond
          (:ok r) r

          ;; A supersession this harness did not witness.  Re-acquire and
          ;; retry once — never twice, an unbounded retry would spend the
          ;; turn laundering a genuinely broken wiring.
          (and (= :stale-binding (:sandbox-error r)) (not retried?))
          (if-let [fresh (js1-reacquire ctx binding)]
            (do (base/update-js1-binding! ctx fresh)
                (recur fresh true))
            r)

          ;; Any other failure ran the commit-only rollback (or never
          ;; reached the sandbox at all): the held binding may be
          ;; superseded even though THIS result is a legitimate evaluation
          ;; error the model must see.  Refresh the holder so the NEXT
          ;; eval works; report the original error untouched.
          :else (do (refresh! binding)
                    r))))))

(defn- js1-doc-result
  "Doc lookup inside the JS1 sandbox: host-derived safe capability
   discovery ONLY.  Delegates to samizdat.agent.sandbox/operation-doc,
   which describes authorized projected operations from inert authority
   data and never evaluates any form inside the sandbox.  Returns the
   same shape as repl/doc-sym, or {:not-found true}."
  [binding sym-str]
  (or (when-let [doc-fn (sandbox-var "operation-doc")]
        (doc-fn binding sym-str))
      {:not-found true :symbol (str sym-str)}))

(defn- js1-complete-result
  "Completion inside the JS1 sandbox: host-derived safe capability
   discovery ONLY.  Delegates to samizdat.agent.sandbox/complete-capability
   (inert prefix filtering over effective authority); never evaluates
   ns-publics or any other form inside the sandbox.  Returns a seq of
   strings."
  [binding prefix]
  (if-let [complete-fn (sandbox-var "complete-capability")]
    (complete-fn binding (str prefix))
    []))

;; --- Tool dispatch methods ----------------------------------------------------

(defn- eval-format-result
  "Format an eval result map for the model. Shared by JS1 and live paths."
  [branch r]
  (if (:ok r)
    (base/ok branch (str "=> " (:value r)
                     (when (seq (:out r)) (str "\n" (:out r)))))
    (base/fail branch (str "Eval error: " (:error r)
                      (when (seq (:out r)) (str "\n" (:out r)))))))

(defmethod base/run-tool "eval" [{:keys [branch] :as ctx}]
  (if-let [m (base/missing ctx :code)]
    (base/malformed branch m)
    (let [code (str (base/arg ctx :code))
          route (js1-route ctx)]
       (cond
         ;; JS1 path: evaluate in the persistent SCI binding, durably
         ;; recorded.  The spec's timeout ceiling governs; a model-supplied
         ;; timeout is not forwarded (authority is fixed at bind time).
         (some? route) (if (= ::unbound route)
                         (js1-refusal branch)
                         (eval-format-result
                           branch (js1-eval-result ctx code)))
         ;; Non-JS1 path: live REPL (unchanged)
         :else (eval-format-result
                 branch (repl/eval-code code (:repl-session ctx)
                                        (some-> (base/arg ctx :timeout-ms)
                                                str str/trim not-empty parse-long)))))))

(defmethod base/run-tool "doc" [{:keys [branch] :as ctx}]
  (if-let [m (base/missing ctx :symbol)]
    (base/malformed branch m)
    (let [sym-str (str (base/arg ctx :symbol))
          route (js1-route ctx)]
      (cond
        ;; JS1 path: host-derived safe capability discovery
        (some? route) (if (= ::unbound route)
                        (js1-refusal branch)
                        (let [d (js1-doc-result route sym-str)]
                          (if (:not-found d)
                            (base/malformed
                              branch (str "No var " sym-str " is available in this context."
                                          " Authorized sandbox operations: "
                                          (str/join ", " (js1-complete-result route "")) "."))
                            (base/ok branch (str (:name d) "\n" (pr-str (:arglists d))
                                                 "\n\n" (:doc d))))))
        ;; Non-JS1 path: live REPL var resolution (unchanged)
        :else (let [d (repl/doc-sym sym-str)]
                (if (:not-found d)
                  (base/malformed branch (str "No var " sym-str " is loaded."))
                  (base/ok branch (str (:name d) "\n" (pr-str (:arglists d))
                                       "\n\n" (:doc d)))))))))

(defmethod base/run-tool "complete" [{:keys [branch] :as ctx}]
  (if-let [m (base/missing ctx :prefix)]
    (base/malformed branch m)
    (let [prefix-str (str (base/arg ctx :prefix))
          route (js1-route ctx)]
      (cond
        ;; JS1 path: host-derived safe capability discovery
        (some? route) (if (= ::unbound route)
                        (js1-refusal branch)
                        (let [ms (js1-complete-result route prefix-str)]
                          (base/ok branch (if (seq ms)
                                       (str/join "\n" (take 50 ms))
                                       (str "No symbols match " prefix-str ".")))))
        ;; Non-JS1 path: live REPL (unchanged)
        :else (let [ms (repl/complete prefix-str)]
                (base/ok branch (if (seq ms)
                             (str/join "\n" (take 50 ms))
                             (str "No symbols match " prefix-str "."))))))))
