(ns mycelium.timeout-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(defn- make-cell
  ([id] (make-cell id (fn [_ data] (assoc data id true))))
  ([id handler]
   (defmethod cell/cell-spec id [_]
     {:id id
      :handler handler
      :schema {:input [:map] :output [:map]}})))

;; ===== Round 1: Basic timeout routing =====

(deftest basic-timeout-routing-test
  (testing "Cell that exceeds timeout routes to :timeout edge target"
    (make-cell :c/slow (fn [_ data]
                         (Thread/sleep 200)
                         (assoc data :result "done")))
    (make-cell :c/fallback)
    (make-cell :c/unreachable)

    (let [result (myc/run-workflow
                   {:cells {:start :c/slow
                            :ok    :c/unreachable
                            :fallback :c/fallback}
                    :edges {:start {:done :ok, :timeout :fallback}
                            :ok :end
                            :fallback :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (true? (:mycelium/timeout result))
          "Timeout flag is injected into data")
      (is (true? (:c/fallback result))
          "Fallback cell ran")
      (is (nil? (:c/unreachable result))
          "Normal path cell did not run"))))

;; ===== Round 2: No timeout — handler completes in time =====

(deftest no-timeout-when-fast-test
  (testing "Cell that completes before timeout routes normally"
    (make-cell :c/fast (fn [_ data]
                         (assoc data :result "fast")))
    (make-cell :c/next)

    (let [result (myc/run-workflow
                   {:cells {:start :c/fast, :next :c/next}
                    :edges {:start {:done :next, :timeout :end}
                            :next :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :timeouts {:start 5000}}
                   {} {})]
      (is (= "fast" (:result result))
          "Handler result is present")
      (is (nil? (:mycelium/timeout result))
          "No timeout flag"))))

;; ===== Round 3: Validation — timeout value must be positive integer =====

(deftest timeout-validation-positive-integer-test
  (testing "Timeout value must be a positive integer"
    (make-cell :c/val3)

    (is (thrown-with-msg? Exception #"positive integer"
          (myc/run-workflow
            {:cells {:start :c/val3}
             :edges {:start {:done :end, :timeout :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :timeouts {:start -100}}
            {} {})))

    (is (thrown-with-msg? Exception #"positive integer"
          (myc/run-workflow
            {:cells {:start :c/val3}
             :edges {:start {:done :end, :timeout :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :timeouts {:start 0}}
            {} {})))

    (is (thrown-with-msg? Exception #"positive integer"
          (myc/run-workflow
            {:cells {:start :c/val3}
             :edges {:start {:done :end, :timeout :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :timeouts {:start "fast"}}
            {} {})))))

;; ===== Round 4: Validation — timeout cell must have :timeout edge =====

(deftest timeout-requires-timeout-edge-test
  (testing "Cell with timeout must have a :timeout edge target"
    (make-cell :c/val4)

    (is (thrown-with-msg? Exception #"timeout.*edge"
          (myc/run-workflow
            {:cells {:start :c/val4}
             :edges {:start :end}
             :timeouts {:start 1000}}
            {} {})))))

;; ===== Round 5: Validation — timeout cell must exist =====

(deftest timeout-cell-must-exist-test
  (testing "Timeout references a cell that exists in :cells"
    (make-cell :c/val5)

    (is (thrown-with-msg? Exception #"timeout.*:nonexistent"
          (myc/run-workflow
            {:cells {:start :c/val5}
             :edges {:start :end}
             :timeouts {:nonexistent 1000}}
            {} {})))))

;; ===== Round 6: Timeout with async cell =====

(deftest timeout-with-async-cell-test
  (testing "Async cell that times out routes to :timeout edge"
    (defmethod cell/cell-spec :c/async-slow [_]
      {:id :c/async-slow
       :handler (fn [_resources data callback _error-cb]
                  (future
                    (Thread/sleep 200)
                    (callback (assoc data :async-result "done"))))
       :async? true
       :schema {:input [:map] :output [:map]}})
    (make-cell :c/async-fallback)

    (let [result (myc/run-workflow
                   {:cells {:start :c/async-slow, :fallback :c/async-fallback}
                    :edges {:start {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (true? (:mycelium/timeout result)))
      (is (true? (:c/async-fallback result))))))

;; ===== Round 7: Timeout dispatch auto-injection =====

(deftest timeout-dispatch-auto-injected-test
  (testing ":timeout dispatch is auto-injected — user doesn't need to write it"
    (make-cell :c/slow7 (fn [_ data]
                          (Thread/sleep 200)
                          (assoc data :result "done")))
    (make-cell :c/fallback7)

    ;; No :timeout in dispatches — should be auto-injected
    (let [result (myc/run-workflow
                   {:cells {:start :c/slow7, :fallback :c/fallback7}
                    :edges {:start {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (fn [d] (not (:mycelium/timeout d)))]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (true? (:mycelium/timeout result))))))

;; ===== Round 8: Timeout with :default edge =====

(deftest timeout-with-default-edge-test
  (testing "Timeout works alongside :default edge"
    (make-cell :c/slow8 (fn [_ data]
                          (Thread/sleep 200)
                          (assoc data :result "done")))
    (make-cell :c/fallback8)
    (make-cell :c/default8)

    (let [result (myc/run-workflow
                   {:cells {:start :c/slow8, :fallback :c/fallback8, :default-cell :c/default8}
                    :edges {:start {:done :end, :timeout :fallback, :default :default-cell}
                            :fallback :end
                            :default-cell :end}
                    :dispatches {:start [[:done (fn [d] (and (not (:mycelium/timeout d))
                                                             (:result d)))]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (true? (:mycelium/timeout result)))
      (is (true? (:c/fallback8 result))
          ":timeout routes before :default"))))

;; ===== Round 9: Trace records timeout =====

(deftest timeout-trace-test
  (testing "Trace entry records timeout event"
    (make-cell :c/slow9 (fn [_ data]
                          (Thread/sleep 200)
                          (assoc data :result "done")))
    (make-cell :c/fallback9)

    (let [result (myc/run-workflow
                   {:cells {:start :c/slow9, :fallback :c/fallback9}
                    :edges {:start {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :timeouts {:start 50}}
                   {} {})
          trace (:mycelium/trace result)
          start-entry (first (filter #(= :start (:cell %)) trace))]
      (is (some? start-entry) "Start cell appears in trace")
      (is (true? (:timeout? start-entry)) "Trace entry has :timeout? flag"))))

;; ===== Round 10: Multiple cells with timeouts =====

(deftest multiple-timeouts-test
  (testing "Multiple cells can have independent timeouts"
    (make-cell :c/fast10 (fn [_ data] (assoc data :first "ok")))
    (make-cell :c/slow10 (fn [_ data]
                           (Thread/sleep 200)
                           (assoc data :second "done")))
    (make-cell :c/fallback10)

    (let [result (myc/run-workflow
                   {:cells {:start :c/fast10
                            :next  :c/slow10
                            :fallback :c/fallback10}
                    :edges {:start {:done :next, :timeout :end}
                            :next {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (fn [d] (not (:mycelium/timeout d)))]]
                                 :next  [[:done (fn [d] (not (:mycelium/timeout d)))]]}
                    :timeouts {:start 5000, :next 50}}
                   {} {})]
      (is (= "ok" (:first result)) "First cell completes normally")
      (is (true? (:mycelium/timeout result)) "Second cell times out")
      (is (true? (:c/fallback10 result)) "Routes to fallback"))))
