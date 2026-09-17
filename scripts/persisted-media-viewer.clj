;; Run under env -i, the pinned Chez wrapper and a bounded parent.
(require '[samizdat.demo.embedded-model :as demo]
         '[clojure.data.json :as json] '[clojure.java.io :as io])
(let [out (System/getenv "MEDIA_OUTPUT")
      data (System/getenv "MEDIA_DATA")
      port (#'demo/free-port)
      opts {:checkout-root "/home/chuck/ai-src/worktrees/samizdat-52-hybrid-tool-wrapper"
            :output out :project-root (str data "/project")
            :durable-root (str data "/durable") :db-path (str data "/samizdat.sqlite3")
            :http-port port :nrepl-port (#'demo/free-port)
            :base-url "http://127.0.0.1:1/v1" :model "viewer-no-inference"
            :libchdb "/home/chuck/.cache/jolt-chdb/26.7.3/linux-amd64/libchdb.so"
            :wrapper "/home/chuck/ai-src/tools/jolt-with-chez-10.4.1"
            :jolt "/home/chuck/ai-src/worktrees/jolt-aea91781-release-137/target/release/jolt"}
      deadline (+ (System/currentTimeMillis) 280000)
      server (#'demo/start-server! opts "persisted-media")
      captured (atom false) closed (atom false)
      trace-snapshot
      (fn []
        (let [base (str "http://127.0.0.1:" port)
              index (#'demo/raw-get base "/oscope/telemetry?window=24h" 10000)
              run-id "67d79ff7-11d9-459a-8cb9-af49b733f506"
              proof (demo/assert-trace! run-id (:body index)
                       #(#'demo/raw-get base (str "/oscope/telemetry/traces/" %) 10000))
              detail (#'demo/raw-get base (str "/oscope/telemetry/traces/" (:trace-id proof)) 10000)
              html (:body detail)
              ids (vec (sort (map second (re-seq #"<dt>Span</dt>\s*<dd><code>([0-9a-f]{16})</code>" html))))]
          (when-not (and (= 200 (:status index)) (= 200 (:status detail))
                        (= "353809eba7677816b289a41c39eb7a33" (:trace-id proof))
                        (= 66 (count ids)) (= 66 (count (set ids))))
            (throw (ex-info "Persisted trace identity changed" {})))
          (assoc proof :span-count (count ids) :span-ids ids)))]
  (try
    (#'demo/await-ready! (str "http://127.0.0.1:" port)
                        (+ (System/currentTimeMillis) 90000))
    (spit (str out "/trace-before.json") (json/write-str (trace-snapshot)))
    (spit (str out "/ready.json") (json/write-str {:base (str "http://127.0.0.1:" port)}))
    (loop []
      (cond
        (.exists (io/file out "capture-done"))
        (let [after (trace-snapshot)]
          (spit (str out "/trace-after.json") (json/write-str after))
          (reset! captured (= after (json/read-str (slurp (str out "/trace-before.json")) :key-fn keyword))))
        (>= (System/currentTimeMillis) deadline) (println :capture-deadline)
        :else (do (Thread/sleep 100) (recur))))
    (catch Throwable _ (println :viewer-failed))
    (finally
      (try
        (let [r (#'demo/stop-server! server 60000)]
          (reset! closed (and (:terminal? r) (:closed? r) (:graceful? r)))
          (spit (str out "/retirement.json") (json/write-str r))
          (#'demo/publish-sanitized-logs! server out)
          (println :retirement (select-keys r [:exit :terminal? :closed? :graceful?])))
        (catch Throwable _ (println :retirement-unconfirmed)))))
  (System/exit (if (and @captured @closed) 0 1)))
