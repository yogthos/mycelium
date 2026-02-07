(ns app.cells.user-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [app.db :as db]))

(use-fixtures :each (fn [f]
                      (when-not (cell/get-cell :user/fetch-profile)
                        (require 'app.cells.user :reload))
                      ;; Load manifest to attach schemas
                      (require 'app.workflows.onboarding :reload)
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
  (testing "fetch-profile returns not-found when user doesn't exist"
    (with-redefs [db/get-user (fn [_ds _user-id] nil)]
      (let [result (dev/test-cell :user/fetch-profile
                    {:input {:user-id "nobody" :session-valid true}
                     :resources {:db :mock-ds}})]
        (is (= :not-found (get-in result [:output :mycelium/transition])))))))
