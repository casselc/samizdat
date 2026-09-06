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

(ns samizdat.store.lifecycle
  "Append-only decision lifecycle with a current-state view (issue #11, first
  slice). Mechanism only: what a decision IS allowed to become next is the
  transition table below, an invariant of the store like a schema; WHEN a
  decision moves, and what an action means, is the caller's business.

  Every write goes through `transition!`, which does four things inside ONE
  `BEGIN IMMEDIATE` transaction on the shared connection:

    1. idempotency  — an `event-id` already in decision_events is returned as
                      `:duplicate` with its original sequence; nothing changes;
    2. freshness    — the caller presents the `revision` it read; a mismatch is
                      `:stale` and nothing changes (compare-and-set, R2-4);
    3. legality     — the event type must be a legal successor of the current
                      state, else `:illegal`;
    4. the write    — the event row and the pilot_decisions update land
                      together, or neither does.

  A returned action is not a completed effect and a worker's claim of
  completion is not an outcome: the states keep `dispatched`, `completed`,
  `failed` and `interrupted` apart from `evaluated`, which only the evaluator
  writes (and may supersede with `outcome-reevaluated`, keeping every verdict).
  `interrupted` is terminal-but-unusable on purpose: an effect whose
  end was not observed cannot be told from success by inspection later.

  Dispatch is fenced by a single-writer lease per scope (`acquire-lease!`).
  `dispatch-intent` and `dispatched` are accepted only from the lease holder,
  so a second orchestrator on the same file cannot double-dispatch."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            ;; db.jdbc registers the java.sql shim clojure.jdbc compiles against;
            ;; it has to load before jdbc.core (see samizdat.store.db).
            [db.jdbc]
            [jdbc.core :as jdbc]
            [samizdat.store.db :as db]))

(def schema-version
  "Payload schema of decision_events rows written by this namespace."
  1)

