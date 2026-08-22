;; samizdat - a self-hosting agentic harness
;; License: EPL-2.0

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
            [samizdat.store.knowledge :as knowledge]))

(def ^:private usage
  (str "`remember` stores a fact for later runs: {content, kind?}. "
       "`recall` has two modes: {query} searches what was stored; "
       "{id} returns one memory's full text by its k- id."))

(defn- memory-line [m]
  (str (:id m) " [" (:kind m) "] " (:content m)))

(defmethod base/run-tool "remember" [{:keys [branch conn] :as ctx}]
  (if-let [miss (base/missing ctx :content)]
    (base/malformed branch (str miss "\n\n" usage))
    (let [id (knowledge/remember! conn {:content (base/arg ctx :content)
                                        :kind (base/arg ctx :kind)})]
      (base/ok branch (str "Remembered " id ": " (base/arg ctx :content))))))

(defmethod base/run-tool "recall" [{:keys [branch conn] :as ctx}]
  (if-let [id (base/arg ctx :id)]
    (if-let [row (knowledge/get-by-id conn id)]
      (base/ok branch (memory-line row))
      (base/fail branch (str "No memory with id " id ".")))
    (if-let [miss (base/missing ctx :query)]
      (base/malformed branch (str miss "\n\n" usage))
      (let [rows (knowledge/recall conn (base/arg ctx :query))]
        (base/ok branch
                 (if (seq rows)
                   (str/join "\n" (map memory-line rows))
                   (str "No memories match \"" (base/arg ctx :query) "\".")))))))
