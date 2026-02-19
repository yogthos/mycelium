(ns app.middleware
  (:require [selmer.parser :as selmer]))

(defn logging-interceptor
  "Maestro post-interceptor that logs cell transitions."
  [fsm-state _resources]
  (let [last-state    (:last-state-id fsm-state)
        current-state (:current-state-id fsm-state)]
    (when last-state
      (println (str "[workflow] " last-state " → " current-state)))
    fsm-state))

(def workflow-opts
  "Standard workflow opts for the app: logging interceptor."
  {:post logging-interceptor})

(defn wrap-errors
  "Ring middleware that catches exceptions and returns an HTML error page."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (println (str "[error] " (.getMessage e)))
        (.printStackTrace e)
        {:status  500
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body    (selmer/render-file "templates/error.html"
                                      {:status  500
                                       :title   "Server Error"
                                       :message (.getMessage e)})}))))
