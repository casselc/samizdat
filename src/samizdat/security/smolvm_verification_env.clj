;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.smolvm-verification-env
  "The SmolVM VerificationEnvironment — the controller-owned, fail-closed
  MACHINE-isolated verify provider, ported from the proven bbagent execution
  substrate (bbagent.executor/worker/snapshot/process; A3a/A3b/A3c evidence).

  This is not an adapter over bbagent and it never shells out to it: the
  manager/image/snapshot/overlay/process/stream/reap/coordinate behaviour
  bbagent measured is re-implemented here, on this harness's own primitives
  (samizdat.engine.proc for bounded spawns and tree reaping,
  samizdat.security.canonical-edn for coordinates), against the same external
  contract — the smolvm machine manager, the pinned guest image whose prelude
  is the guest half of the boundary, and the repository-neutral
  ExecutionEnvironment EDN SPI (RFC-012) the bwrap environment already
  speaks.

  The shape of the boundary is bbagent's, verbatim:

    authoritative project root
          |  read-only mount (-v root:/input:ro)  the host tree is never writable
          v
       /input                                overlay lower layer, then MASKED
          |  overlayfs, upper inside the VM   copy-on-write, nothing copied in
          v
       /work                                 writable, dies with the machine
          |  excluded paths whited out
          |  privilege dropped here           no capabilities past this line
          v
       the PINNED verifier argv

  What a verification run means here, and how it differs from the bwrap
  environment beside it:

    - EPHEMERAL MACHINE PER EXECUTION — every run boots a throwaway VM and
      destroys it; there is no reuse for a timeout to have to poison.
    - PRIVATE SNAPSHOT SEMANTICS — the run is bracketed by the project input
      coordinate (a manifest of the tree the workload saw, exclusions hidden
      by name). The overlay's lower layer is the LIVE host tree, not a copy:
      a project that moved under a run is reported :project-changed, carries
      NO input coordinate, and demotes its process outcome — it is not
      evidence about either the old tree or the new one (A3a's measured
      torn-read finding; the bwrap environment cannot produce this status
      because it verifies a throwaway copy).
    - FIXED CONTROLLER VERIFIER ARGV — the verifier executable and its fixed
      args are CONTROLLER CONSTANTS over the ONE shared focused-expression
      derivation (samizdat.agent.verify/focused-expr), so both providers and
      the ordinary lane verify the same namespaces for the same changed
      files. The verifier is the toolchain INSIDE the pinned image —
      babashka — so its identity is pinned by the image digest the
      environment's own coordinate carries, not by a host path. gates.edn is
      never consulted for any of it: that file is runtime-mutable by the
      very tier this gate judges.
    - NO NETWORK — smolvm is started without --net; outbound networking is
      off unless asked for, and nothing here asks.
    - NO HOST SECRETS — the machine manager forwards no host environment;
      the guest environment is CONSTRUCTED (TMPDIR, LANG, PATH over the
      in-image toolchain; HOME is set by the prelude past the privilege
      drop). Nothing is filtered, because nothing is inherited.
    - NO AUTHORITATIVE WRITES — the only host filesystem the machine can
      see is mounted read-only; the layer that absorbs writes lives and
      dies inside the machine.
    - BOUNDED STREAMS/TIME — each output stream is kept to a byte budget
      while its true size is counted; the wall clock is the caller's verify
      timeout capped by the pinned ceiling; the manager's own --timeout runs
      teardown-grace-ms BEHIND the host deadline as a backstop, because a
      deadline is not a program that chose an exit code.
    - REPLAY ATTRIBUTION — the invocation counter is claimed immediately
      before the spawn and moved by nothing else; every spawned run carries
      its envelope, so a future replay that restored a receipt without
      re-running is distinguishable from one that re-ran.

  LINUX + KVM ONLY, FAIL CLOSED. The substrate — Linux, a resolvable stat,
  the smolvm manager at a version whose isolation behaviour has been
  MEASURED (A3a/A3b/A3c measured 1.7.5; a version nobody has measured may be
  better, worse, or differently wrong, and the honest response to not
  knowing is refusal), a usable guest image matching the digest this host
  pinned, and a real minimum machine spawn — is probed before anything runs.
  When any of it is unavailable, `done` in the bounded lane is REFUSED with
  the reason; it never falls back to the bwrap environment, a direct host
  spawn, or any other euphemism.

  The guest image and its pinned digest are trusted-controller configuration
  (the harness process's own environment — SAMIZDAT_SMOLVM_IMAGE,
  SAMIZDAT_SMOLVM_IMAGE_SHA256), exactly the standing the bwrap
  environment's pinned verifier authority has: code-and-host, never
  gates.edn, never anything model-facing. Which provider the bounded lane
  uses is settled the same way, one namespace over
  (samizdat.security.verification-provider)."
  (:require [clojure.string :as str]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.verify :as verify]
            [samizdat.engine.proc :as proc]
            [samizdat.prompt :as prompt]
            [samizdat.security.canonical-edn :as cedn]
            [samizdat.security.secrets :as secrets]))

