;; samizdat - a claim-first verification harness
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

(ns samizdat.proc-test
  "The subprocess reaper, against a process that refuses to cooperate."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [samizdat.engine.proc :as proc]))

(def ^:private needle "sleep 987")

(defn- survivors []
  ;; pgrep exits 1 when nothing matches — an empty list, not an error.
  (let [r (proc/run {:timeout-ms 5000} "pgrep" "-f" needle)]
    (if (zero? (:exit r)) (str/split-lines (:out r)) [])))

(defn- sweep! []
  (try (proc/run {:timeout-ms 5000} "pkill" "-9" "-f" needle)
       (catch Throwable _ nil)))

(deftest a-term-trapping-tree-dies-to-the-last-process
  ;; review3 #8. reap!'s escalation was destroyForcibly on the ROOT only, and
  ;; worse: jolt's ProcessHandle.destroy — what destroy-tree signals through —
  ;; silently fails to signal at all, so the tree-wide TERM never landed. A
  ;; child that ignores TERM outlived the reap as an orphan, which is the
  ;; 28-z3-processes failure mode this namespace exists to prevent. The kill
  ;; must reach every descendant with SIGKILL, enumerated before the root dies
  ;; (afterwards they reparent to init and are invisible to descendants()).
  (sweep!)
  (try
    (let [r (proc/run {:timeout-ms 1500} "sh" "-c"
                      "trap '' TERM; sleep 987 & wait")]
      (is (:timeout r) "the run times out")
      (Thread/sleep 300)
      (is (empty? (survivors))
          "a TERM-trapping child tree must not outlive the reap"))
    (finally (sweep!))))
