;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.test-runner
  "jolt -M:test — run every test namespace and exit non-zero on failure.

  Also callable from a connected editor as (samizdat.test-runner/run) so the
  suite runs against the live process without paying startup again."
  (:require [clojure.test :as t]
            [maestro.core-test]
            [mycelium.cell-test]
            [mycelium.coercion-test]
            [mycelium.compose-test]
            [mycelium.constraints-test]
            [mycelium.core-test]
            [mycelium.default-transition-test]
            [mycelium.defcell-test]
            [mycelium.dev-test]
            [mycelium.error-groups-test]
            [mycelium.error-handler-test]
            [mycelium.error-messages-test]
            [mycelium.error-taxonomy-test]
            [mycelium.execution-tracing-test]
            [mycelium.fragment-test]
            [mycelium.generate-stubs-test]
            [mycelium.halt-resume-test]
            [mycelium.infer-schema-test]
            [mycelium.input-schema-test]
            [mycelium.integration-test]
            [mycelium.interceptor-test]
            [mycelium.invoke-cell-test]
            [mycelium.join-test]
            [mycelium.lite-schema-test]
            [mycelium.manifest-test]
            [mycelium.middleware-test]
            [mycelium.orchestrate-test]
            [mycelium.propagate-keys-test]
            [mycelium.queue-integration-test]
            [mycelium.queue-test]
            [mycelium.registry-test]
            [mycelium.resilience-test]
            [mycelium.schema-error-test]
            [mycelium.schema-test]
            [mycelium.store-test]
            [mycelium.system-test]
            [mycelium.timeout-test]
            [mycelium.transform-test]
            [mycelium.validate-warn-test]
            [mycelium.validation-test]
            [mycelium.workflow-test]
            [samizdat.agent-test]
            [samizdat.llm-test]
            [samizdat.prompt-test]
            [samizdat.server-test]
            [samizdat.store-test]
            [samizdat.gui-api-test]
            [samizdat.gui-graph-test]
            [samizdat.gui-style-test]
            [samizdat.gui-input-test]
            [samizdat.gui-mathtext-test]
            [samizdat.gui-newrun-test]))

(def namespaces
  '[maestro.core-test
    mycelium.cell-test
    mycelium.coercion-test
    mycelium.compose-test
    mycelium.constraints-test
    mycelium.core-test
    mycelium.default-transition-test
    mycelium.defcell-test
    mycelium.dev-test
    mycelium.error-groups-test
    mycelium.error-handler-test
    mycelium.error-messages-test
    mycelium.error-taxonomy-test
    mycelium.execution-tracing-test
    mycelium.fragment-test
    mycelium.generate-stubs-test
    mycelium.halt-resume-test
    mycelium.infer-schema-test
    mycelium.input-schema-test
    mycelium.integration-test
    mycelium.interceptor-test
    mycelium.invoke-cell-test
    mycelium.join-test
    mycelium.lite-schema-test
    mycelium.manifest-test
    mycelium.middleware-test
    mycelium.orchestrate-test
    mycelium.propagate-keys-test
    mycelium.queue-integration-test
    mycelium.queue-test
    mycelium.registry-test
    mycelium.resilience-test
    mycelium.schema-error-test
    mycelium.schema-test
    mycelium.store-test
    mycelium.system-test
    mycelium.timeout-test
    mycelium.transform-test
    mycelium.validate-warn-test
    mycelium.validation-test
    mycelium.workflow-test
    samizdat.store-test
    samizdat.llm-test
    samizdat.agent-test
    samizdat.prompt-test
    samizdat.server-test
    samizdat.gui-api-test
    samizdat.gui-graph-test
    samizdat.gui-style-test
    samizdat.gui-input-test
    samizdat.gui-mathtext-test
    samizdat.gui-newrun-test])

(defn run []
  (apply t/run-tests namespaces))

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (run)]
    (println)
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
