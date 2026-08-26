;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.sandbox-test
  "JS1 EvaluatorSpec / Instance / Binding seam tests (deterministic, offline).

   The sandbox needs jolt.sandbox, which needs the vendored SCI source and
   its Maven deps on the source roots — none of which are in samizdat's
   deps.edn.  Digests additionally need jolt-crypto's source root (its
   MessageDigest shim plus libcrypto, which samizdat.agent.files bootstraps
   for -Scp runs that load no natives).  Direct invocation (from the
   samizdat project dir), with explicit -Scp roots so no dependency is
   expanded or fetched (one line; M2 is $HOME/.m2/repository):

     SAMIZDAT_SANDBOX_TEST_RUN=1 JOLT_CHEZ=/usr/local/bin/scheme JOLT_QUIET=1 /home/chuck/opencode/src/jolt/bin/jolt -Scp \"$PWD/src:$PWD/test:$HOME/.gitlibs/libs/jolt-lang/jolt-crypto/1ab72aa5f73be7ec41f01086953ffb43ecd3d84e/src:/home/chuck/opencode/src/jolt/vendor/sci/src:$M2/borkdude/edamame/1.5.39/edamame-1.5.39.jar.jolt:$M2/org/babashka/sci.impl.types/0.0.3/sci.impl.types-0.0.3.jar.jolt:$M2/borkdude/graal.locking/0.0.2/graal.locking-0.0.2.jar.jolt:$M2/org/clojure/tools.reader/1.5.2/tools.reader-1.5.2.jar.jolt\" run \"$PWD/test/samizdat/sandbox_test.clj\"

   (The .jar.jolt directories are jolt's extracted-jar layout beside each
   cached Maven artifact; -Scp takes source roots verbatim.  Without the
   jolt-crypto root every digest fails closed by contract, and the
   digest-dependent assertions below fail.)

   Under plain `jolt -M:test` the SCI deps are absent, the sandbox ns
   fails to resolve, and every test here skips: this file defines its
   tests only when the require succeeds, and self-runs only when
   SAMIZDAT_SANDBOX_TEST_RUN=1, so the suite runner never double-runs it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is are] :as t]
            [jolt.fs :as fs]
            [samizdat.agent.files :as files]))

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

(def ^:private digest-available?
  "Whether the digest machinery works in this process (it does when
   jolt-crypto's root is on the -Scp roots, which the documented direct
   invocation includes; samizdat.agent.files bootstraps libcrypto for it).
   Every digest assertion runs under this gate so a crypto-less run fails
   ONLY the fail-on-error assertions, never with a nil fake coordinate."
  (try (do (files/bytes-digest (.getBytes "probe" "UTF-8")) true)
       (catch Throwable _ false)))

(defn- with-tmp [f]
  (let [root (str "/tmp/samizdat-sandbox-" (random-uuid))]
    (fs/create-dirs root)
    (try (f root) (finally (fs/delete-tree root)))))

(defn- deny? [f]
  (try (f) false (catch Throwable _ true)))

(defn- thrown-data
  "The {:samizdat.sandbox/error …} ex-data of a failure, digging through
   the SCI wrapper's cause chain to the host error the operation raised."
  [f]
  (try (f) nil
       (catch Throwable e
         (loop [e e]
           (cond
             (:samizdat.sandbox/error (ex-data e)) (ex-data e)
             (ex-cause e) (recur (ex-cause e))
             :else (ex-data e))))))

(defn- thrown-code
  "The {:samizdat.sandbox/error …} code of a failure, digging through the
   SCI wrapper's cause chain to the host error the operation raised."
  [f]
  (try (f) nil
       (catch Throwable e
         (loop [e e]
           (cond
             (:samizdat.sandbox/error (ex-data e)) (:samizdat.sandbox/error (ex-data e))
             (ex-cause e) (recur (ex-cause e))
             :else nil)))))

(defn- write-bytes!
  "Raw bytes to a file — the only honest way to fixture invalid UTF-8."
  [path ^bytes bs]
  (let [out (java.io.FileOutputStream. path)]
    (try (.write out bs)
         (finally (try (.close out) (catch Throwable _ nil))))))

(defn- known-sha256
  "The expected sha256:… coordinate of `s`'s UTF-8 bytes."
  [^String s]
  (str "sha256:" (files/bytes-digest (.getBytes s "UTF-8"))))

;; Small append-only implementation of the samizdat.store.evals contract.
;; The direct Jolt+SCI invocation intentionally has no DB dependency on its
;; classpath; production resolves the real store dynamically.  Keeping this
;; adapter as data exercises that exact dynamic seam without weakening the
;; bridge to transcript mocks.  Rows mirror the real store's read shape:
;; underscore identity columns, a per-binding :binding_seq total order, and
;; the :runtime coordinate.
(defn- memory-db []
  (atom {:next-id 1 :records {} :events []}))

(defn- memory-event! [conn event]
  (reset! conn (update @conn :events conj event)))

