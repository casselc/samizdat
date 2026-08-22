;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.agent.tools.messages
  "The mailbox tool: message send {to?, body} and message inbox.

  send leaves a durable note for another branch (or, with :to omitted, a
  broadcast to the whole run). inbox lists this branch's unread messages
  and marks them read — reading via the tool is what consumes; the context
  block only previews. from is this branch's id and run-id is the run's,
  both from ctx — a branch cannot spoof a sender."
  (:require [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.store.messages :as messages]))

(def ^:private message-usage
  (str "Actions: send {to?, body} (to omitted = broadcast), inbox. "
       "`to` is a branch id like b2; broadcast reaches every branch but you."))

(defn- render-message
  [m]
  (str (:id m) " from " (:from_branch m)
       " -> " (or (:to_branch m) "all")
       "\n  " (:body m)))

(defmethod base/run-tool "message" [{:keys [branch conn run-id] :as ctx}]
  ;; Both actions are :neutral bookkeeping, like the task board: leaving and
  ;; reading messages coordinates branches but is not itself progress. A
  ;; missing body and unknown actions are :mechanics.
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)
        body (base/arg ctx :body)
        to (base/arg ctx :to)]
    (try
      (case action
        nil
        (base/malformed branch (str "`message` needs an `action`. " message-usage))

        "send"
        (if (or (nil? body) (str/blank? (str body)))
          (base/malformed branch (str "`message send` needs a `body`. " message-usage))
          (let [id (messages/send! conn {:run-id run-id :from branch :to to :body body})]
            (base/ok branch
                     (str "Sent " id
                          (if (str/blank? (str to)) " (broadcast)" (str " to " to))
                          "."))))

        "inbox"
        (let [rows (messages/inbox conn run-id branch)]
          (if (seq rows)
            (let [n (messages/mark-read! conn (mapv :id rows))]
              (base/ok branch
                       (str (str/join "\n" (map render-message rows))
                            "\n(" n " marked read)")))
            (base/ok branch "Inbox is empty.")))

        (base/malformed branch (str "Unknown action `" action "`. " message-usage)))
      (catch Exception e
        (base/malformed branch (str "message failed: " (.getMessage e)))))))
