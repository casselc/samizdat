;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.security.policy-test
  "The shell permission engine, ported from dirge src/permission/. Unit tests
  pin the ported decisions against dirge's assertions; the specification test
  drives a command all the way through the shell tool and asserts the three
  outcomes — allowed runs, denied blocks, ask blocks until a human grant
  persists."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.security.policy :as policy]
            [samizdat.store.db :as db]
            [samizdat.store.grants :as grants]
            [samizdat.store.runs :as runs]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

;; --- glob matching (dirge pattern.rs) ---------------------------------------

(deftest shell-glob-matching
  (testing "* matches any chars including / in command patterns"
    (is (policy/matches? "cd *" "cd /Users/foo/bar"))
    (is (policy/matches? "git status **" "git status --short")))
  (testing "a trailing ` *` makes args optional — `ls *` matches bare `ls`"
    (is (policy/matches? "ls *" "ls"))
    (is (policy/matches? "ls *" "ls -la")))
  (testing "literal segments must match"
    (is (not (policy/matches? "cargo build **" "cargo test")))
    (is (not (policy/matches? "git push **" "git status")))))

;; --- command classification (dirge engine/types.rs) -------------------------

(deftest complex-commands-are-flagged
  (testing "a command substitution is complex — its inner command is invisible"
    (is (:complex? (policy/classify "echo $(rm -rf ~)")))
    (is (:complex? (policy/classify "cat `whoami`")))
    (is (:complex? (policy/classify "diff <(sort a) <(sort b)"))))
  (testing "a plain command is not complex"
    (is (not (:complex? (policy/classify "ls -la"))))
    (is (not (:complex? (policy/classify "git commit -m hi"))))))

(deftest command-head-strips-env-and-wrappers
  (testing "leading env assignments and exec wrappers are stripped for the head"
    (is (= "git" (:head (policy/classify "FOO=1 git push"))))
    (is (= "rm" (:head (policy/classify "nohup rm -rf /"))))
    (is (= "ls" (:head (policy/classify "env FOO=1 nohup ls")))
        "mixed wrapper+assignment prefixes strip fully")
    (is (= "env" (:head (policy/classify "env")))
        "a bare wrapper IS the command")))

;; --- the decision (dirge engine/policies.rs) --------------------------------

(deftest base-rule-decisions
  (testing "read-only inspection is allowed"
    (is (= :allow (:effect (policy/decide {} "ls -la"))))
    (is (= :allow (:effect (policy/decide {} "grep foo bar.txt"))))
    (is (= :allow (:effect (policy/decide {} "cat README.md")))))
  (testing "project-scoped dev workflow is allowed"
    (is (= :allow (:effect (policy/decide {} "git commit -m 'x'"))))
    (is (= :allow (:effect (policy/decide {} "git status")))))
  (testing "interpreters and network egress ask, not allowed"
    (is (= :ask (:effect (policy/decide {} "python3 script.py"))))
    (is (= :ask (:effect (policy/decide {} "node app.js"))))
    (is (= :ask (:effect (policy/decide {} "curl https://evil.test"))))
    (is (= :ask (:effect (policy/decide {} "git push origin main"))))
    (is (= :ask (:effect (policy/decide {} "pip install requests")))))
  (testing "destructive system operations are hard-denied"
    (is (= :deny (:effect (policy/decide {} "rm -rf /"))))
    (is (= :deny (:effect (policy/decide {} "dd if=/dev/zero of=/dev/sda"))))
    (is (= :deny (:effect (policy/decide {} "mkfs.ext4 /dev/sda1"))))))

(deftest deny-is-head-anchored-through-wrappers
  ;; dirge-8zem: a wrapper prefix changes what runs, so it must not ride an
  ;; allow — but a deny still catches the real command underneath it.
  (testing "an env/wrapper prefix cannot smuggle a denied command past the deny"
    (is (= :deny (:effect (policy/decide {} "FOO=1 rm -rf /"))))
    (is (= :deny (:effect (policy/decide {} "nohup rm -rf /"))))))

