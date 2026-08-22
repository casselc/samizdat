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

(ns samizdat.store.grants
  "Session permission grants: the allow-always the human gives when a command
  asks. Scoped to a run, consulted ahead of the base rules, never able to
  override a hard deny.

  The write side (`grant!`) is a HUMAN-ONLY surface — nothing the model emits
  reaches it. That is property 4 of the security model (docs/security.md): the
  model has no edge into the grants table. Enforced structurally by never
  exposing grant! through a tool; the model can only ever produce a
  needs-approval result that a person acts on."
  (:require [samizdat.store.db :as db]))

(defn grant!
  "Record a human's allow-always for `pattern` on this run. Idempotent-ish: a
  duplicate pattern is harmless (matching is set-membership, not counting)."
  [conn run-id pattern]
  (db/with-writer
    (db/execute! conn ["INSERT INTO grants (run_id, pattern, created_at)
                        VALUES (?, ?, ?)"
                       run-id pattern (db/now)]))
  pattern)

(defn for-run
  "The grant patterns active for a run, as the shape the policy engine reads:
  {:grants [pattern ...]}. Read once per decision so a fresh grant is seen on
  the next command without re-plumbing."
  [conn run-id]
  {:grants (mapv :pattern
                 (db/fetch conn ["SELECT pattern FROM grants WHERE run_id = ?
                                  ORDER BY id" run-id]))})
