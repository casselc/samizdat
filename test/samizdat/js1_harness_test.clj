;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.js1-harness-test
  "Static contract tests for the JS1×SmolVM CI harness (ci/js1-smolvm).

  These tests validate DATA and TEXT ONLY: the runtime lock parses as EDN
  and agrees with the repo's own pin authorities (bin/js1, deps.edn), the
  guest recipe is coherent with the lock, the fixtures match the real
  suite's markers, the shell scripts stay inside their discipline (no
  host /tmp, fail-closed), and the workflow is manual-only on self-hosted
  KVM labels. They run offline under plain `jolt -M:test` — no SCI, no
  smolvm, no KVM, no guest — and they NEVER fabricate a guest run. When
  the lock records the pack as :unbuilt they assert exactly that the
  fail-closed refusal is the wired behavior.

  Self-run for quick iteration:
    SAMIZDAT_JS1_HARNESS_TEST_RUN=1 jolt -Srepro run test/samizdat/js1_harness_test.clj"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is] :as t]
            [jolt.fs :as fs]))

(def ^:private project-dir
  (or (jolt.host/getenv "JOLT_PWD") (System/getProperty "user.dir")))

(defn- p [rel] (str project-dir "/" rel))

(def ^:private lock-rel "ci/js1-smolvm/runtime-lock.edn")
(def ^:private recipe-rel "ci/js1-smolvm/guest-recipe.edn")
(def ^:private fixtures-rel "test/fixtures/js1/fixtures.edn")
(def ^:private dogfood-contract-rel
  "test/fixtures/js1-dogfood/contract.edn")

(def ^:private harness-scripts
  ["ci/js1-smolvm/lib-lock.sh" "ci/js1-smolvm/preflight.sh"
   "ci/js1-smolvm/producer-gate.sh" "ci/js1-smolvm/consumer-run.sh"
   "ci/js1-smolvm/guest-setup.sh" "ci/js1-smolvm/build-guest-pack.sh"])

(defn- slurp-rel
  "Slurp a repo file, throwing ex-info when absent (a missing harness file
  must error the suite loudly, not skip)."
  [rel]
  (let [f (p rel)]
    (when-not (fs/exists? f)
      (throw (ex-info (str "missing harness file: " rel) {:path f})))
    (slurp f)))

(def ^:private lock (edn/read-string (slurp-rel lock-rel)))
(def ^:private recipe (edn/read-string (slurp-rel recipe-rel)))
(def ^:private fixtures (edn/read-string (slurp-rel fixtures-rel)))
(def ^:private dogfood-contract
  (edn/read-string (slurp-rel dogfood-contract-rel)))
(def ^:private wrapper-src (slurp-rel "bin/js1"))
(def ^:private deps-src (slurp-rel "deps.edn"))

(defn- wrapper-var
  "The shell assignment value of VAR in bin/js1."
  [v]
  (second (re-find (re-pattern (str "(?m)^" v "=(\\S+)$")) wrapper-src)))

(defn- lock-line
  "The raw lock line for KEY — the shell gates grep exactly these lines,
  so the test asserts the one-scalar-per-line discipline holds (both
  readers must always see the same bytes). Lock keys contain only
  [a-z0-9/-], so the key itself is regex-safe."
  [k]
  (second (re-find (re-pattern (str "(?m)^ " k " (\\S.*)$"))
                   (slurp-rel lock-rel))))

;; ── lock ↔ repo pin authorities ──────────────────────────────────────────

