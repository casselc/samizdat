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
            [samizdat.agent.supervisor :as supervisor]
            [samizdat.prompt :as sp]))

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
  (sp/prompt name))

(defn- fired-count [branch gate]
  (count (filter #(= gate (:gate %)) (:gate-history branch))))



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

(defn- compile-form
  "Compile an EDN form into (fn [ctx] form) with the gate-context keys bound
  as plain locals — the environment both :when and :message-form build on.
  prompt/threshold/state and the required namespaces resolve at compile, in
  this namespace; the config atom is still read at FIRE time."
  [form]
  (eval `(fn [~'ctx]
           (let [~'directive            (get ~'ctx :directive)
                 ~'done-block           (get ~'ctx :done-block)
                 ~'branch               (get ~'ctx :branch)
                 ~'max-turns            (get ~'ctx :max-turns)
                 ~'branch-count         (get ~'ctx :branch-count)
                 ~'safe-state-coverage  (get ~'ctx :safe-state-coverage)]
             ~form))))

(defn- compile-when
  [form]
  (compile-form form))

(defn- compile-message
  "A prompts/ file plus an optional suffix, selmer-rendered at fire time —
  the same {{...}} convention and the same engine (samizdat.prompt) as
  every other prompt seam."
  [{:keys [message-file message-suffix]}]
  (fn [{:keys [branch max-turns]}]
    (let [ctx {:turn-count (state/turn-count branch) :max-turns max-turns}]
      (str (some-> message-file sp/prompt (sp/render-str ctx))
           (some-> message-suffix (sp/render-str ctx))))))

(defn- compile-gate
  [entry]
  (assoc entry
         :when (compile-when (:when entry))
         :message (if (:message-form entry)
                    (compile-form (:message-form entry))
                    (compile-message entry))
         :prediction (let [p (:prediction entry)] (fn [_] p))))

(def gates
  "The steer table, compiled from gates.edn :gates at load — all data since
  tier 3b. Priorities, not table order, decide arbitration."
  (mapv compile-gate (:gates (config))))

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
