;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; THE EMBEDDED CLOSED-DOMAIN DECISION CANARY.
;;
;;   jolt -A:canary run dev/canary/embedded.clj
;;
;; Needs JOLT_LLAMA_LIB and JOLT_LLAMA_MODEL. Nothing else in samizdat needs
;; either, which is the point: this file is the only place a real inference
;; engine is loaded, it lives behind the :canary alias, and the mechanism it
;; exercises is tested in the ordinary suite with no model at all.
;;
;; What it proves, in one run:
;;
;;   trusted state -> finite legal domain -> jolt-llama embedded scoring
;;                 -> trusted selection -> journalled, auditable decision
;;
;; and, just as importantly, what it proves CANNOT happen: the model never
;; names an action, never widens the domain, and emits no text that is acted
;; on. The scorer below returns a map of id -> log-probability and nothing
;; else. There is no sampler, no grammar, and no generated string anywhere in
;; this file.

(ns canary.embedded
  (:require [clojure.string]
            [jolt.llama :as llama]
            [mycelium.cell :as cell]
            [samizdat.cells :as cells]
            [samizdat.decide :as decide]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(def model-path
  (or (System/getenv "JOLT_LLAMA_MODEL")
      (throw (ex-info "set JOLT_LLAMA_MODEL to a .gguf file" {}))))

(def jolt-llama-sha
  "The jolt-llama coordinate this canary is bound to, recorded with every
  decision. Passed in rather than guessed so the evidence names the exact
  library that produced it."
  (or (System/getenv "JOLT_LLAMA_SHA") "unrecorded"))

(defn model-coordinate
  "Resolve the model's ARTIFACT identity ONCE, when the binding is built.

  A description like \"qwen35 0.8B Q4_0\" is display metadata, not identity:
  it collides across quantizations and says nothing about which bytes were
  loaded. jolt-llama already computes the GGUF sha256 at open, so this reads it
  rather than hashing a multi-gigabyte file per decision."
  [m]
  {:model-id (:desc m)
   :model-sha256 (:content-id m)
   :model-file (some-> (:path m) (clojure.string/split #"/") last)
   :tokenizer-family "qwen35"
   :jolt-llama-sha jolt-llama-sha})

;; ---------------------------------------------------------- the scorer

(defn make-scorer
  "Build the scorer seam over an open jolt-llama session.

  This is the ONLY adapter between samizdat and an inference engine that this
  canary introduces, and it is a function of two arguments. It is deliberately
  not a samizdat.llm.adapter/Adapter: that protocol is chat-url, auth-headers,
  chat-body, parse-chat and prefill, all of which are generation over HTTP.
  Ranking a closed set needs none of them.

  Candidates are tokenized ONCE here and their token vectors cached on the
  candidate, because tokenizing per call would re-derive the same thing every
  decision and, worse, invites the seam bug jolt-llama exists to prevent."
  [model session]
  (fn [context candidates]
    (let [tokens (llama/tokenize model context)]
      (llama/clear! session)
      (llama/eval! session tokens)
      (let [state (llama/save-state session)
            scored (llama/score-candidates session candidates {:state state})]
        {:scores (into {} (map (juxt :id :logprob-sum) (:candidates scored)))
         ;; carried for the console summary only; NOT journalled
         :meta {:convention (:convention scored)
                :homogeneous? (:homogeneous? scored)}}))))

;; ------------------------------------------------- the trusted domain

(def vocabulary
  "The closed action set. Written down HERE, by trusted code, before any model
  is consulted.

  LOWERCASE, and that is a measured decision rather than a style one. Under this
  tokenizer \" ROLLBACK\" is THREE tokens and \" RESTART\" is two, while every
  action is exactly one token in lowercase:

      hold 3222   scale 5281   rollback 58377   restart 16526   page 2081

  all distinct. dev/canary/encoding_probe.clj is the probe that found this.

  The earlier canary wrote uppercase and took the first token, which scored a
  FRAGMENT as if it were the action -- token 423 standing in for ROLLBACK. That
  is what issue #8 was about, and it is why the encoding is now verified rather
  than assumed.

  A single-token vocabulary is the preferred v0 controller ABI: no candidate
  evaluation, one base distribution, exactly comparable under the validated
  jolt-llama path. But it is a property of THIS model and must be re-verified
  whenever the tokenizer coordinate changes."
  [{:id :hold     :text " hold"}
   {:id :scale    :text " scale"}
   {:id :rollback :text " rollback"}
   {:id :restart  :text " restart"}
   {:id :page     :text " page"}])

(defn context-for
  "Render the decision context from trusted state. Ordinary template rendering:
  the state is data samizdat already holds, and nothing the model says can
  reach back into it."
  [state-lines]
  (str "CONTROLLER POLICY v1\n"
       ;; the instruction lists the actions in the SAME casing that is scored;
       ;; asking for HOLD and scoring " hold" measures a different thing
       "Choose exactly one action: hold, scale, rollback, restart, page.\n"
       "TOPOLOGY\n"
       "  api: region=r1 tier=0 budget=120ms\n"
       "  db:  region=r1 tier=1 budget=400ms\n"
       "CURRENT STATE\n" state-lines
       "ACTION:"))

(def situations
  [["healthy"      "  api: p95=95ms err=0\n  db: p95=210ms err=0\n"]
   ["api degraded" "  api: p95=780ms err=41\n  db: p95=1900ms err=88\n"]])

;; ------------------------------------------------------------- the run

(defn -main [& _]
  (llama/with-model [m {:path model-path}]
    (llama/with-session [s m {:context-size 4096 :threads 8}]
      (let [scorer (make-scorer m s)
            tokenize-full (fn [text] (llama/tokenize m text {:add-special? false}))
            ;; #8: tokenize the ENTIRE model-facing encoding and VERIFY it.
            ;; The previous (take 1 ...) guaranteed n_tokens == 1 by truncation
            ;; and therefore proved nothing about the encoding -- only that a
            ;; first token exists. If a label is two tokens the answer is a
            ;; different encoding, not a shorter read of this one.
            enc (decide/verify-encodings vocabulary tokenize-full)
            candidates (mapv (fn [{:keys [id tokens]}]
                               (let [v (first (filter #(= id (:id %)) vocabulary))]
                                 (assoc v :tokens tokens)))
                             (:encodings enc))
            policy {:min-margin 0.5 :max-candidates 12 :require-comparable? true}
            model-coord (model-coordinate m)]

        (println "model:" (:desc m))
        (println "model sha256:" (:model-sha256 model-coord))
        (println "jolt-llama:" jolt-llama-sha)
        (println)
        (println "--- action encodings (verified, not truncated) ---")
        (doseq [e (:encodings enc)]
          (println (format "  %-9s %-11s n_tokens=%d tokens=%s piece=%s"
                           (name (:id e)) (pr-str (:text e)) (:n-tokens e)
                           (pr-str (:tokens e))
                           (pr-str (when (= 1 (:n-tokens e))
                                     (llama/token->piece m (first (:tokens e))))))))
        (println "  encodings ok?" (:ok? enc))
        (when-not (:ok? enc)
          (println "  PROBLEMS:" (pr-str (:problems enc)))
          (println)
          (println "  NOT truncating to force single tokens. A multi-token action")
          (println "  needs a deliberate one-token encoding, or the domain must")
          (println "  accept multi-token candidates and the higher scoring cost.")
          (println))
        (println)

        ;; The domain is AUTHORIZED before any model is consulted. This canary's
        ;; operational fixture is genuinely unconstrained, so it says so with an
        ;; explicit all-legal source rather than omitting the rule -- omitting it
        ;; is now refused.
        (let [authorized (decide/authorize candidates
                                           {:legality (decide/all-legal)
                                            :id :canary/controller
                                            :revision "v1"
                                            :authority :canary-fixture})
              decide-one (fn [lines]
                           (decide/decide
                            {:scorer scorer
                             :context (context-for lines)
                             :domain authorized
                             :policy policy
                             :prov-ctx (merge model-coord
                                              {:scorer-id "jolt-llama/score-candidates@v0"
                                               :policy-revision "canary-fixed"
                                               :min-margin (:min-margin policy)})}))]

        (doseq [[label lines] situations]
          (let [record (decide-one lines)]
            (println "situation:" label)
            (doseq [c (:domain record)]
              (println (format "  %d. %-9s score=%9.5f  n_tokens=%s  status=%s"
                               (inc (or (:rank c) -1)) (name (:id c))
                               (double (or (:score c) 0.0)) (:n-tokens c)
                               (name (:scoring-status c)))))
            (println (format "  decision=%s selected=%s margin=%s reason=%s"
                             (name (:decision record))
                             (some-> (:selected record) name)
                             (when (:margin record) (format "%.5f" (:margin record)))
                             (name (:reason record))))
            (println "  journal-safe:" (nil? (decide/leaks? record)))
            (println)))

        ;; ---- the properties this canary is actually asserting
        (println "--- canary assertions ---")
        (let [rec (decide-one (second (first situations)))
              ;; a hostile scorer: names an option outside the domain, scores it
              ;; best, and leaves an authorized option unscored
              hostile (fn [_ _] {:scores {:hold -9.0 :DELETE-EVERYTHING 0.0}})
              hrec (decide/decide {:scorer hostile :context "x"
                                   :domain authorized :policy policy
                                   :prov-ctx model-coord})
              partial-scorer (fn [_ _] {:scores {:hold -0.1}})
              prec (decide/decide {:scorer partial-scorer :context "x"
                                   :domain authorized :policy policy
                                   :prov-ctx model-coord})]
          (println "  no machine state in the record:      " (nil? (decide/leaks? rec)))
          (println "  every offered option is recorded:    "
                   (= (count candidates) (:n-offered rec) (count (:domain rec))))
          (println "  a scorer cannot introduce an option: "
                   (not (contains? (set (map :id (:domain hrec))) :DELETE-EVERYTHING)))
          (println "  an out-of-domain score FAILS CLOSED: "
                   (= :defer (:decision hrec)))
          (println "  a partial score map FAILS CLOSED:    "
                   (and (= :defer (:decision prec))
                        (= :reason/incomplete-scores (:reason prec))))
          (println "  the full domain survives a failure:  "
                   (= (count candidates) (count (:domain prec))))
          (println "  action encodings verified:           " (:ok? enc))
          (println "  scores are exactly comparable:       " (decide/comparable? candidates))
          (println "  provenance carries the model sha:    "
                   (some? (get-in rec [:provenance :model-sha256]))))

        ;; ---- STATE SENSITIVITY, reported because it decides whether any of
        ;; this is useful. The shape can be perfectly sound and still carry no
        ;; signal, and a canary that only checked the shape would not notice.
        (println)
        (println "--- state sensitivity ---")
        (let [by-situation
              (into {} (for [[label lines] situations]
                         [label (:scores (scorer (context-for lines) candidates))]))
              [a b] (map first situations)
              deltas (into {} (for [c candidates
                                    :let [id (:id c)]]
                                [id (- (double (get-in by-situation [b id]))
                                       (double (get-in by-situation [a id])))]))
              ranks (fn [l] (mapv :id (decide/rank candidates (get by-situation l))))]
          (doseq [[id d] (sort-by (comp - abs val) deltas)]
            (println (format "  %-9s delta=%9.5f between %s and %s" (name id) d a b)))
          (println "  ranking under" (pr-str a) "=" (pr-str (ranks a)))
          (println "  ranking under" (pr-str b) "=" (pr-str (ranks b)))
          (println "  ranking CHANGED with state:" (not= (ranks a) (ranks b)))
          (println "  max |delta|:" (format "%.5f" (apply max (map abs (vals deltas))))))

        ;; ---- THE JOURNAL, for real. The claim is an auditable decision, so
        ;; the record goes through the actual cell into an actual SQLite
        ;; journal and is read back out, rather than being inspected in memory
        ;; and asserted to be journal-shaped.
        (println)
        (println "--- journal round trip (real SQLite, through the real cell) ---")
        (cells/load-cells!)
        (let [conn (db/open! ":memory:")]
          (try
            (let [run-id (runs/start-run! conn {:problem "embedded decision canary"
                                                :provider "jolt-llama"
                                                :model (:desc m)
                                                :max-turns 1 :beam-width 1})
                  ;; the scorer travels as DATA, so the ctx stays exactly the
                  ;; run-scoped set every driver already provides
                  ctx {:conn conn :run-id run-id}
                  ;; the registry holds SPECS; the callable is under :handler
                  handler (fn [id] (:handler (cell/get-cell! id)))
                  domain-cell (handler :decide/domain)
                  score-cell  (handler :decide/score)
                  apply-cell  (handler :decide/apply)
                  data0 {:decide/vocabulary candidates
                         ;; legality is explicit: this fixture IS all-legal, and
                         ;; now has to say so rather than rely on a default
                         :decide/all-legal? true
                         :decide/domain-id :canary/controller
                         :decide/domain-revision "v1"
                         ;; transient binding, never journalled
                         :decide/scorer scorer
                         ;; durable identity of that binding, journalled
                         :decide/scorer-id "jolt-llama/score-candidates@v0"
                         :decide/model-coord model-coord
                         :decide/context (context-for (second (second situations)))}
                  data (-> (domain-cell ctx data0)
                           (->> (score-cell ctx))
                           (->> (apply-cell ctx)))
                  ;; read it back the way an auditor would
                  back (journal/last-note conn run-id :decide)]
              (println "  run:" run-id)
              (println "  cell decision:" (:decide/decision data)
                       " action:" (:decide/action data))
              (println "  read back from SQLite:" (some? back))
              ;; printed because an auditor's first question is what the row
              ;; actually contains, not what the writer believed it wrote
              (println "  fields as stored:" (pr-str (sort (map name (keys back)))))
              (let [g (fn [k] (or (get back k) (get back (name k)) (get back (keyword (name k)))))]
                (println "  recorded decision:" (pr-str (g :decision)))
                (println "  recorded selected:" (pr-str (g :selected)))
                (println "  recorded margin:  " (pr-str (g :margin)))
                (println "  recorded domain size:" (count (or (g :domain) []))))
              (println "  round-trip carries no machine state:"
                       (nil? (decide/leaks? back))))
            (finally (db/close conn)))))))))
