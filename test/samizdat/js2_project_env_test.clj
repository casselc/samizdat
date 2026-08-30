;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.js2-project-env-test
  "The JS2 ProjectExecutionEnvironment's own suite — the half that needs no
  SCI. The bounded half (the semantic operation, its receipts, its replay and
  its lease) is samizdat.js2-project-run-test.

  Three kinds of test live here, and the split is the same one the verify
  environment's suite uses.

  The PURE ones pin the model's side of the request without spawning
  anything: an argv that is data and never a shell line, a working directory
  that cannot climb out, a timeout that narrows and never widens, and — the
  one that carries the most weight — a CLOSED option set, so every controller
  decision a model might try to make (the image, the network, the mounts, the
  environment, the resource limits, the identity) is refused by name rather
  than silently ignored. Beside them: the environment's description says what
  KIND of thing it is, and its coordinates cannot be confused with the verify
  environment's.

  The SUBSTRATE ones are real, and they are the milestone's central claim.
  Inside an actual ephemeral machine, a workload modifies, creates and deletes
  project files, chmods and renames them, dumps its environment looking for
  host credentials, reads host paths, reaches for the network, unmasks the raw
  export, and leaves a daemon behind. Every one of those SUCCEEDS where it is
  supposed to (the private workspace is genuinely writable — a development
  environment a compiler cannot write in is not one) and reaches nothing where
  it is not. The assertions are HOST-side facts: the authoritative tree's
  input coordinate is byte-identical afterwards, and no process survives.

  They run wherever the substrate exists; where it does not, the same tests
  pin the other half of the contract — `project/run` REFUSES, and never
  degrades to a host spawn.

  The COVERAGE ones pin JS2 §3B: what a closure result says about how much it
  covered, in both toolchain dialects, and the three narrow cases where a
  closure result stops being evidence at all."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.fs :as fs]
            [samizdat.agent.observation :as observation]
            [samizdat.engine.proc :as proc]
            [samizdat.security.closure-coverage :as coverage]
            [samizdat.security.project-execution-provider :as pep]
            [samizdat.security.smolvm-project-env :as spe]
            [samizdat.security.smolvm-verification-env :as smve]))

