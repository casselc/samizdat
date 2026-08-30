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

(ns samizdat.agent.surface
  "The EFFECTIVE model-visible surface of one branch, as a value.

  THE INVARIANT THIS NAMESPACE EXISTS FOR: the model is never instructed to
  call a tool it does not have.

  JS1 M4 attempt 1 violated that invariant and paid for it. The bounded lane
  authorizes exactly `eval`, `doc`, `complete`, `done` plus semantic
  operations reachable only INSIDE eval — but the per-turn context block was
  assembled from the ordinary loop's parts and injected `task-none`, whose
  prose tells the model to run `task create` or `task claim`. Two different
  models chased that non-existent tool across at least nine turns of a
  120-turn run (attempt-1 finding F-1). The same class of bug is latent in the
  gate table, where a gate carries a `:tool` that the arbiter prefills into the
  assistant turn: a bounded branch could be handed `{\"name\": \"give_up\"` for
  a tool outside its catalog.

  The fix is structural rather than one conditional at the one site that was
  observed to be wrong. Everything model-visible is derived from, or filtered
  against, ONE description of what the branch may actually call. A part that
  wants to speak about a tool declares which tools it needs; a part whose
  needs the surface cannot meet does not reach the model at all.

  This namespace deliberately knows nothing about SCI. It reads the plain
  durable binding map, so it loads on the ordinary path with no evaluator
  and no sandbox behind it."
  (:require [clojure.string :as str]))

(def bounded-top-level-tools
  "The bounded lane's top-level callables, in the order they are documented.

  THE one definition. samizdat.evaluator's trusted orientation renders this
  list, the context assembly filters against it, and the conformance tests
  assert over it, so the three cannot disagree — which is exactly how the
  orientation and the context block came to describe different surfaces."
  ["eval" "doc" "complete" "done"])

(def semantic-operation-order
  "The semantic operations, in documentation order. These are ordinary Clojure
  calls INSIDE eval and are never top-level tool names — the distinction
  attempt 1's agent got wrong five times, calling `project/read` and
  `project/stat` as though they were tools.

  Order is observe, then mutate, then execute: it is the order the
  orientation documents them in and the order a turn that is going well
  spends them in."
  [:project/read :project/list :project/search :project/stat :project/edit
   :project/run])

(defn operation-name
  "The in-eval callable text for a semantic operation id: `project/read`."
  [op]
  (str "project/" (name op)))

;; ═══════════════════════════════════════════════════════════════════════════
;; The surface value.
;; ═══════════════════════════════════════════════════════════════════════════

(defn of-binding
  "The effective surface a durable bounded binding describes, or nil.

  `binding` is the plain persisted map — no SCI, no live context. The
  capabilities come from the binding's own ContextSpec, so a controller that
  narrowed a develop binding to read-only produces a surface with no mutation
  in it and every derived sentence follows."
  [binding]
  (when binding
    (let [caps (set (get-in binding [:spec :context-spec :context/capabilities]))
          ops (filterv caps semantic-operation-order)]
      {:bounded? true
       :top-level (vec bounded-top-level-tools)
       :operations ops
       :operation-names (mapv operation-name ops)
       :capabilities caps})))

(defn ordinary
  "The effective surface of an ordinary (unbounded) branch: every dispatchable
  tool, and no in-eval semantic operations. `tool-names` is injected rather
  than required so this namespace stays free of the tool multimethod."
  [tool-names]
  {:bounded? false
   :top-level (vec (sort tool-names))
   :operations []
   :operation-names []
   :capabilities #{}})

(defn callable?
  "Whether `tool-name` is a top-level callable on this surface."
  [surface tool-name]
  (boolean (and surface tool-name
                (some #{(str tool-name)} (:top-level surface)))))

(defn satisfies-needs?
  "Whether every tool in `needs` is callable on this surface. A part with no
  needs is always satisfiable — the ledger and the failure log speak about
  evidence, not about tools."
  [surface needs]
  (every? #(callable? surface %) needs))

;; ═══════════════════════════════════════════════════════════════════════════
;; The audit: what a candidate model-bound string would tell the model to call.
;; ═══════════════════════════════════════════════════════════════════════════

(def multiword-tool-forms
  "Tool invocations whose prose form is more than one token, mapped to the tool
  they name. `task-none.md` says \"task create\" and \"task claim\"; a scan for
  the bare word `task` would fire on any sentence about tasks, and a scan for
  `task_create` would have missed the actual text."
  {"task create" "task"
   "task claim" "task"})

(defn- word-mentions?
  "Whether `text` names `tool` as a call rather than as an English word.

  Matched shapes are the ones the prompts actually use: a backticked name, a
  name followed by an open paren, a name inside a tool-call fence, and a
  snake_case or slashed name anywhere (those are never English)."
  [text tool]
  (let [t (str tool)
        q (java.util.regex.Pattern/quote t)]
    (boolean
     (or (and (or (str/includes? t "_") (str/includes? t "/"))
              (re-find (re-pattern (str "(?i)\\b" q "\\b")) text))
         (re-find (re-pattern (str "(?i)`" q "[`(]")) text)
         (re-find (re-pattern (str "(?i)\\b" q "\\(")) text)
         (re-find (re-pattern (str "(?i)\"name\"\\s*:\\s*\"" q "\"")) text)))))

(defn unavailable-mentions
  "Every tool in `universe` that `text` instructs the model to call and that
  `surface` does not have, sorted.

  This is the audit behind the regression tests: it is run over the ACTUAL
  assembled system message and per-turn context of a bounded branch, not over
  the prompt resources in isolation, because attempt 1's violation was
  introduced by assembly and every individual resource was fine on its own."
  [surface universe text]
  (let [text (str text)]
    (vec
     (sort
      (into (set (for [[form tool] multiword-tool-forms
                       :when (and (str/includes? (str/lower-case text)
                                                 (str/lower-case form))
                                  (not (callable? surface tool)))]
                   tool))
            (for [tool universe
                  :let [t (str tool)]
                  :when (and (not (callable? surface t))
                             (word-mentions? text t))]
              t))))))

(defn audit
  "nil when `text` is safe for this surface, else a description of what it
  would have told the model to call. Shaped for an assertion message."
  [surface universe text]
  (when-let [bad (seq (unavailable-mentions surface universe text))]
    {:surface/unavailable (vec bad)
     :surface/top-level (:top-level surface)
     :surface/operations (:operation-names surface)}))
