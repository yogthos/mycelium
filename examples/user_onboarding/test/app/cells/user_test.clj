(ns app.cells.user-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f]
                      ;; Ensure cell is registered (may have been cleared by other test fixtures)
                      (when-not (cell/get-cell :user/fetch-profile)
                        (require 'app.cells.user :reload))
                      (f)))

(deftest fetch-profile-found-test
  (testing "fetch-profile returns profile when user exists"
    (let [db {:users {"alice" {:name "Alice Smith" :email "alice@example.com"}}}
          result (dev/test-cell :user/fetch-profile
                  {:input {:user-id "alice" :session-valid true}
                   :resources {:db db}})]
      (is (:pass? result))
      (is (= {:name "Alice Smith" :email "alice@example.com"}
             (get-in result [:output :profile]))))))

(deftest fetch-profile-not-found-test
  (testing "fetch-profile returns not-found when user doesn't exist"
    (let [db {:users {}}
          result (dev/test-cell :user/fetch-profile
                  {:input {:user-id "nobody" :session-valid true}
                   :resources {:db db}})]
      ;; :not-found transition but missing :profile key
      (is (= :not-found (get-in result [:output :mycelium/transition]))))))
