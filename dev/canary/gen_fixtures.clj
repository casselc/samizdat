;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; Generate the FROZEN v0 qualification fixtures.
;;
;;   jolt -A:canary -m canary.gen-fixtures > resources/decide-eval/v0.edn
;;
;; Run once; the OUTPUT is the artifact. The rule below assigns each label
;; mechanically from explicit state fields, so no label is smuggled in via
;; prose, and the frozen file then stands on its own -- an eval whose labels
;; move when the labelling code is edited is not frozen.

(ns canary.gen-fixtures)

;; ---------------------------------------------------------------- the rule

(defn label
  "The mechanical preferred action for a state. Also baseline C.

  Ordered by authority: a situation needing a human is not automatable no
  matter what else is true, and a regression right after a deploy is explained
  by that deploy, so rolling back beats restarting a process that is only sick
  because of what it is running."
  [{:keys [needs-human? deploy-age-min p95-ms budget-ms err-rate
           restarts saturation]}]
  (cond
    needs-human?                                              :page
    ;; a fresh deploy EXPLAINS a regression, so undo the deploy
    (and deploy-age-min (< deploy-age-min 30)
         (or (> p95-ms budget-ms) (> err-rate 0.05)))         :rollback
    ;; a sick process with healthy latency is a process problem
    (and (> restarts 2) (<= p95-ms budget-ms) (<= err-rate 0.05)) :restart
    ;; failing hard with nothing to blame it on is not automatable: no fresh
    ;; deploy to undo and no crashloop to cycle, so a human decides. Without
    ;; this an 8% error rate fell through to :hold, which is not a defensible
    ;; label for the eval to hold a scorer to.
    (> err-rate 0.05)                                         :page
    (and (> saturation 0.85) (<= err-rate 0.01)
         (<= p95-ms (* 1.5 budget-ms)))                       :scale
    :else                                                     :hold))

(def ^:private base
  {:needs-human? false :deploy-age-min nil :p95-ms 90 :budget-ms 120
   :err-rate 0.001 :restarts 0 :saturation 0.30 :cpu-pct 35 :mem-pct 40})

(defn- fx
  "One fixture. `group` ties counterfactual siblings together so a split can
  never separate them.

  `intent` is what the author MEANT this row to be; `:role` is DERIVED below
  from whether the label actually moved. Writing the role by hand let the two
  disagree -- four rows were authored as controls or counterfactuals and the
  rule labelled them the other way -- and a metric that trusts a hand-written
  role is measuring the author's belief rather than the data."
  [id family group intent overrides]
  (let [state (merge base overrides)]
    {:id id :family family :group group :intent intent
     :state state :expected (label state)}))

