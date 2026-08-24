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

(ns samizdat.security.secrets-test
  "The secrets layer, ported from dirge src/sandbox/mod.rs. The unit tests pin
  the ported predicates against dirge's own assertions; the specification
  tests at the bottom assert the security-model property directly — a planted
  canary secret appears in NO model-bound payload, journal row, or event."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.security.secrets :as secrets]))

;; --- sensitive names (dirge test_is_sensitive_env_name) ---------------------

(deftest sensitive-names
  (testing "credential-shaped names are sensitive"
    (is (secrets/sensitive-name? "OPENAI_API_KEY"))
    (is (secrets/sensitive-name? "ANTHROPIC_API_KEY"))
    (is (secrets/sensitive-name? "DEEPSEEK_API_KEY"))
    (is (secrets/sensitive-name? "MY_SECRET"))
    (is (secrets/sensitive-name? "DB_PASSWORD"))
    (is (secrets/sensitive-name? "SERVICE_AUTH")))
  (testing "the explicit cloud-credential names with no generic pattern"
    (is (secrets/sensitive-name? "AWS_SESSION_TOKEN"))
    (is (secrets/sensitive-name? "GITLAB_TOKEN"))
    (is (secrets/sensitive-name? "BITBUCKET_TOKEN")))
  (testing "SAFE_EXACT names pass despite matching a pattern — the tools need them"
    (is (not (secrets/sensitive-name? "GITHUB_TOKEN")))
    (is (not (secrets/sensitive-name? "GH_TOKEN")))
    (is (not (secrets/sensitive-name? "SSH_AUTH_SOCK"))))
  (testing "ordinary names pass"
    (is (not (secrets/sensitive-name? "PATH")))
    (is (not (secrets/sensitive-name? "HOME")))
    (is (not (secrets/sensitive-name? "LANG"))))
  (testing "case-insensitive"
    (is (secrets/sensitive-name? "openai_api_key"))
    (is (not (secrets/sensitive-name? "github_token")))))

;; --- sensitive values (dirge is_sensitive_env_value) ------------------------

(deftest sensitive-values
  (testing "vendor-prefix credential shapes — every prefix dirge catches"
    (is (secrets/sensitive-value? "sk-abcdefghijklmnopqrstuvwx"))
    (is (secrets/sensitive-value? "sk-proj-abcdefghijklmnopqrstuvwx"))
    (is (secrets/sensitive-value? "AKIAIOSFODNN7EXAMPLE"))
    (is (secrets/sensitive-value? "ghp_abcdefghijklmnopqrstuvwxyz0123456789"))
    (is (secrets/sensitive-value? "hf_abcdefghijklmnopqrstuvwxyz01234567"))
    (is (secrets/sensitive-value? "xai-abcdefghijklmnopqrstuvwx"))
    (is (secrets/sensitive-value? "AIzaSyDabcdefghijklmnopqrstuvwxyz0123456"))
    ;; Real shapes: github_pat is 22 then 59 alnum; a Slack token is
    ;; xox[bpras]- then three numeric groups then a 32+ alnum hash.
    (is (secrets/sensitive-value?
         (str "github_pat_" (apply str (repeat 22 "A")) "_" (apply str (repeat 59 "B")))))
    (is (secrets/sensitive-value?
         (str "xoxb-12-1234567890-1234567890-" (apply str (repeat 32 "a"))))))
  (testing "URL userinfo carries a credential in a benign-named var"
    (is (secrets/sensitive-value? "postgres://user:hunter2@db.internal/app")))
  (testing "opaque values without a vendor prefix do NOT trip it"
    (is (not (secrets/sensitive-value? "/nix/store/abc123-foo")))
    (is (not (secrets/sensitive-value? "just a sentence")))
    (is (not (secrets/sensitive-value? ""))))
  (testing "adversarial input terminates — irregex backtracks where Rust's DFA
            would not, so every quantifier is bounded and a substring gate runs
            first. Before the fix, github_pat_ x 10000 hung the process."
    ;; If the ReDoS regressed, this test would hang rather than fail — the
    ;; wall-clock is the assertion. A generous count check keeps it honest.
    (is (false? (secrets/sensitive-value? (apply str (repeat 10000 "github_pat_x")))))
    (is (false? (secrets/sensitive-value? (apply str (repeat 5000 "sk-")))))
    (is (= 120000 (count (secrets/redact (apply str (repeat 10000 "github_pat_x"))))))))

;; --- scrub-env --------------------------------------------------------------

(deftest scrub-env-strips-and-redacts
  (let [scrubbed (secrets/scrub-env
                  {"PATH" "/usr/bin"
                   "HOME" "/home/x"
                   "OPENAI_API_KEY" "sk-realkeyrealkeyrealkey12"
                   "GITHUB_TOKEN" "ghp_thisoneisallowedthrough000000000000"
                   "DATABASE_URL" "postgres://u:secretpw@h/db"
                   "ALIAS" "the key is sk-realkeyrealkeyrealkey12 embedded"})]
    (testing "name-sensitive vars are removed entirely"
      (is (not (contains? scrubbed "OPENAI_API_KEY"))))
    (testing "SAFE_EXACT survives so the tools that need it work"
      (is (= "ghp_thisoneisallowedthrough000000000000" (scrubbed "GITHUB_TOKEN"))))
    (testing "benign names pass through untouched"
      (is (= "/usr/bin" (scrubbed "PATH")))
      (is (= "/home/x" (scrubbed "HOME"))))
    (testing "a value-shaped credential in a benign name is redacted, not dropped"
      (is (= "[REDACTED]" (scrubbed "DATABASE_URL"))))
    (testing "a var carrying a KNOWN stripped value is caught even without a prefix"
      (is (= "[REDACTED]" (scrubbed "ALIAS"))))))

;; --- redact -----------------------------------------------------------------

(deftest redact-boundary
  (testing "vendor-prefix tokens by regex"
    (is (= "leaked [REDACTED] here"
           (secrets/redact "leaked sk-abcdefghijklmnopqrstuvwx here"))))
  (testing "URL userinfo password only — scheme and host stay readable"
    (is (= "postgres://u:[REDACTED]@h/db"
           (secrets/redact "postgres://u:hunter2@h/db"))))
  (testing "known opaque values by substring, even with no recognizable shape"
    (is (= "the token is [REDACTED] ok"
           (secrets/redact "the token is OPAQUE-BUILD-abc ok" ["OPAQUE-BUILD-abc"]))))
  (testing "clean text is returned unchanged"
    (is (= "nothing to see" (secrets/redact "nothing to see")))))

;; --- redact known-values (the jolt set/dedup bug) ---------------------------
;;
;; Every real caller hands redact a SET — known-values and stripped-values
;; both answer sets — but only vectors were exercised above. jolt's
;; `distinct` destructures its argument positionally, and positional access
;; on a raw set answers nil, so (distinct #{v}) evaluated to (nil): the
;; run's one secret was erased from the redaction set and leaked. Nothing
;; crashed — the injected nil was blank-dropped in turn — and an opaque
;; value never reaches the vendor regexes, so it sailed through the
;; boundary. redact now normalizes through its known-value-seq
;; (str + blank? + a set + an explicit longest-first sort) and never calls
;; `distinct`. These pin that fix.
(deftest redact-known-values-on-jolt
  (testing "a single-element set is redacted — the exact shape that vanished"
    (is (= "the token is [REDACTED] ok"
           (secrets/redact "the token is OPAQUE-BUILD-abc ok"
                           #{"OPAQUE-BUILD-abc"}))))
  (testing "every element of a multi-element set survives dedup"
    (is (= "a [REDACTED] b [REDACTED] c"
           (secrets/redact "a K1 b K2 c" #{"K1" "K2"}))))
  (testing "the set known-values actually builds — stripped env + resolved refs"
    (let [env {"SECRET_API_KEY" "SYNTH-token-9182736" "PATH" "/usr/bin"}
          known (secrets/known-values env "echo {{env/SECRET_API_KEY}}")]
      (is (set? known))
      (is (= "echo [REDACTED] now"
             (secrets/redact "echo SYNTH-token-9182736 now" known)))))
  (testing "deterministic across collection types: vector, list and set agree"
    (let [text "x SECRET-x y SECRET-xyz z"
          vs ["SECRET-x" "SECRET-xyz" "SECRET-x"]
          out-vec (secrets/redact text vs)
          out-set (secrets/redact text (set vs))
          out-list (secrets/redact text '("SECRET-x" "SECRET-xyz"))]
      (is (= "x [REDACTED] y [REDACTED] z" out-vec))
      (is (= out-vec out-set out-list))))
  (testing "longest-first: a value containing another leaves no residue"
    ;; If the shorter value ran first, SECRET-xyz would become
    ;; [REDACTED]yz — a partial leak of the longer secret.
    (is (not (str/includes?
              (secrets/redact "hold SECRET-xyz tight" #{"SECRET-x" "SECRET-xyz"})
              "yz"))))
  (testing "duplicate entries collapse to one replacement"
    (is (= "a [REDACTED] b"
           (secrets/redact "a S1 b" ["S1" "S1" "S1"]))))
  (testing "blank and nil entries are skipped, adding no stray markers"
    (is (= "a K9 b" (secrets/redact "a K9 b" ["" " " "\t" nil "OTHER"])))
    (is (= "a [REDACTED] b" (secrets/redact "a K9 b" ["" nil " " "K9"])))
    (is (= "a K9 b" (secrets/redact "a K9 b" #{" " nil}))))
  (testing "nil and empty known-values leave clean text untouched"
    (is (= "nothing to see" (secrets/redact "nothing to see" nil)))
    (is (= "nothing to see" (secrets/redact "nothing to see" [])))
    (is (= "nothing to see" (secrets/redact "nothing to see" #{}))))
  (testing "known values are matched literally, never as regex syntax"
    ;; A metacharacter-laden password must redact by substring; if the
    ;; value ever reached a regex path it would not match (or throw).
    (is (= "pw is [REDACTED] ok"
           (secrets/redact "pw is p@ss(w0rd![*+?. $ ok" ["p@ss(w0rd![*+?. $"]))))
  (testing "vendor-pattern redaction still runs alongside known values"
    ;; Both the vendor-shaped token and the opaque known value are caught.
    (is (= "leak [REDACTED] and [REDACTED] too"
           (secrets/redact "leak sk-abcdefghijklmnopqrstuvwx and OPAQUE-TOKEN-1 too"
                           #{"OPAQUE-TOKEN-1"})))))

;; --- symbolic reference resolution ------------------------------------------

(deftest symbolic-refs
  (let [env {"OPENAI_API_KEY" "sk-symbolicvalue000000000" "PATH" "/usr/bin"}]
    (testing "a {{env/NAME}} ref resolves to the value at spawn time"
      (is (= "curl -H sk-symbolicvalue000000000 x"
             (secrets/resolve-refs "curl -H {{env/OPENAI_API_KEY}} x" env))))
    (testing "resolving reports which values it exposed, for the redaction set"
      (is (= #{"sk-symbolicvalue000000000"}
             (secrets/refs-used "curl -H {{env/OPENAI_API_KEY}} x" env))))
    (testing "an unknown ref resolves to empty and exposes nothing"
      (is (= "x  y" (secrets/resolve-refs "x {{env/NOPE}} y" env)))
      (is (empty? (secrets/refs-used "x {{env/NOPE}} y" env))))))

;; --- SPECIFICATION: the canary never crosses the boundary -------------------

(deftest spec-a-planted-canary-never-reaches-model-space
  ;; The security-model property, asserted directly. A secret enters at the
  ;; env, is referenced symbolically by a command, and the command echoes it;
  ;; the value must appear in NO model-bound payload the run produces.
  (let [canary "sk-CANARYcanarycanary00000"
        env {"SECRET_API_KEY" canary "PATH" "/usr/bin"}
        command "echo using {{env/SECRET_API_KEY}} now"
        ;; What actually reaches the subprocess: refs resolved, env scrubbed.
        resolved (secrets/resolve-refs command env)
        child-env (secrets/scrub-env env)
        ;; The subprocess echoes the resolved secret — the worst case.
        raw-output resolved
        ;; Everything model-bound goes through redact with the run's known
        ;; values (config keys + resolved refs + stripped env values).
        known (secrets/known-values env command)
        tool-result (secrets/redact raw-output known)
        journal-row (secrets/redact raw-output known)]
    (testing "the child env carries no name-sensitive var"
      (is (not (contains? child-env "SECRET_API_KEY"))))
    (testing "the canary appears in nothing the model or journal will read"
      (is (not (str/includes? tool-result canary)))
      (is (not (str/includes? journal-row canary)))
      (is (str/includes? tool-result "[REDACTED]")))
    (testing "the model still directed the call — the symbolic ref is intact upstream"
      (is (str/includes? command "{{env/SECRET_API_KEY}}"))
      (is (not (str/includes? command canary))))))

(deftest spec-a-shapeless-canary-never-reaches-model-space
  ;; The spec test above plants a vendor-shaped canary (sk-…), which the
  ;; regex layer catches on its own — so it stayed green through the jolt
  ;; set bug where set-supplied known values were erased by `distinct`.
  ;; This canary has NO recognizable shape at all: only the known-value
  ;; path can stop it, which is exactly the path that leaked.
  (let [canary "correct-horse-battery-staple-42"
        env {"SECRET_DB_PASSWORD" canary "PATH" "/usr/bin"}
        command "echo using {{env/SECRET_DB_PASSWORD}} now"
        resolved (secrets/resolve-refs command env)
        child-env (secrets/scrub-env env)
        known (secrets/known-values env command)
        tool-result (secrets/redact resolved known)
        journal-row (secrets/redact resolved known)]
    (testing "the child env carries no name-sensitive var"
      (is (not (contains? child-env "SECRET_DB_PASSWORD"))))
    (testing "the shapeless canary appears in nothing the model or journal reads"
      (is (not (str/includes? tool-result canary)))
      (is (not (str/includes? journal-row canary)))
      (is (str/includes? tool-result "[REDACTED]")))))
