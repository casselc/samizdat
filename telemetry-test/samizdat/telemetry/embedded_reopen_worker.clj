;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-reopen-worker
  "Fresh-process half of the native Durable recovery qualification."
  (:require [clojure.string :as str]
            [samizdat.telemetry.embedded :as embedded]
            [samizdat.telemetry.embedded-http :as embedded-http]))

(def ^:private protocol-prefix "SAMIZDAT_EMBEDDED_REOPEN_V1 ")

(defn -main [durable-root scratch-parent]
  (let [lifecycle* (atom nil)
        viewer* (atom nil)
        stopped? (atom false)]
    (try
      (let [lifecycle
            (embedded/start!
             {:durable-root durable-root
              :durable {:scratch-parent scratch-parent :lease-ttl-ms 30000}
              :sdk-options {:service-name "samizdat-embedded-test"
                            :processor :simple
                            :metrics? false
                            :runtime-metrics? false
                            :logs? false}})
            _ (reset! lifecycle* lifecycle)
            rows (get-in
                  ((:load-command (:source lifecycle))
                   :embedded-test-reopened
                   {:signal :spans :field :span-name
                    :window :15m :limit 20})
                  [:table :rows])
            recovered? (boolean
                        (some #(= "lifecycle.embedded-test" (:value %)) rows))]
        (when-not recovered?
          (throw (ex-info "fresh process did not recover the expected span"
                          {:type ::missing-span})))
        (let [viewer (embedded-http/start! lifecycle "127.0.0.1:8080")
              _ (reset! viewer* viewer)
              response (embedded-http/handle
                        viewer
                        {:request-method :get
                         :uri "/oscope/telemetry"
                         :headers {"Host" "127.0.0.1:8080"}})
              ui-recovered? (and (= 200 (:status response))
                                 (str/includes? (:body response)
                                                "lifecycle.embedded-test"))]
          (when-not ui-recovered?
            (throw (ex-info "fresh process UI did not render the expected span"
                            {:type ::missing-ui-span
                             :status (:status response)})))
          (let [viewer-stop (embedded-http/stop! viewer)]
            (when-not (= {:status :closed :phase :closed} viewer-stop)
              (throw (ex-info "fresh process did not drain the viewer"
                              {:type ::viewer-not-closed
                               :status (:status viewer-stop)
                               :phase (:phase viewer-stop)})))
            (reset! viewer* nil)))
        (let [stop-result (embedded/stop! lifecycle)]
          (reset! stopped? (= {:status :closed :phase :closed} stop-result))
          (when-not @stopped?
            (throw (ex-info "fresh process did not close the embedded owner"
                            {:type ::not-closed
                             :status (:status stop-result)
                             :phase (:phase stop-result)})))
          (println (str protocol-prefix
                        (pr-str {:status :ok
                                 :recovered-span "lifecycle.embedded-test"
                                 :ui-readback true
                                 :stop stop-result})))))
      (finally
        (when @viewer*
          (try (embedded-http/stop! @viewer*) (catch Throwable _ nil)))
        (when (and @lifecycle* (not @stopped?))
          (try (embedded/stop! @lifecycle*) (catch Throwable _ nil)))))))