(defn- with-roles
  "Derive :role from the labels: a row is a :control when it shares its group's
  pivot label and a :counter when it does not. Self-consistent by construction."
  [rows]
  (let [pivots (into {} (for [[g rs] (group-by :group rows)
                              :let [p (first (filter #(= :pivot (:intent %)) rs))]]
                          [g (:expected p)]))]
    (mapv (fn [r]
            (assoc r :role (cond (= :pivot (:intent r)) :pivot
                                 (= (:expected r) (get pivots (:group r))) :control
                                 :else :counter)))
          rows)))

(def fixtures
  (with-roles
   (vec
    (concat
    ;; ---- healthy: nominal, and nearby states that must STILL be hold
    [(fx :healthy-nominal    :healthy :g-healthy :pivot   {})
     (fx :healthy-warm       :healthy :g-healthy :control {:cpu-pct 62 :saturation 0.55})
     (fx :healthy-old-deploy :healthy :g-healthy :control {:deploy-age-min 600})
     (fx :healthy-slow-ok    :healthy :g-healthy :control {:p95-ms 118})
     (fx :healthy-one-restart :healthy :g-healthy :control {:restarts 1})
     (fx :healthy-tiny-errs  :healthy :g-healthy :control {:err-rate 0.004})]

    ;; ---- demand: saturation with a healthy deployment -> scale
    [(fx :demand-saturated   :demand :g-demand :pivot   {:saturation 0.94 :cpu-pct 88})
     (fx :demand-very-sat    :demand :g-demand :control {:saturation 0.99 :cpu-pct 95})
     (fx :demand-sat-warm    :demand :g-demand :control {:saturation 0.90 :p95-ms 150})
     ;; counterfactual: same saturation, but errors say it is not a capacity
     ;; problem, so scaling is the wrong move
     (fx :demand-sat-erroring :demand :g-demand :counter {:saturation 0.94 :err-rate 0.08})
     ;; counterfactual: saturated AND freshly deployed with a regression
     (fx :demand-sat-after-deploy :demand :g-demand :counter
         {:saturation 0.94 :deploy-age-min 5 :p95-ms 400})]

    ;; ---- regression: fresh deploy + degradation -> rollback
    [(fx :regress-latency    :regression :g-regress :pivot   {:deploy-age-min 4 :p95-ms 780})
     (fx :regress-errors     :regression :g-regress :control {:deploy-age-min 7 :err-rate 0.22})
     (fx :regress-both       :regression :g-regress :control {:deploy-age-min 2 :p95-ms 900 :err-rate 0.3})
     (fx :regress-edge       :regression :g-regress :control {:deploy-age-min 29 :p95-ms 300})
     ;; counterfactual: the SAME degradation, but the deploy is old, so the
     ;; deploy does not explain it and rollback is not the indicated action
     (fx :regress-stale-deploy :regression :g-regress :counter
         {:deploy-age-min 400 :p95-ms 780})
     ;; counterfactual: fresh deploy, but nothing is actually wrong
     (fx :regress-clean-deploy :regression :g-regress :counter {:deploy-age-min 3})]

    ;; ---- process: crashlooping without a deploy regression -> restart
    [(fx :process-crashloop  :process :g-process :pivot   {:restarts 7})
     (fx :process-leaky      :process :g-process :control {:restarts 4 :mem-pct 93})
     (fx :process-flapping   :process :g-process :control {:restarts 3 :cpu-pct 70})
     ;; counterfactual: same restarts, but latency is also blown, so this is
     ;; not merely a sick process
     (fx :process-crash-slow :process :g-process :counter {:restarts 7 :p95-ms 700})
     ;; counterfactual: same restarts, immediately after a deploy
     (fx :process-crash-after-deploy :process :g-process :counter
         {:restarts 7 :deploy-age-min 6 :err-rate 0.4})]

    ;; ---- authority: needs a human -> page, and it outranks everything
    [(fx :auth-unknown       :authority :g-auth :pivot   {:needs-human? true})
     (fx :auth-with-regress  :authority :g-auth :control {:needs-human? true :deploy-age-min 3 :p95-ms 900})
     (fx :auth-with-crash    :authority :g-auth :control {:needs-human? true :restarts 9})
     (fx :auth-with-demand   :authority :g-auth :control {:needs-human? true :saturation 0.97})
     ;; counterfactual: identical except the human requirement is gone
     (fx :auth-cleared       :authority :g-auth :counter {:needs-human? false :restarts 9})]

    ;; ---- boundary: rows that sit right at a threshold, to catch a scorer
    ;; that has learned the shape but not the cut
    [(fx :bound-sat-just-under :boundary :g-bound :pivot   {:saturation 0.85})
     (fx :bound-sat-just-over  :boundary :g-bound :counter {:saturation 0.86 :err-rate 0.005})
     (fx :bound-deploy-29      :boundary :g-bound :control {:deploy-age-min 29 :p95-ms 500})
     (fx :bound-deploy-31      :boundary :g-bound :counter {:deploy-age-min 31 :p95-ms 500})
     (fx :bound-restarts-2     :boundary :g-bound :control {:restarts 2})
     (fx :bound-restarts-3     :boundary :g-bound :counter {:restarts 3})]

    ;; ---- mixed: several signals at once, where the ordering of the rule is
    ;; what decides
    [(fx :mixed-deploy-and-sat  :mixed :g-mixed :pivot   {:deploy-age-min 8 :p95-ms 600 :saturation 0.95})
     (fx :mixed-crash-and-sat   :mixed :g-mixed :control {:restarts 5 :saturation 0.95})
     (fx :mixed-all-quiet       :mixed :g-mixed :control {:cpu-pct 20 :saturation 0.1})
     (fx :mixed-slow-not-fresh  :mixed :g-mixed :counter {:deploy-age-min 200 :p95-ms 600 :saturation 0.95})]))))

(defn -main [& _]
  (let [by-family (frequencies (map :family fixtures))
        by-label (frequencies (map :expected fixtures))]
    (println ";; FROZEN closed-domain qualification fixtures, v0.")
    (println ";;")
    (println ";; Generated once by dev/canary/gen_fixtures.clj. The labels are")
    (println ";; MECHANICAL -- derived from explicit state fields by the rule in")
    (println ";; that file, never from prose -- and are frozen here so the eval")
    (println ";; does not move when the labelling code is edited.")
    (println ";;")
    (println ";; :group ties counterfactual siblings together. A split must keep a")
    (println ";; whole group on one side: neighbouring variants on both sides of a")
    (println ";; split measure memorisation, not generalisation.")
    (println ";;")
    (println (format ";; %d fixtures  families=%s" (count fixtures) (pr-str by-family)))
    (println (format ";; labels=%s" (pr-str by-label)))
    (prn fixtures)))
