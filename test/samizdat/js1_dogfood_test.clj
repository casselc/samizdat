;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.js1-dogfood-test
  "Bounded, explicitly opted-in, live JS1 dogfood over a LOCAL model only.

  Ordinary test runs execute no process and no provider call. A live run needs
  all three exact operator choices:

    SAMIZDAT_JS1_DOGFOOD_TEST=1
    HARNESS_PROVIDER=local
    HARNESS_BASE_URL=http://localhost:13305/v1
    HARNESS_MODEL=<operator-selected model>

  The parent creates a disposable Git repository and a detached target
  worktree under /tmp, outside this trusted checkout. It drives the real
  workflow/run! in one pinned-JS1 process, kills that process only after a
  durable red ship-verify event, and drives agent.resume/resume! in a fresh
  pinned-JS1 process. No live REPL fallback exists in this harness.

  Artifacts are retained in a printed /tmp directory: sanitized transcript,
  run id, SQLite DB, process logs, semantic-operation dispatch counters,
  journal, artifact rows, and target diff. No environment dump is captured."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.process :as process]
            [samizdat.engine.proc :as proc]
            [samizdat.store.db :as db]
            [samizdat.store.evals :as evals]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private source-dir
  (or (jolt.host/getenv "SAMIZDAT_JS1_DOGFOOD_SOURCE_DIR")
      (jolt.host/getenv "JOLT_PWD")
      (System/getProperty "user.dir")))

(def ^:private fixture-dir (str source-dir "/test/fixtures/js1-dogfood"))
(def ^:private contract (edn/read-string (slurp (str fixture-dir "/contract.edn"))))
(def ^:private max-turns (get-in contract [:bounds :max-turns]))
(def ^:private phase-timeout-ms (get-in contract [:bounds :phase-timeout-ms]))
(def ^:private total-timeout-ms (get-in contract [:bounds :total-timeout-ms]))

(defn- env [name] (jolt.host/getenv name))
(defn- internal-env [name] (env (str "SAMIZDAT_JS1_DOGFOOD_" name)))

(defn- live-requested? []
  (= "1" (env "SAMIZDAT_JS1_DOGFOOD_TEST")))

(defn- preflight-requested? []
  (= "1" (env "SAMIZDAT_JS1_DOGFOOD_PREFLIGHT")))

(defn- require-live-contract!
  "Fail before runtime discovery or process creation when opt-in is malformed."
  []
  (doseq [[name expected] [["HARNESS_PROVIDER" "local"]
                           ["HARNESS_BASE_URL" "http://localhost:13305/v1"]]]
    (when-not (= expected (env name))
      (throw (ex-info
              (str "JS1 dogfood refused: " name " must be exactly " expected
                   " when SAMIZDAT_JS1_DOGFOOD_TEST=1")
              {:js1-dogfood/error :operator-contract :name name}))))
  (when (str/blank? (env "HARNESS_MODEL"))
    (throw (ex-info
            "JS1 dogfood refused: HARNESS_MODEL must be explicitly set by the operator"
            {:js1-dogfood/error :operator-contract :name "HARNESS_MODEL"})))
  true)

(defn- require-preflight-contract!
  "The no-provider preflight still pins the local surface, but needs no real
  model selection because it never calls the adapter."
  []
  (doseq [[name expected] [["HARNESS_PROVIDER" "local"]
                           ["HARNESS_BASE_URL" "http://localhost:13305/v1"]]]
    (when-not (= expected (env name))
      (throw (ex-info
              (str "JS1 dogfood preflight refused: " name " must be exactly " expected)
              {:js1-dogfood/error :preflight-contract :name name}))))
  true)

(defn- safe-controller-env
  "The complete environment for runtime/setup children. It intentionally has
  no provider key and is never persisted as an environment dump."
  []
  (into {}
        (keep (fn [name] (when-let [v (env name)] [name v])))
        ["PATH" "HOME" "LANG" "LC_ALL" "LC_CTYPE" "TERM" "TMPDIR"
         "JOLT_HOME" "JOLT_CHEZ" "JOLT_QUIET"]))

(defn- run-command
  [opts argv]
  (apply proc/run opts argv))

(defn- command!
  [label opts argv]
  (let [{:keys [exit timeout out err] :as result} (run-command opts argv)]
    (when (or timeout (not= 0 exit))
      (throw (ex-info (str label " failed"
                           (when timeout " (timed out)")
                           "\nstdout: " out "\nstderr: " err)
                      {:js1-dogfood/error :command-failed
                       :label label :argv argv :result result})))
    result))

(defn- git!
  [dir & args]
  (command! (str "git " (str/join " " args))
            {:timeout-ms 30000 :env (safe-controller-env)}
            (into ["git" "-C" dir] args)))

(defn- copy-fixtures!
  [control]
  (doseq [rel (:fixture-files contract)]
    (let [dst (str control "/" rel)]
      (fs/create-dirs (fs/parent dst))
      (spit dst (slurp (str fixture-dir "/" rel))))))

(defn- make-detached-target!
  [root]
  (let [control (str root "/fixture-control")
        target (str root "/detached-target")]
    (fs/create-dirs control)
    (git! control "init" "-q" ".")
    (copy-fixtures! control)
    (git! control "add" "--" ".")
    ;; The source fixtures need not carry executable mode in the trusted
    ;; checkout. The disposable fixture repository owns executable policy.
    (git! control "add" "--chmod=+x" "--" "verify-policy" "test/focused_test.sh")
    (git! control "-c" "user.name=JS1 Dogfood"
          "-c" "user.email=js1-dogfood@invalid"
          "commit" "-q" "-m" "fixture seed")
    (git! control "worktree" "add" "--detach" target "HEAD")
    (when (zero? (:exit (run-command {:timeout-ms 5000 :env (safe-controller-env)}
                                     ["git" "-C" target "symbolic-ref" "-q" "HEAD"])))
      (throw (ex-info "JS1 dogfood target unexpectedly has an attached branch"
                      {:js1-dogfood/error :not-detached :target target})))
    (let [target* (str (fs/canonicalize target))
          source* (str (fs/canonicalize source-dir))]
      (when (or (= target* source*)
                (str/starts-with? target* (str source* "/")))
      (throw (ex-info "JS1 dogfood target must be outside the trusted source checkout"
                        {:js1-dogfood/error :unsafe-target :target target*}))))
    {:control control :target (str (fs/canonicalize target))}))

