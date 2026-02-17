(ns app.cells.ui
  (:require [mycelium.cell :as cell]
            [selmer.parser :as selmer]))

(defmethod cell/cell-spec :ui/render-home [_]
  {:id      :ui/render-home
   :doc     "Render the home page response"
   :handler (fn [_resources data]
              (let [{:keys [name email]} (:profile data)]
                (assoc data
                       :http-response {:status 200
                                       :body   {:message (str "Welcome, " name "!")
                                                :profile {:name name :email email}}}
                       :mycelium/transition :done)))})

(defmethod cell/cell-spec :ui/render-login-page [_]
  {:id      :ui/render-login-page
   :doc     "Render the login form page"
   :handler (fn [_resources data]
              (assoc data
                     :html (selmer/render-file "templates/login.html" {})
                     :mycelium/transition :done))})

(defmethod cell/cell-spec :ui/render-dashboard [_]
  {:id      :ui/render-dashboard
   :doc     "Render the dashboard page with user profile"
   :handler (fn [_resources data]
              (let [{:keys [name email]} (:profile data)]
                (assoc data
                       :html (selmer/render-file "templates/dashboard.html"
                                                 {:name    name
                                                  :email   email
                                                  :user-id (:user-id data)})
                       :mycelium/transition :done)))})

(defmethod cell/cell-spec :ui/render-error [_]
  {:id      :ui/render-error
   :doc     "Render a styled error page"
   :handler (fn [_resources data]
              (let [error-type (:error-type data :server-error)
                    status     (case error-type
                                 :bad-request   400
                                 :unauthorized  401
                                 :not-found     404
                                 500)
                    title      (case error-type
                                 :bad-request   "Bad Request"
                                 :unauthorized  "Unauthorized"
                                 :not-found     "Not Found"
                                 "Server Error")]
                (assoc data
                       :html (selmer/render-file "templates/error.html"
                                                 {:status  status
                                                  :title   title
                                                  :message (:error-message data "An unexpected error occurred")})
                       :error-status status
                       :mycelium/transition :done)))})

(defmethod cell/cell-spec :ui/render-user-list [_]
  {:id      :ui/render-user-list
   :doc     "Render the user list page"
   :handler (fn [_resources data]
              (assoc data
                     :html (selmer/render-file "templates/users.html"
                                               {:users (:users data)})
                     :mycelium/transition :done))})

(defmethod cell/cell-spec :ui/render-user-profile [_]
  {:id      :ui/render-user-profile
   :doc     "Render a single user profile page"
   :handler (fn [_resources data]
              (let [profile (:profile data)]
                (assoc data
                       :html (selmer/render-file "templates/profile.html" profile)
                       :mycelium/transition :done)))})
