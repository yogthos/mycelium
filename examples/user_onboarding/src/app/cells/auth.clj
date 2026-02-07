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
             :user-id       ""
             :auth-token    ""
             :error-type    :bad-request
             :error-message "Missing username or token"
             :mycelium/transition :failure))))

(cell/defcell :auth/validate-session
  {:doc         "Check credentials against the session store"
   :requires    [:db]
   :transitions #{:authorized :unauthorized}}
  [{:keys [db]} data]
  (let [session (db/get-session db (:auth-token data))
        valid?  (and session (= 1 (:valid session)))]
    (if valid?
      (assoc data
             :user-id    (:user_id session)
             :session-valid true
             :mycelium/transition :authorized)
      (assoc data
             :session-valid  false
             :error-type     :unauthorized
             :error-message  "Invalid or expired session token"
             :mycelium/transition :unauthorized))))

(cell/defcell :auth/extract-session
  {:doc         "Extract auth token from query parameters"
   :transitions #{:success :failure}}
  [_resources data]
  (let [token (get-in data [:http-request :query-params :token])]
    (if token
      (assoc data
             :auth-token token
             :mycelium/transition :success)
      (assoc data
             :auth-token    ""
             :error-type    :unauthorized
             :error-message "No session token provided"
             :mycelium/transition :failure))))
