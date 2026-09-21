;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.fence-test
  "Parsing a model's tool call out of whatever it actually emitted.

  Every case here was observed in a real run. The parser already tolerates
  several spellings of the wrapper because models mix them; these cover the one
  that cost a live trial most of its turn budget."
  (:require [clojure.test :refer [deftest is]]
            [samizdat.llm.fence :as fence]))

;; --- an <invoke> whose body is JSON -----------------------------------------
;;
;; The third spelling of the same failure the two comments above `parameter-re`
;; describe: the name survives, the arguments do not, and the branch is told an
;; argument is missing while it sits in the body.
;;
;; Observed, not invented. ws-trial-A run 3b5baaf2 turn 1 (2026-09-21): the
;; model opened `<invoke name="read_file">` and wrote `{"path": ...}` as the
;; body. It was answered "read_file needs a `path`.", tried four more wrappers
;; over turns 2-5, and landed its one correct edit on turn 12 of 12 — the run
;; ended `exhausted` on the turn cap with the fix in the tree and nothing
;; shipped.

(deftest an-invoke-body-that-is-json-carries-its-arguments
  (let [turn-1 (str "I'll start by understanding the current implementation and tests.\n\n"
                    "<invoke name=\"read_file\">\n"
                    "{\"path\": \"src/ws_edit/core.clj\"}\n"
                    "</invoke>")
        out (fence/parse-tool-call turn-1)]
    (is (= "read_file" (:name out)))
    (is (= {:path "src/ws_edit/core.clj"} (:args out))
        "the arguments were in the body all along; dropping them told the model
         its call was missing what it had just supplied")))

(deftest parameter-tags-still-win-when-both-are-present
  ;; A model that wrote the tags meant the tags.
  (let [both (str "<invoke name=\"read_file\">\n"
                  "<parameter name=\"path\">from/tags.clj</parameter>\n"
                  "{\"path\": \"from/json.clj\"}\n"
                  "</invoke>")]
    (is (= {:path "from/tags.clj"} (:args (fence/parse-tool-call both))))))

(deftest an-invoke-body-that-is-not-a-json-object-stays-empty
  ;; Silently inventing arguments is worse than having none: it produces a call
  ;; that looks well-formed and is wrong.
  (doseq [body ["just some prose about {braces}"
                "[1, 2, 3]"
                "{not json at all"]]
    (is (= {} (:args (fence/parse-tool-call
                      (str "<invoke name=\"read_file\">\n" body "\n</invoke>"))))
        (str "body: " body))))

(deftest the-whole-observed-opening-sequence-parses-or-is-refused-cleanly
  ;; Turns 1-5 as the model actually wrote them. Turn 1 now carries its path;
  ;; the rest are genuinely unreadable and must stay refused rather than being
  ;; guessed at.
  (let [turn-1 "<invoke name=\"read_file\">\n{\"path\": \"src/ws_edit/core.clj\"}\n</invoke>"
        turn-2 "<task>\n{\"action\": \"create\", \"title\": \"Fix pick\"}\n</task>"
        turn-3 "<invoke>\n{\"name\": \"task\", \"arguments\": {\"action\": \"create\"}}\n</invoke>"]
    (is (= {:path "src/ws_edit/core.clj"} (:args (fence/parse-tool-call turn-1))))
    (is (nil? (:name (fence/parse-tool-call turn-2)))
        "a bare <task> tag is not a tool call and is not treated as one")
    (is (nil? (:name (fence/parse-tool-call turn-3)))
        "<invoke> with no name attribute carries no tool to call")))
