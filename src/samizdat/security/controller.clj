;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.controller
  "Trusted-controller budget authority: the one gate that can raise a
  run's max_turns, and the audit that follows it.

  Extension is not a parameter, it is an ACT — the one write in the store
  that spends provider budget under policy — so it answers to a dedicated
  authority rather than to a flag in the request:

    (require '[samizdat.security.controller :as controller])
    (controller/extend-budget!
      (controller/authority config)          ; opaque handle, or nil
      conn
      {:run-id rid :request-id \"req-17\"
       :new-max 200 :reason \"B1 is one lemma from the target\"})

  THE MINTED HANDLE IS THE AUTHORITY. `authority` mints it from trusted
  config (HARNESS_BUDGET_TOKEN, or :controller in .samizdat/config.edn) —
  a place only the local controller reads, never the request path. There
  is deliberately no `{:trusted true}` escape hatch and no string that
  could ride a body: the record is a deftype no JSON or EDN reader will
  produce, so a model turn, an HTTP body, or an intervention payload
  cannot name one into existence. For a JS1 run this is airtight by
  construction — the model evaluates inside SCI and its toolset is closed
  to eval/doc/complete/done.

  Verification is a real check, not a type check. `instance?` alone would
  be forgery-shaped: the record's constructors are ordinary vars, so any
  in-process code could build a record of the right TYPE. The gate
  therefore compares the presented handle's token digest against the
  process-local set of digests `authority` has actually minted from
  trusted config (see minted-token-digests) — a config-derived digest
  check. Only the mint populates that set, nothing exposes it for
  writing, and a digest enters it only by pairing with the configured
  token, so a hand-built record is refused exactly like a map or a
  string. In-process code holding the real config can mint a handle that
  passes; in-process code holding the real config owns the process. That
  boundary is the trust model, stated rather than implied.

  The token never moves. The record carries only a SHA-256 digest of it
  (so even a printed handle leaks nothing usable), the minted policy
  ceiling, and the audit principal label. No journal event, audit row, log
  line, return value, or refusal ever contains the token or the digest;
  the mint registry is process memory and is never journaled either.

  WHAT THE GATE BUYS, in order — every refusal is a map, never a throw,
  because a refused extension is a normal answer for a controller to get:

  - authorization: a nil handle, a non-Authority value, or an Authority
    record whose digest this process never minted from trusted config is
    refused (:unauthorized) with nothing about the handle echoed back;
  - idempotency: a request id that already landed returns the recorded
    outcome (:replayed? true) instead of applying again; the same id naming
    a different extension is refused (:request-conflict) — one id, one act;
  - monotonicity: an extension that does not RAISE the cap is refused
    (:not-monotonic) — the budget never shrinks and never stands still via
    this path;
  - the policy ceiling from the minting config: :over-ceiling past it;
  - the run itself: unknown (:unknown-run), terminal (:terminal-run — an
    aborted run stays aborted, a completed run shipped, same rule as
    resumable?).

  What lands is ONE durable transaction (store.runs/extend-budget!): cap
  raised, exhausted branches reopened, and an append-only retained audit
  row (run, request, principal, old, new, reason, timestamp) — all or
  nothing, surviving restarts and the events retention sweep alike."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" %) bs)))

(defn- sha256
  "SHA-256 hex of a string. One-shot through the MessageDigest shim (the
  same substrate agent.files uses for content coordinates), because the
  digest exists to make the handle inert when held, not to be verified."
  [s]
  (-> (java.security.MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes ^String s "UTF-8"))
      hex))

