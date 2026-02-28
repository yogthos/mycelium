(ns mycelium.todomvc.cells.todo-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.dev :as dev]
            [mycelium.cell :as cell]
            [mycelium.todomvc.db :as db]
            [mycelium.todomvc.cells.todo]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus])
  (:import [java.io File]))

(def ^:dynamic *ds* nil)

(defn create-test-db []
  (let [f   (File/createTempFile "todomvc-cell-test-" ".db")
        _   (.deleteOnExit f)
        url (str "jdbc:sqlite:" (.getAbsolutePath f))
        ds  (jdbc/get-datasource {:jdbcUrl url})]
    (migratus/migrate {:store         :database
                       :migration-dir "migrations"
                       :db            {:datasource ds}})
    ds))

(use-fixtures :each
  (fn [f]
    (when-not (cell/get-cell :todo/list)
      (require 'mycelium.todomvc.cells.todo :reload))
    (binding [*ds* (create-test-db)]
      (f))))

(deftest todo-list-cell-test
  (testing ":todo/list returns todos"
    (db/create-todo! *ds* "Test item")
    (let [result (dev/test-cell :todo/list
                  {:input     {:filter "all"}
                   :resources {:db *ds*}})]
      (is (:pass? result))
      (is (= 1 (count (get-in result [:output :todos]))))
      (is (= "Test item" (:title (first (get-in result [:output :todos]))))))))

(deftest todo-count-stats-cell-test
  (testing ":todo/count-stats returns stats"
    (db/create-todo! *ds* "A")
    (let [{:keys [id]} (db/create-todo! *ds* "B")]
      (db/toggle-todo! *ds* id))
    (let [result (dev/test-cell :todo/count-stats
                  {:input     {}
                   :resources {:db *ds*}})]
      (is (:pass? result))
      (is (= {:active 1 :completed 1 :total 2}
             (get-in result [:output :stats]))))))

(deftest todo-create-cell-test
  (testing ":todo/create inserts a new todo"
    (let [result (dev/test-cell :todo/create
                  {:input     {:title "New todo"}
                   :resources {:db *ds*}})]
      (is (:pass? result))
      (is (pos-int? (get-in result [:output :created-id])))
      (is (= 1 (count (db/list-todos *ds* "all")))))))

(deftest todo-toggle-cell-test
  (testing ":todo/toggle flips completed"
    (let [{:keys [id]} (db/create-todo! *ds* "Task")
          result (dev/test-cell :todo/toggle
                  {:input     {:todo-id id}
                   :resources {:db *ds*}})]
      (is (:pass? result))
      (is (= 1 (:completed (first (db/list-todos *ds* "all"))))))))

(deftest todo-update-title-cell-test
  (testing ":todo/update-title changes the title"
    (let [{:keys [id]} (db/create-todo! *ds* "Old")]
      (dev/test-cell :todo/update-title
        {:input     {:todo-id id :title "New"}
         :resources {:db *ds*}})
      (is (= "New" (:title (first (db/list-todos *ds* "all"))))))))

(deftest todo-delete-cell-test
  (testing ":todo/delete removes the todo"
    (let [{:keys [id]} (db/create-todo! *ds* "Bye")]
      (dev/test-cell :todo/delete
        {:input     {:todo-id id}
         :resources {:db *ds*}})
      (is (= [] (db/list-todos *ds* "all"))))))

(deftest todo-toggle-all-cell-test
  (testing ":todo/toggle-all marks all complete"
    (db/create-todo! *ds* "A")
    (db/create-todo! *ds* "B")
    (dev/test-cell :todo/toggle-all
      {:input     {}
       :resources {:db *ds*}})
    (is (every? #(= 1 (:completed %)) (db/list-todos *ds* "all")))))

(deftest todo-clear-completed-cell-test
  (testing ":todo/clear-completed removes completed todos"
    (db/create-todo! *ds* "Keep")
    (let [{:keys [id]} (db/create-todo! *ds* "Remove")]
      (db/toggle-todo! *ds* id)
      (dev/test-cell :todo/clear-completed
        {:input     {}
         :resources {:db *ds*}})
      (is (= 1 (count (db/list-todos *ds* "all"))))
      (is (= "Keep" (:title (first (db/list-todos *ds* "all"))))))))
