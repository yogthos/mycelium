(ns app.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.manifest :as manifest]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(def manifest-data
  (manifest/load-manifest
   "examples/user_onboarding/workflows/user-onboarding.edn"))

(def mock-db
  {:sessions {"tok_abc123" true
              "tok_expired" false}
   :users    {"alice" {:name "Alice Smith" :email "alice@example.com"}
              "bob"   {:name "Bob Jones"   :email "bob@example.com"}}})

(defn- ensure-cells-loaded! []
  ;; Each require :reload will re-register the cells via defcell
  ;; Only reload if a cell from that ns is missing
  (when-not (cell/get-cell :auth/parse-request)
    (require 'app.cells.auth :reload))
  (when-not (cell/get-cell :auth/validate-session)
    (require 'app.cells.auth :reload))
  (when-not (cell/get-cell :user/fetch-profile)
    (require 'app.cells.user :reload))
  (when-not (cell/get-cell :ui/render-home)
    (require 'app.cells.ui :reload)))

(use-fixtures :each (fn [f]
                      (ensure-cells-loaded!)
                      (f)))

(deftest end-to-end-success-test
  (testing "Full workflow: valid request → profile → rendered page"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)
          result      (fsm/run compiled
                              {:db mock-db}
                              {:data {:http-request
                                      {:headers {"content-type" "application/json"}
                                       :body    {"username" "alice"
                                                 "token"    "tok_abc123"}}}})]
      (is (= 200 (get-in result [:http-response :status])))
      (is (re-find #"Alice Smith" (get-in result [:http-response :body]))))))

(deftest end-to-end-unauthorized-test
  (testing "Full workflow: invalid token → error path"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)]
      ;; With an unknown token, validate-session should dispatch to :unauthorized → :error
      (is (thrown? Exception
            (fsm/run compiled
                    {:db mock-db}
                    {:data {:http-request
                            {:headers {}
                             :body    {"username" "alice"
                                       "token"    "bad_token"}}}}))))))
