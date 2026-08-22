;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; Multi-agent fan-out. Not wired into any single-branch loop; the `team`
;; manifest routes through it. Given a vector of sub-tasks, it runs a WORKER
;; sub-loop per sub-task — in parallel, each its own branch on the SHARED run,
;; so the workers coordinate through the run's mailbox (message tool) and its
;; shared artifact/failure pool. It joins their answers into the manager
;; branch's final answer.
;;
;; This is the dataflow fan-out shape (futures + deref, workers run to
;; completion): coordination is between-turn via the mailbox, not a live
;; actor. The escapement-style parked-conversation actor (per-turn peer
;; steering) is karamazov-oy1, a later, larger step.
(ns cells.team
  (:require [clojure.string :as str]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.state :as state]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as wf]))

(defn- summarize [results]
  (str "Team of " (count results) " workers:\n"
       (str/join "\n"
                 (for [{:keys [worker subtask status answer]} results]
                   (str "- W" worker " [" (name (or status :unknown)) "] " subtask
                        (when answer (str "\n    → " answer)))))))

(cell/defcell :team/fan-out
  {:doc "Run a worker sub-loop per sub-task, in parallel, each its own branch on
        the shared run (so they coordinate through the mailbox). Join their
        answers into the manager branch and finish. A dataflow join, not a live
        actor: workers run to completion."
   :effects [:net :db]}
  (fn [{:keys [conn run-id] :as ctx} {:keys [branch subtasks] :as data}]
    (let [tasks (vec (if (seq subtasks) subtasks [(:problem branch)]))
          worker (wf/worker-compiled)
          run-one (fn [idx st]
                    (try
                      (runs/open-branch! conn run-id {:branch-id (str "W" idx)})
                      (let [b (state/new-branch {:id (str "W" idx) :problem st
                                                 :messages (turn/initial-messages st)})
                            out (myc/run-compiled worker ctx {:branch b :turn 1})]
                        {:worker idx :subtask st
                         :status (:verdict out)
                         :answer (get-in out [:branch :final-answer])})
                      (catch Throwable e
                        {:worker idx :subtask st :status :error
                         :answer (str "worker failed: " (ex-message e))})))
          results (->> (map-indexed vector tasks)
                       (mapv (fn [[i s]] (future (run-one i s))))
                       (mapv deref))]
      (journal/note! conn run-id :team
                     {:data {:workers (count results)
                             :done (count (filter #(= :done (:status %)) results))}})
      (assoc data
             :results (vec results)
             :verdict :done
             :branch (assoc branch :status :done
                            :final-answer (summarize results))))))
