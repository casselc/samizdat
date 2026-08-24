;; samizdat - a claim-first verification harness
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

(ns samizdat.engine.proc
  "Subprocess helpers shared by the engines.

  Every engine call is bounded. An unbounded one is not a slow call, it is a
  branch that never returns its turn, and with five branches in the beam that
  is the whole run. `run` kills the process tree on timeout rather than
  leaving an orphan holding a pipe.

  `scope-run` is the STRUCTURED execution seam for paths a model can
  influence: argv + cwd + explicit environment + timeout + independent
  output byte caps, straight to jolt.host's scoped process primitive — no
  shell is built anywhere in the API, so there is no quoting to get right.
  See its docstring for the request contract."
  (:require [clojure.string :as str]
            [jolt.process :as p]
            [samizdat.security.secrets :as secrets]))

(def ^:private sigterm-grace-ms
  "How long a process gets to die of SIGTERM before it is sent SIGKILL."
  2000)

(defn- reap!
  "Kill `proc` and do not return until it is actually gone.

  SIGTERM first, then SIGKILL if it is still alive. `destroy-tree` sends only
  SIGTERM, and the whole point of a timeout is that the process is already not
  behaving, so treating its cooperation as optional is the only honest
  approach. Same principle the rest of the loop follows: a stop path that
  depends on the component agreeing to stop is not a stop path."
  [proc]
  (let [^java.lang.Process p (:proc proc)]
    (try (p/destroy-tree proc) (catch Throwable _ nil))
    (try
      (when-not (.waitFor p sigterm-grace-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (.destroyForcibly p)
        (.waitFor p sigterm-grace-ms java.util.concurrent.TimeUnit/MILLISECONDS))
      (catch Throwable _ nil))))

(defn run
  "Run `args` with `input` on stdin, capturing stdout and stderr.

  Returns {:exit :out :err} or {:timeout true :ms n} if it did not finish
  inside `timeout-ms`. Never throws on a non-zero exit: z3 exits non-zero
  after `(get-model)` on an unsat formula even though the verdict itself was
  emitted cleanly, so the caller reads the output and decides.

  The wait is `.waitFor` with an explicit timeout rather than a timed `deref`,
  which does not work. jolt's `clojure.core/deref` forwards no opts to a record
  implementing IBlockingDeref, so (deref proc ms ::timeout) silently calls the
  blocking one-arity and waits for however long the process takes. It fails
  quietly, in the direction of doing nothing: the timeout branch below was
  simply unreachable, every engine call was unbounded, and the processes this
  believed it was killing accumulated. Twenty-eight orphaned z3 processes were
  found on one dev machine, the oldest at seventeen hours, slowing everything
  else enough to make an unrelated Mathlib import look sixteen times more
  expensive than it is. Fixed upstream too, but this does not depend on that."
  [{:keys [input timeout-ms env]} & args]
  ;; babashka.process/process takes the command vector FIRST and the options
  ;; map second. Passing them the other way round (which is what `sh` accepts)
  ;; stringifies the vector into an argv[0] of "[z3".
  ;;
  ;; :env, when given, is the COMPLETE environment the child sees — jolt's
  ;; process shim runs it as `env -i K=V …`, so nothing from the parent leaks
  ;; in. The shell tool relies on this: it hands a scrubbed environment here so
  ;; a subprocess cannot read a secret the parent holds (samizdat.security).
  (let [proc (p/process (vec args)
                        (cond-> {:in (or input "") :out :string :err :string}
                          env (assoc :env env)))
        ^java.lang.Process p (:proc proc)
        ms (or timeout-ms 30000)
        finished? (try
                    (.waitFor p ms java.util.concurrent.TimeUnit/MILLISECONDS)
                    (catch Throwable _ false))]
    (if-not finished?
      (do (reap! proc) {:timeout true :ms ms})
      ;; Exited, so this deref returns immediately and only collects the
      ;; already-complete stdout/stderr.
      (let [done @proc]
        {:exit (:exit done)
         :out (or (:out done) "")
         :err (or (:err done) "")}))))

(defn available?
  "Whether `bin` can be executed at all. Used by the smoke probes and to give
  a clear error instead of a stack trace when a toolchain is missing."
  [bin]
  (try
    (let [{:keys [exit timeout]} (run {:timeout-ms 5000} bin "--version")]
      (and (not timeout) (some? exit)))
    (catch Throwable _ false)))

;; --- scoped structured execution ---------------------------------------------
;;
;; The trusted controller primitive is the jolt.host scoped run (Linux,
;; posix_spawn with POSIX_SPAWN_SETPGROUP): a STRUCTURED request — argv
;; vector, cwd, explicit envp, controller timeout, independent output byte
;; caps — exec'd with no shell anywhere in the API. The ownership guarantee
;; is the reason this and not ProcessBuilder: when the call returns, the
;; owned process TREE is empty — TERM wave, grace, KILL wave, then a /proc
;; scan that keeps signalling until it agrees — and that guarantee holds for
;; timeouts, output overflows and clean exits alike. Everything below is a
;; checked, fail-closed Clojure doorway onto it.

(def ^:dynamic *scope-run*
  "The trusted controller process-scope primitive `scope-run` hands its
  checked request to (the jolt.host scoped run at the pinned runtime).
  Dynamic ONLY so tests can capture the exact structured request or fake
  results without spawning; production code never rebinds it, and nothing
  outside this namespace reaches around it to the primitive directly."
  jolt.host/process-scope-run)

(def ^:private scope-env-allowlist
  "The ONLY controller-environment names a scoped child may ever see. A
  scoped run hands the primitive an explicit envp, so inheritance is a
  caller choice — and the only choice offered here is this short list of
  names a build tool genuinely needs, each still subject to the value
  scrub below. Everything else the controller holds (provider keys above
  all) never crosses the spawn boundary at all."
  ["PATH" "HOME" "LANG" "LC_ALL" "LC_CTYPE" "TERM" "TMPDIR"])

(defn scrubbed-allowlist-env
  "The explicit child environment for a scoped run: `env` (default: the
  controller's own environment) intersected with the allowlist, then
  scrubbed by the existing secrets discipline (samizdat.security.secrets —
  name-sensitive names are gone by construction, and any value-shaped
  credential surviving under an allowed name is redacted). The result is
  the COMPLETE environment the child sees: nothing else is inherited."
  ([] (scrubbed-allowlist-env (into {} (System/getenv))))
  ([env] (secrets/scrub-env (select-keys (or env {}) scope-env-allowlist))))

(defn- bad-env-entry?
  "Whether one env entry cannot be represented as an honest envp NAME=VALUE
  pair — a non-string, an empty or '='-bearing name (it would corrupt the
  pair), or a NUL in either half."
  [[k v]]
  (or (not (string? k)) (not (string? v)) (str/blank? k)
      (str/includes? k "=") (str/includes? k "\0") (str/includes? v "\0")))

(defn scope-run
  "Execute one STRUCTURED request through the trusted controller scope
  primitive. Request keys — exactly these, all checked before anything
  spawns:

    :cmd           argv vector of non-empty strings. Exec'd DIRECTLY: the
                   primitive builds no shell, so no quoting question exists
                   and no metacharacter in an argv element can become
                   syntax.
    :timeout-ms    REQUIRED positive integer — the controller clock. When
                   it fires, the owned process TREE is TERMed, given
                   :term-grace-ms, KILLed, and /proc-confirmed empty
                   before this returns.
    :env           REQUIRED map of string to string — the COMPLETE child
                   environment (envp replaces; nothing is inherited from
                   the controller). Required on purpose: a future caller
                   cannot silently inherit the controller's secrets by
                   forgetting the key. Hand scrubbed-allowlist-env output
                   here unless you mean something narrower.
    :dir           optional child cwd (absolute; relative resolves against
                   the controller's project dir).
    :term-grace-ms optional TERM-to-KILL grace (primitive default 200).
    :out-bytes     optional positive integer byte cap on stdout capture.
    :err-bytes     optional positive integer byte cap on stderr capture —
                   independent of :out-bytes; neither stream's flood can
                   stall the other's drain or the wait.

  Returns the primitive's map {:pid :exit :timed-out}, plus per REQUESTED
  stream {:out :out-status} / {:err :err-status} where the status is
  :complete (EOF seen — the string is the entire stream), :truncated (the
  cap was reached — the run was ENDED through the same kill ladder, with
  :timed-out still false), or :partial (a prefix; a writer escaped the
  scope's nets). Throws IllegalArgumentException on a malformed request,
  IOException when the program cannot run at all, and
  UnsupportedOperationException off Linux — callers that must not throw
  catch."
  [{:keys [cmd dir env timeout-ms term-grace-ms out-bytes err-bytes]}]
  (let [cmd (vec cmd)]
    (when (or (empty? cmd) (not (every? #(and (string? %) (seq %)) cmd)))
      (throw (IllegalArgumentException.
               "scope-run: :cmd must be a non-empty argv vector of strings")))
    (when (or (nil? env) (not (map? env)) (some bad-env-entry? env))
      (throw (IllegalArgumentException.
               (str "scope-run: :env must be an explicit map of non-empty string"
                    " names (no =, no NUL) to NUL-free string values"))))
    (when (or (not (int? timeout-ms)) (<= timeout-ms 0))
      (throw (IllegalArgumentException.
               "scope-run: :timeout-ms must be a positive integer")))
    (doseq [[who cap] [[:out-bytes out-bytes] [:err-bytes err-bytes]]]
      (when (and (some? cap) (or (not (int? cap)) (<= cap 0)))
        (throw (IllegalArgumentException.
                 (str "scope-run: " who " must be a positive integer byte cap")))))
    (when (and (some? dir) (or (str/blank? (str dir)) (str/includes? (str dir) "\0")))
      (throw (IllegalArgumentException. "scope-run: :dir must be a non-empty path")))
    (*scope-run*
      (cond-> {:cmd cmd :timeout-ms timeout-ms :env env}
        dir           (assoc :dir (str dir))
        term-grace-ms (assoc :term-grace-ms term-grace-ms)
        out-bytes     (assoc :out-bytes out-bytes)
        err-bytes     (assoc :err-bytes err-bytes)))))

(defn scope-supported?
  "Whether the trusted scoped process primitive exists on THIS runtime —
  the capability the verify/gitdiff boundary depends on to fail closed.

  Probed by capability, never by platform string-matching: the primitive
  refuses an unsupported runtime (non-Linux, or a runtime without the
  posix_spawn/poll facilities) BEFORE it validates or spawns anything,
  so an intentionally incomplete request — a lone :cmd with no
  :timeout-ms — separates the two answers cleanly:

    UnsupportedOperationException  the scope facility itself is absent;
    anything else (a request-shape complaint, or success)  it is present
    and enforcing.

  Neither arm spawns a process: the absent case throws before any
  request checking, the present case rejects the malformed request
  before any spawn. A test fake bound to *scope-run* answers 'supported'
  the same way (it returns or throws something other than
  UnsupportedOperationException), so the probe composes with stubs.

  The contract for callers: `false` MUST mean 'refuse to verify', never
  'verify without the scope'. samizdat.agent.verify and the ship gate
  turn it into a blocked ship with an explicit unsupported result; they
  never fall back to a permissive path. Never throws."
  []
  (try (*scope-run* {:cmd ["samizdat-scope-capability-probe"]})
       true
       (catch UnsupportedOperationException _ false)
       (catch Throwable _ true)))
