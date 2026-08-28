(ns samizdat.aspect-manifest-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [samizdat.instrumentation :as instrumentation]))

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
        (is (= instrumentation/compatibility-id
               (get-in manifest [:library :version])))
        (is (seq (:aspects manifest)))
        (doseq [aspect (:aspects manifest)]
          (is (keyword? (:id aspect)))
          (is (valid-selector? (:match aspect)))
          (is (keyword? (:advice-role aspect)))
          (is (= 1 (get-in aspect [:expect :matches]))))))))

(deftest semantic-join-points-match-exact-source-boundaries
  (let [read-manifest #(some-> % io/resource slurp edn/read-string)
        core (read-manifest "META-INF/jolt/aspects/samizdat-m2-core.edn")
        embed (read-manifest "META-INF/jolt/aspects/samizdat-m2-embed.edn")
        aspects (concat (:aspects core) (:aspects embed))
        by-id (into {} (map (juxt :id identity))
                    aspects)
        match #(get-in by-id [% :match])
        role #(get-in by-id [% :advice-role])]
    (is (= 9 (count (:aspects core))))
    (is (= 10 (count aspects)))
    (is (= (count aspects) (count by-id)) "aspect ids are unique")
    (is (= #{:samizdat.agent.beam/control-loop
             :samizdat.store.runs/branch-open
             :samizdat.store.runs/branch-close
             :samizdat.agent.beam/turn
             :samizdat.agent.infer/model
             :samizdat.agent.infer/tool-selection
             :samizdat.agent.loop/tool
             :samizdat.agent.arbiter/steer
             :samizdat.llm.client/http-post
             :samizdat.embed/beam-run}
           (set (keys by-id))))
    (is (= {:entry 'samizdat.agent.beam/run! :arity 1}
           (match :samizdat.embed/beam-run)))
    (is (= :samizdat/run (role :samizdat.embed/beam-run)))
    (is (= {:entry 'samizdat.agent.beam/run-rounds :arity 3}
           (match :samizdat.agent.beam/control-loop)))
    (is (= :samizdat/control-loop
           (role :samizdat.agent.beam/control-loop)))
    (is (= {:entry 'samizdat.store.runs/open-branch! :arity 3}
           (match :samizdat.store.runs/branch-open)))
    (is (= :samizdat/branch-open
           (role :samizdat.store.runs/branch-open)))
    (is (= {:entry 'samizdat.store.runs/close-branch! :arity 5}
           (match :samizdat.store.runs/branch-close)))
    (is (= :samizdat/branch-close
           (role :samizdat.store.runs/branch-close)))
    (is (= {:entry 'samizdat.agent.beam/advance-branch :arity 3}
           (match :samizdat.agent.beam/turn)))
    (is (= :samizdat/turn (role :samizdat.agent.beam/turn)))
    (is (= {:entry 'samizdat.llm.client/chat :arity 4}
           (match :samizdat.agent.infer/model)))
    (is (= :samizdat/model (role :samizdat.agent.infer/model)))
    (is (= {:entry 'samizdat.agent.infer/absorb :arity 3}
           (match :samizdat.agent.infer/tool-selection)))
    (is (= :samizdat/tool-selection
           (role :samizdat.agent.infer/tool-selection)))
    (is (= {:entry 'samizdat.agent.tools/run-tool :arity 1}
           (match :samizdat.agent.loop/tool)))
    (is (= :samizdat/tool (role :samizdat.agent.loop/tool)))
    (is (= {:entry 'samizdat.agent.arbiter/decide :arity 1}
           (match :samizdat.agent.arbiter/steer)))
    (is (= :samizdat/steer (role :samizdat.agent.arbiter/steer)))
    (is (= {:ns 'samizdat.llm.client
            :call 'jolt.http-client/post
            :arity 2}
           (match :samizdat.llm.client/http-post)))
    (is (= :http/client (role :samizdat.llm.client/http-post)))))
