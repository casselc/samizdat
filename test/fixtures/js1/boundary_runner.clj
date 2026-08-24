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

(ns fixtures.js1.boundary-runner
  "FIXTURE — the JS1 durable-restart boundary suite runner for the SmolVM
  CI lane (ci/js1-smolvm). Immutable fixture definition; results are CI
  artifacts only.

  Invoked in the guest by ci/js1-smolvm/guest-setup.sh step `boundary`:

    SAMIZDAT_JS1_BOUNDARY_TEST=1 SAMIZDAT_JOLT_BIN=$JOLT_HOME/bin/jolt \\
      $JOLT_HOME/bin/jolt -Scp \"$(bin/js1 path)\" run <this file>

  The -Scp roots are the recorded JS1 classpath replay (src + test + the
  vendored SCI + jolt-crypto + SCI's four jars), so this file's ns path
  (test/fixtures/js1/) resolves against the test root. It requires
  samizdat.js1-boundary-test and runs exactly that namespace's suite mode,
  which spawns the record/resume/mismatch/unsettled phases as FRESH jolt
  OS processes and asserts on their exit codes and on-disk world. Exit 0
  iff the suite is green; the guest wrapper prints GUEST-BOUNDARY-OK after
  a zero exit and the host harness additionally requires clojure.test's
  \"0 failures, 0 errors\" summary line.

  Outside the lane this file does nothing unusual: without
  SAMIZDAT_JS1_BOUNDARY_TEST=1 the boundary suite skips with its
  documented reason, and the runner still exits 0 for a green (skipped)
  run — the lane, not this fixture, decides whether a skip is acceptable
  (it is not: the consumer asserts the boundary markers)."
  (:require [clojure.test :as t]))

(require 'samizdat.js1-boundary-test)

(let [{:keys [fail error] :as summary}
      (t/run-tests 'samizdat.js1-boundary-test)]
  (println summary)
  (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0)))
