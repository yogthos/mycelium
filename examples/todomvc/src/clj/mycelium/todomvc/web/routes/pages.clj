(ns mycelium.todomvc.web.routes.pages
  (:require [integrant.core :as ig]
            [mycelium.todomvc.workflows.todo :as wf]
            [reitit.ring.middleware.parameters :as parameters]))

(defn- html-response [result]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (:html result)})

(defn- run-and-respond [runner-fn db request]
  (html-response (runner-fn db request)))

(defn page-routes [db]
  [["/"
    {:get {:handler (fn [req] (run-and-respond wf/run-page db req))}}]
   ["/todos"
    {:post {:handler (fn [req] (run-and-respond wf/run-add db req))}}]
   ["/todos/toggle-all"
    {:post {:handler (fn [req] (run-and-respond wf/run-toggle-all db req))}}]
   ["/todos/clear-completed"
    {:post {:handler (fn [req] (run-and-respond wf/run-clear db req))}}]
   ["/todos/:id/toggle"
    {:patch {:handler (fn [req] (run-and-respond wf/run-toggle db req))}}]
   ["/todos/:id"
    {:delete  {:handler (fn [req] (run-and-respond wf/run-delete db req))}
     :put     {:handler (fn [req] (run-and-respond wf/run-update db req))}}]])

(derive :reitit.routes/pages :reitit/routes)

(defmethod ig/init-key :reitit.routes/pages
  [_ {:keys [db]}]
  (fn []
    ["" {:middleware [parameters/parameters-middleware]}
     (page-routes db)]))
