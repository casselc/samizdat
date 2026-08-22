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

(ns samizdat.mutation-test
  "The self-modification protocol: the agent edits a cell on disk, and the
  kernel runs checkpoint -> reload -> validate -> soak -> commit or rollback.
  A good edit commits and changes behavior; a broken one (syntax, wiring, or a
  cell that throws on valid input) rolls back cleanly and is journaled, so a
  bad edit can never brick the loop."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [jolt.fs :as fs]
            [mycelium.cell :as cell]
            [samizdat.cells :as cells]
            [samizdat.mutation :as mut]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(def ^:private root (atom nil))

;; A minimal all-pure loop, so the whole protocol (including soak's dry-run)
;; runs with no IO. two cells: start bumps :n, end passes through.
(defn- write-cells! [dir start-body]
  (fs/create-dirs dir)
  (spit (str dir "/mini.clj")
        (str "(ns cells.mini (:require [mycelium.cell :as cell]))\n"
             "(cell/defcell :mini/start {:doc \"start\" :pure true}\n  " start-body ")\n"
             "(cell/defcell :mini/end {:doc \"end\" :pure true}\n"
             "  (fn [_ d] (assoc d :verdict :done)))\n")))

(def ^:private mini-def
  '{:cells {:start :mini/start :end :mini/end}
    :edges {:start {:go :end} :end :end}
    :dispatches {:start [[:go (fn [d] true)]]}})

(defn- opts []
  {:dirs [(str @root "/cells")]
   :loop-def mini-def
   :soak-input {:n 0}})

(use-fixtures :each
  (fn [f]
    (cell/clear-registry!)
    (reset! root (str "/tmp/samizdat-mut-" (random-uuid)))
    (try (f) (finally (fs/delete-tree @root) (cell/clear-registry!)))))

;; --- a good edit commits ----------------------------------------------------

(deftest a-valid-edit-commits-and-takes-effect
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  (is (= 1 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0}))))
  ;; edit the cell on disk to a new valid behavior, then apply the protocol
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n + 10))")
  (let [r (mut/apply-cell-edit! (opts))]
    (is (= :committed (:status r)))
    (is (= 10 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0})))
        "the committed edit is live in the registry")))

;; --- a syntax error rolls back ----------------------------------------------

(deftest a-syntax-error-rolls-back
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  ;; corrupt the cell file with unbalanced/invalid code
  (spit (str @root "/cells/mini.clj") "(ns cells.mini)\n(this is not valid")
  (let [r (mut/apply-cell-edit! (opts))]
    (is (= :rolled-back (:status r)))
    (is (str/includes? (str/lower-case (:reason r)) "reload"))
    (testing "the prior good cell survives the failed reload"
      (is (= 1 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0})))))))

;; --- a wiring break rolls back (validate) -----------------------------------

(deftest a-cell-that-vanishes-fails-validate-and-rolls-back
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  ;; rewrite the file to define only :mini/start — :mini/end that the manifest
  ;; wires to is gone, so compile-loop cannot resolve it.
  (spit (str @root "/cells/mini.clj")
        (str "(ns cells.mini (:require [mycelium.cell :as cell]))\n"
             "(cell/defcell :mini/start {:doc \"s\" :pure true} (fn [_ d] d))\n"))
  (let [r (mut/apply-cell-edit! (opts))]
    (is (= :rolled-back (:status r)))
    (is (str/includes? (str/lower-case (:reason r)) "validate"))
    (testing "both original cells are restored"
      (is (some? (cell/get-cell :mini/start)))
      (is (some? (cell/get-cell :mini/end))))))

;; --- a cell that throws on valid input rolls back (soak) ---------------------

(deftest a-cell-that-throws-on-soak-rolls-back
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  ;; compiles fine and wires fine, but throws when actually run on {:n 0}
  (write-cells! (str @root "/cells") "(fn [_ d] (throw (ex-info \"boom\" {})))")
  (let [r (mut/apply-cell-edit! (opts))]
    (is (= :rolled-back (:status r)))
    (is (str/includes? (str/lower-case (:reason r)) "soak"))
    (testing "the last good cell is restored — the loop is not bricked"
      (is (= 1 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0})))))))

;; --- the record -------------------------------------------------------------

(deftest a-rollback-is-journaled-as-a-negative-constraint
  (with-open []
    (let [conn (db/open! ":memory:")
          rid (runs/start-run! conn {:problem "p"})]
      (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
      (cells/load-cells! (:dirs (opts)))
      (spit (str @root "/cells/mini.clj") "(ns cells.mini)\n(broken")
      (mut/apply-cell-edit! (assoc (opts) :conn conn :run-id rid))
      (let [events (journal/events-since conn rid 0 100)]
        (is (some #(= "mutation-rolled-back" (:kind %)) events)
            "the failed mutation is on the record for the agent to learn from")))))
