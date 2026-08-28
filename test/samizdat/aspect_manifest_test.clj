(ns samizdat.aspect-manifest-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private compatibility-id
  "35b01fddd20fa9e6d77678eadc2a2bcc6fb9ac2d")

(def ^:private manifest-resources
  ["META-INF/jolt/aspects/samizdat-m2-core.edn"
   "META-INF/jolt/aspects/samizdat-m2-embed.edn"
   "META-INF/jolt/aspects/samizdat-m2-http.edn"])

(deftest library-supplies-inert-instrumentation-contracts
  (doseq [resource-name manifest-resources]
    (testing resource-name
      (let [resource (io/resource resource-name)
            manifest (some-> resource slurp edn/read-string)]
        (is (some? resource))
        (is (= 1 (:schema manifest)))
        (is (= 'yogthos/samizdat (get-in manifest [:library :id])))
        (is (= compatibility-id (get-in manifest [:library :version])))
        (is (seq (:aspects manifest)))
        (doseq [aspect (:aspects manifest)]
          (is (keyword? (:id aspect)))
          (is (symbol? (get-in aspect [:match :ns])))
          (is (symbol? (get-in aspect [:match :call])))
          (is (pos-int? (get-in aspect [:match :arity])))
          (is (keyword? (:advice-role aspect)))
          (is (= 1 (get-in aspect [:expect :matches]))))))))
