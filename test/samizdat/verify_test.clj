;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.verify-test
  "The ship gate's test rung: the pure decision that makes `done` a hard gate on
  a green test run, the focused-request derivation that keeps the loop fast,
  and the hardening that closed the critical verify-path injection of
  code-review 2026-08 pass 3 (docs/code-review-2026-08-3.md finding 1):

  - adversarial changed paths (quote breakouts, semicolons, shell
    metacharacters, spaces, Unicode, traversal, casing, dot segments) REFUSE
    focused verification — never a silent skip, never a passthrough — and the
    only fallback is the operator's already-configured :verify-cmd, which no
    model-reachable text ever enters;
  - execution is one STRUCTURED request (exact argv/cwd/env/timeout/caps,
    no shell on the focused path) through proc/scope-run;
  - the child environment is the explicit scrubbed allowlist — a synthetic
    secret never reaches it — and the ENTIRE output is redacted before any
    model-visible rendering;
  - stdout/stderr are capped independently, a timeout kills a TERM-resistant
    tree with no survivor, and the source discipline (no shell composition on
    the verify path) is asserted statically so it cannot quietly return.

  Real-spawn sections gate on Linux (the scoped primitive's own platform
  bound). Self-run on the recorded JS1 classpath (from the repo root; jolt
  is located exactly as bin/js1 locates it — $JOLT_HOME, else the sibling
  ../jolt checkout — and the classpath is the one bin/js1 path records):
    SAMIZDAT_VERIFY_TEST_RUN=1 ../jolt/bin/jolt -Srepro -Scp \"$(bin/js1 path)\" run test/samizdat/verify_test.clj"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [samizdat.agent.verify :as verify]
            [samizdat.engine.proc :as proc]))

(def ^:private linux?
  (str/includes? (str (System/getProperty "os.name")) "Linux"))

