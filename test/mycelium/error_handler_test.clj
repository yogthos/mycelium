(ns mycelium.error-handler-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.manifest :as manifest]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. Cell without :on-error in strict mode → validation error =====

(deftest missing-on-error-strict-test
  (testing "Cell without :on-error in strict mode fails validation"
    (is (thrown-with-msg? Exception #"on-error"
          (manifest/validate-manifest
           {:id :test/strict-wf
            :cells {:start {:id     :test/strict-cell
                            :schema {:input [:map] :output [:map]}}}
            :edges {:start :end}}
           {:strict? true})))))

;; ===== 2. Cell with :on-error nil → passes validation =====

(deftest on-error-nil-passes-test
  (testing "Cell with :on-error nil passes validation in strict mode"
    (let [result (manifest/validate-manifest
                  {:id :test/nil-on-error-wf
                   :cells {:start {:id       :test/nil-err-cell
                                   :schema   {:input [:map] :output [:map]}
                                   :on-error nil}}
                   :edges {:start :end}}
                  {:strict? true})]
      (is (= :test/nil-on-error-wf (:id result))))))

;; ===== 3. Cell with :on-error :render-error where target exists → passes =====

(deftest on-error-valid-target-passes-test
  (testing "Cell with :on-error pointing to existing cell passes"
    (let [result (manifest/validate-manifest
                  {:id :test/valid-on-error-wf
                   :cells {:start        {:id       :test/oe-start
                                          :schema   {:input [:map] :output [:map [:y :int]]}
                                          :on-error :render-error}
                           :render-error {:id       :test/oe-error
                                          :schema   {:input [:map] :output [:map [:html :string]]}
                                          :on-error nil}}
                   :edges {:start        {:ok :end :fail :render-error}
                           :render-error {:done :end}}
                   :dispatches {:start        [[:ok   (fn [d] (:y d))]
                                               [:fail (fn [d] (not (:y d)))]]
                                :render-error [[:done (constantly true)]]}}
                  {:strict? true})]
      (is (= :test/valid-on-error-wf (:id result))))))

;; ===== 4. Cell with :on-error :nonexistent → validation error =====

(deftest on-error-nonexistent-target-test
  (testing "Cell with :on-error pointing to nonexistent cell fails"
    (is (thrown-with-msg? Exception #"on-error.*nonexistent"
          (manifest/validate-manifest
           {:id :test/bad-on-error-wf
            :cells {:start {:id       :test/bad-oe-cell
                            :schema   {:input [:map] :output [:map]}
                            :on-error :nonexistent}}
            :edges {:start :end}}
           {:strict? true})))))

;; ===== 5. Default non-strict mode: missing :on-error is a warning, not error =====

(deftest non-strict-missing-on-error-warns-test
  (testing "Non-strict mode: missing :on-error does not throw (backwards compat)"
    (let [result (manifest/validate-manifest
                  {:id :test/non-strict-wf
                   :cells {:start {:id     :test/ns-cell
                                   :schema {:input [:map] :output [:map]}}}
                   :edges {:start :end}})]
      (is (= :test/non-strict-wf (:id result))))))

;; ===== 6. Non-strict mode with invalid :on-error target still throws =====

(deftest non-strict-invalid-target-still-throws-test
  (testing "Non-strict mode: :on-error with invalid target still fails"
    (is (thrown-with-msg? Exception #"on-error.*ghost"
          (manifest/validate-manifest
           {:id :test/ns-bad-target-wf
            :cells {:start {:id       :test/ns-bad-cell
                            :schema   {:input [:map] :output [:map]}
                            :on-error :ghost}}
            :edges {:start :end}})))))
