(ns mycelium.todomvc.workflows.todo-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.todomvc.workflows.todo :as wf]
            [mycelium.todomvc.db :as db]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus])
  (:import [java.io File]))

(def ^:dynamic *ds* nil)

(defn create-test-db []
  (let [f   (File/createTempFile "todomvc-wf-test-" ".db")
        _   (.deleteOnExit f)
        url (str "jdbc:sqlite:" (.getAbsolutePath f))
        ds  (jdbc/get-datasource {:jdbcUrl url})]
    (migratus/migrate {:store         :database
                       :migration-dir "migrations"
                       :db            {:datasource ds}})
    ds))

(use-fixtures :each
  (fn [f]
    (binding [*ds* (create-test-db)]
      (f))))

(deftest page-workflow-test
  (testing "page workflow renders full HTML with no todos"
    (let [result (wf/run-page *ds* {:query-params {"filter" "all"}})]
      (is (string? (:html result)))
      (is (re-find #"todoapp" (:html result)))))
  (testing "page workflow renders todos"
    (db/create-todo! *ds* "Test item")
    (let [result (wf/run-page *ds* {:query-params {"filter" "all"}})]
      (is (re-find #"Test item" (:html result))))))

(deftest add-workflow-test
  (testing "add workflow creates todo and returns list fragment"
    (let [result (wf/run-add *ds* {:form-params {"title" "New task"}
                                    :query-params {"filter" "all"}})]
      (is (string? (:html result)))
      (is (re-find #"New task" (:html result)))
      (is (= 1 (count (db/list-todos *ds* "all")))))))

(deftest toggle-workflow-test
  (testing "toggle workflow flips todo and returns list fragment"
    (let [{:keys [id]} (db/create-todo! *ds* "Toggle me")]
      (let [result (wf/run-toggle *ds* {:path-params {:id (str id)}
                                         :query-params {"filter" "all"}})]
        (is (string? (:html result)))
        (is (= 1 (:completed (first (db/list-todos *ds* "all")))))))))

(deftest delete-workflow-test
  (testing "delete workflow removes todo and returns list fragment"
    (let [{:keys [id]} (db/create-todo! *ds* "Delete me")]
      (let [result (wf/run-delete *ds* {:path-params {:id (str id)}
                                         :query-params {"filter" "all"}})]
        (is (string? (:html result)))
        (is (= 0 (count (db/list-todos *ds* "all"))))))))

(deftest update-workflow-test
  (testing "update workflow changes title and returns list fragment"
    (let [{:keys [id]} (db/create-todo! *ds* "Old title")]
      (let [result (wf/run-update *ds* {:path-params  {:id (str id)}
                                         :form-params  {"title" "New title"}
                                         :query-params {"filter" "all"}})]
        (is (string? (:html result)))
        (is (re-find #"New title" (:html result)))))))

(deftest toggle-all-workflow-test
  (testing "toggle-all workflow marks all complete and returns list fragment"
    (db/create-todo! *ds* "A")
    (db/create-todo! *ds* "B")
    (let [result (wf/run-toggle-all *ds* {:query-params {"filter" "all"}})]
      (is (string? (:html result)))
      (is (every? #(= 1 (:completed %)) (db/list-todos *ds* "all"))))))

(deftest clear-workflow-test
  (testing "clear workflow removes completed todos and returns list fragment"
    (db/create-todo! *ds* "Keep")
    (let [{:keys [id]} (db/create-todo! *ds* "Remove")]
      (db/toggle-todo! *ds* id)
      (let [result (wf/run-clear *ds* {:query-params {"filter" "all"}})]
        (is (string? (:html result)))
        (is (= 1 (count (db/list-todos *ds* "all"))))))))
