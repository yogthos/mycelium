(ns app.multi-source-input-test
  "Tests that a join node runs :fetch-profile and :fetch-orders in
   parallel and merges their outputs for :render-summary.

   The order-summary workflow has:

     extract-session → validate-session → fetch-data (join) → render-summary
                                             ├── fetch-profile
                                             └── fetch-orders

   :render-summary needs:
     - :profile  (produced by :fetch-profile inside the join)
     - :orders   (produced by :fetch-orders inside the join)

   Both join members receive the same snapshot containing :user-id
   (produced by :validate-session). Their outputs are merged into
   a single data map for :render-summary."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [mycelium.manifest :as manifest]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus])
  (:import [java.io File]))

(def ^:dynamic *ds* nil)

(defn create-test-db! []
  (let [f   (File/createTempFile "mycelium-order-test-" ".db")
        _   (.deleteOnExit f)
        url (str "jdbc:sqlite:" (.getAbsolutePath f))
        ds  (jdbc/get-datasource {:jdbcUrl url})]
    (migratus/migrate {:store         :database
                       :migration-dir "migrations"
                       :db            {:datasource ds}})
    ds))

(defn- ensure-cells-loaded! []
  (doseq [ns-sym '[app.cells.auth app.cells.user app.cells.ui]]
    (require ns-sym :reload)))

(use-fixtures :each (fn [f]
                      (ensure-cells-loaded!)
                      (binding [*ds* (create-test-db!)]
                        (f))))

(def manifest-data
  (manifest/load-manifest
   (str (io/resource "workflows/order-summary.edn"))))

;; ---------- Happy path ----------

(deftest render-summary-receives-profile-and-orders-test
  (testing "render-summary cell gets :profile from fetch-profile AND :orders from fetch-orders via join"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)
          result      (fsm/run compiled
                              {:db *ds*}
                              {:data {:http-request
                                      {:query-params {"token" "tok_abc123"}}}})]
      ;; The response should contain both profile and order data
      (is (= 200 (get-in result [:http-response :status])))
      (let [body (get-in result [:http-response :body])]
        ;; :profile came from fetch-profile (inside the join)
        (is (= {:name "Alice Smith" :email "alice@example.com"}
               (:user body)))
        ;; :orders came from fetch-orders (inside the join)
        (is (= 2 (get-in body [:summary :order-count])))
        (is (= 79.98 (get-in body [:summary :total])))
        ;; Both order items present
        (is (= #{"Widget Pro" "Gadget Max"}
               (set (map :item (:orders body)))))))))

(deftest fetch-orders-uses-user-id-from-validate-session-test
  (testing "fetch-orders gets :user-id produced by validate-session via join snapshot"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)
          result      (fsm/run compiled
                              {:db *ds*}
                              {:data {:http-request
                                      {:query-params {"token" "tok_bob456"}}}})]
      (is (= 200 (get-in result [:http-response :status])))
      (let [body (get-in result [:http-response :body])]
        ;; Bob has 1 order
        (is (= 1 (get-in body [:summary :order-count])))
        (is (= "Bob Jones" (get-in body [:user :name])))))))

;; ---------- Schema chain validation ----------

(deftest schema-chain-validates-join-inputs-test
  (testing "compile-workflow succeeds — schema chain validator correctly tracks
            keys through join nodes"
    ;; The schema chain validator must verify that join members get
    ;; required keys from upstream, and that the union of join outputs
    ;; satisfies downstream cells.
    (let [workflow-def (manifest/manifest->workflow manifest-data)]
      (is (some? (wf/compile-workflow workflow-def))
          "Workflow compiles successfully with join-based parallel inputs"))))

;; ---------- Error paths ----------

(deftest invalid-token-error-path-test
  (testing "Invalid token routes to error without reaching the join or render-summary"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)
          result      (fsm/run compiled
                              {:db *ds*}
                              {:data {:http-request
                                      {:query-params {"token" "bad_token"}}}})]
      ;; Should have error page HTML, not an http-response map
      (is (string? (:html result)))
      (is (= 401 (:error-status result))))))

(deftest missing-token-error-path-test
  (testing "Missing token is caught at the first cell"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)
          result      (fsm/run compiled
                              {:db *ds*}
                              {:data {:http-request
                                      {:query-params {}}}})]
      (is (string? (:html result)))
      (is (= 401 (:error-status result))))))
