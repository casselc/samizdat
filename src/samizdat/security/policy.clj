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

(ns samizdat.security.policy
  "The shell command permission engine, ported from dirge src/permission/.

  Every command a tool would run faces a decision first: allow, ask, or deny.
  The rules are ordered and last-match-wins; a complex command (one carrying a
  substitution, subshell, compound operator, or unquoted redirection) can
  never ride an allow, because the rest of what the shell would run is
  invisible to the head an allow matched; a deny is head-anchored through
  env/wrapper prefixes AND evaluated per statement segment, so `nohup rm -rf
  /` and `ls; sudo rm -rf /` both still hit `rm -rf /**`; and an allow matches
  the command RAW so a `PATH=/tmp/evil git status` cannot ride a `git *`
  allow.

  Session grants (human-only, from the grants table) are consulted ahead of the
  base rules, so an approved `ask` becomes an allow for the rest of the run —
  but a hard deny always wins. This is the `perm` node of the security model
  (docs/security.md), and `run-shell` is where it, the env scrub, and the
  redaction boundary meet on the shell tool path."
  (:require [clojure.string :as str]
            [samizdat.engine.proc :as proc]
            [samizdat.security.secrets :as secrets]
            [samizdat.store.grants :as grants]
            [samizdat.util :as util]))

;; --- glob → matcher ---------------------------------------------------------

