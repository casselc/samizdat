;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; CLOSED-DOMAIN DECISIONS as cells.
;;
;; The mechanism is samizdat.decide: domain authorization, the scorer-evidence
;; contract, ranking, the trusted selection rule, provenance and the journal
;; record. None of it knows when to fire, which actions exist, or what is legal.
;; That is this layer's business, which is why the vocabulary and the legality
;; rule are here and editable at runtime rather than compiled into src/.
;;
;;     trusted state -> authorized DecisionDomain -> scoring
;;                   -> trusted selection -> journal
;;
;; A model only ever ORDERS a set trusted code authorized. It cannot name an
;; action, cannot widen the set, and emits no text that is acted on.
;;
;; TWO THINGS THAT ARE DELIBERATELY NOT DEFAULTED.
;;
;; Legality has no default. An earlier version used (constantly true) when no
;; rule was supplied, which made "nobody wrote a rule" indistinguishable from
;; "everything is permitted here". A caller that means the latter says so with
;; :decide/all-legal? and the record shows that it said so.
;;
;; The scorer is TRANSIENT. It arrives in the data map as :decide/scorer and is
;; never journalled; what is journalled is :decide/scorer-id, the binding's
;; identity. A resumable workflow persists the identity and reconstructs the
;; function -- serialising a closure or a native session into durable run state
;; is not a thing this can be allowed to grow into.
;;
;; The scorer is also OPTIONAL: with none bound, every decision defers with
;; :reason/scorer-failed. samizdat must not acquire a hard runtime dependency on
;; an inference engine, a native library or model weights in order to run.

(ns cells.decide
  (:require [mycelium.cell :as cell]
            [samizdat.agent.gates :as gates]
            [samizdat.decide :as decide]
            [samizdat.store.journal :as journal]))

(defn- policy
  "The selection policy, read from gates.edn every time rather than captured.

  Read per call so editing gates.edn through the ordinary mutation path changes
  the very next decision, which is the standing rule for this layer. A policy
  captured at load time would need a restart to move, and a threshold that needs
  a restart is a constant in code wearing a data costume."
  []
  {:min-margin          (gates/threshold :decide-min-margin)
   :max-candidates      (gates/threshold :decide-max-candidates)
   :require-comparable? (gates/threshold :decide-require-comparable)})

(defn- policy-revision
  "A short digest of the policy values that actually applied.

  Recorded so a decision can be compared against the rule that produced it
  rather than against whatever gates.edn says today. Cheap and stable: the
  values are three scalars, and a changed threshold changes the digest."
  [p]
  (format "%08x"
          (bit-and (hash [(:min-margin p) (:max-candidates p)
                          (:require-comparable? p)])
                   0xffffffff)))

(def ^:private default-vocabulary
  "The default controller action set.

  Single-token encodings are the preferred v0 controller ABI: they need no
  candidate evaluation, come from one base distribution, and are exactly
  comparable under the validated jolt-llama path. The :tokens here are a
  PLACEHOLDER -- a real binding attaches encodings verified against its own
  tokenizer with decide/verify-encodings, because a token id means nothing
  without the model that produced it."
  [{:id :hold     :text " HOLD"}
   {:id :scale    :text " SCALE"}
   {:id :rollback :text " ROLLBACK"}
   {:id :restart  :text " RESTART"}
   {:id :page     :text " PAGE"}])

