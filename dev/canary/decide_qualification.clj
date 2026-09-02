;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; THE FROZEN v0 QUALIFICATION MINI-EVAL for closed-domain decisions.
;;
;;   jolt -A:canary -m canary.decide-qualification            ; baselines only
;;   JOLT_LLAMA_MODEL=... jolt -A:canary -m canary.decide-qualification --model
;;
;; Issue #4 asked for this as the GATE on any real use of `decide`, not as a
;; one-off sensitivity measurement. It is deliberately small: 37 frozen
;; fixtures, enough to exercise the shape and to tell a scorer that has learned
;; the decision from one that has learned the prompt.
;;
;; It is NOT a production API and NOT a benchmark. Beating this does not make a
;; scorer good; failing it makes a scorer unqualified, which is the useful
;; direction for a gate.
;;
;; WHY "RANKING CHANGED?" IS NOT THE METRIC. A controller may legitimately
;; choose the same action across many nearby states -- that is correct
;; invariance, not insensitivity. So the fixtures carry matched CONTROLS where
;; the label must not move and COUNTERFACTUAL siblings where it must, and the
;; two are scored separately. A scorer that always says :hold scores perfectly
;; on controls and zero on counterfactuals, which is exactly the shape we need
;; to be able to see.

(ns canary.decide-qualification
  (:require [clojure.string :as str]
            [samizdat.decide :as decide]))

(def fixtures (read-string (slurp "resources/decide-eval/v0.edn")))

(def actions [:hold :scale :rollback :restart :page])

;; --------------------------------------------------------- state rendering

(defn render
  "The model-facing projection of a fixture's state.

  Deterministic and mechanical: every field the labelling rule reads appears,
  and nothing else. If the rendering omitted a field the rule uses, the eval
  would be measuring whether the model can guess rather than whether it can
  decide."
  [{:keys [needs-human? deploy-age-min p95-ms budget-ms err-rate restarts
           saturation cpu-pct mem-pct]}]
  (str "SERVICE STATE\n"
       (format "  latency_p95_ms: %d (budget %d)\n" p95-ms budget-ms)
       (format "  error_rate: %.3f\n" (double err-rate))
       (format "  saturation: %.2f\n" (double saturation))
       (format "  cpu_pct: %d\n  mem_pct: %d\n" cpu-pct mem-pct)
       (format "  process_restarts_recent: %d\n" restarts)
       (format "  minutes_since_deploy: %s\n"
               (if deploy-age-min (str deploy-age-min) "none"))
       (format "  needs_human_authority: %s\n" (if needs-human? "yes" "no"))))

(defn context-for [state]
  (str "CONTROLLER POLICY v1\n"
       "Choose exactly one action: hold, scale, rollback, restart, page.\n"
       "  hold     - nothing to do\n"
       "  scale    - add capacity for demand\n"
       "  rollback - undo a recent deploy that caused a regression\n"
       "  restart  - cycle an unhealthy process\n"
       "  page     - escalate to a human\n\n"
       (render state)
       "\nACTION:"))

;; -------------------------------------------------------------- baselines

(defn random-scorer
  "Baseline A: uniform random over the legal domain, seeded for reproducibility."
  [seed]
  (let [state (atom seed)
        next! (fn [] (swap! state (fn [s] (unchecked-int (+ (* 1103515245 s) 12345)))))]
    (fn [_ctx cands]
      {:scores (into {} (map (fn [c] [(:id c) (double (/ (bit-and (next!) 0xffff) 65536.0))])
                             cands))})))

(defn constant-scorer
  "Baseline B: always prefer one action. With :hold this is the majority-class
  baseline, and it is the one a scorer with no signal degenerates into."
  [action]
  (fn [_ctx cands]
    {:scores (into {} (map (fn [c] [(:id c) (if (= action (:id c)) 0.0 -10.0)]) cands))}))

(defn rule-scorer
  "Baseline C: the trusted rule that assigned the labels.

  A ceiling by construction -- it scores 100% because it IS the labelling
  function -- and it is here to prove the harness measures what it claims, not
  to flatter the rule. A harness on which the labelling rule does not score
  perfectly has a bug in the harness."
  [label-fn]
  (fn [ctx cands]
    (let [want (:expected-for-context ctx)]
      {:scores (into {} (map (fn [c] [(:id c) (if (= want (:id c)) 0.0 -10.0)]) cands))
       :meta {:scorer-id "rule@v0"}})))

;; ---------------------------------------------------------------- metrics

