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
  ;; provenance R3-8. reap!'s escalation was destroyForcibly on the ROOT only, and
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

;; --- run-bounded: per-stream byte budgets with true counts -------------------

(deftest run-bounded-keeps-a-budget-and-counts-the-truth
  ;; The port of bbagent's measured bounds (A3a): reading continues past the
  ;; budget rather than stopping at it — a child whose output is not consumed
  ;; blocks on a full pipe — so the true size is still counted.
  (let [r (proc/run-bounded {:timeout-ms 15000
                             :out-max-bytes 4096
                             :err-max-bytes 4096}
                            "sh" "-c"
                            "yes abcdefghij | head -n 20000; yes ABCDEFGHIJ | head -n 20000 >&2")]
    (is (= :exited (:status r)))
    (is (zero? (:exit r)))
    (is (<= (count (.getBytes ^String (:stdout r) "UTF-8")) 4096)
        "what was kept is bounded")
    (is (<= (count (.getBytes ^String (:stderr r) "UTF-8")) 4096))
    (is (true? (:stdout/truncated? r)))
    (is (true? (:stderr/truncated? r)))
    (is (= 220000 (:stdout/bytes r))
        "the TRUE size is reported, not the kept size")
    ;; 220000 exactly under GNU coreutils; this host's uutils yes also
    ;; prints a short broken-pipe diagnostic to stderr when head exits, so
    ;; the honest assertion is at-least — never below the true output.
    (is (>= (:stderr/bytes r) 220000)
        "the true size is reported, not the kept size")))

(deftest run-bounded-within-the-bound-is-not-marked-truncated
  (let [r (proc/run-bounded {:timeout-ms 10000} "sh" "-c" "echo small")]
    (is (= :exited (:status r)))
    (is (false? (:stdout/truncated? r)))
    (is (false? (:stderr/truncated? r)))
    (is (= 6 (:stdout/bytes r)))))

(deftest run-bounded-does-not-invent-an-exit-at-a-deadline
  (sweep!)
  (try
    (let [r (proc/run-bounded {:timeout-ms 1500} "sh" "-c" "exec sleep 987")]
      (is (= :timeout (:status r)))
      (is (not (contains? r :exit))
          "a deadline is not a program that chose a status")
      (Thread/sleep 300)
      (is (empty? (survivors)) "the tree was reaped, not abandoned"))
    (finally (sweep!))))

(deftest run-bounded-reports-a-start-failure
  (let [r (proc/run-bounded {:timeout-ms 5000} "/definitely/not/a/real/binary")]
    (is (= :start-failure (:status r)))
    (is (string? (:error/message r)))
    (is (not (contains? r :exit)))))

(deftest run-bounded-refuses-an-invalid-request
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty vector"
                        (proc/run-bounded {:timeout-ms 5000} "  ")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty vector"
                        (proc/run-bounded {:timeout-ms 5000})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"within the bound"
                        (proc/run-bounded {:timeout-ms 0} "true"))))
