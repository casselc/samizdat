;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; THE BEAM'S ROUND, as cells.
;;
;; The inner TURN has been a manifest since karamazov-ioo.20 — one branch, one
;; model call, one tool call, one arbiter decision. The outer ROUND was not: it
;; was a `loop`/`recur` inside samizdat.agent.beam, so the sequence that
;; decides which branches live, which fork, and when the run ends could not be
;; changed without rebuilding the harness.
;;
;; That sequence is the clearest case of userspace there is. It does not talk
;; to a provider or open a file; it ARRANGES the pieces that do. So it lives
;; here, in this project's own copy, where a project that wants to score before
;; it culls, or cull before it drains directives, or skip repopulation
;; entirely, can say so by editing a manifest.
;;
;; WHAT STAYS IN THE BASE, and why: driving one branch through its turn
;; manifest, fanning out over branches under a deadline, disposing engine
;; sessions, closing branch and run rows, ranking finished branches on a rubric
;; that is already phases.edn data. Those are capabilities. Nothing in them
;; decides a branch's fate.
;;
;; THE ORDER IS LOAD-BEARING and the manifest is where it is now written down:
;;
;;   directives before advance   a human's instruction lands on a turn boundary,
;;                               not mid-turn, so a branch is never holding a
;;                               ledger it read before the change
;;   score before cull           retention reads critic scores, so they must be
;;                               fresh for the post-turn state
;;   cull before spawn           a branch culled this round must not also spend
;;                               the branch budget on children
;;   settle before repopulate    the freed slot has to be visible for the same
;;                               round to refill it
;;
;; Change the order in manifests/beam.edn and you change the policy. That is
;; the point; the reasons above are why you should be deliberate about it.
(ns cells.beam
  (:require [mycelium.cell :as cell]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.state :as state]
            [samizdat.store.failures :as failures]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

;; --- the top of the round ----------------------------------------------------

(cell/defcell :beam/round-open
  {:doc "Decide whether this round happens at all, and record why not.

        Four verdicts. :aborted — the abort flag is set; checked FIRST and at
        the top, because a stop must not need the run's cooperation. :completed
        — a branch shipped and the campaign policy says that ends the run.
        :exhausted — nobody is left to explore, or the turn cap is spent.
        :continue — do the round.

        Reads the abort flag, so not pure."
   :effects [:db]}
  (fn [{:keys [abort max-turns] :as ctx} {:keys [branches turn] :as data}]
    (let [active (filterv state/active? branches)
          candidates (filterv :final-answer branches)
          done (beam/finish-now? ctx (beam/select-done-branch ctx candidates) branches)]
      (assoc data
             :active active
             :done-branch done
             :multi-candidate? (< 1 (count candidates))
             :verdict (cond
                        (and abort @abort) :aborted
                        done :completed
                        (or (empty? active) (> turn max-turns)) :exhausted
                        :else :continue)))))

;; --- the round --------------------------------------------------------------

(cell/defcell :beam/directives
  {:doc "Apply pending human directives at the boundary and resolve each in the
        interventions table. First in the round on purpose: a directive that
        landed mid-turn would rewrite state under a branch that had already
        read it."
   :effects [:db]}
  (fn [ctx {:keys [active turn] :as data}]
    (let [{:keys [conn run-id]} ctx
          pending (interventions/pending conn run-id)]
      (assoc data :active (beam/drain-directives! ctx active pending turn)))))

(cell/defcell :beam/advance
  {:doc "One turn for every active branch, concurrently, each under a hard
        deadline. A branch that throws is abandoned rather than taking the beam
        down with it; a branch that hangs loses only its own turn."
   :effects [:net :db :fs :proc]}
  (fn [ctx {:keys [active branches turn] :as data}]
    (let [advanced (beam/advance-all (assoc ctx :branch-count (count branches))
                                     (filterv state/active? active)
                                     turn)]
      ;; The driver's teardown needs the branches as they stand, and a manifest
      ;; run does not hand intermediate state back on a throw. The atom is the
      ;; driver's window into the round; see beam/run-rounds' finally.
      (when-let [live (:live-branches ctx)] (reset! live advanced))
      (assoc data :advanced advanced))))

(cell/defcell :beam/score
  {:doc "Refresh critic scores for every active branch, at most one sub-LLM
        call per branch per :critic-every window. Before the retention pass,
        because that pass reads them and stale scores would decide a live
        branch's fate on last round's evidence."
   :effects [:net :db]}
  (fn [ctx {:keys [advanced turn] :as data}]
    (assoc data :advanced (beam/ensure-scored ctx advanced turn))))

(cell/defcell :beam/cull
  {:doc "The retention pass: every branch that just failed faces the cull rule,
        left to right, against the count of branches that would still be
        running after the decisions already made.

        Evaluated in order rather than in parallel for that reason — the last
        branch standing is never culled, so whether THIS branch survives
        depends on what happened to the ones before it."
   :effects [:db]}
  (fn [ctx {:keys [advanced turn] :as data}]
    (let [culled (first
                  (reduce (fn [[acc alive] b]
                            (let [sibs (keep #(when (and (state/active? %)
                                                         (not= (:id %) (:id b)))
                                                (get-in % [:critic :scores]))
                                             advanced)
                                  b' (beam/cull-or-keep (assoc ctx :turn turn)
                                                        b (dec alive) sibs)]
                              [(conj acc b') (if (state/active? b') alive (dec alive))]))
                          [[] (count advanced)]
                          advanced))]
      (assoc data :culled culled))))

