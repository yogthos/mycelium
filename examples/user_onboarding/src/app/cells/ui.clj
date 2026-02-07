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
                             [:body map?]]]]}
   :transitions #{:done}}
  [_resources data]
  (let [{:keys [name email]} (:profile data)]
    (assoc data
           :http-response {:status 200
                           :body   {:message (str "Welcome, " name "!")
                                    :profile {:name name :email email}}}
           :mycelium/transition :done)))
