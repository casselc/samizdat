;; JS2 convergence §22: the concurrent-ownership probe, on the FROZEN
;; converged controller. Run A times out while run B is legitimately
;; executing; A's cleanup must touch A's machine and nothing else.
(require '[samizdat.security.smolvm-project-env :as spe]
         '[samizdat.engine.proc :as proc]
         '[clojure.string :as str])
(def root (or (jolt.host/getenv "JS2_TARGET")
              "/home/chuck/opencode/src/samizdat-target-conv"))
(defn table [] (str/trim (str (:out (proc/run {:timeout-ms 15000} "smolvm" "machine" "ls")))))
(println "manager table BEFORE:" (pr-str (table)))
(def b-result (promise))
(def b (future (deliver b-result
                        (spe/run root (spe/validate-request
                                       ["/bin/sh" "-c" "sleep 45; echo B-COMPLETED"]
                                       {:timeout-ms 150000})))))
(Thread/sleep 5000)
(println "B is running; starting A with a 12s deadline")
(def a (spe/run root (spe/validate-request ["/bin/sh" "-c" "sleep 900"] {:timeout-ms 12000})))
(println)
(println "A  invocation:" (:invocation a) " machine:" (:machine a))
(println "A  status:" (:status a) " disposition:" (:disposition a))
(println "A  cleanup owned:" (pr-str (get-in a [:cleanup :owned])))
(println "A  cleanup acted:" (pr-str (get-in a [:cleanup :acted])))
(println "A  cleanup clean?:" (get-in a [:cleanup :clean?]))
(println "A  poisoned after:" (spe/poisoned?))
(def bb (deref b-result 200000 ::never))
(println)
(println "B  invocation:" (:invocation bb) " machine:" (:machine bb))
(println "B  status:" (:status bb) " exit:" (:exit bb) " disposition:" (:disposition bb))
(println "B  stdout:" (pr-str (str/trim (str (get-in bb [:stdout :text])))))
(println)
(println "A machine == B machine?" (= (:machine a) (:machine bb)))
(println "B's machine among A's cleanup targets?"
         (boolean (some #(str/includes? % (str (:machine bb)))
                        (or (get-in a [:cleanup :acted]) []))))
(Thread/sleep 2000)
(println "manager table AFTER:" (pr-str (table)))
(println "A's machine still in the table?" (str/includes? (table) (str (:machine a))))