(cell/defcell :beam/settle
  {:doc "Write the ending of every branch that is no longer active and release
        what it held, then fold this round's survivors back together with the
        branches that were already inactive.

        Before repopulation, so a slot freed this round is visible to the
        refill that happens in the same round."
   :effects [:db]}
  (fn [ctx {:keys [branches culled] :as data}]
    (beam/record-inactive! ctx culled)
    (let [inactive (filterv (complement state/active?) branches)]
      (assoc data
             :inactive inactive
             :all-now (into (vec inactive) culled)))))

(cell/defcell :beam/repopulate
  {:doc "Refill the beam when it has fallen below its target width by MARKING
        the strongest earning survivor. The ask itself is the :repopulate gate,
        which reads the mark — so the invitation has a prediction and shows up
        in the gate tally rather than being an untracked second harness voice."
   :effects [:db]}
  (fn [ctx {:keys [culled all-now turn] :as data}]
    (assoc data :culled (beam/repopulate ctx culled (count all-now) turn))))

(cell/defcell :beam/spawn
  {:doc "Turn each branch's pending theses into sibling branches, under the
        total cap. After the cull, so a branch that died this round does not
        spend the budget on children."
   :effects [:db]}
  (fn [ctx {:keys [culled all-now turn] :as data}]
    (let [[children updated]
          (reduce (fn [[acc bs] b]
                    (if (and (state/active? b) (seq (:pending-branch-theses b)))
                      (let [[kids parent] (beam/spawn-children!
                                           ctx b (+ (count all-now) (count acc)) turn)]
                        [(into acc kids) (conj bs parent)])
                      [acc (conj bs b)]))
                  [[] []]
                  culled)]
      (assoc data :children children :updated updated))))

(cell/defcell :beam/tick
  {:doc "Close the round: reassemble the branch set, advance the turn, and drop
        the per-round products so the data map does not grow without bound.

        The trace is capped hard here. Every mycelium trace entry snapshots the
        whole data map, and this map holds every branch's entire message
        history — an uncapped trace would be quadratic in the run."
   :pure true}
  (fn [ctx {:keys [inactive updated children] :as data}]
    (let [next-branches (into (into (vec inactive) updated) children)]
      (when-let [live (:live-branches ctx)] (reset! live next-branches))
      (-> data
          (assoc :branches next-branches)
          (update :turn inc)
          (dissoc :active :advanced :culled :inactive :all-now :updated :children
                  :done-branch :multi-candidate? :verdict)
          (update :mycelium/trace #(vec (take-last 5 %)))))))

;; --- the three endings -------------------------------------------------------

(cell/defcell :beam/abort
  {:doc "The run was stopped from outside. Every still-active branch is closed
        as abandoned; no answer is claimed."
   :effects [:db]}
  (fn [{:keys [conn run-id]} {:keys [branches active] :as data}]
    (doseq [b active]
      (runs/close-branch! conn run-id (:id b) :abandoned "aborted"))
    (assoc data :status :aborted :result {:status :aborted :run-id run-id
                                          :branches branches})))

(cell/defcell :beam/complete
  {:doc "A branch shipped and the campaign policy says that ends the run. The
        others are closed naming what superseded them — 'outranked by' when
        more than one branch had shipped and the rubric chose, 'superseded by'
        when only one had."
   :effects [:db]}
  (fn [{:keys [conn run-id]} {:keys [branches done-branch multi-candidate?] :as data}]
    (doseq [b branches
            :when (and (state/active? b) (not= (:id b) (:id done-branch)))]
      (runs/close-branch! conn run-id (:id b) :abandoned
                          (str (if multi-candidate? "outranked by " "superseded by ")
                               (:id done-branch)
                               (when-not multi-candidate? " done()"))))
    (runs/finish-run! conn run-id :completed (:final-answer done-branch))
    (assoc data :status :completed
           :result {:status :completed :answer (:final-answer done-branch)
                    :run-id run-id :branches branches})))

(cell/defcell :beam/exhaust
  {:doc "Nobody is left to explore, or the turn cap is spent. Every active
        branch is closed as exhausted and each branch's RESIDUAL is journalled:
        what it believed it was close to when the budget ran out, so a resume
        does not re-derive scope from the transcript."
   :effects [:db]}
  (fn [{:keys [conn run-id max-turns]} {:keys [branches active] :as data}]
    (let [residuals (keep state/residual branches)
          report (state/build-residual-report
                  {:branches branches
                   :failures (failures/recent conn run-id 10)
                   :gate-tally (journal/gate-tally conn run-id)
                   :max-turns max-turns})]
      (doseq [b active]
        (runs/close-branch! conn run-id (:id b) :exhausted
                            (str "turn cap of " max-turns " reached")))
      (doseq [r residuals]
        (journal/note! conn run-id :residual {:branch-id (:branch r) :data r}))
      (journal/note! conn run-id :residual-report {:data report})
      (runs/finish-run! conn run-id :failed nil)
      (assoc data :status :exhausted
             :result {:status :exhausted :run-id run-id :branches branches
                      :residuals (vec residuals) :report report
                      :report-text (state/render-residual-report report)}))))
