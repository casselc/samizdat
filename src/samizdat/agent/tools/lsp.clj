;; samizdat - a self-hosting agentic harness
;; License: EPL-2.0

(ns samizdat.agent.tools.lsp
  "LSP tool: code navigation via clojure-lsp. Read-only inspection."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.lsp.client :as client]))

(def ^:private usage
  (str "lsp ops: definition|references|hover need file (project-relative), line, col (0-based); "
       "diagnostics needs file. line/col are 0-based ints."))

(defn- uri->path [u]
  (.getPath (java.net.URI. u)))

(defn- loc->str [loc]
  (let [{:keys [line character]} (get-in loc [:range :start])]
    (str (uri->path (:uri loc)) ":" line ":" character)))

(defn- contents->str [cs]
  (str/join "\n" (map #(if (map? %) (:value %) (str %)) (if (map? cs) [cs] cs))))

(defn- render-diag [d]
  (str (get-in d [:range :start :line]) ":" (get-in d [:range :start :character])
       " " (:severity d) ": " (:message d)))

(defn- resolve-file [ctx]
  (str (io/file (:root ctx) (base/arg ctx :file))))

(defn- parse-int-or-nil [s]
  (try (Integer/parseInt (str/trim (str s))) (catch Exception _ nil)))

(defn- want-pos [ctx]
  (or (base/missing ctx :line :col)
      (let [line (parse-int-or-nil (base/arg ctx :line))
            col  (parse-int-or-nil (base/arg ctx :col))]
        (when (or (nil? line) (nil? col))
          (str "line and col must be integers. " usage)))))

(defn- lookup-op [op c file line col]
  (case op
    "definition" (let [loc (client/definition c file line col)]
                   (if loc (loc->str loc) "no definition found"))
    "references" (let [refs (client/references c file line col)]
                   (if (seq refs) (str/join "\n" (map loc->str refs)) "no references found"))
    "hover"      (let [h (client/hover c file line col)]
                   (if h (contents->str (:contents h)) "no hover info"))))

(defmethod base/run-tool "lsp" [{:keys [branch root] :as ctx}]
  ;; Read-only code inspection: :neutral, the same reasoning as grep.
  (if-not (client/available?)
    (base/fail branch "clojure-lsp is not installed; install it to use the lsp tool")
    (let [op (some-> (base/arg ctx :op) str str/trim str/lower-case not-empty)]
      (if-not (#{"definition" "references" "hover" "diagnostics"} op)
        (base/malformed branch (str "lsp needs a valid :op. " usage))
        (let [miss-file (base/missing ctx :file)]
          (if miss-file
            (base/malformed branch miss-file)
            (let [file (resolve-file ctx)]
              (if (= op "diagnostics")
                (let [c (client/client-for root)]
                  (if (nil? c)
                    (base/fail branch "could not start clojure-lsp for root")
                    (let [ds (client/diagnostics c file)]
                      (base/ok branch (if (seq ds) (str/join "\n" (map render-diag ds)) "no problems")))))
                (let [err-pos (want-pos ctx)]
                  (if err-pos
                    (base/malformed branch err-pos)
                    (let [c (client/client-for root)]
                      (if (nil? c)
                        (base/fail branch "could not start clojure-lsp for root")
                        (base/ok branch (lookup-op op c file
                                                   (parse-int-or-nil (base/arg ctx :line))
                                                   (parse-int-or-nil (base/arg ctx :col))))))))))))))))
