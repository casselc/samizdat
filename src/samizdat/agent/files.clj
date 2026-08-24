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
   or nil when it escapes the root, is blank, or is invalid. Uses
   canonicalization to detect symlink escapes: any symlink chain that
   lands outside root is caught because the canonical target falls
   outside the boundary. Fails closed on any I/O error."
  [root path]
  (when (and (string? path) (not (str/blank? path)))
    (try
      (let [root* (str (fs/canonicalize root))
            target (str (fs/canonicalize (fs/path root path)))]
        (when (or (= target root*) (str/starts-with? target (str root* "/")))
          target))
      (catch Exception _ nil))))

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

;; --- sandbox substrate: strict UTF-8, bounded reads, digests, listing, search ---
;;
;; The primitives the JS1 sandbox's five projected semantic operations are
;; normalized against: the frozen bb4t A2/A3b project contract.  Everything
;; here fails closed — bounded reads stop AT the bound instead of consuming
;; whole files first, strict UTF-8 decoding rejects rather than replaces,
;; digests propagate errors instead of returning nil, and no walk ever
;; follows a symbolic link.

(defn utf8-bytes
  "The UTF-8 encoding of `s` as a byte array."
  [^String s]
  (.getBytes s "UTF-8"))

(defn utf8-byte-count
  "The UTF-8 byte length of `s`."
  [^String s]
  (alength (utf8-bytes s)))

(defn valid-utf8?
  "Strict structural UTF-8 validation of `bs`: rejects truncated sequences,
  bad continuations, overlong forms, surrogates and code points beyond
  U+10FFFF — the same inputs a Java CharsetDecoder with REPORT would
  reject."
  [^bytes bs]
  (let [n (alength bs)
        continuation? (fn [b] (<= 0x80 b 0xbf))]
    (loop [i 0]
      (if (>= i n)
        true
        (let [b (bit-and (aget bs i) 0xff)]
          (cond
            (< b 0x80) (recur (inc i))

            (<= 0xc2 b 0xdf)
            (and (< (inc i) n)
                 (continuation? (bit-and (aget bs (inc i)) 0xff))
                 (recur (+ i 2)))

            ;; Three-byte forms: e0 and ed constrain the second byte
            ;; (overlong forms, surrogates); the tail is plain continuation.
            (= b 0xe0)
            (and (< (+ i 2) n)
                 (<= 0xa0 (bit-and (aget bs (inc i)) 0xff) 0xbf)
                 (continuation? (bit-and (aget bs (+ i 2)) 0xff))
                 (recur (+ i 3)))

            (or (<= 0xe1 b 0xec) (<= 0xee b 0xef))
            (and (< (+ i 2) n)
                 (continuation? (bit-and (aget bs (inc i)) 0xff))
                 (continuation? (bit-and (aget bs (+ i 2)) 0xff))
                 (recur (+ i 3)))

            (= b 0xed)
            (and (< (+ i 2) n)
                 (<= 0x80 (bit-and (aget bs (inc i)) 0xff) 0x9f)
                 (continuation? (bit-and (aget bs (+ i 2)) 0xff))
                 (recur (+ i 3)))

            ;; Four-byte forms: f0 and f4 constrain the second byte
            ;; (overlong forms, beyond U+10FFFF).
            (= b 0xf0)
            (and (< (+ i 3) n)
                 (<= 0x90 (bit-and (aget bs (inc i)) 0xff) 0xbf)
                 (continuation? (bit-and (aget bs (+ i 2)) 0xff))
                 (continuation? (bit-and (aget bs (+ i 3)) 0xff))
                 (recur (+ i 4)))

            (<= 0xf1 b 0xf3)
            (and (< (+ i 3) n)
                 (continuation? (bit-and (aget bs (inc i)) 0xff))
                 (continuation? (bit-and (aget bs (+ i 2)) 0xff))
                 (continuation? (bit-and (aget bs (+ i 3)) 0xff))
                 (recur (+ i 4)))

            (= b 0xf4)
            (and (< (+ i 3) n)
                 (<= 0x80 (bit-and (aget bs (inc i)) 0xff) 0x8f)
                 (continuation? (bit-and (aget bs (+ i 2)) 0xff))
                 (continuation? (bit-and (aget bs (+ i 3)) 0xff))
                 (recur (+ i 4)))

            ;; 0xc0, 0xc1 (overlong two-byte), 0xf5..0xff: never valid.
            :else false))))))

(defn decode-utf8
  "Strict UTF-8 decode of `bs`.  Malformed input fails
   {:samizdat.files/error :invalid-utf8} — never silently replaced — so a
   binary file is an error (read) or a skip (search), not mojibake."
  [^bytes bs]
  (when-not (valid-utf8? bs)
    (throw (ex-info "Content is not valid UTF-8"
                    {:samizdat.files/error :invalid-utf8})))
  (String. bs "UTF-8"))

