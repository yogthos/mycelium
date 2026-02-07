(ns app.cells.user
  (:require [mycelium.cell :as cell]
            [app.db :as db]))

(cell/defcell :user/fetch-profile
  {:doc         "Fetch user profile from database"
   :requires    [:db]
   :transitions #{:found :not-found}}
  [{:keys [db]} data]
  (let [user (db/get-user db (:user-id data))]
    (if user
      (assoc data
             :profile (select-keys user [:name :email])
             :mycelium/transition :found)
      (assoc data
             :mycelium/transition :not-found))))
