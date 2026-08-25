;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.agent.tools.ship
  "Shipping tools: thesis, done, give_up, branch_theses — and the
  claim-evidence gates those methods share (answer-tokens,
  uncovered-tokens, engages-problem? and friends)."
  (:require [clojure.string :as str]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.state :as state]
            [samizdat.agent.verify :as verify]
            [samizdat.agent.wordlists :as wordlists]
            [samizdat.store.journal :as journal]))



;; --- registering intent -----------------------------------------------------

(defmethod base/run-tool "thesis" [{:keys [branch] :as ctx}]
  (if-let [m (base/missing ctx :goal :technique)]
    (base/malformed branch m)
    (let [thesis {:goal (base/arg ctx :goal)
                  :subClaims (vec (or (base/arg ctx :subClaims) []))
                  :technique (base/arg ctx :technique)
                  :set-at-turn (:turn ctx)}]
      (base/ok (assoc branch :thesis thesis)
          (str "Thesis registered: " (:goal thesis)
               "\nTechnique: " (:technique thesis)
               (when (seq (:subClaims thesis))
                 (str "\nSub-claims:\n"
                      (str/join "\n" (map-indexed #(str "  " (inc %1) ". " %2)
                                                  (:subClaims thesis))))))
          :progress? true
          :thesis thesis))))

;; --- the claim-evidence gates -----------------------------------------------

;; Tier 1c: both the framing stopwords and the tool-version pattern are
;; wordlists.edn data — retunable at runtime without a rebuild. The section
;; comments recording why words are on the list moved with the words.
(def ^:private stopwords (wordlists/wordlist :answer-framing))

(def ^:private tool-version-re
  (re-pattern (wordlists/wordlist :tool-version)))

