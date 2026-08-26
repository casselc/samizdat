;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.tape-test
  "The tape as a value: fork by truncation, and compaction in place.

  The properties worth pinning are the ones llm-repl learned by losing them:
  a fork re-derives its counter from the tape rather than trusting its
  parent's, compaction changes the array on EVERY outcome (so it cannot
  loop), and a declined message never returns to the due-set."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.tape :as tape]))

(defn- tape-of
  "n exchanges: user/assistant pairs with distinguishable content."
  [n]
  (vec (mapcat (fn [i]
                 [{:role "user" :content (str "u" i)}
                  {:role "assistant" :content (str "a" i " " (apply str (repeat 200 "x")))}])
               (range n))))

;; --- the value ---------------------------------------------------------------

(deftest append-normalizes-role-and-shape
  (is (= [{:role "user" :content "hi"}]
         (tape/append-user [] "hi")))
  (is (= [{:role "assistant" :content "yo"}]
         (tape/append-assistant nil "yo"))
      "a nil tape normalizes to a vector rather than throwing")
  (is (= "user" (:role (tape/message :user "kw roles become strings")))))

(deftest truncate-at-is-the-fork-primitive
  (let [t (tape-of 3)]
    (testing "a prefix of the parent, sharing its content exactly"
      (is (= (subvec t 0 4) (tape/truncate-at t 4))))
    (testing "the parent is untouched — the accumulator is a value"
      (tape/truncate-at t 2)
      (is (= 6 (count t))))
    (testing "a depth policy that is absent, stale or negative cannot lengthen the tape"
      (is (= t (tape/truncate-at t nil)))
      (is (= t (tape/truncate-at t 99)))
      (is (= t (tape/truncate-at t -1))))))


;; --- the band ----------------------------------------------------------------

(deftest band-admits-growth-up-to-the-floor
  (testing "a short message may grow to the floor — the ratchet's solution set was empty here"
    (is (tape/within-band? (apply str (repeat 100 "y")) "tiny" 120))
    (is (not (tape/within-band? (apply str (repeat 121 "y")) "tiny" 120))))
  (testing "a long message is bounded by its own length, not the floor"
    (is (tape/within-band? (apply str (repeat 300 "y"))
                           (apply str (repeat 400 "y")) 120)))
  (testing "blank is never within the band — a failed compaction is not a memory"
    (is (not (tape/within-band? "" "anything" 120)))
    (is (not (tape/within-band? "   " "anything" 120)))))

;; --- compaction in place -----------------------------------------------------

