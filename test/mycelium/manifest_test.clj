(ns mycelium.manifest-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.manifest :as manifest]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(def valid-manifest
  {:id :test/workflow
   :doc "A test workflow"
   :cells {:start
           {:id       :test/parse
            :doc      "Parse input"
            :schema   {:input  [:map [:x :int]]
                       :output [:map [:y :int]]}
            :requires []
            :transitions #{:success :failure}}
           :process
           {:id       :test/process
            :doc      "Process data"
            :schema   {:input  [:map [:y :int]]
                       :output [:map [:z :string]]}
            :requires [:db]
            :transitions #{:done}}}
   :edges {:start   {:success :process, :failure :error}
           :process {:done :end}}})

;; ===== 1. Valid manifest passes validation =====

(deftest valid-manifest-passes-test
  (testing "Valid manifest passes validation"
    (let [result (manifest/validate-manifest valid-manifest)]
      (is (= :test/workflow (:id result))))))

;; ===== 2. Invalid Malli schema in manifest fails =====

(deftest invalid-schema-in-manifest-fails-test
  (testing "Invalid Malli schema in manifest fails"
    (let [bad-manifest (assoc-in valid-manifest [:cells :start :schema :input]
                                 [:not-real-type])]
      (is (thrown? Exception
            (manifest/validate-manifest bad-manifest))))))

;; ===== 3. cell-brief generates example data from schema =====

(deftest cell-brief-generates-examples-test
  (testing "cell-brief generates example data from schema"
    (let [brief (manifest/cell-brief valid-manifest :start)]
      (is (some? (:examples brief)))
      (is (map? (get-in brief [:examples :input])))
      (is (int? (get-in brief [:examples :input :x]))))))

;; ===== 4. cell-brief prompt includes contract details =====

(deftest cell-brief-prompt-includes-contract-test
  (testing "cell-brief prompt includes contract details (transitions, resources, schema)"
    (let [brief (manifest/cell-brief valid-manifest :process)]
      (is (string? (:prompt brief)))
      (is (re-find #"process" (:prompt brief)))
      (is (re-find #":done" (:prompt brief)))
      (is (re-find #":db" (:prompt brief)))
      (is (re-find #":z" (:prompt brief))))))

;; ===== 5. manifest->workflow produces compilable workflow def =====

(deftest manifest-to-workflow-produces-compilable-def-test
  (testing "manifest->workflow produces compilable workflow def"
    (let [workflow-def (manifest/manifest->workflow valid-manifest)]
      (is (map? workflow-def))
      (is (contains? workflow-def :cells))
      (is (contains? workflow-def :edges))
      ;; All cell IDs should be in the registry after manifest->workflow
      (is (some? (cell/get-cell :test/parse)))
      (is (some? (cell/get-cell :test/process))))))
