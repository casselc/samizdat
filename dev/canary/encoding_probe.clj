;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; Find a DELIBERATE one-token encoding for each controller action.
;;
;;   jolt -A:canary -m canary.encoding-probe
;;
;; Issue #8: " ROLLBACK" is three tokens and " RESTART" is two under this
;; tokenizer. The answer is not to truncate them -- that scored a fragment as
;; if it were the action -- but to choose an encoding that genuinely is one
;; token, or to accept multi-token candidates and their cost.
;;
;; This probes candidate spellings and reports which are single-token and
;; mutually distinct. The choice is then written down explicitly, per model.

(ns canary.encoding-probe
  (:require [jolt.llama :as llama]))

(def model-path
  (or (System/getenv "JOLT_LLAMA_MODEL")
      (throw (ex-info "set JOLT_LLAMA_MODEL" {}))))

(def spellings
  "Candidate model-facing encodings per semantic action, most preferred first.
  Semantics must survive the choice: an encoding a reader would not recognise
  as the action is not a cheaper encoding, it is a different prompt."
  {:hold     [" HOLD" " Hold" " hold" " WAIT" " NONE"]
   :scale    [" SCALE" " Scale" " scale" " GROW" " UP"]
   :rollback [" ROLLBACK" " Rollback" " rollback" " REVERT" " Revert"
              " revert" " UNDO" " Undo" " undo" " BACK" " Back"]
   :restart  [" RESTART" " Restart" " restart" " REBOOT" " Reboot"
              " reboot" " BOUNCE" " Bounce" " bounce" " CYCLE" " KILL"]
   :page     [" PAGE" " Page" " page" " ALERT" " Alert" " ESCALATE"]})

(defn -main [& _]
  (llama/with-model [m {:path model-path}]
    (println "model:" (:desc m))
    (println)
    (let [tk (fn [t] (vec (llama/tokenize m t {:add-special? false})))
          results (into {} (for [[action opts] spellings]
                             [action (for [o opts
                                           :let [toks (tk o)]]
                                       {:text o :tokens toks :n (count toks)})]))]
      (doseq [[action rows] results]
        (println (format "%s:" (name action)))
        (doseq [{:keys [text tokens n]} rows]
          (println (format "  %-12s n=%d %s %s"
                           (pr-str text) n (pr-str tokens)
                           (if (= 1 n) "<- single token" ""))))
        (println))

      ;; the first single-token spelling per action, and whether they collide
      (let [picks (into {} (for [[action rows] results]
                             [action (first (filter #(= 1 (:n %)) rows))]))
            missing (keep (fn [[a p]] (when-not p a)) picks)
            toks (keep (fn [[_ p]] (some-> p :tokens first)) picks)]
        (println "--- first single-token spelling per action ---")
        (doseq [[action p] picks]
          (println (format "  %-9s %s" (name action)
                           (if p (format "%-12s token=%d" (pr-str (:text p))
                                         (first (:tokens p)))
                               "NONE FOUND"))))
        (println)
        (println "  all actions have a single-token encoding:" (empty? missing))
        (when (seq missing) (println "  MISSING:" (pr-str (vec missing))))
        (println "  token ids are distinct:" (= (count toks) (count (distinct toks))))
        (println)
        (println "A single-token vocabulary is the preferred v0 controller ABI:")
        (println "no candidate evaluation, one base distribution, exactly")
        (println "comparable. But it must be VERIFIED per model -- these ids are")
        (println "meaningless under a different tokenizer.")))))
