;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.js1-boundary-test
  "The JS1 durable-restart evidence, across REAL OS-process boundaries.

  This one file is both halves of the harness:

  SUITE MODE (default): a clojure.test entry that spawns child jolt
  processes and asserts on their exit codes and the on-disk state they
  leave.  A run needs the jolt binary, a Chez 10 scheme, the vendored SCI
  source root, and the four extracted Maven jars the sandbox needs — the
  same prerequisites samizdat.sandbox-test's direct invocation lists —
  plus SAMIZDAT_JS1_BOUNDARY_TEST=1 so the ordinary suite stays fast.
  Without them the test skips with a reason, exactly like the sandbox ns
  skips without SCI.

  CHILD MODE (SAMIZDAT_JS1_BOUNDARY_PHASE=<phase>): this same file is run
  by `jolt -Scp <full project classpath + SCI roots> run <this file>` in a
  FRESH process, executing one phase:

    record          mint a JS1 binding, evaluate a helper def, an edit
                    (actuation), and an observation into it through the
                    durable *eval-store* seam backed by a FILE, plus one
                    deliberately failing evaluation; write the exact
                    journal information (workflow/js1-binding-journal-data,
                    the same map production journals) and exit.  The
                    process then dies — that is the point.

    resume          a NEW process: install the file-backed store, tamper
                    the project files, reconstruct the binding through
                    samizdat.agent.resume/reconstruct-js1-binding! (whole
                    committed history replayed into ONE fresh SCI context)
                    and verify the model's definitions survived the crash.

    mismatch-spec   a NEW process: reconstruct against journal info whose
                    spec coordinate was tampered — must fail CLOSED.
    mismatch-runtime  the same with a tampered runtime coordinate — must
                    fail CLOSED before anything is allocated.
    unsettled       a NEW process: the durable history's last record made
                    pending with an unsettled effect intent, and separately
                    a gap in the binding's total order — both must fail
                    CLOSED, performing zero project operations.

  The no-repeated-invocation evidence: `record` edits made.txt into
  existence and reads it back; before every later phase the harness (or
  the phase itself) tampers it on disk.  Replay serves the edit and the
  read from receipts — a re-invoked edit would conflict (:absent on an
  existing file), a re-invoked read would return the tampered content and
  fail the recorded-result comparison, and a re-invoked edit would
  overwrite the tampered bytes.  A successful reconstruction that leaves
  the tampered file untouched is therefore direct evidence that ZERO real
  project operations executed during replay."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.engine.proc :as proc]))

;; ─── shared layout ──────────────────────────────────────────────────────────

(def ^:private work-id "boundary-work")

(defn- dir-env [] (jolt.host/getenv "SAMIZDAT_JS1_BOUNDARY_DIR"))
(defn- phase-env [] (jolt.host/getenv "SAMIZDAT_JS1_BOUNDARY_PHASE"))

(defn- root-dir [dir] (str dir "/root"))
(defn- store-path [dir] (str dir "/store.edn"))
(defn- journal-path [dir] (str dir "/journal.edn"))
(defn- outcomes-path [dir] (str dir "/outcomes.edn"))
(defn- made-path [dir] (str (root-dir dir) "/made.txt"))

(defn- slurp-edn [path] (edn/read-string (slurp path)))

(defn- deny? [f]
  (try (f) false (catch Throwable _ true)))

;; ─── child: the file-backed durable eval store ──────────────────────────────
;;
;; The samizdat.store.evals contract over one EDN file, so the durable
;; record survives process death the way the sqlite tables do in
;; production.  Row shape mirrors the real store's read projection exactly
;; (underscore identity columns, :binding_seq, :runtime) because
;; rebuild-binding!'s validation reads those keys.  `conn` is the state
;; directory; every write persists the whole small state file.

(defn- store-read!
  "The whole durable state from `dir`, or a fresh empty state."
  [dir]
  (if (fs/exists? (store-path dir))
    (slurp-edn (store-path dir))
    {:next-id 1 :records {}}))

(defn- store-write! [dir state]
  (spit (store-path dir) (pr-str state)))

(defn- file-begin! [dir {:keys [spec-id instance-id binding-id coordinate
                                runtime source]}]
  (let [state (store-read! dir)
        id (:next-id state)
        binding-seq (count (filter #(= (str binding-id) (:binding_id %))
                                   (vals (:records state))))
        record {:id id
                :spec_id (str spec-id)
                :instance_id (str instance-id)
                :binding_id (str binding-id)
                :binding_seq binding-seq
                :coordinate (str coordinate)
                :runtime (str runtime)
                :source (str source)
                :status :pending
                :result nil
                :receipts []}
        state' (-> state
                   (assoc :next-id (inc id))
                   (assoc-in [:records id] record))]
    (store-write! dir state')
    id))