(defn answer-tokens
  "Substantive tokens from a proposed answer: numbers and words that are not
  stopwords. Numbers matter most — an answer naming a size, a bound, or a
  witness has to have that number in the evidence."
  [text]
  (->> (str/split (str/lower-case (str/replace (or text "") tool-version-re " "))
                  #"[^a-z0-9_.-]+")
       ;; `.` and `-` stay INSIDE the split class so 3.5 and cross-check survive
       ;; as one token, which means a sentence-final period rides along with the
       ;; last word. Trim the edges, keep the interior.
       (map #(str/replace % #"^[.-]+|[.-]+$" ""))
       (remove str/blank?)
       (remove stopwords)
       ;; A hyphenated compound is one token, so `engine-confirmed` survived a
       ;; list holding every one of its parts. A compound with a substantive
       ;; half — `optimal-flow` — is not exempt.
       (remove #(and (str/includes? % "-")
                     (let [parts (remove str/blank? (str/split % #"-"))]
                       (and (seq parts)
                            (every? (fn [p] (or (stopwords p) (< (count p) 4)))
                                    parts)))))
       (filter #(or (re-matches #"[0-9]+(\.[0-9]+)?" %) (>= (count %) 4)))
       distinct))

(def ^:private word-suffixes
  "Stripped longest-first, one only. Enough to see that `enumeration` and
  `enumerating` are the same word, which raw substring matching cannot."
  ["ations" "ation" "ising" "izing" "ings" "ing" "ions" "ion" "ies" "ied"
   "es" "ed" "s"])

(defn- stem
  "The token with one morphological suffix removed, or nil.

  Never below five characters, so nothing is shortened into a prefix that
  matches everything."
  [w]
  (some (fn [suf]
          (when (and (str/ends-with? w suf)
                     (>= (- (count w) (count suf)) 5))
            (subs w 0 (- (count w) (count suf)))))
        word-suffixes))

(defn number-token?
  "Whether an answer token is a figure rather than a word. The two halves of
  the coverage check treat them completely differently: a figure blocks a
  ship, a word advises."
  [token]
  (boolean (re-matches #"[0-9]+(\.[0-9]+)?" token)))

(defn- covered?
  "Whether `token` appears in the evidence.

  Numbers are matched exactly, against the artifacts alone. That is the strict
  half and it stays strict: an answer naming a size, a bound or a witness that
  nothing produced is the fabricated report this whole rung exists to catch.

  Words get three chances — the token itself, the token with hyphens
  normalised, and its stem — because a refusal over `residues` when the
  evidence says `residue` teaches the model to strip its prose rather than to
  verify anything."
  [token artifact-text word-text]
  (if (number-token? token)
    (str/includes? artifact-text token)
    (or (str/includes? word-text token)
        (and (str/includes? token "-")
             (or (str/includes? word-text (str/replace token "-" " "))
                 (str/includes? word-text (str/replace token "-" ""))))
        (when-let [s (stem token)] (str/includes? word-text s))
        ;; One derivational step further, for long words only: `computability`
        ;; against `computable` is the same complaint as `residues` against
        ;; `residue`, and no suffix list reaches it.
        (and (>= (count token) 8) (str/includes? word-text (subs token 0 6))))))

(defn uncovered-tokens
  "Answer tokens no confirmed artifact mentions.

  The claim-evidence gate, deterministic and with no model in the path. An
  answer asserting a number that appears nowhere in the evidence is a
  fabricated report."
  ([answer artifacts] (uncovered-tokens answer artifacts nil))
  ([answer artifacts word-context]
   (let [artifact-text (str/lower-case
                        (str/join " " (for [a artifacts]
                                        (str (:claim a) " " (:code a) " "
                                             (pr-str (:witness a))))))
         ;; Words may also come from `word-context`: the problem statement the
         ;; harness handed the branch. Numbers get none of this — a figure has
         ;; to come from an artifact.
         word-text (str artifact-text " "
                        (str/lower-case
                         (if (coll? word-context)
                           (str/join " " (remove nil? word-context))
                           (str word-context))))]
     (remove #(covered? % artifact-text word-text) (answer-tokens answer)))))

(defn engages-problem?
  "Whether the answer shares any substantive vocabulary with the problem.

  The free rung, and deliberately the weakest one: lexical overlap cannot tell
  an answer to the question from an answer about the question's machinery.
  Zero overlap is the only thing it decides, and it decides it with no model
  in the path.

  A problem with no substantive vocabulary of its own — a stub, a test
  fixture — means there is nothing to be irrelevant to, and this passes."
  [problem answer]
  (let [terms (set (answer-tokens problem))]
    (or (empty? terms)
        (boolean (some terms (answer-tokens answer))))))

;; --- shipping ---------------------------------------------------------------

(defmethod base/run-tool "done" [{:keys [branch] :as ctx}]
  ;; Slimmed from the proof harness's seven-rung ship gate: the audit, review
  ;; and LLM-relevance rungs left with the judge machinery. What remains is
  ;; every rung that runs with no model in the path — an answer must exist,
  ;; its figures must come from the evidence, and it must engage the problem.
  ;; The coding loop's ship gate (tests pass, review passed) rebuilds on this
  ;; seam as data-defined gates.
  (let [answer (base/arg ctx :answer)
        confirmed (state/confirmed-artifacts branch)
        own (concat confirmed (state/empirical-artifacts branch))
        ;; And what the rest of the run established: a branch is shown the
        ;; shared-artifact block, so refusing the answer that cites it would
        ;; punish the branch for reading what the harness handed it (vf-b9c).
        elsewhere (when (and (:conn ctx) (:run-id ctx))
                    (journal/corroborating-artifacts
                     (:conn ctx) (:run-id ctx) (:id branch)))
        evidence (concat own elsewhere)
        problem (:problem branch)
        uncovered (uncovered-tokens answer evidence [problem])
        uncovered-numbers (filter number-token? uncovered)
        borrowed (when (seq elsewhere)
                   (seq (remove (set uncovered)
                                (uncovered-tokens answer own [problem]))))
        block (cond
                (str/blank? (str answer))
                "Supply an `answer` to ship."

                ;; Only when the run actually produced evidence to check
                ;; against. The number-coverage rung guards against a
                ;; FABRICATED verification report — a figure claimed as proven
                ;; that no artifact supports. A coding run produces no
                ;; artifacts (its work is proven by tests passing, which the
                ;; shell tool reports and the journal records, not by confirmed
                ;; claims), so with empty evidence EVERY figure reads as
                ;; uncovered and the gate refuses honest answers like "0
                ;; failures, 3 tests". Surfaced by the first self-modification
                ;; run, which did the work correctly and could not ship it.
                (and (seq evidence) (seq uncovered-numbers))
                (str "Your answer states figures no artifact supports: "
                     (str/join ", " (map #(str "`" % "`") (take 8 uncovered-numbers)))
                     ".\nA number in an answer has to come from something"
                     " confirmed or measured — that is the difference between a"
                     " report and a fabricated one. Either verify these or"
                     " remove them from the answer.")

                (and (not (str/blank? (str problem)))
                     (not (engages-problem? problem answer)))
                (str "This answer shares no substantive term with the problem"
                     " statement. Whatever it establishes, it is not an answer to"
                     " the question that was asked."))
        ;; The test rung — what makes the loop test-driven rather than one-shot.
        ;; `done` is not terminal until the unit's tests actually pass: run the
        ;; unit's tests, and a red / hollow / untested result is fed back so the
        ;; branch keeps iterating. Verification is FOCUSED — it runs only the
        ;; test namespaces the branch touched, so the loop iterates in seconds
        ;; against its own new test. On when a :verify-cmd is set or
        ;; :verify-focused? is true; loops with neither behave exactly as before.
        verify-cmd (get-in ctx [:config :run :verify-cmd])
        verify-focused? (get-in ctx [:config :run :verify-focused?] false)
        require-test? (get-in ctx [:config :run :require-test?] true)
        verify-on? (and (nil? block)
                        (or verify-focused? (not (str/blank? (str verify-cmd)))))
        changed (when verify-on? (gitdiff/changed-files (:root ctx) (:git-baseline ctx)))
        ;; Prefer the focused command; fall back to the configured one. Run only
        ;; when the cheap pre-checks (nothing changed / no test yet) haven't
        ;; already doomed the ship — a wasted suite run is a wasted minute.
        cmd (when verify-on? (or (and verify-focused? (verify/focused-cmd changed)) verify-cmd))
        pre-doomed? (or (and (some? changed) (empty? changed))
                        (and require-test? (some? changed) (seq changed)
                             (not (some verify/test-file? changed))))
        vresult (when (and verify-on? cmd (not pre-doomed?))
                  (verify/run-verify (:root ctx) cmd
                                     (get-in ctx [:config :run :verify-timeout-ms])))
        verify-block (verify/verify-block
                      {:verify-on? verify-on? :result vresult
                       :changed changed :require-test? require-test?})
        block (or block verify-block)]
    (when (and vresult (:conn ctx) (:run-id ctx))
      (journal/note! (:conn ctx) (:run-id ctx) :ship-verify
                     {:branch-id (:id branch) :turn (:turn ctx)
                      :data {:green (:green? vresult) :timeout (:timeout? vresult)
                             :blocked (some? verify-block)}}))
    ;; Journalled whether or not anything blocked, so the run record still
    ;; shows what the lexical check saw even though words no longer decide.
    (when-let [words (and (:conn ctx) (:run-id ctx)
                          (seq (remove number-token? uncovered)))]
      (journal/note! (:conn ctx) (:run-id ctx) :uncovered-words
                     {:branch-id (:id branch) :turn (:turn ctx)
                      :data {:words (vec (take 20 words)) :blocked? (some? block)}}))
    (when (and borrowed (:conn ctx) (:run-id ctx))
      (journal/note! (:conn ctx) (:run-id ctx) :cross-branch-citation
                     {:branch-id (:id branch) :turn (:turn ctx)
                      :data {:tokens (vec (take 20 borrowed))
                             :sources (vec (distinct (keep :branch_id elsewhere)))}}))
    (if block
      (base/fail branch (str "`done` refused.\n\n" block) :done-block block)
      {:branch (assoc branch :final-answer answer :status :done)
       :category :success
       :progress? true
       :done? true
       :answer answer
       ;; The green point the safe-state rung rewinds to (loop.clj tool-step
    ;; consumes this): done with the suite actually passing is the branch's
    ;; last known-good state.
    :verified-green? (boolean (:green? vresult))
    :result (str "Answer accepted.\n\n" answer)})))

(defmethod base/run-tool "give_up" [{:keys [branch] :as ctx}]
  (let [reason (or (base/arg ctx :reason) "no reason given")]
    {:branch (assoc branch :status :abandoned :inactive-reason reason)
     :category :neutral :progress? false :gave-up? true
     :result (str "Gave up: " reason)}))

;; --- forking ----------------------------------------------------------------

;; Tier 1b: the cap is gates.edn :max-branch-theses — data, so a project
;; retunes its fork budget at runtime without a rebuild.

(defmethod base/run-tool "branch_theses" [{:keys [branch] :as ctx}]
  (let [proposals (base/arg ctx :theses)
        max-branch-theses (gates/threshold :max-branch-theses)]
    (cond
      (or (not (sequential? proposals)) (empty? proposals))
      (base/malformed branch (str "`theses` must be a non-empty array of"
                        " {goal, subClaims, technique} objects."))

      (> (count proposals) max-branch-theses)
      (base/malformed branch (str "At most " max-branch-theses " theses per call; you proposed "
                             (count proposals) "."))

      (not (every? #(and (map? %) (string? (:goal %))) proposals))
      (base/malformed branch "Every thesis must be an object with a `goal` string.")

      :else
      ;; The first commits THIS branch; the rest become siblings. The scheduler
      ;; reads :pending-branch-theses after the turn and clears it, so a tool
      ;; never creates a branch itself — one place owns the branch table.
      (let [[mine & others] proposals
            thesis (assoc mine :set-at-turn (:turn ctx))]
        (base/ok (assoc branch :thesis thesis
                   :pending-branch-theses (vec others))
            (str "Committed to: " (:goal thesis)
                 (when (seq others)
                   (str "\nRequested " (count others) " sibling branch(es) for: "
                        (str/join "; " (map :goal others))
                        "\nThey explore independently and share this branch's"
                        " failure log, so none of you will repeat another's"
                        " dead end.")))
            :progress? true
            :thesis thesis)))))
