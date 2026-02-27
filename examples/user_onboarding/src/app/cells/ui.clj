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
                                                :profile {:name name :email email}}})))})

(defmethod cell/cell-spec :ui/render-login-page [_]
  {:id      :ui/render-login-page
   :doc     "Render the login form page"
   :handler (fn [_resources data]
              (assoc data :html (selmer/render-file "templates/login.html" {})))})

(defmethod cell/cell-spec :ui/render-dashboard [_]
  {:id      :ui/render-dashboard
   :doc     "Render the dashboard page with user profile"
   :handler (fn [_resources data]
              (let [{:keys [name email]} (:profile data)]
                (assoc data
                       :html (selmer/render-file "templates/dashboard.html"
                                                 {:name    name
                                                  :email   email
                                                  :user-id (:user-id data)}))))})

(defmethod cell/cell-spec :ui/render-error [_]
  {:id      :ui/render-error
   :doc     "Render a styled error page"
   :handler (fn [_resources data]
              (let [error-type (:error-type data :server-error)
                    errors     {:bad-request  {:status 400 :title "Bad Request"}
                                :unauthorized {:status 401 :title "Unauthorized"}
                                :not-found    {:status 404 :title "Not Found"}}
                    {:keys [status title]} (get errors error-type {:status 500 :title "Server Error"})]
                (assoc data
                       :html (selmer/render-file "templates/error.html"
                                                 {:status  status
                                                  :title   title
                                                  :message (:error-message data "An unexpected error occurred")})
                       :error-status status)))})

(defmethod cell/cell-spec :ui/render-order-summary [_]
  {:id      :ui/render-order-summary
   :doc     "Render order summary combining profile and order history"
   :handler (fn [_resources data]
              (let [{:keys [name email]} (:profile data)
                    orders (:orders data)
                    total  (reduce + 0 (map :amount orders))]
                (assoc data
                       :http-response
                       {:status 200
                        :body   {:user    {:name name :email email}
                                 :orders  orders
                                 :summary {:order-count (count orders)
                                           :total       total}}})))})

(defmethod cell/cell-spec :ui/render-user-list [_]
  {:id      :ui/render-user-list
   :doc     "Render the user list page"
   :handler (fn [_resources data]
              (assoc data
                     :html (selmer/render-file "templates/users.html"
                                               {:users (:users data)})))})

(defmethod cell/cell-spec :ui/render-user-profile [_]
  {:id      :ui/render-user-profile
   :doc     "Render a single user profile page"
   :handler (fn [_resources data]
              (let [profile (:profile data)]
                (assoc data
                       :html (selmer/render-file "templates/profile.html" profile))))})
