(ns app.routes
  (:require [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.params :as params]
            [ring.middleware.cookies :as cookies]
            [app.middleware :as mw]
            [app.workflows.onboarding :as onboarding]
            [app.workflows.home :as home]
            [app.workflows.login :as login]
            [app.workflows.dashboard :as dashboard]
            [app.workflows.users :as users]
            [app.workflows.order-summary :as order-summary]))

(defn- onboarding-handler [db]
  (fn [request]
    (let [result (onboarding/run-onboarding db request)]
      (if-let [response (:http-response result)]
        {:status (:status response 200)
         :body   (:body response)}
        ;; Error path — render-error sets :error-status and :error-message
        {:status (:error-status result 500)
         :body   {:error (:error-message result "An error occurred")}}))))

(defn- html-response
  "Build a Ring response from a workflow result containing :html."
  [result]
  (let [status (or (:error-status result) 200)]
    {:status  status
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (:html result)}))

(defn- html-handler
  "Wraps a workflow runner function, returning an HTML Ring response."
  [runner-fn]
  (fn [request]
    (html-response (runner-fn request))))

(defn- login-submit-handler
  "Handles login form submission. On success, sets cookie and redirects to dashboard (PRG pattern)."
  [db]
  (fn [request]
    (let [result (login/run-login-submit db request)]
      (if (and (:session-valid result) (:auth-token result))
        ;; Success — set cookie and redirect to dashboard
        {:status  302
         :headers {"Location" "/dashboard"}
         :cookies {"session-token" {:value     (:auth-token result)
                                    :path      "/"
                                    :http-only true}}}
        ;; Failure — render error page
        (html-response result)))))

(defn- logout-handler
  "Clears session cookie and redirects to home."
  [_request]
  {:status  302
   :headers {"Location" "/"}
   :cookies {"session-token" {:value   ""
                              :path    "/"
                              :max-age 0}}})

(defn app [db]
  (ring/ring-handler
   (ring/router
    [;; JSON API routes — with Muuntaja content negotiation
     ["/api" {:muuntaja   m/instance
              :middleware [muuntaja/format-middleware]}
      ["/health"     {:get {:handler (fn [_] {:status 200 :body {:status "ok"}})}}]
      ["/onboarding" {:post {:handler (onboarding-handler db)}}]]

     ;; HTML routes — no Muuntaja, raw Ring responses
     ["/" {:get {:handler (html-handler #(home/run-home db %))}}]

     ["/login" {:get  {:handler (html-handler (fn [_] (login/run-login-page)))}
                :post {:handler (login-submit-handler db)}}]

     ["/logout" {:get {:handler logout-handler}}]

     ["/dashboard" {:get {:handler (html-handler #(dashboard/run-dashboard db %))}}]

     ["/users" {:get {:handler (html-handler #(users/run-user-list db %))}}]

     ["/users/:id" {:get {:handler (html-handler #(users/run-user-profile db %))}}]

     ["/orders" {:get {:handler (html-handler #(order-summary/run-order-summary db %))}}]])
   (ring/create-default-handler)
   {:middleware [params/wrap-params cookies/wrap-cookies mw/wrap-errors]}))
