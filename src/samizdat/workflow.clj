;; samizdat - a self-hosting agentic harness
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

(ns samizdat.workflow
  "The loop definition's lifecycle: read from the db, compile through
  mycelium's checks, drive a run.

  This ns is the seam the mutation protocol (karamazov-ioo.11) grows on: an
  agent edit is a us/save! :manifest followed by the same compile-loop call the
  driver makes, and a failed compile means the previous version keeps
  driving. Activation is serialized by construction — each run loads and
  compiles once, at start.

  The beam drives this manifest too (karamazov-ioo.20, done): it compiles the
  per-turn SLICE of the run's loop — `turn-manifest` below — and runs one
  branch through it per scheduling round, owning the scheduling, culling,
  forking and finishing the manifest's :finish would otherwise do for a single
  branch. So there is one driver and one definition of a turn.

  It was not always so, and the gap was invisible: the beam called
  samizdat.agent.loop's steps directly, `run!` here was reached only from
  tests, and `:run :loop` was documented in config, parsed from HARNESS_LOOP,
  and read by nothing on the production path — the critic, team, feature and
  decompose manifests could not run outside the suite. `run!` remains as the
  single-branch driver a role's sub-loop uses (see `compiled-manifest`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mycelium.core :as myc]
            [mycelium.cell :as cell]
            [mycelium.compose :as compose]
            [mycelium.workflow :as wf]
            [samizdat.cells :as cells]
            [samizdat.config :as config]
            [samizdat.llm.registry :as registry]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.loop :as branch-loop]
            [samizdat.repl :as repl]
            [samizdat.session :as session]
            [samizdat.watch :as watch]
            [samizdat.userspace :as userspace]
            [samizdat.agent.state :as state]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]
            [samizdat.store.userspace :as us])
  (:refer-clojure :exclude [run!]))

(def loop-name "loop")
(def loop-resource "manifests/loop.edn")

(defn manifest-resource
  "The factory resource path a manifest name seeds from, e.g. \"loop\" ->
  \"manifests/loop.edn\". A manifest with no such resource lives only in the
  workflows table — one the agent authored at runtime."
  [name]
  (str "manifests/" name ".edn"))

(defn active-loop-name
  "Which manifest a run should drive, in precedence order: the name the caller
  configured, then what selection chose, then the factory default.
  HARNESS_LOOP or a project's .samizdat/config.edn set :run :loop.

  THE CONFIGURED NAME ALWAYS WINS. `selected` is what samizdat.agent.select
  picked from the catalogue for a run that named no workflow of its own; a run
  that did name one is never overridden, because a caller who pinned a loop
  asked a question this has no business re-answering.

  Kept as one function with the precedence in it — rather than resolved at the
  call site — because it is the ONLY place a run decides what drives it, and
  that is worth having somewhere a reader can find."
  ([config] (active-loop-name config nil))
  ([config selected]
   (or (get-in config [:run :loop])
       selected
       loop-name)))

(defn read-definition
  "Parse a workflow definition from EDN text. Dispatch predicates stay as
  forms here; maestro evaluates them at compile time."
  [edn-text]
  (edn/read-string edn-text))

(defn register-subworkflows!
  "A manifest can compose sub-loops: `:subworkflows {cell-id manifest-name}`
  registers each named manifest as a workflow-cell (mycelium.compose) under
  cell-id, so the parent can run it as one node. Sub-manifests are read from
  their factory resource — a composed loop is authored, not agent-generated in
  the db (yet). Runs before the parent compiles, since the parent references
  these cell ids. A no-op for a flat manifest."
  [definition]
  (doseq [[cell-id mname] (:subworkflows definition)]
    (let [res (manifest-resource mname)]
      (when-not (io/resource res)
        (throw (ex-info (str "sub-workflow manifest '" mname "' has no resource "
                             res) {:manifest mname})))
      (compose/register-workflow-cell!
       cell-id (read-definition (slurp (io/resource res))) {}))))

