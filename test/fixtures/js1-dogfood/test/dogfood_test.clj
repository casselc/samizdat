(ns fixture.dogfood-test
  (:require [clojure.test :refer [deftest is]]
            [fixture.dogfood]))

;; JS1-DOGFOOD focused contract: the fixture is green only at :green.
(deftest dogfood-state-is-green
  (is (= :green fixture.dogfood/dogfood-state)))
