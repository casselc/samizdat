;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evaluator-test
  "M1's deterministic bounded lane. Ordinary tests load this namespace with no
  SCI and execute only the explicit skip assertion; bin/js1-m1 test selects the
  exact pinned runtime and sets SAMIZDAT_BOUNDED_TEST=1."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.security.policy :as policy]
            [samizdat.store.db :as db]
            [samizdat.store.evaluator :as store]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as workflow]))

(def bounded? (= "1" (jolt.host/getenv "SAMIZDAT_BOUNDED_TEST")))

(defn- evaluator-api []
  (when bounded?
    {:bind! (requiring-resolve 'samizdat.evaluator/bind!)
     :describe (requiring-resolve 'samizdat.evaluator/describe)
     :evaluate! (requiring-resolve 'samizdat.evaluator/evaluate-recorded!)
     :rebuild! (requiring-resolve 'samizdat.evaluator/rebuild!)
     :complete (requiring-resolve 'samizdat.evaluator/complete)
     :doc (requiring-resolve 'samizdat.evaluator/doc)
     :leverage (requiring-resolve 'samizdat.evaluator/leverage)}))

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
            (let [after (workflow/run-turn ctx branch 1)]
              (is (some #(str/includes? (:content %) "src/samizdat/a.clj")
                        (:messages after)))
              (is (= "eval" (:tool_name (last (journal/turns conn run-id)))))
              (is (= [:project/search]
                     (mapv :op (:receipts (first (store/history conn (:binding/id binding)))))))))
          (with-redefs [infer/complete-fn (fake-complete (tool-call "shell" {:command "touch pwned"}))
                        policy/run-shell (fn [& _] (swap! shell-runs inc))]
            (let [after (workflow/run-turn ctx branch 2)]
              (is (some #(str/includes? (:content %) "outside this bounded context")
                        (:messages after)))
              (is (zero? @shell-runs))
              (is (not (fs/exists? (str root "/pwned")))))
          (let [done (tools/run-tool (assoc ctx :branch branch :turn 3
                                            :tool-name "done" :args {}))]
            (is (:verification-unavailable done))
            (is (:completion-refused done))
            (is (not (:done? done))))))))))
