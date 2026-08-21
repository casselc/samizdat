;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

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
