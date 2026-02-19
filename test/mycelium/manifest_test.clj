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
            :requires []}
           :process
           {:id       :test/process
            :doc      "Process data"
            :schema   {:input  [:map [:y :int]]
                       :output [:map [:z :string]]}
            :requires [:db]}}
   :edges {:start   {:success :process, :failure :error}
           :process {:done :end}}
   :dispatches {:start   {:success (fn [data] (:y data))
                           :failure (fn [data] (not (:y data)))}
                :process {:done (constantly true)}}})

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

;; ===== 4. cell-brief prompt does NOT mention :mycelium/transition =====

(deftest cell-brief-prompt-no-transition-test
  (testing "cell-brief prompt does not mention :mycelium/transition"
    (let [brief (manifest/cell-brief valid-manifest :process)]
      (is (string? (:prompt brief)))
      (is (re-find #"process" (:prompt brief)))
      (is (re-find #":db" (:prompt brief)))
      (is (re-find #":z" (:prompt brief)))
      ;; Should NOT reference :mycelium/transition
      (is (not (re-find #"mycelium/transition" (:prompt brief)))))))

;; ===== 5. manifest->workflow produces compilable workflow def =====

(deftest manifest-to-workflow-produces-compilable-def-test
  (testing "manifest->workflow produces compilable workflow def with :dispatches"
    (let [workflow-def (manifest/manifest->workflow valid-manifest)]
      (is (map? workflow-def))
      (is (contains? workflow-def :cells))
      (is (contains? workflow-def :edges))
      (is (contains? workflow-def :dispatches))
      ;; All cell IDs should be in the registry after manifest->workflow
      (is (some? (cell/get-cell :test/parse)))
      (is (some? (cell/get-cell :test/process))))))

;; ===== 6. manifest->workflow applies schema to pre-registered cells =====

(deftest manifest-applies-schema-to-existing-cells-test
  (testing "manifest->workflow applies manifest metadata to pre-registered cells"
    ;; Register a cell WITHOUT schema — manifest is the source of truth
    (defmethod cell/cell-spec :test/parse [_]
      {:id      :test/parse
       :handler (fn [_ data] (assoc data :y 1))})
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
            :requires [:db]}
           :render
           {:id       :test/render
            :doc      "Render output"
            :schema   {:input  [:map [:profile [:map [:name :string]]]]
                       :output [:map [:html :string]]}
            :requires []}}
   :edges {:start  {:found     :render
                    :not-found :error}
           :render {:done :end}}
   :dispatches {:start  {:found     (fn [d] (:profile d))
                          :not-found (fn [d] (:error-message d))}
                :render {:done (constantly true)}}})

(deftest per-transition-manifest-validates-test
  (testing "Manifest with per-transition output passes validation"
    (let [result (manifest/validate-manifest per-transition-manifest)]
      (is (= :test/pt-workflow (:id result))))))

(deftest cell-brief-per-transition-prompt-test
  (testing "cell-brief shows per-transition schemas in prompt"
    (let [brief (manifest/cell-brief per-transition-manifest :start)]
      (is (string? (:prompt brief)))
      ;; Should mention per-transition schema details
      (is (re-find #"profile" (:prompt brief)))
      (is (re-find #"error-message" (:prompt brief)))
      ;; Should NOT mention :mycelium/transition
      (is (not (re-find #"mycelium/transition" (:prompt brief)))))))

(deftest manifest-to-workflow-per-transition-test
  (testing "manifest->workflow applies per-transition schema to cells"
    (let [workflow-def (manifest/manifest->workflow per-transition-manifest)]
      (is (map? workflow-def))
      (let [cell (cell/get-cell :test/lookup)]
        (is (map? (get-in cell [:schema :output])))
        (is (= #{:found :not-found} (set (keys (get-in cell [:schema :output])))))))))

;; ===== Dispatch coverage validation =====

(deftest missing-dispatch-for-edge-test
  (testing "Missing dispatch predicate for a map edge label is rejected"
    (let [bad (assoc valid-manifest :dispatches
                     {:start {:success (fn [d] (:y d))}  ;; missing :failure
                      :process {:done (constantly true)}})]
      (is (thrown-with-msg? Exception #"[Dd]ispatch"
            (manifest/validate-manifest bad))))))

(deftest extra-dispatch-for-edge-test
  (testing "Extra dispatch key not in edges is rejected"
    (let [bad (assoc valid-manifest :dispatches
                     {:start {:success (fn [d] (:y d))
                              :failure (fn [d] (not (:y d)))
                              :extra   (fn [d] d)}
                      :process {:done (constantly true)}})]
      (is (thrown-with-msg? Exception #"[Dd]ispatch"
            (manifest/validate-manifest bad))))))

(deftest unconditional-edge-no-dispatch-test
  (testing "Unconditional edge (keyword target) needs no dispatch entry"
    (let [m {:id :test/simple
             :cells {:start {:id :test/simple-cell
                              :schema {:input [:map] :output [:map]}}}
             :edges {:start :end}}]
      (is (some? (manifest/validate-manifest m))))))
