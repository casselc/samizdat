;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evaluator
  "Trusted bounded evaluator mechanism. M1 shipped the read-only
  :agent/project-read profile; M2 added the :agent/project-develop profile and
  its one semantic mutation; JS2 adds the :agent/project-execute profile and
  its one semantic EXECUTION.

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
  the write.

  The JS2 execution side, (project/run argv) and (project/run argv options),
  is the one genuinely new authority class since M1: the model names an argv
  and it RUNS, inside a controller-selected isolated ExecutionEnvironment.
  Nothing about that authority lives here. This namespace validates nothing
  about the request and knows nothing about machines: it checks that the
  binding holds :project/run, hands the arguments to the selected project
  execution provider, and records intent before and outcome after exactly as
  it does for an edit. The isolation, the pinned image, the read-only project
  mount, the private overlay, the constructed environment, the bounds, the
  cleanup and the fail-closed refusal are the provider's
  (samizdat.security.project-execution-provider), because they are policy and
  this is mechanism.

  Two properties of the execution side are this namespace's, though, and both
  are load-bearing. Its result is DEVELOPMENT evidence and is read by nothing
  that decides completion — `done` crosses the controller's own verifiers and
  has no path to a project/run result at all. And a replayed execution
  consumes its receipt and launches NOTHING: the same replay rule as every
  other semantic operation, which is why the execution provider's invocation
  counter is the thing a resume is checked against."
  (:require [clojure.set :as set]
             [clojure.string :as str]
             [jolt.fs :as fs]
             [jolt.sandbox :as sandbox]
             [samizdat.agent.files :as files]
             [samizdat.agent.surface :as surface]
             [samizdat.prompt :as prompt]
             [samizdat.security.no-replace :as no-replace]
             [samizdat.security.project-execution-provider :as pep]
             [samizdat.store.evaluator :as store]
             [samizdat.store.journal :as journal]))

(def jolt-coordinate "c8d9181e23cc37aa91a38fdcbd01c93917b1be50")
(def jolt-publish-coordinate
  "jolt-publish/v1:sha256:914ccd9f722efd98fe8e1e1381574a3efba04ae45a689e8c1918d420db82f0c1")
(def sci-coordinate "32d62a5136ad3dc148588752f5bcc4cc30b14752")
(def sci-version "0.13.53")
(def profile-id :agent/project-read)
(def develop-profile-id :agent/project-develop)
(def execute-profile-id :agent/project-execute)
(def top-level-tools surface/bounded-top-level-tools)
(def profile-capabilities
  "The :agent/project-read catalog maximum, derived from the sandbox's closed
  profile table — the one source of truth for what a profile may ever hold."
  (:profile/max-capabilities (get sandbox/profiles profile-id)))
(def develop-capabilities
  "The :agent/project-develop catalog maximum: the read profile plus the one
  semantic mutation, from the same table."
  (:profile/max-capabilities (get sandbox/profiles develop-profile-id)))
(def execute-capabilities
  "The :agent/project-execute catalog maximum: the develop profile plus the
  one semantic execution, from the same table. Reading it from the table
  rather than composing it here is the point — the runtime's closed maximum
  decides, and a develop binding cannot acquire :project/run by anything
  written on this side."
  (:profile/max-capabilities (get sandbox/profiles execute-profile-id)))
(def semantic-operation-order surface/semantic-operation-order)
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
   ;; The evaluation ceiling for a binding that holds :project/run.
   ;;
   ;; The 30-second ceiling above bounds COMPUTATION INSIDE SCI, which is the
   ;; thing an unbounded model expression can hang the process with. A
   ;; project/run is not that: it is a controller-owned execution in another
   ;; machine, already bounded by the execution provider's own pinned wall
   ;; clock, whose whole point is that a real project suite takes minutes. An
   ;; evaluation ceiling below the execution ceiling would make the capability
   ;; unusable — every useful run would die at 30 seconds having already spent
   ;; the machine — and would do it in the shape of a timeout the model would
   ;; read as its own code being slow.
   ;;
   ;; It is still a MECHANISM bound, not a tunable: it is selected by the
   ;; capability set the CONTROLLER authorized, which no request can widen,
   ;; and a binding without :project/run keeps the 30-second ceiling exactly.
   ;; The slack over the execution ceiling is for the rest of an eval — the
   ;; observations and the local computation around the run.
   :execute-timeout-ceiling-ms 660000
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
(def execute-timeout-ms (:execute-timeout-ceiling-ms mechanism-bounds))

