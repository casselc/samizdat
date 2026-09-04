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

(ns samizdat.decide
  "Scoring an AUTHORIZED, CLOSED decision domain, as mechanism.

      trusted state -> authorized DecisionDomain -> scoring
                    -> trusted selection -> journal

  A model ranks options trusted code authorized. It never names an option,
  never widens the set, and never emits text that is acted on.

  THREE THINGS THIS NAMESPACE FAILS CLOSED ON, each learned from review:

  1. A DOMAIN IS NOT A VOCABULARY. `decide` takes a DecisionDomain that
     something trusted already authorized against the current state. There is
     no path from a bare list of actions to a decision, because a default of
     `(constantly true)` turns \"nobody supplied a rule\" into \"everything is
     permitted\" -- silently, and in the one direction that matters.

  2. EVIDENCE MUST BE COMPLETE. Every authorized candidate needs exactly one
     finite score. A scorer that answers for one option out of five used to
     collapse the domain to that option and return a confident :act; now it
     defers with :reason/incomplete-scores. Partial evidence is not weak
     evidence, it is absent evidence about the options that went unscored.

  3. THE AUDIT KEEPS WHAT WAS OFFERED, not what survived. An unscored candidate
     stays in the record with :scoring-status, because the question an auditor
     asks is \"what did trusted code permit, what evidence arrived for each,
     and why did policy act or defer\" -- not \"what came back from rank\".

  MECHANISM ONLY, per AGENTS.md. Nothing here decides WHEN to score, WHICH
  actions exist, what is legal, or what the thresholds are. All four arrive as
  arguments; the vocabulary and the legality rules live in resources/.

  Deliberately NOT a samizdat.llm.adapter/Adapter. That protocol is chat-url,
  auth-headers, chat-body, parse-chat, prefill -- generation over HTTP, carrying
  the retry and timeout discipline generation needs. Ranking a closed set asks a
  different question and needs none of it.

  THE SCORER SEAM is a plain function, injected:

      (fn [context candidates] -> {:scores {id number} :meta {...}})

  A function rather than a protocol because there is one method. Injected
  rather than required so nothing here loads a native library: samizdat must
  not need an inference engine to run its own test suite, and every branch
  below is reachable with a scorer that returns a literal map.")

;; ------------------------------------------------------- decision domain

(defn all-legal
  "An explicit legality source that permits every action.

  A NAMED fixture, not a default. The distinction is the whole point of #7: a
  caller that means \"this domain is entirely legal in this state\" can say so
  and the record shows it said so, while a caller that simply forgot supplies
  nothing and is refused. The two used to be indistinguishable."
  []
  {:legality/source :all-legal
   :legality/revision "fixture"
   :legality/pred (fn [_candidate] true)})

(defn legality
  "A trusted legality source: a predicate plus the provenance to audit it.

  `source` names the authority (a policy id, a gates key, a rule namespace) and
  `revision` pins which version of it ran, so a decision can be re-derived later
  against the rule that actually applied rather than today's."
  [source revision pred]
  {:legality/source source
   :legality/revision revision
   :legality/pred pred})

(defn authorize
  "Project a vocabulary through a trusted legality source into a DecisionDomain.

  This is the only way to obtain something `decide` will act on. Returns

    {:domain/id :domain/revision :domain/state-coord :domain/authority
     :domain/legality-source :domain/legality-revision
     :domain/candidates [...] :domain/rejected [...]}

  `:domain/rejected` is kept because an action trusted policy REFUSED is part of
  the decision's evidence: an auditor asking why the model never considered
  :rollback deserves a better answer than its absence.

  The candidates that survive are the only ones a scorer ever sees, which is
  what makes \"the model cannot widen authority\" a structural property rather
  than a convention.

  `:based-on` is the coordinate the domain was derived FROM — run, branch,
  turn, manifest, graph revision and the ledger's state version at the
  boundary — and `:policy-revision` the policy that will select over it. Both
  are carried verbatim onto the domain so a later revalidation (`revalidate`)
  and a later reader can tell a domain derived from THIS state from one that
  merely looks like it. Neither is required to authorize: a caller with no
  coordinate gets a domain that cannot be revalidated as fresh, which is the
  honest reading of a domain of unknown origin."
  [vocabulary {:keys [legality id revision state-coord authority based-on policy-revision]}]
  (let [pred (:legality/pred legality)
        vocab (vec vocabulary)
        keep? (fn [c] (boolean (pred c)))]
    (cond-> {:domain/id                id
             :domain/revision          revision
             :domain/state-coord       state-coord
             :domain/authority         authority
             :domain/legality-source   (:legality/source legality)
             :domain/legality-revision (:legality/revision legality)
             :domain/candidates        (filterv keep? vocab)
             ;; the id AND the reason, so an auditor sees why an action was
             ;; refused rather than only that it was
             :domain/rejected          (mapv :id (remove keep? vocab))
             :domain/rejected-with-reason (mapv (fn [c] {:id (:id c)
                                                         :reason (or (:rejection-reason c)
                                                                     :rejected/not-legal)})
                                                (remove keep? vocab))}
      based-on        (assoc :domain/based-on based-on)
      policy-revision (assoc :domain/policy-revision policy-revision))))

(def ops
  "The closed operation vocabulary a candidate's `:op` may name (ADR-002 §1).
  Gate names, cells, tasks and seeds are TARGETS of an op, never ops. A
  candidate that names an op outside this set is refused by `domain-problem`;
  a candidate with no `:op` is a bare id, which earlier vocabularies used and
  which stays valid."
  #{:continue :steer :block :complete :cull :spare :branch :escalate :defer})

(defn domain-problem
  "Why this DecisionDomain may not be scored, or nil.

  A keyword, never a boolean: the refusal is journalled, and \"illegal\" without
  a cause cannot be audited or learned from."
  [domain {:keys [max-candidates] :or {max-candidates 32}}]
  (let [cands (:domain/candidates domain)]
    (cond
      (not (map? domain))                      :domain/not-authorized
      (nil? (:domain/legality-source domain))  :domain/no-legality-source
      (not (sequential? cands))                :domain/not-authorized
      (empty? cands)                           :domain/empty
      (< max-candidates (count cands))         :domain/too-large
      (not (every? map? cands))                :domain/not-maps
      (not (every? :id cands))                 :domain/missing-id
      (not= (count cands)
            (count (distinct (map :id cands)))) :domain/duplicate-id
      ;; an :op outside the closed vocabulary is a candidate naming an
      ;; operation trusted code never defined; a candidate with no :op is a
      ;; bare id and stays valid
      (some #(and (contains? % :op) (not (contains? ops (:op %)))) cands)
      :domain/unknown-op
      :else nil)))

;; ---------------------------------------------------------- score contract

(defn- finite-number?
  "A usable score. Rejects nil, strings, NaN and both infinities.

  NaN needs the self-comparison because every ordinary comparison against it is
  false, which is exactly how it slips past a range check and then makes a sort
  order meaningless. Infinities are rejected because they make every margin
  either infinite or NaN, so the guard that is supposed to force a deferral
  would instead wave the decision through."
  [x]
  (and (number? x)
       (let [d (double x)]
         (and (== d d)                                  ; not NaN
              (not (Double/isInfinite d))))))

(defn score-problem
  "Why this scorer result may not be used, or nil. Never throws.

  Returns {:reason kw :missing [...] :invalid [...] :extra [...]} so the record
  names WHICH candidates broke the contract, not merely that something did.
  Three distinct reasons because they are three distinct failures and an
  auditor -- or a later training pipeline -- needs to tell them apart:

    :reason/no-scores          nothing usable came back at all
    :reason/incomplete-scores  a candidate the domain authorized went unscored
    :reason/invalid-scores     a score was not a finite number, or the scorer
                               answered for an id the domain never offered

  Extra ids FAIL CLOSED rather than being ignored. A scorer answering about
  options that were never authorized is not a scorer that can be trusted about
  the ones that were -- most likely it is bound to a stale domain, which is the
  case where quietly proceeding is worst.

  A result that names the domain it evaluated (`:evaluated-domain-id`, ADR-002
  §2) and names a DIFFERENT one is refused as :reason/stale-domain before
  anything is read from it: the scorer answered about another state. A result
  that names no domain is accepted as before; the record then says nothing
  about which domain the evidence was for, which is the older, weaker contract."
  [domain result]
  (let [ids (map :id (:domain/candidates domain))
        scores (:scores result)]
    (cond
      (not (map? result)) {:reason :reason/no-scores}
      (and (contains? result :evaluated-domain-id)
           (not= (:evaluated-domain-id result) (:domain/id domain)))
      {:reason :reason/stale-domain
       :evaluated-domain-id (:evaluated-domain-id result)
       :domain-id (:domain/id domain)}
      (not (map? scores)) {:reason :reason/no-scores}
      (empty? scores)     {:reason :reason/no-scores}
      :else
      ;; An explicit nil counts as MISSING, not invalid. The scorer answered
      ;; with nothing, which is the same evidential state as not answering; a
      ;; string or a NaN is a corrupt answer. A later training pipeline needs
      ;; to tell absent evidence from bad evidence, so the two do not merge.
      (let [answered? (fn [id] (and (contains? scores id)
                                    (some? (get scores id))))
            missing (vec (remove answered? ids))
            invalid (vec (remove #(finite-number? (get scores %))
                                 (filter answered? ids)))
            extra   (vec (remove (set ids) (keys scores)))]
        (cond
          (seq invalid) {:reason :reason/invalid-scores
                         :invalid invalid :missing missing :extra extra}
          (seq extra)   {:reason :reason/invalid-scores
                         :invalid [] :missing missing :extra extra}
          (seq missing) {:reason :reason/incomplete-scores
                         :missing missing :invalid [] :extra []}
          :else nil)))))

;; ------------------------------------------------------------- selection

(defn margin
  "Gap between the best and second-best score, or nil for a single candidate.

  The number the deferral policy is written against: how much better the winner
  is, not how good it is. An absolute score says nothing useful on its own,
  because it moves with prompt length and with the model."
  [ranked]
  (when (< 1 (count ranked))
    (- (double (:score (first ranked))) (double (:score (second ranked))))))

(defn comparable?
  "Whether these candidates' scores may be compared to each other.

  Carried from the jolt-llama exactness measurement, not assumed. A candidate's
  first token is read from the base distribution and any later tokens by
  single-token decodes; on a hybrid model those are different kernel paths, so
  candidates of different token lengths have scores built from different
  mixtures. Equal-length -- and single-token in particular -- is exactly
  comparable, and a controller's action vocabulary is naturally that shape."
  [candidates]
  (let [lens (distinct (map (comp count :tokens) candidates))]
    (and (= 1 (count lens)) (some? (first lens)) (pos? (first lens)))))

(defn rank
  "Order candidates by score, best first, attaching :rank.

  Only ever maps over the candidates the DOMAIN authorized, which is what makes
  it structurally impossible for a scorer to introduce an option. Ties break by
  :id so the ordering is total: an unstable order would make the journal
  disagree with itself across replays of the same run."
  [candidates scores]
  (->> candidates
       (map (fn [c] (assoc c :score (get scores (:id c))
                           :n-tokens (some-> (:tokens c) count))))
       (sort-by (juxt (comp - double :score) (comp str :id)))
       (map-indexed (fn [i c] (assoc c :rank i)))
       vec))

(defn select
  "Trusted selection over scored candidates. THE MODEL DOES NOT DECIDE HERE.

  Deferral is the default when evidence is weak, because this is a controller:
  doing nothing is a legal outcome and usually a safe one, whereas acting on a
  coin flip is neither.

  `min-margin` is not defaulted to anything meaningful on purpose. A policy
  number living in mechanism is what AGENTS.md forbids, and a silent default
  would be exactly that with extra steps."
  [ranked {:keys [min-margin require-comparable?]
           :or   {require-comparable? true}}]
  (let [m (margin ranked)]
    (cond
      (empty? ranked)
      {:decision :defer :reason :reason/no-scores :margin nil :selected nil}

      (and require-comparable? (not (comparable? ranked)))
      {:decision :defer :reason :reason/not-comparable :margin m
       :selected nil :would-have-selected (:id (first ranked))}

      (and min-margin m (< m (double min-margin)))
      {:decision :defer :reason :reason/below-margin :margin m
       :selected nil :would-have-selected (:id (first ranked))}

      :else
      {:decision :act :reason :reason/clear-winner :margin m
       :selected (:id (first ranked))})))

;; ------------------------------------------------ evidence summaries (ADR-002 §2)

(defn entropy
  "Shannon entropy, in nats, of the distribution the scores induce over the
  ranked candidates: scores are log-probabilities (or any log-space
  evidence), softmaxed with the max subtracted for stability. nil for fewer
  than two candidates.

  Recorded beside the margin because they answer different questions: the
  margin is how far ahead the winner is, the entropy is how concentrated the
  whole distribution is. A margin can be wide with the mass spread over the
  losers, and that shape is what a later calibration reads."
  [ranked]
  (when (< 1 (count ranked))
    (let [xs (map (comp double :score) ranked)
          m (reduce max xs)
          ws (map #(Math/exp (- % m)) xs)
          z (reduce + ws)]
      (- (reduce + (map (fn [w] (let [p (/ w z)] (if (pos? p) (* p (Math/log p)) 0.0))) ws))))))

;; ------------------------------------------------ revalidation (ADR-002 §3)

(defn revalidate
  "Re-derive freshness against the CURRENT state immediately before apply.

  A model selection is not a committed transition (ADR-001 invariant 4). The
  kernel checks, at apply time, that the state the domain was derived from is
  still the state, that the authority is unchanged, that the budget still
  admits the action and that no invariant is violated; anything else demotes
  an :act to a :defer with a reason and keeps what would have been applied.

  `now` is what the caller knows at apply time:

    {:state/version   the ledger's current state version
     :authority       the current authority, compared with the domain's
     :budget-ok?      whether the budget still admits the selection (default true)
     :invariants-ok?  whether the invariants hold (default true)}

  The domain's `:domain/based-on :state/version` is the version the domain was
  derived at. A domain with no such version cannot be shown fresh and is
  treated as :stale-revision — a domain of unknown origin is not applied."
  [outcome domain {:keys [budget-ok? invariants-ok?] :or {budget-ok? true invariants-ok? true} :as now}]
  (let [derived (get-in domain [:domain/based-on :state/version])
        version (:state/version now)
        verdict (cond
                  (or (nil? derived) (nil? version) (not= derived version)) :stale-revision
                  (and (contains? now :authority)
                       (not= (:authority now) (:domain/authority domain)))    :authority-changed
                  (not budget-ok?)                                            :budget-exceeded
                  (not invariants-ok?)                                        :invariant-violated
                  :else                                                       :fresh)
        reason {:stale-revision :reason/stale-revision
                :authority-changed :reason/authority-changed
                :budget-exceeded :reason/budget-exceeded
                :invariant-violated :reason/invariant-violated}
        out (assoc outcome :revalidated? true
                   :revalidation {:state/version version :derived-at derived :outcome verdict})]
    (if (and (= :act (:decision outcome)) (not= :fresh verdict))
      (assoc out :decision :defer
             :reason (get reason verdict)
             :would-have-selected (:selected outcome)
             :selected nil)
      out)))

;; ------------------------------------------------ model state (ADR-002 §4)

(def ^:private model-state-ref-required
  [:model-state/id :model/coordinate :model/revision :model/representation
   :runtime/backend :runtime/revision :training-abi/version
   :graph/revision :state/version :prefix-token-hash :prefix-token-count
   :native-state/hash :native-state/bytes :blob/ref])

(defn model-state-ref-problem
  "Why a ModelStateRef may not be recorded, or nil.

  The ledger holds identity, provenance, the prefix hash and the content hash
  of a native state; the bytes live outside it (ADR-001 invariant 5). So the
  ref must carry every identifying field and must NOT carry the state itself:
  a ref with a :state, :blob, :bytes or :handle key is the blob wearing a ref's
  name, and is refused."
  [ref]
  (cond
    (not (map? ref)) :model-state/not-a-map
    (some #(not (contains? ref %)) model-state-ref-required)
    :model-state/missing-field
    (some #(contains? ref %) [:state :blob :bytes :handle :ptr :pointer :logits :tokens])
    :model-state/carries-state
    (not (integer? (:prefix-token-count ref))) :model-state/bad-token-count
    (not (integer? (:native-state/bytes ref))) :model-state/bad-byte-count
    (not (string? (:prefix-token-hash ref)))   :model-state/bad-prefix-hash
    (not (string? (:native-state/hash ref)))   :model-state/bad-state-hash
    :else nil))

;; ------------------------------------------------------------ provenance

(def ^:private provenance-keys
  "The ONLY keys allowed into a journalled decision's provenance.

  An allowlist rather than a denylist. Provenance is assembled from a scorer's
  metadata and a caller's context, both of which will grow keys nobody here
  anticipated, and an append-only journal has no second chance -- so the safe
  direction is to drop what is not recognised rather than to trust every future
  contributor to have read this."
  #{;; where in the run
    :decision-id :run-id :branch-id :turn :state-coord
    ;; what was authorized, by whom, under which rule
    :domain-id :domain-revision :authority :legality-source :legality-revision
    ;; the policy that decided
    :policy-revision :min-margin :require-comparable?
    ;; who scored, with what
    :scorer-id :convention :homogeneous?
    ;; the model as an ARTIFACT, not a description
    :model-id :model-sha256 :model-repo :model-revision :model-file
    :tokenizer-family
    ;; the native state the scorer restored (ADR-002 §2), by id only —
    ;; never the state
    :model-state-id
    ;; the runtime under it
    :jolt-llama-sha :llama-cpp-sha :native-abi
    ;; observation, when it happens to be available
    :latency-ms :inference-epoch :trace-id :span-id})

(def ^:private scorer-meta-keys
  "Scorer-supplied metadata that may reach the journal.

  Narrower than provenance-keys: a scorer is the least trusted contributor to
  the record, so it may only speak to how IT scored, never to what was
  authorized or which policy applied."
  #{:convention :homogeneous? :latency-ms :inference-epoch :trace-id :span-id
    :scorer-id :model-id :model-sha256 :model-repo :model-revision :model-file
    :tokenizer-family :jolt-llama-sha :llama-cpp-sha :native-abi
    :model-state-id})

(defn- scalar?
  "Whether a value is small and inert enough to journal.

  Bounds the provenance values as well as their keys. An allowlisted KEY
  carrying a 50 MB blob or a native handle would defeat the allowlist, so the
  value has to be a scalar and a string has to be short."
  [v]
  (or (nil? v)
      (boolean? v)
      (number? v)
      (keyword? v)
      (and (string? v) (<= (count v) 200))))

(defn provenance
  "Assemble a journal-safe provenance map from allowlisted parts.

  Scorer metadata goes through the narrower allowlist first, then the whole
  thing through the value check, so neither an unexpected key nor an
  unexpectedly large value survives."
  [ctx scorer-meta]
  (let [safe (fn [allow m]
               (into {} (for [[k v] m
                              :when (and (contains? allow k) (scalar? v))]
                          [k v])))]
    (merge (safe provenance-keys (or ctx {}))
           (safe scorer-meta-keys (or scorer-meta {})))))

;; ----------------------------------------------------------------- audit

(def ^:private redacted
  "Keys that must never reach the journal, checked rather than remembered.

  A native pointer is meaningless once the process exits and a liability while
  it runs; a state blob is tens of megabytes of KV cache; the raw context is the
  prompt, which is large, often duplicated, and not what a decision record is
  for."
  #{:handle :ptr :pointer :state :blob :logits :tokens :context :prompt
    :session :model :scorer :conn})

(defn leaks?
  "Which forbidden keys a record would carry into the journal, or nil.

  Exposed so a test can assert the property directly rather than eyeballing a
  sample, and so the cell can refuse to write rather than write and regret it."
  [record]
  (let [ks (atom #{})]
    (letfn [(walk [x]
              (cond
                (map? x) (doseq [[k v] x]
                           (when (and (keyword? k) (contains? redacted (keyword (name k))))
                             (swap! ks conj k))
                           (walk v))
                (sequential? x) (doseq [y x] (walk y))
                :else nil))]
      (walk record))
    (when (seq @ks) @ks)))

(defn- domain-entry
  "One candidate as the journal holds it: semantic id, evidence, and the STATUS
  of that evidence. Never the tokens themselves -- `:n-tokens` is the encoding
  fact worth keeping, the vector is not."
  [c status]
  {:id (:id c)
   :score (when (and (= :ok status) (:score c)) (double (:score c)))
   :rank (when (= :ok status) (:rank c))
   :n-tokens (or (:n-tokens c) (some-> (:tokens c) count))
   :scoring-status status})

(defn auditable
  "The journal-shaped record of one decision.

  Carries EVERY candidate trusted code authorized, including those whose score
  was missing or invalid, plus the ones policy rejected before scoring. That is
  the difference between answering \"what did trusted code permit and what
  evidence arrived\" and answering \"what survived rank\"."
  [{:keys [domain ranked outcome domain-check score-check prov]}]
  (let [offered (:domain/candidates domain)
        ent (entropy ranked)
        ranked-by-id (into {} (map (juxt :id identity) ranked))
        missing (set (:missing score-check))
        invalid (set (:invalid score-check))
        entries (mapv (fn [c]
                        (let [id (:id c)
                              status (cond (contains? invalid id) :invalid
                                           (contains? missing id) :missing
                                           (contains? ranked-by-id id) :ok
                                           :else :unscored)]
                          (domain-entry (or (ranked-by-id id) c) status)))
                      offered)]
    {:n-offered   (count offered)
     :n-scored    (count (filter #(= :ok (:scoring-status %)) entries))
     :domain      entries
     :rejected    (vec (:domain/rejected domain))
     :rejected-with-reason (vec (:domain/rejected-with-reason domain))
     ;; the coordinate the domain was derived from and the policy that
     ;; selected over it (ADR-002 §1); nil on a domain that carried none
     :based-on    (:domain/based-on domain)
     :policy-revision (:domain/policy-revision domain)
     ;; how concentrated the evidence was, beside how far ahead the winner
     :entropy     ent
     :domain-check (or domain-check :ok)
     :score-check  (when score-check
                     (cond-> {:reason (:reason score-check)
                              :missing (vec (:missing score-check))
                              :invalid (vec (:invalid score-check))
                              :extra (vec (:extra score-check))}
                       ;; which domain the scorer thought it was answering about
                       (contains? score-check :evaluated-domain-id)
                       (assoc :evaluated-domain-id (:evaluated-domain-id score-check)
                              :domain-id (:domain-id score-check))))
     :decision    (:decision outcome)
     :selected    (:selected outcome)
     :would-have-selected (:would-have-selected outcome)
     :reason      (:reason outcome)
     :margin      (when (:margin outcome) (double (:margin outcome)))
     :provenance  prov}))

(defn durable
  "The record as it should be WRITTEN, with qualified keywords preserved.

  clojure.data.json serialises a namespaced keyword as its NAME alone, so
  :reason/incomplete-scores lands in SQLite as \"incomplete-scores\" and
  :domain/no-legality-source as \"no-legality-source\". The qualifier is the
  half that says which vocabulary the value belongs to, and a replay-grade
  record should not quietly drop it -- today the bare names happen to be unique
  across :reason/* and :domain/*, which is luck rather than a property.

  Applied at the durability boundary only. In-process the record keeps real
  keywords, because that is what callers dispatch on."
  [record]
  (letfn [(conv [x]
            (cond
              (and (keyword? x) (namespace x)) (str (namespace x) "/" (name x))
              (map? x) (into {} (map (fn [[k v]] [k (conv v)]) x))
              (sequential? x) (mapv conv x)
              :else x))]
    (conv record)))

;; ------------------------------------------------------------------- run

(defn decide
  "One authorized closed-domain decision, end to end.

  Pure except for calling `scorer`. Never throws for a bad domain, a bad score
  map or a failing scorer: a controller that dies because its advisor died is
  worse than one that declines, so every failure becomes a recorded deferral
  with a reason and the full offered domain intact.

  Takes a DecisionDomain from `authorize`, not a bare candidate list. There is
  no arity that accepts a vocabulary."
  [{:keys [scorer domain policy context prov-ctx]}]
  (let [policy (or policy {})
        dp (domain-problem domain policy)]
    (if dp
      (auditable {:domain (if (map? domain) domain {}) :ranked []
                  :domain-check dp
                  :prov (provenance prov-ctx nil)
                  :outcome {:decision :defer :reason :reason/unauthorized-domain}})
      (let [cands (:domain/candidates domain)
            result (try (scorer context cands)
                        (catch Throwable e {::threw (or (ex-message e) (str e))}))]
        (if (::threw result)
          (auditable {:domain domain :ranked []
                      :prov (provenance prov-ctx nil)
                      :outcome {:decision :defer :reason :reason/scorer-failed}})
          (let [prov (provenance prov-ctx (:meta result))
                sp (score-problem domain result)]
            (if sp
              ;; Fail closed. The domain is preserved with per-candidate status
              ;; so the record shows exactly which evidence was missing or bad.
              (auditable {:domain domain :ranked [] :score-check sp :prov prov
                          :outcome {:decision :defer :reason (:reason sp)}})
              (let [ranked (rank cands (:scores result))
                    outcome (select ranked policy)]
                (auditable {:domain domain :ranked ranked :prov prov
                            :outcome outcome})))))))))

;; -------------------------------------------------- action encoding (#8)

(defn verify-encodings
  "Check that each action's model-facing encoding really is one distinct token.

  `tokenize` is injected -- (fn [text] -> [token-ids]) -- so this is testable
  with no model, and the canary passes the real tokenizer.

  Tokenizes the WHOLE encoding. The canary previously did (take 1 (tokenize ...)),
  which guaranteed n_tokens == 1 by truncation and therefore proved nothing
  about the encoding: it only proved a first token exists. If \" ROLLBACK\" is
  two tokens the answer is a different encoding, not a shorter read of this one.

  Returns {:ok? bool :encodings [...] :problems [...]}, never throws."
  [actions tokenize]
  (let [encoded (mapv (fn [{:keys [id text]}]
                        (let [toks (try (vec (tokenize text)) (catch Throwable _ nil))]
                          {:id id :text text :tokens toks
                           :n-tokens (count (or toks []))}))
                      actions)
        by-token (group-by #(first (:tokens %)) (filter #(= 1 (:n-tokens %)) encoded))
        problems (vec (concat
                       (for [e encoded :when (nil? (:tokens e))]
                         {:id (:id e) :problem :encoding/tokenize-failed})
                       (for [e encoded :when (and (:tokens e) (zero? (:n-tokens e)))]
                         {:id (:id e) :problem :encoding/empty})
                       (for [e encoded :when (< 1 (:n-tokens e))]
                         {:id (:id e) :problem :encoding/multi-token
                          :n-tokens (:n-tokens e)})
                       (for [[tok es] by-token :when (< 1 (count es))]
                         {:problem :encoding/aliased :token tok
                          :ids (mapv :id es)})))]
    {:ok? (empty? problems)
     :encodings encoded
     :problems problems}))
