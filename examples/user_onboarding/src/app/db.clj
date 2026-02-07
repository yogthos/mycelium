(ns app.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn get-session
  "Returns {:user_id ... :valid 0|1} or nil."
  [ds token]
  (jdbc/execute-one! ds
    ["SELECT user_id, valid FROM sessions WHERE token = ?" token]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn get-user
  "Returns {:id ... :name ... :email ...} or nil."
  [ds user-id]
  (jdbc/execute-one! ds
    ["SELECT id, name, email FROM users WHERE id = ?" user-id]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn get-all-users
  "Returns a vector of all users."
  [ds]
  (jdbc/execute! ds
    ["SELECT id, name, email FROM users ORDER BY id"]
    {:builder-fn rs/as-unqualified-lower-maps}))
