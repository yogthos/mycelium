(ns app.workflows.login
  (:require [mycelium.manifest :as manifest]
            [mycelium.core :as myc]
            [clojure.java.io :as io]
            [app.middleware :as mw]
            ;; Load cell definitions
            [app.cells.auth]
            [app.cells.user]
            [app.cells.ui]))

(def login-page-manifest
  (manifest/load-manifest
   (str (io/resource "workflows/login-page.edn"))))

(def compiled-login-page
  (myc/pre-compile
   (manifest/manifest->workflow login-page-manifest)
   mw/workflow-opts))

(def login-submit-manifest
  (manifest/load-manifest
   (str (io/resource "workflows/login-submit.edn"))))

(def compiled-login-submit
  (myc/pre-compile
   (manifest/manifest->workflow login-submit-manifest)
   mw/workflow-opts))

(defn run-login-page
  "Renders the login form."
  []
  (myc/run-compiled compiled-login-page {} {}))

(defn run-login-submit
  "Processes login form submission."
  [db request]
  (myc/run-compiled
   compiled-login-submit
   {:db db}
   {:http-request {:headers (or (:headers request) {})
                   :body    (or (:form-params request)
                                (:body-params request)
                                (:body request))}}))
