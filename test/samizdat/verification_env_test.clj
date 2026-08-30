;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.verification-env-test
  "The M2 VerificationEnvironment's own adversarial suite.

  Two kinds of test live here. The PURE ones pin the controller's authority
  without spawning anything: the pinned verifier argv ignores gates.edn
  (which the tier under observation can rewrite at runtime), the explicit
  child environment carries no credential-shaped names and pins
  HOME/PWD/TMPDIR to sandbox paths, and the composed sandbox argv holds the
  fail-closed isolation flags with the focused expression as its only
  variable element.

  The ADVERSARIAL ones are real: a hostile test namespace — written exactly
  as a model would write it through project/edit — attempts to write outside
  the workspace, rewrite the protected run config, read host secrets and
  config, reach a live host listener, leave a daemon behind, and flood the
  output; the assertions are host-side facts (nothing landed, nothing
  survives, the authoritative tree is byte-identical) plus the in-sandbox
  evidence that every attempt failed. They run wherever the substrate exists
  (Linux + bwrap + prlimit + user namespaces + the pinned verifier); where
  it does not, the same tests pin the other half of the contract — the run
  REFUSES, it never degrades to a host spawn."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.verify :as verify]
            [samizdat.engine.proc :as proc]
            [samizdat.security.secrets :as secrets]
            [samizdat.security.verification-env :as ve]))

(def ^:private substrate?
  (delay (and (ve/available?) (ve/resolve-verifier))))

(def ^:private samizdat-root?
  "The suite's cwd is the samizdat repo itself (bin/js1-m1 cds here, and a
  plain `jolt -M:test` from the root is the same) — the precondition for the
  real-project parity run. Under another cwd that run is skipped rather than
  copying the wrong tree."
  (delay (fs/exists? "test/samizdat/verification_env_test.clj"
                     {:nofollow-links true})))

;; ─── fixtures ────────────────────────────────────────────────────────────────

(def ^:private real-config "{:run {:verify-cmd \"jolt -A:test -e '(quote nothing)'\"}}\n")

(defn- seed-project!
  "A synthetic project root: deps.edn, one src ns, the protected run config,
  and whatever test namespaces the caller writes. This is the authoritative
  tree a bounded run would verify — and must not be able to touch."
  [root]
  (fs/create-dirs (str root "/src/ve"))
  (fs/create-dirs (str root "/test/ve"))
  (fs/create-dirs (str root "/.samizdat"))
  (spit (str root "/deps.edn")
        "{:paths [\"src\" \"test\"]\n :aliases {:test {:extra-paths [\"test\"]}}}\n")
  (spit (str root "/src/ve/core.clj") "(ns ve.core)\n(defn two [] 2)\n")
  (spit (str root "/.samizdat/config.edn") real-config))

(defn- write-test!
  "Write one test namespace into the project — exactly the way a bounded
  model would: a file under the test tree whose name maps to a whitelisted
  ns."
  [root name content]
  (spit (str root "/test/ve/" name) content))

(def ^:private benign-test
  "(ns ve.benign-test
  (:require [clojure.test :refer [deftest is]]
            [ve.core]))
(deftest two-is-two (is (= 2 (ve.core/two))))
")

