;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; IS THE MODEL EVEN ANSWERING THE QUESTION?
;;
;;   jolt -A:canary -m canary.decide-framing
;;
;; DOMAIN MASS is the precondition every other closed-domain metric depends on:
;; the share of the model's next-token belief that lands on ANY legal action.
;;
;;     domain mass = sum over the closed domain of P(action | context)
;;
;; A qualification score computed at low domain mass is close to meaningless.
;; If 99.6% of the model's belief is on tokens outside the domain, the ranking
;; among the remaining 0.4% is a ranking of things the model was not trying to
;; say -- it is not a decision, and calling the model "unqualified" on that
;; basis blames the model for a question it was never asked.
;;
;; Measured null-prior domain mass under the raw-completion framing:
;;
;;     Qwen3.5-0.8B    8.5%
;;     Qwen3.5-2B     54.2%
;;     Qwen3.6-27B     0.4%   <- the 27B carries a chat template
;;
;; The 27B is an instruct model being handed a raw completion ending in
;; "ACTION:". This probe compares framings and encodings so the comparison
;; across model sizes is between models that were all actually asked.

(ns canary.decide-framing
  (:require [clojure.string :as str]
            [jolt.llama :as llama]))

(def model-path
  (or (System/getenv "JOLT_LLAMA_MODEL")
      (throw (ex-info "set JOLT_LLAMA_MODEL" {}))))

(def actions [:hold :scale :rollback :restart :page])

(def descriptions
  {:hold "nothing to do" :scale "add capacity for demand"
   :rollback "undo a recent deploy that caused a regression"
   :restart "cycle an unhealthy process" :page "escalate to a human"})

(def probe-state
  "One fixture with an unambiguous mechanical answer: a regression minutes
  after a deploy, which the labelling rule calls :rollback."
  (str "SERVICE STATE\n"
       "  latency_p95_ms: 780 (budget 120)\n"
       "  error_rate: 0.001\n  saturation: 0.30\n"
       "  cpu_pct: 35\n  mem_pct: 40\n"
       "  process_restarts_recent: 0\n"
       "  minutes_since_deploy: 4\n"
       "  needs_human_authority: no\n"))

(def policy-text
  (str "Choose exactly one action: " (str/join ", " (map name actions)) ".\n"
       (apply str (for [a actions]
                    (format "  %-8s - %s\n" (name a) (descriptions a))))))

(defn completion-prompt []
  (str "CONTROLLER POLICY v1\n" policy-text "\n" probe-state "\nACTION:"))

(defn chat-prompt
  "ChatML, which is what a Qwen instruct model's template expects. The
  assistant turn is opened and left for the model to continue."
  []
  (str "<|im_start|>system\n"
       "You are a service controller. Answer with exactly one action word.\n"
       "<|im_end|>\n"
       "<|im_start|>user\n"
       "CONTROLLER POLICY v1\n" policy-text "\n" probe-state
       "\nWhich action?<|im_end|>\n"
       "<|im_start|>assistant\n"))

(defn chat-nothink-prompt
  "ChatML with the reasoning block explicitly opened AND CLOSED.

  All three models here are reasoning models: handed an open assistant turn
  they want to emit <think> (0.72, 0.92 and 0.995 respectively), which is why
  the plain chat framing has near-zero domain mass. Pre-closing the block is
  the documented Qwen3 way to ask for an answer without a reasoning pass, and
  it is the only framing under which a reasoning model can be asked for a bare
  next token at all."
  []
  (str "<|im_start|>system\n"
       "You are a service controller. Answer with exactly one action word.\n"
       "<|im_end|>\n"
       "<|im_start|>user\n"
       "CONTROLLER POLICY v1\n" policy-text "\n" probe-state
       "\nWhich action?<|im_end|>\n"
       "<|im_start|>assistant\n<think>\n\n</think>\n\n"))

(defn -main [& _]
  (llama/with-model [m {:path model-path}]
    (llama/with-session [s m {:context-size 4096 :threads 8}]
      (println "model:" (:desc m))
      (println)
      (let [tk (fn [t] (vec (llama/tokenize m t {:add-special? false})))
            ;; Encodings are model AND framing dependent: after "ACTION:" the
            ;; natural continuation carries a leading space, while after an
            ;; opened chat turn it usually does not.
            variants {:leading-space #(str " " (name %))
                      :bare         #(name %)}
            framings {:completion   (completion-prompt)
                      :chat         (chat-prompt)
                      :chat-nothink (chat-nothink-prompt)}]
        (doseq [[fname prompt] framings]
          (println (format "=== framing %s (%d chars) ===" (name fname) (count prompt)))
          (llama/clear! s)
          (llama/eval! s (llama/tokenize m prompt))
          ;; What the model actually WANTS to emit. Low domain mass alone says
          ;; the answer is elsewhere; this says where, which is usually the
          ;; whole diagnosis -- a reasoning tag, a newline, or prose means the
          ;; framing asked for something other than a bare action.
          (println (format "  model's own top-5: %s"
                           (pr-str (mapv (fn [t] [(:piece t)
                                                  (format "%.4f" (Math/exp (double (:logprob t))))])
                                         (llama/top-k s 5)))))
          (doseq [[vname f] variants]
            (let [enc (into {} (for [a actions] [a (tk (f a))]))
                  singles (into {} (filter (fn [[_ t]] (= 1 (count t))) enc))
                  all-single? (= (count actions) (count singles))]
              (if-not all-single?
                (println (format "  %-14s NOT all single-token: %s"
                                 (name vname)
                                 (pr-str (into {} (for [[a t] enc] [a (count t)])))))
                (let [lps (into {} (for [[a t] singles]
                                     [a (llama/token-logprob s (first t))]))
                      mass (reduce + (map #(Math/exp (double %)) (vals lps)))
                      ranked (sort-by (comp - val) lps)]
                  (println (format "  %-14s domain mass %7.4f%%   top=%-9s  ranking=%s"
                                   (name vname) (* 100.0 mass)
                                   (name (first (first ranked)))
                                   (pr-str (mapv (comp name first) ranked))))))))
          (println))
        (println "Reading: the expected action for this probe is :rollback.")
        (println "A framing with near-zero domain mass is not measuring a")
        (println "decision -- it is ranking tokens the model was not trying to")
        (println "emit. Compare model sizes only where the mass is comparable.")))))
