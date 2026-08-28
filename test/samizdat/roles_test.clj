;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.roles-test
  "Each role sees its own world.

  Before this, every role was built the same way — the implementer's system
  prompt plus a suffix — so a supervisor was handed 31 tools written for
  somebody building the project and its role prompt had to spend a paragraph
  arguing it back out of them (\"a base bug is not yours to fix, and you
  cannot reach it\"). A supervisor once spent 108 of a run's 211 turns hunting
  a source tree it was never going to be allowed to open. Roles were
  differentiated by ADDITION; they are constructed now."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.roles :as roles]
            ;; the registry must be LOADED for the surface check to mean anything
            [samizdat.agent.tools]))

(deftest every-role-declares-a-surface
  (doseq [r (roles/names)]
    (is (or (set? (roles/surface r)) (= :all (roles/surface r)))
        (str r " has no tool surface"))
    (is (string? (roles/doc r)) (str r " has no doc — it must say what it is for"))))

(deftest the-implementor-builds-and-the-supervisor-does-not
  (testing "the implementor may change the project"
    (doseq [t ["write_file" "edit_file" "patch" "eval" "plan"]]
      (is (roles/may-use? :implementor t) (str "implementor lost " t))))
  (testing "the supervisor may NOT change the project — that is not its job"
    (doseq [t ["write_file" "edit_file" "patch"]]
      (is (not (roles/may-use? :supervisor t))
          (str "supervisor can " t " on the project; it tunes the harness, not the code"))))
  (testing "but it may INVESTIGATE, including what the implementer did"
    (doseq [t ["read_file" "grep" "fetch_turn" "fetch_artifact" "introspect" "recall"]]
      (is (roles/may-use? :supervisor t)
          (str "supervisor lost " t ", which is how it looks into a run"))))
  (testing "and it may tune the harness, which the implementor may not"
    (doseq [t ["cell" "manifest" "prompt" "policy"]]
      (is (roles/may-use? :supervisor t) (str "supervisor lost " t)))))

(deftest the-reviewer-reads-and-judges-but-does-not-write
  (doseq [t ["read_file" "grep" "done"]]
    (is (roles/may-use? :reviewer t)))
  (doseq [t ["write_file" "edit_file" "patch" "cell" "manifest"]]
    (is (not (roles/may-use? :reviewer t))
        (str "reviewer can " t "; a reviewer that edits is not reviewing"))))

(deftest the-critic-holds-no-tools-at-all
  ;; It is a single call over a requirement and a diff, not a loop.
  (is (empty? (roles/surface :critic))))

(deftest the-catalogue-is-scoped-to-the-role
  (let [cat (str "read_file({path})\n"
                 "    Read a file.\n"
                 "write_file({path, content})\n"
                 "    Write a file.\n"
                 "cell({action})\n"
                 "    Edit a cell.\n")]
    (testing "a role sees the tools it may use"
      (let [s (roles/scope-catalogue cat :supervisor)]
        (is (str/includes? s "read_file({path})"))
        (is (str/includes? s "cell({action})"))))
    (testing "and not the ones it may not — the entry AND its prose go"
      (let [s (roles/scope-catalogue cat :supervisor)]
        (is (not (str/includes? s "write_file({path, content})")))
        (is (not (str/includes? s "Write a file."))
            "the prose under a dropped tool must go with it")))
    (testing "an :all role keeps everything"
      (is (= cat (roles/scope-catalogue cat :implementor))))
    (testing "an unknown role is not silently given everything"
      (is (= cat (roles/scope-catalogue cat nil))
          "nil means no role scoping, which is the pre-existing behaviour"))))

(deftest a-role-cannot-be-given-a-tool-that-does-not-exist
  ;; The same class of bug as `patch` missing from :file-write: a surface
  ;; naming a tool the loop cannot dispatch silently narrows that role.
  (let [registered (set (roles/all-tool-names))]
    (doseq [r (roles/names)
            :let [s (roles/surface r)]
            :when (set? s)
            t s]
      (is (contains? registered t)
          (str r " may use `" t "`, which is not a registered tool")))))
