(ns mycelium.dev-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. test-cell passes with valid handler =====

(deftest test-cell-passes-valid-test
  (testing "test-cell passes with valid handler → {:pass? true}"
    (defmethod cell/cell-spec :dev/good-cell [_]
      {:id          :dev/good-cell
       :handler     (fn [_ data]
                      (assoc data :y (* 2 (:x data)) :mycelium/transition :ok))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}
       :transitions #{:ok}})

    (let [result (dev/test-cell :dev/good-cell
                                {:input {:x 5}
                                 :resources {}})]
      (is (true? (:pass? result)))
      (is (= 10 (get-in result [:output :y])))
      (is (number? (:duration-ms result))))))

;; ===== 2. test-cell reports failure with bad output =====

(deftest test-cell-reports-failure-test
  (testing "test-cell reports failure with bad output → {:pass? false :errors [...]}"
    (defmethod cell/cell-spec :dev/bad-cell [_]
      {:id          :dev/bad-cell
       :handler     (fn [_ data]
                      ;; Returns :y as int instead of string
                      (assoc data :y 42 :mycelium/transition :ok))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :string]]}
       :transitions #{:ok}})

    (let [result (dev/test-cell :dev/bad-cell
                                {:input {:x 5}
                                 :resources {}})]
      (is (false? (:pass? result)))
      (is (seq (:errors result))))))

;; ===== 3. workflow->dot produces valid DOT graph string =====

(deftest workflow-to-dot-test
  (testing "workflow->dot produces valid DOT graph string"
    (let [manifest {:id :test/wf
                    :cells {:start   {:id :test/a :transitions #{:success :failure}}
                            :process {:id :test/b :transitions #{:done}}}
                    :edges {:start   {:success :process, :failure :error}
                            :process {:done :end}}}
          dot (dev/workflow->dot manifest)]
      (is (string? dot))
      (is (re-find #"digraph" dot))
      (is (re-find #"\"start\"" dot))
      (is (re-find #"\"process\"" dot))
      (is (re-find #"success" dot))
      (is (re-find #"\"halt\"" dot)))))

(deftest workflow-to-dot-handles-hyphens-test
  (testing "workflow->dot quotes identifiers with hyphens"
    (let [manifest {:cells {:start {:id :test/a}
                            :fetch-profile {:id :test/b}}
                    :edges {:start {:ok :fetch-profile}
                            :fetch-profile {:done :end}}}
          dot (dev/workflow->dot manifest)]
      (is (re-find #"\"fetch-profile\"" dot)))))

;; ===== test-cell with :expected-transition =====

(deftest test-cell-expected-transition-pass-test
  (testing "test-cell with :expected-transition passes when transition matches"
    (defmethod cell/cell-spec :dev/trans-cell [_]
      {:id          :dev/trans-cell
       :handler     (fn [_ data]
                      (assoc data :y 1 :mycelium/transition :ok))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}
       :transitions #{:ok :fail}})
    (let [result (dev/test-cell :dev/trans-cell
                                {:input {:x 5}
                                 :expected-transition :ok})]
      (is (true? (:pass? result))))))

(deftest test-cell-expected-transition-fail-test
  (testing "test-cell with :expected-transition fails when transition doesn't match"
    (defmethod cell/cell-spec :dev/trans-cell2 [_]
      {:id          :dev/trans-cell2
       :handler     (fn [_ data]
                      (assoc data :y 1 :mycelium/transition :ok))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}
       :transitions #{:ok :fail}})
    (let [result (dev/test-cell :dev/trans-cell2
                                {:input {:x 5}
                                 :expected-transition :fail})]
      (is (false? (:pass? result)))
      (is (some #(= :transition (:phase %)) (:errors result))))))

(deftest test-cell-per-transition-schema-test
  (testing "test-cell validates against per-transition schema"
    (defmethod cell/cell-spec :dev/pt-cell [_]
      {:id          :dev/pt-cell
       :handler     (fn [_ data]
                      (assoc data :profile {:name "Alice"} :mycelium/transition :found))
       :schema      {:input [:map [:id :string]]
                     :output {:found     [:map [:profile [:map [:name :string]]]]
                              :not-found [:map [:error-message :string]]}}
       :transitions #{:found :not-found}})
    (let [result (dev/test-cell :dev/pt-cell
                                {:input {:id "alice"}})]
      (is (true? (:pass? result))))))

;; ===== test-transitions =====

(deftest test-transitions-test
  (testing "test-transitions tests multiple transitions in one call"
    (defmethod cell/cell-spec :dev/multi-cell [_]
      {:id          :dev/multi-cell
       :handler     (fn [_ data]
                      (if (= (:id data) "alice")
                        (assoc data :profile {:name "Alice"} :mycelium/transition :found)
                        (assoc data :error-message "Not found" :mycelium/transition :not-found)))
       :schema      {:input [:map [:id :string]]
                     :output {:found     [:map [:profile [:map [:name :string]]]]
                              :not-found [:map [:error-message :string]]}}
       :transitions #{:found :not-found}})
    (let [results (dev/test-transitions :dev/multi-cell
                    {:found     {:input {:id "alice"}}
                     :not-found {:input {:id "nobody"}}})]
      (is (true? (get-in results [:found :pass?])))
      (is (true? (get-in results [:not-found :pass?])))
      (is (= :found (get-in results [:found :output :mycelium/transition])))
      (is (= :not-found (get-in results [:not-found :output :mycelium/transition]))))))

;; ===== enumerate-paths =====

(deftest enumerate-paths-test
  (testing "enumerate-paths lists all paths through a workflow"
    (let [manifest {:cells {:start  {:id :test/a :transitions #{:ok :fail}}
                            :step-b {:id :test/b :transitions #{:done}}
                            :step-c {:id :test/c :transitions #{:done}}}
                    :edges {:start  {:ok :step-b, :fail :step-c}
                            :step-b {:done :end}
                            :step-c {:done :end}}}
          paths (dev/enumerate-paths manifest)]
      ;; Should have 2 paths: start→step-b→end and start→step-c→end
      (is (= 2 (count paths)))
      ;; Each path should be a vector of steps
      (is (every? vector? paths))
      ;; Check first step in each path is start
      (is (every? #(= :start (:cell (first %))) paths)))))
