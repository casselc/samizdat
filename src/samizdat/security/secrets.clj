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

(ns samizdat.security.secrets
  "Secrets never enter model space. Ported from dirge src/sandbox/mod.rs.

  Three jobs. `scrub-env` builds the environment a subprocess is allowed to
  see — name-sensitive vars removed, value-shaped credentials replaced — so
  the child inherits no secret it was not explicitly handed. `resolve-refs`
  lets the model reference a secret symbolically (`{{env/NAME}}`) without ever
  seeing its value: the kernel resolves it at spawn time. `redact` is the
  boundary every model-bound string crosses — subprocess output, refusal text,
  a journal row — catching vendor-prefix tokens, URL passwords, and any value
  the run is known to hold.

  The security-model diagram (docs/security.md) is the specification: env and
  secrets reach the model, messages, or journal ONLY through redact, verified
  by chiasmus. These functions are the redact node and the scrub node."
  (:require [clojure.string :as str]))

;; --- sensitive names --------------------------------------------------------

(def ^:private name-patterns
  ["KEY" "SECRET" "TOKEN" "PASSWORD" "PASS" "CRED" "AUTH"])

(def ^:private safe-exact
  "Names that match a pattern by accident but must reach the tools that need
  them — X11, locale, the user's editor, and the git/gh credentials."
  #{"DISPLAY" "TERM" "SHLVL" "PWD" "OLDPWD" "PATH" "MANPATH" "LANG" "LC_ALL"
    "LC_CTYPE" "EDITOR" "VISUAL" "PAGER" "HOSTNAME" "USER" "LOGNAME" "HOME"
    "SSH_AUTH_SOCK" "GITHUB_TOKEN" "GH_TOKEN"})

(def ^:private explicit-names
  "Cloud-credential names with no generic pattern of their own."
  #{"AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_SESSION_TOKEN"
    "GITLAB_TOKEN" "BITBUCKET_TOKEN"})

