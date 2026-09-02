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

(def ^:private descriptions
  {:hold     "nothing to do"
   :scale    "add capacity for demand"
   :rollback "undo a recent deploy that caused a regression"
   :restart  "cycle an unhealthy process"
   :page     "escalate to a human"})

(def framing
  "Which prompt shape to score under. See dev/canary/decide_framing.clj.

  :chat-nothink is the default and the only one that measures a decision on
  these models. They are all REASONING models: handed an open assistant turn
  they emit <think> with probability 0.72 / 0.92 / 0.995, and handed a raw
  completion the 27B emits turn-terminators. Under either, almost none of the
  model's belief lands on a legal action, and ranking the remainder ranks
  tokens the model was not trying to emit.

  Pre-closing the reasoning block is the documented Qwen3 way to request an
  answer without a reasoning pass. Under it every model's entire top-5 is
  actions.

  :completion is retained because the first qualification run used it, and that
  run's numbers are reported beside these rather than quietly replaced."
  (keyword (or (System/getenv "DECIDE_FRAMING") "chat-nothink")))

(defn- policy-block [order]
  (str "CONTROLLER POLICY v1\n"
       "Choose exactly one action: " (str/join ", " (map name order)) ".\n"
       (apply str (for [a order]
                    (format "  %-8s - %s\n" (name a) (descriptions a))))))

(defn context-for
  "The model-facing prompt. `order` is the order the actions are LISTED, which
  is presentational and must not change the answer -- but measurably does, so
  it is a parameter rather than a constant."
  ([state] (context-for state actions))
  ([state order]
   (case framing
     :completion
     (str (policy-block order) "\n" (render state) "\nACTION:")

     :chat-nothink
     (str "<|im_start|>system\n"
          "You are a service controller. Answer with exactly one action word.\n"
          "<|im_end|>\n"
          "<|im_start|>user\n"
          (policy-block order) "\n" (render state)
          "\nWhich action?<|im_end|>\n"
          "<|im_start|>assistant\n<think>\n\n</think>\n\n"))))

(def action-text
  "The model-facing encoding per framing. After \"ACTION:\" the natural
  continuation carries a leading space; after a closed reasoning block it does
  not, and the wrong one collapses domain mass by three orders of magnitude."
  (case framing
    :completion   (fn [a] (str " " (name a)))
    :chat-nothink (fn [a] (name a))))

(defn rotations
  "The n cyclic rotations of the action list. Counterbalancing over these gives
  every action the first slot exactly once, which is what removes the
  first-position advantage rather than merely randomising it."
  [order]
  (mapv (fn [i] (vec (concat (drop i order) (take i order))))
        (range (count order))))

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
  "Baseline C: the trusted rule, RUN against each fixture's state.

  It used to read :expected-for-context -- the answer -- straight out of the
  scorer context and ignore its label-fn argument entirely. Scoring 100% that
  way is tautological: it proved only that the harness can copy a value from
  one map to another. It did NOT establish that the mechanical rule, the frozen
  labels, the state projection and the harness still agree, which is the only
  reason to have a baseline C at all.

  Now it derives the action from the fixture's explicit state fields, exactly
  as the label generator does. If it scores below 100% the frozen artifact and
  the rule have drifted apart, and that is a finding rather than a formality."
  [label-fn]
  (fn [ctx cands]
    (let [want (label-fn (:state-for-context ctx))]
      {:scores (into {} (map (fn [c] [(:id c) (if (= want (:id c)) 0.0 -10.0)]) cands))
       :meta {:scorer-id "rule@v0"}})))

(defn label
  "The mechanical labelling rule, kept identical to dev/canary/gen_fixtures.clj.

  Duplicated deliberately rather than required across the dev namespaces: the
  frozen artifact must not move when this file is edited, and the integrity
  test asserts this rule still reproduces every frozen label. A divergence
  shows up as baseline C falling below 100%."
  [{:keys [needs-human? deploy-age-min p95-ms budget-ms err-rate
           restarts saturation]}]
  (cond
    needs-human?                                              :page
    (and deploy-age-min (< deploy-age-min 30)
         (or (> p95-ms budget-ms) (> err-rate 0.05)))         :rollback
    (and (> restarts 2) (<= p95-ms budget-ms) (<= err-rate 0.05)) :restart
    (> err-rate 0.05)                                         :page
    (and (> saturation 0.85) (<= err-rate 0.01)
         (<= p95-ms (* 1.5 budget-ms)))                       :scale
    :else                                                     :hold))

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

