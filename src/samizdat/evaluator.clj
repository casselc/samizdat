;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evaluator
  "Trusted read-only bounded evaluator mechanism for JS1 M1.

  This namespace intentionally requires jolt.sandbox and therefore loads only
  in the pinned bounded lane. Ordinary Samizdat reaches it through dynamic
  resolution and does not put SCI on its classpath."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [jolt.fs :as fs]
            [jolt.sandbox :as sandbox]
            [samizdat.prompt :as prompt]
            [samizdat.store.evaluator :as store]))

(def jolt-coordinate "4af2362176160f2ed0e366689d7232b1a38adfec")
(def sci-coordinate "32d62a5136ad3dc148588752f5bcc4cc30b14752")
(def sci-version "0.13.53")
(def profile-id :agent/project-read)
(def top-level-tools ["eval" "doc" "complete" "done"])
(def profile-capabilities
  #{:project/read :project/list :project/search :project/stat})
(def semantic-operation-order
  [:project/read :project/list :project/search :project/stat])
(def default-bounds
  {:max-read-chars 60000
   :max-list-entries 1000
   :max-search-results 500
   :max-search-files 20000
   :max-search-file-chars 500000
   :max-search-pattern-chars 200})

(def operation-docs
  {:project/read
   {:name "project/read" :arglists [["path"]]
    :doc "Read one UTF-8 file relative to the authorized project root."}
   :project/list
   {:name "project/list" :arglists [["path"]]
    :doc "List one directory level as sorted {:name :kind :bytes?} data."}
   :project/search
   {:name "project/search" :arglists [["pattern"] ["pattern" "options"]]
    :doc "Search bounded project text and return {:path :line :text} data."}
   :project/stat
   {:name "project/stat" :arglists [["path"]]
    :doc "Return a deterministic path, kind, and byte-size observation."}})

(def tool-docs
  {"eval" {:name "eval" :arglists [["code"]]
           :doc "Evaluate code in this binding's persistent bounded SCI context."}
   "doc" {:name "doc" :arglists [["symbol"]]
          :doc "Describe a callable name from this binding's trusted catalog."}
   "complete" {:name "complete" :arglists [["prefix"]]
               :doc "List callable trusted-catalog names matching prefix."}
   "done" {:name "done" :arglists [[]]
           :doc "Emit a completion request. M1 refuses successful completion because verification is unavailable."}})

(defn runtime-snapshot []
  (sandbox/inert
   {:runtime/jolt-source jolt-coordinate
    :runtime/jolt-version (jolt.host/jolt-version)
    :runtime/sci-source sci-coordinate
    :runtime/sci-version sci-version
    :runtime/language (sandbox/language-coordinate)
    :runtime/evaluator-protocol 1
    :runtime/receipt-protocol 1}))

(defn runtime-coordinate []
  (str "js1-rt/v1:" (subs (sandbox/canonical-coordinate (runtime-snapshot)) 4)))

(defn- canonical-root [root]
  (str (fs/canonicalize root)))

(defn- spec-coordinate [spec]
  (str "js1:" (subs (sandbox/canonical-coordinate
                      (dissoc spec :spec/coordinate)) 4)))

(defn context-spec
  "Mint the inert effective ContextSpec. Requested authority is intersected
  with controller authorization, the trusted profile maximum, and the compiled
  operation vocabulary."
  [root {:keys [requested controller-authorized bounds]
         :or {requested profile-capabilities
              controller-authorized profile-capabilities}}]
  (let [effective (set/intersection (set requested)
                                    (set controller-authorized)
                                    profile-capabilities)
        base {:context/profile profile-id
              :context/root (canonical-root root)
              :context/capabilities (vec (sort-by str effective))
              :context/bounds (merge default-bounds bounds)}]
    (assoc base :context/coordinate (sandbox/canonical-coordinate base))))

(defn evaluator-spec [context]
  (let [base {:samizdat.evaluator/kind :spec
              :context-spec context
              :runtime-coordinate (runtime-coordinate)}]
    (assoc base :spec/coordinate (spec-coordinate base))))

(defn- fail! [kind message data]
  (throw (ex-info message (assoc data :samizdat.evaluator/error kind))))

(defn- message [data]
  (prompt/render "bounded-evaluator" data))

(defn- relative-path
  [root rel allow-root?]
  (when-not (and (string? rel) (not (str/blank? rel)))
    (fail! :invalid-path "Expected a non-empty relative project path" {:path rel}))
  (when (fs/absolute? (fs/path rel))
    (fail! :absolute-path (message {:absolute-path true}) {:path rel}))
  (let [root (str root)
        normalized (str (fs/normalize (fs/path root rel)))]
    (when-not (or (= normalized root) (str/starts-with? normalized (str root "/")))
      (fail! :path-escape (message {:path-escape true}) {:path rel}))
    (when (and (= normalized root) (not allow-root?))
      (fail! :not-file (message {:root-not-file true}) {:path rel}))
    normalized))

(defn- relative-name [root path]
  (str (fs/relativize root path)))

(defn- classify [path]
  (cond
    (not (fs/exists? path {:nofollow-links true})) :absent
    (fs/sym-link? path) :symlink
    (fs/directory? path {:nofollow-links true}) :directory
    (fs/regular-file? path {:nofollow-links true}) :file
    :else :other))

(defn- read-text [path max-chars]
  (when-not (= :file (classify path))
    (fail! :not-file (message {:read-not-file true}) {:path path}))
  (let [text (slurp (str path))]
    (when (> (count text) max-chars)
      (fail! :too-large (message {:read-large true})
             {:limit max-chars}))
    text))

(defn- operation-builders [context world-observer hook]
  (let [root (:context/root context)
        bounds (:context/bounds context)
        observe (fn [op args f]
                  (let [run (fn []
                              (when world-observer (world-observer op args))
                              (f))]
                    (if-let [h @hook] (h op args run) (run))))
        read-op {:id :project/read :name 'read :effect :observation
                 :fn (fn [rel]
                       (observe :project/read [rel]
                                #(read-text (relative-path root rel false)
                                            (:max-read-chars bounds))))}
        list-op {:id :project/list :name 'list :effect :observation
                 :fn (fn [rel]
                       (observe
                        :project/list [rel]
                        #(let [dir (relative-path root rel true)]
                           (when-not (= :directory (classify dir))
                             (fail! :not-directory (message {:list-not-dir true})
                                    {:path rel}))
                           (let [entries (sort-by str (fs/list-dir dir))]
                             (when (> (count entries) (:max-list-entries bounds))
                               (fail! :too-many-entries (message {:list-many true})
                                      {:limit (:max-list-entries bounds)}))
                             (mapv (fn [entry]
                                     (let [kind (classify entry)]
                                       (cond-> {:name (str (fs/file-name entry)) :kind kind}
                                         (= :file kind) (assoc :bytes (fs/size entry)))))
                                   entries))))) }
        search-op
        {:id :project/search :name 'search :effect :observation
         :fn (fn [& args]
               (let [[pattern options] args]
                 (when-not (and (<= 1 (count args) 2)
                                (string? pattern) (not (str/blank? pattern))
                                (<= (count pattern) (:max-search-pattern-chars bounds))
                                (or (nil? options) (map? options)))
                   (fail! :invalid-arguments (message {:search-args true})
                          {:args args}))
                 (observe
                  :project/search (vec args)
                  #(let [dir (relative-path root (or (:path options) ".") true)
                         re (try (re-pattern pattern)
                                 (catch Throwable _
                                   (fail! :invalid-regex "Invalid search regex"
                                          {:pattern pattern})))
                         paths (->> (fs/glob dir "**")
                                    (filter (fn [p] (= :file (classify p))))
                                    (sort-by str)
                                    vec)]
                     (when (> (count paths) (:max-search-files bounds))
                       (fail! :too-many-files (message {:search-many true})
                              {:limit (:max-search-files bounds)}))
                     (->> paths
                          (mapcat (fn [p]
                                    (let [text (when (<= (fs/size p)
                                                        (:max-search-file-chars bounds))
                                                 (slurp (str p)))]
                                      (when text
                                        (keep-indexed
                                         (fn [i line]
                                           (when (re-find re line)
                                             {:path (relative-name root p)
                                              :line (inc i)
                                              :text (str/trim line)}))
                                         (str/split text #"\n" -1))))))
                          (take (:max-search-results bounds))
                          vec))))) }
        stat-op {:id :project/stat :name 'stat :effect :observation
                 :fn (fn [rel]
                       (observe
                        :project/stat [rel]
                        #(let [path (relative-path root rel false)
                               kind (classify path)]
                           (cond-> {:path (relative-name root path) :kind kind}
                             (= :file kind) (assoc :bytes (fs/size path))))))}]
    [read-op list-op search-op stat-op]))

(defn- make-instance [spec observer]
  (let [hook (atom nil)
        ops (operation-builders (:context-spec spec) (:world-observer observer) hook)
        capabilities (set (get-in spec [:context-spec :context/capabilities]))
        state (sandbox/create-context
               {:operations ops :profile profile-id
                :requested-capabilities capabilities
                :authorized-capabilities capabilities})]
    {:samizdat.evaluator/kind :instance
     :instance/id (:instance-id observer)
     :context-id (str (random-uuid))
     :state state :hook hook :operations ops}))

(defn- catalog [binding]
  (let [effective (set (get-in binding [:spec :context-spec :context/capabilities]))
        ops (mapv #(str "project/" (name %))
                  (filter effective semantic-operation-order))]
    (vec (concat top-level-tools ops))))

(defn trusted-orientation [binding]
  (str "SYSTEM / TRUSTED SURFACE\n"
       "Callable top-level tools:\n"
       (str/join "\n" (map #(str "- " %) top-level-tools))
       "\nSemantic operations available only inside eval:\n"
       (str/join "\n" (map #(str "- " %)
                            (drop (count top-level-tools) (catalog binding))))
       "\n\n" (message {:orientation-guidance true})))

(defn bind!
  "Create one controller-minted read-only EvaluatorBinding."
  [root work-id opts]
  (let [context (context-spec root opts)
        spec (evaluator-spec context)
        instance-id (str "inst:" work-id)
        observer {:instance-id instance-id :world-observer (:world-observer opts)}
        instance (make-instance spec observer)
        binding {:samizdat.evaluator/kind :binding
                 :binding/id (str "bind:" work-id)
                 :work-id (str work-id)
                 :instance/id instance-id
                 :spec spec
                 :instance (atom instance)
                 :owner (atom nil)
                 :poisoned (atom false)
                 :world-observer (:world-observer opts)}]
    (assoc binding :trusted-orientation (trusted-orientation binding))))

(defn describe [binding]
  (let [instance @(:instance binding)]
    {:evaluator/spec-id (get-in binding [:spec :spec/coordinate])
     :evaluator/instance-id (:instance/id binding)
     :evaluator/binding-id (:binding/id binding)
     :evaluator/context-spec (get-in binding [:spec :context-spec :context/coordinate])
     :evaluator/runtime (get-in binding [:spec :runtime-coordinate])
     :evaluator/live-context (:context-id instance)
     :evaluator/capabilities (get-in binding [:spec :context-spec :context/capabilities])}))

(defn- verify-binding! [binding]
  (when-not (and (= :binding (:samizdat.evaluator/kind binding))
                 (= (get-in binding [:spec :spec/coordinate])
                    (spec-coordinate (dissoc (:spec binding) :spec/coordinate))))
    (fail! :invalid-binding "Invalid evaluator binding" {}))
  binding)

(defn doc [binding symbol]
  (let [s (str/trim (str symbol))]
    (when (some #{s} (catalog binding))
      (or (get tool-docs s) (get operation-docs (keyword s))))))

(defn complete [binding prefix]
  (let [p (str prefix)]
    (vec (filter #(str/starts-with? % p) (catalog binding)))))

(defn- result-record [value]
  (try {:value (sandbox/inert value)}
       (catch Throwable _
         {:rendered (binding [*print-length* 100 *print-level* 20]
                      (pr-str value))})))

(defn- result-matches? [record value]
  (= (:result record) (result-record value)))

(defn- identity-map [binding]
  {:spec-id (get-in binding [:spec :spec/coordinate])
   :instance-id (:instance/id binding)
   :binding-id (:binding/id binding)
   :context-spec (get-in binding [:spec :context-spec :context/coordinate])
   :runtime (get-in binding [:spec :runtime-coordinate])})

(defn- receipt->jolt [receipt]
  (cond-> {:op/id (:op receipt) :op/args (:args receipt)}
    (= :done (:phase receipt)) (assoc :op/result (:result receipt))
    (= :error (:phase receipt)) (assoc :op/error (:error receipt))))

(defn- validate-history! [binding rows]
  (when-not (= (mapv :binding_seq rows) (vec (range (count rows))))
    (fail! :malformed-history (message {:history-gap true})
           {:binding-seqs (mapv :binding_seq rows)}))
  (let [{:keys [spec-id instance-id binding-id context-spec runtime]}
        (identity-map binding)
        expected {:spec_id spec-id :instance_id instance-id :binding_id binding-id
                  :context_spec context-spec :runtime runtime}]
    (doseq [row rows]
      (when (= :pending (:status row))
        (fail! :pending-history "Pending evaluator history refuses reconstruction"
               {:eval-id (:id row)}))
      (when-not (= expected (select-keys row (keys expected)))
        (fail! :history-mismatch "Evaluator history identity mismatch"
               {:eval-id (:id row) :expected expected})))))

(defn- rebuild-internal! [conn binding]
  (let [rows (store/history conn (:binding/id binding))]
    ;; Validate every durable coordinate before allocating or interpreting SCI.
    (validate-history! binding rows)
    (let [fresh (make-instance (:spec binding)
                               {:instance-id (:instance/id binding)
                                :world-observer (:world-observer binding)})
          state (:state fresh)]
      (doseq [row rows]
        (when (= :completed (:status row))
          (let [receipts (mapv receipt->jolt (:receipts row))]
            (sandbox/load-receipts! state receipts)
            (sandbox/set-mode! state :replay)
            (let [value (sandbox/evaluate! state (:source row))]
              (when-not (result-matches? row value)
                (fail! :replay-result-mismatch "Replayed result differs from durable result"
                       {:eval-id (:id row)}))))))
      (sandbox/load-receipts! state [])
      (sandbox/set-mode! state :normal)
      (reset! (:instance binding) fresh)
      (reset! (:poisoned binding) false)
      binding)))

(defn rebuild! [conn binding]
  (verify-binding! binding)
  (rebuild-internal! conn binding))

(defn evaluate-recorded!
  "Evaluate one source form under the binding and append begin, operation
  intent/outcome, and terminal rows. Failed evaluations rebuild to committed
  history before propagating."
  [conn binding source]
  (verify-binding! binding)
  (when @(:poisoned binding)
    (fail! :instance-poisoned "Evaluator instance is poisoned" {}))
  (let [claim (str (random-uuid))]
    (when-not (compare-and-set! (:owner binding) nil claim)
      (fail! :instance-busy (message {:evaluator-busy true}) {}))
    (try
      (let [instance @(:instance binding)
            eval-id (store/begin! conn (assoc (identity-map binding) :source source))
            hook (:hook instance)]
        (reset! hook
                (fn [op args run]
                  (let [seqn (store/intent! conn eval-id op args)]
                    (try
                      (let [value (sandbox/inert (run))]
                        (store/outcome! conn eval-id seqn {:result value})
                        value)
                      (catch Throwable e
                        (store/outcome! conn eval-id seqn {:error (ex-message e)})
                        (throw e))))))
        (try
          (let [value (sandbox/evaluate! (:state instance) source)
                result (result-record value)]
            (store/complete! conn eval-id :completed result)
            {:eval-id eval-id :value value :result result})
          (catch Throwable e
            (try
              (store/complete! conn eval-id :failed {:error (ex-message e)})
              (rebuild-internal! conn binding)
              (catch Throwable rollback
                (reset! (:poisoned binding) true)
                (throw (ex-info "Evaluation failed and committed-state rollback failed"
                                {:samizdat.evaluator/error :rollback-failed
                                 :eval-id eval-id}
                                rollback))))
            (throw e))
          (finally
            (reset! hook nil))))
      (finally
        (compare-and-set! (:owner binding) claim nil)))))

(defn leverage [conn binding]
  (let [completed (filter #(= :completed (:status %))
                          (store/history conn (:binding/id binding)))
        orders (mapv (fn [row] (mapv :op (:receipts row))) completed)]
    {:evaluations (count completed)
     :operations-per-eval (mapv count orders)
     :multi-operation-evals (count (filter #(< 1 (count %)) orders))
     :operation-order orders}))
