;; samizdat - a self-hosting agentic harness
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
;;
;; ---------------------------------------------------------------------------
;; Portions of this file are ported from llm-repl
;; (us.whitford.llm-repl.chat-memory and .core), MIT licensed:
;;
;;   The MIT License (MIT)
;;   Copyright (c) 2026, Michael Whitford
;;
;;   Permission is hereby granted, free of charge, to any person obtaining a
;;   copy of this software and associated documentation files (the
;;   "Software"), to deal in the Software without restriction, including
;;   without limitation the rights to use, copy, modify, merge, publish,
;;   distribute, sublicense, and/or sell copies of the Software, and to
;;   permit persons to whom the Software is furnished to do so, subject to
;;   the following conditions:
;;
;;   The above copyright notice and this permission notice shall be included
;;   in all copies or substantial portions of the Software.
;;
;;   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
;;   OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
;;   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
;;   IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
;;   CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
;;   TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
;;   SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
;; ---------------------------------------------------------------------------

(ns samizdat.tape
  "The tape: a branch's message array as an immutable value.

  A model call is a pure function of the message array, so the array is a
  reduction ACCUMULATOR and every interesting thing the harness wants to do to
  a conversation is an operation on a value:

    advance   append the reply            the committed turn
    fork      copy, optionally truncated  two continuations from one prefix
    probe     apply and discard           what would happen, without spending

  Fork is free because the accumulator is immutable — a child shares structure
  with its parent and neither can see the other's growth. That is what makes
  `truncate-at` a real primitive rather than a convenience: the tape is a TREE
  and the conversation is one path through it.

  This namespace is PURE and knows nothing about branches, runs, providers or
  the db. It is mechanism; every policy decision that reads it — how deep to
  fork, when to compact, what to probe — lives in a cell.

  Message shape is samizdat's: {:role \"user\"|\"assistant\"|\"system\"
  :content s}. The compaction bookkeeping keys (:compacted? :declined?
  :original) ride along on the branch's own copy and never reach the wire —
  `llm.message/prepare` projects role and content only.

  Ported from llm-repl's `chat-memory` (the compaction band, the due-set, the
  session fold) and `core` (the fork). See the MIT notice above.

  NOT KEPT, deliberately: the compaction SCHEDULER — `compact-next`,
  `needs-compaction?`, `backlog-count`, `declined-count` — and
  `assistant-count`. The scheduler exists upstream because llm-repl's compactor
  asks a MODEL for one summary at a time, so something has to ask whether a
  message is due and how deep the backlog is. samizdat's compactor is
  deterministic and rewrites every due message in one pass
  (llm.message/compact), so there is nothing to schedule, and nothing read
  them. `assistant-count` was written for a fork counter that ended up derived
  from the `:turn` stamps instead. All five are in llm-repl and in this file's
  git history if a model-based compactor lands. RFC-004 F1."
  (:require [clojure.string :as str]))

;; --- the value ---------------------------------------------------------------

(defn message
  "A fresh, not-yet-compacted message.

  `meta` is optional provenance merged onto it. `{:turn n}` is the one that
  earns its keep: compaction replaces a message with a summary of the turn it
  belongs to, and the positional guess that stood in for this was not sound —
  a provider error or a no-call turn appends messages without appending a turn
  row, so the k-th message is not the k-th turn."
  ([role content] (message role content nil))
  ([role content meta]
   (merge {:role (if (keyword? role) (name role) (str role))
           :content content}
          (not-empty meta))))

(defn append
  "Append one message. Returns a vector, so a nil or seq tape normalizes."
  ([messages role content] (append messages role content nil))
  ([messages role content meta]
   (conj (vec messages) (message role content meta))))

(defn append-user
  ([messages content] (append messages "user" content))
  ([messages content meta] (append messages "user" content meta)))

(defn append-assistant
  ([messages content] (append messages "assistant" content))
  ([messages content meta] (append messages "assistant" content meta)))

(defn depth
  "How many messages the tape carries. The number a fork depth is expressed
  in — 2 per exchange, which is why `truncate-at` counts messages rather than
  turns."
  [messages]
  (count messages))


(defn truncate-at
  "The tape as it was after its first `n` messages — the fork primitive.

  `n` nil, negative or past the end returns the tape unchanged, so a caller
  that has no depth policy gets the whole prefix and a caller with a stale one
  cannot produce a longer tape than existed."
  [messages n]
  (let [v (vec messages)]
    (if (and (integer? n) (<= 0 n) (< n (count v)))
      (subvec v 0 n)
      v)))

