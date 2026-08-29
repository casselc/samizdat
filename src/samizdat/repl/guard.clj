;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.repl.guard
  "EVAL MAY NOT KILL THE SERVER.

  `eval-code` runs in the LIVE HARNESS IMAGE — that is the whole point of it,
  and it is why the agent can rewrite itself while running. It also means the
  agent's code and the harness's code share one process, so `(System/exit 0)`
  in an eval is not an error the tool reports: it is the server ending, mid-run,
  with a success status.

  OBSERVED, run a3ba69bb: the serve process exited 0 while a branch was
  in-flight, printing a directory listing of the harness's own root just before
  it went. A parked server never exits on its own, and nothing in abort, beam
  teardown or process disposal calls exit — the trigger was in the eval
  (karamazov-1xx). Reproduced directly: an eval of `(System/exit 0)` ends the
  process, and the line after it never runs.

  IN `src/` AND NOT IN `gates.edn`, DELIBERATELY. This is the same shape as
  `the run config is not writable by the run it gates`: a liveness guard the
  guarded thing can edit is not a guard. The harness staying alive is mechanism.

  TWO LAYERS, BECAUSE NEITHER IS ENOUGH ALONE:

  - `terminating-form?` refuses the call before it runs. It is a STATIC read of
    the form, so it catches what was actually observed — a model reaching for
    exit — and not a determined adversary, who has `resolve` and a hundred other
    routes. Confinement is a different job (karamazov-zrq); this one is about
    the harness surviving its own agent's ordinary mistakes.
  - `record-exit!` cannot prevent anything, and does not try. It makes every
    exit VISIBLE — including the routes the static check misses. An exit that
    gets recorded is a bug someone can fix; the reason a3ba69bb took a whole
    investigation is that a 0 with no message looks exactly like a clean
    shutdown. `core/-main` hangs it off the shutdown hook the host already
    provides, registered before `system/stop!` so the store is still open
    enough to answer what was running."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def terminators
  "Calls that end the process rather than the evaluation.

  `System/exit` is the observed one. `.halt` is worse — it skips shutdown hooks,
  so it would defeat the second layer too. `Runtime/getRuntime` is here because
  the two interop routes to the same thing (`(.exit (Runtime/getRuntime) 0)`)
  read as an ordinary method call and only the receiver gives them away."
  '#{System/exit java.lang.System/exit Runtime/getRuntime java.lang.Runtime/getRuntime
     .halt .exit})

(defn terminating-form?
  "Whether `form` contains a call that would end the process.

  Walks the READ form as data, so it sees through nesting, threading macros and
  quoting alike. `.exit`/`.halt` are matched as bare symbols: a false positive
  costs the agent one refused eval and a message telling it exactly what to do,
  and a false negative costs the run."
  [form]
  (boolean (some #(contains? terminators %)
                 (filter symbol? (tree-seq coll? seq form)))))

(defn offending
  "The terminator symbols in `form`, sorted, for the refusal to name them."
  [form]
  (->> (tree-seq coll? seq form)
       (filter symbol?)
       (filter terminators)
       distinct
       sort
       (map str)))

(defn exit-note
  "What to record when the process ends, as DATA rather than a sentence.

  `active` is whatever the caller knows is still in flight — run ids, branch
  labels — and it is the whole value of the record: a server exiting with
  nothing running is somebody stopping it, and a server exiting with three
  branches mid-turn is this bug. `:bug?` is that distinction, decided here so
  it can be tested without capturing a log.

  Data and not a formatted string so the words live at the `log` call, where
  they are a developer's to read off a console — the prose ratchet strips
  strings under a log form, and a note assembled one function away from its
  logging is prose the ratchet cannot tell from a sentence aimed at the model."
  [active]
  (let [ids (mapv str active)]
    {:bug? (boolean (seq ids)) :active ids :count (count ids)}))

(defn record-exit!
  "Log the exit at the level its shape deserves: a warning when work was in
  flight, because that is the bug, and debug when it was idle. Returns the
  note."
  [active]
  (let [{:keys [bug? active] :as note} (exit-note active)]
    (if bug?
      (log/warn "harness process exiting with work still in flight:"
                (str/join ", " active)
                "- a parked server does not exit on its own (karamazov-1xx)")
      (log/debug "harness process exiting; nothing was in flight"))
    note))
