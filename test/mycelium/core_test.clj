(ns mycelium.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as mycelium]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. run-workflow convenience works end-to-end =====

(deftest run-workflow-end-to-end-test
  (testing "run-workflow convenience works end-to-end"
    (mycelium/defcell :core/adder
      {:schema {:input [:map [:x :int]]
                :output [:map [:result :int]]}
       :transitions #{:done}}
      [_ data]
      (assoc data :result (+ (:x data) 100) :mycelium/transition :done))

    (let [result (mycelium/run-workflow
                  {:cells {:start :core/adder}
                   :edges {:start {:done :end}}}
                  {}
                  {:x 42})]
      (is (= 142 (:result result))))))

;; ===== 2. Re-exported functions accessible =====

(deftest re-exported-fns-test
  (testing "Re-exported functions accessible from mycelium.core"
    (is (:macro (meta #'mycelium/defcell)))
    (is (fn? mycelium/compile-workflow))
    (is (fn? mycelium/workflow->cell))
    (is (fn? mycelium/load-manifest))
    (is (fn? mycelium/cell-brief))
    (is (fn? mycelium/run-workflow))))
