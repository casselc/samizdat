;; samizdat - a claim-first verification harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.telemetry.embedded-dependency-test
  "Qualify the viewer-only dependency surface before any handler is mounted."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.process :as process]
            [oscope.ui.events]
            [oscope.ui.web]
            [oscope.ui.workbench]))

(def ^:private expected-shas
  {'io.github.casselc/otel
   "4d61f8e921d1310bc7ba39d7208cc38ac14a3215"
   'io.github.chucklehead-dev/oscope
   "7ee3ec4f6aaa4d085d88384f85934280d421fa1a"
   'io.github.chucklehead-dev/jolt-chdb
   "95d7b2b31c95e007d5065e3950deb1869e2d0f8a"
   'io.github.chucklehead-dev/jolt-otel-clickhouse
   "14a2998a27f64a9bff329811461be9157a00c849"
   'io.github.casselc/jolt-http
   "35d1d7f9ebdc796ee9bd4c80745298b2c8b7fdf8"
   'io.github.chucklehead-dev/jolt-otel-viewer
   "5723a7c28c3bb3ae7cb27f9856b90463e77df523"})

(def ^:private expected-roots
  {"clojure/data/json.clj" "data.json.git/3174868a7baa06e118fb8d1201edd98c5769b335/"
   "db/sqlite.clj" "casselc_db.git/96324713500c96ae97c0deaf84691f31df158f25/"
   "jdbc/chdb/durable.clj" "jolt-chdb.git/95d7b2b31c95e007d5065e3950deb1869e2d0f8a/"
   "jolt/crypto.clj" "jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/"
   "jolt/http_client.clj" "http-client.git/eab6b78d5957f88690faf6768360572a3f185341/"
   "jolt/http/server.clj" "jolt-http.git/35d1d7f9ebdc796ee9bd4c80745298b2c8b7fdf8/"
   "otel/sdk.clj" "casselc_otel.git/4d61f8e921d1310bc7ba39d7208cc38ac14a3215/"
   "otel/exporter/chdb.clj" "jolt-otel-clickhouse/14a2998a27f64a9bff329811461be9157a00c849/"
   "otel/viewer.clj" "jolt-otel-viewer.git/5723a7c28c3bb3ae7cb27f9856b90463e77df523/"})

(defn- sanitized-resolution-env []
  (cond-> {"PATH" (or (System/getenv "PATH") "/usr/bin:/bin")}
    (System/getenv "HOME")
    (assoc "HOME" (System/getenv "HOME"))
    (System/getenv "TMPDIR")
    (assoc "TMPDIR" (System/getenv "TMPDIR"))
    (System/getenv "JOLT_CACHE_DIR")
    (assoc "JOLT_CACHE_DIR" (System/getenv "JOLT_CACHE_DIR"))))

(defn- run-jolt [& args]
  (let [jolt-bin (or (System/getenv "JOLT_BIN") "jolt")
        wrapper (System/getenv "JOLT_WRAPPER")
        command (cond-> [] wrapper (conj wrapper))
        command (into command (into [jolt-bin "-Srepro"] args))
        child (process/process command
                               {:env (sanitized-resolution-env)
                                :out :string :err :string})
        result (deref child 120000 ::timeout)]
    (when (= ::timeout result)
      (try (process/destroy-tree child) (catch Throwable _ nil)))
    result))

(defn- classpath-roots [classpath source-path]
  (->> (str/split (str classpath) #":")
       (filter #(.isFile (java.io.File. % source-path)))
       vec))

(deftest embedded-alias-pins-the-reviewed-viewer-graph
  (let [deps (edn/read-string (slurp "deps.edn"))
        telemetry (get-in deps [:aliases :telemetry :extra-deps])
        embedded (get-in deps [:aliases :embedded-telemetry])
        direct (:extra-deps embedded)
        overrides (:override-deps embedded)]
    (is (= (expected-shas 'io.github.casselc/otel)
           (get-in telemetry ['io.github.casselc/otel :git/sha])))
    (doseq [lib ['io.github.chucklehead-dev/oscope
                 'io.github.casselc/jolt-http
                 'io.github.chucklehead-dev/jolt-otel-viewer]]
      (is (= (expected-shas lib) (get-in direct [lib :git/sha]))))
    (doseq [lib ['io.github.casselc/otel
                 'io.github.chucklehead-dev/jolt-chdb
                 'io.github.chucklehead-dev/jolt-otel-clickhouse]]
      (is (= (expected-shas lib) (get-in overrides [lib :git/sha]))))
    (is (= "96324713500c96ae97c0deaf84691f31df158f25"
           (get-in overrides ['jolt-lang/db :git/sha])))
    (is (= "3174868a7baa06e118fb8d1201edd98c5769b335"
           (get-in overrides ['org.clojure/data.json :git/sha])))
    (is (= "5effcc89a3258499a79a2a3d69edad9e7800d1bf"
           (get-in overrides ['jolt-lang/jolt-crypto :git/sha])))
    (is (= "0.20.1" (get-in overrides ['metosin/malli :mvn/version])))
    (is (= "profiles/embedded"
           (get-in direct ['io.github.chucklehead-dev/oscope :deps/root])))))

(deftest embedded-alias-has-one-provider-for-each-qualified-namespace
  (let [result (run-jolt "-A:telemetry:embedded-telemetry" "-Spath")]
    (is (map? result))
    (when (map? result)
      (is (zero? (:exit result)) (str (:out result) (:err result)))
      (doseq [[source-path expected-root] expected-roots]
        (let [providers (classpath-roots (:out result) source-path)]
          (testing source-path
            (is (= 1 (count providers)) (pr-str providers))
            (is (str/includes? (or (first providers) "") expected-root))))))))

(deftest viewer-handlers-load-without-loading-a-listener-or-receiver-owner
  (doseq [handler-ns '[oscope.ui.events oscope.ui.web oscope.ui.workbench]]
    (is (some? (find-ns handler-ns))))
  (doseq [owner-ns '[oscope.server oscope.otlp oscope.embedded.viewer]]
    (is (nil? (find-ns owner-ns))
        (str owner-ns " must remain unloaded during dependency qualification"))))
