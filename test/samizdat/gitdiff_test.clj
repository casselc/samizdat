;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.gitdiff-test
  (:require [clojure.test :refer [deftest is testing]]
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
