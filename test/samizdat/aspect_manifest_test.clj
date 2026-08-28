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

(defn- qualified-symbol? [value]
  (and (symbol? value) (some? (namespace value))))

(defn- valid-selector? [match]
  (and (pos-int? (:arity match))
       (or (and (= #{:entry :arity} (set (keys match)))
                (qualified-symbol? (:entry match)))
           (and (= #{:ns :call :arity} (set (keys match)))
                (symbol? (:ns match))
                (qualified-symbol? (:call match))))))

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
          (is (valid-selector? (:match aspect)))
          (is (keyword? (:advice-role aspect)))
          (is (= 1 (get-in aspect [:expect :matches]))))))))

(deftest semantic-lifecycles-match-definition-entries
  (let [read-manifest #(some-> % io/resource slurp edn/read-string)
        core (read-manifest "META-INF/jolt/aspects/samizdat-m2-core.edn")
        embed (read-manifest "META-INF/jolt/aspects/samizdat-m2-embed.edn")
        by-id (into {} (map (juxt :id :match))
                    (concat (:aspects core) (:aspects embed)))]
    (is (= {:entry 'samizdat.agent.beam/run! :arity 1}
           (get by-id :samizdat.embed/beam-run)))
    (is (= {:entry 'samizdat.agent.beam/advance-branch :arity 3}
           (get by-id :samizdat.agent.beam/turn)))
    (is (= {:entry 'samizdat.llm.client/chat :arity 4}
           (get by-id :samizdat.agent.infer/model)))
    (is (= {:entry 'samizdat.agent.tools/run-tool :arity 1}
           (get by-id :samizdat.agent.loop/tool)))
    (is (= {:ns 'samizdat.llm.client
            :call 'jolt.http-client/post
            :arity 2}
           (get by-id :samizdat.llm.client/http-post)))))
