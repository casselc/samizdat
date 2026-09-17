;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.migration-lineage-test
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.store.db :as db]
            [samizdat.store.migrations :as migrations]))

;; Freeze the independently shipped histories: do not construct fixtures
;; from the target migration, which would hide an accidental schema change.
;; Fork 789af772196429e2d37fb5a9abe7ce1cf807096b, migrations.clj v30.
(def ^:private historical-fork-v30
  ["ALTER TABLE runs ADD COLUMN max_total_branches INTEGER NOT NULL DEFAULT 8"])

;; Upstream 83eb99a4f6d01923ddee199d453d960a45dd732b, migrations.clj v30.
(def ^:private historical-upstream-v30
  ["ALTER TABLE turns ADD COLUMN prefix_stable_chars INTEGER"
   "ALTER TABLE turns ADD COLUMN prefix_chars INTEGER"
   "ALTER TABLE turns ADD COLUMN prefix_change TEXT"
   "ALTER TABLE turns ADD COLUMN forced_tool TEXT"])

(defn- seed-lineage! [conn lineage]
  (with-redefs [migrations/migrations (subvec migrations/migrations 0 29)]
    (db/migrate! conn))
  (db/execute! conn
               "INSERT INTO runs(id, problem, started_at) VALUES ('saved', 'keep', 'then')")
  (db/execute! conn
               "INSERT INTO turns(run_id, branch_id, turn, result, created_at) VALUES ('saved', 'B1', 1, 'keep-result', 'then')")
  (when (contains? #{:fork :combined} lineage)
    (doseq [sql historical-fork-v30] (db/execute! conn sql))
    (db/execute! conn "UPDATE runs SET max_total_branches = 3 WHERE id = 'saved'"))
  (when (contains? #{:upstream :combined} lineage)
    (doseq [sql historical-upstream-v30] (db/execute! conn sql))
    (db/execute! conn
                 "UPDATE turns SET prefix_stable_chars = 11, prefix_chars = 20, prefix_change = 'tail', forced_tool = 'read_file' WHERE run_id = 'saved'")
    ;; A historical row without a measurement must remain unavailable.
    (db/execute! conn
                 "INSERT INTO turns(run_id, branch_id, turn, result, created_at) VALUES ('saved', 'B1', 2, 'unmeasured', 'then')"))
  (when-not (= :pre-v30 lineage)
    (db/execute! conn "PRAGMA user_version = 30")))

(defn- assert-reconciled! [conn lineage]
  (is (= 31 (db/schema-version conn)))
  (is (= {:problem "keep"
          :max_total_branches (if (contains? #{:fork :combined} lineage) 3 8)}
         (db/fetch-one conn "SELECT problem, max_total_branches FROM runs WHERE id = 'saved'")))
  (let [rows (db/fetch conn
                       "SELECT result, prefix_stable_chars, prefix_chars, prefix_change, forced_tool FROM turns WHERE run_id = 'saved' ORDER BY turn")
        measured? (contains? #{:upstream :combined} lineage)]
    (is (= (if measured? 2 1) (count rows)))
    (is (= {:result "keep-result"
            :prefix_stable_chars (when measured? 11)
            :prefix_chars (when measured? 20)
            :prefix_change (when measured? "tail")
            :forced_tool (when measured? "read_file")}
           (first rows)))
    (when measured?
      (is (= {:result "unmeasured" :prefix_stable_chars nil
              :prefix_chars nil :prefix_change nil :forced_tool nil}
             (second rows))))))

(deftest both-v30-lineages-and-pre-v30-stores-converge-without-data-loss
  (doseq [lineage [:pre-v30 :fork :upstream :combined]]
    (testing (name lineage)
      (let [file (java.io.File/createTempFile "samizdat-lineage-" ".sqlite3")
            path (.getAbsolutePath file)]
        (try
          (let [conn (db/connect path)]
            (try
              (seed-lineage! conn lineage)
              (is (= 31 (db/migrate! conn)))
              (assert-reconciled! conn lineage)
              (finally (db/close conn))))
          ;; Actual repeated startup, not only a second call on one handle.
          (dotimes [_ 2]
            (let [conn (db/open! path)]
              (try
                (assert-reconciled! conn lineage)
                (finally (db/close conn)))))
          (finally
            (doseq [suffix ["" "-wal" "-shm"]]
              (.delete (java.io.File. (str path suffix))))))))))

(deftest reconciliation-still-rolls-back-real-sql-errors
  (let [conn (db/connect ":memory:")]
    (try
      (seed-lineage! conn :upstream)
      (with-redefs [migrations/migrations
                    (assoc migrations/migrations 30
                           (conj migrations/v31 "ALTER TABLE missing_table ADD COLUMN fail INTEGER"))]
        (is (thrown? Throwable (db/migrate! conn))))
      (is (= 30 (db/schema-version conn)))
      (is (not (contains? (set (map :name (db/fetch conn "PRAGMA table_info(runs)")))
                          "max_total_branches")))
      (is (= 31 (db/migrate! conn)))
      (assert-reconciled! conn :upstream)
      (finally (db/close conn)))))

(deftest matching-names-with-incompatible-column-shapes-fail-closed
  (doseq [[table column type nullable? default]
          [["runs" "max_total_branches" "INTEGER" false "8"]
           ["turns" "prefix_stable_chars" "INTEGER" true nil]
           ["turns" "prefix_chars" "INTEGER" true nil]
           ["turns" "prefix_change" "TEXT" true nil]
           ["turns" "forced_tool" "TEXT" true nil]]
          dimension [:type :nullability :default]]
    (testing (str table "." column " " (name dimension))
      (let [conn (db/connect ":memory:")
            wrong-type (if (= "INTEGER" type) "TEXT" "INTEGER")
            declaration (str (if (= dimension :type) wrong-type type)
                             (when-not (if (= dimension :nullability)
                                         (not nullable?) nullable?)
                               " NOT NULL")
                             (when-let [d (if (= dimension :default) "7" default)]
                               (str " DEFAULT " d)))]
        (try
          (with-redefs [migrations/migrations (subvec migrations/migrations 0 29)]
            (db/migrate! conn))
          (db/execute! conn (str "ALTER TABLE " table " ADD COLUMN " column " " declaration))
          (db/execute! conn "PRAGMA user_version = 30")
          (let [before-runs (db/fetch conn "PRAGMA table_info(runs)")
                before-turns (db/fetch conn "PRAGMA table_info(turns)")]
            (let [failure (try (db/migrate! conn) nil (catch Throwable e e))
                  cause (when failure (.getCause failure))]
              (is (some? failure))
              (is (= ::db/incompatible-reconciliation-column (:type (ex-data cause))))
              (is (= [table column] ((juxt :table :column) (ex-data cause)))))
            (is (= 30 (db/schema-version conn)))
            (is (= before-runs (db/fetch conn "PRAGMA table_info(runs)")))
            (is (= before-turns (db/fetch conn "PRAGMA table_info(turns)"))))
          (finally (db/close conn)))))))
