;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.verify
  "The ship gate's test rung — what makes the worker loop TEST-DRIVEN instead of
  one-shot. A `done` is not terminal until the unit's tests actually pass: the
  gate runs the verify command, and a red run (or a hollow / untested change) is
  fed back so the branch keeps iterating (edit -> run -> observe -> fix) rather
  than shipping unverified work.

  Verification is FOCUSED by default: it runs only the test namespaces the branch
  actually touched, so the loop iterates in seconds against its own new test
  rather than paying for the whole suite each time. A configured :verify-cmd is
  the fallback when nothing focusable changed.

  The runner is a thin effect; the DECISION (`verify-block`) and the command
  derivation (`focused-cmd`) are pure, so the gate is testable without spawning a
  process. Same split as planner.clj vs cells/team.clj."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.engine.proc :as proc]
            [samizdat.security.secrets :as secrets]
            [samizdat.util :as util]))

(defn- conventions
  "The focused-verify conventions, from gates.edn :focused-verify (drg-4026
  #47/48) — read at fire time so a project retunes them at runtime."
  []
  (gates/threshold :focused-verify))


(defn test-file?
  "Whether a changed path is a test/spec file — the evidence that a change was
  pinned by a test, which the TDD ship gate requires. The path shape is
  project data (:test-file-regex)."
  [path]
  (boolean (re-find (re-pattern (:test-file-regex (conventions))) (str path))))

(defn ns-from-test-path
  "The Clojure namespace a test file defines: strip the leading source root
  (test/ or gui/), drop the extension, '/'->'.', '_'->'-'. Returns nil for a
  non-Clojure path.
  e.g. \"test/samizdat/agent/decompose_test.clj\" -> \"samizdat.agent.decompose-test\".

  The derived namespace is embedded in a shell command (focused-cmd), and the
  path is model-controlled — a file name crafted to close the sh -c quoting
  (`test/foo'; CMD; echo '.clj`) must yield NO namespace, so the result is
  whitelisted to plain namespace characters (:ns-whitelist-regex, project
  data) and anything else is dropped."
  [path]
  (let [p (str path)
        c (conventions)]
    (when (re-find (re-pattern (:ext-strip-regex c)) p)
      (let [ns (-> p
                   (str/replace (re-pattern (:root-strip-regex c)) "")
                   (str/replace (re-pattern (:ext-strip-regex c)) "")
                   (str/replace "_" "-")
                   (str/replace "/" ".")
                   not-empty)]
        (when (and ns (re-matches (re-pattern (:ns-whitelist-regex c)) ns))
          ns)))))

(defn focused-cmd
  "A test command that runs ONLY the test namespaces among `changed`, with an
  exit code that reflects pass/fail (jolt's bare -e does not exit non-zero on a
  failed assertion, so the expression sets the code itself). nil when no test
  namespace changed — the caller then falls back to the configured :verify-cmd.
  The command shape is project data (:cmd-prefix)."
  [changed]
  (let [nses (->> changed (filter test-file?) (keep ns-from-test-path) distinct vec)]
    (when (seq nses)
      (let [quoted (str/join " " (map #(str "(quote " % ")") nses))
            expr (str "(require (quote clojure.test) " quoted ")"
                      "(let [s (clojure.test/run-tests " quoted ")]"
                      "(clojure.core/println s)"
                      "(clojure.core/flush)"
                      "(java.lang.System/exit (if (clojure.core/pos? (+ (:fail s) (:error s))) 1 0)))")]
        ;; single-quote the whole -e expression for sh -c; every namespace in
        ;; it came through ns-from-test-path's whitelist, so the expression
        ;; genuinely has no single quotes of its own (review3 #1).
        (str (:cmd-prefix (conventions)) expr "'")))))

(defn- tail
  "The last n non-blank lines of s — enough of a failure to act on without
  dragging the whole test log into the branch's context."
  [s n]
  (->> (str/split-lines (str s)) (remove str/blank?) (take-last n) (str/join "\n")))

(defn verify-block
  "The pure ship decision. Returns nil when `done` may ship, or a block message
  explaining what to fix (which becomes the tool result the branch reads and
  iterates on).

    :verify-on?   whether this loop verifies at all (a :verify-cmd or focused
                  verification is configured). When false the rung is inert.
    :result       {:green? :timeout? :output} from run-verify, or nil when the
                  tests were not run (e.g. git could not tell what changed).
    :changed      changed-files since the attempt baseline: a vector, [] for
                  'genuinely nothing', or nil for 'git cannot tell'.
    :require-test? enforce TDD — a change that includes no test file is refused."
  [{:keys [verify-on? result changed require-test?]}]
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

    (and result (:timeout? result))
    (str "Your test run TIMED OUT. Something you changed likely loops or blocks. "
         "Narrow it down — run a smaller piece at the REPL — then call done again.")

    (and result (not (:green? result)))
    (str "Your tests are not green yet:\n\n" (tail (:output result) 25)
         "\n\nYou are NOT done until they pass. Read the failure, change the code, "
         "re-run the test, and call done again only once it is green.")

    ;; Ran and green.
    (and result (:green? result)) nil

    ;; verify on and the change looked fine, but the tests could not be run
    ;; (git could not tell what changed) — trust rather than deadlock the loop.
    :else nil))

(defn run-verify
  "Run `cmd` in the project root and report whether it is green. Bounded by
  `timeout-ms` (default 10 min). Never throws — a spawn failure reads as
  not-green, which sends the branch back rather than shipping.

  Trust boundary (docs/security.md): the child runs with the SCRUBBED process
  environment — never the parent's, which holds provider keys — and its output
  is model-bound, so it passes the redaction boundary before it is returned."
  [root cmd timeout-ms]
  (try
    (let [r (proc/run {:timeout-ms (or timeout-ms 600000)
                       :env (secrets/scrubbed-process-env)}
                      "sh" "-c" (str "cd " (util/sh-quote root) " && " cmd))
          known (secrets/known-values (into {} (System/getenv)))]
      {:green? (and (not (:timeout r)) (zero? (or (:exit r) 1)))
       :timeout? (boolean (:timeout r))
       :exit (:exit r)
       :output (secrets/redact (str (:out r) "\n" (:err r)) known)})
    (catch Throwable e
      {:green? false :timeout? false
       :output (str "verify command failed to run: " (ex-message e))})))
