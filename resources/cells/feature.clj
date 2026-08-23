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
            [samizdat.agent.telemetry :as telemetry]
            [samizdat.engine.proc :as proc]
            [samizdat.llm.client :as llm]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as wf]))

(defn- revision [data] (or (:feature/revisions data) 0))

(defn- hollow?
  "Ground truth: true when the run KNOWS it changed no files — a done with an
  empty working tree is not a feature, however confidently the workers announced
  it. When git is unavailable the answer is unknown, and we do not block what we
  cannot verify (false)."
  [{:keys [root git-baseline]}]
  (let [files (gitdiff/changed-files root git-baseline)]
    (boolean (and files (empty? files)))))

(defn- safely
  "Run a stage body, but never let it take the whole run down. A stage that
  throws records the error and falls through to `fallback` (a safe default for
  that stage) with the error accumulated on :feature/errors — so the run reaches
  the SUPERVISOR, which sees the crash in its telemetry and can plan a fix,
  rather than the workflow dying structurally. The supervisor cannot supervise a
  loop that is already dead."
  [conn run-id stage data body fallback]
  (try (body)
       (catch Throwable e
         (let [msg (str (name stage) ": " (ex-message e))]
           (journal/note! conn run-id :stage-error {:data {:stage stage :error (ex-message e)}})
           (-> (fallback data)
               (update :feature/errors (fnil conj []) msg))))))

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
    (safely conn run-id :review data
      (fn []
        (let [prob (str "Review this feature's work.\n\nFeature:\n" (:problem branch)
                        "\n\nThe implementors reported:\n" (:final-answer branch))
              {:keys [verdict answer]}
              (try (run-role (wf/role-ctx ctx :reviewer) (wf/compiled-manifest "reviewer")
                             (str "R" (revision data)) prob
                             (wf/prompt-text "roles/reviewer"))
                   (catch Throwable e {:verdict :error :answer (ex-message e)}))
              decision (review-decision verdict answer)]
          (journal/note! conn run-id :review {:data {:decision decision :verdict verdict}})
          (assoc data :review/decision decision :review/findings (str answer))))
      ;; fail-open: a broken review does not block shipping
      (fn [d] (assoc d :review/decision :pass :review/findings "")))))

(defn- parse-args [r]
  (update r :args #(try (json/read-str (str %) :key-fn keyword)
                        (catch Throwable _ {}))))

(cell/defcell :feature/critique
  {:doc "The critic role: gate the feature result with the finalization judge —
        deterministic checks, then an LLM verdict on the answer + the run's diff
        — but WITHOUT the single-branch critic's branch surgery. Sets
        :critic/decision :ship or :revise. Fail-open (a judge that errors ships)."
   :effects [:net :db]}
  (fn [{:keys [conn run-id root git-baseline] :as ctx}
       {:keys [branch] :as data}]
    (safely conn run-id :critique data
      (fn []
        (let [{:keys [llm-adapter llm-config]} (wf/role-ctx ctx :critic)
              answer (:final-answer branch)
              rows (map parse-args (journal/turns conn run-id))
              ;; Ground truth first: a done with no diff is not a completed
              ;; feature, whatever the workers claimed. This bounces before the
              ;; LLM judge is even paid for.
              det (or (when (hollow? ctx)
                        "no files were changed — the implementors called done but the working tree is unchanged, so nothing was actually built")
                      (judge/deterministic-block answer rows))
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
          (assoc data :critic/decision decision :critique/findings (or det ""))))
      ;; fail-open: a broken critic ships rather than wedging the loop
      (fn [d] (assoc d :critic/decision :ship :critique/findings "")))))

(defn- supervise-directive
  "The within-run directive from the supervisor's verdict + answer: STOP (ship
  and end), REVISE (force another round), or CONTINUE (default). A supervisor
  that could not finish or said nothing fails SAFE to :continue — it must not be
  able to wedge or hijack the loop by crashing."
  [verdict answer]
  (if (or (not= :done verdict) (str/blank? (str answer)))
    :continue
    (let [first-line (-> (str answer) str/split-lines first str str/upper-case)]
      (cond (str/includes? first-line "STOP")   :stop
            (str/includes? first-line "REVISE") :revise
            :else :continue))))

(defn- tail [s n]
  (->> (str/split-lines (str s)) (remove str/blank?) (take-last n) (str/join "\n")))

