;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.store.knowledge
  "Long-term memory: facts a run keeps beyond its own turns.

  Turns and artifacts are the run's record of what it did; a knowledge row
  is a claim it decided was worth carrying forward. Content is the whole
  searchable payload — recall is a LIKE scan over it, so no extra index.
  Rows are plain data keyed by short ids, same shape as tasks, so anything
  that can hold an id can point at a memory."
  (:require [clojure.string :as str]
            [samizdat.store.db :as db]))

(defn- new-id
  "Six hex chars, same scheme as tasks — readable in a transcript, cheap
  to say out loud, retried on the rare collision."
  []
  (str "k-" (subs (str/replace (str (random-uuid)) "-" "") 0 6)))

(defn remember!
  "Insert a fact and return its id. Kind defaults to 'note' — the column
  exists so later kinds (decisions, gotchas, references) need no migration."
  [conn {:keys [content kind]}]
  (when (str/blank? (str content))
    (throw (ex-info "a memory needs content" {})))
  (let [kind (or kind "note")
        now (db/now)]
    (loop [attempt 1]
      (let [id (new-id)
            n (try
                (db/with-writer
                  (db/execute! conn
                               ["INSERT INTO knowledge (id, content, kind, created_at)
                                 VALUES (?, ?, ?, ?)"
                                id (str content) kind now]))
                1
                (catch Exception _
                  0))]
        (if (pos? n)
          id
          (if (< attempt 5)
            (recur (inc attempt))
            (throw (ex-info "could not allocate a knowledge id" {}))))))))

(defn recall
  "Rows whose content contains the query, newest first. LIKE does the
  matching; the limit keeps a broad query from flooding the context."
  ([conn query] (recall conn query 20))
  ([conn query limit]
   (db/fetch conn
             ["SELECT * FROM knowledge WHERE content LIKE ?
               ORDER BY created_at DESC, id DESC LIMIT ?"
              (str "%" query "%") (long limit)])))

(defn recent
  "The latest n memories regardless of content — what the run has been
  keeping lately, for orienting without a search term."
  [conn n]
  (db/fetch conn
            ["SELECT * FROM knowledge ORDER BY created_at DESC, id DESC LIMIT ?"
             (long n)]))

(defn forget!
  "Delete a memory by id. Memories are cheap to re-record if a fact turns
  out wrong, so deletion is total and returns the row count removed."
  [conn id]
  (db/with-writer
    (db/execute! conn ["DELETE FROM knowledge WHERE id = ?" id])))