;; ─── the guest image (test-only resolution, the verify suite's order) ────────

(defn- test-image-path []
  (let [by-name (fn [v]
                  (let [p (some-> v str/trim not-empty)]
                    (when (and p (fs/exists? p {:nofollow-links true})) p)))]
    (or (by-name (System/getenv "SAMIZDAT_SMOLVM_IMAGE"))
        (by-name (System/getenv "BBAGENT_WORKER_IMAGE"))
        (let [cached (str (fs/temp-dir) "/bbagent-worker-image.tar")]
          (when (fs/exists? cached {:nofollow-links true}) cached)))))

(defn- digest-of [path]
  (let [r (proc/run {:timeout-ms 120000} "sha256sum" (str path))]
    (assert (zero? (or (:exit r) 1)) "sha256sum of the test image failed")
    (str "sha256:" (first (str/split (str/trim (:out r)) #"\s+")))))

(def ^:private fixture-image (delay (test-image-path)))

(use-fixtures
 :once
 (fn [tests]
   (if (and (not (System/getenv "SAMIZDAT_SMOLVM_IMAGE")) @fixture-image)
     (let [path @fixture-image]
       (with-redefs [smve/guest-image
                     (fn [] {:image {:path path
                                     :digest (digest-of path)
                                     :bytes (or (fs/size path) 0)}})]
         (tests)))
     (tests))))

(def ^:private substrate? (delay (spe/available?)))

(defn- seed-project!
  "A synthetic authoritative project root: the tree a run is handed
  READ-ONLY, and must not be able to change."
  [root]
  (fs/create-dirs (str root "/src/pe"))
  (fs/create-dirs (str root "/sub/dir"))
  (fs/create-dirs (str root "/.git"))
  (spit (str root "/deps.edn") "{:paths [\"src\"]}\n")
  (spit (str root "/src/pe/core.clj") "(ns pe.core)\n(defn two [] 2)\n")
  (spit (str root "/sub/dir/marker.txt") "marker\n")
  (spit (str root "/.git/config") "[core] secret-git-config\n"))

(defmacro with-project [[root] & body]
  `(let [~root (str (fs/create-temp-dir {:prefix "samizdat-js2-"}))]
     (try (seed-project! ~root) ~@body
          (finally (fs/delete-tree ~root)))))

(defn- tree-coordinate
  "The authoritative tree's own input coordinate — the fingerprint a run is
  bracketed by, and the thing that must not move because of one."
  [root]
  (smve/input-coordinate root))

(defn- run! [root argv & [options]]
  (spe/run root (spe/validate-request argv options)))

(defn- refusal-of [f]
  (try (f) nil
       (catch Throwable e (:samizdat.smolvm-project-env/error (ex-data e)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; D. Request validation — pure, and before anything is staged.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest argv-is-data-and-never-a-command-line
  (testing "a non-empty vector of non-blank strings, and nothing else"
    (is (= ["bb" "-M:test"] (:request/argv (spe/validate-request ["bb" "-M:test"] nil))))
    (is (= :run-argv-shape (refusal-of #(spe/validate-request [] nil))))
    (is (= :run-argv-shape (refusal-of #(spe/validate-request nil nil))))
    (is (= :run-argv-shape (refusal-of #(spe/validate-request "bb -M:test" nil)))
        "a shell string is not an argv, and is refused rather than split")
    (is (= :run-argv-shape (refusal-of #(spe/validate-request ["bb" ""] nil))))
    (is (= :run-argv-shape (refusal-of #(spe/validate-request ["bb" "  "] nil))))
    (is (= :run-argv-shape (refusal-of #(spe/validate-request ["bb" 7] nil))))
    (is (= :run-argv-shape (refusal-of #(spe/validate-request ["bb" nil] nil)))))
  (testing "bounded in both directions"
    (is (= :run-argv-long
           (refusal-of #(spe/validate-request
                         (vec (repeat (inc (:argv-max-length spe/request-limits))
                                      "x"))
                         nil))))
    (is (= :run-argv-shape
           (refusal-of #(spe/validate-request
                         ["bb" (apply str (repeat (inc (:argv-max-arg-chars
                                                        spe/request-limits))
                                                  "x"))]
                         nil)))))
  (testing "shell metacharacters are ORDINARY ARGUMENT TEXT, not a refusal"
    ;; The security boundary is the isolated world, not a character set. An
    ;; argv element containing a semicolon is one argument containing a
    ;; semicolon, because nothing ever parses it as a command line.
    (is (= ["bb" "-e" "(println \"a;b|c$(d)\")"]
           (:request/argv (spe/validate-request
                           ["bb" "-e" "(println \"a;b|c$(d)\")"] nil))))))

(deftest the-option-set-is-closed-so-controller-decisions-cannot-be-taken
  (testing "the complete set is :cwd and :timeout-ms"
    (is (= #{:cwd :timeout-ms} spe/request-option-keys)))
  (testing "everything a model must not choose is refused BY NAME"
    ;; Not ignored. A request that believed it turned the network on and was
    ;; quietly run without it would be a model drawing conclusions from an
    ;; environment it does not have.
    (doseq [k [:env :environment :network :net :image :mounts :volumes
               :cpus :memory :user :uid :timeout :host-cwd :provider
               :cleanup :verifier]]
      (is (= :run-options-unknown
             (refusal-of #(spe/validate-request ["bb"] {k "anything"})))
          (str "option " k " must be refused, not ignored"))))
  (testing "a non-map options argument is refused"
    (is (= :run-options-shape
           (refusal-of #(spe/validate-request ["bb"] "cwd=."))))))

(deftest the-working-directory-is-relative-and-cannot-climb-out
  (is (= "." (:request/cwd (spe/validate-request ["bb"] nil))))
  (is (= "." (:request/cwd (spe/validate-request ["bb"] {:cwd "."}))))
  (is (= "sub/dir" (:request/cwd (spe/validate-request ["bb"] {:cwd "sub/dir"}))))
  (is (= "sub/dir" (:request/cwd (spe/validate-request ["bb"] {:cwd "./sub/dir/"}))))
  (is (= :run-cwd-absolute (refusal-of #(spe/validate-request ["bb"] {:cwd "/etc"}))))
  (is (= :run-cwd-escape (refusal-of #(spe/validate-request ["bb"] {:cwd "../.."}))))
  (is (= :run-cwd-escape
         (refusal-of #(spe/validate-request ["bb"] {:cwd "sub/../../out"}))))
  (is (= :run-cwd-shape (refusal-of #(spe/validate-request ["bb"] {:cwd ""}))))
  (is (= :run-cwd-shape (refusal-of #(spe/validate-request ["bb"] {:cwd 3})))))

(deftest the-timeout-narrows-and-never-widens
  (let [{ceiling :worker/timeout-ms default :worker/default-timeout-ms}
        spe/resource-limits]
    (is (= default (:request/timeout-ms (spe/validate-request ["bb"] nil)))
        "an unspecified timeout is the pinned default, not the ceiling")
    (is (= 1000 (:request/timeout-ms
                 (spe/validate-request ["bb"] {:timeout-ms 1000})))
        "a smaller request narrows")
    (is (= ceiling (:request/timeout-ms
                    (spe/validate-request ["bb"] {:timeout-ms (* 10 ceiling)})))
        "a larger request is attenuated to the ceiling, never granted")
    (is (= :run-timeout-shape
           (refusal-of #(spe/validate-request ["bb"] {:timeout-ms 0}))))
    (is (= :run-timeout-shape
           (refusal-of #(spe/validate-request ["bb"] {:timeout-ms -1}))))
    (is (= :run-timeout-shape
           (refusal-of #(spe/validate-request ["bb"] {:timeout-ms "60s"}))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The environment says what KIND of thing it is.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest the-description-is-a-project-run-environment-not-a-verifier
  (let [d (spe/environment-description)]
    (is (= :samizdat/smolvm-project-env (:executor/type d)))
    (is (= :project-run (:executor/mode d)))
    (is (= #{:describe :run} (:executor/operations d)))
    (is (= :none (:executor/network d)))
    (is (= :none (get-in d [:executor/workspace :writeback]))
        "the description states the invariant the milestone rests on")
    (is (= :model-supplied (get-in d [:executor/command :argv])))
    (is (= :development-only (get-in d [:executor/command :authority])))
    (testing "and the verify environment still says it is verify-only"
      (let [v (smve/environment-description)]
        (is (= :verify-only (:executor/mode v)))
        (is (= #{:describe :verify} (:executor/operations v)))
        (is (not= (:executor/type v) (:executor/type d)))))))

(deftest the-two-environments-cannot-be-confused-by-coordinate
  ;; Different prefix AND different digest. Two kinds of evidence that could
  ;; be mistaken for each other would defeat the whole separation.
  (is (str/starts-with? (spe/coordinate) "js2-spe/v1:"))
  (is (str/starts-with? (smve/coordinate) "js1-smve/v1:"))
  (is (not= (spe/coordinate) (smve/coordinate)))
  (is (not= (spe/environment-coordinate) (smve/environment-coordinate))
      "the descriptions differ, so their canonical coordinates must"))

(deftest the-selection-is-trusted-and-fails-closed
  (is (= :smolvm (pep/selected)) "the standing default is code")
  (is (= #{:smolvm} pep/providers))
  (testing "an unrecognized controller selection refuses; it never defaults"
    (with-redefs [pep/selected (fn [] :samizdat.security.project-execution-provider/unknown)]
      (is (false? (pep/available?)))
      (is (= :unknown-provider (pep/unavailable-reason)))
      (is (= :refused (:status (pep/run "/tmp" {:request/argv ["bb"]}))))
      (is (nil? (pep/run-envelope {:invocation 1})))
      (is (= 0 (pep/invocation-count))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The invocation counter and the refusal path (pure over redefs).
;; ═══════════════════════════════════════════════════════════════════════════

(deftest an-unavailable-substrate-refuses-and-never-spawns
  (let [spawned (atom false)]
    (with-redefs [spe/available? (fn [] false)
                  spe/unavailable-reason (fn [] :no-manager)
                  proc/run-bounded (fn [& _] (reset! spawned true) {:status :exited :exit 0})]
      (let [before (spe/invocation-count)
            r (spe/run "/tmp" {:request/argv ["bb"] :request/cwd "."
                               :request/timeout-ms 1000})]
        (is (= :refused (:status r)))
        (is (= :no-manager (:reason r)))
        (is (false? @spawned) "a refusal spawns NOTHING — no host fallback")
        (is (= before (spe/invocation-count))
            "and claims no invocation index: it attempted no execution")
        (is (nil? (spe/run-envelope r))
            "a refusal is not a run and has no run envelope")))))

(deftest a-poisoned-environment-refuses-until-cleanup-completes
  (let [spawned (atom false)]
    (with-redefs [spe/poisoned? (fn [] true)
                  proc/run-bounded (fn [& _] (reset! spawned true) {:status :exited :exit 0})]
      (let [r (spe/run "/tmp" {:request/argv ["bb"] :request/cwd "."
                               :request/timeout-ms 1000})]
        (is (= :refused (:status r)))
        (is (= :environment-poisoned (:reason r)))
        (is (false? @spawned)
            "no execution may be issued while a timed-out machine is unaccounted for")))))

(deftest the-refusal-envelope-is-catalogued-and-carries-no-host-specifics
  (doseq [reason [:not-linux :no-manager :manager-unmeasured :no-guest-image
                  :guest-image-digest-mismatch :sandbox-unavailable
                  :project-identity :environment-poisoned]]
    (let [e (spe/refusal-envelope reason)
          {:keys [refusal/category refusal/reason]} (:environment/refusal e)]
      (is (= :spi.environment/availability (:spi/kind e)))
      (is (false? (:environment/available? e)))
      (is (nil? (:environment/coordinate e))
          "a refused environment carries no coordinate, so a reader never chooses")
      (is (= "spi.refusal" (namespace category)))
      (is (string? reason))
      (is (not (str/includes? reason "/"))
          "reasons are noun phrases, never host paths")))
  (testing "an uncatalogued reason refuses as unknown rather than being guessed"
    (is (= :spi.refusal/unknown
           (get-in (spe/refusal-envelope :something-nobody-catalogued)
                   [:environment/refusal :refusal/category])))))

;; ═══════════════════════════════════════════════════════════════════════════
;; E. Isolation — the real thing, in a real machine.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest the-private-workspace-is-writable-and-the-real-project-is-not
  (if @substrate?
    (with-project [root]
      (let [before (tree-coordinate root)
            r (run! root ["/bin/sh" "-c"
                          (str "echo CORRUPTED >> deps.edn"
                               " && echo NEW > brand-new.txt"
                               " && rm -f src/pe/core.clj"
                               " && chmod 777 sub/dir/marker.txt"
                               " && mv sub/dir/marker.txt sub/dir/moved.txt"
                               " && ls brand-new.txt sub/dir/moved.txt"
                               " && grep -c CORRUPTED deps.edn")])
            after (tree-coordinate root)]
        (testing "every write SUCCEEDS — a workspace a compiler cannot write is useless"
          (is (= :completed (:status r)))
          (is (= 0 (:exit r)) (str "stderr: " (get-in r [:stderr :text])))
          (is (str/includes? (get-in r [:stdout :text]) "brand-new.txt"))
          (is (str/includes? (get-in r [:stdout :text]) "moved.txt")))
        (testing "and NONE of it reaches the authoritative tree"
          (is (= before after) "the input coordinate did not move")
          (is (= "{:paths [\"src\"]}\n" (slurp (str root "/deps.edn"))))
          (is (fs/exists? (str root "/src/pe/core.clj")))
          (is (fs/exists? (str root "/sub/dir/marker.txt")))
          (is (not (fs/exists? (str root "/brand-new.txt")))))))
    (is (= :refused (:status (spe/run "/tmp" {:request/argv ["bb"]
                                              :request/cwd "."
                                              :request/timeout-ms 1000})))
        "without a substrate the run refuses; it never spawns on the host")))

(deftest the-workload-reaches-no-host-secret-path-or-network
  (when @substrate?
    (with-project [root]
      (let [r (run! root ["/bin/sh" "-c"
                          (str "echo '--env--'; env | sort;"
                               " echo '--host--';"
                               " cat /etc/shadow 2>&1 | head -1;"
                               " ls /home 2>&1 | head -1;"
                               " echo '--net--';"
                               " wget -T 2 -O- http://example.com 2>&1 | head -1;"
                               " echo '--input--';"
                               " ls /input | head -3;"
                               " echo '--id--'; id")])
            out (get-in r [:stdout :text])]
        (is (= :completed (:status r)))
        (testing "the guest environment is CONSTRUCTED, so no host credential is in it"
          (doseq [k (keys (into {} (System/getenv)))
                  :when (not (contains? #{"HOME" "PATH" "LANG" "TMPDIR" "PWD"
                                          "SHLVL" "OLDPWD"} k))]
            (is (not (str/includes? out (str "\n" k "=")))
                (str "host variable " k " reached the workload"))))
        (testing "host paths, the network and the raw export are all unreachable"
          (is (or (str/includes? out "Permission denied")
                  (str/includes? out "can't open"))
              "the host's /etc/shadow is not readable")
          (is (not (str/includes? out "chuck"))
              "no host home directory is visible")
          (is (or (str/includes? out "bad address")
                  (str/includes? out "not found")
                  (str/includes? out "unreachable")
                  (str/includes? out "download timed out"))
              "there is no route off the machine")
          (is (not (str/includes? out "deps.edn"))
              "/input is masked after the overlay is mounted"))
        (testing "and it is not root"
          (is (str/includes? out "uid=") )
          (is (not (str/includes? out "uid=0("))))))))

(deftest nothing-the-workload-leaves-behind-survives-the-machine
  (when @substrate?
    (with-project [root]
      (let [marker (str "js2-daemon-" (System/currentTimeMillis))
            r (run! root ["/bin/sh" "-c"
                          (str "(setsid sleep 600 " marker " >/dev/null 2>&1 &);"
                               " sleep 1; echo spawned")])]
        (is (= :completed (:status r)))
        (is (str/includes? (get-in r [:stdout :text]) "spawned"))
        (is (= :terminated (:disposition r)))
        ;; The bracket splits the literal so the SEARCHING shell's own command
        ;; line does not match the pattern it is searching for — otherwise
        ;; every such check finds itself and reports a leak that is the check.
        (let [survivors (proc/run {:timeout-ms 10000} "/bin/sh" "-c"
                                  (str "pgrep -f '" (subs marker 0 1) "["
                                       (subs marker 1 2) "]" (subs marker 2)
                                       "' | head -3; echo end"))]
          (is (= "end" (str/trim (str (:out survivors))))
              "the machine is ephemeral: its processes die with it"))))))

(deftest a-completed-run-names-exactly-what-ran-it-and-what-it-ran-against
  (when @substrate?
    (with-project [root]
      (let [expected-input (tree-coordinate root)
            before (spe/invocation-count)
            r (run! root ["/bin/sh" "-c" "echo out; echo err >&2"] {:cwd "sub/dir"})]
        (is (= :completed (:status r)))
        (is (= 0 (:exit r)))
        (is (= "out\n" (get-in r [:stdout :text])))
        (is (str/includes? (get-in r [:stderr :text]) "err"))
        (is (= 4 (:bytes (:stdout r)))
            "the TRUE byte count of what the workload wrote, newline included")
        (is (false? (:truncated? (:stdout r))))
        (testing "the coordinates are exact"
          (is (= expected-input (:input r))
              "the input coordinate names the authoritative bytes that were staged")
          (is (= (spe/environment-coordinate) (:environment r)))
          (is (= (inc before) (:invocation r))
              "the index is claimed once, immediately before the spawn"))
        (testing "and the run envelope is the SPI's shape over the same facts"
          (let [e (spe/run-envelope r)]
            (is (= :spi.execution/run (:spi/kind e)))
            (is (= :completed (:output/status e)))
            (is (= 0 (:output/exit e)))
            (is (= {:input/coordinate expected-input} (:run/input e)))
            (is (= :samizdat/smolvm-project-env
                   (get-in e [:run/attribution :environment/type])))
            (is (= :terminated (:run/disposition e)))))
        (testing "a relative cwd lands inside the workspace"
          (let [p (run! root ["/bin/sh" "-c" "pwd; ls"] {:cwd "sub/dir"})]
            (is (str/includes? (get-in p [:stdout :text]) "/work/sub/dir"))
            (is (str/includes? (get-in p [:stdout :text]) "marker.txt"))))))))

(deftest a-nonzero-exit-is-a-completed-run-not-a-failure
  ;; The distinction the model has to be able to make: a test suite that
  ;; failed RAN, and a host that could not run it did not.
  (when @substrate?
    (with-project [root]
      (let [r (run! root ["/bin/sh" "-c" "echo nope >&2; exit 3"])]
        (is (= :completed (:status r)))
        (is (= 3 (:exit r)))
        (is (nil? (:error r)))))))

(deftest a-timeout-reports-no-exit-poisons-and-hard-cleans
  ;; JS2 §9.1 and §23. A host deadline bounds host WAITING; it does not prove
  ;; the child inside the machine died. So the machine is what has to end.
  (when @substrate?
    (with-project [root]
      (let [r (run! root ["/bin/sh" "-c" "sleep 600"] {:timeout-ms 12000})]
        (is (= :timeout (:status r)))
        (is (not (contains? r :exit))
            "a deadline is not a program that chose a number")
        (is (= :hard-cleaned (:disposition r)))
        (is (true? (get-in r [:cleanup :clean?]))
            "the manager was ASKED, and reported no surviving machine")
        (is (false? (spe/poisoned?))
            "the poison lifts only after a clean sweep — and this one was clean")
        (testing "the envelope reports the timeout and invents no exit"
          (let [e (spe/run-envelope r)]
            (is (= :timeout (:output/status e)))
            (is (not (contains? e :output/exit)))))
        (testing "and the NEXT execution gets a fresh environment"
          (let [n (run! root ["/bin/sh" "-c" "echo fresh"])]
            (is (= :completed (:status n)))
            (is (= "fresh\n" (get-in n [:stdout :text])))
            (is (= (inc (:invocation r)) (:invocation n)))))))))

(deftest a-project-edit-changes-what-the-next-run-is-handed
  ;; §16 J. The staged input is the authoritative tree AT THE MOMENT OF THE
  ;; RUN, so an edit is visible to the next run and a run's private writes are
  ;; visible to nothing.
  (when @substrate?
    (with-project [root]
      (let [before (run! root ["cat" "src/pe/core.clj"])]
        (is (str/includes? (get-in before [:stdout :text]) "(defn two [] 2)"))
        ;; The authoritative mutation — the only kind there is.
        (spit (str root "/src/pe/core.clj") "(ns pe.core)\n(defn two [] 22)\n")
        (let [after (run! root ["cat" "src/pe/core.clj"])]
          (is (str/includes? (get-in after [:stdout :text]) "(defn two [] 22)")
              "the next run stages the new authoritative bytes")
          (is (not= (:input before) (:input after))
              "and says so in its input coordinate"))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; §3B — the ClosureCoverageSignature.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private host-dialect
  (str "Testing samizdat.util-test\n\n"
       "Ran 1585 tests. 6254 assertions passed, 0 failures, 0 errors.\n"
       "{:type :summary, :test 1585, :pass 6254, :fail 0, :error 0}\n"))

(def ^:private guest-dialect
  ;; What babashka prints — clojure.test's stock two-line form, and a summary
  ;; map whose keys are in a DIFFERENT ORDER. The closure verifier runs in the
  ;; guest, so this is the dialect that actually matters.
  (str "Testing samizdat.util-test\n\n"
       "Ran 1585 tests containing 6254 assertions.\n"
       "0 failures, 0 errors.\n"
       "{:test 1585, :pass 6254, :fail 0, :error 0, :type :summary}\n"))

(deftest the-summary-is-read-in-both-toolchain-dialects
  (is (= [1585 6254 0 0] (coverage/parse-summary host-dialect)))
  (is (= [1585 6254 0 0] (coverage/parse-summary guest-dialect)))
  (testing "the LAST summary wins — a per-namespace one is not the run's"
    (is (= [1585 6254 0 0]
           (coverage/parse-summary
            (str "{:type :summary, :test 1, :pass 4, :fail 0, :error 0}\n"
                 host-dialect)))))
  (testing "two summaries that disagree are not one summary"
    (is (nil? (coverage/parse-summary
               (str "Ran 1585 tests. 6254 assertions passed, 0 failures, 0 errors.\n"
                    "{:type :summary, :test 9, :pass 9, :fail 0, :error 0}\n")))))
  (testing "no summary at all is nil, not a guess"
    (is (nil? (coverage/parse-summary "")))
    (is (nil? (coverage/parse-summary "everything is fine")))))

(deftest the-signature-records-the-counts-beside-their-coordinates
  (let [sig (coverage/signature
             {:green? true :exit 0 :output host-dialect
              :stdout {:truncated? false}}
             {:suite "js1-smve/v1:aaa" :verifier "sha256:bbb"
              :input "sha256:ccc"})]
    (is (= :parsed (:coverage/kind sig)))
    (is (= 1585 (:coverage/tests sig)))
    (is (= 6254 (:coverage/assertions sig)))
    (is (= 0 (:coverage/failures sig)))
    (is (= 0 (:coverage/errors sig)))
    (is (= "js1-smve/v1:aaa" (:coverage/suite sig)))
    (is (= "sha256:bbb" (:coverage/verifier sig)))
    (is (= "sha256:ccc" (:coverage/input sig)))
    (is (nil? (coverage/refusal sig)))
    (is (coverage/admissible? sig))))

(deftest a-closure-result-that-stopped-being-evidence-fails-closed
  (let [sig-of (fn [result]
                 (coverage/signature result {:suite "s" :verifier "v" :input "i"}))]
    (testing "an unreadable summary beside a green exit is a claim nobody checked"
      (is (= :closure-summary-unparseable
             (coverage/refusal (sig-of {:green? true :exit 0 :output ""})))))
    (testing "a suite that ran nothing exits zero, which is what green reads as"
      (is (= :closure-zero-tests
             (coverage/refusal
              (sig-of {:green? true :exit 0
                       :output "{:type :summary, :test 0, :pass 0, :fail 0, :error 0}"})))))
    (testing "a green verdict beside its own counted failures is a broken invariant"
      (is (= :closure-summary-contradicts-verdict
             (coverage/refusal
              (sig-of {:green? true :exit 0
                       :output "{:type :summary, :test 5, :pass 4, :fail 1, :error 0}"})))))
    (testing "an ordinary RED closure is admissible — it is evidence, just not good news"
      (is (nil? (coverage/refusal
                 (sig-of {:green? false :exit 1
                          :output "{:type :summary, :test 5, :pass 4, :fail 1, :error 0}"})))))))

(deftest a-coverage-decrease-is-exposed-and-never-a-refusal
  (let [sig (fn [tests passes suite]
              {:coverage/kind :parsed :coverage/tests tests
               :coverage/assertions passes :coverage/failures 0
               :coverage/errors 0 :coverage/suite suite :coverage/green? true})
        base (sig 1585 6254 "js1-smve/v1:aaa")]
    (testing "growth"
      (let [d (coverage/delta base (sig 1589 6270 "js1-smve/v1:aaa"))]
        (is (= 4 (:delta/tests d)))
        (is (= 16 (:delta/assertions d)))
        (is (false? (:delta/decreased? d)))
        (is (empty? (coverage/warnings d)))))
    (testing "a decrease WARNS — deleting a test is legitimate and this cannot tell"
      (let [d (coverage/delta base (sig 1580 6200 "js1-smve/v1:aaa"))]
        (is (true? (:delta/decreased? d)))
        (is (= [:closure-coverage-decreased]
               (mapv :warning (coverage/warnings d))))
        (is (nil? (coverage/refusal (sig 1580 6200 "js1-smve/v1:aaa")))
            "and it is emphatically not a refusal")))
    (testing "a comparison across two different suites says so"
      (let [d (coverage/delta base (sig 1585 6373 "host/v1:zzz"))]
        (is (false? (:delta/same-suite? d)))
        (is (contains? (set (map :warning (coverage/warnings d)))
                       :closure-coverage-suite-changed))))
    (testing "no delta without two parsed signatures"
      (is (nil? (coverage/delta base {:coverage/kind :unparseable}))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; §3A — conservative repeated-observation invalidation.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- obs [op args result] {:op op :args args :result result :phase :done})

(deftest repeated-unchanged-observation-still-fires
  (let [rs (repeat 3 (obs :project/read ["src/a.clj"] {:value "x"}))]
    (is (= [{:op :project/read :path "src/a.clj" :count 3}]
           (mapv #(select-keys % [:op :path :count])
                 (observation/repeated-unchanged rs 3))))
    (is (empty? (observation/repeated-unchanged rs 4)))
    (testing "a DIFFERENT result is a new observation, not a repeat"
      (is (empty? (observation/repeated-unchanged
                   [(obs :project/read ["src/a.clj"] {:value "x"})
                    (obs :project/read ["src/a.clj"] {:value "y"})
                    (obs :project/read ["src/a.clj"] {:value "x"})]
                   2))))))

(deftest any-mutation-clears-the-whole-repeat-state
  ;; The conservative rule (JS2 §3A). A write beneath a listed or searched
  ;; directory invalidates that list or search even though the exact paths
  ;; differ, and no receipt stream can say which — so everything resets.
  (let [reads (repeat 3 (obs :project/read ["src/a.clj"] {:value "x"}))
        lists (repeat 3 (obs :project/list ["src"] {:value []}))
        edit-elsewhere (obs :project/edit ["docs/unrelated.md" "sha256:0" "c"]
                            {:value {}})]
    (is (seq (observation/repeated-unchanged (concat reads lists) 3))
        "the signal fires without an intervening mutation")
    (is (empty? (observation/repeated-unchanged
                 (concat reads lists [edit-elsewhere]) 3))
        "a mutation ANYWHERE clears every accumulated signature")
    (testing "and the same read afterwards begins a fresh count"
      (is (empty? (observation/repeated-unchanged
                   (concat reads [edit-elsewhere]
                           [(obs :project/read ["src/a.clj"] {:value "x"})])
                   2)))
      (is (= [3] (mapv :count
                       (observation/repeated-unchanged
                        (concat reads [edit-elsewhere] reads) 3)))
          "the fresh count reaches the threshold on its own evidence"))
    (testing "a REFUSED mutation changed nothing, so it clears nothing"
      (is (seq (observation/repeated-unchanged
                (concat reads
                        [{:op :project/edit :args ["x" "sha256:0" "c"]
                          :error "stale" :phase :error}])
                3))))))

(deftest a-search-pattern-is-never-mistaken-for-a-filesystem-coordinate
  (let [searches (repeat 3 (obs :project/search ["defn\\s+truncate"] {:value []}))
        found (observation/repeated-unchanged searches 3)]
    (is (= 1 (count found)))
    (is (= :project/search (:op (first found))))
    (is (nil? (:path (first found)))
        "a regex is not a path, and is not reported as one")
    (testing "the finding it renders names the operation without inventing a file"
      (let [f (observation/finding searches {:threshold 3
                                             :detail "repeated {{coordinate}}"})]
        (is (= "repeated search" (:detail f))
            "the finding names the operation and no file, because there is none")
        (is (= [{:op :project/search :path nil :count 3}]
               (get-in f [:evidence :coordinates])))))
    (testing "a search with a path option still reports no coordinate"
      (let [with-path (repeat 3 (obs :project/search ["x" {:path "src"}] {:value []}))]
        (is (nil? (:path (first (observation/repeated-unchanged with-path 3)))))))))