;; --- compaction: the band ----------------------------------------------------
;;
;; Ported wholesale from llm-repl's chat-memory, whose design note is the
;; argument for it. The shape (roles, order, count) is what keeps the upstream
;; prefix cache warm, so compaction replaces an assistant message's content IN
;; PLACE, once, as it ages out of a small verbatim window — never rewrites the
;; frame, never inserts a message, never changes the alternation.

(def default-floor
  "The overhead FLOOR, in characters: the length below which compaction is not
  compression but formalization.

  Naming a turn's essence costs characters no matter how short the turn was,
  so there is a length below which no output can beat its input (pigeonhole:
  no compressor compresses everything). llm-repl MEASURED this rather than
  guessing — their compactor's output for trivial turns ran 30-46 chars — and
  120 leaves room for a real summary line while staying far under any message
  worth compressing."
  120)

(defn- assistant-indices
  "Indices of assistant messages, ascending."
  [messages]
  (vec (keep-indexed (fn [i m] (when (= "assistant" (:role m)) i)) messages)))

(defn window-index
  "The index where the last-`k` VERBATIM window begins — everything from here
  on is recent enough to keep whole.

  Counted in assistant turns, because an assistant message is the tape's own
  unit of a turn and is the one thing appended exactly once per model reply.
  Counting in message positions instead would shift the boundary every time
  the harness inserted a message of its own, which it does (a phase valve, a
  provider-error note, a steer).

  Extended one message earlier when the k-th-from-last assistant reply is
  immediately preceded by the user turn that prompted it, so an exchange is
  never split down the middle. `nil` when there are k or fewer assistant turns
  — nothing has aged out yet."
  [messages k]
  (let [a-idxs (assistant-indices messages)]
    (when (> (count a-idxs) k)
      (let [a (nth a-idxs (- (count a-idxs) k))]
        (if (and (pos? a) (= "user" (:role (nth messages (dec a)))))
          (dec a)
          a)))))