(cell/defcell :feature/verify
  {:doc "Gate 2 — run the tests. The completion criteria are two gates: gate 1 is
        that a diff exists and the review passes (hollow? + reviewer + critic);
        gate 2, here, is that the tests actually pass. Runs config :run
        :verify-cmd in the project root and passes only on exit 0. Short-circuits
        (does not pay for a test run) when gate 1 already failed — a hollow diff
        or a revise verdict means the loop is going back anyway. No :verify-cmd
        configured -> not applicable, passes."
   :effects [:proc :db]}
  (fn [{:keys [conn run-id root config] :as ctx} data]
    (let [cmd (get-in config [:run :verify-cmd])]
      (cond
        (hollow? ctx)
        (assoc data :verify/passed? false :verify/note "not run — no diff to test")

        (or (= :revise (:review/decision data)) (= :revise (:critic/decision data)))
        (assoc data :verify/passed? false :verify/note "not run — review already sent it back")

        (str/blank? (str cmd))
        (assoc data :verify/passed? true :verify/note "no :verify-cmd configured")

        :else
        (let [r (proc/run {:timeout-ms (or (get-in config [:run :verify-timeout-ms]) 600000)}
                          "sh" "-c" (str "cd " root " && " cmd))
              passed? (and (not (:timeout r)) (zero? (or (:exit r) 1)))]
          (journal/note! conn run-id :verify
                         {:data {:passed passed? :exit (:exit r) :timeout (:timeout r)}})
          (assoc data :verify/passed? passed?
                 :verify/note (cond (:timeout r) "tests TIMED OUT"
                                    passed? "tests passed"
                                    :else (str "tests FAILED (exit " (:exit r) ")\n"
                                               (tail (str (:out r) "\n" (:err r)) 25)))))))))

(cell/defcell :feature/supervise
  {:doc "The supervisor: the harness's introspection. It runs a supervisor ROLE
        loop (supervisor.edn) over a run-health digest — it diagnoses what is
        suboptimal and decides. Two levers: a within-run directive (CONTINUE /
        REVISE / STOP, read by :feature/route) and, through its own tools, tuning
        the harness's manifests/prompts/cells for future runs (the mutation
        protocol validates those). Not a fixed rule — a reasoning agent. Fails
        SAFE to :continue so it can never wedge the loop."
   :effects [:net :db]}
  (fn [{:keys [conn run-id config] :as ctx} {:keys [results] :as data}]
    (safely conn run-id :supervise data
      (fn []
        (let [soft-cap (or (get-in config [:run :max-revisions]) 6)
              rev (revision data)
              dig (telemetry/digest {:results results
                                     :review (:review/decision data)
                                     :critic (:critic/decision data)
                                     :revision rev
                                     ;; stage crashes are the first thing the
                                     ;; supervisor should see and plan around.
                                     :errors (:feature/errors data)
                                     ;; ground truth: did the working tree
                                     ;; actually change, or was done hollow?
                                     :hollow? (hollow? ctx)
                                     ;; gate 2 — did the tests pass, and if not why.
                                     :tests-passed? (:verify/passed? data)
                                     :verify-note (:verify/note data)
                                     ;; the soft cap is a notification, not a
                                     ;; verdict: at it, the supervisor decides.
                                     :at-cap? (>= rev soft-cap)
                                     :soft-cap soft-cap}
                                    (journal/turns conn run-id))
              prob (str "Introspect on this run and decide whether the loop needs "
                        "an adjustment. A STAGE CRASHED signal is a harness bug the "
                        "loop just survived — diagnose it and, if you can, fix it at "
                        "the source with your tools. If a problem is systemic, tune "
                        "the harness.\n\n" dig
                        "\n\n## Workflows you can switch to, tune, or add to\n"
                        "When the current approach keeps failing — e.g. the "
                        "implementors cannot do the task in one shot — a DIFFERENT "
                        "workflow may fit better. You can point future runs at one of "
                        "these, tune one, or author a new one with the manifest/cells "
                        "tools:\n"
                        ;; auxiliary context — a catalog hiccup must never skip
                        ;; the supervisor itself.
                        (try (wf/render-catalog conn) (catch Throwable _ "")))
              {:keys [verdict answer]}
              (try (run-role (wf/role-ctx ctx :supervisor) (wf/compiled-manifest "supervisor")
                             (str "S" (revision data)) prob
                             (wf/prompt-text "roles/supervisor"))
                   (catch Throwable e {:verdict :error :answer (ex-message e)}))
              directive (supervise-directive verdict answer)]
          (journal/note! conn run-id :supervise
                         {:data {:directive directive :verdict verdict}})
          (cond-> (assoc data :supervisor/notes (str answer))
            (= directive :revise) (assoc :feature/escalate true)
            (= directive :stop)   (assoc :feature/stop true))))
      ;; fail-safe: a broken supervisor lets the loop proceed unchanged
      (fn [d] d))))

