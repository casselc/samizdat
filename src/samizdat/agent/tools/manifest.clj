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

(ns samizdat.agent.tools.manifest
  "Manifest management. The agentic loop is a named, versioned workflow in the
  workflows table, and there can be many of them — the factory `loop` beside a
  more sophisticated one. This tool lists them, shows one, and saves a tuned or
  brand-new manifest. A save must COMPILE the way the loader will before it is
  stored, so a manifest that cannot run cannot be saved.

  Which manifest a run drives is chosen by config (:run :loop / HARNESS_LOOP /
  a project's .samizdat/config.edn). Tuning the active manifest is picked up on
  the next run, because the loader loads the latest stored version. Validation
  goes through mycelium + the cell registry directly rather than the workflow
  loader, to keep this tool out of the loop-driver's require graph."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mycelium.compose :as compose]
            [mycelium.core :as myc]
            [samizdat.agent.tools.base :as base]
            [samizdat.cells :as cells]
            [samizdat.store.workflows :as workflows]))

(defn- register-subworkflows! [definition]
  ;; Mirrors samizdat.workflow/register-subworkflows! — kept here rather than
  ;; required, to keep this tool out of the loop-driver's cycle.
  (doseq [[cell-id mname] (:subworkflows definition)]
    (let [res (str "manifests/" mname ".edn")]
      (when-not (io/resource res)
        (throw (ex-info (str "sub-workflow manifest '" mname "' has no resource")
                        {:manifest mname})))
      (compose/register-workflow-cell!
       cell-id (edn/read-string (slurp (io/resource res))) {}))))

(defn- validate!
  "Compile the definition the way load-loop! will — cells loaded, composed
  sub-loops registered, then a full static pre-compile. Throws on any error."
  [edn-text]
  (cells/load-cells!)
  (let [definition (edn/read-string edn-text)]
    (register-subworkflows! definition)
    (myc/pre-compile definition))
  true)

(def ^:private usage
  "Actions: list, show {name, version?}, save {name, edn}. A manifest is the loop as data — a :cells map, :edges, and dispatch predicates. Save validates by compiling before it stores; the run that uses it is chosen by config :run :loop.")

(defn- render-list [conn]
  (let [rows (workflows/names conn)]
    (if (seq rows)
      (str/join "\n"
                (for [{:keys [name version versions]} rows]
                  (str name "  v" version " (" versions
                       (if (= 1 versions) " version)" " versions)")
                       (when (io/resource (str "manifests/" name ".edn"))
                         "  [factory]"))))
      "No manifests stored yet.")))

(defmethod base/run-tool "manifest" [{:keys [branch conn] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)]
    (try
      (case action
        nil
        (base/malformed branch (str "`manifest` needs an `action`. " usage))

        "list"
        (base/ok branch (render-list conn))

        "show"
        (let [name (base/arg ctx :name)
              v (some-> (base/arg ctx :version) str str/trim not-empty parse-long)]
          (cond
            (str/blank? (str name)) (base/missing branch :name)
            :else
            (if-let [row (if v
                           (workflows/load-version conn name v)
                           (workflows/load-latest conn name))]
              (base/ok branch (str name " v" (:version row) ":\n\n" (:edn row)))
              (base/malformed branch (str "No manifest " name
                                          (when v (str " v" v)) ".")))))

        "save"
        (let [name (base/arg ctx :name)
              edn-text (base/arg ctx :edn)]
          (cond
            (str/blank? (str name)) (base/missing branch :name)
            (str/blank? (str edn-text)) (base/missing branch :edn)
            :else
            (do (validate! edn-text)
                (let [v (workflows/save! conn name edn-text)]
                  (base/ok branch
                           (str "Saved manifest '" name "' v" v " — it compiles."
                                " A run configured for '" name "' (config :run :loop)"
                                " will use it; tuning the active manifest is picked"
                                " up on the next run.")
                           :progress? true)))))

        (base/malformed branch (str "Unknown manifest action `" action "`. " usage)))
      (catch Throwable e
        (base/fail branch (str "`manifest " action "` refused: " (ex-message e)
                               "\n\n" usage))))))
