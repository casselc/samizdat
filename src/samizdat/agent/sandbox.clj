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
   project/search, project/stat, project/edit — all rooted at the trusted
   host root with relative nonescaping paths, symlink escape prevention,
   bounded reads, deterministic digest stat, and anchored edit
   (base-digest or :absent, stale conflict detection, no blind overwrite).
   All returned data is inert/canonical via jolt.sandbox.

   Timeout uses Jolt cooperative interrupt with an unraiseable host
   ceiling: jolt.host/run-interruptible checks the token from the host
   side; SCI code cannot catch or suppress the interruption.

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
(def ^:const default-timeout-ms 30000)

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
;; Path resolution (symlink-safe, fail-closed)
;; ═══════════════════════════════════════════════════════════════════════════

(defn- resolve-root
  "Resolve root to its canonical absolute path string."
  [root]
  (str (fs/canonicalize root)))

(defn- resolve-under-root
  "Resolve relative path under canonical root. Returns the absolute
   path, or nil if the path escapes, is blank, or causes any I/O error.
   Canonicalize follows symlinks so any chain that lands outside root
   is caught.  For non-existing leaf paths the existing prefix is
   resolved; a symlink in that prefix that points outside root is
   still caught because canonicalize resolves it before appending the
   remaining (non-existing) segments."
  [root-canonical path]
  (when (and (string? path) (not (str/blank? path)))
    (try
      (let [target (str (fs/canonicalize (fs/path root-canonical path)))]
        (when (or (= target root-canonical)
                  (str/starts-with? target (str root-canonical "/")))
          target))
      (catch Exception _ nil))))

