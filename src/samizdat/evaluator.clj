;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evaluator
  "Trusted bounded evaluator mechanism for JS1. M1 shipped the read-only
  :agent/project-read profile; M2 adds the :agent/project-develop profile and
  its one semantic mutation.

  This namespace intentionally requires jolt.sandbox and therefore loads only
  in the pinned bounded lane. Ordinary Samizdat reaches it through dynamic
  resolution and does not put SCI on its classpath.

  Read-side root confinement: every read/list/search/stat path is validated
  lexically first (bounded, relative, non-escaping), then walked component by
  component — any symbolic link in ANY intermediate or final component is
  refused, so no walk can be redirected. Bounds are enforced before
  unbounded consumption (bounded byte reads, entry-stream and search-walk
  caps), decoding is strict UTF-8, and project/stat carries a fail-closed
  deterministic sha256 digest. Every evaluation runs under the spec's
  per-evaluation timeout ceiling (default 30s) on a private Jolt interrupt
  token; a caller token (a later TurnLease) may only narrow it.

  The M2 write side, (project/edit path base new-content), reuses the read
  side's lexical validation and component walk unchanged — a symbolic link in
  any component, including the final one, is refused, never followed. The
  parent directory must already exist (edit never creates directories) and
  the target must be a regular file or absent. base is the optimistic-
  concurrency anchor: either the exact digest project/stat returned for the
  current content, or :absent to create. Stale, missing and existing
  conflicts are refused with zero writes, as is the operator's run config —
  through the same files/run-config? seam the ordinary file tools and the
  shell policy use, invoked after confinement and before any existence check,
   their authority ordering. Content is bounded; the write is a temp file in
   the target's own directory atomically published into place, and the return is
  the new content's canonical digest. Intent is recorded before actuation and
  outcome after; a replay consumes the recorded receipt and never re-executes
  the write."
  (:require [clojure.set :as set]
             [clojure.string :as str]
             [jolt.fs :as fs]
             [jolt.sandbox :as sandbox]
             [samizdat.agent.files :as files]
             [samizdat.prompt :as prompt]
             [samizdat.security.no-replace :as no-replace]
             [samizdat.store.evaluator :as store]))

(def jolt-coordinate "4af2362176160f2ed0e366689d7232b1a38adfec")
(def jolt-publish-coordinate
  "jolt-publish/v1:sha256:914ccd9f722efd98fe8e1e1381574a3efba04ae45a689e8c1918d420db82f0c1")
(def sci-coordinate "32d62a5136ad3dc148588752f5bcc4cc30b14752")
(def sci-version "0.13.53")
(def profile-id :agent/project-read)
(def develop-profile-id :agent/project-develop)
(def top-level-tools ["eval" "doc" "complete" "done"])
(def profile-capabilities
  "The :agent/project-read catalog maximum, derived from the sandbox's closed
  profile table — the one source of truth for what a profile may ever hold."
  (:profile/max-capabilities (get sandbox/profiles profile-id)))
(def develop-capabilities
  "The :agent/project-develop catalog maximum: the read profile plus the one
  semantic mutation, from the same table."
  (:profile/max-capabilities (get sandbox/profiles develop-profile-id)))
(def semantic-operation-order
  [:project/read :project/list :project/search :project/stat :project/edit])
(def compiled-capabilities
  "The operation vocabulary this build actually compiles — the code-level
  layer of the authority intersection in context-spec."
  (set semantic-operation-order))
(def default-bounds
  {:max-read-chars 60000
   :max-list-entries 1000
   :max-search-results 500
   :max-search-files 20000
   :max-search-file-chars 500000
   :max-search-pattern-chars 200
   :max-search-line-chars 300
   ;; project/edit content, at the same ceiling as the read side: the edit
   ;; anchor is a digest computed through the bounded reader, so a target a
   ;; binding cannot read in full is one it cannot safely replace either.
   :max-edit-chars 60000})

;; Mechanism bounds — the numbers the evaluator's SAFETY model rests on,
;; gathered as one table so they read as the mechanism they are. They are
;; deliberately NOT under :context/bounds (the tunable surface a controller
;; narrows per binding, defaulted in default-bounds below): each of these is
;; a ceiling the tier under observation must not be able to raise, for the
;; same reason the soak timeout is not userspace — an agent able to stretch
;; its own evaluation ceiling, path budget or matcher budget can hang the
;; process with one call. A project retunes consumption through
;; :context/bounds, never through here.
(def ^:private mechanism-bounds
  {:timeout-ceiling-ms 30000
   :path-chars 4096
   :search-match-budget 200000
   ;; How deep interrupted? walks a cause chain looking for a Jolt
   ;; interruption — a loop bound, not a policy.
   :cause-chain-depth 8
   ;; The watcher's coarsest sleep between deadline checks, in ms.
   :watcher-poll-ms 5})

;; The per-evaluation interrupt ceiling every EvaluatorSpec carries unless the
;; controller attenuates it. A later TurnLease may only narrow it, never
;; stretch it: the guarded evaluation runs on a private per-evaluation Jolt
;; interrupt token (see evaluate-guarded!) that no caller-held token can
;; disarm or outlive.
(def default-timeout-ms (:timeout-ceiling-ms mechanism-bounds))

;; A model-supplied path is one bounded non-empty relative string, and a
;; search pattern at most max-search-pattern-chars, before any filesystem
;; access happens.
(def max-path-chars (:path-chars mechanism-bounds))

;; A line longer than this necessarily costs the regex matcher at least its
;; length in reads on a full scan, so refusing it up front bounds the
;; superlinear-backtracking exposure of any accepted pattern.
(def search-match-budget (:search-match-budget mechanism-bounds))

