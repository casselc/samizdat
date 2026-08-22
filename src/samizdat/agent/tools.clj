;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.agent.tools
  "Tool dispatch, one method per tool.

  A multimethod rather than a case, because it is what lets a tool be
  redefined against a running process and picked up on the next branch turn.
  That is the tightest loop available for the part of the system that changes
  most.

  Every method takes a context and returns a result map:

    {:result   string the model sees
     :category :success | :failure | :neutral   what the cull guard reads
     :progress? bool                            what the stall guard reads
     :branch   the updated branch
     :artifact optional, recorded to the artifacts table
     :failure  optional, recorded to the shared failure log
     :done?    optional, ends the run}

  :category and :progress? are separate on purpose. A tool call can succeed and
  advance nothing, and a model making varied, well-formed, useless calls trips
  no error-keyed guard while burning the whole run.

  The formal-methods tool surface (verify/lean/octave/prolog/SMT and the
  judge-backed review/audit gates) left with the proof harness; what remains
  is the general core the coding tool set builds on."
  (:require [clojure.string :as str]
            [samizdat.agent.files :as files]
            [samizdat.agent.state :as state]
            [samizdat.llm.message :as message]
            [samizdat.repl :as repl]
            [samizdat.security.policy :as policy]
            [samizdat.store.journal :as journal]
            [samizdat.store.tasks :as tasks]))

(defmulti run-tool
  (fn [ctx] (:tool-name ctx)))

(defn- ok [branch result & {:as extra}]
  (merge {:result result :category :neutral :progress? false :branch branch} extra))

(defn- fail [branch result & {:as extra}]
  (merge {:result result :category :failure :progress? false :branch branch} extra))

(defn- malformed
  "A call the harness could not act on because its arguments were wrong.

  NOT a failure. The branch produced no claim and tested nothing, so there is
  no evidence here about its line of inquiry — the same reasoning `unavailable`
  makes about an engine outage and the branch loop makes about a malformed
  fence. Charging it to the counter that decides whether a branch lives is the
  vf-jki mistake, and this is the fifth place it turned up: fences,
  expectedVerdict, proof_start, outages, and argument shape.

  `:mechanics` rather than `:neutral`, deliberately: the count is still kept
  and still bounds a branch looping on malformed calls, which is real spend.
  It just stops being read as substance."
  [branch result]
  {:result result :category :mechanics :progress? false :branch branch})

(defn- unavailable
  "An external capability could not be reached. Not the branch's fault, so not
  its failure: the failure counter neither rises nor resets, and
  turns-since-progress still ticks because nothing was established."
  [branch capability e]
  (ok branch (str capability " is unavailable: " (ex-message e))))

(defn- arg [ctx k] (get-in ctx [:args k]))

(defn- missing
  "The complaint for absent required arguments, WITH the call it wanted.

  This used to be a bare list of names. gen-20 B1 called `proof_start` without
  its arguments five times — three producing the byte-identical message — and
  was culled for it; a model that did not understand the call the first time
  learns nothing from being told the same names again. The skeleton costs
  nothing and needs no schema registry, because the tool name and the keys it
  requires are exactly what this function is already handed."
  [ctx & ks]
  (let [absent (remove #(let [v (arg ctx %)]
                          (and (some? v) (not (and (string? v) (str/blank? v)))))
                       ks)]
    (when (seq absent)
      (str "Missing required argument(s): " (str/join ", " (map name absent)) "."
           "\n\nA call to `" (:tool-name ctx) "` looks like:\n"
           "```tool-call\n"
           "{\"name\": \"" (:tool-name ctx) "\", \"args\": {"
           (str/join ", " (for [k ks]
                            (str "\"" (name k) "\": \"<" (name k) ">\"")))
           "}}\n```"))))

(defn phase-refusal
  "The one place that owns per-phase tool policy, consulted by the branch loop
  BEFORE run-tool dispatch. Returns a result map refusing the call, or nil
  when it may proceed.

  The proof harness's explore/build policy (withhold Lean until a sketch,
  withhold sketch once building) left with its tool surface. The seam stays —
  the loop still asks — and the coding loop's phase policy plugs back in here
  when the loop-as-manifest work defines it. Any refusal returned from here
  must carry `:policy-refusal? true` so the cull record can tell a declined
  call from a malformed fence."
  [_ctx]
  nil)

