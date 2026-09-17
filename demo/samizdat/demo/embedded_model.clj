;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.demo.embedded-model
  "Bounded, credential-free orchestration for the real embedded telemetry demo.

  The model run is intentionally an explicit command, never a test side effect.
  Pure command, environment, poll, summary, and trace checks are exposed so the
  ordinary suite can qualify the harness with fake HTTP and process seams."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.http-client :as http]
            [jolt.process :as process]
            [samizdat.api.client :as api]))

(def default-base-url "http://marvin.asymptote-city.ts.net:13305/v1")
(def default-model "Qwen3.6-27B-MTP-GGUF")
(def default-timeout-ms (* 30 60 1000))
(def default-expected-jolt-rev "aea91781")
(def expected-jolt-rev-env "SAMIZDAT_DEMO_EXPECTED_JOLT_REV")
(def default-jolt
  "/home/chuck/ai-src/worktrees/jolt-aea91781-release-137/target/release/jolt")
(def default-wrapper "/home/chuck/ai-src/tools/jolt-with-chez-10.4.1")
(def default-libchdb "/home/chuck/.cache/jolt-chdb/26.7.3/linux-amd64/libchdb.so")
(def lost-baseline "b15ba4e125c7a57a924e16df403b9a2ddebff816")

(def expected-span-families
  ["run" "run.rounds" "branch.open" "branch.close" "turn" "model.chat"
   "tool.selection" "tool" "steer"])

