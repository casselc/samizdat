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

(ns samizdat.telemetry.test-runner
  "jolt -M:telemetry:telemetry-test — the otel-dependent telemetry tests plus
  the dependency-free contract/hook tests they build on."
  (:require [clojure.test :as t]
            [samizdat.demo.embedded-model-test]
            [samizdat.telemetry.aspect-manifest-test]
            [samizdat.telemetry.contract-test]
            [samizdat.telemetry.embedded-bootstrap-test]
            [samizdat.telemetry.embedded-dependency-test]
            [samizdat.telemetry.embedded-http-test]
            [samizdat.telemetry.embedded-test]
            [samizdat.telemetry.embedded-serve-test]
            [samizdat.telemetry.hook-test]
            [samizdat.telemetry.otel-test]
            [samizdat.telemetry.pipelines-test]))

(def namespaces
  '[samizdat.demo.embedded-model-test
    samizdat.telemetry.aspect-manifest-test
    samizdat.telemetry.contract-test
    samizdat.telemetry.embedded-bootstrap-test
    samizdat.telemetry.embedded-dependency-test
    samizdat.telemetry.embedded-http-test
    samizdat.telemetry.embedded-test
    samizdat.telemetry.embedded-serve-test
    samizdat.telemetry.hook-test
    samizdat.telemetry.otel-test
    samizdat.telemetry.pipelines-test])

(defn run [] (apply t/run-tests namespaces))

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (run)]
    (println)
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
