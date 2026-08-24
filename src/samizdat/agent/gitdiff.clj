;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.gitdiff
  "The run's own changes, as a diff, for the finalization critic to review.

  A baseline is captured when a run starts — a commit object of the working
  tree at that moment (git stash create), which does not touch the tree — so
  the diff at finalization is exactly what the RUN changed, not whatever was
  already uncommitted. Everything here fails soft: no git, no repo, or any
  error yields an empty diff / nil baseline / cannot-tell, and the critic
  simply reviews completeness only.

  git runs as STRUCTURED scoped requests (samizdat.engine.proc/scope-run,
  jolt.host's scoped process primitive): a direct argv with the repo root
  as the request cwd — no shell, no composed directory prefix — under the
  explicit scrubbed allowlist environment (nothing inherited from the
  controller), a hard timeout whose expiry takes the whole git process
  tree down (TERM wave, grace, KILL wave, /proc-confirmed empty), and
  byte-capped capture, so even a pathological repository cannot make a
  diff call unbounded.

  Boundary contract: this namespace's fail-soft answers are 'git cannot
  tell' answers, and cannot-tell is a TRUST decision that belongs to the
  consumer. The ship gate therefore probes the execution boundary itself
  (proc/scope-supported?) before trusting a cannot-tell: on a runtime
  without the scoped primitive every git call here fails, changed-files
  reads as cannot-tell, and done would fall through to trust — so the
  gate blocks as UNAVAILABLE instead. Failing soft here can never fail
  OPEN there."
  (:require [clojure.string :as str]
            [samizdat.engine.proc :as proc]))

(def max-diff-chars 12000)

(def ^:private git-timeout-ms 15000)
(def ^:private git-term-grace-ms 2000)
(def ^:private git-out-bytes 262144)
(def ^:private git-err-bytes 65536)

(defn- git
  "One bounded scoped git invocation in `root` as a direct argv with the
  scrubbed allowlist environment. Returns the captured stdout on exit 0,
  nil on anything else — non-zero, timeout, spawn failure — preserving the
  fail-soft contract the callers' nil-vs-empty logic depends on."
  [root & args]
  (try
    (let [r (proc/scope-run {:cmd (into ["git"] (mapv str args))
                             :dir (str root)
                             :env (proc/scrubbed-allowlist-env)
                             :timeout-ms git-timeout-ms
                             :term-grace-ms git-term-grace-ms
                             :out-bytes git-out-bytes
                             :err-bytes git-err-bytes})]
      (when (and (not (:timed-out r)) (zero? (or (:exit r) 1)))
        (:out r)))
    (catch Throwable _ nil)))

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
  anything.

  These paths are attacker-named data (the model writes the files): they feed
  verification only through samizdat.agent.verify's narrow grammar, which
  refuses anything unrepresentable — and git's own path quoting
  (core.quotePath) keeps exotic names visibly exotic rather than smuggling
  them through as plain text."
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
  (or (when (and root baseline)
        (some-> (git root "diff" baseline)
                (as-> d (if (> (count d) max-diff-chars)
                          (str (subs d 0 max-diff-chars)
                               "\n… (diff truncated at " max-diff-chars " chars)")
                          d))))
      ""))
