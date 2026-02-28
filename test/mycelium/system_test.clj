(ns mycelium.system-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.system :as sys]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Test manifests ---

(def dashboard-manifest
  {:id :dashboard
   :cells {:start   {:id :auth/extract
                      :schema {:input [:map [:http-request [:map]]]
                               :output [:map [:token :string]]}
                      :requires []}
           :render  {:id :ui/render-dashboard
                      :schema {:input [:map [:token :string]]
                               :output [:map [:html :string]]}
                      :requires []}}
   :edges {:start {:done :render}
           :render {:done :end}}
   :dispatches {:start [[:done (constantly true)]]
                :render [[:done (constantly true)]]}})

(def orders-manifest
  {:id :orders
   :cells {:start   {:id :auth/extract
                      :schema {:input [:map [:http-request [:map]]]
                               :output [:map [:token :string]]}
                      :requires []}
           :fetch   {:id :data/fetch-orders
                      :schema {:input [:map [:token :string]]
                               :output [:map [:orders [:vector :map]]]}
                      :requires [:db]}
           :render  {:id :ui/render-orders
                      :schema {:input [:map [:orders [:vector :map]]]
                               :output [:map [:html :string]]}
                      :requires []}}
   :edges {:start {:done :fetch}
           :fetch {:done :render}
           :render {:done :end}}
   :dispatches {:start [[:done (constantly true)]]
                :fetch [[:done (constantly true)]]
                :render [[:done (constantly true)]]}})

(def login-manifest
  {:id :login
   :cells {:start {:id :ui/render-login
                    :schema {:input [:map] :output [:map [:html :string]]}
                    :requires []}}
   :edges {:start {:done :end}}
   :dispatches {:start [[:done (constantly true)]]}})

;; ===== 1. Compile system with 2+ manifests → correct route/cell mapping =====

(deftest compile-system-basic-test
  (testing "compile-system produces correct route/cell mapping"
    (let [system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    orders-manifest
                   "/login"     login-manifest})]
      (is (= 3 (count (:routes system))))
      (is (contains? (:routes system) "/dashboard"))
      (is (contains? (:routes system) "/orders"))
      (is (contains? (:routes system) "/login"))
      ;; Route should have manifest-id
      (is (= :dashboard (get-in system [:routes "/dashboard" :manifest-id])))
      (is (= :orders (get-in system [:routes "/orders" :manifest-id]))))))

;; ===== 2. cell-usage returns correct routes =====

(deftest cell-usage-test
  (testing "cell-usage returns correct routes"
    (let [system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    orders-manifest
                   "/login"     login-manifest})]
      ;; :auth/extract used in both dashboard and orders
      (is (= #{"/dashboard" "/orders"}
             (set (sys/cell-usage system :auth/extract))))
      ;; :data/fetch-orders only in orders
      (is (= #{"/orders"}
             (set (sys/cell-usage system :data/fetch-orders))))
      ;; :ui/render-login only in login
      (is (= #{"/login"}
             (set (sys/cell-usage system :ui/render-login)))))))

;; ===== 3. route-cells returns correct cells =====

(deftest route-cells-test
  (testing "route-cells returns correct cells for a route"
    (let [system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    orders-manifest})]
      (is (= #{:auth/extract :ui/render-dashboard}
             (sys/route-cells system "/dashboard")))
      (is (= #{:auth/extract :data/fetch-orders :ui/render-orders}
             (sys/route-cells system "/orders"))))))

;; ===== 4. schema-conflicts detects different schemas for same cell =====

(deftest schema-conflicts-test
  (testing "schema-conflicts detects differing schemas for same cell across workflows"
    (let [modified-orders (assoc-in orders-manifest [:cells :start :schema :output]
                                    [:map [:different-key :boolean]])
          system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    modified-orders})]
      (let [conflicts (sys/schema-conflicts system)]
        (is (seq conflicts))
        ;; :auth/extract should be in conflicts
        (is (some #(= :auth/extract (:cell-id %)) conflicts))))))

;; ===== 5. system->dot produces valid DOT =====

(deftest system-dot-test
  (testing "system->dot produces valid DOT graph"
    (let [system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    orders-manifest})
          dot (sys/system->dot system)]
      (is (string? dot))
      (is (re-find #"digraph" dot))
      (is (re-find #"dashboard" dot))
      (is (re-find #"orders" dot)))))

;; ===== 6. Empty system → valid empty result =====

(deftest empty-system-test
  (testing "Empty system produces valid empty result"
    (let [system (sys/compile-system {})]
      (is (empty? (:routes system)))
      (is (empty? (:cells system)))
      (is (empty? (:shared-cells system))))))

;; ===== 7. resources-needed aggregates across all workflows =====

(deftest resources-needed-test
  (testing "resources-needed aggregates across all workflows"
    (let [system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    orders-manifest
                   "/login"     login-manifest})]
      ;; :db is required by orders
      (is (contains? (:resources-needed system) :db)))))

;; ===== 8. shared-cells identifies cells used in multiple workflows =====

(deftest shared-cells-test
  (testing "shared-cells identifies cells used in multiple workflows"
    (let [system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    orders-manifest
                   "/login"     login-manifest})]
      (is (contains? (:shared-cells system) :auth/extract))
      (is (not (contains? (:shared-cells system) :data/fetch-orders))))))

;; ===== 9. Warnings for schema mismatches =====

(deftest warnings-test
  (testing "Warnings generated for schema mismatches"
    (let [modified-orders (assoc-in orders-manifest [:cells :start :schema :output]
                                    [:map [:different :string]])
          system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    modified-orders})]
      (is (seq (:warnings system))))))

;; ===== 10. route-resources returns resources for a route =====

(deftest route-resources-test
  (testing "route-resources returns resources for a specific route"
    (let [system (sys/compile-system
                  {"/dashboard" dashboard-manifest
                   "/orders"    orders-manifest})]
      (is (= #{} (sys/route-resources system "/dashboard")))
      (is (= #{:db} (sys/route-resources system "/orders"))))))
