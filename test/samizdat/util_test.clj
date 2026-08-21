;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.util-test
  "Tests for samizdat.util/truncate-middle."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [samizdat.util :as util]))

(deftest truncate-middle-test
  (testing "short string returned unchanged"
    (is (= "hello" (util/truncate-middle "hello" 10))))

  (testing "long string shortened to exactly max-len"
    (let [s "The quick brown fox jumps over the lazy dog"
          result (util/truncate-middle s 20)]
      (is (= 20 (count result)))
      (is (= "The quic …  lazy dog" result))))

  (testing "middle marker present"
    (let [result (util/truncate-middle "abcdefghijklmnopqrstuvwxyz" 10)]
      (is (str/includes? result " … ")))))
