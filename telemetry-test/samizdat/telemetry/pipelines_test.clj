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

(ns samizdat.telemetry.pipelines-test
  "Bounded, independent destinations: a throwing or slow exporter drops or
  fails on its own queue and never blocks the other, shutdown is bounded,
  diagnostics are scalar."
  (:require [clojure.test :refer [deftest is testing]]
            [otel.exporter.memory :as memory]
            [otel.sdk.export :as export]
            [samizdat.telemetry.otel :as tel]))

(defrecord ThrowingExporter []
  export/SpanExporter
  (export-spans! [_ _] (throw (ex-info "collector down: secret=hunter2" {})))
  (flush-exporter! [_] true)
  (shutdown-exporter! [_] true))

(defrecord SlowExporter [delay-ms]
  export/SpanExporter
  (export-spans! [_ _] (Thread/sleep delay-ms) true)
  (flush-exporter! [_] true)
  (shutdown-exporter! [_] true))

(defn- emit! [n]
  (dotimes [i n]
    (tel/with-tool [_ (str "t" i) {"samizdat.tool.name" "x"}])))

(deftest one-dead-destination-does-not-block-the-other
  (tel/shutdown!)
  (let [mem (memory/exporter)]
    (tel/init! {:mode :dual :exporters {:local mem :langfuse (->ThrowingExporter)}
                :batch {:schedule-delay-ms 50}})
    (try
      (emit! 5)
      (let [flush (tel/flush!)
            st (tel/stats)]
        (is (= 5 (count (memory/spans mem))))
        (is (= 5 (get-in st [:local :exported-span-count])))
        (is (= 0 (get-in st [:local :failed-span-count])))
        (is (= 5 (get-in st [:langfuse :failed-span-count])))
        (is (= 0 (get-in st [:langfuse :exported-span-count])))
        (is (true? (get-in flush [:local :ok?])))
        (is (false? (get-in flush [:langfuse :ok?])))
        (is (not (re-find #"hunter2" (pr-str [flush st]))) "exporter errors never reach diagnostics"))
      (finally (tel/shutdown!)))))

(deftest queue-overflow-drops-and-counts
  (tel/shutdown!)
  (let [mem (memory/exporter)]
    (tel/init! {:mode :dual :exporters {:local mem :langfuse (->SlowExporter 400)}
                :batch {:max-queue-size 8 :max-export-batch-size 4 :schedule-delay-ms 100000}})
    (try
      (emit! 40)
      (let [st (tel/stats)]
        (is (= 32 (get-in st [:langfuse :dropped-count])))
        (is (= 32 (get-in st [:local :dropped-count])) "each queue is bounded on its own")
        (is (<= (get-in st [:langfuse :queue-size]) 8)))
      (finally
        (let [t0 (System/nanoTime)
              r (tel/shutdown!)
              ms (/ (- (System/nanoTime) t0) 1e6)]
          (is (map? r))
          (is (contains? r :langfuse))
          (is (< ms 5000) (str "shutdown bounded, took " ms " ms")))))))

(deftest shutdown-is-exactly-once-and-uninstalls
  (tel/shutdown!)
  (tel/init! {:mode :local :exporters {:local (memory/exporter)}})
  (let [r1 (tel/shutdown!)
        r2 (tel/shutdown!)]
    (is (map? r1))
    (is (nil? r2))
    (is (nil? (tel/stats)))
    (is (not (samizdat.telemetry.hook/installed?)))))

(deftest mode-parsing
  (is (= :off (tel/parse-mode nil)))
  (is (= :off (tel/parse-mode "off")))
  (is (= :local (tel/parse-mode " LOCAL ")))
  (is (= :dual (tel/parse-mode "dual")))
  (is (thrown? Throwable (tel/parse-mode "cloud"))))

(deftest langfuse-destination-requires-headers-and-host
  (testing "no header env var -> refuse to build, and the message names the var, not a value"
    (is (thrown-with-msg? Throwable #"SAMIZDAT_TEST_UNSET_HEADERS must contain Authorization"
                          (tel/destinations :langfuse {:host "https://example.invalid"
                                                       :headers-env "SAMIZDAT_TEST_UNSET_HEADERS"}))))
  (is (thrown-with-msg? Throwable #"LANGFUSE_HOST" (tel/langfuse-traces-url nil)))
  (is (= "https://h.example/api/public/otel/v1/traces" (tel/langfuse-traces-url "https://h.example/"))))