(defn sensitive-name?
  "Whether an env var name is credential-shaped. Pattern-based so a novel
  provider (a future MISTRAL_API_KEY) is caught with no code change; the
  SAFE_EXACT set is subtracted so tooling vars still reach the child."
  [name]
  (let [upper (str/upper-case (str name))]
    (cond
      (contains? safe-exact upper) false
      (some #(str/includes? upper %) name-patterns) true
      :else (contains? explicit-names upper))))

;; --- sensitive values -------------------------------------------------------

;; Conservative on purpose: only signatures with low false-positive rates.
;; Long opaque base64 alone does NOT trip this — plenty of harmless vars carry
;; long tokens (nix hashes, etc.).
;;
;; jolt port note: irregex is a backtracking engine, where Rust's regex crate
;; (dirge's) is a linear-time DFA. dirge's open-ended `{n,}` counted
;; quantifiers and nested `(?:…+)*` groups are safe there and a ReDoS hang
;; here — `github_pat_[A-Za-z0-9]{22,}_[A-Za-z0-9]{59,}` hangs outright on a
;; long input. So: a cheap substring gate short-circuits before any regex runs
;; (matching dirge's has_vendor_prefix_gate), and every quantifier carries an
;; explicit upper bound. A credential longer than the bound is not a real one,
;; so bounding changes no real-world match while removing the backtracking.
(def ^:private url-userinfo-re
  #"(?i)([a-z][a-z0-9+.-]*://[^:@/]+:)([^@\s]{1,512})(@)")

(def ^:private vendor-gates
  ["sk-" "AKIA" "ghp_" "github_pat_" "hf_" "xox" "xai-" "AIza"])

(def ^:private vendor-prefix-re
  #"(?x)\b(?:sk-(?:live_|test_|proj-)?[a-zA-Z0-9+/=]{20,512}(?:-[a-zA-Z0-9+/=]{1,64}){0,16}|sk-ant-api[0-9]{2}-[A-Za-z0-9+/=]{90,512}[_-][A-Za-z0-9]{5}|AKIA[A-Z0-9]{16}|ghp_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9]{22,64}_[A-Za-z0-9]{59,255}|hf_[A-Za-z0-9]{34,255}|xox[bpras]-[0-9]{2,20}-[0-9]{2,20}-[0-9]{2,20}-[a-zA-Z0-9]{32,255}|xai-[A-Za-z0-9+/=]{20,512}(?:\.[A-Za-z0-9+/=]{1,128}){0,16}|AIza[0-9A-Za-z_-]{35})")

(defn- has-vendor-gate? [s]
  (boolean (some #(str/includes? s %) vendor-gates)))

(defn sensitive-value?
  "Whether a value carries a high-confidence credential shape, regardless of
  the name it sits under — a DATABASE_URL with an embedded password, or an
  ALIAS holding an sk- token. The substring gates run first so the regex only
  sees inputs that could plausibly match."
  [value]
  (let [v (str value)]
    (boolean (and (seq v)
                  (or (and (str/includes? v "://") (re-find url-userinfo-re v))
                      (and (has-vendor-gate? v) (re-find vendor-prefix-re v)))))))

;; --- redaction --------------------------------------------------------------

(def ^:private redacted "[REDACTED]")

(defn- known-value-seq
  "Known values as a canonical, deduplicated seq of non-blank strings,
  longest first (lexicographic tiebreak) — so what gets replaced never
  depends on the caller's collection type or iteration order, and a value
  that contains another cannot consume its prefix first and leave residue.

  jolt port note: this exists because `distinct` is not safe on a set here.
  jolt's `distinct` destructures its argument positionally, and positional
  access on a raw SET answers nil — so (distinct #{v}) evaluated to (nil):
  the one secret a run holds was erased from the redaction set, and the
  injected nil was blank-dropped in turn, so nothing crashed and the value
  leaked. Vectors destructure positionally, which is why vector inputs
  tested clean while real callers — every one hands `redact` a SET
  (known-values, stripped-values) — leaked. Normalize through str +
  blank? + a set + an explicit sort instead of `distinct`."
  [known-values]
  (->> known-values
       (map str)
       (remove str/blank?)
       (into #{})
       (sort-by (fn [v] [(- (count v)) v]))))

(defn redact
  "Redact known secrets from an arbitrary model-bound string. URL passwords
  and vendor-prefix tokens by regex; any value in `known-values` by substring
  (for opaque secrets with no recognizable shape). `known-values` may be nil
  or any seqable of strings — vector, list, set — and is normalized to a
  canonical longest-first order, so the result is deterministic regardless of
  the caller's collection type. Returns the string unchanged when nothing
  matched."
  ([text] (redact text nil))
  ([text known-values]
   (let [t (str text)
         ;; Same gating as sensitive-value? — skip the regex entirely when the
         ;; substring can't be present, which keeps redaction of ordinary tool
         ;; output cheap and dodges the irregex backtracking on long inputs.
         t (if (str/includes? t "://")
             (str/replace t url-userinfo-re (str "$1" redacted "$3"))
             t)
         t (if (has-vendor-gate? t)
             (str/replace t vendor-prefix-re redacted)
             t)]
     (reduce (fn [s v]
               (if (str/includes? s v)
                 (str/replace s v redacted)
                 s))
             t
             (known-value-seq known-values)))))

;; --- scrubbing the child environment ----------------------------------------

(defn stripped-values
  "The values of every name-sensitive var in `env` — the secrets a subprocess
  must not be able to re-leak under a different name."
  [env]
  (->> env
       (keep (fn [[k v]] (when (sensitive-name? k) v)))
       (remove str/blank?)
       set))

(defn scrub-env
  "The environment a subprocess is allowed to see. Name-sensitive vars are
  removed; any remaining var whose value is credential-shaped OR contains a
  known stripped value is replaced with [REDACTED]. Pure over the env map so
  it is testable without a spawn."
  [env]
  (let [known (stripped-values env)]
    (into {}
          (keep (fn [[k v]]
                  (cond
                    (sensitive-name? k) nil
                    (or (sensitive-value? v)
                        (some #(str/includes? (str v) %) known))
                    [k redacted]
                    :else [k v])))
          env)))

(defn scrubbed-process-env
  "The current process environment, scrubbed — what a spawned tool inherits.
  Read here, at the kernel, never handed to the model."
  []
  (scrub-env (into {} (System/getenv))))

;; --- symbolic references ----------------------------------------------------

(def ^:private ref-re #"\{\{env/([A-Za-z_][A-Za-z0-9_]*)\}\}")

(defn resolve-refs
  "Replace every `{{env/NAME}}` in `text` with the value of NAME in `env`
  (empty string when absent). This runs at spawn time, in the kernel — the
  model authored the reference and never saw the value."
  [text env]
  (str/replace (str text) ref-re
               (fn [[_ name]] (str (get env name "")))))

(defn refs-used
  "The set of secret values a `resolve-refs` on `text` would expose — the
  values to add to the redaction known-set for this call, so even a
  subprocess that echoes one cannot get it past the boundary."
  [text env]
  (->> (re-seq ref-re (str text))
       (keep (fn [[_ name]] (get env name)))
       (remove str/blank?)
       set))

(defn known-values
  "Every secret value this run is known to hold, for the redaction boundary:
  the values of name-sensitive env vars, plus any resolved by a symbolic
  reference in `command`. The config api-key is a name-sensitive env var, so
  it is already covered by the first set."
  ([env] (known-values env nil))
  ([env command]
   (into (stripped-values env)
         (when command (refs-used command env)))))
