;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evaluator-test
  "The deterministic bounded lane for M1 and M2. Ordinary tests load this
  namespace with no SCI and execute only the explicit skip assertion;
  bin/js1-m1 test selects the exact pinned runtime and sets
  SAMIZDAT_BOUNDED_TEST=1.

  The M1 lanes pin the read profile's closure: symlink confinement, bounded
  strict reads, deterministic digests, timeout ceiling semantics, and the
  workflow profile gate. The M2 lanes pin the :agent/project-develop profile's
  one semantic mutation: anchored success and digest chaining with exact
  receipt intent/outcome, conflict and confinement refusals with zero writes,
  :absent create without directory creation, actuation persistence across a
  later SCI failure, receipt-driven replay that never re-writes, and the
  workflow's develop activation with controller-fixed authority."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.base :as base]
            [samizdat.security.policy :as policy]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as store]
            [samizdat.store.inference :as inference]
            [samizdat.store.journal :as journal]
            [samizdat.agent.surface :as surface]
            [samizdat.agent.tools :as tools]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as workflow]))

(def bounded? (= "1" (jolt.host/getenv "SAMIZDAT_BOUNDED_TEST")))

(defn- evaluator-api []
  (when bounded?
    {:bind! (requiring-resolve 'samizdat.evaluator/bind!)
     :describe (requiring-resolve 'samizdat.evaluator/describe)
     :evaluate! (requiring-resolve 'samizdat.evaluator/evaluate-recorded!)
      :persist! (requiring-resolve 'samizdat.evaluator/persist-binding!)
      :reconstruct! (requiring-resolve 'samizdat.evaluator/reconstruct!)
     :rebuild! (requiring-resolve 'samizdat.evaluator/rebuild!)
     :complete (requiring-resolve 'samizdat.evaluator/complete)
     :doc (requiring-resolve 'samizdat.evaluator/doc)
     :leverage (requiring-resolve 'samizdat.evaluator/leverage)
     :context-spec (requiring-resolve 'samizdat.evaluator/context-spec)
      :default-timeout (requiring-resolve 'samizdat.evaluator/default-timeout-ms)}))

(defmacro with-root [[root conn] & body]
  `(let [~root (str (fs/create-temp-dir {:prefix "samizdat-m1-"}))
         ~conn (db/open! ":memory:")]
     (try ~@body
          (finally (db/close ~conn) (fs/delete-tree ~root)))))

(defn- seed-project! [root]
  (fs/create-dirs (str root "/src/samizdat"))
  (spit (str root "/src/samizdat/a.clj") "(ns a)\n(defn alpha [] 1)\n")
  (spit (str root "/src/samizdat/b.clj") "(ns b)\n(defn beta [] 2)\n"))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable e (ex-data e))))

;; ─── M1 closure regression helpers ─────────────────────────────────────────
;; Everything below drives the evaluator only through its public surface
;; (bindings + eval sources), so each assertion exercises the exact code the
;; pinned lane loads.

(defn- error-data
  "The ex-data map anywhere on the cause chain that carries the evaluator
  error kind. SCI wraps host-operation failures in {:type :sci/error ...}
  with the evaluator failure as the cause, while direct evaluator failures
  (timeout, busy) sit at the top — both shapes resolve to one map."
  [e]
  (loop [e e n 0]
    (cond (or (nil? e) (> n 8)) nil
          (:samizdat.evaluator/error (ex-data e)) (ex-data e)
          :else (recur (ex-cause e) (inc n)))))

(defn- eval-error
  "Run one eval expected to fail and return the ex-data carrying its
  :samizdat.evaluator/error (searching the cause chain, see error-data). A
  nil ex-data means the eval failed without an evaluator error kind; an
  unexpected success returns the marker kind, so both produce precise
  assertion failures instead of NullPointerExceptions."
  [evaluate! conn binding source]
  (let [result (try {:value (evaluate! conn binding source)}
                    (catch Throwable t {:error t}))]
    (if (contains? result :value)
      {:samizdat.evaluator/error :unexpected-success :source source}
      (or (error-data (:error result))
          {:samizdat.evaluator/error :no-evaluator-error}))))

(defn- jolt-interrupted?
  "Whether a Jolt interruption (or a cause chain carrying one) stopped the
  computation — the raw caller-revocation signal that must never be relabeled
  a spec :timeout."
  [e]
  (loop [e e n 0]
    (and e (< n 8)
         (or (:jolt/interrupted (ex-data e))
             (recur (ex-cause e) (inc n))))))

(defn- ->bytes
  "Exact raw bytes from ints 0-255 (jolt's byte coercion rejects 128-255, so
  wrap them to signed here)."
  [ints]
  (byte-array (map #(byte (if (> % 127) (- % 256) %)) ints)))

(defn- write-bytes!
  "Seed exact raw bytes (ints 0-255) so malformed UTF-8 fixtures are precise."
  [path ints]
  (with-open [out (java.io.FileOutputStream. (str path))]
    (.write out (->bytes ints))))

(defn- sha256-hex
  "Independent in-test SHA-256 mirror for differential digest checks."
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))

(defn- seed-linked-project!
  "A project whose read-side surface is booby-trapped with symbolic links:
  leaf links that stay inside, dangle, or escape (relatively and absolutely),
  directory links used as intermediate components, and one real secret file
  OUTSIDE the root that every link family tries to reach."
  [base]
  (let [root (str base "/proj")]
    (fs/create-dirs (str root "/src/samizdat"))
    (fs/create-dirs (str root "/docs"))
    (fs/create-dirs (str root "/realdir"))
    (fs/create-dirs (str root "/listdir/a-dir"))
    (spit (str root "/src/samizdat/a.clj") "(ns a)\n(defn alpha [] 1)\n")
    (spit (str root "/docs/guide.md") "guide\n")
    (spit (str root "/realdir/x.txt") "NEEDLE-INSIDE\n")
    (spit (str root "/listdir/b.txt") "hello")
    (spit (str base "/secret.txt") "NEEDLE-SECRET\n")
    ;; leaf links: inside-pointing, dangling, lexically escaping, absolutely escaping
    (fs/create-sym-link (str root "/inside.clj") "src/samizdat/a.clj")
    (fs/create-sym-link (str root "/dangling") "no-such-target")
    (fs/create-sym-link (str root "/escape-rel") "../secret.txt")
    (fs/create-sym-link (str root "/escape-abs") (str base "/secret.txt"))
    ;; intermediate-component links: to a real inside dir, and to the root's parent
    (fs/create-sym-link (str root "/src-link") "src")
    (fs/create-sym-link (str root "/up-link") "..")
    ;; an inside-pointing directory link the walk must not traverse
    (fs/create-sym-link (str root "/linkdir") "realdir")
    ;; a link reported in a listing, never followed
    (fs/create-sym-link (str root "/listdir/c-link") "b.txt")
    root))

(defn- identity-for-store [description]
  {:spec-id (:evaluator/spec-id description)
   :instance-id (:evaluator/instance-id description)
   :binding-id (:evaluator/binding-id description)
   :context-spec (:evaluator/context-spec description)
   :runtime (:evaluator/runtime description)})

(deftest ordinary-lane-does-not-require-sci
  (if bounded?
    (is (some? (requiring-resolve 'samizdat.evaluator/bind!)))
    (is (nil? (try (requiring-resolve 'samizdat.evaluator/bind!)
                   (catch Throwable _ nil)))
        "ordinary test classpath does not load the SCI-dependent evaluator")))

(deftest exact-m1-evaluator-conformance
  (when bounded?
    (let [{:keys [bind! describe evaluate! rebuild! complete doc leverage]}
          (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [world (atom [])
              binding (bind! root "m1-conformance"
                             {:requested #{:project/read :project/list
                                           :project/search :project/stat :project/edit}
                              :controller-authorized #{:project/read :project/list
                                                       :project/search :project/stat}
                              :world-observer #(swap! world conj [%1 %2])})
              ids (describe binding)
              source (str "(let [entries (project/list \"src\") "
                          "relevant? (some #(= \"samizdat\" (:name %)) entries)] "
                          "(if relevant? "
                          "(->> (project/search \"defn\" {:path \"src/samizdat\"}) "
                          "(map :path) distinct (take 5) vec) []))")
              first-eval (evaluate! conn binding source)]
          (println "M1-EVALUATOR-IDENTITIES"
                   (pr-str (select-keys ids
                                        [:evaluator/spec-id :evaluator/instance-id
                                         :evaluator/binding-id :evaluator/context-spec
                                         :evaluator/runtime])))
          (is (= "inst:m1-conformance" (:evaluator/instance-id ids)))
          (is (= "bind:m1-conformance" (:evaluator/binding-id ids)))
          (is (= ["src/samizdat/a.clj" "src/samizdat/b.clj"] (:value first-eval)))
          (is (= [[:project/list ["src"]]
                  [:project/search ["defn" {:path "src/samizdat"}]]]
                 @world)
              "one eval made ordered observations and branched on the first")
          (let [record (store/load-eval conn (:eval-id first-eval))]
            (is (= :completed (:status record)))
            (is (= [:project/list :project/search] (mapv :op (:receipts record))))
            (is (= [:done :done] (mapv :phase (:receipts record)))))

          (evaluate! conn binding "(do (defn source-file? [p] (str p)) :defined)")
          (is (= "src/samizdat/a.clj"
                 (:value (evaluate! conn binding "(source-file? \"src/samizdat/a.clj\")"))))

          (is (some? (thrown-data
                      #(evaluate! conn binding "(do (def ghost 9) (unknown-call))"))))
          (is (= "ok" (:value (evaluate! conn binding "(str \"o\" \"k\")")))
              "failed eval rolled back and later committed state remains usable")
          (is (some? (thrown-data #(evaluate! conn binding "ghost")))
              "partial definition from the failed eval was not committed")

          (let [before-context (:evaluator/live-context (describe binding))
                before-world @world
                rebuilt (rebuild! conn binding)
                after (describe rebuilt)]
            (is (= (select-keys ids [:evaluator/spec-id :evaluator/instance-id
                                     :evaluator/binding-id :evaluator/context-spec
                                     :evaluator/runtime])
                   (select-keys after [:evaluator/spec-id :evaluator/instance-id
                                       :evaluator/binding-id :evaluator/context-spec
                                       :evaluator/runtime])))
            (is (not= before-context (:evaluator/live-context after))
                "fresh reconstruction allocated a new process-local SCI context")
            (is (= before-world @world) "reconstruction made zero real world calls")
            (is (= "src/samizdat/a.clj"
                   (:value (evaluate! conn rebuilt
                                     "(source-file? \"src/samizdat/a.clj\")")))
                "helper state survived whole-history reconstruction"))

          (let [facts (leverage conn binding)]
            (println "M1-LEVERAGE" (pr-str facts))
            (is (= {:evaluations 5
                    :operations-per-eval [2 0 0 0 0]
                    :multi-operation-evals 1
                    :operation-order [[:project/list :project/search] [] [] [] []]}
                   facts)))

          (let [surface ["eval" "doc" "complete" "done"
                         "project/read" "project/list" "project/search" "project/stat"]]
            (is (= surface (complete binding "")))
            (is (every? #(some? (doc binding %)) surface))
            (is (nil? (doc binding "shell")))
            (is (not (str/includes? (:trusted-orientation binding) "project/edit")))
            (is (not (str/includes? (:trusted-orientation binding) "shell"))))

          (is (= #{:project/read :project/list :project/search :project/stat}
                 (set (:evaluator/capabilities ids)))
               "userspace's edit request was attenuated by controller/profile/runtime authority"))))))

(deftest replay-refusal-ordering
  (when bounded?
    (let [{:keys [bind! describe evaluate! rebuild!]} (evaluator-api)]
      (testing "identity mismatch and pending history refuse before SCI replay allocation"
        (with-root [root conn]
          (seed-project! root)
          (let [binding (bind! root "preflight" {})
                eval-id (:eval-id (evaluate! conn binding "(project/list \"src\")"))
                context (:evaluator/live-context (describe binding))]
            (db/execute! conn ["UPDATE evaluator_evals SET runtime = 'wrong' WHERE id = ?" eval-id])
            (is (= :history-mismatch (:samizdat.evaluator/error
                                      (thrown-data #(rebuild! conn binding)))))
            (is (= context (:evaluator/live-context (describe binding))))
            (db/execute! conn ["UPDATE evaluator_evals SET runtime = ? WHERE id = ?"
                               (:evaluator/runtime (describe binding)) eval-id])
            (store/begin! conn (assoc (identity-for-store (describe binding)) :source "1"))
            (is (= :pending-history (:samizdat.evaluator/error
                                     (thrown-data #(rebuild! conn binding)))))
            (is (= context (:evaluator/live-context (describe binding)))))))

      (doseq [[label tamper]
              [[:mismatch
                (fn [conn eval-id]
                  (db/execute! conn
                               ["UPDATE evaluator_receipts SET op = ':project/stat'
                                  WHERE eval_id = ? AND phase = 'intent'" eval-id])
                  (db/execute! conn
                               ["UPDATE evaluator_receipts SET op = ':project/stat'
                                  WHERE eval_id = ? AND phase = 'outcome'" eval-id]))]
               [:exhaustion
                (fn [conn eval-id]
                  (db/execute! conn ["DELETE FROM evaluator_receipts WHERE eval_id = ?" eval-id]))]
               [:unconsumed
                (fn [conn eval-id]
                  (db/execute! conn
                               ["INSERT INTO evaluator_receipts
                                   (eval_id, seq, phase, op, args, created_at)
                                 VALUES (?, 1, 'intent', ':project/stat', '[\"extra\"]', ?)"
                                eval-id (db/now)])
                  (db/execute! conn
                               ["INSERT INTO evaluator_receipts
                                   (eval_id, seq, phase, op, args, result, created_at)
                                 VALUES (?, 1, 'outcome', ':project/stat', '[\"extra\"]',
                                         '{:path \"extra\" :kind :absent}', ?)"
                                eval-id (db/now)]))]]]
        (testing (name label)
          (with-root [root conn]
            (seed-project! root)
            (let [world (atom 0)
                  binding (bind! root (name label)
                                 {:world-observer (fn [_ _] (swap! world inc))})
                  eval-id (:eval-id (evaluate! conn binding "(project/list \"src\")"))
                  context (:evaluator/live-context (describe binding))
                  calls @world]
              (tamper conn eval-id)
              (is (some? (thrown-data #(rebuild! conn binding))))
              (is (= calls @world) "refusal happened without a real semantic operation")
              (is (= context (:evaluator/live-context (describe binding)))
                  "refused reconstruction was not accepted"))))))))

(defn- fake-complete [content]
  (fn [_ctx]
    (fn [_tape]
      {:ok true :response {:content content :finish-reason "stop"}})))

(defn- tool-call [name args]
  (str "```tool-call\n" (json/write-str {:name name :args args}) "\n```"))

(deftest durable-binding-reconstructs-from-recorded-context-and-history
  (when bounded?
    (let [{:keys [bind! persist! reconstruct! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (let [run-id (runs/start-run! conn {:problem "durable binding"
                                            :max-turns 3})
              binding (bind! root run-id {})]
          (persist! conn run-id binding)
          ;; A durable binding is an M3 run: every evaluation under it must
          ;; carry the InferenceEpoch AND the per-call InferenceInvocation of
          ;; the model call that dispatched it.  The epoch resolves to this
          ;; exact binding/spec/runtime, so one epoch serves both evaluations
          ;; (reconstruct! restores the same identity); each evaluation names
          ;; its own invocation, one per dispatched call.
          (let [epoch-id "epoch:durable-binding"
                _ (inference/begin!
                   conn {:id epoch-id :run-id (str run-id) :branch-id "B1" :turn 1
                         :provider :stub :model "m"
                         :binding-id (:binding/id binding)
                         :spec-id (get-in binding [:spec :spec/coordinate])
                         :runtime (get-in binding [:spec :runtime-coordinate])})
                invocation-id (fn [n]
                                (:id (inference/invoke!
                                      conn {:id (str "invocation:durable-" n)
                                            :epoch-id epoch-id
                                            :run-id (str run-id)
                                            :branch-id "B1" :turn n})))]
            (is (= 41 (:value (evaluate! conn binding
                                        "(do (def durable-x 41) durable-x)"
                                        {:inference-epoch-id epoch-id
                                         :inference-invocation-id
                                         (invocation-id 1)}))))
            (let [rebuilt (reconstruct! conn run-id root)]
              (is (= (:binding/id binding) (:binding/id rebuilt)))
              (is (= (:instance/id binding) (:instance/id rebuilt)))
              (is (= 42 (:value (evaluate! conn rebuilt "(inc durable-x)"
                                           {:inference-epoch-id epoch-id
                                            :inference-invocation-id
                                            (invocation-id 2)}))))
              (is (= (:context/coordinate
                      (get-in binding [:spec :context-spec]))
                     (:context/coordinate
                      (get-in rebuilt [:spec :context-spec])))))))))))

(deftest reconstruction-restores-persisted-orientation-with-zero-history
  (when bounded?
    (let [{:keys [bind! persist! reconstruct!]} (evaluator-api)]
      (with-root [root conn]
        (let [run-id (runs/start-run! conn {:problem "orientation recovery"
                                            :max-turns 3})
              binding (bind! root run-id {})
              persisted (:trusted-orientation binding)]
          (persist! conn run-id binding)
          (is (empty? (store/history conn (:binding/id binding))))
          ;; A changed prompt renderer must not affect a zero-history resume:
          ;; reconstruct! installs the durable bytes before allocating SCI.
          (with-redefs-fn {(requiring-resolve 'samizdat.evaluator/trusted-orientation)
                           (constantly "DRIFTED PROMPT")}
            (fn []
              (let [rebuilt (reconstruct! conn run-id root)]
                (is (= persisted (:trusted-orientation rebuilt)))
                (is (not= "DRIFTED PROMPT" (:trusted-orientation rebuilt)))))))))))

(deftest no-network-current-turn-smoke
  (when bounded?
    (let [{:keys [bind!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [run-id (runs/start-run! conn {:problem "bounded smoke" :max-turns 3})
              binding (bind! root run-id {})
              branch (state/new-branch
                      {:id "B1" :problem "bounded smoke"
                       :messages (turn/initial-messages
                                  "bounded smoke" "read only"
                                  (:trusted-orientation binding))})
              ctx {:conn conn :run-id run-id :root root :config {:run {}}
                   :max-turns 3 :evaluator/profile :agent/project-read
                   :evaluator/binding binding}
              shell-runs (atom 0)]
          (runs/open-branch! conn run-id {:branch-id "B1"})
          (is (str/starts-with? (get-in branch [:messages 0 :content])
                                "SYSTEM / TRUSTED SURFACE"))
          (with-redefs [infer/complete-fn
                        (fake-complete
                         (tool-call "eval"
                                    {:code "(->> (project/search \"defn\" {:path \"src/samizdat\"}) (map :path) distinct vec)"}))]
            (let [after (workflow/run-turn
                         (assoc ctx :turn-lease
                                (base/mint-turn-lease run-id "B1" 1))
                         branch 1)]
              (is (some #(str/includes? (:content %) "src/samizdat/a.clj")
                        (:messages after)))
              (is (= "eval" (:tool_name (last (journal/turns conn run-id)))))
              (is (= [:project/search]
                     (mapv :op (:receipts (first (store/history conn (:binding/id binding)))))))))
          (with-redefs [infer/complete-fn (fake-complete (tool-call "shell" {:command "touch pwned"}))
                        policy/run-shell (fn [& _] (swap! shell-runs inc))]
            (let [after (workflow/run-turn
                         (assoc ctx :turn-lease
                                (base/mint-turn-lease run-id "B1" 2))
                         branch 2)]
              (is (some #(str/includes? (:content %) "outside this bounded context")
                        (:messages after)))
              (is (zero? @shell-runs))
              (is (not (fs/exists? (str root "/pwned")))))
          (let [done (tools/run-tool (assoc ctx :branch branch :turn 3
                                             :turn-lease (base/mint-turn-lease
                                                          run-id "B1" 3)
                                             :tool-name "done" :args {}))]
            (is (= :done (:control-event done)))
            (is (not (:done? done))
                "M2: done is a controller-verified ControlEvent, and a run that edited nothing has nothing to verify")
            (is (str/includes? (:result done) "changed no project files")))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; M1 closure adversarial regression: symlink confinement, bounded strict
;; reads, deterministic digests, timeout ceiling semantics, and the workflow
;; profile gate. Every case is deterministic and wall-clock bounded.
;; ═══════════════════════════════════════════════════════════════════════════

(deftest read-side-symlinks-are-refused-in-every-component
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (let [base (str (fs/create-temp-dir {:prefix "samizdat-m1-sym-"}))]
        (try
          (let [root (seed-linked-project! base)
                conn (db/open! ":memory:")]
            (try
              (let [binding (bind! root "symlink-confinement" {})]
                (testing "intermediate components: a link is refused even when it stays inside"
                  (is (= :symlink (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding "(project/read \"src-link/samizdat/a.clj\")"))))
                  (is (= :symlink (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding "(project/list \"src-link\")"))))
                  (is (= :symlink (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding "(project/search \"defn\" {:path \"src-link\"})")))))
                (testing "intermediate components: a link escaping the root is refused identically"
                  (is (= :symlink (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding "(project/read \"up-link/secret.txt\")")))))
                (testing "final components: inside, dangling, and escaping links are never followed"
                  (doseq [path ["inside.clj" "dangling" "escape-rel" "escape-abs"]]
                    (is (= :not-file (:samizdat.evaluator/error
                                      (eval-error evaluate! conn binding (str "(project/read \"" path "\")"))))
                        (str "read refuses the leaf link " path " without following it"))))
                (testing "stat reports a link as a link, with no digest and no target size"
                  (doseq [path ["inside.clj" "dangling" "escape-rel" "escape-abs"]]
                    (is (= {:path path :kind :symlink}
                           (:value (evaluate! conn binding (str "(project/stat \"" path "\")")))))))
                (testing "the walk never follows links: the outside secret is unreachable"
                  (is (= [] (:value (evaluate! conn binding "(project/search \"NEEDLE-SECRET\")")))
                      "no link family can steer a search at content outside the root")
                  (is (= [{:path "realdir/x.txt" :line 1 :text "NEEDLE-INSIDE"}]
                         (:value (evaluate! conn binding "(project/search \"NEEDLE\" {:path \".\"})")))
                      "the inside-pointing linkdir is skipped, not traversed"))
                (testing "listings report symlink entries as :symlink, never followed"
                  (is (= [{:name "a-dir" :kind :directory}
                          {:name "b.txt" :kind :file :bytes 5}
                          {:name "c-link" :kind :symlink}]
                         (:value (evaluate! conn binding "(project/list \"listdir\")")))))
                (testing "the binding stays usable after every refusal"
                  (is (= "(ns a)\n(defn alpha [] 1)\n"
                         (:value (evaluate! conn binding "(project/read \"src/samizdat/a.clj\")"))))))
              (finally (db/close conn))))
          (finally (fs/delete-tree base)))))))

(deftest lexical-path-policy-runs-before-filesystem
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [binding (bind! root "lexical-policy" {})]
          (testing "absolute paths are refused before any filesystem access"
            (is (= :absolute-path (:samizdat.evaluator/error
                                   (eval-error evaluate! conn binding "(project/read \"/etc/passwd\")"))))
            (is (= :absolute-path (:samizdat.evaluator/error
                                   (eval-error evaluate! conn binding "(project/list \"/tmp\")"))))
            (is (= :absolute-path (:samizdat.evaluator/error
                                   (eval-error evaluate! conn binding "(project/search \"x\" {:path \"/etc\"})")))))
          (testing "lexical escapes are refused before any filesystem access"
            (is (= :path-escape (:samizdat.evaluator/error
                                 (eval-error evaluate! conn binding "(project/read \"..\")"))))
            (is (= :path-escape (:samizdat.evaluator/error
                                 (eval-error evaluate! conn binding "(project/read \"../../etc/passwd\")"))))
            (is (= :path-escape (:samizdat.evaluator/error
                                 (eval-error evaluate! conn binding "(project/search \"x\" {:path \"../../\"})")))))
          (testing "malformed paths are refused as invalid"
            (is (= :invalid-path (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding "(project/read \"\")"))))
            (is (= :invalid-path (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding "(project/read 42)"))))
            (is (= :invalid-path (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding
                                              (str "(project/read \"" (str/join (repeat 4097 "a")) "\")"))))
                "a path over max-path-chars is refused before filesystem access"))
          (testing "missing paths distinguish intermediate from final components"
            (is (= :not-found (:samizdat.evaluator/error
                               (eval-error evaluate! conn binding "(project/read \"nope/samizdat/a.clj\")"))))
            (is (= :not-file (:samizdat.evaluator/error
                              (eval-error evaluate! conn binding "(project/read \"src/samizdat/missing.clj\")")))))
          (testing "the root itself is not a readable or stat-able file"
            (is (= :not-file (:samizdat.evaluator/error
                              (eval-error evaluate! conn binding "(project/read \".\")"))))
            (is (= :not-file (:samizdat.evaluator/error
                              (eval-error evaluate! conn binding "(project/stat \".\")")))))
          (testing "dot-dot that normalizes back inside the root is admitted"
            (is (= "(ns a)\n(defn alpha [] 1)\n"
                   (:value (evaluate! conn binding "(project/read \"src/../src/samizdat/a.clj\")"))))))))))

(deftest read-stops-at-the-bound-before-consuming-and-decodes-strictly
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (testing "a narrowed bound fails at the byte ceiling or the character bound, never after"
          (let [binding (bind! root "bounded-read" {:bounds {:max-read-chars 10}})]
            (spit (str root "/ten.txt") "0123456789")
            (spit (str root "/eleven.txt") "0123456789A")
            (spit (str root "/accents-ok.txt") (apply str (repeat 10 "é")))
            (spit (str root "/accents-over.txt") (apply str (repeat 11 "é")))
            (spit (str root "/bytes-over.txt") (apply str (repeat 41 "x")))
            (is (= "0123456789" (:value (evaluate! conn binding "(project/read \"ten.txt\")")))
                "content exactly at the bound reads in full")
            (let [data (eval-error evaluate! conn binding "(project/read \"eleven.txt\")")]
              (is (= :too-large (:samizdat.evaluator/error data)))
              (is (= 10 (:limit data))))
            (let [data (eval-error evaluate! conn binding "(project/read \"accents-over.txt\")")]
              (is (= :too-large (:samizdat.evaluator/error data)))
              (is (= 10 (:limit data))
                  "11 two-byte chars fit the 40-byte ceiling and fail on characters"))
            (let [data (eval-error evaluate! conn binding "(project/read \"bytes-over.txt\")")]
              (is (= :too-large (:samizdat.evaluator/error data)))
              (is (= 40 (:limit data))
                  "41 bytes fail the derived 4x byte ceiling during consumption, before decoding"))
            (is (= (apply str (repeat 10 "é"))
                   (:value (evaluate! conn binding "(project/read \"accents-ok.txt\")"))))))
        (testing "the default character bound is 60000"
          (let [binding (bind! root "default-bound" {})]
            (spit (str root "/big-ok.txt") (apply str (repeat 60000 "a")))
            (spit (str root "/big-over.txt") (apply str (repeat 60001 "a")))
            (is (= 60000 (count (:value (evaluate! conn binding "(project/read \"big-ok.txt\")")))))
            (is (= :too-large (:samizdat.evaluator/error
                               (eval-error evaluate! conn binding "(project/read \"big-over.txt\")"))))))
        (testing "decoding is strict UTF-8: malformed structures fail, never replace"
          (let [binding (bind! root "strict-utf8" {})
                malformed [["lone continuation" [0x80]]
                           ["bad continuation" [0x61 0xC3 0x28]]
                           ["overlong two-byte" [0xC0 0x81]]
                           ["overlong three-byte" [0xE0 0x80 0x80]]
                           ["utf-16 surrogate" [0xED 0xA0 0x80]]
                           ["beyond U+10FFFF" [0xF4 0x90 0x80 0x80]]
                           ["invalid lead 0xF5" [0xF5 0x80 0x80 0x80]]
                           ["truncated tail" [0x6F 0x6B 0xC3]]]]
            (doseq [[label ints] malformed]
              (write-bytes! (str root "/bad.bin") ints)
              (is (= :invalid-utf8 (:samizdat.evaluator/error
                                    (eval-error evaluate! conn binding "(project/read \"bad.bin\")")))
                  (str "invalid UTF-8 is refused: " label)))))
        (testing "valid multi-byte UTF-8 through the four-byte maximum decodes exactly"
          (let [binding (bind! root "utf8-valid" {})
                ints [0x68 0xC3 0xA9 0x6C 0x6C 0x6F 0xE2 0x82 0xAC
                      0xF0 0x9F 0x98 0x80 0xF4 0x8F 0xBF 0xBF 0x0A]]
            (write-bytes! (str root "/valid.txt") ints)
            (is (= (String. (->bytes ints) "UTF-8")
                   (:value (evaluate! conn binding "(project/read \"valid.txt\")"))))))
        (testing "a truncated sequence after a full valid prefix still fails"
          (let [binding (bind! root "utf8-boundary" {})
                ints (concat (map int (.getBytes "0123456789" "UTF-8")) [0xC3])]
            (write-bytes! (str root "/tail.bin") ints)
            (is (= :invalid-utf8 (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding "(project/read \"tail.bin\")"))))))))))

(deftest search-and-list-consumption-bounds-are-enforced-during-consumption
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (testing "collection stops at the result bound"
          (let [binding (bind! root "search-results" {:bounds {:max-search-results 1}})]
            (is (= [{:path "src/samizdat/a.clj" :line 2 :text "(defn alpha [] 1)"}]
                   (:value (evaluate! conn binding "(project/search \"defn\" {:path \"src/samizdat\"})"))))))
        (testing "the file bound fails during the walk"
          (let [binding (bind! root "search-files" {:bounds {:max-search-files 1}})]
            (is (= :too-many-files (:samizdat.evaluator/error
                                    (eval-error evaluate! conn binding "(project/search \"defn\" {:path \"src/samizdat\"})"))))))
        (testing "a file over the per-file bound is skipped without reading"
          (let [binding (bind! root "search-skip-big" {:bounds {:max-search-file-chars 7}})]
            (fs/create-dirs (str root "/searchbig"))
            (spit (str root "/searchbig/small.txt") "NEEDLE")
            (spit (str root "/searchbig/large.txt") (str "NEEDLE " (apply str (repeat 50 "x"))))
            (is (= [{:path "searchbig/small.txt" :line 1 :text "NEEDLE"}]
                   (:value (evaluate! conn binding "(project/search \"NEEDLE\" {:path \"searchbig\"})"))))))
        (testing "files that are not valid UTF-8 are skipped, not errors"
          (let [binding (bind! root "search-skip-bad" {})]
            (fs/create-dirs (str root "/searchbad"))
            (write-bytes! (str root "/searchbad/bad.bin") [0x4E 0x45 0x45 0x44 0x4C 0x45 0xFF])
            (spit (str root "/searchbad/valid.txt") "NEEDLE here\n")
            (is (= [{:path "searchbad/valid.txt" :line 1 :text "NEEDLE here"}]
                   (:value (evaluate! conn binding "(project/search \"NEEDLE\" {:path \"searchbad\"})"))))))
        (testing "match text is clipped at the line bound"
          (let [binding (bind! root "search-lines" {:bounds {:max-search-line-chars 4}})]
            (fs/create-dirs (str root "/searchlines"))
            (spit (str root "/searchlines/line.txt") "NEEDLE-padding-padding-padding")
            (is (= [{:path "searchlines/line.txt" :line 1 :text "NEED..."}]
                   (:value (evaluate! conn binding "(project/search \"NEEDLE\" {:path \"searchlines\"})"))))))
        (testing "patterns are bounded before any filesystem access"
          (let [binding (bind! root "search-pattern" {})]
            (is (= :invalid-arguments
                   (:samizdat.evaluator/error
                    (eval-error evaluate! conn binding
                                (str "(project/search \"" (apply str (repeat 201 "p")) "\")")))))
            (is (= :invalid-regex (:samizdat.evaluator/error
                                   (eval-error evaluate! conn binding "(project/search \"([\")"))))))
        (testing "entry consumption stops at the list bound"
          (let [binding (bind! root "list-bound" {:bounds {:max-list-entries 1}})]
            (is (= :too-many-entries (:samizdat.evaluator/error
                                      (eval-error evaluate! conn binding "(project/list \"src/samizdat\")"))))))))))

(deftest stat-digest-is-deterministic-and-fails-closed
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (fs/create-dirs (str root "/src"))
        (let [binding (bind! root "stat-digest" {})]
          (testing "known-answer digests"
            (spit (str root "/empty.txt") "")
            (spit (str root "/abc.txt") "abc")
            (is (= {:path "empty.txt" :kind :file :bytes 0
                    :digest "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}
                   (:value (evaluate! conn binding "(project/stat \"empty.txt\")"))))
            (is (= {:path "abc.txt" :kind :file :bytes 3
                    :digest "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}
                   (:value (evaluate! conn binding "(project/stat \"abc.txt\")")))))
          (testing "identical content yields identical digests and stat is stable"
            (spit (str root "/copy.txt") "abc")
            (is (= (:digest (:value (evaluate! conn binding "(project/stat \"abc.txt\")")))
                   (:digest (:value (evaluate! conn binding "(project/stat \"copy.txt\")")))
                   (:digest (:value (evaluate! conn binding "(project/stat \"abc.txt\")"))))))
          (testing "content change changes the digest"
            (let [before (:digest (:value (evaluate! conn binding "(project/stat \"abc.txt\")")))]
              (spit (str root "/abc.txt") "abd")
              (is (not= before
                        (:digest (:value (evaluate! conn binding "(project/stat \"abc.txt\")")))))))
          (testing "the digest mirrors an independent JVM SHA-256 over the bytes"
            (let [content (str/join (map #(str "NEEDLE-" % "\n") (range 200)))]
              (spit (str root "/pattern.txt") content)
              (is (= (str "sha256:" (sha256-hex (.getBytes content "UTF-8")))
                     (:digest (:value (evaluate! conn binding "(project/stat \"pattern.txt\")")))))))
          (testing "non-file leaves are inert observations, never fake digests"
            (is (= {:path "missing.txt" :kind :absent}
                   (:value (evaluate! conn binding "(project/stat \"missing.txt\")"))))
            (is (= {:path "src" :kind :directory}
                   (:value (evaluate! conn binding "(project/stat \"src\")"))))))
        (testing "a digest over the read bound fails closed, never a fake coordinate"
          (let [binding (bind! root "stat-bound" {:bounds {:max-read-chars 10}})]
            (spit (str root "/over.txt") (apply str (repeat 41 "z")))
            (is (= :too-large (:samizdat.evaluator/error
                               (eval-error evaluate! conn binding "(project/stat \"over.txt\")"))))))
        (testing "a digest is over raw bytes even when they are not UTF-8"
          (let [binding (bind! root "stat-raw" {})]
            (write-bytes! (str root "/bin.bin") [0xC3 0x28])
            (is (= :invalid-utf8 (:samizdat.evaluator/error
                                  (eval-error evaluate! conn binding "(project/read \"bin.bin\")"))))
            (is (= (str "sha256:" (sha256-hex (->bytes [0xC3 0x28])))
                   (:digest (:value (evaluate! conn binding "(project/stat \"bin.bin\")")))))))))))

(deftest context-spec-timeout-defaults-clamps-and-refuses
  (when bounded?
    (let [{:keys [bind! describe context-spec default-timeout]} (evaluator-api)]
      (with-root [root conn]
        (is (= 30000 @default-timeout) "the documented default ceiling is 30 seconds")
        (let [default (context-spec root {})
              repeated (context-spec root {})
              clamped (context-spec root {:timeout-ms 60000})
              narrowed (context-spec root {:timeout-ms 250})]
          (is (= 30000 (:context/timeout-ms default)))
          (is (= (:context/coordinate default) (:context/coordinate repeated))
              "the effective ContextSpec coordinate is deterministic")
          (is (= 30000 (:context/timeout-ms clamped))
              "a requested timeout above the default is attenuated down to it")
          (is (= (:context/coordinate default) (:context/coordinate clamped))
              "a clamped request mints exactly the default spec")
          (is (= 250 (:context/timeout-ms narrowed)))
          (is (not= (:context/coordinate default) (:context/coordinate narrowed))
              "the timeout ceiling is part of the spec coordinate"))
        (doseq [bad [0 -1 1.5 "30000" true]]
          (is (= :invalid-timeout (:samizdat.evaluator/error
                                   (thrown-data #(context-spec root {:timeout-ms bad}))))
              (str "timeout-ms " (pr-str bad) " is refused, never read as no ceiling")))
        (is (= 30000 (:evaluator/timeout-ms (describe (bind! root "timeout-default" {})))))
        (is (= 250 (:evaluator/timeout-ms (describe (bind! root "timeout-narrow" {:timeout-ms 250})))))
        (is (= 30000 (:evaluator/timeout-ms (describe (bind! root "timeout-clamp" {:timeout-ms 999999})))))))))

(def ^:private spin "(loop [] (recur))")

(defn- bounded-eval!
  "Run evaluate! on a future with a hard wall-clock ceiling; returns the
  Throwable it threw, its value if it (wrongly) returned, or ::unstopped."
  [evaluate! conn binding source opts]
  (deref (future (try (evaluate! conn binding source opts)
                      (catch Throwable e e)))
         10000 ::unstopped))

(deftest evaluation-timeout-ceiling-stops-and-rolls-back
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [binding (bind! root "timeout-fire" {:timeout-ms 400})]
          (is (= 3 (:value (evaluate! conn binding "(+ 1 2)"))))
          (let [r (bounded-eval! evaluate! conn binding spin nil)]
            (is (not= ::unstopped r) "the runaway evaluation stopped within a bounded wall clock")
            (let [data (error-data r)]
              (is (= :timeout (:samizdat.evaluator/error data))
                  "the ceiling reports :timeout rather than a raw interrupt")
              (is (= 400 (:timeout-ms data)))))
          (let [rows (store/history conn "bind:timeout-fire")]
            (is (= :completed (:status (first rows))))
            (is (= :failed (:status (last rows)))
                "the timed-out evaluation is durably failed"))
          (is (= 4 (:value (evaluate! conn binding "(+ 2 2)")))
              "the binding rolls back to committed state and stays usable"))))))

(deftest caller-token-only-narrows-and-the-spec-timer-never-fires-it
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (testing "a pre-fired caller token stops the evaluation as a raw interrupt"
          (let [binding (bind! root "caller-pre-fired" {})
                token (jolt.host/make-interrupt)]
            (jolt.host/interrupt! token)
            (let [r (bounded-eval! evaluate! conn binding spin {:token token})]
              (is (not= ::unstopped r))
              (is (jolt-interrupted? r)
                  "the caller revocation propagated as a raw Jolt interrupt")
              (is (not= :timeout (:samizdat.evaluator/error (error-data r)))
                  "a caller stop is never relabeled the spec timeout"))
            (is (= 42 (:value (evaluate! conn binding "(+ 40 2)")))
                "the binding stays usable after a caller-revoked evaluation")))
        (testing "the spec ceiling timer fires only the private token"
          (let [binding (bind! root "spec-timer" {:timeout-ms 300})
                token (jolt.host/make-interrupt)]
            (let [r (bounded-eval! evaluate! conn binding spin {:token token})]
              (is (not= ::unstopped r))
              (is (= :timeout (:samizdat.evaluator/error (error-data r)))))
            (is (not (jolt.host/interrupted? token))
                "the spec timer never fired the caller's shared token")
            (is (= 7 (:value (evaluate! conn binding "(+ 3 4)" {:token token})))
                "an unfired caller token is not poisoned by the spec's wake")))))))

(deftest workflow-bounded-profile-activation-mints-the-controller-owned-binding
  (when bounded?
    (let [{:keys [describe]} (evaluator-api)]
      (let [base (str (fs/create-temp-dir {:prefix "samizdat-m1-wf-"}))]
        (try
          (let [root (str base "/proj")]
            (fs/create-dirs root)
            (testing "[:run :bounded :profile] activates the read profile with controller authority"
              (let [ids (describe (workflow/bounded-binding
                                   root "wf-read"
                                   {:run {:bounded {:profile :agent/project-read}}}))]
                (is (= "bind:wf-read" (:evaluator/binding-id ids)))
                (is (= "inst:wf-read" (:evaluator/instance-id ids)))
                (is (= #{:project/read :project/list :project/search :project/stat}
                       (set (:evaluator/capabilities ids))))
                (is (= 30000 (:evaluator/timeout-ms ids)))))
            (testing "the string profile name activates the same lane"
              (is (= "bind:wf-str"
                     (:evaluator/binding-id
                      (describe (workflow/bounded-binding
                                 root "wf-str"
                                 {:run {:bounded {:profile "agent/project-read"}}}))))))
            (testing "no bounded request means no binding"
              (is (nil? (workflow/bounded-binding root "wf-none" {})))
              (is (nil? (workflow/bounded-binding root "wf-none2" {:run {:bounded {}}}))))
            (testing "userspace cannot widen read authority through config"
              (let [ids (describe (workflow/bounded-binding
                                   root "wf-widen"
                                   {:run {:bounded {:profile :agent/project-read
                                                    :requested #{:project/edit :project/shell}
                                                    :controller-authorized #{:project/edit}}}}))]
                (is (= #{:project/read :project/list :project/search :project/stat}
                       (set (:evaluator/capabilities ids)))))))
          (finally (fs/delete-tree base))))
      (testing "unsupported and wider profiles fail closed before any binding exists"
        ;; M2: :agent/project-develop left this list — it is now a supported
        ;; profile, covered by the develop activation tests below.
        (doseq [profile [:agent/minimal :agent/shell "agent/admin" :project/read]]
          (is (= :unsupported-profile
                 (:samizdat.evaluator/error
                  (thrown-data #(workflow/bounded-binding
                                 "/tmp" "wf-bad"
                                 {:run {:bounded {:profile profile}}}))))
              (str "profile " (pr-str profile) " fails closed"))))
      (testing "a missing pinned runtime fails closed"
        (with-redefs [clojure.core/requiring-resolve
                      (fn [& _] (throw (ex-info "no SCI on this classpath" {})))]
          ;; the redef must take effect for the assertion to mean anything
          (is (thrown? Throwable (requiring-resolve 'samizdat.evaluator/bind!)))
          (is (= :runtime-unavailable
                 (:samizdat.evaluator/error
                  (thrown-data #(workflow/bounded-binding
                                 "/tmp" "wf-nort"
                                 {:run {:bounded {:profile :agent/project-read}}}))))))
        (is (some? (requiring-resolve 'samizdat.evaluator/bind!))
            "the lane is intact after the redefinition is restored")))))

;; ═══════════════════════════════════════════════════════════════════════════
;; M2 write lane: the :agent/project-develop profile and its one semantic
;; mutation, (project/edit path base new-content). Anchored success and digest
;; chaining with exact receipt intent/outcome; stale/missing/existing conflicts
;; with zero writes; :absent create that never creates directories; absolute,
;; escaping, symlinked, directory, oversize, and operator-run-config refusals
;; with zero writes; actuation persistence across a later SCI failure; fresh
;; replay that consumes receipts and never re-writes; and the workflow's
;; develop activation with controller-fixed authority. Deterministic and
;; wall-clock bounded, like the M1 lanes above.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- file-bytes
  "Raw bytes of one file, read directly host-side (never through the
  evaluator under test)."
  [path]
  (java.nio.file.Files/readAllBytes (fs/path path)))

(defn- file-text
  "UTF-8 text of one file, decoded host-side from its exact raw bytes."
  [path]
  (String. (file-bytes path) "UTF-8"))

(defn- digest-of
  "The exact stat-anchor shape for one string's UTF-8 bytes — the coordinate
  project/stat returns and project/edit demands. An independent in-test
  SHA-256 mirror, differential against the evaluator's own digesting."
  [^String s]
  (str "sha256:" (sha256-hex (.getBytes s "UTF-8"))))

(defn- snapshot-tree
  "Deterministic byte-level fingerprint of a whole tree, independent of the
  evaluator under test: every regular file by the sha256 of its raw bytes,
  every symbolic link and directory by its NOFOLLOW kind, keyed by relative
  path. A refused edit must leave this exactly unchanged — the zero-write
  witness for every refusal lane below."
  [root]
  (let [entries (atom {})]
    (letfn [(walk [dir prefix]
              (doseq [entry (sort-by #(str (fs/file-name %)) (fs/list-dir (str dir)))]
                (let [name (str (fs/file-name entry))
                      rel (if (str/blank? prefix) name (str prefix "/" name))
                      kind (cond (fs/sym-link? (str entry)) :symlink
                                 (fs/regular-file? (str entry)
                                                   {:nofollow-links true}) :file
                                 (fs/directory? (str entry)
                                                {:nofollow-links true}) :directory
                                 :else :other)]
                  (swap! entries assoc rel
                         (if (= :file kind)
                           [:file (sha256-hex (file-bytes (str entry)))]
                           [kind]))
                  (when (= :directory kind)
                    (walk (str entry) rel)))))]
      (walk root "")
    @entries)))

(defn- no-edit-litter?
  "No spent or abandoned edit temp file remains anywhere under root — the
  atomic temp-then-rename substrate must always clean up after itself."
  [root]
  (let [litter (atom [])]
    (letfn [(walk [dir]
              (doseq [entry (fs/list-dir (str dir))]
                (let [name (str (fs/file-name entry))]
                  (when (str/starts-with? name ".samizdat-edit-")
                    (swap! litter conj name))
                  (when (fs/directory? (str entry) {:nofollow-links true})
                    (walk (str entry))))))]
      (walk root)
      (empty? @litter))))

(def ^:private develop-caps
  "The :agent/project-develop catalog maximum: the read profile plus the one
  semantic mutation."
  #{:project/read :project/list :project/search :project/stat :project/edit})

(defn- sandbox-error
  "The ex-data anywhere on the cause chain carrying a jolt.sandbox error
  kind — the authority-refusal shape a narrowed binding produces, which has
  no evaluator error kind of its own."
  [e]
  (loop [e e n 0]
    (cond (or (nil? e) (> n 8)) nil
          (:jolt.sandbox/error (ex-data e)) (ex-data e)
          :else (recur (ex-cause e) (inc n)))))

(deftest anchored-edit-chains-digests-and-records-exact-receipts
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [binding (bind! root "edit-chain" {:profile :agent/project-develop})
              d1 (digest-of "v1")
              d2 (digest-of "v2")
              created {:path "notes.md" :kind :file :bytes 2 :digest d1}
              replaced {:path "notes.md" :kind :file :bytes 2 :digest d2}
              source (str "(let [created (project/edit \"notes.md\" :absent \"v1\") "
                          "after-create (project/stat \"notes.md\") "
                          "replaced (project/edit \"notes.md\" "
                          "(:digest after-create) \"v2\") "
                          "after-replace (project/stat \"notes.md\")] "
                          "[created after-create replaced after-replace])")
              result (evaluate! conn binding source)]
          (testing "an anchored create/replace returns exactly what project/stat reports"
            (is (= [created created replaced replaced] (:value result))
                "each edit's return is the very map the following stat reports")
            (is (= "v2" (file-text (str root "/notes.md")))
                "the external file holds the final anchored content"))
          (testing "the durable receipts are the exact intent/outcome pairs, in order"
            (is (= [{:seq 0 :op :project/edit
                     :args ["notes.md" :absent "v1"] :phase :done :result created}
                    {:seq 1 :op :project/stat
                     :args ["notes.md"] :phase :done :result created}
                    {:seq 2 :op :project/edit
                     :args ["notes.md" d1 "v2"] :phase :done :result replaced}
                    {:seq 3 :op :project/stat
                     :args ["notes.md"] :phase :done :result replaced}]
                   (:receipts (store/load-eval conn (:eval-id result))))))
          (testing "intent rows precede actuation; outcome rows carry only results"
            (let [rows (db/fetch conn
                                 ["SELECT seq, phase, args, result FROM evaluator_receipts
                                     WHERE eval_id = ? ORDER BY seq, id"
                                  (:eval-id result)])]
              (is (= [[0 "intent"] [0 "outcome"] [1 "intent"] [1 "outcome"]
                      [2 "intent"] [2 "outcome"] [3 "intent"] [3 "outcome"]]
                     (mapv (juxt :seq :phase) rows))
                  "one intent recorded before and one outcome after each operation")
              (is (every? #(and (some? (:args %)) (nil? (:result %)))
                          (filter #(= "intent" (:phase %)) rows))
                  "intent rows carry the exact arguments and no result")
              (is (every? #(and (nil? (:args %)) (some? (:result %)))
                          (filter #(= "outcome" (:phase %)) rows))
                  "outcome rows carry the exact result and no arguments")))
          (testing "the next anchor is over raw UTF-8 bytes, not characters"
            (let [content "héllo€\n"
                  d0 (digest-of "(ns a)\n(defn alpha [] 1)\n")
                  expected {:path "src/samizdat/a.clj" :kind :file :bytes 10
                            :digest (digest-of content)}]
              (is (= expected
                     (:value (evaluate! conn binding
                                        (str "(project/edit \"src/samizdat/a.clj\" "
                                             (pr-str d0) " " (pr-str content) ")")))))
              (is (= 10 (count (file-bytes (str root "/src/samizdat/a.clj")))))
              (is (= (digest-of content)
                     (str "sha256:"
                          (sha256-hex (file-bytes (str root "/src/samizdat/a.clj"))))))
              (is (= content
                     (:value (evaluate! conn binding
                                        "(project/read \"src/samizdat/a.clj\")"))))))
          (testing "an atomic edit leaves no temp litter"
            (is (no-edit-litter? root))))))))

(deftest stale-missing-and-existing-edit-conflicts-write-nothing
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [binding (bind! root "edit-conflicts" {:profile :agent/project-develop})
              rel "src/samizdat/a.clj"
              current (digest-of "(ns a)\n(defn alpha [] 1)\n")
              wrong (digest-of "someone-else-edited-this\n")]
          (testing "a stale base is refused with the current digest, writing nothing"
            (let [before (snapshot-tree root)
                  data (eval-error evaluate! conn binding
                                   (str "(project/edit " (pr-str rel) " "
                                        (pr-str wrong) " \"pwned\")"))]
              (is (= :stale (:samizdat.evaluator/error data)))
              (is (= {:path rel :current-digest current}
                     (select-keys data [:path :current-digest])))
              (is (= before (snapshot-tree root)))))
          (testing "a missing anchor target is refused, creating nothing"
            (let [before (snapshot-tree root)
                  data (eval-error evaluate! conn binding
                                   (str "(project/edit \"src/samizdat/gone.clj\" "
                                        (pr-str wrong) " \"x\")"))]
              (is (= :missing (:samizdat.evaluator/error data)))
              (is (= before (snapshot-tree root)))))
          (testing "an existing create target is refused, writing nothing"
            (let [before (snapshot-tree root)
                  data (eval-error evaluate! conn binding
                                   (str "(project/edit " (pr-str rel)
                                        " :absent \"pwned\")"))]
              (is (= :existing (:samizdat.evaluator/error data)))
              (is (= rel (:path data)))
              (is (= before (snapshot-tree root)))))
          (testing "each conflict is a durably failed evaluation with its exact receipt"
            (let [rows (store/history conn "bind:edit-conflicts")]
              (is (= [:failed :failed :failed] (mapv :status rows)))
              (is (= [{:seq 0 :op :project/edit
                       :args [rel wrong "pwned"] :phase :error}]
                     (mapv #(select-keys % [:seq :op :args :phase])
                           (:receipts (first rows))))
                  "the refused edit's receipt carries the exact intent and an error outcome")))
          (testing "the binding stays usable and the tree stays clean"
            (is (= 2 (:value (evaluate! conn binding "(+ 1 1)"))))
            (is (no-edit-litter? root))))))))

(deftest absent-create-race-is-decided-by-native-no-replace-publication
  ;; Both writers pass the lexical/root/symlink checks, see an absent target,
  ;; write a complete same-directory temp, then stop at the sole seam before
  ;; Jolt's native publication. Releasing them together therefore closes the
  ;; former check-then-rename window deterministically: exactly one native
  ;; no-replace call can publish, while the other observes :exists. This is not
  ;; a scheduler-timing test and it touches no generic FFI surface.
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)
          root (str (fs/create-temp-dir {:prefix "samizdat-m2-create-race-"}))
          conn-a (db/open! ":memory:")
          conn-b (db/open! ":memory:")
          ready (java.util.concurrent.CountDownLatch. 2)
          go (java.util.concurrent.CountDownLatch. 1)
          hook (fn [_tmp _target]
                 (.countDown ready)
                 (when-not (.await go 10 java.util.concurrent.TimeUnit/SECONDS)
                   (throw (ex-info "race test release timed out" {}))))]
      (try
        (seed-project! root)
        (let [a (bind! root "edit-create-race-a" {:profile :agent/project-develop})
              b (bind! root "edit-create-race-b" {:profile :agent/project-develop})
              outcomes (atom [])
              worker (fn [conn binding content]
                       (Thread.
                        (fn []
                          (try
                            (swap! outcomes conj
                                   {:ok (evaluate! conn binding
                                                   (str "(project/edit \"raced.md\" :absent "
                                                        (pr-str content) ")"))})
                            (catch Throwable e
                              (swap! outcomes conj {:error e}))))))]
          (with-redefs-fn {(ns-resolve 'samizdat.evaluator '*before-create-publish*) hook}
            (fn []
              (let [ta (worker conn-a a "writer-a\n")
                    tb (worker conn-b b "writer-b\n")]
                (.start ta)
                (.start tb)
                (is (.await ready 10 java.util.concurrent.TimeUnit/SECONDS)
                    "both writers reached native-publication boundary")
                (.countDown go)
                (.join ta)
                (.join tb))))
          (let [wins (filter :ok @outcomes)
                losses (map #(error-data (:error %)) (filter :error @outcomes))]
            (is (= 1 (count wins)) "exactly one same-name create publishes")
            (is (= [:existing] (mapv :samizdat.evaluator/error losses))
                "the losing native call reports the existing-create conflict")
            (is (contains? #{"writer-a\n" "writer-b\n"}
                           (file-text (str root "/raced.md")))
                "the target is one whole candidate, never a replacement or torn write")
            (is (no-edit-litter? root))))
        (finally
          (db/close conn-a)
          (db/close conn-b)
          (fs/delete-tree root))))))

(deftest absent-edit-creates-files-but-never-directories
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [binding (bind! root "edit-create" {:profile :agent/project-develop})
              empty-digest "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"]
          (testing ":absent creates one regular file, including empty and nested ones"
            (is (= {:path "created.md" :kind :file :bytes 3 :digest (digest-of "new")}
                   (:value (evaluate! conn binding
                                      "(project/edit \"created.md\" :absent \"new\")"))))
            (is (= {:path "src/samizdat/fresh.clj" :kind :file
                    :bytes 0 :digest empty-digest}
                   (:value (evaluate! conn binding
                                      "(project/edit \"src/samizdat/fresh.clj\" :absent \"\")"))))
            (is (= "new" (file-text (str root "/created.md"))))
            (is (= 0 (count (file-bytes (str root "/src/samizdat/fresh.clj"))))))
          (testing "a missing parent is refused and no directory is ever created"
            (doseq [path ["no/such/dir/f.txt" "src/samizdat/absent-dir/f.txt"]]
              (let [before (snapshot-tree root)
                    data (eval-error evaluate! conn binding
                                     (str "(project/edit " (pr-str path)
                                          " :absent \"x\")"))]
                (is (= :not-found (:samizdat.evaluator/error data))
                    (str "a missing parent is refused for " path))
                (is (= before (snapshot-tree root))
                    (str "zero writes and zero directory creation for " path))))
            (is (not (fs/exists? (str root "/no")))
                "edit never created the missing leading directory")
            (is (not (fs/exists? (str root "/src/samizdat/absent-dir")))
                "edit never created the missing inner directory"))
          (testing "a file used as a parent directory is refused as missing"
            (is (= :not-found
                   (:samizdat.evaluator/error
                    (eval-error evaluate! conn binding
                                "(project/edit \"src/samizdat/a.clj/deeper.txt\" :absent \"x\")")))))
          (testing "the root itself is not an editable target"
            (is (= :not-file
                   (:samizdat.evaluator/error
                    (eval-error evaluate! conn binding
                                "(project/edit \".\" :absent \"x\")")))))
          (is (no-edit-litter? root)))))))

(deftest unconfined-and-non-file-edit-targets-are-refused-with-zero-writes
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (let [base (str (fs/create-temp-dir {:prefix "samizdat-m2-sym-" }))]
        (try
          (let [root (seed-linked-project! base)
                conn (db/open! ":memory:")]
            (try
              (spit (str base "/outside.txt") "OUTSIDE\n")
              (let [binding (bind! root "edit-confinement"
                                   {:profile :agent/project-develop})
                    a-digest (digest-of "(ns a)\n(defn alpha [] 1)\n")]
                (testing "absolute and lexically escaping paths are refused before any write"
                  (doseq [[path kind] [["/etc/motd" :absolute-path]
                                       ["../outside.txt" :path-escape]
                                       ["../../etc/passwd" :path-escape]]]
                    (let [before (snapshot-tree base)]
                      (is (= kind
                             (:samizdat.evaluator/error
                              (eval-error evaluate! conn binding
                                          (str "(project/edit " (pr-str path)
                                               " :absent \"x\")")))))
                      (is (= before (snapshot-tree base))
                          (str "zero writes anywhere, inside or outside, for " path)))))
                (testing "a symbolic link in an intermediate component is refused, never followed"
                  (let [before (snapshot-tree base)]
                    (is (= :symlink
                           (:samizdat.evaluator/error
                            (eval-error evaluate! conn binding
                                        "(project/edit \"src-link/samizdat/new.clj\" :absent \"x\")"))))
                    (is (= before (snapshot-tree base)))))
                (testing "a symbolic link as the final component is refused even with a valid anchor"
                  (let [before (snapshot-tree base)]
                    (is (= :symlink
                           (:samizdat.evaluator/error
                            (eval-error evaluate! conn binding
                                        (str "(project/edit \"inside.clj\" "
                                             (pr-str a-digest) " \"pwned\")")))))
                    (is (= before (snapshot-tree base))
                        "the link stays a link and its target keeps its bytes — no rename over the link")))
                (testing "a dangling final link is a link, not an absent create"
                  (let [before (snapshot-tree base)]
                    (is (= :symlink
                           (:samizdat.evaluator/error
                            (eval-error evaluate! conn binding
                                        "(project/edit \"dangling\" :absent \"x\")"))))
                    (is (= before (snapshot-tree base)))))
                (testing "a directory target is refused as not-a-file"
                  (doseq [form ["(project/edit \"docs\" :absent \"x\")"
                                (str "(project/edit \"docs\" " (pr-str a-digest)
                                     " \"x\")")]]
                    (let [before (snapshot-tree base)
                          data (eval-error evaluate! conn binding form)]
                      (is (= :not-file (:samizdat.evaluator/error data)))
                      (is (= :directory (:kind data)))
                      (is (= before (snapshot-tree base))))))
                (testing "the develop binding stays usable after every refusal"
                  (is (= "guide\n"
                         (:value (evaluate! conn binding
                                            "(project/read \"docs/guide.md\")"))))
                  (is (no-edit-litter? root))))
              (finally (db/close conn))))
          (finally (fs/delete-tree base)))))))

(deftest edit-content-and-base-arguments-are-bounded-before-any-write
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (spit (str root "/a.md") "a\n")
        (let [binding (bind! root "edit-bounds" {:profile :agent/project-develop})
              big (apply str (repeat 60000 "a"))
              bigger (apply str (repeat 60001 "a"))]
          (testing "content at the default ceiling succeeds; one character over fails closed"
            (is (= {:path "big.txt" :kind :file :bytes 60000 :digest (digest-of big)}
                   (:value (evaluate! conn binding
                                      (str "(project/edit \"big.txt\" :absent "
                                           (pr-str big) ")")))))
            (let [before (snapshot-tree root)
                  data (eval-error evaluate! conn binding
                                   (str "(project/edit \"big.txt\" "
                                        (pr-str (digest-of big)) " "
                                        (pr-str bigger) ")"))]
              (is (= :too-large (:samizdat.evaluator/error data)))
              (is (= 60000 (:limit data)))
              (is (= before (snapshot-tree root))
                  "the oversize replacement wrote nothing")))
          (testing "a controller-narrowed edit bound is enforced at the same seam"
            (let [narrow (bind! root "edit-bounds-8"
                                {:profile :agent/project-develop
                                 :bounds {:max-edit-chars 8}})]
              (is (= {:path "tiny.txt" :kind :file :bytes 8
                      :digest (digest-of "12345678")}
                     (:value (evaluate! conn narrow
                                        "(project/edit \"tiny.txt\" :absent \"12345678\")"))))
              (let [before (snapshot-tree root)
                    data (eval-error evaluate! conn narrow
                                     "(project/edit \"tiny.txt\" :absent \"123456789\")")]
                (is (= :too-large (:samizdat.evaluator/error data)))
                (is (= 8 (:limit data)))
                (is (= before (snapshot-tree root))))))
          (testing "base and content shapes are validated before any filesystem access"
            (doseq [[form kind] [["(project/edit \"a.md\" \"sha256:deadbeef\" \"x\")"
                                  :invalid-arguments]
                                 ["(project/edit \"a.md\" 42 \"x\")"
                                  :invalid-arguments]
                                 ["(project/edit \"a.md\" :absent 42)"
                                  :invalid-arguments]
                                 ["(project/edit \"\" :absent \"x\")"
                                  :invalid-path]]]
              (let [before (snapshot-tree root)]
                (is (= kind (:samizdat.evaluator/error
                             (eval-error evaluate! conn binding form))))
                (is (= before (snapshot-tree root))))))
          (is (no-edit-litter? root)))))))

(deftest edit-refuses-the-operators-run-config-through-the-files-seam
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (fs/create-dirs (str root "/.samizdat"))
        (let [binding (bind! root "edit-protected" {:profile :agent/project-develop})
              config (str root "/.samizdat/config.edn")
              config-content "{:run {:verify-cmd \"cargo test\" :require-test? true}}\n"]
          (spit config config-content)
          (testing "the run config is refused before any anchor check, even with a stale base"
            (let [before (snapshot-tree root)
                  data (eval-error evaluate! conn binding
                                   (str "(project/edit \".samizdat/config.edn\" "
                                        (pr-str (digest-of "wrong\n"))
                                        " \"{:run {}}\")"))]
              (is (= :protected-path (:samizdat.evaluator/error data)))
              (is (= before (snapshot-tree root)))))
          (testing "a lexically-normalized alias of the run config is refused identically"
            (let [before (snapshot-tree root)]
              (is (= :protected-path
                     (:samizdat.evaluator/error
                      (eval-error evaluate! conn binding
                                  "(project/edit \"src/../.samizdat/config.edn\" :absent \"x\")"))))
              (is (= before (snapshot-tree root)))))
          (testing "creating the run config through :absent is refused"
            (fs/delete config)
            (let [data (eval-error evaluate! conn binding
                                   "(project/edit \".samizdat/config.edn\" :absent \"{:run {}}\")")]
              (is (= :protected-path (:samizdat.evaluator/error data)))
              (is (not (fs/exists? config))
                  "the refused create wrote no run config")))
          (testing "the rest of .samizdat/ stays writable through the same seam"
            (is (= {:path ".samizdat/cells.md" :kind :file
                    :bytes 2 :digest (digest-of "ok")}
                   (:value (evaluate! conn binding
                                      "(project/edit \".samizdat/cells.md\" :absent \"ok\")"))))
            (is (= "ok" (file-text (str root "/.samizdat/cells.md")))))
          (is (no-edit-litter? root)))))))

(deftest edit-then-sci-failure-keeps-the-write-and-rolls-back-sci-state
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [binding (bind! root "edit-then-fail" {:profile :agent/project-develop})
              rel "src/samizdat/a.clj"
              d0 (digest-of "(ns a)\n(defn alpha [] 1)\n")
              d1 (digest-of "line-one\n")]
          (is (= {:path rel :kind :file :bytes 9 :digest d1}
                 (:value (evaluate! conn binding
                                    (str "(project/edit " (pr-str rel) " "
                                         (pr-str d0) " \"line-one\\n\")")))))
          (is (= "line-one\n" (file-text (str root "/" rel))))
          (testing "a later SCI failure keeps the external write and its receipts"
            (is (some? (thrown-data
                        #(evaluate! conn binding
                                    (str "(do (def canary 1) "
                                         "(project/edit " (pr-str rel) " "
                                         (pr-str d1) " \"line-two\\n\") "
                                         "canary-typo)")))))
            (is (= "line-two\n" (file-text (str root "/" rel)))
                "the actuation persisted — external effects are never unwound")
            (is (some? (thrown-data #(evaluate! conn binding "canary")))
                "the failed evaluation's SCI state was rolled back"))
          (testing "the failed evaluation is durable with its settled edit receipt"
            ;; The canary probe below adds its own durably failed row after
            ;; these, so scope the history assertion to the two evals under
            ;; test rather than to the whole binding.
            (let [rows (take 2 (store/history conn "bind:edit-then-fail"))]
              (is (= [:completed :failed] (mapv :status rows)))
              (is (= [{:seq 0 :op :project/edit
                       :args [rel d1 "line-two\n"] :phase :done
                       :result {:path rel :kind :file :bytes 9
                                :digest (digest-of "line-two\n")}}]
                     (:receipts (second rows)))
                  "the failed eval's edit receipt is retained, settled and exact")
              (is (= [{:seq 0 :op :project/edit
                       :args [rel d0 "line-one\n"] :phase :done}]
                     (mapv #(select-keys % [:seq :op :args :phase])
                           (:receipts (first rows))))
                  "committed history is intact")))
          (testing "the binding stays usable after the rollback"
            (is (= 3 (:value (evaluate! conn binding "(+ 1 2)")))))
          (is (no-edit-litter? root)))))))

(deftest fresh-replay-consumes-receipts-and-never-rewrites
  (when bounded?
    (let [{:keys [bind! describe evaluate! rebuild!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [world (atom [])
              binding (bind! root "edit-replay"
                             {:profile :agent/project-develop
                              :world-observer #(swap! world conj [%1 %2])})
              d1 (digest-of "v1")]
          (evaluate! conn binding
                     "(do (def marker \"committed\") (project/edit \"notes.md\" :absent \"v1\"))")
          (evaluate! conn binding
                     (str "(project/edit \"notes.md\" " (pr-str d1) " \"v2\")"))
          (is (= [[:project/edit ["notes.md" :absent "v1"]]
                  [:project/edit ["notes.md" d1 "v2"]]]
                 @world)
              "the two real writes were observed as ordered actuations")
          (is (= "v2" (file-text (str root "/notes.md"))))
          (spit (str root "/notes.md") "EXTERNAL-SENTINEL")
          (let [calls @world
                before-context (:evaluator/live-context (describe binding))
                rebuilt (rebuild! conn binding)]
            (testing "reconstruction replays receipts and never re-executes the writes"
              (is (= calls @world)
                  "zero real world calls during reconstruction")
              (is (= "EXTERNAL-SENTINEL" (file-text (str root "/notes.md")))
                  "neither recorded write was re-executed onto the file"))
            (testing "committed SCI state survives fresh reconstruction"
              (is (not= before-context
                        (:evaluator/live-context (describe rebuilt)))
                  "fresh reconstruction allocated a new process-local SCI context")
              (is (= "committed-ok"
                     (:value (evaluate! conn rebuilt "(str marker \"-ok\")")))))
            (testing "both edits remain durable completed history"
              ;; The marker probe above adds its own receiptless completed row
              ;; after these, so scope the history assertion to the two edit
              ;; evals under test rather than to the whole binding.
              (let [rows (take 2 (store/history conn "bind:edit-replay"))]
                (is (= [:completed :completed] (mapv :status rows)))
                (is (= [[:project/edit] [:project/edit]]
                       (mapv #(mapv :op (:receipts %)) rows)))))))))))

(deftest workflow-develop-activation-and-authority-narrowing
  (when bounded?
    (let [{:keys [bind! describe evaluate! context-spec complete doc]} (evaluator-api)]
      (let [base (str (fs/create-temp-dir {:prefix "samizdat-m2-wf-" }))]
        (try
          (let [root (str base "/proj")
                conn (db/open! ":memory:")]
            (try
              (fs/create-dirs root)
              (testing "[:run :bounded :profile :agent/project-develop] mints the develop lane"
                (let [b (workflow/bounded-binding root "wf-develop"
                                                  {:run {:bounded
                                                         {:profile :agent/project-develop}}})
                      ids (describe b)]
                  (is (= "bind:wf-develop" (:evaluator/binding-id ids)))
                  (is (= "inst:wf-develop" (:evaluator/instance-id ids)))
                  (is (= develop-caps (set (:evaluator/capabilities ids))))
                  (is (= 30000 (:evaluator/timeout-ms ids)))
                  (is (= :agent/project-develop
                         (get-in b [:spec :context-spec :context/profile]))
                      "the profile the workflow signals is the binding's minted profile")))
              (testing "the string profile name activates the same develop lane"
                (is (= develop-caps
                       (set (:evaluator/capabilities
                            (describe (workflow/bounded-binding
                                       root "wf-develop-str"
                                       {:run {:bounded
                                              {:profile "agent/project-develop"}}})))))))
              (testing "userspace config cannot widen or reshape the controller's authority"
                (is (= develop-caps
                       (set (:evaluator/capabilities
                            (describe (workflow/bounded-binding
                                       root "wf-widen"
                                       {:run {:bounded
                                              {:profile :agent/project-develop
                                               :requested #{:project/edit :project/shell}
                                               :controller-authorized #{:project/shell}}}})))))))
              (testing "a narrowed develop binding loses the mutation and its guidance"
                (let [b (bind! root "dev-narrowed"
                               {:profile :agent/project-develop
                                :controller-authorized (disj develop-caps :project/edit)})]
                  (is (= (disj develop-caps :project/edit)
                         (set (:evaluator/capabilities (describe b)))))
                  (is (= ["project/read" "project/list" "project/search" "project/stat"]
                         (complete b "project/")))
                  (is (nil? (doc b "project/edit")))
                  (is (not (str/includes? (:trusted-orientation b) "project/edit"))
                      "orientation guidance follows the capability held, not the profile name")
                  (spit (str root "/keep.txt") "keep\n")
                  (let [failure (try {:value (evaluate! conn b
                                                        "(project/edit \"keep.txt\" :absent \"pwned\")")}
                                     (catch Throwable t {:error t}))]
                    (is (contains? failure :error)
                        "the narrowed binding refuses the edit outright")
                    (is (= {:jolt.sandbox/error :unauthorized :op/id :project/edit}
                           (select-keys (sandbox-error (:error failure))
                                        [:jolt.sandbox/error :op/id])))
                    (is (= "keep\n" (file-text (str root "/keep.txt")))
                        "the refused unauthorized edit wrote nothing"))))
              (testing "the full develop binding carries the edit catalog entry and guidance"
                (let [b (bind! root "dev-full" {:profile :agent/project-develop})]
                  (is (= ["project/read" "project/list" "project/search"
                          "project/stat" "project/edit"]
                         (complete b "project/")))
                  (is (= "project/edit" (:name (doc b "project/edit"))))
                  (is (str/includes? (:trusted-orientation b) "project/edit"))))
              (finally (db/close conn))))
          (finally (fs/delete-tree base))))
      (with-root [root conn]
        (testing "context-spec intersects request, authorization and the profile maximum"
          (is (= develop-caps
                 (set (:context/capabilities
                       (context-spec root {:profile :agent/project-develop})))))
          (is (= [:project/edit :project/read]
                 (:context/capabilities
                  (context-spec root {:profile :agent/project-develop
                                      :requested #{:project/read :project/edit}}))))
          (is (= [:project/read]
                 (:context/capabilities
                  (context-spec root {:profile :agent/project-develop
                                      :controller-authorized #{:project/read}})))))
        (testing "an unknown profile fails closed before any spec or binding exists"
          (is (= :unsupported-profile
                 (:samizdat.evaluator/error
                  (thrown-data #(context-spec root {:profile :agent/shell})))))
          (is (= :unsupported-profile
                 (:samizdat.evaluator/error
                  (thrown-data #(bind! root "bad-profile"
                                       {:profile :agent/shell}))))))))))

;; ─── M4 attempt 2: the anchored (surgical) mutation form ───────────────────
;;
;; JS1 M4 attempt 1 had only the whole-file shape. A model that wanted to
;; change one arithmetic expression had to reproduce an entire namespace
;; verbatim, could not, regenerated it from its priors instead, and silently
;; deleted two live production functions. The four-argument form replaces the
;; ONE exact occurrence of an anchor and leaves every other byte alone.
;;
;; It runs under the SAME :project/edit capability — the pinned runtime's
;; profile table is a closed maximum and rejects both an unlisted capability
;; id and a duplicate operation id — so this is a narrower way to spend
;; authority that already exists, not new authority.

(deftest anchored-replacement-changes-one-occurrence-and-nothing-else
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [original (str "(ns keep)\n"
                            "(defn keep-me [] :kept)\n"
                            "(defn target [] :old)\n"
                            "(defn keep-me-too [] :also-kept)\n")
              _ (spit (str root "/src/samizdat/c.clj") original)
              binding (bind! root "anchored-ok" {:profile :agent/project-develop})
              expected (str/replace original "(defn target [] :old)"
                                    "(defn target [] :new)")
              source (str "(let [s (project/stat \"src/samizdat/c.clj\") "
                          "r (project/edit \"src/samizdat/c.clj\" (:digest s) "
                          "\"(defn target [] :old)\" \"(defn target [] :new)\") "
                          "after (project/stat \"src/samizdat/c.clj\")] "
                          "[r after])")
              [r after] (:value (evaluate! conn binding source))]
          (testing "only the anchored text changes"
            (is (= expected (file-text (str root "/src/samizdat/c.clj")))
                "every byte outside the anchor survives verbatim")
            (is (str/includes? (file-text (str root "/src/samizdat/c.clj"))
                               "(defn keep-me [] :kept)"))
            (is (str/includes? (file-text (str root "/src/samizdat/c.clj"))
                               "(defn keep-me-too [] :also-kept)")))
          (testing "the return is the canonical next anchor"
            (is (= (digest-of expected) (:digest r)))
            (is (= r after) "the return is exactly what project/stat reports")
            (is (= :file (:kind r)))
            (is (= (count (.getBytes ^String expected "UTF-8")) (:bytes r)))))))))

(deftest anchored-replacement-refuses-every-unsafe-request-with-zero-writes
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [original "(ns d)\n(defn twice [] :x)\n(defn twice-again [] :x)\n"
              path (str root "/src/samizdat/d.clj")
              _ (spit path original)
              binding (bind! root "anchored-refusals"
                             {:profile :agent/project-develop})
              stat-digest (digest-of original)
              refuse (fn [src] (eval-error evaluate! conn binding src))
              untouched? #(= original (file-text path))]

          (testing "an anchor that is not present is refused"
            (is (= :anchor-missing
                   (:samizdat.evaluator/error
                    (refuse (str "(project/edit \"src/samizdat/d.clj\" \""
                                 stat-digest "\" \"(defn absent [] 1)\" \"x\")"))))
                )
            (is (untouched?)))

          (testing "an anchor occurring more than once is refused, never guessed"
            (is (= :anchor-ambiguous
                   (:samizdat.evaluator/error
                    (refuse (str "(project/edit \"src/samizdat/d.clj\" \""
                                 stat-digest "\" \":x\" \":y\")")))))
            (is (untouched?)))

          (testing "an empty anchor is not an anchor"
            (is (= :invalid-arguments
                   (:samizdat.evaluator/error
                    (refuse (str "(project/edit \"src/samizdat/d.clj\" \""
                                 stat-digest "\" \"\" \"x\")")))))
            (is (untouched?)))

          (testing "a stale digest is refused"
            (is (= :stale
                   (:samizdat.evaluator/error
                    (refuse (str "(project/edit \"src/samizdat/d.clj\" \""
                                 (digest-of "something else")
                                 "\" \"(defn twice [] :x)\" \"x\")")))))
            (is (untouched?)))

          (testing ":absent is not a legal anchored base — creation is 3-arity"
            (is (contains? #{:invalid-arguments :missing}
                           (:samizdat.evaluator/error
                            (refuse (str "(project/edit \"src/samizdat/d.clj\""
                                         " :absent \"a\" \"b\")")))))
            (is (untouched?)))

          (testing "a path escaping the root is refused"
            (is (contains? #{:path-escape :absolute-path}
                           (:samizdat.evaluator/error
                            (refuse (str "(project/edit \"../outside.clj\" \""
                                         stat-digest "\" \"a\" \"b\")")))))
            (is (untouched?)))

          (testing "a non-string replacement is refused"
            (is (= :invalid-arguments
                   (:samizdat.evaluator/error
                    (refuse (str "(project/edit \"src/samizdat/d.clj\" \""
                                 stat-digest "\" \"(defn twice [] :x)\" 7)")))))
            (is (untouched?)))

          (testing "a missing target is refused"
            (is (= :missing
                   (:samizdat.evaluator/error
                    (refuse (str "(project/edit \"src/samizdat/nope.clj\" \""
                                 stat-digest "\" \"a\" \"b\")"))))))

          (testing "the operator's run config is refused through the files seam"
            (fs/create-dirs (str root "/.samizdat"))
            (spit (str root "/.samizdat/config.edn") "{:run {:a 1}}")
            (is (= :protected-path
                   (:samizdat.evaluator/error
                    (refuse (str "(project/edit \".samizdat/config.edn\" \""
                                 (digest-of "{:run {:a 1}}")
                                 "\" \"{:run\" \"{:evil\")")))))
            (is (= "{:run {:a 1}}"
                   (file-text (str root "/.samizdat/config.edn"))))))))))

(deftest anchored-replacement-refuses-a-symlink-in-any-component
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [target (str root "/real.clj")
              _ (spit target "(ns real)\n(defn f [] 1)\n")
              _ (fs/create-sym-link (str root "/link.clj") target)
              binding (bind! root "anchored-symlink"
                             {:profile :agent/project-develop})
              e (eval-error evaluate! conn binding
                            (str "(project/edit \"link.clj\" \""
                                 (digest-of "(ns real)\n(defn f [] 1)\n")
                                 "\" \"(defn f [] 1)\" \"(defn f [] 2)\")"))]
          (is (= :symlink (:samizdat.evaluator/error e)))
          (is (= "(ns real)\n(defn f [] 1)\n" (file-text target))
              "the symlink's target is untouched"))))))

(deftest anchored-replacement-records-receipts-and-replays-without-writing
  (when bounded?
    (let [{:keys [bind! evaluate! persist! reconstruct!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [original "(ns e)\n(defn f [] :old)\n"
              path (str root "/src/samizdat/e.clj")
              _ (spit path original)
              run-id (runs/start-run! conn {:problem "anchored replay"
                                            :max-turns 3})
              binding (bind! root run-id {:profile :agent/project-develop})
              _ (persist! conn run-id binding)
              expected (str/replace original ":old" ":new")
              ;; A durable binding is an M3 run: the evaluation must carry the
              ;; InferenceEpoch and the per-call InferenceInvocation that
              ;; dispatched it, exactly as the production path does.
              epoch-id "epoch:anchored-replay"
              _ (inference/begin!
                 conn {:id epoch-id :run-id (str run-id) :branch-id "B1" :turn 1
                       :provider :stub :model "m"
                       :binding-id (:binding/id binding)
                       :spec-id (get-in binding [:spec :spec/coordinate])
                       :runtime (get-in binding [:spec :runtime-coordinate])})
              invocation (:id (inference/invoke!
                               conn {:id "invocation:anchored-replay-1"
                                     :epoch-id epoch-id :run-id (str run-id)
                                     :branch-id "B1" :turn 1}))
              source (str "(let [s (project/stat \"src/samizdat/e.clj\")] "
                          "(project/edit \"src/samizdat/e.clj\" (:digest s) "
                          "\"(defn f [] :old)\" \"(defn f [] :new)\"))")
              _ (evaluate! conn binding source
                           {:inference-epoch-id epoch-id
                            :inference-invocation-id invocation})
              after-write (file-text path)
              rows (store/history
                    conn (:binding/id binding))
              receipts (mapcat :receipts rows)
              edits (filter #(= :project/edit (:op %)) receipts)]

          (testing "the anchored call records an exact four-argument receipt"
            (is (= 1 (count edits)))
            (is (= 4 (count (:args (first edits))))
                "the argument vector distinguishes the anchored form from the
                 whole-file form in durable evidence")
            (is (= "src/samizdat/e.clj" (first (:args (first edits))))))

          (testing "replay consumes the mutation receipt and writes nothing"
            (is (= expected after-write))
            (let [mtime #(str (fs/last-modified-time path))
                  before-mtime (mtime)
                  rebuilt (reconstruct! conn run-id root)]
              (is (some? rebuilt))
              (is (= expected (file-text path))
                  "reconstruction must not re-apply the recorded write")
              (is (= before-mtime (mtime))
                  "an unchanged mtime is the strong form: the file was not
                   rewritten with identical bytes either"))))))))

(deftest whole-file-edit-remains-correct-for-create-and-full-replacement
  (when bounded?
    (let [{:keys [bind! evaluate!]} (evaluator-api)]
      (with-root [root conn]
        (seed-project! root)
        (let [binding (bind! root "whole-file-still-works"
                             {:profile :agent/project-develop})
              source (str "(let [made (project/edit \"fresh.md\" :absent \"one\") "
                          "s (project/stat \"fresh.md\") "
                          "whole (project/edit \"fresh.md\" (:digest s) \"two\")] "
                          "[made whole])")
              [made whole] (:value (evaluate! conn binding source))]
          (testing "3-arity create and whole-file replace are unchanged"
            (is (= (digest-of "one") (:digest made)))
            (is (= (digest-of "two") (:digest whole)))
            (is (= "two" (file-text (str root "/fresh.md"))))))))))


;; ─── M4 attempt 2, gate items C and D: the trusted orientation ─────────────

(deftest trusted-orientation-describes-exactly-the-real-surface
  (when bounded?
    (let [{:keys [bind!]} (evaluator-api)
          orientation (requiring-resolve 'samizdat.evaluator/trusted-orientation)
          universe (vec (tools/tool-names))]
      (with-root [root conn]
        (seed-project! root)
        (let [b (bind! root "orientation" {:profile :agent/project-develop})
              s (surface/of-binding b)
              text (orientation b)]
          (testing "every top-level bounded tool is documented"
            (doseq [t (:top-level s)]
              (is (str/includes? text t) (str "orientation omits " t))))

          (testing "no unavailable top-level tool is documented"
            (is (empty? (surface/unavailable-mentions s universe text))
                "the trusted orientation must not name a tool the binding lacks"))

          (testing "the tool-call envelope is documented"
            (is (str/includes? text "```tool-call"))
            (is (str/includes? text "\"name\""))
            (is (str/includes? text "\"args\"")))

          (testing "semantic operations are marked inside-eval, not top-level"
            (is (re-find #"(?i)only inside eval" text))
            (is (str/includes? text "RIGHT:"))
            (is (str/includes? text "WRONG:"))
            (doseq [op (:operation-names s)]
              (is (str/includes? text op) (str "orientation omits " op))))

          (testing "the anchored mutation is the advertised default"
            (is (str/includes? text "old-text"))
            (is (str/includes? text "new-text"))))))))

(deftest trusted-orientation-narrows-with-the-binding
  (when bounded?
    (let [{:keys [bind!]} (evaluator-api)
          orientation (requiring-resolve 'samizdat.evaluator/trusted-orientation)]
      (with-root [root conn]
        (seed-project! root)
        (let [text (orientation (bind! root "orientation-read"
                                       {:profile :agent/project-read}))]
          (is (not (str/includes? text "project/edit"))
              "a read-only binding must not be told about a mutation it lacks")
          (is (str/includes? text "project/read")))))))