(def terminal-statuses
  #{"completed" "aborted" "failed" "interrupted" "exhausted" "abandoned"})

(def ^:private safe-env-keys
  ["HOME" "PATH" "TMPDIR" "JOLT_CACHE_DIR"])

(ffi/defcfn ^:private c-kill "kill" [:int :int] :int)
(declare sanitize-log)
(declare bounded-tail)
(declare bounded-health-get!)

(defn fail! [message data]
  (throw (ex-info message (assoc data :samizdat.demo.embedded-model/error true))))

(defn- remaining-timeout! [deadline-ms cap-ms phase]
  (let [remaining (- deadline-ms (System/currentTimeMillis))]
    (when-not (pos? remaining)
      (fail! "demo exceeded the overall deadline" {:phase phase}))
    (min cap-ms remaining)))

(defn sanitized-child-env
  "Build the complete child environment. It is an allowlist, not a redaction
  pass, so Langfuse, OTLP, provider, and shell secrets cannot be inherited."
  ([options] (sanitized-child-env options #(System/getenv %)))
  ([options getenv]
   (merge
    (into {} (keep (fn [key] (when-let [value (getenv key)] [key value])))
          safe-env-keys)
    {"HARNESS_PROVIDER" "local"
     "HARNESS_BASE_URL" (:base-url options)
     "HARNESS_MODEL" (:model options)
     "HARNESS_ROOT" (:project-root options)
     "HARNESS_DB" (:db-path options)
     "HARNESS_PORT" (str (:http-port options))
     "HARNESS_NREPL_PORT" (str (:nrepl-port options))
     "JOLT_CHDB_LIB" (:libchdb options)
     "SAMIZDAT_EMBEDDED_DURABLE_ROOT" (:durable-root options)
     ;; Redundant with the embedded owner's hard-coded policy, and useful as
     ;; evidence that a future launcher cannot accidentally consult inherited
     ;; content configuration.
     "SAMIZDAT_TELEMETRY_CONTENT" "off"})))

(defn server-child-env
  "Add the one bounded runtime diagnostic accepted by server A/B. Other demo
  children retain the smaller base allowlist."
  ([options] (server-child-env options #(System/getenv %)))
  ([options getenv]
   (let [raw (getenv "JOLT_FIBER_TRACE_LIMIT")
         parsed (when (and (string? raw) (re-matches #"[1-9][0-9]*" raw))
                  (parse-long raw))]
     (when (and (some? raw)
                (not (and parsed (<= parsed 4096))))
       (fail! "JOLT_FIBER_TRACE_LIMIT must be an integer from 1 to 4096"
              {:phase :environment :variable "JOLT_FIBER_TRACE_LIMIT"}))
     (cond-> (sanitized-child-env options getenv)
       raw (assoc "JOLT_FIBER_TRACE_LIMIT" raw)))))

(defn assert-jolt-revision!
  "Require the exact caller-selected revision substring in `jolt --version`."
  [expected-revision result]
  (when (str/blank? (str expected-revision))
    (fail! "SAMIZDAT_DEMO_EXPECTED_JOLT_REV must be nonblank"
           {:phase :toolchain :variable "SAMIZDAT_DEMO_EXPECTED_JOLT_REV"}))
  (when-not (str/includes? (str (:out result)) expected-revision)
    (fail! "demo Jolt build did not match SAMIZDAT_DEMO_EXPECTED_JOLT_REV"
           {:phase :toolchain :expected-revision expected-revision}))
  result)

(defn embedded-command [{:keys [wrapper jolt durable-root]}]
  [wrapper jolt "-M:telemetry:embedded-telemetry:embedded-serve"
   "--" "--durable-root" durable-root])

(defn run-request [problem model]
  {:problem problem
   :model model
   :max_turns 14
   :token_budget 120000
   :beam_width 1
   :max_total_branches 1})

(defn first-turn-event? [event]
  (= "turn" (some-> (:kind event) name)))

(defn run-status [detail]
  (some-> detail :body :run :status name))

(defn drive-run!
  "Start, steer exactly once after the first durable turn event, and wait for
  a terminal run. `ops` is the fakeable HTTP/time seam. The shared deadline is
  absolute; a timeout requests abort once and remains an error."
  [{:keys [start-run journal-since intervene run-detail abort now-ms sleep-ms
           runtime-failure]}
   {:keys [base request steering deadline-ms poll-ms]
    :or {poll-ms 1000}}]
  (let [started (start-run base request)]
    (when-not (:ok started)
      (fail! "model run did not start" {:phase :start :error (:error started)}))
    (let [run-id (get-in started [:body :run_id])]
      (when (str/blank? (str run-id))
        (fail! "model run start returned no run id" {:phase :start}))
      (loop [cursor 0 steered? false turns 0]
        (when (>= (now-ms) deadline-ms)
          (abort base run-id)
          (fail! "model run exceeded the overall deadline"
                 {:phase :run :run-id run-id :steered? steered? :turns turns}))
        (when-let [failure (when runtime-failure (runtime-failure))]
          (abort base run-id)
          (fail! "embedded runtime failed while the run still claimed liveness"
                 {:phase :runtime :run-id run-id :failure failure
                  :steered? steered? :turns turns}))
        (let [journal (journal-since base run-id cursor 200)]
          (when-not (:ok journal)
            (fail! "journal poll failed"
                   {:phase :journal :run-id run-id :error (:error journal)}))
          (let [events (vec (get-in journal [:body :events]))
                new-turns (count (filter first-turn-event? events))
                turns' (+ turns new-turns)
                cursor' (or (get-in journal [:body :next]) cursor)
                steered-now? (and (not steered?) (pos? turns'))
                _ (when steered-now?
                    (let [result (intervene base run-id
                                            {:kind "message" :payload steering})]
                      (when-not (:ok result)
                        (fail! "steering intervention was refused"
                               {:phase :steer :run-id run-id
                                :error (:error result)}))))
                steered?' (or steered? steered-now?)
                detail (run-detail base run-id)]
            (when-not (:ok detail)
              (fail! "run detail poll failed"
                     {:phase :detail :run-id run-id :error (:error detail)}))
            (let [status (run-status detail)]
              (cond
                (contains? terminal-statuses status)
                (do
                  (when-not steered?'
                    (fail! "run ended before the required steering boundary"
                           {:phase :steer :run-id run-id :status status
                            :turns turns'}))
                  {:run-id run-id :status status :turns turns'
                   :steering-count 1 :detail (:body detail)})

                :else
                (do (sleep-ms poll-ms)
                    (recur cursor' steered?' turns'))))))))))

(def ^:private test-summary-pattern
  #"\s*(?:Ran 6 tests containing 6 assertions\.\s*0 failures, 0 errors\.|Ran 6 tests\.\s*6 assertions passed, 0 failures, 0 errors\.)\s*")

(defn assert-six-tests! [{:keys [exit out err timeout] :as result}]
  (when (or timeout (not= 0 exit)
            (not (re-matches test-summary-pattern (str out))))
    (fail! "independent fixture verification was not exactly 6 tests/6 assertions"
           {:phase :verify :exit exit :timeout (boolean timeout)
            :stdout (let [value (sanitize-log out)]
                      (subs value 0 (min 2000 (count value))))
            :stderr (let [value (sanitize-log err)]
                      (subs value 0 (min 2000 (count value))))}))
  (assoc (select-keys result [:exit]) :tests 6 :assertions 6
         :failures 0 :errors 0))

(def trusted-verifier-expression
  "Fixed host-owned checks, independent of the model's test files."
  "(require '[calc.core :as calc]) (prn [(calc/square 0) (calc/square 3) (calc/square -4) (calc/cube 0) (calc/cube 3) (calc/cube -2)])")

(defn trusted-verifier-command [{:keys [wrapper jolt]}]
  [wrapper jolt "-Srepro" "-e" trusted-verifier-expression])

(defn assert-trusted-semantics!
  "Fail closed on anything except the six host-selected results."
  [{:keys [exit out err timeout]}]
  (when (or timeout (not= 0 exit)
            (not (re-matches #"\s*\[0 9 16 0 27 -8\]\s*" (str out)))
            (not (str/blank? (str err))))
    (fail! "host-owned fixture semantic verification failed"
           {:phase :trusted-semantics :exit exit :timeout (boolean timeout)}))
  {:trusted-semantic-check true :semantic-case-count 6})

(def ^:private trace-link-pattern
  #"/oscope/telemetry/traces/([0-9a-f]{32})")

(defn trace-ids [index-html]
  (vec (distinct (map second (re-seq trace-link-pattern (str index-html))))))

(defn assert-trace!
  "Find the run's supported Oscope trace-detail page and check the complete
  nine-family/content-off contract. Returns only bounded evidence, never HTML."
  [run-id index-html detail-for]
  (let [ids (trace-ids index-html)
        run-attribute-pattern
        (re-pattern
         (str "(?s)<dt>samizdat\\.run\\.id</dt>\\s*<dd>"
              (java.util.regex.Pattern/quote run-id) "</dd>"))
        matches (keep (fn [trace-id]
                        (let [{:keys [status body]} (detail-for trace-id)
                              html (str body)]
                          (when (and (= 200 status)
                                     (re-find run-attribute-pattern html))
                            [trace-id html])))
                      ids)
        [trace-id html] (first matches)]
    (when-not trace-id
      (fail! "Oscope index exposed no trace detail for the run id"
             {:phase :telemetry :run-id run-id :trace-links (count ids)}))
    (let [span-present? (fn [family]
                          (boolean
                           (re-find
                            (re-pattern
                             (str "class=\"otel-span-name[^\"]*\">"
                                  (java.util.regex.Pattern/quote family)
                                  "</span>"))
                            html)))
          missing (vec (remove span-present? expected-span-families))
          leaked (vec (filter #(str/includes? html %)
                              ["langfuse.observation.input"
                               "langfuse.observation.output"]))]
      (when (seq missing)
        (fail! "run trace is missing required span families"
               {:phase :telemetry :run-id run-id :trace-id trace-id
                :missing missing}))
      (when (seq leaked)
        (fail! "content-off trace contains content attribute keys"
               {:phase :telemetry :run-id run-id :trace-id trace-id
                :leaked-keys leaked}))
      {:trace-id trace-id :span-families expected-span-families
       :content-enabled false})))

(defn- parse-long! [flag value]
  (let [parsed (parse-long (str value))]
    (when-not (and parsed (pos? parsed))
      (fail! (str flag " must be a positive integer") {:phase :arguments :flag flag}))
    parsed))

(defn parse-options
  ([args] (parse-options args #(System/getenv %)))
  ([args getenv]
   (let [defaults {:base-url default-base-url :model default-model
                   :model-preflight :lemonade-loaded
                   :timeout-ms default-timeout-ms :jolt default-jolt
                   :expected-jolt-rev (or (getenv expected-jolt-rev-env)
                                          default-expected-jolt-rev)
                   :wrapper default-wrapper :libchdb default-libchdb
                   :fixture "fixtures/embedded-model/calc-v1"
                   :output "target/embedded-model-demo"}]
     (loop [options defaults args (seq args)]
       (if-not args
         options
         (let [[flag value & more] args]
           (when-not value
             (fail! (str flag " requires a value") {:phase :arguments :flag flag}))
           (recur
            (case flag
              "--base-url" (assoc options :base-url value)
              "--model" (assoc options :model value)
              "--model-preflight"
              (assoc options :model-preflight
                     (case value
                       "lemonade-loaded" :lemonade-loaded
                       "none" :none
                       (fail! "invalid model preflight mode"
                              {:phase :arguments :flag flag})))
              "--timeout-ms" (assoc options :timeout-ms (parse-long! flag value))
              "--jolt" (assoc options :jolt value)
              "--wrapper" (assoc options :wrapper value)
              "--libchdb" (assoc options :libchdb value)
              "--fixture" (assoc options :fixture value)
              "--output" (assoc options :output value)
              (fail! (str "unknown option " flag) {:phase :arguments :flag flag}))
            more)))))))

(defn assert-loaded-model!
  "Validate Lemonade metadata only; this does not establish inference success.
  Neither raw health payloads nor parser/transport exceptions escape."
  [model {:keys [status body]}]
  (when-not (= 200 status)
    (fail! "model readiness preflight failed"
           {:phase :model-preflight :reason :health-http
            :http-status (if (and (integer? status) (<= 100 status 599)) status 0)}))
  (let [health (try
                 (when (and (string? body) (<= (count body) 65536))
                   ;; This callback also rejects legal trailing whitespace.
                   ;; Keep the raw guard, then strip only JSON whitespace.
                   (json/read-str (str/replace body #"[ \t\r\n]+$" "") :key-fn keyword
                                  :extra-data-fn json/on-extra-throw))
                 (catch Throwable _ nil))
        loaded (:all_models_loaded health)]
    (when-not (and (map? health) (= "ok" (:status health))
                   (vector? loaded)
                   (every? #(and (map? %) (string? (:model_name %))
                                 (not (str/blank? (:model_name %)))) loaded))
      (fail! "model readiness preflight failed"
             {:phase :model-preflight :reason :health-malformed :http-status 200}))
    (when-not (some #(= model (:model_name %)) loaded)
      (fail! "requested model is not loaded; operator action is required"
             {:phase :model-preflight :reason :model-not-loaded :http-status 200}))
    {:mode :lemonade-loaded :metadata-ready? true}))

(defn model-preflight!
  "Explicit read-only Lemonade check before any collector acquisition.
  Generic OpenAI-compatible endpoints must opt out with mode :none."
  [{:keys [base-url model model-preflight deadline-ms]}]
  (case (or model-preflight :lemonade-loaded)
    :none {:mode :none :metadata-ready? false}
    :lemonade-loaded
    (let [base (str/replace (str base-url) #"/+$" "")
          valid-endpoint? (try
                            (let [url (java.net.URL. base)]
                              (and (contains? #{"http" "https"} (.getProtocol url))
                                   (not (str/blank? (.getHost url)))
                                   (nil? (.getUserInfo url))
                                   (not (str/includes? base "?"))
                                   (not (str/includes? base "#"))))
                            (catch Throwable _ false))]
      (when-not (and valid-endpoint? (string? model) (not (str/blank? model)))
        (fail! "invalid model readiness configuration"
               {:phase :model-preflight :reason :invalid-configuration}))
      (let [response (try (bounded-health-get! (str base "/health") deadline-ms)
                          (catch Throwable error
                            (let [reason (:reason (ex-data error))]
                              (fail! "model readiness preflight failed"
                                     {:phase :model-preflight :http-status 0
                                      :reason (if (contains? #{:health-timeout :health-unsettled
                                                               :health-size :curl-unavailable} reason)
                                                reason :health-transport)}))))]
        (remaining-timeout! deadline-ms 10000 :model-preflight)
        (when-not response
          (fail! "model readiness preflight failed"
                 {:phase :model-preflight :reason :health-transport :http-status 0}))
        (assert-loaded-model! model response)))
    (fail! "invalid model preflight mode"
           {:phase :model-preflight :reason :invalid-configuration})))

(defn- copy-tree! [source target]
  (let [source-path (.toPath (io/file source))]
    (doseq [file (file-seq (io/file source))]
      (let [relative (.relativize source-path (.toPath file))
            destination (io/file target (str relative))]
        (if (.isDirectory file)
          (.mkdirs destination)
          (do (.mkdirs (.getParentFile destination))
              (with-open [input (io/input-stream file)
                          output (io/output-stream destination)]
                (io/copy input output))))))))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket)))

(defn- positive-pid? [pid]
  ;; kill(0, ...) and kill(-1, ...) have process-group/broadcast semantics.
  (and (integer? pid) (pos? pid) (<= pid 2147483647)))

(defn- owned-pid [child]
  (try
    (let [pid (.pid (:proc child))]
      (when (positive-pid? pid) pid))
    (catch Throwable _ nil)))

(defn- signal! [pid number]
  (if (positive-pid? pid)
    (try (c-kill pid number) (catch Throwable _ -1))
    -1))

(defn- wait-for-child! [child timeout-ms]
  (try
    (true? (.waitFor (:proc child) timeout-ms
                     java.util.concurrent.TimeUnit/MILLISECONDS))
    (catch Throwable _ false)))

(defn- force-reap!
  "Bounded direct-child retirement. Descendant snapshots are not ownership
  capabilities and are never signaled. A request is not a terminal receipt."
  [child]
  (if (wait-for-child! child 0)
    {:terminal? true}
    (if-let [pid (let [pid (owned-pid child)] (when (positive-pid? pid) pid))]
      (do
        (signal! pid 15)
        (if (wait-for-child! child 2000)
          {:terminal? true}
          (do
            (signal! pid 9)
            {:terminal? (wait-for-child! child 2000)})))
      {:terminal? false :status :ownership-unavailable})))

(defn bounded-health-get!
  "Demo-only curl transport. No output drain promises or provider payloads
  enter diagnostics. Setup/version/transfer share one monotonic deadline;
  direct-child retirement has a separate bounded four-second allowance."
  [url overall-deadline-ms]
  (let [budget (remaining-timeout! overall-deadline-ms 10000 :model-preflight)
        deadline (+ (System/nanoTime) (* budget 1000000))
        remaining (fn []
                    (let [ms (quot (- deadline (System/nanoTime)) 1000000)]
                      (when-not (pos? ms)
                        (fail! "model readiness preflight failed"
                               {:phase :model-preflight :reason :health-timeout :http-status 0}))
                      (min ms (remaining-timeout! overall-deadline-ms 10000 :model-preflight))))
        scratch (io/file (str (java.nio.file.Files/createTempDirectory
                              "samizdat-health-" (make-array java.nio.file.attribute.FileAttribute 0))))
        output (io/file scratch "status")
        body (io/file scratch "body")
        settled? (atom true)
        run (fn [argv]
              (let [_ (remaining)
                    child (try (process/process argv {:env {"PATH" "/usr/bin:/bin"}
                                                       :in (io/file "/dev/null") :out :write :out-file output
                                                       :err :discard})
                               (catch Throwable _
                                 (fail! "model readiness preflight failed"
                                        {:phase :model-preflight :reason :health-transport :http-status 0})))]
                (reset! settled? false)
                (try
                  ;; Recompute after spawn; setup must not buy a fresh wait.
                  (if (wait-for-child! child (remaining))
                    (do (reset! settled? true)
                        ;; Redirected files have no asynchronous drain workers.
                        (try (.exitValue (:proc child))
                             (catch Throwable _ -1)))
                    (fail! "model readiness preflight failed"
                           {:phase :model-preflight :reason :health-timeout :http-status 0}))
                  (catch Throwable _
                    (when-not @settled?
                      (reset! settled? (:terminal? (force-reap! child))))
                    (fail! "model readiness preflight failed"
                           {:phase :model-preflight
                            :reason (if @settled? :health-timeout :health-unsettled)
                            :http-status 0})))))]
    (try
      (when-not (and (zero? (run ["/usr/bin/curl" "--disable" "--version"]))
                     (<= (.length output) 2048)
                     (let [[_ major minor] (re-find #"^curl ([0-9]+)\.([0-9]+)\." (slurp output))]
                       (and major (or (> (parse-long major) 8)
                                      (and (= 8 (parse-long major)) (>= (parse-long minor) 5))))))
        (fail! "model readiness preflight failed"
               {:phase :model-preflight :reason :curl-unavailable :http-status 0}))
      (let [ms (remaining)
            exit (run ["/usr/bin/curl" "--disable" "--silent" "--globoff"
                       "--disallow-username-in-url" "--proxy" "" "--noproxy" "*"
                       "--proto" "=http,https" "--max-time" (str (/ ms 1000.0))
                       "--connect-timeout" (str (/ (min ms 3000) 1000.0))
                       "--max-filesize" "65536" "--output" (str body)
                       "--write-out" "%{http_code}" "--url" url])]
        (remaining)
        (when-not (and (zero? exit) (<= (.length body) 65536)
                       (= 3 (.length output)))
          (fail! "model readiness preflight failed"
                 {:phase :model-preflight
                  :reason (case exit 28 :health-timeout 63 :health-size :health-transport)
                  :http-status 0}))
        {:status (try (parse-long (slurp output)) (catch Throwable _ 0))
         :body (slurp body)})
      (finally
        ;; Unknown ownership/settlement fails closed and preserves private
        ;; scratch. Never unlink a file which an unconfirmed child can write.
        (when @settled?
          (doseq [file [output body scratch]]
            (try (.delete file) (catch Throwable _ nil))))))))

(defn- bounded-process!
  [command {:keys [dir env timeout-ms out-file err-file]
            :or {timeout-ms 30000}}]
  (let [child (process/process
               command
               (cond-> {:dir dir :env env}
                 out-file (assoc :out :write :out-file (io/file out-file))
                 err-file (assoc :err :write :err-file (io/file err-file))
                 (not out-file) (assoc :out :string)
                 (not err-file) (assoc :err :string)))
        proc (:proc child)
        finished? (wait-for-child! child timeout-ms)]
    (try
      (if finished?
        (let [result @child]
          {:exit (:exit result) :out (if out-file "" (or (:out result) ""))
           :err (if err-file "" (or (:err result) ""))})
        (let [retirement (force-reap! child)]
          {:timeout true :ms timeout-ms
           :terminal? (:terminal? retirement)}))
      (finally
        (doseq [stream [(.getInputStream proc) (.getErrorStream proc)
                        (.getOutputStream proc)]]
          (try (.close stream) (catch Throwable _ nil)))))))

(defn- command-ok! [phase result]
  (when (or (:timeout result) (not= 0 (:exit result)))
    (fail! (str (name phase) " command failed")
           {:phase phase :exit (:exit result) :timeout (boolean (:timeout result))
            :stdout (let [value (sanitize-log (:out result))]
                      (subs value 0 (min 2000 (count value))))
            :stderr (let [value (sanitize-log (:err result))]
                      (subs value 0 (min 2000 (count value))))}))
  result)

(defn- prepare-project! [{:keys [fixture project-root] :as options}]
  (.mkdirs (io/file project-root))
  (copy-tree! fixture project-root)
  (let [git-env (merge (sanitized-child-env options)
                       {"GIT_AUTHOR_NAME" "Samizdat demo fixture"
                        "GIT_AUTHOR_EMAIL" "samizdat-demo@invalid.example"
                        "GIT_COMMITTER_NAME" "Samizdat demo fixture"
                        "GIT_COMMITTER_EMAIL" "samizdat-demo@invalid.example"
                        "GIT_AUTHOR_DATE" "2026-09-15T00:00:00Z"
                        "GIT_COMMITTER_DATE" "2026-09-15T00:00:00Z"})]
    (doseq [command [["git" "init" "--quiet"]
                     ["git" "add" "."]
                     ["git" "commit" "--quiet" "-m" "fixture: broken square baseline"]]]
      (command-ok! :fixture-git
                   (bounded-process!
                    command
                    {:dir project-root :env git-env
                     :timeout-ms (remaining-timeout! (:deadline-ms options)
                                                     30000 :fixture-git)})))
    (let [revision (str/trim (:out (command-ok!
                                    :fixture-git
                                    (bounded-process!
                                     ["git" "rev-parse" "HEAD"]
                                     {:dir project-root :env git-env
                                      :timeout-ms
                                      (remaining-timeout! (:deadline-ms options)
                                                          30000 :fixture-git)}))))]
      (when (= lost-baseline revision)
        (fail! "new fixture must not impersonate the unavailable historical object"
               {:phase :fixture-git}))
      revision)))

(defn- raw-get
  ([base path] (raw-get base path 10000))
  ([base path timeout-ms]
   (try
     (let [response (http/get (str base path)
                              {:socket-timeout timeout-ms
                               :conn-timeout (min 3000 timeout-ms)
                               :throw-exceptions false})]
       {:status (:status response) :body (str (:body response))})
     (catch Throwable error {:status 0 :error (ex-message error)}))))

(defn- await-ready! [base deadline-ms]
  (loop []
    (let [response (raw-get base "/health"
                            (remaining-timeout! deadline-ms 10000 :server-start))]
      (cond
        (= 200 (:status response)) true
        (>= (System/currentTimeMillis) deadline-ms)
        (fail! "embedded server did not become ready"
               {:phase :server-start :status (:status response)})
        :else (do (Thread/sleep 100) (recur))))))

(defn- start-server! [options label]
  (let [raw-out (str (:output options) "/." label ".stdout.raw")
        raw-err (str (:output options) "/." label ".stderr.raw")]
    {:label label
     :terminal (atom false)
     :raw-out raw-out :raw-err raw-err
     :child (process/process (embedded-command options)
                             {:dir (:checkout-root options)
                              :env (server-child-env options)
                              :out :write :out-file (io/file raw-out)
                              :err :write :err-file (io/file raw-err)})}))

(defn shutdown-evidence
  "Require one confirmed close and the handled TERM exit contract."
  [finished? exit logs]
  (let [confirmed (count (re-seq
                          #"embedded telemetry stopped\s+\{:status :closed, :phase :closed\}"
                          (str logs)))
        announced (count (re-seq #"embedded telemetry stopped" (str logs)))]
    {:exit exit :closed? (boolean finished?)
     :confirmed-close-count confirmed
     :graceful? (and finished? (= 143 exit) (= 1 confirmed announced)
                     (not (str/includes? (str logs) "Samizdat application stop failed")))}))

(defn runtime-failure-marker [logs]
  (when (or (str/includes? (str logs) "JOLT_FIBER_INVARIANT")
            (re-find #"Exception in jolt-fiber-run: fiber in unexpected state"
                     (str logs)))
    :jolt-fiber-state))

(defn- stop-server! [{:keys [child raw-out raw-err terminal]} timeout-ms]
  (let [started-ms (System/currentTimeMillis)
        already-terminal? (wait-for-child! child 0)
        pid (when-not already-terminal?
              (let [pid (owned-pid child)] (when (positive-pid? pid) pid)))
        signaled (when pid (signal! pid 15))
        wait-ms (max 0 (- timeout-ms (- (System/currentTimeMillis) started-ms)))
        finished? (or already-terminal? (and pid (wait-for-child! child wait-ms)))
        retired? (or finished? (:terminal? (force-reap! child)))]
    (when terminal (reset! terminal (true? retired?)))
    (let [logs (str (bounded-tail raw-out 65536)
                    (bounded-tail raw-err 65536))]
      (assoc (shutdown-evidence finished? (when finished? (:exit @child)) logs)
             :terminal? (true? retired?) :term-exit signaled))))

(defn- bounded-tail [file limit]
  (let [source (io/file file)]
    (if-not (.exists source)
      ""
      (with-open [input (io/input-stream source)]
        (let [length (.length source)
              start (max 0 (- length limit))
              bytes (byte-array (int (- length start)))]
          (loop [remaining start]
            (when (pos? remaining)
              (let [skipped (.skip input remaining)]
                (if (pos? skipped)
                  (recur (- remaining skipped))
                  (when-not (= -1 (.read input))
                    (recur (dec remaining)))))))
          (let [read-count
                (loop [offset 0]
                  (if (< offset (alength bytes))
                    (let [read (.read input bytes offset (- (alength bytes) offset))]
                      (if (pos? read)
                        (recur (+ offset read))
                        offset))
                    offset))
                actual (if (= read-count (alength bytes))
                         bytes
                         (let [trimmed (byte-array read-count)]
                           (System/arraycopy bytes 0 trimmed 0 read-count)
                           trimmed))]
            (String. actual "UTF-8")))))))

(defn sanitize-log [value]
  (-> (str value)
      (str/replace #"(?i)authorization\s*[:=]\s*(?:basic\s+)?\S+"
                   "Authorization=[REDACTED]")
      (str/replace #"(?i)basic\s+[A-Za-z0-9+/=]+" "Basic [REDACTED]")
      (str/replace #"(?i)(authorization|api[-_]?key|secret|token)\s*[:=]\s*\S+"
                   "$1=[REDACTED]")))

(defn- publish-sanitized-logs! [server output]
  (when (true? (some-> server :terminal deref))
   (doseq [[source suffix] [[(:raw-out server) "stdout"] [(:raw-err server) "stderr"]]]
    (when (.exists (io/file source))
      (try
        (spit (io/file output (str (:label server) "." suffix ".log"))
              (sanitize-log (bounded-tail source 65536)))
        (finally
          (java.nio.file.Files/deleteIfExists (.toPath (io/file source)))))))))

(defn- cleanup-diagnostic! [message]
  (try (println message) (catch Throwable _ nil)))

(defn- cleanup-server! [server output]
  ;; Finally cleanup is observational and must not mask the original failure.
  ;; Unknown liveness is not proof of exit; retain raw files and scratch.
  (let [retired? (or (true? (some-> server :terminal deref))
                     (try (:terminal? (stop-server! server 5000))
                          (catch Throwable _ false)))]
    (if (true? retired?)
      (try
        (publish-sanitized-logs! server output)
        {:terminal? true :status :closed}
        (catch Throwable _
          (cleanup-diagnostic! "demo cleanup incomplete: evidence publication failed")
          {:terminal? true :status :publication-failed}))
      (do
        (cleanup-diagnostic! "demo cleanup incomplete: child termination unconfirmed; evidence retained")
        {:terminal? false :status :cleanup-incomplete}))))

(defn- oscope-evidence! [base run-id deadline-ms]
  (let [index (raw-get base "/oscope/telemetry?window=1h"
                       (remaining-timeout! deadline-ms 10000 :telemetry))]
    (when-not (= 200 (:status index))
      (fail! "Oscope telemetry index was unavailable"
             {:phase :telemetry :status (:status index)}))
    (assert-trace! run-id (:body index)
                   #(raw-get base (str "/oscope/telemetry/traces/" %)
                             (remaining-timeout! deadline-ms 10000 :telemetry)))))

(defn- write-evidence! [output evidence]
  (spit (io/file output "evidence.json")
        (json/write-str evidence :escape-slash false)))

(defn evidence-record
  "Assemble the bounded artifact independently of the cost-bearing runner."
  [{:keys [baseline request run test-result stopped-a trace-a stopped-b trace-b
           model-readiness]}]
  {:evidence/version 1
   :fixture {:version 1 :baseline-commit baseline
             :historical-reference lost-baseline
             :historical-reference-available false}
   :request (select-keys request [:model :max_turns :token_budget
                                  :beam_width :max_total_branches])
   :run (select-keys run [:run-id :status :turns :steering-count])
   :verification test-result
   :model-readiness model-readiness
   :process-a {:stopped-before-reopen (:closed? stopped-a) :trace trace-a}
   :process-b {:same-durable-root true :no-model-call true
               :closed (:closed? stopped-b) :graceful (:graceful? stopped-b)
               :trace trace-b}
   :content-enabled false
   :external-export false})

(defn verify-terminal-run!
  "Independently verify completed or exhausted tasks. This does not change
  orchestration truth: an exhausted run stays exhausted in the evidence."
  [run verify!]
  (when-not (contains? #{"completed" "exhausted"} (:status run))
    (fail! "model run cannot qualify" {:phase :run :run-id (:run-id run)
                                      :status (:status run)}))
  (verify!))

(defn run-demo!
  "Execute the authorized real demo. This is not called by tests or namespace
  load. The caller owns the external endpoint's cost authorization."
  [options]
  (let [checkout-root (.getCanonicalPath (io/file "."))
        session-id (str (java.util.UUID/randomUUID))
        output (.getCanonicalPath (io/file (:output options) session-id))
        project-root (str output "/project")
        durable-root (str output "/durable")
        db-path (str output "/samizdat.sqlite3")
        deadline-ms (+ (System/currentTimeMillis) (:timeout-ms options))
        model-readiness (model-preflight! (assoc options :deadline-ms deadline-ms))
        options (assoc options :checkout-root checkout-root :output output
                       :deadline-ms deadline-ms
                       :project-root project-root :durable-root durable-root
                       :db-path db-path :http-port (free-port)
                       :nrepl-port (free-port))]
    (.mkdirs (io/file output))
    (let [version (assert-jolt-revision!
                   (:expected-jolt-rev options)
                   (command-ok! :toolchain
                                (bounded-process!
                                 [(:wrapper options) (:jolt options) "--version"]
                                 {:dir checkout-root
                                  :env (sanitized-child-env options)
                                  :timeout-ms
                                  (remaining-timeout! deadline-ms
                                                      30000 :toolchain)})))]
      (let [baseline (prepare-project! options)
            problem (slurp (io/file project-root "problem.md"))
            steering (slurp (io/file project-root "steer.md"))
            base (str "http://127.0.0.1:" (:http-port options))
            server-a (start-server! options "server-a")]
        (try
          (await-ready! base (min deadline-ms (+ (System/currentTimeMillis) 120000)))
          (let [run (drive-run! {:start-run api/start-run!
                                 :journal-since api/journal-since
                                 :intervene api/intervene!
                                 :run-detail api/run-detail
                                 :abort api/abort!
                                 :now-ms #(System/currentTimeMillis)
                                 :sleep-ms #(Thread/sleep %)
                                 :runtime-failure
                                 #(runtime-failure-marker
                                   (bounded-tail (:raw-err server-a) 65536))}
                                {:base base :request (run-request problem (:model options))
                                 :steering steering :deadline-ms deadline-ms})]
            (let [test-result (verify-terminal-run!
                              run #(assert-six-tests!
                               (bounded-process!
                                [(:wrapper options) (:jolt options) "-M:test"]
                                {:dir project-root
                                 :env (sanitized-child-env options)
                                 :timeout-ms
                                 (remaining-timeout! deadline-ms 120000 :verify)})))
                  semantic-result (assert-trusted-semantics!
                                   (bounded-process!
                                    (trusted-verifier-command options)
                                    {:dir project-root
                                     :env (sanitized-child-env options)
                                     :timeout-ms
                                     (remaining-timeout! deadline-ms 120000
                                                         :trusted-semantics)}))
                  verified-result (merge test-result semantic-result)
                  trace-a (oscope-evidence! base (:run-id run) deadline-ms)
                  stopped-a (stop-server!
                             server-a
                             (remaining-timeout! deadline-ms 120000 :server-a-stop))]
              (publish-sanitized-logs! server-a output)
              (when-not (and (:closed? stopped-a) (:graceful? stopped-a))
                (fail! "first embedded process did not gracefully close before reopen"
                       {:phase :server-a-stop
                        :closed? (:closed? stopped-a)
                        :graceful? (:graceful? stopped-a)}))
              (let [options-b (assoc options :http-port (free-port)
                                     :nrepl-port (free-port))
                    base-b (str "http://127.0.0.1:" (:http-port options-b))
                    server-b (start-server! options-b "server-b")]
                (try
                  (await-ready! base-b
                                (min deadline-ms (+ (System/currentTimeMillis) 120000)))
                  (let [trace-b (oscope-evidence! base-b (:run-id run) deadline-ms)
                        stopped-b (stop-server!
                                   server-b
                                   (remaining-timeout! deadline-ms 120000 :server-b-stop))
                        evidence (evidence-record
                                  {:baseline baseline :model-readiness model-readiness
                                   :request (run-request problem (:model options))
                                   :run run :test-result verified-result
                                   :stopped-a stopped-a :trace-a trace-a
                                   :stopped-b stopped-b :trace-b trace-b})]
                    (publish-sanitized-logs! server-b output)
                    (when-not (and (:closed? stopped-b) (:graceful? stopped-b))
                      (fail! "fresh reopen process did not close cleanly"
                             {:phase :server-b-stop
                              :closed? (:closed? stopped-b)
                              :graceful? (:graceful? stopped-b)}))
                    (write-evidence! output evidence)
                    evidence)
                  (finally
                    (cleanup-server! server-b output))))))

          (finally
            (cleanup-server! server-a output)))))))

(defn -main [& args]
  (let [evidence (run-demo! (parse-options args))]
    (println (json/write-str evidence :escape-slash false))))
