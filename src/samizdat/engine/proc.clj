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
  leaving an orphan holding a pipe."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.process :as p]))

(ffi/defcfn ^:private c-kill "kill" [:int :int] :int)

(def ^:private sigterm-grace-ms
  "How long a process gets to die of SIGTERM before it is sent SIGKILL."
  2000)

(defn- descendant-pids
  "Every live process below `p`, root excluded. jolt's ProcessHandle can
  ENUMERATE the tree, but its destroy never signals (probed: a plain sleep
  survives a direct ProcessHandle.destroy), so the killing here is done by
  pid through kill(2). Must run before the root dies — afterwards the
  children reparent to init and disappear from descendants()."
  [^java.lang.Process p]
  (try
    (mapv (fn [h] (.pid ^java.lang.ProcessHandle h))
          (iterator-seq (.iterator (.descendants (.toHandle p)))))
    (catch Throwable _ [])))

(defn- kill!
  "kill(2) on a pid; signal 0 probes existence. Never throws."
  [pid sig]
  (try (c-kill pid sig) (catch Throwable _ -1)))

(defn- reap!
  "Kill `proc`'s whole tree and do not return until the root is gone.

  SIGTERM the tree first, then SIGKILL whatever is still alive — including
  processes that trapped TERM, which outlived the old root-only escalation as
  orphans (provenance R3-8). p/destroy-tree is deliberately NOT used: on jolt it
  signals through ProcessHandle.destroy, which silently does nothing, so its
  tree-wide TERM never landed at all.

  Same principle the rest of the loop follows: a stop path that depends on
  the component agreeing to stop is not a stop path."
  [proc]
  (let [^java.lang.Process p (:proc proc)
        root (try (.pid p) (catch Throwable _ -1))]
    (doseq [pid (cons root (descendant-pids p))] (kill! pid 15))
    (when-not (try (.waitFor p sigterm-grace-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                   (catch Throwable _ false))
      ;; Still alive. SIGKILL the children BEFORE the root — enumerated now,
      ;; because once the root dies they reparent to init and vanish from
      ;; descendants(). Re-enumerated here, not reused: the tree may have
      ;; spawned between the TERM and the KILL.
      (doseq [pid (descendant-pids p)] (kill! pid 9))
      (kill! root 9)
      (try (.destroyForcibly p) (catch Throwable _ nil))
      (try (.waitFor p sigterm-grace-ms java.util.concurrent.TimeUnit/MILLISECONDS)
           (catch Throwable _ nil)))))

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
  expensive than it is. Fixed upstream too, but this does not depend on that.

  :out-file/:err-file redirect the child's stdout/stderr to files instead of
  capturing them as strings: the verifier's sandbox (security.verification-env)
  uses this so a flooding child writes to an RLIMIT-bounded spool rather than
  into a host-side string of unbounded size — it then reads back only the
  first bounded bytes itself. Everything else about the run (env, dir,
  timeout, tree reaping on timeout) is unchanged."
  [{:keys [input timeout-ms env dir out-file err-file]} & args]
  ;; babashka.process/process takes the command vector FIRST and the options
  ;; map second. Passing them the other way round (which is what `sh` accepts)
  ;; stringifies the vector into an argv[0] of "[z3".
  ;;
  ;; :env, when given, is the COMPLETE environment the child sees — jolt's
  ;; process shim runs it as `env -i K=V …`, so nothing from the parent leaks
  ;; in. The shell tool relies on this: it hands a scrubbed environment here so
  ;; a subprocess cannot read a secret the parent holds (samizdat.security).
  ;;
  ;; :dir, when given, is the child's working directory — the structured-argv
  ;; alternative to a `cd … &&` shell prefix, so a caller that must not compose
  ;; a shell (the bounded verify path) can still pin the cwd.
  (let [proc (p/process (vec args)
                         (cond-> {:in (or input "")}
                           (nil? out-file) (assoc :out :string)
                           out-file (assoc :out :write :out-file (str out-file))
                           (nil? err-file) (assoc :err :string)
                           err-file (assoc :err :write :err-file (str err-file))
                           env (assoc :env env)
                           dir (assoc :dir dir)))
        ^java.lang.Process p (:proc proc)
        ms (or timeout-ms 30000)
        finished? (try
                    (.waitFor p ms java.util.concurrent.TimeUnit/MILLISECONDS)
                    (catch Throwable _ false))]
    (if-not finished?
      (do (reap! proc) {:timeout true :ms ms})
      ;; Exited, so this deref returns immediately and only collects the
      ;; already-complete stdout/stderr. A redirected stream has no string
      ;; to collect — the caller reads its own file.
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

;; --- bounded stream capture ---------------------------------------------------
;;
;; Ported from bbagent's proven process facility (bbagent.process, A3a): a
;; caller that runs a supervisor-style child (a VM manager's front end) needs
;; three properties `run` above cannot give — an output stream kept to a byte
;; BUDGET while its true size is still counted (a chatty child must not become
;; an unbounded host string, but abandoning the pipe early turns a flood into
;; a hang), a status that never invents an exit code for a process that was
;; killed at its deadline, and a process tree that is actually dead when the
;; call returns. Reaped through the same kill(2) descendant-first path `run`
;; uses on timeout.

(def default-stream-max-bytes
  "How much of one stream is kept before the rest is counted and dropped.
  Exceeding it is not an error — a build that prints a megabyte of warnings
  should still report its exit code — but it is recorded, because a truncated
  stream that does not say so is a lie about what ran."
  1048576)

(def max-timeout-ms
  "The longest deadline a caller may ask for. A deadline is the only thing
  standing between a wedged child and a host process that waits forever."
  3600000)

(defn- drain
  "Reads a stream to exhaustion, keeping at most max-bytes of it.

  Reading continues past the budget rather than stopping at it: a child whose
  output is not consumed blocks on a full pipe, so abandoning the stream
  early would turn a chatty command into a hang."
  [^java.io.InputStream stream max-bytes]
  (let [buffer (byte-array 8192)
        captured (java.io.ByteArrayOutputStream.)]
    (loop [total 0]
      (let [read (.read stream buffer)]
        (if (neg? read)
          {:text (String. (.toByteArray captured) "UTF-8")
           :bytes total
           :truncated? (> total max-bytes)}
          (do
            (when (< (.size captured) max-bytes)
              (.write captured buffer 0
                      (min read (- max-bytes (.size captured)))))
            (recur (+ total read))))))))

(defn- valid-argv?
  [argv]
  (and (sequential? argv)
       (seq argv)
       (every? #(and (string? %) (not (str/blank? %))) argv)))

(defn run-bounded
  "Runs argv to completion, to its deadline, or to a failure to start, with
  each output stream kept to its byte budget while its TRUE byte count is
  still reported.

  Returns {:status :exited | :timeout | :start-failure}. :exit is present only
  when the process actually exited — a deadline is not a program that chose a
  status, and no code is invented for one that did not exit. Each stream is
  {:text :bytes :truncated?} where :bytes is what the child WROTE, not what
  was kept. On timeout the whole process tree is SIGTERM/SIGKILL-reaped
  (descendants first) before the call returns. `:env`, when given, is the
  COMPLETE child environment (env -i semantics, like `run`); when absent the
  child inherits the parent's — for trusted controller spawns that need the
  ambient environment (git configuration, a VM manager's own data dirs) and
  hand the child a CONSTRUCTED environment some other way."
  [{:keys [timeout-ms dir env out-max-bytes err-max-bytes]
    :or {out-max-bytes default-stream-max-bytes
         err-max-bytes default-stream-max-bytes}}
   & args]
  (when-not (valid-argv? args)
    (throw (ex-info "Process argv must be a non-empty vector of non-blank strings"
                    {:samizdat.proc/error :invalid-argv :argv (vec args)})))
  (when-not (and (integer? timeout-ms) (pos? timeout-ms)
                 (<= timeout-ms max-timeout-ms))
    (throw (ex-info "Process timeout must be a positive number of milliseconds within the bound"
                    {:samizdat.proc/error :invalid-timeout
                     :timeout/requested timeout-ms
                     :timeout/max max-timeout-ms})))
  (let [started (System/nanoTime)
        elapsed #(long (quot (- (System/nanoTime) started) 1000000))]
    (try
      (let [proc (p/process (vec args)
                            (cond-> {:in ""}
                              dir (assoc :dir (str dir))
                              env (assoc :env env)))
            ^java.lang.Process p (:proc proc)
            stdout (future (drain (.getInputStream p) out-max-bytes))
            stderr (future (drain (.getErrorStream p) err-max-bytes))
            exited? (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
        (when-not exited?
          (reap! proc)
          (try (.waitFor p 5000 java.util.concurrent.TimeUnit/MILLISECONDS)
               (catch Throwable _ nil)))
        ;; The drains end when the pipes close — which reaping causes. They
        ;; are still dereferenced with a bound: a grandchild holding the pipe
        ;; open must not become this process's problem.
        (let [empty {:text "" :bytes 0 :truncated? false}
              out (deref stdout 5000 empty)
              err (deref stderr 5000 empty)]
          (cond-> {:status (if exited? :exited :timeout)
                   :duration-ms (elapsed)
                   :stdout (:text out)
                   :stdout/bytes (:bytes out)
                   :stdout/truncated? (:truncated? out)
                   :stderr (:text err)
                   :stderr/bytes (:bytes err)
                   :stderr/truncated? (:truncated? err)}
            exited? (assoc :exit (.exitValue p)))))
      (catch java.io.IOException failure
        {:status :start-failure
         :duration-ms (elapsed)
         :error/message (.getMessage failure)}))))