(deftest compaction-preserves-the-array-shape
  (let [t (tape-of 5)
        i (tape/next-to-compact t 2)
        t' (tape/compact-at t i "did the thing")]
    (is (= (count t) (count t'))
        "count unchanged — the shape is what keeps the prefix cacheable")
    (is (= (mapv :role t) (mapv :role t'))
        "roles and order unchanged, so alternation holds with no provider forgiveness")
    (is (= (subvec t 0 i) (subvec t' 0 i))
        "everything BEFORE the compacted message is byte-identical — the frame never moves")))

(deftest compaction-retains-the-original-and-never-ships-it
  (let [t (tape-of 4)
        i (tape/next-to-compact t 1)
        t' (tape/compact-at t i "summary")
        m (nth t' i)]
    (is (= "summary" (:content m)))
    (is (true? (:compacted? m)))
    (is (re-find #"^a0" (:original m)) "the prose is kept as the record")))

(deftest every-outcome-changes-the-array
  (testing "accept marks compacted, decline marks declined — a rejection that marks nothing loops"
    (let [t (tape-of 3)
          i (tape/next-to-compact t 1)
          accepted (tape/compact-at t i "short")
          declined (tape/compact-at t i (apply str (repeat 5000 "z")))]
      (is (true? (:compacted? (nth accepted i))))
      (is (true? (:declined? (nth declined i))))
      (is (= (:content (nth t i)) (:content (nth declined i)))
          "a decline leaves the content alone")
      (is (not= t accepted))
      (is (not= t declined)))))

(deftest a-declined-message-never-returns-to-the-due-set
  (let [t (tape-of 3)
        i (tape/next-to-compact t 1)
        t' (tape/compact-at t i (apply str (repeat 5000 "z")))]
    (is (not= i (tape/next-to-compact t' 1))
        "one attempt per message, ever: a false transient costs an unbounded loop")
    (is (true? (:declined? (nth t' i))))))




;; --- the session fold --------------------------------------------------------

(deftest fold-split-keeps-the-tail-exchange-whole
  (let [t (tape-of 4)
        {:keys [head tail]} (tape/fold-split t 1)]
    (is (= (count t) (+ (count head) (count tail))))
    (is (= "user" (:role (first tail)))
        "the tail starts at the user turn that prompted the kept assistant reply")
    (is (= 2 (count tail)))))

(deftest a-session-too-short-to-fold-seeds-as-is
  (let [t (tape-of 1)
        {:keys [head tail]} (tape/fold-split t 2)]
    (is (= [] head))
    (is (= t tail))))

(deftest fold-accepts-only-a-strictly-shorter-block
  (let [t (tape-of 4)]
    (testing "a short summary folds"
      (let [{:keys [messages folded?]} (tape/apply-fold t 1 "run-7" "proved the bound")]
        (is folded?)
        (is (< (count messages) (count t)))
        (is (true? (:compacted? (first messages)))
            "the fold block is never re-targeted by per-message compaction")
        (is (re-find #"session\(run-7\)" (:content (first messages))))))
    (testing "a summary bigger than what it replaces rejects, and the tape is unchanged"
      (let [{:keys [messages folded?]}
            (tape/apply-fold t 1 "run-7" (apply str (repeat 99999 "z")))]
        (is (not folded?))
        (is (= t messages))))
    (testing "a blank summary rejects safely"
      (is (= t (:messages (tape/apply-fold t 1 "run-7" "  ")))))))

(deftest fold-input-renders-role-tagged-dialogue
  (is (= "user: u0\nassistant: a0"
         (tape/fold-input [{:role "user" :content "u0"}
                           {:role "assistant" :content "a0"}]))))

(deftest the-window-protects-recent-turns
  ;; The due-set is what compaction schedules from, and it is the only
  ;; scheduler surface this harness keeps — the compactor rewrites every due
  ;; message in one pass, so there is no "next one" to ask for (RFC-004 F1).
  (let [t (tape-of 4)]
    (is (= 2 (count (tape/due-indices t 2)))
        "4 assistant turns, 2 inside the verbatim window, 2 due")
    (is (= 3 (count (tape/due-indices t 1))))
    (is (empty? (tape/due-indices (tape-of 1) 2))
        "a tape inside the window has nothing due")))

(deftest compacting-every-due-message-touches-each-once
  ;; What llm.message/compact does: fold compact-at over the whole due set,
  ;; rather than draining a backlog one summary at a time.
  (let [t (tape-of 5)
        due (tape/due-indices t 2)
        out (reduce (fn [ms i] (tape/compact-at ms i (str "s" i))) t due)]
    (is (= 3 (count due)) "5 assistant turns, 2 inside the window")
    (is (= (count t) (count out)) "shape preserved")
    (is (= 3 (count (filter :compacted? out))))
    (is (empty? (tape/due-indices out 2))
        "and nothing is left due, so a second pass is a no-op")))

(deftest a-zero-window-ages-out-the-whole-tape
  ;; :context-budget :keep-pairs is runtime-editable and 0 is a coherent
  ;; setting — keep nothing verbatim, compact everything. window-index used to
  ;; nth one past the end of the assistant indices for it, throwing out of
  ;; infer/render on every model call of every run until the edit was
  ;; reverted (karamazov-blt.32).
  (let [t [(tape/message "system" "s") (tape/message "user" "p")
           (tape/message "user" "q1") (tape/message "assistant" "a1")
           (tape/message "user" "q2") (tape/message "assistant" "a2")]]
    (is (= (count t) (tape/window-index t 0))
        "an empty verbatim window begins past the end of the tape")
    (is (= [3 5] (tape/due-indices t 0))
        "every assistant message has aged out")
    (is (nil? (tape/window-index [] 0))
        "no assistant turns still means nothing has aged out")))
