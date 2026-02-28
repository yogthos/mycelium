(ns mycelium.todomvc.web.routes.pages
  (:require [integrant.core :as ig]
            [mycelium.middleware :as mw]
            [mycelium.todomvc.workflows.todo :as wf]
            [reitit.ring.middleware.parameters :as parameters]))

(defn- wf-handler [compiled db]
  (mw/workflow-handler compiled {:resources {:db db}}))

(defn page-routes [db]
  [["/"
    {:get {:handler (wf-handler wf/compiled-page db)}}]
   ["/todos"
    {:post {:handler (wf-handler wf/compiled-add db)}}]
   ["/todos/toggle-all"
    {:post {:handler (wf-handler wf/compiled-toggle-all db)}}]
   ["/todos/clear-completed"
    {:post {:handler (wf-handler wf/compiled-clear db)}}]
   ["/todos/:id/toggle"
    {:patch {:handler (wf-handler wf/compiled-toggle db)}}]
   ["/todos/:id"
    {:delete  {:handler (wf-handler wf/compiled-delete db)}
     :put     {:handler (wf-handler wf/compiled-update db)}}]])

(derive :reitit.routes/pages :reitit/routes)

(defmethod ig/init-key :reitit.routes/pages
  [_ {:keys [db]}]
  (fn []
    ["" {:middleware [parameters/parameters-middleware]}
     (page-routes db)]))
