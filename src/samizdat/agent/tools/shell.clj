;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.agent.tools.shell
  "The shell tool.

  One call into samizdat.security.policy, which owns the permission engine,
  the scrubbed environment, and output redaction. See
  samizdat.agent.tools.base for the shared result helpers and the run-tool
  multimethod."
  (:require [samizdat.agent.tools.base :as base]
            [samizdat.security.policy :as policy]))

(defmethod base/run-tool "shell" [{:keys [branch] :as ctx}]
  ;; Every command faces the permission engine, runs under a scrubbed
  ;; environment, and its output is redacted before it returns — one call into
  ;; samizdat.security.policy, which owns all three. A denied or unapproved
  ;; command never spawns. The result's :category is what the cull guard reads:
  ;; a policy refusal is :neutral (the branch did nothing wrong, the harness
  ;; declined), a real command failure is :failure.
  (if-let [m (base/missing ctx :command)]
    (base/malformed branch m)
    (let [r (policy/run-shell ctx)]
      (assoc r :branch branch
             ;; A policy refusal is journalled as declined, like a phase
             ;; refusal, so the record can tell it from a command that ran
             ;; and failed.
             :policy-refusal? (contains? #{:deny :ask} (get-in r [:policy :effect]))))))
