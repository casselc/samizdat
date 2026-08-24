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

(ns samizdat.store.migrations
  "Schema as numbered, idempotent migrations against PRAGMA user_version.

  Every migration is a VECTOR OF SINGLE STATEMENTS, never one multi-statement
  string. db.sqlite/query calls sqlite3_prepare_v2 with a null tail pointer, so
  a string holding several statements executes only the first and reports no
  error at all. migrations-test asserts the statement count of each migration
  against the objects it should have created, so that failure mode cannot
  return quietly.

  Add a migration by appending to `migrations`; never edit one that shipped.
  Each carries a comment saying what it is for, which is the reason dirge's
  schema is still legible eleven migrations in.")

(def ^:private v1
  ;; The run journal. Everything the loop learns is appended here as it
  ;; happens, not assembled at the end, so a crashed run stays inspectable and
  ;; the read API can serve a live run and a finished one with the same query.
  ["CREATE TABLE IF NOT EXISTS runs (
      id            TEXT PRIMARY KEY,
      problem       TEXT NOT NULL,
      status        TEXT NOT NULL DEFAULT 'running',
      provider      TEXT NOT NULL DEFAULT '',
      model         TEXT NOT NULL DEFAULT '',
      max_turns     INTEGER NOT NULL DEFAULT 0,
      beam_width    INTEGER NOT NULL DEFAULT 0,
      prompt_digest TEXT NOT NULL DEFAULT '',
      final_answer  TEXT,
      started_at    TEXT NOT NULL,
      ended_at      TEXT
    )"

   ;; A branch is an entity with a durable id rather than a value threaded
   ;; through the loop, because an intervention has to be able to name one.
   "CREATE TABLE IF NOT EXISTS branches (
      id              TEXT NOT NULL,
      run_id          TEXT NOT NULL REFERENCES runs(id),
      parent_id       TEXT,
      status          TEXT NOT NULL DEFAULT 'active',
      inactive_reason TEXT,
      thesis          TEXT,
      created_at_turn INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (run_id, id)
    )"

   "CREATE TABLE IF NOT EXISTS turns (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL REFERENCES runs(id),
      branch_id  TEXT NOT NULL,
      turn       INTEGER NOT NULL,
      tool_name  TEXT NOT NULL DEFAULT '',
      args       TEXT NOT NULL DEFAULT '',
      result     TEXT NOT NULL DEFAULT '',
      category   TEXT,
      parse_error   TEXT,
      auto_repaired INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL
    )"

   "CREATE INDEX IF NOT EXISTS idx_turns_run ON turns(run_id, branch_id, turn)"

   ;; claim_status is the confirmed / refuted / ambiguous / existential split.
   ;; The existential bucket is why this column exists: a SAT verdict over free
   ;; variables says a solution exists and does not hand you one, and the
   ;; done gate refuses to let it substantiate a concrete answer.
   "CREATE TABLE IF NOT EXISTS artifacts (
      id           INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id       TEXT NOT NULL REFERENCES runs(id),
      branch_id    TEXT NOT NULL,
      turn         INTEGER NOT NULL,
      kind         TEXT NOT NULL,
      claim        TEXT NOT NULL,
      code         TEXT NOT NULL DEFAULT '',
      verdict      TEXT,
      witness      TEXT,
      claim_status TEXT NOT NULL,
      tier         TEXT NOT NULL DEFAULT 'fast',
      created_at   TEXT NOT NULL
    )"

   "CREATE INDEX IF NOT EXISTS idx_artifacts_run ON artifacts(run_id, branch_id)"

   ;; The cross-branch failure log. In the TypeScript harness this is a vector
   ;; re-rendered into every branch's context each turn, so it grows without
   ;; bound; backed by FTS5 it becomes a query for the failures most like what
   ;; this branch is about to try.
   "CREATE TABLE IF NOT EXISTS failures (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL REFERENCES runs(id),
      branch_id  TEXT NOT NULL,
      turn       INTEGER NOT NULL,
      tool_name  TEXT NOT NULL DEFAULT '',
      claim      TEXT NOT NULL DEFAULT '',
      reason     TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL
    )"

   ;; Standalone FTS5, not external-content: the indexed text is a projection
   ;; (claim plus reason), and external-content deletes require the exact
   ;; indexed values back, which a projection cannot promise. Sync is
   ;; app-managed in store.failures, no triggers.
   "CREATE VIRTUAL TABLE IF NOT EXISTS failures_fts USING fts5(claim, reason)"

   ;; Decision observability: a gate firing records what it expected to happen
   ;; next, and a later turn settles that prediction from the journal with no
   ;; LLM in the path. A gate whose predictions never settle is not steering.
   "CREATE TABLE IF NOT EXISTS gate_firings (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id      TEXT NOT NULL REFERENCES runs(id),
      branch_id   TEXT NOT NULL,
      turn        INTEGER NOT NULL,
      gate        TEXT NOT NULL,
      priority    INTEGER NOT NULL DEFAULT 0,
      message     TEXT NOT NULL DEFAULT '',
      prediction  TEXT NOT NULL DEFAULT '',
      window      INTEGER NOT NULL DEFAULT 0,
      outcome     TEXT,
      settled_at_turn INTEGER,
      created_at  TEXT NOT NULL
    )"

   "CREATE INDEX IF NOT EXISTS idx_gate_firings_run ON gate_firings(run_id, gate)"

   ;; Human directives. Applied at the next branch boundary, never mid-turn:
   ;; a branch inside a provider call or a Lean tactic is not in a state anyone
   ;; should mutate. status carries pending / applied / rejected so a UI can
   ;; show the difference honestly instead of pretending a click took effect.
   "CREATE TABLE IF NOT EXISTS interventions (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id      TEXT NOT NULL REFERENCES runs(id),
      branch_id   TEXT,
      kind        TEXT NOT NULL,
      payload     TEXT NOT NULL DEFAULT '',
      issued_by   TEXT NOT NULL DEFAULT 'human',
      status      TEXT NOT NULL DEFAULT 'pending',
      disposition TEXT,
      created_at  TEXT NOT NULL,
      applied_at_turn INTEGER
    )"

   "CREATE INDEX IF NOT EXISTS idx_interventions_pending
      ON interventions(run_id, status)"

   ;; The event cursor the tail endpoint reads. One row per appended event, so
   ;; GET /v1/runs/:id/journal?since=N is a single indexed range scan and the
   ;; UI needs no cooperation from the loop.
   "CREATE TABLE IF NOT EXISTS events (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL REFERENCES runs(id),
      branch_id  TEXT,
      turn       INTEGER,
      kind       TEXT NOT NULL,
      data       TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL
    )"

   "CREATE INDEX IF NOT EXISTS idx_events_cursor ON events(run_id, id)"])

