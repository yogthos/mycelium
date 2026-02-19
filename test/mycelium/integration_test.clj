(ns mycelium.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. Linear workflow runs end-to-end =====

(deftest linear-workflow-end-to-end-test
  (testing "Linear workflow runs end-to-end, all data accumulated"
    (defmethod cell/cell-spec :int/step-1 [_]
      {:id          :int/step-1
       :handler     (fn [_ data]
                      (assoc data :a (inc (:x data))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:a :int]]}})

    (defmethod cell/cell-spec :int/step-2 [_]
      {:id          :int/step-2
       :handler     (fn [_ data]
                      (assoc data :b (* 2 (:a data))))
       :schema      {:input [:map [:a :int]]
                     :output [:map [:b :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/step-1
                             :step2 :int/step-2}
                     :edges {:start {:next :step2}
                             :step2 {:done :end}}
                     :dispatches {:start {:next (constantly true)}
                                  :step2 {:done (constantly true)}}})
          result   (fsm/run compiled {} {:data {:x 10}})]
      (is (= 11 (:a result)))
      (is (= 22 (:b result))))))

;; ===== 2. Branching workflow takes correct path =====

(deftest branching-workflow-test
  (testing "Branching workflow takes correct path based on dispatch predicates"
    (defmethod cell/cell-spec :int/router [_]
      {:id          :int/router
       :handler     (fn [_ data] data)
       :schema      {:input [:map [:value :int]]
                     :output [:map]}})

    (defmethod cell/cell-spec :int/high-path [_]
      {:id          :int/high-path
       :handler     (fn [_ data]
                      (assoc data :result "high"))
       :schema      {:input [:map [:value :int]]
                     :output [:map [:result :string]]}})

    (defmethod cell/cell-spec :int/low-path [_]
      {:id          :int/low-path
       :handler     (fn [_ data]
                      (assoc data :result "low"))
       :schema      {:input [:map [:value :int]]
                     :output [:map [:result :string]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start    :int/router
                             :high     :int/high-path
                             :low      :int/low-path}
                     :edges {:start {:high :high, :low :low}
                             :high  {:done :end}
                             :low   {:done :end}}
                     :dispatches {:start {:high (fn [d] (> (:value d) 5))
                                          :low  (fn [d] (<= (:value d) 5))}
                                  :high {:done (constantly true)}
                                  :low  {:done (constantly true)}}})]
      (is (= "high" (:result (fsm/run compiled {} {:data {:value 10}}))))
      (is (= "low"  (:result (fsm/run compiled {} {:data {:value 2}})))))))

;; ===== 3. Invalid input triggers error state =====

(deftest invalid-input-triggers-error-test
  (testing "Invalid input triggers error state (pre interceptor catches)"
    (defmethod cell/cell-spec :int/strict-cell [_]
      {:id          :int/strict-cell
       :handler     (fn [_ data]
                      (assoc data :greeting (str "Hello " (:name data))))
       :schema      {:input [:map [:name :string]]
                     :output [:map [:greeting :string]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/strict-cell}
                     :edges {:start {:ok :end}}
                     :dispatches {:start {:ok (constantly true)}}})]
      (is (thrown? Exception
            (fsm/run compiled {} {:data {:name 42}}))))))

;; ===== 4. Invalid output triggers error state =====

(deftest invalid-output-triggers-error-test
  (testing "Invalid output triggers error state (post interceptor catches)"
    (defmethod cell/cell-spec :int/bad-output-cell [_]
      {:id          :int/bad-output-cell
       :handler     (fn [_ data]
                      ;; Returns :y as int instead of string - schema violation
                      (assoc data :y 42))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :string]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/bad-output-cell}
                     :edges {:start {:ok :end}}
                     :dispatches {:start {:ok (constantly true)}}})]
      (is (thrown? Exception
            (fsm/run compiled {} {:data {:x 1}}))))))

;; ===== 5. Error state receives schema error details =====

(deftest error-state-receives-schema-error-test
  (testing "Error state receives :mycelium/schema-error details"
    (defmethod cell/cell-spec :int/will-fail [_]
      {:id          :int/will-fail
       :handler     (fn [_ data]
                      (assoc data :y 42))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :string]]}})

    (let [error-data (atom nil)
          compiled   (wf/compile-workflow
                      {:cells {:start :int/will-fail}
                       :edges {:start {:ok :end}}
                       :dispatches {:start {:ok (constantly true)}}}
                      {:on-error (fn [_ fsm-state]
                                   (reset! error-data (:data fsm-state))
                                   (:data fsm-state))})]
      (fsm/run compiled {} {:data {:x 1}})
      (is (some? (:mycelium/schema-error @error-data)))
      (is (= :int/will-fail (get-in @error-data [:mycelium/schema-error :cell-id]))))))

;; ===== 6. Resources pass through to cell handlers =====

(deftest resources-pass-through-test
  (testing "Resources pass through to cell handlers"
    (defmethod cell/cell-spec :int/uses-resource [_]
      {:id          :int/uses-resource
       :handler     (fn [{:keys [config]} data]
                      (assoc data :config-val (:api-key config)))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:config-val :string]]}
       :requires    [:config]})

    (let [compiled  (wf/compile-workflow
                     {:cells {:start :int/uses-resource}
                      :edges {:start {:ok :end}}
                      :dispatches {:start {:ok (constantly true)}}})
          resources {:config {:api-key "secret-123"}}
          result    (fsm/run compiled resources {:data {:x 1}})]
      (is (= "secret-123" (:config-val result))))))

;; ===== 7. Looping workflow with counter =====

(deftest looping-workflow-test
  (testing "Looping workflow (cycle) works with counter via dispatch predicates"
    (defmethod cell/cell-spec :int/incrementer [_]
      {:id          :int/incrementer
       :handler     (fn [_ data]
                      (assoc data :count (inc (:count data))))
       :schema      {:input [:map [:count :int]]
                     :output [:map [:count :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/incrementer}
                     :edges {:start {:again :start, :done :end}}
                     :dispatches {:start {:again (fn [d] (< (:count d) 5))
                                          :done  (fn [d] (>= (:count d) 5))}}})
          result   (fsm/run compiled {} {:data {:count 0}})]
      (is (= 5 (:count result))))))

;; ===== 8. Async cell runs correctly =====

(deftest async-cell-in-workflow-test
  (testing "Async cell runs correctly in workflow"
    (defmethod cell/cell-spec :int/async-cell [_]
      {:id          :int/async-cell
       :handler     (fn [_ data callback _error-callback]
                      (future
                        (Thread/sleep 10)
                        (callback (assoc data :y (* (:x data) 3)))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}
       :async?      true})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/async-cell}
                     :edges {:start {:ok :end}}
                     :dispatches {:start {:ok (constantly true)}}})
          result   (fsm/run compiled {} {:data {:x 7}})]
      (is (= 21 (:y result))))))
