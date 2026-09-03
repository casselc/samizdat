;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; OFFLINE margin-threshold sweep over frozen qualification rows.
;;
;;   jolt -A:canary -m canary.margin-sweep docs/canary/qualification-rows-2b.edn
;;
;; Issue #3 asked whether the margin guard is enough. On the 0.8B it appeared
;; to be: the model was too unconfident to act, so the guard caught everything.
;; The 27B then acted confidently and wrongly on 30-40% of fixtures, which is
;; the failure the guard exists to prevent and did not.
;;
;; The question a single operating point cannot answer is whether SOME other
;; threshold would have been safe. This re-derives the decision at every
;; threshold from the recorded per-row margins -- no model run -- and reports
;; the cost of each. A guard is only meaningful if precision rises with the
;; threshold; if it is flat, the margin carries no information about
;; correctness and no setting of it can rescue the scorer.

(ns canary.margin-sweep
  (:require [clojure.string :as str]))

(defn- pct [x] (if x (format "%5.1f%%" (* 100.0 x)) "    -"))

(defn sweep
  "Re-derive act/defer at threshold t from recorded margins."
  [rows t]
  (let [scored (filter :margin rows)
        act    (filter #(>= (double (:margin %)) t) scored)
        defer  (remove #(and (:margin %) (>= (double (:margin %)) t)) rows)
        ok     (filter #(= (:expected %) (:top1 %)) act)
        wrong  (filter #(not= (:expected %) (:top1 %)) act)
        ;; a deferral is CORRECT when acting would have been wrong
        good-defer (filter #(not= (:expected %) (:top1 %)) defer)
        counters   (filter #(= :counter (:role %)) act)
        c-ok       (filter #(= (:expected %) (:top1 %)) counters)
        n (count rows)]
    {:t t
     :coverage (/ (count act) (double n))
     :precision (when (seq act) (/ (count ok) (double (count act))))
     :wrong-confident (/ (count wrong) (double n))
     :correct-defer (when (seq defer) (/ (count good-defer) (double (count defer))))
     :counter-n (count counters)
     :counter-precision (when (seq counters) (/ (count c-ok) (double (count counters))))}))

(defn faithful?
  "The re-derivation must reproduce the RECORDED decisions at the threshold the
  rows were captured under. If it does not, every other row of the sweep is
  fiction: it would mean :margin is not the quantity the policy actually gated
  on. Checked rather than eyeballed."
  [rows t]
  (let [mismatch (remove (fn [r]
                           (let [act? (and (:margin r) (>= (double (:margin r)) t))]
                             (= act? (= :act (:decision r)))))
                         rows)]
    {:ok? (empty? mismatch)
     :n-mismatch (count mismatch)
     :examples (mapv #(select-keys % [:id :margin :decision :reason]) (take 3 mismatch))}))

(defn -main [& args]
  (let [path (or (first args) "docs/canary/qualification-rows.edn")
        data (read-string (slurp path))
        thresholds (concat [0.0] (map #(/ % 4.0) (range 1 17)))]
    (println (format "margin sweep: %s" path))
    (when-let [m (:model data)]
      (println (format "model: %s (%s)  encodings-verified=%s"
                       (:desc m) (:file m) (:encodings-verified? m))))
    (println (format "framing=%s  policy-at-capture=%s"
                     (:framing data) (pr-str (:policy data))))
    (println)
    (doseq [{:keys [scorer rows]} (:scorers data)]
      (println (format "=== %s  (n=%d) ===" scorer (count rows)))
      (let [f (faithful? rows (double (:min-margin (:policy data))))]
        (println (format "   re-derivation faithful at capture threshold %.2f: %s%s"
                         (double (:min-margin (:policy data)))
                         (:ok? f)
                         (if (:ok? f) ""
                             (format "  (%d mismatched, e.g. %s)"
                                     (:n-mismatch f) (pr-str (:examples f)))))))
      (println "   thresh  coverage  precision  wrong+conf  correct-defer  c/f prec (n)")
      (doseq [t thresholds]
        (let [r (sweep rows t)]
          (println (format "   %5.2f    %s    %s     %s        %s      %s (%d)"
                           (:t r) (pct (:coverage r)) (pct (:precision r))
                           (pct (:wrong-confident r)) (pct (:correct-defer r))
                           (pct (:counter-precision r)) (:counter-n r)))))
      ;; Does the margin carry ANY information about correctness? If precision
      ;; is flat in the threshold, the guard is a coverage knob and nothing
      ;; more -- it trades away decisions without buying correctness.
      (let [ps (keep #(:precision (sweep rows %)) thresholds)]
        (println (format "   precision range over thresholds: %s -> %s  (spread %s)"
                         (pct (first ps)) (pct (last ps))
                         (pct (when (seq ps) (- (apply max ps) (apply min ps)))))))
      (println))))
