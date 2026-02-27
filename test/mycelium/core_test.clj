(ns mycelium.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as mycelium]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. run-workflow convenience works end-to-end =====

(deftest run-workflow-end-to-end-test
  (testing "run-workflow convenience works end-to-end"
    (defmethod cell/cell-spec :core/adder [_]
      {:id          :core/adder
       :handler     (fn [_ data]
                      (assoc data :result (+ (:x data) 100)))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:result :int]]}})

    (let [result (mycelium/run-workflow
                  {:cells {:start :core/adder}
                   :edges {:start {:done :end}}
                   :dispatches {:start [[:done (constantly true)]]}}
                  {}
                  {:x 42})]
      (is (= 142 (:result result))))))

;; ===== 2. Re-exported functions accessible =====

(deftest re-exported-fns-test
  (testing "Re-exported functions accessible from mycelium.core"
    (is (= cell/cell-spec mycelium/cell-spec))
    (is (fn? mycelium/compile-workflow))
    (is (fn? mycelium/workflow->cell))
    (is (fn? mycelium/load-manifest))
    (is (fn? mycelium/cell-brief))
    (is (fn? mycelium/run-workflow))
    (is (fn? mycelium/run-workflow-async))
    (is (fn? mycelium/analyze-workflow))))

;; ===== 3. run-workflow-async returns a future =====

(deftest run-workflow-async-test
  (testing "run-workflow-async returns a future that resolves to the final data"
    (defmethod cell/cell-spec :core/async-adder [_]
      {:id      :core/async-adder
       :handler (fn [_ data] (assoc data :result (+ (:x data) 200)))
       :schema  {:input [:map [:x :int]]
                 :output [:map [:result :int]]}})

    (let [fut (mycelium/run-workflow-async
               {:cells {:start :core/async-adder}
                :edges {:start {:done :end}}
                :dispatches {:start [[:done (constantly true)]]}}
               {}
               {:x 42})]
      (is (future? fut))
      (let [result (deref fut 5000 :timeout)]
        (is (not= :timeout result))
        (is (= 242 (:result result)))))))

;; ===== 4. run-workflow-async with branching takes correct path =====

(deftest run-workflow-async-branching-test
  (testing "run-workflow-async resolves correct branch"
    (defmethod cell/cell-spec :core/async-router [_]
      {:id      :core/async-router
       :handler (fn [_ data] data)
       :schema  {:input [:map [:value :int]] :output [:map]}})
    (defmethod cell/cell-spec :core/async-high [_]
      {:id      :core/async-high
       :handler (fn [_ data] (assoc data :path "high"))
       :schema  {:input [:map [:value :int]] :output [:map [:path :string]]}})
    (defmethod cell/cell-spec :core/async-low [_]
      {:id      :core/async-low
       :handler (fn [_ data] (assoc data :path "low"))
       :schema  {:input [:map [:value :int]] :output [:map [:path :string]]}})

    (let [wf-def {:cells {:start :core/async-router
                          :high  :core/async-high
                          :low   :core/async-low}
                  :edges {:start {:high :high, :low :low}
                          :high  {:done :end}
                          :low   {:done :end}}
                  :dispatches {:start [[:high (fn [d] (> (:value d) 5))]
                                       [:low  (fn [d] (<= (:value d) 5))]]
                               :high [[:done (constantly true)]]
                               :low  [[:done (constantly true)]]}}
          fut-high (mycelium/run-workflow-async wf-def {} {:value 10})
          fut-low  (mycelium/run-workflow-async wf-def {} {:value 2})]
      (is (= "high" (:path (deref fut-high 5000 :timeout))))
      (is (= "low"  (:path (deref fut-low 5000 :timeout)))))))

;; ===== 5. run-workflow-async with resources =====

(deftest run-workflow-async-resources-test
  (testing "run-workflow-async passes resources through to handlers"
    (defmethod cell/cell-spec :core/async-res [_]
      {:id      :core/async-res
       :handler (fn [{:keys [multiplier]} data]
                  (assoc data :result (* (:x data) multiplier)))
       :schema  {:input [:map [:x :int]] :output [:map [:result :int]]}})

    (let [fut (mycelium/run-workflow-async
               {:cells {:start :core/async-res}
                :edges {:start {:done :end}}
                :dispatches {:start [[:done (constantly true)]]}}
               {:multiplier 7}
               {:x 6})]
      (is (= 42 (:result (deref fut 5000 :timeout)))))))

;; ===== 6. run-workflow-async propagates errors =====

(deftest run-workflow-async-error-propagation-test
  (testing "run-workflow-async propagates schema errors when deref'd"
    (defmethod cell/cell-spec :core/async-bad [_]
      {:id      :core/async-bad
       :handler (fn [_ data] (assoc data :y "not-an-int"))
       :schema  {:input [:map [:x :int]] :output [:map [:y :int]]}})

    (let [fut (mycelium/run-workflow-async
               {:cells {:start :core/async-bad}
                :edges {:start {:ok :end}}
                :dispatches {:start [[:ok (constantly true)]]}}
               {}
               {:x 1})]
      ;; Deref should throw (Maestro hits error state with no handler)
      (is (thrown? Exception (deref fut 5000 :timeout))))))

;; ===== 7. run-workflow-async includes trace with duration-ms =====

(deftest run-workflow-async-trace-test
  (testing "run-workflow-async result includes trace with duration-ms"
    (defmethod cell/cell-spec :core/async-traced [_]
      {:id      :core/async-traced
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map [:x :int]] :output [:map [:a :int]]}})

    (let [fut (mycelium/run-workflow-async
               {:cells {:start :core/async-traced}
                :edges {:start {:done :end}}
                :dispatches {:start [[:done (constantly true)]]}}
               {}
               {:x 10})
          result (deref fut 5000 :timeout)
          trace  (:mycelium/trace result)]
      (is (= 1 (count trace)))
      (is (= :start (:cell (first trace))))
      (is (number? (:duration-ms (first trace)))))))

;; ===== 8. analyze-workflow exposed in core =====

(deftest analyze-workflow-test
  (testing "analyze-workflow returns analysis with cell names"
    (defmethod cell/cell-spec :core/analyze-a [_]
      {:id      :core/analyze-a
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map] :output [:map [:a :int]]}})

    (let [analysis (mycelium/analyze-workflow
                    {:cells {:start :core/analyze-a}
                     :edges {:start {:done :end}}
                     :dispatches {:start [[:done (constantly true)]]}})]
      (is (contains? (:reachable analysis) :start))
      (is (empty? (:unreachable analysis)))
      (is (empty? (:no-path-to-end analysis)))
      (is (empty? (:cycles analysis))))))