(defn timeout-ceiling
  "The per-evaluation ceiling for an EFFECTIVE capability set. Derived, never
  configured: a binding that may execute gets the execution ceiling and every
  other binding gets the ordinary one."
  [capabilities]
  (if (contains? (set capabilities) :project/run)
    execute-timeout-ms
    default-timeout-ms))

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
   {:name "project/edit"
    :arglists [["path" "base" "old-text" "new-text"]
               ["path" "base" "new-content"]]
    :doc "Mutate one regular file under the authorized project root and return its new {:path :kind :bytes :digest} — exactly what project/stat would report, so the digest is the next mutation's anchor. base is always the exact digest project/stat returned for the current content, or :absent to create.

PREFER THE FOUR-ARGUMENT ANCHORED FORM for a change to an existing file: (project/edit path base old-text new-text) replaces the ONE exact occurrence of old-text with new-text and leaves every other byte of the file untouched. old-text must occur exactly once — zero occurrences and two or more occurrences are both refused, so the anchor can never silently hit the wrong place. This is the surgical form: use it to change one function without reproducing the rest of the namespace.

The three-argument form (project/edit path base new-content) replaces the WHOLE file, and with base :absent creates a new one. Use it to create a file, or when you genuinely intend to rewrite the entire contents. Rewriting a whole existing file from memory is how unrelated definitions get silently deleted.

Both forms refuse, and write nothing, on: a stale base, a missing anchor target, an existing create target, the operator's run config, a symbolic link in any component, a non-regular-file target, a missing parent, or content over the bound. The write is a temp file in the target's directory: create is an atomic Linux no-replace publication, replacement is an atomic rename."}
   :project/run
   {:name "project/run"
    :arglists [["argv"] ["argv" "options"]]
    :doc "Run a command against a DISPOSABLE PRIVATE COPY of the project, inside an isolated execution environment, and return the result as data.

argv is a non-empty vector of strings — the executable and its arguments, never a shell command line. options is an optional map accepting only :cwd (a relative directory inside the workspace) and :timeout-ms (which may only NARROW the environment's ceiling). Anything else is refused: the image, the network, the mounts, the environment variables, the resource limits and the identity are the controller's, not yours.

WHAT IT IS FOR: running the project's own toolchain while you work — its tests, its compiler, its linter, its formatter. It is how you find out whether an edit you just made is right, without spending a turn guessing.

WHAT THE WORKSPACE IS: a private copy. Writes inside it succeed and then vanish with the environment — build artifacts, caches, files a formatter rewrote, anything. THEY DO NOT CHANGE THE REAL PROJECT. The only thing that changes the real project is project/edit.

WHAT IT IS NOT: verification. A green run here is evidence for YOU. Completion is decided by the controller's own verifiers when you call done, which run independently and are not this. Running the suite here does not make you done, and skipping it does not stop you being done.

The result is a map: :status (:completed, :timeout, :failed or :refused), :exit (present only when the workload actually exited), :stdout and :stderr (each {:text :bytes :truncated?}, where :bytes is what was WRITTEN and :text may be cut short), :duration-ms, :argv, :cwd, :invocation, and the :environment and :input coordinates naming what ran it and which project bytes it ran against.

ONE EVAL CAN DO THE WHOLE LOOP: inspect state, run the toolchain, read the structured result, branch on it, and return a short conclusion. Do that instead of spending a model turn per command — see (doc \"eval\")."}})

(def tool-docs
  {"eval" {:name "eval" :arglists [["code"]]
           :doc "Evaluate code in this binding's persistent bounded SCI context."}
   "doc" {:name "doc" :arglists [["symbol"]]
          :doc "Describe a callable name from this binding's trusted catalog."}
   "complete" {:name "complete" :arglists [["prefix"]]
               :doc "List callable trusted-catalog names matching prefix."}
   "done" {:name "done" :arglists [[]]
           :doc "Emit a completion request. M1 refuses successful completion because verification is unavailable."}})

(defn capability-catalog
  "The runtime's capability/profile catalog, as an inert value.

  Part of the RuntimeCoordinate from JS2 onward (§4.1). M4's coordinate named
  the Jolt source, the language surface and the two protocol versions, which
  between them could not distinguish a runtime that gained a capability from
  one that had not — and a capability catalog is exactly the kind of thing a
  durable binding must be reconstructed against. Both halves are here: the
  runtime's CLOSED profile maxima, and the operation vocabulary this build
  actually compiles. A runtime that offers a profile this build cannot supply
  operations for, or a build that compiles an operation the runtime has no
  capability for, is a different runtime and says so."
  []
  (sandbox/inert
   {:catalog/profiles
    (into {} (map (fn [[id data]]
                    [(str id)
                     (vec (sort (map str (:profile/max-capabilities data))))]))
          sandbox/profiles)
    :catalog/compiled (vec (sort (map str compiled-capabilities)))}))

(defn runtime-snapshot []
   (sandbox/inert
    {:runtime/jolt-source jolt-coordinate
     :runtime/jolt-publish-source jolt-publish-coordinate
    :runtime/jolt-version (jolt.host/jolt-version)
    :runtime/sci-source sci-coordinate
    :runtime/sci-version sci-version
    :runtime/language (sandbox/language-coordinate)
    :runtime/capability-catalog (capability-catalog)
    :runtime/evaluator-protocol 1
    :runtime/receipt-protocol 1}))

(defn runtime-coordinate
  "The exact identity of the runtime a durable binding was minted under.

  The prefix moves with the coordinate's CONTENT, deliberately: this is
  `js2-rt/v1:`, not `js1-rt/v1:`, because the JS2 runtime is a different
  runtime — different Jolt source, a capability the M4 catalog did not have,
  and a catalog identity M4's coordinate did not name at all. A JS1 binding
  and a JS2 binding must not be able to look like each other, and a resume
  that crosses them must fail closed on the mismatch rather than reconstruct
  a JS1 history into a runtime that can execute."
  []
  (str "js2-rt/v1:" (subs (sandbox/canonical-coordinate (runtime-snapshot)) 4)))

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

  Defaults to the ceiling the binding's own effective authority derives (30
  seconds, or the execution ceiling for a binding that holds :project/run).
  The controller may only NARROW it: a requested value above the ceiling is
  attenuated down to it, exactly as a requested capability beyond
  authorization is intersected away, and zero or a negative value is refused
  rather than read as \"no ceiling\". Nothing a caller or controller supplies
  can stretch an evaluation past the ceiling its capabilities derive."
  ([timeout-ms] (resolve-timeout timeout-ms default-timeout-ms))
  ([timeout-ms ceiling]
   (let [requested (or timeout-ms ceiling)]
     (when-not (and (integer? requested) (pos? requested))
       (fail! :invalid-timeout
              "timeout-ms: positive integer milliseconds required"
              {:timeout-ms timeout-ms}))
     (min (long requested) (long ceiling)))))

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
              ;; The ceiling follows the EFFECTIVE set, computed above — not
              ;; the profile and not the request. A project-execute binding
              ;; the controller attenuated down to reads gets the ordinary
              ;; 30-second ceiling, because it can no longer do the thing the
              ;; longer one exists for.
              :context/timeout-ms (resolve-timeout
                                   timeout-ms (timeout-ceiling effective))}]
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

