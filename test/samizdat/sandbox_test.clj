;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.sandbox-test
  "JS1 EvaluatorSpec / Instance / Binding seam tests (deterministic, offline).

   The sandbox needs jolt.sandbox, which needs the vendored SCI source and
   its Maven deps on the source roots — none of which are in samizdat's
   deps.edn.  Direct invocation (from the samizdat project dir), with
   explicit -Scp roots so no dependency is expanded or fetched (one
   line; M2 is $HOME/.m2/repository):

     SAMIZDAT_SANDBOX_TEST_RUN=1 JOLT_CHEZ=/usr/local/bin/scheme JOLT_QUIET=1 /home/chuck/opencode/src/jolt/bin/jolt -Scp "$PWD/src:$PWD/test:/home/chuck/opencode/src/jolt/vendor/sci/src:$M2/borkdude/edamame/1.5.39/edamame-1.5.39.jar.jolt:$M2/org/babashka/sci.impl.types/0.0.3/sci.impl.types-0.0.3.jar.jolt:$M2/borkdude/graal.locking/0.0.2/graal.locking-0.0.2.jar.jolt:$M2/org/clojure/tools.reader/1.5.2/tools.reader-1.5.2.jar.jolt" run "$PWD/test/samizdat/sandbox_test.clj"

   (The .jar.jolt directories are jolt's extracted-jar layout beside each
   cached Maven artifact; -Scp takes source roots verbatim.)

   Under plain `jolt -M:test` the SCI deps are absent, the sandbox ns
   fails to resolve, and every test here skips: this file defines its
   tests only when the require succeeds, and self-runs only when
   SAMIZDAT_SANDBOX_TEST_RUN=1, so the suite runner never double-runs it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is are] :as t]
            [jolt.fs :as fs]))

