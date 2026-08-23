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
  agent edit is a workflows/save! followed by the same compile-loop call the
  driver makes, and a failed compile means the previous version keeps
  driving. Activation is serialized by construction — each run loads and
  compiles once, at start.

  The beam still composes its turns directly (karamazov-ioo.20 tracks its
  migration); both drivers share the step implementations in
  samizdat.agent.loop, so this manifest and the beam cannot drift apart on
  behavior — only on composition."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jolt.fs :as fs]
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
            [samizdat.agent.state :as state]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.store.workflows :as workflows])
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
  "Which manifest a run should drive: the configured name, or the factory
  default. HARNESS_LOOP or a project's .samizdat/config.edn set :run :loop."
  [config]
  (or (get-in config [:run :loop]) loop-name))

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
  (cells/load-cells!)
  ;; Register any composed sub-loops as cells before the parent references them.
  (register-subworkflows! definition)
  (let [compiled (myc/pre-compile definition)]
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
         row (if (io/resource res)
               (workflows/seed! conn name res)
               (workflows/load-latest conn name))]
     (when-not row
       (throw (ex-info (str "no loop manifest named '" name
                            "' — no resource at " res " and nothing stored")
                       {:name name})))
     {:name name
      :version (:version row)
      :definition (read-definition (:edn row))
      :compiled (compile-loop (read-definition (:edn row)))})))

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
  (some-> (io/resource (str "prompts/" name ".md")) slurp))

(defn- manifest-name-from-path [p]
  (-> (str p) (str/replace #".*/" "") (str/replace #"\.edn$" "")))

(defn catalog
  "The workflows available to select or adapt: every factory manifest and every
  stored one, each with its :description. This is the set the supervisor reads to
  decide whether to switch a run to a different workflow, tweak an existing one,
  or author a new one — the compiled menu the self-healing loop chooses from.
  A manifest with no :description still lists, with an empty one."
  [conn]
  (let [factory (->> (try (fs/glob "resources/manifests" "*.edn") (catch Throwable _ nil))
                     (map manifest-name-from-path)
                     set)
        stored (->> (try (workflows/names conn) (catch Throwable _ nil))
                    ;; workflows/names yields rows ({:name :version :versions}),
                    ;; factory yields name strings — normalise to names.
                    (map (fn [x] (if (map? x) (:name x) x)))
                    (remove nil?)
                    set)]
    (->> (sort (into factory stored))
         (keep (fn [nm]
                 (let [res (manifest-resource nm)
                       edn (if (io/resource res)
                             (slurp (io/resource res))
                             (some-> (workflows/load-latest conn nm) :edn))]
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

(defn- js1-binding
  "Create a JS1 sandbox binding for the run, or nil.

   Activated ONLY when config :run :js1/profile is set to a truthy value.
   The profile string (e.g. \"single-player\") is stored in the ctx as
   :js1/profile — a display/journal label; the BINDING this function
   returns is the canonical signal the tool gate and eval routing both
   read (:js1/binding).

   Trust boundary: the config key is read once here by controller code;
   the model never sees or sets it.  The preset is hardcoded to
   :project/develop; the instance key is :main (persistent across turns);
   the work-id is the run-id, binding one instance per run.

   The sandbox provider, spec, instance, and binding IDs are journaled
   so a resumed run can verify it reconstructs the same context.

   JS1 is an explicit bounded workflow.  If its SCI dependency is absent,
   creation FAILS CLOSED — it must never silently select the live REPL."
  [conn run-id config root]
  (when (get-in config [:run :js1/profile])
    (try
      (require 'samizdat.agent.sandbox)
      (let [provider-fn (resolve 'samizdat.agent.sandbox/provider)
            bind-fn     (resolve 'samizdat.agent.sandbox/bind!)
            desc-fn     (resolve 'samizdat.agent.sandbox/describe)
            prov (provider-fn {:root root})
            profile-name (str (get-in config [:run :js1/profile]))
            binding (bind-fn prov (str run-id)
                            {:preset :project/develop
                             :root root
                             :instance/key :main})
            desc (desc-fn binding)]
        ;; Journal the binding identity for resume verification.  The preset
        ;; keyword is written through data.json, which reads back as a plain
        ;; string (colon form, or name-only under jolt's port); resume
        ;; converts it back to the catalog keyword before binding.
        (journal/note! conn run-id :js1-binding-created
                       {:data {:profile profile-name
                               :binding-id (:samizdat.sandbox/binding-id desc)
                               :instance-id (:samizdat.sandbox/instance-id desc)
                               :spec-coordinate (:samizdat.sandbox/spec-coordinate desc)
                               :preset (:samizdat.sandbox/preset desc)}})
        binding)
      (catch Throwable e
        ;; Fail closed either way; a sandbox-domain error (unknown preset,
        ;; spec conflict, ...) keeps its own diagnostics instead of being
        ;; relabeled, and only an unavailable sandbox is labeled as such.
        (if (:samizdat.sandbox/error (ex-data e))
          (throw e)
          (throw (ex-info "JS1 sandbox unavailable; refusing live-eval fallback"
                          {:js1/error :sandbox-unavailable :run-id run-id}
                          e)))))))

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
         ;; JS1 binding: one persistent SCI instance for the whole run when
         ;; explicitly configured. Its absence is allowed only for non-JS1.
        js1 (js1-binding conn run-id config root)
        ctx {:conn conn :run-id run-id :config config
             :llm-adapter llm-adapter :llm-config llm-config
             :root root
             ;; A run-start git baseline so a finalization critic can review
             ;; exactly what this run changed. Only captured for a non-default
             ;; manifest — the factory loop has no critic to read it, and
             ;; skipping it keeps the common path off git entirely.
             :git-baseline (when (not= loop-nm loop-name) (gitdiff/baseline root))
             ;; A per-run eval session, so defs the agent makes with `eval`
             ;; persist across their turns (define, then use) — REPL-first
             ;; development against the live image.
             :repl-session (when-not js1 (repl/new-session))
             :max-turns max-turns
             ;; JS1 profile flags — set only here, read by phase-refusal.
             :js1/profile (when js1 (str (get-in config [:run :js1/profile])))
             :js1/binding js1}]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    ;; Which loop drove this run, durably: an agent reading a surprising run
    ;; back needs to know which version of itself produced it.
    (journal/note! conn run-id :loop-workflow
                   {:data {:name loop-nm :version version}})
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
        ;; The run's eval namespace does not outlive the run
        ;; (code-review-2026-08 #6): one namespace per run, never removed, was
        ;; unbounded growth on a serve process.
        (repl/close-session (:repl-session ctx))))))
