;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.verify
  "The ship gate's test rung — what makes the worker loop TEST-DRIVEN instead of
  one-shot. A `done` is not terminal until the unit's tests actually pass: the
  gate runs the verification, and a red run (or a hollow / untested / REFUSED
  change) is fed back so the branch keeps iterating (edit -> run -> observe ->
  fix) rather than shipping unverified work.

  Verification is FOCUSED by default: it runs only the test namespaces the
  branch actually touched, so the loop iterates in seconds against its own new
  test rather than paying for the whole suite each time. A configured
  :verify-cmd is the fallback when nothing focusable changed — or when a
  changed path cannot be SAFELY represented (below).

  Trust shape (the critical finding of docs/code-review-2026-08-3.md): the
  model's only input to verification is the `done` call itself. The paths
  focused verification reads come from git, and a path a model can name is
  attacker-controlled data, so they are validated against a NARROW namespace
  grammar and every unrepresentable path REFUSES focused verification —
  falling back to the operator's configured command only when one already
  exists, never to anything derived from the paths. Execution is one
  STRUCTURED request — argv vector, cwd, explicit scrubbed allowlist
  environment, timeout, independent stdout/stderr byte caps — through
  samizdat.engine.proc/scope-run (jolt.host's scoped process primitive),
  so no shell composes anything and no controller environment is
  inherited; the process TREE is bounded by the primitive's
  TERM/grace/KILL//proc-confirmed-empty ladder; and the ENTIRE output is
  redacted (samizdat.security.secrets) before any model-visible
  rendering of it happens.

  The runner is a thin effect; the DECISION (`verify-block`) and the
  derivations (`verify-request`, `ns-from-test-path`) are pure, so the gate is
  testable without spawning a process. Same split as planner.clj vs
  cells/team.clj."
  (:require [clojure.string :as str]
            [samizdat.engine.proc :as proc]
            [samizdat.security.secrets :as secrets]
            [samizdat.util :as util]))

(def default-timeout-ms
  "The verify timeout when :run :verify-timeout-ms is not configured — the
  same 10 minutes the rung always allowed."
  600000)

(def term-grace-ms
  "How long a timed-out verification tree gets to die of SIGTERM before the
  KILL wave — generous enough for a Clojure runtime to flush, short enough
  that the rung still decides in seconds."
  2000)

(def out-bytes
  "The independent stdout byte cap for a verification run. A suite flooding
  past this is truncated, marked as such in the model-visible output, and
  ENDED (the overflow runs the same kill ladder a timeout runs) — a flooded
  log is not a green one."
  131072)

(def err-bytes
  "The independent stderr byte cap — same semantics, separate stream: neither
  flood can starve the other's drain."
  131072)

(def render-chars
  "How much of the (already redacted, already capped) output the block
  message renders — the failure tail the branch acts on, bounded."
  6000)

