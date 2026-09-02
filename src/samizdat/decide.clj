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
  "Scoring a CLOSED decision domain, as mechanism.

  The shape this exists to support:

      trusted state -> finite legal domain -> scoring -> trusted selection
                                                               -> journal

  A model ranks options that trusted code wrote down. It never names an option,
  never widens the set, and never emits text that is acted on. If the scorer
  returns nonsense, the worst it can do is misorder a list the caller already
  decided was entirely legal — which is the reason to prefer this shape over
  generation wherever a decision is genuinely closed.

  MECHANISM ONLY, per AGENTS.md. Nothing here decides WHEN to score, WHAT the
  domain is, or what the thresholds are. The domain arrives as an argument, the
  thresholds arrive as an argument, and the decision to call is the cell's.

  This is deliberately NOT a samizdat.llm.adapter/Adapter. That protocol is
  shaped for generation over HTTP — chat-url, auth-headers, chat-body,
  parse-chat, prefill — and carries the retry, timeout and think-block
  discipline that generation needs. Scoring a closed domain asks a different
  question and needs none of it. Making it an Adapter would mean inheriting a
  machine built for a problem this does not have.

  THE SCORER SEAM is a plain function, injected:

      (fn [context candidates] -> {:scores {id logprob} :meta {...}})

  A function rather than a protocol because there is one method. Injected
  rather than required so that nothing here loads a native library: samizdat
  must not acquire a hard runtime dependency on an inference engine to run its
  own test suite, and the pure functions below are the majority of the code.")

;; ---------------------------------------------------------------- domain

(defn legal-domain?
  "Whether `candidates` is a domain this code is willing to score.

  Returns nil when legal, or a keyword naming the first violation. A keyword
  rather than a boolean because the caller journals the refusal, and 'illegal'
  without a reason is not auditable."
  [candidates {:keys [max-candidates] :or {max-candidates 32}}]
  (cond
    (not (sequential? candidates))          :domain/not-a-sequence
    (empty? candidates)                     :domain/empty
    (< max-candidates (count candidates))   :domain/too-large
    (not (every? map? candidates))          :domain/not-maps
    (not (every? :id candidates))           :domain/missing-id
    (not= (count candidates)
          (count (distinct (map :id candidates)))) :domain/duplicate-id
    :else nil))

(defn comparable?
  "Whether these candidates' scores may be compared against each other.

  Carried over from the jolt-llama exactness work, where it was measured rather
  than assumed. Scoring a candidate reads the first token's log-probability from
  the base distribution and reaches any later tokens by single-token decodes.
  On a hybrid model those are different kernel paths, so candidates of DIFFERENT
  token lengths have scores built from different mixtures of them. The head of
  the distribution is stable, but a near-tie between a 1-token and a 4-token
  candidate is not evidence.

  Equal-length candidates — and the single-token case in particular — are
  exactly comparable. A controller's action vocabulary is naturally that shape,
  so this is a cheap invariant to hold rather than a real restriction."
  [candidates]
  (let [lens (distinct (map (comp count :tokens) candidates))]
    (and (= 1 (count lens)) (some? (first lens)))))

;; ------------------------------------------------------------- selection

(defn margin
  "Gap between the best and second-best score, or nil for a single candidate.

  This is the number the deferral policy is written against: how much better
  the winner is, not how good it is. An absolute score says nothing useful,
  because it moves with prompt length and model."
  [ranked]
  (when (< 1 (count ranked))
    (- (double (:score (first ranked))) (double (:score (second ranked))))))

(defn rank
  "Order candidates by score, best first, attaching :rank.

  Ties are broken by :id so the ordering is total and reproducible. An unstable
  order would make the journal disagree with itself across replays of the same
  run, which is worse than an arbitrary but fixed rule."
  [candidates scores]
  (->> candidates
       (map (fn [c] (assoc c :score (get scores (:id c))
                              :n-tokens (some-> (:tokens c) count))))
       (filter :score)
       (sort-by (juxt (comp - double :score) (comp str :id)))
       (map-indexed (fn [i c] (assoc c :rank i)))
       vec))

