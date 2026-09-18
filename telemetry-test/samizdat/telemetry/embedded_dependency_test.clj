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
   "88a63fd90e0969635dda75fbb9fe5ba2264c09d8"
   'io.github.chucklehead-dev/oscope
   "9a35e58100a00deb5a50f80045f066ce7d09bb3a"
   'io.github.chucklehead-dev/jolt-chdb
   "19e0ecf9e9f5e2c3f24ac8758f5d6953fd021774"
   'io.github.chucklehead-dev/jolt-otel-clickhouse
   "0f8bf3de8c225ed4ab4f700fe60bb7c85229136d"
   'io.github.casselc/jolt-http
   "35d1d7f9ebdc796ee9bd4c80745298b2c8b7fdf8"
   'io.github.chucklehead-dev/jolt-otel-viewer
   "5723a7c28c3bb3ae7cb27f9856b90463e77df523"})

(def ^:private expected-roots
  {"clojure/data/json.clj" "data.json.git/3174868a7baa06e118fb8d1201edd98c5769b335/"
   "db/sqlite.clj" "casselc_db.git/6db791634e5a4c65c24646833b2e82d3a5d7a121/"
   "jdbc/chdb/durable.clj" "jolt-chdb.git/19e0ecf9e9f5e2c3f24ac8758f5d6953fd021774/"
   "jolt/time.clj" "chucklehead-dev_time.git/2494b21b25cd959573c3e6050cd475e4bf302fdb/"
   "jolt/crypto.clj" "jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/"
   "jolt/http_client.clj" "http-client.git/eab6b78d5957f88690faf6768360572a3f185341/"
   "jolt/http/server.clj" "jolt-http.git/35d1d7f9ebdc796ee9bd4c80745298b2c8b7fdf8/"
   "otel/sdk.clj" "casselc_otel.git/88a63fd90e0969635dda75fbb9fe5ba2264c09d8/"
   "otel/exporter/chdb.clj" "jolt-otel-clickhouse.git/0f8bf3de8c225ed4ab4f700fe60bb7c85229136d/"
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
       (mapcat (fn [root]
                 (for [path [source-path
                             (str/replace source-path #"\.clj$" ".cljc")]
                       :let [file (java.io.File. root path)]
                       :when (.isFile file)]
                   (.getPath file))))
       vec))

(deftest embedded-alias-pins-the-reviewed-viewer-graph
  (let [deps (edn/read-string (slurp "deps.edn"))
        telemetry (get-in deps [:aliases :telemetry :extra-deps])
        telemetry-overrides (get-in deps [:aliases :telemetry :override-deps])
        embedded (get-in deps [:aliases :embedded-telemetry])
        direct (:extra-deps embedded)
        overrides (:override-deps embedded)]
    (is (= "https://github.com/chucklehead-dev/time.git"
           (get-in telemetry ['jolt-lang/time :git/url])))
    (is (= "2494b21b25cd959573c3e6050cd475e4bf302fdb"
           (get-in telemetry ['jolt-lang/time :git/sha])))
    (is (= "https://github.com/jolt-lang/db"
           (get-in telemetry-overrides ['jolt-lang/db :git/url])))
    (is (= "v0.4.0" (get-in telemetry-overrides ['jolt-lang/db :git/tag])))
    (is (= "d85f391ca521da389b935c38f3d78b30eaa23208"
           (get-in telemetry-overrides ['jolt-lang/db :git/sha])))
    (is (= ['io.github.jolt-lang/time]
           (get-in telemetry-overrides ['jolt-lang/db :exclusions])))
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
    (is (= "6db791634e5a4c65c24646833b2e82d3a5d7a121"
           (get-in overrides ['jolt-lang/db :git/sha])))
    (is (= ['io.github.jolt-lang/time]
           (get-in overrides ['jolt-lang/db :exclusions])))
    (is (= "2494b21b25cd959573c3e6050cd475e4bf302fdb"
           (get-in overrides ['jolt-lang/time :git/sha])))
    (is (= "https://github.com/chucklehead-dev/time.git"
           (get-in overrides ['jolt-lang/time :git/url])))
    (is (= "eab6b78d5957f88690faf6768360572a3f185341"
           (get-in telemetry ['jolt-lang/http-client :git/sha])))
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
      (is (zero? (:exit result)) "physical resolution must exit zero")
      (doseq [[source-path expected-root] expected-roots]
        (let [providers (classpath-roots (:out result) source-path)]
          (testing source-path
            (is (= 1 (count providers)) "exactly one physical namespace provider")
            (is (str/includes? (or (first providers) "") expected-root))))))))

(deftest viewer-handlers-load-without-loading-a-listener-or-receiver-owner
  (doseq [handler-ns '[oscope.ui.events oscope.ui.web oscope.ui.workbench]]
    (is (some? (find-ns handler-ns))))
  (doseq [owner-ns '[oscope.server oscope.otlp oscope.embedded.viewer]]
    (is (nil? (find-ns owner-ns))
        (str owner-ns " must remain unloaded during dependency qualification"))))
