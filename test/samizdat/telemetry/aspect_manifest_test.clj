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

(ns samizdat.telemetry.aspect-manifest-test
  "The observability aspect pack names exactly the nine harness run seams the
  source-mode hook wraps, each resolving to one real var of the stated arity
  on this tree (upstream main 22be90d plus instrumentation; the lifecycle pack
  of the pilot lineage does not apply here — samizdat.store.lifecycle is not
  upstream)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.arbiter]
            [samizdat.agent.beam]
            [samizdat.agent.infer]
            [samizdat.agent.tools]
            [samizdat.llm.client]
            [samizdat.store.runs]))

(def run-resource-name "META-INF/jolt/aspects/samizdat-observability-run-22be90d.edn")

(defn- arities [v]
  (set (map count (:arglists (meta v)))))

(def run-seams
  "entry -> arity, as verified against upstream main 22be90d and this tree."
  {'samizdat.agent.beam/run! 1
   'samizdat.agent.beam/run-rounds 3
   'samizdat.agent.beam/advance-branch 3
   'samizdat.store.runs/open-branch! 3
   'samizdat.store.runs/close-branch! 5
   'samizdat.llm.client/chat 4
   'samizdat.agent.infer/absorb 3
   'samizdat.agent.tools/run-tool 1
   'samizdat.agent.arbiter/decide 1})

(deftest run-pack-is-well-formed-and-resolves
  (let [manifest (some-> (io/resource run-resource-name) slurp edn/read-string)]
    (is (some? manifest))
    (is (= 1 (:schema manifest)))
    (is (= 'yogthos/samizdat (get-in manifest [:library :id])))
    (is (= "22be90ddf9b05ba8406d6ec231d2748a4da22d8e" (get-in manifest [:library :version])))
    (is (= 9 (count (:aspects manifest))))
    (is (= 9 (count (set (map :id (:aspects manifest))))) "ids unique")
    (is (= run-seams
           (into {} (map (fn [a] [(get-in a [:match :entry]) (get-in a [:match :arity])])
                         (:aspects manifest))))
        "exactly the nine hooked seams at their hooked arities")
    (doseq [{:keys [id match advice-role expect]} (:aspects manifest)]
      (testing (str id)
        (is (= #{:entry :arity} (set (keys match))))
        (is (= :samizdat.telemetry/run advice-role))
        (is (= {:matches 1} expect))
        (let [v (resolve (:entry match))]
          (is (var? v) "entry resolves to a var")
          (when v
            (is (contains? (arities v) (:arity match))
                (str "arity " (:arity match) " exists among " (arities v)))))))))