(defonce ^:private minted-token-digests
  ;; Every token digest `authority` has minted from trusted config in this
  ;; process. This is the gate's answer to instance?-forgery: the record's
  ;; constructors are ordinary vars, so a type check proves nothing, and
  ;; the digest field is caller-controlled data until it is compared
  ;; against SOMETHING. That something is this set: only the mint conjes
  ;; onto it, a digest enters only by pairing with the configured token,
  ;; and nothing reads it except the authorization check — which is what
  ;; makes the check a config-derived comparison rather than a comparison
  ;; of the caller's data against itself. Process memory only, deliberately:
  ;; handles are never serialized, so a digest minted here is verified here,
  ;; and no token or digest is ever journaled, logged, or returned.
  ;; defonce so a namespace reload cannot strand live handles.
  (atom #{}))

(defrecord Authority
  [;; SHA-256 hex of the configured token, registered in
   ;; minted-token-digests at mint time. A digest, not the token: even a
   ;; handle that ends up printed carries nothing its holder could replay
   ;; elsewhere. The extension gate refuses the handle unless this digest
   ;; is in the minted set — so the field exists to be CHECKED against a
   ;; config-derived value, and a record built by hand fails that check.
   ^String token-digest
   ;; The absolute max_turns this authority may grant, or nil for
   ;; unbounded. Minted from config with the token so the policy and the
   ;; permission cannot drift apart between mint and use.
   ceiling
   ;; The audit label recorded on every extension this handle grants.
   principal]
  ;; Printed without fields, both ways: a record's default str/pr show the
  ;; map, and a handle that ends up in a log line or error message should
  ;; read as what it is, not as data about the token.
  Object
  (toString [this]
    (str "#samizdat.security.controller.Authority[" (:principal this) "]")))

(defmethod print-method Authority [a ^java.io.Writer w]
  (.write w (str a)))

(defn authority
  "The budget authority minted from trusted `config`, or nil when the
  controller path is not configured — in which case extension is refused
  EVERYWHERE: no token configured means nothing exists to present, and
  (unlike an EDN flag) absence cannot be talked around.

  `config` is the load-config map (or a project/test map carrying
  {:controller {:budget-token … :budget-ceiling … :budget-principal …}}).
  A blank token is no token.

  Minting registers the token's digest in this process's minted set — the
  act that later lets the gate tell a real handle from a hand-built record
  of the same type. The token itself is not retained anywhere."
  [{:keys [controller] :as _config}]
  (let [tok (:budget-token controller)]
    (when-not (str/blank? tok)
      (let [digest (sha256 tok)]
        (swap! minted-token-digests conj digest)
        (->Authority digest
                     (some-> (:budget-ceiling controller) long)
                     (or (:budget-principal controller) "controller"))))))

(defn- refuse
  [code message & [data]]
  {:ok false :code code :message message :data (or data {})})

(defn- authorized?
  "The verification half of the capability: `a` must be an Authority record
  whose token digest this process actually minted from trusted config.
  instance? alone is not verification — the record's constructors are
  public vars, so type is forgeable — and the digest alone is caller data;
  only membership in the mint set, which nothing but `authority` populates
  and nothing exposes for writing, ties a presented handle to a configured
  token. A nil or blank digest is simply absent from the set."
  [a]
  (and (instance? Authority a)
       (contains? @minted-token-digests (:token-digest a))))

(defn- replay
  "The idempotent answer to a request id that already landed: the recorded
  outcome, marked as a replay so the caller can tell nothing new happened."
  [row]
  {:ok true :replayed? true
   :run-id (:run_id row) :request-id (:request_id row)
   :principal (:principal row)
   :old-max (:old_max_turns row) :new-max (:new_max_turns row)
   :reason (:reason row) :extended-at (:created_at row) :reopened []})

(defn- extend-atomically!
  "The transaction, with its two race outcomes translated: a UNIQUE
  failure on the audit insert means another writer landed this request id
  (read the row back — matching is a replay, anything else a conflict),
  and a lost :stale guard means the cap moved between the caller's read
  and the transaction (:concurrent-raise, with the row as it now stands)."
  [authority conn {:keys [run-id request-id new-max reason principal]} old-max]
  (try
    (let [r (runs/extend-budget!
             conn {:run-id run-id :request-id request-id
                   :principal (or principal (:principal authority))
                   :old-max old-max :new-max new-max :reason reason})]
      (log/info "budget extension" run-id old-max "->" new-max
                "request" request-id
                "principal" (or principal (:principal authority)))
      (assoc r :ok true :replayed? false))
    (catch Throwable e
      (cond
        (db/id-collision? e)
        (if-let [row (runs/extension-audit conn request-id)]
          (if (and (= run-id (:run_id row)) (= new-max (:new_max_turns row)))
            (replay row)
            (refuse :request-conflict
                    (str "request id " request-id " already named a"
                         " different extension; one id, one act")
                    {:recorded {:run-id (:run_id row)
                                :new-max (:new_max_turns row)}}))
          ;; Nothing readable behind a collision we cannot explain: the
          ;; honest answer is the conflict, not a guess.
          (refuse :request-conflict
                  (str "request id " request-id " is already taken")))
        (= :stale (-> e ex-data :budget/error))
        (refuse :concurrent-raise
                (str "the cap of run " run-id " moved before the extension"
                     " landed; read the run and decide again")
                {:now (some-> (runs/get-run conn run-id) :max_turns)})
        :else (throw e)))))

(defn extend-budget!
  "Raise a run's turn cap under the trusted controller's authority.

  `authority` is (authority config) — nil, any other value, or an
  Authority record whose digest this process never minted from trusted
  config is refused. `request` carries :run-id, :request-id (the
  idempotency key: one id names one extension act, ever), :new-max,
  :reason (recorded in the retained audit; an extension without a stated
  reason is not an auditable act), and optionally :principal to override
  the handle's label.

  Returns {:ok true ...} — the change map, :replayed? true for an
  idempotent replay — or {:ok false :code ... :message ...}. Refusals
  never echo the handle, and neither kind of answer carries the token."
  [authority conn {:keys [run-id request-id new-max reason principal]}]
  (if-not (authorized? authority)
    (refuse :unauthorized
            (str "budget extension requires the trusted controller authority"
                 " (samizdat.security.controller/authority); this path is"
                 " closed to request bodies, tools, forged flags, and"
                 " records this process never minted from config"))
    (let [bad (cond
                (or (not (string? run-id)) (str/blank? run-id)) :run-id
                (or (not (string? request-id)) (str/blank? request-id)) :request-id
                (not (and (integer? new-max) (pos? new-max))) :new-max
                (or (not (string? reason)) (str/blank? reason)) :reason)]
      (if bad
        (refuse :bad-request
                (str "an extension needs a non-blank run-id and request-id,"
                     " a positive integer new-max, and a reason; bad field: "
                     (name bad)))
        (if-let [row (runs/extension-audit conn request-id)]
          ;; The idempotency probe, ahead of everything that would write:
          ;; a landed request answers from the record.
          (if (and (= run-id (:run_id row)) (= new-max (:new_max_turns row)))
            (replay row)
            (refuse :request-conflict
                    (str "request id " request-id " already named a"
                         " different extension; one id, one act")
                    {:recorded {:run-id (:run_id row)
                                :new-max (:new_max_turns row)}}))
          (let [run (runs/get-run conn run-id)]
            (cond
              (nil? run)
              (refuse :unknown-run (str "no run " run-id))

              (contains? #{"completed" "aborted"} (:status run))
              (refuse :terminal-run
                      (str "run " run-id " is " (:status run)
                           "; a terminal run's budget is part of its record"))

              (<= new-max (:max_turns run))
              (refuse :not-monotonic
                      (str "an extension must RAISE the cap; run " run-id
                           " is already at " (:max_turns run))
                      {:current (:max_turns run)})

              (and (:ceiling authority) (> new-max (:ceiling authority)))
              (refuse :over-ceiling
                      (str "new-max " new-max " exceeds the policy ceiling "
                           (:ceiling authority))
                      {:ceiling (:ceiling authority)})

              :else (extend-atomically! authority conn
                                       {:run-id run-id :request-id request-id
                                        :new-max new-max :reason reason
                                        :principal principal}
                                       (:max_turns run)))))))))