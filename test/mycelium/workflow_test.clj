(ns mycelium.workflow-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Helpers ---
(defn- register-cells! []
  (cell/register-cell!
   {:id :test/cell-a
    :handler (fn [_ data] (assoc data :a-done true :mycelium/transition :success))
    :schema {:input [:map [:x :int]]
             :output [:map [:a-done :boolean]]}
    :transitions #{:success :failure}})
  (cell/register-cell!
   {:id :test/cell-b
    :handler (fn [_ data] (assoc data :b-done true :mycelium/transition :success))
    :schema {:input [:map [:a-done :boolean]]
             :output [:map [:b-done :boolean]]}
    :transitions #{:success}})
  (cell/register-cell!
   {:id :test/cell-c
    :handler (fn [_ data] (assoc data :c-done true :mycelium/transition :done))
    :schema {:input [:map [:b-done :boolean]]
             :output [:map [:c-done :boolean]]}
    :transitions #{:done}}))

;; ===== resolve-state-id tests =====

(deftest resolve-state-id-test
  (testing "resolve-state-id maps special keywords to Maestro reserved states"
    (is (= ::fsm/start (wf/resolve-state-id :start)))
    (is (= ::fsm/end (wf/resolve-state-id :end)))
    (is (= ::fsm/error (wf/resolve-state-id :error)))
    (is (= ::fsm/halt (wf/resolve-state-id :halt))))
  (testing "resolve-state-id namespaces plain keywords"
    (let [resolved (wf/resolve-state-id :validate)]
      (is (qualified-keyword? resolved))
      (is (= "mycelium.workflow" (namespace resolved))))))

;; ===== compile-edges tests =====

(deftest compile-edges-map-test
  (testing "compile-edges with map → correct dispatch predicates"
    (let [edges {:success :validate, :failure :error}
          dispatches (wf/compile-edges edges)]
      (is (= 2 (count dispatches)))
      ;; Each dispatch is [target-id pred-fn]
      (let [[target pred] (first dispatches)]
        (is (qualified-keyword? target))
        (is (true? (pred {:mycelium/transition :success})))
        (is (false? (pred {:mycelium/transition :failure})))))))

(deftest compile-edges-keyword-test
  (testing "compile-edges with keyword → unconditional dispatch"
    (let [dispatches (wf/compile-edges :end)]
      (is (= 1 (count dispatches)))
      (let [[target pred] (first dispatches)]
        (is (= ::fsm/end target))
        (is (true? (pred {})))
        (is (true? (pred {:anything "works"})))))))

;; ===== Linear workflow compilation =====

(deftest linear-workflow-compiles-test
  (testing "Linear workflow (A→B→C→end) compiles to valid Maestro spec"
    (register-cells!)
    (let [workflow {:cells {:start :test/cell-a
                           :step-b :test/cell-b
                           :step-c :test/cell-c}
                    :edges {:start  {:success :step-b, :failure :error}
                            :step-b {:success :step-c}
                            :step-c {:done :end}}}
          compiled (wf/compile-workflow workflow)]
      (is (some? compiled))
      (is (map? compiled)))))

;; ===== Validation: missing cell =====

(deftest validate-catches-missing-cell-test
  (testing "Validate catches missing cell in registry"
    ;; Don't register any cells
    (is (thrown-with-msg? Exception #"not found"
          (wf/compile-workflow
           {:cells {:start :test/nonexistent}
            :edges {:start :end}})))))

;; ===== Validation: unreachable cell =====

(deftest validate-catches-unreachable-cell-test
  (testing "Validate catches unreachable cell"
    (register-cells!)
    (is (thrown-with-msg? Exception #"[Uu]nreachable"
          (wf/compile-workflow
           {:cells {:start    :test/cell-a
                    :step-b   :test/cell-b
                    :orphan   :test/cell-c}
            :edges {:start  {:success :step-b, :failure :error}
                    :step-b {:success :end}}})))))

;; ===== Validation: missing edge branch =====

(deftest validate-catches-missing-edge-branch-test
  (testing "Validate catches missing edge branch (cell declares transition not in edges)"
    (register-cells!)
    ;; cell-a declares #{:success :failure} but edges only cover :success
    (is (thrown-with-msg? Exception #"transition"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :edges {:start {:success :end}}})))))

;; ===== Schema chain validation =====

(deftest schema-chain-valid-test
  (testing "Schema chain validation passes valid chain"
    (register-cells!)
    (let [workflow {:cells {:start  :test/cell-a
                            :step-b :test/cell-b
                            :step-c :test/cell-c}
                    :edges {:start  {:success :step-b, :failure :error}
                            :step-b {:success :step-c}
                            :step-c {:done :end}}}]
      ;; Should not throw
      (is (some? (wf/compile-workflow workflow))))))

(deftest schema-chain-catches-missing-key-test
  (testing "Schema chain validation catches missing upstream key with detailed error"
    (cell/register-cell!
     {:id :test/needs-z
      :handler (fn [_ data] (assoc data :w true :mycelium/transition :ok))
      :schema {:input [:map [:z :string]]
               :output [:map [:w :boolean]]}
      :transitions #{:ok}})
    (cell/register-cell!
     {:id :test/produces-a
      :handler (fn [_ data] (assoc data :a-val 1 :mycelium/transition :next))
      :schema {:input [:map [:x :int]]
               :output [:map [:a-val :int]]}
      :transitions #{:next}})
    (is (thrown-with-msg? Exception #"[Ss]chema chain"
          (wf/compile-workflow
           {:cells {:start  :test/produces-a
                    :step-2 :test/needs-z}
            :edges {:start  {:next :step-2}
                    :step-2 {:ok :end}}})))))

;; ===== Branching workflow =====

(deftest branching-workflow-compiles-test
  (testing "Branching workflow compiles correctly"
    (register-cells!)
    ;; Register a cell compatible with branch from :start (needs :a-done from cell-a output)
    (cell/register-cell!
     {:id :test/cell-d
      :handler (fn [_ data] (assoc data :d-done true :mycelium/transition :done))
      :schema {:input [:map [:a-done :boolean]]
               :output [:map [:d-done :boolean]]}
      :transitions #{:done}})
    (let [workflow {:cells {:start  :test/cell-a
                            :path-b :test/cell-b
                            :path-d :test/cell-d}
                    :edges {:start  {:success :path-b, :failure :path-d}
                            :path-b {:success :end}
                            :path-d {:done :end}}}
          compiled (wf/compile-workflow workflow)]
      (is (some? compiled)))))

;; ===== Invalid edge target =====

(deftest invalid-edge-target-test
  (testing "Invalid edge target (referencing non-existent cell name) fails"
    (register-cells!)
    (is (thrown-with-msg? Exception #"[Ii]nvalid edge target"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :edges {:start {:success :nonexistent-cell, :failure :error}}})))))
