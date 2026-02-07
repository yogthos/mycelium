(ns app.core
  (:require [integrant.core :as ig]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus]
            [ring.adapter.jetty :as jetty]
            [app.routes :as routes]))

(defn read-config []
  (let [content (slurp (io/resource "system.edn"))]
    (edn/read-string {:readers {'ig/ref ig/ref}} content)))

;; --- Integrant components ---

(defmethod ig/init-key :app/db [_ {:keys [jdbc-url]}]
  (let [ds (jdbc/get-datasource {:jdbcUrl jdbc-url})]
    ;; Ensure the datasource is usable
    (jdbc/execute-one! ds ["SELECT 1"])
    ds))

(defmethod ig/init-key :app/migrations [_ {:keys [db]}]
  (migratus/migrate {:store         :database
                     :migration-dir "migrations"
                     :db            {:datasource db}})
  :migrated)

(defmethod ig/init-key :app/handler [_ {:keys [db]}]
  (routes/app db))

(defmethod ig/init-key :app/server [_ {:keys [handler port]}]
  (println (str "Starting server on port " port))
  (jetty/run-jetty handler {:port port :join? false}))

(defmethod ig/halt-key! :app/server [_ server]
  (.stop server))

;; --- REPL helpers ---

(defonce ^:private system (atom nil))

(defn start! []
  (let [config (read-config)]
    (reset! system (ig/init config))))

(defn stop! []
  (when-let [s @system]
    (ig/halt! s)
    (reset! system nil)))

;; --- Entry point ---

(defn -main [& _args]
  (start!)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable stop!)))
