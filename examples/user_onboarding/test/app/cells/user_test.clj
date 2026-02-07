(ns app.cells.user-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [app.db :as db]))

(use-fixtures :each (fn [f]
                      (when-not (cell/get-cell :user/fetch-profile)
                        (require 'app.cells.user :reload))
                      ;; Load manifests to attach schemas
                      (require 'app.workflows.onboarding :reload)
                      (require 'app.workflows.users :reload)
                      (f)))

(deftest fetch-profile-found-test
  (testing "fetch-profile returns profile when user exists"
    (with-redefs [db/get-user (fn [_ds user-id]
                                (when (= user-id "alice")
                                  {:id "alice" :name "Alice Smith" :email "alice@example.com"}))]
      (let [result (dev/test-cell :user/fetch-profile
                    {:input {:user-id "alice" :session-valid true}
                     :resources {:db :mock-ds}})]
        (is (:pass? result))
        (is (= {:name "Alice Smith" :email "alice@example.com"}
               (get-in result [:output :profile])))))))

(deftest fetch-profile-not-found-test
  (testing "fetch-profile returns not-found with error context"
    (with-redefs [db/get-user (fn [_ds _user-id] nil)]
      (let [result (dev/test-cell :user/fetch-profile
                    {:input {:user-id "nobody" :session-valid true}
                     :resources {:db :mock-ds}})]
        (is (= :not-found (get-in result [:output :mycelium/transition])))
        (is (= :not-found (get-in result [:output :error-type])))
        (is (string? (get-in result [:output :error-message])))))))

(deftest fetch-all-users-test
  (testing "fetch-all-users returns all users"
    (with-redefs [db/get-all-users (fn [_ds]
                                     [{:id "alice" :name "Alice Smith" :email "alice@example.com"}
                                      {:id "bob" :name "Bob Jones" :email "bob@example.com"}])]
      (let [result (dev/test-cell :user/fetch-all-users
                    {:input {}
                     :resources {:db :mock-ds}})]
        (is (:pass? result))
        (is (= 2 (count (get-in result [:output :users]))))
        (is (= :done (get-in result [:output :mycelium/transition])))))))

(deftest fetch-profile-by-id-found-test
  (testing "fetch-profile-by-id returns full profile when user exists"
    (with-redefs [db/get-user (fn [_ds user-id]
                                (when (= user-id "alice")
                                  {:id "alice" :name "Alice Smith" :email "alice@example.com"}))]
      (let [result (dev/test-cell :user/fetch-profile-by-id
                    {:input {:http-request {:path-params {:id "alice"}}}
                     :resources {:db :mock-ds}})]
        (is (:pass? result))
        (is (= {:id "alice" :name "Alice Smith" :email "alice@example.com"}
               (get-in result [:output :profile])))))))

(deftest fetch-profile-by-id-not-found-test
  (testing "fetch-profile-by-id returns not-found with error context"
    (with-redefs [db/get-user (fn [_ds _user-id] nil)]
      (let [result (dev/test-cell :user/fetch-profile-by-id
                    {:input {:http-request {:path-params {:id "nobody"}}}
                     :resources {:db :mock-ds}})]
        (is (= :not-found (get-in result [:output :mycelium/transition])))
        (is (= :not-found (get-in result [:output :error-type])))))))