;; ═══════════════════════════════════════════════════════════════════════════
;; The pinned controller authority.
;;
;; Code, not gates.edn, for the same reason as the bwrap environment's
;; verifier authority: resources/*.edn is the surface a run can rewrite at
;; runtime, and an argv or image authority living there is one project/edit
;; away from a sandbox the party under observation can switch off.
;; ═══════════════════════════════════════════════════════════════════════════

(def manager-exec-name
  "The virtual machine manager this environment drives. Named once; nothing
  model-facing can spell it."
  "smolvm")

(def approved-manager-versions
  "Manager versions whose isolation behaviour has been measured — A3a/A3b/A3c
  measured one (killing the manager's front end leaves the machine running;
  cleanup means reaping the process TREE, descendants first). A version
  nobody has measured may be better, worse, or differently wrong; the honest
  response to not knowing is to refuse."
  #{"1.7.5"})

(def verifier-exec-name
  "The pinned verifier executable. It lives INSIDE the guest image — the
  A3c decision: the toolchain is in the image, no host tool path exists, and
  the image digest in the environment's coordinate is what pins which
  toolchain bytes ran. Never resolved against a host PATH."
  "bb")

(def verifier-fixed-args
  "The fixed argv between the executable and the one derived expression. The
  classpath is the focused-verify conventions' own source roots (gates.edn
  :root-strip-regex strips ^test/ ^gui/ ^src/), pinned here as a constant so
  the derivation stays one code path shared with the other lanes."
  ["--classpath" "src:test:gui" "-e"])

(def guest-paths
  "Where the guest half of the boundary lives, inside the image."
  {:input "/input"
   :work "/work"
   :tools "/opt/bbagent-tools"
   :prelude "/usr/local/bin/bbagent-prelude"})

(def prelude-contract
  "The argument-order contract the host and the image's prelude must agree
  on, checked by the prelude before anything is mounted. A binary and an
  image that disagreed would otherwise build a workspace and then run the
  wrong thing inside it."
  "1")

(def prelude-exit
  "The exit status the guest prelude uses when it never reached the argv: a
  command that could not be started did not fail — it did not run."
  125)

(def prelude-marker "bbagent-worker: prelude failed: ")

(def teardown-grace-ms
  "How much longer the machine manager's own deadline sits BEHIND the host
  deadline. The host deadline is the one that classifies a timeout (a program
  is free to exit 124 for its own reasons); the manager's is a backstop for
  the case where the host cannot reap the tree at all."
  5000)

(def resource-limits
  "The pinned in-VM and manifest policy. The stream budgets keep each output
  stream to a byte cap while its TRUE size is counted; the wall-clock ceiling
  caps what a caller may ask for; the manifest bounds fail the whole
  verification closed rather than walking an unbounded tree. Mechanism
  safety bounds, not tunables: the tier under observation must not be able
  to raise them."
  {:worker/cpus 2
   :worker/memory-mib 2048
   :worker/stdout-max-bytes 1048576        ;; 1 MiB kept per stream
   :worker/stderr-max-bytes 1048576
   :worker/timeout-ms 300000
   :manifest-max-entries 20000
   :manifest-max-bytes 2147483648})        ;; 2 GiB

(def workspace-exclusions
  "Project paths a run neither describes nor sees — the SAME name set the
  bwrap environment's workspace policy copies without and its input
  coordinate omits, so both verify environments mean the same thing by 'the
  verify input'. VCS internals and build caches are not inputs to running
  the focused tests, and the workload hides exactly what the manifest
  refused to describe: the prelude whites the excluded paths out of the
  overlay and masks the raw export."
  #{".git" ".cpcache" "target" "node_modules" ".cache"})

(def guest-environment
  "Exactly what a workload receives — CONSTRUCTED, not filtered. The measured
  behaviour of the machine manager is that it forwards no host environment at
  all, so nothing is being removed here and nothing needs to be: this map is
  the whole environment, and a host credential cannot be omitted from it by
  accident. HOME is absent deliberately — the prelude points it at a
  directory the workload owns, past the privilege drop."
  {"TMPDIR" "/tmp"
   "LANG" "C.UTF-8"
   "PATH" (str (:tools guest-paths)
               ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")})

(def ^:private manager-banner
  ;; The manager announces itself on stderr and has no quiet flag, so its
  ;; progress line would otherwise be reported as something the workload
  ;; wrote. It is removed, and its bytes removed from the count, so the
  ;; captured stream describes the workload alone. (The line terminator is
  ;; consumed separately: this runtime's regex engine has no \R.)
  #"Starting ephemeral machine \(vm-[0-9a-f]+\)\.\.\.")

(def guest-image-env "SAMIZDAT_SMOLVM_IMAGE")
(def guest-image-digest-env "SAMIZDAT_SMOLVM_IMAGE_SHA256")

(defn controller-image-config
  "The controller-owned image configuration. Kept as a seam so tests can
  supply real pinned bytes without mutating process state; neither key is
  sourced from model-editable policy. Both are required for availability."
  []
  {:path (System/getenv guest-image-env)
   :digest (System/getenv guest-image-digest-env)})

(defn- message
  "Every model-bound sentence this namespace emits comes through the
  bounded-evaluator template, like the bwrap environment's own messages —
  the words are prose the model reads, so they are editable without a
  rebuild."
  [data]
  (prompt/render "bounded-evaluator" data))

;; ═══════════════════════════════════════════════════════════════════════════
;; The machine manager: description and measured-version approval.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- linux? []
  (str/starts-with? (str (System/getProperty "os.name")) "Linux"))

(defn- abs-bin
  "The absolute path of `name` when the controller can execute it, else nil."
  [name]
  (when-let [p (fs/which name)]
    (when (fs/executable? p) (str p))))

(defn- parsed-version
  "The version number out of a manager's version banner."
  [reported]
  (some-> reported str str/trim (str/split #"\s+") last str/trim not-empty))

(defn describe-manager
  "What machine manager this host has, if any — reported rather than
  assumed, because the coordinate of the thing that provides the isolation
  belongs in the evidence for the isolation. Bounded, never throws."
  []
  (try
    (let [r (proc/run-bounded {:timeout-ms 15000} (abs-bin manager-exec-name)
                              "--version")]
      (if (and (= :exited (:status r)) (zero? (or (:exit r) 1)))
        {:worker/runtime manager-exec-name
         :worker/version (str/trim (str (:stdout r)))
         :worker/available? true}
        {:worker/runtime manager-exec-name
         :worker/available? false
         :worker/error (or (not-empty (str/trim (str (:stderr r))))
                           (:error/message r)
                           (str (:status r)))}))
    (catch Throwable t
      {:worker/runtime manager-exec-name
       :worker/available? false
       :worker/error (ex-message t)})))

(defn approved-manager
  "The manager this host has, when it is one whose isolation behaviour has
  been measured against the `approved` set. Fails closed twice over: once
  when there is no manager at all, and once when there is one nobody has
  measured. Pure over the description it is handed, so the refusal can be
  proved against a real manager rather than only reasoned about."
  ([]
   (approved-manager (describe-manager) approved-manager-versions))
  ([described approved]
   (when-not (:worker/available? described)
     (throw (ex-info
             (str "No machine manager is available to run verification; the "
                  "SmolVM environment refuses rather than spawning on the host")
             {:executor/manager manager-exec-name
              :executor/error (:worker/error described)
              :samizdat.smolvm-verification-env/error :no-manager})))
   (let [version (parsed-version (:worker/version described))]
     (when-not (contains? approved version)
       (throw (ex-info
               (str "The machine manager on this host is version " version
                    ", whose isolation behaviour has not been measured; "
                    "verification refuses rather than assuming it matches an "
                    "approved version")
               {:executor/manager manager-exec-name
                :executor/version version
                :executor/approved (vec (sort approved))
                :samizdat.smolvm-verification-env/error :manager-unmeasured})))
     {:version version :approval :recognized})))

;; ═══════════════════════════════════════════════════════════════════════════
;; The guest image: host-selected archive, digest-pinned.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- normalized-digest
  "A digest as bare lowercase hex, whether pinned with or without the
  sha256: prefix."
  [value]
  (let [v (str/trim (str value))]
    (when (seq v) (str/replace v #"^sha256:" ""))))

(defn- image-digest
  "The sha256 of the image archive's bytes, as a bounded sha256sum spawn.

  The canonical-EDN keeper's streaming digest is right for manifest-sized
  files and wrong here: this runtime's MessageDigest shim digests at roughly
  a megabyte per second, so a guest-image-sized archive (hundreds of MiB)
  costs minutes in-process while sha256sum reads it in under a second. Same
  standing as the stat spawn identity derivation uses: a trusted,
  controller-owned bounded spawn of a standard tool, never a shell. Returns
  nil when the tool is missing or its answer is not a digest — the caller
  reads that as no usable image."
   [path]
  (let [r (try (proc/run {:timeout-ms 120000} (abs-bin "sha256sum") (str path))
               (catch Throwable _ {:exit -1}))]
    (when (and (map? r) (not (:timeout r)) (zero? (or (:exit r) 1)))
      (let [first-line (first (str/split-lines (str (or (:out r) ""))))
            digest (some-> first-line (str/split #"\s+") first str/trim)]
        (when (and digest (re-matches #"[0-9a-f]{64}" digest))
          (str "sha256:" digest))))))

(defn guest-image
  "The configured guest image archive and the digest of the bytes that will
  run — trusted-controller configuration read from the harness process's own
  environment, never gates.edn and never anything model-facing. An expected
  digest may be pinned, in which case a different image refuses here rather
  than running and being noticed afterwards. Returns {:image …}, or nil
  under :reason when no usable image can be had."
   []
   (let [{:keys [path digest]} (controller-image-config)
         supplied (some-> path str/trim not-empty)
         pinned (normalized-digest digest)]
     (if (nil? supplied)
       {:reason :no-guest-image}
       (if (nil? pinned)
         {:reason :guest-image-unpinned}
       (let [path (try (str (fs/canonicalize supplied))
                       (catch Throwable _ nil))]
        (if (or (nil? path)
                (not (fs/exists? path {:nofollow-links true}))
                (not (fs/regular-file? path)))
          {:reason :no-guest-image}
           (if-let [actual-digest (image-digest path)]
             (let []
               (if (not= pinned (normalized-digest actual-digest))
                 {:reason :guest-image-digest-mismatch}
                 {:image {:path path
                          :digest actual-digest
                          :bytes (or (fs/size path) 0)}}))
             {:reason :no-guest-image})))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The project identity: derived from the project, never chosen.
;; ═══════════════════════════════════════════════════════════════════════════

(defn project-identity
  "Who a run against this project executes as, derived per run from the
  project root itself — the overlay's permissions are the project's
  permissions, so the workload must run as the tree's owner or it cannot
  write its own workspace, and one that runs as root can undo the parts of
  the workspace this design relies on. The uid/gid pair is read with a
  bounded stat spawn (this runtime's java.nio cannot read unix:uid), never
  a shell. A root-owned project has no non-root identity to derive and is
  refused rather than quietly run privileged. `stat-run` is the injection
  seam the tests use."
  ([root] (project-identity root {}))
  ([root {:keys [stat-run]}]
   (let [run (or stat-run proc/run)
         r (try (run {:timeout-ms 10000} "stat" "-c" "%u:%g" (str root))
                (catch Throwable _ {:exit -1}))]
     (if (and (map? r) (not (:timeout r)) (zero? (or (:exit r) 1)))
       (let [[uid gid] (map #(try (Long/parseLong (str/trim (str %)))
                                  (catch Throwable _ nil))
                            (str/split (str/trim (str (or (:out r) ""))) #":"))]
         (cond
           (or (nil? uid) (nil? gid))
           (throw (ex-info
                    "The project's owner identity could not be read; the SmolVM environment refuses rather than guessing an identity"
                    {:project/root (str root)
                     :samizdat.smolvm-verification-env/error :project-identity}))

           (zero? uid)
           (throw (ex-info
                   (str "This project is owned by root, so there is no "
                        "unprivileged identity to run its verification as; "
                        "execution refuses rather than running it with full "
                        "privileges")
                   {:project/uid uid
                    :samizdat.smolvm-verification-env/error :project-identity}))

           :else {:uid uid :gid gid}))
      (throw (ex-info
              "The project's owner identity could not be read; the SmolVM environment refuses rather than guessing an identity"
              {:project/root (str root)
               :samizdat.smolvm-verification-env/error :project-identity}))))))

(defn guest-identity
  "The validated uid:gid argument the prelude drops to. Pure over the
  identity map, so the root refusal is provable without a machine."
  [identity]
  (let [{:keys [uid gid]} identity]
    (when-not (and (integer? uid) (integer? gid) (pos? uid) (not (neg? gid)))
      (throw (ex-info
              "Worker identity must be a non-root uid and a gid"
              {:worker/identity identity
               :samizdat.smolvm-verification-env/error :project-identity})))
    (str uid ":" gid)))

;; ═══════════════════════════════════════════════════════════════════════════
;; The project input manifest and its coordinate (the private snapshot half).
;;
;; A run is bracketed by this manifest's coordinate. The workload sees
;; exactly what the manifest describes: the excluded names are whited out of
;; the overlay by the prelude (the HIDDEN list is the paths the walk refused,
;; taken from the manifest rather than from the exclusion names so the two
;; cannot drift), and the raw export at /input is masked after the overlay
;; is mounted. The manifest grammar is the SAME one the bwrap environment's
;; verify input speaks (:samizdat.ve/verify-input over
;; {:workspace/exclusions :workspace/entries}), so a tree verified by either
;; environment names the same input coordinate — pinned by test.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- lexical-path
  "The absolute path of `p` with . and .. resolved lexically — WITHOUT
  following the final link (or any component). Used to judge whether a link
  target stays under the root without resolving the target itself."
  [p]
  (str (fs/normalize (fs/absolutize (str p)))))

(defn- link-target-in-root?
  "Whether a link is faithful to hand over: a RELATIVE target that stays
  under `root`. An absolute target names a path the machine resolves inside
  its OWN filesystem, where it means something else (even when it lexically
  lies under the project root — /home/me/project/lib is not /work/lib); an
  escaping one climbs out of the tree. Neither is a containment hole (the
  machine is the boundary); both would make the manifest describe a tree the
  workload does not have, and the honest response is refusal — A3a's
  fidelity finding, ported."
  [root link]
  (let [target (str (fs/read-link link))]
    (and (not (str/starts-with? target "/"))
         (let [parent (some-> (fs/path (str link)) .getParent str)
               resolved (fs/path (lexical-path (str parent "/" target)))]
           (.startsWith resolved (fs/path (str root)))))))

(defn- walk-input
  "Collect the manifest entries for everything under `root`, exclusions
  applied BY NAME at every level and the refused paths recorded (they are
  the workload's hidden list), links described and never followed, budgets
  enforced BEFORE an entry lands (a terminal entry must not sneak one past
  the stated maximum — A3a's off-by-one, ported). Throws fail-closed on an
  unrepresentable symlink or a tree over budget."
  [root]
  (let [{:keys [manifest-max-entries manifest-max-bytes]} resource-limits]
    (letfn [(step [{:keys [entries excluded bytes]} entry prefix]
              (let [name (str (fs/file-name entry))]
                (if (contains? workspace-exclusions name)
                  {:entries entries
                   :excluded (conj excluded (if (empty? prefix)
                                              name (str prefix "/" name)))
                   :bytes bytes}
                  (let [rel (if (empty? prefix)
                              name (str prefix "/" name))
                        _ (when (>= (count entries) manifest-max-entries)
                            (throw (ex-info
                                    "Project input exceeds the manifest entry limit"
                                    {:manifest/entries manifest-max-entries
                                     :manifest/path rel
                                     :samizdat.smolvm-verification-env/error
                                     :input-too-large})))]
                    (cond
                      (fs/directory? entry {:nofollow-links true})
                      (walk! entry rel (conj entries {:path rel :kind :directory})
                             excluded bytes)

                      (fs/sym-link? entry)
                      (do (when-not (link-target-in-root? root entry)
                            (throw (ex-info
                                    (str "Project input contains a symbolic link "
                                         "the worker cannot be given a faithful "
                                         "copy of (absolute or escaping target); "
                                         "the SmolVM environment refuses to "
                                         "describe a tree it is not handing over")
                                    {:manifest/path rel
                                     :manifest/target (str (fs/read-link entry))
                                     :samizdat.smolvm-verification-env/error
                                     :unrepresentable-symlink})))
                          {:entries (conj entries {:path rel :kind :link
                                                   :target (str (fs/read-link entry))})
                           :excluded excluded
                           :bytes bytes})

                      :else
                      (let [size (or (fs/size entry) 0)]
                        (when (> (+ bytes size) manifest-max-bytes)
                          (throw (ex-info
                                  "Project input exceeds the manifest byte limit"
                                  {:manifest/bytes manifest-max-bytes
                                   :manifest/path rel
                                   :samizdat.smolvm-verification-env/error
                                   :input-too-large})))
                        {:entries (conj entries {:path rel :kind :file
                                                 :bytes size
                                                 :digest (str "sha256:"
                                                              (cedn/sha-256-path
                                                               entry))})
                         :excluded excluded
                         :bytes (+ bytes size)}))))))
            (walk! [dir prefix entries excluded bytes]
              (reduce (fn [acc entry] (step acc entry prefix))
                      {:entries entries :excluded excluded :bytes bytes}
                      (sort-by #(str (fs/file-name %)) (fs/list-dir dir))))]
      (walk! (str (fs/canonicalize root)) "" [] [] 0))))

(defn input-manifest
  "The project input manifest, its excluded paths, and its coordinate.
  Entries are sorted by path so the coordinate does not depend on the order
  a filesystem happened to enumerate; exclusions are recorded rather than
  applied silently, because a coordinate that quietly omits part of a tree
  describes a project that does not exist."
  [root]
  (let [{:keys [entries excluded bytes]} (walk-input root)
        payload {:workspace/exclusions (vec (sort workspace-exclusions))
                 :workspace/entries (vec (sort-by :path entries))}]
    (merge payload
           {:workspace/excluded-paths (vec (sort excluded))
            :workspace/entry-count (count entries)
            :workspace/bytes bytes
            :workspace/coordinate
            (cedn/coordinate :samizdat.ve/verify-input payload)})))

(defn input-coordinate
  "Just the coordinate of the tree the run is bracketed by — the same kind
  and grammar the bwrap environment's verify input uses over the same
  manifest payload, so both environments name one input the same way."
  [root]
  (:workspace/coordinate (input-manifest root)))

;; ═══════════════════════════════════════════════════════════════════════════
;; The derived verifier argv — pinned prefix plus ONE controller-derived
;; expression, shared with the other lanes.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- whitelisted-namespaces
  "The focused namespaces among `changed` — the same selection every lane
  shares (verify/test-file? + verify/ns-from-test-path's whitelist)."
  [changed]
  (->> changed (filter verify/test-file?) (keep verify/ns-from-test-path)
       distinct vec))

(defn focused-argv
  "The verifier argv for this environment: the PINNED in-image executable and
  fixed args, plus exactly one derived element — the focused -e expression
  (verify/focused-expr), whose namespaces passed the ns whitelist. nil when
  nothing among `changed` is a verifiable test (the gate then refuses, never
  trusts). The prefix is controller code; gates.edn is not consulted and
  cannot widen this — and the executable is never resolved against a host
  PATH, because the toolchain lives in the pinned image."
  [changed]
  (let [nses (whitelisted-namespaces changed)]
    (when (seq nses)
      (into [verifier-exec-name]
            (into (vec verifier-fixed-args) [(verify/focused-expr nses)])))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The manager command line: an image, one read-only mount, and data.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- machine-argv
  "The manager arguments after the executable itself. The guest command is
  the image's own prelude followed by DATA — a contract version, the
  identity to run as, the paths to hide, a working directory, then the
  pinned verifier argv — never shell source assembled here, and nothing a
  caller supplies is interpolated into anything that gets parsed."
  [{:keys [root image argv identity hidden host-timeout-ms]}]
  (-> ["machine" "run"
       "--image" image
       ;; Behind the host deadline; see teardown-grace-ms.
       "--timeout" (str (+ host-timeout-ms teardown-grace-ms) "ms")
       "--cpus" (str (:worker/cpus resource-limits))
       "--mem" (str (:worker/memory-mib resource-limits))
       ;; No --net: outbound networking is off unless asked for, and nothing
       ;; here asks. The project is the ONLY host path mounted, because the
       ;; toolchain is in the image.
       "-v" (str root ":" (:input guest-paths) ":ro")]
      (into (mapcat (fn [[k v]] ["-e" (str k "=" v)]))
            (into (sorted-map) guest-environment))
      (into ["--" (:prelude guest-paths) prelude-contract identity
             (str (count hidden))])
      (into hidden)
      (conj ".")
      (into argv)))

(defn host-timeout-ms
  "The host deadline for one run: the caller's verify timeout (the gate's
  own threshold when absent), capped by the pinned ceiling. The host
  deadline is the one that classifies a timeout."
  [timeout-ms]
  (min (or timeout-ms (gates/threshold :verify-timeout-ms))
       (:worker/timeout-ms resource-limits)))

(defn build-verification
  "Compose EVERYTHING one verification run spawns — the full manager argv,
  the workload's hidden paths, the derived identity, the bracketing input
  coordinate — without spawning it (the pure-with-effects half of the
  runner, like the bwrap environment's build-environment). Throws the
  catalogued refusals (unmeasured manager, unusable image, root-owned
  project, unrepresentable input) so a test can pin what would run without
  paying for the run."
  [root changed timeout-ms]
  (let [image (:image (guest-image))
        argv (focused-argv changed)
        manifest (input-manifest root)
        identity (guest-identity (project-identity root))]
    {:argv (into [(abs-bin manager-exec-name)]
                 (machine-argv {:root (str (fs/canonicalize root))
                                :image (:path image)
                                :argv argv
                                :identity identity
                                :hidden (:workspace/excluded-paths manifest)
                                :host-timeout-ms (host-timeout-ms timeout-ms)}))
     :hidden (:workspace/excluded-paths manifest)
     :identity identity
     :input-coordinate (:workspace/coordinate manifest)
     :verifier-argv argv
     :image image}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Substrate probing — Linux + KVM + a measured manager + a pinned image,
;; proven by one real machine spawn, and refusal rather than degradation.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- probe-substrate
  "The honest availability answer, in the refusals' catalogue order: Linux,
  a resolvable stat (identity derivation needs it), a manager at a measured
  version, a usable pinned guest image, and — the only part a config check
  cannot fake — one REAL minimum machine spawn through the prelude (an
  empty project mounted read-only, the harness identity, /bin/true). KVM
  disabled, a corrupt archive, or an image whose prelude disagrees with this
  host's contract all refuse here rather than at the first real run.
  Returns nil when everything needed is present, or a keyword reason."
  []
  (try
    (cond
      (not (linux?)) :not-linux
      (nil? (abs-bin "stat")) :no-stat
      (nil? (abs-bin "sha256sum")) :no-sha256sum
      :else
      (let [described (describe-manager)]
        (if-not (:worker/available? described)
          :no-manager
          (let [approval (try (approved-manager described approved-manager-versions)
                              (catch Throwable t
                                (:samizdat.smolvm-verification-env/error
                                 (ex-data t))))]
            (if (keyword? approval)              ; the refusal reason itself
              (if (= :no-manager approval) :no-manager approval)
              (let [image (guest-image)]
                (if-some [reason (:reason image)]
                  reason
                  (let [probe-root (str (fs/create-temp-dir
                                          {:prefix "samizdat-smve-probe-"}))]
                    (try
                      (let [r (apply proc/run-bounded
                                     {:timeout-ms 60000
                                      :out-max-bytes 4096
                                      :err-max-bytes 4096}
                                     (abs-bin manager-exec-name)
                                     (machine-argv
                                      {:root probe-root
                                       :image (:path (:image image))
                                       :argv ["/bin/true"]
                                       :identity (guest-identity
                                                  (project-identity probe-root))
                                       :hidden []
                                       :host-timeout-ms 45000}))]
                        (when (or (not= :exited (:status r))
                                  (not (zero? (or (:exit r) 1))))
                          :sandbox-unavailable))
                      (finally
                        (try (fs/delete-tree probe-root)
                             (catch Throwable _ nil))))))))))))
    (catch Throwable _ :sandbox-unavailable)))

(def ^:private substrate
  "Memoized: the probe boots a machine, and availability does not change
  within a run. Tests that need the other branch redef available?/
  unavailable-reason (or the image/manager resolvers before first deref)
  rather than the probe."
  (delay (probe-substrate)))

(defn available?
  "Whether this host can run the SmolVM VerificationEnvironment at all. When
  false, bounded `done` refuses through the provider selector — never a
  direct host spawn and never a silent fallback to another environment."
  []
  (nil? @substrate))

(defn unavailable-reason []
  (or @substrate :unknown))

(defn- resolved-image
  "The image the probe approved (re-read, not cached, so a run holds the
  same standard availability was held to) — nil when somehow gone."
  []
  (when (available?) (:image (guest-image))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The ExecutionEnvironment EDN SPI (RFC-012): description, coordinate,
;; envelopes. The description names WHAT IT IS in inert vocabulary the
;; conformance fixtures pin; `coordinate` names the FULL policy.
;; ═══════════════════════════════════════════════════════════════════════════

(def envelope-version
  "The EDN SPI's envelope version this adapter emits (RFC-012). It stays 1
  until a rule changes rendered bytes; then it is 2, and coordinates taken
  under 1 remain what they were."
  1)

(defn environment-description
  "The INERT description of this environment — the SPI's description
  vocabulary. Deliberately names the SHAPE and the pinned identity (which
  manager at which measured version, which guest image by digest, which
  verifier by in-image constant) and no host paths: this is the
  cross-repository name, canonicalized to the same digest by any conformant
  keeper. The image digest participates in the identity, so changing the
  image changes the coordinate of every run attributed to this environment."
  []
  (let [described (describe-manager)
        version (parsed-version (:worker/version described))
        image (resolved-image)]
    {:executor/type :samizdat/smolvm-verification-env
     :executor/mode :verify-only
     :executor/operations #{:describe :verify}
     :executor/manager manager-exec-name
     :executor/version (or version "unresolved")
     :executor/approval :recognized
     :executor/network :none
     :executor/guest {:image :bbagent/worker-image
                      :image/digest (or (:digest image) "unresolved")
                      :privilege :unprivileged
                      :identity :derived-from-project-owner
                      :capabilities :none
                      :prelude :in-image
                      :prelude/contract prelude-contract
                      :environment :constructed
                      :host-environment :not-inherited}
     :executor/workspace {:model :overlayfs
                          :project-mount :read-only
                          :lifecycle :ephemeral-machine-per-execution
                          :excluded-paths :hidden-from-workload}
     :executor/verifier {:toolchain :in-image
                         :exec verifier-exec-name
                         :fixed-args (vec verifier-fixed-args)
                         :authority :controller-pinned}
     :executor/limits (into (sorted-map)
                            (select-keys resource-limits
                                         [:worker/cpus :worker/memory-mib
                                          :worker/stdout-max-bytes
                                          :worker/stderr-max-bytes
                                          :worker/timeout-ms]))}))

(defn environment-coordinate
  "The canonical-EDN coordinate of the description — the name a second
  repository can check (kind :bb4t/execution-environment, the same kind and
  grammar the bwrap environment's description uses). Recomputed, never
  cached, so attribution and description cannot drift apart."
  []
  (cedn/coordinate :bb4t/execution-environment (environment-description)))

(defn- envelope!
  "Check one outgoing envelope against its kind's contract — the frame, the
  EXACT key set, and inertness — and return it (the same construction check
  the bwrap environment applies; canonical-tree throws on anything alive or
  ambiguous, so a bug that misshapes an envelope fails HERE)."
  [envelope required optional]
  (let [present (set (keys envelope))
        allowed (into required optional)
        missing (not-empty (vec (remove present required)))
        extra (not-empty (vec (remove allowed present)))]
    (when (or missing extra)
      (throw (ex-info "envelope key set is not its kind's"
                      {:samizdat.smolvm-verification-env/error :envelope-keys
                       :missing missing :extra extra})))
    (cedn/canonical-tree envelope)
    envelope))

(defn describe-envelope
  "What this environment IS, as the SPI's describe envelope: the inert
  description beside its own recomputed canonical coordinate."
  []
  (let [description (environment-description)]
    (when-not (keyword? (:executor/type description))
      (throw (ex-info "environment description names its type"
                      {:samizdat.smolvm-verification-env/error :description-type})))
    (envelope!
     {:spi/version envelope-version
      :spi/kind :spi.environment/describe
      :environment/description description
      :environment/coordinate (cedn/coordinate
                               :bb4t/execution-environment description)}
     #{:spi/version :spi/kind :environment/description :environment/coordinate}
     #{})))

(def ^:private refusal-categories
  "The catalogued SPI refusal for each controller refusal reason — this
  environment's OWN refusal points, none invented (RFC-012: catalogues are
  per-environment while the :spi.refusal/ namespace is shared): a non-Linux
  host, a missing identity-derivation or image-digest tool, a machine
  manager that is absent or whose isolation nobody has measured, a guest
  image that is absent or not the one pinned, a kernel/manager that will
  not run the minimum machine, a project with no unprivileged identity,
  changed files with nothing verifiable among them, and the bucket for a
  reason this catalogue does not name (a refusal all the same)."
  {:not-linux :spi.refusal/not-linux
   :no-stat :spi.refusal/no-stat
   :no-sha256sum :spi.refusal/no-digest-tool
   :no-manager :spi.refusal/manager-unavailable
    :manager-unmeasured :spi.refusal/manager-unmeasured
    :no-guest-image :spi.refusal/guest-image-unusable
    :guest-image-unpinned :spi.refusal/guest-image-unpinned
   :guest-image-digest-mismatch :spi.refusal/guest-image-digest-mismatch
   :sandbox-unavailable :spi.refusal/sandbox-unavailable
   :project-identity :spi.refusal/project-identity
   :no-verifiable-test :spi.refusal/nothing-verifiable})

(def ^:private refusal-reasons
  "The authored reason string for each catalogued refusal. Noun phrases,
  never sentences and never host specifics: a refusal crosses the same
  repository boundary a description does and carries the same inertness
  obligation. The MODEL-facing sentence is a different vocabulary entirely —
  it lives in the bounded-evaluator template — and the two do not meet."
  {:spi.refusal/not-linux
   "verification environment requires Linux; host spawn refused"
   :spi.refusal/no-stat
   "stat executable missing; project identity unreadable"
   :spi.refusal/no-digest-tool
   "sha256sum executable missing; guest image digest unavailable"
   :spi.refusal/manager-unavailable
   "machine manager unavailable; machine isolation unavailable"
   :spi.refusal/manager-unmeasured
   "machine manager version unmeasured; measured versions approved"
    :spi.refusal/guest-image-unusable
    "guest image archive absent, unusable, digest unreadable"
    :spi.refusal/guest-image-unpinned
    "guest image digest absent; pinned digest required"
   :spi.refusal/guest-image-digest-mismatch
   "guest image digest differs; pinned digest required"
   :spi.refusal/sandbox-unavailable
   "minimum machine spawn failed; virtualization substrate unavailable"
   :spi.refusal/project-identity
   "project owner identity unreadable, root-owned, underived"
   :spi.refusal/nothing-verifiable
   "changed files include zero whitelisted test namespaces"
   :spi.refusal/unknown
   "verification environment refused; reason uncatalogued"})

(defn refusal-envelope
  "A controller refusal reason as the SPI's availability-refusal envelope.
  Which category a reason maps to is decided here, once; an uncatalogued
  reason refuses as :spi.refusal/unknown rather than being guessed at."
  [reason]
  (let [category (get refusal-categories reason :spi.refusal/unknown)]
    (envelope!
     {:spi/version envelope-version
      :spi/kind :spi.environment/availability
      :environment/available? false
      :environment/refusal {:refusal/category category
                            :refusal/reason (get refusal-reasons category)}}
     #{:spi/version :spi/kind :environment/available? :environment/refusal}
     #{})))

(defn availability-envelope
  "Whether this host can run this environment, as the SPI's availability
  envelope: exactly one of the coordinate (available) or the catalogued
  refusal — a reader never has to decide which to believe. The answer is the
  probe's (a real minimum-machine spawn), never a config check."
  []
  (if (available?)
    (envelope!
     {:spi/version envelope-version
      :spi/kind :spi.environment/availability
      :environment/available? true
      :environment/coordinate (environment-coordinate)}
     #{:spi/version :spi/kind :environment/available? :environment/coordinate}
     #{})
    (refusal-envelope (unavailable-reason))))

(defn- sha256 [^String s]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes s "UTF-8")))))

(defn coordinate
  "A stable name for the pinned POLICY itself — manager approval set,
  ceilings, verifier authority, guest contract, workspace exclusions, and
  the image digest when one is resolved. This names the full policy where
  the SPI description deliberately names only the shape; the journal's
  ship-verify row carries it as :verify-env (the bwrap environment's
  `coordinate` is its analogue)."
  []
  (str "js1-smve/v1:"
       (sha256 (pr-str {:manager [manager-exec-name
                                  (vec (sort approved-manager-versions))]
                        :limits (into (sorted-map) resource-limits)
                        :verifier [verifier-exec-name verifier-fixed-args]
                        :guest {:prelude-contract prelude-contract
                                :environment (into (sorted-map) guest-environment)
                                :digest (or (:digest (resolved-image)) :unresolved)}
                        :workspace {:exclusions (vec (sort workspace-exclusions))}}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The invocation counter.
;;
;; One environment per harness process, so the counter is process-local. It
;; is incremented BEFORE the machine spawn: the index read when a run returns
;; is that run's index, and the probe, refusals, manifest failures and
;; failed spawns-never-reached never claim one, because none of them
;; attempted an execution. The durable order of verifications is the
;; journal's; this index is what a future replay must not move.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private invocations (atom 0))

(defn invocation-count
  "How many machine verifications this process's environment has attempted.
  Real spawns only — the substrate probe, refused requests and input
  failures never move it; a future replay must not either."
  []
  @invocations)

(defn- claim-invocation!
  "Claim the next invocation index, immediately before the machine spawn."
  []
  (swap! invocations inc))

;; ═══════════════════════════════════════════════════════════════════════════
;; The run.
;; ═══════════════════════════════════════════════════════════════════════════

(defn request-run-refusal
  "Why a requested verification cannot run here, or nil when it may — the
  gate every run request passes FIRST: an unavailable substrate (Linux, the
  measured manager, the pinned image, a kernel that runs the minimum
  machine), or nothing verifiable among the changed paths. A refused request
  never spawns and never moves the invocation index."
  [changed]
  (cond
    (not (available?)) (unavailable-reason)
    (nil? (focused-argv changed)) :no-verifiable-test
    (nil? (resolved-image)) :no-guest-image
    :else nil))

(defn- refused
  "The result of a run request this environment REFUSED, in the shape the
  bounded lane reads: substrate and image refusals carry :unavailable? true
  so the caller fails closed on the environment; the nothing-verifiable
  refusal reads as ordinary not-green evidence (the model can fix that one
  by writing a test). Every refusal carries its catalogued EDN-SPI
  spelling."
  [reason]
  (let [base (case reason
               :no-verifiable-test
               {:green? false :timeout? false
                :output (message {:ve-no-test true})}

               ;; :not-linux, :no-stat, :no-manager, :manager-unmeasured,
               ;; :no-guest-image, :guest-image-digest-mismatch,
               ;; :sandbox-unavailable, :project-identity.
               {:green? false :timeout? false :unavailable? true
                :reason reason
                :output (message {:ve-unavailable true})})]
    (assoc base :refusal (refusal-envelope reason))))

(defn- workload-stderr
  "The manager's own banner stripped from the captured stderr — and its
  bytes from the count — so :stderr and its byte count describe the workload
  alone."
  [result]
  (let [text (str (:stderr result))
        total (or (:stderr/bytes result) 0)]
    (if-let [banner (re-find manager-banner text)]
      (let [rest (subs text (count banner))
            newline? (str/starts-with? rest "\n")
            cut (+ (count banner) (if newline? 1 0))]
        {:text (subs text (min cut (count text)))
         :bytes (max 0 (- total (count (.getBytes ^String banner "UTF-8"))
                          (if newline? 1 0)))})
      {:text text :bytes total})))

(defn- prelude-failure?
  "Whether the run ended in the prelude's own refusal — the machine never
  reached the argv. The exit status alone cannot say it (a program is free
  to exit 125 for its own reasons); the marker can."
  [{:keys [status exit]} stderr]
  (and (= :exited status)
       (= prelude-exit exit)
       (str/includes? (str stderr) prelude-marker)))

(defn- capped-stream
  "One captured stream as the run's bounded capture: the kept text (the
  drain already bounded it; an honest truncation marker is appended when the
  workload wrote more than was kept), the stream's TRUE byte count — what
  the workload wrote, not what was kept — and whether that happened."
  [text total-bytes max-bytes]
  {:text (str text (when (> (or total-bytes 0) max-bytes)
                     (message {:ve-truncated true})))
   :bytes (or total-bytes 0)
   :truncated? (> (or total-bytes 0) max-bytes)})

(def ^:private worker-failure-reason
  "The run envelope's error string for a run whose spawn failed after the
  invocation was claimed. A fixed authored phrase, never the exception
  message the model-bound :output carries: an exception message can name
  host paths, and envelope data crosses the same boundary a description
  does."
  "verification environment run failed")

(defn- identity-refusal
  "The :project-identity refusal as a run RESULT (the derivation threw
  inside run's input half; the same refused shape, reason and envelope, with
  the specific failure as model-facing evidence)."
  [ex]
  (assoc (refused :project-identity)
         :output (message {:ve-run-failed true :reason (ex-message ex)})))

(defn run
  "Verify `changed` inside the SmolVM VerificationEnvironment rooted at
  `root`, and report the shape the bounded lane judges:
  {:green? :timeout? :exit :output} plus {:unavailable? true :reason k}
  when the substrate, the image or the project identity is missing — which
  the caller reads as a REFUSAL, never as red tests and never as licence to
  spawn directly. A refused request also carries its catalogued :refusal
  envelope.

  A run that spawned carries, additively, what the ExecutionEnvironment EDN
  SPI (RFC-012) requires a run to be attributable by: :invocation-index
  (claimed immediately before the spawn — monotonic across this process's
  REAL spawns), :attribution (the description's canonical coordinate and
  the environment's type), :input-coordinate (present ONLY when the project
  was stable for the whole run — the overlay's lower layer is the LIVE host
  tree, so a project that moved under the run is reported :project-changed
  by `verify-envelope` and carries no coordinate), :project/input-stable?,
  :duration-ms, and each stream's bounded capture :stdout/:stderr
  ({:text :bytes :truncated?}). Never throws: an input or spawn failure
  reads as not-green with its message as evidence.

  The machine is ephemeral: it is destroyed with the process tree however
  the run ends, so nothing inside it can outlive the call."
  [root changed timeout-ms]
  (if-let [refusal (request-run-refusal changed)]
    (refused refusal)
    (try
      (let [before (input-manifest root)
            identity (try (guest-identity (project-identity root))
                          (catch Throwable t
                            (if (= :project-identity
                                   (:samizdat.smolvm-verification-env/error
                                    (ex-data t)))
                              (identity-refusal t)
                              (throw t))))]
        (if-not (string? identity)
          identity                                   ; the refusal result
          (let [image (:path (resolved-image))
                argv (focused-argv changed)
                hidden (:workspace/excluded-paths before)
                host-ms (host-timeout-ms timeout-ms)
                full-argv (into [(abs-bin manager-exec-name)]
                                (machine-argv
                                 {:root (str (fs/canonicalize root))
                                  :image image
                                  :argv argv
                                  :identity identity
                                  :hidden hidden
                                  :host-timeout-ms host-ms}))
                ;; The invocation index is claimed immediately before the
                ;; spawn: the counter's rule, same as the bwrap lane's.
                index (claim-invocation!)
                started (System/nanoTime)
                raw (try (apply proc/run-bounded
                                {:timeout-ms host-ms
                                 :out-max-bytes (:worker/stdout-max-bytes
                                                 resource-limits)
                                 :err-max-bytes (:worker/stderr-max-bytes
                                                 resource-limits)}
                                full-argv)
                         ;; A spawn failure is a FAILED RUN, not a refused
                         ;; request: the index was claimed, so the attempt
                         ;; is attributable.
                         (catch Throwable t
                           {:status :start-failure
                            :error/message (ex-message t)}))
                r (merge {:stdout "" :stdout/bytes 0 :stdout/truncated? false
                          :stderr "" :stderr/bytes 0 :stderr/truncated? false}
                         raw)
                duration-ms (long (quot (- (System/nanoTime) started) 1000000))
                stderr (workload-stderr r)
                ;; The bracket's far side: a project that moved while the
                ;; machine ran is not a project that was verified.
                after (try (input-coordinate root)
                           (catch Throwable _ ::moved))
                stable? (= (:workspace/coordinate before) after)
                status (cond
                         (= :timeout (:status r)) :timeout
                         (= :start-failure (:status r)) :worker-failure
                         (prelude-failure? r (:text stderr)) :worker-failure
                         :else :completed)
                known (secrets/known-values (into {} (System/getenv)))
                out-stream (capped-stream (:stdout r) (:stdout/bytes r)
                                          (:worker/stdout-max-bytes
                                           resource-limits))
                err-stream (capped-stream (:text stderr) (:bytes stderr)
                                          (:worker/stderr-max-bytes
                                           resource-limits))
                output (secrets/redact
                        (str (:text out-stream) "\n" (:text err-stream)) known)
                base {:invocation-index index
                      :duration-ms duration-ms
                      :attribution {:environment/coordinate (environment-coordinate)
                                    :environment/type
                                    :samizdat/smolvm-verification-env}
                      :project/input-stable? stable?
                      ;; Present when and only when the project was stable
                      ;; for the whole run — a moved project carries NO
                      ;; coordinate, not a nil one (bbagent's shape: no key
                      ;; at all is the claim that cannot be misread).
                      :input-coordinate (when stable?
                                          (:workspace/coordinate before))
                      :stdout {:text (secrets/redact (:text out-stream) known)
                               :bytes (:bytes out-stream)
                               :truncated? (:truncated? out-stream)}
                      :stderr {:text (secrets/redact (:text err-stream) known)
                               :bytes (:bytes err-stream)
                               :truncated? (:truncated? err-stream)}
                      :worker/status status}]
            ;; Nil fields are dropped: an absent coordinate and a present-
            ;; but-nil one are the same refusal to claim an input.
            (into {} (remove (fn [[_ value]] (nil? value)))
                  (merge base
                         (case status
                           :timeout {:green? false :timeout? true :output output}
                           :worker-failure
                           {:green? false :timeout? false
                            :output (str output "\n"
                                         (message
                                          {:ve-run-failed true
                                           :reason (or (:error/message r)
                                                       (str/trim
                                                        (str (:text stderr))))}))}
                           ;; :completed — the exit is present whenever the
                           ;; workload exited; green only when it chose zero
                           ;; AND the input was stable for the whole run.
                           {:green? (and (zero? (or (:exit r) 1)) stable?)
                            :timeout? false
                            :exit (:exit r)
                            :output output}))))))

      (catch Throwable e
        ;; An input failure (unrepresentable symlink, manifest over budget)
        ;; is not-green evidence the model can act on, not a refusal: the
        ;; model can fix the tree. The catalogued refusals all refused above.
        {:green? false :timeout? false
         :output (message {:ve-run-failed true :reason (ex-message e)})}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The run envelope.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private run-statuses
  "The statuses a verify run envelope may carry — the verify-only subset of
  the SPI's vocabulary, plus :project-changed, which only LIVE-TREE
  environments can produce (the bwrap environment verifies a throwaway copy
  that cannot move; this one brackets the authoritative tree)."
  #{:completed :timeout :worker-failure :project-changed})

(defn verify-envelope
  "One `run` result as the SPI's run envelope (:spi.execution/run): the
  invocation index, the attribution, the input (its coordinate when and only
  when the project was stable; :input/project-changed when it moved, with
  the process outcome DEMOTED to :output/process so an unanchored run cannot
  pattern-match as ordinary success), the status, the exit when and only
  when the workload actually exited (:completed), each stream's capture with
  its TRUE byte count, the wall clock, and the disposition (:terminated —
  the machine is ephemeral and destroyed however the run ends). Fields that
  are nil are dropped.

  nil when the result is not a run: a refused request or a failed input
  produced no spawn, claimed no index, and so has no execution to envelope."
  [result]
  (when (and (map? result) (:invocation-index result))
    (when-not (pos? (:invocation-index result))
      (throw (ex-info "invocation index starts at one"
                      {:samizdat.smolvm-verification-env/error :envelope-index
                       :invocation-index (:invocation-index result)})))
    (let [worker-status (:worker/status result)
          _ (when-not (contains? #{:completed :timeout :worker-failure}
                                 worker-status)
              (throw (ex-info "unknown worker status"
                              {:samizdat.smolvm-verification-env/error
                               :envelope-status
                               :worker/status worker-status})))
          stable? (true? (:project/input-stable? result))
          exited? (integer? (:exit result))
          status (cond
                   (not stable?) :project-changed
                   (:timeout? result) :timeout
                   exited? :completed
                   :else :worker-failure)
          _ (when-not (contains? run-statuses status)
              (throw (ex-info "unknown run status"
                              {:samizdat.smolvm-verification-env/error
                               :envelope-status
                               :output/status status})))
          stream (fn [s]
                   {:stream/text (:text s)
                    :stream/bytes (:bytes s)
                    :stream/truncated? (:truncated? s)})]
      (envelope!
       (into {} (remove (fn [[_ value]] (nil? value)))
             {:spi/version envelope-version
              :spi/kind :spi.execution/run
              :run/invocation-index (:invocation-index result)
              :run/attribution (:attribution result)
              :run/input (if stable?
                           {:input/coordinate (:input-coordinate result)}
                           {:input/stability :input/project-changed})
              :output/status status
              :output/exit (when (and (= :completed status) exited?)
                             (:exit result))
              ;; The demotion is mandatory, not decorative: a changed
              ;; project carries its process outcome under :output/process
              ;; — nothing, anywhere, reads it as the run's own.
              :output/process (when (= :project-changed status)
                                (cond-> {:process/status worker-status}
                                  exited? (assoc :process/exit (:exit result))))
              :output/stdout (stream (:stdout result))
              :output/stderr (stream (:stderr result))
              :output/duration-ms (:duration-ms result)
              :output/error (when (= :worker-failure status)
                              worker-failure-reason)
              :run/disposition :terminated})
       #{:spi/version :spi/kind :run/invocation-index :run/attribution
         :run/input :output/status :output/stdout :output/stderr
         :output/duration-ms :run/disposition}
       #{:output/exit :output/process :output/error}))))
