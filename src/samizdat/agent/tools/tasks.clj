;; samizdat - a self-hosting agentic harness
;; License: EPL-2.0

(ns samizdat.agent.tools.tasks
  "The task board tool: task create/list/show/update/claim/close."
  (:require
            [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.store.tasks :as tasks]))

;; --- the task board ----------------------------------------------------------

(defn- task-line [t]
  (str (:id t) " [" (:status t) "/" (:priority t)
       (when-not (= "task" (:type t)) (str " " (:type t)))
       (when (:parent_id t) (str " < " (:parent_id t)))
       "] " (:title t)))

(defn- render-task [conn t]
  (str (task-line t)
       (when (seq (:body t)) (str "\n\n" (:body t)))
       (when (seq (:contract t)) (str "\n\nCONTRACT\n" (:contract t)))
       (when (seq (:tests t)) (str "\n\nTESTS\n" (:tests t)))
       (when-let [kids (seq (tasks/children-of conn (:id t)))]
         (str "\n\nCHILDREN\n" (str/join "\n" (map task-line kids))))))

(def ^:private task-usage
  (str "Actions: create {title, body?, type?, priority?, parentId?, contract?, tests?},"
       " list, show {id}, update {id, ...fields}, claim {id}, close {id, status?}."))

(defmethod base/run-tool "task" [{:keys [branch conn run-id] :as ctx}]
  ;; Every action is `ok` (:neutral) on purpose: working the board is
  ;; bookkeeping, and bookkeeping is not progress — the same reasoning as
  ;; fetch_artifact. Grounding work in tasks is required; credit for the work
  ;; itself comes from artifacts. Bad ids, bad statuses, and unknown actions
  ;; are :mechanics — calls made wrong, not failed lines of inquiry.
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)
        want (fn [k] (let [v (base/arg ctx k)]
                       (when-not (and (some? v) (not (and (string? v) (str/blank? v))))
                         (base/malformed branch (str "`task " action "` needs `" (name k) "`. "
                                                task-usage)))))]
    (try
      (case action
        nil
        (base/malformed branch (str "`task` needs an `action`. " task-usage))

        "create"
        (or (want :title)
            (let [id (tasks/create! conn {:title (base/arg ctx :title)
                                          :body (base/arg ctx :body)
                                          :type (base/arg ctx :type)
                                          :status (base/arg ctx :status)
                                          :priority (base/arg ctx :priority)
                                          :parent-id (base/arg ctx :parentId)
                                          :contract (base/arg ctx :contract)
                                          :tests (base/arg ctx :tests)
                                          :run-id (when-not (base/arg ctx :backlog) run-id)})]
              (base/ok branch (str "Created " (task-line (tasks/get-task conn id))))))

        "list"
        (let [rows (tasks/board conn {:run-id run-id})]
          (base/ok branch (if (seq rows)
                       (str/join "\n" (map task-line rows))
                       "The board is empty.")))

        "show"
        (or (want :id)
            (if-let [t (tasks/get-task conn (base/arg ctx :id))]
              (base/ok branch (render-task conn t))
              (base/malformed branch (str "No task " (base/arg ctx :id) "."))))

        "update"
        (or (want :id)
            (if-not (tasks/get-task conn (base/arg ctx :id))
              (base/malformed branch (str "No task " (base/arg ctx :id) "."))
              (let [t (tasks/update! conn (base/arg ctx :id)
                                     {:title (base/arg ctx :title)
                                      :body (base/arg ctx :body)
                                      :type (base/arg ctx :type)
                                      :status (base/arg ctx :status)
                                      :priority (base/arg ctx :priority)
                                      :parent-id (base/arg ctx :parentId)
                                      :contract (base/arg ctx :contract)
                                      :tests (base/arg ctx :tests)})]
                (base/ok branch (str "Updated " (task-line t))))))

        "claim"
        (or (want :id)
            (if-let [t (tasks/claim! conn (base/arg ctx :id) run-id)]
              (base/ok branch (str "Claimed " (task-line t)))
              (base/malformed branch (str "Cannot claim " (base/arg ctx :id)
                                     ": no such task, or another run holds it."))))

        "close"
        (or (want :id)
            (if-not (tasks/get-task conn (base/arg ctx :id))
              (base/malformed branch (str "No task " (base/arg ctx :id) "."))
              (let [t (tasks/close! conn (base/arg ctx :id) (or (base/arg ctx :status) "done"))]
                (base/ok branch (str "Closed " (task-line t))))))

        (base/malformed branch (str "Unknown task action `" action "`. " task-usage)))
      (catch Throwable e
        ;; Unknown statuses, missing parents: the store's validation errors are
        ;; calls made wrong, and the message already says what was wrong.
        (base/malformed branch (str "`task " action "` refused: " (ex-message e)
                               "\n" task-usage))))))

