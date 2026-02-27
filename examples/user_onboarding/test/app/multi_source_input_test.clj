(ns app.multi-source-input-test
  "Tests that a cell can consume inputs produced by multiple different
   upstream cells. The order-summary workflow has:

     extract-session → validate-session → fetch-profile → fetch-orders → render-summary

   :render-summary needs:
     - :profile  (produced by :fetch-profile, 2 steps back)
     - :orders   (produced by :fetch-orders, immediate predecessor)
   :fetch-orders needs:
     - :user-id  (produced by :validate-session, 2 steps back)

   This works because Mycelium passes an accumulating data map — every
   cell sees all keys produced by all prior cells, not just its
   immediate predecessor."
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
  (testing "render-summary cell gets :profile from fetch-profile AND :orders from fetch-orders"
    (let [workflow-def (manifest/manifest->workflow manifest-data)
          compiled    (wf/compile-workflow workflow-def)
          result      (fsm/run compiled
                              {:db *ds*}
                              {:data {:http-request
                                      {:query-params {"token" "tok_abc123"}}}})]
      ;; The response should contain both profile and order data
      (is (= 200 (get-in result [:http-response :status])))
      (let [body (get-in result [:http-response :body])]
        ;; :profile came from fetch-profile (2 steps before render-summary)
        (is (= {:name "Alice Smith" :email "alice@example.com"}
               (:user body)))
        ;; :orders came from fetch-orders (1 step before render-summary)
        (is (= 2 (get-in body [:summary :order-count])))
        (is (= 79.98 (get-in body [:summary :total])))
        ;; Both order items present
        (is (= #{"Widget Pro" "Gadget Max"}
               (set (map :item (:orders body)))))))))

(deftest fetch-orders-uses-user-id-from-validate-session-test
  (testing "fetch-orders gets :user-id produced by validate-session, not its immediate predecessor"
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

(deftest schema-chain-validates-multi-source-inputs-test
  (testing "compile-workflow succeeds — schema chain validator correctly tracks
            keys across non-adjacent cells"
    ;; If the schema chain validator only looked at the immediate predecessor,
    ;; this would fail because :render-summary requires :profile (from
    ;; fetch-profile) but its immediate predecessor is fetch-orders.
    (let [workflow-def (manifest/manifest->workflow manifest-data)]
      (is (some? (wf/compile-workflow workflow-def))
          "Workflow compiles successfully with multi-source input requirements"))))

;; ---------- Error paths ----------

(deftest invalid-token-error-path-test
  (testing "Invalid token routes to error without reaching fetch-orders or render-summary"
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
