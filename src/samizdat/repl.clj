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

(ns samizdat.repl
  "The in-process eval seam: the agent develops REPL-first against the LIVE
  harness image.

  This is the homoiconic substrate the plan turns on. Because the agent runs
  inside a jolt image, `eval-code` evaluates into that same image — it can
  define functions, call them, require and exercise samizdat's own namespaces,
  and inspect the running system, exactly as a developer at a REPL does. A
  value, its printed output, and any exception all come back as data; nothing
  thrown escapes.

  Each session is its own namespace, so defs accumulate across calls within a
  run (define, then use) while staying isolated from other sessions. This is
  arbitrary code execution in the harness process, by design: it is the
  mechanism the mutation protocol (karamazov-ioo.11) will build its
  checkpoint/soak/rollback safety around."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jolt.host :as host]))

(defn- project-paths
  "The source paths `root` declares in its own deps.edn: `:paths` plus every
  alias's `:extra-paths`. Falls back to src and test, which is what a Clojure
  project without a deps.edn (or with one this cannot read) almost always
  uses — a guess that is wrong costs a failed require, and no guess at all
  costs the whole REPL-first workflow."
  [root]
  (let [f (io/file root "deps.edn")
        declared (when (.exists f)
                   (try (let [d (edn/read-string (slurp f))]
                          (concat (:paths d)
                                  (mapcat :extra-paths (vals (:aliases d)))))
                        (catch Throwable _ nil)))]
    (distinct (or (seq declared) ["src" "test"]))))

(defn ensure-project-roots!
  "Make the project at `root` loadable from `eval`, and return the paths added.

  THE SYSTEM PROMPT PROMISES THIS AND THE HARNESS DID NOT DELIVER IT. The
  prompt's whole first section is REPL-first — *try a form, inspect what it
  returns, iterate BEFORE writing it to a file; require and exercise the
  project's own namespaces here too* — and `eval` runs in the harness image,
  whose source roots are samizdat's. A run targeting another project could
  write `src/todo/core.clj` and then not require it: observed live, turn 12,
  `Could not locate todo/core.jolt (or .clj/.cljc) on the source roots`. Every
  word of that instruction was unreachable for the case the harness exists to
  serve.

  ADDITIVE, never a replacement: samizdat's own roots stay, because the agent
  introspecting the harness it runs in is the other half of the job. Idempotent
  — a second run against the same root changes nothing.

  A stale root from a previous run is left in place deliberately. Removing it
  would unload nothing (namespaces are already interned) while breaking a
  resume that still refers to it, and an extra directory on the search path
  costs a stat."
  [root]
  (when root
    (let [added (mapv #(str (io/file root %)) (project-paths root))
          current (vec (host/source-roots))
          missing (remove (set current) added)]
      (when (seq missing)
        (host/set-source-roots! (into current missing))
        (log/info "eval can now reach" (str/join ", " missing)))
      (vec missing))))

(def ^:private session-counter (atom 0))

(defn new-session
  "A fresh eval session: a unique namespace that `clojure.core` is referred
  into, so ordinary forms work and defs persist across calls to it."
  []
  (let [ns-sym (symbol (str "samizdat.repl.session-" (swap! session-counter inc)))
        ns* (create-ns ns-sym)]
    (binding [*ns* ns*]
      (refer-clojure))
    ns-sym))

(defn fork-session
  "A new session carrying everything `parent` has defined.

  A forked branch inherits its parent's CONVERSATION (state/fork-branch), so
  it inherits a transcript in which those defs were made — and a child that
  can read `(defn helper …)` in its own history but cannot call it is being
  shown a lie about its own state. Copying the parent's interned vars is what
  makes the inherited transcript true.

  Copied, not shared: the two branches are competing approaches and a def one
  makes after the fork must not appear in the other. `refer-clojure` gives the
  child core; only the parent's OWN interns come across, which is why this
  walks `ns-interns` rather than `ns-map`.

  A missing or already-dropped parent yields a plain new session, because a
  fork must never fail on the state of the thing it is forking from."
  [parent]
  (let [child (new-session)]
    (when-let [pns (and parent (find-ns parent))]
      (let [cns (find-ns child)]
        (doseq [[sym v] (ns-interns pns)]
          (when (var? v)
            (intern cns sym @v)))))
    child))

(defn close-session
  "Drop a session's namespace. Each run gets a fresh namespace so defs
  accumulate across its turns; without this a long-lived serve process kept
  one namespace (plus everything the agent defined in it) per run, forever
  (provenance CR1-6). Idempotent on an unknown or already-removed name."
  [session]
  (when (find-ns session)
    (remove-ns session))
  nil)

(def ^:private default-session (delay (new-session)))

(def default-eval-timeout-ms
  "Wall-clock bound on one `eval`, unless the caller asks for more. The agent
  runs code in the same image the harness runs in, on a thread the harness waits
  on — an infinite loop or a runaway computation (a live one pinned a core with
  no bound) would otherwise hang the whole harness. The agent can pass a larger
  timeout when a call genuinely needs it."
  10000)

(defn eval-code
  "Evaluate `code` (a string of one or more Clojure forms) in the session's
  namespace, in the live harness image. Returns:
    {:ok true  :value \"<pr-str of the last form's value>\" :out \"<stdout>\"}
    {:ok false :error \"<message>\" :out \"<stdout>\" :error-type \"<class>\"}
  Reads and evaluates form by form so a leading `(require …)` takes effect
  before the forms that depend on it, matching REPL semantics.

  Bounded by `timeout-ms` (default `default-eval-timeout-ms`): the code runs on
  a separate thread the caller waits on with a deadline, so a runaway eval times
  out with :error-type \"timeout\" instead of hanging the harness. The abandoned
  computation is best-effort cancelled; a tight CPU loop may not honour it, but
  control returns to the harness regardless."
  ([code] (eval-code code @default-session nil))
  ([code session] (eval-code code session nil))
  ([code session timeout-ms]
   (let [ns* (the-ns (or session @default-session))
         out (java.io.StringWriter.)
         timeout (or timeout-ms default-eval-timeout-ms)
         ;; Run on its own thread and wait with a deadline. The eval catches its
         ;; own throwable and returns a result map, so deref yields a map or the
         ;; ::timeout sentinel — never a re-thrown exception.
         fut (future
               (try
                 (let [value (binding [*ns* ns* *out* out]
                               (let [forms (read-string (str "[" code "\n]"))]
                                 (reduce (fn [_ form] (eval form)) nil forms)))]
                   {:ok true :value (pr-str value) :out (str out)})
                 (catch Throwable e
                   {:ok false
                    :error (or (ex-message e) (str e))
                    :error-type (str (type e))
                    :out (str out)})))
         result (deref fut timeout ::timeout)]
     (if (= result ::timeout)
       (do (future-cancel fut)
           {:ok false
            :error (str "eval timed out after " timeout "ms — the code ran too long "
                        "(an infinite loop or a heavy computation?). If it genuinely "
                        "needs more time, pass a larger :timeout-ms.")
            :error-type "timeout"
            :out (str out)})
       result))))

(defn- resolve-sym
  "Resolve a fully-qualified or core symbol string to its var, or nil."
  [sym-str]
  (try
    (let [s (symbol sym-str)]
      (if (namespace s)
        (when-let [ns* (find-ns (symbol (namespace s)))]
          (ns-resolve ns* (symbol (name s))))
        (ns-resolve 'clojure.core s)))
    (catch Throwable _ nil)))

(defn doc-sym
  "The docstring and arglists of a symbol, for the agent inspecting code.
  jolt strips core-var metadata, so this is most useful on the project's own
  vars (which carry their docstrings). Returns {:not-found true} when unknown."
  [sym-str]
  (if-let [v (resolve-sym sym-str)]
    (let [m (meta v)]
      {:name (str (:ns m) "/" (:name m))
       :arglists (:arglists m)
       :doc (or (:doc m) "(no docstring — jolt strips core-var metadata)")})
    {:not-found true :symbol sym-str}))

(defn complete
  "Public symbols whose name starts with `prefix`. A qualified prefix
  (`samizdat.lisp/b`) completes within that namespace; a bare prefix
  (`redu`) completes across clojure.core. Returns a sorted vector of strings."
  [prefix]
  (let [p (str prefix)]
    (if-let [slash (str/index-of p "/")]
      (let [ns-part (subs p 0 slash)
            name-part (subs p (inc slash))]
        (if-let [ns* (find-ns (symbol ns-part))]
          (->> (ns-publics ns*)
               keys
               (map name)
               (filter #(str/starts-with? % name-part))
               (map #(str ns-part "/" %))
               sort vec)
          []))
      (->> (ns-publics 'clojure.core)
           keys
           (map name)
           (filter #(str/starts-with? % p))
           sort vec))))