(def operation-docs
  {:project/read
   {:name "project/read" :arglists [["path"]]
    :doc "Read one UTF-8 file relative to the authorized project root. Consumption stops at the byte/character bound instead of reading whole first; invalid UTF-8 fails rather than being replaced; a symbolic link is refused, not followed, in every path component."}
   :project/list
   {:name "project/list" :arglists [["path"]]
    :doc "List one directory level as sorted {:name :kind :bytes?} data. Entry consumption stops at the bound; symbolic links are refused in every path component and reported as :symlink entries, never followed."}
   :project/search
   {:name "project/search" :arglists [["pattern"] ["pattern" "options"]]
    :doc "Search bounded project text and return {:path :line :text} data. The file bound is enforced during the walk, a file larger than the per-file bound is skipped without reading, files that are not valid UTF-8 are skipped, symbolic links are never followed, and collection stops at the result bound."}
   :project/stat
   {:name "project/stat" :arglists [["path"]]
    :doc "Return a deterministic path, kind, byte-size, and sha256 content digest. The digest is computed through the bounded reader and the operation fails rather than returning a fake coordinate."}
   :project/edit
   {:name "project/edit" :arglists [["path" "base" "new-content"]]
    :doc "Replace or create one regular file under the authorized project root and return its new {:path :kind :bytes :digest} — exactly what project/stat would report, so the digest is the next edit's anchor. base is the exact digest project/stat returned for the current content, or :absent to create a path that must not exist yet. A stale base, a missing anchor target, an existing create target, the operator's run config, a symbolic link in any component, a non-regular-file target, a missing parent, or content over the bound is refused and writes nothing. The write is a temp file in the target's directory: create is an atomic Linux no-replace publication, replacement is an atomic rename."}})

(def tool-docs
  {"eval" {:name "eval" :arglists [["code"]]
           :doc "Evaluate code in this binding's persistent bounded SCI context."}
   "doc" {:name "doc" :arglists [["symbol"]]
          :doc "Describe a callable name from this binding's trusted catalog."}
   "complete" {:name "complete" :arglists [["prefix"]]
               :doc "List callable trusted-catalog names matching prefix."}
   "done" {:name "done" :arglists [[]]
           :doc "Emit a completion request. M1 refuses successful completion because verification is unavailable."}})

(defn runtime-snapshot []
   (sandbox/inert
    {:runtime/jolt-source jolt-coordinate
     :runtime/jolt-publish-source jolt-publish-coordinate
    :runtime/jolt-version (jolt.host/jolt-version)
    :runtime/sci-source sci-coordinate
    :runtime/sci-version sci-version
    :runtime/language (sandbox/language-coordinate)
    :runtime/evaluator-protocol 1
    :runtime/receipt-protocol 1}))

(defn runtime-coordinate []
  (str "js1-rt/v1:" (subs (sandbox/canonical-coordinate (runtime-snapshot)) 4)))

(defn- canonical-root [root]
  (str (fs/canonicalize root)))

(defn- spec-coordinate [spec]
  (str "js1:" (subs (sandbox/canonical-coordinate
                      (dissoc spec :spec/coordinate)) 4)))

(defn- fail!
  ([kind message data] (fail! kind message data nil))
  ([kind message data cause]
   (throw (ex-info message (assoc data :samizdat.evaluator/error kind) cause))))

(defn- message [data]
  (prompt/render "bounded-evaluator" data))

(defn- resolve-timeout
  "The per-evaluation computational ceiling, in milliseconds.

  Defaults to 30 seconds. The controller may only NARROW it: a requested
  value above the default is attenuated down to the default, exactly as a
  requested capability beyond authorization is intersected away, and zero or
  a negative value is refused rather than read as \"no ceiling\". Nothing a
  caller or controller supplies can stretch an evaluation past the default."
  [timeout-ms]
  (let [requested (or timeout-ms default-timeout-ms)]
    (when-not (and (integer? requested) (pos? requested))
      (fail! :invalid-timeout
             "timeout-ms: positive integer milliseconds required"
             {:timeout-ms timeout-ms}))
    (min (long requested) (long default-timeout-ms))))

(defn- profile-maximum
  "The catalog maximum for a profile, from the sandbox's closed profile
  table. An unknown profile fails closed here, before any spec exists."
  [profile]
  (or (get-in sandbox/profiles [profile :profile/max-capabilities])
      (fail! :unsupported-profile
             "Unsupported bounded evaluator profile"
             {:profile profile})))

(defn context-spec
  "Mint the inert effective ContextSpec. Requested authority is intersected
  with controller authorization, the trusted profile maximum, and the compiled
  operation vocabulary — userspace request ∩ controller authorization ∩
  catalog maximum ∩ compiled capability, in that authority order. The timeout
  ceiling is part of the coordinate."
  [root {:keys [profile requested controller-authorized bounds timeout-ms]
         :or {profile profile-id}}]
  (let [maximum (profile-maximum profile)
        requested (or requested maximum)
        authorized (or controller-authorized maximum)
        effective (set/intersection (set requested)
                                    (set authorized)
                                    maximum
                                    compiled-capabilities)
        base {:context/profile profile
              :context/root (canonical-root root)
              :context/capabilities (vec (sort-by str effective))
              :context/bounds (merge default-bounds bounds)
              :context/timeout-ms (resolve-timeout timeout-ms)}]
    (assoc base :context/coordinate (sandbox/canonical-coordinate base))))

