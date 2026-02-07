(ns app.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.manifest :as manifest]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus])
  (:import [java.io File]))

(def ^:dynamic *ds* nil)

(defn create-test-db! []
  (let [f    (File/createTempFile "mycelium-test-" ".db")
        _    (.deleteOnExit f)
        url  (str "jdbc:sqlite:" (.getAbsolutePath f))
        ds   (jdbc/get-datasource {:jdbcUrl url})]
    (migratus/migrate {:store         :database
                       :migration-dir "migrations"
                       :db            {:datasource ds}})
    ds))

(defn- ensure-cells-loaded! []
  (when-not (cell/get-cell :auth/parse-request)
    (require 'app.cells.auth :reload))
  (when-not (cell/get-cell :user/fetch-profile)
    (require 'app.cells.user :reload))
  (when-not (cell/get-cell :ui/render-home)
    (require 'app.cells.ui :reload))
  (when-not (cell/get-cell :auth/extract-session)
    (require 'app.cells.auth :reload))
  (when-not (cell/get-cell :user/fetch-all-users)
    (require 'app.cells.user :reload))
  (when-not (cell/get-cell :auth/extract-cookie-session)
    (require 'app.cells.auth :reload)))

(use-fixtures :each (fn [f]
                      (ensure-cells-loaded!)
                      (binding [*ds* (create-test-db!)]
                        (f))))

(def manifest-data
  (manifest/load-manifest
   (str (clojure.java.io/resource "workflows/user-onboarding.edn"))))

(deftest end-to-end-success-test
  (testing "Full workflow: valid request → profile → rendered response"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)
          result      (fsm/run compiled
                              {:db *ds*}
                              {:data {:http-request
                                      {:headers {"content-type" "application/json"}
                                       :body    {"username" "alice"
                                                 "token"    "tok_abc123"}}}})]
      (is (= 200 (get-in result [:http-response :status])))
      (is (= "Welcome, Alice Smith!"
             (get-in result [:http-response :body :message])))
      (is (= {:name "Alice Smith" :email "alice@example.com"}
             (get-in result [:http-response :body :profile]))))))

(deftest end-to-end-unauthorized-test
  (testing "Full workflow: invalid token → error path"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)]
      (is (thrown? Exception
            (fsm/run compiled
                    {:db *ds*}
                    {:data {:http-request
                            {:headers {}
                             :body    {"username" "alice"
                                       "token"    "bad_token"}}}}))))))

(deftest end-to-end-expired-token-test
  (testing "Full workflow: expired token → error path"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)]
      (is (thrown? Exception
            (fsm/run compiled
                    {:db *ds*}
                    {:data {:http-request
                            {:headers {}
                             :body    {"username" "alice"
                                       "token"    "tok_expired"}}}}))))))
