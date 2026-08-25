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
  "Manifest definitions — a THIN SHIM over samizdat.store.userspace.

  Manifests were the one userspace layer that already worked the right way:
  seeded from the shipped template into the project's db, versioned
  append-only, and rewritten by the agent without touching the harness. The
  userspace table generalised that to cells, policy tables and prompts, so
  manifests moved onto it (migration v11 copies every existing row across).

  This namespace stays because samizdat.workflow and the manifest tool are
  written against it, and because `name` alone is the right signature for a
  caller that only ever deals in manifests. Every function here is
  `(userspace/... :manifest ...)` with the rows shaped as they were — `:edn`
  rather than `:body` — so nothing above it had to change.

  New code should prefer samizdat.userspace, which reads the project's version
  or seeds the template without the caller knowing which happened."
  (:require [clojure.java.io :as io]
            [samizdat.store.userspace :as us]))

(defn- ->row
  "A userspace row in the shape this namespace has always returned: the body
  under :edn, because every caller destructures it that way."
  [row]
  (when row
    (-> row (assoc :edn (:body row)) (dissoc :body :kind))))

(defn load-latest
  "The newest version of the named manifest, or nil."
  [conn name]
  (->row (us/load-latest conn :manifest name)))

(defn load-version [conn name version]
  (->row (us/load-version conn :manifest name version)))

(defn versions [conn name]
  (us/versions conn :manifest name))

(defn save!
  "Append the EDN text as the next version of the named manifest. Returns the
  new version number."
  [conn name edn-text]
  (us/save! conn :manifest name edn-text))

(defn seed!
  "Install the resource at `resource-path` as version 1 of the named manifest
  when the project has no version yet. Returns the latest row either way, so
  callers can seed-and-load in one motion."
  [conn name resource-path]
  (->row (us/seed! conn :manifest name (slurp (io/resource resource-path)))))

(defn names
  "Every manifest name the project holds, with its latest version and how many
  versions it has — the catalogue the manifest tool lists."
  [conn]
  (us/names conn :manifest))
