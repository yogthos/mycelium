(ns app.workflows.users
  (:require [mycelium.manifest :as manifest]
            [mycelium.core :as myc]
            [clojure.java.io :as io]
            [app.middleware :as mw]
            ;; Load cell definitions
            [app.cells.user]
            [app.cells.ui]))

(def user-list-manifest
  (manifest/load-manifest
   (str (io/resource "workflows/user-list.edn"))))

(def user-list-workflow
  (manifest/manifest->workflow user-list-manifest))

(def user-profile-manifest
  (manifest/load-manifest
   (str (io/resource "workflows/user-profile.edn"))))

(def user-profile-workflow
  (manifest/manifest->workflow user-profile-manifest))

(defn run-user-list
  "Fetches all users and renders the list page."
  [db]
  (myc/run-workflow user-list-workflow {:db db} {} mw/workflow-opts))

(defn run-user-profile
  "Fetches a single user and renders the profile page."
  [db request]
  (myc/run-workflow
   user-profile-workflow
   {:db db}
   {:http-request {:path-params (:path-params request)}}
   mw/workflow-opts))
