;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.cells
  "The cell loader — kernel mechanism, cell-agnostic by design.

  Cells are the harness's plugins: not compiled into src, but loaded at
  runtime from resources/cells (and .samizdat/cells project overrides), each a
  small self-contained Clojure file that calls mycelium's `defcell`. This
  namespace knows how to find them, load them into the live image, register
  them, and reload them — it knows no specific cell. With no cell files, the
  kernel registers no cells and runs no loop; the loop is entirely user space.

  Loading is transactional (autolith's extension-registry pattern): the whole
  cell registry is snapshotted before a load and restored if any file fails, so
  a broken cell edit never leaves the registry half-loaded. That is also the
  reversible-load half of the mutation protocol (karamazov-ioo.11): the agent
  edits a cell, this reloads it, and a bad edit rolls back cleanly.

  Files are load-stringed rather than required, so they are dynamically loaded
  into the running image (dev filesystem or a built binary's resources alike)
  and never AOT-compiled into src."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.fs :as fs]
            [mycelium.cell :as cell]))

(def default-dirs
  "Where cells live, lowest precedence first: the shipped library, then a
  project's own overrides. A later dir's cell of the same id wins (it loads
  last), so a project can replace a shipped cell without touching it."
  ["resources/cells" ".samizdat/cells"])

;; Which cells this loader registered, and from which file — introspection for
;; the mutation protocol and for `dev`/debugging. {cell-id {:source path}}.
(defonce ^:private loaded-cells (atom {}))

(defn loaded [] @loaded-cells)

(defn- cell-files
  "The .clj files under `dir`, sorted, or nil when the dir is absent."
  [dir]
  (when (fs/exists? dir)
    (->> (fs/glob dir "**.clj")
         (map str)
         (filter #(str/ends-with? % ".clj"))
         sort)))

(defn- defcell-ids
  "The cell ids a cell file defines, by reading its `defcell` forms — so a
  reload attributes a cell to its file even though re-registration is not a
  'new' method, and so we never have to clear the shared registry to tell
  which cells are ours."
  [content]
  (->> (read-string (str "[" content "\n]"))
       (tree-seq coll? seq)
       (filter #(and (seq? %) (symbol? (first %))
                     (= "defcell" (name (first %)))))
       (map second)
       (filter keyword?)
       vec))

(defn- load-file!
  "Load one cell file into the live image; return the cell ids it defines.

  The load is wrapped in a *ns* binding: a cell file begins with an `(ns …)`
  form, and load-string's evaluation of it switches *ns* and does not restore
  it — leaking the cell namespace into whatever called the loader, and breaking
  a second load. Binding *ns* to itself reverts it on exit, so the loader is
  repeatable (the reload the mutation protocol needs) and leaves the caller's
  namespace untouched."
  [path]
  (let [content (slurp path)]
    (binding [*ns* *ns*]
      (load-string content))
    (defcell-ids content)))

(defn load-cells!
  "Load every cell file under the given dirs (default `default-dirs`) into the
  live image, registering their cells. Transactional: on any file error the
  registry is restored to its prior state and the error rethrown, so a bad cell
  never half-loads. Returns the loaded map {cell-id {:source path}}."
  ([] (load-cells! default-dirs))
  ([dirs]
   (let [snapshot (cell/registry-snapshot)
         files (mapcat cell-files dirs)]
     (try
       (let [loaded (reduce (fn [acc path]
                              (into acc (for [id (load-file! path)]
                                          [id {:source path}])))
                            {}
                            files)]
         ;; Drop any cell we loaded before that is gone from the new set (a
         ;; deleted cell file / removed defcell), WITHOUT clearing the shared
         ;; registry — other code and tests hold cells here that are not ours.
         (doseq [id (remove (set (keys loaded)) (keys @loaded-cells))]
           (cell/remove-cell! id))
         (reset! loaded-cells loaded)
         loaded)
       (catch Throwable e
         (cell/registry-restore! snapshot)
         (throw (ex-info (str "cell load failed; registry rolled back: "
                              (ex-message e))
                         {:dirs dirs} e)))))))

(defn reload!
  "Reload all cells from disk into the live image — the hot-swap path. Same
  transactional guarantee as load-cells!."
  ([] (load-cells!))
  ([dirs] (load-cells! dirs)))

(defn resource-dir
  "Resolve the shipped cells dir from the classpath, so a caller that is not
  running from the project root still finds resources/cells."
  []
  (when-let [url (io/resource "cells")]
    (.getPath url)))
