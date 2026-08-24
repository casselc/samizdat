;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.proc-test
  "The scoped structured execution seam (proc/scope-run over
  jolt.host/process-scope-run): the fail-closed request contract, the exact
  request forwarded to the trusted primitive, the explicit scrubbed allowlist
  environment (no controller inheritance), independent stdout/stderr byte
  caps, and the process-tree timeout that leaves no TERM-resistant survivor.

  Real-spawn sections gate on Linux — the primitive itself throws
  UnsupportedOperationException elsewhere, so off Linux they skip (and the
  pure contract tests still run). The no-survivor checks read /proc
  independently of the primitive's own confirmation, so a regression is
  caught by evidence it cannot fabricate.

  Self-run on the recorded JS1 classpath (from the repo root; jolt is
  located exactly as bin/js1 locates it — $JOLT_HOME, else the sibling
  ../jolt checkout — and the classpath is the one bin/js1 path records):
    SAMIZDAT_PROC_TEST_RUN=1 ../jolt/bin/jolt -Srepro -Scp \"$(bin/js1 path)\" run test/samizdat/proc_test.clj"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [samizdat.engine.proc :as proc]))

(def ^:private linux?
  (str/includes? (str (System/getProperty "os.name")) "Linux"))

;; The trusted primitive, captured at load (the root binding) so the
;; dispatching stubs below can delegate git-style calls to reality while
;; capturing the call under test.
(def real-scope-run proc/*scope-run*)

;; Alive in the same sense the primitive's confirm loop means it: a /proc
;; entry whose state is not Z/X/x. A vanished entry is not live, never an
;; exception.
(defn- proc-live? [pid]
  (when pid
    (try
      (let [s (slurp (str "/proc/" pid "/stat"))
            rp (.lastIndexOf s ")")]
        (and (>= rp 0)
             (> (count s) (+ rp 2))
             (not (contains? #{"Z" "X" "x"} (subs s (+ rp 2) (+ rp 3))))))
      (catch Exception _ false))))

(defn- nuke-group!
  "Belt and braces AFTER a failed no-survivor assertion: destroy the leaked
  group so a regression cannot outlive the test run. Runs only when the
  assertion's evidence is already captured."
  [pgid]
  (try
    (real-scope-run {:cmd ["/bin/sh" "-c" (str "kill -9 -" pgid)]
                     :env {} :timeout-ms 5000})
    (catch Throwable _ nil)))

;; --- the explicit scrubbed allowlist environment (pure) ----------------------

(deftest scrubbed-allowlist-env-is-explicit-and-scrubbed
  (let [child (proc/scrubbed-allowlist-env
                {"PATH" "/usr/bin:/bin"
                 "HOME" "/home/w"
                 "TERM" "xterm"
                 "SAMIZDAT_API_KEY" "sk-abcdefghijklmnopqrstuv"
                 "HARNESS_TOKEN" "opaque-token-value-0123456789"
                 "DATABASE_URL" "postgres://u:secretpw@db.example.com/prod"
                 "SOMETHING_ELSE" "1"})]
    (testing "exactly the allowlisted names survive — nothing else crosses"
      (is (= #{"PATH" "HOME" "TERM"} (set (keys child)))))
    (testing "name-sensitive values are absent entirely"
      (is (not (str/includes? (str (vals child)) "sk-abcdefghijklmnopqrstuv")))
      (is (not (str/includes? (str (vals child)) "opaque-token-value"))))
    (testing "unallowlisted names are dropped, not redacted-in-place"
      (is (nil? (get child "DATABASE_URL")))
      (is (nil? (get child "SOMETHING_ELSE"))))
    (testing "allowed values pass through untouched"
      (is (= "/usr/bin:/bin" (get child "PATH")))))
  (testing "empty and nil sources yield an empty explicit environment"
    (is (= {} (proc/scrubbed-allowlist-env {})))
    (is (= {} (proc/scrubbed-allowlist-env nil))))
  (testing "a credential-shaped value under an ALLOWED name is scrubbed"
    (is (= "[REDACTED]"
           (get (proc/scrubbed-allowlist-env {"PATH" "sk-abcdefghijklmnopqrstuv"})
                "PATH")))))

;; --- the request contract fails closed before anything spawns -----------------

(deftest scope-run-fails-closed-on-malformed-requests
  (binding [proc/*scope-run* (fn [req] (throw (ex-info "must not spawn" req)))]
    (doseq [[label req]
            ["empty argv"        {:cmd [] :env {} :timeout-ms 100}
             "nil argv"          {:cmd nil :env {} :timeout-ms 100}
             "blank element"     {:cmd [""] :env {} :timeout-ms 100}
             "non-string elem"   {:cmd [:git] :env {} :timeout-ms 100}
             "no env at all"     {:cmd ["git"] :timeout-ms 100}
             "non-map env"       {:cmd ["git"] :env "PATH=/bin" :timeout-ms 100}
             "= inside name"     {:cmd ["git"] :env {"A=B" "1"} :timeout-ms 100}
             "non-string value"  {:cmd ["git"] :env {"A" 1} :timeout-ms 100}
             "no timeout"        {:cmd ["git"] :env {}}
             "zero timeout"      {:cmd ["git"] :env {} :timeout-ms 0}
             "fractional time"   {:cmd ["git"] :env {} :timeout-ms 2.5}
             "zero out cap"      {:cmd ["git"] :env {} :timeout-ms 100 :out-bytes 0}
             "negative err cap"  {:cmd ["git"] :env {} :timeout-ms 100 :err-bytes -1}
             "fractional cap"    {:cmd ["git"] :env {} :timeout-ms 100 :out-bytes 2.5}
             "blank dir"         {:cmd ["git"] :env {} :timeout-ms 100 :dir ""}]]
      (is (thrown? IllegalArgumentException (proc/scope-run req))
          (str label ": must throw before anything spawns")))))

(deftest scope-run-forwards-exactly-the-checked-request
  (let [seen (atom ::none)
        ok {:pid 1 :exit 0 :timed-out false}]
    (binding [proc/*scope-run* (fn [req] (reset! seen req) ok)]
      (proc/scope-run {:cmd ["git" "status"] :env {"A" "1"} :timeout-ms 5000
                       :dir "/tmp" :term-grace-ms 250 :out-bytes 10 :err-bytes 20})
      (is (= {:cmd ["git" "status"] :env {"A" "1"} :timeout-ms 5000
              :dir "/tmp" :term-grace-ms 250 :out-bytes 10 :err-bytes 20}
             @seen)
          "every requested knob, verbatim")
      (proc/scope-run {:cmd ["x"] :env {} :timeout-ms 1})
      (is (= {:cmd ["x"] :env {} :timeout-ms 1} @seen)
          "absent optionals are ABSENT keys — no nil-valued or invented knobs"))))

;; --- the capability probe: off-Linux / missing primitive must be answerable ----

(deftest scope-supported?-probes-the-primitive-without-spawning
  (testing "on this runtime (Linux) the scoped capability is present"
    (is (true? (proc/scope-supported?))))
  (testing "a primitive that answers, or throws a request-shape complaint, counts as present"
    (binding [proc/*scope-run* (fn [_req] {:exit 0})]
      (is (true? (proc/scope-supported?))))
    (binding [proc/*scope-run*
              (fn [req]
                (throw (IllegalArgumentException. (str "shape: " (pr-str (:cmd req))))))]
      (is (true? (proc/scope-supported?)))))
  (testing "UnsupportedOperationException is the absent answer — off Linux"
    (binding [proc/*scope-run*
              (fn [_req]
                (throw (UnsupportedOperationException.
                         "process-scope: requires Linux with posix_spawn process-group and poll(2) support")))]
      (is (false? (proc/scope-supported?)))))
  (testing "the probe request is the capability shape: a lone :cmd, no :timeout-ms"
    (let [seen (atom nil)]
      (binding [proc/*scope-run* (fn [req] (reset! seen req) {:exit 0})]
        (is (true? (proc/scope-supported?)))
        (is (= ["samizdat-scope-capability-probe"] (:cmd @seen)))
        (is (nil? (:timeout-ms @seen))
            "no timeout means no spawn path is ever reached on a present
             primitive — and on an absent one the throw precedes everything")))))

;; --- the allowlist is LOCKED, not merely filtered ------------------------------

(def ^:private expected-allowlist
  "The pinned set. Changing the allowlist is a trust decision; this test is
  where that decision gets made visibly, in both directions — an addition
  or removal anywhere else fails here."
  #{"PATH" "HOME" "LANG" "LC_ALL" "LC_CTYPE" "TERM" "TMPDIR"})

(def ^:private project-dir
  (or (jolt.host/getenv "JOLT_PWD") (System/getProperty "user.dir")))

(deftest scoped-env-allowlist-is-locked-exactly
  (testing "the source literal in proc.clj IS the pinned set (no drift either way)"
    (let [src (slurp (str project-dir "/src/samizdat/engine/proc.clj"))
          m (re-find #"(?s)\(def \^:private scope-env-allowlist.*?\[([^\]]*)\]" src)]
      (is (some? m) "the scope-env-allowlist vector was not found in proc.clj")
      (is (= expected-allowlist
             (set (map second (re-seq #"\"([^\"]+)\"" (second m)))))
          "the allowlist changed without updating this lock — that is a
           trust decision, make it here, deliberately")))
  (testing "behavior: a source env holding every allowlisted name plus junk
            yields EXACTLY the allowlist — equality, not subset"
    (let [src-env (merge (into {} (map #(vector % (str "v-" %)) expected-allowlist))
                         {"SAMIZDAT_API_KEY" "x" "SOMETHING_ELSE" "y"})
          child (proc/scrubbed-allowlist-env src-env)]
      (is (= expected-allowlist (set (keys child)))
          "no name outside the pinned set may ever appear")
      (is (every? #(= (str "v-" %) (get child %)) expected-allowlist)
          "and every pinned name's plain value passes through"))))

;; --- real scoped execution (Linux: the primitive's own platform bound) -------

(deftest scope-run-executes-structured-argv-without-inheriting-the-controller-env
  (when linux?
    (let [r (proc/scope-run {:cmd ["/bin/sh" "-c"
                                   "echo \"JP=$JP_ONLY HOME=${HOME:-unset}\"; pwd"]
                             :dir "/tmp"
                             :env {"JP_ONLY" "1"}
                             :timeout-ms 10000
                             :out-bytes 1000 :err-bytes 1000})]
      (is (zero? (:exit r)))
      (is (false? (:timed-out r)))
      (is (= :complete (:out-status r)))
      (is (str/includes? (:out r) "JP=1") "the explicit env reached the child")
      (is (str/includes? (:out r) "HOME=unset")
          "the controller environment is NOT inherited — :env replaces it")
      (is (str/includes? (:out r) "/tmp") "the request :dir set the child cwd"))))

(deftest scope-run-caps-stdout-and-stderr-independently
  (when linux?
    (let [r (proc/scope-run {:cmd ["/bin/sh" "-c"
                                   (str "echo one-line; i=0; "
                                        "while [ $i -lt 100000 ]; do echo E$i 1>&2; "
                                        "i=$((i+1)); done; sleep 30")]
                             :env {} :timeout-ms 20000
                             :err-bytes 16 :out-bytes 4096})]
      (is (= :truncated (:err-status r)) "the flooding stream hit its own cap")
      (is (= 16 (count (:err r))) "the stderr bound is honored exactly")
      (is (= :complete (:out-status r)) "the bounded stdout still completed")
      (is (= "one-line\n" (:out r)) "stdout content is intact")
      (is (false? (:timed-out r)) "an overflow abort is not a controller timeout")
      (is (not (zero? (:exit r))) "the overflow abort killed the run"))))

(deftest scope-run-timeout-leaves-no-term-resistant-survivors
  (when linux?
    (let [pidf (str "/tmp/samizdat-proc-test-"
                    (System/currentTimeMillis) ".pid")]
      (try
        ;; A tree where EVERY member resists SIGTERM: the child traps TERM,
        ;; and a shell that has trapped TERM leaves SIG_IGN in place for
        ;; everything it spawns afterwards, so the grandchild's sleep
        ;; survives the TERM wave too. Only the group KILL clears it.
        (let [script (str "trap '' TERM; "
                          "/bin/sh -c 'trap \"\" TERM; echo $$ > " pidf
                          "; sleep 60' & wait")
              r (proc/scope-run {:cmd ["/bin/sh" "-c" script]
                                 :env {} :timeout-ms 800 :term-grace-ms 250
                                 :out-bytes 1000})
              leaked-grandchild? (when (.exists (java.io.File. pidf))
                                   (proc-live? (str/trim (slurp pidf))))]
          (is (true? (:timed-out r)))
          (is (contains? #{137 143} (:exit r))
              "the root died to the escalation ladder (KILL 137 / TERM 143)")
          (is (not (proc-live? (:pid r))) "the root is gone from /proc")
          (is (not leaked-grandchild?)
              "the TERM-resistant grandchild is dead — no survivor")
          (when leaked-grandchild? (nuke-group! (:pid r))))
        (finally
          (let [f (java.io.File. pidf)]
            (when (.exists f) (.delete f))))))))

(deftest scope-run-throws-when-the-program-cannot-run
  (when linux?
    (is (thrown? Exception
                 (proc/scope-run {:cmd ["definitely-no-such-program-xyz"]
                                  :env {} :timeout-ms 1000})))))

;; --- self-run for the focused lane --------------------------------------------

(when (= "1" (jolt.host/getenv "SAMIZDAT_PROC_TEST_RUN"))
  (let [{:keys [fail error] :as summary} (run-tests 'samizdat.proc-test)]
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
