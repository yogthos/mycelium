(ns app.workflows.onboarding
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
   (str (io/resource "workflows/user-onboarding.edn"))))

(def compiled-workflow
  (myc/pre-compile
   (manifest/manifest->workflow manifest-data)
   mw/workflow-opts))

(defn run-onboarding
  "Bridges a Ring request into the Mycelium workflow.
   Returns the final data map from the workflow."
  [db request]
  (myc/run-compiled
   compiled-workflow
   {:db db}
   {:http-request {:headers (or (:headers request) {})
                   :body    (or (:body-params request) (:body request))}}))