;; --- registering intent -----------------------------------------------------

(defmethod run-tool "thesis" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :goal :technique)]
    (malformed branch m)
    (let [thesis {:goal (arg ctx :goal)
                  :subClaims (vec (or (arg ctx :subClaims) []))
                  :technique (arg ctx :technique)
                  :set-at-turn (:turn ctx)}]
      (ok (assoc branch :thesis thesis)
          (str "Thesis registered: " (:goal thesis)
               "\nTechnique: " (:technique thesis)
               (when (seq (:subClaims thesis))
                 (str "\nSub-claims:\n"
                      (str/join "\n" (map-indexed #(str "  " (inc %1) ". " %2)
                                                  (:subClaims thesis))))))
          :progress? true
          :thesis thesis))))

;; --- the claim-evidence gates -----------------------------------------------

(def ^:private stopwords
  ;; Grammar plus the vocabulary a model uses to FRAME an answer rather than to
  ;; assert one. The gate is aimed at specifics — numbers, names, witnesses —
  ;; because that is where fabrication actually happens; "the answer is" is not
  ;; a claim about anything. Widening this list weakens the gate, so entries
  ;; earn their place by being framing rather than content.
  #{"the" "a" "an" "is" "are" "was" "were" "of" "for" "and" "or" "not" "no"
    "in" "on" "to" "with" "that" "this" "it" "as" "by" "at" "be" "there"
    "exists" "all" "any" "we" "have" "has" "can" "so" "if" "then" "thus"
    "answer" "solution" "solutions" "result" "results" "value" "values"
    "therefore" "hence" "conclusion" "shows" "show" "proved" "proven"
    "verified" "confirms" "confirmed" "follows" "given" "which" "where"
    "does" "do" "did" "follow" "following" "having" "from" "into" "than"
    "when" "while" "because" "since" "about" "over" "under" "between"
    "unique" "uniquely" "only" "exactly" "such" "these" "those" "each"
    "every" "must" "also" "both" "same" "case" "cases" "holds" "true" "false"

    ;; Provenance vocabulary: how the answer was checked, not what it claims.
    ;; These can never appear in an artifact, because an artifact's claim and
    ;; code are about the problem and say nothing about the engine that ran
    ;; them. Leaving them in made the gate refuse answers for asserting the
    ;; word "mathlib", which pushed the model toward stripping every
    ;; explanatory sentence to get past it — the opposite of what the harness
    ;; wants its answers to look like. The proof-engine names stay even
    ;; though the engines left: they are still provenance if they appear.
    "lean" "mathlib" "prolog" "clpfd" "swipl" "z3" "smt" "smtlib" "octave"
    "engine" "engines" "harness" "kernel" "kernel-checked" "machine-checked"
    "theorem" "theorems" "lemma" "lemmas" "proof" "proofs" "tactic" "tactics"
    "statement" "statements" "universal" "universally" "induction" "inductive"
    "base" "step" "successor" "encoding" "encodings" "formalisation"
    "formalization" "independent" "independently" "cross-check" "cross-checked"
    ;; Generic mathematical prose. "equals" and "numbers" carry no specific
    ;; content — the specific part is the number or name they connect.
    "number" "numbers" "equal" "equals" "integer" "integers" "natural"
    "naturals" "first" "sums" "pairwise" "distinct" "positive"

    ;; The vocabulary of SCOPING an answer: saying what was and was not
    ;; settled, and how sure of it you are. What you failed to establish is,
    ;; by construction, not in your evidence; a gate that reads naming it as
    ;; asserting it makes honesty impossible (vf-w2k).
    "stated" "states" "fact" "facts" "establish" "establishes" "established"
    "evidence" "check" "checks" "checked" "unchecked" "found" "finds"
    "finding" "findings" "showed" "shown" "showing"
    "prove" "proves" "proving" "support" "supports"
    "supported" "settle" "settles" "settled" "unsettled" "unresolved"
    "resolve" "resolves" "resolved" "ask" "asks" "asked" "question"
    "questions" "answers" "answered" "unanswered" "claim" "claims"
    "claimed" "assert" "asserts" "asserted" "conclude" "concludes"
    "remain" "remains" "remaining"
    "outstanding" "together" "against" "general" "generally" "partial"
    "partially" "whenever" "conditional" "conditionally" "arbitrary"
    "computation" "computations" "computed" "search" "searched" "taken"
    "what" "branch" "branches" "verify" "verifies" "verification"
    "establishing" "demonstrate" "demonstrates" "demonstrated"
    ;; Deictics. A word that points at the document rather than at the
    ;; substance cannot be an assertion about the substance.
    "here" "above" "below" "within" "throughout"
    "reach" "reaches" "reached" "give" "gives" "yield" "yields" "open"})