(cell/defcell :decide/domain
  {:doc "Authorize a DecisionDomain from trusted state.

        The vocabulary and the legality rule live here, in resources, because
        WHICH actions exist and WHICH are permitted is behaviour.

        Legality must be explicit. Supply :decide/legal? (a predicate) with
        :decide/legality-source and :decide/legality-revision, or say
        :decide/all-legal? true for a domain genuinely unconstrained in this
        state. Supplying neither produces an unauthorized domain, which
        :decide/score refuses -- rather than quietly permitting everything.

        Authorization happens BEFORE and WITHOUT any model. A model that scores
        an action highly cannot thereby make it legal, because it never sees an
        action that was not."
   :pure true
   :requires [:run-id]
   ;; Every input is optional: the canary starts from an empty data map and a
   ;; caller that supplies nothing gets an explicitly unauthorized domain,
   ;; which :decide/score refuses. The one thing this cell promises downstream
   ;; is the domain itself.
   :input  [:map
            [:decide/vocabulary {:optional true} :any]
            [:decide/legal? {:optional true} :any]
            [:decide/all-legal? {:optional true} :boolean]
            [:decide/legality-source {:optional true} :any]
            [:decide/legality-revision {:optional true} :any]
            [:decide/domain-id {:optional true} :any]
            [:decide/domain-revision {:optional true} :any]
            [:decide/state-coord {:optional true} :any]
            [:decide/authority {:optional true} :any]
            ;; the coordinate the domain is derived FROM (ADR-002 §1): given
            ;; whole, or assembled from the run's own keys when present
            [:decide/based-on {:optional true} :map]
            [:decide/manifest {:optional true} :any]
            [:decide/state-version {:optional true} :any]
            [:decide/graph-revision {:optional true} :any]
            [:branch {:optional true} :any]
            [:turn {:optional true} :any]]
   :output [:map [:decide/authorized :map]]}
  (fn [{:keys [run-id]}
       {:keys [decide/vocabulary decide/legal? decide/all-legal?
               decide/legality-source decide/legality-revision
               decide/domain-id decide/domain-revision decide/state-coord
               decide/authority decide/based-on decide/manifest
               decide/state-version decide/graph-revision branch turn]
        :as data}]
    (let [vocab (or vocabulary default-vocabulary)
          legality (cond
                     legal? (decide/legality (or legality-source :cell/legal-pred)
                                             (or legality-revision "unversioned")
                                             legal?)
                     all-legal? (decide/all-legal)
                     :else nil)
          ;; Only the coordinate parts that were actually supplied. A domain
          ;; with no :state/version cannot later be revalidated as fresh, and
          ;; that is the correct reading of a caller that did not say which
          ;; state it derived the domain from.
          based-on (or based-on
                       (let [m (cond-> {}
                                 run-id (assoc :run/id run-id)
                                 (:id branch) (assoc :branch/id (:id branch))
                                 turn (assoc :turn turn)
                                 manifest (assoc :manifest manifest)
                                 graph-revision (assoc :graph/revision graph-revision)
                                 state-version (assoc :state/version state-version))]
                         (when (seq m) m)))]
      (assoc data :decide/authorized
             (if legality
               (decide/authorize vocab {:legality legality
                                        :id (or domain-id :decide/default)
                                        :revision (or domain-revision "v1")
                                        :state-coord state-coord
                                        :authority authority
                                        :based-on based-on
                                        :policy-revision (policy-revision (policy))})
               ;; no legality evidence: an explicitly unauthorized domain, which
               ;; decide refuses with :domain/no-legality-source
               {:domain/candidates (vec vocab)})))))

