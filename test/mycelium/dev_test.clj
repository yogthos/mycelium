(ns mycelium.dev-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. test-cell passes with valid handler =====

(deftest test-cell-passes-valid-test
  (testing "test-cell passes with valid handler → {:pass? true}"
    (cell/defcell :dev/good-cell
      {:schema {:input [:map [:x :int]]
                :output [:map [:y :int]]}
       :transitions #{:ok}}
      [_ data]
      (assoc data :y (* 2 (:x data)) :mycelium/transition :ok))

    (let [result (dev/test-cell :dev/good-cell
                                {:input {:x 5}
                                 :resources {}})]
      (is (true? (:pass? result)))
      (is (= 10 (get-in result [:output :y])))
      (is (number? (:duration-ms result))))))

;; ===== 2. test-cell reports failure with bad output =====

(deftest test-cell-reports-failure-test
  (testing "test-cell reports failure with bad output → {:pass? false :errors [...]}"
    (cell/defcell :dev/bad-cell
      {:schema {:input [:map [:x :int]]
                :output [:map [:y :string]]}
       :transitions #{:ok}}
      [_ data]
      ;; Returns :y as int instead of string
      (assoc data :y 42 :mycelium/transition :ok))

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
