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
            [samizdat.agent.gates :as gates]
            [samizdat.agent.phases :as phases]
            [samizdat.agent.wordlists :as wordlists]
            [samizdat.config :as config]
            [samizdat.llm.registry :as registry]
            [samizdat.lsp.client :as lsp-client]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(defonce system (atom nil))

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
   (let [cfg (config/load-config overrides)
          ;; Gate thresholds are cached in an atom that survives stop!/start!,
           ;; so a restart would keep serving the pre-edit gates.edn. Reload on
           ;; every start: long-lived interpreted sessions pick up threshold
           ;; edits without a process restart. Same for the wordlists (tier 1c)
           ;; and the phase machine (drg-4026 #34).
           _ (gates/reload-config!)
           _ (wordlists/reload!)
           _ (phases/reload!)
         ;; Process-wide, and set here rather than in core so that every entry
         ;; point gets it: the tests, the benchmark runner and a REPL session
         ;; all bring the system up through start! without going through -main.
         _ (platform/set-max-response-ms! (get-in cfg [:llm :max-response-ms]))
         c (db/open! (get-in cfg [:db :path]))
         server (adapter/run-server handler {:port (get-in cfg [:http :port])})]
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
         (log/info "marked" n "run(s) interrupted: still flagged running with no process")))
     :started)))

(defn stop!
  "Tear the system down. Best effort per resource: one failing close must not
  strand the others, which is the whole reason the RAX manager could always
  stop the Lisp task regardless of what the agent believed."
  []
  (when-let [s @system]
    (doseq [[label f] [["http server" #(adapter/stop-server (:server s))]
                       ["lsp clients" #(lsp-client/shutdown-all!)]
                       ["database" #(db/close (:conn s))]]]
      (try (f) (catch Throwable e (log/warn "stopping" label "failed:" (ex-message e)))))
    (reset! system nil)
    :stopped))

(defn restart!
  ([handler] (restart! handler nil))
  ([handler overrides] (stop!) (start! handler overrides)))