(defn- jolt-home []
  (let [explicit (env "JOLT_HOME")]
    (if (str/blank? explicit) (str source-dir "/../jolt") explicit)))

(defn- distinct-classpath [paths]
  (->> paths
       (mapcat #(str/split (str/trim %) #":"))
       (remove str/blank?)
       distinct
       (str/join ":")))

(defn- live-runtime!
  "Use bin/js1 as the pin-checking/SCI authority, then ask the pinned Jolt
  resolver for ONE launch plan that retains the project's normal dependencies
  and native descriptors while adding the validated SCI local root. A bare
  -Scp union is not sufficient: it names db source but bypasses dependency
  processing that loads sqlite3_open."
  []
  (let [wrapper (str source-dir "/bin/js1")
        jolt (str (jolt-home) "/bin/jolt")
        env (assoc (safe-controller-env) "JOLT_PWD" source-dir)
        js1 (:out (command! "bin/js1 path" {:timeout-ms 180000 :env env}
                            [wrapper "path"]))
        production (:out (command! "pinned jolt production path"
                                   {:timeout-ms 180000 :env env}
                                   [jolt "-Srepro" "-Spath"]))
        sci-root (str (jolt-home) "/vendor/sci")
        resolver-sdeps
        (pr-str {:aliases
                 {:js1-dogfood-live
                  {:extra-paths ["test"]
                   :extra-deps
                   {(symbol "borkdude/sci") {:local/root sci-root}}}}})
        combined (:out
                  (command! "normal Samizdat plus pinned JS1 path"
                            {:timeout-ms 180000 :env env}
                            [jolt "-Srepro" "-Sdeps" resolver-sdeps
                             "-A:js1-dogfood-live" "-Spath"]))
        cp (distinct-classpath [combined])]
    (when (or (str/blank? cp) (not (fs/exists? jolt))
              (not (str/includes? cp "/vendor/sci/src"))
              (not (str/includes? cp "/samizdat/src")))
      (throw (ex-info
              "live JS1 dogfood unavailable: normal dependencies and bin/js1 SCI roots did not compose; refusing live REPL fallback"
              {:js1-dogfood/error :runtime-unavailable
               :has-sci? (str/includes? cp "/vendor/sci/src")
               :has-samizdat? (str/includes? cp "/samizdat/src")})))
    {:jolt jolt
     ;; B0: neither component is sufficient by itself. Production contributes
     ;; data.json, jdbc/db, HTTP, logging, Mycelium and resources; bin/js1
     ;; contributes the pin-checked SCI roots and its Maven implementation jars.
     :production-classpath (str/trim production)
     :js1-classpath (str/trim js1)
     :classpath cp
     :resolver-sdeps resolver-sdeps
     :resolver-alias "-A:js1-dogfood-live"}))

(defn- child-env
  [{:keys [phase target db-path counter-path run-id]}]
  (cond-> (assoc (safe-controller-env)
                 "HARNESS_PROVIDER" "local"
                 "HARNESS_BASE_URL" "http://localhost:13305/v1"
                 "HARNESS_MODEL" (or (env "HARNESS_MODEL")
                                      "js1-preflight-no-provider")
                 ;; B1: workflow manifests/cells/resources resolve from the
                 ;; trusted checkout. The absolute :run :root below remains the
                 ;; disposable target, which is the only root JS1 receives.
                 "JOLT_PWD" (str (fs/canonicalize source-dir))
                 "SAMIZDAT_JS1_DOGFOOD_SOURCE_DIR" source-dir
                 "SAMIZDAT_JS1_DOGFOOD_PHASE" phase
                 "SAMIZDAT_JS1_DOGFOOD_TARGET" target
                 "SAMIZDAT_JS1_DOGFOOD_DB" db-path
                 "SAMIZDAT_JS1_DOGFOOD_COUNTERS" counter-path)
    (= phase "preflight")
    (assoc "SAMIZDAT_JS1_DOGFOOD_PREFLIGHT" "1")
    (not= phase "preflight")
    (assoc "SAMIZDAT_JS1_DOGFOOD_TEST" "1")
    run-id (assoc "SAMIZDAT_JS1_DOGFOOD_RUN_ID" run-id)))

(defn- child-command [{:keys [jolt resolver-sdeps resolver-alias]}]
  [jolt "-Srepro" "-Sdeps" resolver-sdeps resolver-alias "run"
   (str source-dir "/test/samizdat/js1_dogfood_test.clj")])

(defn- run-child!
  [runtime phase paths]
  (let [result (run-command {:timeout-ms (or (:timeout-ms paths)
                                             phase-timeout-ms)
                             :env (child-env (assoc paths :phase phase))}
                            (child-command runtime))]
    (when (or (:timeout result) (not= 0 (:exit result)))
      (throw (ex-info
              (str "live JS1 dogfood " phase " process failed. "
                   "Current workflow APIs did not complete the requested JS1 phase; "
                   "refusing any live REPL fallback.\nstdout: " (:out result)
                   "\nstderr: " (:err result))
              {:js1-dogfood/error :phase-failed :phase phase :result result})))
    result))

(defn- spawn-start!
  [runtime paths]
  (let [log-dir (fs/parent (:counter-path paths))
        out-path (str log-dir "/start-live.stdout")
        err-path (str log-dir "/start-live.stderr")]
    ;; File redirection prevents an escaped descendant holding a capture pipe
    ;; open from making result collection unbounded after the root exits.
    (assoc (process/process (child-command runtime)
                            {:dir source-dir
                             :env (child-env (assoc paths :phase "start"))
                             :out out-path :err err-path})
           :dogfood/out-path out-path
           :dogfood/err-path err-path)))

(defn- reap-child!
  "Own the child to a bounded terminal state. TERM-tree gets a short grace,
  then the root is forcibly killed and re-waited. Output was redirected to
  files, so collection performs no process deref and cannot wait on a pipe."
  [child]
  (let [p (:proc child)
        started (System/currentTimeMillis)]
    (try (process/destroy-tree child) (catch Throwable _ nil))
    (let [term-exited? (try (.waitFor p 2000 TimeUnit/MILLISECONDS)
                            (catch Throwable _ false))
          _ (when-not term-exited? (try (.destroyForcibly p)
                                        (catch Throwable _ nil)))
          exited? (or term-exited?
                      (try (.waitFor p 3000 TimeUnit/MILLISECONDS)
                           (catch Throwable _ false)))
          elapsed (- (System/currentTimeMillis) started)]
      (if-not exited?
        {:exit nil :out "" :err "child did not terminate after TERM/KILL"
         :reap-timeout true :reap-ms elapsed}
        {:exit (try (.exitValue p) (catch Throwable _ nil))
         :out (if (fs/exists? (:dogfood/out-path child))
                (slurp (:dogfood/out-path child)) "")
         :err (if (fs/exists? (:dogfood/err-path child))
                (slurp (:dogfood/err-path child)) "")
         :reap-timeout false :reap-ms elapsed}))))

(defn- require-reaped!
  [result]
  (when (:reap-timeout result)
    (throw (ex-info
            (str "JS1 dogfood could not boundedly reap/collect the start child: "
                 (:err result))
            {:js1-dogfood/error :child-reap-failed :result result})))
  result)

(defn- signal-child!
  "Synchronously deliver SIGNAL to the owned root process. SIGSTOP is the
  quiescence fence: after kill(2) returns, that process cannot begin another
  model eval while the parent validates durable history."
  [child signal]
  (let [pid (.pid (:proc child))]
    (command! (str "signal child " signal)
              {:timeout-ms 5000 :env (safe-controller-env)}
              ["kill" (str "-" signal) (str pid)])))

(defn- parsed-event-data [event]
  (try (json/read-str (:data event) :key-fn keyword)
       (catch Throwable _ {})))

(defn- red-ship-event [conn run-id]
  (some (fn [event]
          (let [data (parsed-event-data event)]
            (when (and (= "ship-verify" (:kind event))
                       (= false (:green data))
                       (= true (:blocked data))
                       (= 1 (:exit data))
                       (str/ends-with? (str (:kind data)) "fallback"))
              (assoc event :parsed-data data))))
        (journal/events-since conn run-id 0 1000)))

(defn- eval-quiescence
  "The durable recovery-safety witness. Any pending eval is unsafe even when
  pure; unsettled receipts additionally identify effects whose world state is
  unknown. Called only while the producer process is SIGSTOPped."
  [conn]
  (let [pending (evals/pending conn 100)
        unsettled (mapv (fn [row]
                          {:eval-id (:id row)
                           :binding-seq (:binding_seq row)
                           :effects (evals/unsettled-effects conn (:id row))})
                        pending)]
    {:quiescent? (and (empty? pending)
                      (every? (comp empty? :effects) unsettled))
     :pending-eval-ids (mapv :id pending)
     :unsettled unsettled}))

(defn- wait-for-red-checkpoint!
  [db-path target child timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        held-conn (atom nil)]
    (try
      (loop []
        (when (>= (System/currentTimeMillis) deadline)
          (throw (ex-info
                  "JS1 dogfood timed out before a durable quiescent RED ship-verify checkpoint"
                  {:js1-dogfood/error :red-checkpoint-timeout})))
        (when (and (nil? @held-conn) (fs/exists? db-path))
          (try (reset! held-conn (db/connect db-path))
               (catch Throwable _ nil)))
        (let [conn @held-conn
              run-id (when conn
                       (try (:id (db/fetch-one conn
                                               ["SELECT id FROM runs ORDER BY started_at DESC LIMIT 1"]))
                            (catch Throwable _ nil)))
              event (when run-id (try (red-ship-event conn run-id)
                                      (catch Throwable _ nil)))]
          (cond
            event
            (do
              (when-not (and (fs/exists? (str target "/red-evidence.txt"))
                             (str/includes? (slurp (str target "/red-evidence.txt"))
                                            "JS1-DOGFOOD-FOCUSED-RED"))
                (throw (ex-info "red ship event landed without fixture red evidence"
                                {:js1-dogfood/error :missing-red-evidence})))
              ;; N1 linearization: freeze the sole producer after the durable
              ;; red event, then inspect the durable eval log while no new eval
              ;; can begin. Only this state earns the checkpoint label.
              (signal-child! child "STOP")
              (let [quiescence (try (eval-quiescence conn)
                                    (catch Throwable e
                                      (throw (ex-info
                                              (str "could not establish RED checkpoint quiescence: "
                                                   (ex-message e))
                                              {:js1-dogfood/error
                                               :quiescence-check-failed}
                                              e))))]
                (when-not (:quiescent? quiescence)
                  (throw (ex-info
                          (str "unsafe RED checkpoint: pending evals or unsettled effects; "
                               "refusing resume")
                          {:js1-dogfood/error :unsafe-red-history
                           :run-id run-id :quiescence quiescence})))
                (println "JS1-DOGFOOD-RED-QUIESCENT" run-id)
                {:run-id run-id :event event :quiescence quiescence
                 :producer-stopped true}))

            (not (.isAlive (:proc child)))
            (throw (ex-info
                    "JS1 dogfood start process exited before the RED checkpoint"
                    {:js1-dogfood/error :early-exit}))

            :else
            (do (Thread/sleep 25) (recur)))))
      (finally
        (when-let [conn @held-conn] (db/close conn))))))

