;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.closure-coverage
  "The ClosureCoverageSignature — how much of the project the closure verifier
  actually covered, read out of the closure result itself.

  JS1 M4 attempt 2 finished with two numbers that were both green and did not
  match: the host-side ordinary gate ran 1585 tests / 6373 assertions, and the
  isolated closure verifier ran 1585 tests / 6254 assertions. Nothing was
  wrong — the environments differ deliberately (a different toolchain, a
  different filesystem, host-dependent tests that skip inside a machine) — but
  nothing in the evidence could SAY that, because the closure gate recorded
  only `green`. A gate whose strength is unobservable is a gate nobody can
  argue with.

  This namespace is the observability, and deliberately nothing more:

    - It PARSES. The closure verifier's own summary is the source; no count is
      derived, estimated, or carried over from another environment.
    - It COMPARES to a baseline the controller supplies, and exposes the
      delta. It does not require parity with the host suite, and there is no
      assertion-count security theorem here: an assertion count is a fact
      about a suite, not a proof about a codebase.
    - It FAILS CLOSED in exactly three places, all of them cases where the
      closure result has stopped being evidence at all: a summary that cannot
      be parsed, a summary reporting zero tests, and a summary whose own
      failure/error counts contradict the green verdict beside it. A DECREASE
      is a warning, never a refusal — deleting a test is a legitimate change
      and this layer cannot tell a legitimate one from a regression.

  Pure over the closure result. No new policy engine, no thresholds in code,
  no filesystem, no environment."
  (:require [clojure.string :as str]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Parsing the verifier's own summary.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private summary-map-pattern
  "clojure.test's summary MAP, which this project's own test runner prints as
  the last thing it emits. Matched as a brace-delimited region containing
  `:type :summary`, and then read KEY BY KEY — never positionally.

  The key-order independence is not defensive programming, it is a fact about
  the environments this has to read. The host toolchain prints
  `{:type :summary, :test 1585, :pass 6254, …}` and babashka — the toolchain
  INSIDE the guest, which is where the closure verifier actually runs —
  prints `{:test 1585, :pass 6254, …, :type :summary}`. A positional parse
  would have read one environment and refused the other, and refused the one
  that matters."
  #"\{[^{}]*:type\s+:summary[^{}]*\}")

(defn- summary-key
  "One `:key <integer>` out of a summary map's text, or nil."
  [text k]
  (some-> (re-find (re-pattern (str ":" k "\\s+(\\d+)")) (str text))
          second parse-long))

(def ^:private summary-line-patterns
  "The human summary LINES, as the fallback for a runner whose map form this
  parser does not recognize. Two dialects, both real: the host toolchain's
  one-line form, and clojure.test's stock two-line form (\"Ran N tests
  containing M assertions.\" then \"F failures, E errors.\"), which is what
  babashka prints."
  {:one-line #"Ran\s+(\d+)\s+tests?\.\s+(\d+)\s+assertions?\s+passed,\s+(\d+)\s+failures?,\s+(\d+)\s+errors?\."
   :containing #"Ran\s+(\d+)\s+tests?\s+containing\s+(\d+)\s+assertions?\."
   :failures #"(\d+)\s+failures?,\s+(\d+)\s+errors?\."})

(defn- last-match
  "The LAST match of `re` in `text`, as a vector of parsed longs, or nil.

  The last, not the first: a suite that prints a per-namespace summary on its
  way past ends with the whole run's, and the whole run's is the one that
  describes the closure."
  [re text]
  (when (string? text)
    (let [ms (re-seq re text)]
      (when (seq ms)
        (mapv parse-long (rest (last ms)))))))

(defn- map-summary
  "[tests passes failures errors] from the LAST summary map in `text`, or nil
  when there is none or one of its four keys is missing."
  [text]
  (when-let [region (last (re-seq summary-map-pattern (str text)))]
    (let [vs (mapv #(summary-key region %) ["test" "pass" "fail" "error"])]
      (when (every? some? vs) vs))))

(defn- line-summary
  "[tests passes failures errors] from the summary LINES, or nil."
  [text]
  (or (last-match (:one-line summary-line-patterns) text)
      (let [c (last-match (:containing summary-line-patterns) text)
            f (last-match (:failures summary-line-patterns) text)]
        (when (and c f) (into c f)))))

(defn parse-summary
  "The [tests passes failures errors] the closure verifier reported, or nil
  when its output carries no summary this parser recognizes.

  Both forms are read when both are present, and a DISAGREEMENT between them
  returns nil rather than a choice: two summaries that do not match are not
  one summary, and picking a winner would be inventing a coverage claim."
  [output]
  (let [m (map-summary output)
        l (line-summary output)]
    (if (and m l)
      (when (= m l) m)
      (or m l))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The signature.
;; ═══════════════════════════════════════════════════════════════════════════

(def signature-version 1)

(defn signature
  "The ClosureCoverageSignature of one closure verification result.

  `result` is the provider's run result ({:green? :exit :output …} plus the
  RFC-012 additions a real spawn carries). `coordinates` names the three
  things a coverage number is only meaningful beside:

    :suite     — which suite/environment produced it (the environment's own
                 canonical coordinate; the SAME environment digest the run
                 envelope's attribution carries)
    :verifier  — which verifier, by the environment's full-policy coordinate
    :input     — which project bytes, by the staged input coordinate

  Always returns a map. A result whose summary cannot be read is a signature
  of kind :unparseable rather than an absent one: 'the closure verifier said
  something this parser could not read' is itself the fact worth recording,
  and it is the fact `admissible?` refuses on."
  [result {:keys [suite verifier input]}]
  (let [truncated? (boolean (get-in result [:stdout :truncated?]))
        counts (parse-summary (:output result))
        base {:coverage/version signature-version
              :coverage/suite suite
              :coverage/verifier verifier
              :coverage/input input
              :coverage/green? (boolean (:green? result))
              :coverage/exit (:exit result)
              :coverage/output-truncated? truncated?}]
    (if counts
      (let [[tests passes failures errors] counts]
        (assoc base
               :coverage/kind :parsed
               :coverage/tests tests
               :coverage/assertions passes
               :coverage/failures failures
               :coverage/errors errors))
      (assoc base :coverage/kind :unparseable))))

(defn refusal
  "Why this signature is not admissible closure evidence, or nil.

  The three fail-closed cases, and no fourth. Each one is a closure result
  that has stopped being evidence rather than a result that says something
  unwelcome:

    :closure-summary-unparseable — the verifier's output carries no summary
      this parser recognizes, or carries two that disagree. A green exit code
      beside an unreadable summary is a claim nobody checked; a truncated
      capture reaches here too, and reaches it honestly.
    :closure-zero-tests — the summary reports that nothing ran. An empty
      suite exits zero, and exit zero is exactly what a closure gate reads as
      GREEN.
    :closure-summary-contradicts-verdict — the verifier called it green while
      its own summary counted failures or errors. Two trusted numbers from
      one run that do not agree is a broken invariant, not a soft signal.

  A decrease against a baseline is deliberately absent: see `delta`."
  [sig]
  (cond
    (= :unparseable (:coverage/kind sig)) :closure-summary-unparseable
    (not (pos? (or (:coverage/tests sig) 0))) :closure-zero-tests
    (and (:coverage/green? sig)
         (pos? (+ (or (:coverage/failures sig) 0)
                  (or (:coverage/errors sig) 0))))
    :closure-summary-contradicts-verdict
    :else nil))

(defn admissible?
  "Whether this signature may stand as closure evidence."
  [sig]
  (nil? (refusal sig)))

;; ═══════════════════════════════════════════════════════════════════════════
;; The baseline comparison.
;; ═══════════════════════════════════════════════════════════════════════════

(defn delta
  "The coverage movement from `baseline` to `final`, or nil without a usable
  baseline pair.

  Exposes the numbers and says whether the coverage DECREASED; it does not
  decide anything. A decrease has legitimate causes (a test deleted on
  purpose, a namespace merged) and illegitimate ones (a test quietly removed
  to make a gate pass), and this layer cannot tell them apart — so it reports
  and a human explains. Comparing to a HOST suite's counts is not what this
  is for: the environments differ by design, and `:coverage/suite` is carried
  precisely so a mechanical comparison across two different suites is
  visibly wrong rather than tempting."
  [baseline final]
  (when (and (= :parsed (:coverage/kind baseline))
             (= :parsed (:coverage/kind final)))
    (let [d (fn [k] (- (or (k final) 0) (or (k baseline) 0)))
          tests (d :coverage/tests)
          assertions (d :coverage/assertions)]
      {:delta/tests tests
       :delta/assertions assertions
       :delta/failures (d :coverage/failures)
       :delta/errors (d :coverage/errors)
       :delta/decreased? (or (neg? tests) (neg? assertions))
       :delta/same-suite? (= (:coverage/suite baseline) (:coverage/suite final))
       :delta/baseline (select-keys baseline [:coverage/tests :coverage/assertions
                                              :coverage/suite])})))

(defn warnings
  "The unexplained-decrease warnings for a delta, as data. Warnings only —
  nothing here refuses, and the deliberate absence of a severity ladder is
  the point: one fact, stated once."
  [d]
  (cond-> []
    (and d (:delta/decreased? d))
    (conj {:warning :closure-coverage-decreased
           :delta/tests (:delta/tests d)
           :delta/assertions (:delta/assertions d)})
    (and d (false? (:delta/same-suite? d)))
    (conj {:warning :closure-coverage-suite-changed})))
