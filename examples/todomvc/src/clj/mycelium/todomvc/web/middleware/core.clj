(ns mycelium.todomvc.web.middleware.core
  (:require
    [mycelium.todomvc.env :as env]
    [ring.middleware.defaults :as defaults]
    [ring.middleware.session.cookie :as cookie]))

(defn wrap-base
  [{:keys [metrics site-defaults-config cookie-secret] :as opts}]
  (let [cookie-store (cookie/cookie-store {:key (.getBytes ^String cookie-secret)})]
    (fn [handler]
      (cond-> ((:middleware env/defaults) handler opts)
              true (defaults/wrap-defaults
                     (assoc-in site-defaults-config [:session :store] cookie-store))
              ))))
