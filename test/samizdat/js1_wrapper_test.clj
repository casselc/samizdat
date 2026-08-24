;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.js1-wrapper-test
  "bin/js1 wrapper contract tests (deterministic, offline, no SCI needed).

  The smoke's evidence value rests on the wrapper's fail-closed gates, so
  those gates are what this file pins: every wrong-runtime shape must be
  refused with the remedy on stderr.  Only sh (jolt.host/sh) and git are
  required; the positive pin-check runs only when a genuinely pinned Jolt
  checkout is discoverable (JOLT_HOME, else the sibling ../jolt) and skips
  otherwise.  The smoke itself is deliberately NOT run here — it is the
  samizdat.sandbox-test self-run plus this plumbing, and docs/
  JS1_RUNTIME.md records its evidence."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]))

(def ^:private project-dir
  (or (jolt.host/getenv "JOLT_PWD") (System/getProperty "user.dir")))

(def ^:private wrapper (str project-dir "/bin/js1"))

;; The same pins bin/js1 enforces (docs/JS1_RUNTIME.md § Pins).
(def ^:private expected-jolt-sha "619ef19685460af847654e22cf6beda904d052fb")
(def ^:private jolt-url "https://github.com/casselc/jolt")
(def ^:private jolt-branch "js0-functional-sci-upstream")

(defn- git-available? []
  (zero? (jolt.host/sh "command -v git >/dev/null 2>&1")))

(defn- sh-capture
  "Run CMD through sh with stdout/stderr captured to files under a fresh
  /tmp dir.  Returns {:exit :out :err}."
  [cmd]
  (let [dir (str "/tmp/samizdat-js1-wrapper-" (random-uuid))
        out (str dir "/out")
        err (str dir "/err")]
    (fs/create-dirs dir)
    (try
      (let [exit (jolt.host/sh (str cmd " >" out " 2>" err))]
        {:exit exit
         :out (when (fs/exists? out) (slurp out))
         :err (when (fs/exists? err) (slurp err))})
      (finally (fs/delete-tree dir)))))

(defn- git
  "git -C dir <args>; returns the exit code.  pr-str quotes the dir for
  the shell (EDN string escapes are sh double-quote escapes)."
  [dir args]
  (jolt.host/sh (str "git -C " (pr-str dir) " " args " >/dev/null 2>&1")))

(defn- make-fake-jolt
  "A tmp dir that looks like a Jolt checkout (executable bin/jolt stub, a
  git repo with one empty commit) — enough to reach the pin check, at a
  commit that is necessarily not the pin.  Returns the dir."
  []
  (let [dir (str "/tmp/samizdat-js1-fake-jolt-" (random-uuid))]
    (fs/create-dirs (str dir "/bin"))
    (spit (str dir "/bin/jolt") "#!/bin/sh\n")
    (jolt.host/sh (str "chmod +x " dir "/bin/jolt"))
    (git dir "init -q .")
    (git dir "-c user.email=js1@test -c user.name=js1 commit -q --allow-empty -m x")
    dir))

(deftest wrapper-refuses-missing-checkout
  (if-not (git-available?)
    (is true "skipped: git not on PATH")
    (testing "JOLT_HOME set to a nonexistent directory"
      (let [{:keys [exit err]} (sh-capture
                                (str "env JOLT_HOME=/nonexistent-js1-" (random-uuid)
                                     " sh " (pr-str wrapper) " check"))]
        (is (not= 0 exit))
        (is (str/includes? err "is not a directory"))
        (is (str/includes? err jolt-url))
        (is (str/includes? err expected-jolt-sha))))
    (testing "no JOLT_HOME and no sibling checkout"
      ;; A copy of the wrapper in a bare tmp tree has no sibling ../jolt,
      ;; so the fallback probe misses regardless of this workspace.
      (let [dir (str "/tmp/samizdat-js1-nosib-" (random-uuid))]
        (fs/create-dirs (str dir "/bin"))
        (spit (str dir "/bin/js1") (slurp wrapper))
        (try
          (let [{:keys [exit err]} (sh-capture
                                    (str "env JOLT_HOME= sh " dir "/bin/js1 check"))]
            (is (not= 0 exit))
            (is (str/includes? err "no Jolt checkout found"))
            (is (str/includes? err jolt-url))
            (is (str/includes? err jolt-branch)))
          (finally (fs/delete-tree dir)))))))

(deftest wrapper-refuses-wrong-commit
  (if-not (git-available?)
    (is true "skipped: git not on PATH")
    (let [fake (make-fake-jolt)]
      (try
        (let [{:keys [exit err]} (sh-capture
                                  (str "env JOLT_HOME=" fake " sh " (pr-str wrapper) " check"))]
          (is (not= 0 exit))
          (is (str/includes? err "not at the pinned JS1 runtime commit"))
          (is (str/includes? err expected-jolt-sha))
          (is (str/includes? err jolt-branch))
          ;; the remedy, not just the refusal
          (is (str/includes? err "submodule update --init vendor/sci")))
        (finally (fs/delete-tree fake))))))

(deftest wrapper-accepts-pinned-checkout-when-present
  (if-not (git-available?)
    (is true "skipped: git not on PATH")
    ;; Discover a candidate exactly the way the wrapper does, then only
    ;; demand a pass when it is genuinely at the pin.
    (let [home (jolt.host/getenv "JOLT_HOME")
          candidate (if (and home (not (str/blank? home)))
                      home
                      (str project-dir "/../jolt"))
          at-pin? (and (fs/exists? (str candidate "/.git"))
                       (= expected-jolt-sha
                          (let [{:keys [exit out]} (sh-capture
                                                    (str "git -C " (pr-str candidate) " rev-parse HEAD"))]
                            (when (zero? exit) (str/trim out)))))]
      (if-not at-pin?
        (is true (str "skipped: no Jolt checkout at the pinned commit "
                      "(looked at " candidate ")"))
        (let [{:keys [exit out]} (sh-capture (str "sh " (pr-str wrapper) " check"))]
          (is (zero? exit))
          (is (str/includes? out "js1 runtime stack: OK"))
          (is (str/includes? out expected-jolt-sha))
          (is (str/includes? out "0.13.53")))))))
