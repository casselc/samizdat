;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.api.control
  "Starting runs, intervening in them, and stopping them.

  Two paths on purpose. A directive goes on a queue and is drained at the next
  branch boundary, because a branch mid provider-call is not something to
  mutate. An abort goes straight to the supervisor, because a wedged run is
  exactly the one that will never reach another boundary — that is the RAX
  manager pattern, and it is why the stop path does not share machinery with
  the steer path."
  (:require [samizdat.lexicon :as lexicon]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.resume :as resume]
            [samizdat.approval :as approval]
            [samizdat.cancel :as cancel]
            [samizdat.llm.registry :as registry]
            [samizdat.prompt :as prompt]
            [samizdat.store.grants :as grants]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

;; run-id -> {:done promise :abort (atom false) :cancel fn}. A run outlives
;; the request that started it, so something has to own its complete lifetime.
(defonce active (atom {}))

(defn close-exceptional-task!
  "Best-effort durable closure shared by background and synchronous run
  owners. Only a still-running row may transition; a terminal winner gets no
  misleading task-exit event. The initial durable lookup may itself throw;
  owners that must preserve the task exception must guard this call separately."
  ([conn run-id e] (close-exceptional-task! conn run-id e nil))
  ([conn run-id e secondary-report]
  ;; The beam records failures inside run-rounds, but task setup and teardown
  ;; sit outside that recorder. If one of those throws after on-start delivered
  ;; the id, the task is gone and a `running` row is a false liveness claim.
  ;; Best effort independently: a failed diagnostic write must not prevent the
  ;; terminal transition, and finish-run!'s row guard preserves an abort or
  ;; completion that won the race.
  (when (= "running" (:status (runs/get-run conn run-id)))
    (if (cancel/control-signal? e)
      (try (runs/finish-run! conn run-id :aborted nil)
           (catch Throwable close-error
             (if secondary-report
               (secondary-report :cancelled-transition)
               (log/warn "closing cancelled run" run-id "failed:"
                         (ex-message close-error)))))
      (let [closed? (try
                      (pos? (runs/finish-run! conn run-id :failed nil))
                      (catch Throwable close-error
                        (if secondary-report
                          (secondary-report :failed-transition)
                          (log/warn "closing failed run" run-id "failed:"
                                    (ex-message close-error)))
                        false))]
        ;; Journal only when this task won the terminal transition. A stale
        ;; `running` read followed by a concurrent completion or abort must
        ;; not append a misleading run-error to the winner's history.
        (when closed?
          (try
            (journal/note! conn run-id :run-error
                           {:data {:error (ex-message e)
                                   :type (some-> (:via (Throwable->map e)) first :type str)
                                   :phase :task-exit}})
            (catch Throwable note-error
              (if secondary-report
                (secondary-report :failure-journal)
                (log/warn "recording task-level failure for run" run-id "failed:"
                          (ex-message note-error)))))))))))

(defn- secondary-cleanup-warning! [phase]
  ;; Secondary failures must not mask the task outcome or print storage/error
  ;; content. Even a failed logger must not interrupt the remaining cleanup.
  (try (log/warn "background task secondary cleanup failed" {:phase phase})
       (catch Throwable _ nil)))

(defn- close-background-exception! [conn run-id failure]
  (try (close-exceptional-task! conn run-id failure secondary-cleanup-warning!)
       (catch Throwable _ (secondary-cleanup-warning! :exceptional-storage))))

(defn- release-background-owner! [run-id]
  ;; Keep one terminal cleanup path for success, failure and cancellation.
  ;; The scheduler settles the canonical done only after this finally returns.
  (try (approval/abandon! run-id)
       (catch Throwable _ (secondary-cleanup-warning! :approval-release))
       (finally (swap! active dissoc run-id))))