(defn- read-edn-file [path default]
  (try (if (fs/exists? path) (edn/read-string (slurp path)) default)
       (catch Throwable _ default)))

(defn- phase-events [counter-path phase]
  (->> (:events (read-edn-file counter-path {:events [] :counts {}}))
       (filter #(= phase (:phase %)))
       vec))

(defn- event-matches-contract?
  [event requirement]
  (let [args (:args event)]
    (and (= (:op requirement) (:op event))
         (or (not (contains? requirement :args))
             (= (:args requirement) args))
         (or (not (contains? requirement :path))
             (= (:path requirement) (nth args 0 nil)))
         (or (not (contains? requirement :content))
             (= (:content requirement) (nth args 2 nil)))
         (or (not= :sha256 (:base requirement))
             (boolean (re-matches #"sha256:[0-9a-f]{64}"
                                  (str (nth args 1 nil))))))))

(defn- required-subsequence?
  [events requirements]
  (loop [events (seq events), requirements (seq requirements)]
    (cond
      (nil? requirements) true
      (nil? events) false
      (event-matches-contract? (first events) (first requirements))
      (recur (next events) (next requirements))
      :else (recur (next events) requirements))))

(defn- validate-pre-red-events!
  "Order-aware start-phase contract. Extra discovery is allowed, but it must
  be read-only and before the sole anchored RED edit. The required operations
  remain an ordered subsequence, stat/edit counts remain exact, and the edit's
  path, digest-shaped base, and complete content are checked."
  [events]
  (let [{:keys [required-subsequence minimum-counts exact-counts
                additional-before-edit]}
        (get-in contract [:semantic-operations :before-resume])
        counts (frequencies (map :op events))
        edit-indexes (keep-indexed (fn [i event]
                                     (when (= :project/edit (:op event)) i))
                                   events)
        edit-index (first edit-indexes)
        allowed-before (conj additional-before-edit :project/stat)
        errors (cond-> []
                 (not (required-subsequence? events required-subsequence))
                 (conj :required-subsequence-missing)

                 (some (fn [[op n]] (< (get counts op 0) n)) minimum-counts)
                 (conj :minimum-count-missing)

                 (some (fn [[op n]] (not= n (get counts op 0))) exact-counts)
                 (conj :exact-count-violated)

                 (not= 1 (count edit-indexes))
                 (conj :first-edit-not-unique)

                 (and edit-index (not= edit-index (dec (count events))))
                 (conj :semantic-operation-after-edit)

                 (and edit-index
                      (some #(not (contains? allowed-before (:op %)))
                            (subvec events 0 edit-index)))
                 (conj :non-discovery-operation-before-edit))]
    (when (seq errors)
      (throw (ex-info
              "RED checkpoint violates the ordered/minimum semantic-operation contract; refusing resume"
              {:js1-dogfood/error :unexpected-red-operations
               :errors errors
               :counts counts
               :events (mapv #(select-keys % [:phase :op :args]) events)})))
    {:counts counts
     :event-count (count events)
     :extra-read-only-count
     (- (count events) (count required-subsequence))
     :required-subsequence true
     :first-edit-index edit-index}))

(defn- validate-resume-events!
  "Exact resume-phase contract. Reconstruction itself contributes ZERO
  events; the only permitted post-reconstruction operations are the new red
  evidence read, one fresh stat, and one GREEN anchored edit. Thus historical
  list/search/TASK/source/test observations and the RED edit cannot repeat."
  [events]
  (let [expected (get-in contract [:semantic-operations :resume-exact])]
    (when-not (and (= (count expected) (count events))
                   (every? true? (map event-matches-contract? events expected)))
      (throw (ex-info
              "resume/replay semantic operations were not exact"
              {:js1-dogfood/error :unexpected-resume-operations
               :expected expected
               :actual (mapv #(select-keys % [:phase :op :args]) events)})))
    {:exact true :event-count (count events)}))

(defn- safe-spit! [path value]
  (spit path (if (string? value) value (pr-str value))))

(defn- capture-artifacts!
  [{:keys [artifact-dir target db-path counter-path run-id start-result resume-result checkpoint]}]
  (fs/create-dirs artifact-dir)
  (safe-spit! (str artifact-dir "/run-id.txt") (str run-id "\n"))
  (safe-spit! (str artifact-dir "/semantic-operations.edn")
              (read-edn-file counter-path {:events [] :counts {}}))
  (safe-spit! (str artifact-dir "/phase-results.edn")
              {:checkpoint checkpoint
               :start-exit (:exit start-result)
               :resume-exit (:exit resume-result)})
  (safe-spit! (str artifact-dir "/start.stdout") (:out start-result))
  (safe-spit! (str artifact-dir "/start.stderr") (:err start-result))
  (safe-spit! (str artifact-dir "/resume.stdout") (:out resume-result))
  (safe-spit! (str artifact-dir "/resume.stderr") (:err resume-result))
  (safe-spit! (str artifact-dir "/safe-config.edn")
              {:provider :local :base-url "http://localhost:13305/v1"
               :model (env "HARNESS_MODEL") :api-key nil
               :max-turns max-turns :beam-width 1
               :verify-cmd "./verify-policy"})
  (when (and run-id (fs/exists? db-path))
    (let [conn (db/connect db-path)]
      (try
        (safe-spit! (str artifact-dir "/transcript.edn") (journal/turns conn run-id))
        (safe-spit! (str artifact-dir "/journal.edn")
                    (journal/events-since conn run-id 0 5000))
        (safe-spit! (str artifact-dir "/artifacts.edn")
                    (journal/artifacts conn run-id))
        (safe-spit! (str artifact-dir "/eval-history.edn")
                    (evals/history conn (str "bind:main:" run-id)))
        (finally (db/close conn)))))
  (when (fs/exists? target)
    (let [diff (run-command {:timeout-ms 30000 :env (safe-controller-env)}
                            ["git" "-C" target "diff" "--no-ext-diff" "HEAD"])
          status (run-command {:timeout-ms 30000 :env (safe-controller-env)}
                              ["git" "-C" target "status" "--short"])]
      (safe-spit! (str artifact-dir "/worktree.diff") (:out diff))
      (safe-spit! (str artifact-dir "/worktree.status") (:out status))
      (when (fs/exists? (str target "/src/dogfood.clj"))
        (safe-spit! (str artifact-dir "/final-source.clj")
                    (slurp (str target "/src/dogfood.clj"))))
      (when (fs/exists? (str target "/red-evidence.txt"))
        (safe-spit! (str artifact-dir "/red-evidence.txt")
                    (slurp (str target "/red-evidence.txt"))))))
  artifact-dir)

(defn- source-git-output [label & args]
  (:out (command! label {:timeout-ms 30000 :env (safe-controller-env)}
                  (into ["git" "-C" source-dir] args))))

(defn- source-witness
  "Stronger than status alone: HEAD, staged and unstaged patches, the complete
  untracked inventory, and the bytes of every file this fenced change may
  touch. The byte snapshot closes status's blind spot for an already-untracked
  fixture whose content is modified in place."
  []
  (let [fenced (concat ["test/samizdat/js1_dogfood_test.clj"
                        "test/samizdat/js1_harness_test.clj"
                        "test/samizdat/test_runner.clj"]
                       (map #(str "test/fixtures/js1-dogfood/" %)
                            (:fixture-files contract))
                       ["test/fixtures/js1-dogfood/contract.edn"])]
    {:root (str (fs/canonicalize source-dir))
     :head (str/trim (source-git-output "trusted source HEAD" "rev-parse" "HEAD"))
     :status (source-git-output "trusted source status" "status" "--porcelain=v1"
                                "--untracked-files=all")
     :unstaged (source-git-output "trusted source unstaged diff"
                                  "diff" "--no-ext-diff" "--binary")
     :staged (source-git-output "trusted source staged diff"
                                "diff" "--cached" "--no-ext-diff" "--binary")
     :fenced-bytes (into {}
                         (map (fn [rel] [rel (slurp (str source-dir "/" rel))]))
                         fenced)}))

(defn- require-quiescent-db!
  [db-path run-id stage]
  (let [conn (db/connect db-path)]
    (try
      (let [q (eval-quiescence conn)]
        (when-not (:quiescent? q)
          (throw (ex-info
                  (str "unsafe JS1 history " stage
                       ": pending evals or unsettled effects; refusing resume")
                  {:js1-dogfood/error :unsafe-red-history
                   :stage stage :run-id run-id :quiescence q})))
        q)
      (finally (db/close conn)))))

;; ── child phases ------------------------------------------------------------

(defn- counter-state [path]
  (read-edn-file path {:events [] :counts {}}))

(defn- count-semantic-op!
  [path phase op args]
  (let [state (counter-state path)
        event {:phase phase :op op :args args}
        next (-> state
                 (update :events (fnil conj []) event)
                 (update-in [:counts op] (fnil inc 0)))]
    (spit path (pr-str next))))

(defn- install-counting-eval-store!
  "Instrument the trusted durable-store seam. record-intent! is called by
  run-recorded-effect! immediately before the real semantic operation. Replay
  consumes receipts through :history and never calls record-intent!, so an
  unchanged counter is direct evidence that reconstruction re-actuated no
  observation or edit."
  [counter-path phase]
  (require 'samizdat.agent.sandbox)
  (let [store-var (resolve 'samizdat.agent.sandbox/*eval-store*)]
    (alter-var-root
     store-var
     (constantly
      {:begin! evals/begin!
       :record-intent! (fn [conn id {:keys [op args] :as intent}]
                         (let [seq-n (evals/record-intent! conn id intent)]
                           (count-semantic-op! counter-path phase op args)
                           seq-n))
       :record-outcome! evals/record-outcome!
       :complete! evals/complete!
       :load-eval evals/load-eval
       :verify-binding! evals/verify-binding!
       :history evals/history}))))

(defn- dogfood-config [target db-path]
  (require 'samizdat.config)
  (let [load-config (resolve 'samizdat.config/load-config)]
    (load-config
     {:db {:path db-path}
      :llm {:provider :local
            :base-url "http://localhost:13305/v1"
            :api-key nil
            :model (env "HARNESS_MODEL")
            :max-tokens 4096
            :temperature 0.0
            :timeout-ms 120000
            :conn-timeout-ms 5000
            :max-response-ms 180000}
      :run {:root target
            :max-turns max-turns
            :beam-width 1
            :js1/profile "single-player"
            :verify-focused? false
            :require-test? false
            :verify-cmd "./verify-policy"
            :verify-timeout-ms 30000
             :stop-on-first-done? true}})))

(def ^:private preflight-api-symbols
  "Every dynamically reached API in start, resume, and the reconstruction
  witness. Preflight resolves the closed list before compiling the workflow."
  '[samizdat.config/load-config
    samizdat.workflow/load-loop!
    samizdat.workflow/run!
    samizdat.agent.resume/resume!
    samizdat.agent.resume/reconstruct-js1-binding!
    samizdat.agent.sandbox/provider
    samizdat.agent.sandbox/bind!
    samizdat.agent.sandbox/runtime-coordinate
    samizdat.agent.sandbox/evaluate!
    samizdat.agent.sandbox/evaluate-recorded!
    samizdat.agent.sandbox/*eval-store*
    samizdat.llm.registry/adapter-for
    samizdat.store.db/open!
    samizdat.store.db/close
    samizdat.store.evals/begin!
    samizdat.store.evals/record-intent!
    samizdat.store.evals/record-outcome!
    samizdat.store.evals/complete!
    samizdat.store.evals/load-eval
    samizdat.store.evals/verify-binding!
    samizdat.store.evals/history
    samizdat.store.evals/pending
    samizdat.store.evals/unsettled-effects])

(defn- resolve-preflight-apis! []
  (let [missing (vec (remove resolve preflight-api-symbols))]
    (when (seq missing)
      (throw (ex-info "required dogfood APIs are unresolved"
                      {:missing missing})))
    true))

(defn- phase-preflight! []
  (try
    (require 'samizdat.config)
    (require 'samizdat.workflow)
    (require 'samizdat.agent.resume)
    (require 'samizdat.agent.sandbox)
    (require 'samizdat.llm.registry)
    (require 'samizdat.store.db)
    (resolve-preflight-apis!)
    (when-not (proc/scope-supported?)
      (throw (ex-info "hardened verifier process scope is absent" {})))
    (let [target (str (fs/canonicalize (internal-env "TARGET")))
          db-path (internal-env "DB")
          trusted-source (str (fs/canonicalize source-dir))
          jolt-pwd (str (fs/canonicalize (env "JOLT_PWD")))
          config (dogfood-config target db-path)
          conn (db/open! db-path)]
      (try
        (when-not (= trusted-source jolt-pwd)
          (throw (ex-info "child JOLT_PWD is not the trusted source checkout"
                          {:expected trusted-source :actual jolt-pwd})))
        (when-not (= target (get-in config [:run :root]))
          (throw (ex-info "preflight config did not preserve the absolute target root"
                          {:expected target :actual (get-in config [:run :root])})))
        ;; This is a real resource/cell load and Mycelium compile, not a mere
        ;; namespace require. It catches a child cwd/JOLT_PWD that resolves the
        ;; disposable fixture instead of trusted workflow resources.
        (let [loaded ((resolve 'samizdat.workflow/load-loop!) conn)
              provider ((resolve 'samizdat.agent.sandbox/provider) {:root target})
              binding ((resolve 'samizdat.agent.sandbox/bind!)
                       provider "preflight-no-run"
                       {:preset :project/develop :root target :instance/key :main})
              adapter ((resolve 'samizdat.llm.registry/adapter-for) :local)
              json-roundtrip (json/read-str (json/write-str {:ok true})
                                            :key-fn keyword)]
          (when-not (and (:compiled loaded)
                         (= target (get-in binding [:spec :root]))
                         adapter
                         (= {:ok true} json-roundtrip))
            (throw (ex-info "preflight compile/dependency/root witness failed"
                            {:workflow-loaded? (boolean (:compiled loaded))
                             :binding-root (get-in binding [:spec :root])
                             :target target
                             :adapter-loaded? (boolean adapter)
                             :json json-roundtrip}))))
        (println "JS1-DOGFOOD-PREFLIGHT-OK"
                 "workflow=compiled" "jolt-pwd=source" "sandbox-root=target")
        (finally (db/close conn))))
    (catch Throwable e
      (throw (ex-info
              (str "live JS1 dogfood unavailable: the classpath built from "
                   "bin/js1 plus production dependencies cannot load workflow/run!, "
                   "agent.resume/resume!, SCI, SQLite, the local adapter, and the "
                   "hardened verifier scope together: " (ex-message e)
                   ". Refusing live REPL fallback.")
              {:js1-dogfood/error :workflow-api-unavailable}
              e)))))

(defn- phase-start! []
  (require 'samizdat.workflow)
  (require 'samizdat.llm.registry)
  (let [target (internal-env "TARGET")
        db-path (internal-env "DB")
        counter-path (internal-env "COUNTERS")
        config (dogfood-config target db-path)
        adapter-for (resolve 'samizdat.llm.registry/adapter-for)
        run! (resolve 'samizdat.workflow/run!)
        conn (db/open! db-path)]
    (try
      (install-counting-eval-store! counter-path "start")
      ;; The parent deliberately kills this process at RED; returning here is a
      ;; harness failure (the model either skipped RED or completed too early).
      (let [result (run! {:conn conn :config config
                          :llm-adapter (adapter-for :local)
                          :llm-config (:llm config)
                          :problem (slurp (str target "/TASK.md"))
                          :max-turns max-turns})]
        (throw (ex-info "start phase returned before the intentional RED kill"
                        {:js1-dogfood/error :missed-red-kill :result result})))
      (finally (db/close conn)))))

(defn- js1-event-info [conn run-id]
  (let [event (some #(when (= "js1-binding-created" (:kind %)) %)
                    (journal/events-since conn run-id 0 1000))]
    (when-not event
      (throw (ex-info "resume has no durable JS1 binding event"
                      {:js1-dogfood/error :missing-js1-event})))
    (json/read-str (:data event) :key-fn keyword)))

(defn- phase-resume! []
  (require 'samizdat.agent.resume)
  (require 'samizdat.agent.sandbox)
  (require 'samizdat.llm.registry)
  (let [target (internal-env "TARGET")
        db-path (internal-env "DB")
        counter-path (internal-env "COUNTERS")
        run-id (internal-env "RUN_ID")
        config (dogfood-config target db-path)
        reconstruct (resolve 'samizdat.agent.resume/reconstruct-js1-binding!)
        resume! (resolve 'samizdat.agent.resume/resume!)
        evaluate! (resolve 'samizdat.agent.sandbox/evaluate!)
        adapter-for (resolve 'samizdat.llm.registry/adapter-for)
        conn (db/open! db-path)]
    (try
      (install-counting-eval-store! counter-path "resume")
      (let [before (counter-state counter-path)
            info (js1-event-info conn run-id)
            {:keys [binding]} (reconstruct conn info target)
            helper-value (evaluate! binding "(dogfood-helper \"controller-resume-check\")")
            after (counter-state counter-path)]
        (when-not (= "DOGFOOD-HELPER:controller-resume-check" helper-value)
          (throw (ex-info "persistent SCI helper was not reconstructed"
                          {:js1-dogfood/error :helper-missing
                           :actual (pr-str helper-value)})))
        (when-not (= before after)
          (throw (ex-info
                  "JS1 reconstruction repeated a semantic project operation"
                  {:js1-dogfood/error :replay-actuated
                   :before before :after after})))
        (println "JS1-DOGFOOD-RESUME-HELPER-OK"))
      (let [result (resume! {:conn conn :run-id run-id :config config
                             :llm-adapter (adapter-for :local)
                             :llm-config (:llm config)})]
        (when-not (= :completed (:status result))
          (throw (ex-info "resumed JS1 dogfood did not complete GREEN"
                          {:js1-dogfood/error :not-green :result result})))
        (spit (str (fs/parent counter-path) "/resume-result.edn")
              (pr-str (select-keys result [:status :answer :run-id])))
        (println "JS1-DOGFOOD-GREEN-OK"))
      (finally (db/close conn)))))

(defn- run-child-phase! [phase]
  ;; An internal phase name is not an alternate live opt-in. Preflight has its
  ;; own explicit no-provider gate; start/resume still require the live gate.
  (if (= phase "preflight")
    (do
      (when-not (or (preflight-requested?) (live-requested?))
        (throw (ex-info
                "JS1 dogfood preflight refused without its explicit preflight flag"
                {:js1-dogfood/error :not-opted-in})))
      (require-preflight-contract!))
    (do
      (when-not (live-requested?)
        (throw (ex-info "JS1 dogfood child refused without SAMIZDAT_JS1_DOGFOOD_TEST=1"
                        {:js1-dogfood/error :not-opted-in})))
      (require-live-contract!)))
  (case phase
    "preflight" (phase-preflight!)
    "start" (phase-start!)
    "resume" (phase-resume!)
    (throw (ex-info "unknown JS1 dogfood phase"
                    {:js1-dogfood/error :unknown-phase :phase phase})))
  (flush)
  (System/exit 0))

;; ── parent test -------------------------------------------------------------

(deftest pre-red-contract-allows-repeated-read-only-discovery
  (let [event (fn [op args] {:phase "start" :op op :args args})
        events [(event :project/list ["."])
                ;; Exact independently observed live prefix: the first search
                ;; attempt used the alternate argument order, then discovery
                ;; retried with the documented order. Both are read-only.
                (event :project/search [{:path "."} "DOGFOOD"])
                (event :project/list ["."])
                (event :project/search ["DOGFOOD" {:path "."}])
                (event :project/read ["TASK.md"])
                (event :project/read ["src/dogfood.clj"])
                (event :project/read ["test/dogfood_test.clj"])
                (event :project/stat ["src/dogfood.clj"])
                (event :project/edit
                       ["src/dogfood.clj"
                        (str "sha256:" (apply str (repeat 64 "a")))
                        "(ns fixture.dogfood)\n\n(def dogfood-state :red)\n"])]
        witness (validate-pre-red-events! events)]
    (is (:required-subsequence witness))
    (is (= 2 (:extra-read-only-count witness)))
    (is (= 1 (get-in witness [:counts :project/edit])))
    (is (= :unexpected-red-operations
           (try
             (validate-pre-red-events! (conj events (event :project/read ["TASK.md"])))
             nil
             (catch ExceptionInfo e (:js1-dogfood/error (ex-data e))))))))

(deftest durable-red-quiescence-classification
  ;; Offline discriminator for N1: a pending eval is unsafe even before it has
  ;; an effect, and an unsettled intent is reported explicitly. No SCI or model.
  (let [conn (db/open! ":memory:")]
    (try
      (is (:quiescent? (eval-quiescence conn)))
      (let [id (evals/begin! conn {:spec-id "spec" :instance-id "instance"
                                   :binding-id "binding" :coordinate "coord"
                                   :runtime "runtime" :source "(+ 1 2)"})]
        (is (= [id] (:pending-eval-ids (eval-quiescence conn))))
        (let [seq-n (evals/record-intent! conn id
                                         {:op :project/edit
                                          :args ["x" :absent "y"]})
              unsafe (eval-quiescence conn)]
          (is (false? (:quiescent? unsafe)))
          (is (= :project/edit
                 (get-in unsafe [:unsettled 0 :effects 0 :op])))
          (evals/record-outcome! conn id seq-n {:result {:path "x"}})
          (evals/complete! conn id {:status :completed :result {:value true}})
          (is (:quiescent? (eval-quiescence conn)))))
      (finally (db/close conn)))))

(deftest no-provider-js1-dogfood-preflight
  (if-not (and (preflight-requested?) (not (live-requested?)))
    (is true "skipped: set SAMIZDAT_JS1_DOGFOOD_PREFLIGHT=1 for no-provider compile preflight")
    (do
      (require-preflight-contract!)
      (let [id (str (random-uuid))
            root (str "/tmp/samizdat-js1-dogfood-preflight-" id)
            db-path (str root "/preflight.sqlite3")
            counter-path (str root "/preflight-counters.edn")
            before-source (source-witness)
            layout (atom nil)]
        (fs/create-dirs root)
        (try
          (let [{:keys [target] :as made} (make-detached-target! root)
                _ (reset! layout made)
                runtime (live-runtime!)
                result (run-child! runtime "preflight"
                                   {:target target :db-path db-path
                                    :counter-path counter-path})]
            (is (str/includes? (:out result) "JS1-DOGFOOD-PREFLIGHT-OK"))
            (is (str/includes? (:out result) "workflow=compiled"))
            (is (str/includes? (:out result) "jolt-pwd=source"))
            (is (str/includes? (:out result) "sandbox-root=target"))
            (is (not (fs/exists? counter-path))
                "preflight dispatched no model-facing semantic operation"))
          (finally
            (when-let [{:keys [control target]} @layout]
              (try (git! control "worktree" "remove" "--force" target)
                   (catch Throwable _ nil)))
            (try (fs/delete-tree root) (catch Throwable _ nil))
            (is (= before-source (source-witness))
                "no-provider preflight modified the trusted source checkout")))))))

(deftest bounded-opt-in-live-js1-dogfood
  (if-not (live-requested?)
    (is true "skipped: set SAMIZDAT_JS1_DOGFOOD_TEST=1 plus the exact local-provider contract")
    (do
      (require-live-contract!)
      (let [id (str (random-uuid))
            root (str "/tmp/samizdat-js1-dogfood-" id)
            artifact-dir (str "/tmp/samizdat-js1-dogfood-artifacts-" id)
            db-path (str artifact-dir "/run.sqlite3")
            counter-path (str artifact-dir "/semantic-operations-live.edn")
             before-source (source-witness)
            layout (atom nil)
            run-data (atom {:artifact-dir artifact-dir :db-path db-path
                            :counter-path counter-path})]
        (fs/create-dirs root)
        (fs/create-dirs artifact-dir)
        (try
          (let [{:keys [control target] :as made} (make-detached-target! root)
                _ (reset! layout made)
                _ (swap! run-data assoc :target target)
                runtime (live-runtime!)
                paths {:target target :db-path db-path :counter-path counter-path}
                preflight (run-child! runtime "preflight" paths)]
            (testing "current APIs form a real JS1 workflow path"
              (is (str/includes? (:out preflight) "JS1-DOGFOOD-PREFLIGHT-OK")))
            (let [child (spawn-start! runtime paths)
                  ;; One wall-clock budget is shared by BOTH model-facing
                  ;; processes. Resume receives only what start did not spend.
                  model-deadline (+ (System/currentTimeMillis) total-timeout-ms)
                  start-budget (min phase-timeout-ms
                                    (- model-deadline (System/currentTimeMillis)))
                  checkpoint (try (wait-for-red-checkpoint! db-path target child
                                                            start-budget)
                                   (catch Throwable e
                                     (let [start-result (reap-child! child)]
                                       (swap! run-data assoc :start-result start-result)
                                       (require-reaped! start-result)
                                       (throw e))))
                  start-result (require-reaped! (reap-child! child))
                  run-id (:run-id checkpoint)
                  _ (swap! run-data assoc :target target :run-id run-id
                           :checkpoint checkpoint :start-result start-result)
                  start-events (phase-events counter-path "start")
                  pre-red-witness (validate-pre-red-events! start-events)]
              (println "JS1-DOGFOOD-RED-CHECKPOINT" run-id)
              ;; STOP made the first witness race-free; the post-reap witness
              ;; proves shutdown added no torn record before resume is allowed.
              (require-quiescent-db! db-path run-id "after bounded child reap")
              (testing "the first process was intentionally killed at durable RED"
                (is (not (.isAlive (:proc child))))
                (is (= "(ns fixture.dogfood)\n\n(def dogfood-state :red)\n"
                       (slurp (str target "/src/dogfood.clj"))))
                (is (:required-subsequence pre-red-witness))
                (is (= 1 (get-in pre-red-witness [:counts :project/stat])))
                (is (= 1 (get-in pre-red-witness [:counts :project/edit]))))
              (let [resume-result (run-child! runtime "resume"
                                              (assoc paths :run-id run-id
                                                     :timeout-ms
                                                     (let [remaining
                                                           (- model-deadline
                                                              (System/currentTimeMillis))]
                                                       (when-not (pos? remaining)
                                                         (throw (ex-info
                                                                 "JS1 dogfood exhausted its total live-run deadline before resume"
                                                                 {:js1-dogfood/error
                                                                  :total-timeout})))
                                                       (min phase-timeout-ms
                                                            remaining))))]
                (swap! run-data assoc :resume-result resume-result)
                ;; Per-phase exactness is intentionally separate from the
                ;; flexible discovery prefix. Replay emits no counter events;
                ;; resume may only read NEW red evidence, stat, and edit GREEN.
                (let [resume-witness
                      (validate-resume-events!
                       (phase-events counter-path "resume"))]
                (testing "fresh-process resume reconstructed state and shipped GREEN"
                  (is (str/includes? (:out resume-result)
                                     "JS1-DOGFOOD-RESUME-HELPER-OK"))
                  (is (str/includes? (:out resume-result)
                                     "JS1-DOGFOOD-GREEN-OK"))
                  (is (= "(ns fixture.dogfood)\n\n(def dogfood-state :green)\n"
                         (slurp (str target "/src/dogfood.clj"))))
                  (is (:exact resume-witness)))
                (let [conn (db/connect db-path)]
                  (try
                    (let [run (runs/get-run conn run-id)
                          turns (journal/turns conn run-id)
                          events (journal/events-since conn run-id 0 5000)
                          history (evals/history conn (str "bind:main:" run-id))
                          sources (mapv :source history)
                          green-events (filter
                                        (fn [event]
                                          (let [d (parsed-event-data event)]
                                            (and (= "ship-verify" (:kind event))
                                                 (= true (:green d))
                                                 (= false (:blocked d))
                                                 (= 0 (:exit d)))))
                                        events)]
                      (testing "single-player and total turn bounds are durable"
                        (is (= 1 (:beam_width run)))
                        (is (= max-turns (:max_turns run)))
                        (is (= "completed" (:status run)))
                        (is (<= (count turns) max-turns)))
                      (testing "helper was defined once and used after resume"
                        (is (= 1 (count (filter #(str/includes? % "(defn dogfood-helper")
                                               sources))))
                        (is (some #{"(dogfood-helper \"resumed\")"} sources)))
                      (testing "the resumed model read red evidence and did not repeat observations"
                        (is (some #{"(project/read \"red-evidence.txt\")"} sources))
                        (is (every? #{"eval" "doc" "complete" "done"}
                                    (map :tool_name turns))))
                      (testing "the same fixed operator verifier eventually recorded GREEN"
                        (is (= 1 (count green-events)))))
                    (finally (db/close conn))))))))
          (finally
            (when-let [target (:target @run-data)]
              (try
                (capture-artifacts! (assoc @run-data :target target))
                (catch Throwable e
                  (safe-spit! (str artifact-dir "/capture-error.txt")
                              (str (ex-message e) "\n")))))
            (when-let [{:keys [control target]} @layout]
              (try (git! control "worktree" "remove" "--force" target)
                   (catch Throwable _ nil)))
            (try (fs/delete-tree root) (catch Throwable _ nil))
            (is (= before-source (source-witness))
                "the live dogfood modified the trusted running checkout")
            (println "JS1 dogfood artifacts:" artifact-dir)))))))

(when-let [phase (internal-env "PHASE")]
  (run-child-phase! phase))
