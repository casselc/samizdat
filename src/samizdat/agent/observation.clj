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

(ns samizdat.agent.observation
  "Re-observation of an unchanged coordinate, derived from durable receipts.

  JS1 M4 attempt 1 read src/samizdat/util.clj 29 times and
  test/samizdat/util_test.clj 23 times inside one run, mostly getting back
  bytes it had already been given. The storm guard did fire — it watches the
  `eval` call signature — but an eval whose CODE differs by a comment is a
  different signature while the OBSERVATION it makes is identical, so the
  guard saw novelty where the evidence says there was none.

  This namespace reads the thing that actually knows: the semantic receipts.
  Same operation, same canonical arguments, same result, and no intervening
  mutation of that resource is a re-observation, and it is a FACT rather than
  a guess about the model's intent. It is pure and takes receipts as data, so
  it computes identically from a live run and from a finished journal.

  It is feedback only. Nothing here blocks a read: a fresh read after a
  mutation, after a restart with no prior result in the model's context, or
  with materially different arguments is legitimate and is exactly what the
  intervening-mutation reset is for.

  JS2 §3A made the invalidation CONSERVATIVE. The M4 coordinate rule was too
  optimistic in two ways at once. `project/search` leads with a SEARCH
  PATTERN rather than a resource path, so a pattern was being read as a
  filesystem coordinate — reported as one in the finding, and compared
  against mutation paths as one. And a per-path reset cannot see that a write
  BENEATH a listed or searched directory invalidates the earlier list or
  search even though the exact paths differ. Both are now settled the same
  way: any successful project mutation clears the WHOLE accumulated repeat
  state. Since this signal is feedback, over-clearing can only ever suppress
  a warning; it can never manufacture one — which is the direction a
  feedback signal is allowed to be wrong in. Filesystem dependency tracking
  is deliberately not built here."
  (:require [clojure.string :as str]))

(def observation-ops
  "The operations whose repetition is worth counting. A mutation is never a
  re-observation, and `done` is the ship gate's business."
  #{:project/read :project/list :project/search :project/stat})

(def mutation-ops
  #{:project/edit})

(def ^:private path-leading-ops
  "The operations whose FIRST argument is a project path. Membership is the
  whole rule: `project/search` is absent because it leads with a pattern, and
  a pattern is not a coordinate — reading one as a path is how a regex ends
  up printed in a finding as though it named a file."
  #{:project/read :project/list :project/stat :project/edit})

(defn- coordinate
  "The resource a receipt is about, when it names one: the first string
  argument of a path-leading operation. nil for a search, whose subject is a
  pattern rather than a resource — a search is still counted as a repeat by
  its full signature, it simply has no filesystem coordinate to report."
  [op args]
  (let [a (first args)]
    (when (and (contains? path-leading-ops op) (string? a)) a)))

(defn- signature
  "The exact identity of one observation: operation plus canonical arguments."
  [{:keys [op args]}]
  [op (vec args)])

(defn repeated-unchanged
  "The observation coordinates this receipt stream observed repeatedly, with
  an identical result each time and no intervening mutation of that resource.

  `receipts` is the ordered, flattened committed receipt stream —
  `[{:op :args :result :phase}]` in evaluation order. Returns
  `[{:op :args :path :count :result}]`, most repeated first, for every
  signature seen at least `threshold` times.

  ANY successful project mutation clears the WHOLE accumulated state, and the
  same read afterwards begins a fresh count. A per-path reset would have to
  answer questions a receipt stream cannot: whether the write landed beneath
  a directory an earlier list or search walked, whether a pattern's result
  set moved. Clearing everything answers none of them and needs to answer
  none of them — the cost is a suppressed warning, and this is a signal whose
  only job is to be right when it fires."
  [receipts threshold]
  (let [seen (reduce
              (fn [acc {:keys [op args result phase] :as r}]
                (cond
                  ;; Only settled, successful receipts are evidence. An error
                  ;; receipt did not observe anything, and a refused mutation
                  ;; changed nothing to invalidate.
                  (not= :done phase) acc

                  (mutation-ops op) {}

                  (observation-ops op)
                  (let [sig (signature r)
                        prior (get acc sig)]
                    (if (and prior (= (:result prior) result))
                      (update-in acc [sig :count] inc)
                      (assoc acc sig {:count 1 :result result
                                      :op op :args (vec args)
                                      :path (coordinate op args)})))

                  :else acc))
              {}
              receipts)]
    (->> (vals seen)
         (filter #(>= (:count %) threshold))
         (sort-by (comp - :count))
         vec)))

(defn finding
  "The repeated-unchanged-observation finding for a receipt stream, or nil.

  Shaped exactly like samizdat.session's findings so it joins the SAME watch
  pipeline — one finding kind, raised once per run, through the interventions
  queue a human uses. No new policy engine and no new supervisor."
  [receipts {:keys [threshold detail]}]
  (when (and threshold (pos? threshold))
    (when-let [repeats (seq (repeated-unchanged receipts threshold))]
      (let [total (reduce + 0 (map :count repeats))
            worst (first repeats)]
        {:kind :repeated-unchanged-observation
         :severity :medium
         :detail (str/replace (str detail) "{{coordinate}}"
                              (str (name (:op worst))
                                   (when (:path worst)
                                     (str " " (:path worst)))))
         :evidence {:coordinates (mapv (fn [r]
                                         {:op (:op r) :path (:path r)
                                          :count (:count r)})
                                       (take 5 repeats))
                    :repeated-observations total}}))))
