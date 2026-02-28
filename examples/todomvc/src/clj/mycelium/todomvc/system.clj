(ns mycelium.todomvc.system
  (:require [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus]))

(defmethod ig/init-key :todomvc/db
  [_ {:keys [jdbc-url]}]
  (let [ds (jdbc/get-datasource {:jdbcUrl jdbc-url})]
    (jdbc/execute-one! ds ["SELECT 1"])
    ds))

(defmethod ig/init-key :todomvc/migrations
  [_ {:keys [db]}]
  (migratus/migrate {:store         :database
                     :migration-dir "migrations"
                     :db            {:datasource db}})
  :migrated)