;; A tool name followed by its version. Stripped BEFORE tokenizing, because the
;; version is a bare number and numbers are the part of this gate that must not
;; be relaxed — "Python 3" would otherwise assert the number 3 and get refused
;; for it. Narrow on purpose: only a number directly after a known tool name.
(def ^:private tool-version-re
  #"(?i)\b(lean|mathlib|z3|swipl|swi-prolog|prolog|octave|clojure|jolt|python|node|java|deepseek)[\s-]*[0-9]+(\.[0-9]+)*")

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

(defn labelled-line
  "The text after `LABEL:` on the last line carrying one, or nil."
  [text label]
  (last (keep (fn [line]
                (when-let [m (re-matches
                              (re-pattern (str "(?i)" label "\\s*:\\s*(.+)"))
                              (str/trim line))]
                  (str/trim (second m))))
              (str/split-lines (str text)))))

;; --- shipping ---------------------------------------------------------------

(defmethod run-tool "done" [{:keys [branch] :as ctx}]
  ;; Slimmed from the proof harness's seven-rung ship gate: the audit, review
  ;; and LLM-relevance rungs left with the judge machinery. What remains is
  ;; every rung that runs with no model in the path — an answer must exist,
  ;; its figures must come from the evidence, and it must engage the problem.
  ;; The coding loop's ship gate (tests pass, review passed) rebuilds on this
  ;; seam as data-defined gates.
  (let [answer (arg ctx :answer)
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
                     " the question that was asked."))]
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
      (fail branch (str "`done` refused.\n\n" block) :done-block block)
      {:branch (assoc branch :final-answer answer :status :done)
       :category :success
       :progress? true
       :done? true
       :answer answer
       :result (str "Answer accepted.\n\n" answer)})))

(defmethod run-tool "give_up" [{:keys [branch] :as ctx}]
  (let [reason (or (arg ctx :reason) "no reason given")]
    {:branch (assoc branch :status :abandoned :inactive-reason reason)
     :category :neutral :progress? false :gave-up? true
     :result (str "Gave up: " reason)}))

;; --- unknown ----------------------------------------------------------------

