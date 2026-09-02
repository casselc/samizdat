;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; CLOSED-DOMAIN DECISIONS as cells.
;;
;; The mechanism is samizdat.decide: legality of a domain, ranking, the trusted
;; selection rule, and the journal-shaped record. None of it knows when to fire
;; or what the options are. That is this layer's business, which is why the
;; action vocabulary below is here and editable at runtime rather than compiled
;; into src/.
;;
;; The shape being proved:
;;
;;     trusted state -> finite legal domain -> scoring -> trusted selection
;;                                                             -> journal
;;
;; A model only ever ORDERS a list that trusted code wrote down. It cannot name
;; an option, cannot widen the set, and emits no text that is acted on. The
;; worst a broken or hostile scorer achieves is a bad ordering of options that
;; were all already legal -- and even then the margin rule declines rather than
;; acts. That bound is the entire reason to prefer this over generation for a
;; decision that is genuinely closed.
;;
;; The scorer is OPTIONAL and arrives in the DATA map as :decide/scorer, not on
;; the ctx. That is deliberate. manifests/ctx-keys is the contract every driver
;; must satisfy, asserted from both ends -- compile refuses a cell requiring a
;; key not in the set, and beam-test asserts production actually provides every
;; key in it. Putting a scorer there would oblige every driver to carry a key
;; only this capability uses, which is a change to the base for the benefit of
;; one workflow. A scorer is per-decision input, so it travels as data.
;;
;; When it is absent every decision defers with :reason/no-scorer. samizdat must
;; not acquire a hard runtime dependency on an inference engine, a native
;; library or model weights in order to run.

(ns cells.decide
  (:require [mycelium.cell :as cell]
            [samizdat.agent.gates :as gates]
            [samizdat.decide :as decide]
            [samizdat.store.journal :as journal]))

(defn- policy
  "The selection policy, read from gates.edn every time rather than captured.

  Read per call so that editing gates.edn through the ordinary mutation path
  changes the very next decision, which is the standing rule for this layer. A
  policy captured at load time would need a restart to move, and a threshold
  that needs a restart is a constant in code wearing a data costume."
  []
  {:min-margin          (gates/threshold :decide-min-margin)
   :max-candidates      (gates/threshold :decide-max-candidates)
   :require-comparable? (gates/threshold :decide-require-comparable)})

(cell/defcell :decide/domain
  {:doc "Build the finite legal domain from trusted state.

        The vocabulary lives here, in resources, because WHICH actions are
        legal is behaviour. A run that needs a different vocabulary edits this
        cell; nothing rebuilds.

        Legality is decided BEFORE scoring and without consulting any model:
        the options are filtered by what the run's own state permits. A model
        that scores an option highly cannot thereby make it legal, because it
        never sees an option that was not."
   :pure true
   :requires []}
  (fn [_ data]
    (let [{:keys [decide/vocabulary decide/legal?]} data
          vocab (or vocabulary
                    ;; the default controller vocabulary. Single-token ids on
                    ;; purpose: equal-length candidates are exactly comparable,
                    ;; and one token per action is the cheapest case there is --
                    ;; it needs no evaluation at all, only a read of the base
                    ;; distribution.
                    [{:id :hold     :text " HOLD"}
                     {:id :scale    :text " SCALE"}
                     {:id :rollback :text " ROLLBACK"}
                     {:id :restart  :text " RESTART"}
                     {:id :page     :text " PAGE"}])
          allow (or legal? (constantly true))]
      (assoc data :decide/candidates (filterv allow vocab)))))

(cell/defcell :decide/score
  {:doc "Score the domain, apply trusted selection, and journal the decision.

        Never throws and never fails a turn. A controller whose advisor is down
        must still be able to proceed by declining, so a missing scorer, an
        illegal domain and an exploding scorer all arrive at the same place: a
        recorded deferral with a reason.

        What is journalled is the DECISION, not the machine: the domain that
        was offered, every score, the margin, what policy did with them, and
        which model produced them. Never a native handle, a state blob, the
        logits, the token vectors or the prompt. The record is checked against
        that rule before it is written rather than after, because an
        append-only journal has no second chance."
   :effects [:db]
   :requires [:conn :run-id]}
  (fn [{:keys [conn run-id]}
       {:keys [decide/candidates decide/context decide/scorer decide/model-id]
        :as data}]
    (let [record (if-not scorer
                   {:n-offered (count candidates) :n-scored 0
                    :decision :defer :reason :reason/no-scorer
                    :selected nil :margin nil :domain []
                    :domain-check :ok :model-id nil}
                   (decide/decide {:scorer scorer
                                   :context context
                                   :candidates candidates
                                   :policy (policy)
                                   :model-id model-id}))
          leaked (decide/leaks? record)
          ;; A record that would leak is replaced, not written and apologised
          ;; for. The offending keys are named so the cell that introduced them
          ;; is findable, which a dropped record would not be.
          safe (if leaked
                 {:decision :defer :reason :reason/record-would-leak
                  :leaked-keys (vec leaked) :n-offered (count candidates)}
                 record)]
      ;; note! forwards its 4th argument to emit! as OPTIONS, so the payload
      ;; goes under :data. Passing the record bare stores an empty object --
      ;; silently, since emit! does (or data {}).
      (journal/note! conn run-id :decide {:data safe})
      (assoc data :decide/record safe :decide/decision (:decision safe)))))

(cell/defcell :decide/apply
  {:doc "Carry a decision into the data map, or carry the deferral.

        Separate from :decide/score so that the record is written BEFORE
        anything acts on it. A manifest that drops this cell still journals the
        decision; a manifest that drops the journal cannot reach this one. That
        ordering is what the :must-precede invariant in the manifest enforces,
        and it is the difference between an auditable decision and an
        audited-afterwards one."
   :pure true
   :requires []}
  (fn [_ {:keys [decide/record] :as data}]
    (if (= :act (:decision record))
      (assoc data :decide/action (:selected record))
      (assoc data :decide/action nil
             :decide/deferred-reason (:reason record)))))
