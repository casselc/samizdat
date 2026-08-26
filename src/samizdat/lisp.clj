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

(ns samizdat.lisp
  "Delimiter repair for the Clojure the agent writes.

  Models drop trailing parens — the single most common way a written-out
  Clojure form fails to load. Ported in spirit from dirge's repair_delimiters
  (semantic/syntax_validator.rs): close a TRAILING truncation by appending the
  missing closers innermost-first, and trim a TRAILING over-close by removing
  stray closers; do neither for a MID-FILE imbalance, because closing it would
  re-parent unrelated code and any balanced arrangement parses, so the mistake
  would go silent. The scan is lexer-aware — a delimiter inside a string, a
  char literal, or a line comment does not count — and every repair is
  re-validated by actually reading it.

  For a lisp this is the whole story, as dirge's comment puts it: any balanced
  paren arrangement reads, so getting the balance right is getting it right."
  (:require [clojure.string :as str]))

(def ^:private opener->closer {\( \), \[ \], \{ \}})
(def ^:private closer->opener {\) \(, \] \[, \} \{})

(defn- string-end
  "The index just past the closing quote of a string that opens at `i` (where
  `(nth s i)` is the opening `\"`), honoring `\\\"` escapes. Returns the length
  of `s` when the string is unterminated."
  [s n i]
  (loop [j (inc i)]
    (cond
      (>= j n) n
      (= (nth s j) \\) (recur (+ j 2))
      (= (nth s j) \") (inc j)
      :else (recur (inc j)))))

(defn- escaped?
  "Whether the character at `i` is preceded by an odd run of backslashes —
  escaped, so not a real delimiter. The run must be counted, not just the one
  character before: an escaped backslash before a quote leaves the quote real,
  while an escaped quote leaves no string terminator at all (provenance A-5,
  docs/provenance.md)."
  [s i]
  (loop [j (dec i), run 0]
    (if (or (neg? j) (not= \\ (nth s j)))
      (odd? run)
      (recur (dec j) (inc run)))))

(defn scan
  "Lexer-aware delimiter scan of Clojure source. Returns one of:
    {:balance :balanced}
    {:balance :unclosed :stack [[opener idx] ...]}   ; openers with no closer
    {:balance :stray :at idx :char c}                ; a closer with no opener
    {:balance :mismatch :at idx :expected c :got c}  ; wrong closer
    {:balance :unterminated-string :at idx}          ; a string with no close
  Delimiters inside strings, char literals (\\x), and line comments (;…) are
  skipped, so they never count toward the balance."
  [s]
  (let [n (count s)]
    (loop [i 0, stack []]              ; stack of [opener-char index]
      (if (>= i n)
        (if (empty? stack) {:balance :balanced} {:balance :unclosed :stack stack})
        (let [c (nth s i)]
          (cond
            ;; char literal: backslash consumes the next char (which may be a
            ;; delimiter, e.g. \( ) — skip both.
            (= c \\) (recur (+ i 2) stack)

            ;; string: jump past it. An unterminated string is its own status,
            ;; not a delimiter imbalance.
            (= c \") (let [e (string-end s n i)]
                       (if (>= e n)
                         ;; string-end returns n both when the final quote
                         ;; closes the string at EOF and when it is ESCAPED —
                         ;; only an unescaped one actually closed it (provenance A-5).
                         (if (and (> e 0) (= (nth s (dec n)) \") (not (escaped? s (dec n))))
                           (recur n stack)               ; closed exactly at EOF
                           {:balance :unterminated-string :at i})
                         (recur e stack)))

            ;; line comment: skip to end of line.
            (= c \;) (let [nl (str/index-of s "\n" i)]
                       (if nl (recur (inc nl) stack) (recur n stack)))

            (opener->closer c) (recur (inc i) (conj stack [c i]))

            (closer->opener c)
            (if-let [[open _] (peek stack)]
              (if (= open (closer->opener c))
                (recur (inc i) (pop stack))
                {:balance :mismatch :at i :expected (opener->closer open) :got c})
              {:balance :stray :at i :char c})

            :else (recur (inc i) stack)))))))

(defn- reads?
  "Whether `content` parses as a sequence of Clojure forms. Wrapped in (do …)
  so multiple top-level forms are allowed; reading does not evaluate, so this
  only checks that it is well-formed."
  [content]
  (try (read-string (str "(do\n" content "\n)")) true
       (catch Throwable _ false)))

(defn balance
  "Repair a trailing delimiter imbalance in Clojure source.

  Returns {:status :balanced :content s} when it already reads,
          {:status :repaired :content s' :note \"…\"} when a trailing
            truncation or over-close was mechanically fixed and the result
            reads,
          {:status :unbalanced :note \"…\"} when the imbalance is mid-file or a
            mismatch — left for the model, never silently rewritten."
  [s]
  (let [{:keys [balance stack at] :as sc} (scan s)]
    (case balance
      :balanced {:status :balanced :content s}

      :unclosed
      ;; A trailing truncation: append the missing closers, innermost first.
      ;; Guard: it must actually read afterwards (a mid-file unclosed that
      ;; happens to balance by appending would fail this or re-parent, and an
      ;; unterminated string is not repairable by appending closers).
      (if (every? opener->closer (map first stack))
        (let [closers (apply str (map (comp opener->closer first) (reverse stack)))
              repaired (str s closers)]
          (if (reads? repaired)
            {:status :repaired :content repaired
             :note (str "auto-closed " (count stack) " unclosed delimiter(s) at a"
                        " trailing truncation: appended `" closers "`. If that"
                        " placement is wrong, resend the corrected text.")}
            {:status :unbalanced
             :note "the text has unclosed delimiters that do not resolve by closing at the end."}))
        {:status :unbalanced
         :note "an unterminated string or an unrecognized opener; cannot auto-close."})

      :stray
      ;; Only a TRAILING run of stray closers is removable — a mid-file stray
      ;; (real code after it) is never touched. Trim closer-by-closer from the
      ;; end, re-scanning, and require the result to read.
      (let [trimmed (loop [cur s, removed 0]
                      (when (< removed 256)
                        (let [end (count (str/trimr cur))]
                          (cond
                            (zero? end) nil
                            (not (closer->opener (nth cur (dec end)))) nil ; non-closer at end → mid-file stray
                            :else
                            (let [cut (str (subs cur 0 (dec end)) (subs cur end))]
                              (case (:balance (scan cut))
                                :balanced (when (reads? cut) [cut (inc removed)])
                                :stray (recur cut (inc removed))
                                nil))))))]
        (if trimmed
          {:status :repaired :content (first trimmed)
           :note (str "auto-removed " (second trimmed) " extra trailing closing"
                      " delimiter(s) to balance the text. If that placement is"
                      " wrong, resend the corrected text.")}
          {:status :unbalanced
           :note (str "an extra closing delimiter at position " at
                      " with code after it — remove it yourself so the"
                      " surrounding forms are not re-parented.")}))

      :mismatch
      {:status :unbalanced
       :note (str "a mismatched delimiter at position " (:at sc) ": expected `"
                  (:expected sc) "` but found `" (:got sc) "`.")}

      :unterminated-string
      {:status :unbalanced
       :note (str "an unterminated string starting at position " (:at sc)
                  "; close the string yourself.")})))
