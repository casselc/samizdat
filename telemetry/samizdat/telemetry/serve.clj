;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.serve
  "`jolt -M:telemetry -m samizdat.telemetry.serve`: the stock server with the
  telemetry runtime started first, so a live run's seams export as spans.
  The stock entry point (samizdat.core) is untouched; this only wraps it.

  Configuration is the environment the otel namespace already reads:
  SAMIZDAT_TELEMETRY (off|local|langfuse|dual), SAMIZDAT_OTEL_LOCAL_ENDPOINT,
  LANGFUSE_HOST and the Langfuse header variable named by
  SAMIZDAT_LANGFUSE_OTLP_HEADERS_ENV. Nothing here reads the model endpoint
  or any credential; the harness reads HARNESS_* itself."
  (:require [clojure.tools.logging :as log]
            [samizdat.core :as core]
            [samizdat.telemetry.otel :as otel]))

(defn -main [& args]
  (let [rt (otel/init! {:service-name "samizdat"})]
    (log/info "telemetry" (if rt (str (name (:mode rt)) " -> " (pr-str (:destinations rt))) "off"))
    ;; Registered before the server's own hooks, so the last spans of a run
    ;; that was in flight are flushed while the process still can.
    (when rt (jolt.host/add-shutdown-hook otel/shutdown!))
    (apply core/-main args)))
