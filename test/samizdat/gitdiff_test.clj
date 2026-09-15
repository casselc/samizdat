;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.gitdiff-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.gitdiff :as gd]
            [samizdat.engine.proc :as proc]
            [samizdat.security.secrets :as scrub]))

(deftest gitdiff-spawns-with-a-scrubbed-environment
  (let [captured (atom nil)]
    (with-redefs [proc/run (fn [opts & _] (reset! captured opts) {:exit 0 :out "" :err ""})]
      (gd/diff "/tmp/some-root" "HEAD"))
    (is (map? (:env @captured)) "git children get an explicit environment")
    (is (= (scrub/scrubbed-process-env) (:env @captured))
        "the git child sees the scrubbed process environment, not the parent's")))

(deftest diff-fails-soft
  ;; No root, no baseline, or no repo must yield an empty diff, not a throw —
  ;; so the finalization critic degrades to completeness-only.
  (is (= "" (gd/diff nil nil)))
  (is (= "" (gd/diff nil "HEAD")))
  (is (= "" (gd/diff "/tmp" nil)))
  (is (nil? (gd/baseline nil))))

(defn- sh [dir cmd]
  (proc/run {:timeout-ms 15000} "sh" "-c" (str "cd " dir " && " cmd)))

(deftest untracked-line-scan-is-gates-data
  (is (= {:binary-prefix-bytes 8000
          :buffer-bytes 8192
          :max-bytes 65536}
         (gates/threshold :untracked-line-scan))))

(deftest changed-files-sees-new-untracked-files
  ;; The bug this pins: `git diff` is blind to untracked files, so a run that
  ;; CREATES a namespace + its test read as 'changed nothing' and every done
  ;; was refused as hollow. changed-files must union in `git ls-files --others`.
  (when (proc/available? "git")
    (let [dir (str (System/getProperty "java.io.tmpdir") "/gd-untracked-"
                   (System/currentTimeMillis))]
      (try
        (proc/run {:timeout-ms 15000} "sh" "-c" (str "mkdir -p " dir))
        (sh dir "git init -q && git config user.email t@t.co && git config user.name t")
        (sh dir "echo seed > seed.txt && git add -A && git commit -qm init")
        (let [base (gd/baseline dir)]
          (testing "a clean tree has changed nothing"
            (is (= [] (gd/changed-files dir base))))
          ;; a NEW untracked file (the create-a-namespace case) and a tracked edit
          (sh dir "echo new > src_new.clj && echo more >> seed.txt")
          (let [changed (set (gd/changed-files dir base))]
            (is (contains? changed "src_new.clj") "the newly-created file is seen")
            (is (contains? changed "seed.txt") "and a tracked edit is still seen")))
        (finally (sh dir (str "rm -rf " dir)))))))

(deftest changed-lines-uses-gits-text-binary-classification-for-untracked-files
  (when (proc/available? "git")
    (let [dir (str (System/getProperty "java.io.tmpdir") "/gd-lines-"
                   (System/currentTimeMillis))]
      (try
        (proc/run {:timeout-ms 15000} "sh" "-c" (str "mkdir -p " dir))
        (sh dir "git init -q && git config user.email t@t.co && git config user.name t")
        (sh dir "printf 'seed\\n' > seed.txt && git add -A && git commit -qm init")
        (let [base (gd/baseline dir)
              large-lines 5000]
          (spit (str dir "/notes.txt") "first\nsecond\n")
          (spit (str dir "/large.txt") (apply str (repeat large-lines "x\n")))
          (spit (str dir "/unterminated.txt") "last line")
          (spit (str dir "/empty.txt") "")
          ;; SQLite's header is followed by a NUL. Newline-like bytes after it
          ;; must not turn persistent binary state into apparent source lines.
          (spit (str dir "/state.sqlite3")
                (str "SQLite format 3" \u0000 "page\nbytes\n"))
          (testing "text, a multi-buffer file, and a final unterminated line count"
            (is (= (+ large-lines 3) (gd/changed-lines dir base))))
          (spit (str dir "/seed.txt") "seed\ntracked one\ntracked two\n")
          (testing "tracked and untracked text counts are combined"
            (is (= (+ large-lines 5) (gd/changed-lines dir base)))))
        (finally (sh dir (str "rm -rf " dir)))))))

(deftest changed-lines-fails-soft-when-an-untracked-file-vanishes
  ;; The path can disappear or become unreadable after ls-files reports it.
  ;; That one file contributes zero; the line-budget measurement still returns.
  (let [calls (atom 0)
        responses (atom [{:exit 0 :out "" :err ""}
                         {:exit 0 :out "vanished.sqlite3\n" :err ""}])]
    (with-redefs [proc/run (fn [& _]
                            (swap! calls inc)
                            (let [r (first @responses)]
                              (swap! responses rest)
                              r))]
      (is (= 0 (gd/changed-lines "/tmp/racing-repo" "HEAD")))
      (is (= 2 @calls) "only tracked numstat and the untracked listing spawn Git")
      (is (empty? @responses)))))

(deftest changed-lines-does-not-spawn-per-untracked-file
  (let [path-count 200
        calls (atom 0)
        listing (str (str/join "\n" (map #(str "gone-" % ".dat")
                                          (range path-count)))
                     "\n")]
    (with-redefs [proc/run (fn [& _]
                            (let [call (swap! calls inc)]
                              (case call
                                1 {:exit 0 :out "" :err ""}
                                2 {:exit 0 :out listing :err ""}
                                {:exit 128 :out "" :err "unexpected process"})))]
      (is (= 0 (gd/changed-lines "/tmp/many-racing-files" "HEAD")))
      (is (= 2 @calls)
          "untracked file count must not multiply the per-turn process count"))))

