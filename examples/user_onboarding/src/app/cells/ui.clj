(ns app.cells.ui
  (:require [mycelium.cell :as cell]))

(cell/defcell :ui/render-home
  {:doc         "Render the home page response"
   :transitions #{:done}}
  [_resources data]
  (let [{:keys [name email]} (:profile data)]
    (assoc data
           :http-response {:status 200
                           :body   {:message (str "Welcome, " name "!")
                                    :profile {:name name :email email}}}
           :mycelium/transition :done)))
