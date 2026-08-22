;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.lsp.client
  "A narrow LSP client over a persistent clojure-lsp subprocess.

  clojure-lsp is spoken to as `clojure-lsp listen`: LSP JSON-RPC messages,
  each framed with a Content-Length header, over the child's stdin/stdout.
  One server is started per project root and kept alive across turns —
  startup analyses the project, so per-request spawning would be far too
  slow.

  The read is deliberately single-threaded: a request sends, then reads
  frames until it sees its own id, routing any server-pushed notifications
  as it goes (diagnostics are accumulated per uri; everything else is
  dropped). Each read is bounded by a future+timeout so a wedged server
  costs a known amount rather than the run."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as jp]
            [samizdat.engine.proc :as proc])
  (:import [java.io BufferedInputStream OutputStream]))

;; root -> {:proc :out :in :next-id :opened :diagnostics}
(defonce ^:private clients (atom {}))

(def ^:private read-timeout-ms 20000)

(defn available?
  "Whether clojure-lsp can be executed at all."
  []
  (proc/available? "clojure-lsp"))

;; ---- framing --------------------------------------------------------------

(defn- send-frame!
  "Write one Content-Length-framed JSON-RPC message. Synchronised on the
  stream so two callers cannot interleave a frame."
  [{:keys [^OutputStream out]} msg]
  (let [body (.getBytes ^String (json/write-str msg) "UTF-8")
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "UTF-8")]
    (locking out
      (.write out header) (.write out body) (.flush out))))

(defn- read-frame
  "Read one message: the Content-Length header block, then that many bytes
  of JSON. Blocking; callers bound it with a timeout. Returns nil at EOF."
  [{:keys [^BufferedInputStream in]}]
  (let [header (loop [sb (StringBuilder.)]
                 (let [c (.read in)]
                   (cond
                     (neg? c) nil
                     (str/ends-with? (.append sb (char c)) "\r\n\r\n") (.toString sb)
                     :else (recur sb))))
        m (some->> header (re-find #"(?i)Content-Length:\s*(\d+)"))]
    (when m
      (let [n (Integer/parseInt (second m))
            buf (byte-array n)]
        (loop [off 0]
          (when (< off n)
            (let [r (.read in buf off (- n off))]
              (when-not (neg? r) (recur (+ off r))))))
        (json/read-str (String. buf "UTF-8") :key-fn keyword)))))

(defn- read-frame-bounded [client]
  (deref (future (read-frame client)) read-timeout-ms ::timeout))

;; ---- request / notify -----------------------------------------------------

(defn- store-diagnostics! [client msg]
  (when (= "textDocument/publishDiagnostics" (:method msg))
    (let [uri (get-in msg [:params :uri])
          diags (get-in msg [:params :diagnostics])]
      (swap! (:diagnostics client) assoc uri diags))))

(defn- request!
  "Send a request and read frames until the matching response arrives,
  routing notifications seen on the way. Returns the :result (or throws on
  an :error / timeout)."
  [client method params]
  (let [id (swap! (:next-id client) inc)]
    (send-frame! client {:jsonrpc "2.0" :id id :method method :params params})
    (loop [seen 0]
      (let [msg (read-frame-bounded client)]
        (cond
          (= ::timeout msg) (throw (ex-info (str "lsp: no response to " method) {:method method}))
          (nil? msg) (throw (ex-info (str "lsp: server closed during " method) {:method method}))
          (= id (:id msg)) (if-let [e (:error msg)]
                             (throw (ex-info (str "lsp error: " (:message e)) {:error e}))
                             (:result msg))
          :else (do (store-diagnostics! client msg)
                    (if (> seen 200)
                      (throw (ex-info (str "lsp: flooded before answering " method) {}))
                      (recur (inc seen)))))))))

(defn- notify! [client method params]
  (send-frame! client {:jsonrpc "2.0" :method method :params params}))

;; ---- lifecycle ------------------------------------------------------------

(defn- uri [path] (str "file://" (.getAbsolutePath (io/file path))))

(defn- start!
  "Spawn clojure-lsp for `root` and run the initialize/initialized
  handshake. Returns the client map."
  [root]
  (let [p (jp/process ["clojure-lsp" "listen"])
        ^java.lang.Process osproc (:proc p)
        client {:proc p
                :out (.getOutputStream osproc)
                :in (BufferedInputStream. (.getInputStream osproc))
                :next-id (atom 0)
                :opened (atom #{})
                :diagnostics (atom {})}]
    (request! client "initialize"
              {:processId nil :rootUri (uri root) :capabilities {}})
    (notify! client "initialized" {})
    client))

(defn client-for
  "The live client for `root`, started on first use. nil when clojure-lsp
  is not installed."
  [root]
  (when (available?)
    (or (get @clients root)
        (let [c (start! root)]
          (swap! clients assoc root c)
          c))))

(defn shutdown!
  "Stop the server for `root`, if any."
  [root]
  (when-let [c (get @clients root)]
    (try (notify! c "exit" {}) (catch Throwable _ nil))
    (try (.destroyForcibly ^java.lang.Process (:proc (:proc c))) (catch Throwable _ nil))
    (swap! clients dissoc root)))

;; ---- document + narrow ops ------------------------------------------------

(defn- ensure-open! [client path]
  (when-not (contains? @(:opened client) path)
    (notify! client "textDocument/didOpen"
             {:textDocument {:uri (uri path) :languageId "clojure"
                             :version 1 :text (slurp path)}})
    (swap! (:opened client) conj path)))

(defn- at [client path line character method]
  (ensure-open! client path)
  (request! client method
            {:textDocument {:uri (uri path)}
             :position {:line line :character character}}))

(defn definition [client path line character]
  (at client path line character "textDocument/definition"))

(defn references [client path line character]
  (ensure-open! client path)
  (request! client "textDocument/references"
            {:textDocument {:uri (uri path)}
             :position {:line line :character character}
             :context {:includeDeclaration true}}))

(defn hover [client path line character]
  (at client path line character "textDocument/hover"))

(defn diagnostics
  "The problems clojure-lsp reports for `path`. didOpen triggers a
  publishDiagnostics push; wait briefly for it, then return what arrived."
  [client path]
  (let [u (uri path)]
    (swap! (:diagnostics client) dissoc u)
    (ensure-open! client path)
    ;; if already open, ask the server to re-analyse by bumping the doc
    (notify! client "textDocument/didChange"
             {:textDocument {:uri u :version 2}
              :contentChanges [{:text (slurp path)}]})
    (loop [waited 0]
      (let [d (get @(:diagnostics client) u)]
        (cond
          (some? d) d
          (>= waited 5000) []
          :else (do (deref (future nil) 250 nil)
                    ;; drain any pending pushes
                    (let [m (read-frame-bounded client)]
                      (when (and (map? m) (not= ::timeout m)) (store-diagnostics! client m)))
                    (recur (+ waited 250))))))))
