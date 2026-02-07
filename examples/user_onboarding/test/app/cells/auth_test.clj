(ns app.cells.auth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            ;; Load the cell definitions
            [app.cells.auth]))

(use-fixtures :each (fn [f]
                      (when-not (cell/get-cell :auth/parse-request)
                        (require 'app.cells.auth :reload))
                      (f)))

(deftest parse-request-success-test
  (testing "parse-request extracts credentials successfully"
    (let [result (dev/test-cell :auth/parse-request
                  {:input {:http-request {:headers {"content-type" "application/json"}
                                          :body    {"username" "alice"
                                                    "token"    "tok_abc123"}}}
                   :resources {}})]
      (is (:pass? result))
      (is (= "alice" (get-in result [:output :user-id])))
      (is (= "tok_abc123" (get-in result [:output :auth-token]))))))

(deftest parse-request-failure-test
  (testing "parse-request fails with missing credentials"
    (let [result (dev/test-cell :auth/parse-request
                  {:input {:http-request {:headers {}
                                          :body    {}}}
                   :resources {}})]
      ;; Handler returns :failure transition but output schema won't have :user-id/:auth-token
      ;; The test-cell should report the schema failure
      (is (= :failure (get-in result [:output :mycelium/transition]))))))

(deftest validate-session-authorized-test
  (testing "validate-session authorizes valid token"
    (let [db {:sessions {"tok_abc123" true}}
          result (dev/test-cell :auth/validate-session
                  {:input {:user-id "alice" :auth-token "tok_abc123"}
                   :resources {:db db}})]
      (is (:pass? result))
      (is (true? (get-in result [:output :session-valid]))))))

(deftest validate-session-unauthorized-test
  (testing "validate-session rejects invalid token"
    (let [db {:sessions {}}
          result (dev/test-cell :auth/validate-session
                  {:input {:user-id "alice" :auth-token "bad_token"}
                   :resources {:db db}})]
      (is (:pass? result))
      (is (false? (get-in result [:output :session-valid]))))))
