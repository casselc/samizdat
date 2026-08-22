;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License v 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.messages-test
  "Mailbox store: routing between branches. A directed message reaches its
  addressee and not the sender; a broadcast reaches everyone but the sender;
  mark-read! is what consumes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [samizdat.store.db :as db]
            [samizdat.store.messages :as messages]))

(def conn (atom nil))

(def ^:private run "run-xyz")

(use-fixtures :each (fn [f] (reset! conn (db/open! ":memory:")) (f)))

(deftest send-returns-a-short-id
  (let [id (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "hello"})]
    (is (string? id))
    (is (str/starts-with? id "msg-"))
    (is (= 10 (count id)))))

(deftest directed-message-reaches-addressee-only
  (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "im taking the parser"})
  (is (= ["im taking the parser"] (mapv :body (messages/inbox @conn run "b2"))))
  (is (empty? (messages/inbox @conn run "b1"))))

(deftest broadcast-reaches-others-not-sender
  (messages/send! @conn {:run-id run :from "b1" :to nil :body "schema changed"})
  (is (= ["schema changed"] (mapv :body (messages/inbox @conn run "b2"))))
  (is (empty? (messages/inbox @conn run "b1"))))

(deftest inbox-is-scoped-to-run-and-excludes-read
  (messages/send! @conn {:run-id "other-run" :from "b9" :to "b2" :body "wrong run"})
  (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "right run"})
  (is (= ["right run"] (mapv :body (messages/inbox @conn run "b2"))))
  (let [id (messages/send! @conn {:run-id run :from "b3" :to "b2" :body "consumed"})]
    (messages/mark-read! @conn [id])
    (is (= ["right run"] (mapv :body (messages/inbox @conn run "b2"))))))

(deftest mark-read-consumes-the-inbox
  (let [id (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "read me"})]
    (is (= 1 (messages/mark-read! @conn [id])))
    (is (empty? (messages/inbox @conn run "b2")))
    (is (= 0 (messages/mark-read! @conn [id])))))

(deftest thread-shows-recent-regardless-of-read
  (let [a (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "one"})
        _ (messages/send! @conn {:run-id run :from "b2" :to "b1" :body "two"})]
    (messages/mark-read! @conn [a])
    (is (= ["two" "one"] (mapv :body (messages/thread @conn run))))
    (is (= ["two"] (mapv :body (messages/thread @conn run 1)))))
  (is (empty? (messages/thread @conn "no-such-run"))))