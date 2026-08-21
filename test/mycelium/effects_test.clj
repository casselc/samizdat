(ns mycelium.effects-test
  "Cells declare whether they are pure or what they touch. The declaration is
  data for three consumers: a reader of the graph (dot, briefs), the compile
  step (which warns about cells that never said), and the mutation-protocol
  soak (which will stub effectful cells to shadow-run a workflow)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [mycelium.manifest :as manifest]
            [mycelium.workflow :as wf]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(defn- reg! [id opts]
  (cell/defcell id (merge {:doc "a test cell"} opts) (fn [_ d] d)))

;; --- declarations -----------------------------------------------------------

(deftest declaring-purity
  (let [spec (reg! :fx/pure {:pure true})]
    (is (true? (:pure spec)))
    (is (cell/pure? spec))
    (is (= #{} (cell/effects spec)))
    (is (cell/effects-declared? spec))))

(deftest declaring-effects
  (let [spec (reg! :fx/writer {:effects [:fs :net]})]
    (is (not (cell/pure? spec)))
    (is (= #{:fs :net} (cell/effects spec)))
    (is (cell/effects-declared? spec))))

(deftest contradictory-and-malformed-declarations
  (testing "pure and effectful at once is a contradiction, not a merge"
    (is (thrown? Exception (reg! :fx/both {:pure true :effects [:fs]}))))
  (testing "an empty effects list says nothing and must not pass as a declaration"
    (is (thrown? Exception (reg! :fx/empty {:effects []}))))
  (testing "effects are keywords"
    (is (thrown? Exception (reg! :fx/strings {:effects ["fs"]}))))
  (testing "the only honest value for :pure is true — declare effects instead"
    (is (thrown? Exception (reg! :fx/pure-false {:pure false})))))

(deftest undeclared-is-back-compatible
  (let [spec (reg! :fx/legacy {})]
    (is (not (cell/effects-declared? spec)))
    (is (not (cell/pure? spec)) "undeclared must never pass for pure")
    (is (= #{} (cell/effects spec)))))

;; --- compile-time surfacing ---------------------------------------------------

(deftest compile-warns-on-undeclared-cells
  (reg! :fx/a {:pure true})
  (reg! :fx/b {})
  (let [compiled (wf/compile-workflow
                  {:cells {:start :fx/a :next :fx/b}
                   :edges {:start {:go :next} :next :end}
                   :dispatches {:start [[:go (constantly true)]]}})]
    (is (= [{:type :undeclared-effects :cell :next :cell-id :fx/b}]
           (:mycelium/compile-warnings compiled))))
  (testing "a fully declared workflow compiles with no warnings key"
    (reg! :fx/c {:effects [:fs]})
    (is (nil? (:mycelium/compile-warnings
               (wf/compile-workflow
                {:cells {:start :fx/a :next :fx/c}
                 :edges {:start {:go :next} :next :end}
                 :dispatches {:start [[:go (constantly true)]]}}))))))

(deftest workflow-effects-map
  ;; The soak's input: one map from cell name to what running it would touch.
  (reg! :fx/a {:pure true})
  (reg! :fx/b {:effects [:proc]})
  (reg! :fx/c {})
  (is (= {:start {:pure true}
          :run   {:effects #{:proc}}
          :old   {:undeclared true}}
         (wf/workflow-effects
          {:cells {:start :fx/a :run :fx/b :old :fx/c}
           :edges {:start {:go :run} :run {:go :old} :old :end}}))))

;; --- the graph, readable ------------------------------------------------------

(deftest dot-marks-effectful-cells
  (reg! :fx/a {:pure true})
  (reg! :fx/w {:effects [:fs]})
  (reg! :fx/old {})
  (let [dot (dev/workflow->dot {:cells {:start :fx/a :write :fx/w :legacy :fx/old}
                                :edges {:start {:go :write}
                                        :write {:go :legacy}
                                        :legacy :end}})
        line (fn [nm] (first (filter #(str/starts-with? (str/trim %) (str "\"" nm "\" ["))
                                     (str/split-lines dot))))]
    (is (some? (line "write")) "effectful cells carry node attributes")
    (is (str/includes? (line "write") "fs") "the effects are named on the node")
    (is (str/includes? dot "dashed") "undeclared cells are visibly unaccounted for")))

;; --- briefs -------------------------------------------------------------------

(deftest brief-includes-effects
  (reg! :fx/w {:effects [:fs :net]})
  (reg! :fx/p {:pure true})
  (let [m {:id :fx-manifest
           :cells {:work {:id :fx/w :doc "writes things"
                          :schema {:input [:map] :output [:map]}}
                   :calc {:id :fx/p :doc "computes things"
                          :schema {:input [:map] :output [:map]}}}
           :edges {:work {:go :calc} :calc :end}}
        work-brief (manifest/cell-brief m :work)
        calc-brief (manifest/cell-brief m :calc)]
    (is (= #{:fs :net} (:effects work-brief)))
    (is (str/includes? (:prompt work-brief) ":fs"))
    (is (= :pure (:effects calc-brief)))
    (is (str/includes? (:prompt calc-brief) "pure"))))