(defn test-file?
  "Whether a changed path is a test/spec file — the evidence that a change was
  pinned by a test, which the TDD ship gate requires."
  [path]
  (boolean (re-find #"(?i)(^|/)(test|spec)s?/|[_-](test|spec)\.[a-z]+$" (str path))))

;; --- focused derivation, against a narrow grammar -----------------------------
;;
;; The old derivation replaced _ and / in a changed path and interpolated the
;; result into a shell string; the safety argument was that the expression
;; carries no quote of its own — which assumes away exactly the character a
;; model-chosen file name can carry. The derivation is still _ and / — but its
;; OUTPUT is now checked against a grammar narrow enough that nothing derived
;; from a changed path can mean anything but a namespace, and anything that
;; fails is REFUSED (named, fed back, fail-closed), never skipped silently and
;; never passed through.

(def ^:private ns-segment-re
  "One segment of the narrow namespace grammar focused verification will
  materialize into an argv: a lowercase letter, then lowercase alphanumerics
  and single hyphens. Anything a changed path derives outside this — quotes,
  semicolons, any shell metacharacter, whitespace, casing, Unicode, leading
  digits or dashes, dot or traversal segments — is unrepresentable."
  #"[a-z][a-z0-9]*(-[a-z0-9]+)*")

(def ^:private ns-re
  "A whole derived namespace: dot-joined narrow segments (ns-segment-re —
  keep the two literals in sync). This is the entire safety argument for
  the focused argv: a string matching it is inert as Clojure data and
  inert as an argv element."
  #"^[a-z][a-z0-9]*(-[a-z0-9]+)*(\.[a-z][a-z0-9]*(-[a-z0-9]+)*)*$")

(def ^:private clj-ext-any
  "Detection of a Clojure test path is case-insensitive, so a name like
  x_test.CLJ still counts as a test claim (and is then refused — the strict
  strip below will not take the extension)."
  #"(?i)\.cljc?$")

(def ^:private clj-ext-strict
  "The extension the derivation can actually strip: lowercase .clj/.cljc."
  #"\.cljc?$")

(defn ns-from-test-path
  "The Clojure namespace a test file defines: strip the leading source root
  (test/ or gui/ or src/), drop the extension, '/'->'.', '_'->'-'. Returns
  the namespace ONLY when the whole derivation stays inside the narrow
  grammar — nil otherwise, and the caller REFUSES the path: a changed test
  path that cannot be represented is an adversarial or broken name, and the
  honest outcomes are the operator's configured fallback or a blocked ship,
  never a silent skip and never a passthrough.
  e.g. \"test/samizdat/agent/decompose_test.clj\" -> \"samizdat.agent.decompose-test\"."
  [path]
  (when (and (string? path)
             (re-find clj-ext-any path)
             (re-find clj-ext-strict path))
    (let [ns (-> path
                 (str/replace #"^(test|gui|src)/" "")
                 (str/replace #"\.cljc?$" "")
                 (str/replace "_" "-")
                 (str/replace "/" ".")
                 not-empty)]
      (when (and ns (re-matches ns-re ns))
        ns))))

(defn focused-namespaces
  "Split the Clojure test paths among `changed` into {:nses […]} — the
  namespaces focused verification may run — and {:refused […]} — the paths it
  must refuse. Only paths that both claim to be tests (test-file?) and claim
  to be Clojure (a .clj/.cljc extension, any case) are classified; a non-
  Clojure test path (a fixture under tests/) is neither focusable nor
  suspicious, exactly as before. A nil `changed` (git cannot tell) classifies
  to nothing."
  [changed]
  (reduce (fn [acc path]
            (if-let [n (ns-from-test-path path)]
              (update acc :nses conj n)
              (update acc :refused conj (str path))))
          {:nses [] :refused []}
          (filter #(and (test-file? %) (re-find clj-ext-any (str %)))
                  (or changed []))))

(defn- focused-argv
  "The argv that runs ONLY `nses`: jolt's -A:test classpath with an -e
  expression that requires the namespaces, runs them, prints the summary and
  exits non-zero on any failure or error (a bare -e does not set the code).
  The namespace strings are already grammar-validated — inert as data — and
  the whole expression is ONE argv element handed to the scoped primitive:
  there is no shell to quote it for, so quoting cannot be gotten wrong."
  [nses]
  (let [quoted (str/join " " (map #(str "(quote " % ")") (distinct nses)))
        expr (str "(require (quote clojure.test) " quoted ")"
                  "(let [s (clojure.test/run-tests " quoted ")]"
                  "(clojure.core/println s)"
                  "(clojure.core/flush)"
                  "(java.lang.System/exit (if (clojure.core/pos? (+ (:fail s) (:error s))) 1 0)))")]
    ["jolt" "-A:test" "-e" expr]))

(defn- fallback-argv
  "The trusted operator fallback as argv: the configured :verify-cmd string,
  VERBATIM, as the single operand of sh -c. This is the only shell anywhere
  on the verify path, and it is structurally composition-free: nothing —
  root, cwd, changed path, namespace, timeout — is ever interpolated into
  the string, which comes from operator config alone (verify-request is its
  only caller and passes the config value untouched). The cwd is the
  request's :dir, the environment is the scrubbed allowlist, and the process
  tree is scoped and bounded exactly like the focused run — the operator's
  fallback POLICY (a shell command, run in the project root, exit 0 passes)
  is preserved whole, while the model's input stays outside it."
  [cmd]
  ["sh" "-c" cmd])

(defn verify-request
  "Derive the STRUCTURED verification request for a `done` from `changed`
  (the git-reported changed paths), `focused?` (the :verify-focused?
  config) and `fallback-cmd` (the operator's :verify-cmd — used only when
  a non-blank string is ALREADY configured; nothing is ever invented here).

  Returns one of:
    {:kind :focused  :argv […] :nses […]}   run only the changed tests
    {:kind :fallback :argv […] }            run the operator's command
    {:kind :refused  :refused […]}          unrepresentable changed paths
                                            and NO trusted fallback —
                                            nothing may run
    {:kind :invalid  :reason …}              the configured :verify-cmd is
                                            present but not a command
                                            STRING — an operator error,
                                            which FAILS CLOSED (blocks)
                                            rather than silently
                                            skipping verification
    nil                                     focused? off with no fallback,
                                            or nothing focusable changed
                                            with no fallback — the rung's
                                            existing not-run semantics.

  Model input enters ONLY through `changed`, and it can only choose between
  focused-over-validated-namespaces and the refused / configured-fallback
  outcomes — never an executable, argv element, env var, cwd or timeout of
  its own. The one non-model input that can refuse the request is a
  malformed OPERATOR config, and that refusal is a block, not a skip."
  [{:keys [changed focused? fallback-cmd]}]
  (let [invalid (when (and (some? fallback-cmd) (not (string? fallback-cmd)))
                  {:kind :invalid
                   :reason (str ":run :verify-cmd must be a shell-command"
                                " string when configured; got a "
                                (type fallback-cmd))})
        fallback (when (and (string? fallback-cmd) (seq (str/trim fallback-cmd)))
                   {:kind :fallback :argv (fallback-argv fallback-cmd)})]
    (or invalid
        (if-not focused?
          fallback
          (let [{:keys [nses refused]} (focused-namespaces changed)]
            (cond
              (seq refused) (or fallback {:kind :refused :refused refused})
              (seq nses)    {:kind :focused :argv (focused-argv nses)
                             :nses (vec (distinct nses))}
              :else         fallback))))))

(defn- tail
  "The last n non-blank lines of s — enough of a failure to act on without
  dragging the whole test log into the branch's context."
  [s n]
  (->> (str/split-lines (str s)) (remove str/blank?) (take-last n) (str/join "\n")))

(defn- annotate-stream
  "The captured stream with an honest marker when the cap or the kill cut it
  short — the model must be able to tell a truncated log from a complete one
  (and a truncated run is never green, so the marker explains the red)."
  [s status cap which]
  (str (or s "")
       (case status
         :truncated (str "\n… [" which " truncated at " cap " bytes]")
         :partial   (str "\n… [" which " capture incomplete]")
         nil)))

(defn run-verify
  "Execute a structured verification REQUEST (verify-request's shape) in
  `root` and report whether it is green. The request's :argv is exec'd
  DIRECTLY by the scoped primitive — cwd is `root`, the child environment is
  the explicit scrubbed allowlist (nothing inherited from the controller),
  the whole process TREE is bounded by `timeout-ms` (default
  default-timeout-ms) through the primitive's TERM/grace/KILL ladder with
  a /proc-confirmed-empty scope, and stdout/stderr are captured under the
  independent out-bytes / err-bytes caps. The ENTIRE combined output is
  redacted — existing secrets discipline: vendor-shaped credentials plus
  every name-sensitive value the source environment holds — BEFORE any
  model-visible rendering happens.

  Fail-closed boundaries, checked before anything spawns: a :refused or
  :invalid request returns a clear not-green result naming why (nothing
  runs); a runtime without the scoped primitive returns an :unsupported?
  result (proc/scope-supported? — verification NEVER degrades to an
  inherited-env or unscoped spawn, and the caller blocks the ship); a
  `timeout-ms` that is present but not a positive integer returns an
  :invalid-config? result rather than defaulting or skipping.

  Optional `env` (the source environment for the allowlist and the redaction
  known-set) defaults to the controller's own; it is an operator/test seam,
  never model input. Never throws — every failure mode reads as not-green,
  which sends the branch back rather than shipping."
  ([root request timeout-ms] (run-verify root request timeout-ms nil))
  ([root request timeout-ms env]
   (let [not-green (fn [m] (merge {:green? false :timeout? false} m))]
     (cond
       (= :refused (:kind request))
       (not-green {:refused? true
                   :output (str "verification refused: changed test path(s)"
                                " cannot be represented safely and no"
                                " trusted fallback is configured")})

       (= :invalid (:kind request))
       (not-green {:invalid-config? true
                   :output (str "verification misconfigured: "
                                (:reason request))})

       ;; The scoped primitive is the only execution path this rung has;
       ;; absent it, verification is UNAVAILABLE — never silently
       ;; downgraded to an unscoped spawn or an inherited environment.
       (not (proc/scope-supported?))
       (not-green {:unsupported? true
                   :output (str "verification unavailable: the scoped process"
                                " execution primitive is not supported on"
                                " this runtime (Linux with posix_spawn"
                                " process-group and poll(2) support is"
                                " required). Verification cannot run, so"
                                " the ship is blocked rather than trusted.")})

       (and (some? timeout-ms)
            (or (not (int? timeout-ms)) (not (pos? timeout-ms))))
       (not-green {:invalid-config? true
                   :output (str "verification misconfigured: :run"
                                " :verify-timeout-ms must be a positive"
                                " integer milliseconds value when set; got "
                                (pr-str timeout-ms) ". The ship is blocked"
                                " rather than defaulted.")})

       :else
       (try
         (let [env (or env (into {} (System/getenv)))
               r (proc/scope-run {:cmd (:argv request)
                                  :dir (str root)
                                  :env (proc/scrubbed-allowlist-env env)
                                  :timeout-ms (or timeout-ms default-timeout-ms)
                                  :term-grace-ms term-grace-ms
                                  :out-bytes out-bytes
                                  :err-bytes err-bytes})
               out (annotate-stream (:out r) (:out-status r) out-bytes "stdout")
               err (annotate-stream (:err r) (:err-status r) err-bytes "stderr")]
           {:green? (and (not (:timed-out r)) (zero? (or (:exit r) 1)))
            :timeout? (boolean (:timed-out r))
            :exit (:exit r)
            ;; known-values returns a set; secrets/redact canonicalizes any
            ;; seqable itself (longest-first, since the jolt
            ;; distinct-over-set fix), and vec just keeps the call shape
            ;; explicitly a seq of values.
            :output (secrets/redact (str out "\n" err)
                                    (vec (secrets/known-values env)))})
         (catch Throwable e
           (not-green {:output (str "verify command failed to run: "
                                    (ex-message e))})))))))

(defn verify-block
  "The pure ship decision. Returns nil when `done` may ship, or a block message
  explaining what to fix (which becomes the tool result the branch reads and
  iterates on).

    :verify-on?     whether this loop verifies at all (a :verify-cmd or
                    focused verification is configured). When false the
                    rung is inert.
    :request        the structured request verify-request derived — its
                    :kind explains a refusal (:refused names the offending
                    paths) or an operator misconfiguration (:invalid).
    :result         {:green? :timeout? :output} from run-verify, or nil
                    when the tests were not run (e.g. git could not tell
                    what changed). :unsupported? / :invalid-config?
                    results carry their own fail-closed branches below.
    :changed        changed-files since the attempt baseline: a vector, []
                    for 'genuinely nothing', or nil for 'git cannot tell'.
    :require-test?  enforce TDD — a change that includes no test file is
                    refused.
    :unsupported?   the runtime cannot execute scoped verification at all
                    (proc/scope-supported? false — off Linux / missing
                    primitive). Verification is UNAVAILABLE, and an
                    unavailable gate BLOCKS: the trust-without-verify
                    fallthrough must never fire just because the executor
                    is missing."
  [{:keys [verify-on? result request changed require-test? unsupported?]}]
  (cond
    (not verify-on?) nil

    ;; Cheap pre-checks first (no test run needed to decide these):
    ;; the worker changed nothing — it shipped without doing the work.
    (and (some? changed) (empty? changed))
    (str "The suite is green but you changed no files, so nothing was actually "
         "done. Make the change on disk (edit_file/write_file), prove it with a "
         "test, then call done.")

    ;; TDD: files changed but none is a test — the behaviour was never pinned.
    (and require-test? (some? changed) (seq changed)
         (not (some test-file? changed)))
    (str "You added no test, so the new behaviour is not pinned. Write a focused "
         "test that FAILS without your change and passes with it, get it green, "
         "then call done.")

    ;; The execution boundary is missing: scoped verification cannot run on
    ;; this runtime at all, so nothing ran and nothing MAY run. This is a
    ;; harness/runtime condition, not a worker error — but the ship still
    ;; fails closed: an unverifiable `done` is not a shipped `done`, and the
    ;; git-cannot-tell trust fallthrough below must not absorb it.
    (or unsupported? (and result (:unsupported? result)))
    (str "Verification UNAVAILABLE: this runtime does not provide the scoped "
         "process execution primitive verification depends on (Linux with "
         "posix_spawn process-group and poll(2) support is required), so the "
         "tests cannot be run here.\n\nThis is not a failure of your change — "
         "but an unverified `done` cannot ship. Fix the runtime (or run on a "
         "supported one) and call done again.")

    ;; An operator misconfiguration in the verify wiring itself: a
    ;; :verify-cmd that is not a command string. Surfaced loudly rather
    ;; than silently skipped — the operator asked for verification and
    ;; would otherwise believe it happened.
    (= :invalid (:kind request))
    (str "Verification MISCONFIGURED: " (:reason request)
         ". A configured verify command must be a string; fix the :run "
         ":verify-cmd setting. Until then `done` is blocked rather than "
         "shipping unverified.")

    ;; The same fail-closed rule for a nonpositive/invalid configured
    ;; timeout, reported by run-verify rather than defaulted.
    (and result (:invalid-config? result))
    (str "Verification MISCONFIGURED — the ship is blocked, not defaulted:\n"
         (tail (:output result) 4))

    ;; A changed test path that cannot be represented safely, with no trusted
    ;; fallback configured: nothing ran, and nothing MAY run — fail closed
    ;; rather than ship unverified (the model cannot un-refuse itself).
    (= :refused (:kind request))
    (str "Verification REFUSED: the changed test file(s) "
         (str/join ", " (map #(str "`" % "`") (take 4 (:refused request))))
         " cannot be represented safely for focused verification — a test path "
         "has to be plain ASCII Clojure namespace segments (lowercase "
         "[a-z0-9-], no quotes, shell metacharacters, spaces, Unicode, casing "
         "or traversal). No trusted fallback :verify-cmd is configured, so "
         "nothing was run. Rename the file to a representable namespace path "
         "and call done again.")

    (and result (:timeout? result))
    (str "Your test run TIMED OUT. Something you changed likely loops or blocks. "
         "Narrow it down — run a smaller piece at the REPL — then call done again.")

    (and result (not (:green? result)))
    (str "Your tests are not green yet:\n\n"
         (util/truncate-middle (tail (:output result) 25) render-chars)
         "\n\nYou are NOT done until they pass. Read the failure, change the code, "
         "re-run the test, and call done again only once it is green.")

    ;; Ran and green.
    (and result (:green? result)) nil

    ;; verify on and the change looked fine, but the tests were not run
    ;; (refused with a fallback that ran green is handled above; this is the
    ;; git-cannot-tell trust case) — trust rather than deadlock the loop.
    :else nil))
