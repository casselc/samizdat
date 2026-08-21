;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.config :as config]))

(deftest glm-uses-the-coding-endpoint
  ;; Aligned with the config dirge drives GLM through: the coding endpoint,
  ;; glm-5.3, low temperature. The coding /models listing advertises the base
  ;; models (glm-4.5/4.6), not the coding alias, but chat accepts glm-5.3.
  (let [cfg (config/load-config {:llm {:provider :glm}})
        {:keys [base-url model temperature]} (:llm cfg)]
    (testing "the provider defaults resolve to dirge's working GLM config"
      ;; provider defaults are read by key-env detection; assert the static
      ;; provider table rather than a live-env-dependent selection.
      (is (= "https://open.bigmodel.cn/api/coding/paas/v4"
             (get-in config/providers-for-test [:glm :base-url])))
      (is (= "glm-5.3" (get-in config/providers-for-test [:glm :model])))
      (is (= 0.2 (get-in config/providers-for-test [:glm :temperature]))))))

(deftest provider-temperature-wins-over-family-default
  ;; A provider that pins a temperature (GLM's 0.2) beats the 0.7 family
  ;; default; a provider that doesn't falls back to 0.7.
  (is (= 0.2 (config/provider-temperature :glm)))
  (is (= 0.7 (config/provider-temperature :deepseek))))
