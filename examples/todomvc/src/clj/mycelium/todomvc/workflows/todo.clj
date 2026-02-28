(ns mycelium.todomvc.workflows.todo
  (:require [mycelium.manifest :as manifest]
            [mycelium.core :as myc]
            [clojure.java.io :as io]
            ;; Cell registrations
            [mycelium.todomvc.cells.request]
            [mycelium.todomvc.cells.todo]
            [mycelium.todomvc.cells.ui]))

(defn- load-and-compile [filename]
  (let [manifest-data (manifest/load-manifest
                        (str (io/resource (str "workflows/" filename))))
        workflow-def  (manifest/manifest->workflow manifest-data)]
    (myc/pre-compile workflow-def)))

(def compiled-page       (load-and-compile "todo-page.edn"))
(def compiled-add        (load-and-compile "todo-add.edn"))
(def compiled-toggle     (load-and-compile "todo-toggle.edn"))
(def compiled-delete     (load-and-compile "todo-delete.edn"))
(def compiled-update     (load-and-compile "todo-update.edn"))
(def compiled-toggle-all (load-and-compile "todo-toggle-all.edn"))
(def compiled-clear      (load-and-compile "todo-clear.edn"))

(defn- run [compiled db request]
  (myc/run-compiled compiled {:db db} {:http-request request}))

(defn run-page [db request]
  (run compiled-page db request))

(defn run-add [db request]
  (run compiled-add db request))

(defn run-toggle [db request]
  (run compiled-toggle db request))

(defn run-delete [db request]
  (run compiled-delete db request))

(defn run-update [db request]
  (run compiled-update db request))

(defn run-toggle-all [db request]
  (run compiled-toggle-all db request))

(defn run-clear [db request]
  (run compiled-clear db request))
