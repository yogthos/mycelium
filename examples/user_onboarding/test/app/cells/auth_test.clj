(ns app.cells.auth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [app.db :as db]
            ;; Load the cell definitions
            [app.cells.auth]))

(use-fixtures :each (fn [f]
                      (when-not (cell/get-cell :auth/parse-request)
                        (require 'app.cells.auth :reload))
                      ;; Load manifests to attach schemas
                      (require 'app.workflows.onboarding :reload)
                      (require 'app.workflows.login :reload)
                      (require 'app.workflows.dashboard :reload)
                      (require 'app.workflows.home :reload)
                      (f)))

(deftest parse-request-success-test
  (testing "parse-request extracts credentials from string-keyed body"
    (let [dispatches [[:success (fn [d] (:auth-token d))]
                      [:failure (fn [d] (:error-type d))]]
          result (dev/test-cell :auth/parse-request
                  {:input {:http-request {:headers {"content-type" "application/json"}
                                          :body    {"username" "alice"
                                                    "token"    "tok_abc123"}}}
                   :resources {}
                   :dispatches dispatches})]
      (is (:pass? result))
      (is (= "alice" (get-in result [:output :user-id])))
      (is (= "tok_abc123" (get-in result [:output :auth-token])))
      (is (= :success (:matched-dispatch result))))))

(deftest parse-request-keyword-keys-test
  (testing "parse-request extracts credentials from keyword-keyed body (Muuntaja)"
    (let [dispatches [[:success (fn [d] (:auth-token d))]
                      [:failure (fn [d] (:error-type d))]]
          result (dev/test-cell :auth/parse-request
                  {:input {:http-request {:headers {"content-type" "application/json"}
                                          :body    {:username "bob"
                                                    :token    "tok_bob456"}}}
                   :resources {}
                   :dispatches dispatches})]
      (is (:pass? result))
      (is (= "bob" (get-in result [:output :user-id])))
      (is (= "tok_bob456" (get-in result [:output :auth-token]))))))

(deftest parse-request-failure-test
  (testing "parse-request fails with missing credentials and sets error context"
    (let [dispatches [[:success (fn [d] (:auth-token d))]
                      [:failure (fn [d] (:error-type d))]]
          result (dev/test-cell :auth/parse-request
                  {:input {:http-request {:headers {}
                                          :body    {}}}
                   :resources {}
                   :dispatches dispatches})]
      (is (= :failure (:matched-dispatch result)))
      (is (= :bad-request (get-in result [:output :error-type])))
      (is (string? (get-in result [:output :error-message]))))))

(deftest validate-session-authorized-test
  (testing "validate-session authorizes valid token and sets user-id"
    (with-redefs [db/get-session (fn [_ds token]
                                   (when (= token "tok_abc123")
                                     {:user_id "alice" :valid 1}))]
      (let [dispatches [[:authorized   (fn [d] (:session-valid d))]
                        [:unauthorized (fn [d] (not (:session-valid d)))]]
            result (dev/test-cell :auth/validate-session
                    {:input {:user-id "alice" :auth-token "tok_abc123"}
                     :resources {:db :mock-ds}
                     :dispatches dispatches})]
        (is (:pass? result))
        (is (true? (get-in result [:output :session-valid])))
        (is (= "alice" (get-in result [:output :user-id])))
        (is (= :authorized (:matched-dispatch result)))))))

(deftest validate-session-unauthorized-test
  (testing "validate-session rejects invalid token with error context"
    (with-redefs [db/get-session (fn [_ds _token] nil)]
      (let [dispatches [[:authorized   (fn [d] (:session-valid d))]
                        [:unauthorized (fn [d] (not (:session-valid d)))]]
            result (dev/test-cell :auth/validate-session
                    {:input {:user-id "alice" :auth-token "bad_token"}
                     :resources {:db :mock-ds}
                     :dispatches dispatches})]
        (is (:pass? result))
        (is (false? (get-in result [:output :session-valid])))
        (is (= :unauthorized (get-in result [:output :error-type])))
        (is (string? (get-in result [:output :error-message])))))))

(deftest validate-session-expired-test
  (testing "validate-session rejects expired token"
    (with-redefs [db/get-session (fn [_ds _token]
                                   {:user_id "alice" :valid 0})]
      (let [dispatches [[:authorized   (fn [d] (:session-valid d))]
                        [:unauthorized (fn [d] (not (:session-valid d)))]]
            result (dev/test-cell :auth/validate-session
                    {:input {:user-id "alice" :auth-token "tok_expired"}
                     :resources {:db :mock-ds}
                     :dispatches dispatches})]
        (is (:pass? result))
        (is (false? (get-in result [:output :session-valid])))
        (is (= :unauthorized (:matched-dispatch result)))))))

(deftest extract-session-success-test
  (testing "extract-session reads token from query params"
    (let [dispatches [[:success (fn [d] (:auth-token d))]
                      [:failure (fn [d] (:error-type d))]]
          result (dev/test-cell :auth/extract-session
                  {:input {:http-request {:query-params {:token "tok_abc123"}}}
                   :resources {}
                   :dispatches dispatches})]
      (is (:pass? result))
      (is (= "tok_abc123" (get-in result [:output :auth-token])))
      (is (= :success (:matched-dispatch result))))))

(deftest extract-session-failure-test
  (testing "extract-session fails when no token in query params"
    (let [dispatches [[:success (fn [d] (:auth-token d))]
                      [:failure (fn [d] (:error-type d))]]
          result (dev/test-cell :auth/extract-session
                  {:input {:http-request {:query-params {}}}
                   :resources {}
                   :dispatches dispatches})]
      (is (= :failure (:matched-dispatch result)))
      (is (= :unauthorized (get-in result [:output :error-type]))))))

(deftest extract-cookie-session-success-test
  (testing "extract-cookie-session reads token from cookie"
    (let [dispatches [[:success (fn [d] (:auth-token d))]
                      [:failure (fn [d] (not (:auth-token d)))]]
          result (dev/test-cell :auth/extract-cookie-session
                  {:input {:http-request {:cookies {"session-token" {:value "tok_abc123"}}}}
                   :resources {}
                   :dispatches dispatches})]
      (is (:pass? result))
      (is (= "tok_abc123" (get-in result [:output :auth-token])))
      (is (= :success (:matched-dispatch result))))))

(deftest extract-cookie-session-failure-test
  (testing "extract-cookie-session fails when no session cookie"
    (let [dispatches [[:success (fn [d] (:auth-token d))]
                      [:failure (fn [d] (not (:auth-token d)))]]
          result (dev/test-cell :auth/extract-cookie-session
                  {:input {:http-request {:cookies {}}}
                   :resources {}
                   :dispatches dispatches})]
      (is (= :failure (:matched-dispatch result))))))

;; ===== test-transitions for multi-transition cells =====

(deftest parse-request-test-transitions-test
  (testing "test-transitions covers both parse-request dispatches"
    (let [dispatches [[:success (fn [d] (:auth-token d))]
                      [:failure (fn [d] (:error-type d))]]
          results (dev/test-transitions :auth/parse-request
                    {:success {:input {:http-request {:headers {} :body {"username" "alice" "token" "tok_abc"}}}
                               :dispatches dispatches}
                     :failure {:input {:http-request {:headers {} :body {}}}
                               :dispatches dispatches}})]
      (is (true? (get-in results [:success :pass?])))
      (is (true? (get-in results [:failure :pass?]))))))

(deftest validate-session-test-transitions-test
  (testing "test-transitions covers both validate-session dispatches"
    (with-redefs [db/get-session (fn [_ds token]
                                   (when (= token "tok_valid")
                                     {:user_id "alice" :valid 1}))]
      (let [dispatches [[:authorized   (fn [d] (:session-valid d))]
                        [:unauthorized (fn [d] (not (:session-valid d)))]]
            results (dev/test-transitions :auth/validate-session
                      {:authorized   {:input {:user-id "alice" :auth-token "tok_valid"}
                                      :resources {:db :mock-ds}
                                      :dispatches dispatches}
                       :unauthorized {:input {:user-id "alice" :auth-token "bad"}
                                      :resources {:db :mock-ds}
                                      :dispatches dispatches}})]
        (is (true? (get-in results [:authorized :pass?])))
        (is (true? (get-in results [:unauthorized :pass?])))))))
