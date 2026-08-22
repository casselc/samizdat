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
  (:require [clojure.string :as str]))

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

(def ^:private default-session (delay (new-session)))

(defn eval-code
  "Evaluate `code` (a string of one or more Clojure forms) in the session's
  namespace, in the live harness image. Returns:
    {:ok true  :value \"<pr-str of the last form's value>\" :out \"<stdout>\"}
    {:ok false :error \"<message>\" :out \"<stdout>\" :error-type \"<class>\"}
  Reads and evaluates form by form so a leading `(require …)` takes effect
  before the forms that depend on it, matching REPL semantics."
  ([code] (eval-code code @default-session))
  ([code session]
   (let [ns* (the-ns session)
         out (java.io.StringWriter.)]
     (try
       (let [value (binding [*ns* ns* *out* out]
                     ;; Read every form from the string, evaluating each in
                     ;; turn; the last form's value is the result.
                     (let [forms (read-string (str "[" code "\n]"))]
                       (reduce (fn [_ form] (eval form)) nil forms)))]
         {:ok true :value (pr-str value) :out (str out)})
       (catch Throwable e
         {:ok false
          :error (or (ex-message e) (str e))
          :error-type (str (type e))
          :out (str out)})))))

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
