;; The supervisor's stream, as cells. See manifests/oversight.edn.
;;
;; One pass: gather -> (reason -> apply | quiet). The gate between them is
;; `worth-a-look?`, and it is deliberately cheap and conservative, because a
;; pass costs a model call and most moments in a run do not need one.
(ns cells.oversight
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mycelium.cell :as cell]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.state :as state]
            [samizdat.agent.telemetry :as telemetry]
            [samizdat.agent.gates :as gates]
            [samizdat.prompt :as prompt]
            [samizdat.session :as session]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as wf]
            [mycelium.core :as myc]))

(defn- safely
  "A supervisor stage that throws leaves the run alone and the stream alive.

  LOGGED, always. The first version swallowed silently, and a stream whose
  failures are invisible cannot be told apart from one that never started —
  which is exactly the confusion it caused the first time it ran."
  [what f fallback]
  (try (f)
       (catch Throwable e
         (log/warn "oversight" what "failed:" (ex-message e))
         fallback)))

(defn- clip
  "First `n` characters, safely. Collapsing whitespace shortens the string, so
  indexing the ORIGINAL length into the COLLAPSED one overruns it — a crash
  that could only happen once a pass actually succeeded, which is the worst
  time to find it."
  [s n]
  (let [t (str/replace (str s) #"\s+" " ")]
    (subs t 0 (min n (count t)))))

;; --- gather -----------------------------------------------------------------

(defn worth-a-look?
  "Whether this moment deserves a model call.

  PURE, and the whole cost control of the stream. Three things make a pass
  worth its price, and none of them is 'time has passed':

  - the run is being STEERED AND IGNORING IT. A gate firing unmet is the
    harness saying something the branch did not act on, which is the signal
    that the harness's own words are wrong — the supervisor's actual job.
  - the run is PRODUCING NOTHING. Turns are being spent with no artifact and
    no file written.
  - something CRASHED. A stage error is a harness bug the loop survived, and
    it will happen again on the next run if nobody looks.

  A healthy run that is shipping gets no supervision, which is correct: there
  is nothing to tune and saying so costs a turn of somebody's budget."
  [{:keys [unmet-gates idle-turns errors]} {:keys [unmet-floor idle-floor]}]
  (boolean (or (>= (or unmet-gates 0) unmet-floor)
               (>= (or idle-turns 0) idle-floor)
               (seq errors))))

(cell/defcell :oversight/gather
  {:doc "Read the run's health from the JOURNAL rather than from a stage's data
        map. That is what makes this a stream: it depends on nothing having
        been handed to it, so a stalled implementer that hands nothing to
        anybody cannot starve it."
   :effects [:db]
   :requires [:conn :run-id]}
  (fn [{:keys [conn run-id]} data]
    (safely :gather
     (fn []
       (let [turns (journal/turns conn run-id)
             firings (journal/gate-firings conn run-id)
             unmet (count (filter #(= "unmet" (str (:outcome %))) firings))
             ;; Turns since anything was written. The stream's cheapest and
             ;; most reliable distress signal — every stalled run in this
             ;; project's history shows it.
             writes (gates/tool-vocab :file-write)
             since (count (take-while #(not (contains? writes (str (:tool_name %))))
                                      (reverse turns)))
             findings (session/findings (session/run-window run-id))]
         (assoc data
                :oversight/turns turns
                :oversight/firings firings
                :oversight/findings findings
                :oversight/unmet unmet
                :oversight/idle since
                :oversight/worth-a-look?
                (worth-a-look? {:unmet-gates unmet :idle-turns since
                                :errors (seq (filter :error findings))}
                               {:unmet-floor (gates/threshold :oversight-unmet-floor)
                                :idle-floor (gates/threshold :oversight-idle-floor)}))))
     (assoc data :oversight/worth-a-look? false))))

;; --- reason -----------------------------------------------------------------

(defn resume-branch
  "The carried branch, ready for another pass.

  Keeps the MESSAGES — the supervisor's memory of what it already noticed and
  already tried — and clears the terminal state. A branch that concluded once
  is finished forever otherwise: run b2ffb2ad's supervisor called `done` on
  pass one and its next four passes resumed a completed branch and returned
  instantly, so it spoke once and went quiet for the rest of the run.

  Concluding is not the same as having nothing left to say. A pass ends; the
  stream does not."
  [b]
  (-> b (dissoc :final-answer :verdict :done? :status) (assoc :advisory? true)))

(cell/defcell :oversight/reason
  {:doc "One turn of the supervisor ROLE, in the stream's OWN branch.

        The branch id is stable for the whole run (`S0`), not minted per pass,
        so the supervisor accumulates a memory of what it already noticed and
        already tried. `:feature/supervise` opens `S<revision>` — a new context
        every time — which is why the supervisor there re-derives the same
        diagnosis on every look and can never say 'I changed that last time and
        it did not help'."
   :effects [:net :db]
   :requires [:conn :run-id :config]}
  (fn [{:keys [conn run-id] :as ctx} data]
    (safely :reason
     (fn []
       (let [dig (telemetry/digest {:idle-turns (:oversight/idle data)
                                    :unmet-gates (:oversight/unmet data)}
                                   (:oversight/turns data)
                                   (:oversight/firings data))
             prob (prompt/render "oversight-pass"
                                 {:digest dig
                                  :learned (seq (knowledge/standing conn))
                                  :catalog (safely :catalog #(wf/render-catalog conn) "")})
             ;; ONE branch for the run, carried by the stream. Opened once;
             ;; re-opening an existing id is a no-op that returns the row.
             bid "S0"
             _ (runs/open-branch! conn run-id {:branch-id bid})
             ;; The stream's memory arrives in DATA, not ctx: ctx is the
             ;; run-scoped resources every driver provides, and the carry is
             ;; this pass's value. Putting it in ctx would have meant claiming
             ;; the beam driver provides it, which it does not.
             b (or (some-> (:oversight/carry data) resume-branch)
                   (assoc (state/new-branch
                           {:id bid :problem prob
                            :messages (turn/initial-messages
                                       prob (wf/prompt-text "roles/supervisor") :supervisor)})
                          :advisory? true :role :supervisor))
             out (myc/run-compiled (wf/compiled-manifest "supervisor")
                                   (wf/role-ctx ctx :supervisor)
                                   {:branch b :turn 1})]
         (assoc data
                :oversight/answer (get-in out [:branch :final-answer])
                :oversight/branch (:branch out))))
     data)))

;; --- apply ------------------------------------------------------------------

(cell/defcell :oversight/apply
  {:doc "Record what the pass concluded.

        The supervisor ACTS THROUGH ITS TOOLS — `intervene` to steer the run,
        the mutation protocol to tune the harness — so by the time control
        reaches here the acting has already happened. What is left is the
        record, which is not a formality: a decision that appears nowhere is
        indistinguishable from a pass that never ran, and the next pass reads
        this to know what it already tried."
   :effects [:db]
   :requires [:conn :run-id]}
  (fn [{:keys [conn run-id]} data]
    (safely :apply
     (fn []
       (journal/note! conn run-id :oversight
                      {:data {:idle (:oversight/idle data)
                              :unmet (:oversight/unmet data)
                              :notes (some-> (:oversight/answer data)
                                             (clip (gates/threshold :oversight-note-chars)))}})
       data)
     data)))

(cell/defcell :oversight/quiet
  {:doc "The run is fine. No model call — the correct outcome for most passes,
        and the reason the stream is affordable at all.

        It still leaves a HEARTBEAT. Saying nothing and not running look
        identical from outside otherwise, and telling those two apart is the
        whole of knowing whether the harness is watching itself. It is one
        cheap row against a run's thousands."
   :effects [:db]
   :requires [:conn :run-id]}
  (fn [{:keys [conn run-id]} data]
    (safely :quiet
     (fn []
       (journal/note! conn run-id :oversight-quiet
                      {:data {:idle (:oversight/idle data)
                              :unmet (:oversight/unmet data)}})
       data)
     data)))
