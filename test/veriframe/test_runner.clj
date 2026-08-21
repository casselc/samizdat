;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.test-runner
  "jolt -M:test — run every test namespace and exit non-zero on failure.

  Also callable from a connected editor as (veriframe.test-runner/run) so the
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
            [veriframe.engine-test]
            [veriframe.agent-test]
            [veriframe.llm-test]
            [veriframe.pool-test]
            [veriframe.prompt-test]
            [veriframe.server-test]
            [veriframe.store-test]
            [veriframe.gui-api-test]
            [veriframe.gui-graph-test]
            [veriframe.gui-style-test]
            [veriframe.gui-input-test]
            [veriframe.gui-mathtext-test]
            [veriframe.gui-newrun-test]
            [veriframe.faithful-test]))

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
    veriframe.faithful-test
    veriframe.store-test
    veriframe.llm-test
    veriframe.agent-test
    veriframe.pool-test
    veriframe.prompt-test
    veriframe.server-test
    veriframe.gui-api-test
    veriframe.gui-graph-test
    veriframe.gui-style-test
    veriframe.gui-input-test
    veriframe.gui-mathtext-test
    veriframe.gui-newrun-test
    veriframe.engine-test])

(defn run []
  (apply t/run-tests namespaces))

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (run)]
    (println)
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
