(ns samizdat.lsp-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.tools.base :as base]
             [samizdat.agent.tools.lsp]
             [clojure.data.json :as json]
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

(defn- write-frame!
  "Test-side framer: hand-written response frames onto the client's stream."
  [^java.io.PipedOutputStream out msg]
  (let [body (.getBytes (json/write-str msg) "UTF-8")
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "UTF-8")]
    (.write out header) (.write out body) (.flush out)))

(deftest concurrent-requests-correlate-their-own-responses
  ;; code-review-2026-08 #4: every caller read the shared stream itself, so
  ;; two concurrent requests could steal each other's frames — the response
  ;; landed by whichever reader got there first was dropped by the other.
  ;; One dedicated reader thread routes frames by id instead.
  (let [resp-writer (java.io.PipedOutputStream.)
        client {:in (java.io.BufferedInputStream.
                     (java.io.PipedInputStream. resp-writer))
                :out (java.io.ByteArrayOutputStream.)
                :next-id (atom 0)
                :opened (atom #{})
                :diagnostics (atom {})
                :pending (atom {})}]
    (#'client/start-reader! client)
    (let [f1 (future (#'client/request! client "textDocument/hover" {}))]
      ;; Pin the ids: whichever request is pending first holds id 1, so the
      ;; out-of-order responses below prove correlation, not scheduling luck.
      (while (nil? (get @(:pending client) 1)) (Thread/sleep 5))
      (let [f2 (future (#'client/request! client "textDocument/definition" {}))]
        (while (nil? (get @(:pending client) 2)) (Thread/sleep 5))
        ;; answered out of order on purpose: the definition (id 2) first
        (write-frame! resp-writer {:jsonrpc "2.0" :id 2 :result "def"})
        (write-frame! resp-writer {:jsonrpc "2.0" :id 1 :result "hover"})
        (is (= "def" (deref f2 5000 ::timeout)))
        (is (= "hover" (deref f1 5000 ::timeout)))))))
