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

;; ===== 6. manifest->workflow applies schema to pre-registered cells =====

(deftest manifest-applies-schema-to-existing-cells-test
  (testing "manifest->workflow applies manifest schema to pre-registered cells"
    ;; Register a cell without schema
    (cell/register-cell!
     {:id          :test/parse
      :handler     (fn [_ data] (assoc data :y 1 :mycelium/transition :success))
      :transitions #{:success :failure}})
    (is (nil? (:schema (cell/get-cell :test/parse))))

    ;; Now load the manifest — should apply schema from manifest
    (manifest/manifest->workflow valid-manifest)

    (let [cell (cell/get-cell :test/parse)]
      (is (some? (:schema cell)))
      (is (= [:map [:x :int]] (get-in cell [:schema :input])))
      (is (= [:map [:y :int]] (get-in cell [:schema :output]))))))

;; ===== Per-transition output schema in manifest =====

(def per-transition-manifest
  {:id :test/pt-workflow
   :doc "A workflow with per-transition output schemas"
   :cells {:start
           {:id       :test/lookup
            :doc      "Look up a thing"
            :schema   {:input  [:map [:id :string]]
                       :output {:found     [:map [:profile [:map [:name :string]]]]
                                :not-found [:map [:error-message :string]]}}
            :requires [:db]
            :transitions #{:found :not-found}}
           :render
           {:id       :test/render
            :doc      "Render output"
            :schema   {:input  [:map [:profile [:map [:name :string]]]]
                       :output [:map [:html :string]]}
            :requires []
            :transitions #{:done}}}
   :edges {:start  {:found     :render
                    :not-found :error}
           :render {:done :end}}})

(deftest per-transition-manifest-validates-test
  (testing "Manifest with per-transition output passes validation"
    (let [result (manifest/validate-manifest per-transition-manifest)]
      (is (= :test/pt-workflow (:id result))))))

(deftest per-transition-manifest-key-mismatch-test
  (testing "Output map keys / transitions mismatch → rejected"
    (let [bad (assoc-in per-transition-manifest [:cells :start :schema :output]
                        {:found     [:map [:profile :map]]
                         :typo      [:map [:error :string]]})]
      (is (thrown-with-msg? Exception #"not in transitions|missing keys"
            (manifest/validate-manifest bad))))))

(deftest cell-brief-per-transition-prompt-test
  (testing "cell-brief shows per-transition schemas in prompt"
    (let [brief (manifest/cell-brief per-transition-manifest :start)]
      (is (string? (:prompt brief)))
      ;; Should mention both transitions
      (is (re-find #":found" (:prompt brief)))
      (is (re-find #":not-found" (:prompt brief)))
      ;; Should mention per-transition schema details
      (is (re-find #"profile" (:prompt brief)))
      (is (re-find #"error-message" (:prompt brief))))))

(deftest manifest-to-workflow-per-transition-test
  (testing "manifest->workflow applies per-transition schema to cells"
    (let [workflow-def (manifest/manifest->workflow per-transition-manifest)]
      (is (map? workflow-def))
      (let [cell (cell/get-cell :test/lookup)]
        (is (map? (get-in cell [:schema :output])))
        (is (= #{:found :not-found} (set (keys (get-in cell [:schema :output])))))))))