(defn- rel-rate
  "A relational rate over sibling rows, each compared with its group's pivot.

    :invariant           the sibling got the same prediction as the pivot
    :correct-invariant   ... and both predictions were correct
    :responsive          the sibling got a DIFFERENT prediction than the pivot
    :correct-responsive  ... and both predictions were correct

  A constant scorer scores 100% invariance and 0% change rate; the trusted rule
  scores 100% on both correctness variants. Those two shapes are what the
  metric exists to tell apart, and per-role accuracy could not."
  [siblings pivot-of kind]
  (let [pairs (keep (fn [r] (when-let [p (pivot-of (:group r))] [r p])) siblings)]
    (when (seq pairs)
      (/ (count (filter (fn [[r p]]
                          (let [same? (= (:top1 r) (:top1 p))
                                both-right? (and (= (:expected r) (:top1 r))
                                                 (= (:expected p) (:top1 p)))]
                            (case kind
                              :invariant          same?
                              :correct-invariant  (and same? both-right?)
                              :responsive         (not same?)
                              :correct-responsive (and (not same?) both-right?))))
                        pairs))
         (double (count pairs))))))

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
                ctx {:text (context-for state) :expected-for-context expected
                     :state-for-context state}
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
        pivot-of (into {} (for [[g rs] (group-by :group rows)]
                            [g (first (filter #(= :pivot (:role %)) rs))]))
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
     ;; RELATIONAL, comparing each sibling to its group's PIVOT. The earlier
     ;; versions of these were ordinary per-role accuracy and were labelled
     ;; "responsiveness" and "invariance", which they were not: a scorer can be
     ;; accurate on counterfactual rows while never CHANGING its answer between
     ;; a pivot and its counterfactual, and that is the thing being measured.
     :control-invariance (rel-rate controls pivot-of :invariant)
     :correct-control-invariance (rel-rate controls pivot-of :correct-invariant)
     :counterfactual-change (rel-rate counters pivot-of :responsive)
     :correct-counterfactual (rel-rate counters pivot-of :correct-responsive)
     ;; kept, but no longer called responsiveness or invariance
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
  (println (format "  control invariance   %s  (correct %s)  <- same answer as its pivot"
                   (pct (:control-invariance r)) (pct (:correct-control-invariance r))))
  (println (format "  counterfactual change%s  (correct %s)  <- DIFFERENT from its pivot"
                   (pct (:counterfactual-change r)) (pct (:correct-counterfactual r))))
  (println (format "  per-role acc c/f %s  ctrl %s   (plain accuracy, not relational)"
                   (pct (:counterfactual-acc r)) (pct (:control-acc r))))
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

(def reasoning-budget
  "Tokens of bounded reasoning to allow before scoring, 0 to disable.

  These are reasoning models -- handed an open assistant turn they emit <think>
  with probability 0.72 / 0.92 / 0.995 -- so :chat-nothink asks them for an
  answer in a mode they were not trained for. Letting them think first and THEN
  scoring the closed domain is the framing they were built for.

  WHAT THIS DOES NOT CHANGE: the decision is still a closed-domain score over
  the fixed action set. The model still cannot name an action or widen the
  domain; the reasoning is intermediate evidence, never something acted on.

  WHAT IT DOES CHANGE, and these are real costs:
    * price -- N decodes per decision instead of one scoring
    * the exact-spine story -- reasoning is dynamic, so it lands in the delta
      and cannot live in the reusable spine
    * audit -- reasoning is the EVIDENCE for the decision, so #9's rule against
      journalling prompt contents needs a deliberate answer rather than an
      inherited one

  Generated by GREEDY ARGMAX through the ordinary top-k/eval! API, not by a
  sampler. jolt-llama deliberately has no sampler and this does not add one:
  there is no temperature, no seed and no randomness, so a fixture's reasoning
  is reproducible."
  (Integer/parseInt (or (System/getenv "DECIDE_REASONING") "0")))

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
    (report (evaluate "C rule (labelling fn)" (rule-scorer label) policy))
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
                         (mapv (fn [a] {:id a :text (action-text a)}) actions) tk)]
                (println (format "=== model: %s   framing: %s ===" (:desc m) (name framing)))
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
                        raw-score
                        (fn [ctx-text cands]
                          (let [toks ((resolve 'jolt.llama/tokenize) m ctx-text)]
                            ((resolve 'jolt.llama/clear!) s)
                            ((resolve 'jolt.llama/eval!) s toks)
                            (let [st ((resolve 'jolt.llama/save-state) s)
                                  scored ((resolve 'jolt.llama/score-candidates)
                                          s (mapv (fn [c] (assoc c :tokens (get encodings (:id c)))) cands)
                                          {:state st})]
                              (into {} (map (juxt :id :logprob-sum) (:candidates scored))))))
                        scorer (fn [ctx cands]
                                 {:scores (raw-score (:text ctx) cands)
                                  :meta {:scorer-id "jolt-llama/score-candidates@v0"}})
                        ;; COUNTERBALANCED: score under every cyclic rotation of
                        ;; the listed order and average. The diagnostic showed
                        ;; that permuting a purely presentational detail changes
                        ;; the selected action, so a single fixed order measures
                        ;; position bias and decision quality together. Averaging
                        ;; over rotations gives each action the first slot once
                        ;; and cancels that advantage.
                        ;; bounded greedy reasoning, then score the closed domain
                        think-score
                        (fn [state cands order]
                          (let [open (str "<|im_start|>system\n"
                                          "You are a service controller. Answer with exactly one action word.\n"
                                          "<|im_end|>\n<|im_start|>user\n"
                                          (policy-block order) "\n" (render state)
                                          "\nWhich action?<|im_end|>\n"
                                          "<|im_start|>assistant\n<think>\n")
                                close-tok (first ((resolve 'jolt.llama/tokenize) m "</think>" {:add-special? false}))]
                            ((resolve 'jolt.llama/clear!) s)
                            ((resolve 'jolt.llama/eval!) s ((resolve 'jolt.llama/tokenize) m open))
                            ;; greedy argmax, stopping early on </think>
                            (loop [i 0]
                              (when (< i reasoning-budget)
                                (let [t (:token (first ((resolve 'jolt.llama/top-k) s 1 {:pieces? false})))]
                                  (when-not (= t close-tok)
                                    ((resolve 'jolt.llama/eval!) s [t])
                                    (recur (inc i))))))
                            ;; force the block closed, then score
                            ((resolve 'jolt.llama/eval!) s
                             ((resolve 'jolt.llama/tokenize) m "\n</think>\n\n" {:add-special? false}))
                            (let [st ((resolve 'jolt.llama/save-state) s)
                                  scored ((resolve 'jolt.llama/score-candidates)
                                          s (mapv (fn [c] (assoc c :tokens (get encodings (:id c)))) cands)
                                          {:state st})]
                              (into {} (map (juxt :id :logprob-sum) (:candidates scored))))))
                        think-scorer (fn [ctx cands]
                                       {:scores (think-score (:state-for-context ctx) cands actions)
                                        :meta {:scorer-id "jolt-llama/reasoning@v0"}})
                        cb-scorer (fn [ctx cands]
                                    (let [st (:state-for-context ctx)
                                          runs (map #(raw-score (context-for st %) cands)
                                                    (rotations actions))]
                                      {:scores (into {} (for [c cands
                                                              :let [id (:id c)]]
                                                          [id (/ (reduce + (map #(double (get % id)) runs))
                                                                 (double (count runs)))]))
                                       :meta {:scorer-id "jolt-llama/counterbalanced@v0"}}))]
                    (report (evaluate (str "D " (:desc m) " base") scorer policy))
                    (println "  (single fixed action order -- confounded with position bias)")
                    (println)
                    (report (evaluate (str "D' " (:desc m) " counterbal.") cb-scorer policy))
                    (when (pos? reasoning-budget)
                      (println (format "  (reasoning budget: %d greedy tokens before scoring)"
                                       reasoning-budget))
                      (println)
                      (report (evaluate (str "D'' " (:desc m) " +reasoning") think-scorer policy)))
                    (println "  (averaged over all 5 cyclic orders; position advantage cancelled)")
                    (println))))
              (finally (close! s))))
          (finally (close! m)))))

    (println "Reading: baseline C is the labelling rule and MUST score 100% --")
    (println "anything less is a bug in the harness, not a result. Baseline B is")
    (println "what a scorer with no signal degenerates into: perfect on matched")
    (println "controls, zero on counterfactuals. A scorer is qualified only if it")
    (println "beats B on COUNTERFACTUAL accuracy, which is the column that")
    (println "separates deciding from guessing the majority class.")))
