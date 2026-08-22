;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.agent.tools.introspect
  "Self-reflection, read-only: see the loop you are running in.

  Two renderings, both bounded:

    WIRING  the loop graph from the manifest — every node, its cell, the
            cell's effects, and its outgoing edge or dispatch table. The
            same data reload_cells validates an edit against.

    HEALTH  this run so far, from the turns table — the last few turns
            (turn, tool, category) and simple tallies.

  A separate namespace requiring only base + the read seams, so the tool
  surface grows by a plug-in file rather than by editing the aggregator.
  Render fns are exposed (not private) so a test can call them directly."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [samizdat.agent.tools.base :as base]
            [samizdat.cells :as cells]
            [samizdat.store.journal :as journal]))

(defn loop-def
  "The loop's workflow definition — :cells (node -> cell-id), :edges
  (node -> next node or dispatch map), :dispatches. Read from the manifest
  resource for the same reason reload_cells does: no loop-driver cycle."
  []
  (edn/read-string (slurp (io/resource "manifests/loop.edn"))))

(defn cell-effects
  "A cell's effects, as the cells tool renders them: 'pure', the sorted
  effect set, or 'undeclared'. 'not-loaded' when the registry has no such
  cell, so a stale manifest degrades to a note rather than a throw."
  [id]
  (if-let [spec (cell/get-cell id)]
    (cond (cell/pure? spec) "pure"
          (seq (cell/effects spec))
          (str/join "," (map name (sort (cell/effects spec))))
          :else "undeclared")
    "not-loaded"))

(defn edge-str
  "One node's outgoing wiring: a single successor name, or the dispatch
  table rendered as decision->node, pipe-separated."
  [edge]
  (if (map? edge)
    (str/join " | " (for [[d n] (sort-by key edge)]
                      (str (name d) "->" (name n))))
    (name edge)))

(defn render-wiring
  "The whole path a turn takes: for each node in the manifest, its cell,
  that cell's effects, and where the node routes next. Bounded by the size
  of the manifest itself."
  ([] (render-wiring (loop-def)))
  ([def]
   (str/join "\n"
             (for [[node cell-id] (sort-by key (:cells def))]
               (str (name node) " = " (name cell-id)
                    "  [" (cell-effects cell-id) "]"
                    "  -> " (edge-str (get (:edges def) node)))))))

(defn render-recent
  "The last n turns as one line each: turn, tool, category. The compact
  tail of the run, not the whole log."
  ([rows] (render-recent rows 8))
  ([rows n]
   (if (empty? rows)
     "(no turns recorded yet)"
     (str/join "\n"
               (for [r (take-last n rows)]
                 (str (:turn r) "  " (:tool_name r "?")
                      "  " (some-> (:category r) name)))))))

(defn render-health
  "A compact snapshot of a run: tallies over every turn row, then the
  recent tail. rows are turn maps with :turn :tool_name :category
  :parse_error; max-turns may be absent, in which case only the count is
  shown."
  ([rows] (render-health rows nil))
  ([rows max-turns]
   (let [n (count rows)
         parse-errors (count (filter :parse_error rows))
         failures (count (filter #(= "failure" (some-> (:category %) name)) rows))
         head (str "turns: " n (when max-turns (str " of " max-turns))
                   " | failed calls: " failures
                   " | parse errors: " parse-errors)]
     (str head "\n\nrecent:\n" (render-recent rows)))))

(defmethod base/run-tool "introspect" [{:keys [branch conn run-id max-turns]}]
  (base/ok branch
           (str "=== LOOP WIRING (manifests/loop.edn) ===\n\n"
                (render-wiring)
                "\n\n=== RUN HEALTH ===\n\n"
                (if conn
                  (render-health
                   (map #(select-keys % [:turn :tool_name :category :parse_error])
                        (journal/turns conn run-id))
                   max-turns)
                  "(no run database in this context — wiring only)"))))
