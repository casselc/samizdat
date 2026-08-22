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

(ns samizdat.store.workflows
  "Workflow definitions as durable, versioned rows.

  Append-only on purpose: every save! is a new version, a rollback is a
  pointer to an older row, and the edit history — including every edit the
  agent makes to its own loop — stays readable in one query. This is the
  hivemind version-bump pattern applied to the harness's own behavior."
  (:require [clojure.java.io :as io]
            [samizdat.store.db :as db]))

(defn load-latest
  "The newest version of the named workflow, or nil."
  [conn name]
  (db/fetch-one conn ["SELECT * FROM workflows WHERE name = ?
                       ORDER BY version DESC LIMIT 1" name]))

(defn load-version [conn name version]
  (db/fetch-one conn ["SELECT * FROM workflows WHERE name = ? AND version = ?"
                      name version]))

(defn versions [conn name]
  (db/fetch conn ["SELECT version, created_at FROM workflows
                   WHERE name = ? ORDER BY version" name]))

(defn save!
  "Append the EDN text as the next version of the named workflow. Returns the
  new version number."
  [conn name edn-text]
  (db/with-writer
    (let [v (inc (or (:version (load-latest conn name)) 0))]
      (db/execute! conn ["INSERT INTO workflows (name, version, edn, created_at)
                          VALUES (?, ?, ?, ?)"
                         name v edn-text (db/now)])
      v)))

(defn seed!
  "Install the resource at `resource-path` as version 1 of the named workflow
  when no version exists yet. Returns the latest row either way, so callers
  can seed-and-load in one motion."
  [conn name resource-path]
  (when-not (load-latest conn name)
    (save! conn name (slurp (io/resource resource-path))))
  (load-latest conn name))
