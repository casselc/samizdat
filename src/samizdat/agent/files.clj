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
  (:require [samizdat.agent.gates :as gates]
            [clojure.string :as str]
            [jolt.fs :as fs]
            [samizdat.lisp :as lisp]
            [samizdat.prompt :as prompt]
            [samizdat.store.journal :as journal]))

(def ^:private clojure-exts #{"clj" "cljc" "cljs" "cljd" "edn" "bb"})

(defn- msg
  "One of the file tools' branch-facing messages, from prompts/file-tool.md.

  Every one of these was a `str` here. They are the whole of what the model
  learns when an edit does not apply — which text did not match, that
  whitespace was tolerated, that the result no longer balances — and a
  project working in a language the paren-balance note means nothing for
  should be able to reword or drop them without a rebuild. One template keyed
  by reason rather than a file per sentence, because they are one surface."
  [ctx]
  (prompt/render "file-tool" ctx))

(defn- clojure-file? [path]
  (contains? clojure-exts (last (str/split (str path) #"\."))))

(defn- max-read-chars
  "How much of a file one `read` returns: the SMALLER of :file-read-chars and
  :tool-result-chars, both gates.edn :context-budget.

  The minimum, not :file-read-chars alone, because every tool result passes
  through the loop's own clip on its way to the model. :file-read-chars was
  60000 and :tool-result-chars 4000, so this tool's budget never bound
  anything — the outer clip did, and it lands AFTER the page marker is
  written. Paging against a budget that is not the effective one produces a
  `continue from line N` the model never sees, which is the dead end it was
  meant to fix, one layer further in."
  []
  (let [{:keys [file-read-chars tool-result-chars]} (gates/threshold :context-budget)]
    ;; No fallback numbers: repeating gates.edn's values here is how the table
    ;; stops being the one place they live. A budget missing from the table is
    ;; a broken table, and throwing says so.
    (apply min (keep identity [file-read-chars tool-result-chars]))))

(defn- grep-ranges
  "How many distinct line ranges a search reports before it says '… and N
  more', from gates.edn :context-budget."
  []
  (:grep-ranges (gates/threshold :context-budget)))

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

(defn- page
  "The lines of `content` from `offset` (0-based) that fit in the char budget,
  as {:text :from :next :total}. `next` is the line to ask for to continue, or
  nil at the end.

  Lines rather than characters because a line number is something the model
  can hold and act on; a character offset into a file it has only seen part of
  is not. A single line longer than the whole budget is emitted anyway rather
  than dropped — a page that can return nothing would never advance."
  [content offset budget limit]
  (let [lines (str/split-lines content)
        total (count lines)
        from (max 0 (min offset total))]
    (loop [i from, taken [], used 0]
      (if (or (>= i total)
              (and limit (>= (count taken) limit))
              (and (seq taken) (> (+ used (count (nth lines i)) 1) budget)))
        {:text (str/join "\n" taken) :from from :total total
         :next (when (< i total) i)}
        (recur (inc i) (conj taken (nth lines i))
               (+ used (count (nth lines i)) 1))))))

(defn read-file
  "Return the contents of a file under the root, a page at a time. :neutral —
  reading establishes nothing. A missing file or an escaping path is
  :mechanics (a call made wrong), never :failure.

  `offset` is a 0-based LINE to start from and `limit` a maximum number of
  lines; both are optional and the char budget still bounds the result.

  IT PAGES BECAUSE WITHOUT PAGING IT LOOPED. The result was clipped at the
  budget and marked `… [truncated]`, which names a problem and no way out of
  it. Live, against a 7KB brief: the model read the same file six times
  through `read_file`, `cat`, `wc && sed` and `sed`, got the identical first
  4014 characters every time, and then spent four more turns writing a chunked
  reader in `eval` — ten turns of a forty-turn budget to read one file it had
  been told to start from. A truncation marker has to end with the call that
  continues it, or it is a dead end the model can only walk into again."
  [{:keys [branch root args]}]
  (let [path (str (:path args))
        offset (or (some-> (:offset args) str parse-long) 0)
        limit (some-> (:limit args) str parse-long)]
    (cond
      (str/blank? path)
      (miss branch (msg {:needs-path true :tool "read_file"}))

      :else
      (if-let [abs (resolve-under-root (or root ".") path)]
        (if (fs/exists? abs)
          (let [content (slurp abs)
                {:keys [text from next total]}
                (page content offset (max-read-chars) limit)]
            {:result (str path
                          (when (pos? from) (str " (from line " from ")"))
                          ":\n" text
                          (when next
                            (str "\n" (msg {:more true :path path :next next
                                            :shown next :total total}))))
             :category :neutral :progress? false :branch branch})
          (miss branch (msg {:no-file true :path path})))
        (miss branch (msg {:outside-root true :path path :verb "read"}))))))

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
      (str/blank? path) (miss branch (msg {:needs-path true :tool "edit_file"}))
      (str/blank? old-text) (miss branch (msg {:needs-old-text true}))
      :else
      (if-let [abs (resolve-under-root (or root ".") path)]
        (if-not (fs/exists? abs)
          (miss branch (msg {:no-file true :path path}))
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
              (miss branch (msg {:not-found true :path path}))

              (and (> (count ranges) 1) (not replace-all?))
              (miss branch
                    (msg {:ambiguous true :path path :count (count ranges)
                          :lines (str/join "\n"
                                           (for [[s _] (take (grep-ranges) ranges)]
                                             (str "  Line " (line-of starts s))))
                          :more (when (> (count ranges) (grep-ranges))
                                  (- (count ranges) (grep-ranges)))}))

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
                                     (or note (msg {:unbalanced-generic true})))))]
                (spit abs edited)
                {:result (let [n (if replace-all? (count ranges) 1)]
                           (msg {:edited true :path path :replacements n
                                 :plural (when (> n 1) "s")
                                 :fallback fallback :unbalanced unbalanced}))
                 :category :success :progress? true :branch branch
                 :fallback fallback}))))
        (miss branch (msg {:outside-root true :path path :verb "edited"}))))))

