(ns app.cells.ui
  (:require [mycelium.cell :as cell]))

(cell/defcell :ui/render-home
  {:doc         "Render the home page response"
   :schema      {:input  [:map
                           [:profile [:map
                             [:name :string]
                             [:email :string]]]]
                 :output [:map
                           [:http-response [:map
                             [:status :int]
                             [:body :string]]]]}
   :transitions #{:done}}
  [_resources data]
  (let [{:keys [name email]} (:profile data)]
    (assoc data
           :http-response {:status 200
                           :body   (str "Welcome, " name " (" email ")!")}
           :mycelium/transition :done)))
