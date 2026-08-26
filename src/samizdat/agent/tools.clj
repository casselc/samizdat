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

(ns samizdat.agent.tools
  "Tool dispatch aggregator.

  run-tool is a multimethod defined in samizdat.agent.tools.base; each tool
  group namespace below registers its defmethods on load. A multimethod
  rather than a case, because it is what lets a tool be redefined against a
  running process and picked up on the next branch turn.

  Every method takes a context and returns a result map:

    {:result   string the model sees
     :category :success | :failure | :neutral   what the cull guard reads
     :progress? bool                            what the stall guard reads
     :branch   the updated branch
     :artifact optional, recorded to the artifacts table
     :failure  optional, recorded to the shared failure log
     :done?    optional, ends the run}

  :category and :progress? are separate on purpose. A tool call can succeed
  and advance nothing, and a model making varied, well-formed, useless calls
  trips no error-keyed guard while burning the whole run.

  To add a tool: open the group namespace it belongs to (or create a new one
  beside them) and defmethod base/run-tool there. This file stays as is."
  (:require [clojure.tools.logging :as log]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.repl]
            [samizdat.agent.tools.files]
            [samizdat.agent.tools.shell]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.agent.tools.tasks]
            [samizdat.agent.tools.messages]
            [samizdat.agent.tools.knowledge]
            [samizdat.agent.tools.journal]
            [samizdat.agent.tools.mutate]
            [samizdat.agent.tools.manifest]
            [samizdat.agent.tools.experiments]
            [samizdat.agent.tools.prompts]
            [samizdat.agent.tools.skills]
            [samizdat.agent.tools.introspect]
            [samizdat.agent.tools.lsp]
            [samizdat.security.secrets :as secrets]))

;; --- the dispatch seam ------------------------------------------------------
;;
;; Everything a tool returns crosses this one function, which is why the two
;; properties that are about the RETURN both live here rather than in each
;; tool: the result envelope is well formed, and no secret is in it.
;;
;; Before this the file read `(def run-tool base/run-tool)` — a bare alias —
;; and both properties were per-tool conventions. RFC-003 recorded redaction
;; as having one structural chokepoint (`run-shell`) plus one wrapper
;; (`tools/repl`), and warned that a future tool returning host-derived
;; content would be outside the boundary with nothing to say so. That was
;; already true of four SHIPPED tools: `read_file` on a `.env`, `grep` for a
;; pattern that happens to match a key, `lsp` relaying a language server's
;; reply, `skill` reading a file off disk. RFC-008 separately recorded that
;; the envelope had no schema and that a bare string once NPE'd the loop
;; (provenance CR1-1).

(def ^:private process-known-values
  "The redaction set for a tool that named no command of its own: every
  credential-shaped value in this process's environment.

  A delay, because it is the same answer for the life of the process and this
  runs on every tool call. `run-shell` still computes its own, narrower and
  per-call, because it also knows which `{{env/NAME}}` refs that command
  resolved — the two compose, and redacting twice is a no-op."
  (delay (secrets/known-values (into {} (System/getenv)))))

(defn- known-values-for [ctx]
  (if-let [env (:env ctx)]
    (secrets/known-values env)
    @process-known-values))

(defn- redact-result
  "The model-bound strings of a result, with secrets replaced.

  `:result` is what the branch reads. `:artifact` and `:failure` are journal
  rows AND model-visible — the settled-state ledger renders a claim back into
  every later turn, and the failure log the same — so RFC-003's invariant (no
  path from env to model, messages or journal without passing redact) reaches
  them too. Nothing else in the envelope carries free text."
  [r known]
  (let [scrub (fn [v] (if (string? v) (secrets/redact v known) v))
        scrub-map (fn [m] (when m (into {} (map (fn [[k v]] [k (scrub v)])) m)))]
    (cond-> r
      (string? (:result r)) (update :result secrets/redact known)
      (map? (:artifact r)) (update :artifact scrub-map)
      (map? (:failure r)) (update :failure scrub-map))))

(defn- envelope-fault
  "Why `r` cannot be used as a tool result, as a sentence — or nil."
  [r]
  (cond
    (not (map? r))
    (str "returned " (if (nil? r) "nil" (str "a " (.getSimpleName (class r))))
         " instead of a result map")

    (not (contains? r :result))
    "returned a result map with no :result for the model to read"

    (nil? (:branch r))
    "returned a result map with no :branch, so the turn had no branch to carry forward"))

(defn run-tool
  "Dispatch one tool call and return a result the loop can always use.

  The multimethod is still `base/run-tool` and is still resolved HERE, at call
  time, so redefining a tool against a running process keeps working — that is
  what RFC-008 says the multimethod is for, and a wrapper that captured the
  method would have quietly cost it.

  Three things happen to what comes back, and all three are about making the
  next step's job possible rather than about the tool:

  1. A throw becomes a result. A tool that explodes costs the turn, not the
     branch, and the loop gets an envelope like any other.
  2. A malformed envelope becomes a `:mechanics` complaint naming the tool.
     `:mechanics` and not `:failure`: the branch produced no claim and tested
     nothing, so there is no evidence here about its line of inquiry — the
     same reasoning `base/malformed` makes, and the fifth-place-it-turned-up
     mistake it exists to prevent.
  3. The model-bound strings are redacted. See the note above the delay."
  [{:keys [branch tool-name] :as ctx}]
  (let [known (known-values-for ctx)
        outcome (try {:ok (base/run-tool ctx)}
                     (catch Throwable e {:threw e}))]
    (if-let [e (:threw outcome)]
      (do (log/warn "tool" tool-name "threw:" (ex-message e))
          (redact-result
           (base/malformed branch (str "`" tool-name "` failed: " (ex-message e)))
           known))
      (let [r (:ok outcome)]
        (if-let [fault (envelope-fault r)]
          (do (log/error "tool" tool-name fault)
              (redact-result
               (base/malformed
                branch
                (str "`" tool-name "` " fault
                     ". This is a harness fault, not yours — the call was fine."))
               known))
          (redact-result r known))))))

;; Re-exports: loop.clj and the tests reach the tool surface through this
;; namespace and keep working unchanged.
(def arg base/arg)
(def ok base/ok)
(def fail base/fail)
(def malformed base/malformed)
(def missing base/missing)
(def unavailable base/unavailable)
(def phase-refusal base/phase-refusal)
(def tool-names base/tool-names)
(def answer-tokens ship/answer-tokens)
(def uncovered-tokens ship/uncovered-tokens)