(def default-roles
  "Which roles compaction may rewrite, by default.

  llm-repl compacts assistant turns only, because there a user turn is a
  short human prompt that anchors the dialogue. In samizdat a \"user\" message
  is usually a TOOL RESULT — the largest thing in the context by a wide
  margin — so a caller that wants real compression passes both roles and
  protects its own frame by index. Assistant-only stays the default so the
  ported semantics are what you get unless you ask for more."
  #{"assistant"})

(defn due-indices
  "THE due-set, ascending — one definition, every consumer.

  DUE means a message that has aged out of the last-`k` verbatim window, whose
  role compaction may rewrite, and which is still a CANDIDATE: neither
  compacted, nor declined, nor PINNED. Oldest first, so a backlog drains in
  order.

  llm-repl had this duplicated across two functions and recorded what that
  cost: adding `:declined?` to one and not the other is exactly how the
  scheduler comes to disagree with what it is showing you.

  The caller owns its FRAME. This function knows nothing about which leading
  messages are load-bearing, so a caller passing \"user\" in `roles` must drop
  the indices it cannot afford to lose."
  ([messages k] (due-indices messages k default-roles))
  ([messages k roles]
   (if-let [start (window-index messages k)]
     (vec (keep-indexed (fn [i m]
                          (when (and (< i start)
                                     (contains? roles (:role m))
                                     (not (:compacted? m))
                                     (not (:declined? m))
                                     ;; A PINNED message is never unloaded. The
                                     ;; current task's statement is pinned: it
                                     ;; is the thing the branch is working on,
                                     ;; so summarising it away as it ages is
                                     ;; precisely backwards — it matters more
                                     ;; the longer the task runs.
                                     (not (:pinned? m)))
                            i))
                        messages))
     [])))

(defn next-to-compact
  "Index of the message due for compaction, or nil."
  ([messages k] (first (due-indices messages k)))
  ([messages k roles] (first (due-indices messages k roles))))




(defn within-band?
  "THE COMPRESSION BAND: `|new| <= max(|original|, floor)` — a message may grow
  UP TO the floor, never past it. Blank is always outside; a failed compaction
  is not a memory.

  Why a band and not `strictly shorter`: the ratchet is a per-item SAFETY
  property standing in for a GLOBAL objective (total context under budget),
  and the two come apart at the small end where the fixed overhead of naming a
  turn exceeds the prose it replaces. There the ratchet's solution set is
  EMPTY, so the compactor can never satisfy it and the scheduler re-derives
  the same job forever — llm-repl logged 31 attempts against one 26-char
  message. The aggregate is dominated by large turns, which compress well;
  small ones can only move the total by tens of characters, bounded by
  n*floor. Local slack, global feedback.

  The CEILING is still the tripwire. A replacement 20x the size of what it
  replaces is not \"a bit bigger\", it is the echo failure mode — the model
  restating its instructions and calling it a summary."
  [replacement original floor]
  (and (not (str/blank? replacement))
       (<= (count replacement) (max (count original) (or floor default-floor)))))

(defn compact-at
  "Replace the message at index `i` with `replacement`, in place.

  `opts`: `:floor` (band floor, default `default-floor`) and `:roles` (which
  roles may be rewritten, default `default-roles`).

  THREE outcomes, and every one of them CHANGES THE ARRAY — which is what
  makes a loop impossible. Termination needs a well-founded measure to
  decrease on every attempt, and a rejection that marks nothing decrements
  nothing:

    accept  — within the band: content replaced, `:compacted? true`, and the
              original retained as `:original` (the human/journal record; it
              never reaches the wire)
    decline — past the ceiling: `:declined? true`, content untouched. The
              message leaves the due-set FOREVER — an immutable input and a
              pure length comparison make that a negative cache entry with a
              correct infinite TTL.
    no-op   — index absent, out of range, a role compaction may not rewrite,
              or already settled

  One attempt per message, ever. The cost asymmetry demands it: a false
  permanent costs one slightly longer message in context, a false transient
  costs an unbounded loop."
  ([messages i replacement] (compact-at messages i replacement nil))
  ([messages i replacement {:keys [floor roles]}]
   (let [messages (vec messages)
         roles (or roles default-roles)
         replacement (some-> replacement str str/trim)
         m (when (and (integer? i) (< -1 i (count messages)))
             (nth messages i))]
     (cond
       (or (nil? m)
           (not (contains? roles (:role m)))
           (:compacted? m)
           (:declined? m))
       messages

       (within-band? replacement (str (:content m)) floor)
       (assoc messages i (assoc m :content replacement
                                :original (:content m)
                                :compacted? true))

       :else
       (assoc messages i (assoc m :declined? true))))))


;; --- the session fold --------------------------------------------------------
;;
;; Per-message compaction shrinks the tokens WITHIN a message; the fold shrinks
;; the NUMBER of messages, at the one point where the array's shape stops being
;; load-bearing: the session boundary. Within a session the shape is what keeps
;; the prefix cache stable. Across a boundary the dialogue rhythm of a finished
;; conversation is dead weight — only the essence plus a verbatim tail needs to
;; travel.

(defn fold-split
  "Split a finished tape for the boundary fold: `{:head :tail}`, where :head is
  the fold target and :tail crosses verbatim.

  The tail is the last-`k` verbatim window — the same boundary compaction
  uses within a session, which is the point: what travels whole across a
  boundary is what would have stayed whole inside one. Fewer than k+1
  assistant messages means there is nothing to fold, and the tape seeds
  as-is."
  [messages k]
  (let [messages (vec messages)]
    (if-let [start (window-index messages k)]
      {:head (subvec messages 0 start)
       :tail (subvec messages start)}
      {:head [] :tail messages})))

(defn fold-input
  "The fold target as role-tagged dialogue text — what a compactor reads. Head
  messages are mostly already compacted (per-message compaction ran during the
  session), so a fold is largely summary-of-summaries."
  [head]
  (str/join "\n" (map (fn [{:keys [role content]}]
                        (str role ": " content))
                      head)))

(defn fold-message
  "The single assistant message carrying a prior session's folded essence.
  Marked `:compacted?` so per-message compaction never re-targets it."
  [session-id summary]
  {:role "assistant"
   :content (str "session(" session-id ") ⊢\n" summary)
   :compacted? true})

(defn apply-fold
  "Fold `messages` into [fold-block ⊕ tail] under the COMPRESSION CONTRACT: the
  block, header included, must be STRICTLY SHORTER than the head text it
  replaces, else the fold is rejected and the tape seeds unfolded.

  Returns `{:messages :folded?}`. A blank summary, or a session too short to
  fold, rejects safely — the unfolded array is always correct, just larger."
  [messages k session-id summary]
  (let [{:keys [head tail]} (fold-split messages k)
        summary (some-> summary str str/trim)
        head-size (reduce + 0 (map (comp count str :content) head))
        block (when-not (str/blank? summary) (fold-message session-id summary))]
    (if (and (seq head) block (< (count (:content block)) head-size))
      {:messages (into [block] tail) :folded? true}
      {:messages (vec messages) :folded? false})))