(def ctx-keys
  "The run-scoped resources every driver hands a cell, as a set.

  A cell receives `ctx` and `data`. `data` is the workflow's value and
  mycelium checks its shape; `ctx` is mycelium's `resources` slot, and its
  keys were conventional — RFC-002 recorded that a cell reading a key the
  driver does not set gets `nil` at run time, with nothing to say so until
  something downstream fell over.

  This is the contract, and it is checked from both ends: `compile-loop`
  refuses a cell whose `:requires` names a key that is not here, and
  `beam-test` asserts the production ctx actually carries every key that is.
  One without the other is worth little — a contract nobody satisfies, or a
  driver nobody holds to it.

  Mechanism, not policy: it describes what the base provides, not what any
  project should do with it."
  #{;; RFC-002's documented set
    :conn :run-id :config :llm-adapter :llm-config :root :max-turns :abort
    ;; What the beam driver adds
    :problem :beam? :beam-width :turn-workflow :iterating-loop? :git-baseline
    :repl-session :sessions :engine-sessions :live-branches})

(defn cell-requires
  "The ctx keys `cell-id` declares it reads. `:requires` is mycelium's own
  vocabulary — the guide documents it beside `:doc` and `:effects` — and it
  was simply unused here, the same shape of miss as the manifests using only
  `:must-follow` when `:must-precede` was sitting there."
  [cell-id]
  (set (:requires (cell/get-cell cell-id))))

(defn- check-requires!
  "Refuse a manifest whose cells want ctx keys no driver provides.

  At COMPILE time, so a bad edit fails in the mutation protocol's validate
  step — before the soak, and long before a nil surfaces as a
  NullPointerException six cells downstream with nothing pointing back here."
  [definition]
  (let [wanted (for [[node cell-id] (:cells definition)
                     k (cell-requires cell-id)
                     :when (not (ctx-keys k))]
                 {:node node :cell cell-id :key k})]
    (when (seq wanted)
      (throw (ex-info
              (str "cells require ctx keys no driver provides: "
                   (str/join ", " (for [{:keys [node cell key]} wanted]
                                    (str node " (" cell ") wants " key)))
                   ". Either the key belongs in workflow/ctx-keys and the"
                   " drivers must set it, or the cell should not be asking.")
              {:wanted wanted :provided (sort ctx-keys)})))))

(defn invariants
  "Every ordering rule a manifest CLAIMS, enforced or not.

  The list exists because `:constraints` alone could not answer the question
  an editor actually has. A manifest carrying two constraints looks like a
  manifest with two invariants; the beam had four and the turn had five, and
  the rest lived in cell docstrings — so there was no way to tell, from the
  file being edited, which of its rules the compiler would catch. RFC-002
  recorded that as a gap: an editor cannot know what is defended."
  [definition]
  (vec (:invariants definition)))

(def ^:private constraint-keys
  "The keys mycelium's checker reads, by constraint type. Anything else in an
  invariant entry — `:protects`, `:enforced`, `:unenforced-because` — is for
  the reader and must not reach the compiler."
  [:type :if :then :cell :before :cells])

