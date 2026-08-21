;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

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
