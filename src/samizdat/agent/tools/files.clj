;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.agent.tools.files
  "The file tools: read_file, write_file, edit_file, grep.

  read/write/edit are thin dispatchers over samizdat.agent.files; grep is
  the project search. See samizdat.agent.tools.base for the shared result
  helpers and the run-tool multimethod."
  (:require [clojure.string :as str]
            [samizdat.agent.files :as files]
            [samizdat.agent.tools.base :as base]))

(defmethod base/run-tool "read_file" [ctx]
  (files/read-file ctx))

(defmethod base/run-tool "write_file" [ctx]
  (files/write-file ctx))

(defmethod base/run-tool "edit_file" [ctx]
  (files/edit-file ctx))

(defmethod base/run-tool "grep" [{:keys [branch root] :as ctx}]
  ;; Search the project's Clojure sources for a regex. :neutral — searching
  ;; establishes nothing, like read_file. The search logic (files/grep-project)
  ;; was written by the agent itself in a supervised self-building run.
  (if-let [m (base/missing ctx :pattern)]
    (base/malformed branch m)
    (let [hits (try (files/grep-project (or root ".") (str (base/arg ctx :pattern)))
                    (catch Throwable e [::error (ex-message e)]))]
      (cond
        (and (vector? hits) (= ::error (first hits)))
        (base/malformed branch (str "Bad grep pattern: " (second hits)))

        (empty? hits)
        (base/ok branch (str "No matches for " (pr-str (base/arg ctx :pattern)) "."))

        :else
        (base/ok branch (str/join "\n" (for [{:keys [path line text]} (take 200 hits)]
                                    (str path ":" line ": " (str/trim text)))))))))
