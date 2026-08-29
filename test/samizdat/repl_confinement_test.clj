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

(ns samizdat.repl-confinement-test
  "Characterization of karamazov-zrq: `eval` is the unconfined host image.

  RED ON PURPOSE, AND RED UNTIL ROUND 4. These four tests assert the behaviour
  the fix is supposed to produce, so today they fail — that is the point. Each
  pins one facet of what dogfood run a3ba69bb's supervisor branch S2 actually
  did at turns 26-38, after `read_file` correctly refused
  `src/samizdat/agent/tools/introspect.clj` and the model routed around the
  refusal with `eval`.

  The live escape was slurp harness source -> spit a patched copy ->
  `(require … :reload)` into the running process. Reproducing that literally
  would patch the harness while the suite runs, so the write and the live-image
  mutation are pinned separately and non-destructively: a write to a path
  outside the run root, and an intern of a throwaway symbol into a harness
  namespace. Together they are the same capability without the damage.

  These test `samizdat.repl/eval-code` directly, the way repl-test does, rather
  than the tool wrapper — the seam is where the confinement has to live, and a
  test against the tool would pass the moment the tool stopped being offered
  while the seam stayed open."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.repl :as repl]))

(defn- project-run!
  "A fresh run root, registered the way a real project run registers one.
  Returns the root. `ensure-project-roots!` is additive and idempotent, so the
  temp root it leaves behind costs a stat and nothing else (see its docstring)."
  []
  (let [root (str (fs/create-temp-dir))]
    (repl/ensure-project-roots! root)
    root))

(deftest eval-resolves-relative-paths-against-the-run-root
  ;; Observed live at turn 4: the agent read samizdat's README believing it was
  ;; the project's, listed samizdat's directory at turn 5, and slurped
  ;; samizdat's deps.edn at turn 6. A wrong answer that looks right is the
  ;; worst failure this seam has.
  (let [root (project-run!)]
    (spit (str root "/README.md") "PROJECT-README-MARKER")
    (let [r (repl/eval-code "(slurp \"README.md\")")]
      (is (:ok r)
          "a relative read inside eval should resolve somewhere in the run root")
      (is (str/includes? (str (:value r)) "PROJECT-README-MARKER")
          "eval read the HARNESS README, not the project's"))))

(deftest eval-cannot-read-harness-source-from-a-project-run
  ;; The S2 escape's first move, verbatim. `read_file` refuses this path with
  ;; "No file … under the project root"; eval answers it.
  (let [_ (project-run!)
        r (repl/eval-code "(slurp \"src/samizdat/agent/tools/introspect.clj\")")]
    (is (not (:ok r))
        "eval slurped harness source by relative path, routing around the file-tool root confinement")))

(deftest eval-cannot-write-outside-the-run-root
  ;; write_file is root-confined. eval is not, which is what makes the stale
  ;; policy.clj justification for `sed` false (karamazov-zrq.7).
  (let [_ (project-run!)
        outside (str (fs/create-temp-dir) "/escape.txt")
        r (repl/eval-code (pr-str (list 'spit outside "escaped")))]
    (testing "the write is refused"
      (is (not (:ok r)) "eval wrote to a path outside the run root"))
    (testing "and nothing lands on disk"
      (is (not (.exists (io/file outside)))))))

(deftest eval-cannot-mutate-the-live-harness-image
  ;; The payload of the escape: model-authored code taking effect in the
  ;; running process, invisible to the mutation protocol — no checkpoint, no
  ;; validate, no soak, no userspace version, nothing for rollback to see.
  ;; A throwaway intern proves the reach without breaking anything.
  (let [_ (project-run!)]
    (try
      (let [r (repl/eval-code
               "(intern 'samizdat.repl 'zrq-characterization-probe :reached)")]
        (is (not (:ok r))
            "eval interned a var into a live harness namespace"))
      (finally
        (when-let [n (find-ns 'samizdat.repl)]
          (ns-unmap n 'zrq-characterization-probe))))))
