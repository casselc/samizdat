;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.js2-project-run-test
  "The JS2 semantic EXECUTION, inside the bounded evaluator.

  Ordinary tests load this namespace with no SCI and execute only the explicit
  skip assertion; `bin/js2 bounded` selects the exact pinned runtime and sets
  SAMIZDAT_BOUNDED_TEST=1. The environment's own contract — the isolation, the
  request validation, the timeout cleanup — is
  samizdat.js2-project-env-test; what is pinned HERE is everything between the
  model and that environment.

  Four claims, in the order they matter:

  AUTHORITY. `:project/run` is reachable under :agent/project-execute and
  under nothing else. The two profiles that existed before JS2 have exactly
  the maxima they had, and a develop binding calling project/run is denied by
  the runtime — not by a check this side could forget to write. The usual
  intersection still applies in both directions, and an attenuated execute
  binding is a develop binding in every observable respect, including the
  sentence its orientation renders.

  RECEIPTS. An execution appends its durable intent BEFORE anything runs and
  its outcome after, carrying the exact request and the exact result, exactly
  as a mutation does.

  REPLAY. A reconstruction consumes the recorded execution receipt and
  launches NOTHING. This is the claim JS2 exists to make: the execution
  provider's invocation counter does not move, a mismatched argv fails closed,
  and an unconsumed execution receipt fails closed.

  INDEPENDENCE. `done` cannot see a project/run. The controller's record of
  what a run changed reads :project/edit receipts and nothing else, so a green
  execution can neither become a changed path nor shorten the verifier's work."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.surface :as surface]
            [samizdat.agent.tools.base :as tools-base]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.security.project-execution-provider :as pep]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as store]
            [samizdat.store.inference :as inference]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as workflow]))

(def bounded? (= "1" (jolt.host/getenv "SAMIZDAT_BOUNDED_TEST")))

