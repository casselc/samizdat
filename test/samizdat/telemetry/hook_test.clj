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

(ns samizdat.telemetry.hook-test
  "The seam is inert without an observer and cannot change the thunk's
  outcome with one, however the observer misbehaves."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.telemetry.hook :as hook]))

(use-fixtures :each (fn [f] (hook/uninstall!) (try (f) (finally (hook/uninstall!)))))

(deftest no-observer-is-identity
  (is (not (hook/installed?)))
  (is (= 42 (hook/observe! :x {} (fn [] 42))))
  (is (thrown? Throwable (hook/observe! :x {} (fn [] (throw (ex-info "boom" {})))))))

(deftest observer-sees-kind-attrs-and-value
  (let [seen (atom [])]
    (hook/install! (fn [kind attrs thunk]
                     (let [v (thunk)]
                       (swap! seen conj [kind attrs v])
                       v)))
    (is (= :v (hook/observe! :op {:a 1} (fn [] :v))))
    (is (= [[:op {:a 1} :v]] @seen))))

(deftest thunk-runs-exactly-once-whatever-the-observer-does
  (testing "observer throws before calling"
    (let [n (atom 0)]
      (hook/install! (fn [_ _ _] (throw (ex-info "observer broke" {}))))
      (is (= :v (hook/observe! :op {} (fn [] (swap! n inc) :v))))
      (is (= 1 @n))))
  (testing "observer throws after calling"
    (let [n (atom 0)]
      (hook/install! (fn [_ _ thunk] (thunk) (throw (ex-info "late" {}))))
      (is (= :v (hook/observe! :op {} (fn [] (swap! n inc) :v))))
      (is (= 1 @n))))
  (testing "observer forgets to call"
    (let [n (atom 0)]
      (hook/install! (fn [_ _ _] nil))
      (is (= :v (hook/observe! :op {} (fn [] (swap! n inc) :v))))
      (is (= 1 @n))))
  (testing "observer calls twice: the thunk still runs once"
    (let [n (atom 0)]
      (hook/install! (fn [_ _ thunk] (thunk) (thunk)))
      (is (= :v (hook/observe! :op {} (fn [] (swap! n inc) :v))))
      (is (= 1 @n))))
  (testing "thunk exception propagates and observer failure does not mask it"
    (hook/install! (fn [_ _ thunk] (thunk) (throw (ex-info "late" {}))))
    (is (thrown-with-msg? Throwable #"real"
                          (hook/observe! :op {} (fn [] (throw (ex-info "real" {}))))))))
