(ns mycelium.todomvc.env
  (:require
    [clojure.tools.logging :as log]
    [mycelium.todomvc.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[todomvc starting using the development or test profile]=-"))
   :start      (fn []
                 (log/info "\n-=[todomvc started successfully using the development or test profile]=-"))
   :stop       (fn []
                 (log/info "\n-=[todomvc has shut down successfully]=-"))
   :middleware wrap-dev
   :opts       {:profile       :dev}})
