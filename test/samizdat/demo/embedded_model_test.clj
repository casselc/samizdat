;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.demo.embedded-model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing thrown?]]
            [clojure.java.io :as io]
            [samizdat.demo.embedded-model :as demo]))

(deftest child-environment-is-an-allowlist-with-explicit-local-settings
  (let [options {:base-url "http://model/v1" :model "model"
                 :project-root "/project" :db-path "/db"
                 :http-port 31001 :nrepl-port 31002
                 :libchdb "/libchdb.so" :durable-root "/durable"}
        inherited {"HOME" "/home/demo"
                   "JOLT_FIBER_TRACE_LIMIT" "64"
                   "LANGFUSE_PUBLIC_KEY" "public-secret"
                   "OTEL_EXPORTER_OTLP_HEADERS" "Authorization=private"
                   "OPENAI_API_KEY" "provider-secret"}
        getenv #(get inherited %)
        env (demo/sanitized-child-env options getenv)]
    (is (= "local" (get env "HARNESS_PROVIDER")))
    (is (= "off" (get env "SAMIZDAT_TELEMETRY_CONTENT")))
    (is (= "/libchdb.so" (get env "JOLT_CHDB_LIB")))
    (is (not (contains? env "JOLT_FIBER_TRACE_LIMIT"))
        "non-server children do not inherit the diagnostic knob")
    (doseq [secret ["LANGFUSE_PUBLIC_KEY" "LANGFUSE_SECRET_KEY"
                    "OTEL_EXPORTER_OTLP_HEADERS" "OPENAI_API_KEY"
                    "DEEPSEEK_API_KEY" "ZHIPU_API_KEY"]]
      (is (not (contains? env secret)) secret))))

