;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

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
            [clojure.tools.logging :as log]
            [mycelium.core :as myc]
            [mycelium.workflow :as wf]
            [samizdat.agent.cells :as cells]
            [samizdat.agent.loop :as branch-loop]
            [samizdat.repl :as repl]
            [samizdat.agent.state :as state]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.store.workflows :as workflows])
  (:refer-clojure :exclude [run!]))

(def loop-name "loop")
(def loop-resource "manifests/loop.edn")

(defn read-definition
  "Parse a workflow definition from EDN text. Dispatch predicates stay as
  forms here; maestro evaluates them at compile time."
  [edn-text]
  (edn/read-string edn-text))

(defn compile-loop
  "Compile a loop definition through mycelium's full static checking:
  structure, dispatch coverage, reachability, and the :constraints that make
  the loop's invariants compile-time errors. Throws on any violation —
  which is the mutation protocol's first line of defense. Logs, and returns
  compiled with, any :mycelium/compile-warnings (undeclared cell effects)."
  [definition]
  ;; The cell registry is global mutable state; re-register before every
  ;; compile so nothing that emptied it (a REPL clear, a test fixture) can
  ;; make the loop compile against a half-empty registry.
  (cells/register!)
  (let [compiled (myc/pre-compile definition)]
    (when-let [warnings (:mycelium/compile-warnings (:compiled-fsm compiled))]
      (log/warn "loop definition compiled with warnings:" (pr-str warnings)))
    compiled))

(defn load-loop!
  "The current loop: seed the factory default on first use, then load and
  compile the latest stored version. Returns {:version :definition :compiled}."
  [conn]
  (let [row (workflows/seed! conn loop-name loop-resource)
        definition (read-definition (:edn row))]
    {:version (:version row)
     :definition definition
     :compiled (compile-loop definition)}))

(defn run!
  "Run one branch to completion under the stored loop definition.
  Returns {:status :answer :branch :run-id (:residual)}."
  [{:keys [conn config llm-adapter llm-config problem max-turns]}]
  (let [max-turns (or max-turns (get-in config [:run :max-turns]) 40)
        {:keys [version compiled]} (load-loop! conn)
        run-id (runs/start-run! conn {:problem problem
                                      :provider (:provider llm-config)
                                      :model (:model llm-config)
                                      :max-turns max-turns
                                      :beam-width 1
                                      :prompt-digest (branch-loop/prompt-digest)})
        branch (state/new-branch {:id "B1" :problem problem
                                  :messages (branch-loop/initial-messages problem)})
        ctx {:conn conn :run-id run-id :config config
             :llm-adapter llm-adapter :llm-config llm-config
             ;; The project root the file tools are confined to, and the shell
             ;; tool runs in. Configurable so a run can target another checkout.
             :root (or (get-in config [:run :root]) (System/getProperty "user.dir"))
             ;; A per-run eval session, so defs the agent makes with `eval`
             ;; persist across its turns (define, then use) — REPL-first
             ;; development against the live image.
             :repl-session (repl/new-session)
             :max-turns max-turns}]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    ;; Which loop drove this run, durably: an agent reading a surprising run
    ;; back needs to know which version of itself produced it.
    (journal/note! conn run-id :loop-workflow
                   {:data {:name loop-name :version version}})
    (let [data (myc/run-compiled compiled ctx {:branch branch :turn 1})]
      (when (myc/error? data)
        ;; A structural failure mid-run is a harness bug, not a branch
        ;; outcome; surface it rather than shipping a half-closed run.
        (throw (ex-info "loop workflow failed structurally"
                        {:run-id run-id :error (myc/workflow-error data)})))
      (-> (select-keys data [:status :answer :branch :residual])
          (assoc :run-id run-id)))))
