(ns app.cells.auth
  (:require [mycelium.cell :as cell]))

(cell/defcell :auth/parse-request
  {:doc         "Extract and validate credentials from the HTTP request"
   :schema      {:input  [:map
                           [:http-request [:map
                             [:headers map?]
                             [:body map?]]]]
                 :output [:map
                           [:user-id :string]
                           [:auth-token :string]]}
   :transitions #{:success :failure}}
  [_resources data]
  (let [body    (get-in data [:http-request :body])
        user-id (get body "username")
        token   (get body "token")]
    (if (and user-id token)
      (assoc data
             :user-id    user-id
             :auth-token token
             :mycelium/transition :success)
      (assoc data
             :user-id    ""
             :auth-token ""
             :mycelium/transition :failure))))

(cell/defcell :auth/validate-session
  {:doc         "Check credentials against the session store"
   :schema      {:input  [:map
                           [:user-id :string]
                           [:auth-token :string]]
                 :output [:map
                           [:session-valid :boolean]]}
   :requires    [:db]
   :transitions #{:authorized :unauthorized}}
  [{:keys [db]} data]
  (let [valid? (get-in db [:sessions (:auth-token data)])]
    (assoc data
           :session-valid (boolean valid?)
           :mycelium/transition (if valid? :authorized :unauthorized))))