(defn stale-note
  "A line telling this branch that a sibling changed `path` after it last read
  it, or nil.

  A NOTICE, NOT A REFUSAL. Team workers share one working tree on purpose —
  the parts of a feature belong in the same files — so two branches in one
  file is the design working, not a fault to block. What the harness can say
  is what it knows: somebody else has been in here since you looked. Deciding
  which version wins is exactly the judgement it does not have.

  It matters most on `write_file`, which replaces a whole file: a worker
  writing from its own picture of what belongs there silently drops whatever a
  sibling added. Live, three branches wrote src/kit/core.clj fifteen times
  between them, twice on the same turn, and nothing said a word.

  Best effort: a failure to look must never fail the write that succeeded."
  [{:keys [conn run-id branch args]}]
  (try
    (when (and conn run-id (:id branch))
      (when-let [{:keys [branch tool]}
                 (journal/changed-since-read conn run-id (:id branch) (str (:path args)))]
        ;; No turn number in the notice. Branches on one run advance their own
        ;; turn counters independently, so a worker on turn 19 was told a peer
        ;; had acted "on turn 25" — accurate, and it reads like the future.
        ;; "since you last read it" is the ordering that matters and the only
        ;; one both branches share.
        (prompt/render "stale-write" {:branch branch :path (str (:path args))
                                      :tool tool})))
    (catch Throwable _ nil)))

(defn with-stale
  "Append the sibling notice to a successful file result."
  [result ctx]
  (if-let [n (and (= :success (:category result)) (stale-note ctx))]
    (update result :result str "\n\n" n)
    result))

(defn write-file
  "Write `content` to a file under the root, creating parent directories.
  :success with :progress? — changing the tree is real work. An escaping path
  is refused and writes nothing."
  [{:keys [branch root args]}]
  (let [path (str (:path args))
        content (:content args)]
    (cond
      (str/blank? path)
      (miss branch (msg {:needs-path true :tool "write_file"}))

      (nil? content)
      (miss branch (msg {:needs-content true}))

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
          {:result (msg {:wrote true :path path :chars (count content*)
                         :repaired (= :repaired status)
                         :note note
                         :unbalanced (when (= :unbalanced status) note)})
           :category :success :progress? true :branch branch
           :repaired? (= :repaired status)})
        (miss branch (msg {:outside-root true :path path :verb "written"}))))))
