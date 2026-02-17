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
                      (assoc data :a (inc (:x data)) :mycelium/transition :next))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:a :int]]}
       :transitions #{:next}})

    (defmethod cell/cell-spec :int/step-2 [_]
      {:id          :int/step-2
       :handler     (fn [_ data]
                      (assoc data :b (* 2 (:a data)) :mycelium/transition :done))
       :schema      {:input [:map [:a :int]]
                     :output [:map [:b :int]]}
       :transitions #{:done}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/step-1
                             :step2 :int/step-2}
                     :edges {:start {:next :step2}
                             :step2 {:done :end}}})
          result   (fsm/run compiled {} {:data {:x 10}})]
      (is (= 11 (:a result)))
      (is (= 22 (:b result)))
      (is (= :done (:mycelium/transition result))))))

;; ===== 2. Branching workflow takes correct path =====

(deftest branching-workflow-test
  (testing "Branching workflow takes correct path based on data"
    (defmethod cell/cell-spec :int/router [_]
      {:id          :int/router
       :handler     (fn [_ data]
                      (assoc data :mycelium/transition (if (> (:value data) 5) :high :low)))
       :schema      {:input [:map [:value :int]]
                     :output [:map]}
       :transitions #{:high :low}})

    (defmethod cell/cell-spec :int/high-path [_]
      {:id          :int/high-path
       :handler     (fn [_ data]
                      (assoc data :result "high" :mycelium/transition :done))
       :schema      {:input [:map [:value :int]]
                     :output [:map [:result :string]]}
       :transitions #{:done}})

    (defmethod cell/cell-spec :int/low-path [_]
      {:id          :int/low-path
       :handler     (fn [_ data]
                      (assoc data :result "low" :mycelium/transition :done))
       :schema      {:input [:map [:value :int]]
                     :output [:map [:result :string]]}
       :transitions #{:done}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start    :int/router
                             :high     :int/high-path
                             :low      :int/low-path}
                     :edges {:start {:high :high, :low :low}
                             :high  {:done :end}
                             :low   {:done :end}}})]
      (is (= "high" (:result (fsm/run compiled {} {:data {:value 10}}))))
      (is (= "low"  (:result (fsm/run compiled {} {:data {:value 2}})))))))

;; ===== 3. Invalid input triggers error state =====

(deftest invalid-input-triggers-error-test
  (testing "Invalid input triggers error state (pre interceptor catches)"
    (defmethod cell/cell-spec :int/strict-cell [_]
      {:id          :int/strict-cell
       :handler     (fn [_ data]
                      (assoc data :greeting (str "Hello " (:name data)) :mycelium/transition :ok))
       :schema      {:input [:map [:name :string]]
                     :output [:map [:greeting :string]]}
       :transitions #{:ok}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/strict-cell}
                     :edges {:start {:ok :end}}})]
      (is (thrown? Exception
            (fsm/run compiled {} {:data {:name 42}}))))))

;; ===== 4. Invalid output triggers error state =====

(deftest invalid-output-triggers-error-test
  (testing "Invalid output triggers error state (post interceptor catches)"
    (defmethod cell/cell-spec :int/bad-output-cell [_]
      {:id          :int/bad-output-cell
       :handler     (fn [_ data]
                      ;; Returns :y as int instead of string - schema violation
                      (assoc data :y 42 :mycelium/transition :ok))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :string]]}
       :transitions #{:ok}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/bad-output-cell}
                     :edges {:start {:ok :end}}})]
      (is (thrown? Exception
            (fsm/run compiled {} {:data {:x 1}}))))))

;; ===== 5. Error state receives schema error details =====

(deftest error-state-receives-schema-error-test
  (testing "Error state receives :mycelium/schema-error details"
    (defmethod cell/cell-spec :int/will-fail [_]
      {:id          :int/will-fail
       :handler     (fn [_ data]
                      (assoc data :y 42 :mycelium/transition :ok))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :string]]}
       :transitions #{:ok}})

    (let [error-data (atom nil)
          compiled   (wf/compile-workflow
                      {:cells {:start :int/will-fail}
                       :edges {:start {:ok :end}}}
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
                      (assoc data
                             :config-val (:api-key config)
                             :mycelium/transition :ok))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:config-val :string]]}
       :transitions #{:ok}
       :requires    [:config]})

    (let [compiled  (wf/compile-workflow
                     {:cells {:start :int/uses-resource}
                      :edges {:start {:ok :end}}})
          resources {:config {:api-key "secret-123"}}
          result    (fsm/run compiled resources {:data {:x 1}})]
      (is (= "secret-123" (:config-val result))))))

;; ===== 7. Looping workflow with counter =====

(deftest looping-workflow-test
  (testing "Looping workflow (cycle) works with counter"
    (defmethod cell/cell-spec :int/incrementer [_]
      {:id          :int/incrementer
       :handler     (fn [_ data]
                      (let [new-count (inc (:count data))]
                        (assoc data
                               :count new-count
                               :mycelium/transition (if (>= new-count 5) :done :again))))
       :schema      {:input [:map [:count :int]]
                     :output [:map [:count :int]]}
       :transitions #{:again :done}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/incrementer}
                     :edges {:start {:again :start, :done :end}}})
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
                        (callback (assoc data :y (* (:x data) 3) :mycelium/transition :ok))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}
       :transitions #{:ok}
       :async?      true})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/async-cell}
                     :edges {:start {:ok :end}}})
          result   (fsm/run compiled {} {:data {:x 7}})]
      (is (= 21 (:y result))))))
