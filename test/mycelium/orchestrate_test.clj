(ns mycelium.orchestrate-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.orchestrate :as orchestrate]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(def test-manifest
  {:id :test/workflow
   :doc "Test workflow"
   :cells {:start
           {:id       :test/step-a
            :doc      "Step A"
            :schema   {:input  [:map [:x :int]]
                       :output [:map [:y :int]]}
            :requires []}
           :process
           {:id       :test/step-b
            :doc      "Step B"
            :schema   {:input  [:map [:y :int]]
                       :output [:map [:z :string]]}
            :requires [:db]}}
   :edges {:start   {:next :process}
           :process {:done :end, :error :error}}
   :dispatches {:start   {:next (constantly true)}
                :process {:done  (fn [d] (:z d))
                          :error (fn [d] (not (:z d)))}}})

;; ===== 1. cell-briefs returns brief for every cell =====

(deftest cell-briefs-returns-all-test
  (testing "cell-briefs returns brief for every cell in manifest"
    (let [briefs (orchestrate/cell-briefs test-manifest)]
      (is (= 2 (count briefs)))
      (is (contains? briefs :start))
      (is (contains? briefs :process))
      (is (string? (get-in briefs [:start :prompt])))
      (is (string? (get-in briefs [:process :prompt]))))))

;; ===== 2. reassignment-brief includes error context =====

(deftest reassignment-brief-includes-error-context-test
  (testing "reassignment-brief includes error context in prompt"
    (let [brief (orchestrate/reassignment-brief
                 test-manifest :process
                 {:error  "Output missing key :z"
                  :input  {:y 42}
                  :output {:y 42}})]
      (is (string? (:prompt brief)))
      (is (re-find #"missing" (:prompt brief)))
      (is (re-find #":z" (:prompt brief)))
      (is (re-find #"42" (:prompt brief))))))

;; ===== 3. plan identifies independent cells =====

(deftest plan-identifies-parallelizable-test
  (testing "plan identifies independent cells as parallelizable"
    (let [p (orchestrate/plan test-manifest)]
      (is (contains? p :parallel))
      (is (contains? p :scaffold))
      ;; All cells should appear in scaffold
      (is (= 2 (count (:scaffold p)))))))

;; ===== 4. progress reflects current status =====

(deftest progress-reflects-status-test
  (testing "progress reflects current status"
    ;; Register one cell, leave the other unregistered
    (defmethod cell/cell-spec :test/step-a [_]
      {:id          :test/step-a
       :handler     (fn [_ data]
                      (assoc data :y (inc (:x data))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}})

    (let [report (orchestrate/progress test-manifest)]
      (is (string? report))
      ;; Should mention the workflow
      (is (re-find #"test/workflow" report))
      ;; Should show total count and pending info
      (is (re-find #"2 total" report))
      (is (re-find #"1 pending" report)))))
