;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.ship-test
  "The `done` gate's verify wiring over the structured seam: an adversarial
  test-file name with no trusted fallback REFUSES the ship (fail closed —
  the model cannot disable verification by naming a file hostilely); with a
  configured fallback the operator's command runs as an exact structured
  request untouched by any model-reachable text; a clean focused change
  runs the derived argv; and a loop with verification off behaves exactly
  as before. The scoped primitive is stubbed with a git-delegating fake so
  the assertions see the exact request the gate would spawn.

  Requires the ordinary classpath (the done method loads the journal);
  self-run from the repo root with jolt on PATH (the JS1 classpath
  deliberately lacks the journal/db deps):
    SAMIZDAT_SHIP_TEST_RUN=1 jolt -Srepro -A:dev run test/samizdat/ship_test.clj"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.ship]
            [samizdat.agent.verify :as verify]
            [samizdat.engine.proc :as proc]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(def real-scope-run proc/*scope-run*)

(defn- temp-dir [tag]
  (str (System/getProperty "java.io.tmpdir") "/samizdat-ship-" tag "-"
       (System/currentTimeMillis) "-" (rand-int 100000)))

(defn- sh
  [dir & cmd]
  (proc/scope-run {:cmd (vec cmd) :dir dir
                   :env (proc/scrubbed-allowlist-env)
                   :timeout-ms 15000 :term-grace-ms 2000
                   :out-bytes 65536 :err-bytes 65536}))

(defn- init-repo!
  [dir]
  (.mkdirs (java.io.File. dir))
  (sh dir "git" "init" "-q")
  (sh dir "git" "config" "user.email" "t@t.co")
  (sh dir "git" "config" "user.name" "t")
  (spit (str dir "/seed.txt") "seed\n")
  (sh dir "git" "add" "-A")
  (sh dir "git" "commit" "-qm" "init"))

(defn- done-ctx
  "A minimal `done` context: no conn/run-id (journal writes skip), a branch
  with no artifacts and no problem (the lexical rungs are inert), the run
  `config` under test, and the repo `root`/`baseline` the gate diffs."
  [root baseline config]
  {:tool-name "done"
   :branch {:id "b1" :artifacts [] :problem nil}
   :turn 3
   :root root
   :git-baseline baseline
   :config {:run config}
   :args {:answer "tests pass"}})

(defn- git-or-probe?
  "Whether a stubbed scope request is gitdiff's git (delegated to the real
  primitive) or proc/scope-supported?'s spawn-free capability probe —
  neither is a verification RUN, so the nothing-spawned stubs must not
  count them."
  [req]
  (contains? #{"git" "samizdat-scope-capability-probe"} (first (:cmd req))))

(def ^:private green-run
  {:pid 1 :exit 0 :timed-out false
   :out "Ran 1 tests containing 1 assertions.\n0 failures, 0 errors."
   :out-status :complete :err "" :err-status :complete})

(deftest done-refuses-adversarial-test-names-when-no-trusted-fallback-exists
  (when (proc/available? "git")
    (let [dir (temp-dir "refused")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)]
          ;; the model's move: a test file whose NAME is the payload
          (spit (str dir "/evil';touch pwned_test.clj") "(ns evil)\n")
           (let [r (base/run-tool
                     (done-ctx dir base {:verify-focused? true}))]
            (is (= :failure (:category r)) "fail closed, not a silent pass")
            (is (some? (:done-block r)))
            (is (str/includes? (:result r) "REFUSED"))
            (is (str/includes? (:result r) "evil';touch pwned")
                "the offending path is named back to the worker")
            (is (not (:done? r)))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest done-runs-the-trusted-fallback-as-an-exact-structured-request
  (when (proc/available? "git")
    (let [dir (temp-dir "fallback")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)
              seen (atom nil)]
          (spit (str dir "/evil';touch pwned_test.clj") "(ns evil)\n")
          (binding [proc/*scope-run*
                    (fn [req]
                      (if (git-or-probe? req)
                        (real-scope-run req)   ; gitdiff/probe keep working
                        (do (reset! seen req) green-run)))]
            (let [r (base/run-tool
                      (done-ctx dir base
                                {:verify-focused? true
                                 :verify-cmd "jolt -M:test --some 'operator quoting'"
                                 :verify-timeout-ms 4321}))]
              (testing "the fallback green ships"
                (is (:done? r))
                (is (= :success (:category r))))
              (testing "the request is exactly the operator's command, structured"
                (is (some? @seen))
                (is (= ["sh" "-c" "jolt -M:test --some 'operator quoting'"]
                       (:cmd @seen))
                    "the config string verbatim as the single sh -c operand")
                (is (= dir (:dir @seen)) "cwd is the project root")
                (is (= 4321 (:timeout-ms @seen)) "timeout is operator config")
                (is (= verify/term-grace-ms (:term-grace-ms @seen)))
                (is (= verify/out-bytes (:out-bytes @seen)))
                (is (= verify/err-bytes (:err-bytes @seen)))
                (testing "the child env is the scrubbed allowlist"
                  (is (contains? (:env @seen) "PATH"))
                  (is (every? #{"PATH" "HOME" "LANG" "LC_ALL" "LC_CTYPE"
                                "TERM" "TMPDIR"}
                              (keys (:env @seen)))))
                (is (not (str/includes? (pr-str (:cmd @seen)) "evil"))
                    "no model-reachable text entered the request")
                (is (= #{:cmd :dir :env :timeout-ms :term-grace-ms
                         :out-bytes :err-bytes}
                       (set (keys @seen))))))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest done-runs-the-focused-request-as-derived-argv
  (when (proc/available? "git")
    (let [dir (temp-dir "focused")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)
              seen (atom nil)]
          (spit (str dir "/src.clj") "(ns src)\n")
          (spit (str dir "/good_test.clj") "(ns good-test)\n")
          (binding [proc/*scope-run*
                    (fn [req]
                      (if (git-or-probe? req)
                        (real-scope-run req)
                        (do (reset! seen req) green-run)))]
            (let [r (base/run-tool
                      (done-ctx dir base {:verify-focused? true}))]
              (is (:done? r) "a green focused run ships")
              (is (some? @seen))
              (is (= "jolt" (first (:cmd @seen))))
              (is (= ["jolt" "-A:test" "-e"] (subvec (:cmd @seen) 0 3)))
              (is (str/includes? (nth (:cmd @seen) 3) "good-test")
                  "the touched namespace is required")
              (is (not (str/includes? (nth (:cmd @seen) 3) "src.clj"))
                  "non-test paths do not ride along")
              (is (not (some #{"sh" "-c"} (:cmd @seen)))
                  "the focused path is pure argv, no shell"))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest done-feeds-back-a-red-focused-run
  (when (proc/available? "git")
    (let [dir (temp-dir "red")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)]
          (spit (str dir "/good_test.clj") "(ns good-test)\n")
          (binding [proc/*scope-run*
                    (fn [req]
                      (if (git-or-probe? req)
                        (real-scope-run req)
                        {:pid 1 :exit 1 :timed-out false
                         :out "FAIL in good-test (good_test.clj:3)\nexpected: 2\nactual: 3"
                         :out-status :complete :err "" :err-status :complete}))]
            (let [r (base/run-tool
                      (done-ctx dir base {:verify-focused? true}))]
              (is (not (:done? r)))
              (is (= :failure (:category r)))
              (is (str/includes? (:result r) "not green"))
              (is (str/includes? (:result r) "FAIL in good-test")
                  "the failure tail reaches the branch"))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest done-is-unchanged-when-verification-is-not-configured
  (when (proc/available? "git")
    (let [dir (temp-dir "off")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)
              seen (atom nil)]
          (spit (str dir "/good_test.clj") "(ns good-test)\n")
          (binding [proc/*scope-run*
                    (fn [req]
                      (if (git-or-probe? req)
                        (real-scope-run req)
                        (do (reset! seen req) green-run)))]
            (let [r (base/run-tool (done-ctx dir base {}))]
              (is (:done? r) "no verify config: the rung is inert, as before")
              (is (nil? @seen) "nothing but git ever spawned"))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

;; --- the execution boundary fails closed through the gate ----------------------

(deftest done-blocks-when-the-runtime-cannot-run-scoped-verification
  ;; Off-Linux / missing primitive, simulated at the capability the gate
  ;; probes: verification must come back UNAVAILABLE and the ship must be
  ;; blocked — never silently permitted through the cannot-tell trust
  ;; fallthrough that a dead executor would otherwise produce.
  (when (proc/available? "git")
    (let [dir (temp-dir "unsup")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)]
          (spit (str dir "/good_test.clj") "(ns good-test)\n")
          (with-redefs [proc/scope-supported? (constantly false)]
            (let [r (base/run-tool (done-ctx dir base {:verify-focused? true}))]
              (is (not (:done? r)) "never silently permitted")
              (is (= :failure (:category r)))
               (is (str/includes? (:result r) "UNAVAILABLE"))
               (is (str/includes? (:result r) "scoped process execution")))))
         (finally
           (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest done-blocks-on-a-malformed-verify-cmd-config
  ;; A non-string :verify-cmd is an operator error: the gate must BLOCK
  ;; with the misconfiguration named, not silently skip verification (the
  ;; old behavior when the fallback derivation read 'unconfigured').
  (when (proc/available? "git")
    (let [dir (temp-dir "badcfg")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)
              seen (atom nil)]
          (spit (str dir "/good_test.clj") "(ns good-test)\n")
          (binding [proc/*scope-run*
                    (fn [req]
                      (if (git-or-probe? req)
                        (real-scope-run req)
                        (do (reset! seen req) green-run)))]
            (let [r (base/run-tool
                      (done-ctx dir base {:verify-cmd [:jolt "-M:test"]}))]
              (is (not (:done? r)))
              (is (= :failure (:category r)))
              (is (str/includes? (:result r) "MISCONFIGURED"))
              (is (str/includes? (:result r) "verify-cmd"))
              (is (nil? @seen) "nothing but git ever spawned"))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

;; --- the durable ship-verify journal record ------------------------------------

(defn- ship-verify-rows
  "The :ship-verify events of `run-id`, newest last."
  [conn run-id]
  (filter #(= "ship-verify" (:kind %)) (journal/events-since conn run-id 0)))

(defn- with-run-db
  "Open an in-memory run database, open the run + branch the gate journals
  against, run `body` over [conn run-id], close."
  [body]
  (let [c (db/open! ":memory:")]
    (try
      (let [rid (runs/start-run! c {:problem "p"})]
        (runs/open-branch! c rid {:branch-id "b1"})
        (body c rid))
      (finally (db/close c)))))

(deftest refused-verification-is-journalled-durably-and-blocks
  (when (proc/available? "git")
    (let [dir (temp-dir "jrnl-refused")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)]
          (spit (str dir "/evil';touch pwned_test.clj") "(ns evil)\n")
          (with-run-db
            (fn [c rid]
              (let [r (base/run-tool (assoc (done-ctx dir base {:verify-focused? true})
                                            :conn c :run-id rid))
                    rows (ship-verify-rows c rid)]
                (is (not (:done? r)) "blocked")
                (is (= 1 (count rows))
                    "the refusal has exactly one durable ship-verify row")
                (let [data (json/read-str (:data (first rows)) :key-fn keyword)]
                  (is (false? (:green data)))
                  (is (= "refused" (:kind data)))
                  (is (true? (:blocked data)))
                  (is (some #(str/includes? % "evil';touch pwned")
                            (map str (:refused data)))
                      "the refused paths are in the durable record"))))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest unavailable-verification-is-journalled-durably-and-blocks
  (when (proc/available? "git")
    (let [dir (temp-dir "jrnl-unsup")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)]
          (spit (str dir "/good_test.clj") "(ns good-test)\n")
          (with-redefs [proc/scope-supported? (constantly false)]
            (with-run-db
              (fn [c rid]
                (let [r (base/run-tool (assoc (done-ctx dir base {:verify-focused? true})
                                              :conn c :run-id rid))
                      rows (ship-verify-rows c rid)]
                  (is (not (:done? r)))
                  (is (= 1 (count rows)) "the unavailability is recorded")
                  (let [data (json/read-str (:data (first rows)) :key-fn keyword)]
                    (is (false? (:green data)))
                    (is (true? (:unsupported? data)))
                    (is (true? (:blocked data)))))))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

(deftest a-run-verification-is-journalled-with-its-kind
  (when (proc/available? "git")
    (let [dir (temp-dir "jrnl-run")]
      (try
        (init-repo! dir)
        (let [base (gitdiff/baseline dir)
              seen (atom nil)]
          (spit (str dir "/good_test.clj") "(ns good-test)\n")
          (binding [proc/*scope-run*
                    (fn [req]
                      (if (git-or-probe? req)
                        (real-scope-run req)
                        (do (reset! seen req) green-run)))]
            (with-run-db
              (fn [c rid]
                (let [r (base/run-tool (assoc (done-ctx dir base {:verify-focused? true})
                                              :conn c :run-id rid))
                      rows (ship-verify-rows c rid)]
                  (is (:done? r) "the green stubbed run ships")
                  (is (= 1 (count rows)))
                  (let [data (json/read-str (:data (first rows)) :key-fn keyword)]
                    (is (true? (:green data)))
                    (is (= "focused" (:kind data)))
                    (is (false? (:blocked data)))))))))
        (finally
          (sh (System/getProperty "java.io.tmpdir") "rm" "-rf" dir))))))

;; --- self-run for the focused lane ---------------------------------------------

(when (= "1" (jolt.host/getenv "SAMIZDAT_SHIP_TEST_RUN"))
  (let [{:keys [fail error] :as summary} (run-tests 'samizdat.ship-test)]
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
