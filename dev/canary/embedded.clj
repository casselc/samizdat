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
  (:require [jolt.llama :as llama]
            [mycelium.cell :as cell]
            [samizdat.cells :as cells]
            [samizdat.decide :as decide]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(def model-path
  (or (System/getenv "JOLT_LLAMA_MODEL")
      (throw (ex-info "set JOLT_LLAMA_MODEL to a .gguf file" {}))))

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
  is consulted. Single-token ids so every score is exactly comparable."
  [{:id :hold     :text " HOLD"}
   {:id :scale    :text " SCALE"}
   {:id :rollback :text " ROLLBACK"}
   {:id :restart  :text " RESTART"}
   {:id :page     :text " PAGE"}])

(defn context-for
  "Render the decision context from trusted state. Ordinary template rendering:
  the state is data samizdat already holds, and nothing the model says can
  reach back into it."
  [state-lines]
  (str "CONTROLLER POLICY v1\n"
       "Choose exactly one action: HOLD, SCALE, ROLLBACK, RESTART, PAGE.\n"
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
            ;; token vectors attached once, by trusted code
            candidates (mapv (fn [c]
                               (assoc c :tokens
                                      (vec (take 1 (llama/tokenize m (:text c)
                                                                   {:add-special? false})))))
                             vocabulary)
            policy {:min-margin 0.5 :max-candidates 12 :require-comparable? true}]

        (println "model:" (:desc m))
        (println "domain:" (mapv :id candidates)
                 " comparable:" (decide/comparable? candidates))
        (println)

        (doseq [[label lines] situations]
          (let [record (decide/decide {:scorer scorer
                                       :context (context-for lines)
                                       :candidates candidates
                                       :policy policy
                                       :model-id (:desc m)})]
            (println "situation:" label)
            (doseq [c (:domain record)]
              (println (format "  %d. %-9s score=%9.5f  n_tokens=%s"
                               (inc (:rank c)) (name (:id c))
                               (:score c) (:n-tokens c))))
            (println (format "  decision=%s selected=%s margin=%s reason=%s"
                             (name (:decision record))
                             (some-> (:selected record) name)
                             (when (:margin record) (format "%.5f" (:margin record)))
                             (name (:reason record))))
            (println "  journal-safe:" (nil? (decide/leaks? record)))
            (println)))

        ;; ---- the properties this canary is actually asserting
        (println "--- canary assertions ---")
        (let [rec (decide/decide {:scorer scorer
                                  :context (context-for (second (first situations)))
                                  :candidates candidates
                                  :policy policy
                                  :model-id (:desc m)})
              ;; a hostile scorer: tries to name an option outside the domain,
              ;; and to score it best
              hostile (fn [_ _] {:scores {:hold -9.0 :DELETE-EVERYTHING 0.0}})
              hrec (decide/decide {:scorer hostile :context "x"
                                   :candidates candidates :policy policy
                                   :model-id (:desc m)})]
          (println "  no machine state in the record:      " (nil? (decide/leaks? rec)))
          (println "  every offered option is recorded:    "
                   (= (count candidates) (:n-offered rec)))
          (println "  a scorer cannot introduce an option: "
                   (not (contains? (set (map :id (:domain hrec))) :DELETE-EVERYTHING)))
          (println "  an unknown id cannot be selected:    "
                   (contains? (conj (set (map :id candidates)) nil) (:selected hrec)))
          (println "  scores are exactly comparable:       " (decide/comparable? candidates)))

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
                         :decide/scorer scorer
                         :decide/model-id (:desc m)
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
            (finally (db/close conn))))))))