(def real-scope-run proc/*scope-run*)

(defn- proc-live? [pid]
  (when pid
    (try
      (let [s (slurp (str "/proc/" pid "/stat"))
            rp (.lastIndexOf s ")")]
        (and (>= rp 0)
             (> (count s) (+ rp 2))
             (not (contains? #{"Z" "X" "x"} (subs s (+ rp 2) (+ rp 3))))))
      (catch Exception _ false))))

;; --- the pure derivations ------------------------------------------------------

(deftest test-file?-recognises-test-and-spec-paths
  (is (verify/test-file? "test/samizdat/foo_test.clj"))
  (is (verify/test-file? "src/samizdat/foo_spec.clj"))
  (is (verify/test-file? "test/samizdat/agent/decompose_test.clj"))
  (is (not (verify/test-file? "src/samizdat/agent/tools/knowledge.clj")))
  (is (not (verify/test-file? "resources/cells/feature.clj"))))

(deftest ns-from-test-path-maps-a-path-to-its-namespace
  (is (= "samizdat.agent.decompose-test"
         (verify/ns-from-test-path "test/samizdat/agent/decompose_test.clj")))
  (is (= "samizdat.knowledge-test"
         (verify/ns-from-test-path "test/samizdat/knowledge_test.clj")))
  (is (= "samizdat.js1-boundary-test"
         (verify/ns-from-test-path "test/samizdat/js1_boundary_test.clj"))
      "digits ride mid-segment")
  (is (= "samizdat.gui-ops-test"
         (verify/ns-from-test-path "gui/samizdat/gui_ops_test.clj")))
  (is (nil? (verify/ns-from-test-path "resources/skills/repl-workflow.md"))
      "a non-clj path has no namespace")
  (is (nil? (verify/ns-from-test-path "test/fixtures/notes.txt"))
      "a non-clj test-path is not focusable (and not suspicious)"))

(deftest focused-request-is-structured-argv-with-no-shell
  (let [req (verify/verify-request
              {:changed ["src/samizdat/agent/tools/knowledge.clj"
                         "test/samizdat/knowledge_test.clj"]
               :focused? true})]
    (is (= :focused (:kind req)))
    (is (vector? (:argv req)))
    (is (= 4 (count (:argv req))))
    (is (= ["jolt" "-A:test" "-e"] (subvec (:argv req) 0 3))
        "the program and its flags are fixed derivation, not input")
    (is (string? (nth (:argv req) 3)) "the expression is ONE argv element")
    (is (str/includes? (nth (:argv req) 3) "samizdat.knowledge-test")
        "targets the touched test ns")
    (is (not (str/includes? (nth (:argv req) 3) "knowledge.clj"))
        "path text does not ride along")
    (is (not (str/includes? (nth (:argv req) 3) "-M:test"))
        "does NOT run the whole suite")
    (is (not (some #{"sh" "bash" "-c"} (:argv req)))
        "no shell, no -c: the focused path is pure argv")
    (is (= ["samizdat.knowledge-test"] (:nses req))))
  (testing "nothing focusable => nil with no fallback; the config fallback with one"
    (is (nil? (verify/verify-request {:changed ["src/samizdat/only_src.clj"]
                                      :focused? true})))
    (is (nil? (verify/verify-request {:changed [] :focused? true})))
    (is (nil? (verify/verify-request {:changed nil :focused? true})))
    (is (= :fallback (:kind (verify/verify-request
                              {:changed ["src/samizdat/only_src.clj"]
                               :focused? true
                               :fallback-cmd "jolt -M:test"})))))
  (testing "focused? off => the configured fallback only"
    (is (nil? (verify/verify-request {:changed ["test/x_test.clj"]
                                      :focused? false})))
    (is (= :fallback (:kind (verify/verify-request
                              {:changed ["test/x_test.clj"] :focused? false
                               :fallback-cmd "make verify"})))))
  (testing "a blank-string fallback config is not a fallback; a NON-STRING one is
            an :invalid request that blocks (fail closed, never skipped)"
    (is (nil? (verify/verify-request {:changed ["src/samizdat/only_src.clj"]
                                      :focused? true :fallback-cmd "  "})))
    (is (= :invalid (:kind (verify/verify-request
                             {:changed ["src/samizdat/only_src.clj"]
                              :focused? true :fallback-cmd
                              ["jolt" "-M:test"]})))))
  (testing "a non-Clojure test path is neither focusable nor refusable"
    (let [req (verify/verify-request {:changed ["test/fixtures/data.json"]
                                      :focused? true})]
      (is (nil? req) "a fixture change falls back / not-runs as before"))))

;; --- adversarial changed paths refuse focused verification --------------------

(def ^:private adversarial-paths
  ["test/foo';touch pwned_test.clj"        ;; the review's quote breakout
   "test/foo'; rm -rf /; echo '_test.clj"
   "test/a b_test.clj"                      ;; spaces
   "test/a;b_test.clj"                      ;; semicolon
   "test/a|b_test.clj"                      ;; pipe
   "test/a&b_test.clj"                      ;; ampersand
   "test/a$b_test.clj"                      ;; interpolation
   "test/a>b_test.clj"                      ;; redirection
   "test/a<b_test.clj"
   "test/a`id`_test.clj"                    ;; backtick substitution
   "test/a\\b_test.clj"                     ;; backslash (a legal Linux byte)
   "test/a\"b_test.clj"
   "test/a\tb_test.clj"
   "test/ünïcode_test.clj"                  ;; non-ASCII
   "test/日本語_test.clj"
   "test/../src/evil_test.clj"              ;; traversal
   "../test/evil_test.clj"
   "/abs/evil_test.clj"                     ;; absolute
   "test/-dash_test.clj"                    ;; leading dash (option shape)
   "test/.hidden_test.clj"                  ;; dot file
   "test/UPPER_test.clj"                    ;; casing outside the grammar
   "test/x_test.CLJ"                        ;; case-warped extension
   "test/dot./path_test.clj"                ;; dot segment
   "test/a..b_test.clj"                     ;; empty dot segment
   "test/2026_test.clj"])                   ;; leading-digit segment

(deftest adversarial-names-refuse-focused-verification
  (doseq [p adversarial-paths]
    (testing p
      (is (verify/test-file? p)
          "fixture sanity: the path really claims to be a test, else it is
           ignored rather than refused")
      (is (nil? (verify/ns-from-test-path p)) "unrepresentable under the grammar")
      (let [req (verify/verify-request
                  {:changed ["test/samizdat/good_test.clj" p] :focused? true})]
        (is (= :refused (:kind req))
            "one bad path refuses the WHOLE focused derivation")
        (is (some #{p} (:refused req)) "the offending path is named")))))

(deftest refusal-uses-only-a-trusted-already-configured-fallback
  (let [operator-cmd "jolt -M:test --exclude 'operator quoting stays intact'"
        req (verify/verify-request
              {:changed ["test/foo';touch pwned_test.clj"]
               :focused? true :fallback-cmd operator-cmd})]
    (is (= :fallback (:kind req)) "the configured fallback absorbs the refusal")
    (is (= ["sh" "-c" operator-cmd] (:argv req))
        "the operator string runs VERBATIM as the single sh -c operand")
    (is (not (str/includes? (pr-str (:argv req)) "touch pwned"))
        "no model-reachable text entered the fallback argv")
    (is (not (str/includes? (pr-str (:argv req)) "foo'"))))
  (testing "without a configured fallback the refusal stands"
    (is (= :refused (:kind (verify/verify-request
                             {:changed ["test/foo';touch pwned_test.clj"]
                              :focused? true}))))))

;; --- run-verify sends exactly the structured request --------------------------

(deftest run-verify-sends-exactly-the-structured-request
  (let [seen (atom ::none)
        argv ["jolt" "-A:test" "-e" "(java.lang.System/exit 0)"]
        env {"PATH" "/usr/bin" "HARNESS_API_KEY" "zz-supersecret-zz"}]
    (binding [proc/*scope-run*
              (fn [req] (reset! seen req)
                {:pid 1 :exit 0 :timed-out false
                 :out "ok" :out-status :complete
                 :err "" :err-status :complete})]
      (let [r (verify/run-verify "/proj/root" {:kind :focused :argv argv}
                                 4321 env)]
        (is (:green? r))
        (is (false? (:timeout? r)))
        (is (= argv (:cmd @seen)) "argv passes through verbatim")
        (is (= "/proj/root" (:dir @seen)) "cwd is the root — no cd anywhere")
        (is (= {"PATH" "/usr/bin"} (:env @seen))
            "the child env is the scrubbed allowlist of the source env: the
             API key never reaches the child")
        (is (= 4321 (:timeout-ms @seen)))
        (is (= verify/term-grace-ms (:term-grace-ms @seen)))
        (is (= verify/out-bytes (:out-bytes @seen)))
        (is (= verify/err-bytes (:err-bytes @seen)))
        (is (= #{:cmd :dir :env :timeout-ms :term-grace-ms :out-bytes :err-bytes}
               (set (keys @seen)))
            "no other request knob exists to smuggle through")
        (is (str/includes? (:output r) "ok"))))
    (testing "a nil timeout config falls back to the documented default"
      (binding [proc/*scope-run* (fn [req] (reset! seen req)
                                   {:pid 1 :exit 1 :timed-out false})]
        (verify/run-verify "/r" {:kind :fallback :argv ["sh" "-c" "x"]} nil)
        (is (= verify/default-timeout-ms (:timeout-ms @seen)))))
    (testing "a refused request never spawns"
      (binding [proc/*scope-run* (fn [req] (throw (ex-info "must not run" req)))]
        (let [r (verify/run-verify "/r" {:kind :refused :refused ["test/x_test.clj"]}
                                   1000)]
          (is (false? (:green? r)))
          (is (str/includes? (:output r) "refused")))))))

(deftest run-verify-green-requires-a-clean-exit
  (testing "a stubbed non-zero exit is not green, timed-out or not"
    (binding [proc/*scope-run* (fn [_] {:pid 1 :exit 1 :timed-out false})]
      (is (false? (:green? (verify/run-verify "/r" {:kind :focused :argv ["x"]} 1)))))
    (binding [proc/*scope-run* (fn [_] {:pid 1 :exit 137 :timed-out true})]
      (let [r (verify/run-verify "/r" {:kind :focused :argv ["x"]} 1)]
        (is (false? (:green? r)))
        (is (true? (:timeout? r)))))))

;; --- the execution boundary fails closed ---------------------------------------

(deftest run-verify-fails-closed-when-the-scoped-primitive-is-missing
  ;; The off-Linux / missing-primitive runtime: verification must report
  ;; UNAVAILABLE — never silently skip, never downgrade to an unscoped
  ;; spawn, never inherit an environment.
  (with-redefs [proc/scope-supported? (constantly false)]
    (binding [proc/*scope-run* (fn [_] (throw (ex-info "must not spawn" {})))]
      (let [r (verify/run-verify "/r" {:kind :focused :argv ["x"]} 1000 nil)]
        (is (false? (:green? r)))
        (is (true? (:unsupported? r)))
        (is (str/includes? (:output r) "verification unavailable"))
        (is (str/includes? (:output r) "scoped process"))))))

(deftest verify-block-blocks-an-unavailable-runtime-never-trusts
  (testing "the boundary fact threaded by the ship gate (git cannot tell + no executor)"
    (let [b (verify/verify-block {:verify-on? true :unsupported? true
                                  :result nil :request nil
                                  :changed nil :require-test? true})]
      (is (some? b) "cannot-tell trust must NOT absorb a missing executor")
      (is (str/includes? b "UNAVAILABLE"))))
  (testing "and the same fact reported through the result"
    (let [b (verify/verify-block {:verify-on? true
                                  :result {:green? false :unsupported? true
                                           :output "verification unavailable"}
                                  :request {:kind :focused}
                                  :changed ["test/x_test.clj"]
                                  :require-test? true})]
      (is (some? b))
      (is (str/includes? b "UNAVAILABLE"))))
  (testing "verify-on? false keeps the rung inert (a loop that never verified
            is not a platform failure)"
    (is (nil? (verify/verify-block {:verify-on? false :unsupported? true
                                    :result nil :request nil
                                    :changed nil :require-test? true})))))

;; --- operator misconfiguration fails closed ------------------------------------

(deftest malformed-verify-cmd-is-an-invalid-request-that-blocks
  (doseq [bad [[:jolt "-M:test"] :keyword 123 {:a 1}]]
    (testing (pr-str bad)
      (let [req (verify/verify-request {:changed ["test/x_test.clj"]
                                        :focused? false :fallback-cmd bad})]
        (is (= :invalid (:kind req))
            "a non-string verify-cmd is an operator error, not 'unconfigured'")
        (is (str/includes? (str (:reason req)) "verify-cmd"))))
    (testing "focused runs are poisoned too — misconfig never silently skips"
      (is (= :invalid (:kind (verify/verify-request {:changed ["test/x_test.clj"]
                                                     :focused? true
                                                     :fallback-cmd bad}))))))
  (testing "nil and plain-string configs keep their existing meanings"
    (is (nil? (verify/verify-request {:changed ["test/x_test.clj"]
                                      :focused? false :fallback-cmd nil})))
    (is (= :fallback (:kind (verify/verify-request {:changed ["test/x_test.clj"]
                                                    :focused? false
                                                    :fallback-cmd "make test"})))))
  (testing "run-verify returns a clear invalid result without spawning"
    (binding [proc/*scope-run* (fn [_] (throw (ex-info "must not spawn" {})))]
      (let [r (verify/run-verify "/r" {:kind :invalid
                                       :reason ":run :verify-cmd must be a string"}
                                 1000 nil)]
        (is (false? (:green? r)))
        (is (true? (:invalid-config? r)))
        (is (str/includes? (:output r) "verify-cmd")))))
  (testing "verify-block blocks the invalid request with the operator-facing fix"
    (let [b (verify/verify-block {:verify-on? true
                                  :request {:kind :invalid
                                            :reason ":run :verify-cmd must be a shell-command string"}
                                  :result nil
                                  :changed ["test/x_test.clj"]
                                  :require-test? true})]
      (is (some? b))
      (is (str/includes? b "MISCONFIGURED"))
      (is (str/includes? b "verify-cmd")))))

(deftest nonpositive-verify-timeout-blocks-rather-than-defaults
  (doseq [bad [0 -1 2.5 "30000"]]
    (testing (pr-str bad)
      (binding [proc/*scope-run* (fn [_] (throw (ex-info "must not spawn" {})))]
        (let [r (verify/run-verify "/r" {:kind :focused :argv ["x"]} bad nil)]
          (is (false? (:green? r)) "fail closed")
          (is (true? (:invalid-config? r)) "reported as config, not test failure")
          (is (str/includes? (:output r) "verify-timeout-ms"))
          (is (str/includes? (:output r) "blocked"))))))
  (testing "nil still means the documented default (the legitimate unset case)"
    (let [seen (atom nil)]
      (binding [proc/*scope-run* (fn [req] (reset! seen req)
                                   {:pid 1 :exit 0 :timed-out false})]
        (is (true? (:green? (verify/run-verify "/r" {:kind :focused :argv ["x"]}
                                               nil nil))))
        (is (= verify/default-timeout-ms (:timeout-ms @seen))))))
  (testing "verify-block renders the misconfiguration as a block"
    (let [b (verify/verify-block {:verify-on? true
                                  :request {:kind :focused}
                                  :result {:green? false :invalid-config? true
                                           :output "verification misconfigured: :run :verify-timeout-ms must be a positive integer"}
                                  :changed ["test/x_test.clj"]
                                  :require-test? true})]
      (is (some? b))
      (is (str/includes? b "MISCONFIGURED"))
      (is (str/includes? b "verify-timeout-ms")))))

;; --- capture statuses are annotated honestly (pure, stubbed primitive) --------

(deftest run-verify-annotates-partial-and-truncated-capture
  (let [stub (fn [m] (fn [_] (merge {:pid 1 :exit 0 :timed-out false} m)))]
    (testing ":partial — a prefix the model can tell is a prefix"
      (binding [proc/*scope-run* (stub {:out "out-prefix" :out-status :partial
                                        :err "err-prefix" :err-status :partial})]
        (let [r (verify/run-verify "/r" {:kind :focused :argv ["x"]} 1000 nil)]
          (is (str/includes? (:output r) "out-prefix"))
          (is (str/includes? (:output r) "[stdout capture incomplete]"))
          (is (str/includes? (:output r) "[stderr capture incomplete]")))))
    (testing ":truncated — the cap is named"
      (binding [proc/*scope-run* (stub {:out "aa" :out-status :truncated})]
        (let [r (verify/run-verify "/r" {:kind :focused :argv ["x"]} 1000 nil)]
          (is (str/includes? (:output r)
                             (str "[stdout truncated at " verify/out-bytes " bytes]"))))))
    (testing ":complete — no marker at all"
      (binding [proc/*scope-run* (stub {:out "clean\n" :out-status :complete
                                        :err "" :err-status :complete})]
        (let [r (verify/run-verify "/r" {:kind :focused :argv ["x"]} 1000 nil)]
          (is (str/includes? (:output r) "clean"))
          (is (not (str/includes? (:output r) "truncated")))
          (is (not (str/includes? (:output r) "incomplete"))))))))

;; --- real execution: child env, redaction, caps, timeout (Linux) --------------

(deftest run-verify-omits-secrets-from-the-child-environment
  (when linux?
    (let [r (verify/run-verify
              "/tmp"
              {:kind :focused
               :argv ["/bin/sh" "-c"
                      "if [ -n \"$SAMIZDAT_ADVERSARIAL_KEY\" ]; then echo LEAKED; else echo CLEAN; fi"]}
              10000
              {"SAMIZDAT_ADVERSARIAL_KEY" "sk-abcdefghijklmnopqrstuv"
               "PATH" "/usr/bin:/bin"})]
      (is (:green? r))
      (is (str/includes? (:output r) "CLEAN"))
      (is (not (str/includes? (:output r) "LEAKED")))
      (is (not (str/includes? (:output r) "sk-abcdefghijklmnopqrstuv"))
          "the synthetic secret is absent from env AND output"))))

(deftest run-verify-redacts-the-entire-output-before-rendering
  (when linux?
    (testing "a vendor-shaped credential printed by the child is redacted"
      (let [r (verify/run-verify
                "/tmp"
                {:kind :focused
                 :argv ["/bin/sh" "-c"
                        "echo token=sk-abcdefghijklmnopqrst; echo done"]}
                10000 nil)]
        (is (str/includes? (:output r) "[REDACTED]"))
        (is (not (str/includes? (:output r) "sk-abcdefghijklmnopqrst")))
        (is (str/includes? (:output r) "done") "non-secret text survives")))
    (testing "an opaque known value from the source env is redacted by value"
      (let [r (verify/run-verify
                "/tmp"
                {:kind :focused
                 :argv ["/bin/sh" "-c" "echo zz-opaque-known-secret-9"]}
                10000 {"HARNESS_TOKEN" "zz-opaque-known-secret-9"
                       "PATH" "/usr/bin:/bin"})]
        (is (not (str/includes? (:output r) "zz-opaque-known-secret-9")))
        (is (str/includes? (:output r) "[REDACTED]"))))))

(deftest run-verify-caps-and-annotates-truncated-output
  (when linux?
    (let [r (verify/run-verify
              "/tmp"
              {:kind :focused
               :argv ["/bin/sh" "-c"
                      "echo OUT-START; while :; do echo 0123456789012345678901234567890123456789; done"]}
              30000 nil)]
      (is (false? (:green? r))
          "the overflow abort ended the run — a truncated log is not green")
      (is (str/includes? (:output r) "OUT-START") "the true prefix is retained")
      (is (str/includes? (:output r) "truncated")
          "the truncation is announced to the model")
      (is (<= (count (:output r))
              (+ verify/out-bytes verify/err-bytes 200))
          "the rendered output stays bounded by the caps"))))

(deftest run-verify-timeout-kills-a-term-resistant-tree-with-no-survivor
  (when linux?
    (let [pidf (str "/tmp/samizdat-verify-test-"
                    (System/currentTimeMillis) ".pid")]
      (try
        (let [script (str "trap '' TERM; "
                          "/bin/sh -c 'trap \"\" TERM; echo $$ > " pidf
                          "; sleep 60' & wait")
              r (verify/run-verify "/tmp"
                                   {:kind :focused
                                    :argv ["/bin/sh" "-c" script]}
                                   800 nil)
              leaked? (when (.exists (java.io.File. pidf))
                        (proc-live? (str/trim (slurp pidf))))]
          (is (true? (:timeout? r)))
          (is (false? (:green? r)))
          (is (not leaked?)
              "the TERM-resistant grandchild is dead after the timeout")
          (when leaked?
            (try (real-scope-run {:cmd ["/bin/sh" "-c"
                                        (str "kill -9 -" (str/trim (slurp pidf)))]
                                  :env {} :timeout-ms 5000})
                 (catch Throwable _ nil))))
        (finally
          (let [f (java.io.File. pidf)]
            (when (.exists f) (.delete f))))))))

;; --- the focused argv, end to end (only where jolt is PATH-resolvable) --------

(deftest focused-request-runs-green-and-red-through-the-real-runtime
  (when (and linux? (proc/available? "jolt"))
    (let [green (verify/verify-request
                  {:changed ["test/samizdat/verify_fixture_test.clj"]
                   :focused? true})]
      (is (= :focused (:kind green)))
      (let [r (verify/run-verify (System/getProperty "user.dir") green 120000 nil)]
        (is (:green? r) "the real focused run of a green fixture is green")
        (is (str/includes? (:output r) "0 failures")))
    (let [red (verify/verify-request
                {:changed ["test/samizdat/definitely-not-a-real-ns_test.clj"]
                 :focused? true})
          r (verify/run-verify (System/getProperty "user.dir") red 120000 nil)]
      (is (false? (:green? r)) "a namespace that does not load is red, not green")))))

;; --- the pure ship decision ----------------------------------------------------

(deftest verify-block-is-inert-when-verify-is-off
  (is (nil? (verify/verify-block {:verify-on? false :result nil :changed nil
                                  :require-test? true}))))

(deftest verify-block-blocks-a-red-run-with-the-output
  (let [b (verify/verify-block {:verify-on? true
                                :result {:green? false
                                         :output "FAIL: 2 assertions failed\nat foo_test"}
                                :changed ["src/x.clj" "test/x_test.clj"]
                                :require-test? true})]
    (is (some? b))
    (is (str/includes? b "not green"))
    (is (str/includes? b "assertions failed") "feeds the failure output back")))

(deftest verify-block-bounds-the-rendered-output
  (let [huge (str/join "\n" (repeat 40 (str/join (repeat 500 "x"))))
        b (verify/verify-block {:verify-on? true
                                :result {:green? false :output huge}
                                :changed ["test/x_test.clj"]
                                :require-test? true})]
    (is (some? b))
    (is (<= (count b) (+ verify/render-chars 2000))
        "the model-visible block is bounded even against giant lines")))

(deftest verify-block-blocks-a-timeout
  (let [b (verify/verify-block {:verify-on? true
                                :result {:green? false :timeout? true :output ""}
                                :changed ["test/x_test.clj"] :require-test? true})]
    (is (some? b))
    (is (str/includes? (str/lower-case b) "timed out"))))

(deftest verify-block-blocks-a-hollow-done-when-nothing-changed
  (let [b (verify/verify-block {:verify-on? true :result {:green? true :output ""}
                                :changed [] :require-test? false})]
    (is (some? b))
    (is (str/includes? (str/lower-case b) "changed no files"))))

(deftest verify-block-requires-a-test-when-tdd-strict
  (let [b (verify/verify-block {:verify-on? true :result nil ; not even run — the pre-check fires first
                                :changed ["src/samizdat/agent/tools/knowledge.clj"]
                                :require-test? true})]
    (is (some? b))
    (is (str/includes? (str/lower-case b) "test")))
  (testing "with require-test? off, a green src-only change ships"
    (is (nil? (verify/verify-block {:verify-on? true :result {:green? true :output ""}
                                    :changed ["src/x.clj"] :require-test? false})))))

(deftest verify-block-refuses-when-no-trusted-fallback-exists
  (let [b (verify/verify-block
            {:verify-on? true :result nil
             :request {:kind :refused :refused ["test/foo';touch pwned_test.clj"]}
             :changed ["test/foo';touch pwned_test.clj"]
             :require-test? true})]
    (is (some? b) "fail closed: an adversarial name cannot disable the gate")
    (is (str/includes? b "REFUSED"))
    (is (str/includes? b "test/foo';touch pwned_test.clj")
        "the offending path is named back to the worker"))
  (testing "a refusal absorbed by the configured fallback runs the normal ladder"
    (is (nil? (verify/verify-block
                {:verify-on? true
                 :request {:kind :fallback :argv ["sh" "-c" "jolt -M:test"]}
                 :result {:green? true :output ""}
                 :changed ["test/foo';x_test.clj"]
                 :require-test? true})))))

(deftest verify-block-passes-a-green-tdd-change
  (is (nil? (verify/verify-block {:verify-on? true :result {:green? true :output ""}
                                  :changed ["src/samizdat/agent/tools/knowledge.clj"
                                            "test/samizdat/knowledge_test.clj"]
                                  :require-test? true}))))

(deftest verify-block-trusts-a-green-run-when-git-cannot-tell
  (is (nil? (verify/verify-block {:verify-on? true :result {:green? true :output ""}
                                  :changed nil :require-test? true}))))

;; --- static source discipline: the composition cannot quietly return ----------

(def ^:private project-dir
  (or (jolt.host/getenv "JOLT_PWD") (System/getProperty "user.dir")))

(defn- src-of [rel] (slurp (str project-dir "/" rel)))

(deftest verifier-source-discipline-no-shell-composition
  (let [sources {"verify" (src-of "src/samizdat/agent/verify.clj")
                 "gitdiff" (src-of "src/samizdat/agent/gitdiff.clj")
                 "proc" (src-of "src/samizdat/engine/proc.clj")
                 "ship" (src-of "src/samizdat/agent/tools/ship.clj")}]
    (testing "no cd-prefix or statement shell composition anywhere on the path"
      (doseq [[nm src] sources]
        (is (not (str/includes? src " && "))
            (str nm ": shell statement composition is back"))
        (is (not (re-find #"\(str\s+\"cd\b" src))
            (str nm ": composes a cd prefix into a command string"))))
    (testing "the only shell on the verify path is the fallback's single operand"
      (is (= 1 (count (re-seq #"\[\"sh\" \"-c\" " (get sources "verify"))))
          "verify.clj: exactly the trusted-fallback argv vector")
      (is (zero? (count (re-seq #"\"sh\"" (get sources "gitdiff"))))
          "gitdiff spawns no shell")
      (is (zero? (count (re-seq #"\"sh\"" (get sources "proc"))))
          "proc spawns no shell"))
    (testing "the focused expression is an argv element, never a quoted shell string"
      (is (not (str/includes? (get sources "verify") "-e '"))))
    (testing "the scoped primitive is reached only through proc's indirection"
      (doseq [[nm src] sources]
        (is (or (= "proc" nm)
                (not (str/includes? src "jolt.host/process-scope-run")))
            (str nm ": reaches around proc/scope-run to the primitive")))
      (is (= 1 (count (re-seq #"jolt\.host/process-scope-run"
                              (get sources "proc"))))
          "proc.clj binds the trusted primitive exactly once"))))

;; --- self-run for the focused lane ---------------------------------------------

(when (= "1" (jolt.host/getenv "SAMIZDAT_VERIFY_TEST_RUN"))
  (let [{:keys [fail error] :as summary} (run-tests 'samizdat.verify-test)]
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
