;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.agent.tools.knowledge
  "Long-term memory tools: remember a fact, recall it by search.

  Both are :neutral on purpose — same reasoning as the task board and
  fetch_artifact. Recording a memory is bookkeeping; the fact it stores was
  established by whatever turn produced it, and that turn already got its
  credit. Charging recall would be worse: reading your own notes back is not
  progress, and a run that searches before every claim would farm the
  counter."
  (:require [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.prompt :as prompt]))

(defn- msg [ctx] (prompt/render "memory-tool" ctx))

(def ^:private usage (delay (msg {:usage true})))

(defn- memory-line
  "One memory as the model reads it, with the two numbers that say why it is
  ranked where it is. A list that hid its own ordering would be asking the
  model to trust it; showing the standing and the record lets the model judge
  a memory the way the ranking did."
  [m]
  (str (:id m) " [" (:kind m) "]"
       (when-let [s (:salience m)] (format " s%.2f" (double s)))
       (let [w (or (:success_count m) 0) f (or (:failure_count m) 0)]
         (when (pos? (+ w f)) (str " " w "✓/" f "✗")))
       " " (:content m)))

(defmethod base/run-tool "remember" [{:keys [branch conn] :as ctx}]
  (if-let [miss (base/missing ctx :content)]
    (base/malformed branch (str miss "\n\n" @usage))
    (let [kind (or (base/arg ctx :kind) "procedural")
          id (knowledge/remember!
              conn {:content (base/arg ctx :content)
                    :kind kind
                    :confidence (some-> (base/arg ctx :confidence) str parse-double)
                    :run-id (:run-id ctx)})]
      (base/ok branch (msg {:remembered true :id id :kind kind
                            :content (base/arg ctx :content)})))))

(defmethod base/run-tool "outcome" [{:keys [branch conn] :as ctx}]
  ;; The axis that makes memory a loop rather than a list. Kind, use and
  ;; recency all measure whether a memory gets READ; only this measures
  ;; whether reading it HELPED.
  (if-let [miss (base/missing ctx :id)]
    (base/malformed branch (str miss "\n\n" @usage))
    (let [id (str (base/arg ctx :id))
          worked? (contains? #{"true" "yes" "1"} (str/lower-case (str (base/arg ctx :worked))))]
      (if-not (knowledge/get-by-id conn id)
        (base/fail branch (msg {:no-memory true :id id}))
        (do (knowledge/record-outcome! conn id worked?)
            (base/ok branch (msg {:outcome-recorded true :id id :worked worked?})))))))

(defmethod base/run-tool "forget" [{:keys [branch conn] :as ctx}]
  (if-let [miss (base/missing ctx :id)]
    (base/malformed branch (str miss "\n\n" @usage))
    (if (pos? (knowledge/forget! conn (base/arg ctx :id)))
      (base/ok branch (msg {:forgot true :id (base/arg ctx :id)}))
      (base/fail branch (msg {:no-memory true :id (base/arg ctx :id)})))))

(defmethod base/run-tool "recall" [{:keys [branch conn] :as ctx}]
  (if-let [id (base/arg ctx :id)]
    (if-let [row (knowledge/get-by-id conn id)]
      (base/ok branch (memory-line row))
      (base/fail branch (msg {:no-memory true :id id})))
    (if-let [miss (base/missing ctx :query)]
      (base/malformed branch (str miss "\n\n" @usage))
      (let [rows (knowledge/recall conn (base/arg ctx :query))]
        (base/ok branch
                 (if (seq rows)
                   (str/join "\n" (map memory-line rows))
                   (msg {:no-match true :query (base/arg ctx :query)})))))))
