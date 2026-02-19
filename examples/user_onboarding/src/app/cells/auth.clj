(ns app.cells.auth
  (:require [mycelium.cell :as cell]
            [app.db :as db]))

(defmethod cell/cell-spec :auth/parse-request [_]
  {:id      :auth/parse-request
   :doc     "Extract and validate credentials from the HTTP request"
   :handler (fn [_resources data]
              (let [body    (get-in data [:http-request :body])
                    ;; Support both string keys (raw Ring) and keyword keys (Muuntaja)
                    user-id (or (get body "username") (get body :username))
                    token   (or (get body "token") (get body :token))]
                (if (and user-id token)
                  (assoc data
                         :user-id    user-id
                         :auth-token token)
                  (assoc data
                         :error-type    :bad-request
                         :error-message "Missing username or token"))))})

(defmethod cell/cell-spec :auth/validate-session [_]
  {:id      :auth/validate-session
   :doc     "Check credentials against the session store"
   :handler (fn [{:keys [db]} data]
              (let [session (db/get-session db (:auth-token data))
                    valid?  (and session (= 1 (:valid session)))]
                (if valid?
                  (assoc data
                         :user-id    (:user_id session)
                         :session-valid true)
                  (assoc data
                         :session-valid  false
                         :error-type     :unauthorized
                         :error-message  "Invalid or expired session token"))))})

(defmethod cell/cell-spec :auth/extract-session [_]
  {:id      :auth/extract-session
   :doc     "Extract auth token from query parameters"
   :handler (fn [_resources data]
              (let [qp    (get-in data [:http-request :query-params])
                    token (or (get qp :token) (get qp "token"))]
                (if token
                  (assoc data :auth-token token)
                  (assoc data
                         :error-type    :unauthorized
                         :error-message "No session token provided"))))})

(defmethod cell/cell-spec :auth/extract-cookie-session [_]
  {:id      :auth/extract-cookie-session
   :doc     "Extract auth token from session-token cookie"
   :handler (fn [_resources data]
              (let [token (get-in data [:http-request :cookies "session-token" :value])]
                (if token
                  (assoc data :auth-token token)
                  data)))})
