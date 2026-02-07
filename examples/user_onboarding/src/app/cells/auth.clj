(ns app.cells.auth
  (:require [mycelium.cell :as cell]
            [app.db :as db]))

(cell/defcell :auth/parse-request
  {:doc         "Extract and validate credentials from the HTTP request"
   :transitions #{:success :failure}}
  [_resources data]
  (let [body    (get-in data [:http-request :body])
        ;; Support both string keys (raw Ring) and keyword keys (Muuntaja)
        user-id (or (get body "username") (get body :username))
        token   (or (get body "token") (get body :token))]
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
   :requires    [:db]
   :transitions #{:authorized :unauthorized}}
  [{:keys [db]} data]
  (let [session (db/get-session db (:auth-token data))
        valid?  (and session (= 1 (:valid session)))]
    (assoc data
           :session-valid (boolean valid?)
           :mycelium/transition (if valid? :authorized :unauthorized))))