(defn enforced-constraints
  "mycelium `:constraints`, DERIVED from the enforced invariants.

  One list, not two. A manifest that declared its invariants separately from
  its constraints would let the two disagree, and the disagreement would say
  the opposite of the truth in the more dangerous direction — a rule
  documented as enforced that nothing checks.

  An explicit `:constraints` is still honoured and appended, so a project
  manifest stored before this key existed keeps compiling unchanged."
  [definition]
  (into (vec (:constraints definition))
        (comp (filter :enforced)
              (map #(into {} (filter (fn [[k _]] (some #{k} constraint-keys))) %)))
        (:invariants definition)))

(defn unenforced-invariants
  "The rules a manifest claims that nothing checks. Each must say why: `no
  constraint` and `no constraint yet` are different facts, and only one of
  them is a decision."
  [definition]
  (vec (remove :enforced (:invariants definition))))

(defn compile-loop
  "Compile a loop definition through mycelium's full static checking:
  structure, dispatch coverage, reachability, and the :constraints that make
  the loop's invariants compile-time errors. Throws on any violation —
  which is the mutation protocol's first line of defense. Logs, and returns
  compiled with, any :mycelium/compile-warnings (undeclared cell effects)."
  [definition]
  ;; Load the cells from resources before every compile. The cell registry is
  ;; global mutable state, and a non-empty registry is not proof the LOOP's
  ;; cells are present (a test or another workflow may have registered
  ;; different ones) — so this always loads rather than guarding on emptiness.
  ;; Idempotent, cheap (one file), and it picks up any edited cell, which is
  ;; the hot-reload the mutation protocol will build on.
  ;; A definition with no cells is not a loop, and mycelium will happily
  ;; compile one: `pre-compile` on nil returns an FSM whose start state is
  ;; missing, and the failure surfaces later as a ClassCastException with
  ;; nothing pointing back here. That is how a manifest read from the wrong
  ;; column produced a run that died four frames deep in the driver — the
  ;; store returns `:body` and the caller was still reading `:edn`, so
  ;; `read-definition` was handed nil and returned it.
  (when-not (seq (:cells definition))
    (throw (ex-info (str "not a workflow definition: no :cells"
                         (when (nil? definition) " (the definition is nil)"))
                    {:definition definition})))
  (cells/load-cells!)
  ;; Register any composed sub-loops as cells before the parent references them.
  (register-subworkflows! definition)
  (check-requires! definition)
  (let [compiled (myc/pre-compile
                  (assoc definition :constraints (enforced-constraints definition)))]
    (when-let [warnings (:mycelium/compile-warnings (:compiled-fsm compiled))]
      (log/warn "loop definition compiled with warnings:" (pr-str warnings)))
    compiled))

(defn load-loop!
  "The loop to drive a run: seed its factory resource on first use (if it has
  one), then load and compile the latest stored version. Named manifests let a
  sophisticated loop live in the workflows table beside the default; a name
  with no resource and no stored version is an error. Returns {:name :version
  :definition :compiled}."
  ([conn] (load-loop! conn loop-name))
  ([conn name]
   (let [res (manifest-resource name)
         row (if-let [r (io/resource res)]
               (us/seed! conn :manifest name (slurp r))
               (us/load-latest conn :manifest name))]
     (when-not row
       (throw (ex-info (str "no loop manifest named '" name
                            "' — no resource at " res " and nothing stored")
                       {:name name})))
     ;; `:body`, not `:edn`. store/workflows.clj used to rename the column on
     ;; the way out; reading the raw userspace row means the key is what the
     ;; table calls it. Read once and compiled from the same value, so a
     ;; caller cannot get a definition and a compiled FSM built from different
     ;; text.
     (let [definition (read-definition (:body row))]
       {:name name
        :version (:version row)
        :definition definition
        :compiled (compile-loop definition)}))))

;; --- the per-turn slice, for the beam ---------------------------------------
;;
;; A loop manifest describes a WHOLE run: the per-turn chain, a back edge from
;; :route to :start, and a :finish that closes the branch and run rows. The
;; beam needs the per-turn chain alone — it owns scheduling, culling, forking
;; and finishing across many branches, and a driver that runs one branch to
;; completion cannot be scheduled against four others.
;;
;; Rather than maintain a second per-turn manifest per loop (two files to keep
;; in agreement, which is how the two drivers drifted apart in the first
;; place), the slice is DERIVED: every edge that would loop back to the start
;; node or hand off to :loop/finish instead goes to :end. What remains is one
;; turn, and the beam does the rest. The cells, the dispatches and the
;; constraints are untouched, so a manifest edit reaches both drivers.

(def start-node
  "The manifest's entry node. A convention every shipped manifest follows and
  mycelium's own compile assumes."
  :start)

(defn finish-nodes
  "Nodes whose cell is :loop/finish — the whole-run teardown the beam owns."
  [definition]
  (set (keep (fn [[node cell]] (when (= :loop/finish cell) node))
             (:cells definition))))

(defn iterating?
  "Whether one pass through this manifest's slice is one TURN — a single model
  call the beam can schedule against four siblings — or a whole-run workflow
  that does its own looping inside one call.

  Two conditions, and both are needed. The slice must contain :llm/infer, so
  that a pass is one model call: `orchestrator` loops back to its start node,
  but that node is an entire nested worker RUN, and treating it as a turn
  would put a multi-minute job under the 900s turn deadline and run five of
  them at once. And the chain must loop back to the start node, so that a pass
  is a turn rather than the whole job: `team`, `feature` and `decompose` run
  straight through.

  loop / critic / review / worker / reviewer / supervisor iterate; team,
  feature, decompose and orchestrator do not. The answer decides the beam's
  width and whether the per-turn deadline applies."
  [definition]
  (let [cells (set (vals (:cells definition)))
        loops-back? (some (fn [[_ to]]
                            (if (map? to)
                              (some #(= start-node %) (vals to))
                              (= start-node to)))
                          (:edges definition))]
    (boolean (and (contains? cells :llm/infer) loops-back?))))

(defn turn-manifest
  "`definition` reduced to ONE turn: edges back to the start node and edges
  into :loop/finish are redirected to :end, and the finish node is dropped
  (mycelium's reachability check refuses an orphan).

  Returns a definition that compiles and runs exactly like the original up to
  the turn boundary, and then stops."
  [definition]
  (let [finish (finish-nodes definition)
        terminal (conj finish start-node)
        retarget (fn [to] (if (contains? terminal to) :end to))]
    (-> definition
        (assoc :cells (into {} (remove (comp finish key)) (:cells definition)))
        (assoc :edges
               (into {}
                     (for [[from to] (:edges definition)
                           ;; The finish node's own outgoing edge goes with it.
                           :when (not (contains? finish from))]
                       [from (if (map? to)
                               (into {} (map (juxt key (comp retarget val))) to)
                               (retarget to))]))))))

(defn compile-turn-loop
  "Load the named manifest and compile BOTH forms: the whole-run definition
  (for provenance and for `iterating?`) and its per-turn slice, which is what
  the beam drives. Returns {:name :version :definition :iterating? :compiled}."
  [conn name]
  (let [{:keys [version definition]} (load-loop! conn name)]
    {:name name
     :version version
     :definition definition
     :iterating? (iterating? definition)
     :compiled (compile-loop (turn-manifest definition))}))

(defn compiled-manifest
  "Compile the named factory manifest to a runnable sub-loop. The seam a role
  cell uses to run a role's own loop (worker for an implementor, reviewer for a
  reviewer). Compiled fresh each call, so a cell edit is picked up. Throws if
  the name has no factory resource."
  [name]
  (let [res (manifest-resource name)]
    (when-not (io/resource res)
      (throw (ex-info (str "no factory manifest resource for '" name "' at " res)
                      {:manifest name})))
    (compile-loop (read-definition (slurp (io/resource res))))))

(defn worker-compiled
  "The worker sub-loop, compiled — for a team cell that runs a worker per
  sub-task, each on its own branch. Compiled fresh (cells may have changed);
  the caller runs it N times."
  []
  (compiled-manifest "worker"))

(defn prompt-text
  "The text of a named prompt resource (resources/prompts/<name>.md), or nil if
  there is no such resource. The shared reader behind manifest :prompt injection
  and the team-worker roster."
  [name]
  ;; Through the userspace seam: a workflow's prompt is this project's prompt.
  ;; nil-tolerant, unlike prompt/prompt — a manifest declaring no :prompt and a
  ;; :prompt naming nothing are both "no suffix", not errors.
  (userspace/body :prompt name))

(def ^:private factory-manifest-names
  "The manifests that ship with the harness.

  A literal list, resolved against `io/resource` rather than globbed off a
  cwd-relative `resources/manifests`. Everything else in this namespace
  already reads manifests through io/resource — the glob was the one holdout,
  and it was the same bug provenance R3-11 fixed for the cells dir: a binary (or a
  process started anywhere but the project root) found no directory, caught
  the exception, and served the supervisor a catalogue with the factory half
  silently missing. There is no portable listing for classpath resources, so
  the set is enumerated and `catalog` drops any name that does not resolve —
  a manifest deleted from resources/ falls out rather than 404ing."
  ["loop" "beam" "critic" "orchestrator" "probe" "review" "reviewer"
   "supervisor" "worker" "team" "feature" "decompose"])

(defn catalog
  "The workflows available to select or adapt: every factory manifest and every
  stored one, each with its :description. This is the set the supervisor reads to
  decide whether to switch a run to a different workflow, tweak an existing one,
  or author a new one — the compiled menu the self-healing loop chooses from.
  A manifest with no :description still lists, with an empty one."
  [conn]
  (let [factory (->> factory-manifest-names
                     (filter #(io/resource (manifest-resource %)))
                     set)
        stored (->> (try (us/names conn :manifest) (catch Throwable _ nil))
                    ;; us/names yields rows ({:name :version :versions}),
                    ;; factory yields name strings — normalise to names.
                    (map (fn [x] (if (map? x) (:name x) x)))
                    (remove nil?)
                    set)]
    (->> (sort (into factory stored))
         (keep (fn [nm]
                 (let [res (manifest-resource nm)
                       edn (if (io/resource res)
                             (slurp (io/resource res))
                             (some-> (us/load-latest conn :manifest nm) :body))]
                   (when edn
                     (let [d (try (read-definition edn) (catch Throwable _ nil))]
                       {:name nm :description (str (:description d))})))))
         vec)))

(defn render-catalog
  "The workflow catalog as a text menu — one `- name — description` line each —
  for injecting into the supervisor's context."
  [conn]
  (str/join "\n" (for [{:keys [name description]} (catalog conn)]
                   (str "- " name (when (seq description) (str " — " description))))))

(defn workflow-prompt
  "A manifest may declare `:prompt <name>`, naming a prompt resource
  (resources/prompts/<name>.md) that is appended to the base system prompt for
  that workflow — how a workflow injects its own instructions at the start. A
  review manifest points at review guidance; the default loop declares none and
  runs the base prompt. Returns the text, or nil."
  [definition]
  (when-let [p (:prompt definition)]
    (prompt-text p)))

(defn role-ctx
  "The ctx a role's sub-loop runs under, with its LLM adapter and config swapped
  to the model assigned to `role` under config :run :role-models — e.g.
  {:implementor {:provider \"deepseek\"} :supervisor {:provider \"glm\"}}. A role
  with no entry keeps the run's default model. `:provider` may be omitted to keep
  the run's provider and only change the model. This is how a cheap model can
  implement while a stronger one reviews or supervises."
  [ctx role]
  (if-let [spec (get-in (:config ctx) [:run :role-models role])]
    (let [provider (or (some-> (:provider spec) name str/lower-case keyword)
                       (:provider (:llm-config ctx)))
          llm (config/provider-llm provider (dissoc spec :provider))]
      (assoc ctx :llm-adapter (registry/adapter-for provider) :llm-config llm))
    ctx))

(defn run-turn
  "Advance one branch by one turn, through the manifest.

  THE ONE DEFINITION OF A TURN. samizdat.agent.loop composed the same steps in
  compiled Clojure until this replaced it, which meant there were two
  definitions and an edit to the loop manifest reached only one of them. That
  is the drift karamazov-ioo.20 found the first time — the beam called the
  compiled composition while the manifest driver ran the same steps as cells,
  and nothing in the production path ever reached the manifest, so four
  workflows existed only under the test suite. Unifying the call site left the
  duplicate standing; this removes it.

  Lives here rather than in samizdat.agent.loop because a turn is now defined
  by a manifest, and loading a manifest is this namespace's job — agent.loop
  cannot require it without a cycle.

  For a caller that wants one turn rather than a whole run: the benches, and
  the tests that assert what a single turn does to a branch. Compiles the named
  manifest's per-turn slice fresh, so a cell or manifest edit is picked up."
  ([ctx branch turn] (run-turn ctx branch turn loop-name))
  ([ctx branch turn manifest-name]
   (let [wf (compile-loop
             (turn-manifest
              (read-definition
               (:body (us/seed! (:conn ctx) :manifest manifest-name
                                (slurp (io/resource (manifest-resource manifest-name))))))))
         data (myc/run-compiled wf ctx {:branch branch :turn turn})]
     (when (myc/error? data)
       (throw (ex-info "the turn manifest failed structurally"
                       {:error (myc/workflow-error data)})))
     (:branch data))))

(defn run!
  "Run one branch to completion under the stored loop definition.
  Returns {:status :answer :branch :run-id (:residual)}."
  [{:keys [conn config llm-adapter llm-config problem max-turns]}]
  (let [max-turns (or max-turns (get-in config [:run :max-turns]) 40)
        loop-nm (active-loop-name config)
        {:keys [version compiled definition]} (load-loop! conn loop-nm)
        run-id (runs/start-run! conn {:problem problem
                                      :provider (:provider llm-config)
                                      :model (:model llm-config)
                                      :max-turns max-turns
                                      :beam-width 1
                                      :prompt-digest (branch-loop/prompt-digest)})
        branch (state/new-branch {:id "B1" :problem problem
                                  :messages (branch-loop/initial-messages
                                             problem (workflow-prompt definition))})
        ;; The project root the file tools are confined to, and the shell tool
        ;; runs in. Configurable so a run can target another checkout.
        root (or (get-in config [:run :root]) (System/getProperty "user.dir"))
        ;; Make the project's own namespaces requirable from `eval` before any
        ;; branch takes a turn. The system prompt's whole first section is
        ;; REPL-first against the project under work, and without this that
        ;; instruction is unreachable the moment :run :root is not the harness.
        _ (repl/ensure-project-roots! root)
        ctx {:conn conn :run-id run-id :config config
             :llm-adapter llm-adapter :llm-config llm-config
             :root root
             ;; A run-start git baseline: what this run changed, for a
             ;; finalization critic AND — the part this used to miss — for the
             ;; ship gate's test rung.
             ;;
             ;; This was `(when (not= loop-nm loop-name) …)`, on the reasoning
             ;; that the factory loop has no critic to read it and skipping it
             ;; keeps the common path off git entirely. That was true when the
             ;; critic was the only reader. The ship gate reads it too now, via
             ;; changed-files, and with no baseline `changed` is nil, no focused
             ;; command is built, no tests run, and verify-block falls through
             ;; its last clause — which trusts rather than deadlocks. So the
             ;; default loop verified NOTHING while reporting a successful ship:
             ;; observed live, a run that shipped `{:test 19 :pass 49 :error 5}`
             ;; with the gate silently inert.
             ;;
             ;; Captured whenever anything downstream could use it.
             :git-baseline (when (or (not= loop-nm loop-name)
                                     (get-in config [:run :verify-focused?])
                                     (not (str/blank? (str (get-in config [:run :verify-cmd])))))
                             (gitdiff/baseline root))
             ;; A per-run eval session, so defs the agent makes with `eval`
             ;; persist across its turns (define, then use) — REPL-first
             ;; development against the live image.
             :repl-session (repl/new-session)
             :max-turns max-turns}]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    ;; The window findings are evaluated over.
    (session/mark-run! run-id)
    ;; The single-branch driver drains the same interventions queue the beam
    ;; does (loop/drain-directives!), so the watcher works here unchanged.

    ;; Which loop drove this run, durably: an agent reading a surprising run
    ;; back needs to know which version of itself produced it.
    (journal/note! conn run-id :loop-workflow
                   {:data {:name loop-nm :version version}})
    (let [stop-watch (watch/start! ctx)]
     (try
      (let [data (myc/run-compiled compiled ctx
                                   (cond-> {:branch branch :turn 1}
                                     ;; A team workflow fans out over these — one
                                     ;; worker per sub-task. The single-branch
                                     ;; loops ignore the key.
                                     (seq (get-in config [:run :subtasks]))
                                     (assoc :subtasks (get-in config [:run :subtasks]))))]
        (when (myc/error? data)
          ;; A structural failure mid-run is a harness bug, not a branch
          ;; outcome; surface it rather than shipping a half-closed run.
          (throw (ex-info "loop workflow failed structurally"
                          {:run-id run-id :error (myc/workflow-error data)})))
        (-> (select-keys data [:status :answer :branch :residual])
            (assoc :run-id run-id)))
      (finally
        (stop-watch)
        ;; SHORT-TERM BECOMES LONG-TERM, here too. This driver runs the factory
        ;; loop, which is what most runs use; distilling only in the beam meant
        ;; the common path measured everything and remembered none of it.
        (try
          (knowledge/distil-session! conn {:run-id run-id
                                           :findings (session/findings)
                                           :experiments (session/experiments)})
          (catch Throwable e
            (log/warn "distilling the session failed:" (ex-message e))))
        ;; The run's eval namespace does not outlive the run
        ;; (provenance CR1-6): one namespace per run, never removed, was
        ;; unbounded growth on a serve process.
        (repl/close-session (:repl-session ctx)))))))
