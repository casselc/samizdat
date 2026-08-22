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
            [samizdat.agent.planner :as planner]
            [samizdat.agent.state :as state]
            [samizdat.llm.client :as llm]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as wf]))

(defn- summarize [results]
  (str "Team of " (count results) " workers:\n"
       (str/join "\n"
                 (for [{:keys [worker subtask status answer]} results]
                   (str "- W" worker " [" (name (or status :unknown)) "] " subtask
                        (when answer (str "\n    → " answer)))))))

(defn- roster
  "The team-context prompt suffix for worker `idx`: which worker it is, its part,
  and every peer's part (so it knows who to coordinate with), followed by the
  shared team-worker guide. Only meaningful for a real team — a solo worker gets
  no suffix (see worker-prompt)."
  [idx tasks]
  (str "## Your team\n\n"
       "You are worker W" idx " of " (count tasks) ". Your part: " (nth tasks idx)
       "\n\nThe team and their parts:\n"
       (str/join "\n"
                 (map-indexed (fn [i t] (str "- W" i (when (= i idx) " (you)") ": " t))
                              tasks))
       "\n\n"
       (or (wf/prompt-text "team-worker") "")))

(defn- worker-prompt
  "The prompt suffix for worker `idx`: a peer roster + coordination guide for a
  real team (>1 task), nil for a solo worker (which then runs the plain worker
  prompt, unchanged from before this slice)."
  [idx tasks]
  (when (> (count tasks) 1)
    (roster idx tasks)))

(defn- run-worker
  "Run one worker sub-loop on sub-task `st` as branch `bid` on the shared run,
  with peer roster `suffix`. Returns a result map. A throw becomes an :error
  result rather than taking the whole fan-out down — one worker's crash is not
  the team's."
  [{:keys [conn run-id] :as ctx} worker bid idx st suffix]
  (try
    (runs/open-branch! conn run-id {:branch-id bid})
    (let [b (state/new-branch {:id bid :problem st
                               :messages (turn/initial-messages st suffix)})
          out (myc/run-compiled worker ctx {:branch b :turn 1})]
      {:worker idx :subtask st :branch bid
       :status (:verdict out)
       :answer (get-in out [:branch :final-answer])})
    (catch Throwable e
      {:worker idx :subtask st :branch bid :status :error
       :answer (str "worker failed: " (ex-message e))})))

(defn- ok?
  "A worker result that landed a shippable answer. Anything else — :abandoned
  (gave up), :exhausted (turn cap), :error (crash) — is a part the supervisor
  may re-task."
  [r]
  (= :done (:status r)))

(cell/defcell :team/plan
  {:doc "Split the manager branch's problem into independent sub-tasks for the
        team to fan out over. If sub-tasks were already provided (config
        :run :subtasks), pass through — an explicit split wins. Otherwise one
        LLM call proposes at most :max-subtasks parts; fail-soft to a single
        worker on the whole problem when the call fails or yields no list."
   :effects [:net]}
  (fn [{:keys [conn run-id llm-adapter llm-config config] :as ctx}
       {:keys [branch subtasks] :as data}]
    (if (seq subtasks)
      data
      (let [max-parts (or (get-in config [:run :max-subtasks])
                          planner/default-max-parts)
            reply (try (:content (llm/chat llm-adapter llm-config
                                           [{:role "user"
                                             :content (planner/plan-prompt
                                                       (:problem branch) max-parts)}]))
                       (catch Throwable _ nil))
            parts (planner/parse-plan reply max-parts)
            tasks (or parts [(:problem branch)])]
        (journal/note! conn run-id :plan
                       {:data {:planned (count tasks) :split (boolean parts)}})
        (assoc data :subtasks tasks)))))

(cell/defcell :team/fan-out
  {:doc "Run a worker sub-loop per sub-task, in parallel, each its own branch on
        the shared run (so they coordinate through the mailbox). Join their
        answers into the manager branch and finish. A dataflow join, not a live
        actor: workers run to completion."
   :effects [:net :db]}
  (fn [{:keys [conn run-id] :as ctx} {:keys [branch subtasks] :as data}]
    (let [tasks (vec (if (seq subtasks) subtasks [(:problem branch)]))
          worker (wf/worker-compiled)
          results (->> (map-indexed vector tasks)
                       (mapv (fn [[i s]]
                               (future (run-worker ctx worker (str "W" i) i s
                                                   (worker-prompt i tasks)))))
                       (mapv deref))]
      (journal/note! conn run-id :team
                     {:data {:workers (count results)
                             :done (count (filter ok? results))}})
      (assoc data
             :subtasks tasks
             :results (vec results)
             :verdict :done
             :branch (assoc branch :status :done
                            :final-answer (summarize results))))))

(cell/defcell :team/supervise
  {:doc "Watch the fan-out's results and re-task the parts that did not land: a
        worker that gave up, hit the turn cap, or crashed gets one more run on a
        fresh branch (W<idx>r1). The retry replaces the original only if it does
        better. A bounded re-task, not an open loop — the supervisor's job is to
        catch a stalled part, not to grind. Re-joins the answers after."
   :effects [:net :db]}
  (fn [{:keys [conn run-id] :as ctx} {:keys [branch results subtasks] :as data}]
    (let [tasks (vec subtasks)
          worker (wf/worker-compiled)
          retried (mapv (fn [r]
                          (if (ok? r)
                            r
                            (let [i (:worker r)
                                  r2 (run-worker ctx worker (str "W" i "r1") i
                                                 (:subtask r) (worker-prompt i tasks))]
                              (if (ok? r2) r2 r))))
                        results)
          fixed (count (filter (fn [[a b]] (and (not (ok? a)) (ok? b)))
                               (map vector results retried)))]
      (journal/note! conn run-id :supervise
                     {:data {:retried (count (remove ok? results)) :fixed fixed}})
      (assoc data
             :results retried
             :branch (assoc branch :final-answer (summarize retried))))))
