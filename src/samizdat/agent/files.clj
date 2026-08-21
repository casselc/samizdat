;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.agent.files
  "read_file / write_file: the agent's view of, and edits to, the project tree.

  Both are confined to a root (the project directory). A path is resolved
  against the root and canonicalized, and anything landing outside is refused —
  a self-modifying agent operates on its own repo, not the filesystem. Reads
  are bounded; writes create parent directories and overwrite in place.

  Ordinary edit tools, deliberately not shell redirection: an agent that has to
  write files through `cat > f <<EOF` fights the tool surface, and the point of
  the dogfood is to see it change code, not wrestle heredocs."
  (:require [clojure.string :as str]
            [jolt.fs :as fs]))

(def ^:private max-read-chars 60000)

(defn- resolve-under-root
  "Canonicalize `path` under `root`; return the resolved absolute path string,
  or nil when it escapes the root. The canonical root is the boundary — a
  `..` or an absolute path that lands outside it fails closed."
  [root path]
  (let [root* (str (fs/canonicalize root))
        target (str (fs/canonicalize (fs/path root path)))]
    (when (or (= target root*) (str/starts-with? target (str root* "/")))
      target)))

(defn- miss [branch msg]
  {:result msg :category :mechanics :progress? false :branch branch})

(defn read-file
  "Return the contents of a file under the root. :neutral — reading establishes
  nothing. A missing file or an escaping path is :mechanics (a call made
  wrong), never :failure."
  [{:keys [branch root args]}]
  (let [path (str (:path args))]
    (cond
      (str/blank? path)
      (miss branch "read_file needs a `path`.")

      :else
      (if-let [abs (resolve-under-root (or root ".") path)]
        (if (fs/exists? abs)
          (let [content (slurp abs)
                shown (subs content 0 (min (count content) max-read-chars))]
            {:result (str path ":\n" shown
                          (when (> (count content) max-read-chars)
                            "\n… [truncated]"))
             :category :neutral :progress? false :branch branch})
          (miss branch (str "No file " path " under the project root.")))
        (miss branch (str "Path " path " is outside the project root and cannot be read."))))))

(defn write-file
  "Write `content` to a file under the root, creating parent directories.
  :success with :progress? — changing the tree is real work. An escaping path
  is refused and writes nothing."
  [{:keys [branch root args]}]
  (let [path (str (:path args))
        content (:content args)]
    (cond
      (str/blank? path)
      (miss branch "write_file needs a `path`.")

      (nil? content)
      (miss branch "write_file needs `content` (an empty string is allowed).")

      :else
      (if-let [abs (resolve-under-root (or root ".") path)]
        (do
          (when-let [parent (fs/parent abs)]
            (fs/create-dirs parent))
          (spit abs (str content))
          {:result (str "Wrote " (count (str content)) " chars to " path ".")
           :category :success :progress? true :branch branch})
        (miss branch (str "Path " path " is outside the project root and cannot be written."))))))
