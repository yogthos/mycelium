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

(def compiled-workflow
  (myc/pre-compile
   (manifest/manifest->workflow manifest-data)
   mw/workflow-opts))

(defn run-home
  "Checks cookie session: renders dashboard if logged in, login form if not."
  [db request]
  (myc/run-compiled
   compiled-workflow
   {:db db}
   {:http-request {:cookies      (or (:cookies request) {})
                   :query-params (or (:query-params request) {})}}))
