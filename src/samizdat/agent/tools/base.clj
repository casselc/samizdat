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
  require this namespace; nothing here requires a group back."
  (:require [clojure.string :as str]
            [samizdat.agent.phases :as phases]))

(defmulti run-tool
  (fn [ctx] (:tool-name ctx)))

(defn ok [branch result & {:as extra}]
  (merge {:result result :category :neutral :progress? false :branch branch} extra))

(defn fail [branch result & {:as extra}]
  (merge {:result result :category :failure :progress? false :branch branch} extra))

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

  The policy is phases.edn data (drg-4026 #34): each phase's :withholds set.
  Empty today — the proof harness's explore/build policy (withhold Lean until
  a sketch, withhold sketch once building) left with its tool surface — but
  the table is consulted, so the coding loop's phase policy plugs back in as
  a data edit rather than new code. Any refusal returned from here carries
  `:policy-refusal? true` so the cull record can tell a declined call from a
  malformed fence."
  [{:keys [branch tool-name] :as _ctx}]
  (when (contains? (phases/withholds (:phase branch)) tool-name)
    (fail branch
          (str "`" tool-name "` is not available in the "
               (name (:phase branch))
               " phase. The phase-valve message says when that changes.")
          :policy-refusal? true)))


;; --- unknown ----------------------------------------------------------------

(defmethod run-tool :default [{:keys [branch tool-name]}]
  (fail (update-in branch [:mechanics :unknown-tools] inc)
        (str "No tool named `" tool-name "`. Available: "
             (str/join ", " (sort (remove #{:default} (keys (methods run-tool)))))
             ".")))

(defn tool-names []
  (sort (remove keyword? (keys (methods run-tool)))))