(defn- glob->regex
  "A shell-style glob to a regex string. `*` and `**` both match any run of
  characters (including `/`), which is dirge's command-glob semantic — unlike
  a path glob, a `*` is not stopped by a slash. Everything else is literal.
  A trailing ` *` makes the args optional (`ls *` matches bare `ls`)."
  [pattern]
  (let [;; A trailing ` *`/` **` (space then stars) becomes an optional args
        ;; group, so the bare command with no args also matches.
        [head optional?] (if-let [m (re-matches #"(.*?)\s+\*+" pattern)]
                           [(nth m 1) true]
                           [pattern false])
        rx (->> head
                (partition-by #(= \* %))
                (map (fn [chs]
                       (if (= \* (first chs))
                         ".*"
                         (java.util.regex.Pattern/quote (apply str chs)))))
                (apply str))]
    (str "^" rx (when optional? "(?:\\s.*)?") "$")))

(defn matches?
  "Whether `input` matches the shell glob `pattern`."
  [pattern input]
  (boolean (re-matches (re-pattern (glob->regex pattern)) input)))

;; --- command classification -------------------------------------------------

(def ^:private complex-markers
  "A command carrying any of these was not decomposed: the shell would run an
  inner command an allow rule never sees. Treated as ask-regardless. (Compound
  operators and redirection are caught separately, by shell-split, because a
  regex cannot tell a quoted `;` from an operator.)"
  [#"\$\(" #"`" #"<\(" #">\(" #"\$\[" #"\(\("])

(defn- shell-split
  "One quote-aware pass over a command string, yielding its shell STRUCTURE:
  the statement segments (split at unquoted `;`, `|`, `&`, and newline) and
  whether an unquoted redirection (`<` or `>`) appears anywhere.

  Quote semantics follow bash: single quotes are literal (nothing inside is
  an operator, not even backslash), double quotes honor backslash escapes, and
  an unquoted backslash escapes the next character. Operators inside quotes
  are string literals — `git commit -m \"a; b\"` is one statement.

  Redirection does not split: it does not start a new command, and a deny glob
  (`.*` spans the rest of the string) already covers the tail of its segment.
  This is the lexer a#1 (docs/code-review.md) asked for — the old regex-only
  classification let `echo pwned; rm -rf ~` ride `echo **` because `.*`
  matches `;` too."
  [raw]
  (let [n (count raw)
        sep? #{\; \| \& \newline}]
    (loop [i 0, state :code, cur [], segs [], redirect? false]
      (if (>= i n)
        {:segments (->> (conj segs (apply str cur))
                        (map str/trim)
                        (remove str/blank?)
                        vec)
         :redirection? redirect?}
        (let [c (nth raw i)]
          (case state
            :code (cond
                    (= c \\) (if (< (inc i) n)
                                (recur (+ i 2) :code (into cur [c (nth raw (inc i))]) segs redirect?)
                                (recur (inc i) :code (conj cur c) segs redirect?))
                    (= c \') (recur (inc i) :single (conj cur c) segs redirect?)
                    (= c \") (recur (inc i) :double (conj cur c) segs redirect?)
                    (sep? c) (recur (inc i) :code [] (conj segs (apply str cur)) redirect?)
                    (or (= c \<) (= c \>)) (recur (inc i) :code (conj cur c) segs true)
                    :else (recur (inc i) :code (conj cur c) segs redirect?))
            :single (if (= c \')
                      (recur (inc i) :code (conj cur c) segs redirect?)
                      (recur (inc i) :single (conj cur c) segs redirect?))
            :double (cond
                      (and (= c \\) (< (inc i) n))
                      (recur (+ i 2) :double (into cur [c (nth raw (inc i))]) segs redirect?)

                      (= c \") (recur (inc i) :code (conj cur c) segs redirect?)
                      :else (recur (inc i) :double (conj cur c) segs redirect?))))))))

(defn- exec-prefix-stripped
  "The command with leading `VAR=val` assignments and exec wrappers
  (env/nohup/nice/…) removed, so a head-anchored deny still sees the real
  command. Deny-side only: widening here can only over-deny."
  [raw]
  (let [wrappers #{"env" "nohup" "nice" "ionice" "setsid" "stdbuf" "time"
                   "timeout" "xargs" "sudo" "doas"}]
    (loop [s (str/trim raw)]
      (let [tok (first (str/split s #"\s+"))
            rest (str/trim (subs s (min (count s) (count tok))))]
        (cond
          ;; a VAR=value assignment prefix — strip and keep going
          (re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" (str tok))
          (if (str/blank? rest) s (recur rest))
          ;; an exec wrapper — strip and keep going, but a bare wrapper with
          ;; nothing after it IS the command (e.g. `env` alone), so stop there
          (and (contains? wrappers tok) (not (str/blank? rest)))
          (recur rest)
          :else s)))))

(defn- command-head
  "The leading executable token of the real command — env/wrapper prefixes
  stripped — for display and rule matching."
  [raw]
  (or (first (str/split (exec-prefix-stripped raw) #"\s+")) ""))

(defn classify
  "A shell command string into {:raw :head :complex?}. A complex command is
  one the shell would expand or compound — substitution, subshell, arithmetic,
  a `;`/`|`/`&`/newline separator, or an unquoted redirection — because in
  every one of those cases an allow rule matched on the head never saw the
  rest of what would run."
  [command]
  (let [raw (str/trim (str command))
        {:keys [segments redirection?]} (shell-split raw)]
    {:raw raw
     :head (command-head raw)
     :complex? (boolean (or (some #(re-find % raw) complex-markers)
                            redirection?
                            (> (count segments) 1)))}))

;; --- the rules --------------------------------------------------------------

(def base-rules
  "The curated allow/ask/deny table, ported from dirge permission/mod.rs
  base_bash_rules. Ordered; last match wins. Interpreters (python/node/npx),
  git push, destructive git, package installs, sudo, and curl/wget are
  deliberately absent — they fall through to the default `ask`. Hard denies
  come last so they win over any allow."
  [;; read-only inspection
   ["ls **" :allow] ["cd **" :allow] ["pwd" :allow] ["echo **" :allow]
   ["which **" :allow] ["type **" :allow] ["cat **" :allow] ["head **" :allow]
   ["tail **" :allow] ["wc **" :allow] ["sort **" :allow] ["uniq **" :allow]
   ["cut **" :allow] ["diff **" :allow] ["grep **" :allow] ["rg **" :allow]
   ["find **" :allow] ["file **" :allow] ["stat **" :allow] ["env" :allow]
   ["date **" :allow] ["whoami" :allow] ["hostname" :allow]
   ;; benign shell builtins
   ["export *" :allow] ["set *" :allow] ["unset *" :allow]
   ["pushd *" :allow] ["popd *" :allow]
   ;; git — local read/write inside the repo (push/reset/checkout/clean omitted)
   ["git status **" :allow] ["git log **" :allow] ["git diff **" :allow]
   ["git show **" :allow] ["git branch **" :allow] ["git add **" :allow]
   ["git commit **" :allow] ["git pull **" :allow] ["git fetch **" :allow]
   ["git remote **" :allow] ["git tag **" :allow] ["git blame **" :allow]
   ["git rev-parse **" :allow] ["git rev-list **" :allow] ["git ls-files **" :allow]
   ;; filesystem mutators
   ["mkdir **" :allow] ["touch **" :allow] ["mv **" :allow] ["cp **" :allow]
   ["ln **" :allow] ["chmod **" :allow]
   ;; project-scoped runners — jolt/clojure toolchain for THIS project, plus
   ;; the common ecosystems dirge trusts. Bare interpreters stay excluded.
   ;; The project's own toolchain — running its tests and evaluating Clojure
   ;; in the project image is the core self-modification workflow, and jolt
   ;; runs THIS project's code (same trust as editing it). The colon-alias
   ;; forms (`-A:test`, `-M:test`, `-A:dev`) need their own patterns: a
   ;; trailing ` **` makes args optional only after a space, so `jolt -A **`
   ;; does not match `jolt -A:test …`. Surfaced by the first dogfood run,
   ;; which blocked on exactly this and needed a manual grant to proceed.
   ["jolt test **" :allow] ["jolt build **" :allow]
   ["jolt -e **" :allow] ["jolt -A **" :allow] ["jolt -M **" :allow]
   ["jolt -A:test **" :allow] ["jolt -M:test **" :allow] ["jolt -A:dev **" :allow]
   ["jolt -A:test -e **" :allow] ["jolt -M:test -e **" :allow]
   ["clj -M **" :allow] ["clojure -M **" :allow] ["lein test **" :allow]
   ["cargo check **" :allow] ["cargo build **" :allow] ["cargo test **" :allow]
   ["cargo fmt **" :allow] ["cargo clippy **" :allow] ["cargo run **" :allow]
   ["pytest **" :allow] ["ruff **" :allow] ["black **" :allow] ["mypy **" :allow]
   ["go build **" :allow] ["go test **" :allow] ["go run **" :allow]
   ["make **" :allow] ["just **" :allow] ["bd **" :allow]
   ;; hard denies — destructive system-level operations, last so they win
   ["rm -rf /**" :deny] ["sudo rm -rf /**" :deny] ["dd **" :deny]
   ["mkfs **" :deny] ["mkfs.* **" :deny] ["fdisk **" :deny] ["mkswap **" :deny]])

(def ^:private default-effect :ask)

(defn- last-match
  "The effect of the last rule whose pattern matches any of `candidates`, or
  nil when none match."
  [rules candidates]
  (reduce (fn [acc [pattern effect]]
            (if (some #(matches? pattern %) candidates)
              effect
              acc))
          nil
          rules))

(defn decide
  "The decision for a shell command: {:effect :allow|:ask|:deny :head :raw}.

  Order, most-authoritative last: a hard deny in the base rules always wins;
  otherwise a session grant (human-only) allows; otherwise the base rules
  (last match); otherwise the default `ask`. A complex command whose only
  support is an allow is downgraded to `ask` — its inner command is invisible.

  `session` is {:grants [pattern ...]} from the grants table (empty is fine)."
  [session command]
  (let [{:keys [raw head complex?]} (classify command)
        ;; Allow matching sees the command RAW — a wrapper prefix changes what
        ;; runs and must not ride an allow. Deny matching sees EVERY statement
        ;; segment (each is a command the shell would run on its own) plus its
        ;; exec-prefix-stripped form, so a denied command hidden after a `;`, a
        ;; newline, or a pipe still denies — widening here can only over-deny.
        allow-candidates [raw]
        deny-candidates (->> (shell-split raw)
                             :segments
                             (cons raw)
                             (mapcat (fn [s] [s (exec-prefix-stripped s)]))
                             distinct
                             vec)
        deny-hit (last-match (filter #(= :deny (second %)) base-rules) deny-candidates)
        grant-hit (when (some #(matches? % raw) (:grants session)) :allow)
        base-hit (last-match base-rules allow-candidates)
        effect (cond
                 deny-hit :deny
                 grant-hit :allow
                 :else (or base-hit default-effect))
        ;; A complex command cannot ride an allow: downgrade allow → ask, but a
        ;; deny still stands.
        effect (if (and complex? (= :allow effect)) :ask effect)]
    {:effect effect :head head :raw raw}))

;; --- the shell tool ---------------------------------------------------------

(def ^:private max-output-chars 8000)

(defn run-shell
  "Run a shell command through the full gate: decide, then (on allow) resolve
  symbolic refs, spawn with a scrubbed environment, and redact the output
  before it returns. Returns a tool-result map.

  `ctx` carries :conn :run-id and :args {:command …}; :env defaults to the
  process environment. This is the one place the perm, scrub, and redact nodes
  of the security model meet."
  [{:keys [conn run-id args] :as ctx}]
  (let [command (str (:command args))
        env (or (:env ctx) (into {} (System/getenv)))
        session (if (and conn run-id) (grants/for-run conn run-id) {:grants []})
        {:keys [effect head]} (decide session command)
        known (secrets/known-values env command)]
    (case effect
      :deny
      {:category :failure :progress? false
       :result (str "Command denied by policy: `" head "` is on the deny list."
                    " This cannot be overridden.")
       :policy {:effect :deny}}

      :ask
      {:category :neutral :progress? false :needs-approval true
       :result (str "Command needs approval: `" command "`.\n"
                    "This is not on the allow list. A human must grant it"
                    " (allow-always for `" head " *`) before it can run.")
       :policy {:effect :ask :suggest (str head " *")}}

      :allow
      (let [resolved (secrets/resolve-refs command env)
            ;; The child sees ONLY the scrubbed environment — name-sensitive
            ;; vars removed, value-shaped credentials redacted — so a
            ;; subprocess cannot read a secret the parent holds even by
            ;; expanding $VAR itself. env -i semantics (see proc/run :env).
            child-env (secrets/scrub-env env)
            r (proc/run {:timeout-ms (or (:timeout-ms ctx) 120000)
                         :env child-env}
                        "bash" "-c" resolved)
            out (if (:timeout r)
                  (str "[timed out after " (:ms r) "ms]")
                  (str (:out r)
                       (when (seq (:err r)) (str "\n" (:err r)))))
            ;; Redact the WHOLE output first, then truncate — so a secret that
            ;; would straddle the truncation boundary is caught before the cut.
            ;; truncate-middle keeps the head AND tail, because the end of a
            ;; command's output (a test summary, an exit line) is as load-
            ;; bearing as the start.
            redacted (util/truncate-middle (secrets/redact out known) max-output-chars)]
        {:category (if (and (not (:timeout r)) (zero? (or (:exit r) 0)))
                     :success :failure)
         :progress? true
         :result redacted
         :policy {:effect :allow}}))))