(def ^:private v3
  ;; The run-scoped shared confirmed-artifact log, twin of the failure log.
  ;; Branches already share what was disproven; this shares what an engine
  ;; CONFIRMED, with provenance (branch, engine, tier) inline. Only
  ;; engine-confirmed artifacts enter — never self-reports, which is the
  ;; difference from UCLA's harness. Off by default because shared lemmas can
  ;; cost beam diversity; sweep-widths runs it both ways to find out.
  ["CREATE TABLE IF NOT EXISTS shared_artifacts (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL REFERENCES runs(id),
      branch_id  TEXT NOT NULL,
      turn       INTEGER NOT NULL,
      kind       TEXT NOT NULL DEFAULT '',
      tier       TEXT NOT NULL DEFAULT 'fast',
      claim      TEXT NOT NULL DEFAULT '',
      code       TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL
    )"
   ;; Standalone FTS5 like failures_fts: the indexed text is a projection
   ;; (claim alone here), and sync is app-managed in store.artifacts.
   "CREATE VIRTUAL TABLE IF NOT EXISTS shared_artifacts_fts USING fts5(claim)"])

(def ^:private v2
  ;; What the model actually said.
  ;;
  ;; v1 stored the tool call and the result but not the prose around it, so a
  ;; turn that produced no tool call at all recorded only that fact. Nine of
  ;; twenty turns in a Lean run came back "__no_call__" and there was no way to
  ;; ask why, because the one artefact that would answer it was the one thing
  ;; not kept. A harness whose whole thesis is that decisions must be
  ;; inspectable after the fact should not throw away the decision.
  ;;
  ;; Nullable, because the provider-error path has no response to record.
  ["ALTER TABLE turns ADD COLUMN assistant_text TEXT"
   ;; The reasoning block, split out rather than left inline. Reasoning models
   ;; put most of their output here and it dwarfs the answer, so a UI wants to
   ;; fold it and a query that greps the response should not have to wade
   ;; through it. Empty for models that do not emit one.
   "ALTER TABLE turns ADD COLUMN reasoning_text TEXT"])