(defn- occurrence-index
  "The index of the single occurrence of `needle` in `haystack`, or a keyword
  saying why there is not exactly one: :none or :ambiguous.

  Literal scanning, never a regex. A model-supplied anchor is data, and the one
  thing a maintenance primitive must never do is let that data become a
  matching language — `.*` in an anchor would make the blast radius of a
  'surgical' edit the whole file again."
  [^String haystack ^String needle]
  (let [i (.indexOf haystack needle)]
    (cond
      (neg? i) :none
      (nat-int? (.indexOf haystack needle (inc i))) :ambiguous
      :else i)))

(defn- validate-replace-arguments!
  "Lexical validation for the anchored form, before any filesystem access."
  [base old-text new-text bounds]
  (when-not (and (string? old-text) (string? new-text))
    (fail! :invalid-arguments (message {:replace-args true})
           {:old-type (str (type old-text)) :new-type (str (type new-text))}))
  ;; An empty anchor matches at index 0 of every file, which is not an anchor.
  (when (zero? (count old-text))
    (fail! :invalid-arguments (message {:replace-args true}) {:old-text :empty}))
  (when (> (count new-text) (:max-edit-chars bounds))
    (fail! :too-large (message {:edit-large true})
           {:limit (:max-edit-chars bounds)}))
  (when-not (and (string? base) (re-matches edit-base-pattern base))
    (fail! :invalid-arguments (message {:replace-args true})
           {:base (pr-str base)})))

