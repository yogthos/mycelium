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
      (let [dispatches {:found     (fn [d] (:profile d))
                        :not-found (fn [d] (:error-type d))}
            result (dev/test-cell :user/fetch-profile
                    {:input {:user-id "alice" :session-valid true}
                     :resources {:db :mock-ds}
                     :dispatches dispatches})]
        (is (:pass? result))
        (is (= {:name "Alice Smith" :email "alice@example.com"}
               (get-in result [:output :profile])))
        (is (= :found (:matched-dispatch result)))))))

(deftest fetch-profile-not-found-test
  (testing "fetch-profile returns not-found with error context"
    (with-redefs [db/get-user (fn [_ds _user-id] nil)]
      (let [dispatches {:found     (fn [d] (:profile d))
                        :not-found (fn [d] (:error-type d))}
            result (dev/test-cell :user/fetch-profile
                    {:input {:user-id "nobody" :session-valid true}
                     :resources {:db :mock-ds}
                     :dispatches dispatches})]
        (is (= :not-found (:matched-dispatch result)))
        (is (= :not-found (get-in result [:output :error-type])))
        (is (string? (get-in result [:output :error-message])))))))

(deftest fetch-all-users-test
  (testing "fetch-all-users returns all users"
    (with-redefs [db/get-all-users (fn [_ds]
                                     [{:id "alice" :name "Alice Smith" :email "alice@example.com"}
                                      {:id "bob" :name "Bob Jones" :email "bob@example.com"}])]
      (let [dispatches {:done (fn [_] true)}
            result (dev/test-cell :user/fetch-all-users
                    {:input {}
                     :resources {:db :mock-ds}
                     :dispatches dispatches})]
        (is (:pass? result))
        (is (= 2 (count (get-in result [:output :users]))))
        (is (= :done (:matched-dispatch result)))))))

(deftest fetch-profile-by-id-found-test
  (testing "fetch-profile-by-id returns full profile when user exists"
    (with-redefs [db/get-user (fn [_ds user-id]
                                (when (= user-id "alice")
                                  {:id "alice" :name "Alice Smith" :email "alice@example.com"}))]
      (let [dispatches {:found     (fn [d] (:profile d))
                        :not-found (fn [d] (:error-type d))}
            result (dev/test-cell :user/fetch-profile-by-id
                    {:input {:http-request {:path-params {:id "alice"}}}
                     :resources {:db :mock-ds}
                     :dispatches dispatches})]
        (is (:pass? result))
        (is (= {:id "alice" :name "Alice Smith" :email "alice@example.com"}
               (get-in result [:output :profile])))
        (is (= :found (:matched-dispatch result)))))))

(deftest fetch-profile-by-id-not-found-test
  (testing "fetch-profile-by-id returns not-found with error context"
    (with-redefs [db/get-user (fn [_ds _user-id] nil)]
      (let [dispatches {:found     (fn [d] (:profile d))
                        :not-found (fn [d] (:error-type d))}
            result (dev/test-cell :user/fetch-profile-by-id
                    {:input {:http-request {:path-params {:id "nobody"}}}
                     :resources {:db :mock-ds}
                     :dispatches dispatches})]
        (is (= :not-found (:matched-dispatch result)))
        (is (= :not-found (get-in result [:output :error-type])))))))

;; ===== test-transitions for multi-transition cells =====

(deftest fetch-profile-test-transitions-test
  (testing "test-transitions covers both fetch-profile dispatches"
    (with-redefs [db/get-user (fn [_ds user-id]
                                (when (= user-id "alice")
                                  {:id "alice" :name "Alice Smith" :email "alice@example.com"}))]
      (let [dispatches {:found     (fn [d] (:profile d))
                        :not-found (fn [d] (:error-type d))}
            results (dev/test-transitions :user/fetch-profile
                      {:found     {:input {:user-id "alice" :session-valid true}
                                   :resources {:db :mock-ds}
                                   :dispatches dispatches}
                       :not-found {:input {:user-id "nobody" :session-valid true}
                                   :resources {:db :mock-ds}
                                   :dispatches dispatches}})]
        (is (true? (get-in results [:found :pass?])))
        (is (true? (get-in results [:not-found :pass?])))))))

(deftest fetch-profile-by-id-test-transitions-test
  (testing "test-transitions covers both fetch-profile-by-id dispatches"
    (with-redefs [db/get-user (fn [_ds user-id]
                                (when (= user-id "alice")
                                  {:id "alice" :name "Alice Smith" :email "alice@example.com"}))]
      (let [dispatches {:found     (fn [d] (:profile d))
                        :not-found (fn [d] (:error-type d))}
            results (dev/test-transitions :user/fetch-profile-by-id
                      {:found     {:input {:http-request {:path-params {:id "alice"}}}
                                   :resources {:db :mock-ds}
                                   :dispatches dispatches}
                       :not-found {:input {:http-request {:path-params {:id "nobody"}}}
                                   :resources {:db :mock-ds}
                                   :dispatches dispatches}})]
        (is (true? (get-in results [:found :pass?])))
        (is (true? (get-in results [:not-found :pass?])))))))
