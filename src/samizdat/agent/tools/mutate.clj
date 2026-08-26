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

(ns samizdat.agent.tools.mutate
  "The self-modification tool: after the agent edits a cell in resources/cells,
  `reload_cells` runs the mutation protocol (checkpoint -> reload -> validate
  -> soak -> commit or rollback). A good edit goes live on the next turn; a bad
  one is rolled back and the reason returned, so the agent can fix it. This is
  how the harness safely changes its own behavior at runtime.

  A separate namespace requiring only base, so it plugs into the tool surface
  without dragging the mutation machinery into the aggregator."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.base :as base]
            [samizdat.cells :as cells]
            [samizdat.mutation :as mutation]
            [samizdat.store.userspace]
            [samizdat.userspace :as userspace]))

(defmethod base/run-tool "cells" [{:keys [branch]}]
  ;; What the loop is made of: the cells currently loaded, their effects, and
  ;; the file each came from — so the agent knows what it can edit and where.
  (base/ok branch
           (str/join "\n"
                     (for [[id {:keys [source]}] (sort-by key (cells/loaded))]
                       (let [spec (cell/get-cell id)
                             fx (cond (cell/pure? spec) "pure"
                                      (seq (cell/effects spec))
                                      (str/join "," (map name (sort (cell/effects spec))))
                                      :else "undeclared")]
                         (str id "  [" fx "]  " source))))))

(defn- current-loop-def
  "The loop's workflow definition — the wiring the edit is validated against.
  Read from the manifest resource rather than the workflow namespace, to keep
  the tool free of the loop-driver dependency cycle."
  []
  (edn/read-string (slurp (io/resource "manifests/loop.edn"))))

(defn- soak-input
  "A synthetic starting state the soak dry-run terminates from: a branch that
  is already done, so the loop routes straight to finish without needing a
  real model call."
  []
  {:branch (assoc (state/new-branch {:id "soak" :problem "soak"})
                  :status :done :final-answer "soak")
   :turn 1})

(defmethod base/run-tool "reload_cells" [{:keys [branch conn run-id]}]
  (let [r (mutation/apply-cell-edit!
           {:loop-def (current-loop-def)
            :soak-input (soak-input)
            :conn conn :run-id run-id})]
    (if (= :committed (:status r))
      (base/ok branch
               (str "Cell edit committed — it is live on your next turn."
                    " (checkpoint -> reload -> validate -> soak all passed.)")
               :progress? true)
      (base/fail branch
                 (str "Cell edit rolled back; the loop is unchanged and your"
                      " file was restored to the last good version.\n\n"
                      (:reason r)
                      "\n\nFix the cell and call reload_cells again.")))))

;; --- the project's own cells (userspace) -------------------------------------

(def ^:private cell-usage
  "Actions: list, show {name, version?}, save {name, clj}, versions {name}, revert {name, version}. A cell is one step of the loop, as Clojure. Save validates by compiling the loop and dry-running it before it stores, and stores a new VERSION in this project — the shipped template is never written.")

(defn- render-versions [name]
  (let [rows (userspace/versions :cell name)]
    (if (seq rows)
      (str/join "\n" (for [{:keys [version created_at]} rows]
                       (str "v" version "  " created_at)))
      (str "No stored versions of '" name "' in this project."
           " It is still the shipped template."))))

(defmethod base/run-tool "cell" [{:keys [branch conn run-id] :as ctx}]
  ;; The project-scoped half of self-modification. `cells` lists what is
  ;; loaded; this edits it. Every save is a new version in THIS project's
  ;; userspace store, seeded from the harness's template on first read — so a
  ;; loop this project evolves is its own, and no other project sees it.
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)
        name (some-> (base/arg ctx :name) str str/trim not-empty)]
    (try
      (case action
        nil
        (base/malformed branch (str "`cell` needs an `action`. " cell-usage))

        "list"
        (let [rows (userspace/names :cell)]
          (base/ok branch
                   (if (seq rows)
                     (str/join "\n" (for [{:keys [name version versions]} rows]
                                      (str name "  v" version " (" versions
                                           (if (= 1 versions) " version)" " versions)"))))
                     (str "This project has stored no cell versions yet — it is"
                          " running the shipped templates. Any save starts its"
                          " own copy."))))

        "show"
        (if-not name
          (base/malformed branch (base/missing ctx :name))
          (let [v (some-> (base/arg ctx :version) str str/trim not-empty parse-long)
                body (if v
                       (some-> (userspace/conn)
                               (samizdat.store.userspace/load-version :cell name v)
                               :body)
                       (userspace/body :cell name))]
            (if body
              (base/ok branch (str name (when v (str " v" v)) ":\n\n" body))
              (base/malformed branch (str "No cell '" name "'"
                                          (when v (str " v" v)) "."
                                          " `cell list` shows this project's;"
                                          " `cells` shows what is loaded.")))))

        "versions"
        (if-not name
          (base/malformed branch (base/missing ctx :name))
          (base/ok branch (render-versions name)))

        "save"
        (let [body (base/arg ctx :clj)]
          (cond
            (not name) (base/malformed branch (base/missing ctx :name))
            (str/blank? (str body)) (base/malformed branch (base/missing ctx :clj))
            :else
            (let [r (mutation/propose-cell!
                     {:name name :body (str body)
                      :loop-def (current-loop-def)
                      :soak-input (soak-input)
                      :conn conn :run-id run-id})]
              (if (= :committed (:status r))
                (base/ok branch
                         (str "Saved cell '" name "' as v" (:version r)
                              " in this project — it compiled, it dry-ran, and it"
                              " is live on your next turn. The shipped template is"
                              " unchanged; other projects still start from it.")
                         :progress? true)
                (base/fail branch
                           (str "Cell '" name "' was NOT saved; the loop is"
                                " unchanged and nothing entered this project's"
                                " history.\n\n" (:reason r)
                                "\n\nFix it and save again."))))))

        "revert"
        (let [v (some-> (base/arg ctx :version) str str/trim not-empty parse-long)]
          (cond
            (not name) (base/malformed branch (base/missing ctx :name))
            (nil? v) (base/malformed branch (base/missing ctx :version))
            :else
            (if-let [nv (userspace/revert! :cell name v)]
              (base/ok branch
                       (str "Reverted cell '" name "' to the body of v" v
                            ", stored as v" nv ". Reverting is itself an edit, so"
                            " the version you left behind is still readable."
                            " Call reload_cells to make it live.")
                       :progress? true)
              (base/malformed branch (str "No v" v " of cell '" name
                                          "' in this project. " (render-versions name))))))

        (base/malformed branch (str "Unknown cell action `" action "`. " cell-usage)))
      (catch Throwable e
        (base/fail branch (str "`cell " action "` refused: " (ex-message e)
                               "\n\n" cell-usage))))))
