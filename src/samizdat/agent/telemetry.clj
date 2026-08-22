;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.telemetry
  "A compact health digest of a run, for the supervisor to introspect on. What
  each part of the loop did and how it landed — worker outcomes, per-branch
  turn/thrash counts, the loop's mechanics (parse-error / no-call) rate, the
  review and critic decisions, the revision history — rendered as text the
  supervisor reads to diagnose what is suboptimal and decide what to tune.

  Pure over already-extracted facts + journal rows, so it is testable without a
  run. The supervisor is a general reasoning agent; this only gives it eyes."
  (:require [clojure.string :as str]))

(defn- s [x] (when x (str/lower-case (str (if (keyword? x) (name x) x)))))

(defn branch-health
  "Per-branch health from journal turn rows: turns taken, how many were
  mechanics (a no-call or parse-repair — the loop spinning without acting), and
  whether the branch ever shipped a `done`. The mechanics rate is the clearest
  thrash signal — a branch burning turns on empty/mis-parsed calls."
  [rows]
  (->> (group-by :branch_id rows)
       (map (fn [[b rs]]
              (let [n (count rs)
                    mech (count (filter #(= "mechanics" (s (:category %))) rs))]
                [b {:turns n
                    :mechanics mech
                    :mechanics-rate (if (pos? n) (/ (double mech) n) 0.0)
                    :shipped? (boolean (some #(= "done" (s (:tool_name %))) rs))}])))
       (into (sorted-map))))

(defn signals
  "The suboptimality flags the digest calls out explicitly, so the supervisor
  does not have to re-derive the obvious: a stage crashed, nothing shipped, a
  thrashing branch, the reviewer bouncing the work, a run deep into revisions."
  [{:keys [results review revision errors]} health]
  (let [total (count results)
        shipped (count (filter #(= :done (:status %)) results))]
    (cond-> []
      (seq errors)
      (into (map #(str "STAGE CRASHED — " %) errors))

      (and (pos? total) (zero? shipped))
      (conj "NO IMPLEMENTOR SHIPPED — the implement round produced nothing verified")

      (some (fn [[_ h]] (and (>= (:turns h) 4) (>= (:mechanics-rate h) 0.33))) health)
      (conj "THRASH — a branch spent a third+ of its turns on empty/mis-parsed calls")

      (= :revise review)
      (conj "REVIEWER BOUNCED — the reviewer sent the work back")

      (>= (or revision 0) 1)
      (conj (str "REVISING — this feature is on revision " revision)))))

(defn digest
  "The run-health block the supervisor reads. `facts` = {:results :review
  :critic :revision}; `rows` = the run's journal turns."
  [{:keys [results review critic revision errors] :as facts} rows]
  (let [health (branch-health rows)
        total (count results)
        shipped (count (filter #(= :done (:status %)) results))
        sigs (signals facts health)]
    (str "## Run health (revision " (or revision 0) ")\n\n"
         "Implementors: " shipped "/" total " shipped. Outcomes: "
         (pr-str (frequencies (map :status results))) "\n"
         "Reviewer: " (or (s review) "n/a") "   Critic: " (or (s critic) "n/a") "\n\n"
         "Per branch (turns / mechanics-thrash / shipped?):\n"
         (str/join "\n"
                   (for [[b h] health]
                     (str "- " b ": " (:turns h) " turns, "
                          (:mechanics h) " thrash, shipped=" (:shipped? h))))
         "\n\nSignals:\n"
         (if (seq sigs)
           (str/join "\n" (map #(str "- " %) sigs))
           "- none flagged; the loop looks healthy"))))
