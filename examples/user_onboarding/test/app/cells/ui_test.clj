(ns app.cells.ui-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f]
                      (when-not (cell/get-cell :ui/render-home)
                        (require 'app.cells.ui :reload))
                      ;; Load manifests to attach schemas
                      (require 'app.workflows.onboarding :reload)
                      (require 'app.workflows.login :reload)
                      (require 'app.workflows.dashboard :reload)
                      (require 'app.workflows.users :reload)
                      (require 'app.workflows.home :reload)
                      (f)))

(deftest render-home-test
  (testing "render-home produces correct HTTP response with map body"
    (let [result (dev/test-cell :ui/render-home
                  {:input {:profile {:name "Alice Smith" :email "alice@example.com"}}
                   :resources {}})]
      (is (:pass? result))
      (is (= 200 (get-in result [:output :http-response :status])))
      (let [body (get-in result [:output :http-response :body])]
        (is (= "Welcome, Alice Smith!" (:message body)))
        (is (= {:name "Alice Smith" :email "alice@example.com"}
               (:profile body)))))))

(deftest render-login-page-test
  (testing "render-login-page produces HTML with login form"
    (let [result (dev/test-cell :ui/render-login-page
                  {:input {}
                   :resources {}})]
      (is (:pass? result))
      (is (string? (get-in result [:output :html])))
      (is (re-find #"<form" (get-in result [:output :html])))
      (is (re-find #"username" (get-in result [:output :html]))))))

(deftest render-dashboard-test
  (testing "render-dashboard produces HTML with user info"
    (let [result (dev/test-cell :ui/render-dashboard
                  {:input {:profile {:name "Alice Smith" :email "alice@example.com"}
                           :user-id "alice"}
                   :resources {}})]
      (is (:pass? result))
      (is (string? (get-in result [:output :html])))
      (is (re-find #"Alice Smith" (get-in result [:output :html])))
      (is (re-find #"alice@example\.com" (get-in result [:output :html]))))))

(deftest render-error-unauthorized-test
  (testing "render-error produces 401 HTML page"
    (let [result (dev/test-cell :ui/render-error
                  {:input {:error-type :unauthorized
                           :error-message "Invalid or expired session token"}
                   :resources {}})]
      (is (:pass? result))
      (is (= 401 (get-in result [:output :error-status])))
      (is (string? (get-in result [:output :html])))
      (is (re-find #"401" (get-in result [:output :html])))
      (is (re-find #"Unauthorized" (get-in result [:output :html]))))))

(deftest render-error-not-found-test
  (testing "render-error produces 404 HTML page"
    (let [result (dev/test-cell :ui/render-error
                  {:input {:error-type :not-found
                           :error-message "User not found: nobody"}
                   :resources {}})]
      (is (:pass? result))
      (is (= 404 (get-in result [:output :error-status])))
      (is (re-find #"404" (get-in result [:output :html]))))))

(deftest render-user-list-test
  (testing "render-user-list produces HTML with user table"
    (let [result (dev/test-cell :ui/render-user-list
                  {:input {:users [{:id "alice" :name "Alice Smith" :email "alice@example.com"}
                                   {:id "bob" :name "Bob Jones" :email "bob@example.com"}]}
                   :resources {}})]
      (is (:pass? result))
      (is (string? (get-in result [:output :html])))
      (is (re-find #"Alice Smith" (get-in result [:output :html])))
      (is (re-find #"Bob Jones" (get-in result [:output :html]))))))

(deftest render-user-profile-test
  (testing "render-user-profile produces HTML with user details"
    (let [result (dev/test-cell :ui/render-user-profile
                  {:input {:profile {:id "alice" :name "Alice Smith" :email "alice@example.com"}}
                   :resources {}})]
      (is (:pass? result))
      (is (string? (get-in result [:output :html])))
      (is (re-find #"Alice Smith" (get-in result [:output :html])))
      (is (re-find #"alice@example\.com" (get-in result [:output :html]))))))
