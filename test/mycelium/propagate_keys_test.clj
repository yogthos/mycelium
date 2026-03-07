(ns mycelium.propagate-keys-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== Basic key propagation =====

(deftest propagate-keys-merges-input-into-output-test
  (testing "With :propagate-keys? true, input keys automatically appear in output"
    (defmethod cell/cell-spec :test/add-b [_]
      {:id      :test/add-b
       ;; Handler returns ONLY :b — without propagation, :a is lost
       :handler (fn [_ data] {:b (inc (:a data))})
       :schema  {:input  [:map [:a :int]]
                 :output [:map [:b :int]]}})
    (defmethod cell/cell-spec :test/use-both [_]
      {:id      :test/use-both
       :handler (fn [_ data] {:result (+ (:a data) (:b data))})
       :schema  {:input  [:map [:a :int] [:b :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/add-b
                                 :use   :test/use-both}
                    :edges      {:start {:done :use}
                                 :use   {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :use   [[:done (constantly true)]]}}
                   {} {:a 10} {:propagate-keys? true})]
      ;; :a should propagate through :test/add-b to :test/use-both
      (is (= 21 (:result result))))))

(deftest propagate-keys-handler-output-takes-precedence-test
  (testing "Handler output keys override input keys (handler wins)"
    (defmethod cell/cell-spec :test/override [_]
      {:id      :test/override
       :handler (fn [_ data] {:x (inc (:x data)) :y 99})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:x :int] [:y :int]]}})
    (defmethod cell/cell-spec :test/read-xy [_]
      {:id      :test/read-xy
       :handler (fn [_ data] {:result (+ (:x data) (:y data))})
       :schema  {:input  [:map [:x :int] [:y :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/override
                                 :read  :test/read-xy}
                    :edges      {:start {:done :read}
                                 :read  {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :read  [[:done (constantly true)]]}}
                   {} {:x 10} {:propagate-keys? true})]
      ;; handler output :x=11 should override input :x=10
      (is (= 110 (:result result))))))

(deftest propagate-keys-disabled-by-default-test
  (testing "Without :propagate-keys?, cells must explicitly include keys in output"
    (defmethod cell/cell-spec :test/add-b [_]
      {:id      :test/add-b
       ;; Handler returns ONLY :b — :a is lost
       :handler (fn [_ data] {:b (inc (:a data))})
       :schema  {:input  [:map [:a :int]]
                 :output [:map [:b :int]]}})
    (defmethod cell/cell-spec :test/use-both [_]
      {:id      :test/use-both
       :handler (fn [_ data] {:result (+ (or (:a data) 0) (:b data))})
       :schema  {:input  [:map [:a :int] [:b :int]]
                 :output [:map [:result :int]]}})
    ;; Without propagate-keys?, input validation on :use will fail
    ;; because :a is not in the data (only :b is)
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells      {:start :test/add-b
                                 :use   :test/use-both}
                    :edges      {:start {:done :use}
                                 :use   {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :use   [[:done (constantly true)]]}}
                   {} {:a 10} {:on-error on-error})]
      ;; :a was lost because propagation is off — input validation on :use should fail
      (is (some? (:mycelium/schema-error result)))
      (is (= :input (:phase (:mycelium/schema-error result)))))))

;; ===== Three-cell chain =====

(deftest propagate-keys-through-chain-test
  (testing "Keys propagate through a multi-cell chain"
    (defmethod cell/cell-spec :test/step-a [_]
      {:id      :test/step-a
       :handler (fn [_ data] {:a-out 1})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:a-out :int]]}})
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] {:b-out 2})
       :schema  {:input  [:map [:a-out :int]]
                 :output [:map [:b-out :int]]}})
    (defmethod cell/cell-spec :test/step-c [_]
      {:id      :test/step-c
       :handler (fn [_ data]
                  {:result (+ (:x data) (:a-out data) (:b-out data))})
       :schema  {:input  [:map [:x :int] [:a-out :int] [:b-out :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/step-a
                                 :b     :test/step-b
                                 :c     :test/step-c}
                    :edges      {:start {:done :b}
                                 :b     {:done :c}
                                 :c     {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :b     [[:done (constantly true)]]
                                 :c     [[:done (constantly true)]]}}
                   {} {:x 100} {:propagate-keys? true})]
      ;; :x propagates through all cells, :a-out through step-b and step-c
      (is (= 103 (:result result))))))

