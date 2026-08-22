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
            [clojure.tools.logging :as log]
            [mycelium.core :as myc]
            [mycelium.cell :as cell]
            [mycelium.compose :as compose]
            [mycelium.workflow :as wf]
            [samizdat.cells :as cells]
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

(defn workflow-prompt
  "A manifest may declare `:prompt <name>`, naming a prompt resource
  (resources/prompts/<name>.md) that is appended to the base system prompt for
  that workflow — how a workflow injects its own instructions at the start. A
  review manifest points at review guidance; the default loop declares none and
  runs the base prompt. Returns the text, or nil."
  [definition]
  (when-let [p (:prompt definition)]
    (some-> (io/resource (str "prompts/" p ".md")) slurp)))

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
        ctx {:conn conn :run-id run-id :config config
             :llm-adapter llm-adapter :llm-config llm-config
             :root root
             ;; A run-start git baseline so a finalization critic can review
             ;; exactly what this run changed. Only captured for a non-default
             ;; manifest — the factory loop has no critic to read it, and
             ;; skipping it keeps the common path off git entirely.
             :git-baseline (when (not= loop-nm loop-name) (gitdiff/baseline root))
             ;; A per-run eval session, so defs the agent makes with `eval`
             ;; persist across its turns (define, then use) — REPL-first
             ;; development against the live image.
             :repl-session (repl/new-session)
             :max-turns max-turns}]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    ;; Which loop drove this run, durably: an agent reading a surprising run
    ;; back needs to know which version of itself produced it.
    (journal/note! conn run-id :loop-workflow
                   {:data {:name loop-nm :version version}})
    (let [data (myc/run-compiled compiled ctx {:branch branch :turn 1})]
      (when (myc/error? data)
        ;; A structural failure mid-run is a harness bug, not a branch
        ;; outcome; surface it rather than shipping a half-closed run.
        (throw (ex-info "loop workflow failed structurally"
                        {:run-id run-id :error (myc/workflow-error data)})))
      (-> (select-keys data [:status :answer :branch :residual])
          (assoc :run-id run-id)))))