(defn evaluator-spec [context]
  (let [base {:samizdat.evaluator/kind :spec
              :context-spec context
              :runtime-coordinate (runtime-coordinate)}]
    (assoc base :spec/coordinate (spec-coordinate base))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Path policy — read-side root confinement. Every intermediate AND final
;; path component is checked: a walk may never be redirected through a
;; symbolic link, so "would this link escape?" never has to be answered.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- lexical-components
  "Validate one model-supplied relative path lexically, before any filesystem
  access, and return its normalized components under the root ([] is the root
  itself). The root itself is admitted only when allow-root? (listing and
  searching it are their primary use; read/stat reject it as not a file)."
  [root rel allow-root?]
  (when-not (and (string? rel) (not (str/blank? rel))
                 (<= (count rel) max-path-chars))
    (fail! :invalid-path "Expected a bounded non-empty relative project path"
           {:path (str rel)}))
  (when (fs/absolute? (fs/path rel))
    (fail! :absolute-path (message {:absolute-path true}) {:path rel}))
  (let [root (str root)
        normalized (str (fs/normalize (fs/path root rel)))]
    (when-not (or (= normalized root) (str/starts-with? normalized (str root "/")))
      (fail! :path-escape (message {:path-escape true}) {:path rel}))
    (when (and (= normalized root) (not allow-root?))
      (fail! :not-file (message {:root-not-file true}) {:path rel}))
    (if (= normalized root)
      []
      (vec (remove str/blank?
                   (str/split (subs normalized (inc (count root))) #"/"))))))

(defn- require-directory-component!
  "One intermediate walk component must exist, be a directory, and NOT be a
  symbolic link — even a link that stays inside the root is refused."
  [dir component]
  (let [child (str dir "/" component)]
    (cond
      (not (fs/exists? child {:nofollow-links true}))
      (fail! :not-found (message {:path-missing true}) {:component component})

      (fs/sym-link? child)
      (fail! :symlink (message {:symlink-path true}) {:component component})

      (not (fs/directory? child {:nofollow-links true}))
      (fail! :not-found (message {:path-missing true}) {:component component}))))

(defn- descend
  "Walk components under the root, refusing every symbolic link and every
  missing/non-directory intermediate. Returns the absolute directory path the
  walk lands in — the root itself when components is empty."
  [root components]
  (loop [dir root
         [component & more] components]
    (if component
      (do (require-directory-component! dir component)
          (recur (str dir "/" component) more))
      dir)))

(defn- classify
  "The NOFOLLOW kind of a leaf path: a link is reported as a link and never
  followed, inside or outside any root."
  [path]
  (cond
    (not (fs/exists? path {:nofollow-links true})) :absent
    (fs/sym-link? path) :symlink
    (fs/directory? path {:nofollow-links true}) :directory
    (fs/regular-file? path {:nofollow-links true}) :file
    :else :other))

(defn- target-of
  "Resolve a leaf path to [parent-abs kind]: the walk descends to the parent
  with the frozen rules and classifies the leaf NOFOLLOW."
  [root components]
  (let [parent (descend root (butlast components))
        abs (str parent "/" (last components))]
    [abs (classify abs)]))

(defn- relative-name [components]
  (str/join "/" components))

;; ═══════════════════════════════════════════════════════════════════════════
;; Bounded read substrate — bounds are enforced BEFORE unbounded consumption,
;; decoding is strict UTF-8 (never replacement), and digests fail closed.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- read-byte-ceiling
  "The byte bound a bounded read stops at, derived from a character bound: a
  character occupies at most four UTF-8 bytes, so this is the largest byte
  consumption the read can ever need. No read consumes past it regardless of
  what the file contains."
  [max-chars]
  (* 4 max-chars))

(defn- read-bounded-bytes
  "Read at most max-bytes bytes from path, STOPPING AT THE BOUND: content
  larger than the limit fails :too-large instead of being read whole and
  checked afterwards."
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
              (fail! :too-large (message {:read-large true})
                     {:limit max-bytes})

              :else
              (do (.write output buffer 0 read)
                  (recur (+ total read)))))))
      (finally
        (try (.close input) (catch Throwable _ nil))))))

