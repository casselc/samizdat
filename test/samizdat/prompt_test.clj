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
  ;; The prompt is assembled from a template; a substitution placeholder
  ;; reaching the model means an edit broke the seam. `{{env/NAME}}` is
  ;; excluded on purpose — it is documented runtime syntax the shell tool
  ;; resolves at spawn, not a template hole the loader should have filled.
  (let [prompt (loop/system-prompt)
        holes (->> (re-seq #"\{\{([^}]+)\}\}" prompt)
                   (map second)
                   (remove #(str/starts-with? % "env/")))]
    (is (empty? holes)
        (str "unfilled template placeholders: " (str/join ", " holes)))))