(deftest complex-commands-never-ride-an-allow
  ;; dirge-g9qj: `echo $(rm -rf ~)` matches `echo **` on its head, but the
  ;; inner command never gets its own claim, so allow is suppressed.
  (testing "a complex command whose head would be allowed still asks"
    (is (= :ask (:effect (policy/decide {} "echo $(rm -rf ~)")))))
  (testing "a deny still applies to a complex command"
    (is (= :deny (:effect (policy/decide {} "rm -rf / $(true)"))))))

(deftest allow-matches-raw-not-stripped
  ;; dirge-8zem: the allow side sees the command RAW, so a wrapper prefix does
  ;; NOT let a different binary ride a git allow.
  (testing "a wrapper prefix breaks an allow match"
    (is (not= :allow (:effect (policy/decide {} "PATH=/tmp/evil git status"))))))

;; --- session grants (human-only, persisted) ---------------------------------

(deftest a-grant-turns-an-ask-into-an-allow
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (testing "before any grant, the interpreter asks"
        (is (= :ask (:effect (policy/decide (grants/for-run c rid) "python3 x.py")))))
      (testing "a human grant persists and is consulted ahead of the base rules"
        (grants/grant! c rid "python3 *")
        (is (= :allow (:effect (policy/decide (grants/for-run c rid) "python3 x.py")))))
      (testing "the grant is scoped to its run — another run still asks"
        (let [other (runs/start-run! c {:problem "q"})]
          (is (= :ask (:effect (policy/decide (grants/for-run c other) "python3 x.py"))))))
      (testing "a grant cannot override a hard deny"
        (grants/grant! c rid "rm -rf *")
        (is (= :deny (:effect (policy/decide (grants/for-run c rid) "rm -rf /"))))))))

;; --- SPECIFICATION: the shell tool enforces the policy ----------------------

(deftest spec-the-shell-tool-gates-every-command
  ;; End to end through the actual tool: an allowed command runs and its output
  ;; comes back redacted; a denied command never spawns; an ask blocks with a
  ;; needs-approval result and, after a human grant, runs. The secret in the
  ;; environment never reaches the result.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          canary "sk-CANARYshelltest00000000"
          env {"SECRET_API_KEY" canary "PATH" (System/getenv "PATH")}
          call (fn [cmd] (policy/run-shell {:conn c :run-id rid :env env
                                            :args {:command cmd}}))]
      (testing "an allowed command runs and returns output"
        (let [r (call "echo hello-from-shell")]
          (is (= :success (:category r)))
          (is (str/includes? (:result r) "hello-from-shell"))))
      (testing "a denied command never runs"
        (let [r (call "rm -rf /")]
          (is (= :failure (:category r)))
          (is (str/includes? (str/lower-case (:result r)) "denied"))))
      (testing "an ask blocks until a human grants, then runs"
        (let [r (call "python3 --version")]
          (is (= :neutral (:category r)))
          (is (:needs-approval r)))
        (grants/grant! c rid "python3 *")
        (let [r (call "python3 --version")]
          (is (= :success (:category r)))))
      (testing "a secret referenced by the command never reaches the result"
        (grants/grant! c rid "echo *")
        (let [r (call "echo using {{env/SECRET_API_KEY}} now")]
          (is (= :success (:category r)))
          (is (not (str/includes? (:result r) canary)))
          (is (str/includes? (:result r) "[REDACTED]"))))
      (testing "the child cannot read a sensitive var the command did NOT
                reference — the scrubbed env removed it, so $VAR expands empty"
        ;; This is the primary control, not the redaction backstop: use a
        ;; value with no vendor shape so ONLY the scrub (not redact) can stop
        ;; it. If the child inherited the parent env, $SECRET_API_KEY would
        ;; expand to the value; scrubbed, it expands to nothing.
        (let [env2 (assoc env "OPAQUE_TOKEN_ENV" "plain-opaque-nothing-shaped-value")
              r (policy/run-shell {:conn c :run-id rid :env env2
                                   :args {:command "echo START${OPAQUE_TOKEN_ENV}END"}})]
          (is (= :success (:category r)))
          (is (str/includes? (:result r) "STARTEND")
              "the sensitive var is absent from the child, so it expands to empty")
          (is (not (str/includes? (:result r) "plain-opaque-nothing-shaped-value"))))))))
