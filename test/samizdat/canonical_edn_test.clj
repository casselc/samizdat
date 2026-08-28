;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.canonical-edn-test
  "Conformance for the canonical EDN contract keeper (RFC-012).

  The load-bearing assertions are the SHARED vectors: their golden digests
  come from the bbagent ecosystem's own contract tests, so passing here means
  this repository's independent keeper still produces byte-identical
  coordinates over the same grammar. Failing means the two repositories have
  stopped keeping one contract — which is exactly what must fail loudly
  rather than drift.

  The rest pin the grammar's rules on this side of the contract: order
  freedom, print-binding independence, integer normalization, domain
  separation, and the rejection of everything alive or ambiguous."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.security.canonical-edn :as cedn]))

(def ^:private fixtures
  (delay
   (or (some-> (io/resource "samizdat/fixtures/execution_env_edn.edn")
              slurp
              edn/read-string)
       (throw (ex-info "the EDN SPI fixture set is missing from the classpath"
                       {:fixture "samizdat/fixtures/execution_env_edn.edn"})))))

(deftest the-shared-golden-vectors-are-the-other-repositories-digests
  (doseq [{:keys [fixture/id kind value coordinate]} (:shared-vectors @fixtures)]
    (testing (str id)
      (is (= coordinate (cedn/coordinate kind value))
          "this keeper no longer produces the digest the other repository
          pinned — the two sides have drifted apart on the shared grammar"))))

(deftest the-shared-vector-is-order-free
  ;; The permuted spelling bbagent's own test uses: same value, different
  ;; insertion order, array-maps and hash-set enumerations rearranged. (=)
  ;; would pass trivially — maps compare by value — so what is pinned is the
  ;; COORDINATE: a different enumeration order must not move it.
  (let [{:keys [kind coordinate]} (first (:shared-vectors @fixtures))
        permuted (array-map :nested (array-map :y [:ok]
                                               :x (into #{} [1 3 2]))
                            :a 1)]
    (is (= coordinate (cedn/coordinate kind permuted))
        "map and set enumeration order changed the coordinate")))

(deftest a-coordinate-ignores-the-ambient-print-bindings
  (let [{:keys [kind value coordinate]} (first (:shared-vectors @fixtures))]
    (is (= coordinate
           (binding [*print-length* 1
                     *print-level* 1]
             (cedn/coordinate kind value)))
        "a caller's *print-length* changed a coordinate — the printer is not
        pinned")))

(deftest the-regression-vectors-hold-this-sides-vocabulary
  (doseq [{:keys [fixture/id kind value coordinate]} (:regression-vectors @fixtures)]
    (testing (str id)
      (is (= coordinate (cedn/coordinate kind value))
          "the keeper's digest for this repository's own fixture vocabulary
          moved — the envelope coordinates journal readers compare against
          are no longer the ones this suite shipped with"))))

(deftest integers-normalize-through-bigint
  (is (= (cedn/coordinate :bb4t/test-vector {:n 1})
         (cedn/coordinate :bb4t/test-vector {:n 1N}))))

(deftest the-kind-separates-domains
  (let [{:keys [value]} (first (:shared-vectors @fixtures))]
    (is (not= (cedn/coordinate :bb4t/test-vector value)
              (cedn/coordinate :bb4t/execution-environment value))
        "the same value under two kinds shares a coordinate")))

(deftest a-different-grant-set-is-a-different-coordinate
  (is (not= (cedn/coordinate :bb4t/test-vector {:grant #{:a/x}})
            (cedn/coordinate :bb4t/test-vector
                              {:grant #{:a/x :b/y}}))))

(deftest the-canonical-domain-rejects-live-or-ambiguous-values
  (doseq [value [(with-meta [:value] {:source :accidental})
                 1.0
                 1/2
                 (Object.)
                 String
                 #'cedn/coordinate
                 (fn [])
                 (atom {})
                 (map identity [1 2 3])]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (cedn/coordinate :bb4t/test-vector value))
        (str "accepted unsupported value " (some-> value class .getName)))))

(deftest the-kind-must-be-qualified
  (is (thrown? clojure.lang.ExceptionInfo
               (cedn/coordinate :context {}))))

(deftest sha-256-path-digests-bytes-not-names
  (let [dir (str (fs/create-temp-dir {:prefix "cedn-fixture-"}))
        path (str dir "/payload")]
    (try
      (spit path "canonical bytes\n")
      (is (= (cedn/sha-256-path path)
             (cedn/sha-256 "canonical bytes\n")))
      (spit path "different bytes\n")
      (is (not= (cedn/sha-256-path path)
                (cedn/sha-256 "canonical bytes\n"))
          "the file digest followed the name rather than the content")
      (finally (fs/delete-tree dir)))))
