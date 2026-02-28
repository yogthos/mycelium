(ns mycelium.todomvc.cells.ui-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.dev :as dev]
            [mycelium.cell :as cell]
            [mycelium.todomvc.cells.ui]))

(use-fixtures :each
  (fn [f]
    (when-not (cell/get-cell :ui/render-todo-page)
      (require 'mycelium.todomvc.cells.ui :reload))
    (f)))

(deftest render-todo-page-test
  (testing ":ui/render-todo-page renders full page HTML"
    (let [result (dev/test-cell :ui/render-todo-page
                  {:input {:todos  [{:id 1 :title "Buy milk" :completed 0}]
                           :stats  {:active 1 :completed 0 :total 1}
                           :filter "all"}})]
      (is (:pass? result))
      (let [html (get-in result [:output :html])]
        (is (string? html))
        (is (re-find #"Buy milk" html))
        (is (re-find #"todoapp" html))
        (is (re-find #"htmx" html))))))

(deftest render-todo-list-test
  (testing ":ui/render-todo-list renders the list fragment"
    (let [result (dev/test-cell :ui/render-todo-list
                  {:input {:todos  [{:id 1 :title "Test" :completed 1}
                                    {:id 2 :title "Active" :completed 0}]
                           :stats  {:active 1 :completed 1 :total 2}
                           :filter "all"}})]
      (is (:pass? result))
      (let [html (get-in result [:output :html])]
        (is (string? html))
        (is (re-find #"Test" html))
        (is (re-find #"Active" html))
        (is (re-find #"1.*item" html))))))

(deftest render-empty-list-test
  (testing ":ui/render-todo-list with no todos renders no list"
    (let [result (dev/test-cell :ui/render-todo-list
                  {:input {:todos  []
                           :stats  {:active 0 :completed 0 :total 0}
                           :filter "all"}})]
      (is (:pass? result))
      (let [html (get-in result [:output :html])]
        (is (string? html))
        ;; No main section when total is 0
        (is (not (re-find #"todo-list" html)))))))