(defmethod run-tool :default [{:keys [branch tool-name]}]
  (fail (update-in branch [:mechanics :unknown-tools] inc)
        (str "No tool named `" tool-name "`. Available: "
             (str/join ", " (sort (remove #{:default} (keys (methods run-tool)))))
             ".")))

(defn tool-names []
  (sort (remove keyword? (keys (methods run-tool)))))

;; --- forking ----------------------------------------------------------------

(def max-branch-theses 4)

(defmethod run-tool "branch_theses" [{:keys [branch] :as ctx}]
  (let [proposals (arg ctx :theses)]
    (cond
      (or (not (sequential? proposals)) (empty? proposals))
      (malformed branch (str "`theses` must be a non-empty array of"
                        " {goal, subClaims, technique} objects."))

      (> (count proposals) max-branch-theses)
      (malformed branch (str "At most " max-branch-theses " theses per call; you proposed "
                             (count proposals) "."))

      (not (every? #(and (map? %) (string? (:goal %))) proposals))
      (malformed branch "Every thesis must be an object with a `goal` string.")

      :else
      ;; The first commits THIS branch; the rest become siblings. The scheduler
      ;; reads :pending-branch-theses after the turn and clears it, so a tool
      ;; never creates a branch itself — one place owns the branch table.
      (let [[mine & others] proposals
            thesis (assoc mine :set-at-turn (:turn ctx))]
        (ok (assoc branch :thesis thesis
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

;; --- the REPL ----------------------------------------------------------------

(defmethod run-tool "eval" [{:keys [branch repl-session] :as ctx}]
  ;; Evaluate Clojure in the live harness image. REPL-first development: the
  ;; agent tries a form, sees the value and output, and iterates before
  ;; committing it to a file. :neutral — evaluating establishes nothing on its
  ;; own; a define-and-test is exploration, and progress is the file it leads
  ;; to. Defs persist across evals within a run (the session is per-run).
  (if-let [m (missing ctx :code)]
    (malformed branch m)
    (let [r (if repl-session
              (repl/eval-code (str (arg ctx :code)) repl-session)
              (repl/eval-code (str (arg ctx :code))))]
      (if (:ok r)
        (ok branch (str "=> " (:value r)
                        (when (seq (:out r)) (str "\n" (:out r)))))
        (fail branch (str "Eval error: " (:error r)
                          (when (seq (:out r)) (str "\n" (:out r)))))))))

(defmethod run-tool "doc" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :symbol)]
    (malformed branch m)
    (let [d (repl/doc-sym (str (arg ctx :symbol)))]
      (if (:not-found d)
        (malformed branch (str "No var " (arg ctx :symbol) " is loaded."))
        (ok branch (str (:name d) "\n" (pr-str (:arglists d)) "\n\n" (:doc d)))))))

(defmethod run-tool "complete" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :prefix)]
    (malformed branch m)
    (let [ms (repl/complete (str (arg ctx :prefix)))]
      (ok branch (if (seq ms)
                   (str/join "\n" (take 50 ms))
                   (str "No symbols match " (arg ctx :prefix) "."))))))

;; --- the files ---------------------------------------------------------------

(defmethod run-tool "read_file" [ctx]
  (files/read-file ctx))

(defmethod run-tool "write_file" [ctx]
  (files/write-file ctx))

;; --- the shell ---------------------------------------------------------------

(defmethod run-tool "shell" [{:keys [branch] :as ctx}]
  ;; Every command faces the permission engine, runs under a scrubbed
  ;; environment, and its output is redacted before it returns — one call into
  ;; samizdat.security.policy, which owns all three. A denied or unapproved
  ;; command never spawns. The result's :category is what the cull guard reads:
  ;; a policy refusal is :neutral (the branch did nothing wrong, the harness
  ;; declined), a real command failure is :failure.
  (if-let [m (missing ctx :command)]
    (malformed branch m)
    (let [r (policy/run-shell ctx)]
      (assoc r :branch branch
             ;; A policy refusal is journalled as declined, like a phase
             ;; refusal, so the record can tell it from a command that ran
             ;; and failed.
             :policy-refusal? (contains? #{:deny :ask} (get-in r [:policy :effect]))))))

;; --- the task board ----------------------------------------------------------

(defn- task-line [t]
  (str (:id t) " [" (:status t) "/" (:priority t)
       (when-not (= "task" (:type t)) (str " " (:type t)))
       (when (:parent_id t) (str " < " (:parent_id t)))
       "] " (:title t)))

(defn- render-task [conn t]
  (str (task-line t)
       (when (seq (:body t)) (str "\n\n" (:body t)))
       (when (seq (:contract t)) (str "\n\nCONTRACT\n" (:contract t)))
       (when (seq (:tests t)) (str "\n\nTESTS\n" (:tests t)))
       (when-let [kids (seq (tasks/children-of conn (:id t)))]
         (str "\n\nCHILDREN\n" (str/join "\n" (map task-line kids))))))

(def ^:private task-usage
  (str "Actions: create {title, body?, type?, priority?, parentId?, contract?, tests?},"
       " list, show {id}, update {id, ...fields}, claim {id}, close {id, status?}."))

(defmethod run-tool "task" [{:keys [branch conn run-id] :as ctx}]
  ;; Every action is `ok` (:neutral) on purpose: working the board is
  ;; bookkeeping, and bookkeeping is not progress — the same reasoning as
  ;; fetch_artifact. Grounding work in tasks is required; credit for the work
  ;; itself comes from artifacts. Bad ids, bad statuses, and unknown actions
  ;; are :mechanics — calls made wrong, not failed lines of inquiry.
  (let [action (some-> (arg ctx :action) str str/trim str/lower-case not-empty)
        want (fn [k] (let [v (arg ctx k)]
                       (when-not (and (some? v) (not (and (string? v) (str/blank? v))))
                         (malformed branch (str "`task " action "` needs `" (name k) "`. "
                                                task-usage)))))]
    (try
      (case action
        nil
        (malformed branch (str "`task` needs an `action`. " task-usage))

        "create"
        (or (want :title)
            (let [id (tasks/create! conn {:title (arg ctx :title)
                                          :body (arg ctx :body)
                                          :type (arg ctx :type)
                                          :status (arg ctx :status)
                                          :priority (arg ctx :priority)
                                          :parent-id (arg ctx :parentId)
                                          :contract (arg ctx :contract)
                                          :tests (arg ctx :tests)
                                          :run-id (when-not (arg ctx :backlog) run-id)})]
              (ok branch (str "Created " (task-line (tasks/get-task conn id))))))

        "list"
        (let [rows (tasks/board conn {:run-id run-id})]
          (ok branch (if (seq rows)
                       (str/join "\n" (map task-line rows))
                       "The board is empty.")))

        "show"
        (or (want :id)
            (if-let [t (tasks/get-task conn (arg ctx :id))]
              (ok branch (render-task conn t))
              (malformed branch (str "No task " (arg ctx :id) "."))))

        "update"
        (or (want :id)
            (if-not (tasks/get-task conn (arg ctx :id))
              (malformed branch (str "No task " (arg ctx :id) "."))
              (let [t (tasks/update! conn (arg ctx :id)
                                     {:title (arg ctx :title)
                                      :body (arg ctx :body)
                                      :type (arg ctx :type)
                                      :status (arg ctx :status)
                                      :priority (arg ctx :priority)
                                      :parent-id (arg ctx :parentId)
                                      :contract (arg ctx :contract)
                                      :tests (arg ctx :tests)})]
                (ok branch (str "Updated " (task-line t))))))

        "claim"
        (or (want :id)
            (if-let [t (tasks/claim! conn (arg ctx :id) run-id)]
              (ok branch (str "Claimed " (task-line t)))
              (malformed branch (str "Cannot claim " (arg ctx :id)
                                     ": no such task, or another run holds it."))))

        "close"
        (or (want :id)
            (if-not (tasks/get-task conn (arg ctx :id))
              (malformed branch (str "No task " (arg ctx :id) "."))
              (let [t (tasks/close! conn (arg ctx :id) (or (arg ctx :status) "done"))]
                (ok branch (str "Closed " (task-line t))))))

        (malformed branch (str "Unknown task action `" action "`. " task-usage)))
      (catch Throwable e
        ;; Unknown statuses, missing parents: the store's validation errors are
        ;; calls made wrong, and the message already says what was wrong.
        (malformed branch (str "`task " action "` refused: " (ex-message e)
                               "\n" task-usage))))))

;; --- the journal, readable --------------------------------------------------

(defmethod run-tool "fetch_artifact" [{:keys [branch conn run-id] :as ctx}]
  ;; The ledger lists claims with ids and leaves the encodings out, so the code
  ;; costs a turn only when a branch actually wants it rather than riding in
  ;; every context block. This is what makes an id actionable.
  ;;
  ;; Deliberately :neutral, via `ok`: a lookup establishes nothing. Reporting
  ;; :success would clear the branch's consecutive-failure count and read as
  ;; progress, which is the "well-formed but useless call" failure the
  ;; progress guards exist to catch.
  (if-let [m (missing ctx :id)]
    (malformed branch m)
    (let [raw (str/trim (str (arg ctx :id)))
          ;; `a#` is this run's own artifacts, `s#` the shared pool a seed was
          ;; copied into — two tables, two id spaces. A bare number means the
          ;; branch's own, which is the common case. `p#` is also this run's
          ;; own artifacts — the ledger's handle for a SKETCH, same table,
          ;; different status, so the prefix survives the round trip.
          shared? (str/starts-with? raw "s#")
          own? (or (str/starts-with? raw "a#") (str/starts-with? raw "p#"))
          sketch? (str/starts-with? raw "p#")
          id (parse-long (str/replace raw #"^[aps]#" ""))
          ;; An explicit prefix is honoured exactly. A BARE number tries this
          ;; run's own artifacts and then falls back to the shared pool:
          ;; insisting on the prefix cost six of the first eleven fetches in
          ;; an observed run.
          own (when (and conn run-id id (not shared?))
                (journal/artifact-by-id conn run-id id))
          a (or own
                (when (and conn run-id id (not own?))
                  (journal/shared-artifact-by-id conn run-id id)))
          ;; Which space it actually came from, so the echoed handle matches
          ;; what the ledger showed.
          from-shared? (and a (nil? own))]
      (if-not a
        ;; :mechanics, not :failure — a lookup that finds nothing refutes
        ;; nothing; a bad id is a call made wrong.
        (malformed branch (str "No artifact " raw " in this run."
                          " Ids come from the settled-state block: `a#12` for"
                          " something this run established, `s#7` for something"
                          " it inherited. A run cannot reach another run's"
                          " artifacts."))
        (ok branch
            (str (if from-shared? "s#" (if sketch? "p#" "a#")) (:id a)
                 " [" (:branch_id a) " " (:kind a) "/" (:tier a) "]"
                 ;; The status travels with the encoding or a refutation reads
                 ;; as an established result. Seeded rows carry no status
                 ;; column of their own; seed-from-run! copies only confirmed
                 ;; artifacts, so saying so is accurate rather than a guess.
                 " status " (if from-shared?
                              "CONFIRMED (inherited from the seed run)"
                              (str/upper-case (str (:claim_status a))))
                 (when (:verdict a) (str ", verdict " (:verdict a)))
                 "\n\nCLAIM\n" (:claim a)
                 "\n\nENCODING\n" (:code a)))))))

(defmethod run-tool "fetch_turn" [{:keys [branch conn run-id] :as ctx}]
  ;; The other half of compaction. Unloading a branch's early turns to one
  ;; line each is only honest if a line can be opened again; before this,
  ;; the digest pointed at a journal the branch had no tool to read.
  ;;
  ;; :neutral for the same reason as fetch_artifact — a lookup establishes
  ;; nothing, and reporting success would clear the failure count.
  (if-let [m (missing ctx :turn)]
    (malformed branch m)
    (let [raw (str/trim (str (arg ctx :turn)))
          n (parse-long (str/replace raw #"^t" ""))
          t (when (and conn run-id n)
              (journal/branch-turn conn run-id (:id branch) n))]
      (if-not t
        ;; :mechanics for the same reason as fetch_artifact's miss.
        (malformed branch (str "No turn " raw " on this branch. The digest lists"
                          " your own turns as t1, t2, …; a sibling's turns are"
                          " not readable here — what crossed from them is in"
                          " the settled-state block."))
        (ok branch
            (str "t" (:turn t) " " (:tool_name t)
                 " → " (or (:category t) "neutral")
                 (when (seq (str (:args t))) (str "\n\nARGUMENTS\n" (:args t)))
                 ;; Reasoning is stripped: it is 96% of stored assistant text
                 ;; and is dropped from every prior turn on the way to the
                 ;; wire anyway. Reloading it here would undo that in one call.
                 (when-let [said (some-> (:assistant_text t)
                                         message/strip-think-blocks
                                         not-empty)]
                   (str "\n\nWHAT YOU SAID\n" said))
                 "\n\nRESULT\n" (:result t)))))))
