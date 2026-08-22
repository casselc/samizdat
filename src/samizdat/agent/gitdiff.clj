;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.gitdiff
  "The run's own changes, as a diff, for the finalization critic to review.

  A baseline is captured when a run starts — a commit object of the working
  tree at that moment (git stash create), which does not touch the tree — so
  the diff at finalization is exactly what the RUN changed, not whatever was
  already uncommitted. Everything here fails soft: no git, no repo, or any
  error yields an empty diff, and the critic simply reviews completeness only."
  (:require [clojure.string :as str]
            [samizdat.engine.proc :as proc]))

(def max-diff-chars 12000)

(defn- git [root & args]
  (let [r (apply proc/run {:timeout-ms 15000} "git" "-C" (str root) args)]
    (when (and (not (:timeout r)) (zero? (or (:exit r) 1)))
      (:out r))))

(defn baseline
  "A ref the run's changes are diffed against: a commit object capturing the
  working tree now (so later edits show as the diff), or \"HEAD\" when the tree
  is clean. nil when git or the repo is unavailable — the critic then reviews
  completeness only."
  [root]
  (when (and root (proc/available? "git")
             (git root "rev-parse" "--is-inside-work-tree"))
    (or (some-> (git root "stash" "create") str/trim not-empty)
        "HEAD")))

(defn diff
  "The unified diff of the run's changes since `baseline`, bounded to keep it
  out of a runaway prompt. Empty string when there is nothing to show."
  [root baseline]
  (or (when (and root baseline)
        (some-> (git root "diff" baseline)
                (as-> d (if (> (count d) max-diff-chars)
                          (str (subs d 0 max-diff-chars)
                               "\n… (diff truncated at " max-diff-chars " chars)")
                          d))))
      ""))
