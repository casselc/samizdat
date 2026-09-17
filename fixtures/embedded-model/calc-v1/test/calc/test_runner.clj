(ns calc.test-runner
  (:require [calc.core-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'calc.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