(defn select
  "Trusted selection over scored candidates. THE MODEL DOES NOT DECIDE HERE.

  Returns {:decision :act|:defer :selected id :margin d :reason kw ...}.

  Deferral is the default when the evidence is weak, because this is a
  controller: doing nothing is a legal outcome and usually a safe one, whereas
  acting on a coin flip is neither. Three ways to decline:

    :reason/below-margin       the top two are within `min-margin` of each other
    :reason/not-comparable     unequal token lengths, so the ordering is not
                               evidence at the precision the margin assumes
    :reason/no-scores          the scorer returned nothing usable

  `min-margin` comes from gates.edn via the cell. It is not defaulted to
  anything meaningful here on purpose: a policy number living in mechanism is
  exactly what AGENTS.md forbids, and a silent default would be that with extra
  steps."
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

;; --------------------------------------------------------------- audit

(def ^:private redacted
  "Keys that must never reach the journal, checked rather than remembered.

  A native pointer is meaningless once the process exits and is a liability
  while it runs; a state blob is tens of megabytes of KV cache per entry; the
  raw context is the prompt, which is large, often duplicated, and not what a
  decision record is for. The journal answers 'what was decided, among what, on
  what evidence' — not 'what did the machine look like'."
  #{:handle :ptr :pointer :state :blob :logits :tokens :context :prompt :session :model})

(defn auditable
  "The journal-shaped record of one decision.

  Everything a later reader needs to check the decision without re-running it:
  the domain that was offered, every score, the margin, what trusted policy did
  with them, and which model produced them. Nothing else.

  The scrub is a belt-and-braces pass over the candidate maps rather than a
  select-keys, because candidates are constructed by cells and a future cell
  will carry something new on them. A leak here is silent and permanent — it is
  an append-only journal — so the safe direction is to drop unknown large keys
  rather than to trust every future caller to have read this docstring."
  [{:keys [ranked outcome domain-check model-id n-offered]}]
  {:n-offered   n-offered
   :n-scored    (count ranked)
   :domain      (mapv (fn [c] (-> c
                                  (select-keys [:id :rank :score :n-tokens])
                                  (update :score #(when % (double %)))))
                      ranked)
   :domain-check (or domain-check :ok)
   :decision    (:decision outcome)
   :selected    (:selected outcome)
   :would-have-selected (:would-have-selected outcome)
   :reason      (:reason outcome)
   :margin      (when (:margin outcome) (double (:margin outcome)))
   ;; :model-id, not :model -- `redacted` claims :model, and a record that
   ;; tripped its own leak check would make the check useless. This is a
   ;; descriptive string ("qwen35 0.8B Q4_0"), never a handle.
   :model-id    model-id})

(defn leaks?
  "Whether a record carries anything the journal must not hold.

  Exposed so a test can assert the property directly rather than eyeballing a
  sample, and so the cell can refuse to write rather than write and regret it."
  [record]
  (let [ks (atom #{})]
    (letfn [(walk [x]
              (cond
                (map? x) (do (doseq [[k v] x]
                               (when (contains? redacted (keyword (name k)))
                                 (swap! ks conj k))
                               (walk v)))
                (sequential? x) (doseq [y x] (walk y))
                :else nil))]
      (walk record))
    (when (seq @ks) @ks)))

;; ----------------------------------------------------------------- run

(defn decide
  "One closed-domain decision, end to end, given a scorer.

  Pure except for calling `scorer`, which is the whole point of the seam: every
  branch below is reachable in a test with a scorer that returns a literal map,
  and none of them needs a model.

  Never throws for a bad domain or a failing scorer. A controller that dies
  because its advisor died is worse than one that declines, so a scorer
  exception becomes a deferral with the message attached."
  [{:keys [scorer context candidates policy model-id]}]
  (let [violation (legal-domain? candidates (or policy {}))]
    (if violation
      (auditable {:ranked [] :n-offered (count candidates)
                  :domain-check violation :model-id model-id
                  :outcome {:decision :defer :reason :reason/illegal-domain}})
      (let [{:keys [scores error]}
            (try (scorer context candidates)
                 (catch Throwable e {:error (or (ex-message e) (str e))}))]
        (if error
          (auditable {:ranked [] :n-offered (count candidates)
                      :model-id model-id
                      :outcome {:decision :defer :reason :reason/scorer-failed}})
          (let [ranked (rank candidates scores)
                outcome (select ranked (or policy {}))]
            (auditable {:ranked ranked :outcome outcome
                        :n-offered (count candidates)
                        :model-id model-id})))))))