(cell/defcell :decide/score
  {:doc "Score the authorized domain, apply trusted selection, journal it.

        Never throws and never fails a turn. A controller whose advisor is down
        must still be able to proceed by declining, so an unauthorized domain,
        a missing scorer, incomplete or malformed evidence and an exploding
        scorer all arrive at the same place: a recorded deferral with a reason
        specific enough to act on.

        What is journalled is the DECISION and its coordinates: every candidate
        trusted code authorized with the status of its evidence, what policy did
        and why, and enough provenance to re-derive the situation. Never a
        native handle, a state blob, the logits, the token vectors, the prompt,
        or the scorer itself. The record is checked against that rule BEFORE it
        is written, because an append-only journal has no second chance."
   :effects [:db]
   :requires [:conn :run-id]
   ;; The domain is the one required input, and it is what :decide/domain
   ;; leaves behind; the scorer and its coordinates are transient bindings the
   ;; caller may or may not supply. The record and the decision keyword are
   ;; what :decide/apply reads.
   :input  [:map
            [:decide/authorized :map]
            [:decide/context {:optional true} :any]
            [:decide/scorer {:optional true} :any]
            [:decide/scorer-id {:optional true} :any]
            [:decide/model-coord {:optional true} :any]
            [:decide/decision-id {:optional true} :any]
            [:branch {:optional true} :any]
            [:turn {:optional true} :any]]
   :output [:map [:decide/record :map] [:decide/decision :keyword]
            [:decide/decision-row {:optional true} :any]]}
  (fn [{:keys [conn run-id]}
       {:keys [decide/authorized decide/context decide/scorer decide/scorer-id
               decide/model-coord decide/decision-id branch turn]
        :as data}]
    (let [p (policy)
          prov-ctx (merge {:run-id run-id
                           :branch-id (:id branch)
                           :turn turn
                           :decision-id decision-id
                           :domain-id (:domain/id authorized)
                           :domain-revision (:domain/revision authorized)
                           :state-coord (:domain/state-coord authorized)
                           :authority (:domain/authority authorized)
                           :legality-source (:domain/legality-source authorized)
                           :legality-revision (:domain/legality-revision authorized)
                           :policy-revision (policy-revision p)
                           :min-margin (:min-margin p)
                           :require-comparable? (:require-comparable? p)
                           :scorer-id scorer-id}
                          ;; model artifact coordinates, resolved once by the
                          ;; binding and passed through as scalars
                          (select-keys (or model-coord {})
                                       [:model-id :model-sha256 :model-repo
                                        :model-revision :model-file
                                        :tokenizer-family :jolt-llama-sha
                                        :llama-cpp-sha :native-abi]))
          record (decide/decide {:scorer (or scorer (fn [_ _] (throw (ex-info "no scorer bound" {}))))
                                 :domain authorized
                                 :context context
                                 :policy p
                                 :prov-ctx prov-ctx})
          leaked (decide/leaks? record)
          ;; A record that would leak is replaced, not written and apologised
          ;; for. The offending keys are named so the cell that introduced them
          ;; is findable, which a dropped record would not be.
          safe (if leaked
                 {:decision :defer :reason :reason/record-would-leak
                  :leaked-keys (vec leaked)
                  :n-offered (count (:domain/candidates authorized))}
                 record)]
      ;; The durable row (migration v21, ADR-001): the decision is a fact about
      ;; the run, not a tail-buffer note. `durable` keeps qualified keywords
      ;; intact: data.json would otherwise write :reason/incomplete-scores as
      ;; "incomplete-scores" and drop the half of the value that says which
      ;; vocabulary it came from. record-decision! also emits the :decide event
      ;; for anything watching live.
      (let [row (journal/record-decision! conn run-id
                                          {:branch-id (:id branch) :turn turn
                                           :decision-id decision-id
                                           :manifest (:manifest (:domain/based-on authorized))}
                                          (decide/durable safe))]
        (assoc data :decide/record safe :decide/decision (:decision safe)
               :decide/decision-row row)))))

(cell/defcell :decide/apply
  {:doc "Carry a decision into the data map, or carry the deferral.

        Separate from :decide/score so the record is written BEFORE anything
        acts on it. A manifest that drops this cell still journals the decision;
        a manifest that drops the journal cannot reach this one. The manifest's
        :must-precede invariant enforces that ordering at compile time, which is
        the difference between an auditable decision and an audited-afterwards
        one.

        A model selection is not a committed transition (ADR-001 invariant 4).
        When the caller supplies the CURRENT state as :decide/current
        ({:state/version :authority :budget-ok? :invariants-ok?}), the kernel
        re-derives freshness here, immediately before apply (decide/revalidate):
        a domain derived at another state version, under another authority,
        past its budget or against an invariant is not applied, and the record
        says which. Without :decide/current the decision is applied as scored
        and the record says :revalidated? false -- honest, and the older
        contract. The durable row is updated when there is one."
   :effects [:db]
   :requires [:conn]
   :input  [:map [:decide/record :map]
            [:decide/authorized {:optional true} :map]
            [:decide/current {:optional true} :map]
            [:decide/decision-row {:optional true} :any]]
   ;; :decide/action is nil on a deferral, so :any rather than :keyword; the
   ;; reason is only present when there is one.
   :output [:map [:decide/action :any]
            [:decide/applied? :boolean]
            [:decide/deferred-reason {:optional true} :any]
            [:decide/revalidation {:optional true} :any]]}
  (fn [{:keys [conn]}
       {:keys [decide/record decide/authorized decide/current decide/decision-row] :as data}]
    (let [record' (if current
                    (decide/revalidate record (or authorized {}) current)
                    (assoc record :revalidated? false))]
      (when (and conn decision-row current)
        (journal/decision-revalidated! conn decision-row record'))
      (if (= :act (:decision record'))
        (assoc data :decide/record record'
               :decide/action (:selected record') :decide/applied? true
               :decide/revalidation (:revalidation record'))
        (assoc data :decide/record record'
               :decide/action nil :decide/applied? false
               :decide/deferred-reason (:reason record')
               :decide/revalidation (:revalidation record'))))))
