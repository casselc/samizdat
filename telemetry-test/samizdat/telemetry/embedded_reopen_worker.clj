;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-reopen-worker
  "Fresh-process half of the native Durable recovery qualification."
  (:require [samizdat.telemetry.embedded :as embedded]))

(def ^:private protocol-prefix "SAMIZDAT_EMBEDDED_REOPEN_V1 ")

(defn -main [durable-root scratch-parent]
  (let [lifecycle* (atom nil)
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
                                 :stop stop-result})))))
      (finally
        (when (and @lifecycle* (not @stopped?))
          (try (embedded/stop! @lifecycle*) (catch Throwable _ nil)))))))
