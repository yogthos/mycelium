(ns app.workflows.dashboard
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
   (str (io/resource "workflows/dashboard.edn"))))

(def workflow-def
  (manifest/manifest->workflow manifest-data))

(defn run-dashboard
  "Renders the dashboard for an authenticated session."
  [db request]
  (myc/run-workflow
   workflow-def
   {:db db}
   {:http-request {:query-params (or (:query-params request) {})}}
   mw/workflow-opts))
