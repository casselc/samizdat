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

(ns samizdat.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.config :as config]))

(deftest glm-uses-the-coding-endpoint
  ;; Aligned with the config dirge drives GLM through: the coding endpoint,
  ;; glm-5.3, low temperature. The coding /models listing advertises the base
  ;; models (glm-4.5/4.6), not the coding alias, but chat accepts glm-5.3.
  (let [cfg (config/load-config {:llm {:provider :glm}})
        {:keys [base-url model temperature]} (:llm cfg)]
    (testing "the provider defaults resolve to dirge's working GLM config"
      ;; provider defaults are read by key-env detection; assert the static
      ;; provider table rather than a live-env-dependent selection.
      (is (= "https://open.bigmodel.cn/api/coding/paas/v4"
             (get-in config/providers-for-test [:glm :base-url])))
      (is (= "glm-5.3" (get-in config/providers-for-test [:glm :model])))
      (is (= 0.2 (get-in config/providers-for-test [:glm :temperature]))))))

(deftest provider-temperature-wins-over-family-default
  ;; A provider that pins a temperature (GLM's 0.2) beats the 0.7 family
  ;; default; a provider that doesn't falls back to 0.7.
  (is (= 0.2 (config/provider-temperature :glm)))
  (is (= 0.7 (config/provider-temperature :deepseek))))

(defn- temp-project-root
  "A temp dir with .samizdat/ created; caller passes config.edn content or nil."
  [edn-content]
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "samizdat-proj-cfg"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        dir (java.io.File. root ".samizdat")]
    (.mkdirs dir)
    (when edn-content
      (spit (java.io.File. dir "config.edn") edn-content))
    root))

(defn- delete-recursively [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-recursively c)))
  (.delete f))

(deftest deep-merge-merges-nested-maps-and-later-wins
  (is (= {:a {:b 2 :c 3} :d 4}
         (config/deep-merge {:a {:b 1 :c 3}} {:a {:b 2} :d 4})))
  ;; non-map collisions: the later value simply replaces
  (is (= {:a 2} (config/deep-merge {:a 1} {:a 2})))
  (is (= {:a [2]} (config/deep-merge {:a {:b 1}} {:a [2]})))
  ;; no later layer leaves the base untouched
  (is (= {:a {:b 1}} (config/deep-merge {:a {:b 1}} {}))))

(deftest project-config-layers-between-defaults-and-overrides
  (let [root (temp-project-root "{ :http { :port 4242 } :llm { :model \"project-model\" } }")]
    (try
      (testing "project-config reads the file"
        (is (= {:http {:port 4242} :llm {:model "project-model"}}
               (config/project-config root))))
      (testing "load-config picks up the project value"
        (let [cfg (config/load-config {:run {:root root}})]
          (is (= 4242 (get-in cfg [:http :port])))
          (is (= "project-model" (get-in cfg [:llm :model])))))
      (testing "an explicit override still beats the project value"
        (let [cfg (config/load-config {:run {:root root} :http {:port 5555}})]
          (is (= 5555 (get-in cfg [:http :port])))
          ;; untouched keys keep the project value
          (is (= "project-model" (get-in cfg [:llm :model])))))
      (finally (delete-recursively (java.io.File. root))))))

(deftest missing-or-broken-project-file-is-ignored
  (testing "absent file"
    (let [root (temp-project-root nil)]
      (try
        (is (= {} (config/project-config root)))
        (is (= 3985 (get-in (config/load-config {:run {:root root}}) [:http :port])))
        (finally (delete-recursively (java.io.File. root))))))
  (testing "garbage EDN"
    (let [root (temp-project-root "{:http {:port ")]
      (try
        (is (= {} (config/project-config root)))
        (finally (delete-recursively (java.io.File. root))))))
  (testing "valid EDN that is not a map"
    (let [root (temp-project-root "[:not :a :map]")]
      (try
        (is (= {} (config/project-config root)))
        (finally (delete-recursively (java.io.File. root)))))))
