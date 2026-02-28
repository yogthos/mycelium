(ns app.workflows.users
  (:require [mycelium.manifest :as manifest]
            [mycelium.core :as myc]
            [clojure.java.io :as io]
            [app.middleware :as mw]
            ;; Load cell definitions
            [app.cells.auth]
            [app.cells.user]
            [app.cells.ui]))

(def user-list-manifest
  (manifest/load-manifest
   (str (io/resource "workflows/user-list.edn"))))

(def compiled-user-list
  (myc/pre-compile
   (manifest/manifest->workflow user-list-manifest)
   mw/workflow-opts))

(def user-profile-manifest
  (manifest/load-manifest
   (str (io/resource "workflows/user-profile.edn"))))

(def compiled-user-profile
  (myc/pre-compile
   (manifest/manifest->workflow user-profile-manifest)
   mw/workflow-opts))

(defn run-user-list
  "Fetches all users and renders the list page."
  [db request]
  (myc/run-compiled
   compiled-user-list
   {:db db}
   {:http-request {:cookies      (or (:cookies request) {})
                   :query-params (or (:query-params request) {})}}))

(defn run-user-profile
  "Fetches a single user and renders the profile page."
  [db request]
  (myc/run-compiled
   compiled-user-profile
   {:db db}
   {:http-request {:path-params  (:path-params request)
                   :cookies      (or (:cookies request) {})
                   :query-params (or (:query-params request) {})}}))
