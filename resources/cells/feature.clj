;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; The FEATURE loop's role cells (feature.edn wires them). The feature manifest
;; is the outer state machine; these cells delegate its stages to roles:
;;
;;   :feature/review    the REVIEWER role — run reviewer.edn on the implementors'
;;                      finished work; PASS or REVISE.
;;   :feature/critique  the CRITIC role — gate the result with the same judge the
;;                      finalization critic uses, without its branch surgery.
;;   :feature/supervise the SUPERVISOR — watch the role loops and adjust the
;;                      outer loop (round one: force another round on a hollow
;;                      implement result).
;;   :feature/route     ship, or send back to implement with findings as
;;                      guidance, bounded by :run :max-revisions.
;;
;; The implement stage itself is :team/fan-out (cells/team.clj) — the horizontal
;; team of implementor workers lives inside this loop as one stage.
(ns cells.feature
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.judge :as judge]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.state :as state]
            [samizdat.llm.client :as llm]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as wf]))

(defn- revision [data] (or (:feature/revisions data) 0))

(defn- run-role
  "Run a role sub-loop (compiled) on a fresh branch `bid` with problem `prob` and
  role-prompt `suffix`. Returns {:verdict :answer}."
  [{:keys [conn run-id] :as ctx} compiled bid prob suffix]
  (runs/open-branch! conn run-id {:branch-id bid})
  (let [b (state/new-branch {:id bid :problem prob
                             :messages (turn/initial-messages prob suffix)})
        out (myc/run-compiled compiled ctx {:branch b :turn 1})]
    {:verdict (:verdict out) :answer (get-in out [:branch :final-answer])}))

(defn- review-decision
  "PASS/REVISE from the reviewer's verdict + answer. A reviewer that could not
  finish (not :done) or said nothing fails OPEN to :pass — a review backstop
  that can wedge the loop is worse than none. Otherwise the first line decides."
  [verdict answer]
  (if (or (not= :done verdict) (str/blank? (str answer)))
    :pass
    (let [first-line (-> (str answer) str/split-lines first str str/upper-case)]
      (if (str/includes? first-line "REVISE") :revise :pass))))

(cell/defcell :feature/review
  {:doc "The reviewer role: run reviewer.edn on the implementors' finished work
        (on its own branch R<rev>) and read back PASS or REVISE. Fail-open to
        :pass on a reviewer error/abstention."
   :effects [:net :db]}
  (fn [{:keys [conn run-id] :as ctx} {:keys [branch] :as data}]
    (let [prob (str "Review this feature's work.\n\nFeature:\n" (:problem branch)
                    "\n\nThe implementors reported:\n" (:final-answer branch))
          {:keys [verdict answer]}
          (try (run-role ctx (wf/compiled-manifest "reviewer")
                         (str "R" (revision data)) prob
                         (wf/prompt-text "roles/reviewer"))
               (catch Throwable e {:verdict :error :answer (ex-message e)}))
          decision (review-decision verdict answer)]
      (journal/note! conn run-id :review {:data {:decision decision :verdict verdict}})
      (assoc data :review/decision decision :review/findings (str answer)))))

(defn- parse-args [r]
  (update r :args #(try (json/read-str (str %) :key-fn keyword)
                        (catch Throwable _ {}))))

(cell/defcell :feature/critique
  {:doc "The critic role: gate the feature result with the finalization judge —
        deterministic checks, then an LLM verdict on the answer + the run's diff
        — but WITHOUT the single-branch critic's branch surgery. Sets
        :critic/decision :ship or :revise. Fail-open (a judge that errors ships)."
   :effects [:net :db]}
  (fn [{:keys [conn run-id root git-baseline llm-adapter llm-config]}
       {:keys [branch] :as data}]
    (let [answer (:final-answer branch)
          rows (map parse-args (journal/turns conn run-id))
          det (judge/deterministic-block answer rows)
          decision
          (if det
            :revise
            (let [diff (gitdiff/diff root git-baseline)
                  evidence (judge/evidence rows)
                  prompt (judge/critic-prompt {:rules (turn/system-prompt)
                                               :transcript (str answer)
                                               :evidence evidence
                                               :diff diff
                                               :answer answer})
                  reply (try (:content (llm/chat llm-adapter llm-config
                                                 [{:role "user" :content prompt}]))
                             (catch Throwable _ nil))
                  verdict (if reply (judge/parse-verdict reply) :complete)
                  blocking (when reply (judge/blocking-findings reply))]
              (if (and (= :complete verdict) (not blocking)) :ship :revise)))]
      (journal/note! conn run-id :critique
                     {:data {:decision decision :deterministic (boolean det)}})
      (assoc data :critic/decision decision :critique/findings (or det "")))))

(cell/defcell :feature/supervise
  {:doc "The supervisor: watch the role loops and adjust the outer loop. Round
        one's concrete tweak — if the implement round shipped nothing (no worker
        reached :done) it forces another round (:feature/escalate) rather than
        letting a hollow result ship. Records a supervisory note either way.
        Deeper manifest rewriting via the mutation protocol is a later step."
   :effects [:db]}
  (fn [{:keys [conn run-id]} {:keys [results] :as data}]
    (let [total (count results)
          shipped (count (filter #(= :done (:status %)) results))
          no-progress? (and (pos? total) (zero? shipped))]
      (journal/note! conn run-id :supervise
                     {:data {:workers total :shipped shipped
                             :review (:review/decision data)
                             :critic (:critic/decision data)
                             :escalate no-progress?}})
      (cond-> data
        no-progress? (assoc :feature/escalate true)))))

(cell/defcell :feature/route
  {:doc "Decide the feature's fate: SHIP if the reviewer passed, the critic
        shipped, and the supervisor did not escalate; else send the work back to
        implement with the findings as guidance — bounded by :run :max-revisions
        (default 2), after which it ships what it has. Threads :revise/guidance
        and a fresh revision number to the next implement round."
   :effects [:db]}
  (fn [{:keys [conn run-id config]} data]
    (let [rev (revision data)
          cap (or (get-in config [:run :max-revisions]) 2)
          pass? (and (= :pass (:review/decision data))
                     (= :ship (:critic/decision data))
                     (not (:feature/escalate data)))
          ship? (or pass? (>= rev cap))]
      (journal/note! conn run-id :route
                     {:data {:decision (if ship? :ship :revise) :revision rev
                             :cap cap :forced-ship (and ship? (not pass?))}})
      (if ship?
        (assoc data :feature/decision :ship)
        (-> data
            (assoc :feature/decision :revise
                   :feature/revisions (inc rev)
                   :feature/escalate false
                   :revise/guidance
                   (str/trim
                    (str (when (= :revise (:review/decision data))
                           (str "Reviewer asked for changes:\n"
                                (:review/findings data) "\n\n"))
                         (when (seq (:critique/findings data))
                           (str "Critic flagged:\n" (:critique/findings data))))))
            (dissoc :results :review/decision :critic/decision
                    :review/findings :critique/findings))))))
