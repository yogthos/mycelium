(ns mycelium.todomvc.web.routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.todomvc.web.routes.pages :as pages]
            [mycelium.todomvc.db :as db]
            [reitit.ring :as ring]
            [ring.middleware.params :as params]
            [ring.mock.request :as mock]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus])
  (:import [java.io File]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *app* nil)

(defn create-test-db []
  (let [f   (File/createTempFile "todomvc-route-test-" ".db")
        _   (.deleteOnExit f)
        url (str "jdbc:sqlite:" (.getAbsolutePath f))
        ds  (jdbc/get-datasource {:jdbcUrl url})]
    (migratus/migrate {:store         :database
                       :migration-dir "migrations"
                       :db            {:datasource ds}})
    ds))

(use-fixtures :each
  (fn [f]
    (let [ds (create-test-db)]
      (binding [*ds* ds
                *app* (ring/ring-handler
                        (ring/router
                          ["" {:middleware [params/wrap-params]}
                           (pages/page-routes ds)]
                          {:conflicts nil})
                        (ring/create-default-handler))]
        (f)))))

(deftest get-page-test
  (testing "GET / returns full page HTML"
    (let [resp (*app* (mock/request :get "/"))]
      (is (= 200 (:status resp)))
      (is (re-find #"todoapp" (:body resp))))))

(deftest post-add-test
  (testing "POST /todos creates a todo and returns list fragment"
    (let [resp (*app* (-> (mock/request :post "/todos")
                          (mock/content-type "application/x-www-form-urlencoded")
                          (mock/body "title=New+task")))]
      (is (= 200 (:status resp)))
      (is (re-find #"New task" (:body resp)))
      (is (= 1 (count (db/list-todos *ds* "all")))))))

(deftest patch-toggle-test
  (testing "PATCH /todos/:id/toggle toggles and returns list"
    (let [{:keys [id]} (db/create-todo! *ds* "Toggle")]
      (let [resp (*app* (mock/request :patch (str "/todos/" id "/toggle")))]
        (is (= 200 (:status resp)))
        (is (= 1 (:completed (first (db/list-todos *ds* "all")))))))))

(deftest delete-test
  (testing "DELETE /todos/:id removes todo"
    (let [{:keys [id]} (db/create-todo! *ds* "Remove")]
      (let [resp (*app* (mock/request :delete (str "/todos/" id)))]
        (is (= 200 (:status resp)))
        (is (= 0 (count (db/list-todos *ds* "all"))))))))

(deftest put-update-test
  (testing "PUT /todos/:id updates title"
    (let [{:keys [id]} (db/create-todo! *ds* "Old")]
      (let [resp (*app* (-> (mock/request :put (str "/todos/" id))
                            (mock/content-type "application/x-www-form-urlencoded")
                            (mock/body "title=New")))]
        (is (= 200 (:status resp)))
        (is (= "New" (:title (first (db/list-todos *ds* "all")))))))))

(deftest post-toggle-all-test
  (testing "POST /todos/toggle-all toggles all"
    (db/create-todo! *ds* "A")
    (db/create-todo! *ds* "B")
    (let [resp (*app* (mock/request :post "/todos/toggle-all"))]
      (is (= 200 (:status resp)))
      (is (every? #(= 1 (:completed %)) (db/list-todos *ds* "all"))))))

(deftest post-clear-completed-test
  (testing "POST /todos/clear-completed removes completed"
    (db/create-todo! *ds* "Keep")
    (let [{:keys [id]} (db/create-todo! *ds* "Remove")]
      (db/toggle-todo! *ds* id)
      (let [resp (*app* (mock/request :post "/todos/clear-completed"))]
        (is (= 200 (:status resp)))
        (is (= 1 (count (db/list-todos *ds* "all"))))))))