(deftest lock-matches-wrapper-pins
  (testing "the lock restates bin/js1's pins exactly (no drift, no second truth)"
    (is (= "4af2362176160f2ed0e366689d7232b1a38adfec" (:jolt/sha lock)))
    (is (= (:jolt/sha lock) (wrapper-var "JOLT_SHA")))
    (is (= (:jolt/branch lock) (wrapper-var "JOLT_BRANCH")))
    (is (= (:jolt/url lock) (wrapper-var "JOLT_URL")))
    (is (= "js1-runtime-current-upstream" (:jolt/branch lock)))
    (is (= "32d62a5136ad3dc148588752f5bcc4cc30b14752" (:sci/sha lock)))
    (is (= (:sci/sha lock) (wrapper-var "SCI_SHA")))
    (is (= "0.13.53" (:sci/version lock)))
    (is (= (:sci/version lock) (wrapper-var "SCI_VERSION")))))

(deftest docs-name-the-wrapper-pins
  (testing "docs/JS1_RUNTIME.md § Pins names the pinned runtime (doc drift guard)"
    (let [doc (slurp-rel "docs/JS1_RUNTIME.md")]
      (is (str/includes? doc (wrapper-var "JOLT_SHA"))
          "the docs no longer name bin/js1's JOLT_SHA")
      (is (str/includes? doc (wrapper-var "JOLT_BRANCH"))
          "the docs no longer name bin/js1's JOLT_BRANCH"))))