;; ===== Pre-compile with propagate-keys? =====

(deftest propagate-keys-with-pre-compile-test
  (testing "propagate-keys? works with pre-compile"
    (defmethod cell/cell-spec :test/add-b [_]
      {:id      :test/add-b
       :handler (fn [_ data] {:b (inc (:a data))})
       :schema  {:input  [:map [:a :int]]
                 :output [:map [:b :int]]}})
    (defmethod cell/cell-spec :test/use-both [_]
      {:id      :test/use-both
       :handler (fn [_ data] {:result (+ (:a data) (:b data))})
       :schema  {:input  [:map [:a :int] [:b :int]]
                 :output [:map [:result :int]]}})
    (let [compiled (myc/pre-compile
                     {:cells      {:start :test/add-b
                                   :use   :test/use-both}
                      :edges      {:start {:done :use}
                                   :use   {:done :end}}
                      :dispatches {:start [[:done (constantly true)]]
                                   :use   [[:done (constantly true)]]}}
                     {:propagate-keys? true})
          result (myc/run-compiled compiled {} {:a 10})]
      (is (= 21 (:result result))))))

;; ===== Schema validation still works =====

(deftest propagate-keys-schema-validation-still-enforced-test
  (testing "Output schema validation still catches bad handler output with propagate-keys?"
    (defmethod cell/cell-spec :test/bad-output [_]
      {:id      :test/bad-output
       :handler (fn [_ data] {:count "not-an-int"})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:count :int]]}})
    (defmethod cell/cell-spec :test/next [_]
      {:id      :test/next
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:count :int]]
                 :output [:map]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells      {:start :test/bad-output
                                 :next  :test/next}
                    :edges      {:start {:done :next}
                                 :next  {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :next  [[:done (constantly true)]]}}
                   {} {:x 42} {:propagate-keys? true :on-error on-error})]
      ;; Output schema validation should still catch "not-an-int" for :count
      (is (some? (:mycelium/schema-error result))))))

;; ===== Coercion + propagate-keys? combined =====

(deftest propagate-keys-with-coercion-test
  (testing "propagate-keys? and coerce? work together"
    (defmethod cell/cell-spec :test/produce-double [_]
      {:id      :test/produce-double
       :handler (fn [_ data] {:count 10.0})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:count :double]]}})
    (defmethod cell/cell-spec :test/consume-int [_]
      {:id      :test/consume-int
       :handler (fn [_ data] {:result (+ (:x data) (:count data))})
       :schema  {:input  [:map [:x :int] [:count :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/produce-double
                                 :use   :test/consume-int}
                    :edges      {:start {:done :use}
                                 :use   {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :use   [[:done (constantly true)]]}}
                   {} {:x 5} {:propagate-keys? true :coerce? true})]
      (is (= 15 (:result result))))))

;; ===== Propagated keys visible in final result =====

(deftest propagated-keys-in-final-result-test
  (testing "Propagated keys appear in the workflow result"
    (defmethod cell/cell-spec :test/add-tag [_]
      {:id      :test/add-tag
       :handler (fn [_ data] {:tag "processed"})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:tag :string]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/add-tag}
                    :edges      {:start :end}}
                   {} {:x 42} {:propagate-keys? true})]
      ;; Both :x (propagated) and :tag (handler output) should be present
      (is (= 42 (:x result)))
      (is (= "processed" (:tag result))))))