(defn run-llm-config
  "The llm config this run should use, after the request's own overrides.

  The model used to come only from HARNESS_MODEL at startup, so putting a run
  on a different arm meant restarting the server — which kills whatever run is
  in flight, hours of provider spend, plus another Mathlib import for the Lean
  pool. Comparing arms was therefore gated on the box being idle, which is the
  one thing it never is during a campaign.

  Per-run instead. beam/run! already records (:model llm-config) on the run
  row, so the arm becomes provenance on the result rather than something to
  remember about the environment when reading it back months later.

  `reasoning_effort` is passed to the provider verbatim. It matters because
  whether a model thinks was otherwise a property of which one was configured:
  deepseek-v4-pro thinks by default, deepseek-v4-flash does not, and neither
  says so in the run record.

  Blank is not a value — an unset select posts \"\" — so it leaves the
  configured default standing rather than asking for a model with no name."
  [llm-config body]
  (let [pick (fn [& ks]
               (let [v (some #(let [x (get body %)]
                                (when-not (str/blank? (str x)) x))
                             ks)]
                 v))]
    (cond-> llm-config
      (pick :model :model "model") (assoc :model (pick :model "model"))
      (pick :reasoning_effort :reasoning-effort "reasoning_effort")
      (assoc :reasoning-effort
             (pick :reasoning_effort :reasoning-effort "reasoning_effort")))))

(defn branch-cap-result
  "The validated effective branch ceiling named by one request body.

  Shared by both run-producing HTTP surfaces so invalid input has the same
  pre-start 400 behavior rather than escaping one of them as a server error."
  [config body]
  (let [requested (or (:max_total_branches body)
                      (:max-total-branches body))]
    (try
      {:value (beam/effective-max-total-branches config requested)}
      (catch Throwable e {:error (ex-message e)}))))

(defn start-run!
  "Kick off a run in the background and return its id immediately.

  `POST /v1/chat/completions` blocks on the same machinery for OpenAI
  compatibility; this is the path for everything else."
  ;; JSON bodies arrive with underscored keys; accept both so a caller is
  ;; never silently given the config default when they asked for something
  ;; specific. The first API call made here asked for beam_width 2 and got 5.
  [{:keys [conn config]} body]
  (let [problem (or (:problem body) (get body "problem"))
        max-turns (or (:max_turns body) (:max-turns body))
        beam-width (or (:beam_width body) (:beam-width body))
        token-budget (or (:token_budget body) (:token-budget body))
        seed-run (or (:seed_run body) (:seed-run body))
        quarantine (or (:quarantine body) (get body "quarantine"))
        cap-result (branch-cap-result config body)]
  ;; A {} body used to start a REAL run on a nil problem — a selection model
  ;; call plus a full beam of provider spend answering nothing, while
  ;; /v1/chat/completions 400s the same input (blt.38).
  (cond
    (str/blank? (str problem))
    {:status 400
     :body {:error {:message "a run needs a non-blank `problem`"
                    :type "invalid_request_error"}}}

    (:error cap-result)
    {:status 400
     :body {:error {:message (:error cap-result)
                    :type "invalid_request_error"}}}

    :else
  (let [max-total-branches (:value cap-result)
        llm-config (run-llm-config (:llm config) body)
        adapter (registry/adapter-for (:provider llm-config))
        abort (atom false)
        promised (promise)
        done (promise)
        cancel* (atom nil)
        ;; The run is a TASK (RFC-013): abort cancels it, and the cancel is
        ;; observed at the round's next step or a turn's next check. The abort
        ;; flag stays beside it for the waits a cancel cannot reach.
        started (cancel/start!
                 (cancel/spawn
                  (fn []
                    (try
                      (let [r (beam/run! {:conn conn :config config
                                          :llm-adapter adapter :llm-config llm-config
                                          :problem problem
                                          :max-turns max-turns
                                          :beam-width beam-width
                                          :max-total-branches max-total-branches
                                          :token-budget token-budget
                                          :seed-run seed-run
                                          :quarantine quarantine
                                          :abort abort
                                          :on-start (fn [rid]
                                                      (swap! active assoc rid
                                                             {:abort abort
                                                              :done done
                                                              :cancel (fn [] (some-> @cancel* (apply [])))})
                                                      (deliver promised rid))})]
                        r)
                      (catch Throwable e
                        (try
                          (if (cancel/control-signal? e)
                            (log/info "run aborted:" (ex-message e))
                            (log/error "run failed:" (ex-message e)))
                          (catch Throwable _ (secondary-cleanup-warning! :task-failure-report)))
                        (when-let [rid (deref promised 0 nil)]
                          (close-background-exception! conn rid e))
                        {:status :error :error (ex-message e)})
                      (finally
                        (when-let [rid (deref promised 0 nil)]
                          (release-background-owner! rid))))))
                 done)
        _ (reset! cancel* (:cancel started))
        ;; How long the request waits for the run row before answering 503.
        ;; gates.edn :run-start-deadline-ms: the selection model call runs
        ;; BEFORE the row exists, and on GLM-5.3 with thinking it took 28 s
        ;; live (2026-09-07), so a 30 s literal here answered 503 to a run
        ;; that then started anyway.
        start-deadline (lexicon/policy :run-start-deadline-ms)
        run-id (deref promised start-deadline nil)]
    (if run-id
      ;; Wrapped in :body like resume, so one route shape serves both the
      ;; success and the refusal and neither has to be special-cased.
      {:body {:run_id run-id :status "running"
              :beam_width (or beam-width (get-in config [:run :beam-width]))
              :max_total_branches max-total-branches
              :max_turns (or max-turns (get-in config [:run :max-turns]))
              :token_budget (or token-budget (get-in config [:run :token-budget]))}}
      ;; 503, not 200: the request was well formed and the server could not
      ;; service it. Answering 200 with an error body made a caller that checks
      ;; the status code read this as a started run, which is why gui.api's
      ;; start-run! had to unwrap the body to find out otherwise.
      {:status 503
       :body {:error {:message (str/trim
                                (prompt/render "run-start-timeout"
                                               {:seconds (quot start-deadline 1000)}))}}})))))