(def ^:private v4
  ;; What each turn cost.
  ;;
  ;; The adapter parsed usage and client/chat returned it, and the agent loop
  ;; dropped it: nothing outside the bench harness and the raw passthrough API
  ;; ever read it, and turns had no columns to put it in. So the one number a
  ;; harness whose operating rule is "a generation is hours of provider spend"
  ;; most needs was the number it never kept.
  ;;
  ;; Nullable throughout, deliberately. The provider-error path has no response
  ;; and therefore no usage, and a zero there would claim the call was free —
  ;; summing it would under-report the run rather than admit the gap.
  ["ALTER TABLE turns ADD COLUMN prompt_tokens INTEGER"
   "ALTER TABLE turns ADD COLUMN completion_tokens INTEGER"
   "ALTER TABLE turns ADD COLUMN total_tokens INTEGER"
   ;; The prefix-cache split, when the provider reports one. Each branch
   ;; carries its own growing message list, so a beam of five holds five
   ;; diverging prefixes; whether that is cheap is the question these answer.
   "ALTER TABLE turns ADD COLUMN cache_hit_tokens INTEGER"
   "ALTER TABLE turns ADD COLUMN cache_miss_tokens INTEGER"])

(def ^:private v5
  ;; One bit per turn: whether the tool call was declined by harness phase
  ;; policy (vf-b25/vf-eaw). The cull record has to be able to tell a
  ;; declined call — a perfectly well-formed one the harness refused — from a
  ;; malformed fence, or the reason string lies in the permanent record
  ;; (gen-30 B3.2 was culled with exactly that false reason).
  ["ALTER TABLE turns ADD COLUMN policy_refusal INTEGER"])

(def ^:private v6
  ;; The task board. dirge's issues schema with two generalizations: epic_id
  ;; becomes a self-referential parent_id plus a type column (an epic is a
  ;; TYPE of task, so epics nest and the model decides how many levels it
  ;; wants), and session scoping becomes run scoping (run_id NULL = passive
  ;; backlog, set = claimed onto that run's active board). contract and tests
  ;; are what make a task a delegable unit: the spec the work must satisfy
  ;; and the tests that define delivery, per the decomposition design.
  ["CREATE TABLE IF NOT EXISTS tasks (
      id          TEXT PRIMARY KEY,
      title       TEXT NOT NULL,
      body        TEXT NOT NULL DEFAULT '',
      type        TEXT NOT NULL DEFAULT 'task',
      status      TEXT NOT NULL DEFAULT 'open',
      priority    TEXT NOT NULL DEFAULT 'normal',
      parent_id   TEXT REFERENCES tasks(id),
      run_id      TEXT,
      contract    TEXT NOT NULL DEFAULT '',
      tests       TEXT NOT NULL DEFAULT '',
      created_at  TEXT NOT NULL,
      updated_at  TEXT NOT NULL,
      closed_at   TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)"
   "CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks(parent_id)"])

(def ^:private v7
  ;; Workflow definitions: the agentic loop as durable, versioned data. Every
  ;; save is a new version — never an UPDATE — so a bad edit is rolled back by
  ;; pointing at the previous row and the whole edit history stays readable.
  ;; Runs journal which (name, version) drove them.
  ["CREATE TABLE IF NOT EXISTS workflows (
      name       TEXT NOT NULL,
      version    INTEGER NOT NULL,
      edn        TEXT NOT NULL,
      created_at TEXT NOT NULL,
      PRIMARY KEY (name, version)
    )"])

(def ^:private v8
  ;; Session permission grants. When a command that would ask is approved by a
  ;; human, the grant persists here scoped to its run and is consulted ahead of
  ;; the base rules on later commands. Human-only writes: nothing the model
  ;; emits reaches this table (the model has no edge into grants — see the
  ;; security model, docs/security.md). A grant can never override a hard deny.
  ["CREATE TABLE IF NOT EXISTS grants (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL,
      pattern    TEXT NOT NULL,
      created_at TEXT NOT NULL
    )"
   "CREATE INDEX IF NOT EXISTS idx_grants_run ON grants(run_id)"])