(defn- require-under-root!
  "Resolve relative path under canonical root; throws on escape or
   missing path with {:samizdat.sandbox/error} in ex-data."
  [root-canonical rel-path label]
  (if-let [abs (resolve-under-root root-canonical rel-path)]
    abs
    (throw (ex-info (str label ": path escapes root or is invalid: " rel-path)
                    {:samizdat.sandbox/error :path-escape
                     :path rel-path}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Digest
;; ═══════════════════════════════════════════════════════════════════════════

(defn- file-digest-str
  "SHA-256 hex digest string prefixed with 'sha256:', or nil."
  [abs-path]
  (when-let [hex (files/file-digest abs-path)]
    (str "sha256:" hex)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Operation descriptors
;; ═══════════════════════════════════════════════════════════════════════════

(defn- make-read-op
  "project/read — bounded file read. Returns {:path :content :truncated}"
  [root-canonical max-chars]
  {:id :project/read :name 'read :effect :observation
   :fn (fn [rel-path]
         (let [abs (require-under-root! root-canonical rel-path "project/read")]
           (when-not (fs/regular-file? abs {:nofollow-links true})
             (throw (ex-info "project/read: not a regular file"
                             {:samizdat.sandbox/error :not-file :path rel-path})))
           (let [content (slurp abs)
                 truncated? (> (count content) max-chars)
                 shown (if truncated? (subs content 0 max-chars) content)]
             {:path rel-path :content shown :truncated truncated?})))})

(defn- make-list-op
  "project/list — bounded directory listing.
   Optional rel-path argument defaults to the root."
  [root-canonical max-entries]
  {:id :project/list :name 'list :effect :observation
   :fn (fn [& args]
         (let [rel-path (or (first args) ".")
               abs (resolve-under-root root-canonical rel-path)]
           (if (and abs (fs/directory? abs {:nofollow-links true}))
             {:path rel-path
              :entries (files/safe-list-dir root-canonical rel-path max-entries)}
             {:path rel-path :entries []})))})

(defn- make-search-op
  "project/search — bounded regex search. Returns {:pattern :matches}."
  [root-canonical max-results max-chars]
  {:id :project/search :name 'search :effect :observation
   :fn (fn [pattern & args]
         (let [rel-path (or (first args) ".")]
           {:pattern pattern
            :path rel-path
            :matches (files/safe-search-files
                       root-canonical rel-path pattern max-results max-chars)}))})

(defn- make-stat-op
  "project/stat — deterministic stat with coordinate/digest.
   Returns {:path :exists :type :size :digest}."
  [root-canonical]
  {:id :project/stat :name 'stat :effect :observation
   :fn (fn [rel-path]
         (if-let [abs (resolve-under-root root-canonical rel-path)]
           (let [exists? (fs/exists? abs {:nofollow-links true})]
             (if exists?
               (let [sym? (fs/sym-link? abs)
                     dir? (fs/directory? abs {:nofollow-links true})
                     reg? (and (not sym?) (not dir?) (fs/regular-file? abs))
                     type (cond sym? :symlink dir? :dir reg? :file)
                     size (when reg? (fs/size abs))
                     digest (when reg? (file-digest-str abs))]
                 {:path rel-path :exists true :type type
                  :size size :digest digest})
               {:path rel-path :exists false}))
           {:path rel-path :exists false}))})

(defn- make-edit-op
  "project/edit — anchored edit requiring base digest or :absent.

   Args: [rel-path base-digest new-content]

   - base-digest is a sha256:… string (for an existing file) or :absent / nil
     (for creation).  A string base-digest on a non-existing file, or
     :absent on an existing file, is a conflict.
   - Stale conflict: file exists but its current digest ≠ base-digest.
   - No blind overwrite: base-digest must be supplied when the file exists.
   - Returns {:path :digest :created?}"
  [root-canonical]
  {:id :project/edit :name 'edit :effect :actuation
   :fn (fn [rel-path base-digest new-content]
         (let [abs (require-under-root! root-canonical rel-path "project/edit")
               new-content (str new-content)
               exists? (fs/exists? abs {:nofollow-links true})
               creating? (or (nil? base-digest) (= :absent base-digest))]
           (cond
             ;; Creation path
             creating?
             (when exists?
               (throw (ex-info "project/edit: file already exists"
                               {:samizdat.sandbox/error :already-exists
                                :path rel-path
                                :actual-digest (file-digest-str abs)})))

             ;; Update path — base-digest must be a string
              (string? base-digest)
              (do
                (when (not exists?)
                  (throw (ex-info "project/edit: file does not exist"
                                  {:samizdat.sandbox/error :not-found
                                   :path rel-path})))
                (let [current-digest (file-digest-str abs)]
                  (when (not= base-digest current-digest)
                    (throw (ex-info "project/edit: stale conflict"
                                    {:samizdat.sandbox/error :stale-conflict
                                     :path rel-path
                                     :expected base-digest
                                     :actual current-digest})))))

             ;; Invalid base-digest
             :else
             (throw (ex-info "project/edit: invalid base-digest"
                             {:samizdat.sandbox/error :invalid-base-digest
                              :path rel-path
                              :base-digest base-digest})))

         ;; Perform the write
         (when-let [parent (fs/parent abs)]
           (fs/create-dirs parent))
         (spit abs new-content)

         ;; Return new digest
         (let [new-digest (file-digest-str abs)]
           {:path rel-path
            :digest new-digest
            :created? (not exists?)})))})

;; ═══════════════════════════════════════════════════════════════════════════
;; Operation construction
;; ═══════════════════════════════════════════════════════════════════════════

(defn- build-operations
  "Build the five semantic operation descriptors."
  [root-canonical {:keys [max-read-chars max-list-entries
                           max-search-results search-max-chars]}]
  [(make-read-op  root-canonical (or max-read-chars default-max-read-chars))
   (make-list-op  root-canonical (or max-list-entries default-max-list-entries))
   (make-search-op root-canonical
                     (or max-search-results default-max-search-results)
                     (or search-max-chars default-search-max-chars))
   (make-stat-op  root-canonical)
   (make-edit-op  root-canonical)])

(def ^:private operation-docs
  "Host-owned inert documentation for the five projected operations — the
   same knowledge the constructors above encode, kept beside them so safe
   discovery (operation-doc) can describe authority without evaluating any
   form inside the sandbox."
  {:project/read
   {:arglists '([rel-path])
    :doc "Bounded read of one file under the sandbox root. Returns {:path :content :truncated}."}
   :project/list
   {:arglists '([rel-path?])
    :doc "Bounded listing of a directory under the sandbox root (default the root itself). Returns {:path :entries}."}
   :project/search
   {:arglists '([pattern rel-path?])
    :doc "Bounded regex search under the sandbox root. Returns {:pattern :path :matches}."}
   :project/stat
   {:arglists '([rel-path])
    :doc "Deterministic stat of one path, with content digest. Returns {:path :exists :type :size :digest}."}
   :project/edit
   {:arglists '([rel-path base-digest new-content])
    :doc "Anchored write: base-digest is the file's current sha256:… digest, or :absent to create. A stale digest conflicts; existing files are never blindly overwritten. Returns {:path :digest :created?}."}})

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
  "Validate the four bound options (defaults applied).  Fail-closed: every
   bound must be a positive integer."
  [{:keys [max-read-chars max-list-entries max-search-results search-max-chars]}]
  (let [bounds {:max-read-chars (or max-read-chars default-max-read-chars)
                :max-list-entries (or max-list-entries default-max-list-entries)
                :max-search-results (or max-search-results default-max-search-results)
                :search-max-chars (or search-max-chars default-search-max-chars)}]
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
     :search-max-chars — bounds (positive integers; defaults apply)
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
     ::evaluation-owner (atom nil)}))

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
       ::evaluation-owner (::evaluation-owner c)})))

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
         ::evaluation-owner (::evaluation-owner c)}))))

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
   :complete!, :load-eval, and :verify-binding! to supply the same contract."
  nil)

