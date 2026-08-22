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
            [samizdat.mutation :as mutation]))

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