(deftest server-child-environment-propagates-only-a-valid-fiber-trace-limit
  (let [options {:base-url "http://model/v1" :model "model"
                 :project-root "/project" :db-path "/db"
                 :http-port 31001 :nrepl-port 31002
                 :libchdb "/libchdb.so" :durable-root "/durable"}
        inherited {"HOME" "/home/demo"
                   "JOLT_FIBER_TRACE_LIMIT" "4096"
                   "LANGFUSE_HOST" "https://langfuse.invalid"
                   "LANGFUSE_SECRET_KEY" "langfuse-secret"
                   "SAMIZDAT_LANGFUSE_OTLP_HEADERS" "Authorization=private"
                   "SAMIZDAT_LANGFUSE_OTLP_HEADERS_ENV" "PRIVATE_HEADERS"
                   "OTEL_EXPORTER_OTLP_ENDPOINT" "https://collector.invalid"
                   "OTEL_EXPORTER_OTLP_HEADERS" "Authorization=private"
                   "DEEPSEEK_API_KEY" "provider-secret"}
        env (demo/server-child-env options #(get inherited %))]
    (is (= "4096" (get env "JOLT_FIBER_TRACE_LIMIT")))
    (doseq [secret ["LANGFUSE_HOST" "LANGFUSE_SECRET_KEY"
                    "SAMIZDAT_LANGFUSE_OTLP_HEADERS"
                    "SAMIZDAT_LANGFUSE_OTLP_HEADERS_ENV"
                    "OTEL_EXPORTER_OTLP_ENDPOINT"
                    "OTEL_EXPORTER_OTLP_HEADERS"
                    "DEEPSEEK_API_KEY"]]
      (is (not (contains? env secret)) secret)))
  (let [options {:base-url "http://model/v1" :model "model"
                 :project-root "/project" :db-path "/db"
                 :http-port 31001 :nrepl-port 31002
                 :libchdb "/libchdb.so" :durable-root "/durable"}]
    (is (not (contains? (demo/server-child-env options (constantly nil))
                        "JOLT_FIBER_TRACE_LIMIT")))
    (doseq [invalid ["" "0" "-1" "+1" "01" "1.5" " 1" "4097"
                     "999999999999999999999999"]]
      (is (thrown? Throwable
                   (demo/server-child-env
                    options #(when (= % "JOLT_FIBER_TRACE_LIMIT") invalid)))
          invalid))))

(deftest expected-jolt-revision-is-explicit-and-exact
  (is (= "aea91781" demo/default-expected-jolt-rev))
  (is (= "aea91781"
         (:expected-jolt-rev (demo/parse-options [] (constantly nil)))))
  (is (= "a7d07660"
         (:expected-jolt-rev
          (demo/parse-options
           [] #(when (= % demo/expected-jolt-rev-env) "a7d07660")))))
  (is (= "jolt v0.8.6-10-ga7d07660"
         (:out (demo/assert-jolt-revision!
                "a7d07660" {:out "jolt v0.8.6-10-ga7d07660"}))))
  (is (thrown? Throwable
               (demo/assert-jolt-revision!
                "aa0e71f" {:out "jolt v0.8.6-10-ga7d07660"})))
  (is (thrown? Throwable
               (demo/assert-jolt-revision! "" {:out "jolt v0.8.6"}))))

(deftest exhausted-tasks-receive-independent-verification-without-relabeling
  (doseq [status ["completed" "exhausted"]]
    (let [calls (atom 0)
          run {:status status :run-id "r"}
          result (demo/verify-terminal-run! run #(do (swap! calls inc) :verified))
          evidence (demo/evidence-record {:run run})]
      (is (= :verified result))
      (is (= 1 @calls))
      (is (= status (get-in evidence [:run :status])))))
  (doseq [status ["failed" "aborted" "running" "interrupted" "abandoned"]]
    (let [calls (atom 0)]
      (is (thrown? Throwable
                   (demo/verify-terminal-run! {:status status}
                                             #(swap! calls inc))))
      (is (zero? @calls))))
  (let [failure (ex-info "fixture failed" {})]
    (is (identical? failure
                    (try (demo/verify-terminal-run! {:status "exhausted"}
                                                   #(throw failure))
                         (catch Throwable caught caught))))))

(deftest exhausted-run-drives-fixture-telemetry-close-and-fresh-readback-gates
  (let [root (java.nio.file.Files/createTempDirectory
              "samizdat-demo-policy-" (make-array java.nio.file.attribute.FileAttribute 0))
        calls (atom [])
        trace {:trace-id "t" :families demo/expected-span-families
               :content-enabled false}
        options {:output (str root) :timeout-ms 60000
                 :wrapper "/wrapper" :jolt "/jolt"
                 :expected-jolt-rev "aea91781"}]
    (try
      (let [result
            (with-redefs-fn
              {#'demo/prepare-project! (fn [_] "baseline")
               #'clojure.core/slurp (constantly "fixture instruction")
               #'demo/bounded-process! (fn [command _]
                                        (cond
                                          (= "--version" (last command))
                                          {:exit 0 :out "jolt v0.8.6-gaea91781"}
                                          (= demo/trusted-verifier-expression (last command))
                                          (do (swap! calls conj :semantics)
                                              {:exit 0 :out "[0 9 16 0 27 -8]\n"})
                                          :else
                                          (do (swap! calls conj :fixture)
                                              {:exit 0 :out "Ran 6 tests. 6 assertions passed, 0 failures, 0 errors."})))
               #'demo/start-server! (fn [options label]
                                     (swap! calls conj [label (:durable-root options)])
                                     {:label label :terminal (atom false)})
               #'demo/await-ready! (fn [& _] nil)
               #'demo/drive-run! (fn [& _] {:run-id "run" :status "exhausted" :turns 12 :steering-count 1})
               #'demo/oscope-evidence! (fn [_ run-id _]
                                        (swap! calls conj [:trace run-id]) trace)
               #'demo/stop-server! (fn [server _]
                                    (swap! calls conj [:stop (:label server)])
                                    (reset! (:terminal server) true)
                                    {:closed? true :graceful? true :terminal? true})
               #'demo/publish-sanitized-logs! (fn [& _] nil)
               #'demo/write-evidence! (fn [& _] nil)}
              #(demo/run-demo! options))
            starts (filter vector? @calls)]
        (is (= "exhausted" (get-in result [:run :status])))
        (is (= 6 (get-in result [:verification :tests])))
        (is (true? (get-in result [:verification :trusted-semantic-check])))
        (is (= 6 (get-in result [:verification :semantic-case-count])))
        (is (= demo/expected-span-families (get-in result [:process-a :trace :families])))
        (is (= trace (get-in result [:process-b :trace])))
        (is (false? (:external-export result)))
        (is (= ["server-a" :fixture :semantics :trace :stop "server-b" :trace :stop]
               (mapv #(if (vector? %) (first %) %) @calls)))
        (is (= (second (first starts)) (second (nth starts 3)))
            "fresh process uses the same Durable root"))
      (finally
        (doseq [file (reverse (file-seq (io/file (str root))))]
          (java.nio.file.Files/deleteIfExists (.toPath file)))))))

(deftest trusted-verifier-is-host-owned-and-nonvacuous
  (is (= ["/wrapper" "/jolt" "-Srepro" "-e" demo/trusted-verifier-expression]
         (demo/trusted-verifier-command {:wrapper "/wrapper" :jolt "/jolt"})))
  (is (= {:trusted-semantic-check true :semantic-case-count 6}
         (demo/assert-trusted-semantics!
          {:exit 0 :out "[0 9 16 0 27 -8]\n" :err ""})))
  ;; Six passing model-authored assertions do not establish arithmetic truth.
  (doseq [square [(fn [x] (* 2 x)) (constantly 0)]]
    (is (= 6 (:tests (demo/assert-six-tests!
                     {:exit 0 :out "Ran 6 tests. 6 assertions passed, 0 failures, 0 errors."}))))
    (is (thrown? Throwable
                 (demo/assert-trusted-semantics!
                  {:exit 0 :out (pr-str (into (mapv square [0 3 -4])
                                             (mapv #(* % % %) [0 3 -2])))}))))
  (doseq [result [{:exit 1 :out "" :err "missing cube"}
                  {:exit 0 :out "[0 9 16]"}
                  {:exit 0 :out "[0 9 16 0 27 -8 1]"}
                  {:exit 0 :out "noise\n[0 9 16 0 27 -8]"}
                  {:exit 0 :out "[0 9 16 0 27 -8]" :err "unexpected"}
                  {:exit 1 :out "[0 9 16 0 27 -8]"}
                  {:timeout true :out "[0 9 16 0 27 -8]"}]]
    (is (thrown? Throwable (demo/assert-trusted-semantics! result)))))

(deftest command-and-request-pin-the-reviewed-bounds
  (is (= ["/wrapper" "/jolt"
          "-M:telemetry:embedded-telemetry:embedded-serve"
          "--" "--durable-root" "/durable"]
         (demo/embedded-command {:wrapper "/wrapper" :jolt "/jolt"
                                 :durable-root "/durable"})))
  (is (= {:problem "fix it" :model "qwen" :max_turns 14
          :token_budget 120000 :beam_width 1 :max_total_branches 1}
         (demo/run-request "fix it" "qwen"))))

(deftest run-driver-steers-exactly-once-after-first-turn
  (let [clock (atom 1000)
        details (atom ["running" "completed"])
        interventions (atom [])
        aborts (atom 0)
        result
        (demo/drive-run!
         {:start-run (fn [_ request]
                       (is (= 1 (:max_total_branches request)))
                       {:ok true :body {:run_id "run-1"}})
          :journal-since (fn [_ _ cursor _]
                           {:ok true :body {:next (inc cursor)
                                            :events (if (zero? cursor)
                                                      [{:kind "turn"}]
                                                      [])}})
          :intervene (fn [_ run-id directive]
                       (swap! interventions conj [run-id directive])
                       {:ok true :body {:status "pending"}})
          :run-detail (fn [_ _]
                        (let [status (first @details)]
                          (swap! details subvec 1)
                          {:ok true :body {:run {:status status}}}))
          :abort (fn [& _] (swap! aborts inc))
          :now-ms #(deref clock)
          :sleep-ms #(swap! clock + %)}
         {:base "http://samizdat" :request (demo/run-request "p" "m")
          :steering "add cube" :deadline-ms 10000 :poll-ms 10})]
    (is (= "completed" (:status result)))
    (is (= 1 (:turns result)))
    (is (= 1 (:steering-count result)))
    (is (= [["run-1" {:kind "message" :payload "add cube"}]]
           @interventions))
    (is (zero? @aborts))))

(deftest run-driver-aborts-on-a-runtime-fatal-before-the-overall-deadline
  (let [aborts (atom 0)
        journal-polls (atom 0)]
    (is (thrown? Throwable
                 (demo/drive-run!
                  {:start-run (fn [_ _] {:ok true :body {:run_id "run-fatal"}})
                   :journal-since (fn [& _]
                                    (swap! journal-polls inc)
                                    {:ok true :body {:next 0 :events []}})
                   :intervene (fn [& _] {:ok true})
                   :run-detail (fn [& _]
                                 {:ok true :body {:run {:status "running"}}})
                   :abort (fn [& _] (swap! aborts inc) {:ok true})
                   :runtime-failure (constantly :jolt-fiber-state)
                   :now-ms (constantly 1000)
                   :sleep-ms (fn [_])}
                  {:base "http://local" :request {} :steering "s"
                   :deadline-ms 100000})))
    (is (= 1 @aborts) "the known-dead task is aborted exactly once")
    (is (zero? @journal-polls)
        "the harness does not poll a false running row until the deadline")))

(deftest run-driver-refuses-a-terminal-run-before-steering
  (let [error (try
                (demo/drive-run!
                 {:start-run (fn [& _] {:ok true :body {:run_id "run-early"}})
                  :journal-since (fn [& _] {:ok true :body {:next 0 :events []}})
                  :intervene (fn [& _] (throw (AssertionError. "must not steer")))
                  :run-detail (fn [& _]
                                {:ok true :body {:run {:status "completed"}}})
                  :abort (fn [& _] nil) :now-ms (constantly 0)
                  :sleep-ms (fn [_] nil)}
                 {:base "http://samizdat" :request {} :steering "late"
                  :deadline-ms 1000})
                nil
                (catch Throwable caught caught))]
    (is (= :steer (:phase (ex-data error))))))

(deftest shutdown-proof-requires-one-confirmed-close-and-signal-exit
  (let [closed "embedded telemetry stopped {:status :closed, :phase :closed}"]
    (is (:graceful? (demo/shutdown-evidence true 143 closed)))
    (doseq [[finished? exit logs] [[false nil closed] [true 0 closed] [true 143 "embedded telemetry stopped {:status :draining}"] [true 143 (str closed "\n" closed)] [true 143 (str closed "\nSamizdat application stop failed")]]]
      (is (false? (:graceful? (demo/shutdown-evidence finished? exit logs)))))))

(deftest merged-and-original-structural-runtime-failures-abort
  (is (= :jolt-fiber-state
         (demo/runtime-failure-marker "JOLT_FIBER_INVARIANT where=enqueue")))
  (is (= :jolt-fiber-state
         (demo/runtime-failure-marker
          "Exception in jolt-fiber-run: fiber in unexpected state")))
  (is (nil? (demo/runtime-failure-marker "ordinary server milestone"))))

(deftest exact-independent-test-summary-is-not-weakened
  (is (= {:exit 0 :tests 6 :assertions 6 :failures 0 :errors 0}
         (demo/assert-six-tests!
          {:exit 0 :out "\nRan 6 tests. 6 assertions passed, 0 failures, 0 errors.\n"})))
  (is (= 6 (:tests
            (demo/assert-six-tests!
             {:exit 0
              :out "Ran 6 tests containing 6 assertions.\n0 failures, 0 errors.\n"
              :err ""}))))
  (doseq [out ["Ran 5 tests containing 6 assertions.\n0 failures, 0 errors."
               "Ran 6 tests containing 7 assertions.\n0 failures, 0 errors."
               "Ran 6 tests containing 6 assertions.\n1 failures, 0 errors."
               "Ran 5 tests. 6 assertions passed, 0 failures, 0 errors."
               "Ran 6 tests. 7 assertions passed, 0 failures, 0 errors."
               "Ran 6 tests. 6 assertions passed, 1 failures, 0 errors."
               "Ran 6 tests. 6 assertions passed, 0 failures, 1 errors."
               "Ran 6 tests containing 6 assertions.\n0 failures, 0 errors.\nRan 5 tests containing 5 assertions.\n0 failures, 0 errors."
               "Ran 6 tests containing 6 assertions.\n0 failures, 0 errors.\n1 failures, 0 errors."]]
    (is (thrown? Throwable
                 (demo/assert-six-tests! {:exit 0 :out out :err ""}))))
  (doseq [result [{:exit 1} {:exit 0 :timeout true}]]
    (is (thrown? Throwable
                 (demo/assert-six-tests!
                  (assoc result :out "Ran 6 tests. 6 assertions passed, 0 failures, 0 errors."))))))

(deftest supported-oscope-pages-prove-run-family-and-content-contract
  (let [trace-id "0123456789abcdef0123456789abcdef"
        index (str "<a href=\"/oscope/telemetry/traces/" trace-id "\">trace</a>")
        detail (str "<dt>samizdat.run.id</dt><dd>run-id-7</dd> "
                    (str/join " "
                              (map #(str "<span class=\"otel-span-name\">" % "</span>")
                                   demo/expected-span-families)))
        evidence (demo/assert-trace! "run-id-7" index
                                     (fn [id]
                                       (is (= trace-id id))
                                       {:status 200 :body detail}))]
    (is (= trace-id (:trace-id evidence)))
    (is (false? (:content-enabled evidence))))
  (testing "content attributes fail closed"
    (let [trace-id "fedcba9876543210fedcba9876543210"
          index (str "/oscope/telemetry/traces/" trace-id)
          detail (str "<dt>samizdat.run.id</dt><dd>run-id-8</dd> "
                      (str/join " "
                                (map #(str "<span class=\"otel-span-name\">" % "</span>")
                                     demo/expected-span-families))
                      " langfuse.observation.input")]
      (is (thrown? Throwable
                   (demo/assert-trace! "run-id-8" index
                                       (constantly {:status 200 :body detail})))))))

(deftest trace-correlation-requires-the-run-id-attribute-not-a-loose-substring
  (let [trace-id "0123456789abcdef0123456789abcdef"
        index (str "/oscope/telemetry/traces/" trace-id)
        spans (str/join " "
                        (map #(str "<span class=\"otel-span-name\">" % "</span>")
                             demo/expected-span-families))]
    (is (thrown? Throwable
                 (demo/assert-trace! "run-id-loose" index
                                     (constantly
                                      {:status 200
                                       :body (str "elsewhere run-id-loose " spans)}))))))

(deftest evidence-log-redaction-is-bounded-to-secret-shaped-values
  (let [sanitized (demo/sanitize-log
                   "Authorization: Basic cGs6c2s= api_key=abc token:xyz safe=ok")]
    (is (not (str/includes? sanitized "cGs6c2s=")))
    (is (not (str/includes? sanitized "abc")))
    (is (not (str/includes? sanitized "xyz")))
    (is (str/includes? sanitized "safe=ok"))))

(deftest bounded-log-tail-covers-whole-file-and-tail-only-branches
  (let [file (java.io.File/createTempFile "samizdat-demo-tail-" ".log")]
    (try
      (spit file "discard-this-tail-only")
      (is (= "discard-this-tail-only"
             (#'demo/bounded-tail (str file) 65536)))
      (is (= "tail-only"
             (#'demo/bounded-tail (str file) 9)))
      (finally
        (java.nio.file.Files/deleteIfExists (.toPath file))))))

(deftest failed-sanitized-log-publication-still-removes-the-raw-log
  (let [raw (java.io.File/createTempFile "samizdat-demo-raw-" ".log")
        output-file (java.io.File/createTempFile "samizdat-demo-output-" ".tmp")]
    (try
      (spit raw "Authorization: Basic credential-shaped-value")
      (is (thrown? Throwable
                   (#'demo/publish-sanitized-logs!
                    {:label "server-a"
                     :terminal (atom true)
                     :raw-out (str raw)
                     :raw-err (str raw ".missing")}
                    output-file)))
      (is (not (.exists raw)))
      (finally
        (java.nio.file.Files/deleteIfExists (.toPath raw))
          (java.nio.file.Files/deleteIfExists (.toPath output-file))))))

(deftest invalid-owned-pids-never-reach-a-native-signal
  (let [signals (atom [])]
    (with-redefs-fn
      {#'demo/c-kill (fn [& args] (swap! signals conj args) 0)}
      #(doseq [pid [nil 0 -1 -42 2147483648 "101"]]
         (is (= -1 (#'demo/signal! pid 15)))))
    (doseq [pid [nil 0 -1 -42 2147483648]]
      (with-redefs-fn
        {#'demo/owned-pid (constantly pid)
         #'demo/wait-for-child! (constantly false)
         #'demo/signal! (fn [& args] (swap! signals conj args) 0)}
        #(do
           (is (false? (:terminal? (#'demo/force-reap! {}))))
           (is (false? (:terminal?
                        (#'demo/stop-server!
                         {:child {} :terminal (atom false)
                          :raw-out "/missing-demo-stdout"
                          :raw-err "/missing-demo-stderr"} 1)))))))
    (is (nil? (#'demo/owned-pid {:proc nil}))
        "PID acquisition exceptions do not become a broadcast sentinel")
    (is (empty? @signals))))

(deftest direct-child-reap-requires-confirmed-terminal-wait
  (doseq [[waits expected-signals terminal?]
          [[[true] [] true]
           [[false true] [[101 15]] true]
           [[false false true] [[101 15] [101 9]] true]
           [[false false false] [[101 15] [101 9]] false]]]
    (let [pending (atom waits) signals (atom [])]
      (with-redefs-fn
        {#'demo/owned-pid (constantly 101)
         #'demo/signal! (fn [& args] (swap! signals conj (vec args)) 0)
         #'demo/wait-for-child! (fn [& _]
                                 (let [result (first @pending)]
                                   (swap! pending rest) result))}
        #(is (= terminal? (:terminal? (#'demo/force-reap! {})))))
      (is (= expected-signals @signals)))))

(deftest unsettled-cleanup-retains-raw-evidence-without-masking-body-failure
  (let [raw (java.io.File/createTempFile "samizdat-demo-unsettled-" ".log")
        server {:label "server-a" :raw-out (str raw)
                :raw-err (str raw ".missing") :terminal (atom false)}
        original (ex-info "body failed" {})]
    (try
      (spit raw "public retained evidence")
      (with-redefs-fn
        {#'demo/stop-server! (fn [& _] (throw (ex-info "wait unavailable" {})))
         #'clojure.core/println (fn [& _] (throw (ex-info "report unavailable" {})))}
        #(do
           (is (false? (:terminal? (#'demo/cleanup-server! server "/unused"))))
           (is (identical? original
                           (try (try (throw original)
                                     (finally (#'demo/cleanup-server! server "/unused")))
                                (catch Throwable failure failure))))))
      (#'demo/publish-sanitized-logs! server "/unused")
      (is (.exists raw))
      (is (= "public retained evidence" (slurp raw)))
      (finally (java.nio.file.Files/deleteIfExists (.toPath raw))))))

(deftest unconfirmed-server-a-close-prevents-fresh-reopen
  (let [output (java.nio.file.Files/createTempDirectory
                "samizdat-demo-unconfirmed-"
                (make-array java.nio.file.attribute.FileAttribute 0))
        starts (atom [])]
    (try
      (with-redefs-fn
        {#'demo/prepare-project! (constantly "baseline")
         #'clojure.core/slurp (constantly "public fixture")
         #'demo/bounded-process! (fn [command _]
                                  {:exit 0 :out (cond
                                                (= "--version" (last command)) "jolt gaea91781"
                                                (= demo/trusted-verifier-expression (last command)) "[0 9 16 0 27 -8]"
                                                :else "Ran 6 tests. 6 assertions passed, 0 failures, 0 errors.")})
         #'demo/start-server! (fn [_ label]
                               (swap! starts conj label)
                               {:label label :terminal (atom false)})
         #'demo/await-ready! (fn [& _])
         #'demo/drive-run! (fn [& _] {:run-id "run" :status "completed"})
         #'demo/oscope-evidence! (fn [& _] {})
         #'demo/stop-server! (fn [& _] {:closed? false :graceful? false :terminal? false})}
        #(is (thrown? Throwable
                      (demo/run-demo! {:output (str output) :timeout-ms 60000
                                       :expected-jolt-rev "aea91781"
                                       :wrapper "/wrapper" :jolt "/jolt"}))))
      (is (= ["server-a"] @starts))
      (finally
        (doseq [file (reverse (file-seq (io/file (str output))))]
          (java.nio.file.Files/deleteIfExists (.toPath file)))))))

(deftest bounded-evidence-preserves-the-steering-and-reopen-proof
  (let [evidence (demo/evidence-record
                  {:baseline "new-sha"
                   :request (assoc (demo/run-request "private problem" "qwen")
                                   :unbounded "omit")
                   :run {:run-id "r1" :status "completed" :turns 6
                         :steering-count 1 :detail "omit"}
                   :test-result {:tests 6 :assertions 6 :failures 0 :errors 0}
                   :stopped-a {:closed? true :graceful? true}
                   :trace-a {:trace-id "t1"}
                   :stopped-b {:closed? true :graceful? true}
                   :trace-b {:trace-id "t1"}})]
    (is (= {:run-id "r1" :status "completed" :turns 6 :steering-count 1}
           (:run evidence)))
    (is (= true (get-in evidence [:process-a :stopped-before-reopen])))
    (is (= true (get-in evidence [:process-b :no-model-call])))
    (is (not (contains? (:request evidence) :problem)))
    (is (not (contains? (:request evidence) :unbounded)))))
