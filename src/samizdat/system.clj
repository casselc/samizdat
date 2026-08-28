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

(ns samizdat.system
  "The one place long-lived resources live, so everything else can be a
  function that an editor redefines against a running process.

  The rule this namespace exists to enforce: resources that are expensive to
  recreate go in `system` behind start!/stop!; logic does not. A swipl session,
  a Lean REPL that spends thirty seconds importing Mathlib, the database
  connection, and the HTTP server are resources. Gate definitions, prompts,
  tool methods, and parsers are logic, and reloading their namespace mid-run is
  supposed to work.

  `defonce` so reloading this namespace from a connected editor does not drop
  the handles to a server that is still listening."
  (:require [clojure.tools.logging :as log]
            ;; installs the java.time.* host shim tools.logging's timestamp
            ;; formatter resolves against; must load before the first log call
            [jolt.time]
            [jolt.http.platform :as platform]
            [ring-chez.adapter :as adapter]
            [samizdat.api.control :as api-control]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.phases :as phases]
            [samizdat.lexicon :as lexicon]
            [samizdat.config :as config]
            [samizdat.llm.client :as llm-client]
            [samizdat.llm.registry :as registry]
            [samizdat.session :as session]
            [samizdat.lsp.client :as lsp-client]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [samizdat.userspace :as userspace]))

(defonce system (atom nil))

(def ^:private userspace-owner ::served-system)

(defn started? [] (some? @system))

(defn config [] (:config @system))

(defn conn
  "The single writer connection. See store.db for why there is only one."
  []
  (:conn @system))

(defn adapter
  "The provider adapter for the configured provider."
  []
  (registry/adapter-for (get-in @system [:config :llm :provider])))

(defn bind-project!
  "Point userspace at this project's store, THEN reload every policy table.

  One seam, because the order is the whole point (karamazov-blt.1): the
  reloads used to run at the top of start!, ~35 lines before `bind!`, so all
  three caches were filled from the SHIPPED templates and nothing re-read
  them after the project bound — a project whose gates, wordlists or phases
  had diverged silently ran factory policy for the whole process lifetime.
  Reloading after the bind is what makes the caches hold the project's own
  policy; the reload-on-every-start half (rather than trusting an atom that
  survives stop!/start!) is what lets a long-lived interpreted session pick
  up edits without a process restart."
  ([conn] (bind-project! nil conn))
  ([owner conn]
   (if owner
     (do
       (userspace/claim! owner conn)
       (try
         (gates/reload-config!)
         (lexicon/reload!)
         (phases/reload!)
         conn
         (catch Throwable e
           (userspace/release! owner)
           (throw e))))
     (do
       (userspace/bind! conn)
       (gates/reload-config!)
       (lexicon/reload!)
       (phases/reload!)
       conn))))

(defn prepare-config!
  "Load runtime configuration and initialize its process-wide provider state.

  Shared by the served and embedded lifecycles so provider probing, response
  bounds, and session reset cannot drift between entry points. Does not open a
  database or start a server."
  [overrides]
  (let [cfg (config/load-config overrides)
        _ (session/reset!)
        _ (platform/set-max-response-ms! (get-in cfg [:llm :max-response-ms]))
        probed (llm-client/probe-llama-cpp (:llm cfg))
        cfg (cond-> cfg probed (update :llm merge probed))]
    (when probed
      (log/info "endpoint identified as llama.cpp:"
                (:total-slots probed) "KV slots — prefix caching on"))
    cfg))