(def ^:private eval-store-symbols
  {:begin! 'samizdat.store.evals/begin!
   :record-intent! 'samizdat.store.evals/record-intent!
   :record-outcome! 'samizdat.store.evals/record-outcome!
   :complete! 'samizdat.store.evals/complete!
   :load-eval 'samizdat.store.evals/load-eval
   :verify-binding! 'samizdat.store.evals/verify-binding!})

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
                   (::evaluation-owner target))
      (throw (ex-info (str label " needs a sandbox context, instance, or binding")
                      {:samizdat.sandbox/error :not-a-context})))
    target))

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
  "Run source after ownership has been acquired."
  [target source opts]
  (let [state-atom (::state target)
        timeout-ms (:timeout-ms target)
        token (:token opts)]
    (if (or token (not timeout-ms) (zero? timeout-ms))
      (sandbox/evaluate! @state-atom source token)
      (let [tok (jolt.host/make-interrupt)
            _ (let [t (Thread. (fn []
                                (try
                                  (Thread/sleep timeout-ms)
                                  (jolt.host/interrupt! tok)
                                  (catch Exception _ nil))))]
                (.setDaemon t true)
                (.start t))]
        (try
          (sandbox/evaluate! @state-atom source tok)
          (catch Throwable e
            (if (:jolt/interrupted (ex-data e))
              (throw (ex-info "Sandbox evaluation timed out"
                              {:samizdat.sandbox/error :timeout
                               :timeout-ms timeout-ms}
                              e))
              (throw e))))))))

(defn evaluate!
  "Evaluate `source` in `x` — a context (new), an Instance, or a Binding.

   Optional opts:
     :token — a Jolt interrupt token (jolt.host/make-interrupt).
              When supplied, evaluation uses jolt.host/run-interruptible
              so the token's interruption is an unraiseable host ceiling.
              If no token is given and the target has a timeout, one is
              created and scheduled automatically via a daemon thread.

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
  [binding]
  {:spec-id (:spec/coordinate (:spec binding))
   :instance-id (:instance/id binding)
   :binding-id (:binding/id binding)})

(defn- current-coordinate
  [target]
  (sandbox/canonical-coordinate
   (sandbox/effective-authority @(::state target))))

