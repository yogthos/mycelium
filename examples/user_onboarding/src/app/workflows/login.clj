(ns app.workflows.login
  (:require [mycelium.manifest :as manifest]
            [mycelium.core :as myc]
            [clojure.java.io :as io]
            ;; Load cell definitions
            [app.cells.auth]
            [app.cells.ui]))

(def login-page-manifest
  (manifest/load-manifest
   (str (io/resource "workflows/login-page.edn"))))

(def login-page-workflow
  (manifest/manifest->workflow login-page-manifest))

(def login-submit-manifest
  (manifest/load-manifest
   (str (io/resource "workflows/login-submit.edn"))))

(def login-submit-workflow
  (manifest/manifest->workflow login-submit-manifest))

(defn run-login-page
  "Renders the login form."
  []
  (myc/run-workflow login-page-workflow {} {}))

(defn run-login-submit
  "Processes login form submission."
  [db request]
  (myc/run-workflow
   login-submit-workflow
   {:db db}
   {:http-request {:headers (or (:headers request) {})
                   :body    (or (:form-params request)
                                (:body-params request)
                                (:body request))}}))
