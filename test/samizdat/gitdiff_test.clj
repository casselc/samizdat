;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.gitdiff-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [samizdat.agent.gitdiff :as gd]
            [samizdat.agent.verify :as verify]
            [samizdat.engine.proc :as proc]))

(deftest diff-fails-soft
  ;; No root, no baseline, or no repo must yield an empty diff, not a throw —
  ;; so the finalization critic degrades to completeness-only.
  (is (= "" (gd/diff nil nil)))
  (is (= "" (gd/diff nil "HEAD")))
  (is (= "" (gd/diff "/tmp" nil)))
  (is (nil? (gd/baseline nil))))

;; Fixture setup runs through the same scoped structured seam the production
;; code under test uses — direct argv with the repo as cwd and the scrubbed
;; allowlist environment — so the tests never lean on shell composition the
;; source discipline forbids.
(defn- sh
  [dir & cmd]
  (proc/scope-run {:cmd (vec cmd) :dir dir
                   :env (proc/scrubbed-allowlist-env)
                   :timeout-ms 15000 :term-grace-ms 2000
                   :out-bytes 65536 :err-bytes 65536}))

(defn- temp-dir [tag]
  (str (System/getProperty "java.io.tmpdir") "/samizdat-gd-" tag "-"
       (System/currentTimeMillis) "-" (rand-int 100000)))

(defn- init-repo! [dir]
  (.mkdirs (java.io.File. dir))
  (sh dir "git" "init" "-q")
  (sh dir "git" "config" "user.email" "t@t.co")
  (sh dir "git" "config" "user.name" "t"))

(deftest changed-files-sees-new-untracked-files
  ;; The bug this pins: `git diff` is blind to untracked files, so a run that
  ;; CREATES a namespace + its test read as 'changed nothing' and every done
  ;; was refused as hollow. changed-files must union in `git ls-files --others`.
  (when (proc/available? "git")
    (let [dir (temp-dir "untracked")]
      (try
        (init-repo! dir)
        (spit (str dir "/seed.txt") "seed\n")
        (sh dir "git" "add" "-A")
        (sh dir "git" "commit" "-qm" "init")
        (let [base (gd/baseline dir)]
          (testing "a clean tree has changed nothing"
            (is (= [] (gd/changed-files dir base))))
          ;; a NEW untracked file (the create-a-namespace case) and a tracked edit
          (spit (str dir "/src_new.clj") "new\n")
          (spit (str dir "/seed.txt") "seed\nmore\n" :append true)
          (let [changed (set (gd/changed-files dir base))]
            (is (contains? changed "src_new.clj") "the newly-created file is seen")
            (is (contains? changed "seed.txt") "and a tracked edit is still seen")))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest adversarial-names-survive-git-and-refuse-focused-verification
  ;; End-to-end for the critical verify-path finding: the model writes test
  ;; files whose NAMES are the attack. git (which quotes exotic paths, so
  ;; they stay visibly exotic) reports them through the scoped structured
  ;; gitdiff; focused verification then REFUSES them — or, with a trusted
  ;; fallback configured, runs the operator's command untouched. Nothing
  ;; composes a shell anywhere on the way, so the names are data end to end.
  (when (proc/available? "git")
    (let [dir (temp-dir "adversarial")]
      (try
        (init-repo! dir)
        (spit (str dir "/seed.txt") "seed\n")
        (sh dir "git" "add" "-A")
        (sh dir "git" "commit" "-qm" "init")
        (let [base (gd/baseline dir)]
          (spit (str dir "/good_test.clj") "(ns good-test)\n")
          ;; the review's quote breakout, verbatim, as a real file name
          (spit (str dir "/evil';touch pwned_test.clj") "(ns evil)\n")
          ;; non-ASCII — git reports it C-quoted with octal escapes
          (spit (str dir "/ünïcode_test.clj") "(ns uni)\n")
          (let [changed (gd/changed-files dir base)
                listed (some->> (.list (java.io.File. dir)) seq)]
            (testing "the attack name really exists on disk"
              (is (some #(str/includes? % "evil';touch pwned") listed)))
            (testing "git reports the adversarial names back"
              (is (some #(str/includes? % "good_test") changed))
              (is (some #(str/includes? % "touch pwned") changed)
                  "the printable-ASCII attack name arrives raw")
              (is (some #(re-find #"\\3" %) changed)
                  "the Unicode name arrives octal-escaped (git quoting)"))
            (testing "focused verification refuses the whole derivation"
              (let [req (verify/verify-request {:changed changed :focused? true})]
                (is (= :refused (:kind req)))
                (is (some #(str/includes? % "evil") (:refused req))
                    "the adversarial name is named in the refusal")))
            (testing "a trusted configured fallback absorbs it untouched"
              (let [req (verify/verify-request {:changed changed :focused? true
                                                :fallback-cmd "jolt -M:test"})]
                (is (= :fallback (:kind req)))
                (is (= ["sh" "-c" "jolt -M:test"] (:argv req))
                    "the operator command runs verbatim; no changed path
                     entered it")))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest gitdiff-fails-soft-when-the-scoped-primitive-is-missing
  ;; The boundary half of the fail-closed contract: on a runtime without
  ;; the scoped primitive every git call here fails, so this namespace's
  ;; answers degrade to its fail-soft shapes (nil baseline, cannot-tell,
  ;; empty diff) WITHOUT throwing — and the SHIP gate, which is where
  ;; cannot-tell becomes a trust decision, probes the same boundary and
  ;; blocks instead (see ship-test). Failing soft here can never fail
  ;; open there.
  (with-redefs [proc/scope-supported? (constantly false)]
    (binding [proc/*scope-run*
              (fn [_]
                (throw (UnsupportedOperationException.
                         "process-scope: requires Linux with posix_spawn process-group and poll(2) support")))]
      (is (nil? (gd/baseline "/tmp")))
      (is (nil? (gd/changed-files "/tmp" "HEAD")))
      (is (= "" (gd/diff "/tmp" "HEAD"))))))

;; --- self-run for the focused lane ---------------------------------------------
;; On the recorded JS1 classpath (repo root; jolt located as bin/js1 locates
;; it — $JOLT_HOME, else the sibling ../jolt checkout):
;;   SAMIZDAT_GITDIFF_TEST_RUN=1 ../jolt/bin/jolt -Srepro -Scp "$(bin/js1 path)" run test/samizdat/gitdiff_test.clj

(when (= "1" (jolt.host/getenv "SAMIZDAT_GITDIFF_TEST_RUN"))
  (let [{:keys [fail error] :as summary} (run-tests 'samizdat.gitdiff-test)]
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
