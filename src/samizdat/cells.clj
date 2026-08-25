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
            [mycelium.cell :as cell]
            ;; Preload the namespaces the shipped cells load-string reach for, so
            ;; they compile the normal way first and the AOT cache stays sound
            ;; (samizdat.cell-prelude explains the -dirty-build failure it fixes).
            [samizdat.cell-prelude]))

(defn resource-dir
  "Resolve the shipped cells dir from the classpath, so a caller that is not
  running from the project root still finds resources/cells."
  []
  (when-let [url (io/resource "cells")]
    (.getPath url)))

(def default-dirs
  "Where cells live, lowest precedence first: the shipped library, then a
  project's own overrides. A later dir's cell of the same id wins (it loads
  last), so a project can replace a shipped cell without touching it.

  The shipped entry resolves through the classpath (review3 #11): a built
  binary started outside the project root must still find the cells it
  ships — a cwd-relative `resources/cells` there resolves to nothing and
  the kernel silently registers zero cells. The override dir stays
  cwd-relative: it belongs to the project being worked on."
  [(or (resource-dir) "resources/cells") ".samizdat/cells"])

;; Which cells this loader registered, and from which file — introspection for
;; the mutation protocol and for `dev`/debugging. {cell-id {:source path}}.
(defonce ^:private loaded-cells (atom {}))

;; The on-disk content of each file at the last SUCCESSFUL load — the known-good
;; snapshot the mutation protocol rolls a bad edit back to. {path content}.
(defonce ^:private loaded-content (atom {}))

(defn loaded [] @loaded-cells)

(defn loaded-file-content
  "The content of every cell file as it was at the last successful load — the
  last-good disk state, for the mutation protocol to restore on a rollback."
  []
  @loaded-content)

(defn- cell-files
  "The .clj files under `dir`, sorted, or nil when the dir is absent."
  [dir]
  (when (fs/exists? dir)
    (->> (fs/glob dir "**.clj")
         (map str)
         (filter #(str/ends-with? % ".clj"))
         sort)))

(def shipped-cells
  "The cell files that ship with the harness, as RESOURCE names.

  Enumerated rather than globbed, and read through io/resource, so the shipped
  cells load from a built binary that has no resources/ on disk (deps.edn
  :jolt/build :embed bakes them in). A classpath has no directory listing and
  an embedded resource has no filesystem path for `.getPath` to name, so the
  glob above finds nothing there and the kernel registered ZERO cells — which
  surfaces as `Cell :loop/assemble not found in registry` the moment a run
  tries to compile its loop. Same reasoning as workflow/factory-manifest-names;
  both are pinned against their directory by a test so the list cannot drift.

  Lowest precedence: a project's .samizdat/cells override still loads after
  these and wins, and in a source checkout the dir scan reads the very same
  files, so a shipped cell edited in place is picked up from disk."
  ["cells/critic.clj" "cells/decompose.clj" "cells/feature.clj"
   "cells/loop.clj" "cells/team.clj"])

(defn- shipped-sources
  "The shipped cells as {:id :content} — read from the classpath (embedded or
  not). Skips any whose dir scan already produced it, so a source checkout
  loads each file once and from disk."
  [covered]
  (for [r shipped-cells
        :let [url (io/resource r)]
        :when (and url (not (contains? covered (last (str/split r #"/")))))]
    {:id r :content (slurp url) :file? false}))

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

(defn- load-source!
  "Load one cell SOURCE into the live image; return the cell ids it defines.

  Takes {:id :content} rather than a path, because a shipped cell may come
  from an embedded resource with no file behind it.

  The load is wrapped in a *ns* binding: a cell file begins with an `(ns …)`
  form, and load-string's evaluation of it switches *ns* and does not restore
  it — leaking the cell namespace into whatever called the loader, and breaking
  a second load. Binding *ns* to itself reverts it on exit, so the loader is
  repeatable (the reload the mutation protocol needs) and leaves the caller's
  namespace untouched."
  [{:keys [content]}]
  (binding [*ns* *ns*]
    (load-string content))
  (defcell-ids content))

(defn load-cells!
  "Load every cell file under the given dirs (default `default-dirs`) into the
  live image, registering their cells. Transactional: on any file error the
  registry is restored to its prior state and the error rethrown, so a bad cell
  never half-loads. Returns the loaded map {cell-id {:source path}}."
  ([] (load-cells! default-dirs))
  ([dirs]
   (let [snapshot (cell/registry-snapshot)
         files (mapcat cell-files dirs)
         ;; Shipped resources FIRST and only where the dir scan did not
         ;; already produce that file, so a source checkout loads each cell
         ;; once from disk and a built binary with no resources/ dir still
         ;; gets the loop's cells (deps.edn :jolt/build :embed).
         sources (concat (shipped-sources
                          (set (map #(last (str/split (str %) #"/")) files)))
                         (for [p files] {:id p :content (slurp p) :file? true}))]
     (try
       (let [loaded (reduce (fn [acc src]
                              (into acc (for [id (load-source! src)]
                                          [id {:source (:id src)}])))
                            {}
                            sources)]
         ;; Drop any cell we loaded before that is gone from the new set (a
         ;; deleted cell file / removed defcell), WITHOUT clearing the shared
         ;; registry — other code and tests hold cells here that are not ours.
         (doseq [id (remove (set (keys loaded)) (keys @loaded-cells))]
           (cell/remove-cell! id))
         (reset! loaded-cells loaded)
         ;; Remember the good on-disk content, so the mutation protocol can
         ;; roll a later bad edit back to exactly this. Only reached on
         ;; success, so it never records a half-loaded state.
         ;; Only the sources backed by a real file: the mutation protocol
         ;; restores by spitting content back to the path, and an embedded
         ;; resource has no path to spit to (nor any way for the agent to
         ;; have edited it).
         (reset! loaded-content
                 (into {} (for [src sources :when (:file? src)]
                            [(:id src) (:content src)])))
         loaded)
          (catch Throwable e
            (cell/registry-restore! snapshot)
            (throw (ex-info (str "cell load failed; registry rolled back: "
                                 (ex-message e))
                          {:dirs dirs} e)))))))