(def transitions
  "state -> {event-type -> next-state}. `nil` is the state of a decision that
  does not exist yet; only `action-proposed` creates one."
  {nil            {"action-proposed"   "proposed"}
   "proposed"     {"action-forced"     "chosen"
                   "action-selected"   "chosen"}
   "chosen"       {"action-authorized" "authorized"
                   "action-rejected"   "rejected"}
   "authorized"   {"dispatch-intent"   "dispatching"}
   "dispatching"  {"action-dispatched" "dispatched"
                   "action-interrupted" "interrupted"}
   "dispatched"   {"action-completed"  "completed"
                   "action-failed"     "failed"
                   "action-interrupted" "interrupted"}
   "completed"    {"outcome-evaluated" "evaluated"}
   "failed"       {"outcome-evaluated" "evaluated"}
   "interrupted"  {"outcome-evaluated" "evaluated"}
   "rejected"     {}
   ;; An evaluation can be superseded (the evaluator's check changed) but the
   ;; earlier verdict stays in the event log: re-evaluation is append-only and
   ;; never leaves `evaluated`.
   "evaluated"    {"outcome-reevaluated" "evaluated"}})

(def lease-fenced
  "Event types only the dispatch-lease holder for the decision's case may
  write."
  #{"dispatch-intent" "action-dispatched"})

(defn- js [v] (if (string? v) v (json/write-str v)))

(defn- read-json [s]
  (when (some? s)
    (try (json/read-str (str s) :key-fn keyword) (catch Throwable _ nil))))

(defmacro ^:private in-tx
  "Run body inside BEGIN IMMEDIATE … COMMIT on the serialized connection,
  rolling back on any throw. The body's value is the transaction's."
  [conn & body]
  `(db/with-writer
     (jdbc/execute! ~conn "BEGIN IMMEDIATE")
     (try
       (let [v# (do ~@body)]
         (jdbc/execute! ~conn "COMMIT")
         v#)
       (catch Throwable e#
         (try (jdbc/execute! ~conn "ROLLBACK") (catch Throwable _# nil))
         (throw e#)))))

;; --- cases -----------------------------------------------------------------

(defn upsert-case!
  "Register an immutable case. Re-registering the same case-id with the same
  spec is a no-op (`:existing`); a different spec under the same id throws,
  because a case is the identity other records hang from."
  [conn {:keys [case-id scenario-id checkpoint-id observation-ref domain-ref spec]}]
  (in-tx conn
    (if-let [row (jdbc/fetch-one conn ["SELECT spec FROM pilot_cases WHERE case_id = ?" case-id])]
      (if (= (str (:spec row)) (js spec))
        {:status :existing :case-id case-id}
        (throw (ex-info "case already registered with a different spec"
                        {:case-id case-id})))
      (do (jdbc/execute! conn ["INSERT INTO pilot_cases (case_id, scenario_id, checkpoint_id,
                                                        observation_ref, domain_ref, spec, created_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?)"
                               case-id scenario-id checkpoint-id observation-ref domain-ref
                               (js spec) (db/now)])
          {:status :created :case-id case-id}))))

(defn case-by-id [conn case-id]
  (some-> (db/fetch-one conn ["SELECT * FROM pilot_cases WHERE case_id = ?" case-id])
          (update :spec read-json)))

;; --- leases ----------------------------------------------------------------

(defn acquire-lease!
  "Take or renew the exclusive dispatch lease for `scope` (a case-id). Returns
  {:status :acquired|:renewed|:held :holder … :revision …}. A lease held by
  someone else is refused until it expires; an expired lease is taken over
  with a bumped revision, so the previous holder's fenced writes fail as
  stale rather than interleave."
  [conn scope holder ttl-ms]
  (in-tx conn
    (let [now (java.time.Instant/now)
          expires (str (.plusMillis now ttl-ms))
          row (jdbc/fetch-one conn ["SELECT * FROM pilot_leases WHERE scope = ?" scope])]
      (cond
        (nil? row)
        (do (jdbc/execute! conn ["INSERT INTO pilot_leases (scope, holder, revision, acquired_at, expires_at)
                                  VALUES (?, ?, 1, ?, ?)" scope holder (str now) expires])
            {:status :acquired :holder holder :revision 1})

        (= holder (:holder row))
        (do (jdbc/execute! conn ["UPDATE pilot_leases SET expires_at = ? WHERE scope = ?" expires scope])
            {:status :renewed :holder holder :revision (:revision row)})

        (neg? (compare (str (:expires_at row)) (str now)))
        (let [rev (inc (long (:revision row)))]
          (jdbc/execute! conn ["UPDATE pilot_leases SET holder = ?, revision = ?, acquired_at = ?, expires_at = ?
                                WHERE scope = ?" holder rev (str now) expires scope])
          {:status :acquired :holder holder :revision rev :took-over (:holder row)})

        :else
        {:status :held :holder (:holder row) :revision (:revision row)
         :expires-at (:expires_at row)}))))

(defn release-lease! [conn scope holder]
  (in-tx conn
    (jdbc/execute! conn ["DELETE FROM pilot_leases WHERE scope = ? AND holder = ?" scope holder])
    {:status :released}))

(defn- lease-ok? [conn scope holder]
  (when-let [row (jdbc/fetch-one conn ["SELECT holder, expires_at FROM pilot_leases WHERE scope = ?" scope])]
    (and (= holder (:holder row))
         (not (neg? (compare (str (:expires_at row)) (str (java.time.Instant/now))))))))

;; --- decisions -------------------------------------------------------------

(defn decision
  "Current state of one decision, or nil."
  [conn decision-id]
  (some-> (db/fetch-one conn ["SELECT * FROM pilot_decisions WHERE decision_id = ?" decision-id])
          (update :action_params read-json)))

(defn transition!
  "Append one lifecycle event and move the decision, atomically. See the
  namespace doc for the four checks. `revision` is mandatory: for
  `action-proposed` it is 0 (the decision does not exist yet).

  Returns {:status :applied :event-seq n :revision r :state s}, or one of
  {:status :duplicate …} {:status :stale …} {:status :illegal …}
  {:status :unleased …}, none of which wrote anything."
  [conn {:keys [decision-id event-id event-type revision payload lease-holder
                case-id evaluation-id action-id action-params origin]}]
  (when (str/blank? event-id) (throw (ex-info "event-id is required" {})))
  (when (nil? revision) (throw (ex-info "revision is required" {:decision-id decision-id})))
  (in-tx conn
    (if-let [dup (jdbc/fetch-one conn ["SELECT event_seq, revision FROM decision_events WHERE event_id = ?" event-id])]
      {:status :duplicate :event-seq (:event_seq dup) :revision (:revision dup)}
      (let [row   (jdbc/fetch-one conn ["SELECT * FROM pilot_decisions WHERE decision_id = ?" decision-id])
            state (some-> row :state str)
            cur   (if row (long (:revision row)) 0)
            next  (get-in transitions [state event-type])]
        (cond
          (not= cur (long revision))
          {:status :stale :expected cur :presented revision :state state}

          (nil? next)
          {:status :illegal :state state :event-type event-type}

          (and (contains? lease-fenced event-type)
               (not (lease-ok? conn (str (:case_id row)) lease-holder)))
          {:status :unleased :state state :event-type event-type}

          :else
          (let [now (db/now)
                rev (inc cur)]
            (jdbc/execute! conn ["INSERT INTO decision_events (event_id, decision_id, event_type,
                                                              schema_version, revision, payload, created_at)
                                  VALUES (?, ?, ?, ?, ?, ?, ?)"
                                 event-id decision-id event-type schema-version rev
                                 (js (or payload {})) now])
            (let [seq (:id (jdbc/fetch-one conn "select last_insert_rowid() as id"))]
              (if row
                (jdbc/execute! conn ["UPDATE pilot_decisions SET state = ?, revision = ?, last_event_seq = ?,
                                        action_id = COALESCE(?, action_id),
                                        action_params = COALESCE(?, action_params),
                                        origin = COALESCE(?, origin), updated_at = ?
                                      WHERE decision_id = ? AND revision = ?"
                                     next rev seq action-id (some-> action-params js) origin now
                                     decision-id cur])
                (do (when (or (str/blank? case-id) (str/blank? evaluation-id))
                      (throw (ex-info "action-proposed needs case-id and evaluation-id" {})))
                    (jdbc/execute! conn ["INSERT INTO pilot_decisions (decision_id, case_id, evaluation_id,
                                            state, revision, action_id, action_params, origin,
                                            last_event_seq, created_at, updated_at)
                                          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                         decision-id case-id evaluation-id next rev action-id
                                         (some-> action-params js) origin seq now now])))
              {:status :applied :event-seq seq :revision rev :state next})))))))

(defn decision-events
  "Bounded read of the lifecycle by durable sequence: rows with
  event_seq > `after`, oldest first, at most `limit` (default 500)."
  ([conn] (decision-events conn {}))
  ([conn {:keys [after limit decision-id] :or {after 0 limit 500}}]
   (mapv #(update % :payload read-json)
         (if decision-id
           (db/fetch conn ["SELECT * FROM decision_events WHERE event_seq > ? AND decision_id = ?
                            ORDER BY event_seq LIMIT ?" after decision-id limit])
           (db/fetch conn ["SELECT * FROM decision_events WHERE event_seq > ? ORDER BY event_seq LIMIT ?"
                           after limit])))))

(defn decisions-for-case [conn case-id]
  (mapv #(update % :action_params read-json)
        (db/fetch conn ["SELECT * FROM pilot_decisions WHERE case_id = ? ORDER BY created_at, decision_id" case-id])))
