;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; The agentic loop's cells — dynamically loaded from resources, NOT compiled
;; into src. Each is a thin cell over a named step in samizdat.agent.loop, so
;; the cell layer is policy (wiring, docs, effects) and the step logic it calls
;; is core infrastructure. This file is loaded at runtime by samizdat.cells;
;; edit it and reload to change the loop's behavior without recompiling.
;;
;; The workflow data map carries {:branch :turn} plus per-turn products
;; (:call :parsed :signals :said :result :tool :verdict); resources carry the
;; run ctx ({:conn :run-id :config :llm-adapter :llm-config :max-turns}).
;; Naming is load-bearing: :llm/*, :tool/*, :journal/*, :gate/* are what
;; glob-scoped interceptors match on.
(ns cells.loop
  (:require [mycelium.cell :as cell]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.reflect :as reflect]
            [samizdat.agent.state :as state]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(cell/defcell :loop/assemble
  {:doc "Open the turn: capture the before-snapshot the settle step compares
        against, and run the explore-prologue release valve so its message
        lands before the model call."
   :pure true
   :requires []}
  (fn [_ctx {:keys [branch turn] :as data}]
    (assoc data
           :before branch
           :branch (turn/phase-valve branch turn))))

(cell/defcell :llm/infer
  {:doc "One model call, retried once at a doubled budget when the response hit
        the token cap before emitting a tool call. Produces :call {:ok
        :response} or {:ok false :error} — a provider failure is data, never an
        exception."
   :effects [:net :db]
   :requires []}
  (fn [ctx {:keys [branch] :as data}]
    (assoc data :call (turn/call-model ctx branch))))

(cell/defcell :llm/parse
  {:doc "Fold the response into the branch: parse the fence, record mechanics
        signals, append what the assistant actually said. On a provider failure
        this passes the data through untouched for the error route."
   :pure true
   :requires []}
  (fn [_ctx {:keys [branch call turn] :as data}]
    (if-not (:ok call)
      data
      (let [{:keys [branch parsed signals said]}
            (turn/absorb-response branch (:response call) turn)]
        (assoc data :branch branch :parsed parsed :signals signals :said said)))))

(cell/defcell :loop/provider-error
  {:doc "A provider failure is not the branch's fault: journal it as neutral
        and tell the branch to try again."
   :effects [:db]
   :requires []}
  (fn [ctx {:keys [branch turn call] :as data}]
    (assoc data :branch (turn/provider-error-step ctx branch turn
                                                  (:error call) (:reason call)))))

(cell/defcell :loop/no-call
  {:doc "The response carried no usable tool call: say exactly what was wrong,
        journal the turn as mechanics, and make the next request start
        mid-fence so prose is not an available reply."
   :effects [:db]
   :requires []}
  (fn [ctx {:keys [branch turn parsed signals said call] :as data}]
    (assoc data :branch (turn/no-call-step ctx branch turn
                                           {:parsed parsed :signals signals
                                            :said said :response (:response call)}))))

(cell/defcell :tool/dispatch
  {:doc "Phase policy first, then the tool, then the branch bookkeeping the
        outcome demands (outcome counters, artifact banking, repeat-failure
        escalation)."
   :effects [:db :fs :proc]
   :requires []}
  (fn [ctx {:keys [branch turn parsed] :as data}]
    (merge data (turn/tool-step ctx branch turn parsed))))

(cell/defcell :journal/record
  {:doc "The durable record of the turn: the turn row, any artifact and its
        entry into the shared pool, any failure, any thesis. Everything a gate
        reads and everything resume replays goes through here."
   :effects [:db]
   :requires []}
  (fn [ctx {:keys [branch turn parsed result tool said call] :as data}]
    (turn/journal-step! ctx branch turn {:parsed parsed :result result
                                         :tool tool :said said
                                         :response (:response call)})
    data))

(cell/defcell :gate/arbiter
  {:doc "Predictions settle, then the single boundary: at most one steer,
        chosen in priority, plus the context block of shared artifacts and
        similar failures."
   :effects [:db]
   :requires []}
  (fn [ctx {:keys [before branch turn parsed result] :as data}]
    (assoc data :branch (turn/steer-step ctx before branch turn
                                         {:parsed parsed :result result}))))

(cell/defcell :loop/route
  {:doc "Decide the turn's verdict: :continue (next turn), :done, :abandoned,
        or :exhausted at the turn cap. On :continue the per-turn products are
        dropped so the data map does not grow without bound. Reads only the
        branch and the configured cap — no side effects."
   :pure true
   :requires [:max-turns]}
  (fn [ctx {:keys [branch turn] :as data}]
    (let [max-turns (:max-turns ctx)
          verdict (cond
                    (not (state/active? branch))
                    (if (:final-answer branch) :done :abandoned)

                    (>= turn max-turns) :exhausted
                    :else :continue)]
      (cond-> (assoc data :verdict verdict)
        (= verdict :continue)
        (-> (update :turn inc)
            (dissoc :before :call :parsed :signals :said :result :tool)
            ;; Each mycelium trace entry snapshots the whole data map — branch
            ;; message history included — so an uncapped trace grows
            ;; quadratically over a run. The journal is the durable record; the
            ;; in-data trace is a debugging window, and a window has edges.
            (update :mycelium/trace #(vec (take-last 20 %))))))))

(cell/defcell :memory/distil
  {:doc "What this task leaves behind about the PROJECT.

        Runs when a task ENDS, however it ended. A run that shipped knows how
        the project is built; a run that gave up knows what wasted its turns,
        and that is often the more valuable of the two — a gotcha recorded once
        saves every later session the turn it costs to rediscover.

        A STEP rather than an instruction. The prompt has asked the model to
        `remember` project facts for a while and produced none: 46 turns of
        live runs, zero calls. A node in the manifest runs whether or not the
        model felt like it.

        Fails safe to the data it was given: recording what was learned must
        never be able to stop a finished task from finishing."
   :effects [:net :db]
   :requires [:conn :run-id :llm-adapter :llm-config]}
  (fn [ctx {:keys [branch] :as data}]
    (reflect/distil-task! ctx branch)
    data))

(cell/defcell :loop/finish
  {:doc "Close the run the way the verdict says: branch row, run row, and for
        an exhausted run the residual — what the branch believed it was close
        to when the budget ran out."
   :effects [:db]
   :requires [:conn :run-id]}
  (fn [{:keys [conn run-id]} {:keys [branch turn verdict] :as data}]
    (case verdict
      (:done :abandoned)
      (let [status (if (:final-answer branch) :completed :abandoned)]
        (runs/close-branch! conn run-id (:id branch)
                            (:status branch) (:inactive-reason branch))
        (runs/finish-run! conn run-id status (:final-answer branch))
        (assoc data :status status :answer (:final-answer branch)))

      :exhausted
      (let [residual (state/residual branch)]
        (runs/close-branch! conn run-id (:id branch) :exhausted
                            (str "turn cap of " turn " reached"))
        (journal/note! conn run-id :residual
                       {:branch-id (:id branch) :data residual})
        (runs/finish-run! conn run-id :failed nil)
        (assoc data :status :exhausted :residual residual)))))