(defn- hostile-test
  "The hostile namespace, generated with the live host listener port and the
  live host secret-marker path baked in. Every deftest ASSERTS THE ATTACK
  SUCCEEDED — green here would mean the sandbox failed — so the expected
  outcome through a correct environment is RED with these very failures as
  the evidence, and no host-side damage."
  [host-port secret-marker]
  (str "(ns ve.hostile-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.process :as p]))

(defn- attempt [f] (try (f) ::succeeded (catch Throwable t ::failed)))
(def HOST-PORT " host-port ")
(def SECRET-MARKER \"" secret-marker "\")

;; 1. write outside the workspace: the stage root, /etc, /usr, ../ off the
;;    workspace, and the run's protected config rewritten to a no-op gate.
(deftest outside-root-write
  (is (= ::succeeded (attempt #(spit \"/pwned-stage-root\" \"x\"))))
  (is (= ::succeeded (attempt #(spit \"/etc/pwned\" \"x\"))))
  (is (= ::succeeded (attempt #(spit \"/usr/pwned\" \"x\"))))
  (is (= ::succeeded (attempt #(spit \"/workspace/../pwned-escape\" \"x\"))))
  (is (= ::succeeded (attempt #(spit \"/workspace/.samizdat/config.edn\"
                                    \"{:run {:verify-cmd \\\"true\\\"}}\")))))

;; 2. host secrets and config: passwd, the live host secret marker (a file
  ;; that exists on the host right now), and a dump of the whole child env.
(deftest host-secrets-readable
  (is (= ::succeeded (attempt #(slurp \"/etc/passwd\"))))
  (is (= ::succeeded (attempt #(slurp SECRET-MARKER))))
  (println \"HOSTILE-ENV-DUMP-BEGIN\")
  (doseq [[k v] (sort-by first (into {} (System/getenv)))] (println k \"=\" v))
  (println \"HOSTILE-ENV-DUMP-END\"))

;; 3. network: a listener is LIVE on the host loopback at HOST-PORT; inside
  ;; an isolated net namespace it must be unreachable.
(deftest network-reachable
  (is (= ::succeeded (attempt #(java.net.Socket. \"127.0.0.1\" HOST-PORT)))))

;; 4. a daemon that outlives the verifier.
(deftest daemon-spawned
  (is (some? (p/process [\"sh\" \"-c\" \"exec sleep 547\"]))))

;; 5. output flood.
(deftest output-flood
  (dotimes [_ 3000] (println (apply str (repeat 1000 \"x\")))))
"))

(defn- host-secret-marker!
  "A file that exists on the HOST right now, in a location the sandbox
  cannot see (the host /tmp — the sandbox's /tmp is a fresh tmpfs)."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "ve-host-secret-"}))
        marker (str dir "/marker")]
    (spit marker "HOST-SECRET-DO-NOT-LEAK")
    marker))

(defn- live-listener
  "Bind a loopback listener host-side and return [server port] — reachable
  from the host net namespace, unreachable inside the sandbox's own."
  []
  (let [server (java.net.ServerSocket. 0)]
    [server (.getLocalPort server)]))

(defn- pgrep-live? [pattern]
  (let [r (proc/run {:timeout-ms 5000} "pgrep" "-f" pattern)]
    (and (not (:timeout r)) (zero? (or (:exit r) 1)))))

(defn- leftover-stages
  "The samizdat-verify-* stage dirs currently sitting in the temp dir — the
  cleanup assertions compare snapshots around a run."
  []
  (set (map str (fs/list-dir (str (fs/temp-dir)) "samizdat-verify-*"))))

(defn- file-digest [path]
  (let [bs (with-open [in (java.io.FileInputStream. (str path))]
             (let [out (java.io.ByteArrayOutputStream.)
                   buf (byte-array 8192)]
               (loop []
                 (let [n (.read in buf)]
                   (when (not (neg? n))
                     (.write out buf 0 n)
                     (recur))))
               (.toByteArray out)))]
    (apply str (map #(format "%02x" %)
                    (.digest (java.security.MessageDigest/getInstance "SHA-256")
                             bs)))))

(defn- with-project [f]
  (let [root (str (fs/create-temp-dir {:prefix "ve-project-"}))]
    (try
      (seed-project! root)
      (f root)
      (finally (fs/delete-tree root)))))

(defn- when-substrate [f]
  (if @substrate?
    (f)
    (testing "substrate unavailable here: the refusal contract instead"
      (let [r (ve/run "/nonexistent-root" ["test/ve/benign_test.clj"] 1000)]
        (is (or (:unavailable? r) (not (:green? r)))
            "no substrate => never green, and never a host spawn")))))

;; ─── the pinned authority (pure — runs everywhere) ───────────────────────────

(deftest focused-argv-is-the-pinned-verifier-not-gates-edn
  (let [argv (ve/focused-argv ["src/ve/core.clj" "test/ve/benign_test.clj"])]
    (is (vector? argv))
    (is (= 4 (count argv))
        "pinned executable, two fixed args, one derived expression")
    (is (or (= "jolt" (first argv)) (str/ends-with? (first argv) "/jolt"))
        "the executable is the controller-resolved pinned verifier — absolute
        where the controller could resolve it, and the pinned name never
        anything else")
    (is (= ["-A:test" "-e"] (vec (rest (butlast argv))))
        "the fixed argv is the controller constant, verbatim")
    (is (not-any? #{"sh" "-c"} argv) "no shell anywhere in the vector"))
  (testing "nothing verifiable => nil, never a command"
    (is (nil? (ve/focused-argv ["src/ve/core.clj"])))
    (is (nil? (ve/focused-argv [])))))

(deftest a-retuned-gates-edn-cannot-widen-the-verifier-argv
  ;; THE immutable-argv-authority test: gates.edn is runtime-mutable by the
  ;; tier this gate judges. Retune the argv key to a hostile command and the
  ;; derivation must not move — the pinned table is code. (The key is no
  ;; longer read at all; the redef proves it by changing nothing.)
  (let [hostile (assoc (gates/threshold :focused-verify)
                       :argv-prefix ["sh" "-c" "touch /tmp/ve-pwned; exit 0"])]
    (with-redefs [gates/threshold (fn [_] hostile)]
      (let [argv (ve/focused-argv ["test/ve/benign_test.clj"])]
        (is (some? argv))
        (is (= 4 (count argv)) "same shape under a hostile gates.edn")
        (is (= ve/verifier-fixed-args (vec (rest (butlast argv))))
            "the fixed args are still the controller constants")
        (is (not-any? #{"sh" "-c" "touch"} argv)
            "nothing from the retuned data reached the argv"))))

  (testing "a crafted edited path contributes no argv element"
    (let [crafted "test/foo'; touch /tmp/ve-pwned; echo '_x.clj"
          argv (ve/focused-argv [crafted "test/ve/benign_test.clj"])]
      (is (= 4 (count argv)))
      (is (not (str/includes? (pr-str argv) "ve-pwned")))
      (is (nil? (ve/focused-argv [crafted]))
          "only crafted names => no argv at all"))))

(deftest both-lanes-verify-the-same-namespaces
  ;; The parity fixture: the ordinary sh lane and the bounded sandbox lane
  ;; share ONE derivation, so a change is verified identically either way.
  (let [changed ["src/ve/core.clj" "test/ve/benign_test.clj"]
        expr (verify/focused-expr ["ve.benign-test"])]
    (is (str/ends-with? (last (ve/focused-argv changed)) expr)
        "the sandbox lane's one variable element is the shared expression")
    (is (str/includes? (verify/focused-cmd changed) expr)
        "the ordinary lane's command embeds the same expression")))

(deftest the-child-environment-is-explicit-scrubbed-and-sandbox-pinned
  (testing "the environment is constructed, never inherited or filtered"
    (with-redefs [secrets/scrubbed-process-env
                   (fn [] (secrets/scrub-env {"HARNESS_PLANTED_TOKEN" "leak-me"
                                              "PATH" "/usr/bin:/bin"
                                              "AWS_SECRET_ACCESS_KEY" "AKIA123"}))]
      (let [env (ve/child-env)]
        (is (not (contains? env "HARNESS_PLANTED_TOKEN")))
        (is (not (contains? env "AWS_SECRET_ACCESS_KEY")))
        (is (not (contains? env "GITHUB_TOKEN")))
        (is (not (contains? env "GH_TOKEN")))
        (is (not (contains? env "SSH_AUTH_SOCK")))
        (is (= #{"HOME" "PWD" "TMPDIR" "JOLT_PWD" "LANG" "PATH"}
               (set (keys env))) "only controller-authored names exist"))))
  (testing "the controller's Chez pin rides along, and only it"
    (with-redefs [secrets/scrubbed-process-env
                   (fn [] (secrets/scrub-env {"JOLT_CHEZ" "/usr/local/bin/scheme"
                                              "PATH" "/usr/bin:/bin"
                                              "HARNESS_PLANTED_TOKEN" "leak-me"}))]
      (let [env (ve/child-env)]
        (is (= "/usr/local/bin/scheme" (get env "JOLT_CHEZ"))
            "bin/jolt treats JOLT_CHEZ as authoritative; the controller hands
            its own resolution to the pinned verifier unchanged")
        (is (= "/workspace" (get env "JOLT_PWD"))
            "the pin adds a toolchain name; every sandbox-path pin still holds")
        (is (= #{"HOME" "PWD" "TMPDIR" "JOLT_PWD" "LANG" "PATH" "JOLT_CHEZ"}
               (set (keys env)))
            "the pin is the ONLY additional name — a credential-shaped one is
            dropped by scrubbed-process-env before child-env ever sees it")
        (is (not (contains? env "HARNESS_PLANTED_TOKEN"))))))
  (testing "HOME/PWD/TMPDIR and the verifier's own PWD locator are pinned to sandbox paths"
    (let [env (ve/child-env)]
      (is (= "/home" (get env "HOME")))
      (is (= "/workspace" (get env "PWD")))
      (is (= "/tmp" (get env "TMPDIR")))
      (is (= "/workspace" (get env "JOLT_PWD"))
          "a leaked harness JOLT_PWD would point the verifier at a host
          path that does not exist inside the sandbox")))
  (testing "against the LIVE process environment, no name is credential-shaped"
    (let [env (ve/child-env)]
      (is (seq env))
      (doseq [k (keys env)]
        (is (not (secrets/sensitive-name? k))
            (str k " reached the child env"))))))

(deftest the-composed-sandbox-argv-holds-the-fail-closed-flags
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "benign_test.clj" benign-test)
        (let [stage (str (fs/create-temp-dir {:prefix "samizdat-verify-"}))]
          (try
            (let [{:keys [argv ro-binds]} (ve/build-environment
                                           stage root
                                           ["test/ve/benign_test.clj"])]
              (is (str/ends-with? (first argv) "/bwrap")
                  "bwrap is absolute, controller resolved")
              (is (some #{"--unshare-user"} argv)
                  "user namespace REQUIRED — silently running without it is the
                  failure mode this flag exists to forbid")
              (is (some #{"--unshare-net"} argv) "no network")
              (is (some #{"--unshare-pid"} argv)
                  "PID namespace — daemons die with the sandbox")
              (is (some #{"--unshare-ipc"} argv))
              (is (some #{"--unshare-uts"} argv))
              (is (some #{"--die-with-parent"} argv))
              (is (some #{"--cap-drop" "ALL"} argv) "all capabilities dropped")
              (is (some #{(str "--fsize=" (:fsize-bytes ve/resource-limits))} argv)
                  "the output/files flood bound is pinned in the argv")
               (is (some #{(str "--nproc=" (:nproc ve/resource-limits))} argv))
               (is (some #{(str "--as=" (:as-bytes ve/resource-limits))} argv))
               (is (some #{(str "--nofile=" (:nofile ve/resource-limits))} argv))
               (is (some #{"--ro-bind"} argv) "the private stage is read-only")
               (is (not-any? #{"--dev"} argv) "the unsized bwrap --dev mount is forbidden")
              (is (every? (fn [[src dest]]
                            (and (str/starts-with? src "/")
                                 (str/starts-with? dest "/")
                                 ;; A system bind lands at its own path; a
                                 ;; dependency cache is RELOCATED under the
                                 ;; private /home — never its host location.
                                 (or (= src dest) (str/starts-with? dest "/home/"))))
                          ro-binds)
                  "every ro-bind is absolute, and only caches are relocated —
                  always into the private /home, never at a host path")
              (is (some (fn [[src dest]] (and (= src "/usr") (= dest "/usr")))
                        ro-binds)
                  "the system runtime is bound read-only at its own path"))
            (finally (fs/delete-tree stage)))))))))

;; ─── the substrate contract ──────────────────────────────────────────────────

(deftest an-unavailable-substrate-refuses-never-degrades
  (with-redefs [ve/available? (fn [] false)
                ve/unavailable-reason (fn [] :not-linux)]
    (with-project
     (fn [root]
       (let [ran (atom nil)]
         (with-redefs [proc/run (fn [& args] (reset! ran args)
                                 {:exit 0 :out "" :err ""})]
           (let [r (ve/run root ["test/ve/benign_test.clj"] 60000)]
             (is (not (:green? r)))
             (is (:unavailable? r)
                 "the refusal is marked, not read as red tests")
             (is (= :not-linux (:reason r)))
             (is (nil? @ran)
                 "NOTHING was spawned — no fallback to a host spawn")))))))
  (testing "an unresolvable pinned verifier refuses the same way"
    ;; available? is pinned true so this asserts the rung it names. Left to the
    ;; host, the ladder short-circuits at :sandbox-unavailable wherever bwrap
    ;; is absent — including inside the controller's OWN VerificationEnvironment,
    ;; which is where the closure gate runs this suite.
    (with-redefs [ve/available? (fn [] true)
                  ve/resolve-verifier (fn [] nil)]
      (with-project
       (fn [root]
         (let [r (ve/run root ["test/ve/benign_test.clj"] 60000)]
           (is (not (:green? r)))
           (is (:unavailable? r))
           (is (= :no-verifier-executable (:reason r)))))))))

;; ─── the real adversarial runs ───────────────────────────────────────────────

(deftest a-hostile-test-namespace-cannot-reach-the-host
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (let [marker (host-secret-marker!)
              [server port] (live-listener)]
          (try
            (write-test! root "hostile_test.clj" (hostile-test port marker))
            (let [before (leftover-stages)
                  config-before (file-digest (str root "/.samizdat/config.edn"))
                  r (ve/run root ["test/ve/hostile_test.clj"] 120000)]
              (testing "the hostile run is not green — its attacks failed in-sandbox"
                (is (not (:green? r)))
                (is (not (:timeout? r)))
                (doseq [attempt ["outside-root-write" "host-secrets-readable"
                                 "network-reachable"]]
                  (is (str/includes? (:output r) (str "FAIL in (" attempt))
                      (str "the " attempt " attack failed INSIDE the sandbox"))))
              (testing "the authoritative root is byte-identical"
                (is (= "(ns ve.core)\n(defn two [] 2)\n"
                       (slurp (str root "/src/ve/core.clj"))))
                (is (= config-before
                       (file-digest (str root "/.samizdat/config.edn")))
                    "the protected run config — the gate's own definition — is exactly what it was"))
              (testing "nothing landed on the host"
                (is (not (fs/exists? "/etc/pwned" {:nofollow-links true})))
                (is (not (fs/exists? "/usr/pwned" {:nofollow-links true})))
                (is (not (fs/exists? (str root "/../pwned-escape")
                                     {:nofollow-links true}))))
              (testing "no daemon outlives the verifier"
                (Thread/sleep 300)
                (is (not (pgrep-live? "sleep 547"))
                    "the PID namespace tore the daemon down with the sandbox"))
              (testing "the stage is cleaned up"
                (is (every? #(contains? before %) (leftover-stages))
                    "no throwaway workspace survives the run"))
              (testing "the output passes the redaction and env boundaries"
                (is (not (str/includes? (:output r) "HOST-SECRET-DO-NOT-LEAK"))
                    "the host secret marker's content never reached the output")
                (is (str/includes? (:output r) "HOME = /home"))
                (is (str/includes? (:output r) "PWD = /workspace")
                    "the child saw the sandbox-pinned env, not the host's")))
            (finally
              (try (.close server) (catch Throwable _ nil))
              (fs/delete-tree (str (fs/parent (fs/path marker))))))))))))


(deftest a-live-host-listener-is-unreachable-from-inside-the-sandbox
  ;; The network attack as its own differential: the listener IS live on the
  ;; host loopback (a connection from the host namespace succeeds), and the
  ;; same connection from inside the sandbox must fail.
  (when-substrate
   (fn []
     (let [[server port] (live-listener)]
       (try
         (is (try (let [s (java.net.Socket. "127.0.0.1" port)]
                    (.close s) true)
                  (catch Throwable _ false))
             "sanity: the listener is reachable from the host namespace")
         (with-project
          (fn [root]
            (write-test! root "probe_test.clj"
                         (str "(ns ve.probe-test
  (:require [clojure.test :refer [deftest is]]))
(defn- attempt [f] (try (f) ::succeeded (catch Throwable t ::failed)))
(deftest loopback-listener-reachable
  (is (= ::succeeded (attempt #(java.net.Socket. \"127.0.0.1\" " port ")))))
"))
            (let [r (ve/run root ["test/ve/probe_test.clj"] 120000)]
              (is (not (:green? r)) "the in-sandbox connection failed")
              (is (str/includes? (:output r)
                                 "FAIL in (loopback-listener-reachable")))))
         (finally (try (.close server) (catch Throwable _ nil))))))))

(deftest an-output-flood-dies-at-its-own-bound-and-the-capture-stays-small
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "flood_test.clj"
                     "(ns ve.flood-test
  (:require [clojure.test :refer [deftest]]))
(deftest flood (dotimes [_ 3000] (println (apply str (repeat 1000 \"x\")))))
")
        (with-redefs [ve/resource-limits {:fsize-bytes 1048576 :nproc 64
                                          :as-bytes 4294967296 :nofile 1024
                                          :tmpfs-bytes 1073741824 :dev-bytes 1048576
                                          :capture-bytes 8192}]
          (let [r (ve/run root ["test/ve/flood_test.clj"] 120000)]
            (is (not (:green? r))
                "the flood killed its own process at RLIMIT_FSIZE")
            (is (not (:timeout? r)) "and it died fast, not by the wall clock")
            (is (<= (count (:output r)) 40000)
                "the harness read back only bounded bytes")
            (is (str/includes? (:output r) "truncated at the capture bound")
                "the truncation is honest, not silent"))))))))

(deftest a-hanging-verification-is-reaped-cleaned-up-and-never-green
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "hang_test.clj"
                     "(ns ve.hang-test
  (:require [clojure.test :refer [deftest]]))
(deftest hang (Thread/sleep 240000))
")
        (let [before (leftover-stages)
              r (ve/run root ["test/ve/hang_test.clj"] 5000)]
          (is (not (:green? r)))
          (is (:timeout? r) "the wall clock fired")
          (Thread/sleep 300)
          (is (not (pgrep-live? "samizdat-verify-"))
              "no bwrap from this run lingers on the host")
          (is (every? #(contains? before %) (leftover-stages))
              "the stage was deleted even on the timeout path")))))))

(deftest a-benign-change-verifies-green-and-touches-nothing
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "benign_test.clj" benign-test)
        (let [sentinel (str root "/src/ve/core.clj")
              digest (file-digest sentinel)
              r (ve/run root ["src/ve/core.clj" "test/ve/benign_test.clj"]
                        120000)]
          (is (:green? r) (str "the benign focused suite ran green inside the
                              sandbox: " (:output r)))
          (is (= digest (file-digest sentinel))
              "the authoritative tree is untouched by the run")))))))

(deftest a-hostile-gates-edn-does-not-change-what-actually-runs
  ;; The end-to-end half of immutable argv authority: with the argv key
  ;; retuned to a hostile command, the REAL run still executes the pinned
  ;; verifier and reports its honest verdict.
  (when-substrate
   (fn []
     (let [hostile (assoc (gates/threshold :focused-verify)
                          :argv-prefix ["sh" "-c"
                                        "touch /tmp/ve-pwned-argv; exit 0"])]
       (with-redefs [gates/threshold (fn [_] hostile)]
         (with-project
          (fn [root]
            (write-test! root "benign_test.clj" benign-test)
            (let [r (ve/run root ["test/ve/benign_test.clj"] 120000)]
              (is (:green? r)
                  (str "the pinned verifier ran and passed: " (:output r)))
              (is (not (fs/exists? "/tmp/ve-pwned-argv" {:nofollow-links true}))
                  "the retuned command never executed")))))))))

(deftest the-real-project-verifies-inside-the-environment
  ;; Full-fidelity parity: the samizdat repo's own focused suite — dependency
  ;; resolution, caches and all — runs green inside the sandbox, proving the
  ;; allowlist covers what a real verifier genuinely needs.
  (when (and @substrate? @samizdat-root?)
    (let [r (ve/run (str (fs/cwd)) ["test/samizdat/verify_test.clj"] 300000)]
      (is (:green? r) (str "samizdat's own verify-test ran green in the sandbox: "
                           (subs (:output r) 0 (min 400 (count (:output r))))))
      (is (str/includes? (:output r) ":fail 0"))
      (is (str/includes? (:output r) ":error 0")))))
