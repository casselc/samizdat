;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; The finalization critic cell, ported from dirge's unified judge. It is NOT
;; wired into the factory `loop` manifest; the `critic` manifest routes a
;; branch's :done through it. When a branch tries to finish, an LLM judge —
;; fed a deterministic evidence block and judged against the agent's own rules
;; — decides whether the task is really complete. If not, the done is undone:
;; the branch is re-activated, a single [critic] note is injected, and the loop
;; re-enters. Bounded (so it cannot block forever) and fail-open (a judge that
;; errors or abstains-into-silence ships), because a backstop that can wedge
;; the loop is worse than no backstop.
(ns cells.critic
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.judge :as judge]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.state :as state]
            [samizdat.llm.client :as llm]
            [samizdat.store.journal :as journal]))

(def max-critic-attempts
  "How many times the critic may send a branch back before it ships anyway.
  A calibrated judge blocks rarely; this caps the cost when it is wrong."
  2)

(defn- parse-args [r]
  (update r :args #(try (json/read-str (str %) :key-fn keyword)
                        (catch Throwable _ {}))))

(defn- ship [data] (assoc data :critic/decision :ship))

(cell/defcell :gate/critic
  {:doc "The finalization judge: on a genuine :done, ask an LLM whether the
        task is actually complete, against the agent's own rules and a
        deterministic evidence block. COMPLETE ships; INCOMPLETE/ABSTAIN
        re-activates the branch, injects one [critic] note, and re-enters the
        loop. Spent budget or a judge error ships (fail-open)."
   :effects [:net :db]}
  (fn [{:keys [conn run-id root git-baseline llm-adapter llm-config]}
       {:keys [branch turn] :as data}]
    (let [attempts (or (:critic/attempts data) 0)]
      (if (>= attempts max-critic-attempts)
        (ship data)
        (let [rows (map parse-args (journal/turns conn run-id))
              evidence (judge/evidence rows)
              diff (gitdiff/diff root git-baseline)
              transcript (->> (:messages branch) (map :content) (str/join "\n"))
              prompt (judge/critic-prompt {:rules (turn/system-prompt)
                                           :transcript transcript
                                           :evidence evidence
                                           :diff diff
                                           :answer (:final-answer branch)})
              reply (try (:content (llm/chat llm-adapter llm-config
                                             [{:role "user" :content prompt}]))
                         (catch Throwable _ nil))
              verdict (if reply (judge/parse-verdict reply) :complete)
              ;; A COMPLETE verdict still blocks if the diff review turned up a
              ;; critical or high-severity defect.
              blocking (when reply (judge/blocking-findings reply))]
          (journal/note! conn run-id :critic
                         {:data {:branch-id (:id branch) :turn turn
                                 :verdict verdict :blocked (boolean blocking)
                                 :attempt (inc attempts)}})
          (if (and (= :complete verdict) (not blocking))
            (ship data)
            ;; Block: undo the done and re-enter, doing the same per-turn
            ;; bookkeeping :loop/route does on a :continue (route did none —
            ;; it routed us here on :done).
            (let [msg (if (= :complete verdict)
                        (str "[critic] The diff review found defects to fix"
                             " before shipping.\n\n" blocking
                             "\n\nAddress these, then finish again.")
                        (judge/critique-message verdict (judge/findings reply)))
                  branch' (-> branch
                              (assoc :status :active :final-answer nil)
                              (state/add-message "user" msg))]
              (-> data
                  (assoc :branch branch'
                         :critic/decision :revise
                         :critic/attempts (inc attempts))
                  (update :turn inc)
                  (dissoc :before :call :parsed :signals :said :result :tool)
                  (update :mycelium/trace #(vec (take-last 20 %)))))))))))
