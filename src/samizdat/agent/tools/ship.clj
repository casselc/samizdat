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
            [samizdat.lexicon :as lexicon]
            [samizdat.security.verification-provider :as vprov]
            [samizdat.store.evaluator :as estore]
            [samizdat.store.journal :as journal]
            [samizdat.util :as util]
            [samizdat.session :as session]))



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
;; Both memoized against the wordlists' generation rather than realized at
;; namespace load: system/start! calls lexicon/reload! so a list edit takes
;; effect, and a top-level def turned that call into a no-op here.
(def ^:private stopwords
  (util/generation-cache lexicon/gen #(lexicon/wordlist :answer-framing)))

(defn- min-token
  "The shortest token the answer-evidence gate will hold against an answer.
  wordlists.edn `:claim-matching :answer-token-min-length`."
  []
  (lexicon/tuning :claim-matching :answer-token-min-length))

(def ^:private tool-version-re
  (util/generation-cache lexicon/gen
                         #(re-pattern (lexicon/wordlist :tool-version))))

;; The completeness rung's three lists (karamazov-g86), wordlists.edn data
;; like every vocabulary above.
(def ^:private completeness-forward
  (util/generation-cache lexicon/gen #(lexicon/wordlist :completeness-forward)))
(def ^:private completeness-work-verbs
  (util/generation-cache lexicon/gen #(lexicon/wordlist :completeness-work-verbs)))
(def ^:private completeness-second-person
  (util/generation-cache lexicon/gen
                         #(lexicon/wordlist :completeness-second-person)))

(defn unfinished-claim?
  "Whether the answer says, in the model's own first-person voice, that work
  remains (dirge completeness_gate.rs, karamazov-g86). Fires only when ONE
  SENTENCE holds all three: a first-person forward marker, a concrete work
  verb (exact token, so 'latest' cannot read as 'test'), and no
  second-person address — advice to the reader is a legitimate ending, a
  plan to keep working is not. The conjunction IS the control: a run that
  edits real files, verifies, claims nothing false, and stops halfway is the
  most ordinary bad ending an autonomous run has, and this is its one
  lexical tell. The lists are wordlists.edn data; dirge's warning against
  widening them travels with the lists."
  [answer]
  (let [fwd (completeness-forward)
        verbs (completeness-work-verbs)
        second-p (completeness-second-person)]
    (boolean
     (some (fn [sentence]
             (let [s (str " " (str/lower-case (str/trim sentence)) " ")
                   tokens (set (re-seq #"[a-z']+" s))]
               (and (some #(str/includes? s %) fwd)
                    (some tokens verbs)
                    (not-any? #(str/includes? s %) second-p))))
           (str/split (str answer) #"[.!?\n]+")))))

(defn answer-tokens
  "Substantive tokens from a proposed answer: numbers and words that are not
  stopwords. Numbers matter most — an answer naming a size, a bound, or a
  witness has to have that number in the evidence."
  [text]
  (->> (str/split (str/lower-case (str/replace (or text "") (tool-version-re) " "))
                  #"[^a-z0-9_.-]+")
       ;; `.` and `-` stay INSIDE the split class so 3.5 and cross-check survive
       ;; as one token, which means a sentence-final period rides along with the
       ;; last word. Trim the edges, keep the interior.
       (map #(str/replace % #"^[.-]+|[.-]+$" ""))
       (remove str/blank?)
       (remove (stopwords))
       ;; A hyphenated compound is one token, so `engine-confirmed` survived a
       ;; list holding every one of its parts. A compound with a substantive
       ;; half — `optimal-flow` — is not exempt.
       (remove #(and (str/includes? % "-")
                     (let [parts (remove str/blank? (str/split % #"-"))]
                       (and (seq parts)
                            (every? (fn [p] (or ((stopwords) p)
                                                (< (count p) (min-token))))
                                    parts)))))
       (filter #(or (re-matches #"[0-9]+(\.[0-9]+)?" %) (>= (count %) (min-token))))
       distinct))

(def ^:private word-suffixes
  "Stripped longest-first, one only. Enough to see that `enumeration` and
  `enumerating` are the same word, which raw substring matching cannot."
  ["ations" "ation" "ising" "izing" "ings" "ing" "ions" "ion" "ies" "ied"
   "es" "ed" "s"])

(defn- stem
  "The token with one morphological suffix removed, or nil.

  Never below the lexicon's `:answer-suffix-min-stem`, so nothing is
  shortened into a prefix that matches everything."
  [w]
  (some (fn [suf]
          (when (and (str/ends-with? w suf)
                     (>= (- (count w) (count suf))
                         (lexicon/tuning :claim-matching :answer-suffix-min-stem)))
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
        (let [long-enough (lexicon/tuning :claim-matching :answer-prefix-token-length)
              prefix (lexicon/tuning :claim-matching :answer-prefix-match-length)]
          (and (>= (count token) long-enough)
               (str/includes? word-text (subs token 0 prefix)))))))

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

;; --- the ship rungs as data (drg-4026 #44) -----------------------------------
;;
;; The model-free lexical rungs live in gates.edn :ship-gates and are
;; compiled HERE at load into predicate/message closures — the same pattern
;; as the steer gates (tier 3): the forms see the evidence keys as plain
;; locals, fns resolve in this namespace at compile, and the VALUES arrive
;; at fire time. Adding a rung is a data edit.

(defn- compile-rung-form
  "`*ns*` bound for the same reason as gates/compile-form: the rungs are now
  compiled on first use rather than at namespace load, and `str/blank?` and
  `engages-problem?` resolve in this namespace and nowhere the caller might
  happen to be."
  [form]
  (binding [*ns* (the-ns 'samizdat.agent.tools.ship)]
    (eval `(fn [~'ctx]
             (let [~'answer            (get ~'ctx :answer)
                   ~'problem           (get ~'ctx :problem)
                   ~'evidence          (get ~'ctx :evidence)
                   ~'uncovered-numbers (get ~'ctx :uncovered-numbers)]
               ~form)))))

(def ship-gates
  "The lexical ship rungs, compiled from gates.edn :ship-gates.

  A function, memoized against the config generation, so adding or retuning a
  rung is the data edit the docstring in gates.edn promises. As a top-level
  def the rungs were compiled once at namespace load and `reload-config!`
  could not touch them."
  (util/generation-cache
   gates/gen
   #(mapv (fn [rung]
            (assoc rung
                   :when (compile-rung-form (:when rung))
                   :message (if (:message-form rung)
                              (compile-rung-form (:message-form rung))
                              (:message rung))))
          (gates/threshold :ship-gates))))

(defn ship-gate-block
  "The first lexical ship rung that fires on this evidence, or nil. Pure —
  `done` computes the evidence and calls this."
  [evidence]
  (some (fn [rung]
          (when ((:when rung) evidence)
            (let [m (:message rung)]
              (if (fn? m) (m evidence) m))))
        (ship-gates)))

;; --- shipping ---------------------------------------------------------------

(defmethod base/run-tool "done" [{:keys [branch] :as ctx}]
  ;; Slimmed from the proof harness's seven-rung ship gate: the audit, review
  ;; and LLM-relevance rungs left with the judge machinery. What remains is
  ;; every rung that runs with no model in the path — an answer must exist,
  ;; its figures must come from the evidence, and it must engage the problem
  ;; — now data-defined (gates.edn :ship-gates, drg-4026 #44). The coding
  ;; loop's ship gate (tests pass, review passed) rebuilds on this seam.
  (let [answer (base/arg ctx :answer)
        ;; An ADVISORY branch (a reviewer or supervisor role loop) delivers a
        ;; VERDICT through done, not shippable work: it quotes the run's own
        ;; figures ("19 tests, 7 failed") and, on a red tree, describes the
        ;; redness it is reporting. The figure rung demanded artifacts for
        ;; those numbers and the verify rung demanded the very green the
        ;; verdict may be saying is absent, so every advisory role ground out
        ;; its whole budget unable to say what it had concluded, and the
        ;; caller read an exhaustion fallback instead of the verdict
        ;; (karamazov-t86 — observed on every supervisor branch of three
        ;; consecutive live runs). run-role marks the branch.
        advisory? (boolean (:advisory? branch))
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
         block (when-not advisory?
                 (ship-gate-block
                  {:answer answer :problem problem
                   :evidence evidence
                   :uncovered-numbers uncovered-numbers}))
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
        verify-on? (and (not advisory?)
                        (nil? block)
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
    ;; Journalled whether the tests RAN or not. A rung that was configured on
    ;; and then did nothing used to leave no trace at all — the note fired only
    ;; when there was a result — so a run that shipped unverified looked
    ;; identical in the record to one that shipped green. `:ran false` with a
    ;; reason is the difference between a gate that passed and a gate that was
    ;; never asked.
    ;; The live tally, so a supervisor can see the gate being skipped WHILE it
    ;; is happening rather than by reading the journal afterwards.
    (when verify-on?
      (session/observe! (if vresult
                          [:verify (if (:green? vresult) :green :red)]
                          [:verify :skipped]))
      (when vresult (session/observe! [:verify :ran])))
    (when (and verify-on? (:conn ctx) (:run-id ctx))
      (journal/note! (:conn ctx) (:run-id ctx) :ship-verify
                     {:branch-id (:id branch) :turn (:turn ctx)
                      :data (if vresult
                              {:ran true
                               :green (:green? vresult) :timeout (:timeout? vresult)
                               :blocked (some? verify-block)}
                              {:ran false
                               :blocked (some? verify-block)
                               ;; A keyword, not a sentence: this is a journal
                               ;; row somebody queries, and a stable token is
                               ;; worth more to whoever is counting than prose
                               ;; that reads nicely once.
                               :why (cond
                                      (nil? changed) :no-git-baseline
                                      (empty? changed) :nothing-changed
                                      (nil? cmd) :no-test-among-changed
                                      :else :pre-checks-decided)})}))
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
      (cond->
       {:branch (assoc branch :final-answer answer :status :done)
        :category :success
        :progress? true
        :done? true
        :answer answer
        ;; The green point the safe-state rung rewinds to (loop.clj tool-step
        ;; consumes this): done with the suite actually passing is the branch's
        ;; last known-good state.
        :verified-green? (boolean (:green? vresult))
        :result (str "Answer accepted.\n\n" answer)}

        ;; A green ship-verify IS this loop's confirmed artifact.
        ;;
        ;; The proof engines produced :confirmed artifacts and left with the
        ;; proof harness, and nothing replaced them — so `confirmed-artifacts`
        ;; was empty on every run, by construction, and everything keyed on it
        ;; was dead code that still read like live policy: the milestone,
        ;; branch-out and emergency-review gates could not fire, `shareable?`
        ;; admitted nothing so the shared pool stayed empty (and with it
        ;; corroborating-artifacts and seed-from-run!), the figure-coverage
        ;; ship rung had no evidence to check figures against, two of the
        ;; winner rubric's five components were always 0, and arbiter's
        ;; `progressed?` — which is how prologue-cap and progress-stalled
        ;; settle — could never be true.
        ;;
        ;; A test run the harness itself spawned and observed exit 0 is the
        ;; coding loop's exact analogue of an engine confirmation: machine-
        ;; checked, not self-reported, and produced by the same
        ;; verify-before-you-ship discipline. So emit one.
        ;;
        ;; :tier :slow because a real test run is not a one-shot syntax check
        ;; — it is the cross-check the rubric's slow-seen component means.
        (:green? vresult)
        (assoc :artifact {:kind :test
                          :claim answer
                          :code cmd
                          :verdict :pass
                          :claim-status :confirmed
                          :tier :slow})))))

;; --- the bounded lane's done: a ControlEvent the controller verifies (M2) -----
;;
;; In the bounded lane the model cannot run anything — its whole vocabulary is
;; eval/doc/complete/done over the trusted evaluator surface — so the ordinary
;; ship gate's `sh -c` verify is unreachable BY DESIGN, and done arrives as a
;; completion REQUEST (tools.clj routes it here before the ordinary method).
;; What settles it is the controller's own verification inside the M2
;; verify environment the trusted controller SELECTED
;; (samizdat.security.verification-provider — the bwrap
;; VerificationEnvironment over a private copy, or the SmolVM ephemeral
;; machine ported from bbagent's measured substrate): the changed paths
;; come from the binding's tamper-evident edit receipts (never from a
;; model-supplied command), the argv is the controller's PINNED verifier
;; authority plus the one derived focused expression, and the run happens
;; inside the selected fail-closed environment — no network, no host
;; secrets/config, no write to the authoritative tree, bounded
;; output/time, cleanup and reaping. Only a green run is terminal; every
;; other outcome is bounded evidence handed back so the branch keeps
;; iterating — and when the selected environment's substrate is
;; unavailable, the whole thing REFUSES rather than degrading to another
;; provider or a host spawn.

(defn- edited-paths
  "Every path THIS binding changed through project/edit, in first-write order
  — the controller's own record of what the run changed, read from the
  evaluator's append-only receipts rather than from anything the model claims
  or from git (a bounded root need not be a repo). Only :done-phase edit
  receipts count: a refused or errored edit wrote nothing. A missing conn or
  binding id reads as 'nothing changed' — the gate fails closed."
  [conn binding]
  (if-let [binding-id (and conn (:binding/id binding))]
    (into [] (comp (mapcat :receipts)
                   (filter #(and (= :project/edit (:op %))
                                 (= :done (:phase %))))
                   (map #(first (:args %)))
                   (distinct))
          (estore/history conn binding-id))
    []))

(defn bounded-done
  "The bounded lane's `done`: verify, then terminate — and only GREEN
  terminates.

  The controller — not the model — decides WHAT runs and WHERE: the run's
  edited paths from its own receipts, the verifier argv from the SELECTED
  provider's PINNED authority over the focused derivation (never gates.edn —
  that file is runtime-mutable by the very tier this gate judges), and the
  whole run inside the controller-owned verify environment selected by
  trusted controller policy (security.verification-provider — bwrap's
  private-copy sandbox or the SmolVM ephemeral machine, never a host spawn):
  no network, no host secrets, no write to the authoritative tree, bounded
  output/time, and cleanup/reaping however the run ends. The model's only
  influence on any of it is which files it chose to write through the
  anchored edit path; a file NAME crafted to inject a command yields no
  namespace (ns-from-test-path's whitelist), so it can shrink the argv
  toward empty — where the gate refuses — and can never widen it into a
  command.

  RED is not terminal: the branch gets the bounded tail of the failure and
  keeps iterating. Neither is 'nothing to run': a hollow done (no edits) and
  a change with no verifiable test both refuse with evidence. And when the
  selected environment's substrate is unavailable, the done REFUSES with the
  reason — there is no fallback to another provider or to a direct host
  spawn, and no trust-on-unknown clause here at all: the ordinary lane's
  :verify-unknown policy exists for a loop whose git might genuinely be
  unable to tell, which a bounded receipt log never is."
  [{:keys [branch] :as ctx}]
  (let [answer (some-> (base/arg ctx :answer) str str/trim not-empty)
        changed (edited-paths (:conn ctx) (base/bounded-binding ctx))
        env-ok? (vprov/available?)
        argv (vprov/focused-argv changed)
        vresult (when (and env-ok? argv)
                  (vprov/run (:root ctx) changed
                             (get-in ctx [:config :run :verify-timeout-ms])))
        unavailable-reason (cond
                             (not env-ok?) (vprov/unavailable-reason)
                             (:unavailable? vresult) (:reason vresult :unknown))
        block (cond
                (empty? changed)
                (base/bounded-message {:done-nothing-changed true})

                (nil? argv)
                (base/bounded-message {:done-no-verifiable-test true})

                ;; Fail closed on the substrate BEFORE any red/green reading:
                ;; an unavailable sandbox is not a failing test, and it is
                ;; never licence to spawn on the host instead.
                unavailable-reason
                (base/bounded-message {:done-verify-env-unavailable true
                                       :reason unavailable-reason})

                ;; vresult is present from here on, so verify-block's
                ;; red / timeout / green clauses decide and its
                ;; trust-on-unknown fallthrough is unreachable.
                :else
                (verify/verify-block {:verify-on? true :result vresult
                                      :changed changed :require-test? true}))]
    ;; The same record the ordinary ship gate keeps: a gate that was configured
    ;; on and did nothing must not read identically to one that ran green.
    ;; An environment that refused (unavailable) never counts as a run.
    (let [ran? (boolean (and vresult (not (:unavailable? vresult))))]
      (session/observe! (if ran?
                          [:verify (if (:green? vresult) :green :red)]
                          [:verify :skipped]))
      (when ran? (session/observe! [:verify :ran]))
      (when (and (:conn ctx) (:run-id ctx))
        (journal/note! (:conn ctx) (:run-id ctx) :ship-verify
                       {:branch-id (:id branch) :turn (:turn ctx)
                        :data (if ran?
                                (let [envelope (vprov/verify-envelope vresult)]
                                  (cond-> {:ran true
                                           :green (:green? vresult) :timeout (:timeout? vresult)
                                           :blocked (some? block)
                                           ;; WHICH environment produced the verdict: the
                                           ;; selected provider's policy coordinate, not prose.
                                           :verify-env (vprov/coordinate)}
                                    ;; The run envelope itself (RFC-012): the
                                    ;; attribution, input coordinate,
                                    ;; invocation index, duration and capture
                                    ;; of the execution that produced the
                                    ;; verdict — present exactly when a real
                                    ;; spawn happened; a failed staging has
                                    ;; no spawn and so no envelope.
                                    envelope (assoc :envelope envelope)))
                                (cond-> {:ran false
                                         :blocked (some? block)
                                         ;; A keyword, not a sentence, like the ordinary
                                         ;; lane's: this row is queried.
                                         :why (cond
                                                (empty? changed) :nothing-changed
                                                (nil? argv) :no-test-among-changed
                                                :else :verify-env-unavailable)}
                                  ;; The catalogued refusal when the
                                  ;; environment answered the request with
                                  ;; one — the same envelope shape a second
                                  ;; repository renders, beside the stable
                                  ;; :why token.
                                  (:refusal vresult) (assoc :refusal
                                                            (:refusal vresult))))})))
    (if block
      (base/fail branch (str "`done` refused.\n\n" block)
                 :control-event :done :done-block block)
      (let [final (or answer (base/bounded-message {:done-green true}))]
        {:branch (assoc branch :final-answer final :status :done)
         :category :success
         :progress? true
         :done? true
         :control-event :done
         ;; The green point the safe-state rung rewinds to, exactly as in the
         ;; ordinary lane.
         :verified-green? true
         :answer final
         :result (str "Answer accepted.\n\n" final)
         ;; A green controller verification IS the bounded lane's confirmed
         ;; artifact — machine-checked, not self-reported, the same reasoning
         ;; as the ordinary lane's green ship-verify artifact. :code is the
         ;; derived verifier argv itself: the exact thing that ran, pinned
         ;; prefix and all.
         :artifact {:kind :test
                    :claim final
                    :code (pr-str argv)
                    :verdict :pass
                    :claim-status :confirmed
                    :tier :slow}}))))

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
