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
  "The observability aspect pack names exactly the five lifecycle seams the
  source-mode hook wraps, each resolving to one real var of the stated arity."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [samizdat.store.lifecycle]))

(def resource-name "META-INF/jolt/aspects/samizdat-observability-38dc6d7.edn")

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
