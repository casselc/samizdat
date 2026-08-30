;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.smolvm-verification-env-test
  "The SmolVM VerificationEnvironment's own suite — the port of bbagent's
  A3a/A3b/A3c worker tests to this provider, plus this provider's own
  contract.

  Three kinds of test live here.

  The PURE ones pin the controller's authority without spawning anything:
  the pinned in-image verifier argv ignores gates.edn (runtime-mutable by
  the tier under observation), the manager command line holds the measured
  contract (image, one read-only mount, constructed environment, prelude
  data, NO --net), identity and input refusals fail closed, the invocation
  counter moves only for real spawns, the envelopes conform to the SPI's
  grammar-independent rule set, and the provider selection is trusted and
  fail-closed in both directions.

  The SUBSTRATE ones are real: a hostile test namespace — written exactly
  as a bounded model would write it through project/edit, and run by the
  PINNED in-image verifier inside a real ephemeral machine — attempts to
  overwrite the authoritative project, read host files outside it, read the
  excluded .git, reach a live host listener, unmask the raw export, leave a
  daemon behind, and flood the output. The assertions are HOST-side facts
  (nothing landed, nothing survives, the authoritative tree is
  byte-identical, no machine is running) plus the in-machine evidence that
  every attempt failed. Two direct-manager probes bracket the substrate
  itself: the manager forwards no host environment (sentinels in the very
  process that launches it), and there is no route off the machine.

  They run wherever the substrate exists (Linux + the measured smolvm
  manager + a guest image + KVM); where it does not, the same tests pin the
  other half of the contract — the run REFUSES, it never degrades to the
  bwrap environment or a host spawn.

  The guest archive is resolved the way bbagent's own test helper resolves
  one — SAMIZDAT_SMOLVM_IMAGE, BBAGENT_WORKER_IMAGE, or the archive a
  bbagent build already left in the temp dir — and is handed to the
  provider through its image seam for the duration of the suite. Nothing in
  src/ knows that path exists."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.verify :as verify]
            [samizdat.engine.proc :as proc]
            [samizdat.execution-env-spi-test :as spi-test]
            [samizdat.security.secrets :as secrets]
            [samizdat.security.smolvm-verification-env :as smve]
            [samizdat.security.verification-env :as bve]
            [samizdat.security.verification-provider :as vprov]))

;; ─── the guest image (test-only resolution, bbagent's own helper's order) ────

(defn- test-image-path
  "The guest archive the machine-backed tests run against: an explicit
  environment variable, or the archive a bbagent image build already left
  in the temp dir. Test-only — production resolution is SAMIZDAT_SMOLVM_IMAGE
  alone, and nothing in src/ reads this."
  []
  (let [by-name (fn [v]
                  (let [p (some-> v str/trim not-empty)]
                    (when (and p (fs/exists? p {:nofollow-links true})) p)))]
    (or (by-name (System/getenv "SAMIZDAT_SMOLVM_IMAGE"))
        (by-name (System/getenv "BBAGENT_WORKER_IMAGE"))
        (let [cached (str (fs/temp-dir) "/bbagent-worker-image.tar")]
          (when (fs/exists? cached {:nofollow-links true}) cached)))))