(defn decode-utf8-or-nil
  "Strict decode, or nil for anything that is not valid UTF-8 — how a binary
   file is recognized during search without guessing from its name."
  [^bytes bs]
  (try (decode-utf8 bs) (catch Exception _ nil)))

(defn read-bounded-bytes
  "Read at most `max-bytes` bytes from `path`.  Consumption stops at the
   bound: content larger than the limit fails
   {:samizdat.files/error :too-large :limit max-bytes} instead of being read
   whole and truncated afterwards.  Any read failure propagates."
  [path max-bytes]
  (let [input (java.io.FileInputStream. (str path))]
    (try
      (let [output (java.io.ByteArrayOutputStream.)
            buffer (byte-array 8192)]
        (loop [total 0]
          (let [remaining (- (inc max-bytes) total)
                read (.read input buffer 0 (min (alength buffer) remaining))]
            (cond
              (neg? read) (.toByteArray output)

              (> (+ total read) max-bytes)
              (throw (ex-info "File content exceeds the byte limit"
                              {:samizdat.files/error :too-large
                               :limit max-bytes}))

              :else
              (do (.write output buffer 0 read)
                  (recur (+ total read)))))))
      (finally
        (try (.close input) (catch Throwable _ nil))))))

;; --- digest ------------------------------------------------------------------

(def ^:private libcrypto-candidates
  "The shared libraries jolt-crypto's foreign functions resolve against
   (mirroring its :jolt/native declaration), tried in order."
  ["libcrypto.so.3" "libcrypto.so.1.1" "libcrypto.so"
   "/opt/homebrew/opt/openssl@3/lib/libcrypto.dylib"
   "/usr/lib/libcrypto.dylib" "libcrypto.dylib"])

