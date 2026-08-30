;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.smolvm-project-env
  "The SmolVM ProjectExecutionEnvironment — the isolated world a MODEL may
  run arbitrary project tooling in.

  THE ONE THING TO KEEP STRAIGHT. There are now two execution environments in
  this repository and they are not the same kind of thing:

    VerificationEnvironment    (smolvm-verification-env, verification-env)
        CONTROLLER-owned. Its argv is the controller's, derived from the
        binding's own edit receipts. Its result is ACCEPTANCE evidence: a
        green one is what makes `done` terminal.

    ProjectExecutionEnvironment (this namespace)
        MODEL-authorized. Its argv is the model's, whatever the pinned guest
        toolchain makes available. Its result is DEVELOPMENT evidence: it
        tells the model whether its own work looks right, and it settles
        NOTHING about completion. A green `project/run` is not a green gate,
        and no code path anywhere reads one as the other.

  They share the low-level boundary — the same manager at the same measured
  version, the same digest-pinned guest image, the same prelude, the same
  overlay, the same privilege drop, the same constructed environment — because
  the isolation must behave identically in both, and one implementation of a
  boundary is how it stays that way. They share no authority at all.
  (RFC-012 §8; the shared mechanism lives in smolvm-verification-env and is
  called from here.)

  What a model gets, and what it does not:

    (project/run [\"bb\" \"-M:test\"])
    (project/run [\"bb\" \"--classpath\" \"src:test\" \"-e\" \"…\"] {:cwd \".\"
                                                                :timeout-ms 60000})

  It chooses the ARGV and, at most, a relative working directory inside its
  own workspace and a timeout that may only NARROW the pinned ceiling. It
  chooses nothing else: not the image, not the network mode, not the mounts,
  not the environment variables, not the resource limits, not the identity, not
  the host cwd, not the cleanup, not the provider. Those are constants of this
  namespace and of the mechanism it calls, which is what makes the argv safe
  to hand over — `project/run` IS the arbitrary-code authority, and pretending
  an executable denylist were the security boundary would be pretending the
  wrong thing is holding the line. The isolated world is what holds it.

  The workspace is PRIVATE and DISPOSABLE. The authoritative project tree is
  mounted read-only and then masked; everything the workload writes lands in an
  overlay that dies with the machine. A compiler may emit artifacts, a test
  runner may write a cache, a formatter may rewrite every file it can see —
  and the authoritative tree is byte-identical afterwards. The ONLY way it
  changes is a separate `project/edit` semantic operation.

  FAIL CLOSED. When the substrate is unavailable — not Linux, no measured
  manager, no pinned image, a kernel that will not boot the minimum machine —
  `project/run` REFUSES with the catalogued reason. It never falls back to a
  host process, a direct toolchain spawn, a shell, ordinary Samizdat
  execution, or the bwrap sandbox. A development convenience is not worth a
  hole in the boundary the whole milestone is about.

  TIMEOUT POISONS. A host deadline bounds host WAITING; it does not prove the
  child inside the machine died (the measured A3a result). So a timed-out run
  is not merely reported: the process tree is reaped descendants-first, the
  environment is marked poisoned, the machines THIS INVOCATION OWNS are
  stopped and deleted, the manager is asked again whether they are gone, and
  the poison is cleared only on a clean answer. Nothing is reused across it —
  there is no worker pool here at all, and a fresh ephemeral machine per
  invocation measured at roughly three seconds to boot, which is why there is
  not one.

  OWNERSHIP IS THE HARD PART, and getting it wrong is worse than the leak it
  chases. The frozen JS2 implementation swept every ephemeral machine the
  manager held, which is correct for one execution at a time and wrong for a
  server: run A timing out would delete run B's still-running machine. Cleanup
  is now scoped to what this invocation can be shown to own — the manager's
  own startup banner naming the machine it started, or, as a bounded fallback,
  the single machine that appeared since the baseline read immediately before
  this spawn. When neither settles it the cleanup does NOTHING, reports itself
  unclean, and leaves the provider poisoned: a lane that refuses further
  executions is a problem an operator can see, and a run that deleted another
  run's machine is a problem nobody sees until the other run reports
  nonsense. See `owned-machines`."
  (:require [clojure.string :as str]
            [jolt.fs :as fs]
            [samizdat.engine.proc :as proc]
            [samizdat.prompt :as prompt]
            [samizdat.security.canonical-edn :as cedn]
            [samizdat.security.secrets :as secrets]
            [samizdat.security.smolvm-verification-env :as smve]))

;; ═══════════════════════════════════════════════════════════════════════════
;; The pinned controller authority.
;;
;; Code, not gates.edn: resources/*.edn is the surface a run can rewrite at
;; runtime, and an execution limit living there is one project/edit away from
;; a bound the party under observation can lift.
;; ═══════════════════════════════════════════════════════════════════════════

(def resource-limits
  "The pinned in-VM and request policy for DEVELOPMENT execution.

  Deliberately not the verify environment's table. A verification runs one
  known argv; a development run is whatever the model asked for, and a real
  project suite is slower than a focused one — so the wall clock is larger
  here and the stream budgets are the same, because output a model reads
  should be no bigger than output a controller reads.

  Mechanism safety bounds, not tunables. `:worker/timeout-ms` is a CEILING a
  request may narrow and can never widen."
  {:worker/cpus 2
   :worker/memory-mib 2048
   :worker/stdout-max-bytes 1048576        ;; 1 MiB kept per stream
   :worker/stderr-max-bytes 1048576
   :worker/timeout-ms 600000               ;; the ceiling: 10 minutes
   :worker/default-timeout-ms 300000       ;; what an unspecified request gets
   ;; The open-file floor the MANAGER SPAWN runs under, and the reason it is
   ;; pinned here rather than left to whatever launched the harness.
   ;;
   ;; MEASURED: a controller started with `ulimit -n 4096` produced a guest in
   ;; which `rm -rf` of the project's .git (7684 loose objects) failed EMFILE,
   ;; deterministically, on every execution — the prelude then refused, and the
   ;; model read "the environment failed" for work that was fine. The guest's
   ;; own limits were identical either way (1024 soft / 4096 hard); the limit
   ;; that mattered was the HOST's, inherited by whatever serves the read-only
   ;; mount.
   ;;
   ;; That is the defect, not the number: a host-side resource limit reached
   ;; inside an environment whose whole claim is that the host does not reach
   ;; inside it. An environment that behaves differently depending on how the
   ;; harness was launched is not isolated, it is coincidentally working. So
   ;; the spawn runs under a pinned limit, and a host that cannot grant it
   ;; REFUSES rather than producing a development run whose failure the model
   ;; will read as its own.
   :host/nofile 65536})

(def request-limits
  "What a model-supplied request may be, before anything is staged. Bounds on
  the REQUEST, distinct from bounds on the run: a refusal here costs no
  machine."
  {:argv-max-length 64
   :argv-max-arg-chars 4096
   :cwd-max-chars 1024})

(def request-option-keys
  "The COMPLETE set of options a model may supply. Exhaustive by design and
  checked as a closed set: an unknown key is refused rather than ignored, so
  a request naming :env, :network, :image, :mounts, :cpus or :user fails
  loudly instead of quietly running under the controller's defaults while the
  model believes it changed them."
  #{:cwd :timeout-ms})

(def executor-type :samizdat/smolvm-project-env)

(defn- message [data]
  (prompt/render "bounded-evaluator" data))

;; ═══════════════════════════════════════════════════════════════════════════
;; Request validation — pure, and before any staging.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- invalid! [reason data]
  (throw (ex-info (message (assoc data reason true))
                  (assoc data :samizdat.smolvm-project-env/error reason))))

(defn validate-argv
  "The model's argv, validated as DATA. A non-empty vector of non-blank
  bounded strings and nothing else — no shell string is ever composed from
  it, here or in the mechanism it is handed to, so there is no quoting rule
  to get wrong and no metacharacter to think about."
  [argv]
  (let [{:keys [argv-max-length argv-max-arg-chars]} request-limits]
    (when-not (and (sequential? argv) (seq argv))
      (invalid! :run-argv-shape {:argv/given (pr-str argv)}))
    (when (> (count argv) argv-max-length)
      (invalid! :run-argv-long {:argv/max argv-max-length}))
    (doseq [a argv]
      (when-not (and (string? a) (not (str/blank? a))
                     (<= (count a) argv-max-arg-chars))
        (invalid! :run-argv-shape {:argv/given (pr-str argv)})))
    (vec argv)))

(defn validate-cwd
  "The relative working directory inside the model's OWN workspace, validated
  lexically. Relative, bounded, non-escaping, no absolute path and no
  component that climbs — the same shape rule the read side applies to a
  project path, for the same reason: this string is handed to the guest
  prelude as data, and a path that escapes the workspace names something in
  the machine's own filesystem, which is a different thing entirely."
  [cwd]
  (if (nil? cwd)
    "."
    (do
      (when-not (and (string? cwd) (not (str/blank? cwd))
                     (<= (count cwd) (:cwd-max-chars request-limits)))
        (invalid! :run-cwd-shape {:cwd/given (pr-str cwd)}))
      (when (str/starts-with? cwd "/")
        (invalid! :run-cwd-absolute {:cwd/given cwd}))
      (let [parts (remove #(or (str/blank? %) (= "." %)) (str/split cwd #"/"))]
        (when (some #{".."} parts)
          (invalid! :run-cwd-escape {:cwd/given cwd}))
        (if (seq parts) (str/join "/" parts) ".")))))

(defn validate-timeout
  "The run's wall clock. Absent means the pinned default; present must be a
  positive integer, and is capped at the pinned ceiling — a request may
  NARROW what it is given and can never widen it, exactly as a requested
  capability is intersected with authorization rather than added to it."
  [timeout-ms]
  (let [{ceiling :worker/timeout-ms default :worker/default-timeout-ms}
        resource-limits]
    (cond
      (nil? timeout-ms) default
      (and (integer? timeout-ms) (pos? timeout-ms)) (min (long timeout-ms) (long ceiling))
      :else (invalid! :run-timeout-shape {:timeout/given (pr-str timeout-ms)}))))

(defn validate-request
  "One model request as a validated, inert execution request, or a throw.

  The options map is checked as a CLOSED set first: everything a model must
  not choose is refused by the absence of its key from `request-option-keys`,
  which is why that set is exhaustive rather than a filter."
  [argv options]
  (when-not (or (nil? options) (map? options))
    (invalid! :run-options-shape {:options/given (pr-str options)}))
  (let [ks (set (keys options))
        extra (vec (sort-by str (remove request-option-keys ks)))]
    (when (seq extra)
      (invalid! :run-options-unknown {:options/unknown (mapv str extra)}))
    {:request/argv (validate-argv argv)
     :request/cwd (validate-cwd (:cwd options))
     :request/timeout-ms (validate-timeout (:timeout-ms options))}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Availability — the SAME substrate the verify environment probes, asked
;; through its own public answer rather than re-probed. One boot per process
;; either way, and one place where "can a machine run here" is decided.
;; ═══════════════════════════════════════════════════════════════════════════

(defn available?
  "Whether this host can run isolated project execution at all. When false,
  `project/run` refuses — never a host spawn, never another provider."
  []
  (smve/available?))

(defn unavailable-reason []
  (smve/unavailable-reason))

(defn- resolved-image []
  (when (available?) (:image (smve/guest-image))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The host-side open-file floor.
;; ═══════════════════════════════════════════════════════════════════════════

(defn host-fd-limits
  "[soft hard] RLIMIT_NOFILE of THIS process, from its own /proc entry, or nil
  when it cannot be read. The controller's limit, not the guest's — the guest
  has its own and they are unrelated."
  []
  (try
    (when-let [line (->> (str/split-lines (slurp "/proc/self/limits"))
                         (filter #(str/starts-with? % "Max open files"))
                         first)]
      (let [[soft hard] (->> (str/split (subs line (count "Max open files")) #"\s+")
                             (remove str/blank?)
                             (take 2)
                             (map #(if (= "unlimited" %) Long/MAX_VALUE (parse-long %))))]
        (when (and soft hard) [soft hard])))
    (catch Throwable _ nil)))

(defn- limiter
  "The controller-owned rlimit wrapper for the manager spawn: `prlimit` set to
  the pinned floor. nil when the host cannot provide it — the caller refuses
  rather than spawning under whatever it happened to inherit."
  []
  (let [floor (:host/nofile resource-limits)
        [_ hard] (or (host-fd-limits) [nil nil])
        exe (some-> (fs/which "prlimit") str)]
    (when (and exe hard (>= hard floor))
      [exe (str "--nofile=" floor ":" floor)])))

;; ═══════════════════════════════════════════════════════════════════════════
;; The RFC-012 description. Its MODE is what distinguishes it from the verify
;; environment's description, and the mode is in the coordinate, so a
;; development envelope can never be mistaken for an acceptance one.
;; ═══════════════════════════════════════════════════════════════════════════

(def envelope-version 1)

(defn environment-description
  "The INERT description of this environment. Names the shape and the pinned
  identity and no host paths, like its verify sibling — but with
  `:executor/mode :project-run` and `:executor/operations #{:describe :run}`,
  and with the authority over the argv stated as what it is."
  []
  (let [described (smve/describe-manager)
        version (some-> (:worker/version described) str str/trim
                        (str/split #"\s+") last str/trim not-empty)
        image (resolved-image)]
    {:executor/type executor-type
     :executor/mode :project-run
     :executor/operations #{:describe :run}
     :executor/manager smve/manager-exec-name
     :executor/version (or version "unresolved")
     :executor/approval :recognized
     :executor/network :none
     :executor/guest {:image :bbagent/worker-image
                      :image/digest (or (:digest image) "unresolved")
                      :privilege :unprivileged
                      :identity :derived-from-project-owner
                      :capabilities :none
                      :prelude :in-image
                      :prelude/contract smve/prelude-contract
                      :environment :constructed
                      :host-environment :not-inherited}
     :executor/workspace {:model :overlayfs
                          :project-mount :read-only
                          :lifecycle :ephemeral-machine-per-execution
                          :excluded-paths :hidden-from-workload
                          :writeback :none}
     ;; The one field that says what kind of thing this is. The verify
     ;; environment's analogue names a controller-pinned verifier; here the
     ;; argv is the model's, inside a toolchain the controller pinned.
     :executor/command {:argv :model-supplied
                        :argv/form :structured
                        :toolchain :in-image
                        :options (vec (sort-by str request-option-keys))
                        :authority :development-only}
     :executor/limits (into (sorted-map)
                            (select-keys resource-limits
                                         [:worker/cpus :worker/memory-mib
                                          :worker/stdout-max-bytes
                                          :worker/stderr-max-bytes
                                          :worker/timeout-ms
                                          :worker/default-timeout-ms
                                          ;; Part of the identity: an
                                          ;; environment staged under a
                                          ;; different fd floor is a different
                                          ;; environment, because that is
                                          ;; exactly what was observed to
                                          ;; change its behaviour.
                                          :host/nofile]))}))

(defn environment-coordinate
  "The canonical-EDN coordinate of the description — recomputed, never
  cached, so attribution cannot drift from description."
  []
  (cedn/coordinate :bb4t/execution-environment (environment-description)))

(defn- sha256 [^String s]
  (apply str (map #(format "%02x" %)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes s "UTF-8")))))

(defn coordinate
  "A stable name for this environment's pinned POLICY. Distinct from the
  verify environment's `js1-smve/v1:` coordinate by prefix as well as by
  content: two coordinates that could be confused would defeat the point of
  keeping the two kinds of evidence apart."
  []
  (str "js2-spe/v1:"
       (sha256 (pr-str {:manager [smve/manager-exec-name
                                  (vec (sort smve/approved-manager-versions))]
                        :limits (into (sorted-map) resource-limits)
                        :request (into (sorted-map) request-limits)
                        :options (vec (sort-by str request-option-keys))
                        :guest {:prelude-contract smve/prelude-contract
                                :environment (into (sorted-map)
                                                   smve/guest-environment)
                                :digest (or (:digest (resolved-image))
                                            :unresolved)}
                        :workspace {:exclusions (vec (sort smve/workspace-exclusions))}}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Refusals — this environment's OWN catalogue (RFC-012: catalogues are
;; per-environment while the :spi.refusal/ namespace is shared).
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private refusal-categories
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
   ;; This environment's own, which the verify environment has no analogue
   ;; for: it never has to poison, because nothing it runs is reused, and it
   ;; never refuses a request for being unclean.
   :environment-poisoned :spi.refusal/environment-poisoned
   :host-fd-limit :spi.refusal/host-fd-limit})

(def ^:private refusal-reasons
  {:spi.refusal/not-linux
   "project execution environment requires Linux; host spawn refused"
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
   :spi.refusal/environment-poisoned
   "prior execution timed out; hard cleanup incomplete"
   :spi.refusal/host-fd-limit
   "controller open-file limit below floor; staging unreliable"
   :spi.refusal/unknown
   "project execution environment refused; reason uncatalogued"})

(defn- envelope!
  [envelope required optional]
  (let [present (set (keys envelope))
        allowed (into required optional)
        missing (not-empty (vec (remove present required)))
        extra (not-empty (vec (remove allowed present)))]
    (when (or missing extra)
      (throw (ex-info "envelope key set is not its kind's"
                      {:samizdat.smolvm-project-env/error :envelope-keys
                       :missing missing :extra extra})))
    (cedn/canonical-tree envelope)
    envelope))

(defn describe-envelope
  "What this environment IS, as the SPI's describe envelope."
  []
  (let [description (environment-description)]
    (when-not (keyword? (:executor/type description))
      (throw (ex-info "environment description names its type"
                      {:samizdat.smolvm-project-env/error :description-type})))
    (envelope!
     {:spi/version envelope-version
      :spi/kind :spi.environment/describe
      :environment/description description
      :environment/coordinate (cedn/coordinate
                               :bb4t/execution-environment description)}
     #{:spi/version :spi/kind :environment/description :environment/coordinate}
     #{})))

(defn refusal-envelope
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

(defn availability-envelope []
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
;; The invocation counter and the poison flag.
;;
;; The counter is claimed IMMEDIATELY BEFORE the machine spawn: refused
;; requests, failed staging and replays never move it — which is exactly what
;; makes "a replayed project/run launched zero machines" a checkable claim
;; rather than a hopeful one.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private invocations (atom 0))

(def ^:private poisoned
  "The invocations whose timeout cleanup has NOT been shown to be clean, as
  {invocation -> evidence}.

  A SET, not a flag, and that is the correction. It was one slot holding one
  invocation, which is indistinguishable from correct while at most one
  execution can be in flight — and loses state the moment two overlap:

    A times out          poison = A
    B times out          poison = B      <- A's uncertainty is now unrecorded
    A cleans up          poison = nil    <- B's uncertainty is gone with it
    C starts             ...on a provider with an unresolved machine

  The last line is the whole problem: C runs believing the environment is
  settled because A said so about itself. Each invocation now poisons and
  resolves its OWN entry, and no invocation can clear another's."
  (atom {}))

(defn invocation-count
  "How many machine executions this process's environment has ATTEMPTED.
  Real spawns only. A replay must not move it."
  []
  @invocations)

(defn poisoned?
  "Whether ANY invocation's timeout cleanup is still unresolved. A boolean
  read-model over the poison set; `unresolved-poison` is what says which."
  []
  (boolean (seq @poisoned)))

(defn unresolved-poison
  "The invocations still poisoned, with the evidence each carries. What an
  operator reads to find out WHICH execution the lane is fenced by; a
  provider that refuses every request without saying why is a provider nobody
  can unblock."
  []
  @poisoned)

(defn- poison!
  "Record one invocation's cleanup as unresolved."
  [invocation evidence]
  (swap! poisoned assoc invocation evidence))

(defn- claim-invocation! [] (swap! invocations inc))

(defn- surviving-machines
  "What the manager's own machine table reports, as text — the sweep's
  observation. Bounded and never throws: a cleanup step that could fail the
  run it is cleaning up after would be worse than the leak it looks for."
  []
  (try
    (let [r (proc/run-bounded {:timeout-ms 15000 :out-max-bytes 65536
                               :err-max-bytes 65536}
                              (str (fs/which smve/manager-exec-name))
                              "machine" "ls")]
      (str/trim (str (:stdout r))))
    (catch Throwable t (str "unreadable: " (ex-message t)))))

(def ^:private ephemeral-machine-name
  "The name shape the manager gives an ephemeral machine. The sweep acts on
  THIS and nothing else: a named machine in the table belongs to whoever
  named it, and a cleanup that stopped a stranger's long-lived machine
  because it was cleaning up after itself would be a worse bug than the leak
  it was chasing."
  #"^vm-[0-9a-f]+$")

(defn- listed-machines
  "The ephemeral machine names the manager's table currently reports."
  [text]
  (->> (str/split-lines (str text))
       (map str/trim)
       (remove str/blank?)
       (keep #(first (str/split % #"\s+")))
       (filter #(re-matches ephemeral-machine-name %))
       distinct
       vec))

(defn owned-machines
  "The machines THIS invocation is entitled to stop and delete, or ::unknown
  when that cannot be established.

  THE CROSS-RUN SAFETY RULE, and the reason it is a named function.

  Ownership has exactly ONE source: the manager's own startup banner, which
  names the machine it started for this spawn. That is not an inference — it
  is the manager telling this invocation which machine is its.

  WITHOUT IT, OWNERSHIP IS UNKNOWN AND NOTHING IS DELETED. There was a
  fallback here that took the single machine which had appeared since a
  baseline read before the spawn, and it is wrong under concurrency in a way
  that is easy to miss:

    A reads its baseline (empty) and spawns
    B spawns after A's baseline was taken
    A times out; A's own machine never registered, or is already gone
    A has no banner id
    the table now holds exactly one machine that A did not see: vm-B

  The difference is {vm-B}, exactly one candidate, and it belongs to B. The
  rule would have deleted a healthy machine belonging to another run, and
  been most confident precisely when it was alone in the world with somebody
  else's VM.

  The baseline and the table are still gathered — as EVIDENCE. They can tell
  an operator `these appeared while this invocation ran`. They may not tell
  the cleanup `therefore kill them`. Leaving an unattributable machine behind
  and refusing further executions is a failure an operator can see and undo;
  deleting another run's machine is one nobody sees until that run reports
  nonsense."
  [baseline id after]
  (let [candidates (vec (remove (set baseline) (listed-machines after)))]
    (if id
      [id]
      ;; No banner, no ownership. `candidates` travels on as evidence only.
      ::unknown)))

(defn- hard-cleanup!
  "The hard boundary after an uncertain timeout.

  A host deadline bounds host WAITING. It does not prove the child inside the
  machine died — the measured A3a result: killing the manager's front end
  leaves the machine running — so the MACHINE, not the wait, has to be the
  thing that ends. `proc/run-bounded` has already reaped the process tree
  descendants-first by the time this runs; this then stops and deletes the
  machines THIS INVOCATION OWNS (see `owned-machines`), and re-asks the
  manager whether they are gone.

  Asking is the point. A run that merely believes its machine is gone and a
  run that asked the manager and was told so are different claims, and only
  the second is evidence.

  When ownership is UNKNOWN nothing is stopped at all: the cleanup reports
  `:cleanup/clean? false` with the unattributable candidates named as
  evidence, this invocation's poison is therefore never lifted, and the lane
  fails closed with the surviving state visible."
  [baseline id]
  (let [manager (str (fs/which smve/manager-exec-name))
        before (surviving-machines)
        owned (owned-machines baseline id before)
        unknown? (= ::unknown owned)
        targets (if unknown? [] owned)
        acted (vec (for [n targets
                         verb ["stop" "delete"]]
                     (do (try (proc/run-bounded {:timeout-ms 30000}
                                                manager "machine" verb n)
                              (catch Throwable _ nil))
                         (str verb " " n))))
        after (surviving-machines)
        remaining (set (listed-machines after))]
    (cond-> {:cleanup/baseline (vec (sort baseline))
             :cleanup/owned (if unknown? :unknown (vec owned))
             :cleanup/machines-before before
             :cleanup/acted acted
             :cleanup/machines-after after
             ;; CLEAN means none of OURS remains — not that the table is
             ;; empty. Another run's machine standing in the table is not this
             ;; invocation's uncleanliness, and treating it as one would fence
             ;; this provider for the life of the process. Unknown ownership
             ;; is never clean: there may be a survivor and we cannot say.
             :cleanup/clean? (and (not unknown?)
                                  (not-any? remaining owned))}
      unknown?
      ;; EVIDENCE, not authority. What appeared while this invocation ran, so
      ;; an operator has somewhere to start; the cleanup acted on none of it.
      (assoc :cleanup/candidates
             (vec (remove (set baseline) (listed-machines before)))))))

(defn resolve-poison!
  "Lift ONE invocation's poison after ITS cleanup came back clean. Exposed so
  the sequencing is visible and testable rather than an implicit side effect,
  and scoped to one invocation so a clean cleanup cannot vouch for an
  execution it knows nothing about."
  [invocation]
  (swap! poisoned dissoc invocation))

;; ═══════════════════════════════════════════════════════════════════════════
;; The run.
;; ═══════════════════════════════════════════════════════════════════════════

(defn request-run-refusal
  "Why a request cannot execute here, or nil when it may. Checked FIRST: a
  refused request spawns nothing and claims no invocation index."
  []
  (cond
    (poisoned?) :environment-poisoned
    (not (available?)) (unavailable-reason)
    (nil? (resolved-image)) :no-guest-image
    ;; Checked before every request, not once at startup: the limit belongs to
    ;; the process, and a controller that lowered its own is a controller whose
    ;; executions would silently start behaving differently.
    (nil? (limiter)) :host-fd-limit
    :else nil))

(defn- refusal-result
  "A refused request as the canonical result shape.

  `:status :refused` is its own status, distinct from `:failed`: a program
  that ran and chose a non-zero exit said something about the project, and a
  request that never ran said something about the host. A model that cannot
  tell those apart will edit code to fix a missing hypervisor."
  [reason]
  {:status :refused
   :reason reason
   :message (message (case reason
                       :environment-poisoned {:run-poisoned true}
                       :host-fd-limit {:run-host-fd-limit true}
                       {:run-unavailable true}))})

(defn- capped-stream
  "One captured stream as the model reads it: the kept text with an honest
  truncation marker when there was more, the stream's TRUE byte count — what
  the workload wrote, not what was kept — and whether that happened."
  [text total-bytes max-bytes]
  {:text (str text (when (> (or total-bytes 0) max-bytes)
                     (message {:ve-truncated true})))
   :bytes (or total-bytes 0)
   :truncated? (> (or total-bytes 0) max-bytes)})

(def ^:private machine-banner
  "The manager's own progress line, which it writes to stderr and has no
  quiet flag for. Removed from the captured stream so the capture describes
  the workload alone — and mined on the way past for the machine id, which
  is the ONE thing that makes the timeout sweep precise instead of a
  guess at somebody else's machine."
  #"Starting ephemeral machine \((vm-[0-9a-f]+)\)\.\.\.")

(defn machine-id
  "The ephemeral machine's own id, from the manager's banner, or nil.

  THE ONLY SOURCE OF OWNERSHIP (see `owned-machines`), and public so a test
  can suppress it and prove the cleanup refrains from acting rather than
  guessing. A pure function over captured text."
  [stderr-text]
  (second (re-find machine-banner (str stderr-text))))

(defn- workload-stderr
  "The manager's own banner removed from the captured stderr, and its bytes
  from the count, so the stream describes the workload alone."
  [result]
  (let [text (str (:stderr result))
        total (or (:stderr/bytes result) 0)
        banner (first (re-find machine-banner text))]
    (if banner
      (let [rest (subs text (count banner))
            newline? (str/starts-with? rest "\n")
            cut (+ (count banner) (if newline? 1 0))]
        {:text (subs text (min cut (count text)))
         :bytes (max 0 (- total (count (.getBytes ^String banner "UTF-8"))
                          (if newline? 1 0)))})
      {:text text :bytes total})))

(defn- prelude-failure?
  [{:keys [status exit]} stderr]
  (and (= :exited status)
       (= smve/prelude-exit exit)
       (str/includes? (str stderr) smve/prelude-marker)))

(defn build-execution
  "Compose EVERYTHING one execution spawns — the full manager argv, the
  hidden paths, the derived identity, the staging input coordinate — WITHOUT
  spawning it. The pure-with-effects half, so a test can pin exactly what
  would run (and exactly what a model cannot reach) without paying for a
  machine."
  [root request]
  (let [image (:image (smve/guest-image))
        manifest (smve/input-manifest root)
        identity (smve/guest-identity (smve/project-identity root))
        host-ms (:request/timeout-ms request)]
    {:argv (into (conj (vec (limiter)) (str (fs/which smve/manager-exec-name)))
                 (smve/machine-argv
                  {:root (str (fs/canonicalize root))
                   :image (:path image)
                   :argv (:request/argv request)
                   :identity identity
                   :hidden (:workspace/excluded-paths manifest)
                   :host-timeout-ms host-ms}))
     :cwd (:request/cwd request)
     :hidden (:workspace/excluded-paths manifest)
     :identity identity
     :input-coordinate (:workspace/coordinate manifest)
     :image image}))

(defn- with-cwd
  "The manager argv with the model's working directory in the prelude's CWD
  slot. `machine-argv` composes the guest command with `.` there; the slot is
  positional data in the prelude's contract, and this replaces exactly that
  one element — the LAST `.` before the workload argv."
  [argv cwd request-argv]
  (if (= "." cwd)
    argv
    (let [tail (count request-argv)
          idx (- (count argv) tail 1)]
      (when-not (and (pos? idx) (= "." (nth argv idx)))
        (throw (ex-info "guest command shape is not the prelude's contract"
                        {:samizdat.smolvm-project-env/error :guest-command-shape})))
      (assoc (vec argv) idx cwd))))

(defn run
  "Execute the model's `request` against `root` inside a fresh ephemeral
  machine, and report the canonical structured result.

  Never throws for a run's own outcome: an unavailable substrate, a refused
  request, a failed staging, a non-zero exit and a deadline are all RESULTS,
  because the model reads this value and a host exception is not something it
  can act on. A request that is not a valid request DOES throw, before any
  staging — that is a programming error in the model's call, reported to it
  as an evaluation error like any other bad argument.

  The result carries no live object, no lazy value, and nothing outside the
  receipt domain: it is recorded into a durable receipt verbatim and replayed
  out of one, so every value in it must be inert."
  [root request]
  (if-let [reason (request-run-refusal)]
    (refusal-result reason)
    (try
      (let [{:keys [argv input-coordinate]} (build-execution root request)
            host-ms (:request/timeout-ms request)
            full-argv (with-cwd argv (:request/cwd request)
                                (:request/argv request))
            ;; THE OWNERSHIP BASELINE, read immediately before the spawn.
            ;; Everything the manager already holds belongs to somebody else —
            ;; another run, another process — and is never a candidate for
            ;; this invocation's cleanup. Read on EVERY execution because
            ;; whether one will time out is not knowable in advance, and a
            ;; baseline taken after the fact is not a baseline.
            baseline (listed-machines (surviving-machines))
            ;; Claimed immediately before the spawn, and by nothing else.
            index (claim-invocation!)
            started (System/nanoTime)
            raw (try (apply proc/run-bounded
                            {:timeout-ms host-ms
                             :out-max-bytes (:worker/stdout-max-bytes resource-limits)
                             :err-max-bytes (:worker/stderr-max-bytes resource-limits)}
                            full-argv)
                     (catch Throwable t
                       {:status :start-failure :error/message (ex-message t)}))
            r (merge {:stdout "" :stdout/bytes 0 :stderr "" :stderr/bytes 0} raw)
            duration-ms (long (quot (- (System/nanoTime) started) 1000000))
            stderr (workload-stderr r)
            timed-out? (= :timeout (:status r))
            ;; POISON FIRST, then clean, then lift. The ordering is the
            ;; contract: no execution may be issued while the poison stands,
            ;; and the poison stands until the machine table has been swept.
            _ (when timed-out?
                (poison! index {:invocation index
                                :machine (machine-id (:stderr r))
                                :at (System/currentTimeMillis)}))
            cleanup (when timed-out?
                      (let [c (hard-cleanup! baseline (machine-id (:stderr r)))]
                        ;; THIS invocation's poison, and only this one. A
                        ;; clean cleanup vouches for the machine it stopped;
                        ;; it says nothing about an execution running beside
                        ;; it that has not finished failing yet.
                        (when (:cleanup/clean? c) (resolve-poison! index))
                        c))
            status (cond
                     timed-out? :timeout
                     (= :start-failure (:status r)) :failed
                     (prelude-failure? r (:text stderr)) :failed
                     :else :completed)
            known (secrets/known-values (into {} (System/getenv)))
            redact (fn [s] (secrets/redact (str s) known))
            out (capped-stream (:stdout r) (:stdout/bytes r)
                               (:worker/stdout-max-bytes resource-limits))
            err (capped-stream (:text stderr) (:bytes stderr)
                               (:worker/stderr-max-bytes resource-limits))
            ;; SIMPLE keys, deliberately. This value is what a model reads
            ;; and branches on inside one eval — (:exit r), (get-in r
            ;; [:stderr :text]) — and an ergonomics tax on the shape a model
            ;; must destructure is paid in model turns. The cross-repository
            ;; SPI vocabulary is the ENVELOPE's (run-envelope below), which is
            ;; where a second keeper reads it; the two are different audiences
            ;; and this is the one that has to be pleasant.
            base {:status status
                  :invocation index
                  ;; WHICH MACHINE ran this, as the manager named it in its
                  ;; own startup banner. Attribution data, like :invocation:
                  ;; it is what makes "this cleanup touched only its own
                  ;; machine" a checkable claim rather than an assurance, and
                  ;; it is an opaque id for a machine that no longer exists by
                  ;; the time anybody reads it.
                  :machine (machine-id (:stderr r))
                  :argv (:request/argv request)
                  :cwd (:request/cwd request)
                  :timeout-ms host-ms
                  :duration-ms duration-ms
                  :environment (environment-coordinate)
                  :input input-coordinate
                  :stdout (update out :text redact)
                  :stderr (update err :text redact)
                  ;; :terminated — the machine is ephemeral and is destroyed
                  ;; however the run ends; :hard-cleaned says the timeout
                  ;; path's explicit sweep also ran.
                  :disposition (if timed-out? :hard-cleaned :terminated)}]
        (into {} (remove (fn [[_ v]] (nil? v)))
              (cond-> base
                ;; An exit survives ONLY when the workload exited. A deadline
                ;; is not a program that chose a number.
                (= :completed status) (assoc :exit (:exit r))
                (= :failed status) (assoc :error
                                          (or (:error/message r)
                                              (str/trim (str (:text stderr)))))
                cleanup (assoc :cleanup
                               (cond-> {:acted (:cleanup/acted cleanup)
                                        :clean? (:cleanup/clean? cleanup)
                                        :owned (:cleanup/owned cleanup)}
                                 (:cleanup/candidates cleanup)
                                 (assoc :candidates
                                        (:cleanup/candidates cleanup)))))))
      (catch Throwable e
        ;; A staging failure (unrepresentable symlink, a tree over the
        ;; manifest budget, a root-owned project) is a FAILED run the model
        ;; can act on, not a host exception it cannot.
        {:status :failed
         :argv (:request/argv request)
         :cwd (:request/cwd request)
         :error (str (ex-message e))
         :disposition :not-started}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The run envelope (RFC-012 :spi.execution/run).
;;
;; DEVELOPMENT evidence. It is journalled under its own key and read by
;; nothing that decides completion.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private envelope-statuses #{:completed :timeout :worker-failure})

(defn run-envelope
  "One `run` result as the SPI's run envelope, or nil when the result is not
  a run (a refused request and a failed staging claimed no invocation index
  and spawned nothing, so there is no execution to envelope)."
  [result]
  (when (and (map? result) (:invocation result))
    (when-not (pos? (:invocation result))
      (throw (ex-info "invocation index starts at one"
                      {:samizdat.smolvm-project-env/error :envelope-index
                       :run/invocation (:invocation result)})))
    (let [status (case (:status result)
                   :completed :completed
                   :timeout :timeout
                   :worker-failure)
          _ (when-not (contains? envelope-statuses status)
              (throw (ex-info "unknown run status"
                              {:samizdat.smolvm-project-env/error :envelope-status
                               :output/status status})))
          stream (fn [s] {:stream/text (:text s)
                          :stream/bytes (:bytes s)
                          :stream/truncated? (:truncated? s)})]
      (envelope!
       (into {} (remove (fn [[_ v]] (nil? v)))
             {:spi/version envelope-version
              :spi/kind :spi.execution/run
              :run/invocation-index (:invocation result)
              :run/attribution {:environment/coordinate (:environment result)
                                :environment/type executor-type}
              :run/input {:input/coordinate (:input result)}
              :output/status status
              :output/exit (when (= :completed status) (:exit result))
              :output/stdout (stream (:stdout result))
              :output/stderr (stream (:stderr result))
              :output/duration-ms (:duration-ms result)
              :output/error (when (= :worker-failure status)
                              "project execution environment run failed")
              :run/disposition (:disposition result)})
       #{:spi/version :spi/kind :run/invocation-index :run/attribution
         :run/input :output/status :output/stdout :output/stderr
         :output/duration-ms :run/disposition}
       #{:output/exit :output/error}))))