(deftest changed-lines-caps-an-untracked-text-scan
  (when (proc/available? "git")
    (let [dir (str (System/getProperty "java.io.tmpdir") "/gd-capped-"
                   (System/currentTimeMillis))
          scan-cap (:max-bytes (gates/threshold :untracked-line-scan))
          complete-lines (dec (/ scan-cap 2))]
      (try
        (proc/run {:timeout-ms 15000} "sh" "-c" (str "mkdir -p " dir))
        (sh dir "git init -q && git config user.email t@t.co && git config user.name t")
        (sh dir "printf 'seed\\n' > seed.txt && git add -A && git commit -qm init")
        (let [base (gd/baseline dir)]
          ;; At the cap: complete-lines `x\n` records plus the first two bytes
          ;; of "partial". Lines beyond that point must never be read/countable.
          (spit (str dir "/capped.txt")
                (str (apply str (repeat complete-lines "x\n"))
                     "partial\nignored\nignored\n"))
          (is (= (inc complete-lines) (gd/changed-lines dir base))
              "the partial line at the cap counts once; later lines do not"))
        (finally (sh dir (str "rm -rf " dir)))))))

(deftest changed-lines-skips-an-untracked-symlink-to-a-special-file
  ;; Git reports the symlink as untracked even though it omits the FIFO itself.
  ;; Opening the link as a FileInputStream would block waiting for a writer.
  (when (and (proc/available? "git") (proc/available? "mkfifo"))
    (let [dir (str (System/getProperty "java.io.tmpdir") "/gd-fifo-"
                   (System/currentTimeMillis))]
      (try
        (proc/run {:timeout-ms 15000} "sh" "-c" (str "mkdir -p " dir))
        (sh dir "git init -q && git config user.email t@t.co && git config user.name t")
        (sh dir "printf 'seed\\n' > seed.txt && git add -A && git commit -qm init")
        (let [base (gd/baseline dir)]
          (sh dir "mkfifo blocked.pipe && ln -s blocked.pipe blocked-link")
          (is (= #{"blocked-link"} (set (gd/changed-files dir base)))
              "Git reports the symlink but omits the direct FIFO")
          (is (= 1 (gd/changed-lines dir base))
              "the symlink itself counts once without opening its FIFO target"))
        (finally (sh dir (str "rm -rf " dir)))))))

(deftest snapshot-reads-the-tree-at-a-glance
  ;; What the TUI's footer and GIT panel are built on: branch, dirty counts
  ;; split the way git splits them, and the last commit's subject. Ported from
  ;; dirge's status line (project:branch) and left-panel GIT box
  ;; (⎇ branch / +staged ~unstaged ?untracked / last commit).
  (when (proc/available? "git")
    (let [dir (str (System/getProperty "java.io.tmpdir") "/gd-snap-"
                   (System/currentTimeMillis))]
      (try
        (proc/run {:timeout-ms 15000} "sh" "-c" (str "mkdir -p " dir))
        (sh dir "git init -q -b trunk && git config user.email t@t.co && git config user.name t")
        (sh dir "echo seed > seed.txt && git add -A && git commit -qm 'the first commit'")
        (testing "a clean tree"
          (let [s (gd/snapshot dir)]
            (is (= "trunk" (:branch s)))
            (is (= 0 (:staged s)))
            (is (= 0 (:unstaged s)))
            (is (= 0 (:untracked s)))
            (is (= "the first commit" (:last-commit s)))))
        (testing "one of each kind of dirty, counted separately"
          ;; git's own split: a staged add is X, an unstaged edit is Y, and a
          ;; file git has never seen is ??. Collapsing them into one "dirty"
          ;; number would lose the distinction the panel exists to show.
          (sh dir "echo staged > staged.txt && git add staged.txt")
          (sh dir "echo more >> seed.txt")
          (sh dir "echo untracked > untracked.txt")
          (let [s (gd/snapshot dir)]
            (is (= 1 (:staged s)))
            (is (= 1 (:unstaged s)))
            (is (= 1 (:untracked s)))))
        (testing "a file both staged and edited again counts on both sides"
          (sh dir "echo edited >> staged.txt")
          (let [s (gd/snapshot dir)]
            (is (= 1 (:staged s)))
            (is (= 2 (:unstaged s)) "seed.txt and the re-edited staged.txt")))
        (finally (sh dir (str "rm -rf " dir)))))))

(deftest snapshot-fails-soft
  ;; Same posture as diff and changed-files: the footer degrades to the
  ;; project name rather than the UI failing to draw.
  (is (nil? (gd/snapshot nil)))
  (is (nil? (gd/snapshot (str (System/getProperty "java.io.tmpdir") "/definitely-not-a-repo-xyz")))))

(deftest a-detached-head-has-no-branch-but-still-has-counts
  ;; Rebase, bisect, and a CI checkout all sit on a detached HEAD. dirge omits
  ;; the branch segment there; the counts are still worth showing.
  (when (proc/available? "git")
    (let [dir (str (System/getProperty "java.io.tmpdir") "/gd-detached-"
                   (System/currentTimeMillis))]
      (try
        (proc/run {:timeout-ms 15000} "sh" "-c" (str "mkdir -p " dir))
        (sh dir "git init -q && git config user.email t@t.co && git config user.name t")
        (sh dir "echo a > a.txt && git add -A && git commit -qm one")
        (sh dir "git checkout -q --detach HEAD")
        (let [s (gd/snapshot dir)]
          (is (some? s) "still a repo")
          (is (nil? (:branch s)) "no branch to name")
          (is (= "one" (:last-commit s))))
        (finally (sh dir (str "rm -rf " dir)))))))