(defn- file-record-intent! [dir eval-id {:keys [op args]}]
  (let [state (store-read! dir)
        record (get-in state [:records eval-id])
        n (count (:receipts record))
        receipt {:seq n :op op :args (vec (or args [])) :phase :intent}
        state' (assoc-in state [:records eval-id :receipts]
                         (conj (vec (:receipts record)) receipt))]
    (store-write! dir state')
    n))

(defn- file-record-outcome! [dir eval-id seq-n {:keys [result error]}]
  (let [state (store-read! dir)
        receipts (vec (get-in state [:records eval-id :receipts]))
        receipt (nth receipts seq-n)
        ;; exactly one of :result / :error, like the real store
        settled (if (some? error)
                  (assoc receipt :phase :error :error (str error))
                  (assoc receipt :phase :done :result result))
        state' (assoc-in state [:records eval-id :receipts seq-n] settled)]
    (store-write! dir state')
    seq-n))

(defn- file-complete! [dir eval-id {:keys [status result]}]
  (let [state (store-read! dir)
        state' (-> state
                   (assoc-in [:records eval-id :status] status)
                   (assoc-in [:records eval-id :result] result))]
    (store-write! dir state')
    true))

(defn- file-load-eval [dir eval-id]
  (get-in (store-read! dir) [:records eval-id]))

(defn- file-verify-binding! [dir eval-id {:keys [spec-id instance-id
                                                 binding-id coordinate runtime]}]
  (let [record (file-load-eval dir eval-id)
        expected {:spec_id (str spec-id)
                  :instance_id (str instance-id)
                  :binding_id (str binding-id)
                  :coordinate (str coordinate)
                  :runtime (str runtime)}]
    (when-not (and record
                   (= expected (select-keys record (keys expected))))
      (throw (ex-info "evaluator binding does not match durable evaluation"
                      {:eval-id eval-id})))
    record))

(defn- file-history [dir binding-id]
  (->> (vals (:records (store-read! dir)))
       (filter #(= (str binding-id) (:binding_id %)))
       (sort-by :binding_seq)
       vec))

(defn- install-file-store!
  "Point the sandbox's durable-eval seam at the file adapter for `dir`,
  exactly the way a controller or test binds it in production (the seam
  production leaves nil and dynamically resolves to samizdat.store.evals)."
  [dir]
  (let [v (resolve 'samizdat.agent.sandbox/*eval-store*)]
    (alter-var-root
     v (constantly
        {:begin! (fn [conn intent] (file-begin! conn intent))
         :record-intent! (fn [conn id m] (file-record-intent! conn id m))
         :record-outcome! (fn [conn id n m] (file-record-outcome! conn id n m))
         :complete! (fn [conn id m] (file-complete! conn id m))
         :load-eval (fn [conn id] (file-load-eval conn id))
         :verify-binding! (fn [conn id m] (file-verify-binding! conn id m))
         :history (fn [conn b] (file-history conn b))}))))

;; ─── child phases ───────────────────────────────────────────────────────────

(defn- require-sandbox! []
  (require 'samizdat.agent.sandbox))

(defn- phase-record! [dir]
  (require-sandbox!)
  (require 'samizdat.workflow)
  (fs/create-dirs (root-dir dir))
  (install-file-store! dir)
  (let [provider-fn (resolve 'samizdat.agent.sandbox/provider)
        bind-fn (resolve 'samizdat.agent.sandbox/bind!)
        rt-fn (resolve 'samizdat.agent.sandbox/runtime-coordinate)
        journal-data-fn (resolve 'samizdat.workflow/js1-binding-journal-data)
        evaluate! (resolve 'samizdat.agent.sandbox/evaluate-recorded!)
        provider (provider-fn {:root (root-dir dir)})
        binding (bind-fn provider work-id
                         {:preset :project/develop
                          :root (root-dir dir)
                          :instance/key :main})]
    ;; helper definition (pure state)
    (evaluate! dir binding "(defn helper [x] (* 2 x))")
    ;; actuation: the edit that must NEVER be re-invoked at replay
    (evaluate! dir binding
               "(project/edit \"made.txt\" :absent \"made-by-record\")")
    ;; cross-eval state + observation: answer needs `helper` from the
    ;; FIRST eval; the read's recorded result pins made.txt's content
    (evaluate! dir binding
               "(def answer (helper 21)) (project/read \"made.txt\")")
    ;; a deliberately failing evaluation: durably :failed, rolled back,
    ;; never replayed — `ghost` must not exist after the restart
    (try
      (evaluate! dir binding "(def ghost 1) (no-such-fn)")
      (catch Throwable _ nil))
    ;; the exact journal event data, from the same function production
    ;; journals with
    (spit (journal-path dir)
          (pr-str (journal-data-fn "single-player" binding (rt-fn))))
    (println "RECORD-OK")))

(defn- expect-js1-error!
  "Run `f`, require that it fails closed with {:js1/error code} (and
  optionally a specific :sandbox-error), print OK, or throw so the child
  exits non-zero."
  ([f code] (expect-js1-error! f code nil))
  ([f code sandbox-code]
   (let [outcome (try
                   {:returned (f)}
                   (catch Throwable e
                     {:thrown (ex-data e)}))]
     (when (:returned outcome)
       (throw (ex-info (str "expected a closed :js1/error " code
                            " failure, but reconstruction returned")
                       {:got (:returned outcome)})))
     (let [d (:thrown outcome)]
       (when (not= code (:js1/error d))
         (throw (ex-info (str "expected :js1/error " code ", got "
                              (:js1/error d))
                       {:ex-data d})))
       (when (and sandbox-code (not= sandbox-code (:sandbox-error d)))
         (throw (ex-info (str "expected :sandbox-error " sandbox-code
                              ", got " (:sandbox-error d))
                         {:ex-data d})))
        (println "CLOSED-OK" code
                 (str "sandbox-error " (or (:sandbox-error d) "-")))))))

(defn- reconstruct! [dir info]
  (require 'samizdat.agent.resume)
  (let [reconstruct (resolve 'samizdat.agent.resume/reconstruct-js1-binding!)]
    (reconstruct dir info (root-dir dir))))

(defn- phase-tool-refresh! [dir]
  "The held-binding refresh contract, at the EVAL TOOL level, against the
  real sandbox and the real durable store:

  - a failed recorded evaluation rolls the instance back and supersedes
    the ctx's held binding; the tool must transparently re-acquire the
    registry's current binding so the NEXT eval works (and the committed
    state survives while the failed eval's defs do not);
  - an out-of-band rebuild-binding! between two tool evals supersedes the
    held binding; the next eval must observe :stale-binding, re-acquire,
    and retry ONCE — the model asked for an evaluation, not a wiring
    error it cannot see."
  (require-sandbox!)
  (install-file-store! dir)
  (require 'samizdat.agent.tools)
  (require 'samizdat.agent.state)
  (let [provider-fn (resolve 'samizdat.agent.sandbox/provider)
        bind-fn (resolve 'samizdat.agent.sandbox/bind!)
        rebuild-fn (resolve 'samizdat.agent.sandbox/rebuild-binding!)
        provider (provider-fn {:root (root-dir dir)})
        binding (bind-fn provider work-id
                         {:preset :project/develop
                          :root (root-dir dir)
                          :instance/key :main})
        holder (atom binding)
        tools (resolve 'samizdat.agent.tools/run-tool)
        new-branch (resolve 'samizdat.agent.state/new-branch)
        branch (new-branch {:id "B1" :problem "boundary"})
        eval-tool (fn [code]
                    (let [r (tools {:branch branch
                                    :conn dir
                                    :tool-name "eval"
                                    :args {:code code}
                                    :js1/profile "single-player"
                                    :js1/binding holder
                                    :js1/provider provider})
                          result (str (:result r))]
                      {:ok? (str/starts-with? result "=>")
                       :result result
                       :branch (:branch r)}))
        outcomes (atom [])]
    ;; a committed def, then a failing eval whose partial def must roll
    ;; back — the tool re-acquires the fresh binding after the failure
    (let [r1 (eval-tool "(def x 1) x")]
      (swap! outcomes conj [:commit-1 (:ok? r1) (:result r1)]))
    (let [r2 (eval-tool "(def y 2) (no-such-fn)")]
      (swap! outcomes conj [:fail (:ok? r2)]))
    ;; THE assertion: the next eval works on the refreshed binding, sees
    ;; the committed def, and does not see the rolled-back one
    (let [r3 (eval-tool "(inc x)")]
      (swap! outcomes conj [:after-rollback (:ok? r3) (:result r3)]))
    (let [r4 (eval-tool "y")]
      (swap! outcomes conj [:rolled-back-denied (not (:ok? r4))]))
    ;; an out-of-band whole-history rebuild supersedes the held binding;
    ;; the next tool eval must absorb it (:stale-binding retry-once)
    (rebuild-fn dir @holder)
    (let [r5 (eval-tool "(inc (inc x))")]
      (swap! outcomes conj [:after-stale (:ok? r5) (:result r5)]))
    (let [pad (fn [rows] (mapv (fn [row] (if (= 3 (count row)) row (conj row nil)))
                               rows))
          expected [[:commit-1 true "=> 1"]
                    [:fail false]
                    [:after-rollback true "=> 2"]
                    [:rolled-back-denied true]
                    [:after-stale true "=> 3"]]
          got (pad @outcomes)]
      (when (not= (pad expected) got)
        (throw (ex-info "tool-refresh contract violated"
                        {:expected (pad expected) :got got})))
      (spit (str dir "/tool-refresh.edn") (pr-str got))
      (println "TOOL-REFRESH-OK"))))

(defn- phase-prompt!
  "The bounded-prompt contract, against the REAL sandbox: the prompt a JS1
  :project/develop context renders must teach exactly the gated tool
  vocabulary and exactly the binding's effective project capabilities — the
  SAME catalog doc/complete serve (so prompt authority cannot drift from
  dispatch authority) — and none of the old surface.  Writes the prompts
  and the structured outcomes for the suite to assert on."
  [dir]
  (require-sandbox!)
  (require 'samizdat.agent.loop)
  (require 'samizdat.agent.tools.base)
  (fs/create-dirs (root-dir dir))
  (let [provider-fn (resolve 'samizdat.agent.sandbox/provider)
        bind-fn (resolve 'samizdat.agent.sandbox/bind!)
        briefs-fn (resolve 'samizdat.agent.sandbox/capability-briefs)
        complete-fn (resolve 'samizdat.agent.sandbox/complete-capability)
        doc-fn (resolve 'samizdat.agent.sandbox/operation-doc)
        prompt-fn (resolve 'samizdat.agent.loop/js1-system-prompt)
        vocab-fn (resolve 'samizdat.agent.tools.base/js1-tool-vocabulary)
        provider (provider-fn {:root (root-dir dir)})
        binding (bind-fn provider work-id
                         {:preset :project/develop
                          :root (root-dir dir)
                          :instance/key :main})
        ctx {:js1/profile "single-player" :js1/binding binding}
        prompt (prompt-fn ctx)
        briefs (briefs-fn binding)
        ;; An attenuated sibling: same preset narrowed by the controller to
        ;; read/list only (a separate provider — same :main key would be a
        ;; spec conflict).  Its prompt must not inherit :project/develop's
        ;; full prose.
        attenuated (bind-fn (provider-fn {:root (root-dir dir)}) "attenuated"
                            {:preset :project/develop
                             :root (root-dir dir)
                             :capabilities #{:project/read :project/list}})
        attenuated-briefs (briefs-fn attenuated)
        attenuated-prompt (prompt-fn {:js1/profile "single-player"
                                      :js1/binding attenuated})]
    (when (empty? briefs)
      (throw (ex-info "live capability-briefs returned nothing for a real binding"
                      {})))
    (spit (str dir "/prompt.txt") prompt)
    (spit (str dir "/prompt-attenuated.txt") attenuated-prompt)
    (spit (str dir "/prompt-outcomes.edn")
          (pr-str
           {:vocabulary (vec (vocab-fn))
            ;; the tool-signature lines the prompt teaches
            :tools-taught (vec (map second (re-seq #"(?m)^(\w+)\(\{" prompt)))
            ;; the SAME-catalog chain: prompt <= briefs == describe ==
            ;; complete-capability == operation-doc
            :brief-names (mapv :name briefs)
            :brief-effects (mapv :effect briefs)
            :complete-project (complete-fn binding "project/")
            :edit-doc-substring?
            (str/includes? prompt (:doc (doc-fn binding "project/edit")))
            :read-doc-substring?
            (str/includes? prompt (:doc (doc-fn binding "project/read")))
            :attenuated-brief-names (mapv :name attenuated-briefs)
            :attenuated-complete (complete-fn attenuated "project/")}))
    (println "PROMPT-OK")))

(defn- phase-resume! [dir]
  (require-sandbox!)
  (install-file-store! dir)
  (let [info (slurp-edn (journal-path dir))]
    ;; The crash gap: between the record process dying and this one
    ;; starting, the world moved. made.txt is tampered on disk; replay
    ;; must serve BOTH the edit and the read from receipts, so a
    ;; successful reconstruction leaving these bytes untouched is the
    ;; no-repeated-invocation evidence.
    (spit (made-path dir) "tampered-after-crash")
    (let [{:keys [binding]} (reconstruct! dir info)
          evaluate! (resolve 'samizdat.agent.sandbox/evaluate!)
          outcomes {:helper (evaluate! binding "(helper 5)")
                    :answer (evaluate! binding "answer")
                    :ghost-denied? (deny? #(evaluate! binding "ghost"))
                    :made-on-disk (slurp (made-path dir))
                    :spec-coordinate (:spec/coordinate (:spec binding))
                    :expected-spec-coordinate (:spec-coordinate info)}]
      (spit (outcomes-path dir) (pr-str outcomes))
      (println "RESUME-OK"))))

(defn- phase-mismatch-spec! [dir]
  (require-sandbox!)
  (install-file-store! dir)
  (let [info (assoc (slurp-edn (journal-path dir))
                    :spec-coordinate "js1:tampered-coordinate")]
    (expect-js1-error! #(reconstruct! dir info) :coordinate-mismatch)))

(defn- phase-mismatch-runtime! [dir]
  (require-sandbox!)
  (install-file-store! dir)
  (let [info (assoc (slurp-edn (journal-path dir))
                    :runtime-coordinate "js1-rt/v1:tampered")]
    (expect-js1-error! #(reconstruct! dir info) :runtime-mismatch)))

(defn- last-completed-id [state]
  (->> (vals (:records state))
       (filter #(= :completed (:status %)))
       (sort-by :binding_seq)
       last
       :id))

(defn- phase-unsettled! [dir]
  (require-sandbox!)
  (install-file-store! dir)
  (let [info (slurp-edn (journal-path dir))
        state0 (store-read! dir)
        target-id (last-completed-id state0)
        original (get-in state0 [:records target-id])
        first-id (->> (vals (:records state0))
                      (sort-by :binding_seq)
                      first
                      :id)]
    ;; Case 1: the target record is rewritten as the append-only crash
    ;; shape — begin + edit intent landed, neither outcome nor completion
    ;; did.  The actuation state of that edit is UNKNOWN, so the honest
    ;; resume refuses.
    (store-write!
     dir
     (assoc-in state0 [:records target-id]
               (assoc original
                      :status :pending :result nil
                      :source "(project/edit \"unsettled.txt\" :absent \"maybe\")"
                      :receipts [{:seq 0 :op :project/edit
                                  :args ["unsettled.txt" :absent "maybe"]
                                  :phase :intent}])))
    (expect-js1-error! #(reconstruct! dir info)
                       :history-invalid :pending-history)
    (when (fs/exists? (str (root-dir dir) "/unsettled.txt"))
      (throw (ex-info "the refused resume performed a project actuation" {})))
    (println "UNSETTLED-OK")
    ;; Case 2, from the clean state: a gap in the binding's durable total
    ;; order is a torn record, refused as malformed history.
    (store-write!
     dir
     (-> state0
         (assoc-in [:records first-id :binding_seq] 5)
         (assoc-in [:records target-id] original)))
    (expect-js1-error! #(reconstruct! dir info)
                       :history-invalid :malformed-history)
    (when (fs/exists? (str (root-dir dir) "/unsettled.txt"))
      (throw (ex-info "the refused resume performed a project actuation" {})))
    (println "GAP-OK")))

(defn- run-phase! []
  (let [phase (phase-env)
        dir (dir-env)]
    (when (or (str/blank? phase) (str/blank? dir))
      (throw (ex-info "child mode needs SAMIZDAT_JS1_BOUNDARY_PHASE and _DIR"
                      {:phase phase})))
    (case phase
      "record" (phase-record! dir)
      "resume" (phase-resume! dir)
      "mismatch-spec" (phase-mismatch-spec! dir)
      "mismatch-runtime" (phase-mismatch-runtime! dir)
      "unsettled" (phase-unsettled! dir)
      "tool-refresh" (phase-tool-refresh! dir)
      "prompt" (phase-prompt! dir)
      (throw (ex-info "unknown phase" {:phase phase})))
    (System/exit 0)))

;; ─── suite mode: spawn the children, assert on their world ──────────────────

(def ^:private m2-jars
  ["borkdude/edamame/1.5.39/edamame-1.5.39.jar.jolt"
   "org/babashka/sci.impl.types/0.0.3/sci.impl.types-0.0.3.jar.jolt"
   "borkdude/graal.locking/0.0.2/graal.locking-0.0.2.jar.jolt"
   "org/clojure/tools.reader/1.5.2/tools.reader-1.5.2.jar.jolt"])

(defn- jolt-bin
  "The jolt launcher: explicit override, then the sibling checkout of this
  project (the dev layout this harness is written against)."
  []
  (let [candidates (remove str/blank?
                           [(jolt.host/getenv "SAMIZDAT_JOLT_BIN")
                            (let [wd (System/getProperty "user.dir")]
                              (str wd "/../jolt/bin/jolt"))])]
    (some #(when (fs/exists? %) %) candidates)))

(defn- scheme-bin []
  (or (let [e (jolt.host/getenv "JOLT_CHEZ")]
        (when (and e (fs/exists? e)) e))
      (let [e "/usr/local/bin/scheme"]
        (when (fs/exists? e) e))))

(defn- prerequisites []
  (let [bin (jolt-bin)
        scheme (scheme-bin)
        sci-src (let [b (jolt-bin)]
                  (when b (str (fs/parent (fs/parent b)) "/vendor/sci/src")))
        home (jolt.host/getenv "HOME")
        jars (when home
               (mapv #(str home "/.m2/repository/" %) m2-jars))]
    {:jolt bin
     :scheme scheme
     :sci-src sci-src
     :jars jars
     :ready? (and bin scheme
                  sci-src (fs/exists? sci-src)
                  (every? #(and % (fs/exists? %)) jars))}))

(defn- project-dir []
  (System/getProperty "user.dir"))

(defn- child-classpath
  "The full project classpath (resolved exactly as `jolt path` resolves
  it, in a child of this project directory) plus the SCI roots the
  sandbox needs — everything samizdat.agent.sandbox, samizdat.workflow
  and samizdat.agent.resume load in child mode."
  []
  (let [{:keys [jolt scheme]} (prerequisites)
        ;; jolt path resolves the deps.edn of its working directory
        {:keys [exit out err]} (proc/run
                                {:timeout-ms 120000
                                 :env {"PATH" "/usr/local/bin:/usr/bin:/bin"
                                       "HOME" (jolt.host/getenv "HOME")
                                       "JOLT_CHEZ" scheme
                                       "JOLT_QUIET" "1"
                                       "JOLT_PWD" (project-dir)}}
                                jolt "path")]
    (when (not= 0 exit)
      (throw (ex-info (str "jolt path failed: " err) {})))
    (let [base (str/trim out)]
      (when-not (str/includes? base "/src")
        (throw (ex-info "jolt path returned no source roots" {:out out})))
      base)))

(defn- run-child!
  "Spawn one phase in a fresh jolt process with the full classpath + SCI.
  Returns {:exit :out :err :timeout}."
  [cp phase dir]
  (let [{:keys [jolt scheme]} (prerequisites)
        this-file (str (project-dir) "/test/samizdat/js1_boundary_test.clj")]
    (proc/run
     {:timeout-ms 300000
      :env {"PATH" "/usr/local/bin:/usr/bin:/bin"
            "HOME" (jolt.host/getenv "HOME")
            "JOLT_CHEZ" scheme
            "JOLT_QUIET" "1"
            "JOLT_PWD" (project-dir)
            "SAMIZDAT_JS1_BOUNDARY_PHASE" phase
            "SAMIZDAT_JS1_BOUNDARY_DIR" dir}}
     jolt "-Scp" cp "run" this-file)))

(defn- sci-classpath
  "Project classpath plus the vendored SCI source and its four jars."
  [cp]
  (let [{:keys [sci-src jars]} (prerequisites)]
    (str/join ":" (into [cp sci-src] jars))))

(deftest js1-durable-restart-across-os-process-boundaries
  (let [{:keys [ready? jolt scheme]} (prerequisites)]
    (if (or (not ready?)
            (not= "1" (jolt.host/getenv "SAMIZDAT_JS1_BOUNDARY_TEST")))
      (is true (str "skipped: needs the jolt binary, Chez scheme, vendored"
                    " SCI roots, the four SCI Maven jars, and"
                    " SAMIZDAT_JS1_BOUNDARY_TEST=1"
                    (when jolt (str " (jolt: " jolt ")"))
                    (when scheme (str " (scheme: " scheme ")"))))
      (let [base-cp (child-classpath)
            cp (sci-classpath base-cp)
            dir (str "/tmp/samizdat-js1-boundary-" (random-uuid))]
        (fs/create-dirs dir)
        (try
          (testing "the record process mints the binding and durable history"
            (let [{:keys [exit out err timeout]} (run-child! cp "record" dir)]
              (is (not timeout) "record phase finished")
              (is (= 0 exit) (str "record exit 0\nstdout: " out "\nstderr: " err))
              (is (str/includes? out "RECORD-OK"))
              (is (= "made-by-record" (slurp (made-path dir)))
                  "the edit really actuated in the record process")
              (is (fs/exists? (journal-path dir)))
              (is (= 4 (count (file-history dir (str "bind:main:" work-id))))
                  "helper, edit, observation, and the failed eval are all durable")))
          (testing "a NEW process resumes: whole history, one SCI context, no re-invocation"
            (let [{:keys [exit out err timeout]} (run-child! cp "resume" dir)]
              (is (not timeout) "resume phase finished")
              (is (= 0 exit) (str "resume exit 0\nstdout: " out "\nstderr: " err))
              (is (str/includes? out "RESUME-OK"))
              (let [o (slurp-edn (outcomes-path dir))]
                (is (= 10 (:helper o))
                    "the helper def from the first eval survived the crash")
                (is (= 42 (:answer o))
                    "cross-eval state (answer = helper 21) survived")
                (is (true? (:ghost-denied? o))
                    "the failed eval's def never committed and never replayed")
                (is (= "tampered-after-crash" (:made-on-disk o))
                    "replay invoked zero real project operations — the edit
                     was served from its receipt, not re-run")
                (is (= (:expected-spec-coordinate o) (:spec-coordinate o))
                    "the reconstructed binding is spec-identical to the original"))))
          (testing "a tampered spec coordinate fails closed in a fresh process"
            (let [{:keys [exit out err timeout]} (run-child! cp "mismatch-spec" dir)]
              (is (not timeout))
              (is (= 0 exit) (str "mismatch-spec verified the closed failure"
                                  "\nstdout: " out "\nstderr: " err))
              (is (str/includes? out "CLOSED-OK :coordinate-mismatch"))))
          (testing "a tampered runtime coordinate fails closed before allocation"
            (let [{:keys [exit out err timeout]} (run-child! cp "mismatch-runtime" dir)]
              (is (not timeout))
              (is (= 0 exit) (str "mismatch-runtime verified the closed failure"
                                  "\nstdout: " out "\nstderr: " err))
              (is (str/includes? out "CLOSED-OK :runtime-mismatch"))))
          (testing "an unsettled receipt history fails closed with no actuation"
            (let [{:keys [exit out err timeout]} (run-child! cp "unsettled" dir)]
              (is (not timeout))
              (is (= 0 exit) (str "unsettled verified both closed failures"
                                  "\nstdout: " out "\nstderr: " err))
              (is (str/includes? out ":pending-history"))
              (is (str/includes? out ":malformed-history"))
              (is (not (fs/exists? (str dir "/root/unsettled.txt")))
                  "neither refused resume performed a project actuation")
              (is (= "tampered-after-crash" (slurp (made-path dir)))
                  "made.txt untouched through every phase — no phase after
                   record ever invoked a real project operation")))
          (testing "the eval tool absorbs rollback and stale bindings so the next eval works"
            (let [tdir (str dir "-tools")]
              (fs/create-dirs tdir)
              (let [{:keys [exit out err timeout]} (run-child! cp "tool-refresh" tdir)]
                (is (not timeout))
                (is (= 0 exit) (str "tool-refresh verified the refresh contract"
                                    "\nstdout: " out "\nstderr: " err))
                (is (str/includes? out "TOOL-REFRESH-OK"))
                (is (= [[:commit-1 true "=> 1"]
                        [:fail false nil]
                        [:after-rollback true "=> 2"]
                        [:rolled-back-denied true nil]
                        [:after-stale true "=> 3"]]
                       (slurp-edn (str tdir "/tool-refresh.edn")))
                    "commit -> failure(rollback) -> next eval works with
                     committed state -> rolled-back def denied -> out-of-band
                     rebuild absorbed transparently"))))
          (finally (fs/delete-tree dir) (fs/delete-tree (str dir "-tools"))))))))

(deftest js1-prompt-is-the-closed-surface
  ;; The canary-failure regression (artifacts/self-hosting-canary.edn): a JS1
  ;; run's model was shown the generic prompt plus a trailing disclaimer and
  ;; spent its turns calling grep/thesis/write_file/give_up into a gate that
  ;; could only refuse.  The child renders the bounded prompt from a REAL
  ;; :project/develop binding; these assertions read what it wrote.
  (let [{:keys [ready?]} (prerequisites)]
    (if (or (not ready?)
            (not= "1" (jolt.host/getenv "SAMIZDAT_JS1_BOUNDARY_TEST")))
      (is true (str "skipped: needs the jolt binary, Chez scheme, vendored"
                    " SCI roots, the four SCI Maven jars, and"
                    " SAMIZDAT_JS1_BOUNDARY_TEST=1"))
      (let [base-cp (child-classpath)
            cp (sci-classpath base-cp)
            dir (str "/tmp/samizdat-js1-prompt-" (random-uuid))]
        (fs/create-dirs dir)
        (try
          (let [{:keys [exit out err timeout]} (run-child! cp "prompt" dir)]
            (is (not timeout) "prompt phase finished")
            (is (= 0 exit) (str "prompt exit 0\nstdout: " out "\nstderr: " err))
            (is (str/includes? out "PROMPT-OK"))
            (let [o (slurp-edn (str dir "/prompt-outcomes.edn"))
                  prompt (slurp (str dir "/prompt.txt"))
                  attenuated (slurp (str dir "/prompt-attenuated.txt"))]
              (testing "exactly the gated high-level tools are taught"
                (is (= ["complete" "doc" "done" "eval"] (:vocabulary o)))
                (is (= (set (:vocabulary o)) (set (:tools-taught o)))
                    "the prompt's call signatures are the gated set"))
              (testing "the prompt's operations are the effective ContextSpec"
                (is (= ["project/edit" "project/list" "project/read"
                        "project/search" "project/stat"]
                       (:brief-names o)
                       (:complete-project o))
                    "prompt briefs and complete-capability read the one catalog")
                (is (= #{:actuation :observation} (set (:brief-effects o)))
                    "the briefs carry the op effects (edit actuates)")
                (is (true? (:edit-doc-substring? o))
                    "the prompt serves exactly what doc serves for project/edit")
                (is (true? (:read-doc-substring? o)))
                (doseq [c (:brief-names o)]
                  (is (str/includes? prompt c) (str c " advertised"))))
              (testing "no old-surface tool name appears anywhere"
                (doseq [t ["branch_theses" "cells" "edit_file" "fetch_artifact"
                           "fetch_turn" "give_up" "grep" "introspect" "lsp"
                           "manifest" "message" "read_file" "recall"
                           "reload_cells" "remember" "shell" "skill" "task"
                           "thesis" "write_file"]]
                  (is (not (str/includes? prompt t))
                      (str "the bounded prompt never names " t))))
              (testing "the live prompt carries the per-operation docs"
                (is (str/includes? prompt "project/edit [[rel-path base new-content]]")
                    "the anchored-edit arglist is taught, rendered exactly as doc serves it")
                (is (str/includes? prompt "Effect: actuation.")))
              (testing "the fence mechanics and the persistent-helper rhythm are taught"
                (is (str/includes? prompt "```tool-call"))
                (is (str/includes? prompt "(defn halve"))
                (is (str/includes? prompt "persist"))
                (is (str/includes? prompt "rolled back")))
              (testing "an attenuated binding's prompt omits the ungranted operations"
                (is (= ["project/list" "project/read"]
                       (:attenuated-brief-names o)
                       (:attenuated-complete o)))
                (is (str/includes? attenuated "project/read"))
                (is (str/includes? attenuated "project/list"))
                (doseq [absent ["project/edit" "project/search" "project/stat"]]
                  (is (not (str/includes? attenuated absent))
                      (str absent " is not granted, so it is not advertised"))))))
          (finally (fs/delete-tree dir)))))))

;; ─── child-mode entry ───────────────────────────────────────────────────────
;;
;; When the environment names a phase, this file IS the harness: run the
;; phase and exit, so the suite process (which spawned us) asserts on our
;; exit code, our stdout markers, and the world we left on disk.
(when-let [phase (phase-env)]
  (run-phase!))