(defn abort!
  "Stop a run without asking it to cooperate. Cancels the run task, which is
  observed at the round's next step or a turn's next check (RFC-013), and sets
  the flag the waits a cancel cannot reach still read. The run's finally block
  disposes every engine session regardless of how it ended."
  [conn run-id]
  (if-let [{:keys [abort cancel]} (get @active run-id)]
    (do (reset! abort true)
        (when cancel (cancel))
        (if (pos? (runs/finish-run! conn run-id :aborted nil))
          ;; :body, not a bare map: the run's own :status is the string
          ;; "aborting", and a route reading (:status r) as an HTTP code would
          ;; have sent that.
          {:body {:run_id run-id :status "aborting"}}
          ;; The run finished between the registry read and the store write;
          ;; the row guard refused the rewrite (provenance R2-4). Same refusal
          ;; shape as an unknown run — the abort did not land.
          {:status 409
           :body {:error {:message (str "run " run-id " already finished")}
                  :run_id run-id}}))
    ;; 409, matching resume's "not resumable": the run may well exist, it is
    ;; just not in a state that can be aborted. This answered 200 with an error
    ;; body, so a caller reading the status code alone saw a refusal as a
    ;; successful abort.
    {:status 409
     :body {:error {:message (str "no active run " run-id)}
            :run_id run-id}}))

(defn resume!
  "Resume a crashed run from its journal, in the background like start-run!.

  Returns {:status 409 :body ...} when the run is not resumable — aborted runs
  stay aborted, completed runs shipped — else a success map the caller turns
  into an HTTP 200. The resumed run is registered under `active` with a fresh
  abort flag, so abort! can stop it like any other.

  `body` may carry max_turns: an explicit budget extension that reopens
  branches closed as exhausted. Omitted, the original budget stands."
  [{:keys [conn config]} run-id body]
  (if-not (resume/resumable? conn run-id)
    {:status 409 :body {:error {:message (str "run " run-id " is not resumable")
                                :run_id run-id}}}
    ;; A resume may name an arm too — a run that crashed on one model can be
    ;; picked up on another, and saying nothing keeps the original.
    (let [llm-config (run-llm-config (:llm config) body)
          adapter (registry/adapter-for (:provider llm-config))
          abort (atom false)
          max-turns (or (:max_turns body) (:max-turns body))]
      (let [cancel* (atom nil)
            done (promise)
            started (cancel/start!
                     (cancel/spawn
                      (fn []
                        (try
                          (swap! active assoc run-id
                                 {:abort abort
                                  :done done
                                  :cancel (fn [] (some-> @cancel* (apply [])))})
                          (let [r (resume/resume! {:conn conn :config config
                                                   :llm-adapter adapter
                                                   :llm-config llm-config
                                                   :run-id run-id :abort abort
                                                   :max-turns max-turns})]
                            r)
                          (catch Throwable e
                            (try
                              (if (cancel/control-signal? e)
                                (log/info "resume aborted:" (ex-message e))
                                (log/error "resume failed:" (ex-message e)))
                              (catch Throwable _ (secondary-cleanup-warning! :task-failure-report)))
                            (close-background-exception! conn run-id e)
                            {:status :error :error (ex-message e)})
                          (finally (release-background-owner! run-id)))))
                     done)]
        (reset! cancel* (:cancel started)))
      ;; The budget this resume is running under, from what the caller asked
      ;; for, falling back to the row as it stood BEFORE the future started.
      ;; Reading the row here unconditionally raced the resume that is
      ;; rewriting it: the answer was whichever thread won, and an explicit
      ;; max_turns extension was reported as the old budget more often than
      ;; not.
      {:body {:run_id run-id :status "resuming"
              :max_turns (or max-turns (:max_turns (runs/get-run conn run-id)))}})))
