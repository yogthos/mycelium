(ns app.routes
  (:require [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [app.workflows.onboarding :as onboarding]))

(defn onboarding-handler [db]
  (fn [request]
    (try
      (let [result   (onboarding/run-onboarding db request)
            response (:http-response result)]
        {:status (:status response 200)
         :body   (:body response)})
      (catch Exception e
        (let [msg (ex-message e)]
          {:status 401
           :body   {:error msg}})))))

(defn app [db]
  (ring/ring-handler
   (ring/router
    [["/api/health"     {:get {:handler (fn [_] {:status 200 :body {:status "ok"}})}}]
     ["/api/onboarding" {:post {:handler (onboarding-handler db)}}]]
    {:data {:muuntaja   m/instance
            :middleware [muuntaja/format-middleware]}})
   (ring/create-default-handler)))
