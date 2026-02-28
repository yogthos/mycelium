(ns mycelium.todomvc.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[todomvc starting]=-"))
   :start      (fn []
                 (log/info "\n-=[todomvc started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[todomvc has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :prod}})