(cell/defcell :feature/route
  {:doc "Decide the feature's fate. The default is to KEEP SOLVING: unless the
        work is real and verified, send it back to implement with the findings
        as guidance — the loop is an open-ended problem solver, not a one-shot.
        It SHIPS (completed) only on real, verified work (reviewer pass + critic
        ship + the working tree actually changed). It ABANDONS only when the
        SUPERVISOR gives up (STOP) after failing to find a solution — the loop is
        fully supervisor-driven, with no numeric cap that ends it. A run may opt
        into a hard runaway guard (:run :max-revisions-hard) as an unattended
        safety net, but by default there is none. Abandoning is honest, not a
        hollow ship: the run reports it did not solve the task."
   :effects [:db]}
  (fn [{:keys [conn run-id config] :as ctx} data]
    (let [rev (revision data)
          soft-cap (or (get-in config [:run :max-revisions]) 6)
          ;; Fully supervisor-driven by default: there is NO numeric abandon, so
          ;; the loop keeps solving until the supervisor decides to STOP. A hard
          ;; runaway guard exists only if a run explicitly opts into one
          ;; (:run :max-revisions-hard) — a safety net for unattended runs, not
          ;; the normal terminator.
          hard-cap (get-in config [:run :max-revisions-hard])
          hollow (hollow? ctx)
          ;; BOTH gates green to ship completed. Gate 1: a diff exists and it
          ;; passed review (reviewer + critic). Gate 2: the tests passed.
          pass? (and (= :pass (:review/decision data))
                     (= :ship (:critic/decision data))
                     (not (:feature/escalate data))
                     (not hollow)
                     (:verify/passed? data))
          ;; Abandon only in the extreme: the supervisor gave up (STOP), or the
          ;; runaway guard tripped. The SOFT cap does NOT abandon — it only
          ;; notifies the supervisor (via telemetry) so it decides for itself.
          give-up? (:feature/stop data)
          runaway? (and hard-cap (>= rev hard-cap))
          decision (cond pass? :ship
                         (or give-up? runaway?) :abandon
                         :else :revise)]
      (journal/note! conn run-id :route
                     {:data {:decision decision :revision rev :soft-cap soft-cap
                             :hard-cap hard-cap :hollow hollow
                             :tests-passed (:verify/passed? data)
                             :gave-up (boolean give-up?) :runaway runaway?}})
      (case decision
        :ship
        (assoc data :feature/decision :ship)   ; -> finish, :completed

        :abandon
        ;; Honest end, not a hollow completed. Any partial work stays on disk for
        ;; a human or the next run to pick up; the run just does not claim done.
        (assoc data :feature/decision :ship :verdict :abandoned
               :branch (assoc (:branch data)
                              :status :abandoned :final-answer nil
                              :inactive-reason
                              (if give-up?
                                "supervisor could not find a solution"
                                (str "runaway guard tripped after " rev " revisions"))))

        :revise
        ;; keep solving — another implement round with the findings as guidance.
        (-> data
            (assoc :feature/decision :revise
                   :feature/revisions (inc rev)
                   :feature/escalate false
                   :revise/guidance
                   (str/trim
                    (str (when hollow
                           "The implementors called done but changed no files — actually edit the code this round.\n\n")
                         (when (= :revise (:review/decision data))
                           (str "Reviewer asked for changes:\n"
                                (:review/findings data) "\n\n"))
                         (when (seq (:critique/findings data))
                           (str "Critic flagged:\n" (:critique/findings data) "\n\n"))
                         ;; the tests are ground truth — a failure here is the
                         ;; most actionable guidance the implementors can get.
                         (when (and (some? (:verify/passed? data))
                                    (not (:verify/passed? data))
                                    (not hollow)
                                    (not= :revise (:review/decision data)))
                           (str "The tests did not pass:\n" (:verify/note data))))))
            (dissoc :results :review/decision :critic/decision
                    :review/findings :critique/findings :verify/passed? :verify/note))))))
