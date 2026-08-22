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

(defn grep-project
  "Search the project's source files for `pattern` (a regex string); return a
  seq of {:path :line :text} for each matching line, with paths relative to
  `root`. Globs the Clojure source extensions rather than walking the tree:
  glob skips hidden directories, so cache and VCS noise never matches, and the
  brace pattern covers files sitting directly in the root, which a plain
  `**/*.clj` misses. Reading establishes nothing: :neutral."
  [root pattern]
  (let [root* (str (fs/canonicalize (or root ".")))
        re (re-pattern pattern)
        files (mapcat #(fs/glob root* (str "{*." % ",**/*." % "}")) clojure-exts)]
    (mapcat (fn [p]
              (let [rel (str (fs/relativize root* (fs/canonicalize (str p))))]
                (keep-indexed (fn [i line]
                                (when (re-find re line)
                                  {:path rel :line (inc i) :text line}))
                              (str/split (slurp (str p)) #"\n" -1))))
            files)))

;; --- surgical edit ----------------------------------------------------------
;; Ported from dirge's edit tool (src/agent/tools/edit.rs): exact match first,
;; a line-trimmed fallback for the whitespace drift that is ~95% of failed
;; edits, ambiguous matches reported rather than guessed, and replace_all
;; splicing every occurrence in reverse so offsets stay valid.

(defn- exact-ranges
  "Byte [start end] ranges of every exact occurrence of `needle` in `s`."
  [s needle]
  (when (seq needle)
    (loop [from 0, out []]
      (let [i (str/index-of s needle from)]
        (if i
          (recur (+ i (count needle)) (conj out [i (+ i (count needle))]))
          out)))))

(defn- line-starts [s]
  (into [0] (keep-indexed (fn [i c] (when (= c \newline) (inc i))) s)))

(defn- line-trimmed-ranges
  "Ranges where a window of lines matches `needle`'s lines after trimming each
  — the fallback for leading/trailing whitespace drift. dirge's
  find_line_trimmed_matches."
  [s needle]
  (let [clines (str/split s #"\n" -1)
        flines (str/split needle #"\n" -1)
        fn* (count flines)
        starts (reductions + 0 (map #(inc (count %)) clines))]
    (when (and (pos? fn*) (<= fn* (count clines)))
      (for [i (range (inc (- (count clines) fn*)))
            :let [block (subvec (vec clines) i (+ i fn*))]
            :when (every? true? (map #(= (str/trim %1) (str/trim %2)) block flines))]
        (let [start (nth starts i)
              end (reduce + start (concat (map count block)
                                          (repeat (dec fn*) 1)))]
          [start end])))))

(defn- line-of [starts pos]
  (inc (count (take-while #(<= % pos) (rest starts)))))

(defn edit-file
  "Replace `old_text` with `new_text` in a file under the root. Exact match
  first; on whitespace drift, a line-trimmed fallback (noted). An old_text
  that matches more than once without :replace_all is reported with line
  numbers rather than guessed. For a Clojure file, an edit that breaks the
  delimiter balance is flagged. :mechanics for a call made wrong (not found,
  ambiguous, escaping path); :success when the edit lands."
  [{:keys [branch root args]}]
  (let [path (str (:path args))
        old-text (str (:old_text args))
        new-text (str (:new_text args))
        replace-all? (boolean (:replace_all args))]
    (cond
      (str/blank? path) (miss branch "edit_file needs a `path`.")
      (str/blank? old-text) (miss branch "edit_file needs `old_text` to find.")
      :else
      (if-let [abs (resolve-under-root (or root ".") path)]
        (if-not (fs/exists? abs)
          (miss branch (str "No file " path " under the project root."))
          (let [content (str/replace (slurp abs) "\r\n" "\n")
                old-text (str/replace old-text "\r\n" "\n")
                new-text (str/replace new-text "\r\n" "\n")
                [ranges fallback] (if-let [ex (seq (exact-ranges content old-text))]
                                    [ex nil]
                                    (when-let [lt (seq (line-trimmed-ranges content old-text))]
                                      [lt "line-trimmed"]))
                starts (line-starts content)]
            (cond
              (empty? ranges)
              (miss branch
                    (str "old_text not found in " path ". The exact text must match,"
                         " including whitespace — tried an exact and a line-trimmed match."))

              (and (> (count ranges) 1) (not replace-all?))
              (miss branch
                    (str "old_text matched " (count ranges) " times in " path ":\n"
                         (str/join "\n"
                                   (for [[s _] (take 20 ranges)]
                                     (str "  Line " (line-of starts s))))
                         (when (> (count ranges) 20)
                           (str "\n  … and " (- (count ranges) 20) " more"))
                         "\n\nAdd surrounding context to old_text to narrow it, or pass"
                         " replace_all: true to change every occurrence."))

              :else
              ;; Splice in reverse so earlier offsets stay valid.
              (let [edited (reduce (fn [s [start end]]
                                     (str (subs s 0 start) new-text (subs s end)))
                                   content
                                   (sort-by first > (if replace-all? ranges [(first ranges)])))
                    ;; Any non-:balanced result means the edit broke the file —
                    ;; a surgical edit must not silently auto-close (that would
                    ;; re-parent code), so it is flagged for the model to fix.
                    unbalanced (when (clojure-file? path)
                                 (let [{:keys [status note]} (lisp/balance edited)]
                                   (when (not= :balanced status)
                                     (or note "the delimiters no longer balance"))))]
                (spit abs edited)
                {:result (str "Edited " path
                              " (" (if replace-all? (count ranges) 1) " replacement"
                              (when (and replace-all? (> (count ranges) 1)) "s") ")."
                              (when fallback (str "\n[harness] matched via " fallback
                                                  " fallback — exact text not found;"
                                                  " whitespace tolerated."))
                              (when unbalanced
                                (str "\n[harness] the edit means the Clojure no longer"
                                     " balances / does not balance: " unbalanced
                                     " It will not load until you fix it.")))
                 :category :success :progress? true :branch branch
                 :fallback fallback}))))
        (miss branch (str "Path " path " is outside the project root and cannot be edited."))))))

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