(defn start!
  "Bring the system up. `overrides` is merged into the config, so a REPL
  session can do (start! {:db {:path \":memory:\"} :http {:port 3999}}).

  The handler is passed IN as a var rather than resolved here, because
  samizdat.server requires this namespace and resolving it dynamically would
  invert the dependency. It also has to be static: `jolt build` embeds what it
  can reach statically, and a `requiring-resolve` here left the entire server
  and engine subtree out of the binary, which then failed at startup trying to
  compile namespaces off source roots that do not exist in an image.

  Vars are callable and deref on each call, so redefining
  samizdat.server/handler in a connected editor still takes effect on the
  next request."
  ([handler] (start! handler nil))
  ([handler overrides]
   (when (started?)
     (throw (ex-info "system already started; call stop! first" {})))
   (when (userspace/bound?)
     (throw (ex-info "another Samizdat lifecycle already owns userspace" {})))
   (let [cfg (prepare-config! overrides)
         c (db/open! (get-in cfg [:db :path]))
         bound? (atom false)
         server* (atom nil)]
     (try
       ;; Point the userspace reads at THIS project's store, and reload the
       ;; policy caches AFTER the bind so they hold the project's own
       ;; gates/wordlists/phases (bind-project! carries the ordering argument).
       (bind-project! userspace-owner c)
       (reset! bound? true)
       (let [server (adapter/run-server handler {:port (get-in cfg [:http :port])})]
         (reset! server* server)
         (reset! system {:config cfg :conn c :server server})
         (log/info "samizdat up on port" (get-in cfg [:http :port])
                   "provider" (get-in cfg [:llm :provider])
                   "model" (get-in cfg [:llm :model])
                   "db" (get-in cfg [:db :path]))
         ;; Nothing can be running yet, so any row that says it is, is a leftover
         ;; from a process that died. This is the only moment that inference is
         ;; sound. See store.runs/reconcile-orphans!.
         (let [n (runs/reconcile-orphans! c)]
           (when (pos? n)
             (log/info "marked" n
                       "run(s) interrupted: still flagged running with no process")))
         :started)
       (catch Throwable e
         ;; A failed bind or server startup owns no durable lifecycle. Undo each
         ;; resource that did start so a retry is possible in the same process.
         (when-let [server @server*]
           (try (adapter/stop-server server) (catch Throwable _ nil)))
         (when @bound?
           (try (userspace/release! userspace-owner) (catch Throwable _ nil)))
         (try (db/close c) (catch Throwable _ nil))
         (reset! system nil)
         (throw e))))))

(defn stop!
  "Tear the system down. Best effort per resource: one failing close must not
  strand the others, which is the whole reason the RAX manager could always
  stop the Lisp task regardless of what the agent believed."
  []
  (when-let [s @system]
    (doseq [[label f] [;; Active runs FIRST, before anything they depend on
                       ;; closes under them: set every abort flag and give the
                       ;; run threads a bounded window to reach a boundary and
                       ;; journal their ending. Tearing the db down while run
                       ;; futures kept executing meant their writes — including
                       ;; the crash record — landed on a closed handle, and a
                       ;; restart!'s reconcile-orphans! marked still-executing
                       ;; runs interrupted while their threads kept going
                       ;; (karamazov-blt.14).
                       ["active runs"
                        #(let [runs @api-control/active]
                           (doseq [[_ {:keys [abort]}] runs]
                             (when abort (reset! abort true)))
                           (doseq [[rid {:keys [future]}] runs]
                             (when future
                               (when (= ::hung (deref future 15000 ::hung))
                                 (log/warn "run" rid "did not stop within 15s;"
                                           "closing the system under it")))))]
                       ["http server" #(adapter/stop-server (:server s))]
                       ["lsp clients" #(lsp-client/shutdown-all!)]
                       ;; Unbind BEFORE the connection closes: a userspace read
                       ;; against a closed handle would throw where the same
                       ;; read against no handle simply serves the template.
                       ["userspace" #(userspace/release! userspace-owner)]
                       ["database" #(db/close (:conn s))]]]
      (try (f) (catch Throwable e (log/warn "stopping" label "failed:" (ex-message e)))))
    (reset! system nil)
    :stopped))

(defn restart!
  ([handler] (restart! handler nil))
  ([handler overrides] (stop!) (start! handler overrides)))
