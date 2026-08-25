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

(ns samizdat.store.userspace
  "The userspace layer as durable, versioned, PER-PROJECT rows.

  The harness ships a userspace TEMPLATE in resources/: the cells, the
  manifests, the policy tables, the prompts. This table is a project's own
  copy of it. A project seeds from the template on first use and evolves from
  there — every edit the supervisor makes is a new version here, in this
  project's database, and the shipped files are never written.

  That is what makes two projects running the same harness able to diverge in
  how they work, which is the point of the base/userspace split: src/ is
  capability, userspace is the loop that assembles capabilities, and the loop
  belongs to the project rather than to the harness.

  Four kinds, one lifecycle — seed, load latest, append a version, roll back
  by pointing at an older row:

    :cell      Clojure source, load-stringed into the live image
    :manifest  EDN, a mycelium workflow definition
    :policy    EDN, a table of thresholds/rules (gates, phases, retention)
    :prompt    markdown, text the model reads

  `body` is TEXT for all of them. The kind says how to read it; nothing here
  parses it, which is what keeps this namespace a store rather than a loader.

  APPEND-ONLY, never an UPDATE. The edit history of a system that rewrites
  itself is the most valuable thing in its database: it is how a surprising
  run is explained, and how a bad edit is undone without a backup."
  (:require [clojure.string :as str]
            [samizdat.store.db :as db]))

(def kinds
  "The kinds this store holds. Enumerated so a typo'd kind is a loud failure
  rather than a row nobody will ever read again."
  #{:cell :manifest :policy :prompt})

(defn- kind-str [kind]
  (let [k (if (keyword? kind) (name kind) (str kind))]
    (when-not (contains? kinds (keyword k))
      (throw (ex-info (str "unknown userspace kind " (pr-str kind)
                           " — expected one of " (str/join ", " (sort (map name kinds))))
                      {:kind kind})))
    k))

(defn load-latest
  "The newest version of `name` at `kind`, or nil."
  [conn kind name]
  (db/fetch-one conn ["SELECT * FROM userspace
                       WHERE kind = ? AND name = ?
                       ORDER BY version DESC LIMIT 1"
                      (kind-str kind) (str name)]))

(defn load-version
  [conn kind name version]
  (db/fetch-one conn ["SELECT * FROM userspace
                       WHERE kind = ? AND name = ? AND version = ?"
                      (kind-str kind) (str name) (long version)]))

(defn versions
  "Every version of `name`, oldest first — the edit history of one piece of
  userspace."
  [conn kind name]
  (db/fetch conn ["SELECT version, created_at FROM userspace
                   WHERE kind = ? AND name = ? ORDER BY version"
                  (kind-str kind) (str name)]))

(defn save!
  "Append `body` as the next version of `name` at `kind`. Returns the new
  version number.

  No content comparison: saving a body identical to the current version still
  appends. An edit that turned out to be a no-op is itself a fact about what
  the supervisor tried, and suppressing it would make the history lie by
  omission."
  [conn kind name body]
  (let [k (kind-str kind)]
    (db/with-writer
      (let [v (inc (or (:version (load-latest conn k name)) 0))]
        (db/execute! conn ["INSERT INTO userspace (kind, name, version, body, created_at)
                            VALUES (?, ?, ?, ?, ?)"
                           k (str name) v (str body) (db/now)])
        v))))

(defn seed!
  "Install `body` as version 1 of `name` when the project has no version yet.
  Returns the latest row either way, so a caller can seed-and-load in one
  motion.

  Idempotent by construction and cheap enough to call on every load: seeding
  is how a project acquires its copy of the template, and the only way to be
  sure it has one is to try. A project that has already evolved past version 1
  is untouched — the template never overwrites what the supervisor has done."
  [conn kind name body]
  (when-not (load-latest conn kind name)
    (save! conn kind name body))
  (load-latest conn kind name))

(defn revert!
  "Re-append the body of `version` as a NEW latest version — the rollback.

  Not a delete and not a pointer move: the failed edit stays in the history
  where it can be read, and the revert is itself an edit. Returns the new
  version number, or nil when the named version does not exist."
  [conn kind name version]
  (when-let [row (load-version conn kind name version)]
    (save! conn kind name (:body row))))

(defn names
  "Every name at `kind`, with its latest version and how many versions it
  has — the catalogue a tool lists."
  [conn kind]
  (db/fetch conn ["SELECT name, MAX(version) AS version, COUNT(*) AS versions
                   FROM userspace WHERE kind = ? GROUP BY name ORDER BY name"
                  (kind-str kind)]))

(defn latest-bodies
  "{name body} for every name at `kind`, at its newest version — one query for
  a loader that needs the whole kind (the cell loader does).

  The correlated MAX rather than a GROUP BY on the row: SQLite would let a
  bare GROUP BY pick any row's body, and picking the wrong version of a cell
  is the kind of bug that looks like the supervisor's edit silently not
  taking."
  [conn kind]
  (let [k (kind-str kind)]
    (into {}
          (map (juxt :name :body))
          (db/fetch conn
                    ["SELECT u.name, u.body FROM userspace u
                      WHERE u.kind = ?
                        AND u.version = (SELECT MAX(v.version) FROM userspace v
                                         WHERE v.kind = u.kind AND v.name = u.name)
                      ORDER BY u.name"
                     k]))))
