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
            [samizdat.engine.proc :as proc]
            [samizdat.lexicon :as lexicon]
            [samizdat.security.secrets :as secrets]))

(defn max-diff-chars
  "How much of the working-tree diff a branch and the judge are shown.
  gates.edn `:context-budget :diff-chars` — how much the model gets to see is
  one table, and this was a constant outside it."
  []
  (lexicon/budget :diff-chars))

(defn- git [root & args]
  (let [r (apply proc/run {:timeout-ms 15000 :env (secrets/scrubbed-process-env)}
                 "git" "-C" (str root) args)]
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

(defn changed-files
  "The paths the run changed since `baseline`: tracked edits (git diff
  --name-only) UNION new files (git ls-files --others). The union matters —
  `git diff` is blind to untracked files, so a run that CREATES a namespace (the
  common case, and the one the prompt actively encourages) would otherwise read
  as 'changed nothing' and be judged hollow. nil when git or the repo is
  unavailable — 'cannot tell', distinct from [] which means 'genuinely nothing
  changed'. Ground truth for whether a run that claims done actually produced
  anything."
  [root baseline]
  (when (and root baseline)
    (let [lines (fn [out] (some->> out str/split-lines (remove str/blank?)))
          tracked (lines (git root "diff" "--name-only" baseline))
          untracked (lines (git root "ls-files" "--others" "--exclude-standard"))]
      ;; nil only when BOTH git calls failed (cannot tell); otherwise the union,
      ;; which may be empty (genuinely nothing changed).
      (when (or (some? tracked) (some? untracked))
        (vec (distinct (concat (or tracked []) (or untracked []))))))))

(defn diff
  "The unified diff of the run's changes since `baseline`, bounded to keep it
  out of a runaway prompt. Empty string when there is nothing to show."
  [root baseline]
  (let [cap (max-diff-chars)]
    (or (when (and root baseline)
          (some-> (git root "diff" baseline)
                  (as-> d (if (> (count d) cap)
                            (str (subs d 0 cap)
                                 "\n… (diff truncated at " cap " chars)")
                            d))))
        "")))