(deftest lock-matches-deps-crypto-pin
  (testing "the lock's jolt-crypto pin equals deps.edn's :git/sha (its single home)"
    (let [m (re-find #"(?s)jolt-lang/jolt-crypto.*?\"([0-9a-f]{40})\"" deps-src)]
      (is (some? m) "deps.edn carries a jolt-crypto :git/sha")
      (is (= "1ab72aa5f73be7ec41f01086953ffb43ecd3d84e" (second m)))
      (is (= (second m) (:jolt-crypto/sha lock))))))

(deftest lock-is-shell-greppable
  (testing "every pin the shell gates read sits alone on its line"
    (doseq [k [:lock/version :platform/os :platform/arch :jolt/sha :sci/sha
               :sci/version :jolt-crypto/sha :smolvm/version
               :smolvm/bin-sha256 :guest-pack/status :guest-pack/sha256
               :machine/cpus :machine/mem-mib :deadline/total-seconds
               :log/max-step-bytes]]
      (is (some? (lock-line k)) (str "lock line missing for " k))))
  (testing "platform is exactly linux/x86_64 + /dev/kvm — never silently retargeted"
    (is (= "linux" (:platform/os lock)))
    (is (= "x86_64" (:platform/arch lock)))
    (is (= "/dev/kvm" (:platform/kvm-device lock))))
  (testing "smolvm is pinned by version AND binary digest"
    (is (= "1.7.5" (:smolvm/version lock)))
    (is (re-matches #"[0-9a-f]{64}" (str (:smolvm/bin-sha256 lock))))))

(deftest lock-pack-gate-is-fail-closed
  (testing "the pack status is a known keyword and the sha256 shape agrees"
    (is (contains? #{:unbuilt :built} (:guest-pack/status lock)))
    (if (= :unbuilt (:guest-pack/status lock))
      (is (nil? (:guest-pack/sha256 lock))
          ":unbuilt means NO digest is asserted — a run may not be fabricated")
      (is (re-matches #"[0-9a-f]{64}" (str (:guest-pack/sha256 lock)))
          ":built requires a real sha256 pin")))
  (testing "the producer gate refuses an unbuilt/absent pack with the remedy"
    (let [src (slurp-rel "ci/js1-smolvm/producer-gate.sh")]
      (is (str/includes? src "the JS1 guest pack is not provisioned"))
      (is (str/includes? src "build-guest-pack.sh"))
      (is (str/includes? src "never fabricates one"))
      ;; the build-lane exemption is explicit and scoped to the build only
      (is (str/includes? src "JS1_SMOLVM_BUILD_LANE")))))

(deftest lock-bounds-are-bounded
  (testing "every deadline is a positive int and the total caps the lane"
    (doseq [k [:deadline/preflight-seconds :deadline/producer-seconds
               :deadline/setup-seconds :deadline/check-seconds
               :deadline/smoke-seconds :deadline/boundary-seconds
               :deadline/teardown-seconds :deadline/total-seconds]]
      (is (and (integer? (get lock k)) (pos? (get lock k)))
          (str "deadline missing or non-positive: " k)))
    (is (<= (:deadline/total-seconds lock) 2700)
        "the lane's total deadline fits the workflow's timeout-minutes 45"))
  (testing "machine + log bounds are explicit and modest"
    (is (<= 1 (:machine/cpus lock) 8))
    (is (<= 512 (:machine/mem-mib lock) 16384))
    (is (<= 65536 (:log/max-step-bytes lock) (* 64 1024 1024)))))

;; ── recipe coherence ─────────────────────────────────────────────────────

(deftest recipe-agrees-with-lock
  (testing "the recipe restates the same runtime pins"
    (is (= (:jolt/sha lock) (get-in recipe [:jolt-runtime :sha])))
    (is (= (:sci/sha lock) (get-in recipe [:jolt-runtime :submodule :sha])))
    (is (= (:sci/version lock) (get-in recipe [:jolt-runtime :submodule :version])))
    (is (= (get-in recipe [:jolt-runtime :guest-path]) (:guest/jolt-home lock))))
  (testing "chez payload is digest-pinned and threaded"
    (is (re-matches #"[0-9a-f]{64}" (get-in recipe [:chez :payload-sha256])))
    (is (true? (get-in recipe [:chez :threaded])))
    (is (= "10.4.1" (get-in recipe [:chez :version]))))
  (testing "the four SCI jars on the sandbox classpath are sha256-pinned"
    (is (= 4 (count (get-in recipe [:warm-caches :sci-jar-sha256]))))
    (doseq [[artifact sha] (get-in recipe [:warm-caches :sci-jar-sha256])]
      (is (str/ends-with? artifact ".jar"))
      (is (re-matches #"[0-9a-f]{64}" sha) (str "jar pin malformed: " artifact))))
  (testing "the warm git-dep set covers deps.edn's git deps (full-resolve warm)"
    (doseq [lib ["jolt-lang/http-client" "jolt-lang/db" "jolt-lang/nrepl"
                 "jolt-lang/jolt-crypto" "org.clojure/data.json"
                 "jolt-lang/logging" "jolt-lang/time"]]
      (is (re-matches #"[0-9a-f]{40}"
                      (str (get-in recipe [:warm-caches :git-deps lib] "")))
          (str "warm-caches missing git dep " lib))))
  (testing "the guest env contract names the baked runtime"
    (is (= (:guest/jolt-home lock) (get-in recipe [:guest-env "JOLT_HOME"])))
    (is (= (:guest/chez lock) (get-in recipe [:guest-env "JOLT_CHEZ"])))
    (is (= (:guest/home lock) (get-in recipe [:guest-env "HOME"])))))

;; ── fixtures match the real suite ────────────────────────────────────────

(deftest fixtures-match-boundary-suite
  (let [suite (slurp-rel "test/samizdat/js1_boundary_test.clj")]
    (testing "every declared phase is a phase the suite's run-phase! dispatches"
      (doseq [phase (get-in fixtures [:boundary :phases])]
        (is (str/includes? suite (str "\"" phase "\""))
            (str "suite has no phase " phase))))
    (testing "every declared marker string appears in the suite source"
      (doseq [[phase markers] (get-in fixtures [:boundary :phase-markers])
              marker markers]
        (is (str/includes? suite marker)
            (str "suite never prints/asserts marker " marker " for phase " phase))))
    (testing "the non-actuation witness string is the suite's"
      (is (str/includes? suite (get-in fixtures [:boundary :non-actuation-witness]))))
    (testing "the env vars are the suite's"
      (doseq [k [:suite-env :phase-env :dir-env :jolt-bin-env]]
        (is (str/includes? suite (get-in fixtures [:boundary k]))
            (str "suite does not read " (get-in fixtures [:boundary k])))))))

(deftest fixtures-match-smoke-and-wrapper
  (testing "the smoke marker is the suite's own success line"
    (is (str/includes? (slurp-rel "test/samizdat/sandbox_test.clj")
                       (get-in fixtures [:wrapper :smoke-marker]))))
  (testing "the runner fixture drives the boundary suite and propagates exit"
    (let [runner (slurp-rel (get-in fixtures [:boundary :runner]))]
      (is (str/includes? runner "(require 'samizdat.js1-boundary-test)"))
      (is (str/includes? runner "t/run-tests 'samizdat.js1-boundary-test"))
      (is (str/includes? runner "System/exit"))))
  (testing "guest step markers in fixtures are what guest-setup.sh prints"
    (let [gs (slurp-rel "ci/js1-smolvm/guest-setup.sh")]
      (doseq [[_ marker] (:guest-markers fixtures)]
        (is (str/includes? gs marker)
            (str "guest-setup.sh never prints " marker))))))

;; ── bounded live dogfood fixture (static only; NEVER calls a provider) ─────

(deftest dogfood-contract-is-explicitly-opted-in-and-bounded
  (let [suite (slurp-rel "test/samizdat/js1_dogfood_test.clj")
        task (slurp-rel "test/fixtures/js1-dogfood/TASK.md")]
    (testing "all three operator choices are exact and the model is explicit"
      (is (= "SAMIZDAT_JS1_DOGFOOD_TEST"
             (get-in dogfood-contract [:opt-in :flag])))
      (is (= "SAMIZDAT_JS1_DOGFOOD_PREFLIGHT"
             (get-in dogfood-contract [:opt-in :preflight-flag])))
      (is (= {"HARNESS_PROVIDER" "local"
              "HARNESS_BASE_URL" "http://localhost:13305/v1"}
             (get-in dogfood-contract [:opt-in :required])))
      (is (= "HARNESS_MODEL"
             (get-in dogfood-contract [:opt-in :operator-model-env])))
      (doseq [literal ["SAMIZDAT_JS1_DOGFOOD_TEST=1"
                       "HARNESS_PROVIDER=local"
                       "HARNESS_BASE_URL=http://localhost:13305/v1"
                       "HARNESS_MODEL=<operator-selected model>"]]
        (is (str/includes? suite literal) (str "suite lacks " literal))))
    (testing "turn, player, phase, and shared live-run wall-clock bounds are data"
      (is (= 14 (get-in dogfood-contract [:bounds :max-turns])))
      (is (= 1 (get-in dogfood-contract [:bounds :beam-width])))
      (is (<= (get-in dogfood-contract [:bounds :phase-timeout-ms]) 900000))
      (is (<= (get-in dogfood-contract [:bounds :total-timeout-ms]) 1800000))
      (is (= ["preflight" "start" "resume"] (:phases dogfood-contract)))
      (is (str/includes? suite ":max-turns max-turns"))
      (is (str/includes? suite ":beam-width 1")))
    (testing "the task itself names the required discovery and phase boundary"
      (doseq [marker ["complete" "project/" "doc" "project/edit"
                      "symbol `map`"
                      "project/list" "project/search" "project/read"
                      "dogfood-helper" "project/stat" "first anchored edit"
                      "ship-verify" "fresh-process resume"
                      "red-evidence.txt" "second anchored edit"]]
        (is (str/includes? task marker) (str "task lacks " marker))))))

(deftest dogfood-fixture-markers-and-verifier-are-coherent
  (let [suite (slurp-rel "test/samizdat/js1_dogfood_test.clj")
        policy (slurp-rel "test/fixtures/js1-dogfood/verify-policy")
        focused (slurp-rel "test/fixtures/js1-dogfood/test/focused_test.sh")]
    (testing "every declared file exists"
      (doseq [rel (:fixture-files dogfood-contract)]
        (is (fs/exists? (p (str "test/fixtures/js1-dogfood/" rel)))
            (str "dogfood fixture missing " rel))))
    (testing "phase and red/green markers are source facts"
      (doseq [marker (:phase-markers dogfood-contract)]
        (is (str/includes? suite marker) (str "suite lacks marker " marker)))
      (doseq [marker [(get-in dogfood-contract [:verify :red-marker])
                      (get-in dogfood-contract [:verify :green-marker])]]
        (is (str/includes? focused marker) (str "focused test lacks " marker))))
    (testing "trusted policy is fixed fixture argv, never model-derived"
      (is (= "./verify-policy"
             (get-in dogfood-contract [:verify :operator-command])))
      (is (= ["sh" "-c" "./verify-policy"]
             (get-in dogfood-contract [:verify :hardened-argv])))
      (is (str/includes? policy "exec ./test/focused_test.sh"))
      (is (str/includes? suite ":verify-cmd \"./verify-policy\""))
      (is (str/includes? suite ":verify-focused? false")))
    (testing "the deterministic policy owns both RED evidence and GREEN"
      (is (str/includes? focused "cat > red-evidence.txt"))
      (is (str/includes? focused "exit 1"))
      (is (str/includes? focused "exit 0")))))

(deftest dogfood-live-suite-stays-outside-the-trusted-checkout
  (let [suite (slurp-rel "test/samizdat/js1_dogfood_test.clj")]
    (testing "target is a disposable detached worktree and source status is pinned"
      (is (str/includes? suite "worktree\" \"add\" \"--detach"))
      (is (str/includes? suite "target must be outside the trusted source checkout"))
      (is (str/includes? suite "before-source"))
      (is (str/includes? suite "the live dogfood modified the trusted running checkout"))
      (is (str/includes? suite ":unstaged"))
      (is (str/includes? suite ":staged"))
      (is (str/includes? suite ":fenced-bytes")))
    (testing "actual public workflow and resume APIs run in fresh pinned processes"
      (is (str/includes? suite "samizdat.workflow/run!"))
      (is (str/includes? suite "samizdat.agent.resume/resume!"))
      (is (str/includes? suite "bin/js1 path"))
      (is (str/includes? suite "\"-Sdeps\""))
      (is (str/includes? suite "-A:js1-dogfood-live"))
      (is (str/includes? suite "refusing live REPL fallback"))
      (is (not (str/includes? suite "samizdat.repl"))))
    (testing "B0 composes normal dependencies with pin-checked SCI roots"
      (is (str/includes? suite ":production-classpath"))
      (is (str/includes? suite ":js1-classpath"))
      (is (str/includes? suite "distinct-classpath [combined]"))
      (is (str/includes? suite ":local/root sci-root"))
      (is (str/includes? suite "A bare\n  -Scp union is not sufficient"))
      (is (str/includes? suite "data.json, jdbc/db, HTTP"))
      (is (str/includes? suite "bin/js1 path")))
    (testing "B1 separates trusted workflow resolution from target authority"
      (is (str/includes? suite "\"JOLT_PWD\" (str (fs/canonicalize source-dir))"))
      (is (str/includes? suite ":root target"))
      (is (str/includes? suite "child JOLT_PWD is not the trusted source checkout"))
      (is (str/includes? suite "sandbox-root=target")))
    (testing "preflight really compiles and resolves the used API set"
      (is (str/includes? suite "preflight-api-symbols"))
      (doseq [api ["samizdat.workflow/load-loop!"
                   "samizdat.workflow/run!"
                   "samizdat.agent.resume/resume!"
                   "samizdat.agent.resume/reconstruct-js1-binding!"
                   "samizdat.agent.sandbox/evaluate!"
                   "samizdat.agent.sandbox/evaluate-recorded!"
                   "samizdat.store.evals/unsettled-effects"]]
        (is (str/includes? suite api) (str "preflight/harness lacks " api)))
      (is (str/includes? suite "workflow=compiled"))
      (is (str/includes? suite "SAMIZDAT_JS1_DOGFOOD_PREFLIGHT")))
    (testing "N1 freezes at RED and refuses any unsafe durable history"
      (is (str/includes? suite "signal-child! child \"STOP\""))
      (is (str/includes? suite "evals/pending"))
      (is (str/includes? suite "evals/unsettled-effects"))
      (is (str/includes? suite ":unsafe-red-history"))
      (is (str/includes? suite "require-quiescent-db!"))
      (is (str/includes? suite "JS1-DOGFOOD-RED-QUIESCENT")))
    (testing "N2/N3 cleanup and reaping are bounded"
      (is (str/includes? suite "finally (db/close conn)"))
      (is (str/includes? suite ".waitFor p 2000"))
      (is (str/includes? suite ".waitFor p 3000"))
      (is (str/includes? suite ":reap-timeout true"))
      (is (str/includes? suite ":out out-path :err err-path"))
      (is (not (str/includes? suite "assoc @child")))
      (is (str/includes? suite "require-reaped!")))
    (testing "no push, remote provider, source write, or environment dump"
      (is (not (re-find #"git[^\n]*push" suite)))
      (is (not (re-find #"https://(api|open)\." suite)))
      (is (not (str/includes? suite "System/getenv)")))
      (is (not (str/includes? suite "spit source-dir"))))
    (testing "direct semantic counters bracket replay and enforce no repeats"
      (is (= [:project/list :project/search
              :project/read :project/read :project/read
              :project/stat :project/edit]
             (mapv :op (get-in dogfood-contract
                                [:semantic-operations :before-resume
                                 :required-subsequence]))))
      (is (= {:project/list 1 :project/search 1 :project/read 3}
             (get-in dogfood-contract
                     [:semantic-operations :before-resume :minimum-counts])))
      (is (= {:project/stat 1 :project/edit 1}
             (get-in dogfood-contract
                     [:semantic-operations :before-resume :exact-counts])))
      (is (= {:op :project/edit
              :path "src/dogfood.clj"
              :base :sha256
              :content "(ns fixture.dogfood)\n\n(def dogfood-state :red)\n"}
             (last (get-in dogfood-contract
                           [:semantic-operations :before-resume
                            :required-subsequence]))))
      (is (= #{:project/list :project/search :project/read}
             (get-in dogfood-contract
                     [:semantic-operations :before-resume
                      :additional-before-edit])))
      (is (= [:project/read :project/stat :project/edit]
             (mapv :op (get-in dogfood-contract
                                [:semantic-operations :resume-exact]))))
      (is (str/includes? suite "required-subsequence?"))
      (is (str/includes? suite "validate-pre-red-events!"))
      (is (str/includes? suite "validate-resume-events!"))
      (is (str/includes? suite "phase-events counter-path \"resume\""))
      (is (str/includes? suite "record-intent! is called"))
      (is (str/includes? suite "when-not (= before after)")))))

;; ── shell harness discipline ─────────────────────────────────────────────

(deftest harness-never-uses-host-tmp
  (testing "no ci/js1-smolvm script calls mktemp or writes literal /tmp paths"
    (doseq [rel harness-scripts]
      (let [src (slurp-rel rel)]
        (is (not (str/includes? src "mktemp")) (str rel " uses mktemp"))
        ;; /tmp may appear only in refusal text, the guard pattern itself,
        ;; or guest-side VM-local paths — never as a host write target.
        (doseq [line (str/split-lines src)]
          (when (str/includes? line "/tmp")
            (is (or (str/includes? line "never") (str/includes? line "not ")
                    (str/includes? line "refuses") (str/includes? line "guest")
                    (str/includes? line "VM-local") (str/includes? line "warm")
                    (str/includes? line "/var/tmp"))
                (str rel " mentions /tmp outside an allowed context: " line)))))))
  (testing "the CI dir is required, absolute, and refused under /tmp"
    (let [src (slurp-rel "ci/js1-smolvm/lib-lock.sh")]
      (is (str/includes? src "JS1_SMOLVM_CI_DIR is unset"))
      (is (str/includes? src "must be absolute"))
      (is (str/includes? src "/tmp|/tmp/*|/var/tmp|/var/tmp/*")))))

(deftest consumer-is-bounded-and-clean
  (let [src (slurp-rel "ci/js1-smolvm/consumer-run.sh")
        lib (slurp-rel "ci/js1-smolvm/lib-lock.sh")
        gs (slurp-rel "ci/js1-smolvm/guest-setup.sh")]
    (testing "guest is network-disabled, source read-only, scratch VM-local"
      (is (str/includes? src ":network :disabled"))
      (is (str/includes? src ":ro\" --cpus"))
      (is (str/includes? src "'\"network\":false'"))
      (is (str/includes? src "'\"mounts\":1'")))
    (testing "teardown is trapped and forced, with its own budget"
      (is (str/includes? src "trap teardown EXIT INT TERM"))
      (is (str/includes? src "machine delete --name \"$MACHINE_NAME\" --force")))
    (testing "every exec is host-timeout wrapped and guest-timeout bounded"
      (is (str/includes? lib "timeout -s TERM -k 30"))
      (is (str/includes? src "--timeout \"${_to}s\"")))
    (testing "invokes exactly the required evidence: check, smoke, boundary=1"
      (is (str/includes? gs "bin/js1 check"))
      (is (str/includes? gs "bin/js1 smoke"))
      (is (str/includes? gs "SAMIZDAT_JS1_BOUNDARY_TEST=1")))
    (testing "no producer shortcut: the consumer re-runs both gates"
      (is (str/includes? src "preflight.sh"))
      (is (str/includes? src "producer-gate.sh")))))

;; ── workflow: manual, self-hosted KVM, non-blocking ──────────────────────

(deftest workflow-is-manual-and-non-blocking
  (let [wf (slurp-rel ".github/workflows/js1-smolvm.yml")]
    (testing "dispatch-only: no push/PR triggers, so it can never gate a merge"
      (is (str/includes? wf "workflow_dispatch"))
      (is (not (re-find #"(?m)^  push:" wf)))
      (is (not (re-find #"(?m)^  pull_request:" wf))))
    (testing "self-hosted linux/x64/kvm labels"
      (is (str/includes? wf "runs-on: [self-hosted, linux, x64, kvm]")))
    (testing "bounded job, runner-owned CI dir, artifacts always uploaded"
      (is (str/includes? wf "timeout-minutes: 45"))
      (is (str/includes? wf "runner.temp"))
      (is (str/includes? wf "if: always()")))
    (testing "no reusable-workflow or trigger fan-in from other workflows"
      (is (not (str/includes? wf "workflow_call")))
      (is (not (str/includes? wf "workflow_run")))))
  (testing "the ordinary tests workflow is untouched by this lane"
    (let [tests (slurp-rel ".github/workflows/tests.yml")]
      (is (not (str/includes? tests "js1-smolvm")))
      (is (not (str/includes? tests "smolvm"))))))

;; ── inventory agreement ──────────────────────────────────────────────────

(deftest inventory-files-exist
  (doseq [[k v] lock]
    (when (= "inventory" (namespace k))
      (is (fs/exists? (p v)) (str "inventory missing: " v " (" k ")")))))

;; ── self-run ─────────────────────────────────────────────────────────────

(when (= "1" (jolt.host/getenv "SAMIZDAT_JS1_HARNESS_TEST_RUN"))
  (let [{:keys [fail error] :as summary}
        (t/run-tests 'samizdat.js1-harness-test)]
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
