;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; THE TWO-TURN EXACT-SPINE INTEGRATION CANARY (issue #10).
;;
;;   jolt -A:canary -m canary.exact-spine
;;
;; jolt-llama already validated exact token-boundary state reuse on its own.
;; What this proves is narrower and is the part Samizdat owns: that when
;; SAMIZDAT constructs the prompt projection, the token-identity invariant
;; survives its hands.
;;
;;   canonical stable spine S
;;     -> save native state at an EXACT token boundary
;;     -> later, verify the incoming prompt begins with exactly those tokens
;;     -> restore, append only the dynamic delta
;;     -> score the same authorized domain
;;     -> compare against a full recompute
;;
;; Deliberately bounded. Two spines and two dynamic states; no cache manager,
;; no scheduler, no eviction, no residency policy, no sequence forking. An
;; integration canary is not the beginning of a state store.
;;
;; A STABLE TEXT PREFIX IS NOT A STABLE TOKEN PREFIX. The boundary is computed
;; token-for-token against the canonical full input, never assumed from the
;; text. On a prefix mismatch this cold-rebases explicitly rather than guessing.

(ns canary.exact-spine
  (:require [jolt.llama :as llama]
            [samizdat.decide :as decide]))

(def model-path
  (or (System/getenv "JOLT_LLAMA_MODEL")
      (throw (ex-info "set JOLT_LLAMA_MODEL" {}))))

(def jolt-llama-sha (or (System/getenv "JOLT_LLAMA_SHA") "unrecorded"))

(def actions
  [{:id :hold :text " hold"} {:id :scale :text " scale"}
   {:id :rollback :text " rollback"} {:id :restart :text " restart"}
   {:id :page :text " page"}])

;; ------------------------------------------------ the semantic projection

(defn spine-text
  "The STABLE half of the controller prompt: policy and topology. Large, and
  identical across turns, which is the whole reason to keep its state."
  [tag n-services]
  (str "CONTROLLER POLICY v1 [" tag "]\n"
       "Choose exactly one action: hold, scale, rollback, restart, page.\n"
       "TOPOLOGY\n"
       (apply str
              (for [i (range n-services)]
                (format "  svc%03d: region=r%d tier=%d budget=%dms owner=team%d\n"
                        i (mod i 7) (mod i 4) (+ 80 (mod (* i 31) 400)) (mod i 11))))))

(defn delta-text
  "The DYNAMIC half: current state. Small, and different every turn."
  [epoch n]
  (str "\nCURRENT STATE\n"
       (apply str
              (for [i (range n)]
                (format "  svc%03d: p95=%dms err=%d cpu=%d%%\n"
                        i (+ 40 (mod (+ (* i 13) (* epoch 31)) 500))
                        (mod (+ i epoch) 9) (+ 20 (mod (+ (* i 7) epoch) 70)))))
       "\nACTION:"))

(defn exact-boundary
  "The longest TOKEN prefix the spine shares with the full canonical prompt.

  Computed, never assumed. Tokenizing the spine alone and trusting its length
  is exactly the bug the token-identity contract exists to prevent: a BPE merge
  across the seam moves the final stable token, so the spine's own tokenization
  can be one token longer than the prefix it actually shares."
  [model spine full]
  (let [sp (llama/tokenize model spine)
        fu (llama/tokenize model full)]
    {:spine-tokens sp :full-tokens fu
     :n-exact (count (take-while true? (map = sp fu)))}))

;; --------------------------------------------------------------- scoring

(defn score-from-here
  "Score the authorized domain against whatever the session currently holds."
  [session encodings domain]
  (let [state (llama/save-state session)
        scored (llama/score-candidates
                session
                (mapv (fn [c] (assoc c :tokens (get encodings (:id c))))
                      (:domain/candidates domain))
                {:state state})]
    {:scores (into {} (map (juxt :id :logprob-sum) (:candidates scored)))
     :convention (:convention scored)}))

(defn -main [& _]
  (llama/with-model [m {:path model-path}]
    (llama/with-session [s m {:context-size 8192 :threads 8}]
      (let [tk (fn [t] (llama/tokenize m t {:add-special? false}))
            enc (decide/verify-encodings actions tk)]
        (println "model:" (:desc m))
        (println "model sha256:" (:content-id m))
        (println "jolt-llama:" jolt-llama-sha)
        (println "encodings verified:" (:ok? enc))
        (when-not (:ok? enc)
          (println "ABORTING: unverified encodings measure fragments (#8)")
          (System/exit 1))
        (let [encodings (into {} (map (juxt :id :tokens) (:encodings enc)))
              ;; authorize the actions WITH their verified encodings attached.
              ;; Authorizing the bare vocabulary left the candidates with no
              ;; :tokens, so comparable? failed and both arms deferred with
              ;; :reason/not-comparable -- agreeing for a reason that had
              ;; nothing to do with the spine, which is the kind of green that
              ;; proves nothing.
              domain (decide/authorize
                      (mapv (fn [a] (assoc a :tokens (get encodings (:id a)))) actions)
                      {:legality (decide/all-legal)
                       :id :canary/exact-spine :revision "v1"
                       :authority :canary-fixture})
              policy {:min-margin 0.5 :require-comparable? true}
              spine (spine-text "alpha" 120)]

          ;; ---- TURN 1: cold, and save at the exact boundary
          (println)
          (println "--- turn 1: cold evaluate the spine, save at the exact boundary ---")
          (let [full1 (str spine (delta-text 1 30))
                {:keys [spine-tokens full-tokens n-exact]} (exact-boundary m spine full1)]
            (println (format "  spine tokenizes to %d tokens" (count spine-tokens)))
            (println (format "  full prompt is     %d tokens" (count full-tokens)))
            (println (format "  EXACT token boundary %d  (BPE merge cost %d token(s))"
                             n-exact (- (count spine-tokens) n-exact)))
            (println "  a text prefix is NOT a token prefix:"
                     (not= (count spine-tokens) n-exact))

            (let [stable (vec (take n-exact full-tokens))
                  t0 (System/currentTimeMillis)
                  _ (llama/clear! s)
                  _ (llama/eval! s stable)
                  t-spine (- (System/currentTimeMillis) t0)
                  saved (llama/save-state s)]
              (println (format "  spine eval %d ms, state %d bytes for %d tokens"
                               t-spine (:state-bytes saved) (:n-tokens saved)))

              ;; ---- TURN 2: a DIFFERENT dynamic state over the same spine
              (println)
              (println "--- turn 2: new dynamic state, restore vs full recompute ---")
              (let [full2 (str spine (delta-text 2 30))
                    toks2 (vec (llama/tokenize m full2))
                    ;; the identity check Samizdat owns
                    reusable? (= stable (vec (take n-exact toks2)))]
                (println (format "  incoming prompt %d tokens; first %d identical to the saved spine: %s"
                                 (count toks2) n-exact reusable?))

                (if-not reusable?
                  (do (println "  PREFIX MISMATCH -> cold rebase, explicitly.")
                      (llama/clear! s)
                      (llama/eval! s toks2))
                  (let [;; arm A: full recompute
                        ta (System/currentTimeMillis)
                        _ (llama/clear! s)
                        _ (llama/eval! s toks2)
                        t-cold (- (System/currentTimeMillis) ta)
                        cold (score-from-here s encodings domain)

                        ;; arm B: restore the exact spine, append only the delta
                        suffix (vec (drop n-exact toks2))
                        tb (System/currentTimeMillis)
                        _ (llama/clear! s)
                        _ (llama/load-state! s saved toks2)
                        t-restore (- (System/currentTimeMillis) tb)
                        tc (System/currentTimeMillis)
                        _ (llama/eval! s suffix)
                        t-suffix (- (System/currentTimeMillis) tc)
                        warm (score-from-here s encodings domain)

                        ids (map :id actions)
                        deltas (for [id ids]
                                 [id (abs (- (double (get (:scores cold) id))
                                             (double (get (:scores warm) id))))])
                        max-d (apply max (map second deltas))

                        dec-cold (decide/decide {:scorer (fn [_ _] {:scores (:scores cold)})
                                                 :domain domain :policy policy})
                        dec-warm (decide/decide {:scorer (fn [_ _] {:scores (:scores warm)})
                                                 :domain domain :policy policy})]

                    (println (format "  reused %d of %d tokens (%.1f%%), appended %d"
                                     n-exact (count toks2)
                                     (* 100.0 (/ n-exact (double (count toks2))))
                                     (count suffix)))
                    (println (format "  cold %d ms   restore %d ms + suffix %d ms = %d ms   speedup %.2fx"
                                     t-cold t-restore t-suffix (+ t-restore t-suffix)
                                     (double (/ t-cold (max 1 (+ t-restore t-suffix))))))
                    (println)
                    (println "  action        cold          warm        |delta|")
                    (doseq [id ids]
                      (println (format "  %-9s %11.6f %11.6f %11.8f"
                                       (name id)
                                       (double (get (:scores cold) id))
                                       (double (get (:scores warm) id))
                                       (abs (- (double (get (:scores cold) id))
                                               (double (get (:scores warm) id)))))))
                    (println)
                    (println (format "  max |delta| over the domain: %.8f" max-d))
                    (println (format "  ranking identical:  %s"
                                     (= (mapv :id (:domain dec-cold))
                                        (mapv :id (:domain dec-warm)))))
                    (println (format "  same decision:      %s / %s"
                                     (name (:decision dec-cold)) (name (:decision dec-warm))))
                    (println (format "  same selected:      %s"
                                     (= (:selected dec-cold) (:selected dec-warm))))
                    (println (format "  scoring convention: %s" (:convention cold)))

                    ;; ---- the negative case, asserted rather than assumed
                    (println)
                    (println "--- a DIFFERENT spine must be refused, not silently reused ---")
                    (let [other (spine-text "beta" 120)
                          full-other (str other (delta-text 2 30))
                          toks-other (vec (llama/tokenize m full-other))
                          refused (try (llama/load-state! s saved toks-other)
                                       :ACCEPTED
                                       (catch Throwable e
                                         (:jolt.llama/error (ex-data e))))]
                      (println "  restoring spine alpha's state under spine beta:"
                               (pr-str refused))
                      (println "  refused with a prefix mismatch:"
                               (= :state/prefix-mismatch refused)))))))))))))
