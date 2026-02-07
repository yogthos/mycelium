(ns mycelium.schema-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Helper: register a test cell ---
(defn- register-test-cell! []
  (cell/register-cell!
   {:id          :test/cell-a
    :handler     (fn [_ data] (assoc data :y 42 :mycelium/transition :success))
    :schema      {:input  [:map [:x :int]]
                  :output [:map [:y :int]]}
    :transitions #{:success :failure}}))

;; ===== validate-input tests =====

(deftest validate-input-passes-valid-data-test
  (testing "validate-input passes valid data (including extra passthrough keys)"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (nil? (schema/validate-input cell {:x 1 :extra "ignored"}))))))

(deftest validate-input-fails-missing-key-test
  (testing "validate-input fails on missing required key"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (some? (schema/validate-input cell {:no-x true}))))))

(deftest validate-input-fails-wrong-type-test
  (testing "validate-input fails on wrong type"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (some? (schema/validate-input cell {:x "not-an-int"}))))))

;; ===== validate-output tests =====

(deftest validate-output-passes-valid-test
  (testing "validate-output passes valid data + valid transition"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (nil? (schema/validate-output cell {:y 42 :mycelium/transition :success :extra true}))))))

(deftest validate-output-fails-missing-key-test
  (testing "validate-output fails on missing output key"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (some? (schema/validate-output cell {:mycelium/transition :success}))))))

(deftest validate-output-fails-invalid-transition-test
  (testing "validate-output fails on invalid transition keyword"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (some? (schema/validate-output cell {:y 42 :mycelium/transition :bogus}))))))

(deftest validate-output-fails-missing-transition-test
  (testing "validate-output fails on missing :mycelium/transition"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (some? (schema/validate-output cell {:y 42}))))))

;; ===== Pre interceptor tests =====

(deftest pre-interceptor-passes-valid-test
  (testing "Pre interceptor passes valid data through unchanged"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          pre         (schema/make-pre-interceptor state->cell)
          fsm-state   {:current-state-id :test/cell-a
                       :data             {:x 10}
                       :trace            []}]
      (is (= fsm-state (pre fsm-state {}))))))

(deftest pre-interceptor-redirects-invalid-test
  (testing "Pre interceptor redirects to ::fsm/error on invalid input, attaches :mycelium/schema-error"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          pre         (schema/make-pre-interceptor state->cell)
          fsm-state   {:current-state-id :test/cell-a
                       :data             {:x "wrong"}
                       :trace            []}
          result      (pre fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (is (some? (get-in result [:data :mycelium/schema-error]))))))

(deftest pre-interceptor-skips-terminal-states-test
  (testing "Pre interceptor skips terminal states (::end, ::error, ::halt)"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          pre         (schema/make-pre-interceptor state->cell)]
      (doseq [terminal [::fsm/end ::fsm/error ::fsm/halt]]
        (let [fsm-state {:current-state-id terminal :data {:x 1} :trace []}]
          (is (= fsm-state (pre fsm-state {}))
              (str "Should skip " terminal)))))))

;; ===== Post interceptor tests =====

(deftest post-interceptor-passes-valid-test
  (testing "Post interceptor passes valid output through (looks up cell via :last-state-id)"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          post        (schema/make-post-interceptor state->cell)
          fsm-state   {:last-state-id    :test/cell-a
                       :current-state-id :some/next
                       :data             {:x 10 :y 42 :mycelium/transition :success}
                       :trace            []}]
      (is (= fsm-state (post fsm-state {}))))))

(deftest post-interceptor-catches-invalid-output-test
  (testing "Post interceptor catches invalid output → redirects to ::fsm/error"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          post        (schema/make-post-interceptor state->cell)
          fsm-state   {:last-state-id    :test/cell-a
                       :current-state-id :some/next
                       :data             {:x 10 :mycelium/transition :success}
                       :trace            []}
          result      (post fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (is (some? (get-in result [:data :mycelium/schema-error]))))))

(deftest post-interceptor-catches-bad-transition-test
  (testing "Post interceptor catches bad transition → redirects to ::fsm/error"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          post        (schema/make-post-interceptor state->cell)
          fsm-state   {:last-state-id    :test/cell-a
                       :current-state-id :some/next
                       :data             {:x 10 :y 42 :mycelium/transition :bogus}
                       :trace            []}
          result      (post fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (is (some? (get-in result [:data :mycelium/schema-error]))))))

;; ===== wrap-async-callback tests =====

(deftest wrap-async-callback-forwards-valid-test
  (testing "wrap-async-callback forwards valid output to original callback"
    (register-test-cell!)
    (let [cell     (cell/get-cell! :test/cell-a)
          result   (promise)
          err      (promise)
          wrapped  (schema/wrap-async-callback cell #(deliver result %) #(deliver err %))]
      (wrapped {:y 42 :mycelium/transition :success})
      (is (= {:y 42 :mycelium/transition :success} (deref result 1000 :timeout)))
      (is (= :timeout (deref err 100 :timeout))))))

(deftest wrap-async-callback-rejects-invalid-test
  (testing "wrap-async-callback calls error-callback on invalid output"
    (register-test-cell!)
    (let [cell     (cell/get-cell! :test/cell-a)
          result   (promise)
          err      (promise)
          wrapped  (schema/wrap-async-callback cell #(deliver result %) #(deliver err %))]
      (wrapped {:mycelium/transition :success}) ;; missing :y
      (is (= :timeout (deref result 100 :timeout)))
      (is (not= :timeout (deref err 1000 :timeout))))))