(defn- replace-project-text
  "The (project/edit path base old-text new-text) semantics: replace the ONE
  exact occurrence of old-text and leave every other byte alone.

  THE LOW-AMPLITUDE MUTATION. JS1 M4 attempt 1 had only the whole-file form,
  so a model that wanted to change one arithmetic expression had to reproduce
  an entire namespace verbatim; it could not, regenerated the file from its
  priors instead, and silently deleted two live production functions
  (attempt-1 §14). The amplitude of a mutation should match the amplitude of
  the intent, and this is the form whose blast radius is the anchor.

  It runs under the SAME capability as the whole-file form and reuses its
  confinement, protected-path refusal, symlink refusal, digest anchor and
  atomic publication unchanged. Every refusal throws BEFORE any write, so a
  refused replacement leaves the tree byte-identical."
  [root bounds rel base old-text new-text]
  (let [components (lexical-components root rel false)
        rel (relative-name components)]
    (when (files/run-config? root (str root "/" rel))
      (fail! :protected-path (message {:edit-protected true}) {:path rel}))
    (validate-replace-arguments! base old-text new-text bounds)
    (let [parent (descend root (butlast components))
          abs (str parent "/" (last components))
          kind (classify abs)]
      (when (= :symlink kind)
        (fail! :symlink (message {:symlink-path true}) {:path rel}))
      ;; Anchored replacement is only ever a modification: there is nothing to
      ;; anchor in a file that does not exist. Creating is the 3-arity job.
      (when-not (= :file kind)
        (fail! :missing (message {:edit-missing true}) {:path rel :kind kind}))
      (let [ceiling (read-byte-ceiling (:max-read-chars bounds))
            current-digest (file-digest abs ceiling)]
        (when-not (= base current-digest)
          (fail! :stale (message {:edit-stale true})
                 {:path rel :current-digest current-digest}))
        (let [current (read-text abs (:max-read-chars bounds))
              at (occurrence-index current old-text)]
          (case at
            :none (fail! :anchor-missing (message {:replace-missing true})
                         {:path rel})
            :ambiguous (fail! :anchor-ambiguous
                              (message {:replace-ambiguous true})
                              {:path rel})
            (let [new-content (str (subs current 0 at)
                                   new-text
                                   (subs current (+ at (count old-text))))]
              ;; The SPLICED result carries the bound, not just the fragment:
              ;; a small new-text pasted into a large file must still land
              ;; inside the trusted write ceiling.
              (when (> (count new-content) (:max-edit-chars bounds))
                (fail! :too-large (message {:edit-large true})
                       {:limit (:max-edit-chars bounds)}))
              (let [content-bytes (.getBytes ^String new-content "UTF-8")
                    perms (try (fs/posix-file-permissions abs)
                               (catch Throwable _ nil))]
                (replace-atomically! parent abs false perms content-bytes rel)
                {:path rel :kind :file :bytes (alength content-bytes)
                 :digest (str "sha256:" (bytes-digest content-bytes))}))))))))