(defn- hex-encode
  "Lowercase hex of `bs`, two digits per byte."
  [^bytes bs]
  (apply str (map #(format "%02x" %) bs)))

(defn- compute-digest
  "One digest attempt: getInstance (whose miss auto-loads jolt.crypto's
   host-class shim when that namespace is on the roots) then the one-shot
   digest over `bs`."
  [^bytes bs]
  (hex-encode (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)))

(defn bytes-digest
  "SHA-256 hex digest (64 lowercase hex chars) of `bs`.  Fail-closed, with a
   one-time bootstrap for processes that did not get jolt-crypto's natives
   through dependency resolution (an -Scp run loads none): the first failure
   opens libcrypto, loads jolt.crypto — installing the MessageDigest shim
   and binding its foreign functions to the opened handle — and retries
   once.  Any remaining failure propagates: a digest is a content
   coordinate, and an uncomputable coordinate must not become a fake one."
  [^bytes bs]
  (try
    (compute-digest bs)
    (catch Throwable failure
      (try
        (doseq [lib libcrypto-candidates]
          (try (jolt.ffi/load-native lib) (catch Throwable _ nil)))
        (require 'jolt.crypto)
        (compute-digest bs)
        (catch Throwable _
          (throw failure))))))

(defn file-digest
  "SHA-256 hex digest of a file's bytes, reading at most `max-bytes` through
   the bounded reader.  Fail-closed: over the bound, unreadable, or without
   digest machinery it THROWS — never nil — so stat/edit cannot hand out a
   coordinate that was never computed."
  [path max-bytes]
  (bytes-digest (read-bounded-bytes path max-bytes)))

;; --- one-level listing -------------------------------------------------------

(defn nofollow-size
  "The lstat byte size of `path` (a symbolic link reports the link, not its
   target).  Throws when the attributes cannot be read."
  [path]
  (fs/get-attribute path "basic:size" {:nofollow-links true}))

(defn entry-kind
  "The NOFOLLOW kind of `path`: :symlink, :directory, :file or :other.  A
   link is reported as a link and never followed, inside or outside any
   root.  Existence is the caller's question — this mirrors bb4t's
   entry-kind, which is only asked about entries known to exist."
  [path]
  (cond
    (fs/sym-link? path) :symlink
    (fs/directory? path {:nofollow-links true}) :directory
    (fs/regular-file? path {:nofollow-links true}) :file
    :else :other))

(defn list-one-level
  "The IMMEDIATE entries of the directory at `abs-dir` — exactly one level,
   never recursing — as inert structured maps sorted by name:
   {:name :kind} with :bytes added for regular files, :kind one of :file,
   :directory, :symlink, :other.  Attributes are read NOFOLLOW so a symbolic
   link is a link, not whatever it names.  More than `max-entries` entries
   fails {:samizdat.files/error :too-many-entries} rather than truncating:
   a bounded listing is a bound, not a sample."
  [abs-dir max-entries]
  (let [entries (mapv (fn [p]
                        (let [kind (entry-kind p)]
                          (cond-> {:name (str (fs/file-name p)) :kind kind}
                            (= :file kind) (assoc :bytes (nofollow-size p)))))
                      (fs/list-dir abs-dir))]
    (when (> (count entries) max-entries)
      (throw (ex-info "Project directory exceeds the entry limit"
                      {:samizdat.files/error :too-many-entries
                       :limit max-entries})))
    (vec (sort-by :name entries))))

;; --- atomic anchored write ---------------------------------------------------

(defn atomic-write-file!
  "Write `content-bytes` at `abs-path` through a sibling temporary and an
   atomic rename, so a reader never observes a partially written file and a
   failed write leaves any original intact.  `replace-existing?` selects
   rename-onto-target (an anchored update) against fail-if-present (a
   creation whose leaf must be the only thing absent).  The temporary is
   removed on any failure.  Creates no directories — the parent must exist:
   the frozen A2 edit contract never materializes a hierarchy."
  [abs-path ^bytes content-bytes replace-existing?]
  (let [parent (fs/parent abs-path)
        temp (str parent "/.samizdat-edit-" (random-uuid))]
    (try
      (fs/create-file temp)
      (let [out (java.io.FileOutputStream. temp)]
        (try
          (.write out content-bytes)
          (finally (try (.close out) (catch Throwable _ nil)))))
      (fs/move temp abs-path {:replace-existing replace-existing?
                              :atomic-move true})
      (catch Throwable failure
        (try (fs/delete-if-exists temp) (catch Throwable _ nil))
        (throw failure)))))

;; --- bounded search ----------------------------------------------------------

(def ^:const search-match-budget
  "Character budget one line may cost the regex engine before the whole
   search fails — the superlinear-backtracking bound bb4t enforces with a
   counting CharSequence.  Implemented here as a line-length gate: a line
   longer than the budget necessarily costs at least its length in matcher
   reads on a full scan, so refusing it up front holds the same bound."
  200000)

(defn search-tree
  "Bounded regex search under the directory at `abs-dir`, with match paths
   prefixed by `prefix`.  Returns a vector of {:path :line :text} maps
   ordered by path (depth-first walk, entries sorted at every level).
   Bounds, exactly the frozen A2 semantics:

     :max-results    collection STOPS when reached;
     :max-file-bytes a file larger than this is SKIPPED — its size is
                     checked BEFORE any byte is consumed, so a huge file is
                     never slurped and discarded;
     :max-files      more regular files than this FAILS the search;
     :include-hidden? dot-entries are skipped unless true;
     :max-chars      total decoded characters the scan may consume (a JS1
                     narrowing beyond the frozen contract; only ever stops
                     the walk earlier).

   Symbolic links are never followed; files that are not valid UTF-8 are
   skipped; matched line text is trimmed and capped at :max-line-chars.
   `re` must already be compiled."
  [abs-dir prefix re {:keys [max-results max-file-bytes max-files
                             include-hidden? max-chars max-line-chars]}]
  (let [state (atom {:results [] :files 0 :chars 0})
        budget-file!
        (fn [file]
          "Consume `file`'s size against the total budget BEFORE reading;
           nil means the budget is exhausted for this file (skip)."
          (let [size (nofollow-size file)]
            (when (<= (+ (:chars @state) size) max-chars)
              (swap! state update :chars + size)
              size)))
        search-file!
        (fn [file rel]
          (when-let [size (budget-file! file)]
            (when (<= size max-file-bytes)
              (when-let [content (decode-utf8-or-nil
                                   (read-bounded-bytes file max-file-bytes))]
                (loop [lines (str/split content #"\n" -1)
                       number 1]
                  (when (and (seq lines)
                             (< (count (:results @state)) max-results))
                    (let [line (first lines)]
                      (when (> (count line) search-match-budget)
                        (throw (ex-info
                                "Project search pattern exceeded its matching budget"
                                {:samizdat.files/error :match-budget
                                 :budget search-match-budget})))
                      (when (re-find re line)
                        (swap! state update :results conj
                               {:path rel
                                :line number
                                :text (let [trimmed (str/trim line)]
                                        (if (> (count trimmed) max-line-chars)
                                          (str (subs trimmed 0 max-line-chars) "...")
                                          trimmed))}))
                      (recur (rest lines) (inc number)))))))))
        search-directory!
        (fn search-directory! [dir prefix']
          (doseq [entry (sort-by (fn [p] (str (fs/file-name p)))
                                 (fs/list-dir dir))
                  :while (< (count (:results @state)) max-results)]
            (let [name (str (fs/file-name entry))
                  rel (if (str/blank? prefix')
                        name (str prefix' "/" name))]
              (when (or include-hidden? (not (str/starts-with? name ".")))
                (case (entry-kind entry)
                  ;; Never followed, exactly as in listing, so a search
                  ;; cannot be steered out of the authorized root.
                  :symlink nil
                  :directory (search-directory! entry rel)
                  :file (do
                          (when (>= (:files @state) max-files)
                            (throw (ex-info
                                    "Project search exceeded its file limit"
                                    {:samizdat.files/error :too-many-files
                                     :limit max-files})))
                          (swap! state update :files inc)
                          (search-file! entry rel))
                  nil)))))]
    (search-directory! abs-dir prefix)
    (vec (:results @state))))