(defn- memory-begin! [conn intent]
  (let [id (:next-id @conn)
        binding-seq (count (filter #(= (:binding-id intent) (:binding_id %))
                                   (vals (:records @conn))))
        record {:id id
                :spec_id (:spec-id intent)
                :instance_id (:instance-id intent)
                :binding_id (:binding-id intent)
                :binding_seq binding-seq
                :coordinate (:coordinate intent)
                :runtime (:runtime intent)
                :source (:source intent)
                :status :pending :result nil :receipts []}]
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
  "Mirrors samizdat.store.evals/verify-binding!: all five durable identity
   fields — spec, instance, binding, the exact authority coordinate, AND the
   runtime coordinate — must match, so a caller that omits a field (or holds
   stale authority or a stale runtime) is denied exactly as the real store
   denies it."
  [conn eval-id identity]
  (memory-event! conn :verify)
  (let [record (get-in @conn [:records eval-id])
        expected {:spec_id (:spec-id identity)
                  :instance_id (:instance-id identity)
                  :binding_id (:binding-id identity)
                  :coordinate (:coordinate identity)
                  :runtime (:runtime identity)}]
    (when-not (and record
                   (= expected (select-keys record (keys expected))))
      (throw (ex-info "durable evaluation binding mismatch"
                      {:eval-id eval-id})))
    true))

(defn- memory-history
  "Mirrors samizdat.store.evals/history: every record for the binding in the
   binding's durable total order (binding_seq ascending), statuses included."
  [conn binding-id]
  (memory-event! conn :history)
  (->> (vals (:records @conn))
       (filter #(= binding-id (:binding_id %)))
       (sort-by :binding_seq)
       vec))

(def ^:private memory-eval-store
  {:begin! memory-begin!
   :record-intent! memory-record-intent!
   :record-outcome! memory-record-outcome!
   :complete! memory-complete!
   :load-eval memory-load-eval
   :verify-binding! memory-verify-binding!
   :history memory-history})

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
                   :max-search-results :search-max-chars
                   :search-max-files :max-write-bytes}
                 (set (keys (:bounds s1))))
              "six bounds: the four originals plus the frozen-contract
               search file-count and edit write-byte limits")
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
          (is (= "hello" (sb/evaluate! b "(project/read \"a.txt\")"))
              "the granted op still works, returning the decoded string")
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

  ;; ─── Safe discovery: host-derived union of effective authority and the
  ;; ─── reviewed language surface — never live introspection ───────────

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
            (is (= '[rel-path base new-content] (first (:arglists d))))
            (is (str/includes? (:doc d) "actuation")))
          ;; discovery claims no evaluation ownership: eval still works
          (is (= 2 (sb/evaluate! ro "(inc 1)")))))))

  (deftest safe-discovery-unions-reviewed-symbols-with-effective-operations
    (with-tmp
      (fn [root]
        (let [p (sb/provider)
              ro (sb/bind! p "union-ro" {:preset :project/develop :root root
                                         :capabilities #{:project/read}})
              dev (sb/bind! p "union-dev" {:preset :project/develop :root root
                                           :instance/key :dev2})
              surface-count (count (sb/complete-capability dev ""))
              op-names (filter #(str/starts-with? % "project/")
                               (sb/complete-capability dev ""))]
          ;; doc answers for reviewed pure symbols from the trusted static
          ;; language surface — curated entries carry arglists…
          (let [d (sb/operation-doc ro "map")]
            (is (map? d))
            (is (= "map" (:name d)))
            (is (= '[f coll] (first (:arglists d))))
            (is (str/includes? (:doc d) "pure")))
          ;; …and every other reviewed symbol gets the generic surface doc
          (let [d (sb/operation-doc ro "zipmap")]
            (is (= "zipmap" (:name d)))
            (is (str/includes? (:doc d) "Reviewed pure language-surface")))
          (is (= "map" (:name (sb/operation-doc ro ":map")))
              "a leading colon is tolerated for pure symbols too")
          ;; symbols outside the reviewed surface answer nothing
          (is (nil? (sb/operation-doc ro "eval"))
              "eval is not in the reviewed vocabulary")
          (is (nil? (sb/operation-doc ro "jolt.host/getenv"))
              "host namespaces are not discoverable")
          (is (nil? (sb/operation-doc ro "resolve"))
              "introspection is not part of the surface")
          (is (nil? (sb/operation-doc ro "clojure.string/join")))
          ;; completion is the UNION: operations plus pure symbols, sorted
          (is (= ["map" "map?" "mapcat" "mapv" "max"]
                 (sb/complete-capability dev "ma")))
          (is (= ["project/edit" "project/list" "project/read"
                  "project/search" "project/stat"]
                 op-names)
              "the five effective operations are all and only the project/ names")
          (is (= 5 (count op-names)))
          (is (< 150 surface-count)
              "the union carries the reviewed surface (156 symbols at v1)")
          (is (= 4 (- surface-count (count (sb/complete-capability ro ""))))
              "the reviewed surface is unconditional; only the ops track grants
               (dev has five, ro has one)")
          ;; an ungranted operation never completes, pure symbols always do
          (is (= [] (sb/complete-capability ro "project/e")))
          (is (sb/complete-capability ro "proj")
              "granted project/ names complete for the read-only binding")
          ;; a hostile prefix can only narrow; it never evaluates or errors
          (is (= [] (sb/complete-capability dev "\" (do (evil))")))
          ;; the surface is stable within the process (trusted static data)
          (is (= (sb/complete-capability dev "ma")
                 (sb/complete-capability dev "ma")))))))

  ;; ─── The five projected ops, normalized against the frozen A2/A3b
  ;; ─── contract — positive shapes ────────────────────────────────────

  (deftest semantic-ops-bounded-anchored-and-confined
    (with-tmp
      (fn [root]
        (let [ctx (sb/new {:root root :profile :agent/project-develop})]
          (let [created (sb/evaluate! ctx "(project/edit \"doc.txt\" :absent \"hello world\")")]
            (is (map? created))
            (is (= "doc.txt" (:path created)))
            (is (pos? (:bytes created)))
            (when digest-available?
              (is (= (known-sha256 "hello world") (:digest created))
                  "the result digest is the new content's real coordinate")))
          (let [st (sb/evaluate! ctx "(project/stat \"doc.txt\")")]
            (is (= :file (:kind st)))
            (is (= 11 (:bytes st)))
            (when digest-available?
              (is (= (known-sha256 "hello world") (:digest st)))))
          (is (= "hello world"
                 (sb/evaluate! ctx "(project/read \"doc.txt\")")))
          (is (deny? #(sb/evaluate! ctx "(project/read \"../escape\")"))
              "path escape is denied")
          (is (deny? #(sb/evaluate! ctx "(project/edit \"doc.txt\" :absent \"again\")"))
              "no blind overwrite of an existing file")))))

  ;; ─── A2/A3b conformance: project/read ───

  (deftest project-read-is-bounded-strict-utf8-and-symlink-refusing
    (with-tmp
      (fn [root]
        (spit (str root "/a.txt") "hello")
        (spit (str root "/multi.txt") "héllo — wörld")
        (fs/create-dirs (str root "/sub"))
        (fs/create-sym-link (str root "/link.txt") (str root "/a.txt"))
        (fs/create-sym-link (str root "/linkdir") (str root "/sub"))
        (write-bytes! (str root "/invalid.txt")
                      (byte-array [(byte 0x68) (unchecked-byte 0xc3) (byte 0x28)]))
        (let [ctx (sb/new {:root root :profile :agent/project-develop
                           :max-read-chars 100})]
          (is (= "hello" (sb/evaluate! ctx "(project/read \"a.txt\")"))
              "returns the decoded string, nothing else")
          (is (= "héllo — wörld" (sb/evaluate! ctx "(project/read \"multi.txt\")"))
              "multibyte UTF-8 decodes exactly")
          (is (= "hello" (sb/evaluate! ctx "(project/read \"./a.txt\")"))
              "lexically normalized paths read the same file")
          (is (= "hello" (sb/evaluate! ctx "(project/read \"sub/../a.txt\")")))
          ;; strict UTF-8: malformed input is an error, never mojibake
          (is (= :invalid-utf8
                 (thrown-code #(sb/evaluate! ctx "(project/read \"invalid.txt\")"))))
          ;; bounds fail rather than truncate — consumption stops at the bound
          (spit (str root "/over-byte.txt") (apply str (repeat 500 "x")))
          (is (= :too-large
                 (thrown-code #(sb/evaluate! ctx "(project/read \"over-byte.txt\")")))
              "the derived byte ceiling (4× the char bound) fails first")
          (spit (str root "/over-char.txt") (apply str (repeat 350 "y")))
          (is (= :too-large
                 (thrown-code #(sb/evaluate! ctx "(project/read \"over-char.txt\")")))
              "within the byte ceiling but over the char bound still fails")
          ;; non-regular targets are refused
          (are [code src] (= code (thrown-code #(sb/evaluate! ctx src)))
            :not-file "(project/read \"missing.txt\")"
            :not-file "(project/read \"sub\")"
            :not-file "(project/read \".\")"
            :not-file "(project/read \"link.txt\")"
            :symlink "(project/read \"linkdir/a.txt\")"
            :path-escape "(project/read \"../outside.txt\")"
            :absolute-path (str "(project/read \"" root "/a.txt\")"))
          (is (deny? #(sb/evaluate! ctx "(project/read)"))
              "exactly one path argument")))))

  ;; ─── A2/A3b conformance: project/list ───

  (deftest project-list-is-exactly-one-level-structured-and-sorted
    (with-tmp
      (fn [root]
        (spit (str root "/b.txt") "bb")
        (spit (str root "/a.txt") "hello")
        (fs/create-dirs (str root "/sub/deep"))
        (spit (str root "/sub/inner.txt") "inner")
        (fs/create-sym-link (str root "/z-link.txt") (str root "/a.txt")
                            )
        (let [ctx (sb/new {:root root :profile :agent/project-develop})
              entries (sb/evaluate! ctx "(project/list \".\")")]
          (is (vector? entries))
          (is (= ["a.txt" "b.txt" "sub" "z-link.txt"]
                 (mapv :name entries))
              "one level only, sorted by name — sub's children never appear")
          (is (= [:file :file :directory :symlink] (mapv :kind entries)))
          (is (= 5 (:bytes (first entries)))
              "files carry their byte size")
          (is (= 2 (:bytes (second entries))))
          (is (nil? (:bytes (nth entries 2)))
              "directories carry no :bytes")
          (is (= [{:name "deep" :kind :directory}
                  {:name "inner.txt" :kind :file :bytes 5}]
                 (sb/evaluate! ctx "(project/list \"sub\")"))
              "listing a subdirectory shows exactly its immediate entries"))
        (let [ctx (sb/new {:root root :profile :agent/project-develop})]
          (are [code src] (= code (thrown-code #(sb/evaluate! ctx src)))
            :not-found "(project/list \"nope\")"
            :not-found "(project/list \"a.txt\")"
            :symlink "(project/list \"z-link.txt\")")
          (is (= [] (sb/evaluate! ctx "(project/list \"sub/deep\")"))
              "an empty directory lists as an empty vector"))
        ;; the entry bound fails rather than sampling the directory
        (dotimes [i 3] (spit (str root "/many" i ".txt") "x"))
        (let [ctx (sb/new {:root root :profile :agent/project-develop
                           :max-list-entries 4})]
          (is (= :too-many-entries
                 (thrown-code #(sb/evaluate! ctx "(project/list \".\")")))))
        ;; dot-entries are LISTED (only search hides them by default)
        (spit (str root "/.dotted.txt") "x")
        (let [ctx (sb/new {:root root :profile :agent/project-develop})]
          (is (some #{".dotted.txt"}
                    (mapv :name (sb/evaluate! ctx "(project/list \".\")"))))))))

  ;; ─── A2/A3b conformance: project/search ───

  (deftest project-search-returns-path-line-text-under-frozen-bounds
    (with-tmp
      (fn [root]
        (fs/create-dirs (str root "/alpha"))
        (fs/create-dirs (str root "/beta"))
        (spit (str root "/z.txt") "no match\nneedle last")
        (spit (str root "/alpha/m.txt") "needle one\nplain")
        (spit (str root "/beta/k.txt") "  needle padded  ")
        (spit (str root "/.hidden.txt") "hidden needle")
        (write-bytes! (str root "/binary.dat")
                      (byte-array [(byte 0x6e) (unchecked-byte 0xff)]))
        (let [ctx (sb/new {:root root :profile :agent/project-develop})
              ms (sb/evaluate! ctx "(project/search \"needle\")")]
          (is (vector? ms))
          (is (every? map? ms))
          (is (= ["alpha/m.txt" "beta/k.txt" "z.txt"] (mapv :path ms))
              "results are {:path :line :text} maps ordered by path")
          (is (= [1 1 2] (mapv :line ms)))
          (is (= "needle one" (:text (first ms))))
          (is (= "needle padded" (:text (second ms)))
              "matched text is trimmed")
          (is (nil? (some #(= ".hidden.txt" (:path %)) ms))
              "dot-entries are skipped by default")
          (is (= [".hidden.txt" "alpha/m.txt" "beta/k.txt" "z.txt"]
                 (mapv :path
                       (sb/evaluate! ctx "(project/search \"needle\" {:include-hidden? true})")))
              "dot-entries search with {:include-hidden? true}")
          (is (= ["alpha/m.txt"]
                 (mapv :path (sb/evaluate! ctx "(project/search \"needle\" {:path \"alpha\"})")))
              "{:path …} searches one subtree")
          ;; binary files are skipped by strict decode, not by name: the
          ;; file's text prefix would match, but it never decodes
          (write-bytes! (str root "/binary.dat")
                        (byte-array (concat (map (fn [^long c] (byte c))
                                                 (map int "needle"))
                                            [(unchecked-byte 0xff)])))
          (is (nil? (some #(= "binary.dat" (:path %))
                          (sb/evaluate! ctx "(project/search \"needle\")")))
              "the binary file contributes no match though its text prefix fits")
          (is (= :not-found
                 (thrown-code
                  #(sb/evaluate! ctx "(project/search \"needle\" {:path \"binary.dat\"})")))
              "a file :path is not a directory — the frozen contract refuses it")
          ;; a huge file is skipped UNREAD: its bytes are never consumed
          (spit (str root "/huge.txt") (apply str (repeat 100000 "needle")))
          (is (= ["alpha/m.txt" "beta/k.txt" "z.txt"]
                 (mapv :path (sb/evaluate! ctx "(project/search \"needle\")")))
              "the oversize file is skipped before reading; matches survive"))
        ;; pattern and argument bounds
        (let [ctx (sb/new {:root root :profile :agent/project-develop})]
          (are [code src] (= code (thrown-code #(sb/evaluate! ctx src)))
            :invalid-regex "(project/search \"[unclosed\")"
            :invalid-arguments "(project/search \"needle\" \"alpha\")"
            :invalid-arguments "(project/search)"
            :invalid-arguments "(project/search \"\")"
            :path-escape "(project/search \"x\" {:path \"../out\"})")
          (is (= :invalid-arguments
                 (thrown-code
                   #(sb/evaluate! ctx (str "(project/search \""
                                           (apply str (repeat 201 "a"))
                                           "\")"))))
              "patterns over 200 characters are refused"))
        ;; result and file bounds
        (let [ctx (sb/new {:root root :profile :agent/project-develop
                           :max-search-results 2})]
          (is (= 2 (count (sb/evaluate! ctx "(project/search \"needle\")")))
              "collection stops at the result bound"))
        (let [ctx (sb/new {:root root :profile :agent/project-develop
                           :search-max-files 2})]
          (is (= :too-many-files
                 (thrown-code #(sb/evaluate! ctx "(project/search \"x\")")))
              "more regular files than the file bound fails the search"))
        ;; symlinked directories are never followed into
        (fs/create-sym-link (str root "/alpha/slink") (str root "/beta"))
        (let [ctx (sb/new {:root root :profile :agent/project-develop})]
          (is (= ["alpha/m.txt"]
                 (mapv :path (sb/evaluate! ctx "(project/search \"needle\" {:path \"alpha\"})")))
              "the symlinked subdirectory contributes no matches")))))

  ;; ─── A2/A3b conformance: project/stat ───

  (deftest project-stat-names-the-coordinate-or-fails
    (with-tmp
      (fn [root]
        (spit (str root "/a.txt") "hello")
        (fs/create-dirs (str root "/sub"))
        (fs/create-sym-link (str root "/link.txt") (str root "/a.txt"))
        (fs/create-sym-link (str root "/linkdir") (str root "/sub"))
        (let [ctx (sb/new {:root root :profile :agent/project-develop})]
          (let [st (sb/evaluate! ctx "(project/stat \"a.txt\")")]
            (is (= {:path "a.txt" :kind :file :bytes 5
                    :digest (known-sha256 "hello")}
                   st)
                "a regular file's coordinate is exact — never a nil digest"))
          (is (= {:path "nope.txt" :kind :absent}
                 (sb/evaluate! ctx "(project/stat \"nope.txt\")")))
          (is (= {:path "sub" :kind :directory}
                 (sb/evaluate! ctx "(project/stat \"sub\")")))
          (is (= {:path "link.txt" :kind :symlink}
                 (sb/evaluate! ctx "(project/stat \"link.txt\")"))
              "a symlink is reported as a link, never followed")
          (is (= {:path "linkdir" :kind :symlink}
                 (sb/evaluate! ctx "(project/stat \"linkdir\")"))
              "a symlinked directory is reported as a link too")
          (is (= :not-file
                 (thrown-code #(sb/evaluate! ctx "(project/stat \".\")"))))
          ;; oversize: the digest read fails closed rather than faking nil
          (spit (str root "/huge.txt") (apply str (repeat 100000 "x")))
          (let [narrow (sb/new {:root root :profile :agent/project-develop
                                :max-read-chars 100})]
            (is (= :digest-failed
                   (thrown-code #(sb/evaluate! narrow "(project/stat \"huge.txt\")")))))
          ;; unreadable: same refusal — a coordinate is computed or absent
          (when digest-available?
            (spit (str root "/secret.txt") "s")
            (fs/set-posix-file-permissions (str root "/secret.txt") "---------")
            (try
              (when (deny? #(slurp (str root "/secret.txt")))
                (is (= :digest-failed
                       (thrown-code
                         #(sb/evaluate! ctx "(project/stat \"secret.txt\")")))
                    "a digest that cannot be read fails the stat — nil is a
                     fake coordinate, not an absent one"))
              (finally
                (fs/set-posix-file-permissions (str root "/secret.txt")
                                               "rw-------"))))))))

  ;; ─── A2/A3b conformance: project/edit ───

  (deftest project-edit-is-anchored-atomic-and-never-materializes-hierarchy
    (with-tmp
      (fn [root]
        (let [ctx (sb/new {:root root :profile :agent/project-develop})
              temps (fn [] (filter #(str/includes? (str %) ".samizdat-edit-")
                                   (map str (fs/list-dir root))))]
          ;; creation: :absent, leaf only
          (let [created (sb/evaluate!
                         ctx "(project/edit \"made.txt\" :absent \"first\")")]
            (is (= "made.txt" (:path created)))
            (is (= 5 (:bytes created)))
            (is (= (known-sha256 "first") (:digest created))))
          (is (= [] (temps)) "a successful atomic write leaves no temporary")
          ;; no blind overwrite
          (let [d (thrown-data #(sb/evaluate!
                                 ctx "(project/edit \"made.txt\" :absent \"second\")"))]
            (is (= :already-exists (:samizdat.sandbox/error d)))
            (is (:bbagent/conflict d) "marked as a conflict like the contract")
            (is (= (known-sha256 "first") (:conflict/observed d))))
          ;; anchored update through the stat coordinate
          (let [digest (get-in (sb/evaluate! ctx "(project/stat \"made.txt\")")
                               [:digest])]
            (is (= {:path "made.txt" :bytes 6
                    :digest (known-sha256 "second")}
                   (sb/evaluate!
                    ctx (str "(project/edit \"made.txt\" \"" digest
                             "\" \"second\")"))))
            ;; stale: the world moved since the read
            (let [d (thrown-data
                     #(sb/evaluate!
                       ctx (str "(project/edit \"made.txt\" \"" digest
                                "\" \"third\")")))]
              (is (= :stale-conflict (:samizdat.sandbox/error d)))
              (is (:bbagent/conflict d))
              (is (= (known-sha256 "second") (:conflict/observed d)))
              (is (= digest (:conflict/expected d)))))
          ;; a digest base on a missing file is a conflict, not a creation
          (let [d (thrown-data #(sb/evaluate!
                                 ctx "(project/edit \"gone.txt\" \"sha256:x\" \"y\")"))]
            (is (= :not-found (:samizdat.sandbox/error d)))
            (is (:bbagent/conflict d)))
          ;; :absent creates ONLY an absent leaf — never a parent hierarchy
          (is (= :not-found
                 (thrown-code #(sb/evaluate!
                                ctx "(project/edit \"new/dir/f.txt\" :absent \"x\")"))))
          (is (not (fs/exists? (str root "/new")))
              "no directory was materialized")
          (is (= [] (temps)) "the failed write left no temporary")
          ;; write bound and content typing
          (is (= :invalid-arguments
                 (thrown-code #(sb/evaluate! ctx "(project/edit \"n.txt\" :absent 42)")))
              "content must be a string")
          (let [wide (sb/new {:root root :profile :agent/project-develop
                              :max-write-bytes 10})]
            (is (= :write-limit
                   (thrown-code #(sb/evaluate!
                                  wide "(project/edit \"w.txt\" :absent \"01234567890123456\")")))))
          ;; non-regular targets and root confinement
          (fs/create-dirs (str root "/sub"))
          (fs/create-sym-link (str root "/made-link.txt") (str root "/made.txt"))
          (fs/create-sym-link (str root "/sub-link") (str root "/sub"))
          (are [code src] (= code (thrown-code #(sb/evaluate! ctx src)))
            :not-file "(project/edit \"sub\" :absent \"x\")"
            :not-file "(project/edit \"made-link.txt\" :absent \"x\")"
            :not-file "(project/edit \".\" :absent \"x\")"
            :path-escape "(project/edit \"../out.txt\" :absent \"x\")"
            :absolute-path (str "(project/edit \"" root "/made.txt\" :absent \"x\")"))
          (is (= :symlink
                 (thrown-code #(sb/evaluate!
                                ctx "(project/edit \"sub-link/new.txt\" :absent \"x\")")))
              "a symlinked parent is refused by the descent")
          (is (= [] (temps))
              "no failure path left a temporary behind")))))

  ;; ─── Root confinement and authority negatives across all five ops ───

  (deftest root-confinement-and-authority-negatives-are-preserved
    (with-tmp
      (fn [root]
        (spit (str root "/a.txt") "hello")
        (let [ctx (sb/new {:root root :profile :agent/project-develop})]
          ;; lexical escapes are refused for every op
          (are [code src] (= code (thrown-code #(sb/evaluate! ctx src)))
            :path-escape "(project/read \"../a.txt\")"
            :path-escape "(project/list \"..\")"
            :path-escape "(project/search \"x\" {:path \"..\"})"
            :path-escape "(project/stat \"../a.txt\")"
            :path-escape "(project/edit \"../a.txt\" :absent \"x\")")
          ;; absolute paths are refused outright, under-root or not
          (are [code src] (= code (thrown-code #(sb/evaluate! ctx src)))
            :absolute-path (str "(project/read \"" root "/a.txt\")")
            :absolute-path (str "(project/list \"" root "\")")
            :absolute-path (str "(project/stat \"" root "/a.txt\")")
            :absolute-path (str "(project/edit \"" root "/a.txt\" :absent \"x\")"))
          ;; a symlink pointing outside the root never widens a read
          (fs/create-sym-link (str root "/out-link.txt") "/etc/hostname")
          (is (= :not-file
                 (thrown-code #(sb/evaluate! ctx "(project/read \"out-link.txt\")"))))
          (is (= {:path "out-link.txt" :kind :symlink}
                 (sb/evaluate! ctx "(project/stat \"out-link.txt\")"))
              "an escaping link is reported as a link, never followed")
          ;; the vocabulary's introspection negatives still hold
          (is (deny? #(sb/evaluate! ctx "(resolve 'project/read)")))
          (is (deny? #(sb/evaluate! ctx "(find-ns 'project)")))
          (is (deny? #(sb/evaluate! ctx "(eval (read-string \"(+ 1 2)\"))")))))))

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
              (is (= :file (:kind (sb/evaluate! b "(project/stat \"once.txt\")")))
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

  (deftest recorded-operation-permit-fences-the-durable-intent-and-host-edit
    ;; The real evaluate-recorded! seam, not the repl wiring stand-in: a denied
    ;; launch permit must run before record-intent! and before project/edit.
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  b (sb/bind! (sb/provider) "permit-denied"
                              {:preset :project/develop :root root})
                  permits (atom 0)
                  data (thrown-data
                        #(sb/evaluate-recorded!
                          conn b
                          "(project/edit \"denied.txt\" :absent \"no\")"
                          {:effect-permit!
                           (fn [_initiate]
                             (swap! permits inc)
                             (throw (ex-info "revoked"
                                             {:samizdat.turn-lease/error
                                              :stale})))}))
                  record (get-in @conn [:records 1])]
              (is (= :stale (:samizdat.turn-lease/error data)))
              (is (= 1 @permits) "the sandbox consulted the launch fence once")
              (is (= :failed (:status record)))
              (is (empty? (:receipts record))
                  "revocation before the permit produces no operation receipt")
              (is (not (fs/exists? (str root "/denied.txt")))
                  "and the semantic host edit never starts")))))))

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

(when sandbox-ns-resolved?

  ;; ─── RuntimeCoordinate: versioned, names the whole runtime stack ───

  (deftest runtime-coordinate-is-versioned-and-names-the-runtime-stack
    (let [rc (sb/runtime-coordinate)
          snap (sb/runtime-snapshot)]
      (is (str/starts-with? rc "js1-rt/v1:"))
      (is (= rc (sb/runtime-coordinate)) "deterministic within the process")
      (is (= (jolt.host/jolt-version) (:runtime/jolt snap))
          "names the Jolt version")
      (is (= "sci-0.13.53" (:runtime/sci snap))
          "names the vendored SCI coordinate")
      (is (= 2 (:runtime/evaluator-protocol snap))
          "protocol v2: the normalized frozen-contract operation semantics")
      (is (str/starts-with? (:runtime/language snap) "js0-lang/v1:")
          "names the reviewed language surface's own versioned coordinate")
      (is (str/starts-with? (:runtime/capability-catalog snap) "js0:")
          "names the capability catalog by content coordinate")
      (is (= 1 (:runtime/capability-catalog-version snap)))
      (is (= 1 (:runtime/receipt-protocol snap)))
      (with-tmp
        (fn [root]
          (let [b (sb/bind! (sb/provider) "rt-work"
                            {:preset :project/develop :root root})]
            (is (= rc (:samizdat.sandbox/runtime-coordinate (sb/describe b)))
                "describe exposes the runtime coordinate"))))))

  ;; ─── Whole-history rebuild: cross-eval state, zero real operations ───

  (deftest cross-eval-helper-survives-whole-history-rebuild
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  p (sb/provider)
                  b (sb/bind! p "history-work" {:preset :project/develop :root root})]
              (sb/evaluate-recorded! conn b "(defn helper [x] (* 2 x))")
              (sb/evaluate-recorded! conn b (str "(def answer (helper 21)) "
                                                 "(project/edit \"h.txt\" :absent \"hist\") "
                                                 "(project/read \"h.txt\")"))
              (is (deny? #(sb/evaluate-recorded! conn b "(def ghost 9) (no-such-fn)"))
                  "a failing evaluation between committed ones propagates")
              (let [b2 (sb/bind! p "history-work" {:preset :project/develop :root root})]
                (sb/evaluate-recorded! conn b2 "(def later (inc answer))")
                (is (= [0 1 2 3]
                       (mapv :binding_seq
                             (memory-history conn (:binding/id b2))))
                    "the binding's evaluations form a durable total order")
                (is (= [:completed :completed :failed :completed]
                       (mapv :status (memory-history conn (:binding/id b2)))))
                ;; Replay must serve the edit AND the read from receipts:
                ;; tampering with the file means a real edit conflicts
                ;; (:absent on an existing file) and a real read returns
                ;; different content than the recorded receipt.
                (spit (str root "/h.txt") "tampered")
                (let [b3 (sb/rebuild-binding! conn b2)]
                  (is (= (:binding/id b2) (:binding/id b3)))
                  (is (= (:instance/id b2) (:instance/id b3)))
                  (is (= 42 (sb/evaluate! b3 "answer"))
                      "a def from an earlier committed eval survived the rebuild")
                  (is (= 84 (sb/evaluate! b3 "(helper answer)"))
                      "the cross-eval helper survived the rebuild")
                  (is (= 43 (sb/evaluate! b3 "later"))
                      "the eval committed after the failure replayed")
                  (is (deny? #(sb/evaluate! b3 "ghost"))
                      "the failed eval's def never committed and never replayed")
                  (is (= "tampered" (slurp (str root "/h.txt")))
                      "whole-history replay invoked zero real project operations")))))))))

  ;; ─── Commit-only state: failed / interrupted evals roll back ───

  (deftest failed-recorded-eval-rolls-back-to-committed-state
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  p (sb/provider)
                  b (sb/bind! p "fail-work" {:preset :project/develop :root root})]
              (sb/evaluate-recorded! conn b "(defn helper [x] (* 2 x))")
              (is (deny? #(sb/evaluate-recorded! conn b "(def partial-def 1) (no-such-fn)"))
                  "the failing evaluation propagates its error")
              (is (= :failed (:status (get-in @conn [:records 2])))
                  "the failure is durably recorded as failed, not pending")
              (is (= :stale-binding
                     (:samizdat.sandbox/error
                      (thrown-data #(sb/evaluate-recorded! conn b "1"))))
                  "the pre-rollback binding is superseded; re-acquire it")
              (let [b2 (sb/bind! p "fail-work" {:preset :project/develop :root root})]
                (is (= 10 (sb/evaluate! b2 "(helper 5)"))
                    "committed state survived the rollback")
                (is (deny? #(sb/evaluate! b2 "partial-def"))
                    "the failed eval's partial def never became evaluator state"))))))))

  (deftest interrupted-recorded-eval-rolls-back-to-committed-state
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  p (sb/provider)
                  b (sb/bind! p "interrupt-work" {:preset :project/develop
                                                  :root root
                                                  :timeout-ms 250})]
              (sb/evaluate-recorded! conn b "(defn helper2 [x] (inc x))")
              ;; The bounded loop is long enough that the host interrupt
              ;; lands mid-eval (an uninterruptible loop would finish in
              ;; seconds and fail the test instead of hanging it).
              (let [data (thrown-data
                          #(sb/evaluate-recorded!
                            conn b
                            "(def interrupted-def 1) (loop [i 20000000] (if (pos? i) (recur (dec i)) :done))"))]
                (is (= :timeout (:samizdat.sandbox/error data))
                    "the host interrupt surfaces as a timeout"))
              (is (= :failed (:status (get-in @conn [:records 2])))
                  "the interruption is durably recorded as failed, not pending")
              (let [b2 (sb/bind! p "interrupt-work" {:preset :project/develop
                                                     :root root
                                                     :timeout-ms 250})]
                (is (= 6 (sb/evaluate! b2 "(helper2 5)"))
                    "committed state survived the interruption rollback")
                (is (deny? #(sb/evaluate! b2 "interrupted-def"))
                    "the interrupted eval's partial def never became evaluator state"))))))))

  (deftest spec-ceiling-governs-without-firing-a-caller-supplied-token
    ;; B2 regression, revised composition: the model-facing eval path passes
    ;; the turn lease's token as :token.  Previously a supplied token
    ;; REPLACED the spec's :timeout-ms ceiling, so a model-side lease could
    ;; stretch evaluation past the 30s evaluator ceiling fixed at bind time.
    ;; The ceiling composes over the token — but interrupts a PRIVATE
    ;; per-evaluation token, never the caller's: a caller token is shared
    ;; across the turn's evaluations, and a ceiling fire on it (or a late
    ;; timer wake landing after the guarded extent) would poison every
    ;; later same-turn eval with a spurious interruption.
    (with-tmp
      (fn [root]
        (let [p (sb/provider)
              b (sb/bind! p "ceiling-work" {:preset :project/develop
                                            :root root :timeout-ms 250})
              token (jolt.host/make-interrupt)
              ;; The bounded loop runs for seconds if nothing interrupts it,
              ;; so a 250ms ceiling is the only way this returns early.
              data (thrown-data
                    #(sb/evaluate!
                      b
                      "(loop [i 20000000] (if (pos? i) (recur (dec i)) :done))"
                      {:token token}))]
          (is (= :timeout (:samizdat.sandbox/error data))
              "the spec ceiling, not the caller's patience, ends the eval")
          (is (not (jolt.host/interrupted? token))
              "the caller's token is never the ceiling's target")
          (is (= 2 (sb/evaluate! b "(+ 1 1)" {:token token}))
              "a later eval sharing the caller token is unaffected"))))
    ;; A caller-side interrupt (turn revocation racing in) is NOT the
    ;; ceiling: it must surface as the raw Jolt interruption, not be
    ;; relabeled :timeout — the canceller knows what it did.  (The loop
    ;; source again: the cooperative check fires at evaluation safe
    ;; points, which a trivial form may never reach.)
    (with-tmp
      (fn [root]
        (let [p (sb/provider)
              b (sb/bind! p "ceiling-work-2" {:preset :project/develop
                                              :root root :timeout-ms 60000})
              token (jolt.host/make-interrupt)
              _ (jolt.host/interrupt! token)
              data (thrown-data
                    #(sb/evaluate!
                      b
                      "(loop [i 20000000] (if (pos? i) (recur (dec i)) :done))"
                      {:token token}))]
          (is (:jolt/interrupted data) "the caller's interruption surfaces")
          (is (nil? (:samizdat.sandbox/error data))
              "and is not mislabeled as a spec timeout")))))

  (deftest a-late-ceiling-wake-cannot-interrupt-a-later-same-token-eval
    ;; The review's timer race, closed structurally: the ceiling timer never
    ;; targets the caller's token, so a wake arriving after the guarded eval
    ;; completed cannot poison the next evaluation sharing that token.
    ;; (Against the old composition the poisonous interleaving needed the
    ;; timer to land in the eval-return→disarm gap; this pins the property
    ;; the fix guarantees unconditionally.)
    (with-tmp
      (fn [root]
        (let [p (sb/provider)
              b (sb/bind! p "late-wake-work" {:preset :project/develop
                                              :root root :timeout-ms 50})
              token (jolt.host/make-interrupt)]
          (is (= 2 (sb/evaluate! b "(+ 1 1)" {:token token}))
              "the guarded eval completes well under the ceiling")
          (Thread/sleep 200)
          (is (not (jolt.host/interrupted? token))
              "the late wake found no caller token to fire")
          (is (= 3 (sb/evaluate! b "(+ 1 2)" {:token token}))
              "the later same-token eval runs untainted")))))

  (deftest spec-ceiling-governs-a-tokened-recorded-eval
    ;; B2 at the seam the repl tool actually drives: evaluate-recorded!
    ;; with a lease token times out at the spec ceiling, records :failed
    ;; (never pending), and rolls back to committed state — and the lease's
    ;; token is NOT the ceiling's target, so the turn's next evaluation is
    ;; not poisoned by this one's timeout.
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  p (sb/provider)
                  b (sb/bind! p "ceiling-recorded-work"
                              {:preset :project/develop
                               :root root :timeout-ms 250})
                  token (jolt.host/make-interrupt)]
              (sb/evaluate-recorded! conn b "(def kept-before 1)")
              (let [data (thrown-data
                          #(sb/evaluate-recorded!
                            conn b
                            "(def lost 1) (loop [i 20000000] (if (pos? i) (recur (dec i)) :done))"
                            {:token token}))]
                (is (= :timeout (:samizdat.sandbox/error data))
                    "the ceiling interrupts the tokened recorded eval")
                (is (not (jolt.host/interrupted? token))
                    "via the eval's private token — the lease's is untouched"))
              (is (= :failed (:status (get-in @conn [:records 2])))
                  "the ceiling interruption is durably failed, not pending")
              (let [b2 (sb/bind! p "ceiling-recorded-work"
                                 {:preset :project/develop
                                  :root root :timeout-ms 250})]
                (is (= 2 (sb/evaluate! b2 "(inc kept-before)"))
                    "committed state survived the rollback")
                (is (deny? #(sb/evaluate! b2 "lost"))
                    "the interrupted eval's partial def never committed"))))))))

  (deftest uncompletable-failure-poisons-the-instance
    (with-tmp
      (fn [root]
        (let [failing-store
              (assoc memory-eval-store
                     :complete!
                     (fn [conn eval-id m]
                       (if (= :failed (:status m))
                         (throw (ex-info "simulated completion append failure" {}))
                         ((:complete! memory-eval-store) conn eval-id m))))]
          (with-eval-store failing-store
            (fn []
              (let [conn (memory-db)
                    p (sb/provider)
                    b (sb/bind! p "poison-work" {:preset :project/develop :root root})]
                (sb/evaluate-recorded! conn b "(def kept 1)")
                (is (= :durable-evaluation-incomplete
                       (:samizdat.sandbox/error
                        (thrown-data #(sb/evaluate-recorded! conn b "(no-such-fn)"))))
                    "an unrecordable failure surfaces as durable-incomplete")
                (is (= :instance-poisoned
                       (:samizdat.sandbox/error
                        (thrown-data #(sb/evaluate! b "kept"))))
                    "the poisoned instance refuses evaluation")
                (is (= :pending-history
                       (:samizdat.sandbox/error
                        (thrown-data #(sb/rebuild-binding! conn b))))
                    "the pending record blocks whole-history rebuild: fail closed"))))))))

  ;; ─── Whole-history rebuild: fail closed on pending / mismatch / gaps ───

  (deftest whole-history-rebuild-fails-closed-on-pending-mismatch-and-gaps
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  p (sb/provider)
                  b (sb/bind! p "guard-work" {:preset :project/develop :root root})
                  e1 (:eval-id (sb/evaluate-recorded!
                                conn b "(project/edit \"g.txt\" :absent \"made\")"))
                  original (get-in @conn [:records e1])]
              (fs/delete (str root "/g.txt"))

              ;; A pending record with an unsettled effect intent: the
              ;; actuation may or may not have happened — refuse.
              (let [e2 (memory-begin! conn {:spec-id (:spec_id original)
                                            :instance-id (:instance_id original)
                                            :binding-id (:binding_id original)
                                            :coordinate (:coordinate original)
                                            :runtime (:runtime original)
                                            :source "(project/edit \"g2.txt\" :absent \"maybe\")"})]
                (memory-record-intent! conn e2 {:op :project/edit
                                                :args ["g2.txt" :absent "maybe"]})
                (is (= :pending-history
                       (:samizdat.sandbox/error
                        (thrown-data #(sb/rebuild-binding! conn b))))
                    "a pending record with an unsettled effect fails closed")
                (is (not (fs/exists? (str root "/g2.txt")))
                    "the denial performed no project actuation")
                (reset! conn (update @conn :records dissoc e2)))

              ;; Authority-coordinate mismatch in the history: refuse.
              (reset! conn (assoc-in @conn [:records e1 :coordinate] "js0:forged"))
              (is (= :history-mismatch
                     (:samizdat.sandbox/error
                      (thrown-data #(sb/rebuild-binding! conn b))))
                  "a coordinate mismatch fails closed")
              (reset! conn (assoc-in @conn [:records e1 :coordinate]
                                     (:coordinate original)))

              ;; Runtime-coordinate mismatch: refuse.
              (reset! conn (assoc-in @conn [:records e1 :runtime] "js1-rt/v1:forged"))
              (is (= :history-mismatch
                     (:samizdat.sandbox/error
                      (thrown-data #(sb/rebuild-binding! conn b))))
                  "a runtime mismatch fails closed")
              (reset! conn (assoc-in @conn [:records e1 :runtime]
                                     (:runtime original)))

              ;; A gap in the binding's total order: refuse.
              (reset! conn (assoc-in @conn [:records e1 :binding_seq] 5))
              (is (= :malformed-history
                     (:samizdat.sandbox/error
                      (thrown-data #(sb/rebuild-binding! conn b))))
                  "a non-contiguous total order fails closed")
              (reset! conn (assoc-in @conn [:records e1 :binding_seq] 0))

              ;; Clean history rebuilds — and the deleted file stays deleted:
              ;; the edit was served from its receipt, never really re-run.
              (let [b2 (sb/rebuild-binding! conn b)]
                (is (map? b2) "a validated history rebuilds")
                (is (not (fs/exists? (str root "/g.txt")))
                    "the successful replay invoked zero real operations"))))))))

  ;; ─── Bounded rendered final results: arbitrary SCI values accepted ───

  (deftest noncanonical-final-results-are-rendered-bounded-and-accepted
    (with-tmp
      (fn [root]
        (with-eval-store memory-eval-store
          (fn []
            (let [conn (memory-db)
                  p (sb/provider)
                  b (sb/bind! p "render-work" {:preset :project/develop :root root})]
              (let [{:keys [eval-id value]}
                    (sb/evaluate-recorded! conn b "(map inc [1 2 3])")
                    record (get-in @conn [:records eval-id])]
                (is (= [2 3 4] (vec value)) "the caller gets the live value")
                (is (= :completed (:status record))
                    "a non-inert final value still commits")
                (is (= {:rendered "(2 3 4)" :render-truncated? false}
                       (:result record))
                    "stored as a bounded rendering, not refused"))
              (let [{:keys [eval-id]} (sb/evaluate-recorded! conn b "1.5")]
                (is (= {:rendered "1.5" :render-truncated? false}
                       (:result (get-in @conn [:records eval-id])))))
              (let [{:keys [eval-id]} (sb/evaluate-recorded! conn b "{:a [1 2]}")]
                (is (= {:value {:a [1 2]}}
                       (:result (get-in @conn [:records eval-id])))
                    "inert final values stay exact"))
              (let [hundred-as (apply str (repeat 100 "a"))
                    src (str "(map (fn [_] \"" hundred-as "\") (range 500))")
                    {:keys [eval-id]} (sb/evaluate-recorded! conn b src)
                    result (:result (get-in @conn [:records eval-id]))]
                (is (:render-truncated? result))
                (is (= 8000 (count (:rendered result)))
                    "the rendering is character-bounded"))
              (let [b2 (sb/rebuild-binding! conn b)]
                (is (map? b2)
                    "a history of rendered results replays by rendering comparison"))
              ;; The rebuild superseded b; re-acquire before recording again.
              (let [b3 (sb/bind! p "render-work" {:preset :project/develop :root root})
                    {:keys [eval-id]} (sb/evaluate-recorded! conn b3 "(fn [x] x)")]
                (is (string? (:rendered (:result (get-in @conn [:records eval-id]))))
                    "even a function value is accepted at record time")))))))))

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
