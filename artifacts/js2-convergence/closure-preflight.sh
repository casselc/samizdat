#!/bin/sh
# The M4 lesson, run before the JS2 canary rather than after it.
#
# M4 attempt 2 found the closure verifier RED on an UNTOUCHED target — 21
# failures from tests that assumed the host they were written on. Without that
# pre-flight the canary would have recorded a false FAIL for reasons belonging
# to the harness. This runs the SAME controller-owned closure verifier against
# the untouched target and prints its verdict, its coverage signature, and the
# baseline EDN the run's delta will be computed against.
set -eu
CTRL=/home/chuck/opencode/src/samizdat-controller-conv
TARGET=/home/chuck/opencode/src/samizdat-target-conv
JOLT=/home/chuck/opencode/src/jolt-js2
EV=/home/chuck/opencode/src/js2-converge-evidence

JOLT_CHEZ=/usr/local/bin/scheme
PATH="$JOLT/bin:$PATH"
SAMIZDAT_VERIFY_ENV=bwrap
export JOLT_CHEZ PATH SAMIZDAT_VERIFY_ENV

cd "$CTRL"
exec "$JOLT/bin/jolt" -A:test -e '
(require (quote [samizdat.security.verification-provider :as vprov])
         (quote [samizdat.security.closure-coverage :as coverage])
         (quote [clojure.string :as str]))
(let [root "'"$TARGET"'"
      changed ["test/samizdat/util_test.clj"]
      _ (println "provider" (vprov/selected) "available?" (vprov/available?)
                 "reason" (vprov/unavailable-reason))
      t0 (System/currentTimeMillis)
      focused (vprov/run root changed nil)
      t1 (System/currentTimeMillis)
      closure (vprov/run-closure root changed 900000)
      t2 (System/currentTimeMillis)
      sig (coverage/signature closure
                              {:suite (vprov/coordinate)
                               :verifier (get-in closure [:attribution :environment/coordinate])
                               :input (:input-coordinate closure)})]
  (println "FOCUSED green?" (:green? focused) "exit" (:exit focused)
           "ms" (- t1 t0))
  (println "CLOSURE green?" (:green? closure) "exit" (:exit closure)
           "ms" (- t2 t1))
  (println "COVERAGE" (pr-str sig))
  (println "ADMISSIBLE?" (coverage/admissible? sig) "refusal" (coverage/refusal sig))
  (spit "'"$EV"'/closure-baseline.edn" (pr-str sig))
  (spit "'"$EV"'/closure-preflight-full.txt"
        (str "=== FOCUSED ===\n" (:output focused)
             "\n\n=== CLOSURE ===\n" (:output closure)))
  (println "baseline written to '"$EV"'/closure-baseline.edn"))'
