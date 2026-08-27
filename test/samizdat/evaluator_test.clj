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
        (doseq [profile [:agent/project-develop :agent/minimal :agent/shell
                         "agent/admin" :project/read]]
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
