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
  "The observability aspect packs name exactly the seams the source-mode hook
  wraps — five samizdat.store.lifecycle entries, nine harness run seams — each
  resolving to one real var of the stated arity on this tree."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.arbiter]
            [samizdat.agent.beam]
            [samizdat.agent.infer]
            [samizdat.agent.tools]
            [samizdat.llm.client]
            [samizdat.store.lifecycle]
            [samizdat.store.runs]))

(def resource-name "META-INF/jolt/aspects/samizdat-observability-38dc6d7.edn")
(def run-resource-name "META-INF/jolt/aspects/samizdat-observability-run-dad1c65.edn")

(defn- arities [v]
  (set (map count (:arglists (meta v)))))

(deftest pack-is-well-formed-and-resolves
  (let [manifest (some-> (io/resource resource-name) slurp edn/read-string)]
    (is (some? manifest))
    (is (= 1 (:schema manifest)))
    (is (= 'yogthos/samizdat (get-in manifest [:library :id])))
    (is (string? (get-in manifest [:library :version])))
    (is (= 5 (count (:aspects manifest))))
    (is (= 5 (count (set (map :id (:aspects manifest))))) "ids unique")
    (is (= #{'samizdat.store.lifecycle/upsert-case!
             'samizdat.store.lifecycle/acquire-lease!
             'samizdat.store.lifecycle/release-lease!
             'samizdat.store.lifecycle/decision
             'samizdat.store.lifecycle/transition!}
           (set (map #(get-in % [:match :entry]) (:aspects manifest)))))
    (doseq [{:keys [id match advice-role expect]} (:aspects manifest)]
      (testing (str id)
        (is (= #{:entry :arity} (set (keys match))))
        (is (= :samizdat.telemetry/lifecycle advice-role))
        (is (= {:matches 1} expect))
        (let [v (resolve (:entry match))]
          (is (var? v) "entry resolves to a var")
          (when v
            (is (contains? (arities v) (:arity match))
                (str "arity " (:arity match) " exists among " (arities v)))))))))

(def run-seams
  "entry -> arity, as verified against upstream main dad1c65 and this tree."
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
    (is (= "dad1c65dac7d08d882c9965a05b2c8b1f39e9d87" (get-in manifest [:library :version])))
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