(defn- digest-of
  "The test-side digest of an archive — the same bounded sha256sum spawn
  the provider's own image pinning uses, so the fixture is honest about
  which bytes it is handing over."
  [path]
  (let [r (proc/run {:timeout-ms 120000} "sha256sum" (str path))]
    (assert (zero? (or (:exit r) 1)) "sha256sum of the test image failed")
    (str "sha256:" (first (str/split (str/trim (:out r)) #"\s+")))))

(def ^:private fixture-image
  (delay (test-image-path)))

(use-fixtures
 :once
 (fn [tests]
   ;; Hand the provider the test-resolved archive through its image seam for
   ;; the whole suite, ONLY when the controller did not configure one — the
   ;; configured path is the authority and the fixture must not mask it.
   (if (and (not (System/getenv "SAMIZDAT_SMOLVM_IMAGE")) @fixture-image)
     (let [path @fixture-image]
       (with-redefs [smve/guest-image
                     (fn [] {:image {:path path
                                     :digest (digest-of path)
                                     :bytes (or (fs/size path) 0)}})]
         (tests)))
     (tests))))

(def ^:private substrate?
  (delay (smve/available?)))

;; ─── fixtures ────────────────────────────────────────────────────────────────

(defn- seed-project!
  "A synthetic project root: deps.edn, one src ns, the protected run config,
  a git directory with a secret in it, and a nested excluded build dir —
  the authoritative tree a bounded run would verify, and must not be able
  to touch."
  [root]
  (fs/create-dirs (str root "/src/ve"))
  (fs/create-dirs (str root "/test/ve"))
  (fs/create-dirs (str root "/.samizdat"))
  (fs/create-dirs (str root "/.git"))
  (fs/create-dirs (str root "/src/deep/target"))
  (spit (str root "/deps.edn")
        "{:paths [\"src\" \"test\"]\n :aliases {:test {:extra-paths [\"test\"]}}}\n")
  (spit (str root "/src/ve/core.clj") "(ns ve.core)\n(defn two [] 2)\n")
  (spit (str root "/.samizdat/config.edn")
        "{:run {:verify-cmd \"bb -e '(quote nothing)'\"}}\n")
  (spit (str root "/.git/config") "[core] secret-git-config\n")
  (spit (str root "/src/deep/target/out.o") "binary\n"))

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
  "The hostile namespace, generated with the live host listener port and a
  live host sentinel path baked in. Every deftest ASSERTS THE ATTACK
  SUCCEEDED — green here would mean the boundary failed — so the expected
  outcome through a correct environment is RED with these very failures as
  the evidence, and no host-side damage."
  [host-port secret-marker]
  (str "(ns ve.hostile-test
  (:require [clojure.test :refer [deftest is]]))

(defn- attempt [f] (try (f) ::succeeded (catch Throwable t ::failed)))
(def HOST-PORT " host-port ")
(def SECRET-MARKER \"" secret-marker "\")

;; 1. write: the project's own files and the protected run config (the
;;    overlay should absorb them), and /etc of whatever host there is.
(deftest outside-root-write
  (is (= ::succeeded (attempt #(spit \"src/ve/core.clj\" \"pwned\"))))
  (is (= ::succeeded (attempt #(spit \".samizdat/config.edn\"
                                     \"{:run {:verify-cmd \\\"true\\\"}}\"))))
  (is (= ::succeeded (attempt #(spit \"/etc/pwned\" \"x\"))))
  (is (= ::succeeded (attempt #(spit \"/pwned-escape\" \"x\")))))

;; 2. read what the input coordinate refuses to describe: the excluded
;;    .git through BOTH of the places it could appear (the workspace and
;;    the raw export under it), and a host file outside the project.
(deftest excluded-and-host-reads
  (is (= ::succeeded (attempt #(slurp \".git/config\"))))
  (is (= ::succeeded (attempt #(slurp \"/input/.git/config\"))))
  (is (= ::succeeded (attempt #(slurp SECRET-MARKER)))))

;; 3. environment: dump everything the workload received, so the test
;;    record shows exactly what crossed the boundary.
(deftest env-dump
  (println \"HOSTILE-ENV-DUMP-BEGIN\")
  (doseq [[k v] (sort-by first (into {} (System/getenv)))] (println k \"=\" v))
  (println \"HOSTILE-ENV-DUMP-END\"))

;; 4. network: a listener is LIVE on the host loopback at HOST-PORT; inside
;;    a machine with no network it must be unreachable.
(deftest network-reachable
  (is (= ::succeeded (attempt #(java.net.Socket. \"127.0.0.1\" HOST-PORT)))))

;; 5. a daemon that outlives the verifier.
(deftest daemon-spawned
  (is (some? (babashka.process/process [\"sh\" \"-c\" \"exec sleep 547\"]))))
"))

(def ^:private hiding-test
  "The privilege boundary measured in A3c, asserted from inside: the
  workload holds no capabilities, is not root, and cannot unmask the raw
  project export."
  "(ns ve.hiding-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]))

(defn- sh [& args]
  (let [r (apply clojure.java.shell/sh args)]
    (str \"exit=\" (:exit r) \" out=\" (:out r) \" err=\" (:err r))))

(deftest umount-is-refused
  (println \"UMOUNT:\" (sh \"umount\" \"/input\"))
  (is (zero? (:exit (clojure.java.shell/sh \"umount\" \"/input\")))))

(deftest no-capabilities
  (println \"CAPS:\" (sh \"grep\" \"CapEff\" \"/proc/self/status\"))
  (is (str/includes? (:out (clojure.java.shell/sh \"grep\" \"CapEff\" \"/proc/self/status\"))
                     \"0000000000000000\")))

(deftest not-root
  (println \"ID:\" (sh \"id\" \"-u\"))
  (is (= \"0\" (str/trim (:out (clojure.java.shell/sh \"id\" \"-u\"))))))
")

(defn- host-secret-marker!
  "A file that exists on the HOST right now, in a location the machine
  cannot see: the machine's only host filesystem is the read-only project
  mount."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "smve-host-secret-"}))
        marker (str dir "/marker")]
    (spit marker "HOST-SECRET-DO-NOT-LEAK")
    marker))

(defn- live-listener
  "Bind a loopback listener host-side and return [server port] — reachable
  from the host, unreachable inside a machine with no network."
  []
  (let [server (java.net.ServerSocket. 0)]
    [server (.getLocalPort server)]))

(defn- machine-names
  "The ephemeral machines the manager's table currently names, as a set."
  []
  (let [r (proc/run {:timeout-ms 20000}
                    (str (fs/which "smolvm")) "machine" "ls")]
    (set (re-seq #"vm-[0-9a-f]+" (str (:out r))))))

(defn- machines-since
  "The machines that appeared since `baseline` and are still there.

  NOT `is any machine running` — that was the assertion here, and it is a
  claim this test is not entitled to make. The manager's table is host-wide:
  another test, another run, another process may legitimately own a machine
  while this one runs, and reading its presence as this run's leak is the
  same cross-run confusion the production cleanup had (JS2 convergence §7).
  The teardown claim is about the machine THIS run started, so the assertion
  is scoped to what appeared while it ran."
  [baseline]
  (into #{} (remove baseline) (machine-names)))

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
  (let [root (str (fs/create-temp-dir {:prefix "smve-project-"}))]
    (try
      (seed-project! root)
      (f root)
      (finally (fs/delete-tree root)))))

(defn- when-substrate [f]
  (if @substrate?
    (f)
    (testing "substrate unavailable here: the refusal contract instead"
      (let [r (smve/run "/nonexistent-root" ["test/ve/benign_test.clj"] 1000)]
        (is (or (:unavailable? r) (not (:green? r)))
            "no substrate => never green, and never a host spawn")))))

;; ─── the pinned authority (pure — runs everywhere) ───────────────────────────

(deftest focused-argv-is-the-pinned-in-image-verifier-not-gates-edn
  (let [argv (smve/focused-argv ["src/ve/core.clj" "test/ve/benign_test.clj"])]
    (is (vector? argv))
    (is (= 5 (count argv))
        "in-image executable, three fixed args, one derived expression")
    (is (= "bb" (first argv))
        "the executable is the IN-IMAGE toolchain — never a host path, never
        resolved against a host PATH")
    (is (= ["--classpath" "src:test:gui" "-e"] (vec (butlast (rest argv))))
        "the fixed argv is the controller constant, verbatim")
    (is (not-any? #{"sh" "-c"} argv) "no shell anywhere in the vector"))
  (testing "nothing verifiable => nil, never a command"
    (is (nil? (smve/focused-argv ["src/ve/core.clj"])))
    (is (nil? (smve/focused-argv [])))))

(deftest a-retuned-gates-edn-cannot-widen-the-verifier-argv
  ;; gates.edn is runtime-mutable by the tier this gate judges. Retune the
  ;; argv key to a hostile command and the derivation must not move — the
  ;; pinned table is code.
  (let [hostile (assoc (gates/threshold :focused-verify)
                       :argv-prefix ["sh" "-c" "touch /tmp/smve-pwned; exit 0"])]
    (with-redefs [gates/threshold (fn [_] hostile)]
      (let [argv (smve/focused-argv ["test/ve/benign_test.clj"])]
        (is (some? argv))
        (is (= 5 (count argv)) "same shape under a hostile gates.edn")
        (is (= smve/verifier-fixed-args (vec (butlast (rest argv))))
            "the fixed args are still the controller constants")
        (is (not-any? #{"sh" "-c" "touch"} argv)
            "nothing from the retuned data reached the argv"))))

  (testing "a crafted edited path contributes no argv element"
    (let [crafted "test/foo'; touch /tmp/smve-pwned; echo '_x.clj"
          argv (smve/focused-argv [crafted "test/ve/benign_test.clj"])]
      (is (= 5 (count argv)))
      (is (not (str/includes? (pr-str argv) "smve-pwned")))
      (is (nil? (smve/focused-argv [crafted]))
          "only crafted names => no argv at all"))))

(deftest every-lane-shares-one-focused-derivation
  ;; The parity fixture: the ordinary sh lane, the bwrap lane and the
  ;; SmolVM lane verify the SAME namespaces for the same changed files,
  ;; because all three embed the ONE expression.
  (let [changed ["src/ve/core.clj" "test/ve/benign_test.clj"]
        expr (verify/focused-expr ["ve.benign-test"])]
    (is (str/ends-with? (last (smve/focused-argv changed)) expr)
        "the SmolVM lane's one variable element is the shared expression")
    (is (str/includes? (verify/focused-cmd changed) expr)
        "the ordinary lane's command embeds the same expression")
    (is (= (last (bve/focused-argv changed)) (last (smve/focused-argv changed)))
        "the bwrap lane's derived element is byte-identical")))

(deftest the-guest-environment-is-constructed-and-carries-no-credentials
  (testing "exactly the constructed names exist"
    (is (= #{"TMPDIR" "LANG" "PATH"} (set (keys smve/guest-environment)))))
  (testing "no name is credential-shaped"
    (doseq [k (keys smve/guest-environment)]
      (is (not (secrets/sensitive-name? k)) (str k " is in the guest env"))))
  (testing "no host path appears in any value"
    (doseq [[_ v] smve/guest-environment]
      (is (not (str/starts-with? v "/home/")) "no host home leaks by value"))))

;; ─── identity and input refusals (pure) ─────────────────────────────────────

(deftest a-root-or-malformed-identity-is-refused
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"non-root uid"
       (smve/guest-identity {:uid 0 :gid 0})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"non-root uid"
       (smve/guest-identity {:uid -1 :gid 0})))
  (is (= "1000:1000" (smve/guest-identity {:uid 1000 :gid 1000})))
  (testing "and the derivation itself fails closed on a root-owned project"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"owned by root"
         (smve/project-identity "/tmp" {:stat-run (fn [& _]
                                                    {:exit 0 :out "0:0\n"})})))
    (testing "on an unreadable identity"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"could not be read"
           (smve/project-identity "/tmp" {:stat-run (fn [& _]
                                                      {:exit 1 :out ""})})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"could not be read"
           (smve/project-identity
            "/tmp" {:stat-run (fn [& _] {:exit 0 :out "chuck:chuck\n"})}))))))

(deftest an-unrepresentable-symlink-stops-the-run
  (with-project
   (fn [root]
     (fs/create-sym-link (str root "/escape") "/etc/passwd")
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"faithful"
          (smve/input-manifest root)))
     (fs/delete (str root "/escape"))
     (fs/create-sym-link (str root "/escape") "../../outside")
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"faithful"
          (smve/input-manifest root)))
     (testing "and a faithful relative link is described, not followed"
       (fs/delete (str root "/escape"))
       (fs/create-sym-link (str root "/src/link") "core.clj")
       (let [entries (-> (smve/input-manifest root) :workspace/entries)]
         (is (some #(= {:path "src/link" :kind :link :target "core.clj"} %)
                   entries)))))))

(deftest the-manifest-hides-exactly-what-it-refuses-to-describe
  (with-project
   (fn [root]
     (let [m (smve/input-manifest root)]
       (is (contains? (set (:workspace/excluded-paths m)) ".git")
           "the top-level exclusion is recorded")
       (is (contains? (set (:workspace/excluded-paths m)) "src/deep/target")
           "a nested exclusion is recorded at depth")
       (is (not (some #(str/starts-with? (:path %) ".git")
                      (:workspace/entries m)))
           "and nothing under it is described")
        (is (= smve/workspace-exclusions
               (set (-> m :workspace/exclusions))))))))

(deftest both-environments-name-one-input-the-same-way
  ;; The parity fixture on the input side: over the same tree, the bwrap
  ;; environment's private-copy coordinate and the SmolVM environment's
  ;; live-tree bracket coordinate are the SAME digest — one grammar, one
  ;; manifest, one kind — so a verify envelope from either names its input
  ;; identically.
  (with-project
   (fn [root]
     ;; a faithful link exercises the shared :link entry shape too
     (fs/create-sym-link (str root "/src/link") "core.clj")
     (is (= (bve/input-coordinate root)
            (smve/input-coordinate root))
         "the two environments' input coordinates disagree over one tree"))))

(deftest an-unmeasured-manager-version-is-refused
  (testing "no manager at all"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"No machine manager"
         (smve/approved-manager {:worker/available? false} #{"1.7.5"}))))
  (testing "a version nobody has measured"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"has not been measured"
         (smve/approved-manager {:worker/available? true
                                 :worker/version "smolvm 9.9.9"}
                                #{"1.7.5"})))
    (testing "even when it is a real-looking one"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"has not been measured"
           (smve/approved-manager {:worker/available? true
                                   :worker/version "smolvm 1.7.6"}
                                  #{"1.7.5"})))))
  (testing "the measured one is recognized"
    (is (= {:version "1.7.5" :approval :recognized}
           (smve/approved-manager {:worker/available? true
                                   :worker/version "smolvm 1.7.5"}
                                  #{"1.7.5"})))))

;; ─── the manager command line (pure over the composed argv) ─────────────────

(deftest the-machine-argv-holds-the-measured-contract
  (when @substrate?
    (with-project
     (fn [root]
       (write-test! root "benign_test.clj" benign-test)
       (let [{:keys [argv identity hidden verifier-argv]}
             (smve/build-verification root
                                      ["test/ve/benign_test.clj"] 60000)
              at (fn [flag] (some (fn [[x y]] (when (= x flag) y))
                                  (map vector argv (rest argv))))
              flat (set argv)]
         (is (str/ends-with? (first argv) "/smolvm")
             "the manager is absolute, controller resolved")
         (is (string? (at "--image")))
         (is (= (str (fs/canonicalize root) ":/input:ro") (at "-v"))
             "the project is the ONE host path mounted, read-only, at /input")
         (is (not (contains? flat "--net"))
             "no --net anywhere — outbound networking is never asked for")
         (is (= "65000ms" (at "--timeout"))
             "the manager's deadline sits 5s BEHIND the 60s host deadline")
         (is (every? #(contains? flat %)
                     ["-e" "LANG=C.UTF-8"
                      (str "PATH=" (get smve/guest-environment "PATH"))
                      "TMPDIR=/tmp"])
             "the guest environment is the constructed one, sorted")
         (is (= ["/usr/local/bin/bbagent-prelude" "1" identity
                 (str (count hidden))]
                (->> argv (drop-while #(not= % "--")) rest (take 4)))
             "the prelude, its contract, the derived identity, and the hidden count")
         (is (= "." (nth (drop-while #(not= % "--") argv) (+ 4 1 (count hidden))))
             "the cwd is the workspace root")
         (is (= (vec verifier-argv)
                (->> argv (drop-while #(not= % "--")) rest
                     (drop (+ 4 1 (count hidden))) vec))
             "the pinned verifier argv follows the prelude data, verbatim")
         (is (str/starts-with? identity (str (:uid (smve/project-identity root)) ":"))
             "the identity is derived from the project's owner")
         (is (contains? (set hidden) ".git")
             "the hidden set is the manifest's refused paths"))))))

;; ─── the substrate contract (pure over redefs) ───────────────────────────────

(deftest an-unavailable-substrate-refuses-never-degrades
  (with-redefs [smve/available? (fn [] false)
                smve/unavailable-reason (fn [] :no-manager)]
    (with-project
     (fn [root]
       (let [ran (atom nil)]
         (with-redefs [proc/run-bounded (fn [& args] (reset! ran args)
                                          {:status :exited :exit 0})]
           (let [r (smve/run root ["test/ve/benign_test.clj"] 60000)]
             (is (not (:green? r)))
             (is (:unavailable? r)
                 "the refusal is marked, not read as red tests")
             (is (= :no-manager (:reason r)))
             (is (nil? @ran)
                 "NOTHING was spawned — no fallback to a host spawn")
             (is (nil? (smve/verify-envelope r))
                 "a refused request has no spawn and so no envelope")
             (is (= :spi.refusal/manager-unavailable
                    (get-in r [:refusal :environment/refusal :refusal/category]))
                 "the refusal carries its catalogued SPI spelling")))))))
  (testing "an unconfigured image refuses the same way"
    (with-redefs [smve/available? (fn [] false)
                  smve/unavailable-reason (fn [] :no-guest-image)]
      (with-project
       (fn [root]
         (let [r (smve/run root ["test/ve/benign_test.clj"] 60000)]
           (is (:unavailable? r))
           (is (= :spi.refusal/guest-image-unusable
                  (get-in r [:refusal :environment/refusal :refusal/category]))))))))
  (testing "a digest mismatch refuses with its own catalogue entry"
    (with-redefs [smve/available? (fn [] false)
                  smve/unavailable-reason (fn [] :guest-image-digest-mismatch)]
      (let [r (smve/run "/nonexistent" ["test/ve/benign_test.clj"] 1000)]
        (is (= :spi.refusal/guest-image-digest-mismatch
               (get-in r [:refusal :environment/refusal :refusal/category])))))))

(deftest the-invocation-counter-moves-only-for-real-spawns
  (with-redefs [smve/available? (fn [] false)
                smve/unavailable-reason (fn [] :no-manager)]
    (let [before (smve/invocation-count)]
      (smve/run "/nonexistent" ["test/ve/benign_test.clj"] 1000)
      (smve/run "/nonexistent" ["src/ve/core.clj"] 1000)   ; nothing verifiable
      (is (= before (smve/invocation-count))
          "refused requests never claim an index")))
  (testing "an input failure never claims one either"
    (with-redefs [smve/available? (fn [] true)
                  smve/guest-image (fn [] {:image {:path "/nonexistent-archive"
                                                    :digest "sha256:x"
                                                    :bytes 1}})]
      (with-project
       (fn [root]
         (fs/create-sym-link (str root "/escape") "/etc/passwd")
         (let [before (smve/invocation-count)]
           (let [r (smve/run root ["test/ve/benign_test.clj"] 1000)]
             (is (not (:green? r)) "the unrepresentable input is not green")
             (is (not (:unavailable? r))
                 "and reads as evidence, not as a substrate refusal"))
           (is (= before (smve/invocation-count))
               "an input failure attempted no execution")))))))

;; ─── envelopes (pure — the SPI rule set over synthetic results) ──────────────

(deftest the-envelopes-conform-to-the-shared-rule-set
  (with-redefs [smve/available? (fn [] true)
                smve/guest-image (fn [] {:image {:path "/nonexistent-archive"
                                                 :digest "sha256:x"
                                                 :bytes 1}})
                smve/describe-manager (fn [] {:worker/available? true
                                              :worker/version "smolvm 1.7.5"})]
    (let [reference {:environment/coordinate (smve/environment-coordinate)
                     :environment/type :samizdat/smolvm-verification-env}
          input (str "sha256:" (apply str (repeat 4 "0123456789abcdef")))
          streams (fn [out-bytes]
                    {:stdout {:text "x" :bytes out-bytes :truncated? false}
                     :stderr {:text "" :bytes 0 :truncated? false}})]
      (testing "describe"
        (is (spi-test/shape-validate (smve/describe-envelope)))
        (is (spi-test/shape-validate
             {:spi/version 1 :spi/kind :spi.environment/describe
              :environment/description (:environment/description
                                        (smve/describe-envelope))
              :environment/coordinate (smve/environment-coordinate)})))
      (testing "availability — both answers"
        (is (spi-test/shape-validate (smve/availability-envelope)))
        (with-redefs [smve/available? (fn [] false)
                      smve/unavailable-reason (fn [] :sandbox-unavailable)]
          (is (spi-test/shape-validate (smve/availability-envelope)))))
      (testing "a completed run"
        (let [r (merge {:invocation-index 1 :duration-ms 10
                        :attribution reference
                        :project/input-stable? true
                        :input-coordinate input
                        :worker/status :completed
                        :exit 0 :green? true :timeout? false :output ""}
                       (streams 3))]
          (is (spi-test/shape-validate (smve/verify-envelope r)))))
      (testing "a timeout carries no invented exit"
        (let [r (merge {:invocation-index 2 :duration-ms 8003
                        :attribution reference
                        :project/input-stable? true
                        :input-coordinate input
                        :worker/status :timeout
                        :green? false :timeout? true :output ""}
                       (streams 0))
              e (smve/verify-envelope r)]
          (is (spi-test/shape-validate e))
          (is (= :timeout (:output/status e)))
          (is (not (contains? e :output/exit)))))
      (testing "a changed project demotes its outcome and claims no coordinate"
        (let [r (merge {:invocation-index 3 :duration-ms 4021
                        :attribution reference
                        :project/input-stable? false
                        :worker/status :completed
                        :exit 0 :green? false :timeout? false :output ""}
                       (streams 5))
              e (smve/verify-envelope r)]
          (is (spi-test/shape-validate e))
          (is (= :project-changed (:output/status e)))
          (is (= {:input/stability :input/project-changed} (:run/input e)))
          (is (= {:process/status :completed :process/exit 0}
                 (:output/process e))
              "the process outcome survives only demoted")
          (is (not (contains? e :output/exit))
              "an unanchored run does not carry an exit at the top")))
      (testing "a worker failure carries the authored error, never a message"
        (let [r (merge {:invocation-index 4 :duration-ms 61
                        :attribution reference
                        :project/input-stable? true
                        :input-coordinate input
                        :worker/status :worker-failure
                        :green? false :timeout? false :output "boom"}
                       (streams 0))
              e (smve/verify-envelope r)]
          (is (spi-test/shape-validate e))
          (is (= "verification environment run failed" (:output/error e)))))
      (testing "a refusal result has no envelope at all"
        (is (nil? (smve/verify-envelope {:green? false :unavailable? true})))))))

;; ─── provider selection (pure) ──────────────────────────────────────────────

(deftest selection-is-trusted-controller-policy-and-fails-closed
  (is (contains? vprov/providers (vprov/selected))
      "the ambient selection is always a real provider or ::unknown")
  (testing "each provider answers with its own availability"
    (with-redefs [vprov/selected (fn [] :smolvm)]
      (with-redefs [smve/available? (fn [] false)
                    smve/unavailable-reason (fn [] :no-guest-image)]
        (is (false? (vprov/available?)))
        (is (= :no-guest-image (vprov/unavailable-reason))
            "the refusal is the selected provider's own — no fallback")))
    (with-redefs [vprov/selected (fn [] :bwrap)]
      (with-redefs [bve/available? (fn [] false)
                    bve/unavailable-reason (fn [] :not-linux)]
        (is (false? (vprov/available?)))
        (is (= :not-linux (vprov/unavailable-reason))))))
  (testing "an unrecognized selection name is itself a refusal"
    (with-redefs [vprov/selected (fn [] :samizdat.security.verification-provider/unknown)]
      (is (false? (vprov/available?)))
      (is (= :unknown-provider (vprov/unavailable-reason)))
      (let [r (vprov/run "/nonexistent" ["test/ve/benign_test.clj"] 1000)]
        (is (not (:green? r)))
        (is (:unavailable? r) "never green, never spawned")))))

;; ─── the real adversarial runs (substrate) ───────────────────────────────────

(deftest a-benign-change-verifies-green-and-touches-nothing
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "benign_test.clj" benign-test)
        (let [sentinel (str root "/src/ve/core.clj")
              digest (file-digest sentinel)
              config-digest (file-digest (str root "/.samizdat/config.edn"))
              before (smve/invocation-count)
              r (smve/run root ["src/ve/core.clj" "test/ve/benign_test.clj"]
                          120000)]
          (is (:green? r) (str "the benign focused suite ran green inside the
                               machine: " (:output r)))
          (is (zero? (:exit r)))
          (is (= (inc before) (smve/invocation-count))
              "exactly one real spawn was claimed")
          (is (true? (:project/input-stable? r)))
          (is (= (smve/input-coordinate root) (:input-coordinate r))
              "the run is bracketed by the tree's own coordinate")
          (testing "the manager's banner is not reported as workload stderr"
            (is (= "" (get-in r [:stderr :text])))
            (is (zero? (get-in r [:stderr :bytes]))))
          (testing "the authoritative tree is untouched"
            (is (= digest (file-digest sentinel)))
            (is (= config-digest
                   (file-digest (str root "/.samizdat/config.edn")))))
          (testing "and the run envelope conforms"
            (is (spi-test/shape-validate (smve/verify-envelope r))))))))))

(deftest a-hostile-test-namespace-cannot-reach-the-host
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (let [marker (host-secret-marker!)
              [server port] (live-listener)]
          (try
            (write-test! root "hostile_test.clj" (hostile-test port marker))
            (let [core-before (file-digest (str root "/src/ve/core.clj"))
                  config-before (file-digest (str root "/.samizdat/config.edn"))
                  baseline (machine-names)
                  r (smve/run root ["test/ve/hostile_test.clj"] 120000)]
              (testing "the hostile run is not green — its attacks failed in-machine"
                (is (not (:green? r)))
                (is (not (:timeout? r)) (str "it completed, promptly: " r))
                (is (= 1 (:exit r)) "clojure.test's own failure exit")
                (doseq [attempt ["outside-root-write" "excluded-and-host-reads"
                                 "network-reachable"]]
                  (is (str/includes? (:output r) (str "FAIL in (" attempt))
                      (str "the " attempt " attack failed INSIDE the machine"))))
              (testing "the workload believed its writes succeeded — and the host disagrees"
                (is (str/includes? (:output r) "Ran 5 tests"))
                (is (= core-before (file-digest (str root "/src/ve/core.clj")))
                    "the overlay absorbed the project overwrite; the host tree is byte-identical")
                (is (= config-before
                       (file-digest (str root "/.samizdat/config.edn")))
                    "the protected run config is exactly what it was"))
              (testing "nothing landed on the host"
                (is (not (fs/exists? "/etc/pwned" {:nofollow-links true})))
                (is (not (fs/exists? "/pwned-escape" {:nofollow-links true}))))
              (testing "the excluded and the outside are unreadable"
                (is (not (str/includes? (:output r) "secret-git-config"))
                    "the .git config never reached the workload's output")
                (is (not (str/includes? (:output r) "HOST-SECRET-DO-NOT-LEAK"))
                    "the host marker's content never reached the output"))
              (testing "the environment the workload saw is the constructed one"
                (is (str/includes? (:output r) "HOME = /storage/home"))
                (is (str/includes? (:output r) "PWD = /work"))
                (let [dump (subs (:output r)
                                 (str/index-of (:output r) "HOSTILE-ENV-DUMP-BEGIN")
                                 (str/index-of (:output r) "HOSTILE-ENV-DUMP-END"))
                      names (into #{} (comp (map #(first (str/split % #" ")))
                                            (remove str/blank?))
                                  (str/split-lines dump))]
                  (is (every? names (keys smve/guest-environment))
                      "every constructed name arrived")
                  (is (every? #(not (secrets/sensitive-name? %)) names)
                      "no credential-shaped name arrived")))
              (testing "no machine outlives the run"
                (is (empty? (machines-since baseline))
                    "the ephemeral machine is gone, daemon and all")))
            (finally
              (try (.close server) (catch Throwable _ nil))
              (fs/delete-tree (str (fs/parent (fs/path marker))))))))))))

(deftest a-live-host-listener-is-unreachable-from-inside-the-machine
  (when-substrate
   (fn []
     (let [[server port] (live-listener)]
       (try
         (is (try (let [s (java.net.Socket. "127.0.0.1" port)]
                    (.close s) true)
                  (catch Throwable _ false))
             "sanity: the listener is reachable from the host")
         (with-project
          (fn [root]
            (write-test! root "probe_test.clj"
                         (str "(ns ve.probe-test
  (:require [clojure.test :refer [deftest is]]))
(defn- attempt [f] (try (f) ::succeeded (catch Throwable t ::failed)))
(deftest loopback-listener-reachable
  (is (= ::succeeded (attempt #(java.net.Socket. \"127.0.0.1\" " port ")))))
"))
            (let [r (smve/run root ["test/ve/probe_test.clj"] 120000)]
              (is (not (:green? r)) "the in-machine connection failed")
              (is (str/includes? (:output r)
                                 "FAIL in (loopback-listener-reachable")))))
         (finally (try (.close server) (catch Throwable _ nil))))))))

(deftest an-output-flood-is-bounded-and-honestly-counted
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "flood_test.clj"
                     "(ns ve.flood-test
  (:require [clojure.test :refer [deftest]]))
(deftest flood (dotimes [_ 3000] (println (apply str (repeat 1000 \"x\")))))
")
        (with-redefs [smve/resource-limits
                      (assoc smve/resource-limits
                             :worker/stdout-max-bytes 4096
                             :worker/stderr-max-bytes 4096)]
          (let [r (smve/run root ["test/ve/flood_test.clj"] 120000)]
            (is (not (:timeout? r)) "the flood drained, it did not hang")
            (testing "what was kept is bounded"
              (is (<= (count (.getBytes ^String (get-in r [:stdout :text]) "UTF-8"))
                      (+ 4096 200)))
              (is (true? (get-in r [:stdout :truncated?])))
              (is (str/includes? (get-in r [:stdout :text]) "truncated at the capture bound")
                  "the truncation is honest, not silent"))
            (testing "and the TRUE size is reported, not the kept size"
              (is (>= (get-in r [:stdout :bytes]) 3000000)
                  "the byte count names what the workload wrote"))))))))

(deftest a-hanging-verification-times-out-with-no-invented-exit
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "hang_test.clj"
                     "(ns ve.hang-test
  (:require [clojure.test :refer [deftest]]))
(deftest hang (Thread/sleep 240000))
")
        (let [baseline (machine-names)
              r (smve/run root ["test/ve/hang_test.clj"] 8000)]
          (is (:timeout? r) "the wall clock fired")
          (is (not (:green? r)))
          (is (not (contains? r :exit))
              "no exit status is invented for a workload that never exited")
          (is (>= (:duration-ms r) 8000))
          (is (< (:duration-ms r) 60000))
          (is (= :terminated
                 (:run/disposition (smve/verify-envelope r))))
          (Thread/sleep 300)
          (is (empty? (machines-since baseline))
              "the machine THIS run started was destroyed with the process
              tree — nothing inside it can still be running"))))))))

(deftest a-deadline-is-not-a-program-that-chose-a-status
  ;; The manager reports its own deadline as 124, the conventional status
  ;; for a timeout. A program is free to exit 124 for its own reasons, so
  ;; the classification cannot come from the number.
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "choose_exit_test.clj"
                     "(ns ve.choose-exit-test
  (:require [clojure.test :refer [deftest is]]))
(deftest exit-is-mine (is (= 1 1)))
(System/exit 124)
")
        (write-test! root "hang2_test.clj"
                     "(ns ve.hang2-test
  (:require [clojure.test :refer [deftest]]))
(deftest hang (Thread/sleep 240000))
")
        (let [chose (smve/run root ["test/ve/choose_exit_test.clj"] 60000)
              deadline (smve/run root ["test/ve/hang2_test.clj"] 8000)]
          (is (= :completed (:worker/status chose)))
          (is (= 124 (:exit chose)))
          (is (not (:green? chose)) "the workload CHOSE 124; that is red, not a timeout")
          (is (= :timeout (:worker/status deadline)))
          (is (not (contains? deadline :exit)))
          (is (not= (:worker/status chose) (:worker/status deadline)))))))))

(deftest every-execution-gets-a-machine-that-never-ran-anything
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "residue_test.clj"
                     "(ns ve.residue-test
  (:require [clojure.test :refer [deftest is]]))
(deftest leave-residue
  (spit \"residue.txt\" \"left-behind\")
  (is (= \"left-behind\" (slurp \"residue.txt\"))))
")
        (write-test! root "fresh_test.clj"
                     "(ns ve.fresh-test
  (:require [clojure.test :refer [deftest is]]))
(deftest residue-absent
  (is (thrown? Exception (slurp \"residue.txt\"))))
")
        (is (:green? (smve/run root ["test/ve/residue_test.clj"] 120000))
            "the first run believed it wrote the residue file")
        (let [r (smve/run root ["test/ve/fresh_test.clj"] 120000)]
          (is (:green? r)
              (str "the second run's machine never saw the first one's writes: "
                   (:output r))))
        (is (not (fs/exists? (str root "/residue.txt")
                             {:nofollow-links true}))
            "and none of it reached the authoritative tree")))))

(deftest hiding-is-enforced-not-merely-observed
  ;; A3c's privilege boundary, from inside: the workload holds no
  ;; capabilities, is not root, and cannot unmask the raw export.
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "hiding_test.clj" hiding-test)
        (let [r (smve/run root ["test/ve/hiding_test.clj"] 120000)
              out (:output r)]
          (is (not (:green? r)) "every privilege assertion failed, as it must")
          (is (re-find #"(?i)not permitted|must be superuser|permission denied" out)
              "the workload was able to unmount the raw project export")
          (is (str/includes? out "CapEff:\t0000000000000000")
              "the workload holds capabilities it should have given up")
          (is (str/includes? out "ID: exit=0 out=1000")
              "the workload runs as the project's owner, not root")
          (is (not (str/includes? out "secret-git-config"))
              "the raw export stayed masked"))))))))

(deftest a-project-that-moves-under-a-run-claims-no-coordinate
  ;; The overlay's lower layer is the live host tree, not a frozen copy, so
  ;; a concurrent edit is visible to the workload. A coordinate naming a
  ;; state the run did not entirely see would be worse than none.
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "slow_test.clj"
                     "(ns ve.slow-test
  (:require [clojure.test :refer [deftest is]]))
(deftest slow (Thread/sleep 4000) (is true))
")
        (let [mutate (future (Thread/sleep 1500)
                             (spit (str root "/src/ve/core.clj")
                                   "(ns ve.core)\n(defn two [] 3)\n"))
              r (smve/run root ["test/ve/slow_test.clj"] 120000)
              e (smve/verify-envelope r)]
          @mutate
          (is (= :completed (:worker/status r)))
          (is (zero? (:exit r)) "the workload itself passed")
          (is (false? (:project/input-stable? r)))
          (is (not (:green? r))
              "a green exit over a tree that moved is not a verification")
          (is (not (contains? r :input-coordinate)))
          (is (= :project-changed (:output/status e)))
          (is (= {:process/status :completed :process/exit 0}
                 (:output/process e))
              "the process outcome survives only demoted")
          (is (spi-test/shape-validate e))))))))

(deftest the-prelude-contract-is-checked-before-anything-mounts
  ;; A host and an image that disagree about the argument order must refuse
  ;; before a workspace exists, with the prelude's own marker — not run the
  ;; wrong thing inside one.
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (write-test! root "benign_test.clj" benign-test)
        (with-redefs [smve/prelude-contract "999"]
          (let [before (smve/invocation-count)
                r (smve/run root ["test/ve/benign_test.clj"] 120000)]
            (is (= :worker-failure (:worker/status r)))
            (is (not (contains? r :exit)) "the workload never ran")
            (is (zero? (get-in r [:stdout :bytes]))
                "the workload never ran, so it wrote nothing")
            (is (str/includes? (:output r) "prelude failed: contract")
                "the prelude's own marker names the mismatch")
            (is (= (inc before) (smve/invocation-count))
                "the spawn was claimed — it was a real, failed run")
            (is (= :worker-failure (:output/status (smve/verify-envelope r))))))))))

;; ─── the substrate itself, driven directly (port of bbagent's probes) ───────

(deftest the-manager-forwards-no-host-environment
  ;; The secret probe, against the manager itself: sentinels — including an
  ;; OPENAI_API_KEY — planted in the environment of the very process that
  ;; launches the machine. The measured property is that nothing crosses;
  ;; this is what the provider's "no host secrets" claim rests on.
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (let [image (:path (:image (smve/guest-image)))
              r (proc/run-bounded
                 {:timeout-ms 90000
                  :env {"PATH" (System/getenv "PATH")
                        "HOME" (System/getenv "HOME")
                        "SAMIZDAT_HOST_SENTINEL" "SENTINEL-HOST-CREDENTIAL"
                        "OPENAI_API_KEY" "sk-sentinel-must-not-cross"}
                  :out-max-bytes 65536 :err-max-bytes 65536}
                 (str (fs/which "smolvm")) "machine" "run"
                 "--image" image
                 "-v" (str root ":/input:ro")
                 "--" "/bin/sh" "-c" "env")]
          (is (= :exited (:status r)))
          (is (not (str/includes? (str (:stdout r)) "SENTINEL-HOST-CREDENTIAL")))
          (is (not (str/includes? (str (:stdout r)) "sk-sentinel-must-not-cross")))
          (is (not (str/includes? (str (:stdout r)) "SAMIZDAT_HOST_SENTINEL"))
              "not even the NAME crossed"))))))))

(deftest the-machine-has-no-route-off-it
  (when-substrate
   (fn []
     (with-project
      (fn [root]
        (let [image (:path (:image (smve/guest-image)))
              r (proc/run-bounded
                 {:timeout-ms 90000
                  :out-max-bytes 65536 :err-max-bytes 65536}
                 (str (fs/which "smolvm")) "machine" "run"
                 "--image" image
                 "-v" (str root ":/input:ro")
                 "--" "/bin/sh" "-c"
                 (str "ip route | grep -c default; "
                      "wget -T 3 -q -O- http://1.1.1.1 2>&1; "
                      "echo wget-rc=$?"))]
          (is (= :exited (:status r)))
          (is (str/starts-with? (str/trim (str (:stdout r))) "0")
              "there is no default route")
          (is (str/includes? (str (:stdout r)) "wget-rc=1"))
          (is (re-find #"unreachable|bad address|download timed out"
                       (str (:stdout r) (:stderr r)))
              "and an outbound attempt fails rather than hanging")))))))

(deftest a-process-tree-is-reaped-not-merely-abandoned
  ;; The direct proof, at the primitive that provides the guarantee: the
  ;; workload writes into a HOST directory every fifth of a second; if the
  ;; machine outlived the host's deadline the file would keep growing.
  ;; Killing only the manager's front end is measurably not enough (A3a) —
  ;; which is why the process facility destroys the descendants first.
  (when-substrate
   (fn []
     (let [beat (str (fs/create-temp-dir {:prefix "smve-heartbeat-"}))
           image (:path (:image (smve/guest-image)))
           tick (str beat "/tick")
           baseline (machine-names)
           r (proc/run-bounded
              {:timeout-ms 5000 :out-max-bytes 4096 :err-max-bytes 4096}
              (str (fs/which "smolvm")) "machine" "run"
              "--image" image
              "-v" (str beat ":/beat")
              "--" "/bin/sh" "-c"
              (str "while true; do echo t >> /beat/tick; "
                   "sleep 0.2; done & sleep 300"))
           at-deadline (count (str/split-lines (slurp tick)))]
       (try
         (is (= :timeout (:status r)))
         (is (pos? at-deadline) "the workload was genuinely running")
         (Thread/sleep 4000)
         (is (= at-deadline (count (str/split-lines (slurp tick))))
             "a backgrounded process inside the machine stopped when the
             machine did")
         (is (empty? (machines-since baseline)))
         (finally (fs/delete-tree beat)))))))