(defn- pairwise-accuracy
  "Fraction of (correct, incorrect) pairs the scorer orders correctly.

  Kept alongside top-1 because a scorer can rank the right action second every
  time -- badly wrong for a controller, but a long way from random, and top-1
  alone cannot tell that from noise."
  [record expected]
  (let [by-id (into {} (map (juxt :id identity) (:domain record)))
        want (get-in by-id [expected :score])]
    (when want
      (let [others (keep :score (vals (dissoc by-id expected)))]
        (when (seq others)
          (/ (count (filter #(> want %) others)) (double (count others))))))))

(defn evaluate
  "Run one scorer over every fixture and compute the metrics of §20."
  [scorer-name scorer policy]
  (let [rows
        (for [{:keys [id family group role state expected]} fixtures]
          (let [cands (mapv (fn [a] {:id a :tokens [1]}) actions)
                domain (decide/authorize cands
                                         {:legality (decide/all-legal)
                                          :id :eval/controller :revision "v0"
                                          :state-coord (str "fixture:" (name id))})
                ctx {:text (context-for state) :expected-for-context expected}
                rec (decide/decide {:scorer scorer :domain domain :policy policy
                                    :context ctx
                                    :prov-ctx {:scorer-id scorer-name}})]
            {:id id :family family :group group :role role :expected expected
             :decision (:decision rec) :selected (:selected rec)
             :would (:would-have-selected rec)
             :margin (:margin rec)
             :top1 (or (:selected rec) (:would-have-selected rec))
             :pairwise (pairwise-accuracy rec expected)}))
        rows (vec rows)
        n (count rows)
        acted (filter #(= :act (:decision %)) rows)
        deferred (filter #(= :defer (:decision %)) rows)
        correct-top1 (filter #(= (:expected %) (:top1 %)) rows)
        ;; a deferral is CORRECT when acting would have been wrong
        correct-defer (filter #(not= (:expected %) (:top1 %)) deferred)
        wrong-act (filter #(not= (:expected %) (:selected %)) acted)
        counters (filter #(= :counter (:role %)) rows)
        controls (filter #(= :control (:role %)) rows)
        pw (keep :pairwise rows)
        margins (sort (keep :margin rows))]
    {:scorer scorer-name
     :n n
     :top1-acc (/ (count correct-top1) (double n))
     :pairwise-acc (when (seq pw) (/ (reduce + pw) (double (count pw))))
     :defer-rate (/ (count deferred) (double n))
     :correct-defer-rate (when (seq deferred)
                           (/ (count correct-defer) (double (count deferred))))
     :wrong-confident-rate (/ (count wrong-act) (double n))
     :counterfactual-acc (when (seq counters)
                           (/ (count (filter #(= (:expected %) (:top1 %)) counters))
                              (double (count counters))))
     :control-acc (when (seq controls)
                    (/ (count (filter #(= (:expected %) (:top1 %)) controls))
                       (double (count controls))))
     :margin-p50 (when (seq margins) (nth margins (quot (count margins) 2)))
     :margin-min (first margins)
     :margin-max (last margins)
     :by-family (into (sorted-map)
                      (for [[f fs] (group-by :family rows)]
                        [f (/ (count (filter #(= (:expected %) (:top1 %)) fs))
                              (double (count fs)))]))
     :selected-distribution (frequencies (map :top1 rows))
     :rows rows}))

(defn- pct [x] (if x (format "%5.1f%%" (* 100.0 x)) "    -"))

(defn report [r]
  (println (format "%-22s n=%d" (:scorer r) (:n r)))
  (println (format "  top-1 correct        %s" (pct (:top1-acc r))))
  (println (format "  pairwise ordering    %s" (pct (:pairwise-acc r))))
  (println (format "  counterfactual acc   %s   <- must MOVE when the label moves"
                   (pct (:counterfactual-acc r))))
  (println (format "  matched-control acc  %s   <- must STAY when it should not"
                   (pct (:control-acc r))))
  (println (format "  defer rate           %s  (correct deferrals %s)"
                   (pct (:defer-rate r)) (pct (:correct-defer-rate r))))
  (println (format "  wrong + confident    %s   <- the dangerous cell"
                   (pct (:wrong-confident-rate r))))
  (println (format "  margin p50/min/max   %s"
                   (if (:margin-p50 r)
                     (format "%.3f / %.3f / %.3f" (double (:margin-p50 r))
                             (double (:margin-min r)) (double (:margin-max r)))
                     "-")))
  (println (format "  chose                %s" (pr-str (:selected-distribution r))))
  (println (format "  by family            %s"
                   (str/join "  " (for [[f a] (:by-family r)]
                                    (format "%s=%s" (name f) (str/trim (pct a)))))))
  (println))


;; The real-model scorer is built INLINE in -main against a resolved
;; jolt.llama, so this namespace loads and the baselines run on a machine with
;; no inference engine, no native library and no model weights.

(def ^:private policy {:min-margin 0.5 :require-comparable? true})

(defn -main [& args]
  (let [with-model? (some #{"--model"} args)]
    (println "closed-domain qualification, v0")
    (println (format "fixtures=%d families=%s"
                     (count fixtures)
                     (pr-str (into (sorted-map) (frequencies (map :family fixtures))))))
    (println (format "labels=%s" (pr-str (into (sorted-map) (frequencies (map :expected fixtures))))))
    (println (format "groups=%d  counterfactual rows=%d  matched controls=%d"
                     (count (distinct (map :group fixtures)))
                     (count (filter #(= :counter (:role %)) fixtures))
                     (count (filter #(= :control (:role %)) fixtures))))
    (println)
    (println "=== baselines (no model) ===")
    (println)
    (report (evaluate "C rule (labelling fn)" (rule-scorer nil) policy))
    (report (evaluate "B majority (:hold)" (constant-scorer :hold) policy))
    (report (evaluate "B constant (:page)" (constant-scorer :page) policy))
    (report (evaluate "A random (seeded)" (random-scorer 20260902) policy))

    (when with-model?
      (require '[jolt.llama :as llama])
      (let [open-model (resolve 'jolt.llama/open-model)
            new-session (resolve 'jolt.llama/new-session)
            tokenize (resolve 'jolt.llama/tokenize)
            close! (resolve 'jolt.llama/close!)
            path (or (System/getenv "JOLT_LLAMA_MODEL")
                     (throw (ex-info "set JOLT_LLAMA_MODEL" {})))
            m (open-model {:path path})]
        (try
          (let [s (new-session m {:context-size 4096 :threads 8})]
            (try
              (let [tk (fn [t] (tokenize m t {:add-special? false}))
                    enc (decide/verify-encodings
                         [{:id :hold :text " hold"} {:id :scale :text " scale"}
                          {:id :rollback :text " rollback"} {:id :restart :text " restart"}
                          {:id :page :text " page"}]
                         tk)]
                (println "=== model (Qwen3.5-0.8B via jolt-llama) ===")
                (println)
                (doseq [e (:encodings enc)]
                  (println (format "  %-9s %-11s n=%d %s" (name (:id e)) (pr-str (:text e))
                                   (:n-tokens e) (pr-str (:tokens e)))))
                (println "  encodings verified:" (:ok? enc))
                (println)
                (if-not (:ok? enc)
                  (println "  ABORTING the model run: unverified encodings would\n"
                           "  measure fragments, not actions. See issue #8.")
                  (let [encodings (into {} (map (juxt :id :tokens) (:encodings enc)))
                        scorer (fn [ctx cands]
                                 (let [toks ((resolve 'jolt.llama/tokenize) m (:text ctx))]
                                   ((resolve 'jolt.llama/clear!) s)
                                   ((resolve 'jolt.llama/eval!) s toks)
                                   (let [st ((resolve 'jolt.llama/save-state) s)
                                         scored ((resolve 'jolt.llama/score-candidates)
                                                 s (mapv (fn [c] (assoc c :tokens (get encodings (:id c)))) cands)
                                                 {:state st})]
                                     {:scores (into {} (map (juxt :id :logprob-sum) (:candidates scored)))
                                      :meta {:convention (:convention scored)
                                             :homogeneous? (:homogeneous? scored)
                                             :scorer-id "jolt-llama/score-candidates@v0"}})))]
                    (report (evaluate "D Qwen3.5-0.8B base" scorer policy)))))
              (finally (close! s))))
          (finally (close! m)))))

    (println "Reading: baseline C is the labelling rule and MUST score 100% --")
    (println "anything less is a bug in the harness, not a result. Baseline B is")
    (println "what a scorer with no signal degenerates into: perfect on matched")
    (println "controls, zero on counterfactuals. A scorer is qualified only if it")
    (println "beats B on COUNTERFACTUAL accuracy, which is the column that")
    (println "separates deciding from guessing the majority class.")))
