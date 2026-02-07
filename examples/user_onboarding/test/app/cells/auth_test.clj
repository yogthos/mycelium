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
                      (f)))

(deftest parse-request-success-test
  (testing "parse-request extracts credentials from string-keyed body"
    (let [result (dev/test-cell :auth/parse-request
                  {:input {:http-request {:headers {"content-type" "application/json"}
                                          :body    {"username" "alice"
                                                    "token"    "tok_abc123"}}}
                   :resources {}})]
      (is (:pass? result))
      (is (= "alice" (get-in result [:output :user-id])))
      (is (= "tok_abc123" (get-in result [:output :auth-token]))))))

(deftest parse-request-keyword-keys-test
  (testing "parse-request extracts credentials from keyword-keyed body (Muuntaja)"
    (let [result (dev/test-cell :auth/parse-request
                  {:input {:http-request {:headers {"content-type" "application/json"}
                                          :body    {:username "bob"
                                                    :token    "tok_bob456"}}}
                   :resources {}})]
      (is (:pass? result))
      (is (= "bob" (get-in result [:output :user-id])))
      (is (= "tok_bob456" (get-in result [:output :auth-token]))))))

(deftest parse-request-failure-test
  (testing "parse-request fails with missing credentials"
    (let [result (dev/test-cell :auth/parse-request
                  {:input {:http-request {:headers {}
                                          :body    {}}}
                   :resources {}})]
      (is (= :failure (get-in result [:output :mycelium/transition]))))))

(deftest validate-session-authorized-test
  (testing "validate-session authorizes valid token"
    (with-redefs [db/get-session (fn [_ds token]
                                   (when (= token "tok_abc123")
                                     {:user_id "alice" :valid 1}))]
      (let [result (dev/test-cell :auth/validate-session
                    {:input {:user-id "alice" :auth-token "tok_abc123"}
                     :resources {:db :mock-ds}})]
        (is (:pass? result))
        (is (true? (get-in result [:output :session-valid])))))))

(deftest validate-session-unauthorized-test
  (testing "validate-session rejects invalid token"
    (with-redefs [db/get-session (fn [_ds _token] nil)]
      (let [result (dev/test-cell :auth/validate-session
                    {:input {:user-id "alice" :auth-token "bad_token"}
                     :resources {:db :mock-ds}})]
        (is (:pass? result))
        (is (false? (get-in result [:output :session-valid])))))))

(deftest validate-session-expired-test
  (testing "validate-session rejects expired token"
    (with-redefs [db/get-session (fn [_ds _token]
                                   {:user_id "alice" :valid 0})]
      (let [result (dev/test-cell :auth/validate-session
                    {:input {:user-id "alice" :auth-token "tok_expired"}
                     :resources {:db :mock-ds}})]
        (is (:pass? result))
        (is (false? (get-in result [:output :session-valid])))))))