(def ^:private sandbox-ns-resolved?
  ;; In self-run mode (the direct -Scp invocation) require loudly: a
  ;; compile error in the sandbox ns must fail the run, not masquerade
  ;; as "SCI absent, skipping".  Under the suite runner (-M:test, no SCI
  ;; roots) the catch converts the absent dep into a quiet skip.
  (if (= "1" (jolt.host/getenv "SAMIZDAT_SANDBOX_TEST_RUN"))
    (do (require 'samizdat.agent.sandbox) true)
    (try (require 'samizdat.agent.sandbox) true
         (catch Throwable _ false))))

(when sandbox-ns-resolved?
  (require '[samizdat.agent.sandbox :as sb]))

(defn- with-tmp [f]
  (let [root (str "/tmp/samizdat-sandbox-" (random-uuid))]
    (fs/create-dirs root)
    (try (f root) (finally (fs/delete-tree root)))))

(defn- deny? [f]
  (try (f) false (catch Throwable _ true)))

;; Small append-only implementation of the samizdat.store.evals contract.
;; The direct Jolt+SCI invocation intentionally has no DB dependency on its
;; classpath; production resolves the real store dynamically.  Keeping this
;; adapter as data exercises that exact dynamic seam without weakening the
;; bridge to transcript mocks.
(defn- memory-db []
  (atom {:next-id 1 :records {} :events []}))

(defn- memory-event! [conn event]
  (reset! conn (update @conn :events conj event)))

(defn- memory-begin! [conn intent]
  (let [id (:next-id @conn)
        record (assoc intent :id id :status :pending :result nil :receipts [])]
    (reset! conn (-> @conn
                     (assoc :next-id (inc id))
                     (assoc-in [:records id] record)))
    id))

(defn- memory-record-intent! [conn eval-id {:keys [op args]}]
  (let [record (get-in @conn [:records eval-id])]
    (when-not (= :pending (:status record))
      (throw (ex-info "evaluation is not pending" {:eval-id eval-id})))
    (let [n (count (:receipts record))
          receipt {:seq n :op op :args args :phase :intent}]
      (reset! conn (update-in @conn [:records eval-id :receipts] conj receipt))
      n)))

(defn- memory-record-outcome! [conn eval-id n outcome]
  (let [receipt (get-in @conn [:records eval-id :receipts n])]
    (when-not (= :intent (:phase receipt))
      (throw (ex-info "outcome needs one unsettled intent"
                      {:eval-id eval-id :seq n})))
    (let [settled (if (contains? outcome :result)
                    (assoc receipt :phase :done :result (:result outcome))
                    (assoc receipt :phase :error :error (:error outcome)))]
      (reset! conn (assoc-in @conn [:records eval-id :receipts n] settled))
      n)))

(defn- memory-complete! [conn eval-id {:keys [status result]}]
  (let [record (get-in @conn [:records eval-id])]
    (when-not (= :pending (:status record))
      (throw (ex-info "evaluation already terminal" {:eval-id eval-id})))
    (when (some #(= :intent (:phase %)) (:receipts record))
      (throw (ex-info "unsettled effect" {:eval-id eval-id})))
    (reset! conn (-> @conn
                     (assoc-in [:records eval-id :status] status)
                     (assoc-in [:records eval-id :result] result)))
    true))

(defn- memory-load-eval [conn eval-id]
  (memory-event! conn :load)
  (get-in @conn [:records eval-id]))

(defn- memory-verify-binding!
  "Mirrors samizdat.store.evals/verify-binding!: all four durable identity
  fields — spec, instance, binding, AND the exact spec coordinate — must
  match, so a caller that omits the coordinate (or holds stale authority)
  is denied exactly as the real store denies it."
  [conn eval-id identity]
  (memory-event! conn :verify)
  (let [record (get-in @conn [:records eval-id])]
    (when-not (and record
                   (= (:spec-id identity) (:spec-id record))
                   (= (:instance-id identity) (:instance-id record))
                   (= (:binding-id identity) (:binding-id record))
                   (= (:coordinate identity) (:coordinate record)))
      (throw (ex-info "durable evaluation binding mismatch"
                      {:eval-id eval-id})))
    true))

(def ^:private memory-eval-store
  {:begin! memory-begin!
   :record-intent! memory-record-intent!
   :record-outcome! memory-record-outcome!
   :complete! memory-complete!
   :load-eval memory-load-eval
   :verify-binding! memory-verify-binding!})

(defn- with-eval-store
  "Run `f` with the sandbox's durable-eval seam set to `store` (the memory
  adapter, or a wrapper over it).  alter-var-root on a runtime-resolved
  var, NOT `binding`: jolt analyzes binding's var at load time, but this
  file must stay loadable — quietly skipped — where SCI, and with it the
  `sb` alias, is absent (the `jolt -M:test` suite shape).  The root (nil
  in production) is restored afterward."
  [store f]
  (let [v (resolve 'samizdat.agent.sandbox/*eval-store*)
        original (deref v)]
    (alter-var-root v (constantly store))
    (try (f)
         (finally (alter-var-root v (constantly original))))))

(when sandbox-ns-resolved?

  ;; ─── EvaluatorSpec: trusted catalog, inert self-certifying coordinate ───

  (deftest spec-coordinate-is-deterministic-and-content-addressed
    (with-tmp
      (fn [root]
        (let [s1 (sb/spec :project/develop {:root root})
              s2 (sb/spec :project/develop {:root root})
              s3 (sb/spec :project/develop {:root root
                                            :capabilities #{:project/read}})]
          (is (= :evaluator-spec (:samizdat.sandbox/kind s1)))
          (is (= :agent/project-develop (:profile s1)))
          (is (= [:project/edit :project/list :project/read
                  :project/search :project/stat]
                 (:capabilities s1))
              "preset capabilities are explicit and sorted")
          (is (= 30000 (:timeout-ms s1)))
          (is (= #{:max-read-chars :max-list-entries
                   :max-search-results :search-max-chars}
                 (set (keys (:bounds s1)))))
          (is (= (:spec/coordinate s1) (:spec/coordinate s2))
              "same content, same coordinate")
          (is (not= (:spec/coordinate s1) (:spec/coordinate s3))
              "narrower capabilities change the coordinate")
          (is (str/starts-with? (:spec/coordinate s1) "js1:"))
          (is (= (str (fs/canonicalize root)) (:root s1)))))))

  (deftest spec-catalog-is-closed-and-attenuation-only
    (with-tmp
      (fn [root]
        (is (deny? #(sb/spec :no/such-preset {:root root}))
            "unknown preset is denied")
        (is (deny? #(sb/spec :project/read
                             {:root root :capabilities #{:project/edit}}))
            "controller cannot select capabilities beyond the preset")
        (is (deny? #(sb/spec :project/read {}))
            "a trusted root is required")
        (is (deny? #(sb/spec :project/read {:root root :max-read-chars 0}))
            "bounds must be positive")
        (is (= #{:project/read :project/list :project/search :project/stat}
               (set (:capabilities
                     (sb/spec :project/read {:root root}))))
            "the read preset carries exactly the observation ops"))))

  ;; ─── Provider: JS1 :main policy, binding persistence, isolation ───

  (deftest same-binding-preserves-def
    (with-tmp
      (fn [root]
        (let [p (sb/provider)
              b (sb/bind! p "work-1" {:preset :project/develop :root root})]
          (is (= 7 (sb/evaluate! b "(def kept 6) (inc kept)")))
          (is (= 42 (sb/evaluate! b "(* kept 7)"))
              "the same binding evaluates in the same persistent context")
          (let [b2 (sb/bind! p "work-1" {:preset :project/develop :root root})]
            (is (= (:binding/id b) (:binding/id b2))
                "rebinding a work-id is idempotent")
            (is (= 42 (sb/evaluate! b2 "(* kept 7)"))
                "ordinary work deterministically shares the :main instance"))))))

  (deftest a-second-instance-is-isolated
    (with-tmp
      (fn [root]
        (let [p (sb/provider)
              main (sb/bind! p "work-main" {:preset :project/develop :root root})
              _ (sb/evaluate! main "(def shared-secret 1)")
              side (sb/bind! p "work-side" {:preset :project/develop
                                            :root root
                                            :instance/key :side})]
          (is (= "inst:main" (:instance/id main)))
          (is (= "inst:side" (:instance/id side)))
          (is (deny? #(sb/evaluate! side "shared-secret"))
              "a second controller-created instance lacks the def")
          (is (= 2 (sb/evaluate! main "(inc shared-secret)"))
              "the persistent :main instance is undisturbed")))))

  (deftest instance-spec-conflict-fails-closed
    (with-tmp
      (fn [root]
        (let [p (sb/provider)]
          (sb/bind! p "w1" {:preset :project/develop :root root})
          (is (deny? #(sb/bind! p "w2" {:preset :project/read :root root}))
              "a different spec for :main conflicts instead of silently widening")
          (is (deny? #(sb/bind! p "w1" {:preset :project/develop
                                        :root root :instance/key :other}))
              "rebinding a work-id to a different instance conflicts")))))

  ;; ─── Authority: controller attenuation cannot be widened from source ───

  (deftest narrowed-authority-cannot-widen-via-source
    (with-tmp
      (fn [root]
        (spit (str root "/a.txt") "hello")
        (let [p (sb/provider)
              b (sb/bind! p "work-ro" {:preset :project/develop
                                       :root root
                                       :instance/key :narrow
                                       :capabilities #{:project/read}})]
          (is (= "hello" (:content (sb/evaluate! b "(project/read \"a.txt\")")))
              "the granted op still works")
          (is (deny? #(sb/evaluate! b "(project/edit \"a.txt\" :absent \"x\")"))
              "source cannot invoke a capability the controller did not grant")
          (is (deny? #(sb/evaluate! b "(jolt.host/getenv \"HOME\")"))
              "source cannot reach host authority outside the projection")
          (is (= [:project/read] (sb/capabilities b))
              "capability discovery reads effective authority")
          (is (deny? #(sb/evaluate! b "1" {:preset :project/develop}))
              "evaluation rejects preset selection")
          (is (deny? #(sb/evaluate! b "1" {:capabilities #{:project/edit}}))
              "evaluation rejects capability selection")
          (is (deny? #(sb/evaluate! b "1" {:profile :agent/project-develop}))
              "evaluation rejects profile selection")
          (is (deny? #(sb/acquire! p (assoc (:spec b)
                                            :capabilities [:project/read
                                                           :project/edit])
                                   {:instance/key :crafted}))
              "a hand-edited spec fails the coordinate integrity check")))))

  ;; ─── describe: the three coordinates, inert and distinguishable ───

  (deftest describe-carries-binding-spec-and-instance-ids
    (with-tmp
      (fn [root]
        (let [p (sb/provider)
              b (sb/bind! p "work-9" {:preset :project/read :root root})
              d (sb/describe b)]
          (is (= :binding (:samizdat.sandbox/kind d)))
          (is (= "bind:main:work-9" (:samizdat.sandbox/binding-id d)))
          (is (= "work-9" (:samizdat.sandbox/work-id d)))
          (is (= "inst:main" (:samizdat.sandbox/instance-id d)))
          (is (= :main (:samizdat.sandbox/instance-key d)))
          (is (= :project/read (:samizdat.sandbox/preset d)))
          (is (str/starts-with? (:samizdat.sandbox/spec-coordinate d) "js1:"))
          (is (= (str (fs/canonicalize root))
                 (:samizdat.sandbox/root-canonical d)))
          (is (= 30000 (:samizdat.sandbox/timeout-ms d)))
          (is (= #{:project/read :project/list :project/search :project/stat}
                 (set (sb/capabilities d)))
              "capabilities can be discovered from a describe map")
          ;; A legacy direct context has the spec coordinate but no
          ;; preset, instance, or binding identity.
          (let [ctx (sb/new {:root root :profile :agent/project-read})
                dc (sb/describe ctx)]
            (is (= :context (:samizdat.sandbox/kind dc)))
            (is (str/starts-with? (:samizdat.sandbox/spec-coordinate dc) "js1:"))
            (is (nil? (:samizdat.sandbox/preset dc)))
            (is (nil? (:samizdat.sandbox/instance-id dc)))
            (is (nil? (:samizdat.sandbox/binding-id dc))))))))

  ;; ─── Safe discovery: host-derived over effective authority ───

  (deftest safe-discovery-is-host-derived-over-effective-authority
    (with-tmp
      (fn [root]
        (spit (str root "/d.txt") "x")
        (let [p (sb/provider)
              ro (sb/bind! p "disc-ro" {:preset :project/develop :root root
                                        :capabilities #{:project/read}})
              dev (sb/bind! p "disc-dev" {:preset :project/develop :root root
                                          :instance/key :dev})]
          ;; doc describes a granted capability from inert authority data
          (let [d (sb/operation-doc ro "project/read")]
            (is (map? d))
            (is (= "project/read" (:name d)))
            (is (= '[rel-path] (first (:arglists d))))
            (is (str/includes? (:doc d) "observation")))
          (is (= "project/read" (:name (sb/operation-doc ro ":project/read")))
              "a leading colon is tolerated")
          ;; an ungranted capability is not discoverable
          (is (nil? (sb/operation-doc ro "project/edit")))
          ;; hostile symbol text is inert data to match, never source to run
          (is (nil? (sb/operation-doc ro
                                      "project/read (jolt.host/getenv \"HOME\")")))
          (is (nil? (sb/operation-doc ro "project/read\" (do (evil))")))
          (is (nil? (sb/operation-doc ro 42)))
          ;; the full develop preset discovers its edit op, with arglists
          (let [d (sb/operation-doc dev "project/edit")]
            (is (= "project/edit" (:name d)))
            (is (= '[rel-path base-digest new-content] (first (:arglists d))))
            (is (str/includes? (:doc d) "actuation")))
          ;; completion: authorized projected names only, prefix-narrowed
          (is (= ["project/read"] (sb/complete-capability ro "")))
          (is (= ["project/read"] (sb/complete-capability ro "project/r")))
          (is (= [] (sb/complete-capability ro "project/e")))
          (is (= [] (sb/complete-capability ro "read")))
          (is (= 5 (count (sb/complete-capability dev ""))))
          (is (= ["project/read"] (sb/complete-capability dev "project/re")))
          ;; a hostile prefix can only narrow; it never evaluates or errors
          (is (= [] (sb/complete-capability dev "\" (do (evil))")))
          ;; the sandbox vocabulary denies the forbidden introspection forms
          ;; outright — host-derived discovery is the only door to them
          (is (deny? #(sb/evaluate! ro "(resolve 'project/read)")))
          (is (deny? #(sb/evaluate! ro "(find-ns 'project)")))
          (is (deny? #(sb/evaluate! ro "(ns-publics 'project)")))
          (is (deny? #(sb/evaluate! ro "(meta 'project/read)")))
          ;; discovery claims no evaluation ownership: eval still works
          (is (= 2 (sb/evaluate! ro "(inc 1)")))))))

  ;; ─── Semantic ops still behave under the seam ───

  (deftest semantic-ops-bounded-anchored-and-confined
    (with-tmp
      (fn [root]
        (let [ctx (sb/new {:root root :profile :agent/project-develop})]
          (let [created (sb/evaluate! ctx "(project/edit \"doc.txt\" :absent \"hello world\")")]
            (is (:created? created))
            ;; Digests come from files/file-digest, which needs the
            ;; jolt-crypto natives; under the offline -Scp roots those are
            ;; absent and the digest is nil (fail-closed: updates then
            ;; stale-conflict rather than blindly overwriting).
            (is (or (nil? (:digest created))
                    (str/starts-with? (:digest created) "sha256:"))))
          (let [st (sb/evaluate! ctx "(project/stat \"doc.txt\")")]
            (is (:exists st))
            (is (= :file (:type st))))
          (is (= "hello world"
                 (:content (sb/evaluate! ctx "(project/read \"doc.txt\")"))))
          (is (deny? #(sb/evaluate! ctx "(project/read \"../escape\")"))
              "path escape is denied")
          (is (deny? #(sb/evaluate! ctx "(project/edit \"doc.txt\" :absent \"again\")"))
              "no blind overwrite of an existing file")))))

  ;; ─── rebuild!: fresh context, stable identity ───

  (deftest rebuild-clears-definitions-and-keeps-identity
    (with-tmp
      (fn [root]
        (let [p (sb/provider)
              b (sb/bind! p "work-r" {:preset :project/develop :root root})]
          (sb/evaluate! b "(def ephemeral 5)")
          (let [b2 (sb/rebuild! b)]
            (is (= (:binding/id b) (:binding/id b2))
                "binding id is stable across rebuild")
            (is (= (:instance/id b) (:instance/id b2))
                "instance id is stable across rebuild")
            (is (deny? #(sb/evaluate! b2 "ephemeral"))
                "rebuild yields a fresh context")
            (let [b3 (sb/bind! p "work-r2" {:preset :project/develop :root root})]
              (is (deny? #(sb/evaluate! b3 "ephemeral"))
                  "the provider registry sees the rebuilt instance"))))
        (let [ctx (sb/new {:root root :profile :agent/project-read})]
          (sb/evaluate! ctx "(def z 9)")
          (is (= 9 (sb/evaluate! ctx "z")))
           (is (deny? #(sb/evaluate! (sb/rebuild! ctx) "z"))
               "legacy contexts rebuild fresh as well"))))))

(when sandbox-ns-resolved?

  ;; ─── Durable JS1 bridge: intent/outcome and hermetic exact replay ───

  (deftest recorded-rebuild-restores-definitions-without-repeating-real-ops
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  p (sb/provider)
                  b (sb/bind! p "recorded" {:preset :project/develop :root root})
                  source (str "(def rebuilt-value 40) "
                              "(project/edit \"once.txt\" :absent \"recorded\") "
                              "(inc rebuilt-value)")
                  {:keys [eval-id value]} (sb/evaluate-recorded! conn b source)
                  record (get-in @conn [:records eval-id])]
              (is (= 41 value))
              (is (= :completed (:status record)))
              (is (= [0] (mapv :seq (:receipts record))))
              (is (= [:project/edit] (mapv :op (:receipts record))))
              (is (= [:done] (mapv :phase (:receipts record))))
              (is (= "recorded" (slurp (str root "/once.txt"))))
              (is (:exists (sb/evaluate! b "(project/stat \"once.txt\")"))
                  "the durable hook is removed before ordinary evaluation resumes")

              ;; If replay reaches the real edit it conflicts with this existing
              ;; file.  Success plus unchanged content is direct native evidence
              ;; that Jolt served the historical receipt instead.
              (spit (str root "/once.txt") "outside-change")
              (let [b2 (sb/rebuild-recorded! conn b eval-id)]
                (is (= (:binding/id b) (:binding/id b2)))
                (is (= (:instance/id b) (:instance/id b2)))
                (is (= 40 (sb/evaluate! b2 "rebuilt-value"))
                    "the stored definition was rebuilt in the same logical instance")
                (is (= "outside-change" (slurp (str root "/once.txt")))
                    "replay performed zero real project actuations")
                ;; Repetition from the newly published instance is exact too.
                (let [b3 (sb/rebuild-recorded! conn b2 eval-id)]
                  (is (= 42 (sb/evaluate! b3 "(+ rebuilt-value 2)")))
                  (is (= "outside-change"
                         (slurp (str root "/once.txt"))))
                  ))))))))

  (deftest binding-mismatch-denies-before-load-or-operation
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  b (sb/bind! (sb/provider) "bound-work"
                              {:preset :project/develop :root root})
                  {:keys [eval-id]}
                  (sb/evaluate-recorded!
                   conn b "(project/edit \"mismatch.txt\" :absent \"made\")")
                  forged (assoc b :binding/id "bind:main:somebody-else")]
              (fs/delete (str root "/mismatch.txt"))
              (reset! conn (assoc @conn :events []))
              (is (deny? #(sb/rebuild-recorded! conn forged eval-id)))
              (is (= [:verify] (:events @conn))
                  "identity denial precedes even loading replay material")
              (is (not (fs/exists? (str root "/mismatch.txt")))
                  "the denied source performed no real operation")
              ))))))

  (deftest rebuild-verify-carries-exact-current-coordinate
    (with-tmp
      (fn [root]
        (let [captured (atom nil)
              store (assoc memory-eval-store
                           :verify-binding!
                           (fn [conn eval-id identity]
                             (reset! captured identity)
                             ((:verify-binding! memory-eval-store)
                              conn eval-id identity)))]
          (with-eval-store store
            (fn []
              (let [conn (memory-db)
                    b (sb/bind! (sb/provider) "coord-work"
                                {:preset :project/develop :root root})
                    {:keys [eval-id]}
                    (sb/evaluate-recorded!
                      conn b "(project/edit \"coord.txt\" :absent \"c\")")
                    record (get-in @conn [:records eval-id])]
                ;; The identity handed to verify-binding! carries the exact
                ;; current spec coordinate — the fourth field the store
                ;; compares.  Omit it and every replay is denied (blank
                ;; expected coordinate); hold stale authority and this one is.
                (is (map? (sb/rebuild-recorded! conn b eval-id))
                    "a matching coordinate verifies and replays")
                (is (string? (:coordinate @captured)))
                (is (= (:coordinate record) (:coordinate @captured))
                    "verify-binding! receives the coordinate the record began with")

                ;; A record whose stored coordinate does not match the
                ;; binding's current authority is denied AT verify-binding!,
                ;; before the record is loaded or any receipt replayed.
                (let [conn2 (memory-db)
                      b2 (sb/bind! (sb/provider) "coord-work2"
                                   {:preset :project/develop :root root})
                      {:keys [eval-id]}
                      (sb/evaluate-recorded!
                        conn2 b2 "(project/edit \"coord2.txt\" :absent \"c2\")")]
                  (fs/delete (str root "/coord2.txt"))
                  (reset! conn2 (-> @conn2
                                    (assoc :events [])
                                    (assoc-in [:records eval-id :coordinate]
                                              "js0:forged")))
                  (is (deny? #(sb/rebuild-recorded! conn2 b2 eval-id))
                      "a coordinate mismatch denies at verify-binding!")
                  (is (= [:verify] (:events @conn2))
                      "the denial precedes loading the replay material")
                  (is (not (fs/exists? (str root "/coord2.txt")))
                      "no replay actuation reached the project")))))))))

  (deftest pending-edit-intent-denies-resume
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  b (sb/bind! (sb/provider) "pending-work"
                              {:preset :project/develop :root root})
                  {:keys [eval-id]} (sb/evaluate-recorded! conn b "1")
                  complete-record (get-in @conn [:records eval-id])
                  pending-record (assoc complete-record
                                        :status :pending
                                        :result nil
                                        :source "(project/edit \"unknown.txt\" :absent \"maybe\")"
                                        :receipts [{:seq 0
                                                    :op :project/edit
                                                    :args ["unknown.txt" :absent "maybe"]
                                                    :phase :intent}])]
              ;; Model the append-only crash shape: begin + edit intent landed,
              ;; but neither outcome nor completion did.
              (reset! conn (assoc-in @conn [:records eval-id] pending-record))
              (is (deny? #(sb/rebuild-recorded! conn b eval-id))
                  "a pending actuation is never guessed or resumed")
              (is (not (fs/exists? (str root "/unknown.txt")))
                  "resume denial performs no project actuation")

              ;; A terminal marker does not make malformed history safe.  A gap
              ;; in operation order is denied before source reaches project/edit.
              (reset! conn
                      (-> @conn
                          (assoc-in [:records eval-id :status] :completed)
                          (assoc-in [:records eval-id :result] {:value nil})
                          (assoc-in [:records eval-id :receipts]
                                    [{:seq 1
                                      :op :project/edit
                                      :args ["unknown.txt" :absent "maybe"]
                                      :phase :done
                                      :result {:path "unknown.txt"}}])))
              (is (deny? #(sb/rebuild-recorded! conn b eval-id))
                  "malformed non-contiguous receipts fail closed")
              (is (not (fs/exists? (str root "/unknown.txt"))))
              (is (= 2 (sb/evaluate! b "(+ 1 1)"))
                  "denied rebuild releases instance ownership")
              )))))))

;; Self-run only on explicit request (the direct -Scp invocation in the
;; docstring).  `jolt -M:test` requires this namespace without SCI on the
;; roots, so sandbox-ns-resolved? is false there and nothing runs; the
;; suite runner would otherwise execute the tests a second time at load.
(when (and sandbox-ns-resolved?
           (= "1" (jolt.host/getenv "SAMIZDAT_SANDBOX_TEST_RUN")))
  (let [{:keys [fail error] :as summary} (t/run-tests 'samizdat.sandbox-test)]
    (println summary)
    (if (pos? (+ (or fail 0) (or error 0)))
      (throw (ex-info "samizdat sandbox tests failed" {:fail fail :error error}))
      (println "SANDBOX-TEST OK"))))