(def ^:private v9
  ;; Long-term knowledge: facts a run commits to durable rows and recalls by
  ;; search later. Unlike turns/artifacts (the run's own record of what it
  ;; did), a knowledge row is a claim extracted as worth keeping — content is
  ;; the searchable text, kind separates notes from other kinds later. Recall
  ;; is a LIKE scan, so content is the index and needs no extra one.
  ["CREATE TABLE IF NOT EXISTS knowledge (
      id         TEXT PRIMARY KEY,
      content    TEXT NOT NULL,
      kind       TEXT NOT NULL DEFAULT 'note',
      created_at TEXT NOT NULL
    )"])

(def ^:private v10
  ;; Agent mailbox: durable messages between branches working one feature.
  ;; to_branch NULL means broadcast to the whole run. read_at NULL marks the
  ;; message unread — surfacing it in context does not consume it; the inbox
  ;; tool is what stamps read_at. Inbox queries filter on run_id + read_at,
  ;; so the natural index covers them.
  ["CREATE TABLE IF NOT EXISTS messages (
      id          TEXT PRIMARY KEY,
      run_id      TEXT NOT NULL,
      from_branch TEXT NOT NULL,
      to_branch   TEXT,
      body        TEXT NOT NULL,
      created_at  TEXT NOT NULL,
      read_at     TEXT
    )"

   "CREATE INDEX IF NOT EXISTS idx_messages_run ON messages(run_id, read_at)"])

(def ^:private v11
  ;; Durable evaluator receipts (the JS1 record of a JS0 evaluation).
  ;;
  ;; jolt.sandbox keeps an evaluation's receipts in an in-process atom:
  ;; ordered {op args result|error} entries, one per observation or
  ;; actuation, consumed in order at replay. A process that dies between an
  ;; actuation and the end of its evaluation loses the atom while the world
  ;; keeps the actuation, and nothing on record even says which window was
  ;; open. These tables are the durable version of that model.
  ;;
  ;; The shape is append-only in the strict sense: nothing here is ever
  ;; updated or deleted. An evaluation is registered BEFORE it runs (intent:
  ;; the trusted evaluator identity — spec/instance/binding ids — plus the
  ;; canonical evaluator/context coordinate, the versioned RuntimeCoordinate
  ;; naming the Jolt/SCI/protocol stack the receipts are meaningful under,
  ;; and the source), each effect is recorded as an intent row before it
  ;; executes and an outcome row after, and completion is a row of its own
  ;; appended afterward. A crashed evaluation's record is then exactly what
  ;; the process managed to append — a pending eval row, and an intent with
  ;; no outcome for the effect that was in flight — which is what a caller
  ;; needs to fail closed: the actuation may or may not have happened, and
  ;; the record says so honestly instead of reconstructing a story.
  ;;
  ;; binding_seq is the binding's durable total order: evaluations of one
  ;; binding are numbered 0, 1, 2, ... in registration order at begin! time,
  ;; so whole-history rebuild replays a binding's committed evaluations in
  ;; exactly the order they committed, and a gap in the sequence is a torn
  ;; record to fail closed on rather than a hole to replay across.
  ;;
  ;; Terminal status lives in eval_completions rather than as a status
  ;; column on evals so that "pending" is the ABSENCE of a row: there is no
  ;; status to set wrong, only a completion that has or has not been
  ;; appended. The unique indexes make the invariants structural — one
  ;; terminal record per evaluation, at most one intent and one outcome
  ;; per (eval, seq), and one row per (binding, binding_seq) — rather than
  ;; matters of caller discipline.
  ["CREATE TABLE IF NOT EXISTS evals (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      spec_id     TEXT NOT NULL,
      instance_id TEXT NOT NULL,
      binding_id  TEXT NOT NULL,
      binding_seq INTEGER NOT NULL,
      coordinate  TEXT NOT NULL,
      runtime     TEXT NOT NULL,
      source      TEXT NOT NULL,
      created_at  TEXT NOT NULL
    )"

   "CREATE UNIQUE INDEX IF NOT EXISTS idx_evals_binding_seq
      ON evals(binding_id, binding_seq)"

   "CREATE TABLE IF NOT EXISTS eval_completions (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      eval_id    INTEGER NOT NULL REFERENCES evals(id),
      status     TEXT NOT NULL,
      result     TEXT,
      created_at TEXT NOT NULL
    )"

   "CREATE UNIQUE INDEX IF NOT EXISTS idx_eval_completions_once
      ON eval_completions(eval_id)"

   "CREATE TABLE IF NOT EXISTS eval_receipts (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      eval_id    INTEGER NOT NULL REFERENCES evals(id),
      seq        INTEGER NOT NULL,
      phase      TEXT NOT NULL,
      op         TEXT NOT NULL DEFAULT '',
      args       TEXT,
      result     TEXT,
      error      TEXT,
      created_at TEXT NOT NULL
    )"

   "CREATE UNIQUE INDEX IF NOT EXISTS idx_eval_receipts_phase
      ON eval_receipts(eval_id, seq, phase)"])

(def migrations
  "Ordered. Index 0 is migration 1; PRAGMA user_version holds the count applied."
  [v1 v2 v3 v4 v5 v6 v7 v8 v9 v10 v11])
