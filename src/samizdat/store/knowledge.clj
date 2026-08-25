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
                (catch Exception e
                  ;; Only a UNIQUE collision is an id problem; anything else
                  ;; (disk, lock) must surface as itself (provenance R2-15).
                  (if (db/id-collision? e) 0 (throw e))))]
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

(defn get-by-id
  "The single knowledge row for id, or nil when there is no such memory."
  [conn id]
  (first (db/fetch conn ["SELECT * FROM knowledge WHERE id = ? LIMIT 1" id])))

(def ^:private preamble
  "Memories you kept earlier (still active; fetch the full text with the recall tool by id):")

(defn- preview
  "Content flattened to one line and cut at n chars, ellipsized only
  when something was actually cut."
  [content n]
  (let [flat (str/trim (str/replace (str content) #"\s+" " "))]
    (if (> (count flat) n)
      (str (subs flat 0 n) "...")
      flat)))

(defn- index-line
  "id, kind in brackets, preview — everything needed to decide whether
  to dereference this id, and nothing more."
  [{:keys [id kind content]}]
  (str id " [" (or kind "note") "] " (preview content 70)))

(defn- fit-lines
  "Keep as many whole lines as fit beside the preamble under cap. A line
  that would overflow the budget is dropped entire, never cut mid-line,
  so the index is bounded by construction."
  [preamble cap lines]
  (loop [kept [] [l & more] lines]
    (let [candidate (str/join "\n" (cond-> (cons preamble kept) l (conj l)))]
      (cond
        (nil? l) kept
        (<= (count candidate) cap) (recur (conj kept l) more)
        :else kept))))

(defn breadcrumb-index
  "A bounded index of kept memories as a string, or nil when there are
  none. One line per memory — id, kind, ~70-char preview — so the index
  is cheap enough to sit in every turn's context while full text is
  fetched on demand by id (stub-and-expand). A non-blank query ranks the
  rows via recall; a blank query falls back to the most recent. The
  whole string is hard-capped so it never becomes a content dump."
  ([conn query] (breadcrumb-index conn query {}))
  ([conn query {:keys [rows cap] :or {rows 8 cap 700}}]
   (let [picked (if (str/blank? query)
                  (recent conn rows)
                  (recall conn query rows))]
     (when (seq picked)
       (str/join "\n" (cons preamble (fit-lines preamble cap (map index-line picked))))))))
