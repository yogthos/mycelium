(ns mycelium.todomvc.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.todomvc.db :as db]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus])
  (:import [java.io File]))

(def ^:dynamic *ds* nil)

(defn create-test-db []
  (let [f   (File/createTempFile "todomvc-test-" ".db")
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

(deftest list-todos-empty-test
  (testing "empty table returns empty list"
    (is (= [] (db/list-todos *ds* "all")))))

(deftest create-and-list-test
  (testing "create todo and list it"
    (let [{:keys [id]} (db/create-todo! *ds* "Buy milk")]
      (is (pos-int? id))
      (let [todos (db/list-todos *ds* "all")]
        (is (= 1 (count todos)))
        (is (= "Buy milk" (:title (first todos))))
        (is (= 0 (:completed (first todos))))))))

(deftest filter-active-completed-test
  (testing "filtering by active/completed"
    (db/create-todo! *ds* "Active task")
    (let [{:keys [id]} (db/create-todo! *ds* "Done task")]
      (db/toggle-todo! *ds* id)
      (is (= 1 (count (db/list-todos *ds* "active"))))
      (is (= "Active task" (:title (first (db/list-todos *ds* "active")))))
      (is (= 1 (count (db/list-todos *ds* "completed"))))
      (is (= "Done task" (:title (first (db/list-todos *ds* "completed")))))
      (is (= 2 (count (db/list-todos *ds* "all")))))))

(deftest count-stats-test
  (testing "count-stats returns correct counts"
    (is (= {:active 0 :completed 0 :total 0} (db/count-stats *ds*)))
    (db/create-todo! *ds* "A")
    (let [{:keys [id]} (db/create-todo! *ds* "B")]
      (db/toggle-todo! *ds* id)
      (is (= {:active 1 :completed 1 :total 2} (db/count-stats *ds*))))))

(deftest toggle-todo-test
  (testing "toggle flips completed status"
    (let [{:keys [id]} (db/create-todo! *ds* "Task")]
      (db/toggle-todo! *ds* id)
      (is (= 1 (:completed (first (db/list-todos *ds* "all")))))
      (db/toggle-todo! *ds* id)
      (is (= 0 (:completed (first (db/list-todos *ds* "all"))))))))

(deftest update-todo-title-test
  (testing "update-todo-title! changes the title"
    (let [{:keys [id]} (db/create-todo! *ds* "Old title")]
      (db/update-todo-title! *ds* id "New title")
      (is (= "New title" (:title (first (db/list-todos *ds* "all"))))))))

(deftest delete-todo-test
  (testing "delete-todo! removes the todo"
    (let [{:keys [id]} (db/create-todo! *ds* "To delete")]
      (db/delete-todo! *ds* id)
      (is (= [] (db/list-todos *ds* "all"))))))

(deftest toggle-all-test
  (testing "toggle-all marks all complete when some are active"
    (db/create-todo! *ds* "A")
    (db/create-todo! *ds* "B")
    (db/toggle-all! *ds*)
    (is (every? #(= 1 (:completed %)) (db/list-todos *ds* "all"))))
  (testing "toggle-all marks all incomplete when all are complete"
    (db/toggle-all! *ds*)
    (is (every? #(= 0 (:completed %)) (db/list-todos *ds* "all")))))

(deftest clear-completed-test
  (testing "clear-completed! removes only completed todos"
    (db/create-todo! *ds* "Keep")
    (let [{:keys [id]} (db/create-todo! *ds* "Remove")]
      (db/toggle-todo! *ds* id)
      (db/clear-completed! *ds*)
      (let [remaining (db/list-todos *ds* "all")]
        (is (= 1 (count remaining)))
        (is (= "Keep" (:title (first remaining))))))))
