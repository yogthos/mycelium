(ns app.cells.ui-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f]
                      (when-not (cell/get-cell :ui/render-home)
                        (require 'app.cells.ui :reload))
                      ;; Load manifest to attach schemas
                      (require 'app.workflows.onboarding :reload)
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
