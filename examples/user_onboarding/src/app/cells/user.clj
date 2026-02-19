(ns app.cells.user
  (:require [mycelium.cell :as cell]
            [app.db :as db]))

(defmethod cell/cell-spec :user/fetch-profile [_]
  {:id      :user/fetch-profile
   :doc     "Fetch user profile from database"
   :handler (fn [{:keys [db]} data]
              (let [user (db/get-user db (:user-id data))]
                (if user
                  (assoc data :profile (select-keys user [:name :email]))
                  (assoc data
                         :error-type    :not-found
                         :error-message (str "User not found: " (:user-id data))))))})

(defmethod cell/cell-spec :user/fetch-all-users [_]
  {:id      :user/fetch-all-users
   :doc     "Fetch all users from the database"
   :handler (fn [{:keys [db]} data]
              (let [users (db/get-all-users db)]
                (assoc data :users (vec users))))})

(defmethod cell/cell-spec :user/fetch-profile-by-id [_]
  {:id      :user/fetch-profile-by-id
   :doc     "Extract user-id from path params and fetch profile"
   :handler (fn [{:keys [db]} data]
              (let [user-id (get-in data [:http-request :path-params :id])
                    user    (when user-id (db/get-user db user-id))]
                (if user
                  (assoc data :profile user)
                  (assoc data
                         :error-type    :not-found
                         :error-message (str "User not found: " user-id)))))})
