(ns app.workflows.home
  (:require [mycelium.manifest :as manifest]
            [mycelium.core :as myc]
            [clojure.java.io :as io]
            [app.middleware :as mw]
            ;; Load cell definitions
            [app.cells.auth]
            [app.cells.user]
            [app.cells.ui]))

(def manifest-data
  (manifest/load-manifest
   (str (io/resource "workflows/home.edn"))))

(def workflow-def
  (manifest/manifest->workflow manifest-data))

(defn run-home
  "Checks cookie session: renders dashboard if logged in, login form if not."
  [db request]
  (myc/run-workflow
   workflow-def
   {:db db}
   {:http-request {:cookies (or (:cookies request) {})}}
   mw/workflow-opts))