(def ^:private utf8-lead-classes
  "The RFC 3629 lead-byte grammar as data: one row per lead-byte class,
  [lead-lo lead-hi continuation-ranges], where continuation-ranges holds the
  inclusive [lo hi] byte range each continuation byte of the class must lie
  in (an empty range vector is the ASCII single-byte class).

  The constants of the UTF-8 encoding itself — the same bytes on every
  machine, fixed by the standard — gathered as one grammar table rather than
  scattered through comparisons. Overlong forms (no 0xc0/0xc1 class),
  surrogates (0xed's second byte stops at 0x9f) and code points beyond
  U+10FFFF (0xf4's second byte stops at 0x8f) are excluded by the ranges
  themselves, exactly as the specification defines them."
  [[0x00 0x7f []]
   [0xc2 0xdf [[0x80 0xbf]]]
   [0xe0 0xe0 [[0xa0 0xbf] [0x80 0xbf]]]
   [0xe1 0xec [[0x80 0xbf] [0x80 0xbf]]]
   [0xed 0xed [[0x80 0x9f] [0x80 0xbf]]]
   [0xee 0xef [[0x80 0xbf] [0x80 0xbf]]]
   [0xf0 0xf0 [[0x90 0xbf] [0x80 0xbf] [0x80 0xbf]]]
   [0xf1 0xf3 [[0x80 0xbf] [0x80 0xbf] [0x80 0xbf]]]
   [0xf4 0xf4 [[0x80 0x8f] [0x80 0xbf] [0x80 0xbf]]]])

(defn- valid-utf8?
  "Strict structural UTF-8 validation: rejects truncated sequences, bad
  continuations, overlong forms, surrogates, and code points beyond
  U+10FFFF — the inputs a Java CharsetDecoder with REPORT would reject.
  A lead byte with no class in utf8-lead-classes is never valid."
  [^bytes bs]
  (let [n (alength bs)]
    (letfn [(class-ranges
              [byte]
              (some (fn [[lead-lo lead-hi ranges]]
                      (when (<= lead-lo byte lead-hi) ranges))
                    utf8-lead-classes))
            (valid-from
              [i]
              (if (>= i n)
                true
                (if-let [ranges (class-ranges (bit-and (aget bs i) 0xff))]
                  (let [end (+ i (count ranges))]
                    (and (< end n)
                         (every? (fn [[k [lo hi]]]
                                   (<= lo (bit-and (aget bs (+ i k 1)) 0xff) hi))
                                 (map-indexed vector ranges))
                         (valid-from (inc end))))
                  false)))]
      (valid-from 0))))

(defn- decode-utf8
  "Strict UTF-8 decode: malformed input fails :invalid-utf8 — never silently
  replaced — so a binary file is an error, not mojibake."
  [^bytes bs]
  (when-not (valid-utf8? bs)
    (fail! :invalid-utf8 (message {:read-not-utf8 true}) {}))
  (String. bs "UTF-8"))

(defn- decode-utf8-or-nil [^bytes bs]
  (try (decode-utf8 bs) (catch Throwable _ nil)))

(def ^:private libcrypto-candidates
  "The shared libraries the digest bootstrap tries when the process got no
  MessageDigest provider through dependency resolution."
  ["libcrypto.so.3" "libcrypto.so.1.1" "libcrypto.so"
   "/opt/homebrew/opt/openssl@3/lib/libcrypto.dylib"
   "/usr/lib/libcrypto.dylib" "libcrypto.dylib"])

(defn- hex-encode [^bytes bs]
  (apply str (map #(format "%02x" %) bs)))

(defn- compute-digest [^bytes bs]
  (hex-encode (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)))

(defn- bytes-digest
  "SHA-256 hex digest of bs. Fail-closed, with a one-time bootstrap for
  processes whose dependency resolution loaded no digest natives: any
  remaining failure propagates — a digest is a content coordinate, and an
  uncomputable coordinate must not become a fake one."
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

(defn- file-digest
  "sha256:… digest of a file's bytes read through the bounded reader. Fails
  closed — over the bound, unreadable, or without digest machinery — never
  nil, and never a fake coordinate in place of an uncomputable one."
  [path max-bytes]
  (try
    (str "sha256:" (bytes-digest (read-bounded-bytes path max-bytes)))
    (catch Throwable e
      (if (:samizdat.evaluator/error (ex-data e))
        (throw e)
        (fail! :stat-digest (message {:stat-digest-failed true})
               {:path (str path)} e)))))

(defn- nofollow-size
  "The lstat byte size (a symbolic link reports the link, not its target)."
  [path]
  (fs/get-attribute path "basic:size" {:nofollow-links true}))

(defn- read-text
  "One bounded strict-UTF-8 file read. Bytes are consumed only up to the
  derived byte ceiling, decoding is strict, and the character bound fails
  rather than truncates."
  [abs max-chars]
  (let [content (decode-utf8 (read-bounded-bytes abs (read-byte-ceiling max-chars)))]
    (when (> (count content) max-chars)
      (fail! :too-large (message {:read-large true}) {:limit max-chars}))
    content))

(defn- list-one-level
  "The immediate entries of a directory, consumed at most max-entries + 1 —
  the bound is enforced DURING consumption, so an unbounded directory is
  never materialized whole. Entries are inert {:name :kind :bytes?} maps
  sorted by name, attributes read NOFOLLOW."
  [dir max-entries]
  (with-open [stream (java.nio.file.Files/newDirectoryStream (fs/path dir))]
    (let [overflow? (atom false)
          entries (reduce (fn [acc entry]
                            (let [acc' (conj acc entry)]
                              (if (> (count acc') max-entries)
                                (do (reset! overflow? true) (reduced acc'))
                                acc')))
                          [] stream)]
      (when @overflow?
        (fail! :too-many-entries (message {:list-many true})
               {:limit max-entries}))
      (mapv (fn [entry]
              (let [kind (classify entry)]
                (cond-> {:name (str (fs/file-name entry)) :kind kind}
                  (= :file kind) (assoc :bytes (nofollow-size entry)))))
            (sort-by (fn [entry] (str (fs/file-name entry))) entries)))))

(defn- search-tree
  "Bounded regex search under dir (already confinement-checked), with match
  paths relative to the root prefix. Deterministic depth-first walk with
  entries sorted at every level; symbolic links never followed; the file
  bound fails DURING the walk; a file over the per-file byte bound is
  skipped without reading; non-UTF-8 files are skipped; collection stops at
  max-results."
  [root-prefix dir re {:keys [max-results max-file-bytes max-files
                              max-line-chars]}]
  (let [results (atom [])
      files (atom 0)
       match-line! (fn [rel line number]
                     (when (> (count line) search-match-budget)
                       (fail! :match-budget
                              "Search match budget exceeded"
                              {:budget search-match-budget}))
                    (when (re-find re line)
                      (swap! results conj
                             {:path rel :line number
                              :text (let [trimmed (str/trim line)]
                                      (if (> (count trimmed) max-line-chars)
                                        (str (subs trimmed 0 max-line-chars) "...")
                                        trimmed))})))
      search-file! (fn [abs rel]
                     (let [size (nofollow-size abs)]
                       (when (<= size max-file-bytes)
                         (when-let [content (decode-utf8-or-nil
                                             (read-bounded-bytes abs max-file-bytes))]
                           (loop [lines (str/split content #"\n" -1) number 1]
                             (when (and (seq lines)
                                        (< (count @results) max-results))
                               (match-line! rel (first lines) number)
                               (recur (rest lines) (inc number))))))))
      walk! (fn walk! [d prefix]
              (doseq [entry (sort-by (fn [p] (str (fs/file-name p)))
                                     (fs/list-dir d))
                      :while (< (count @results) max-results)]
                (let [name (str (fs/file-name entry))
                      rel (if (str/blank? prefix) name (str prefix "/" name))]
                  (case (classify entry)
                    ;; Never followed, exactly as in listing.
                    :symlink nil
                    :directory (walk! entry rel)
                    :file (do
                            (when (>= @files max-files)
                              (fail! :too-many-files (message {:search-many true})
                                     {:limit max-files}))
                            (swap! files inc)
                            (search-file! (str entry) rel))
                    nil))))]
    (walk! dir root-prefix)
    @results))

;; ═══════════════════════════════════════════════════════════════════════════
;; Bounded write substrate (M2) — the one semantic mutation, behind the exact
;; stat anchor. Confined, lexical-first and symlink-refusing exactly like the
;; read side; the parent directory must already exist; the target is a regular
;; file or absent. The write is a bounded temp file in the target's own
;; directory, atomically published into place — a reader never observes a
;; half-written file, and a refused or failed edit leaves the tree
;; byte-identical.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private edit-base-pattern
  "The exact anchor shape: the digest project/stat returns."
  #"sha256:[0-9a-f]{64}")

(def ^:dynamic ^:private *before-create-publish*
  "Test-only deterministic race seam. It runs only after the caller-owned temp
  is completely written and before Jolt's no-replace publication. Production
  leaves it nil; it is not part of the evaluator or sandbox surface."
  nil)

(defn- validate-edit-arguments!
  "Lexical argument validation, before any filesystem access: new-content is
  a bounded string and base is either :absent (the create rule) or an exact
  stat digest."
  [base new-content bounds]
  (when-not (string? new-content)
    (fail! :invalid-arguments (message {:edit-args true})
           {:content-type (str (type new-content))}))
  (when (> (count new-content) (:max-edit-chars bounds))
    (fail! :too-large (message {:edit-large true})
           {:limit (:max-edit-chars bounds)}))
  (when-not (or (= :absent base)
                (and (string? base) (re-matches edit-base-pattern base)))
    (fail! :invalid-arguments (message {:edit-args true})
           {:base (pr-str base)})))

(defn- replace-atomically!
  "Write content-bytes to a temp file in the target's own directory and publish
  it to abs.

   A create uses Jolt's Linux no-replace primitive: a target that appeared since
   the :absent check returns :exists and surfaces as the :existing conflict —
   the no-clobber create is atomic, not check-then-rename. A replace is a single
  File.renameTo, which on the pinned runtime is one rename(2) — the atomic
  replace. Files/move with replace-existing is deliberately NOT used for it:
  on this host that code path deletes the destination before renaming, which
  is exactly the gap an atomic replace exists to close.

  perms are applied to the temp before the move, best effort: the file mode
  is not the safety invariant (content and atomicity are), and a host without
  posix permission support must not fail the write that succeeded — the same
  reasoning as files/stale-note."
  [parent abs create? perms ^bytes content-bytes rel]
  (let [tmp (java.nio.file.Files/createTempFile
             (fs/path parent) ".samizdat-edit-" ".tmp"
             (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (java.nio.file.Files/write tmp content-bytes
                                 (into-array java.nio.file.OpenOption []))
      (when perms
        (try (fs/set-posix-file-permissions tmp perms)
             (catch Throwable _ nil)))
       (if create?
         (do
           ;; tmp is owned by this function through its finally; Jolt borrows
           ;; its and abs's path values synchronously and never retains them.
           ;; A test may pause exactly here, after both contenders observed
           ;; :absent and wrote temps, to prove the native publication—not the
           ;; earlier check—decides the winner.
           (when *before-create-publish* (*before-create-publish* tmp abs))
           (case (no-replace/publish-create! tmp abs)
             :published nil
              :exists (fail! :existing (message {:edit-existing true}) {:path rel})
              :unsupported (fail! :edit-create
                                  (message {:edit-create-unsupported true})
                                  {:path rel :status :unsupported})
              :error (fail! :edit-create
                            (message {:edit-create-failed true})
                            {:path rel :status :error})))
        (when-not (try (.renameTo (java.io.File. (str tmp)) (java.io.File. abs))
                       (catch Throwable _ false))
          (fail! :edit-replace (message {:edit-replace true}) {:path rel})))
      (finally
        ;; A spent temp is already moved away; a failed one is litter. Removal
        ;; is best effort and never masks the operation's own outcome.
        (try (fs/delete-if-exists tmp) (catch Throwable _ nil))))))

(defn- edit-project-file
  "The (project/edit path base new-content) semantics, run inside the
  operation's intent/outcome recording: every refusal here throws BEFORE any
  write, so a refused edit leaves the tree byte-identical."
  [root bounds rel base new-content]
  (let [components (lexical-components root rel false)
        rel (relative-name components)]
    ;; The operator's run config is refused through the SAME seam the ordinary
    ;; file tools use (files/run-config?), after confinement and before any
    ;; existence check or write — the authority ordering edit_file applies.
    (when (files/run-config? root (str root "/" rel))
      (fail! :protected-path (message {:edit-protected true}) {:path rel}))
    (validate-edit-arguments! base new-content bounds)
    (let [parent (descend root (butlast components))
          abs (str parent "/" (last components))
          kind (classify abs)]
      (when (= :symlink kind)
        (fail! :symlink (message {:symlink-path true}) {:path rel}))
      (when-not (contains? #{:file :absent} kind)
        (fail! :not-file (message {:edit-not-file true}) {:path rel :kind kind}))
      (if (= :absent base)
        (when-not (= :absent kind)
          (fail! :existing (message {:edit-existing true}) {:path rel}))
        (do (when-not (= :file kind)
              (fail! :missing (message {:edit-missing true}) {:path rel}))
            ;; The anchor is the digest of the FULL current content through
            ;; the bounded reader — over the bound it fails closed, exactly
            ;; like project/stat, never an anchor over truncated bytes.
            (let [current (file-digest abs (read-byte-ceiling
                                          (:max-read-chars bounds)))]
              (when-not (= base current)
                (fail! :stale (message {:edit-stale true})
                       {:path rel :current-digest current})))))
      (let [content-bytes (.getBytes ^String new-content "UTF-8")
            perms (if (= :file kind)
                    (try (fs/posix-file-permissions abs)
                         (catch Throwable _ nil))
                    "rw-r--r--")]
        (replace-atomically! parent abs (= :absent kind) perms content-bytes rel)
        ;; The canonical return is exactly what project/stat reports for the
        ;; file now — the next edit's anchor — computed over the bytes
        ;; written, so it can never be a fake coordinate.
        {:path rel :kind :file :bytes (alength content-bytes)
         :digest (str "sha256:" (bytes-digest content-bytes))}))))

(defn- operation-builders [context world-observer hook]
  (let [root (:context/root context)
        bounds (:context/bounds context)
        observe (fn [op args f]
                  (let [run (fn []
                              (when world-observer (world-observer op args))
                              (f))]
                    (if-let [h @hook] (h op args run) (run))))
        read-op
        {:id :project/read :name 'read :effect :observation
         :fn (fn [rel]
               (observe
                :project/read [rel]
                #(let [components (lexical-components root rel false)]
                   (when (empty? components)
                     (fail! :not-file (message {:read-not-file true}) {:path rel}))
                   (let [[abs kind] (target-of root components)]
                     (when-not (= :file kind)
                       (fail! :not-file (message {:read-not-file true})
                              {:path rel :kind kind}))
                     (read-text abs (:max-read-chars bounds))))))}
        list-op
        {:id :project/list :name 'list :effect :observation
         :fn (fn [rel]
               (observe
                :project/list [rel]
                #(let [components (lexical-components root rel true)
                       dir (descend root components)]
                   (case (classify dir)
                     :symlink (fail! :symlink (message {:symlink-path true})
                                     {:path rel})
                     :absent (fail! :not-found (message {:path-missing true})
                                    {:path rel})
                     :directory (list-one-level dir (:max-list-entries bounds))
                     (fail! :not-directory (message {:list-not-dir true})
                            {:path rel})))))}
        search-op
        {:id :project/search :name 'search :effect :observation
         :fn (fn [& args]
             (let [[pattern options] args]
               (when-not (and (<= 1 (count args) 2)
                              (string? pattern) (not (str/blank? pattern))
                              (<= (count pattern) (:max-search-pattern-chars bounds))
                              (or (nil? options) (map? options)))
                 (fail! :invalid-arguments (message {:search-args true})
                        {:args args}))
               (observe
                :project/search (vec args)
                #(let [rel-path (or (:path options) ".")]
                    (when-not (and (string? rel-path) (not (str/blank? rel-path))
                                   (<= (count rel-path) max-path-chars))
                      (fail! :invalid-path
                             "Expected a bounded relative search path"
                             {:path rel-path}))
                   (let [components (lexical-components root rel-path true)
                         dir (descend root components)
                         re (try (re-pattern pattern)
                                 (catch Throwable _
                                   (fail! :invalid-regex "Invalid search regex"
                                          {:pattern pattern})))]
                     (case (classify dir)
                       :symlink (fail! :symlink (message {:symlink-path true})
                                       {:path rel-path})
                       :absent (fail! :not-found (message {:path-missing true})
                                      {:path rel-path})
                       :directory
                       (->> (search-tree (relative-name components) dir re
                                         {:max-results (:max-search-results bounds)
                                          :max-file-bytes (:max-search-file-chars bounds)
                                          :max-files (:max-search-files bounds)
                                          :max-line-chars (:max-search-line-chars bounds)})
                            (take (:max-search-results bounds))
                            vec)
                       (fail! :not-directory (message {:search-not-dir true})
                              {:path rel-path})))))))}
        stat-op
        {:id :project/stat :name 'stat :effect :observation
         :fn (fn [rel]
               (observe
                :project/stat [rel]
                #(let [components (lexical-components root rel false)]
                   (when (empty? components)
                     (fail! :not-file (message {:root-not-file true}) {:path rel}))
                   (let [[abs kind] (target-of root components)
                         rel (relative-name components)]
                      (case kind
                        :absent {:path rel :kind :absent}
                        :file {:path rel :kind :file
                               :bytes (nofollow-size abs)
                               :digest (file-digest abs
                                                    (read-byte-ceiling
                                                     (:max-read-chars bounds)))}
                        {:path rel :kind kind})))))}
        edit-op
        {:id :project/edit :name 'edit :effect :actuation
         :fn (fn [rel base new-content]
               (observe
                :project/edit [rel base new-content]
                #(edit-project-file root bounds rel base new-content)))}]
    [read-op list-op search-op stat-op edit-op]))

(defn- make-instance [spec observer]
  (let [hook (atom nil)
        ops (operation-builders (:context-spec spec) (:world-observer observer) hook)
        capabilities (set (get-in spec [:context-spec :context/capabilities]))
        state (sandbox/create-context
               {:operations ops
                :profile (get-in spec [:context-spec :context/profile])
                :requested-capabilities capabilities
                :authorized-capabilities capabilities})]
    {:samizdat.evaluator/kind :instance
     :instance/id (:instance-id observer)
     :context-id (str (random-uuid))
     :state state :hook hook :operations ops}))

(defn- catalog [binding]
  (let [effective (set (get-in binding [:spec :context-spec :context/capabilities]))
        ops (mapv #(str "project/" (name %))
                  (filter effective semantic-operation-order))]
    (vec (concat top-level-tools ops))))

(defn trusted-orientation [binding]
  ;; The edit guidance is keyed on the CAPABILITY, not the profile name: a
  ;; develop binding the controller narrowed down to read-only gets the read
  ;; guidance, which is the authority it actually holds.
  (let [develop? (contains? (set (get-in binding [:spec :context-spec
                                                  :context/capabilities]))
                            :project/edit)]
    (str "SYSTEM / TRUSTED SURFACE\n"
         "Callable top-level tools:\n"
         (str/join "\n" (map #(str "- " %) top-level-tools))
         "\nSemantic operations available only inside eval:\n"
         (str/join "\n" (map #(str "- " %)
                             (drop (count top-level-tools) (catalog binding))))
         "\n\n" (message {:orientation-guidance true
                          :orientation-develop develop?}))))

(defn orientation-digest
  "The content coordinate of trusted-orientation bytes.  The durable binding
  persists bytes and digest together, so a resume can restore the exact bytes
  and verify them without consulting the (mutable) prompt resource that first
  rendered them."
  [^String orientation]
  (str "sha256:" (bytes-digest (.getBytes orientation "UTF-8"))))

(declare verify-binding!)

(defn- binding-value
  "One binding value.  Fresh mints render the trusted orientation from the
  prompt resources; `persisted-orientation` (a resume) installs the exact bytes
  restored from the durable binding instead — never a re-render."
  ([work-id spec observer instance]
   (binding-value work-id spec observer instance nil))
  ([work-id spec observer instance persisted-orientation]
   (let [binding {:samizdat.evaluator/kind :binding
                  :binding/id (str "bind:" work-id)
                  :work-id (str work-id)
                  :instance/id (:instance-id observer)
                  :spec spec
                  :instance (atom instance)
                  :owner (atom nil)
                  :poisoned (atom false)
                  :world-observer (:world-observer observer)}
         orientation (or persisted-orientation (trusted-orientation binding))]
     (assoc binding
            :trusted-orientation orientation
            :orientation-digest (orientation-digest orientation)))))

(defn bind!
  "Create one controller-minted EvaluatorBinding. The profile comes from the
  controller via opts (:agent/project-read by default); requested and
  authorized authority are intersected against it in context-spec."
  [root work-id opts]
  (let [context (context-spec root opts)
        spec (evaluator-spec context)
        instance-id (str "inst:" work-id)
        observer {:instance-id instance-id :world-observer (:world-observer opts)}
        instance (make-instance spec observer)]
    (binding-value work-id spec observer instance)))

(defn persist-binding!
  "Persist a minted binding as the run's exact reconstruction authority.
  Must be called before the first model turn; idempotent only for an identical
  binding/run pair.  The exact trusted-orientation bytes and their digest are
  part of the record: a later resume restores THESE bytes and checks the
  digest rather than re-rendering a prompt that may have drifted."
  [conn run-id binding]
  (verify-binding! binding)
  (store/register-binding!
   conn {:binding-id (:binding/id binding)
         :run-id (str run-id)
         :work-id (:work-id binding)
         :instance-id (:instance/id binding)
         :spec-id (get-in binding [:spec :spec/coordinate])
         :context-spec (get-in binding [:spec :context-spec])
         :runtime (get-in binding [:spec :runtime-coordinate])
         :orientation (:trusted-orientation binding)
         :orientation-digest (:orientation-digest binding)})
  binding)

(defn describe [binding]
  (let [instance @(:instance binding)]
    {:evaluator/spec-id (get-in binding [:spec :spec/coordinate])
     :evaluator/instance-id (:instance/id binding)
     :evaluator/binding-id (:binding/id binding)
     :evaluator/context-spec (get-in binding [:spec :context-spec :context/coordinate])
     :evaluator/runtime (get-in binding [:spec :runtime-coordinate])
     :evaluator/timeout-ms (get-in binding [:spec :context-spec :context/timeout-ms])
      :evaluator/live-context (:context-id instance)
     :evaluator/capabilities (get-in binding [:spec :context-spec :context/capabilities])}))

(defn- verify-binding! [binding]
  (when-not (and (= :binding (:samizdat.evaluator/kind binding))
                 (= (get-in binding [:spec :spec/coordinate])
                    (spec-coordinate (dissoc (:spec binding) :spec/coordinate))))
    (fail! :invalid-binding "Invalid evaluator binding" {}))
  binding)

(defn doc [binding symbol]
  (let [s (str/trim (str symbol))]
    (when (some #{s} (catalog binding))
      (or (get tool-docs s) (get operation-docs (keyword s))))))

(defn complete [binding prefix]
  (let [p (str prefix)]
    (vec (filter #(str/starts-with? % p) (catalog binding)))))

(defn- result-record [value]
  (try {:value (sandbox/inert value)}
       (catch Throwable _
         {:rendered (binding [*print-length* 100 *print-level* 20]
                      (pr-str value))})))

(defn- result-matches? [record value]
  (= (:result record) (result-record value)))

(defn- identity-map [binding]
  {:spec-id (get-in binding [:spec :spec/coordinate])
   :instance-id (:instance/id binding)
   :binding-id (:binding/id binding)
   :context-spec (get-in binding [:spec :context-spec :context/coordinate])
   :runtime (get-in binding [:spec :runtime-coordinate])})

(defn- receipt->jolt [receipt]
  (cond-> {:op/id (:op receipt) :op/args (:args receipt)}
    (= :done (:phase receipt)) (assoc :op/result (:result receipt))
    (= :error (:phase receipt)) (assoc :op/error (:error receipt))))

(defn- validate-history! [binding rows]
  (when-not (= (mapv :binding_seq rows) (vec (range (count rows))))
    (fail! :malformed-history (message {:history-gap true})
           {:binding-seqs (mapv :binding_seq rows)}))
  (let [{:keys [spec-id instance-id binding-id context-spec runtime]}
        (identity-map binding)
        expected {:spec_id spec-id :instance_id instance-id :binding_id binding-id
                  :context_spec context-spec :runtime runtime}]
    (doseq [row rows]
      (when (= :pending (:status row))
        (fail! :pending-history "Pending evaluator history refuses reconstruction"
               {:eval-id (:id row)}))
      (when-not (= expected (select-keys row (keys expected)))
        (fail! :history-mismatch "Evaluator history identity mismatch"
               {:eval-id (:id row) :expected expected})))))

(defn- interrupted?
  "Whether a Jolt interruption (or a chain of causes carrying one) stopped
  the evaluation."
  [e]
  (loop [e e n 0]
    (and e (< n (:cause-chain-depth mechanism-bounds))
         (or (:jolt/interrupted (ex-data e))
             (recur (ex-cause e) (inc n))))))

(defn- evaluate-guarded!
  "Run one sandbox evaluation under the spec's per-evaluation timeout ceiling.

  The guarded evaluation ALWAYS runs on a PRIVATE per-evaluation Jolt
  interrupt token when a ceiling is in effect, interrupted by exactly one of:

    - the ceiling timer, at :context/timeout-ms from the evaluation's start —
      reported as {:samizdat.evaluator/error :timeout}; or
    - the relay of a caller-supplied token (a later TurnLease's) — a caller
      revocation, propagated as the raw Jolt interrupt and never relabeled
      :timeout.

  A caller-held token can therefore only NARROW the ceiling: nothing a caller
  holds can stretch an evaluation past the spec's timeout, and the spec's
  timer never fires the caller's shared token (a wake landing after the
  guarded extent would otherwise poison every later same-turn evaluation)."
  [state source timeout-ms caller-token]
  (let [timeout-ms (long (or timeout-ms default-timeout-ms))]
    (when-not (pos? timeout-ms)
      (fail! :invalid-timeout "timeout-ms: positive integer milliseconds required"
             {:timeout-ms timeout-ms}))
    (let [tok (jolt.host/make-interrupt)
          ;; nil | :ceiling | :caller — the first interrupter owns the label.
           cause (atom nil)
           done (atom false)
           deadline (+ (System/currentTimeMillis) (long timeout-ms))
           watcher (fn []
                     (loop []
                       (let [ms-left (- deadline (System/currentTimeMillis))]
                         (cond
                           @done nil
                           (and caller-token (jolt.host/interrupted? caller-token))
                           (do (compare-and-set! cause nil :caller)
                               (jolt.host/interrupt! tok))
                           (<= ms-left 0)
                           (do (compare-and-set! cause nil :ceiling)
                               (jolt.host/interrupt! tok))
                            :else
                            (do (Thread/sleep
                                 (min (:watcher-poll-ms mechanism-bounds)
                                      (max 1 ms-left)))
                                (recur))))))]
        (doto (Thread. watcher) (.setDaemon true) (.start))
        (try
          (sandbox/evaluate! state source tok)
          (catch Throwable e
            (if (and (interrupted? e) (= :ceiling @cause))
              (fail! :timeout (message {:eval-timeout true})
                     {:timeout-ms timeout-ms})
              (throw e)))
          (finally
            (reset! done true))))))

(defn- binding-timeout [binding]
  (get-in binding [:spec :context-spec :context/timeout-ms]))

(defn- rebuild-internal! [conn binding]
  (let [rows (store/history conn (:binding/id binding))]
    ;; Validate every durable coordinate before allocating or interpreting SCI.
    (validate-history! binding rows)
    (let [fresh (make-instance (:spec binding)
                               {:instance-id (:instance/id binding)
                                :world-observer (:world-observer binding)})
          state (:state fresh)]
      (doseq [row rows]
        (when (= :completed (:status row))
          (let [receipts (mapv receipt->jolt (:receipts row))]
            (sandbox/load-receipts! state receipts)
            (sandbox/set-mode! state :replay)
            ;; Replay runs under the same spec timeout ceiling as normal
            ;; evaluation, on its own private interrupt token.
            (let [value (evaluate-guarded! state (:source row)
                                           (binding-timeout binding) nil)]
              (when-not (result-matches? row value)
                (fail! :replay-result-mismatch "Replayed result differs from durable result"
                       {:eval-id (:id row)}))))))
      (sandbox/load-receipts! state [])
      (sandbox/set-mode! state :normal)
      (reset! (:instance binding) fresh)
      (reset! (:poisoned binding) false)
      binding)))

(defn reconstruct!
  "Revalidate and reconstruct one run's durable EvaluatorBinding.

  Current defaults and requested config are deliberately not consulted.  The
  complete persisted ContextSpec is checked for its own canonical coordinate,
  exact trusted root, current RuntimeCoordinate, deterministic spec/binding/
  instance identities, and then the whole durable history is replayed into one
  newly allocated SCI context.  History validation happens before allocation;
  replay consumes receipts and performs zero real world operations.

  The trusted orientation is RESTORED, never re-rendered: the durable record's
  exact bytes are checked against its own digest and installed as the resumed
  binding's orientation, so a prompt resource that drifted between the crash
  and the resume cannot change a bounded run's trusted surface.  A record with
  missing bytes or a mismatched digest is a closed failure."
  [conn run-id root]
  (let [row (or (store/binding-for-run conn run-id)
                (fail! :binding-missing
                       (message {:binding-missing true})
                       {:run-id run-id}))
        context (:context_spec row)
        expected-context-coordinate
        (sandbox/canonical-coordinate (dissoc context :context/coordinate))
        current-root (canonical-root root)
        orientation (:orientation row)]
    (when-not (= (:context/coordinate context) expected-context-coordinate)
       (fail! :context-coordinate-mismatch
              (message {:context-coordinate-mismatch true})
              {:run-id run-id}))
    (when-not (= current-root (:context/root context))
       (fail! :root-mismatch
              (message {:root-mismatch true})
              {:durable (:context/root context) :current current-root}))
    (when (or (str/blank? (str orientation))
              (str/blank? (str (:orientation_digest row))))
      (fail! :orientation-missing
             (message {:orientation-missing true})
             {:run-id run-id}))
    (when-not (= (:orientation_digest row) (orientation-digest orientation))
      (fail! :orientation-digest-mismatch
             (message {:orientation-digest-mismatch true})
             {:run-id run-id
              :digest (:orientation_digest row)}))
    (let [runtime (runtime-coordinate)]
      (when-not (= runtime (:runtime row))
        (fail! :runtime-mismatch
               "Bounded evaluator runtime differs from durable history"
               {:durable (:runtime row) :current runtime})))
    (let [spec (evaluator-spec context)
          work-id (:work_id row)
          expected {:binding_id (str "bind:" work-id)
                    :instance_id (str "inst:" work-id)
                    :spec_id (:spec/coordinate spec)}
          actual (select-keys row (keys expected))]
      (when-not (= expected actual)
        (fail! :binding-identity-mismatch
               "Reconstructed evaluator identity differs from durable binding"
               {:expected expected :actual actual}))
      ;; A shell with no SCI context. rebuild-internal! validates all history
      ;; coordinates/pending states first, then allocates exactly one fresh
      ;; context and replays every committed evaluation into it.  The
      ;; orientation is the durable record's own bytes.
      (let [binding (binding-value work-id spec
                                   {:instance-id (:instance_id row)
                                    :world-observer nil}
                                   nil orientation)]
        (rebuild-internal! conn binding)))))

(defn rebuild! [conn binding]
  (verify-binding! binding)
  (rebuild-internal! conn binding))

(defn evaluate-recorded!
  "Evaluate one source form under the binding and append begin, operation
  intent/outcome, and terminal rows. Failed evaluations rebuild to committed
  history before propagating.

  opts:
    :token — a caller-held Jolt interrupt token (a later TurnLease's). It may
              only NARROW the evaluation: the spec's :context/timeout-ms
              ceiling still applies on a private per-evaluation token, and the
              caller's token is never fired by the spec's timer.
    :inference-epoch-id — the reusable InferenceEpoch of the model call whose
              tool dispatch produced this evaluation.
    :inference-invocation-id — the per-call InferenceInvocation of that same
              model call.  Both are recorded on the eval row and on every
              intent/outcome receipt, closing the causal chain from the exact
              provider invocation to each semantic operation."
  ([conn binding source] (evaluate-recorded! conn binding source nil))
  ([conn binding source opts]
   (verify-binding! binding)
   (when @(:poisoned binding)
     (fail! :instance-poisoned "Evaluator instance is poisoned" {}))
   (let [claim (str (random-uuid))]
     (when-not (compare-and-set! (:owner binding) nil claim)
       (fail! :instance-busy (message {:evaluator-busy true}) {}))
     (try
       (let [instance @(:instance binding)
             epoch-id (:inference-epoch-id opts)
             invocation-id (:inference-invocation-id opts)
             eval-id (store/begin! conn (assoc (identity-map binding)
                                               :source source
                                               :inference-epoch-id epoch-id
                                               :inference-invocation-id
                                               invocation-id))
             hook (:hook instance)
             effect-permit! (or (:effect-permit! opts) (fn [f] (f)))]
         (reset! hook
                 (fn [op args run]
                   ;; The durable intent append is the semantic operation's
                   ;; initiation/TurnLease linearization point.  The ensuing
                   ;; bounded read/edit runs outside the lease monitor; once
                   ;; initiated it is not retroactively unauthorized.
                   (let [seqn (effect-permit!
                               #(store/intent! conn eval-id op args epoch-id
                                               invocation-id))]
                     (try
                       (let [value (sandbox/inert (run))]
                         (store/outcome! conn eval-id seqn {:result value}
                                         epoch-id invocation-id)
                         value)
                       (catch Throwable e
                         (store/outcome! conn eval-id seqn
                                         {:error (ex-message e)}
                                         epoch-id invocation-id)
                         (throw e))))))
         (try
           (let [value (evaluate-guarded! (:state instance) source
                                          (binding-timeout binding)
                                          (:token opts))
                 result (result-record value)]
             (store/complete! conn eval-id :completed result)
             {:eval-id eval-id :value value :result result})
           (catch Throwable e
             (try
               (store/complete! conn eval-id :failed {:error (ex-message e)})
               (rebuild-internal! conn binding)
               (catch Throwable rollback
                 (reset! (:poisoned binding) true)
                 (throw (ex-info "Evaluation failed and committed-state rollback failed"
                                 {:samizdat.evaluator/error :rollback-failed
                                  :eval-id eval-id}
                                 rollback))))
             (throw e))
           (finally
             (reset! hook nil))))
       (finally
         (compare-and-set! (:owner binding) claim nil))))))

(defn leverage [conn binding]
  (let [completed (filter #(= :completed (:status %))
                          (store/history conn (:binding/id binding)))
        orders (mapv (fn [row] (mapv :op (:receipts row))) completed)]
    {:evaluations (count completed)
     :operations-per-eval (mapv count orders)
     :multi-operation-evals (count (filter #(< 1 (count %)) orders))
     :operation-order orders}))
