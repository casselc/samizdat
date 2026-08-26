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
;;
;; ---------------------------------------------------------------------------
;; The design is ported from llm-repl's `manual` (us.whitford.llm-repl.core),
;; MIT licensed, (c) 2026 Michael Whitford. Full notice in src/samizdat/tape.clj.
;; ---------------------------------------------------------------------------

(ns samizdat.manual
  "The operator manual: the harness's own command surface, compiled from data.

  The agent develops at the REPL inside this image, so the functions it can
  usefully call ARE part of its tool surface — but nothing told it they exist.
  `doc` and `complete` answer questions about a name you already have; they do
  not tell you which names are worth having.

  TWO AUDIENCES, TWO TEXTS, ONE SEAM. llm-repl's version of this compiles from
  `^{:manual \"sentence\"}` var metadata, so the docstring stays
  maintainer-dense while the operator gets a curated sentence. That split is
  right and this keeps it — but the curation lives in `resources/manual.edn`
  rather than in metadata, because metadata is src and src is the half of this
  system the supervisor cannot rewrite at runtime. Which capabilities the agent
  is told about is exactly the kind of thing it should be able to change about
  itself.

  So: the DATA is in resources, the mechanism is here, and every surface that
  wants the manual (the tool, a prompt block, a future MCP facade) renders this
  one compile.

  FAILS LOUD on an entry whose var does not resolve. A manual that silently
  drops what it cannot find reads as 'there is nothing here', which is worse
  than no manual at all — the same reasoning gates.edn and the cell loader
  apply to their own data."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.prompt :as prompt]
            [samizdat.userspace :as userspace]))

(def resource-name "manual.edn")

(defn- read-manual
  "The curated groups for the current project, through the userspace seam.

  Which capabilities the agent is told it has is the most userspace thing in
  the harness: a project that built itself a capability should be able to add
  it to its OWN manual, and a project that never uses one should be able to
  drop it. The shipped manual.edn is the template that seeds a project."
  []
  (userspace/edn-body! :policy "manual"))

(defn- resolve-entry
  "Resolve one entry's var, requiring its namespace if it is not loaded yet.

  Throws naming the entry when it cannot. A curated list is a promise that
  these exist; a broken promise is a bug in the list, and the list is editable
  at runtime, so the failure has to say which line to fix."
  [{:keys [name] :as entry}]
  (when-not (symbol? name)
    (throw (ex-info "manual entry has no :name symbol" {:entry entry})))
  (let [ns-sym (some-> (namespace name) symbol)]
    (when-not ns-sym
      (throw (ex-info "manual entry :name must be namespace-qualified"
                      {:entry entry})))
    (when-not (find-ns ns-sym)
      (try (require ns-sym)
           (catch Throwable e
             (throw (ex-info (str "manual entry " name
                                  ": its namespace could not be loaded")
                             {:entry entry} e)))))
    (or (ns-resolve ns-sym (symbol (clojure.core/name name)))
        (throw (ex-info (str "manual entry " name " does not resolve — the"
                             " manual promises a capability that is not there")
                        {:entry entry})))))

(defn entries
  "The manual AS DATA: a flat vector of
  `{:group :name :summary :arglists :doc}`.

  `:summary` is the curated operator sentence from the resource; `:doc` is the
  var's own docstring, for whoever wants the dense version. Compiled fresh on
  every call, so an edit to manual.edn takes effect immediately — that is the
  point of it being data."
  []
  (vec (for [{:keys [group] :as g} (read-manual)
             entry (:entries g)]
         (let [v (resolve-entry entry)
               m (meta v)]
           {:group group
            :name (:name entry)
            :summary (:summary entry)
            :arglists (:arglists m)
            :doc (:doc m)}))))

(defn groups
  "The manual grouped as the resource declares, preserving its order — which is
  editorial: the first group is what an operator should read first."
  []
  (->> (entries)
       (partition-by :group)
       (mapv (fn [es] {:group (:group (first es)) :entries (vec es)}))))

(defn render
  "The manual as text: one block per group, one line per entry with its
  arglists and its curated summary.

  RETURNS a string rather than printing it, so a caller that is rendering into
  a prompt, a tool result or a terminal all get the same bytes."
  []
  (str/join "\n\n"
            (for [{:keys [group entries]} (groups)]
              (str/trimr
               (prompt/render
                "manual-group"
                {:group group
                 :entries (str/join "\n"
                                    (for [{:keys [name arglists summary]} entries]
                                      (str "  " name " " (pr-str arglists) "\n"
                                           "      " summary)))})))))

(defn find-entry
  "The entry for `sym` (a symbol or string), or nil — for a caller that wants
  one capability's full detail rather than the whole surface."
  [sym]
  (let [s (str sym)]
    (first (filter #(= s (str (:name %))) (entries)))))
