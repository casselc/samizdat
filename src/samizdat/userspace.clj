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

(ns samizdat.userspace
  "THE BASE / USERSPACE SEAM.

  The harness is two layers. `src/` is the BASE: how to talk to a provider,
  how to run a tool, how to reach the db, how to render a template, how to
  compile and validate a workflow. Lego pieces — capabilities with no
  opinions. Nothing in the base decides what the harness does.

  USERSPACE is how those pieces snap together into an agentic loop: the cells,
  the manifests that wire them, the policy tables they read, and the prompts
  they speak. It belongs to the PROJECT, not to the harness. `resources/`
  ships a template; a project seeds its own copy on first use and evolves it
  from there, so two projects running the same binary can work differently and
  neither can break the other.

  This namespace is the READ seam. Every loader in the base — the cell loader,
  the manifest loader, the gate and phase tables, the prompt renderer — comes
  through here instead of reaching for `io/resource` directly, and gets:

    the project's current version, if the project has one
    else the shipped template, seeded into the project as version 1

  WHY A BOUND CONNECTION rather than a threaded argument. The readers are
  called from everywhere — a selmer render deep inside a gate message, a cell
  reload from a tool, a threshold lookup inside a compiled predicate — and
  threading a conn through all of it would put the store in the signature of
  every function that reads a number. `bind!` is called once, by
  system/start!, with the project's connection.

  UNBOUND IS A VALID STATE, and it reads the template. A test, a REPL session,
  or a tool that has no run behind it gets exactly the behaviour the harness
  had before this existed. That is what keeps the seam addable without a flag
  day: nothing has to know whether a project store is present."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [samizdat.store.userspace :as store]))

;; --- the bound project -------------------------------------------------------

(defonce ^:private project (atom nil))

;; {[kind name] body} for the bound project.
;;
;; Reads are HOT: a prompt is rendered on every gate message and every turn
;; assembly, and a threshold is read inside compiled predicates. One db query
;; per read would put SQLite in the path of string interpolation. The cache is
;; invalidated wholesale on any write and on any (un)bind — coarse on purpose,
;; because the alternative is reasoning about which read a write could have
;; affected, and a stale cell is the bug that looks like the supervisor's edit
;; silently not taking.
(defonce ^:private cache (atom {}))

(defn invalidate!
  "Drop the read cache. Called on every write; public so a caller that changed
  the store behind this namespace's back can say so."
  []
  (reset! cache {})
  nil)

(defn bind!
  "Point userspace reads at this project's store. Called once by
  system/start! with the project's db connection.

  Returns the previous binding, so a caller that needs to restore it (a test,
  a tool operating on another project) can."
  [conn]
  (let [prev @project]
    (reset! project conn)
    (invalidate!)
    prev))

(defn unbind!
  "Detach from the project store — reads fall back to the shipped template.
  The state a test and a bare REPL run in."
  []
  (reset! project nil)
  (invalidate!)
  nil)

(defn conn
  "The bound project connection, or nil."
  []
  @project)

(defn bound? [] (some? @project))

;; --- the template ------------------------------------------------------------

(def ^:private resource-path
  "Where the shipped template for each kind lives on the classpath. The
  extension is part of the kind, because the kind is what says how to read a
  body: Clojure for a cell, EDN for a manifest or a policy table, markdown for
  a prompt."
  {:cell     (fn [name] (str "cells/" name ".clj"))
   :manifest (fn [name] (str "manifests/" name ".edn"))
   :policy   (fn [name] (str name ".edn"))
   :prompt   (fn [name] (str "prompts/" name ".md"))})

(defn template-path
  "The classpath resource holding the shipped template for `kind`/`name`."
  [kind name]
  (if-let [f (get resource-path kind)]
    (f name)
    (throw (ex-info (str "no template path for userspace kind " (pr-str kind))
                    {:kind kind :name name}))))

(defn template
  "The shipped template body for `kind`/`name`, or nil when nothing ships
  under that name.

  nil is not an error here. A project may hold userspace the harness never
  shipped — a cell the supervisor wrote, a manifest it authored — and those
  have no template by definition."
  [kind name]
  (some-> (io/resource (template-path kind name)) slurp))

;; --- reads -------------------------------------------------------------------

(defn- read-body
  [kind name]
  (if-let [c (conn)]
    (or (:body (store/load-latest c kind name))
        (when-let [t (template kind name)]
          (:body (store/seed! c kind name t)))
        ;; Bound but absent from both: a name the project has never held and
        ;; the harness never shipped.
        nil)
    (template kind name)))

(defn body
  "The body of `kind`/`name` for the current project.

  The project's newest version when it has one; otherwise the shipped
  template, seeded into the project as version 1 on the way past. Unbound,
  the template with no seeding.

  Returns nil when neither the project nor the template has it — the caller
  decides whether that is an error, because it is one for a cell the manifest
  references and not one for an optional prompt.

  Cached per (kind, name) and invalidated on every write; see `cache`."
  [kind name]
  (let [k [kind name]
        hit (get @cache k ::miss)]
    (if (= ::miss hit)
      (let [v (read-body kind name)]
        ;; nil is cached too: an absent name is looked up on every render of a
        ;; prompt block that may not exist, and re-querying for a row that is
        ;; not there is the same cost as one that is.
        (swap! cache assoc k v)
        v)
      hit)))

(defn body!
  "`body`, failing loud when it is absent. For a caller whose whole operation
  is meaningless without it — a manifest node's cell, the system prompt."
  [kind name]
  (or (body kind name)
      (throw (ex-info (str "no userspace " (clojure.core/name kind) " named "
                           (pr-str name) ": the project has no version and"
                           " nothing ships at " (template-path kind name))
                      {:kind kind :name name}))))

(defn edn-body
  "`body` parsed as EDN — a manifest or a policy table. nil stays nil."
  [kind name]
  (some-> (body kind name) edn/read-string))

(defn edn-body!
  [kind name]
  (edn/read-string (body! kind name)))

;; --- writes ------------------------------------------------------------------

(defn save!
  "Append a new version of `kind`/`name` for the current project. Returns the
  new version number, or nil when no project is bound.

  nil rather than a throw on an unbound write: a tool that edits userspace
  outside a run is a real situation (a REPL session, a test), and it should
  hear that nothing was stored rather than crash."
  [kind name new-body]
  (if-let [c (conn)]
    (let [v (store/save! c kind name new-body)]
      (invalidate!)
      (log/info "userspace" (clojure.core/name kind) name "saved as version" v)
      v)
    (do (log/warn "userspace save ignored — no project store is bound:"
                  (clojure.core/name kind) name)
        nil)))

(defn revert!
  "Re-append an older version as the newest — the rollback. Returns the new
  version number, or nil."
  [kind name version]
  (when-let [c (conn)]
    (let [v (store/revert! c kind name version)]
      (invalidate!)
      v)))

(defn versions
  "The edit history of one piece of userspace, oldest first. Empty when
  unbound: the template has no history, which is the point of copying it."
  [kind name]
  (if-let [c (conn)] (store/versions c kind name) []))

(defn names
  "Every name the project holds at `kind`, with its latest version. Empty when
  unbound."
  [kind]
  (if-let [c (conn)] (store/names c kind) []))

(defn seed-all!
  "Seed every named template of `kind` into the project, and return the
  project's bodies for that kind as {name body}.

  `template-names` is enumerated by the caller rather than globbed, for the
  reason every other resource list in this codebase is: a classpath has no
  directory listing and an embedded resource has no filesystem path, so a glob
  finds nothing inside a built binary and the layer silently comes up empty.

  What comes back is the PROJECT's bodies, not the templates: a name the
  project has evolved returns its own version, and a name it has authored that
  no template covers is included too. Seeding and loading in one motion,
  because the only way to be sure a project has its copy is to try."
  [kind template-names]
  (if-let [c (conn)]
    (do (doseq [n template-names]
          (when-let [t (template kind n)]
            (store/seed! c kind n t)))
        (invalidate!)
        (store/latest-bodies c kind))
    ;; Unbound: the template IS the layer.
    (into {}
          (keep (fn [n] (when-let [t (template kind n)] [n t])))
          template-names)))
