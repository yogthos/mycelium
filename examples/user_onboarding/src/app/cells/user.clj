(ns app.cells.user
  (:require [mycelium.cell :as cell]
            [app.db :as db]))

(cell/defcell :user/fetch-profile
  {:doc "Fetch user profile from database"}
  [{:keys [db]} data]
  (let [user (db/get-user db (:user-id data))]
    (if user
      (assoc data
             :profile (select-keys user [:name :email])
             :mycelium/transition :found)
      (assoc data
             :error-type    :not-found
             :error-message (str "User not found: " (:user-id data))
             :mycelium/transition :not-found))))

(cell/defcell :user/fetch-all-users
  {:doc "Fetch all users from the database"}
  [{:keys [db]} data]
  (let [users (db/get-all-users db)]
    (assoc data
           :users (vec users)
           :mycelium/transition :done)))

(cell/defcell :user/fetch-profile-by-id
  {:doc "Extract user-id from path params and fetch profile"}
  [{:keys [db]} data]
  (let [user-id (get-in data [:http-request :path-params :id])
        user    (when user-id (db/get-user db user-id))]
    (if user
      (assoc data
             :profile user
             :mycelium/transition :found)
      (assoc data
             :error-type    :not-found
             :error-message (str "User not found: " user-id)
             :mycelium/transition :not-found))))
