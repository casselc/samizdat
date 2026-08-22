;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.agent.tools.repl
  "The REPL tools: eval, doc, complete.

  Evaluating Clojure in the live image is the primary feedback loop; these
  three methods only dispatch it. See samizdat.agent.tools.base for the
  shared result helpers and the run-tool multimethod."
  (:require [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.repl :as repl]))

(defmethod base/run-tool "eval" [{:keys [branch repl-session] :as ctx}]
  ;; Evaluate Clojure in the live harness image. REPL-first development: the
  ;; agent tries a form, sees the value and output, and iterates before
  ;; committing it to a file. :neutral — evaluating establishes nothing on its
  ;; own; a define-and-test is exploration, and progress is the file it leads
  ;; to. Defs persist across evals within a run (the session is per-run).
  (if-let [m (base/missing ctx :code)]
    (base/malformed branch m)
    (let [r (if repl-session
              (repl/eval-code (str (base/arg ctx :code)) repl-session)
              (repl/eval-code (str (base/arg ctx :code))))]
      (if (:ok r)
        (base/ok branch (str "=> " (:value r)
                        (when (seq (:out r)) (str "\n" (:out r)))))
        (base/fail branch (str "Eval error: " (:error r)
                          (when (seq (:out r)) (str "\n" (:out r)))))))))

(defmethod base/run-tool "doc" [{:keys [branch] :as ctx}]
  (if-let [m (base/missing ctx :symbol)]
    (base/malformed branch m)
    (let [d (repl/doc-sym (str (base/arg ctx :symbol)))]
      (if (:not-found d)
        (base/malformed branch (str "No var " (base/arg ctx :symbol) " is loaded."))
        (base/ok branch (str (:name d) "\n" (pr-str (:arglists d)) "\n\n" (:doc d)))))))

(defmethod base/run-tool "complete" [{:keys [branch] :as ctx}]
  (if-let [m (base/missing ctx :prefix)]
    (base/malformed branch m)
    (let [ms (repl/complete (str (base/arg ctx :prefix)))]
      (base/ok branch (if (seq ms)
                   (str/join "\n" (take 50 ms))
                   (str "No symbols match " (base/arg ctx :prefix) "."))))))