(defn- grant-pattern
  "The pattern from a grant payload. Accepts a map (what body-json yields), a
  bare string, or nil. Blank is not a pattern — an unset form posts empty
  strings."
  [payload]
  (let [p (cond
            (map? payload) (or (:pattern payload) (get payload "pattern"))
            (string? payload) payload
            :else nil)]
    (when-not (str/blank? (str p)) (str p))))

(defn intervene!
  "Record a human intervention. Queued kinds (message, cull, fork, …) go on
  the directive queue and apply at the next branch boundary. The exception is
  `grant`, which applies ON ARRIVAL: it writes the run-scoped permission grant
  the shell policy consults on every command, so there is no boundary to wait
  for. This is the one production write path into the grants table — a human
  surface, never a tool — and without it every deliberate `ask` (interpreters,
  git push, curl, installs) blocked a run forever (provenance A-2, docs/provenance.md)."
  [conn run-id body]
  (if (= "grant" (:kind body))
    (if-let [pattern (grant-pattern (:payload body))]
      (do (grants/grant! conn run-id pattern)
          (log/info "grant" pattern "recorded for run" run-id)
          {:body {:status "granted" :pattern pattern :run_id run-id
                  :note "Applied now. Commands matching the pattern are allowed for the rest of this run; a hard deny still wins."}})
      {:status 400
       :body {:error {:message "a grant intervention needs payload.pattern — the shell glob to allow"
                     :run_id run-id}}})
    (if-let [run (let [r (runs/get-run conn run-id)]
                   (when (or (nil? r) (runs/terminal? r))
                     (or r ::absent)))]
      ;; A directive against a run that does not exist or has ended would sit
      ;; `pending` forever — the UI showing an intervention that will never
      ;; resolve (blt.38).
      (if (= ::absent run)
        {:status 404 :body {:error {:message (str "no run " run-id)}}}
        {:status 409 :body {:error {:message (str "run " run-id " is already "
                                                  (:status run))
                                    :run_id run-id}}})
    (if-not (contains? interventions/kinds (:kind body))
      ;; provenance R3-12: this reached submit!'s throw and surfaced as the
      ;; server's catch-all 500. An unknown kind is the client's mistake.
      {:status 400
       :body {:error {:message (str "Unknown intervention kind " (pr-str (:kind body))
                                    "; known: "
                                    (str/join ", " (sort interventions/kinds)))}
              :run_id run-id}}
      (let [id (interventions/submit! conn run-id
                                      {:branch-id (:branch_id body)
                                       :kind (:kind body)
                                       :payload (:payload body)
                                       :issued-by (or (:issued_by body) "human")})]
        {:body
         {:id id
          :status "pending"
          ;; Said plainly rather than implied, because the difference between
          ;; accepted and applied is the thing a UI most easily lies about.
          :note "Queued. It applies at the branch's next turn boundary, not now."}})))))

(defn kinds
  "Every directive kind with what it does — the names from the store, the
  words from wordlists.edn :directive-kinds."
  []
  {:kinds (lexicon/wordlist :directive-kinds)})
