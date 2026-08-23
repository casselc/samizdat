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

(ns samizdat.evals-test
  "Durable evaluator receipt storage: intent before effect, completion
  afterward, strict-sequence reads, structured EDN round-trips, and the
  fail-closed treatment of whatever was left pending."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.store.db :as db]
            [samizdat.store.evals :as evals]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(def ^:private coordinate
  "An evaluator/context coordinate of the shape jolt.sandbox emits. Opaque
  to the store."
  "js0:[:map [[:jolt.sandbox/profile \":agent/project-read\"]]]")

(def ^:private evaluator-identity
  {:spec-id "project/develop"
   :instance-id "inst:main"
   :binding-id "bind:main:test"})

(defn- begin-eval! [conn opts]
  (evals/begin! conn (merge evaluator-identity opts)))

(deftest migration-creates-the-eval-tables
  (with-db [c]
    (is (every? (set (db/table-names c))
                ["evals" "eval_completions" "eval_receipts"]))))

(deftest begin-records-intent-and-the-record-starts-pending
  (with-db [c]
    (let [id (begin-eval! c {:coordinate coordinate :source "(+ 1 2)"})]
      (is (pos? id))
      (is (= [id] (mapv :id (evals/pending c)))
          "pending from the moment intent is recorded")
      (let [r (evals/load-eval c id)]
        (is (= :pending (:status r)))
        (is (= coordinate (:coordinate r)))
        (is (= "(+ 1 2)" (:source r)))
        (is (= [] (:receipts r)))
        (is (nil? (:result r)))
        (is (nil? (:completed_at r)))))))

(deftest intent-needs-a-coordinate-and-source
  (with-db [c]
    (is (thrown? Exception (begin-eval! c {:coordinate "" :source "(+ 1 2)"})))
    (is (thrown? Exception (begin-eval! c {:coordinate coordinate :source "  "})))
    (is (empty? (evals/pending c)) "a refused intent records nothing")))

(deftest receipts-round-trip-as-structured-data-in-strict-order
  (with-db [c]
    (let [id (begin-eval! c {:coordinate coordinate
                              :source "(project/read \"a\") (project/edit \"b\" 2)"})
          s0 (evals/record-intent! c id {:op :project/read
                                         :args ["a" {:nested [1 2 {:k :v :s 'sym}]}]})
          s1 (evals/record-intent! c id {:op :project/edit :args ["b" 2]})]
      (is (= [0 1] [s0 s1]) "seqs are assigned in intent order")
      (evals/record-outcome! c id s0 {:result {:bytes [1 2 3] :tag :ok}})
      (evals/record-outcome! c id s1 {:error "disk full"})
      (let [rs (:receipts (evals/load-eval c id))]
        (is (= [0 1] (mapv :seq rs)) "receipts load in strict seq order")
        (is (= [:project/read :project/edit] (mapv :op rs)))
        (is (= ["a" {:nested [1 2 {:k :v :s 'sym}]}] (:args (first rs)))
            "args come back as data, not transcript text")
        (is (= {:bytes [1 2 3] :tag :ok} (:result (first rs)))
            "and so does the result")
        (is (= :done (:phase (first rs))))
        (is (= :error (:phase (second rs))))
        (is (= "disk full" (:error (second rs))))))))

(deftest the-stored-form-is-canonical-edn-not-a-transcript
  (with-db [c]
    (let [id (begin-eval! c {:coordinate coordinate :source "x"})
          _ (evals/record-intent! c id {:op :project/stat :args [{:b 1 :a 2}]})
          raw (:args (db/fetch-one c ["SELECT args FROM eval_receipts
                                       WHERE eval_id = ? AND phase = 'intent'" id]))]
      (is (string? raw) "the column holds text...")
      (is (= [{:a 2 :b 1}] (edn/read-string raw)) "...that parses as EDN data")
      (is (< (str/index-of raw ":a") (str/index-of raw ":b"))
          "with map entries in canonical key order, not caller order"))))

(deftest non-canonical-values-are-refused-not-stringified
  ;; A receipt that cannot round-trip as data is not a receipt. Floats,
  ;; live objects and functions are rejected at the boundary rather than
  ;; pr-str'd into something that reads back as a different thing.
  (with-db [c]
    (let [id (begin-eval! c {:coordinate coordinate :source "x"})]
      (is (thrown? Exception (evals/record-intent! c id {:op :project/read :args [(atom 0)]})))
      (is (thrown? Exception (evals/record-intent! c id {:op :project/read :args [1.5]})))
      (is (empty? (evals/unsettled-effects c id))
          "a refused intent records nothing")
      (is (thrown? Exception (evals/complete! c id {:status :completed
                                                    :result {:value (fn [x] x)}})))
      (is (= [id] (mapv :id (evals/pending c))) "and the refused completion did not land"))))

(deftest completion-records-terminal-status-and-structured-result
  (with-db [c]
    (let [id (begin-eval! c {:coordinate coordinate :source "(+ 1 2)"})]
      (is (true? (evals/complete! c id {:status :completed
                                        :result {:value 3 :out "" :receipts-used 0}})))
      (is (empty? (evals/pending c)))
      (let [r (evals/load-eval c id)]
        (is (= :completed (:status r)))
        (is (= {:value 3 :out "" :receipts-used 0} (:result r))
            "terminal result metadata round-trips as data")
        (is (some? (:completed_at r)))))))

(deftest completed-records-list-in-strict-sequence
  ;; Insertion order, not completion order: the sequence is the order the
  ;; evaluations were registered, which is the order their receipts replay.
  (with-db [c]
    (let [a (begin-eval! c {:coordinate coordinate :source "a"})
          b (begin-eval! c {:coordinate coordinate :source "b"})
          d (begin-eval! c {:coordinate coordinate :source "d"})]
      (evals/complete! c b {:status :failed :result {:error "boom"}})
      (evals/complete! c a {:status :completed})
      (let [rows (evals/completed c)]
        (is (= [a b] (mapv :id rows)))
        (is (= [:completed :failed] (mapv :status rows)))
        (is (= {:error "boom"} (:result (second rows)))))
      (is (= [d] (mapv :id (evals/pending c)))))))

(deftest lists-are-bounded
  (with-db [c]
    (dotimes [_ 5]
       (evals/complete! c (begin-eval! c {:coordinate coordinate :source "x"})
                       {:status :completed}))
    (begin-eval! c {:coordinate coordinate :source "p1"})
    (begin-eval! c {:coordinate coordinate :source "p2"})
    (is (= 3 (count (evals/completed c 3))))
    (is (= 5 (count (evals/completed c))) "the default bound covers a small history")
    (is (= 1 (count (evals/pending c 1))))
    (is (= 2 (count (evals/pending c))))))

(deftest an-unsettled-effect-blocks-completion-so-the-caller-fails-closed
  ;; The window that makes durability worth having: the process recorded the
  ;; intent to actuate and died (or is still) between the actuation and its
  ;; outcome. Completing now would claim a whole record when one actuation's
  ;; effect on the world is unknown — so completion refuses, and the record
  ;; stays pending where `pending` reports it.
  (with-db [c]
    (let [id (begin-eval! c {:coordinate coordinate :source "(project/edit \"f\" 1)"})
          n (evals/record-intent! c id {:op :project/edit :args ["f" 1]})]
      (is (thrown? Exception (evals/complete! c id {:status :completed})))
      (is (= [{:seq n :op :project/edit :args ["f" 1]}]
             (evals/unsettled-effects c id))
          "the effect whose actuation state is unknown, named exactly")
      (is (= :intent (:phase (first (:receipts (evals/load-eval c id)))))
          "and the loaded receipt says intent, not a guessed outcome")
      (is (= [id] (mapv :id (evals/pending c)))
          "the record stays pending: the actuation may have happened"))))

(deftest a-settled-record-is-final
  (with-db [c]
    (let [id (begin-eval! c {:coordinate coordinate :source "x"})
          n (evals/record-intent! c id {:op :project/read :args ["a"]})]
      (evals/record-outcome! c id n {:result nil})
      (testing "a nil result is a real outcome, not an unsettled intent"
        (let [r (first (:receipts (evals/load-eval c id)))]
          (is (= :done (:phase r)))
          (is (contains? r :result))
          (is (nil? (:result r))))
        (is (empty? (evals/unsettled-effects c id))))
      (evals/complete! c id {:status :completed})
      (is (thrown? Exception (evals/complete! c id {:status :completed}))
          "no second terminal record")
      (is (thrown? Exception (evals/record-intent! c id {:op :project/read :args []}))
          "no receipts after completion")
      (is (thrown? Exception (evals/record-outcome! c id n {:result 1}))
          "and no late outcomes either"))))

(deftest outcomes-require-an-intent-and-are-not-rewritten
  (with-db [c]
    (let [id (begin-eval! c {:coordinate coordinate :source "x"})]
      (is (thrown? Exception (evals/record-outcome! c id 0 {:result 1}))
          "an outcome with no intent is an effect nobody recorded running")
      (let [n (evals/record-intent! c id {:op :project/read :args ["a"]})]
        (evals/record-outcome! c id n {:result 1})
        (is (thrown? Exception (evals/record-outcome! c id n {:result 2}))
            "a receipt is not rewritten")
        (is (thrown? Exception (evals/record-outcome! c id n {:result 1 :error "x"}))
            "an outcome is a result or an error, not both")))))

(deftest unknown-evaluations-are-refused-on-write-and-nil-on-read
  (with-db [c]
    (is (nil? (evals/load-eval c 999)))
    (is (thrown? Exception (evals/record-intent! c 999 {:op :project/read :args []})))
    (is (thrown? Exception (evals/complete! c 999 {:status :failed})))))
