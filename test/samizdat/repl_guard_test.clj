;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.repl-guard-test
  "Eval may not kill the server.

  Run a3ba69bb: the serve process exited 0 mid-run and nothing in abort, beam
  teardown or process disposal calls exit. Reproduced directly — an eval of
  `(System/exit 0)` ends the process, and the line after it never runs
  (karamazov-1xx)."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.repl :as repl]
            [samizdat.repl.guard :as guard]))

(defn- forms [s] (read-string (str "[" s "\n]")))

(deftest the-routes-out-of-the-process-are-refused
  (testing "the observed one"
    (is (guard/terminating-form? (forms "(System/exit 0)"))))
  (testing "and both interop routes to the same thing, which read as ordinary
            method calls — only the receiver gives them away"
    (is (guard/terminating-form? (forms "(.exit (Runtime/getRuntime) 1)")))
    (is (guard/terminating-form? (forms "(.halt (Runtime/getRuntime) 0)"))))
  (testing "nesting does not hide it: the walk is over the read form as data"
    (is (guard/terminating-form? (forms "(do (println :a) (when true (System/exit 2)))")))
    (is (guard/terminating-form? (forms "(defn boom [] (System/exit 0))"))))
  (testing "a later form is refused even though an earlier one is fine — the
            forms share a process, so form 1 having run does not make form 2's
            exit survivable"
    (is (guard/terminating-form? (forms "(+ 1 2)\n(System/exit 0)")))))

(deftest ordinary-evals-are-untouched
  ;; The guard costs the agent nothing on the work it actually does. System is
  ;; a common namespace and only the exit member of it is a terminator.
  (is (not (guard/terminating-form? (forms "(+ 1 2)"))))
  (is (not (guard/terminating-form? (forms "(println (System/currentTimeMillis))"))))
  (is (not (guard/terminating-form? (forms "(System/getenv \"HOME\")"))))
  (is (not (guard/terminating-form? (forms "(require '[clojure.string :as str])")))))

(deftest the-refusal-names-the-call-and-the-alternative
  (let [r (repl/eval-code "(System/exit 0)")]
    (is (false? (:ok r)) "refused, not run")
    (is (re-find #"System/exit" (str (:error r)))
        "names the call, so the model knows which of its forms was the problem")
    (is (re-find #"(?i)abort" (str (:error r)))
        "and names what to do instead — a refusal with no alternative is one the
         model works around")))

(deftest the-process-survives-the-eval-that-used-to-end-it
  ;; The whole point. If this regresses, the test run itself dies here rather
  ;; than reporting a failure — which is exactly what the bug looks like.
  (repl/eval-code "(System/exit 0)")
  (is true "still running after evaluating an exit"))

(deftest an-exit-with-work-in-flight-reads-differently-from-a-clean-one
  ;; The forensic layer. It cannot prevent anything and does not try; what it
  ;; does is make the 0 stop looking like somebody stopping the server.
  (is (:bug? (guard/exit-note ["run-abc" "run-def"]))
      "work in flight at exit is the bug, and the note says so as data")
  (is (= ["run-abc"] (:active (guard/exit-note ["run-abc"])))
      "names the runs, because that is what makes the record actionable")
  (is (= 2 (:count (guard/exit-note ["run-abc" "run-def"]))))
  (is (not (:bug? (guard/exit-note [])))
      "an idle exit is somebody stopping the server, not this bug")
  (testing "the WORDS live at the log call, not here — a note assembled one
            function away from its logging is prose the ratchet cannot tell
            from a sentence aimed at the model"
    (is (not (string? (guard/exit-note ["run-abc"]))))))
