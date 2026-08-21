;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.prompt-test
  "The prompt-to-dispatch contract.

  The two directions drift independently: a tool dispatched but undocumented
  is invisible to the model, and a tool documented but not dispatched burns
  turns on the :default method while the model reads the failure as its own
  mistake. Both are asserted so neither survives an edit."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [samizdat.agent.loop :as loop]
            [samizdat.agent.tools :as tools]))

(deftest every-tool-is-documented
  (let [prompt (loop/system-prompt)
        undocumented (remove #(str/includes? prompt %) (tools/tool-names))]
    (is (empty? undocumented)
        (str "these tools are dispatched by run-tool but never mentioned in the"
             " prompt, so the model cannot call them: "
             (str/join ", " undocumented)))))

(deftest every-documented-tool-exists
  ;; The opposite drift, which is worse in one way: the model spends turns
  ;; calling something that lands on the :default method, and reads the failure
  ;; as its own mistake.
  (let [prompt (loop/system-prompt)
        known (set (tools/tool-names))
        ;; Names in the prompt are written as `name({args})`.
        mentioned (map second (re-seq #"(?m)^(\w+)\(\{?" prompt))
        phantom (remove known mentioned)]
    (is (empty? phantom)
        (str "the prompt documents tools that run-tool does not dispatch: "
             (str/join ", " phantom)))))

(deftest no-unsubstituted-placeholders
  ;; The prompt is assembled from a template; a `{{...}}` reaching the model
  ;; means an edit broke the substitution seam.
  (let [prompt (loop/system-prompt)]
    (is (not (re-find #"\{\{[^}]+\}\}" prompt)))))
