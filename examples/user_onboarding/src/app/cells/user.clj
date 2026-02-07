(ns app.cells.user
  (:require [mycelium.cell :as cell]))

(cell/defcell :user/fetch-profile
  {:doc         "Fetch user profile from database"
   :schema      {:input  [:map
                           [:user-id :string]
                           [:session-valid :boolean]]
                 :output [:map
                           [:profile [:map
                             [:name :string]
                             [:email :string]]]]}
   :requires    [:db]
   :transitions #{:found :not-found}}
  [{:keys [db]} data]
  (let [profile (get-in db [:users (:user-id data)])]
    (if profile
      (assoc data
             :profile profile
             :mycelium/transition :found)
      (assoc data
             :mycelium/transition :not-found))))