(defn- run-project-command
  "The (project/run argv options) semantics, run inside the operation's
  intent/outcome recording.

  Everything here is a hand-off. The request is validated by the PROVIDER,
  before any staging and having launched nothing — an invalid request is an
  evaluation error the model reads and fixes, exactly like a bad argument to
  any other operation. The execution is the provider's, in the isolated
  environment the controller selected. The result comes back as inert data
  and becomes the receipt.

  This function deliberately contains no policy: no argv inspection, no
  executable list, no path rule, no bound. Every one of those belongs to the
  execution environment, and a copy of one here would be a second place for
  the boundary to be wrong.

  The provider is a STATIC require, not a runtime resolution. It was the
  latter, on the reasoning that keeps SCI off the ordinary classpath — but
  this namespace already loads only in the bounded lane (it requires
  jolt.sandbox), so the provider is no more optional here than the sandbox
  is, and resolving it lazily bought nothing. What it cost was found by the
  convergence smoke: the resolution ran on a branch worker thread, failed
  there for reasons a swallowed exception could not report, and the model was
  told its execution provider was unavailable in a process where it was
  perfectly available. A dependency that must be present is better missing at
  load than missing at the one call that needed it."
  [root argv options]
  (pep/run root (pep/validate-request argv options)))

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
        ;; ONE actuation capability, two shapes. The pinned bounded runtime's
        ;; profile table is a CLOSED maximum (jolt.sandbox/profiles) and
        ;; rejects both an unlisted capability id and a duplicate id, so the
        ;; anchored form is an arity of this operation rather than a second
        ;; one. That is the honest shape anyway: an anchored replacement is
        ;; not new authority over the project, it is a narrower way to spend
        ;; the authority project/edit already holds. Receipts record the full
        ;; argument vector, so the two shapes stay distinguishable in evidence
        ;; and replay matches each exactly.
        {:id :project/edit :name 'edit :effect :actuation
         :fn (fn
               ([rel base new-content]
                (observe
                 :project/edit [rel base new-content]
                 #(edit-project-file root bounds rel base new-content)))
               ([rel base old-text new-text]
                (observe
                 :project/edit [rel base old-text new-text]
                 #(replace-project-text root bounds rel base old-text new-text))))}
        run-op
        ;; :actuation, not :observation — and the classification matters for
        ;; exactly one thing: replay. An execution is recorded and replayed
        ;; from its receipt like a mutation, so a reconstruction consumes the
        ;; historical result and launches no environment. It is NOT an
        ;; actuation upon the project: the authoritative tree cannot change
        ;; through here, and `edited-paths` — the controller's record of what
        ;; a run changed — reads :project/edit receipts and nothing else, so
        ;; a run can never widen what the verifier verifies.
        {:id :project/run :name 'run :effect :actuation
         :fn (fn
               ([argv]
                (observe :project/run [argv]
                         #(run-project-command root argv nil)))
               ([argv options]
                (observe :project/run [argv options]
                         #(run-project-command root argv options))))}]
    [read-op list-op search-op stat-op edit-op run-op]))

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

(defn trusted-orientation
  "The bounded lane's whole system prompt, derived from the binding's own
  effective surface.

  DERIVED, never hand-listed. The tool list, the operation list and the
  guidance all come from `surface/of-binding`, so the orientation cannot
  describe a surface the binding does not have — the failure mode attempt 1
  hit from the other direction, where the per-turn context described tools the
  binding never had (finding F-1).

  It teaches five things. Attempt 1 showed each of the first four costs turns
  when it is missing; the fifth is JS2's new authority, and the cost of not
  teaching it is a model that never uses it:

  1. WHAT is callable — the only part attempt 1 already had.
  2. HOW to call it. The bounded lane replaces the base system prompt
     entirely, so nothing else ever tells the model the tool-call envelope.
     Attempt 1 opened with one no-call and two parse errors before the repair
     ladder taught it the fence by trial (finding F-2).
  3. That project/* are ordinary Clojure calls INSIDE eval and never
     top-level tool names. Attempt 1's agents tried `project/read`,
     `project/stat` and `project/edit` as top-level tools five times.
  4. WHICH mutation shape to reach for. The whole-file form is how attempt 1
     destroyed two live functions; the anchored form is the default here.
  5. THAT the project's toolchain can be run, what the workspace it runs in
     is (disposable, private, no writeback), that its result is development
     evidence and not completion, and that the whole inspect/run/branch loop
     belongs in ONE eval. Attempt 2 spent turns 29-49 validating a helper one
     assertion per model turn with the answer computable in one; the shape of
     the leverage is taught here rather than hoped for."
  [binding]
  (let [surface (surface/of-binding binding)
        develop? (contains? (:capabilities surface) :project/edit)
        ;; JS2's fifth thing to teach, and the same rule as the other four:
        ;; it renders only for a binding that actually holds :project/run, so
        ;; a develop binding is never told about a capability it does not
        ;; have — which is finding F-1 from the other direction.
        execute? (contains? (:capabilities surface) :project/run)
        ;; The template is one file of conditionals, so the branches that do
        ;; not fire still leave their surrounding whitespace behind. Sections
        ;; are trimmed and their blank runs collapsed here rather than by
        ;; contorting the prose, which stays editable without a rebuild.
        section (fn [data] (-> (message data)
                               (str/replace #"\n{3,}" "\n\n")
                               str/trim))]
    (str "SYSTEM / TRUSTED SURFACE\n"
         "Callable top-level tools:\n"
         (str/join "\n" (map #(str "- " %) (:top-level surface)))
         "\nSemantic operations, callable ONLY inside eval:\n"
         (str/join "\n" (map #(str "- " %) (:operation-names surface)))
         "\n\n" (section {:orientation-envelope true})
         ;; The in-eval sentence names the operations this binding actually
         ;; has, so a narrowed binding never advertises a wider set.
         "\n\n" (section {:orientation-in-eval true
                           :ops (str/join ", " (:operation-names surface))
                           :example-op (or (first (:operation-names surface))
                                           "project/read")})
         "\n\n" (section (cond-> {:orientation-guidance true}
                           develop? (assoc :orientation-develop true)
                           execute? (assoc :orientation-execute true))))))

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

(defn live-context-id
  "The process-local identity of this binding's CURRENT SCI context.

  Observability only. It is deliberately not a replay coordinate and not part
  of evaluator authority: reconstruction is validated by the ContextSpec,
  RuntimeCoordinate and durable history, and a context id that changed every
  restart would be a coordinate that can never match. It exists so the
  lifecycle below is readable from durable evidence instead of from an
  operator watching a live process (M4 attempt-1 finding F-6)."
  [binding]
  (some-> (:instance binding) deref :context-id))

(defn- note-context!
  "Journal one SCI context lifecycle fact. Never throws: a run whose evidence
  cannot be written is still a run, and this is an observer."
  [conn run-id phase data]
  (when (and conn run-id)
    (try
      (journal/note! conn run-id :evaluator-context
                     {:data (assoc data :phase phase)})
      (catch Throwable _ nil))))

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
  ;; ALLOCATED. The first context of this binding, in this process.
  (note-context! conn run-id :allocated
                 {:context-id (live-context-id binding)
                  :instance-id (:instance/id binding)
                  :binding-id (:binding/id binding)})
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
                                   nil orientation)
            ;; The context this process is retiring by having never had it:
            ;; the previous process's context died with the process, and the
            ;; last :allocated / :reconstructed event names it.
            prior (:context-id (journal/last-context conn run-id))
            rebuilt (rebuild-internal! conn binding)]
        ;; RECONSTRUCTED. A fresh context, and the identity of the one it
        ;; supersedes — which together make "retired by process death" and
        ;; "fresh context allocated on reconstruction" readable facts rather
        ;; than an operator's observation of a live process.
        (note-context! conn run-id :reconstructed
                       {:context-id (live-context-id rebuilt)
                        :instance-id (:instance/id rebuilt)
                        :binding-id (:binding/id rebuilt)
                        :supersedes prior
                        :replayed-evaluations
                        (count (filter #(= :completed (:status %))
                                       (store/history conn (:binding/id rebuilt))))})
        rebuilt))))

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
