;; samizdat - a self-hosting agentic harness
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

(ns samizdat.mutation
  "The self-modification protocol: the safety around the agent editing its own
  cells.

  The agent edits a cell file, then this runs
  checkpoint -> reload -> validate -> soak -> commit or rollback. A good edit
  commits and is live on the next turn; a bad one — a syntax error, a wiring
  break, or a cell that throws on valid input — rolls the registry AND the file
  back to the last known-good state and is journaled as a negative constraint,
  so a bad edit can never brick the loop.

  This is the plan's 'edit the executor, hot-reload, revert on fault' loop, and
  autolith's journaled mutation state machine (journal -> install -> check ->
  commit -> select -> durable) with a two-tier explore-then-commit: the reload
  installs into the live image, validate + soak are the checks, and commit is
  the point of no return. Once dolt lands (karamazov-ioo.17) a commit becomes a
  dolt commit and rollback a dolt revert; today the durable record is the cell
  file on disk plus the journal."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [samizdat.cells :as cells]
            [samizdat.store.journal :as journal]))

(def ^:private soak-timeout-ms 10000)

(defn- restore-files!
  "Write the checkpoint's file contents back to disk — undo the agent's edit."
  [files]
  (doseq [[path content] files]
    (spit path content)))

(defn- soak
  "Dry-run the loop with the edited cells to catch a cell that compiles and
  wires but throws when actually run. Effectful cells are stubbed to identity
  (the :pure/:effects marks earn their keep here) so the run does no IO, and it
  is bounded by a timeout so a cell that loops cannot hang the protocol. The
  registry is restored to its pre-soak state afterward, so the stubs never
  leak. Returns nil on success or a reason string on failure."
  [loop-def soak-input]
  (let [snap (cell/registry-snapshot)]
    (try
      ;; Stub every effectful cell the loop references.
      (doseq [[_ cell-ref] (:cells loop-def)
              :let [cell-id (if (map? cell-ref) (:id cell-ref) cell-ref)
                    spec (cell/get-cell cell-id)]
              :when (and spec (not (cell/pure? spec)))]
        (cell/register-spec! cell-id (assoc spec :handler (fn [_ d] d))))
      (let [compiled (myc/pre-compile loop-def)
            fut (future
                  (try {:result (myc/run-compiled compiled {:max-turns 1}
                                                  (or soak-input {}))}
                       (catch Throwable e {:error (or (ex-message e) (str e))})))
            outcome (deref fut soak-timeout-ms ::timeout)]
        (cond
          (= ::timeout outcome)
          "soak did not terminate within the time budget — the edited cell may loop"

          (:error outcome)
          (str "soak run threw: " (:error outcome))

          (myc/error? (:result outcome))
          (str "soak run produced an error: "
               (:message (myc/workflow-error (:result outcome))))

          :else nil))
      (catch Throwable e
        (str "soak could not run: " (or (ex-message e) (str e))))
      (finally
        (cell/registry-restore! snap)))))

(defn- validate
  "Compile the loop definition through mycelium's static checks (structure,
  reachability, dispatch coverage, constraints). Also rejects a cell that did
  not declare its effects: the soak stubs effectful cells by their :pure/
  :effects marks, so an undeclared cell would run its side effects during the
  soak — the safety the marks exist for is void. Returns nil on success or a
  reason string."
  [compile-fn loop-def]
  (try
    (let [compiled (compile-fn loop-def)
          warnings (:mycelium/compile-warnings
                    (or (:compiled-fsm compiled) compiled))]
      (when-let [undeclared (seq (filter #(= :undeclared-effects (:type %)) warnings))]
        (str "validate: these cells do not declare :pure or :effects, so the "
             "soak cannot safely stub them: "
             (str/join ", " (map :cell-id undeclared)))))
    (catch Throwable e
      (str "validate: the loop no longer compiles — " (or (ex-message e) (str e))))))

(defn- rollback!
  [{:keys [conn run-id dirs]} {:keys [registry files]} reason]
  ;; Undo the edit on disk, restore the registry, then reload from the restored
  ;; (good) files so the loader's known-good content re-syncs. The reload of
  ;; good files cannot fail; if it somehow does, the registry restore above
  ;; already left the running system working.
  (restore-files! files)
  (cell/registry-restore! registry)
  (try (cells/load-cells! (or dirs cells/default-dirs)) (catch Throwable _ nil))
  (when (and conn run-id)
    (journal/note! conn run-id :mutation-rolled-back
                   {:data {:reason reason}}))
  (log/warn "cell mutation rolled back:" reason)
  {:status :rolled-back :reason reason})

(defn apply-cell-edit!
  "Run the mutation protocol after the agent has edited cell files on disk.

  opts:
    :dirs       — the cell dirs to load (default cells/default-dirs)
    :loop-def   — the loop workflow definition to validate + soak against
    :soak-input — the initial data map the soak dry-run starts from
    :compile-fn — how to compile+validate (default mycelium pre-compile)
    :conn :run-id — to journal the outcome (optional)

  Returns {:status :committed} on success (the edit is live), or
  {:status :rolled-back :reason \"...\"} with the registry and files restored."
  [{:keys [dirs loop-def soak-input compile-fn conn run-id] :as opts}]
  (let [dirs (or dirs cells/default-dirs)
        compile-fn (or compile-fn myc/pre-compile)
        ;; CHECKPOINT — the last known-good state to roll back to: the registry
        ;; as loaded, and the on-disk content the loader last loaded (the
        ;; pre-edit content, since nothing has reloaded the agent's edit yet).
        checkpoint {:registry (cell/registry-snapshot)
                    :files (cells/loaded-file-content)}]
    (try
      ;; RELOAD — install the edit into the live image. Transactional: a syntax
      ;; error throws here and the loader has already restored the registry;
      ;; we still restore the file below.
      (cells/load-cells! dirs)
      ;; VALIDATE — does the loop still compile with the edited cells?
      (if-let [reason (validate compile-fn loop-def)]
        (rollback! opts checkpoint reason)
        ;; SOAK — does the edited cell actually run without throwing?
        (if-let [reason (soak loop-def soak-input)]
          (rollback! opts checkpoint reason)
          ;; COMMIT — the edit stands; it is already live in the registry.
          (do (when (and conn run-id)
                (journal/note! conn run-id :mutation-committed
                               {:data {:cells (keys (cells/loaded))}}))
              (log/info "cell mutation committed")
              {:status :committed})))
      (catch Throwable e
        ;; RELOAD failed (syntax error): the loader restored the registry;
        ;; restore the file and report.
        (rollback! opts checkpoint
                   (str "reload: the edited cell file did not load — "
                        (or (ex-message e) (str e))))))))
