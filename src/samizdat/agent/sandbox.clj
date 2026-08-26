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

(ns samizdat.agent.sandbox
  "JS1 bounded evaluator seam: EvaluatorSpec / Instance / Binding.

   Three distinct coordinates, never conflated:

     EvaluatorSpec — INERT data (jolt.sandbox receipt domain): preset,
       profile, canonical root, sorted capabilities, bounds, timeout, and
       :spec/coordinate, a deterministic self-certifying content coordinate
       covering all of them (\"js1:\" + the JS0 canonical form).  Safe to
       serialize, compare, and re-verify.

     Instance — a LIVE jolt.sandbox context allocated from a spec, with a
       stable :instance/id (\"inst:\" (name key)) that survives rebuild!
       and a policy-assigned :instance/key.

     Binding — the association of a work-id to an instance, minted by the
       provider.  :binding/id is deterministic (\"bind:<key>:<work-id>\");
       the map's identity fields are inert, plus the live instance ref.

    Trust boundary: the controller owns spec/instantiate!/provider/acquire!/
    bind!.  Model-facing evaluation is evaluate!/describe/capabilities
    (and the host-derived discovery in operation-doc/complete-capability)
    on a minted value — evaluate! REJECTS any profile/preset/capability/
    root/instance-key option: authority is fixed at bind time, and the
    preset catalog is closed, so a model cannot select or widen authority.

   JS1 provider policy: ordinary work binds deterministically to one
   persistent instance key, :main.  A controller that needs isolation
   passes a distinct :instance/key and gets a separate live context.

   Presets (trusted, closed catalog — see `presets`):
     :project/read    — :agent/project-read,  capabilities read/list/search/stat
     :project/develop — :agent/project-develop, read/list/search/stat + edit

    Five semantic ops projected as project/read, project/list,
    project/search, project/stat, project/edit — normalized against the
    frozen bb4t A2/A3b project contract, all rooted at the trusted host
    root: relative nonescaping paths validated lexically, symbolic links
    refused everywhere (leaf or component, in-root or escaping), bounded
    strict-UTF-8 reads that stop at the bound before consuming, one-level
    inert sorted structured listings, {:path :line :text} search results
    under file/result/byte/regex bounds with per-file consumption checked
    before reading, fail-closed stat digests (a coordinate is computed or
    the operation fails — never nil/fake), and an anchored edit that writes
    through a sibling temporary and an atomic rename, creates only an
    absent leaf (never parent hierarchy), and conflicts rather than
    overwriting blindly.  All returned data is inert/canonical via
    jolt.sandbox.  Safe host-derived doc/complete unions the effective
    project operations with the reviewed pure language surface
    (jolt.sandbox/language-surface at Jolt 619ef196) — trusted static data,
    never live introspection.

    Timeout uses Jolt cooperative interrupt with an unraiseable host
    ceiling: jolt.host/run-interruptible checks the token from the host
    side; SCI code cannot catch or suppress the interruption.  A
    caller-supplied token composes UNDER the ceiling — the guarded
    evaluation runs on a private per-evaluation token the ceiling timer
    and a caller-token relay both interrupt, so no caller (a model-held
    turn lease included) can stretch an evaluation past the spec's
    timeout, and the spec's timer can never fire a caller's shared token
    after the guarded extent (evaluate-state!).

    Durable recorded evaluation (evaluate-recorded! / rebuild-binding!):

      RuntimeCoordinate — every durable record names the exact runtime stack
        its receipts are meaningful under: Jolt version, vendored SCI
        coordinate, evaluator-protocol version, reviewed language-surface
        coordinate, capability-catalog coordinate/version, and
        receipt-protocol version, all under one versioned self-certifying
        js1-rt/v1: coordinate.  Verification compares it exactly; an
        upgraded runtime fails closed instead of replaying across the
        change.

      Strict receipts, bounded rendered result — receipt payloads stay in
        the strict canonical receipt domain, but an evaluation's FINAL value
        may be any SCI value: inert values are stored exactly, everything
        else is rendered under print bounds plus a character bound.

        Commit-only state — a recorded evaluation's definitions become
        evaluator state only by committing.  A failed or interrupted
        evaluation's partial definitions are rolled back: the instance is
        rebuilt from the binding's durable committed history and the rebuilt
        instance is published before the original error propagates.  If that
        rollback itself fails, the instance is poisoned and refuses further
        evaluation until rebuilt.

      Whole-history rebuild — rebuild-binding! replays EVERY committed
        evaluation of a binding, in the binding's durable total order
        (binding_seq), into ONE fresh SCI context.  Replay runs in
        jolt.sandbox :replay mode throughout, so zero real project
        operations execute; every record is validated against the binding's
        spec/instance/binding identity, authority coordinate, and runtime
        coordinate before any of it is trusted, and a pending record, a gap
        in the total order, a shared instance, or any mismatch fails closed.

    Legacy direct API (preserved): new / evaluate! / rebuild! / describe.
    Note: the original draft named the constructor `registry/new`; under
    Jolt a multi-segment defn name interns under its final segment, so it
    was always callable only as plain `new` — that name is kept.

    See samizdat.sandbox-test docstring for the direct invocation.

    Blockers:
      - jolt.sandbox requires SCI (sci.core) - not available on plain JVM.
      - jolt.host/run-interruptible and make-interrupt are Jolt host fns."
  (:require [clojure.string :as str]
            [jolt.fs :as fs]
            [jolt.sandbox :as sandbox]
            [samizdat.agent.files :as files]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Defaults
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const default-max-read-chars 60000)
(def ^:const default-max-list-entries 1000)
(def ^:const default-max-search-results 500)
(def ^:const default-search-max-chars 500000)
(def ^:const default-search-max-files 20000)
(def ^:const default-max-write-bytes 1048576)
(def ^:const default-timeout-ms 30000)

;; Bounds the frozen bb4t A2/A3b contract fixes for every path argument:
;; a model-supplied path is one bounded (≤ `max-path-chars`) non-empty
;; relative string, and a search pattern at most `max-search-pattern-chars`.
(def ^:const max-path-chars 4096)
(def ^:const max-search-pattern-chars 200)
(def ^:const max-search-line-chars 300)

(defn- read-byte-ceiling
  "The byte bound a bounded read stops at, derived from the character bound
   (a character occupies at most four UTF-8 bytes, so this is the largest
   byte consumption a max-chars read can ever need).  Bounded BEFORE
   decoding: no read ever consumes past this ceiling regardless of what the
   file contains."
  [max-chars]
  (* 4 max-chars))

;; ═══════════════════════════════════════════════════════════════════════════
;; Trusted preset catalog (closed)
;; ═══════════════════════════════════════════════════════════════════════════

(def presets
  "Trusted, closed catalog of named JS1 evaluator presets.  Only controller
   code selects a preset (via spec/bind!); the model-facing evaluation path
   never accepts a profile, capability, or preset selection.  Each preset
   fixes the exact jolt.sandbox profile and the exact maximum capability
   set; a controller may only attenuate (narrow) from here, never widen."
  {:project/read
   {:preset/id :project/read
    :profile :agent/project-read
    :capabilities #{:project/read :project/list :project/search :project/stat}}
   :project/develop
   {:preset/id :project/develop
    :profile :agent/project-develop
    :capabilities #{:project/read :project/list :project/search :project/stat
                    :project/edit}}})

;; ═══════════════════════════════════════════════════════════════════════════
;; RuntimeCoordinate — versioned identity of the runtime a receipt names
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const runtime-protocol-version
  "Version of the RuntimeCoordinate scheme itself (the js1-rt/vN: prefix)."
  1)

(def ^:const evaluator-protocol-version
  "Version of the JS1 evaluator protocol: the EvaluatorSpec/Instance/Binding
   seam, the recorded intent/outcome/completion lifecycle, the five projected
   operations' normalized frozen-contract semantics (bounded strict-UTF-8
   read, one-level structured listing, {:path :line :text} bounded search,
   fail-closed stat digests, atomic anchored edit that never creates
   hierarchy), and the replay contract this namespace implements.  Bumped to
   2 with that normalization: durable receipts recorded under the v1 result
   shapes fail closed at replay instead of being reinterpreted."
  2)

(def ^:const receipt-protocol-version
  "Version of the durable receipt protocol shared with samizdat.store.evals:
   the canonical EDN receipt domain, intent-before-effect ordering, and the
   append-only pending/completed settlement rules."
  1)

(def ^:const capability-catalog-version
  "Version of the trusted closed preset catalog (`presets`).  The catalog
   content is also named exactly by :runtime/capability-catalog's canonical
   coordinate; this version names the catalog SCHEMA."
  1)

(def ^:const sci-implementation
  "The SCI implementation coordinate named by the RuntimeCoordinate.  SCI
   exposes no runtime version, so this names the vendored SCI release in the
   local jolt source tree (vendor/sci, per its resources/SCI_VERSION) as a
   reviewed constant: bump it deliberately when the vendored tree moves."
  "sci-0.13.53")

(defn- capability-catalog-description
  "Inert, canonical description of the closed trusted preset catalog — the
   capability catalog the RuntimeCoordinate names.  Sets are rendered as
   sorted vectors so the description is wholly inside the receipt domain."
  []
  (into {}
        (map (fn [[preset-key preset]]
               [preset-key {:profile (str (:profile preset))
                            :capabilities (vec (sort-by str (:capabilities preset)))}]))
        presets))

(defn runtime-snapshot
  "Inert, data-only description of the exact runtime stack a durable JS1
   record names: the Jolt version, the vendored SCI coordinate, the
   evaluator-protocol version, the reviewed language-surface coordinate
   (jolt.sandbox/language-coordinate, itself versioned), the capability
   catalog's content coordinate and schema version, and the receipt-protocol
   version.  Fully canonical (receipt domain throughout); suitable for
   serialization and comparison."
  []
  (sandbox/inert
    {:runtime/jolt (jolt.host/jolt-version)
     :runtime/sci sci-implementation
     :runtime/evaluator-protocol evaluator-protocol-version
     :runtime/language (sandbox/language-coordinate)
     :runtime/capability-catalog
     (sandbox/canonical-coordinate (capability-catalog-description))
     :runtime/capability-catalog-version capability-catalog-version
     :runtime/receipt-protocol receipt-protocol-version}))

(def ^:private runtime-coordinate*
  (delay
    (let [c (sandbox/canonical-coordinate (runtime-snapshot))]
      (when-not (str/starts-with? c "js0:")
        (throw (ex-info "Unexpected JS0 canonical coordinate form"
                        {:samizdat.sandbox/error :coordinate})))
      (str "js1-rt/v" runtime-protocol-version ":" (subs c 4)))))

(defn runtime-coordinate
  "The versioned RuntimeCoordinate of this process, as a self-certifying
   js1-rt/v1: string: the JS0 canonical form of `runtime-snapshot`, retagged
   and versioned.  Deterministic for one runtime stack; durable evaluation
   records carry it and verification compares it exactly, so a runtime that
   changed out from under a record fails closed at replay instead of
   reinterpretating its receipts."
  []
  @runtime-coordinate*)

;; ═══════════════════════════════════════════════════════════════════════════
;; Path resolution (symlink-safe, fail-closed)
;; ═══════════════════════════════════════════════════════════════════════════

(defn- resolve-root
  "Resolve root to its canonical absolute path string."
  [root]
  (str (fs/canonicalize root)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Path policy — the frozen bb4t A2/A3b rules, model-facing side
;; ═══════════════════════════════════════════════════════════════════════════

(defn- fail!
  [code message data]
  (throw (ex-info message (assoc data :samizdat.sandbox/error code))))

(defn- raise-files-failure
  "Re-throw a samizdat.agent.files substrate failure with the sandbox error
   key added under the same code, so every model-facing failure carries
   {:samizdat.sandbox/error …} whichever layer produced it.  Non-files
   failures pass through untouched."
  [^Throwable failure]
  (let [d (ex-data failure)]
    (if (and (map? d) (contains? d :samizdat.files/error))
      (throw (ex-info (ex-message failure)
                      (assoc d :samizdat.sandbox/error (:samizdat.files/error d))
                      failure))
      (throw failure))))

(defn- lexical-relative
  "Validate one model-supplied relative path exactly as the frozen contract
   does and return its lexically normalized components under the root ([] is
   the root itself): a bounded non-empty relative string, normalized before
   any filesystem access, and unable to leave the root lexically.  The root
   itself is admitted only when allow-root? (listing and searching it is
   their primary use; read/stat/edit reject it as 'not a regular file')."
  [root-canonical rel-path operation allow-root?]
  (when-not (and (string? rel-path)
                 (not (str/blank? rel-path))
                 (<= (count rel-path) max-path-chars))
    (fail! :invalid-path
           (str operation " expects one bounded non-empty relative path")
           {:operation/id operation :path (str rel-path)}))
  (when (fs/absolute? (fs/path rel-path))
    (fail! :absolute-path
           (str operation " rejects absolute paths")
           {:operation/id operation :path rel-path}))
  (let [lexical (str (fs/normalize (fs/path root-canonical rel-path)))]
    (when-not (or (= lexical root-canonical)
                  (str/starts-with? lexical (str root-canonical "/")))
      (fail! :path-escape
             (str operation " path escapes the authorized root")
             {:operation/id operation :path rel-path}))
    (when (and (= lexical root-canonical) (not allow-root?))
      (fail! :not-file
             (str operation " target is the project root, not a file")
             {:operation/id operation}))
    (if (= lexical root-canonical)
      []
      (vec (remove str/blank? (str/split (subs lexical (inc (count root-canonical)))
                                         #"/"))))))

(defn- require-directory-component!
  "One intermediate walk component must exist, be a directory, and NOT be a
   symbolic link.  Refusing links outright — even links that stay inside the
   root — is the frozen contract's rule: no walk can be redirected through
   one, so 'would this link escape?' never has to be answered."
  [dir component operation]
  (let [child (str dir "/" component)]
    (cond
      (not (fs/exists? child {:nofollow-links true}))
      (fail! :not-found (str operation " path does not exist")
             {:operation/id operation :component component})

      (fs/sym-link? child)
      (fail! :symlink (str operation " will not follow a symbolic link")
             {:operation/id operation :component component})

      (not (fs/directory? child {:nofollow-links true}))
      (fail! :not-found (str operation " path component is not a directory")
             {:operation/id operation :component component}))))

(defn- descend
  "Walk `components` under the root, refusing every symbolic link and every
   missing/non-directory component (bb4t's descend).  Returns the absolute
   path of the directory the walk lands in — the target itself when
   components is empty."
  [root-canonical components operation]
  (loop [dir root-canonical
         [component & more] components]
    (if component
      (do (require-directory-component! dir component operation)
          (recur (str dir "/" component) more))
      dir)))

(defn- target-of
  "Resolve a leaf path to [parent-abs leaf-name kind]: the walk descends to
   the parent with the frozen rules and classifies the leaf NOFOLLOW."
  [root-canonical components operation]
  (let [parent (descend root-canonical (butlast components) operation)
        leaf (last components)
        abs (str parent "/" leaf)
        kind (cond
               (not (fs/exists? abs {:nofollow-links true})) :absent
               (fs/sym-link? abs) :symlink
               (fs/directory? abs {:nofollow-links true}) :directory
               (fs/regular-file? abs {:nofollow-links true}) :file
               :else :other)]
    [parent leaf kind]))

(defn- relative-name
  "The canonical relative name results carry: the lexically normalized
   components rejoined, exactly the path the model's write will be known by."
  [components]
  (str/join "/" components))

;; ═══════════════════════════════════════════════════════════════════════════
;; Digest — a coordinate is computed or the operation fails
;; ═══════════════════════════════════════════════════════════════════════════

(defn- file-digest-str
  "The file's sha256:… content coordinate, read through the bounded reader.
   Fail-closed like the frozen contract: a digest that cannot be computed
   (unreadable, over the byte bound, no digest machinery) FAILS the caller
   with {:samizdat.sandbox/error :digest-failed} — it never becomes nil or a
   fake coordinate an anchored write could be tempted to trust."
  [abs-path max-bytes]
  (try
    (str "sha256:" (files/file-digest abs-path max-bytes))
    (catch Throwable failure
      (fail! :digest-failed
             "The content digest could not be computed; refusing to invent one"
             {:cause (str failure)}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Operation descriptors — the frozen bb4t A2/A3b contract, projected
;; ═══════════════════════════════════════════════════════════════════════════

(defn- make-read-op
  "project/read — one bounded UTF-8 file read.  Returns the decoded string.

   Exactly the frozen contract: a regular file only (a symlink is refused,
   not followed, whether it points in or out), bytes consumed only up to the
   derived byte ceiling, strict UTF-8 decoding (invalid input fails, never
   replaces), and the character bound fails rather than truncates — a bound
   the caller can rely on naming everything it got."
  [root-canonical max-chars]
  (let [max-bytes (read-byte-ceiling max-chars)]
    {:id :project/read :name 'read :effect :observation
     :fn (fn [rel-path]
           (let [components (lexical-relative root-canonical rel-path
                                              :project/read false)]
             (when (empty? components)
               (fail! :not-file "project/read target is not a regular file"
                      {:path rel-path}))
             (let [[parent leaf kind] (target-of root-canonical components
                                                 :project/read)]
               (when-not (= :file kind)
                 (fail! :not-file "project/read target is not a regular file"
                        {:path rel-path :kind kind}))
                (let [abs (str parent "/" leaf)]
                  (try
                    (let [bytes (files/read-bounded-bytes abs max-bytes)
                          content (files/decode-utf8 bytes)]
                      (when (> (count content) max-chars)
                        (fail! :too-large
                               "File content exceeds the character limit"
                               {:path rel-path :limit max-chars}))
                      content)
                    (catch Throwable failure
                      (raise-files-failure failure)))))))}))

(defn- make-list-op
  "project/list — one directory level, as inert structured entries.

   Exactly the frozen contract: exactly one relative path ('.' for the root),
   the IMMEDIATE entries only (never recursive), each a {:name :kind} map
   with :bytes on files, :kind one of :file/:directory/:symlink/:other,
   sorted by name, attributes read NOFOLLOW so a link is a link.  More
   entries than the bound fails rather than truncating.  Returns the entries
   vector."
  [root-canonical max-entries]
  {:id :project/list :name 'list :effect :observation
   :fn (fn [rel-path]
         (let [components (lexical-relative root-canonical rel-path
                                            :project/list true)
               dir (descend root-canonical components :project/list)
               kind (cond
                      (not (fs/exists? dir {:nofollow-links true})) :absent
                      (fs/sym-link? dir) :symlink
                      (fs/directory? dir {:nofollow-links true}) :directory
                      :else :other)]
           (cond
             (= :symlink kind)
             (fail! :symlink "project/list will not follow a symbolic link"
                    {:path rel-path})

             (= :absent kind)
             (fail! :not-found "project/list target does not exist"
                    {:path rel-path})

             (not= :directory kind)
             (fail! :not-found "project/list target is not a directory"
                    {:path rel-path})

              :else (try
                      (files/list-one-level dir max-entries)
                      (catch Throwable failure
                        (raise-files-failure failure))))))})

(defn- make-search-op
  "project/search — bounded regex search.  Returns a vector of
   {:path :line :text} maps ordered by path.

   Exactly the frozen contract: a bounded pattern (≤ 200 chars) that must
   compile, an optional options map {:path \"sub\" :include-hidden? true};
   dot-entries skipped unless included; symlinks never followed; files that
   are not valid UTF-8 skipped; a file larger than the per-file byte bound
   skipped with its bytes never consumed (the size is checked first); more
   regular files than :search-max-files FAILS the search; collection stops
   at :max-search-results.  Matched text is trimmed and capped."
  [root-canonical max-results max-chars max-files max-read-chars]
  (let [max-file-bytes (read-byte-ceiling max-read-chars)]
    {:id :project/search :name 'search :effect :observation
     :fn
     (fn [& args]
       (let [[pattern options] args]
         (when-not (and (<= 1 (count args) 2)
                        (string? pattern)
                        (not (str/blank? pattern))
                        (<= (count pattern) max-search-pattern-chars)
                        (or (nil? options) (map? options)))
           (fail! :invalid-arguments
                  "project/search expects a bounded pattern and an optional options map"
                  {:operation/id :project/search}))
         (let [rel-path (or (:path options) ".")]
           (when-not (and (string? rel-path) (not (str/blank? rel-path))
                          (<= (count rel-path) max-path-chars))
             (fail! :invalid-path
                    "project/search :path must be a bounded relative path"
                    {:path rel-path}))
           (let [components (lexical-relative root-canonical rel-path
                                              :project/search true)
                 dir (descend root-canonical components :project/search)
                 kind (cond
                        (not (fs/exists? dir {:nofollow-links true})) :absent
                        (fs/sym-link? dir) :symlink
                        (fs/directory? dir {:nofollow-links true}) :directory
                        :else :other)
                 re (try (re-pattern pattern)
                         (catch Throwable _
                           (fail! :invalid-regex
                                  "project/search pattern is not a valid regex"
                                  {:pattern pattern})))]
             (cond
               (= :symlink kind)
               (fail! :symlink "project/search will not follow a symbolic link"
                      {:path rel-path})

               (= :absent kind)
               (fail! :not-found "project/search :path does not exist"
                      {:path rel-path})

               (not= :directory kind)
               (fail! :not-found "project/search :path is not a directory"
                      {:path rel-path})

                :else
                (try
                  (files/search-tree dir (relative-name components) re
                                     {:max-results max-results
                                      :max-file-bytes max-file-bytes
                                      :max-files max-files
                                      :include-hidden? (true? (:include-hidden? options))
                                      :max-chars max-chars
                                      :max-line-chars max-search-line-chars})
                  (catch Throwable failure
                    (raise-files-failure failure))))))))}))

(defn- make-stat-op
  "project/stat — deterministic coordinate of one path.

   Exactly the frozen contract: {:path :kind :bytes :digest} for a regular
   file, {:path :kind} for a directory/symlink/other, {:path :kind :absent}
   when it does not exist.  The digest is computed through the bounded
   reader and FAILS on error (unreadable, over the bound, no machinery) — a
   regular file's coordinate never carries nil, because that is a fake
   coordinate, not an absent one."
  [root-canonical max-read-chars]
  (let [max-bytes (read-byte-ceiling max-read-chars)]
    {:id :project/stat :name 'stat :effect :observation
     :fn (fn [rel-path]
           (let [components (lexical-relative root-canonical rel-path
                                              :project/stat false)]
             (when (empty? components)
               (fail! :not-file "project/stat target is the project root, not a file"
                      {:path rel-path}))
             (let [[parent leaf kind] (target-of root-canonical components
                                                 :project/stat)
                   rel (relative-name components)]
               (case kind
                 :absent {:path rel :kind :absent}
                 :file {:path rel :kind :file
                        :bytes (try (files/nofollow-size (str parent "/" leaf))
                                    (catch Throwable failure
                                      (fail! :digest-failed
                                             "The file size could not be read; refusing to invent a coordinate"
                                             {:path rel :cause (str failure)})))
                        :digest (file-digest-str (str parent "/" leaf) max-bytes)}
                 {:path rel :kind kind}))))}))

(defn- make-edit-op
  "project/edit — anchored replacement of one regular file's contents.

   JS1 projection of the frozen contract's edit (positional args, the JS1
   receipt convention; bb4t spells the same fields as one options map):

     (project/edit rel-path base new-content)

   base is :absent (or nil) to CREATE — the leaf must be the only thing
   absent, so a missing parent FAILS and no directory is ever created — or
   the file's current sha256:… digest from project/stat: an update states
   which version it believed, and a file that exists with a different
   digest, that does not exist, or whose base was :absent while it exists,
   is a CONFLICT, never a blind overwrite.  The write goes through a
   sibling temporary and an atomic rename, so a reader never sees a partial
   file and a failed write leaves the original intact.  Content over the
   write byte bound fails.  Returns {:path :bytes :digest} of the new
   content."
  [root-canonical max-write-bytes max-read-chars]
  (let [max-bytes (read-byte-ceiling max-read-chars)]
    {:id :project/edit :name 'edit :effect :actuation
     :fn (fn [rel-path base-digest new-content]
           (let [components (lexical-relative root-canonical rel-path
                                              :project/edit false)]
             (when (empty? components)
               (fail! :not-file "project/edit target is the project root, not a file"
                      {:path rel-path}))
             (when-not (string? new-content)
               (fail! :invalid-arguments
                      "project/edit :content must be a string"
                      {:path rel-path}))
             (let [creating? (or (nil? base-digest) (= :absent base-digest))]
               (when-not (or creating? (string? base-digest))
                 (fail! :invalid-base-digest
                        (str "project/edit :base must be :absent or the file's "
                             "sha256:… digest; an edit without a base coordinate "
                             "is a blind overwrite")
                        {:path rel-path :base-digest (str base-digest)}))
               (let [encoded (files/utf8-bytes new-content)]
                 (when (> (alength encoded) max-write-bytes)
                   (fail! :write-limit
                          "project/edit content exceeds the write byte limit"
                          {:limit max-write-bytes
                           :bytes (alength encoded)}))
                 (let [[parent leaf kind] (target-of root-canonical components
                                                     :project/edit)
                       abs (str parent "/" leaf)
                       rel (relative-name components)]
                   (cond
                     (contains? #{:symlink :directory :other} kind)
                     (fail! :not-file "project/edit target is not a regular file"
                            {:path rel :kind kind})

                     ;; Creation: the leaf must be the ONLY thing absent.  A
                     ;; missing parent fails here through the descent above;
                     ;; no hierarchy is ever materialized.
                     (and creating? (not= :absent kind))
                     (fail! :already-exists
                            "project/edit conflict: file exists but base was :absent"
                            {:path rel
                             :conflict/observed (when (= :file kind)
                                                 (file-digest-str abs max-bytes))
                             :bbagent/conflict true})

                     (and (not creating?) (= :absent kind))
                     (fail! :not-found
                            "project/edit conflict: file does not exist"
                            {:path rel
                             :conflict/expected base-digest
                             :bbagent/conflict true})

                     :else
                     (do
                       (when (and (not creating?) (= :file kind))
                         (let [observed (file-digest-str abs max-bytes)]
                           (when-not (= base-digest observed)
                             (fail! :stale-conflict
                                    "project/edit conflict: file changed since it was read"
                                    {:path rel
                                     :conflict/expected base-digest
                                     :conflict/observed observed
                                     :bbagent/conflict true}))))
                       (files/atomic-write-file! abs encoded (not creating?))
                       {:path rel
                        :bytes (alength encoded)
                        :digest (str "sha256:" (files/bytes-digest encoded))})))))))}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Operation construction
;; ═══════════════════════════════════════════════════════════════════════════

(defn- build-operations
  "Build the five semantic operation descriptors from the resolved bounds."
  [root-canonical {:keys [max-read-chars max-list-entries
                           max-search-results search-max-chars
                           search-max-files max-write-bytes]}]
  [(make-read-op root-canonical (or max-read-chars default-max-read-chars))
   (make-list-op root-canonical (or max-list-entries default-max-list-entries))
   (make-search-op root-canonical
                   (or max-search-results default-max-search-results)
                   (or search-max-chars default-search-max-chars)
                   (or search-max-files default-search-max-files)
                   (or max-read-chars default-max-read-chars))
   (make-stat-op root-canonical (or max-read-chars default-max-read-chars))
   (make-edit-op root-canonical
                 (or max-write-bytes default-max-write-bytes)
                 (or max-read-chars default-max-read-chars))])

(def ^:private operation-docs
  "Host-owned inert documentation for the five projected operations — the
    same knowledge the constructors above encode, kept beside them so safe
    discovery (operation-doc) can describe authority without evaluating any
    form inside the sandbox.  Mirrors the frozen bb4t A2/A3b contract."
  {:project/read
   {:arglists '([rel-path])
    :doc (str "Read one UTF-8 file relative to the authorized project root. "
              "Reads at most the context's byte and character bounds and "
              "fails when they are exceeded; invalid UTF-8 fails.  A "
              "symbolic link is refused, not followed.  Returns the decoded "
              "string.")}
   :project/list
   {:arglists '([rel-path])
    :doc (str "List entries directly under one directory relative to the "
              "authorized project root (\".\" for the root itself).  Returns "
              "a vector of {:name :kind} sorted by name, where :kind is "
              ":file, :directory, :symlink or :other, and files also carry "
              ":bytes.  Exactly one level; does not follow symbolic links; "
              "more entries than the bound fails.")}
   :project/search
   {:arglists '([pattern] [pattern options])
    :doc (str "Search file contents under the authorized project root for a "
              "regular expression (at most 200 characters).  Returns a "
              "vector of {:path :line :text} ordered by path.  Options: "
              "{:path \"subdir\"} to search one subtree, {:include-hidden? "
              "true} to include dot-entries, which are skipped by default. "
              "Does not follow symbolic links, skips files that are not "
              "valid UTF-8, skips files larger than the per-file byte bound "
              "without reading them, and fails past its file limit.")}
   :project/stat
   {:arglists '([rel-path])
    :doc (str "Report {:path :kind :bytes :digest} for a file under the "
              "authorized project root, {:path :kind} for a directory, "
              "symbolic link or other entry, or {:path :kind :absent} when "
              "it does not exist.  The :digest is the coordinate "
              "project/edit requires as its base; if it cannot be computed "
              "the operation fails rather than returning a fake one.")}
   :project/edit
   {:arglists '([rel-path base new-content])
    :doc (str "Replace one file's contents under the authorized project "
              "root.  base is the file's current sha256:… digest from "
              "project/stat, or :absent to create a file that must not "
              "already exist.  Fails as a conflict — never overwrites — "
              "when the file changed since that digest, exists against "
              ":absent, or is missing against a digest.  Creates no "
              "directories: the leaf must be the only thing absent.  Writes "
              "through a temporary and renames atomically, so a reader "
              "never sees a partial file.  Returns {:path :bytes :digest} "
              "of the new content.")}})

;; ═══════════════════════════════════════════════════════════════════════════
;; EvaluatorSpec — inert, self-certifying coordinate
;; ═══════════════════════════════════════════════════════════════════════════

(defn- spec-coordinate
  "Deterministic content coordinate for an inert EvaluatorSpec: the JS0
   canonical form recomputed over the spec (sans :spec/coordinate),
   retagged for the JS1 seam.  The coordinate covers preset, profile,
   canonical root, capabilities, bounds, and timeout.  It is
   self-certifying: instantiate!/acquire! recompute and compare before
   allocating authority."
  [inert-spec]
  (let [c (sandbox/canonical-coordinate (dissoc inert-spec :spec/coordinate))]
    (when-not (str/starts-with? c "js0:")
      (throw (ex-info "Unexpected JS0 canonical coordinate form"
                      {:samizdat.sandbox/error :coordinate})))
    (str "js1:" (subs c 4))))

(defn- require-positive-int! [label v]
  (when-not (and (integer? v) (pos? v))
    (throw (ex-info (str label " must be a positive integer, got: " (pr-str v))
                    {:samizdat.sandbox/error :invalid-bound
                     :value (pr-str v)})))
  v)

(defn- resolve-bounds
  "Validate the six bound options (defaults applied).  Fail-closed: every
   bound must be a positive integer."
  [{:keys [max-read-chars max-list-entries max-search-results search-max-chars
           search-max-files max-write-bytes]}]
  (let [bounds {:max-read-chars (or max-read-chars default-max-read-chars)
                :max-list-entries (or max-list-entries default-max-list-entries)
                :max-search-results (or max-search-results default-max-search-results)
                :search-max-chars (or search-max-chars default-search-max-chars)
                :search-max-files (or search-max-files default-search-max-files)
                :max-write-bytes (or max-write-bytes default-max-write-bytes)}]
    (doseq [[k v] bounds]
      (require-positive-int! (str "bound " (name k)) v))
    bounds))

(defn- resolve-timeout
  [timeout-ms]
  (let [timeout (or timeout-ms default-timeout-ms)]
    (when-not (and (integer? timeout) (not (neg? timeout)))
      (throw (ex-info "timeout-ms must be a non-negative integer"
                      {:samizdat.sandbox/error :invalid-timeout
                       :timeout-ms (pr-str timeout)})))
    timeout))

(defn spec
  "Resolve an inert EvaluatorSpec from a trusted named preset (controller
   API; the closed catalog is `presets`).

   Required opts:
     :root — trusted host root directory (string)

    Optional controller attenuation / bounds:
      :capabilities — subset of the preset's capabilities (default: the
                      preset's exact set).  Anything beyond the preset is a
                      fail-closed :over-request.
      :max-read-chars / :max-list-entries / :max-search-results /
      :search-max-chars / :search-max-files / :max-write-bytes — bounds
                      (positive integers; defaults apply)
      :timeout-ms — cooperative interrupt ceiling (non-negative integer;
                    0 disables; default 30000)

   The returned map is fully inert (jolt.sandbox receipt domain): no
   functions, atoms, or host values.  :capabilities is a sorted vector;
   :spec/coordinate is the deterministic js1: content coordinate."
  [preset-key {:keys [root capabilities timeout-ms] :as opts}]
  (let [preset (get presets preset-key)]
    (when-not preset
      (throw (ex-info "Unknown sandbox preset"
                      {:samizdat.sandbox/error :unknown-preset
                       :preset preset-key})))
    (when-not (and (string? root) (not (str/blank? root)))
      (throw (ex-info "EvaluatorSpec requires a trusted host :root"
                      {:samizdat.sandbox/error :missing-root})))
    (let [preset-caps (:capabilities preset)
          caps (if (nil? capabilities)
                 preset-caps
                 (let [c (set capabilities)
                       excess (remove preset-caps c)]
                   (when (seq excess)
                     (throw (ex-info "Controller-selected capabilities exceed the preset"
                                     {:samizdat.sandbox/error :over-request
                                      :preset preset-key
                                      :excess (vec (sort-by str excess))})))
                   c))
          bounds (resolve-bounds opts)
          timeout (resolve-timeout timeout-ms)
          base {:samizdat.sandbox/kind :evaluator-spec
                :preset preset-key
                :profile (:profile preset)
                :root (resolve-root root)
                :capabilities (vec (sort-by str caps))
                :bounds bounds
                :timeout-ms timeout}]
      (assoc base :spec/coordinate (spec-coordinate base)))))

(defn- verify-spec!
  "Fail-closed integrity check: an EvaluatorSpec must be of the right kind
   and its coordinate must recompute exactly.  The coordinate covers root,
   bounds, timeout and capabilities, so a hand-edited spec cannot smuggle
   authority past this gate."
  [spec]
  (when-not (and (map? spec)
                 (= :evaluator-spec (:samizdat.sandbox/kind spec))
                 (string? (:spec/coordinate spec))
                 (= (:spec/coordinate spec) (spec-coordinate spec)))
    (throw (ex-info "Invalid or non-canonical EvaluatorSpec"
                    {:samizdat.sandbox/error :invalid-spec})))
  spec)

;; ═══════════════════════════════════════════════════════════════════════════
;; Context and Instance allocation
;; ═══════════════════════════════════════════════════════════════════════════

(defn- instrument-operation
  "Give one operation a controller-owned, dynamically installed effect hook.
   The hook is absent for the ordinary JS1 API.  Recorded evaluation installs
   it only while it owns the instance, so unchanged callers still use the
   normal Jolt operation API and pay no durable-store dependency at load time."
  [effect-hook operation]
  (let [op-fn (:fn operation)]
    (assoc operation :fn
           (fn [& args]
             (if-let [hook @effect-hook]
               (hook operation (vec args))
               (apply op-fn args))))))

(defn- context-from-spec
  "Allocate the live jolt.sandbox state for a verified inert EvaluatorSpec.
   All five semantic ops are projected (fixture presence); the per-dispatch
   recheck in jolt.sandbox gates them down to the spec's capabilities."
  [spec]
  (verify-spec! spec)
  (let [root-canonical (:root spec)
        effect-hook (atom nil)
        operations (mapv (fn [operation]
                           (instrument-operation effect-hook operation))
                         (build-operations root-canonical (:bounds spec)))
        caps (set (:capabilities spec))
        state (sandbox/create-context
                {:operations operations
                 :profile (:profile spec)
                 :requested-capabilities caps
                 :authorized-capabilities caps})]
    {:root-canonical root-canonical
     :operations operations
     ::state (atom state)
     ::effect-hook effect-hook
     ;; A hook changes how every effect in a persistent SCI context is
     ;; dispatched.  Evaluation ownership therefore belongs to the instance,
     ;; not to a caller thread or a DB record.  Contention fails closed rather
     ;; than letting an unrecorded evaluation pass through somebody else's
     ;; hook.
     ::evaluation-owner (atom nil)
     ;; Commit-only discipline: when a failed/interrupted recorded evaluation
     ;; cannot be rolled back to the durable committed state, the live context
     ;; is no longer a state the history can reconstruct.  The instance is
     ;; poisoned and refuses further evaluation; only a rebuild (which forks a
     ;; fresh, unpoisoned context) cures it.
     ::poisoned (atom nil)}))

(defn instantiate!
  "Allocate a live Instance from an inert EvaluatorSpec (controller API).

   opts:
     :instance/key — stable key naming the instance (default :standalone)

   The instance id is stable and deterministic: \"inst:\" (name key).  It
   survives rebuild! (which returns a fresh value; the provider registry
   is updated so later acquire!/bind! calls see the rebuilt instance).
   Give distinct keys to distinct coexisting instances (the provider
   enforces this by keying its registry on :instance/key); two standalone
   instances share the id :standalone."
  ([spec] (instantiate! spec nil))
  ([spec {:keys [instance/key] :or {key :standalone}}]
   (let [c (context-from-spec spec)]
      {:samizdat.sandbox/kind :instance
       :instance/key key
        :instance/id (str "inst:" (name key))
        :spec spec
        :root-canonical (:root-canonical c)
        :timeout-ms (:timeout-ms spec)
        :operations (:operations c)
        ::state (::state c)
        ::effect-hook (::effect-hook c)
        ::evaluation-owner (::evaluation-owner c)
        ::poisoned (::poisoned c)})))

;; ═══════════════════════════════════════════════════════════════════════════
;; Provider — JS1 acquire/bind policy
;; ═══════════════════════════════════════════════════════════════════════════

(defn provider
  "Create a JS1 evaluator provider (controller API).

   opts:
     :root — default trusted host root for bind!/acquire! specs

   JS1 policy: ordinary work binds deterministically to one persistent
   instance key, :main.  A controller that needs isolation passes a
   distinct :instance/key and gets a separate live context."
  ([] (provider nil))
  ([{:keys [root]}]
   {:samizdat.sandbox/kind :provider
    :provider/default-root root
    ::registry (atom {:instances {} :bindings {}})}))

(defn- registry-acquire-instance
  "Registry update for acquire!: get or create the instance for key.
   Fail-closed on spec conflict.  Top-level (values in, new registry out)
   so the swap! closure stays flat — see note in bind!."
  [r key spec reg]
  (if-let [inst (get-in r [:instances key])]
    (do (when-not (= (:spec/coordinate (:spec inst))
                     (:spec/coordinate spec))
          (throw (ex-info "Instance spec conflict: instance key already holds a different EvaluatorSpec"
                          {:samizdat.sandbox/error :instance-spec-conflict
                           :instance/key key})))
        r)
    (assoc-in r [:instances key]
              (assoc (instantiate! spec {:instance/key key})
                     ::registry reg))))

(defn acquire!
  "Get or create the provider instance for :instance/key (default :main)
   from an inert EvaluatorSpec.  An existing instance is returned only
   when its spec coordinate matches exactly; a mismatch throws
   {:samizdat.sandbox/error :instance-spec-conflict} — never a silent
   widening or rebinding."
  ([provider spec] (acquire! provider spec nil))
  ([provider spec {:keys [instance/key] :or {key :main}}]
   (verify-spec! spec)
   (let [reg (::registry provider)]
     (when-not reg
       (throw (ex-info "acquire! needs a samizdat.agent.sandbox provider"
                       {:samizdat.sandbox/error :not-a-provider})))
     (swap! reg (fn [r] (registry-acquire-instance r key spec reg)))
     (get-in @reg [:instances key]))))

(defn- registry-bind-work
  "Registry update for bind!: idempotent per work-id — an existing binding
   is kept when instance key and spec coordinate match, otherwise a
   fail-closed conflict."
  [r work-id entry]
  (if-let [existing (get-in r [:bindings work-id])]
    (do (when-not (and (= (:instance/key entry) (:instance/key existing))
                       (= (:spec/coordinate (:spec entry))
                          (:spec/coordinate (:spec existing))))
          (throw (ex-info "Binding conflict: work-id already bound with a different instance key or spec"
                          {:samizdat.sandbox/error :binding-conflict
                           :work-id (pr-str work-id)})))
        r)
    (assoc-in r [:bindings work-id] entry)))

(defn bind!
  "Bind work-id to an instance acquired from a trusted named preset
   (controller API).  Model code never calls this: it receives bindings.

   Required opts:
     :preset — a key of `presets` (the closed trusted catalog)
     :root   — trusted host root (or the provider's default root)

   Optional:
     :instance/key — default :main.  JS1 policy: all ordinary work shares
                     the persistent :main instance; a distinct key yields
                     an isolated instance.
     :capabilities — controller-selected subset of the preset's
                     capabilities (attenuation only)
     bounds/timeout opts as in `spec`

   Idempotent per work-id: rebinding the same work-id with an equal key
   and spec returns the existing binding; a conflicting key or spec throws
   {:samizdat.sandbox/error :binding-conflict}.

   Returns the live Binding map: inert :binding/id, :work-id,
   :instance/key, :instance/id and :spec, plus the live instance ref."
  [provider work-id {:keys [preset instance/key root] :as opts}]
  (let [key (or key :main)
        root (or root (:provider/default-root provider))
        sp (spec preset (assoc opts :root root))
        inst (acquire! provider sp {:instance/key key})
        reg (::registry provider)
        entry {:samizdat.sandbox/kind :binding
               :binding/id (str "bind:" (name key) ":" (str work-id))
               :work-id work-id
               :instance/key key
               :instance/id (:instance/id inst)
               :spec sp
               ::instance inst
               ::registry reg}]
    ;; Flat swap! closures only: a let-bound local captured under
    ;; fn > if-let > let currently breaks resolution of the fn's other
    ;; locals in Jolt's analyzer, so registry updates are top-level fns.
    (swap! reg (fn [r] (registry-bind-work r work-id entry)))
    (get-in @reg [:bindings work-id])))

;; ═══════════════════════════════════════════════════════════════════════════
;; Public API
;; ═══════════════════════════════════════════════════════════════════════════

(defn new
  "Create a bounded JS1 project sandbox context directly (legacy API;
   prefer the preset/spec/provider seam for new code).

   Required opts:
     :root    — trusted host root directory (string)
     :profile — :agent/minimal, :agent/project-read, or
                :agent/project-develop

    Optional opts:
      :authorized-capabilities — explicit subset of the profile's maximum
                                 (default: the profile maximum itself)
      :max-read-chars    — bounded read limit (default 60000)
      :max-list-entries  — bounded list limit (default 1000)
      :max-search-results — bounded search result limit (default 500)
      :search-max-chars  — bounded search scan limit (default 500000)
      :search-max-files  — search file-count limit (default 20000)
      :max-write-bytes   — edit write byte limit (default 1048576)
      :timeout-ms        — cooperative interrupt ceiling (default 30000)

   Returns an opaque context map for use with evaluate!, rebuild!,
   describe, and capabilities."
  [{:keys [root profile timeout-ms authorized-capabilities] :as opts}]
  (when-not profile
    (throw (ex-info "new requires an explicit :profile"
                    {:samizdat.sandbox/error :missing-profile})))
  (let [profile-data (get sandbox/profiles profile)]
    (when-not profile-data
      (throw (ex-info "Unknown profile"
                      {:samizdat.sandbox/error :unknown-profile
                       :profile profile})))
    (let [max-caps (:profile/max-capabilities profile-data)
          caps (set (or authorized-capabilities max-caps))
          excess (remove max-caps caps)]
      (when (seq excess)
        (throw (ex-info "Authorized capabilities exceed profile maximum"
                        {:samizdat.sandbox/error :profile-exceeded
                         :profile profile
                         :excess (vec (sort-by str excess))})))
      ;; An ad-hoc inert spec through the same coordinate machinery, so
      ;; describe is uniform.  No :preset key — distinguishable from
      ;; catalog-minted specs.
      (let [base {:samizdat.sandbox/kind :evaluator-spec
                  :profile profile
                  :root (resolve-root root)
                  :capabilities (vec (sort-by str caps))
                  :bounds (resolve-bounds opts)
                  :timeout-ms (resolve-timeout timeout-ms)}
            sp (assoc base :spec/coordinate (spec-coordinate base))
            c (context-from-spec sp)]
        {:samizdat.sandbox/kind :context
         :root root
         :root-canonical (:root-canonical c)
         :profile profile
         :timeout-ms (:timeout-ms sp)
         :operations (:operations c)
         :spec sp
         ::state (::state c)
         ::effect-hook (::effect-hook c)
         ::evaluation-owner (::evaluation-owner c)
         ::poisoned (::poisoned c)}))))

(def ^:private authority-selection-keys
  "evaluate! is the model-facing entry: authority was fixed when the
   controller minted the spec/instance/binding, so any profile, preset,
   capability, root, or instance-key option here is a selection attempt
   and is rejected rather than ignored."
  #{:profile :preset :capabilities :authorized-capabilities
    :requested-capabilities :instance/key :root})

(def ^:dynamic *eval-store*
  "Optional trusted durable-eval adapter.  Production leaves this nil and the
   bridge dynamically resolves samizdat.store.evals, keeping this namespace
   directly loadable on the small Jolt+SCI classpath.  A controller/test may
   bind a map containing :begin!, :record-intent!, :record-outcome!,
   :complete!, :load-eval, :verify-binding!, and :history to supply the same
   contract."
  nil)

(def ^:private eval-store-symbols
  {:begin! 'samizdat.store.evals/begin!
   :record-intent! 'samizdat.store.evals/record-intent!
   :record-outcome! 'samizdat.store.evals/record-outcome!
   :complete! 'samizdat.store.evals/complete!
   :load-eval 'samizdat.store.evals/load-eval
   :verify-binding! 'samizdat.store.evals/verify-binding!
   :history 'samizdat.store.evals/history})

(defn- eval-store
  "Resolve the complete durable-store surface at call time.  Resolution is
   all-or-nothing: a partial/old store cannot silently produce a record that
   this bridge would later be unable to verify."
  []
  (if *eval-store*
    (do
      (doseq [k (keys eval-store-symbols)]
        (when-not (ifn? (get *eval-store* k))
          (throw (ex-info "Incomplete durable evaluation store adapter"
                          {:samizdat.sandbox/error :incomplete-eval-store
                           :missing k}))))
      *eval-store*)
    (into {}
          (map (fn [[k sym]]
                 (let [f (try
                           (requiring-resolve sym)
                           (catch Throwable e
                             (throw (ex-info
                                     (str "Durable evaluation store function is unavailable: " sym)
                                     {:samizdat.sandbox/error :eval-store-unavailable
                                      :symbol sym}
                                     e))))]
                   (when-not (ifn? f)
                     (throw (ex-info "Resolved durable evaluation store value is not callable"
                                     {:samizdat.sandbox/error :invalid-eval-store
                                      :symbol sym})))
                   [k f])))
          eval-store-symbols)))

(defn- evaluation-target
  [x label]
  (let [target (if (::instance x) (::instance x) x)]
    (when-not (and (::state target)
                   (::effect-hook target)
                   (::evaluation-owner target)
                   (::poisoned target))
      (throw (ex-info (str label " needs a sandbox context, instance, or binding")
                      {:samizdat.sandbox/error :not-a-context})))
    target))

(defn- check-not-poisoned!
  "Evaluation is refused on a poisoned instance: its live context is known not
   to be the committed state, and only a rebuild (which forks a fresh context)
   may follow a failed rollback.  Rebuild paths deliberately do NOT call this
   — they are the cure."
  [target label]
  (when @(::poisoned target)
    (throw (ex-info (str label " refused: the instance is poisoned because a"
                         " failed evaluation could not be rolled back to its"
                         " committed state; rebuild it from durable history"
                         " before evaluating")
                    {:samizdat.sandbox/error :instance-poisoned
                     :instance/id (:instance/id target)}))))

(defn- check-current-binding!
  "Fail closed when `binding` has been superseded in its provider registry by
   a rebuild/rollback.  A recorded evaluation must run on the instance the
   registry currently publishes: the durable record names stable
   spec/instance/binding ids, so source evaluated on a superseded context
   would append receipts a whole-history rebuild cannot have produced."
  [binding]
  (when-let [reg (::registry binding)]
    (let [registered (get-in @reg [:bindings (:work-id binding)])]
      (when (and registered
                 (not= (::state (::instance registered))
                       (::state (::instance binding))))
        (throw (ex-info "Binding has been superseded by a rebuild; re-acquire it from the provider"
                        {:samizdat.sandbox/error :stale-binding
                         :binding/id (:binding/id binding)}))))))

(defn- reject-authority-selection!
  [label opts]
  (let [bad (filterv authority-selection-keys (keys (or opts {})))]
    (when (seq bad)
      (throw (ex-info (str label " does not accept authority selection; authority is fixed at bind time")
                      {:samizdat.sandbox/error :authority-selection-forbidden
                       :keys bad})))))

(defn- claim-evaluation!
  [target]
  (let [owner (::evaluation-owner target)
        claim (str (random-uuid))]
    (when-not (compare-and-set! owner nil claim)
      (throw (ex-info "Sandbox instance already has an evaluation in progress"
                      {:samizdat.sandbox/error :instance-busy
                       :instance/id (:instance/id target)})))
    claim))

(defn- release-evaluation!
  [target claim]
  ;; Never clear a different owner's claim.  That should be impossible, but a
  ;; poisoned ownership cell is safer than opening an effect hook to races.
  (compare-and-set! (::evaluation-owner target) claim nil))

(defn- evaluate-state!
  "Run source after ownership has been acquired.

   Ceiling composition: the spec's :timeout-ms is an unraiseable ceiling
   WHETHER OR NOT the caller supplies a token.  The guarded evaluation
   always runs under a PRIVATE per-evaluation token, interrupted by exactly
   one of:

     - the ceiling timer, at :timeout-ms from the eval's start — the strict
       timeout, reported as {:samizdat.sandbox/error :timeout}; or
     - the relay of the caller's token (e.g. a controller TurnLease's) — a
       revocation the caller performed, propagated as the raw Jolt
       interrupt and never relabeled :timeout: the caller that cancelled
       knows what it did.

   The `cause` cell linearizes the two: the first interruption to land owns
   the label, so a revocation noticed before the deadline is never
   misreported as a timeout.

   A caller-held token is NEVER interrupted by the spec's timer.  That is
   what eliminates the late-fire race: a caller token is shared across the
   turn's evaluations, so a timer wake landing after the guarded extent —
   in the gap between the eval's return and the disarm — would otherwise
   poison every later same-turn eval with a spurious interruption.  The
   wake now lands on a token that dies with this evaluation.  The ceiling
   still governs absolutely: nothing the caller holds can stretch the
   evaluation past :timeout-ms."
  [target source opts]
  (let [state-atom (::state target)
        timeout-ms (:timeout-ms target)
        token (:token opts)
        ceiling? (and timeout-ms (pos? timeout-ms))]
    (if-not ceiling?
      (sandbox/evaluate! @state-atom source token)
      (let [tok (jolt.host/make-interrupt)
            ;; nil | :ceiling | :caller — the first interrupter owns the
            ;; label the catch below reads.
            cause (atom nil)
            ;; Ends the watcher once the guarded extent has ended.  A wake
            ;; that already passed this check can only interrupt the private
            ;; token, which is dead by then.
            done (atom false)
            deadline (+ (System/currentTimeMillis) timeout-ms)
            watcher (fn []
                      (loop []
                        (let [ms-left (- deadline (System/currentTimeMillis))]
                          (cond
                            @done nil
                            ;; A caller revocation outranks the ceiling when
                            ;; both land between two polls: the canceller's
                            ;; act is reported as itself, never relabeled.
                            ;; Either fire ends the watch — the private token
                            ;; is set, so the guarded eval is already dying.
                            (and token (jolt.host/interrupted? token))
                            (do (compare-and-set! cause nil :caller)
                                (jolt.host/interrupt! tok))
                            (<= ms-left 0)
                            (do (compare-and-set! cause nil :ceiling)
                                (jolt.host/interrupt! tok))
                            :else
                            (do (Thread/sleep (min 5 (max 1 ms-left)))
                                (recur))))))
            _ (let [t (Thread. watcher)]
                (.setDaemon t true)
                (.start t))]
        (try
          (sandbox/evaluate! @state-atom source tok)
          (catch Throwable e
            (if (and (:jolt/interrupted (ex-data e)) (= :ceiling @cause))
              (throw (ex-info "Sandbox evaluation timed out"
                              {:samizdat.sandbox/error :timeout
                               :timeout-ms timeout-ms}
                              e))
              (throw e)))
          (finally
            (reset! done true)))))))

(defn evaluate!
  "Evaluate `source` in `x` — a context (new), an Instance, or a Binding.

    Optional opts:
      :token — a Jolt interrupt token (jolt.host/make-interrupt).
               When supplied, evaluation uses jolt.host/run-interruptible
               so the token's interruption is an unraiseable host ceiling.
               The spec's :timeout-ms ceiling STILL applies, and still
               composes over the caller's token: the evaluation runs under
               a private per-evaluation token that the ceiling timer and a
               caller-token relay interrupt, so a caller-held token (a
               model-side turn lease, say) can narrow but never extend
               evaluation past the authority fixed at bind time — and the
               caller's token itself is never fired by the spec's timer,
               so a completed evaluation cannot poison a later same-turn
               one sharing that token.  If no token is given and the
               target has a timeout, the ceiling still applies (same
               private-token mechanism, no relay).

   No authority selection: authority is fixed at construction/bind time.
   Passing any of :profile, :preset, :capabilities,
   :authorized-capabilities, :requested-capabilities, :instance/key, or
   :root throws {:samizdat.sandbox/error :authority-selection-forbidden}.

   The sandbox's jolt.sandbox/evaluate! runs SCI eval-string* with the
   token, which cooperatively checks the interrupt at safe points.  The
   interruption cannot be caught or suppressed from within SCI code."
  ([x source] (evaluate! x source nil))
  ([x source opts]
   (reject-authority-selection! "evaluate!" opts)
   (let [target (evaluation-target x "evaluate!")
         claim (claim-evaluation! target)]
     (try
       (check-not-poisoned! target "evaluate!")
       (evaluate-state! target source opts)
       (finally
         (release-evaluation! target claim))))))

(defn- require-binding!
  [binding label]
  (when-not (and (= :binding (:samizdat.sandbox/kind binding))
                 (::instance binding)
                 (string? (:binding/id binding))
                 (string? (:instance/id binding))
                 (string? (:spec/coordinate (:spec binding))))
    (throw (ex-info (str label " requires a controller-minted Binding")
                    {:samizdat.sandbox/error :not-a-binding})))
  binding)

(defn- binding-identity
  "The durable identity of a binding: the three stable ids plus the versioned
   RuntimeCoordinate of this process.  begin! stores all four (with the exact
   authority coordinate as the fifth durable field); verify-binding! and
   whole-history validation compare all five."
  [binding]
  {:spec-id (:spec/coordinate (:spec binding))
   :instance-id (:instance/id binding)
   :binding-id (:binding/id binding)
   :runtime (runtime-coordinate)})

(defn- current-coordinate
  [target]
  (sandbox/canonical-coordinate
   (sandbox/effective-authority @(::state target))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Bounded rendered final result
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const max-render-chars
  "Character bound on a rendered final result.  Only reached by non-inert
   values; inert final values are stored exactly and never truncated."
  8000)

(def ^:const render-print-length 100)
(def ^:const render-print-level 20)

(defn- result-record
  "The durable terminal result for an evaluation's final value.

   Receipt payloads stay strict (jolt.sandbox/inert) — a receipt that cannot
   round-trip as data is not a receipt.  The FINAL value of an evaluation is
   different: it is the answer, not an effect, and arbitrary SCI values are
   legitimate answers.  An inert final value is stored exactly under :value;
   any other value (functions, floats, seqs, host objects) is accepted by
   rendering it — printed under *print-length*/*print-level* bounds (an
   infinite or very deep value cannot hang or explode the rendering) and then
   character-bounded, with :render-truncated? recording whether the bound
   cut.  Rendering is deterministic for replay verification: the same value
   under the same RuntimeCoordinate renders to the same stored form, and a
   value that does not (e.g. one whose printed form carries identity) fails
   closed at replay instead of pretending a match."
  [value]
  (let [inert-attempt (try {:ok (sandbox/inert value)}
                           (catch Throwable _ nil))]
    (if (contains? inert-attempt :ok)
      {:value (:ok inert-attempt)}
      (let [rendered (try
                       (binding [*print-length* render-print-length
                                 *print-level* render-print-level]
                         (pr-str value))
                       (catch Throwable render-error
                         (str "#<unrenderable " (type render-error) ">")))
            truncated? (> (count rendered) max-render-chars)]
        {:rendered (if truncated? (subs rendered 0 max-render-chars) rendered)
         :render-truncated? truncated?}))))

(defn- result-matches?
  "Whether a replayed final `value` matches a durable record's terminal
   :result — exactly for inert values, rendering-compared otherwise.  A
   record with no structured result at all (nil) matches nothing."
  [record value]
  (and (some? (:result record))
       (= (sandbox/inert (:result record))
          (sandbox/inert (result-record value)))))

(defn- run-recorded-effect!
  "Persist intent, run exactly once, then persist exactly one outcome.  A
   failure to append the outcome is deliberately not converted into an error
   outcome: the existing intent stays unsettled because the real world's state
   is then unknown.

   When `effect-permit!` is supplied by the trusted JS1 tool route, it fences
   the durable intent append under the TurnLease monitor.  That append is the
   semantic operation's initiation: once it exists the ensuing bounded host
   operation is authorized even if revocation follows; if revocation won, the
   callback is never entered and neither receipt nor host operation exists.
   Only the intent append is synchronized — the host operation and outcome are
   deliberately outside the lease monitor."
  [store conn eval-id operation args effect-permit!]
  (let [id (:id operation)
        op-fn (:fn operation)
        record-intent (fn []
                        ((:record-intent! store) conn eval-id
                         {:op id :args (sandbox/inert args)}))
        intent (if effect-permit!
                 (effect-permit! record-intent)
                 (record-intent))
        outcome (try
                  {:ok true :value (apply op-fn args)}
                  (catch Throwable e
                    {:ok false :error e}))]
    (if (:ok outcome)
      (let [value (sandbox/inert (:value outcome))]
        ((:record-outcome! store) conn eval-id intent {:result value})
        value)
      (let [error (:error outcome)]
        ((:record-outcome! store) conn eval-id intent {:error (str error)})
        (throw error)))))

(defn- complete-failed-evaluation!
  [store conn eval-id error]
  ((:complete! store) conn eval-id
   {:status :failed :result {:error (str error)}}))

;; Defined in the whole-history rebuild section below; rollback after a
;; failed/interrupted recorded evaluation is the same operation.
(declare rebuild-binding-internal!)

(defn- rollback-to-committed!
  "Roll the instance back to the binding's durable committed state after a
   failed/interrupted recorded evaluation: rebuild from the whole committed
   history and publish the fresh instance.  The caller holds the evaluation
   claim, so the rebuild core runs without re-claiming.

   Commit-only discipline: definitions become evaluator state only by
   committing, so the state after a failure must be exactly the committed
   history, never whatever partial definitions the failed source happened to
   land.  If the rebuild cannot be completed, the live context is no longer
   a state the durable history can reconstruct: the instance is poisoned
   (further evaluation is refused until a rebuild cures it) and a
   :rollback-failed error naming the original evaluation error is thrown."
  [store conn binding target eval-id evaluation-error]
  (try
    (rebuild-binding-internal! store conn binding target)
    (catch Throwable rollback-error
      (reset! (::poisoned target) true)
      (throw (ex-info
              "Evaluation failed and rollback to the committed state failed; the instance is poisoned"
              {:samizdat.sandbox/error :rollback-failed
               :eval-id eval-id
               :evaluation-error (str evaluation-error)}
              rollback-error)))))

(defn evaluate-recorded!
  "Evaluate source in a controller-minted Binding while appending a durable
   JS1 record to trusted `conn`.

   The evaluation row (spec/instance/binding ids, exact current authority
   coordinate, versioned runtime coordinate, and source) lands before source
   runs.  Every semantic operation appends intent before touching the project
   and outcome afterward.  The terminal row is appended only after evaluation
   returns and all outcomes have settled.  Returns {:eval-id id :value value}.

   Commit-only evaluator state: a committed evaluation's definitions persist;
   a failed or interrupted evaluation leaves the instance rolled back —
   rebuilt from the binding's durable committed history and republished —
   before the original error propagates, so partial definitions of failed
   source never become evaluator state.

   The final value may be any SCI value: inert values are stored exactly,
   anything else is stored as a bounded rendering (see result-record).
   Receipt payloads remain strict.

   If an outcome append fails after an operation, completion also fails closed
   and the durable record remains pending with its unsettled intent.  If the
   :failed terminal row itself cannot be appended, the record likewise stays
   pending and the instance is poisoned: a pending record blocks whole-history
   rebuild, so the dirty context has no cure and must refuse evaluation.

   Trusted opts may carry :effect-permit!, a callback used only around each
   durable operation intent (the semantic launch boundary), never around SCI
   evaluation or the host operation's potentially long computation."
  ([conn binding source] (evaluate-recorded! conn binding source nil))
  ([conn binding source opts]
   (require-binding! binding "evaluate-recorded!")
   (reject-authority-selection! "evaluate-recorded!" opts)
   (let [store (eval-store)
         target (evaluation-target binding "evaluate-recorded!")
         claim (claim-evaluation! target)]
     (try
       (check-not-poisoned! target "evaluate-recorded!")
       (check-current-binding! binding)
       (let [identity (binding-identity binding)
             eval-id ((:begin! store) conn
                      (assoc identity
                             :coordinate (current-coordinate target)
                             :source source))
              hook (::effect-hook target)
              effect-permit! (:effect-permit! opts)]
         (reset! hook (fn [operation args]
                         (run-recorded-effect! store conn eval-id operation args
                                               effect-permit!)))
         (sandbox/set-mode! @(::state target) :normal)
         (try
           (let [outcome (try
                           (let [value (evaluate-state! target source opts)]
                             {:ok true :value value})
                           (catch Throwable evaluation-error
                             {:ok false :error evaluation-error}))]
             (if (:ok outcome)
               (do
                 ;; Completion persistence is not part of source evaluation.
                 ;; If it fails, do not attempt a contradictory :failed row;
                 ;; leave the original record pending.
                 ((:complete! store) conn eval-id
                  {:status :completed
                   :result (result-record (:value outcome))})
                 {:eval-id eval-id :value (:value outcome)})
               (let [evaluation-error (:error outcome)]
                 (try
                   (complete-failed-evaluation! store conn eval-id evaluation-error)
                   (catch Throwable completion-error
                     (reset! (::poisoned target) true)
                      (throw (ex-info
                             "Evaluation failed and its durable record could not be completed; the record remains pending and the instance is poisoned"
                             {:samizdat.sandbox/error :durable-evaluation-incomplete
                              :eval-id eval-id}
                             completion-error))))
                 (rollback-to-committed! store conn binding target
                                         eval-id evaluation-error)
                 (throw evaluation-error))))
           (finally
             (reset! hook nil))))
       (finally
         (release-evaluation! target claim))))))

(defn- receipt->jolt
  [known-ops expected-seq receipt]
  (when-not (= expected-seq (:seq receipt))
    (throw (ex-info "Durable receipts are not a contiguous operation sequence"
                    {:samizdat.sandbox/error :malformed-receipts
                     :expected-seq expected-seq
                     :actual-seq (:seq receipt)})))
  (when-not (contains? known-ops (:op receipt))
    (throw (ex-info "Durable receipt names an operation outside this instance"
                    {:samizdat.sandbox/error :unknown-recorded-operation
                     :op (:op receipt)})))
  (when-not (vector? (:args receipt))
    (throw (ex-info "Durable receipt arguments are not a vector"
                    {:samizdat.sandbox/error :malformed-receipts
                     :seq expected-seq})))
  (let [base {:op/id (:op receipt)
              :op/args (sandbox/inert (:args receipt))}]
    (case (:phase receipt)
      :done (assoc base :op/result (sandbox/inert (:result receipt)))
      :error (do
               (when-not (string? (:error receipt))
                 (throw (ex-info "Durable error receipt has no error message"
                                 {:samizdat.sandbox/error :malformed-receipts
                                  :seq expected-seq})))
               (assoc base :op/error (:error receipt)))
      :intent (throw (ex-info
                      "Durable evaluation contains an unsettled effect intent"
                      {:samizdat.sandbox/error :unsettled-recorded-effect
                       :seq expected-seq
                       :op (:op receipt)}))
      (throw (ex-info "Durable receipt has an unknown phase"
                      {:samizdat.sandbox/error :malformed-receipts
                       :seq expected-seq
                       :phase (:phase receipt)})))))

(defn- replay-receipts
  [target record]
  (let [known-ops (set (map :id (:operations target)))]
    (mapv (fn [[n receipt]] (receipt->jolt known-ops n receipt))
          (map-indexed vector (:receipts record)))))

(defn- fresh-target
  "Allocate an independent context from the same spec, attenuated to the
   source target's current authority.  It intentionally does not publish into
   a provider registry until replay has succeeded exactly.  The fresh context
   is never poisoned: a rebuild is the cure for a poisoned instance."
  [target]
  (let [c (context-from-spec (:spec target))
        old-state @(::state target)
        authorized (:authorized @old-state)
        fresh-state @(::state c)
        fresh (assoc target
                     :root-canonical (:root-canonical c)
                     :operations (:operations c)
                     ::state (::state c)
                     ::effect-hook (::effect-hook c)
                     ::evaluation-owner (::evaluation-owner c)
                     ::poisoned (::poisoned c))]
    (doseq [capability (remove authorized (set (:capabilities (:spec target))))]
      (sandbox/revoke! fresh-state capability))
    fresh))

(defn- registry-publish-recorded
  [r old-instance fresh-instance]
  (let [key (:instance/key old-instance)
        registered (get-in r [:instances key])]
    (when-not (= (::state registered) (::state old-instance))
      (throw (ex-info "Binding points at a superseded provider instance"
                      {:samizdat.sandbox/error :stale-binding
                       :instance/key key})))
    (assoc (assoc-in r [:instances key] fresh-instance)
           :bindings
           (into {}
                 (map (fn [[work-id binding]]
                        [work-id
                         (if (= key (:instance/key binding))
                           (assoc binding
                                  ::instance fresh-instance
                                  :instance/id (:instance/id fresh-instance))
                           binding)]))
                 (:bindings r)))))

(defn- publish-recorded-rebuild!
  [binding fresh-instance]
  (let [fresh-binding (assoc binding
                             ::instance fresh-instance
                             :instance/id (:instance/id fresh-instance))]
    (if-let [reg (::registry binding)]
      (do
        (swap! reg (fn [r]
                     (registry-publish-recorded r (::instance binding) fresh-instance)))
        (get-in @reg [:bindings (:work-id binding)]))
      fresh-binding)))

(defn rebuild-recorded!
  "Verify and replay completed durable evaluation `eval-id` into a fresh
   context for `binding`, returning the rebuilt Binding.

   Binding identity is verified by samizdat.store.evals before the record is
   loaded or any source can run.  Pending, failed, malformed, non-contiguous,
   or authority-coordinate-mismatched records fail closed.  During replay Jolt
   serves every observation and actuation from the ordered historical
   receipts; the real project operation functions are never called.  The fresh
   instance is published only after source result and receipt consumption both
   match exactly."
  [conn binding eval-id]
  (require-binding! binding "rebuild-recorded!")
  (let [store (eval-store)
        identity (binding-identity binding)
        target (evaluation-target binding "rebuild-recorded!")
        claim (claim-evaluation! target)]
    (try
      ;; This must remain the first store action and precede allocation/eval.
      ;; The identity carries all four durable fields the store compares,
      ;; including the exact current spec coordinate: without it the store's
      ;; verify-binding! sees a blank expected coordinate and denies every
      ;; replay, and with a stale one it must deny this one.
      ((:verify-binding! store) conn eval-id
       (assoc identity :coordinate (current-coordinate target)))
      (let [record ((:load-eval store) conn eval-id)]
        (when-not record
          (throw (ex-info "No durable evaluation record"
                          {:samizdat.sandbox/error :unknown-evaluation
                           :eval-id eval-id})))
        (when-not (= :completed (:status record))
          (throw (ex-info "Only a completed durable evaluation can be rebuilt"
                          {:samizdat.sandbox/error :incomplete-evaluation
                           :eval-id eval-id
                           :status (:status record)})))
        (when-not (= (:coordinate record) (current-coordinate target))
          (throw (ex-info "Durable evaluation authority coordinate does not match the binding"
                          {:samizdat.sandbox/error :coordinate-mismatch
                           :eval-id eval-id})))
        (let [fresh-instance (fresh-target target)
              receipts (replay-receipts fresh-instance record)
              state @(::state fresh-instance)]
          (sandbox/load-receipts! state receipts)
          (sandbox/set-mode! state :replay)
          (let [value (evaluate-state! fresh-instance (:source record) nil)]
            (when-not (result-matches? record value)
              (throw (ex-info "Replayed source result does not match its durable completion"
                              {:samizdat.sandbox/error :replay-result-mismatch
                               :eval-id eval-id})))
            ;; Historical evidence remains in the DB, not in the now-live
            ;; context.  Clearing it also prevents a later mode change from
            ;; accidentally treating history as a new replay program.
            (sandbox/load-receipts! state [])
            (sandbox/set-mode! state :normal)
            (publish-recorded-rebuild! binding fresh-instance))))
      (finally
        (release-evaluation! target claim)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Whole-history rebuild — one fresh SCI context from the committed record
;; ═══════════════════════════════════════════════════════════════════════════

(defn- check-single-recorded-binding!
  "Whole-history rebuild reconstructs the INSTANCE from ONE binding's durable
   history, which is sound only when that binding is the instance's sole
   recorded binding — the JS1 provider policy (one work-id per instance key;
   a controller needing isolation passes a distinct :instance/key).  A
   provider registry showing a second binding on the same instance key fails
   closed: replaying one binding's history would silently drop the sibling's
   committed definitions."
  [binding]
  (when-let [reg (::registry binding)]
    (let [siblings (->> (:bindings @reg)
                        vals
                        (filter #(= (:instance/key binding) (:instance/key %)))
                        (map :binding/id)
                        distinct
                        vec)]
      (when (< 1 (count siblings))
        (throw (ex-info "Instance is shared by multiple bindings; one binding's durable history cannot rebuild it"
                        {:samizdat.sandbox/error :shared-instance
                         :instance/key (:instance/key binding)
                         :binding-ids siblings}))))))

(defn- validate-history!
  "Fail-closed validation of a binding's durable evaluation history before any
   of it is trusted as replay material:

     - the total order is contiguous from 0 (a gap is a torn record);
     - no record is pending (an unfinished evaluation's actuations may or may
       not have happened — the honest answer is refusal, and an unsettled
       effect intent inside one is the same refusal one level down);
     - every record — replayed or not — names exactly this spec, instance,
       binding, current authority coordinate, and current runtime coordinate."
  [identity coordinate rows]
  (when-not (= (mapv :binding_seq rows) (vec (range (count rows))))
    (throw (ex-info "Durable evaluation history is not a contiguous total order from 0"
                    {:samizdat.sandbox/error :malformed-history
                     :binding-seqs (mapv :binding_seq rows)})))
  (let [expected {:spec_id (str (:spec-id identity))
                  :instance_id (str (:instance-id identity))
                  :binding_id (str (:binding-id identity))
                  :coordinate (str coordinate)
                  :runtime (str (:runtime identity))}]
    (doseq [record rows]
      (when (= :pending (:status record))
        (throw (ex-info "Durable evaluation history contains a pending record; its actuations may or may not have happened"
                        {:samizdat.sandbox/error :pending-history
                         :eval-id (:id record)
                         :binding_seq (:binding_seq record)})))
      (when-not (= expected (select-keys record (keys expected)))
        (throw (ex-info "Durable evaluation record does not match this binding's spec, instance, binding, coordinate, or runtime"
                        {:samizdat.sandbox/error :history-mismatch
                         :eval-id (:id record)
                         :expected expected
                         :actual (select-keys record (keys expected))}))))))

(defn- replay-one-record!
  "Replay one validated completed record into the fresh instance: its exact
   durable receipts in, its source evaluated in :replay mode — so every
   operation is served from the receipts and ZERO real project operations
   execute — then its replayed final value checked against the durable
   terminal result.  jolt.sandbox itself fails replay on operation or
   argument mismatch and on unconsumed receipts."
  [fresh-instance record]
  (let [state @(::state fresh-instance)
        receipts (replay-receipts fresh-instance record)]
    (sandbox/load-receipts! state receipts)
    (sandbox/set-mode! state :replay)
    (let [value (evaluate-state! fresh-instance (:source record) nil)]
      (when-not (result-matches? record value)
        (throw (ex-info "Replayed source result does not match its durable completion"
                        {:samizdat.sandbox/error :replay-result-mismatch
                         :eval-id (:id record)}))))))

(defn- rebuild-binding-internal!
  "Whole-history rebuild core, shared by rebuild-binding! and the
   commit-only rollback path: validate the binding's durable history against
   its current identity, then replay EVERY committed evaluation, in the
   binding's total order, into ONE fresh SCI context, and publish the fresh
   instance only after every replay has matched exactly.  Failed evaluations
   are validated but not replayed — their definitions never committed.
   Returns the rebuilt Binding.  The caller must hold the evaluation claim
   on `target`."
  [store conn binding target]
  (check-single-recorded-binding! binding)
  (let [identity (binding-identity binding)
        coordinate (current-coordinate target)
        rows ((:history store) conn (:binding/id binding))]
    ;; Validation is complete before the fresh context is allocated: nothing
    ;; the history says is trusted until every record has checked out.
    (validate-history! identity coordinate rows)
    (let [fresh-instance (fresh-target target)
          state @(::state fresh-instance)]
      (doseq [record rows]
        (when (= :completed (:status record))
          (replay-one-record! fresh-instance record)))
      ;; Historical evidence remains in the DB, not in the now-live context.
      ;; Clearing it also prevents a later mode change from accidentally
      ;; treating history as a new replay program.
      (sandbox/load-receipts! state [])
      (sandbox/set-mode! state :normal)
      (publish-recorded-rebuild! binding fresh-instance))))

(defn rebuild-binding!
  "Rebuild `binding`'s instance from its WHOLE durable committed history:
   every committed evaluation for the binding, in the binding's durable
   total order, replayed into ONE fresh SCI context.  Returns the rebuilt
   Binding.

   Zero real project operations execute: replay runs in jolt.sandbox :replay
   mode throughout, so every observation and actuation is served from the
   exact durable receipts of its original evaluation.  Before any record is
   trusted, the whole history is validated — contiguous total order, no
   pending records, and per-record spec/instance/binding identity plus exact
   authority and runtime coordinates — and any gap, pending record, shared
   instance, or mismatch fails closed.  Each replayed evaluation must also
   reproduce its durable terminal result exactly.

   A binding with no recorded history rebuilds to a fresh empty context.
   This supersedes rebuild-recorded! (which replays a single evaluation)
   whenever definitions span evaluations: it is the resume/reconcile
   operation, and it is what rollback after a failed evaluation runs."
  [conn binding]
  (require-binding! binding "rebuild-binding!")
  (let [store (eval-store)
        target (evaluation-target binding "rebuild-binding!")
        claim (claim-evaluation! target)]
    (try
      (rebuild-binding-internal! store conn binding target)
      (finally
        (release-evaluation! target claim)))))

(defn- registry-rebind
  "Registry update for rebuild! on a binding: point the work-id's binding
   at the rebuilt instance, if the binding is still registered."
  [r work-id b]
  (if (get-in r [:bindings work-id])
    (assoc-in r [:bindings work-id] b)
    r))

(defn- registry-replace-instance
  "Registry update for rebuild! on an instance: replace the registered
   instance under its key, if still registered."
  [r key fresh]
  (if (contains? (:instances r) key)
    (assoc-in r [:instances key] fresh)
    r))

(defn rebuild!
  "Create a fresh sandbox context with the same configuration and
   effective authority as `x` (a context, Instance, or Binding), but with
   a clean SCI context (no definitions or receipts).  Forks from current
   effective authority, never the creation-time grant.

   Identity is stable: an Instance keeps its :instance/id, a Binding
   keeps its :binding/id.  Provider-registered instances and bindings
   update the provider registry, so a later bind! sees the rebuilt
   instance.  Returns the rebuilt value; previously returned maps still
   reference the superseded context (persistent value semantics)."
  [x]
  (cond
    (::instance x)
    (let [inst (rebuild! (::instance x))
          b (assoc x ::instance inst :instance/id (:instance/id inst))]
      (when-let [reg (::registry x)]
        (swap! reg (fn [r] (registry-rebind r (:work-id x) b))))
      b)

    (::state x)
    (let [fresh (assoc x ::state (atom (sandbox/fork-context @(::state x))))]
      (when-let [reg (::registry x)]
        (swap! reg (fn [r] (registry-replace-instance r (:instance/key x) fresh))))
      fresh)

    :else
    (throw (ex-info "rebuild! needs a sandbox context, instance, or binding"
                    {:samizdat.sandbox/error :not-a-context}))))

(defn describe
  "Return an inert, data-only description of the current effective
   authority of `x` (a context, Instance, or Binding).  Reads the live
   state's current :authorized set, so attenuation/revocation is
   reflected.  Suitable for serialization, comparison, coordination,
   and capability discovery.  The description is fully canonical:
   all collections are sorted and contain only receipt-domain values.

   jolt.sandbox keys:
     :jolt.sandbox/profile — the context profile
     :jolt.sandbox/requested — requested capability IDs
     :jolt.sandbox/authorized — authorized capability IDs
     :jolt.sandbox/operations — authorized operation descriptors

    JS1 seam coordinates:
      :samizdat.sandbox/kind — :context / :instance / :binding
      :samizdat.sandbox/root-canonical — the trusted root
      :samizdat.sandbox/timeout-ms — the configured ceiling
       :samizdat.sandbox/bounds — the six bounds
      :samizdat.sandbox/preset — the trusted preset (catalog specs only)
      :samizdat.sandbox/spec-coordinate — the inert js1: content coordinate
      :samizdat.sandbox/runtime-coordinate — the versioned js1-rt/v1:
        RuntimeCoordinate of this process
      :samizdat.sandbox/instance-key / :samizdat.sandbox/instance-id
        (instances and bindings)
      :samizdat.sandbox/binding-id / :samizdat.sandbox/work-id
        (bindings only)"
  [x]
  (let [binding (when (::instance x) x)
        target (if binding (::instance x) x)
        state-atom (::state target)]
    (when-not state-atom
      (throw (ex-info "describe needs a sandbox context, instance, or binding"
                      {:samizdat.sandbox/error :not-a-context})))
    (let [base (sandbox/effective-authority @state-atom)
          spec (:spec target)]
      (sandbox/inert
        (cond-> (assoc base
                  :samizdat.sandbox/kind (:samizdat.sandbox/kind x)
                  :samizdat.sandbox/root-canonical (:root-canonical target)
                  :samizdat.sandbox/timeout-ms (:timeout-ms target)
                  :samizdat.sandbox/bounds (:bounds spec)
                  :samizdat.sandbox/spec-coordinate (:spec/coordinate spec)
                  :samizdat.sandbox/runtime-coordinate (runtime-coordinate))
          (:preset spec)
          (assoc :samizdat.sandbox/preset (:preset spec))
          (:instance/id target)
          (assoc :samizdat.sandbox/instance-key (:instance/key target)
                 :samizdat.sandbox/instance-id (:instance/id target))
          binding
          (assoc :samizdat.sandbox/binding-id (:binding/id binding)
                 :samizdat.sandbox/work-id (:work-id binding)))))))

(defn capabilities
  "Safe capability discovery from effective authority: the currently
    authorized capability IDs as a sorted vector of keywords, read from
    the same live authority source that dispatch rechecks — so revocation
    is reflected and the result never attests authority the target no
    longer has.  Accepts anything describe accepts, or an
    already-computed describe map."
  [x]
  (let [d (if (and (map? x) (contains? x :jolt.sandbox/authorized))
             x
             (describe x))]
    ;; effective-authority renders capability IDs as strings like
    ;; ":project/read"; strip the leading colon to re-keyword them.
    (vec (sort-by str
                  (map (fn [s] (keyword (if (str/starts-with? s ":")
                                          (subs s 1)
                                          s)))
                       (:jolt.sandbox/authorized d))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Safe discovery — host-derived, never sandbox-evaluated
;; ═══════════════════════════════════════════════════════════════════════════

(defn- describe-for-discovery
  "A describe map for `x`: the map itself when already described, else a
   fresh host-side describe.  Pure data out; nothing is ever evaluated."
  [x]
  (if (and (map? x) (contains? x :jolt.sandbox/authorized))
    x
    (describe x)))

(defn- discovery-symbol
  "Normalize model-supplied symbol text to a capability keyword, or nil.
   Accepts \"project/read\" and \":project/read\" (optionally surrounded by
   whitespace); everything else — bare names, hostile splices, non-strings —
   is nil, because the text is data to match against, never source to run."
  [sym]
  (when (string? sym)
    (let [s (str/trim sym)
          s (if (str/starts-with? s ":") (subs s 1) s)]
      (when (re-matches #"[A-Za-z0-9*+!_'?<>=./-]+" s)
        (keyword s)))))

(def ^:private reviewed-surface*
  "The reviewed pure language surface as trusted static data —
   jolt.sandbox/language-surface (Jolt 619ef196): derived solely from the
   reviewed static allow-list, inert and canonical, with no live SCI
   Context, no introspection, and no host handles.  Cached: the surface is
   a constant of the runtime the RuntimeCoordinate already names."
  (delay (:jolt.sandbox.surface/symbols (sandbox/language-surface))))

(defn- reviewed-symbols
  "The reviewed pure symbol-name strings, sorted."
  []
  @reviewed-surface*)

(defn- reviewed-symbol?
  "Whether `s` names a symbol of the reviewed pure language surface."
  [s]
  (boolean (some #{s} (reviewed-symbols))))

(def ^:private pure-symbol-docs
  "Host-owned inert documentation for the reviewed pure symbols models ask
   about most, keyed by surface symbol name.  The language surface carries
   names, not docs, by design — this is the host's documentation database
   the surface description names.  Absent entries get the generic
   reviewed-surface doc; nothing here grants or attests authority."
  {"map" {:arglists '([f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])
          :doc "Returns the lazy sequence of applying f to each item in the coll(s)."}
   "mapv" {:arglists '([f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])
           :doc "Applies f to each item in the coll(s), eagerly into a vector."}
   "filter" {:arglists '([pred coll])
             :doc "Returns the lazy sequence of items of coll for which (pred item) is true."}
   "filterv" {:arglists '([pred coll])
              :doc "Returns a vector of items of coll for which (pred item) is true."}
   "reduce" {:arglists '([f coll] [f init coll])
             :doc "Accumulates f over coll, optionally starting from init."}
   "mapcat" {:arglists '([f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])
             :doc "Applies f to each item of the coll(s) and concatenates the results."}
   "first" {:arglists '([coll]) :doc "The first item of coll, or nil if empty."}
   "rest" {:arglists '([coll]) :doc "The items of coll after the first; empty seq if none."}
   "last" {:arglists '([coll]) :doc "The last item of coll, in linear time."}
   "count" {:arglists '([coll]) :doc "The number of items in coll."}
   "take" {:arglists '([n coll]) :doc "The lazy sequence of the first n items of coll."}
   "drop" {:arglists '([n coll]) :doc "The lazy sequence of all but the first n items of coll."}
   "nth" {:arglists '([coll index] [coll index not-found])
          :doc "The item at index of coll, or not-found when out of range."}
   "str" {:arglists '([& xs]) :doc "Concatenates the string forms of xs, with no separators."}
   "subs" {:arglists '([s start] [s start end])
           :doc "The substring of s from start (inclusive) to end (defaults to the end)."}
   "split-at" {:arglists '([n coll])
               :doc "A pair [(take n coll) (drop n coll)]."}
   "sort" {:arglists '([coll] [comp coll])
           :doc "A sorted sequence of coll's items, optionally by comparator."}
   "sort-by" {:arglists '([keyfn coll] [keyfn comp coll])
              :doc "A sorted sequence of coll's items by (keyfn item)."}
   "assoc" {:arglists '([map key val] [map key val & kvs])
            :doc "Associates key with val in map, later pairs winning."}
   "dissoc" {:arglists '([map key] [map key & ks])
             :doc "Dissociates the keys from map."}
   "get" {:arglists '([map key] [map key not-found])
          :doc "The value for key in map, or not-found."}
   "merge" {:arglists '([& maps])
            :doc "Merges the maps left to right, later entries winning."}
   "select-keys" {:arglists '([map keyseq])
                  :doc "A map of only the entries of map whose keys are in keyseq."}
   "defn" {:arglists '([name doc-string? attr-map? [params*] prepost-map? body])
           :doc "Defines a function. Pure here: the sandbox vocabulary reaches nothing."}
   "let" {:arglists '([bindings & body])
          :doc "Binding => binding-form init-expr; evaluates body with the bindings."}
   "if-let" {:arglists '([binding then] [binding then else])
             :doc "Binds the test's value when truthy, evaluating then, else else."}
   "cond" {:arglists '([& clauses])
           :doc "Takes test/expr pairs; evaluates the expr of the first truthy test."}
   "range" {:arglists '([] [end] [start end] [start end step])
            :doc "A lazy sequence of numbers from start (0) to end by step (1)."}
   "vec" {:arglists '([coll]) :doc "Creates a vector containing the items of coll."}
   "vector" {:arglists '([& xs]) :doc "Creates a vector of the arguments."}
   "set" {:arglists '([coll]) :doc "Creates a set containing the distinct items of coll."}
   "frequencies" {:arglists '([coll])
                  :doc "A map from the distinct items of coll to their counts."}
   "group-by" {:arglists '([f coll])
               :doc "A map from (f item) to the vectors of items producing it."}
   "distinct" {:arglists '([coll])
               :doc "The lazy sequence of coll's items with duplicates removed."}
   "apply" {:arglists '([f args] [f x args] [f x y args] [f x y z args] [f a b c d & more])
            :doc "Applies f to the arguments, splicing the final coll's items in."}
   "partial" {:arglists '([f] [f arg1] [f arg1 arg2] [f arg1 arg2 arg3] [f arg1 arg2 arg3 & more])
              :doc "A partially applied f with the given arguments fixed."}
   "comp" {:arglists '([& fs]) :doc "Composes the functions right to left."}
   "pr-str" {:arglists '([& xs]) :doc "Prints the arguments to a string."}
   "format" {:arglists '([fmt & args])
             :doc "Formats args per the java.util.Formatter-style fmt string."}
   "name" {:arglists '([x]) :doc "The name string of a string, symbol or keyword."}
   "keyword" {:arglists '([x] [ns x]) :doc "A keyword from the name (and namespace)."}
   "symbol" {:arglists '([name] [ns name]) :doc "A symbol from the name (and namespace)."}})

(defn- reviewed-symbol-doc
  "The inert doc map for one reviewed pure symbol: the host-owned curated
   entry when there is one, otherwise the generic reviewed-surface doc."
  [s]
  (if-let [curated (get pure-symbol-docs s)]
    {:name s
     :arglists (vec (:arglists curated))
     :doc (str (:doc curated) "  Effect: pure.")}
    {:name s
     :doc (str "Reviewed pure language-surface symbol (js0-pure-sci): computes "
               "over inert values and reaches nothing — no IO, no state, no "
               "host access.  Part of the frozen reviewed vocabulary named by "
               "the runtime coordinate.  Effect: pure.")}))

(defn operation-doc
  "Host-derived safe discovery for `doc`: an inert doc-style map for the
   projected operation named by `sym` (e.g. \"project/read\"), or for a
   symbol of the reviewed pure language surface (e.g. \"map\"), or nil.

   The answer is the UNION the model can actually call: effective project
   operations (from the target's describe map plus the host-owned
   operation-docs table) and pure reviewed symbols (from
   jolt.sandbox/language-surface — trusted static data with a versioned
   coordinate, never a live SCI Context).  No resolve/find-ns/ns-publics/
   meta form is ever evaluated inside the sandbox: the sandbox vocabulary
   forbids them, and splicing untrusted symbol text into evaluated source
   would be an injection seam, so discovery never evaluates at all — the
   symbol text is matched as inert data.  Ungranted capabilities are not
   discoverable, and no doc attests anything the target cannot do."
  [x sym]
  (when-let [cap (discovery-symbol sym)]
    (let [d (describe-for-discovery x)
          ;; :op/id is the string form ":project/read"; re-keyword it.
          op (some (fn [o] (when (= cap (keyword (subs (:op/id o) 1))) o))
                   (:jolt.sandbox/operations d))]
      (or (when op
            (let [docs (or (get operation-docs cap) {})]
              {:name (str "project/" (:op/name op))
               :arglists (vec (or (:arglists docs) '([& args])))
               :doc (str (:doc docs)
                         "  Effect: " (name (:op/effect op)) ".")}))
          (let [s (name cap)]
            (when (reviewed-symbol? s)
              (reviewed-symbol-doc s)))))))

(defn complete-capability
  "Host-derived safe discovery for `complete`: the sorted names `x`'s
   evaluation can actually resolve, filtered by `prefix` — the UNION of the
   authorized projected operation names (\"project/…\") and the reviewed
   pure language surface's symbol names.  Both corpora are inert data
   (effective authority and jolt.sandbox/language-surface); nothing is
   evaluated inside the sandbox, so untrusted prefix text can only narrow
   the answer, never widen it or run."
  [x prefix]
  (let [p (str prefix)]
    (vec (sort (filter #(str/starts-with? % p)
                       (concat (map (fn [cap] (str "project/" (name cap)))
                                    (capabilities x))
                               (reviewed-symbols)))))))
