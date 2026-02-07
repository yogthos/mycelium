(ns app.routes
  (:require [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.params :as params]
            [app.workflows.onboarding :as onboarding]
            [app.workflows.login :as login]
            [app.workflows.dashboard :as dashboard]
            [app.workflows.users :as users]))

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

(defn- html-response
  "Build a Ring response from a workflow result containing :html."
  [result]
  (let [status (or (:error-status result) 200)]
    {:status  status
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (:html result)}))

(defn- html-handler
  "Wraps a workflow runner function, returning an HTML Ring response.
   runner-fn should return a workflow result map with :html key."
  [runner-fn]
  (fn [request]
    (try
      (html-response (runner-fn request))
      (catch Exception _e
        {:status  500
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body    "<h1>500 Internal Server Error</h1>"}))))

(defn app [db]
  (ring/ring-handler
   (ring/router
    [;; JSON API routes — with Muuntaja content negotiation
     ["/api" {:muuntaja   m/instance
              :middleware [muuntaja/format-middleware]}
      ["/health"     {:get {:handler (fn [_] {:status 200 :body {:status "ok"}})}}]
      ["/onboarding" {:post {:handler (onboarding-handler db)}}]]

     ;; HTML routes — no Muuntaja, raw Ring responses
     ["/login" {:get  {:handler (html-handler
                                 (fn [_request]
                                   (login/run-login-page)))}
                :post {:handler (html-handler
                                 (fn [request]
                                   (login/run-login-submit db request)))}}]

     ["/dashboard" {:get {:handler (html-handler
                                    (fn [request]
                                      (dashboard/run-dashboard db request)))}}]

     ["/users" {:get {:handler (html-handler
                                (fn [_request]
                                  (users/run-user-list db)))}}]

     ["/users/:id" {:get {:handler (html-handler
                                    (fn [request]
                                      (users/run-user-profile db request)))}}]])
   (ring/create-default-handler)
   {:middleware [params/wrap-params]}))
