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
            [jolt.fs :as fs]
            [samizdat.lisp :as lisp]))

(def ^:private clojure-exts #{"clj" "cljc" "cljs" "cljd" "edn" "bb"})

(defn- clojure-file? [path]
  (contains? clojure-exts (last (str/split (str path) #"\."))))

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
        ;; Paren repair for Clojure sources: models drop trailing closers, and
        ;; a file that does not read is a file that does not load. A trailing
        ;; truncation or over-close is fixed mechanically and noted; a mid-file
        ;; imbalance is written as-is with the imbalance reported, because
        ;; closing it would silently re-parent code (see samizdat.lisp).
        (let [content (str content)
              {:keys [status content* note]}
              (if (clojure-file? path)
                (let [r (lisp/balance content)]
                  {:status (:status r) :content* (or (:content r) content) :note (:note r)})
                {:status :balanced :content* content})]
          (when-let [parent (fs/parent abs)]
            (fs/create-dirs parent))
          (spit abs content*)
          {:result (str "Wrote " (count content*) " chars to " path "."
                        (when (= :repaired status) (str "\n[harness] " note))
                        (when (= :unbalanced status)
                          (str "\n[harness] Written as given, but the Clojure does not"
                               " balance: " note " It will not load until you fix it.")))
           :category :success :progress? true :branch branch
           :repaired? (= :repaired status)})
        (miss branch (str "Path " path " is outside the project root and cannot be written."))))))
