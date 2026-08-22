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
    (let [timeout (some-> (base/arg ctx :timeout-ms) str str/trim not-empty parse-long)
          r (repl/eval-code (str (base/arg ctx :code)) repl-session timeout)]
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
