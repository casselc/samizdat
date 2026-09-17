(ns calc.core-test
  (:require [calc.core :as calc]
            [clojure.test :refer [deftest is]]))

(deftest square-three
  (is (= 9 (calc/square 3))))
