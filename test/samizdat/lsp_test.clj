(ns samizdat.lsp-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.lsp]      ;; registers the "lsp" defmethod
            [samizdat.lsp.client :as client]))

;; clojure-lsp is not installed on CI; every lsp-touching assert is gated so the
;; suite is trivially green there and meaningful on a machine that has it.

(defn- sample-dir [] (io/file "/tmp" "samizdat-lsp-test"))

(defn- write-sample! [dir]
  (.mkdirs dir)
  (let [f (io/file dir "sample.clj")]
    (spit f "(ns sample)\n\n(defn greet [x]\n  (str \"hi \" x))\n\n(greet \"world\")\n\nundefined-thing\n")
    f))

(deftest lsp-tool-test
  (if-not (client/available?)
    (is true "clojure-lsp absent; skipped")
    (let [root (sample-dir)
          f    (write-sample! root)]
      (try
        (let [c (client/client-for root)]
          (is (some? c) "client-for returns a client")
          ;; definition of a used symbol points back at its defn line
          (let [loc (client/definition c (str f) 5 1)]
            (is (some? loc))
            (is (= 2 (get-in loc [:range :start :line])) "defn starts on 0-based line 2"))
          ;; unresolved symbol shows up in diagnostics
          (is (seq (client/diagnostics c (str f))) "unresolved symbol is diagnosed")
          ;; the tool renders path:line:col
          (let [r (base/run-tool {:tool-name "lsp"
                                  :branch {:id "B1"}
                                  :root   (str root)
                                  :args   {:op "definition" :file "sample.clj" :line 5 :col 1}})]
            (is (= :neutral (:category r)))
            (is (re-matches #".*sample\.clj:2:6" (:result r)))))
        (finally
          (client/shutdown! root))))))