(defn- run-recorded-effect!
  "Persist intent, run exactly once, then persist exactly one outcome.  A
   failure to append the outcome is deliberately not converted into an error
   outcome: the existing intent stays unsettled because the real world's state
   is then unknown."
  [store conn eval-id {:keys [id fn]} args]
  (let [intent ((:record-intent! store) conn eval-id
                {:op id :args (sandbox/inert args)})
        outcome (try
                  {:ok true :value (apply fn args)}
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

(defn evaluate-recorded!
  "Evaluate source in a controller-minted Binding while appending a durable
   JS1 record to trusted `conn`.

   The evaluation row (spec/instance/binding ids, exact current authority
   coordinate, and source) lands before source runs.  Every semantic operation
   appends intent before touching the project and outcome afterward.  The
   terminal row is appended only after evaluation returns and all outcomes have
   settled.  Returns {:eval-id id :value value}.

   If an outcome append fails after an operation, completion also fails closed
   and the durable record remains pending with its unsettled intent."
  ([conn binding source] (evaluate-recorded! conn binding source nil))
  ([conn binding source opts]
   (require-binding! binding "evaluate-recorded!")
   (reject-authority-selection! "evaluate-recorded!" opts)
   (let [store (eval-store)
         target (evaluation-target binding "evaluate-recorded!")
         claim (claim-evaluation! target)]
     (try
       (let [identity (binding-identity binding)
             eval-id ((:begin! store) conn
                      (assoc identity
                             :coordinate (current-coordinate target)
                             :source source))
             hook (::effect-hook target)]
         (reset! hook (fn [operation args]
                        (run-recorded-effect! store conn eval-id operation args)))
         (sandbox/set-mode! @(::state target) :normal)
         (try
           (let [outcome (try
                           (let [value (evaluate-state! target source opts)]
                             {:ok true
                              :value value
                              :inert-value (sandbox/inert value)})
                           (catch Throwable evaluation-error
                             {:ok false :error evaluation-error}))]
             (if (:ok outcome)
               (do
                 ;; Completion persistence is not part of source evaluation.
                 ;; If it fails, do not attempt a contradictory :failed row;
                 ;; leave the original record pending.
                 ((:complete! store) conn eval-id
                  {:status :completed
                   :result {:value (:inert-value outcome)}})
                 {:eval-id eval-id :value (:value outcome)})
               (let [evaluation-error (:error outcome)]
                 (try
                   (complete-failed-evaluation! store conn eval-id evaluation-error)
                   (catch Throwable completion-error
                     (throw (ex-info
                             "Evaluation failed and its durable record could not be completed; the record remains pending"
                             {:samizdat.sandbox/error :durable-evaluation-incomplete
                              :eval-id eval-id}
                             completion-error))))
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
   a provider registry until replay has succeeded exactly."
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
                     ::evaluation-owner (::evaluation-owner c))]
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
          (let [value (evaluate-state! fresh-instance (:source record) nil)
                inert-value (sandbox/inert value)
                result (:result record)]
            (when-not (and (map? result)
                           (contains? result :value)
                           (= (sandbox/inert (:value result)) inert-value))
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
     :samizdat.sandbox/bounds — the four bounds
     :samizdat.sandbox/preset — the trusted preset (catalog specs only)
     :samizdat.sandbox/spec-coordinate — the inert js1: content coordinate
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
                  :samizdat.sandbox/spec-coordinate (:spec/coordinate spec))
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

(defn operation-doc
  "Host-derived safe capability discovery for `doc`: an inert doc-style map
   for the projected operation named by `sym` (e.g. \"project/read\"), or
   nil when no authorized operation matches.

   Derived ONLY from the target's effective authority (describe) plus the
   host-owned operation-docs table.  No resolve/find-ns/ns-publics/meta
   form is ever evaluated inside the sandbox: the sandbox vocabulary
   forbids them, and splicing untrusted symbol text into evaluated source
   would be an injection seam, so discovery never evaluates at all — the
   symbol text is matched as inert data.  Ungranted capabilities are not
   discoverable."
  [x sym]
  (when-let [cap (discovery-symbol sym)]
    (let [d (describe-for-discovery x)
          ;; :op/id is the string form ":project/read"; re-keyword it.
          op (some (fn [o] (when (= cap (keyword (subs (:op/id o) 1))) o))
                   (:jolt.sandbox/operations d))]
      (when op
        (let [docs (or (get operation-docs cap) {})]
          {:name (str "project/" (:op/name op))
           :arglists (vec (or (:arglists docs) '([& args])))
           :doc (str (:doc docs)
                     "  Effect: " (name (:op/effect op)) ".")})))))

(defn complete-capability
  "Host-derived safe capability discovery for `complete`: the sorted
   projected operation names (\"project/…\") authorized for `x` whose full
   name starts with `prefix`.  Pure filtering over effective authority —
   nothing is evaluated inside the sandbox, so untrusted prefix text can
   only narrow the answer, never widen it or run."
  [x prefix]
  (let [p (str prefix)]
    (vec (sort (filter #(str/starts-with? % p)
                       (map (fn [cap] (str "project/" (name cap)))
                            (capabilities x)))))))
