;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.verification-env
  "The M2 VerificationEnvironment — the controller-owned, fail-closed sandbox
  the bounded lane's `done` verification runs in.

  What this replaces, and why. M2's first cut ran the derived focused argv
  directly on the host (the old verify/run-bounded-verify): structured argv,
  pinned cwd, scrubbed environment, bounded timeout, redacted output. That
  held the LINE of authority — nothing the model wrote selected an executable
  or an argv element — but the verifier itself ran with the harness's
  ordinary filesystem reach: a hostile test namespace could write outside the
  project root, read whatever the harness user could read, open sockets, and
  leave daemons behind. The trust boundary stopped at ARGV; the process was
  still ours.

  This namespace moves the whole run into a bubblewrap (bwrap) sandbox the
  CONTROLLER owns, fail-closed on every axis:

    - PRIVATE WORKSPACE — the project root is copied into a throwaway stage
      (the authoritative tree itself is never bound), so no verification
      write can reach it, and the stage is deleted after the run however it
      ends.
    - NO NETWORK — the sandbox's net namespace is unshared; loopback is the
      only interface and it is down.
    - NO HOST SECRETS OR CONFIG — the visible filesystem is an explicit
      allowlist: the system runtime read-only (/usr, /usr/local, plus the
      merged-usr symlinks), the pinned verifier's own checkout read-only,
      and the dependency caches (~/.gitlibs, ~/.m2/repository, ~/.jolt)
      read-only under a PRIVATE /home. No /etc, no host $HOME, no host /tmp.
      The environment is CONSTRUCTED, not filtered: child-env below is the
      whole environment (HOME/PWD/TMPDIR/JOLT_PWD/LANG/PATH pinned to
      sandbox values, plus the controller's own JOLT_CHEZ toolchain pin when
      it resolved one), so no host variable — credential-shaped or not, in
      the harness process's own environment — can reach the child by
      inheritance, omission or accident.
    - PINNED IMMUTABLE VERIFIER AUTHORITY — the verifier executable and its
      fixed argv are CONTROLLER CONSTANTS (verifier-exec-name,
      verifier-fixed-args below), resolved to an absolute path by the
      controller. They are deliberately NOT gates.edn data: gates.edn is
      runtime-mutable by the very tier this gate judges, so an argv
      authority read from it is an authority the party under observation can
      rewrite — the same reason files/run-config? and the evaluator's
      mechanism-bounds are code, not policy data. The one variable argv
      element is the focused -e expression derived from the run's own edit
      receipts through the namespace whitelist; a crafted file name can
      shrink the argv toward empty (where the gate refuses) and never widen
      it.
    - TOTALLY BOUNDED RESOURCES — every axis the workload can consume is
      either bounded or does not exist. The prebuilt stage (workspace copy,
      caches, verifier checkout) is bound READ-ONLY: the workload cannot
      write host disk AT ALL through the sandbox. The only writable storage
      is the sized tmpfs mounts (/tmp, /run, /var/tmp, and /dev with the
      device nodes re-bound onto it) — each capped by --size, so a RAM flood
      dies of its own ENOSPC. prlimit bounds each process's address space
      (RLIMIT_AS — the memory bound available without cgroups), its open
      files (RLIMIT_NOFILE — the file-count bound: every file write needs
      an fd), per-file bytes (RLIMIT_FSIZE — the redirected stdout/stderr
      spool included, so an output flood dies of its own SIGXFSZ) and
      process count (RLIMIT_NPROC); only the first capture-bytes are read
      back; the wall clock and the scoped process facility's tree reaping
      close the run. A substrate that cannot express this vocabulary
      (bwrap without sized tmpfs, prlimit without the rlimits) fails the
      availability probe — the capability refuses rather than running
      unbounded.
    - CLEANUP AND REAPING — the sandbox is a PID namespace whose init is the
      verifier, so daemons die with it; the spawn goes through
      samizdat.engine.proc, which SIGTERM/SIGKILLs the host-side tree on
      timeout; the stage is deleted in a finally regardless.

  The spawn itself goes through the existing scoped process facility
  (samizdat.engine.proc over jolt.process) with a structured argv. The
  controller composes no shell and no string that the model authored; the
  facility's own implementation of exec is the runtime's business, and this
  namespace claims only what it controls: every element of the argv it hands
  over.

  LINUX-ONLY, FAIL CLOSED. The substrate — Linux, bwrap, prlimit, unprivileged
  user namespaces, a resolvable pinned verifier — is probed before anything
  runs. When any of it is unavailable, `done` in the bounded lane is REFUSED
  with the reason; it never falls back to a direct host spawn, because the
  fallback would be the very thing this environment exists to make
  impossible.

  The environment also speaks the repository-neutral ExecutionEnvironment
  EDN SPI (RFC-012), kept on this side by samizdat.security.canonical-edn:
  describe/availability envelopes name WHAT it is and WHETHER one can be
  had; every real run's result carries its attribution, input coordinate,
  invocation index, duration and bounded capture additively; and
  verify-envelope projects a run into the run envelope a second repository
  can check. The envelopes are the SAME inert vocabulary the fixtures pin,
  and they are checked against their kind's exact key set on the way out —
  nothing alive or ambiguous leaves this namespace dressed as envelope
  data.

  The ordinary lane is untouched: `run-verify`/`focused-cmd` (verify.clj)
  still run the project's own command through sh -c exactly as before. The
  two lanes share one derivation — verify/focused-expr — so they verify the
  same namespaces for the same changed files (the parity the
  verification-env tests pin)."
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
;; Deliberately code, not gates.edn: resources/*.edn is the surface a run can
;; rewrite at runtime (AGENTS.md — the workflow is data the agent can edit),
;; and an argv authority living there is one project/edit away from
;; `{:argv-prefix [\"sh\" \"-c\" \"exit 0\"]}` — a verification gate the party
;; it judges can switch off. Same standing as files/run-config? and the
;; evaluator's mechanism-bounds: a ceiling the tier under observation must
;; not be able to raise. The ordinary lane's :cmd-prefix stays data because
;; the ORDINARY lane's model already holds shell authority there — it is
;; handed sh -c by design.
;; ═══════════════════════════════════════════════════════════════════════════

;; Forward reference: `coordinate` names the FULL policy, which includes the
;; constructed child environment child-env defines further down.
(declare child-env)

(def verifier-exec-name "jolt")
(def verifier-fixed-args ["-A:test" "-e"])

(def resource-limits
  "The pinned in-sandbox resource policy (prlimit and bwrap). TOTAL bounds,
  each axis covered by the mechanism that can actually enforce it unprivileged:

    :fsize-bytes  RLIMIT_FSIZE — per-file byte cap; the redirected
                  stdout/stderr spool included, so an output flood dies of
                  its own SIGXFSZ at the bound instead of filling the host.
    :nproc        RLIMIT_NPROC — process sprawl (daemon forking) bound.
    :as-bytes     RLIMIT_AS — each process's address space: the memory
                  bound available without cgroups. Generous on purpose (the
                  verifier is a real runtime), but it is a ceiling the
                  tier under observation cannot raise from inside.
    :nofile       RLIMIT_NOFILE — open-file ceiling: the file-count bound
                  (every file write needs an fd). Matches the ulimit the
                  project's own suite runs under (deps.edn :tasks test).
    :tmpfs-bytes  --size on each writable tmpfs mount (/tmp, /run,
                  /var/tmp): the total a workload can write to RAM-backed
                  storage, per mount. A flood dies of its own ENOSPC.
    :dev-bytes    --size on the /dev tmpfs the device nodes are re-bound
                  onto — /dev is writable tmpfs too, so it is sized like
                  the rest rather than left as the one unbounded mount.
    :capture-bytes  how much of each spool the harness reads back.

  With the stage bound read-only (sandbox-argv), these add up to: zero
  writes to host disk, RAM-backed writes capped per mount, per-process
  memory and file counts capped, per-file bytes capped, and the wall clock
  over all of it. Aggregate CPU and aggregate anonymous-memory control needs
  a delegated cgroup and is explicitly not claimed by this provider.
  Mechanism safety bounds, not tunables: the tier under observation must not
  be able to raise them."
  {:fsize-bytes 67108864                     ;; 64 MiB per file
   :nproc 128
   :as-bytes 4294967296                      ;; 4 GiB address space per process
   :nofile 1024
   :tmpfs-bytes 1073741824                   ;; 1 GiB per writable tmpfs mount
   :dev-bytes 1048576                        ;; 1 MiB /dev tmpfs (nodes only)
   :capture-bytes 131072})                   ;; 128 KiB read back per stream

(def dev-nodes
  "The device nodes bound onto the sized /dev tmpfs. bwrap's --dev mounts an
  UNSIZED tmpfs, so it is not used: the nodes a verifier realistically needs
  are bound individually onto a tmpfs this policy sizes, and /dev/fd and
  friends are recreated as the symlinks they are on the host."
  ["null" "zero" "full" "random" "urandom"])

(def workspace-policy
  "The pinned private-workspace policy: what the controller copies and what it
  refuses to pay for. The exclusions are verification INPUTS' complement —
  VCS internals and build caches are not inputs to running the focused tests,
  and the largest of them (.git, node_modules, target) would make the copy
  cost dominate the run. The file/byte budget fails the whole verification
  closed rather than copying an unbounded tree."
  {:excluded-names #{".git" ".cpcache" "target" "node_modules" ".cache"}
   :max-files 20000
   :max-bytes 2147483648})                    ;; 2 GiB

(def ^:private home-cache-names
  "Dependency caches the verifier may read (never write), rebound read-only
  under the sandbox's PRIVATE /home. ~/.m2 is narrowed to repository/ — the
  rest of .m2 can carry credentials (settings.xml server sections), and a
  dependency cache is code, not a credential."
  [".gitlibs" ".jolt" ".m2/repository"])

(def ^:private stage-prefix "samizdat-verify-")

(defn- message
  "Every model-bound sentence this namespace emits comes through the
  bounded-evaluator template, like the evaluator's own messages — the words
  are prose the model reads, so they are editable without a rebuild."
  [data]
  (prompt/render "bounded-evaluator" data))

(def ^:private workspace-too-large
  "The refusal kind for a workspace over the pinned copy budget."
  :workspace-too-large)

(defn- sha256 [^String s]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes s "UTF-8")))))

(defn coordinate
  "A stable name for the pinned environment itself — the flags, limits,
  verifier authority, constructed child environment and cache set, hashed.
  This names the FULL policy, where the SPI description (below) deliberately
  names only the shape; the verify envelope's attribution carries the
  description's canonical coordinate, which is the name a second repository
  can check. The child environment participates through its constructed
  value, so two controllers pinning different verifier toolchains (a JOLT_CHEZ
  pin present or absent) name different environments — which they are."
  []
  (str "js1-ve/v1:"
       (sha256 (pr-str {:namespaces [:user :ipc :net :pid :uts]
                        :caps :drop-all
                        :limits resource-limits
                        :verifier [verifier-exec-name verifier-fixed-args]
                        ;; The RULE, not the resolved path: a path would make
                        ;; this name machine-specific, and the coordinate is
                        ;; what a second repository checks. The bind set now
                        ;; depends on where the verifier lives, so the policy
                        ;; that decides it belongs in the name.
                        :verifier-bind :exec-file-when-outside-usr
                        :workspace workspace-policy
                        :home-caches home-cache-names
                        :child-env (child-env)}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The ExecutionEnvironment EDN SPI (RFC-012): description, coordinate,
;; envelopes. The description names only the SHAPE; `coordinate` above names
;; the FULL policy. Both are this environment's names — one for a second
;; repository to check, one for the controller's own record.
;; ═══════════════════════════════════════════════════════════════════════════

(def envelope-version
  "The EDN SPI's envelope version this adapter emits (RFC-012). It stays 1
  until a rule changes rendered bytes; then it is 2, and coordinates taken
  under 1 remain what they were."
  1)

(def environment-description
  "The INERT description of this environment — the SPI's description
  vocabulary, pinned by the conformance fixtures' golden digest. What
  implements it, that it is verify-only, which operations it answers
  (:describe and :verify — there is no code path that executes anything
  else, by construction), that it has no network, and which namespaces it
  unshares. Deliberately names only the shape: this is the
  CROSS-REPOSITORY name, canonicalized to the same digest by either
  keeper, so a verify envelope's attribution is checkable by a repository
  that never reads this code. What the full policy is — limits, verifier
  authority, cache set, workspace budget — is `coordinate`'s business."
  {:executor/type :samizdat/bwrap-verification-env
   :executor/mode :verify-only
   :executor/operations #{:describe :verify}
   :executor/network :none
   :executor/namespaces #{:user :ipc :net :pid :uts}})

(defn environment-coordinate
  "The canonical-EDN coordinate of `environment-description` — the name a
  second repository can check. Kind :bb4t/execution-environment, the same
  kind bb4t's own describe uses over the same grammar, so either keeper
  reaches the same digest. This, not `coordinate`, is what a run envelope
  attributes its execution to."
  []
  (cedn/coordinate :bb4t/execution-environment environment-description))

(defn- envelope!
  "Check one outgoing envelope against its kind's contract — the frame, the
  EXACT key set, and inertness — and return it. Inertness is the keeper's
  to judge: canonical-tree throws on anything alive or ambiguous, so a bug
  that misshapes an envelope fails HERE, at construction, where the stack
  still says why, rather than in whatever reader eventually trusts it."
  [envelope required optional]
  (let [present (set (keys envelope))
        allowed (into required optional)
        missing (not-empty (vec (remove present required)))
        extra (not-empty (vec (remove allowed present)))]
    (when (or missing extra)
      (throw (ex-info "envelope key set is not its kind's"
                      {:samizdat.verification-env/error :envelope-keys
                       :missing missing :extra extra})))
    (cedn/canonical-tree envelope)
    envelope))

(defn describe-envelope
  "What this environment IS, as the SPI's describe envelope: the inert
  description beside its own canonical coordinate — recomputed in the same
  expression, so a misattributed envelope is a detectable lie rather than
  an odd-looking string. Refuses a description that cannot say what
  implements it: a run cannot be attributed to an environment with no
  type."
  []
  (when-not (keyword? (:executor/type environment-description))
    (throw (ex-info "environment description names its type"
                    {:samizdat.verification-env/error :description-type})))
  (envelope!
   {:spi/version envelope-version
    :spi/kind :spi.environment/describe
    :environment/description environment-description
    :environment/coordinate (environment-coordinate)}
   #{:spi/version :spi/kind :environment/description :environment/coordinate}
   #{}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Substrate probing — Linux-only, and refuse rather than degrade.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- linux? []
  (str/starts-with? (str (System/getProperty "os.name")) "Linux"))

(defn- abs-bin
  "The absolute path of `name` when the controller can execute it, else nil."
  [name]
  (when-let [p (fs/which name)]
    (when (fs/executable? p) (str p))))

(defn resolve-verifier
  "The ABSOLUTE path of the pinned verifier executable, controller-resolved —
  never a bare name left to the child's PATH, never data. Resolution order:
  the controller's own PATH, then the harness process's own launcher
  (bin/jolt under the running jolt's checkout root — the launcher cds there
  before exec, so fs/cwd IS the checkout when samizdat runs under bin/jolt).
  nil means the pinned verifier cannot be located: the bounded lane refuses."
  []
  (or (abs-bin verifier-exec-name)
      (let [own (str (fs/cwd) "/bin/" verifier-exec-name)]
        (when (fs/executable? own) own))))

(defn canonical-verifier
  "The resolved verifier as a CANONICAL absolute path, or nil.

  resolve-verifier returns whatever fs/which or the cwd fallback produced, and
  that string is not necessarily normalised: a PATH entry carrying `..`, or a
  symlink, yields a path whose lexical form and whose real target differ. Every
  later decision -- is it under /usr, is it inside the verified root, where does
  the staged placeholder go -- is a decision about the REAL file, so it is made
  on the canonical form and never on the string.

  Also insists on a regular executable file. A directory or a dangling symlink
  reaching this far would otherwise be staged and bound."
  [exec]
  (when exec
    (try
      (let [c (str (fs/canonicalize exec))]
        (when (and (str/starts-with? c "/")
                   (fs/regular-file? c {:nofollow-links true})
                   (fs/executable? c))
          c))
      (catch Throwable _ nil))))

(defn verifier-inside-root?
  "Whether the canonical verifier lives inside the tree being verified.

  THE PROJECT UNDER VERIFICATION MUST NOT SUPPLY ITS OWN VERIFIER. resolve-
  verifier falls back to (fs/cwd)/bin/jolt, which is the harness's own launcher
  when samizdat runs from its checkout -- but it is project-controlled content
  if the controller is ever invoked from inside a project, and an entry on the
  inherited PATH could point there too.

  Before the verifier executable was bound, such a path simply failed to exec:
  the namespace had no such file, so a hostile bin/jolt could not run and the
  weakness stayed latent. Binding the executable removes that accident, so the
  check has to become deliberate. A verifier inside the verified root is
  refused, and the lane fails closed.

  A fake verifier that ignored the fixed arguments and exited zero would make
  verification green without running anything, which is the whole property this
  environment exists to provide."
  [exec root]
  (boolean
   (when (and exec root)
     (let [croot (try (str (fs/canonicalize root)) (catch Throwable _ nil))]
       (and croot (or (= exec croot)
                      (str/starts-with? exec (str croot "/"))))))))

(defn- verifier-exec-bind
  "The [src dest] ro-bind for the verifier EXECUTABLE ITSELF, when it lives
  outside the already-bound /usr.

  The stage binds /usr, a private /home carrying only the dependency caches,
  and a checkout root when the verifier is a checkout-launched bin/jolt. A
  verifier that is none of those -- a standalone binary in a user prefix such as
  ~/.local/bin, which is what an install into a user-owned prefix produces --
  resolved to a path that did not exist inside the namespace, and the lane died
  at exec:

      prlimit: failed to execute /home/chuck/.local/bin/jolt:
               No such file or directory

  Every assertion in the sandbox suite then failed for the same reason: the
  child never ran, so the hostile attacks it was supposed to be blocked from
  performing produced no output to check.

  Binds the FILE, not its directory. A bin directory in a user prefix holds
  other host binaries, and none of them is the pinned verifier; exposing the one
  executable the policy already names is not a widening of what the sandbox can
  reach. Read-only like every other bind here.

  Takes the CANONICAL path, so the /usr test is about the real file rather than
  a string that may contain `..` or traverse a symlink. Returns nil for a
  verifier under /usr, which the usr binds already cover.

  KNOWN LIMITATION, recorded rather than papered over: binding the executable
  alone suffices for a self-contained binary whose interpreter and libraries
  live under the existing system binds. It is NOT sufficient for a launch script
  that locates resources relative to $0, a binary with $ORIGIN-relative
  libraries in the same prefix, or a shebang interpreter outside /usr. Those
  fail closed -- the child cannot start -- rather than escaping confinement."
  [exec]
  (when (and exec (not= exec "/usr") (not (str/starts-with? exec "/usr/")))
    [exec exec]))

(defn- verifier-root
  "The checkout root when the resolved verifier is a checkout-launched jolt
  (bin/jolt with host/chez/cli.ss beside it). The launcher needs its repo —
  the bootstrap seed, the stdlib, the devboot cache — and binding it
  read-only is binding a toolchain, not host state. A system-installed
  verifier (under the already-bound /usr) returns nil and binds nothing."
  [exec]
  (let [parent (some-> exec fs/path .getParent str)
        candidate (some-> parent fs/path .getParent str)]
    (when (and candidate
               (fs/exists? (str candidate "/host/chez/cli.ss")
                           {:nofollow-links true}))
      candidate)))

(defn- probe-substrate
  "One real spawn of the minimum sandbox, executing /usr/bin/true inside
  --unshare-user. This is the only honest availability answer: bwrap exists
  and user namespaces work on this kernel or they do not, and a config check
  would say yes on hosts where seccomp or sysctl has disabled them. Returns
  nil when everything needed is present, or a keyword reason."
  []
  (cond
    (not (linux?)) :not-linux
    (nil? (abs-bin "bwrap")) :no-bwrap
    (nil? (abs-bin "prlimit")) :no-prlimit
    :else
    (let [r (try
              (proc/run {:timeout-ms 10000 :env {"PATH" "/usr/bin:/bin"}}
                        (abs-bin "bwrap")
                        "--unshare-user" "--unshare-ipc" "--unshare-net"
                        "--unshare-pid" "--unshare-uts"
                        "--die-with-parent" "--new-session" "--cap-drop" "ALL"
                        "--ro-bind" "/usr" "/usr"
                        "--ro-bind-try" "/usr/local" "/usr/local"
                        "--symlink" "usr/bin" "/bin"
                        "--symlink" "usr/sbin" "/sbin"
                        "--symlink" "usr/lib" "/lib"
                        "--symlink" "usr/lib64" "/lib64"
                         "--proc" "/proc"
                         "--size" "1048576" "--tmpfs" "/dev"
                         "--dev-bind-try" "/dev/null" "/dev/null"
                         "--dev-bind-try" "/dev/zero" "/dev/zero"
                         "--dev-bind-try" "/dev/full" "/dev/full"
                         "--dev-bind-try" "/dev/random" "/dev/random"
                         "--dev-bind-try" "/dev/urandom" "/dev/urandom"
                         "--symlink" "/proc/self/fd" "/dev/fd"
                         "--symlink" "/proc/self/fd/0" "/dev/stdin"
                         "--symlink" "/proc/self/fd/1" "/dev/stdout"
                         "--symlink" "/proc/self/fd/2" "/dev/stderr"
                         "--size" "1048576" "--tmpfs" "/tmp"
                        "/usr/bin/true")
              (catch Throwable _ {:exit -1}))]
      (when (or (:timeout r) (not (zero? (or (:exit r) 1))))
        :sandbox-unavailable))))

(def ^:private substrate
  "Memoized: the probe spawns a process, and availability does not change
  within a run. Tests that need the other branch redef available?/
  unavailable-reason rather than the probe."
  (delay (probe-substrate)))

(defn available?
  "Whether this host can run the VerificationEnvironment at all. When false,
  bounded `done` refuses — never a direct host spawn."
  []
  (nil? @substrate))

(defn unavailable-reason []
  (or @substrate :unknown))

(def ^:private refusal-categories
  "The catalogued SPI refusal for each controller refusal reason — this
  environment's own refusal points, none invented: a non-Linux host, a
  missing isolation tool, a kernel that will not grant the minimum
  sandbox, an unresolvable pinned verifier, changed files with nothing
  verifiable among them, and the bucket for a reason this catalogue does
  not name (a refusal all the same)."
  {:not-linux :spi.refusal/not-linux
   :no-bwrap :spi.refusal/no-bubblewrap
   :no-prlimit :spi.refusal/no-prlimit
   :sandbox-unavailable :spi.refusal/sandbox-unavailable
   :no-verifier-executable :spi.refusal/verifier-unresolvable
   :no-verifiable-test :spi.refusal/nothing-verifiable})

(def ^:private refusal-reasons
  "The authored reason string for each catalogued refusal. Noun phrases,
  never sentences and never host specifics: a refusal crosses the same
  repository boundary a description does and carries the same inertness
  obligation. The MODEL-facing sentence for each refusal is a different
  thing entirely — it lives in the bounded-evaluator template — and the
  two vocabularies do not meet."
  {:spi.refusal/not-linux
   "verification environment requires Linux; host spawn refused"
   :spi.refusal/no-bubblewrap
   "bubblewrap executable missing; sandboxed verification requires bwrap"
   :spi.refusal/no-prlimit
   "prlimit executable missing; sandboxed verification requires prlimit"
   :spi.refusal/sandbox-unavailable
   "minimum sandbox spawn failed; unprivileged user namespaces unavailable"
   :spi.refusal/verifier-unresolvable
   "pinned verifier executable unresolvable; controller refuses host spawn"
   :spi.refusal/nothing-verifiable
   "changed files include zero whitelisted test namespaces"
   :spi.refusal/unknown
   "verification environment refused; reason uncatalogued"})

(defn refusal-envelope
  "A controller refusal reason as the SPI's availability-refusal envelope:
  the catalogued category beside its authored reason. Which category a
  reason maps to is decided here, once, so `run`'s refusals and any
  availability envelope cannot disagree. An uncatalogued reason refuses
  as :spi.refusal/unknown rather than being guessed at."
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
  "Whether this host can run the VerificationEnvironment, as the SPI's
  availability envelope. Exactly one of the two answers' payloads: an
  available host carries the environment coordinate and no refusal; a
  refused host carries its catalogued refusal and NO coordinate — a reader
  never has to decide which to believe. The answer is the probe's (a real
  minimum-sandbox spawn), never a config check."
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

;; ═══════════════════════════════════════════════════════════════════════════
;; The derived argv — pinned prefix plus ONE controller-derived expression.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- whitelisted-namespaces
  "The focused namespaces among `changed` — the same selection both lanes
  share (verify/test-file? + verify/ns-from-test-path's whitelist)."
  [changed]
  (->> changed (filter verify/test-file?) (keep verify/ns-from-test-path)
       distinct vec))

(def closure-fixed-args
  "The PINNED argv tail of the CLOSURE verifier: the project's own whole
  suite. Unlike the focused argv this has no derived element at all — the
  model's edits do not shape it even by which namespaces they name — so it is
  the strictly less model-influenced of the two gates."
  ["-M:test"])

(defn closure-argv
  "The controller argv for the broader closure verification, or nil when the
  pinned verifier cannot be resolved.

  WHY A SECOND GATE. The focused verifier runs only the namespaces of the
  changed TEST files, which is right for cheap iteration and cannot see what a
  change did to anything else. JS1 M4 attempt 1 made that concrete: the agent
  rewrote src/samizdat/util.clj from memory and deleted sh-quote and
  generation-cache — both live production dependencies — and the focused
  verifier over samizdat.util-test would have gone GREEN on it had the rewrite
  merely compiled (attempt-1 finding F-4). Focused green now buys the right to
  be checked against the whole suite, not the right to ship."
  []
  (when-let [exec (resolve-verifier)]
    (into [exec] closure-fixed-args)))

(defn focused-argv
  "The verifier argv for the bounded lane: the PINNED executable and fixed
  args, plus exactly one derived element — the focused -e expression
  (verify/focused-expr), whose namespaces passed the ns whitelist. nil when
  nothing among `changed` is a verifiable test (the gate then refuses, never
  trusts — focused-expr itself happily renders an EMPTY namespace list, so
  the emptiness is decided HERE, never left to the expression). The prefix
  is controller code; gates.edn is not consulted and cannot widen this."
  [changed]
  (let [nses (whitelisted-namespaces changed)]
    (when (seq nses)
      (into [(or (resolve-verifier) verifier-exec-name)]
            (into (vec verifier-fixed-args) [(verify/focused-expr nses)])))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The explicit child environment.
;; ═══════════════════════════════════════════════════════════════════════════

(defn child-env
  "The COMPLETE, CONSTRUCTED environment the sandboxed verifier runs under.
  Nothing is inherited from the harness process — not filtered, not
  scrubbed, not merged: this map IS the whole child environment, so a host
  credential (GITHUB_TOKEN, GH_TOKEN, SSH_AUTH_SOCK, an API key the secret
  scanner has never seen) cannot cross by omission or by a name-shape it
  failed to match. HOME/PWD/TMPDIR and JOLT_PWD are pinned to sandbox paths
  (a leaked harness JOLT_PWD would point the verifier at a host path that
  does not exist inside the sandbox), LANG is fixed so output bytes are
  deterministic, and PATH names only the read-only system runtime the
  sandbox actually has.

  One controller-authored TOOLCHAIN pin may ride along: JOLT_CHEZ, the
  interpreter the controller itself resolved for its own launcher (the
  value bin/jolt treats as authoritative), handed to the pinned verifier
  unchanged. Without it the verifier's own discovery must find a threaded
  Chez inside the bind allowlist — a checkout carrying one under
  .cache/local, or a PATH-named chez/chezscheme under /usr; a host whose
  working Chez is named neither leaves the sandboxed verifier dead and
  bounded done refused, fail-closed. The value stays the controller's own
  path: when it is not visible under the binds the verifier dies the same
  fail-closed death — no host path is ever bound for it, and a credential
  cannot ride the name (scrubbed-process-env drops those)."
  []
  (merge {"HOME" "/home"
          "PWD" "/workspace"
          "TMPDIR" "/tmp"
          "JOLT_PWD" "/workspace"
          "LANG" "C.UTF-8"
          "PATH" "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"}
         (when-let [chez (not-empty (get (secrets/scrubbed-process-env)
                                         "JOLT_CHEZ"))]
           {"JOLT_CHEZ" chez})))

;; ═══════════════════════════════════════════════════════════════════════════
;; The private staging root and the workspace copy.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private merged-usr-dirs ["bin" "sbin" "lib" "lib64"])

(defn- merged-usr-link
  "The relative target when /<dir> is a merged-usr symlink (bin -> usr/bin),
  else nil."
  [dir]
  (when (fs/sym-link? (str "/" dir))
    (let [target (str (fs/read-link (str "/" dir)))]
      (when (re-matches #"usr/(bin|sbin|lib|lib64)" target) target))))

(defn- symlink! [path target]
  (try (fs/create-sym-link path target) (catch Throwable _ nil)))

(defn- too-large! [detail]
  (throw (ex-info (str "workspace copy exceeded its budget: " detail)
                  {:samizdat.verification-env/error workspace-too-large})))

(defn- copy-workspace!
  "Copy the authoritative root into the stage's workspace/ — bounded,
  symlink-preserving, exclusion-aware. A symbolic link is recreated AS A
  LINK: its host target is not readable through the copy, and a link pointing
  outside the workspace dangles inside the sandbox rather than escaping it.
  Fails closed when the tree exceeds the pinned file/byte budget: the copy
  is an input to verification, and an unbounded copy is a cost the model's
  file writes must not be able to impose."
  [root dst]
  (let [{:keys [excluded-names max-files max-bytes]} workspace-policy]
    (letfn [(charge! [budget file-bytes]
              (let [budget' (-> budget (update :files inc)
                                (update :bytes + file-bytes))]
                (when (> (:files budget') max-files) (too-large! "too many files"))
                (when (> (:bytes budget') max-bytes) (too-large! "too many bytes"))
                budget'))
            (walk! [src target budget]
              (fs/create-dirs target)
              (reduce
               (fn [budget entry]
                 (let [name (str (fs/file-name entry))]
                   (if (contains? excluded-names name)
                     budget
                     (cond
                       (fs/directory? entry {:nofollow-links true})
                       (walk! entry (str target "/" name) (charge! budget 0))

                       ;; A link is recreated, never followed: the copy
                       ;; cannot pull outside content in, and a hostile or
                       ;; dangling link stays exactly that inside the sandbox.
                       (fs/sym-link? entry)
                       (do (symlink! (str target "/" name)
                                     (str (fs/read-link entry)))
                           budget)

                       :else
                       (do (fs/copy entry (str target "/" name)
                                    {:nofollow-links true
                                     :copy-attributes true})
                           (charge! budget (or (fs/size entry) 0)))))))
               budget
               (sort-by #(str (fs/file-name %)) (fs/list-dir src))))]
      (walk! (str (fs/canonicalize root)) (str dst) {:files 0 :bytes 0}))))

(defn- home-cache-binds
  "The [src dest] ro-bind pairs for the read-only dependency caches. A source
  that does not exist host-side is skipped entirely — a cache the host lacks
  is a cache the verifier works without."
  [stage]
  (let [home (System/getenv "HOME")]
    (into []
          (comp (filter (fn [rel]
                          (and home (fs/exists? (str home "/" rel)
                                                {:nofollow-links true}))))
                (map (fn [rel]
                       (let [dest (str "/home/" rel)]
                         (fs/create-dirs (str stage dest))
                         [(str home "/" rel) dest]))))
          home-cache-names)))

(defn- stage!
  "Build the private staging root: the throwaway / the sandbox runs in.

  Layout: /usr (host, ro) with the merged-usr symlinks recreated — or real
  dirs ro-bound on a split-usr host; /proc /dev /tmp /run /var/tmp as fresh
  mounts; a private /home carrying only the read-only dependency caches; the
  pinned verifier's checkout ro-bound at its real path when it lives in one;
  and /workspace — the COPY of the authoritative root, the only project tree
  the child can touch. Returns the bwrap --ro-bind argument pairs the layout
  implies. Throws on a workspace over budget (the caller reads it as a
  failed-closed verification)."
  [stage root exec]
  (doseq [d ["usr" "usr/local" "proc" "dev" "tmp" "run" "var/tmp" "home"]]
    (fs/create-dirs (str stage "/" d)))
  (let [usr-binds (into [["/usr" "/usr"]]
                        (when (fs/exists? "/usr/local" {:nofollow-links true})
                          [["/usr/local" "/usr/local"]]))
        layout-binds (into []
                           (mapcat
                            (fn [dir]
                              (if-let [target (merged-usr-link dir)]
                                (do (symlink! (str stage "/" dir) target) [])
                                (when (fs/directory? (str "/" dir)
                                                     {:nofollow-links true})
                                  (fs/create-dirs (str stage "/" dir))
                                  [[(str "/" dir) (str "/" dir)]]))))
                           merged-usr-dirs)
        cache-binds (home-cache-binds stage)
        cexec (canonical-verifier exec)
        _ (when (verifier-inside-root? cexec root)
            ;; fail closed: a verifier the verified project could have written
            (throw (ex-info "verifier resolves inside the tree being verified"
                            {:samizdat/refusal :verifier-inside-verified-root
                             :verifier cexec})))
        vroot (some-> exec verifier-root)
        verifier-binds (when vroot
                         (fs/create-dirs (str stage vroot))
                         [[vroot vroot]])
        ;; A verifier outside /usr and outside its own checkout root needs its
        ;; executable bound, or the namespace has no such file to exec.
        ;;
        ;; The whole stage is mounted --ro-bind at /, so bwrap cannot create the
        ;; mountpoint for a FILE bind the way it can for a directory: it reports
        ;; "Can't create file at ...: Read-only file system". An empty
        ;; placeholder is staged at the same path for the bind to land on.
        exec-bind (when-not vroot
                    (when-let [pair (verifier-exec-bind cexec)]
                      (let [dest (str stage (first pair))]
                        (fs/create-dirs (str (some-> (fs/path dest) .getParent)))
                        (spit dest "")
                        [pair])))]
    (copy-workspace! root (str stage "/workspace"))
    {:ro-binds (vec (concat usr-binds layout-binds cache-binds verifier-binds
                            exec-bind))}))

(defn- sandbox-argv
  "The FULL controller argv for one sandboxed verification: bwrap's pinned
  isolation flags and allowlisted binds, the prlimit resource wrapper, then
  the pinned verifier argv (focused-argv). Every element is controller
  authored except the focused expression, which the namespace whitelist
  already reduced to plain namespace characters."
  [stage ro-binds focused]
  (-> [(abs-bin "bwrap")
       "--unshare-user" "--unshare-ipc" "--unshare-net" "--unshare-pid"
       "--unshare-uts"
        "--die-with-parent" "--new-session" "--cap-drop" "ALL"
        "--ro-bind" (str stage) "/"]
       (into (mapcat (fn [[src dest]] ["--ro-bind" src dest])) ro-binds)
       (into ["--proc" "/proc"
              "--size" (str (:dev-bytes resource-limits)) "--tmpfs" "/dev"])
       (into (mapcat (fn [node] ["--dev-bind-try" (str "/dev/" node)
                                (str "/dev/" node)]) dev-nodes))
       (into ["--symlink" "/proc/self/fd" "/dev/fd"
              "--symlink" "/proc/self/fd/0" "/dev/stdin"
              "--symlink" "/proc/self/fd/1" "/dev/stdout"
              "--symlink" "/proc/self/fd/2" "/dev/stderr"
              "--size" (str (:tmpfs-bytes resource-limits)) "--tmpfs" "/tmp"
              "--size" (str (:tmpfs-bytes resource-limits)) "--tmpfs" "/run"
              "--size" (str (:tmpfs-bytes resource-limits)) "--tmpfs" "/var/tmp"
              "--chdir" "/workspace"])
       (into [(abs-bin "prlimit")
              (str "--fsize=" (:fsize-bytes resource-limits))
              (str "--nproc=" (:nproc resource-limits))
              (str "--as=" (:as-bytes resource-limits))
              (str "--nofile=" (:nofile resource-limits))
              "--"])
      (into focused)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Bounded output capture.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- capped-stream
  "One redirect spool as bounded capture: the first at-most-cap bytes as
  UTF-8 (with an honest truncation marker when more exists), the spool's
  TRUE byte count — what the workload wrote, not what was kept — and
  whether the read kept less than the spool holds. The redirect target is
  RLIMIT_FSIZE-bounded, so the spool is bounded already; this bounds what
  the HARNESS pulls into memory and hands toward model space, and records
  the truncation where the envelope's capture can name it."
  [path]
  (let [cap (:capture-bytes resource-limits)]
    (if-not (fs/exists? path {:nofollow-links true})
      {:text "" :bytes 0 :truncated? false}
      (let [[total ^bytes buf] (try
                                 (with-open [in (java.io.FileInputStream.
                                                 (str path))]
                                   (let [buf (byte-array (inc cap))]
                                     (loop [total 0]
                                       (let [remaining (- (inc cap) total)]
                                         (if (<= remaining 0)
                                           [total buf]
                                           (let [n (.read in buf total
                                                          remaining)]
                                             (if (neg? n)
                                               [total buf]
                                               (recur (+ total n)))))))))
                                 (catch Throwable _
                                   [0 (byte-array 0)]))]
        {:text (str (String. buf 0 (min total cap) "UTF-8")
                    (when (> total cap) (message {:ve-truncated true})))
         :bytes (or (fs/size path) total)
         :truncated? (> total cap)}))))

(defn- redacted-stream
  "A captured stream with its text past the redaction boundary — the same
  boundary :output crosses, so the envelope's stream text and the branch's
  combined output cannot disagree about what a secret was."
  [stream known]
  (update stream :text #(secrets/redact % known)))

(defn- spooled-output
  "The run's combined model-bound output: the two captured streams' texts
  joined and redacted — the same reads, the same join, the same boundary
  as before per-stream capture existed."
  [out-stream err-stream known]
  (secrets/redact (str (:text out-stream) "\n" (:text err-stream)) known))

;; ═══════════════════════════════════════════════════════════════════════════
;; The invocation counter.
;;
;; One VerificationEnvironment exists per harness process — the pinned policy
;; above, not an object — so its invocation counter is process-local state.
;; It is incremented BEFORE the sandboxed spawn, mirroring the SPI's rule
;; that the counter read when a run returns is that run's index. The durable
;; order of verifications is the journal's; this index distinguishes two
;; runs inside one process, no more.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private invocations (atom 0))

(defn invocation-count
  "How many sandboxed verifications this process's environment has attempted.
  A faithful availability probe or refusal never moves it."
  []
  @invocations)

(defn- claim-invocation!
  "Claim the next invocation index, immediately before the sandboxed spawn —
  the rule the section comment above states: increment BEFORE the run, so
  the index read when a run returns is that run's index. Real spawns only:
  the substrate probe, a refused request and a failed staging never claim
  one, because none of them attempted an execution."
  []
  (swap! invocations inc))

;; ═══════════════════════════════════════════════════════════════════════════
;; The private copy's manifest and input coordinate (RFC-012).
;;
;; A verify envelope names its INPUT: not the authoritative tree (the run
;; never sees it) and not the model's account of it, but the private copy
;; the verifier actually ran against. The manifest is built over the staged
;; copy AFTER staging and BEFORE the spawn, so it names the input the run
;; started from even when the workload scribbled over its own copy.
;; ═══════════════════════════════════════════════════════════════════════════

(defn workspace-manifest
  "The manifest of a workspace tree as the verifier's private copy of it:
  every entry relative to the root and sorted ascending by :path; a regular
  file carries its byte size and content digest (canonical-edn/sha-256-path);
  a directory carries only its kind; a symbolic link carries its target,
  read but never followed. The copy's exclusions are applied by name at
  every level — the same rule copy-workspace! copies by — and recorded, so
  the manifest of any tree equals the manifest of its private copy.

  Inert EDN throughout, so the canonical keeper can coordinate it."
  [workspace]
  (let [{:keys [excluded-names]} workspace-policy
        entries (letfn [(walk [dir prefix]
                          (into []
                                (comp
                                 (remove #(contains? excluded-names
                                                     (str (fs/file-name %))))
                                 (mapcat
                                  (fn [entry]
                                    (let [name (str (fs/file-name entry))
                                          rel (if (empty? prefix)
                                                name
                                                (str prefix "/" name))]
                                      (cond
                                        (fs/directory? entry {:nofollow-links true})
                                        (into [{:path rel :kind :directory}]
                                              (walk entry rel))

                                        ;; A link is recorded as a link: the
                                        ;; copy recreates it rather than
                                        ;; following it, so the manifest
                                        ;; names the target, not content the
                                        ;; sandbox cannot see through it.
                                        (fs/sym-link? entry)
                                        [{:path rel :kind :link
                                          :target (str (fs/read-link entry))}]

                                        :else
                                        [{:path rel :kind :file
                                          :bytes (or (fs/size entry) 0)
                                          :digest (str "sha256:"
                                                       (cedn/sha-256-path
                                                        entry))}])))))
                                (fs/list-dir dir)))]
                  (walk (str workspace) ""))]
    {:workspace/exclusions (vec (sort excluded-names))
     :workspace/entries (vec (sort-by :path entries))}))

(defn input-coordinate
  "The coordinate naming exactly what one verification ran against: the
  canonical-EDN coordinate (kind :samizdat.ve/verify-input) of the private
  copy's manifest. Two runs against byte-identical inputs name the same
  input; a one-byte difference in one file names another."
  [workspace]
  (cedn/coordinate :samizdat.ve/verify-input (workspace-manifest workspace)))

(defn- attribution
  "How every run out of this environment is attributed: the description's
  canonical coordinate — recomputed here, never cached, so attribution and
  describe-envelope cannot drift apart — beside the environment's type
  keyword. This is the map a run envelope carries at :run/attribution."
  []
  {:environment/coordinate (environment-coordinate)
   :environment/type (:executor/type environment-description)})

;; ═══════════════════════════════════════════════════════════════════════════
;; The run.
;; ═══════════════════════════════════════════════════════════════════════════

(defn build-environment
  "Stage the private root and derive the FULL sandbox argv for `changed` —
  everything `run` spawns, without spawning it. The pure-with-effects half
  of the runner (staging touches the filesystem, but nothing executes), so
  a test can pin what would run without paying for the run."
  [stage root changed]
  (let [focused (focused-argv changed)
        exec (resolve-verifier)
        {:keys [ro-binds]} (stage! stage root (or exec (first focused)))]
    {:ro-binds ro-binds
     :argv (sandbox-argv stage ro-binds focused)}))

(defn request-run-refusal
  "Why a requested verification cannot run here, or nil when it may — the
  gate every run request passes FIRST, in `run`'s own order: an
  unavailable substrate (Linux, bwrap, prlimit, user namespaces), nothing
  verifiable among the changed paths, an unresolvable pinned verifier. A
  refused request never stages, never spawns and never moves the
  invocation index, and `run` answers it with the refusal — never a
  euphemism, never red tests, and never licence to spawn on the host."
  ([changed] (request-run-refusal changed :focused))
  ([changed stage]
   (cond
     (not (available?)) (unavailable-reason)
     ;; Only the focused gate needs something verifiable among the changed
     ;; paths; the closure gate runs the whole suite and does not consult them.
     (and (= :focused stage) (nil? (focused-argv changed))) :no-verifiable-test
     (nil? (resolve-verifier)) :no-verifier-executable
     :else nil)))

(defn- refused
  "The result of a run request this environment REFUSED, in the shape each
  refusal has always read as: substrate and verifier refusals carry
  :unavailable? true so the caller fails closed on the environment, and
  the nothing-verifiable refusal reads as ordinary not-green evidence —
  the model can fix that one by writing a test. Every refusal also
  carries its catalogued EDN-SPI spelling: the same envelope a conformant
  second repository renders, so a refused request and an unavailable host
  are distinguishable in the record, not just in the prose."
  [reason]
  (let [base (case reason
               :no-verifiable-test
               {:green? false :timeout? false
                :output (message {:ve-no-test true})}

               :no-verifier-executable
               {:green? false :timeout? false :unavailable? true
                :reason :no-verifier-executable
                :output (message {:ve-no-verifier true
                                  :exec verifier-exec-name})}

               ;; Any substrate reason: :not-linux, :no-bwrap, :no-prlimit,
               ;; :sandbox-unavailable — the probe's own vocabulary.
               {:green? false :timeout? false :unavailable? true
                :reason reason
                :output (message {:ve-unavailable true})})]
    (assoc base :refusal (refusal-envelope reason))))

(def ^:private worker-failure-reason
  "The run envelope's error string for a run whose spawn failed after the
  invocation was claimed. A fixed authored phrase, never the exception
  message the model-bound :output carries: an exception message can name
  host paths, and envelope data crosses the same boundary a description
  does."
  "verification environment run failed")

(defn run
  "Verify `changed` inside the M2 VerificationEnvironment rooted at `root`,
  and report the shape the bounded lane judges: {:green? :timeout? :exit
  :output}, plus {:unavailable? true :reason k} when the substrate or the
  pinned verifier is missing — which the caller reads as a REFUSAL, never as
  red tests and never as licence to spawn directly. A refused request also
  carries its catalogued :refusal envelope.

  A run that spawned carries, additively, what the ExecutionEnvironment EDN
  SPI (RFC-012) requires a run to be attributable by: :invocation-index
  (claimed immediately before the spawn — monotonic across this process's
  REAL spawns; the probe, refusals and staging failures never move it),
  :attribution (the description's canonical coordinate and the
  environment's type), :input-coordinate (the PRIVATE COPY the verifier
  actually ran against, taken after staging and before the spawn so it
  names the input the run started from), :duration-ms, and each stream's
  bounded capture :stdout/:stderr ({:text — capped and redacted — :bytes
  — the spool's true size — :truncated?}). `verify-envelope` projects
  exactly these into the SPI's run envelope.

  The controller stages the private workspace, runs the pinned verifier argv
  under the pinned bwrap/prlimit policy through the scoped process facility
  (timeout, tree reaping), captures bounded output from the redirect spool,
  redacts it, and deletes the stage however the run ends. Never throws: a
  staging or spawn failure reads as not-green with its message as evidence."
  ([root changed timeout-ms] (run root changed timeout-ms :focused))
  ([root changed timeout-ms stage-kind]
  (if-let [refusal (request-run-refusal changed stage-kind)]
    (refused refusal)
    (if-let [stage (try (str (fs/create-temp-dir {:prefix stage-prefix}))
                        (catch Throwable _ nil))]
      (try
        (let [out-path (str stage "/out.log")
              err-path (str stage "/err.log")
              {:keys [ro-binds]} (stage! stage root (resolve-verifier))
              ;; RFC-012: the input coordinate is taken over the staged COPY,
              ;; after staging and before the spawn — it names the input the
              ;; run started from even when the workload scribbles over its
              ;; own copy, and it cannot move mid-run because the copy is
              ;; throwaway.
              input (input-coordinate (str stage "/workspace"))
              ;; The invocation index is claimed immediately before the
              ;; spawn: the counter's rule, now actually wired.
              index (claim-invocation!)
              started (System/nanoTime)
              r (try
                  (apply proc/run
                         {:timeout-ms (or timeout-ms
                                          (gates/threshold :verify-timeout-ms))
                          :env (child-env)
                          :dir stage
                          :out-file out-path
                          :err-file err-path}
                         (sandbox-argv stage ro-binds
                                       (if (= :closure stage-kind)
                                         (closure-argv)
                                         (focused-argv changed))))
                  ;; A spawn failure is a FAILED RUN, not a refused request:
                  ;; the index was claimed, so the attempt is attributable.
                  (catch Throwable t
                    {:spawn-failure (ex-message t)}))
              duration-ms (long (quot (- (System/nanoTime) started) 1000000))
              known (secrets/known-values (into {} (System/getenv)))
              out-raw (capped-stream out-path)
              err-raw (capped-stream err-path)
              output (spooled-output out-raw err-raw known)]
          (merge {:invocation-index index
                  :duration-ms duration-ms
                  :input-coordinate input
                  :attribution (attribution)
                  :stdout (redacted-stream out-raw known)
                  :stderr (redacted-stream err-raw known)}
                 (if (:spawn-failure r)
                   {:green? false :timeout? false
                    :output (message {:ve-run-failed true
                                      :reason (:spawn-failure r)})}
                   (if (:timeout r)
                     {:green? false :timeout? true :output output}
                     {:green? (zero? (or (:exit r) 1))
                      :timeout? false :exit (:exit r) :output output}))))
        (catch Throwable e
          {:green? false :timeout? false
           :output (message {:ve-run-failed true
                             :reason (ex-message e)})})
        (finally
          (try (fs/delete-tree stage) (catch Throwable _ nil))))
      {:green? false :timeout? false
       :output (message {:ve-stage-failed true})}))))

(defn run-closure
  "Run the CLOSURE verification — the project's whole suite — under the same
  controller-owned environment, staging and bounds as `run`. `changed` is
  carried only so a refusal reads identically; the argv ignores it."
  [root changed timeout-ms]
  (run root changed timeout-ms :closure))

(def ^:private run-statuses
  "The statuses a verify run envelope may carry — the verify-only subset of
  the SPI's vocabulary. :completed (the verifier exited, green or red
  alike), :timeout (the wall clock fired — a deadline is not a program
  that chose a number, so no exit), :worker-failure (the spawn itself
  failed after the invocation was claimed). :project-changed cannot occur
  here: the input coordinate is taken over the staged copy, and a
  throwaway copy cannot move under the run."
  #{:completed :timeout :worker-failure})

(defn verify-envelope
  "One `run` result as the SPI's run envelope (:spi.execution/run): the
  invocation index, the attribution, the input coordinate, the status, the
  exit when and only when the verifier actually exited (:completed), each
  stream's capture, the wall clock, and the disposition (:terminated —
  the stage is deleted and the tree reaped however the run ends). Fields
  that are nil are dropped, so an absent exit and a present-but-nil exit
  are the same refusal to invent one.

  nil when the result is not a run: a refused request or a failed staging
  produced no spawn, claimed no index, and so has no execution to
  envelope."
  [result]
  (when (and (map? result) (:invocation-index result))
    (when-not (pos? (:invocation-index result))
      (throw (ex-info "invocation index starts at one"
                      {:samizdat.verification-env/error :envelope-index
                       :invocation-index (:invocation-index result)})))
    (let [status (cond
                   (:timeout? result) :timeout
                   (integer? (:exit result)) :completed
                   :else :worker-failure)
          stream (fn [s]
                   {:stream/text (:text s)
                    :stream/bytes (:bytes s)
                    :stream/truncated? (:truncated? s)})]
      (when-not (contains? run-statuses status)
        (throw (ex-info "unknown run status"
                        {:samizdat.verification-env/error :envelope-status
                         :output/status status})))
      (envelope!
       (into {} (remove (fn [[_ value]] (nil? value)))
             {:spi/version envelope-version
              :spi/kind :spi.execution/run
              :run/invocation-index (:invocation-index result)
              :run/attribution (:attribution result)
              :run/input {:input/coordinate (:input-coordinate result)}
              :output/status status
              :output/exit (when (= :completed status) (:exit result))
              :output/stdout (stream (:stdout result))
              :output/stderr (stream (:stderr result))
              :output/duration-ms (:duration-ms result)
              :output/error (when (= :worker-failure status)
                              worker-failure-reason)
              :run/disposition :terminated})
       #{:spi/version :spi/kind :run/invocation-index :run/attribution
         :run/input :output/status :output/stdout :output/stderr
         :output/duration-ms :run/disposition}
       #{:output/exit :output/error}))))
