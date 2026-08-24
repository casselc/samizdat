;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.verify-fixture-test
  "FIXTURE — a deliberately tiny, always-green test namespace for the focused
  verification lane: samizdat.verify-test's end-to-end case derives a
  structured focused request for exactly this path and runs it through the
  real runtime, asserting a green summary. Not part of the full-suite runner
  list; it exists to be focused ON."
  (:require [clojure.test :refer [deftest is]]))

(deftest fixture-is-green
  (is (= 2 (+ 1 1))))
