(ns mycelium.todomvc.cells.todo
  (:require [mycelium.cell :as cell]
            [mycelium.todomvc.db :as db]))

(defmethod cell/cell-spec :todo/list [_]
  {:id      :todo/list
   :doc     "Fetch filtered list of todos"
   :handler (fn [{:keys [db]} data]
              (assoc data :todos (db/list-todos db (or (:filter data) "all"))))})

(defmethod cell/cell-spec :todo/count-stats [_]
  {:id      :todo/count-stats
   :doc     "Fetch todo count statistics"
   :handler (fn [{:keys [db]} data]
              (assoc data :stats (db/count-stats db)))})

(defmethod cell/cell-spec :todo/create [_]
  {:id      :todo/create
   :doc     "Create a new todo"
   :handler (fn [{:keys [db]} data]
              (let [{:keys [id]} (db/create-todo! db (:title data))]
                (assoc data :created-id id)))})

(defmethod cell/cell-spec :todo/toggle [_]
  {:id      :todo/toggle
   :doc     "Toggle a todo's completed status"
   :handler (fn [{:keys [db]} data]
              (db/toggle-todo! db (:todo-id data))
              data)})

(defmethod cell/cell-spec :todo/update-title [_]
  {:id      :todo/update-title
   :doc     "Update a todo's title"
   :handler (fn [{:keys [db]} data]
              (db/update-todo-title! db (:todo-id data) (:title data))
              data)})

(defmethod cell/cell-spec :todo/delete [_]
  {:id      :todo/delete
   :doc     "Delete a todo"
   :handler (fn [{:keys [db]} data]
              (db/delete-todo! db (:todo-id data))
              data)})

(defmethod cell/cell-spec :todo/toggle-all [_]
  {:id      :todo/toggle-all
   :doc     "Toggle all todos"
   :handler (fn [{:keys [db]} data]
              (db/toggle-all! db)
              data)})

(defmethod cell/cell-spec :todo/clear-completed [_]
  {:id      :todo/clear-completed
   :doc     "Delete all completed todos"
   :handler (fn [{:keys [db]} data]
              (db/clear-completed! db)
              data)})
