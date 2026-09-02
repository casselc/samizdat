;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; WHY does the scorer fail qualification? Prior, position, or state?
;;
;;   jolt -A:canary -m canary.decide-diagnostics
;;
;; The v0 qualification says Qwen3.5-0.8B scores 13.5% top-1, below a constant
;; :hold (37.8%), choosing :scale for 36 of 37 fixtures. "Unqualified" is a
;; verdict, not a diagnosis, and the two point at very different work:
;;
;;   the model cannot do this            -> train or replace it
;;   the model is not CONDITIONING       -> fix the framing first
;;
;; A detail worth chasing: the embedded canary, on a differently worded prompt,
;; got a constant :hold instead. A model with signal should not flip its
;; constant answer when the wording changes; a model reporting an unconditional
;; token prior should do exactly that.
;;
;; Three measurements, cheap and decisive:
;;
;;   A NULL PRIOR    score the domain with the policy but NO state. If the
;;                   conditioned rankings equal this, the model is reading its
;;                   prior and ignoring the state.
;;   B CONDITIONING  per fixture, how far the scores move from the prior, and
;;                   how often the argmax differs from the prior's argmax.
;;   C POSITION      permute the order the actions are LISTED in the prompt. If
;;                   the argmax follows the list position, the result is a
;;                   prompt artifact, not a judgement.

(ns canary.decide-diagnostics
  (:require [clojure.string :as str]
            [jolt.llama :as llama]
            [samizdat.decide :as decide]))

(def model-path
  (or (System/getenv "JOLT_LLAMA_MODEL")
      (throw (ex-info "set JOLT_LLAMA_MODEL" {}))))

(def fixtures (read-string (slurp "resources/decide-eval/v0.edn")))

(def action-order [:hold :scale :rollback :restart :page])

(def encodings-text
  {:hold " hold" :scale " scale" :rollback " rollback"
   :restart " restart" :page " page"})

(def descriptions
  {:hold     "nothing to do"
   :scale    "add capacity for demand"
   :rollback "undo a recent deploy that caused a regression"
   :restart  "cycle an unhealthy process"
   :page     "escalate to a human"})

(defn render [{:keys [needs-human? deploy-age-min p95-ms budget-ms err-rate restarts
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

(defn context
  "The prompt. `order` controls the order actions are LISTED, which is the
  variable measurement C manipulates. `state` nil renders the NULL context:
  identical policy, no state at all."
  [order state]
  (str "CONTROLLER POLICY v1\n"
       "Choose exactly one action: " (str/join ", " (map name order)) ".\n"
       (apply str (for [a order]
                    (format "  %-8s - %s\n" (name a) (descriptions a))))
       "\n"
       (if state (render state) "SERVICE STATE\n  (no state available)\n")
       "\nACTION:"))

(defn -main [& _]
  (llama/with-model [m {:path model-path}]
    (llama/with-session [s m {:context-size 4096 :threads 8}]
      (let [tk (fn [t] (llama/tokenize m t {:add-special? false}))
            enc (decide/verify-encodings
                 (mapv (fn [a] {:id a :text (encodings-text a)}) action-order) tk)
            _ (when-not (:ok? enc)
                (println "ABORT: unverified encodings") (System/exit 1))
            encodings (into {} (map (juxt :id :tokens) (:encodings enc)))
            cands (mapv (fn [a] {:id a :tokens (encodings a)}) action-order)
            score (fn [ctx-text]
                    (llama/clear! s)
                    (llama/eval! s (llama/tokenize m ctx-text))
                    (let [st (llama/save-state s)
                          r (llama/score-candidates s cands {:state st})]
                      (into {} (map (juxt :id :logprob-sum) (:candidates r)))))
            rank-of (fn [sc] (mapv first (sort-by (comp - val) sc)))]

        (println "model:" (:desc m))
        (println)

        ;; ---- A. the NULL PRIOR
        (println "=== A. null prior: same policy, NO state ===")
        (let [prior (score (context action-order nil))
              prior-rank (rank-of prior)]
          (doseq [a prior-rank]
            (println (format "  %-9s %9.5f" (name a) (double (prior a)))))
          (println "  prior ranking:" (pr-str prior-rank))
          (println)

          ;; ---- B. CONDITIONING: how far does state move it?
          (println "=== B. conditioning: do the 37 fixtures move the scores? ===")
          (let [rows (for [{:keys [id expected state]} fixtures]
                       (let [sc (score (context action-order state))
                             r (rank-of sc)
                             deltas (map (fn [a] (abs (- (double (sc a)) (double (prior a)))))
                                         action-order)]
                         {:id id :expected expected :rank r :top (first r)
                          :same-as-prior? (= r prior-rank)
                          :same-top? (= (first r) (first prior-rank))
                          :max-delta (apply max deltas)
                          :mean-delta (/ (reduce + deltas) (double (count deltas)))}))
                rows (vec rows)
                n (count rows)]
            (println (format "  identical FULL ranking to the prior:  %d/%d (%.1f%%)"
                             (count (filter :same-as-prior? rows)) n
                             (* 100.0 (/ (count (filter :same-as-prior? rows)) (double n)))))
            (println (format "  identical ARGMAX to the prior:        %d/%d (%.1f%%)"
                             (count (filter :same-top? rows)) n
                             (* 100.0 (/ (count (filter :same-top? rows)) (double n)))))
            (println (format "  mean |score - prior| across actions:  %.4f"
                             (/ (reduce + (map :mean-delta rows)) (double n))))
            (println (format "  max  |score - prior| seen:            %.4f"
                             (apply max (map :max-delta rows))))
            (println (format "  distinct rankings over 37 states:     %d"
                             (count (distinct (map :rank rows)))))
            (println (format "  distinct argmaxes over 37 states:     %s"
                             (pr-str (frequencies (map :top rows)))))
            (println)
            (println "  Reading: if the rankings match the prior, the model is")
            (println "  reporting what it believes before seeing any state, and")
            (println "  the failure is CONDITIONING rather than capability.")
            (println))

          ;; ---- C. POSITION BIAS
          (println "=== C. position bias: permute the ORDER actions are listed ===")
          (let [orders [action-order
                        [:scale :hold :rollback :restart :page]
                        [:page :restart :rollback :scale :hold]
                        [:rollback :page :hold :scale :restart]]
                probe (:state (first (filter #(= :regress-latency (:id %)) fixtures)))]
            (println "  fixture :regress-latency (expected :rollback)")
            (doseq [o orders]
              (let [sc (score (context o probe))
                    r (rank-of sc)]
                (println (format "  listed %-42s -> top=%-9s rank=%s"
                                 (pr-str (mapv name o)) (name (first r))
                                 (pr-str (mapv name r))))))
            (println)
            (println "  Reading: if the argmax tracks the LIST POSITION, the")
            (println "  result is an artifact of the prompt, not a judgement")
            (println "  about the service. If it is stable across permutations,")
            (println "  the preference is at least a real preference.")))))))
