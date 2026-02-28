(ns mycelium.todomvc.cells.ui
  (:require [mycelium.cell :as cell]
            [selmer.parser :as selmer]))

(defmethod cell/cell-spec :ui/render-todo-page [_]
  {:id      :ui/render-todo-page
   :doc     "Render the full TodoMVC page"
   :handler (fn [_resources data]
              (assoc data :html
                     (selmer/render-file "templates/todo-page.html"
                                         {:todos  (:todos data)
                                          :stats  (:stats data)
                                          :filter (or (:filter data) "all")})))})

(defmethod cell/cell-spec :ui/render-todo-list [_]
  {:id      :ui/render-todo-list
   :doc     "Render the todo list fragment for HTMX swaps"
   :handler (fn [_resources data]
              (assoc data :html
                     (selmer/render-file "templates/partials/todo-list.html"
                                         {:todos  (:todos data)
                                          :stats  (:stats data)
                                          :filter (or (:filter data) "all")})))})