(defn- api []
  (when bounded?
    {:bind! (requiring-resolve 'samizdat.evaluator/bind!)
     :describe (requiring-resolve 'samizdat.evaluator/describe)
     :evaluate! (requiring-resolve 'samizdat.evaluator/evaluate-recorded!)
     :persist! (requiring-resolve 'samizdat.evaluator/persist-binding!)
     :reconstruct! (requiring-resolve 'samizdat.evaluator/reconstruct!)
     :rebuild! (requiring-resolve 'samizdat.evaluator/rebuild!)
     :complete (requiring-resolve 'samizdat.evaluator/complete)
     :doc (requiring-resolve 'samizdat.evaluator/doc)
     :orientation (requiring-resolve 'samizdat.evaluator/trusted-orientation)
     :context-spec (requiring-resolve 'samizdat.evaluator/context-spec)
     :execute-caps (requiring-resolve 'samizdat.evaluator/execute-capabilities)
     :develop-caps (requiring-resolve 'samizdat.evaluator/develop-capabilities)
     :read-caps (requiring-resolve 'samizdat.evaluator/profile-capabilities)
     :default-timeout (requiring-resolve 'samizdat.evaluator/default-timeout-ms)
     :execute-timeout (requiring-resolve 'samizdat.evaluator/execute-timeout-ms)}))

(defmacro with-root [[root conn] & body]
  `(let [~root (str (fs/create-temp-dir {:prefix "samizdat-js2-run-"}))
         ~conn (db/open! ":memory:")]
     (try ~@body
          (finally (db/close ~conn) (fs/delete-tree ~root)))))

(defn- seed! [root]
  (fs/create-dirs (str root "/src/pe"))
  (spit (str root "/src/pe/core.clj") "(ns pe.core)\n(defn two [] 2)\n"))

(defn- error-data
  "The ex-data anywhere on the cause chain that carries an error kind — SCI
  wraps host-operation failures around the real one."
  [e ks]
  (loop [e e n 0]
    (cond (or (nil? e) (> n 8)) nil
          (some #(contains? (ex-data e) %) ks) (ex-data e)
          :else (recur (ex-cause e) (inc n)))))

(defn- eval-error
  "Run one eval expected to fail and return the ex-data carrying one of `ks`
  from anywhere on the cause chain. A success or a failure without one of
  those keys returns a marker, so both produce a precise assertion failure."
  [ev! source ks]
  (try (ev! source) ::no-error
       (catch Throwable e (or (error-data e ks) ::no-data))))

(def execute-caps-all
  #{:project/read :project/list :project/search :project/stat
    :project/edit :project/run})

(defn- dispatcher
  "An `evaluate!` wrapper that carries the M3 causal chain every durable
  evaluation needs: the InferenceEpoch of the model call and one
  InferenceInvocation per dispatch. Every eval below goes through it, which is
  also what makes each execution receipt attributable to an exact provider
  call."
  [{:keys [evaluate!]} conn run-id binding]
  ;; One epoch per dispatcher, not per run: a resume mints a new epoch for the
  ;; same run, which is exactly what an InferenceEpoch is.
  (let [epoch-id (str "epoch:" (random-uuid))
        n (atom 0)]
    (inference/begin!
     conn {:id epoch-id :run-id (str run-id) :branch-id "B1" :turn 1
           :provider :stub :model "m"
           :binding-id (:binding/id binding)
           :spec-id (get-in binding [:spec :spec/coordinate])
           :runtime (get-in binding [:spec :runtime-coordinate])})
    (fn [source & [opts]]
      (let [i (swap! n inc)
            invocation (:id (inference/invoke!
                             conn {:id (str "invocation:" epoch-id "-" i)
                                   :epoch-id epoch-id :run-id (str run-id)
                                   :branch-id "B1" :turn i}))]
        (evaluate! conn binding source
                   (merge {:inference-epoch-id epoch-id
                           :inference-invocation-id invocation}
                          opts))))))

(defn- execute-binding
  "One :agent/project-execute binding on a real run, persisted, with a
  dispatcher over it. The run row is real because the durable binding is a
  foreign key into it: a binding with no run is not a thing this harness can
  have."
  [api conn root label & [caps]]
  (let [{:keys [bind! persist!]} api
        caps (or caps execute-caps-all)
        run-id (runs/start-run! conn {:problem label :max-turns 3})
        b (persist! conn run-id
                    (bind! root run-id {:profile :agent/project-execute
                                        :requested caps
                                        :controller-authorized caps}))]
    [run-id b (dispatcher api conn run-id b)]))

;; A stub execution provider. Every test that is about the evaluator rather
;; than about machines runs against this, and COUNTS its calls — because "how
;; many times did the environment actually launch" is the question replay has
;; to answer.
(defn- stub-result [argv n]
  {:status :completed :exit 0 :invocation n :argv (vec argv) :cwd "."
   :timeout-ms 1000 :duration-ms 7
   :environment "sha256:env" :input "sha256:input"
   :stdout {:text "ok\n" :bytes 3 :truncated? false}
   :stderr {:text "" :bytes 0 :truncated? false}
   :disposition :terminated})

(defmacro with-stub-provider
  "Bind `launches` to an atom counting REAL provider runs for the body."
  [[launches] & body]
  `(let [~launches (atom 0)]
     (with-redefs [pep/validate-request
                   (fn [argv# options#]
                     {:request/argv (vec argv#)
                      :request/cwd (or (:cwd options#) ".")
                      :request/timeout-ms (or (:timeout-ms options#) 1000)})
                   pep/run
                   (fn [_# request#]
                     (stub-result (:request/argv request#) (swap! ~launches inc)))]
       ~@body)))

;; ═══════════════════════════════════════════════════════════════════════════

(deftest bounded-lane-selection-is-explicit
  (if bounded?
    (is (some? (requiring-resolve 'samizdat.evaluator/bind!)))
    (is (nil? (try (requiring-resolve 'samizdat.evaluator/bind!)
                   (catch Throwable _ nil)))
        "ordinary test classpath does not load the SCI-dependent evaluator")))

;; ═══════════════════════════════════════════════════════════════════════════
;; A / B. Authority — the old profiles are untouched and the new one is exact.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest the-profiles-that-existed-before-js2-are-byte-for-byte-what-they-were
  (when bounded?
    (let [{:keys [read-caps develop-caps execute-caps]} (api)]
      (is (= #{:project/read :project/list :project/search :project/stat}
             @read-caps))
      (is (= #{:project/read :project/list :project/search :project/stat
               :project/edit}
             @develop-caps))
      (is (not (contains? @read-caps :project/run))
          "a read binding cannot execute")
      (is (not (contains? @develop-caps :project/run))
          "and NEITHER CAN A DEVELOP BINDING — execution is not a wider edit")
      (is (= (conj @develop-caps :project/run) @execute-caps)
          "project-execute is exactly develop plus the one new authority"))))

(deftest the-controller-authorizes-execution-only-under-its-own-profile
  (when bounded?
    (let [{:keys [describe]} (api)]
      (with-root [root _conn]
        (seed! root)
        (testing "the controller's own table, not userspace, fixes each profile"
          (is (= [:project/list :project/read :project/search :project/stat]
                 (:evaluator/capabilities
                  (describe (workflow/bounded-binding
                             root "wf-r"
                             {:run {:bounded {:profile :agent/project-read}}})))))
          (is (= [:project/edit :project/list :project/read :project/search
                  :project/stat]
                 (:evaluator/capabilities
                  (describe (workflow/bounded-binding
                             root "wf-d"
                             {:run {:bounded {:profile :agent/project-develop}}}))))
              "the develop activation did not gain project/run")
          (is (= [:project/edit :project/list :project/read :project/run
                  :project/search :project/stat]
                 (:evaluator/capabilities
                  (describe (workflow/bounded-binding
                             root "wf-x"
                             {:run {:bounded {:profile :agent/project-execute}}}))))))
        (testing "an unknown profile still fails closed before any binding exists"
          (is (thrown? Throwable
                       (workflow/bounded-binding
                        root "wf-bad"
                        {:run {:bounded {:profile :agent/project-anything}}}))))))))

(deftest requested-authorized-and-the-catalog-maximum-still-intersect
  (when bounded?
    (let [{:keys [context-spec execute-caps develop-caps]} (api)]
      (with-root [root _conn]
        (testing "a develop PROFILE cannot acquire execution, however it is asked for"
          ;; The intersection is userspace request ∩ controller authorization ∩
          ;; CATALOG MAXIMUM ∩ compiled vocabulary, so a controller that
          ;; authorized execution under the develop profile does not get a
          ;; refusal — it gets a develop binding, which is the same answer
          ;; arrived at earlier. (The runtime refuses an over-authorization
          ;; handed to it directly; that is pinned by the Jolt authority gate.)
          (let [spec (context-spec root {:profile :agent/project-develop
                                         :requested @execute-caps
                                         :controller-authorized @execute-caps})]
            (is (not (some #{:project/run} (:context/capabilities spec))))
            (is (= (vec (sort-by str @develop-caps))
                   (:context/capabilities spec)))))
        (testing "an execute profile attenuated to develop keeps no execution"
          (let [spec (context-spec root {:profile :agent/project-execute
                                         :requested @execute-caps
                                         :controller-authorized @develop-caps})]
            (is (= (vec (sort-by str @develop-caps))
                   (:context/capabilities spec)))))
        (testing "requesting less than authorized grants less"
          (let [spec (context-spec root {:profile :agent/project-execute
                                         :requested #{:project/read}
                                         :controller-authorized @execute-caps})]
            (is (= [:project/read] (:context/capabilities spec)))))))))

(deftest the-evaluation-ceiling-follows-the-effective-authority
  ;; A project/run is a bounded execution in another machine, not computation
  ;; inside SCI, and the 30-second ceiling exists for the latter. The longer
  ;; ceiling is derived from the EFFECTIVE capability set, so it cannot be
  ;; requested — only authorized.
  (when bounded?
    (let [{:keys [context-spec execute-caps develop-caps default-timeout
                  execute-timeout]} (api)]
      (with-root [root _conn]
        (is (= @default-timeout
               (:context/timeout-ms
                (context-spec root {:profile :agent/project-develop
                                    :requested @develop-caps
                                    :controller-authorized @develop-caps}))))
        (is (= @execute-timeout
               (:context/timeout-ms
                (context-spec root {:profile :agent/project-execute
                                    :requested @execute-caps
                                    :controller-authorized @execute-caps}))))
        (is (= @default-timeout
               (:context/timeout-ms
                (context-spec root {:profile :agent/project-execute
                                    :requested @execute-caps
                                    :controller-authorized @develop-caps})))
            "attenuated away from execution, the ordinary ceiling returns")
        (is (= 5000
               (:context/timeout-ms
                (context-spec root {:profile :agent/project-execute
                                    :requested @execute-caps
                                    :controller-authorized @execute-caps
                                    :timeout-ms 5000})))
            "and the controller may still narrow it")
        (is (= @execute-timeout
               (:context/timeout-ms
                (context-spec root {:profile :agent/project-execute
                                    :requested @execute-caps
                                    :controller-authorized @execute-caps
                                    :timeout-ms (* 100 @execute-timeout)})))
            "but never widen it")))))

;; ═══════════════════════════════════════════════════════════════════════════
;; C. The surface — advertised only when authorized, and only inside eval.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest project-run-is-advertised-only-to-a-binding-that-has-it
  (when bounded?
    (let [{:keys [bind! complete doc orientation execute-caps develop-caps]} (api)]
      (with-root [root _conn]
        (seed! root)
        (let [x (bind! root "surface-x" {:profile :agent/project-execute
                                         :requested @execute-caps
                                         :controller-authorized @execute-caps})
              d (bind! root "surface-d" {:profile :agent/project-develop
                                         :requested @develop-caps
                                         :controller-authorized @develop-caps})]
          (testing "it is never a TOP-LEVEL tool on either binding"
            (is (= ["eval" "doc" "complete" "done"]
                   surface/bounded-top-level-tools))
            (is (= ["eval" "doc" "complete" "done"]
                   (:top-level (surface/of-binding x))))
            (is (not (surface/callable? (surface/of-binding x) "project/run"))))
          (testing "the execute binding advertises and documents it"
            (is (some #{"project/run"} (complete x "project/")))
            (is (= "project/run" (:name (doc x "project/run"))))
            (is (= [["argv"] ["argv" "options"]] (:arglists (doc x "project/run")))))
          (testing "the develop binding advertises nothing about it"
            (is (nil? (some #{"project/run"} (complete d "project/"))))
            (is (nil? (doc d "project/run"))))
          (testing "and each orientation describes only its own surface"
            (let [ox (orientation x) od (orientation d)]
              (is (str/includes? ox "project/run"))
              (is (str/includes? ox "project/edit"))
              (is (not (str/includes? od "project/run"))
                  "a develop binding is never told about a capability it lacks")
              (is (str/includes? od "project/edit")))))))))

(deftest the-orientation-teaches-what-a-run-is-not
  (when bounded?
    (let [{:keys [bind! orientation execute-caps]} (api)]
      (with-root [root _conn]
        (let [o (str/lower-case
                 (orientation (bind! root "orient"
                                     {:profile :agent/project-execute
                                      :requested @execute-caps
                                      :controller-authorized @execute-caps})))]
          (is (str/includes? o "private"))
          (is (or (str/includes? o "vanish") (str/includes? o "disposable")))
          (is (str/includes? o "project/edit")
              "the orientation points at the only authoritative mutation")
          (is (str/includes? o "done")
              "and says completion is decided elsewhere"))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Denial — the runtime refuses, not this side.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest a-develop-binding-calling-project-run-is-denied-and-launches-nothing
  (when bounded?
    (let [{:keys [bind! persist! evaluate! develop-caps]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [run-id (runs/start-run! conn {:problem "dev" :max-turns 3})
              b (persist! conn run-id
                          (bind! root run-id
                                 {:profile :agent/project-develop
                                  :requested @develop-caps
                                  :controller-authorized @develop-caps}))
              ev! (dispatcher (api) conn run-id b)]
          (with-stub-provider [launches]
            (is (thrown? Throwable (ev! "(project/run [\"bb\" \"-M:test\"])")))
            (is (= 0 @launches)
                "the denial is at dispatch, before any provider is reached")))))))

(deftest an-attenuated-execute-binding-is-denied-the-same-way
  (when bounded?
    (let [{:keys [evaluate!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [[_ _b ev!] (execute-binding (api) conn root "atten"
                                         #{:project/read :project/list :project/search
                                           :project/stat :project/edit})]
          (with-stub-provider [launches]
            (is (thrown? Throwable (ev! "(project/run [\"bb\"])")))
            (is (= 0 @launches))
            (testing "and its other authority is untouched"
              (is (str/includes?
                   (:value (ev! "(project/read \"src/pe/core.clj\")"))
                   "defn two")))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; F / G. The result, and the receipts around it.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest an-execution-returns-canonical-data-the-model-can-branch-on
  (when bounded?
    (let [{:keys [evaluate!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [[_ _b ev!] (execute-binding (api) conn root "shape")]
          (with-stub-provider [launches]
            (testing "one eval inspects, runs, branches and concludes"
              ;; The leverage shape §13 and §14 are about, exercised as the
              ;; single evaluation it is supposed to be.
              (let [v (:value (ev!
                               "(let [src (project/read \"src/pe/core.clj\")
                                      r (project/run [\"bb\" \"-M:test\"])]
                                  {:has-two (if (nil? src) false true)
                                   :green (= 0 (:exit r))
                                   :out (get-in r [:stdout :text])})"))]
                (is (= {:has-two true :green true :out "ok\n"} v)))
              (is (= 1 @launches) "one execution, in one model turn"))
            (testing "the options arity reaches the provider"
              (let [v (:value (ev!
                               "(:argv (project/run [\"bb\" \"x\"] {:cwd \"src\"}))"))]
                (is (= ["bb" "x"] v))))))))))

(deftest an-execution-appends-intent-before-and-outcome-after
  (when bounded?
    (let [{:keys [evaluate!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [[_ b ev!] (execute-binding (api) conn root "receipts")]
          (with-stub-provider [_]
            (ev! "(project/run [\"bb\" \"-M:test\"] {:cwd \".\"})")
            (let [rows (store/history conn (:binding/id b))
                  receipts (mapcat :receipts rows)
                  run-receipts (filter #(= :project/run (:op %)) receipts)]
              (is (= 1 (count run-receipts)))
              (let [r (first run-receipts)]
                (is (= :done (:phase r)))
                (is (= [["bb" "-M:test"] {:cwd "."}] (:args r))
                    "the exact request, argv and options, is durable")
                (is (= :completed (get-in r [:result :status])))
                (is (= 0 (get-in r [:result :exit])))
                (is (= "sha256:input" (get-in r [:result :input]))
                    "and so is the exact staged-input coordinate")
                (is (= "sha256:env" (get-in r [:result :environment]))
                    "and the exact environment that produced it")))))))))

(deftest a-refused-execution-records-its-refusal-and-not-a-success
  (when bounded?
    (let [{:keys [evaluate!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [[_ _b ev!] (execute-binding (api) conn root "refused")]
          (with-redefs [pep/validate-request (fn [argv _] {:request/argv (vec argv)})
                        pep/run (fn [_ _] {:status :refused :reason :no-manager})]
            (let [v (:value (ev! "(project/run [\"bb\"])"))]
              (is (= :refused (:status v)))
              (is (= :no-manager (:reason v))
                  "the model can tell a missing hypervisor from a failing test"))))))))

(deftest an-invalid-request-is-an-evaluation-error-and-reaches-no-provider
  (when bounded?
    (let [{:keys [evaluate!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [[_ _b ev!] (execute-binding (api) conn root "invalid")
              ran (atom 0)]
          (with-redefs [pep/run (fn [& _] (swap! ran inc) {:status :completed})]
            (is (= :run-argv-shape
                   (:samizdat.smolvm-project-env/error
                    (eval-error ev! "(project/run [])"
                                #{:samizdat.smolvm-project-env/error}))))
            (is (= :run-options-unknown
                   (:samizdat.smolvm-project-env/error
                    (eval-error ev! "(project/run [\"bb\"] {:network true})"
                                #{:samizdat.smolvm-project-env/error}))))
            (is (= 0 @ran) "validation refuses before the provider is called")))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; H. Replay — THE claim.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest a-replayed-execution-returns-the-historical-result-and-launches-nothing
  (when bounded?
    (let [{:keys [evaluate! reconstruct!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [[run-id _b ev!] (execute-binding (api) conn root "replay")]
          (with-stub-provider [launches]
            (ev! "(def r1 (project/run [\"bb\" \"-M:test\"]))")
            (ev! "(project/run [\"bb\" \"other\"] {:cwd \"src\"})")
            (is (= 2 @launches))
            (let [before @launches
                  rebuilt (reconstruct! conn run-id root)
                  ev2! (dispatcher (api) conn run-id rebuilt)]
              (is (= before @launches)
                  "RECONSTRUCTION LAUNCHED NOTHING: the receipts were consumed")
              (testing "and the historical result is what the rebuilt context holds"
                ;; Reading r1 performs no operation at all — the point is that
                ;; the def survived, holding the value the ORIGINAL execution
                ;; returned rather than a fresh one.
                (is (= {:status :completed :exit 0 :invocation 1}
                       (select-keys (:value (ev2! "r1"))
                                    [:status :exit :invocation])))
                (is (= before @launches)))
              (testing "a NEW execution after the replay does launch — this is not a freeze"
                (ev2! "(project/run [\"bb\" \"fresh\"])")
                (is (= (inc before) @launches))))))))))

(deftest a-replay-whose-execution-request-differs-fails-closed
  (when bounded?
    (let [{:keys [bind! persist! evaluate! rebuild!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [caps execute-caps-all
              run-id (runs/start-run! conn {:problem "mismatch" :max-turns 3})
              b (persist! conn run-id
                          (bind! root run-id
                                 {:profile :agent/project-execute
                                  :requested caps :controller-authorized caps}))
              ev! (dispatcher (api) conn run-id b)]
          (with-stub-provider [launches]
            (ev! "(project/run [\"bb\" \"-M:test\"])")
            (is (= 1 @launches))
            ;; Rewrite the durable source so the replayed evaluation asks for a
            ;; DIFFERENT command than the receipt records. A replay that ran it
            ;; anyway would be re-actuating history.
            (db/execute! conn ["UPDATE evaluator_evals SET source = ? WHERE binding_id = ?"
                               "(project/run [\"bb\" \"rm-rf\"])" (:binding/id b)])
            (let [before @launches]
              (is (thrown? Throwable (rebuild! conn b)))
              (is (= before @launches)
                  "a mismatched replay fails closed having launched nothing"))))))))

(deftest an-unconsumed-execution-receipt-fails-closed
  (when bounded?
    (let [{:keys [bind! persist! evaluate! rebuild!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [caps execute-caps-all
              run-id (runs/start-run! conn {:problem "unconsumed" :max-turns 3})
              b (persist! conn run-id
                          (bind! root run-id
                                 {:profile :agent/project-execute
                                  :requested caps :controller-authorized caps}))
              ev! (dispatcher (api) conn run-id b)]
          (with-stub-provider [launches]
            (ev! "(project/run [\"bb\" \"-M:test\"])")
            ;; The replayed source no longer performs the operation its
            ;; transcript holds.
            (db/execute! conn ["UPDATE evaluator_evals SET source = ? WHERE binding_id = ?"
                               "(+ 1 1)" (:binding/id b)])
            (let [before @launches]
              (is (thrown? Throwable (rebuild! conn b)))
              (is (= before @launches)))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; I. The TurnLease.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest a-stale-turn-never-starts-an-execution
  ;; The durable intent append is the semantic operation's initiation point
  ;; and it happens under the lease monitor, so a revoked lease stops the
  ;; execution BEFORE the environment is reached — not after it has booted.
  (when bounded?
    (let [{:keys [evaluate!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [[_ b ev!] (execute-binding (api) conn root "lease")
              lease (tools-base/mint-turn-lease "r" "B1" 1)
              ctx {:run-id "r" :branch {:id "B1"} :turn 1 :turn-lease lease
                   :evaluator/binding b}]
          (with-stub-provider [launches]
            (testing "an active lease permits the execution"
              (ev! "(project/run [\"bb\"])"
                 {:effect-permit!
                          (fn [initiate] (tools-base/with-turn-lease-permit! ctx initiate))})
              (is (= 1 @launches)))
            (testing "a REVOKED lease refuses it, having launched nothing"
              (tools-base/revoke-turn-lease! lease :turn-ended)
              (let [before @launches]
                (is (thrown? Throwable
                             (ev! "(project/run [\"bb\"])"
                                  {:effect-permit!
                                         (fn [initiate]
                                           (tools-base/with-turn-lease-permit! ctx initiate))})))
                (is (= before @launches)
                    "a stale project/run never starts a machine")))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; K. Independence — `done` cannot see an execution.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest a-green-execution-is-not-a-changed-path-and-not-a-completion
  ;; The controller's record of what a run changed is its :project/edit
  ;; receipts. An execution receipt sitting beside them changes neither what
  ;; the verifier verifies nor whether there is anything to verify.
  (when bounded?
    (let [{:keys [evaluate!]} (api)]
      (with-root [root conn]
        (seed! root)
        (let [[_ b ev!] (execute-binding (api) conn root "independence")
              edited #'ship/edited-paths]
          (with-stub-provider [_]
            (ev! "(project/run [\"bb\" \"-M:test\"])")
            (ev! "(project/run [\"bb\" \"-M:test\"])")
            (is (= [] (edited conn b))
                "two green executions changed nothing, so `done` has nothing to verify")
            (ev! (str "(project/edit \"src/pe/core.clj\" "
                            "(:digest (project/stat \"src/pe/core.clj\")) "
                            "\"(defn two [] 2)\" \"(defn two [] 22)\")"))
            (is (= ["src/pe/core.clj"] (edited conn b))
                "only the edit is a changed path")
            (ev! "(project/run [\"bb\" \"-M:test\"])")
            (is (= ["src/pe/core.clj"] (edited conn b))
                "and a later execution neither adds one nor removes one")))))))
