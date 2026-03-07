(ns mycelium.execution-tracing-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== :on-trace callback =====

(deftest on-trace-called-for-each-cell-test
  (testing ":on-trace callback receives trace entry after each cell"
    (defmethod cell/cell-spec :test/step-a [_]
      {:id      :test/step-a
       :handler (fn [_ data] {:a 1})
       :schema  {:input [:map] :output [:map [:a :int]]}})
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] {:b 2})
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})
    (let [entries (atom [])
          result (myc/run-workflow
                   {:cells      {:start :test/step-a
                                 :b     :test/step-b}
                    :edges      {:start {:done :b}
                                 :b     {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :b     [[:done (constantly true)]]}}
                   {} {} {:on-trace #(swap! entries conj %)})]
      ;; Callback should have been called twice (once per cell)
      (is (= 2 (count @entries)))
      ;; First entry is step-a
      (is (= :start (:cell (first @entries))))
      (is (= :test/step-a (:cell-id (first @entries))))
      (is (= :done (:transition (first @entries))))
      ;; Second entry is step-b
      (is (= :b (:cell (second @entries))))
      (is (= :test/step-b (:cell-id (second @entries))))
      ;; Each entry has duration
      (is (number? (:duration-ms (first @entries)))))))

(deftest on-trace-includes-data-snapshot-test
  (testing ":on-trace entry contains data snapshot"
    (defmethod cell/cell-spec :test/add-x [_]
      {:id      :test/add-x
       :handler (fn [_ data] {:result (* 2 (:x data))})
       :schema  {:input [:map [:x :int]] :output [:map [:result :int]]}})
    (let [entries (atom [])
          result (myc/run-workflow
                   {:cells      {:start :test/add-x}
                    :edges      {:start :end}}
                   {} {:x 21} {:on-trace #(swap! entries conj %)})]
      (is (= 1 (count @entries)))
      ;; Data snapshot should have the handler output
      (is (= 42 (get-in (first @entries) [:data :result]))))))

(deftest on-trace-with-pre-compile-test
  (testing ":on-trace works with pre-compiled workflows"
    (defmethod cell/cell-spec :test/step [_]
      {:id      :test/step
       :handler (fn [_ data] {:done true})
       :schema  {:input [:map] :output [:map [:done :boolean]]}})
    (let [entries (atom [])
          compiled (myc/pre-compile
                     {:cells {:start :test/step}
                      :edges {:start :end}}
                     {:on-trace #(swap! entries conj %)})]
      (myc/run-compiled compiled {} {})
      (is (= 1 (count @entries)))
      (is (= :start (:cell (first @entries)))))))

(deftest on-trace-on-error-test
  (testing ":on-trace is called even when a cell triggers a schema error"
    (defmethod cell/cell-spec :test/bad [_]
      {:id      :test/bad
       :handler (fn [_ data] {:count "not-int"})
       :schema  {:input [:map] :output [:map [:count :int]]}})
    (let [entries (atom [])
          on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells {:start :test/bad}
                    :edges {:start :end}}
                   {} {} {:on-trace #(swap! entries conj %)
                          :on-error on-error})]
      ;; Should still get a trace entry (with :error)
      (is (= 1 (count @entries)))
      (is (some? (:error (first @entries)))))))

;; ===== format-trace =====

(deftest format-trace-test
  (testing "format-trace produces human-readable output"
    (let [trace [{:cell :start :cell-id :app/validate
                  :transition :done :duration-ms 1.5
                  :data {:user-id 42}}
                 {:cell :process :cell-id :app/process
                  :transition :done :duration-ms 3.2
                  :data {:user-id 42 :result "ok"}}]
          formatted (dev/format-trace trace)]
      ;; Should be a string
      (is (string? formatted))
      ;; Should contain cell names
      (is (str/includes? formatted ":start"))
      (is (str/includes? formatted ":process"))
      ;; Should contain duration info
      (is (str/includes? formatted "1.5"))
      (is (str/includes? formatted "3.2"))
      ;; Should contain transitions
      (is (str/includes? formatted ":done")))))

(deftest format-trace-with-error-test
  (testing "format-trace shows errors"
    (let [trace [{:cell :start :cell-id :app/step
                  :transition :done :duration-ms 0.5
                  :data {:x 1}
                  :error {:cell-id :app/step :phase :output
                          :errors {:count ["should be an integer"]}}}]
          formatted (dev/format-trace trace)]
      (is (str/includes? formatted "ERROR"))
      (is (str/includes? formatted "should be an integer")))))

(deftest format-trace-empty-test
  (testing "format-trace handles empty trace"
    (is (string? (dev/format-trace [])))
    (is (string? (dev/format-trace nil)))))

;; ===== trace-logger =====

(deftest trace-logger-test
  (testing "trace-logger returns a callback that prints trace entries"
    (defmethod cell/cell-spec :test/step [_]
      {:id      :test/step
       :handler (fn [_ data] {:done true})
       :schema  {:input [:map] :output [:map [:done :boolean]]}})
    (let [output (with-out-str
                   (myc/run-workflow
                     {:cells {:start :test/step}
                      :edges {:start :end}}
                     {} {} {:on-trace (dev/trace-logger)}))]
      ;; Should have printed something about the cell
      (is (str/includes? output ":start"))
      (is (str/includes? output ":test/step")))))
