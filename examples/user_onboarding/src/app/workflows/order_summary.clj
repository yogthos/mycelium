(ns app.workflows.order-summary
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
   (str (io/resource "workflows/order-summary.edn"))))

(def workflow-def
  (manifest/manifest->workflow manifest-data))

(defn run-order-summary
  "Renders order summary for an authenticated session.
   Exercises multi-source inputs: the render cell needs data
   from two different upstream cells."
  [db request]
  (myc/run-workflow
   workflow-def
   {:db db}
   {:http-request {:query-params (or (:query-params request) {})
                   :cookies      (or (:cookies request) {})}}
   mw/workflow-opts))
