;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.gitdiff-test
  (:require [clojure.test :refer [deftest is]]
            [samizdat.agent.gitdiff :as gd]))

(deftest diff-fails-soft
  ;; No root, no baseline, or no repo must yield an empty diff, not a throw —
  ;; so the finalization critic degrades to completeness-only.
  (is (= "" (gd/diff nil nil)))
  (is (= "" (gd/diff nil "HEAD")))
  (is (= "" (gd/diff "/tmp" nil)))
  (is (nil? (gd/baseline nil))))
