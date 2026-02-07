(ns mycelium.cell-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(deftest register-valid-cell-test
  (testing "Register valid cell spec → retrievable from registry"
    (cell/register-cell!
     {:id          :test/valid-cell
      :handler     (fn [_resources data] data)
      :schema      {:input  [:map [:x :int]]
                    :output [:map [:y :int]]}
      :transitions #{:success :failure}})
    (is (= :test/valid-cell (:id (cell/get-cell :test/valid-cell))))))

(deftest reject-duplicate-cell-test
  (testing "Reject duplicate cell ID → throws"
    (cell/register-cell!
     {:id          :test/dup
      :handler     (fn [_ d] d)
      :schema      {:input  [:map [:x :int]]
                    :output [:map [:y :int]]}
      :transitions #{:done}})
    (is (thrown-with-msg? Exception #"already registered"
          (cell/register-cell!
           {:id          :test/dup
            :handler     (fn [_ d] d)
            :schema      {:input  [:map [:x :int]]
                          :output [:map [:y :int]]}
            :transitions #{:done}})))))

(deftest reject-invalid-schema-test
  (testing "Reject invalid Malli schema → throws"
    (is (thrown? Exception
          (cell/register-cell!
           {:id          :test/bad-schema
            :handler     (fn [_ d] d)
            :schema      {:input  [:not-a-real-schema-type]
                          :output [:map [:y :int]]}
            :transitions #{:done}})))))

(deftest reject-empty-transitions-test
  (testing "Reject empty transitions set → throws"
    (is (thrown-with-msg? Exception #"transitions"
          (cell/register-cell!
           {:id          :test/no-trans
            :handler     (fn [_ d] d)
            :schema      {:input  [:map [:x :int]]
                          :output [:map [:y :int]]}
            :transitions #{}})))))

(deftest defcell-macro-test
  (testing "defcell macro registers and creates working handler"
    (cell/defcell :test/macro-cell
      {:doc         "A test cell"
       :schema      {:input  [:map [:a :int]]
                     :output [:map [:b :int]]}
       :transitions #{:ok}}
      [_resources data]
      (assoc data :b (* 2 (:a data)) :mycelium/transition :ok))
    (let [spec (cell/get-cell :test/macro-cell)]
      (is (some? spec))
      (is (= :test/macro-cell (:id spec)))
      (is (= {:b 10 :a 5 :mycelium/transition :ok}
             ((:handler spec) {} {:a 5}))))))

(deftest defcell-async-test
  (testing "defcell with :async? true works with callback"
    (cell/defcell :test/async-cell
      {:doc         "An async cell"
       :schema      {:input  [:map [:x :int]]
                     :output [:map [:y :int]]}
       :transitions #{:done}
       :async?      true}
      [_resources data callback _error-callback]
      (callback (assoc data :y (inc (:x data)) :mycelium/transition :done)))
    (let [spec    (cell/get-cell :test/async-cell)
          result  (promise)]
      (is (:async? spec))
      ((:handler spec) {} {:x 41} #(deliver result %) identity)
      (is (= {:x 41 :y 42 :mycelium/transition :done} @result)))))

(deftest get-cell-bang-throws-test
  (testing "get-cell! throws on missing ID"
    (is (thrown-with-msg? Exception #"not found"
          (cell/get-cell! :test/nonexistent)))))

(deftest reject-missing-required-fields-test
  (testing "Reject missing required fields (no handler, no schema)"
    (is (thrown? Exception
          (cell/register-cell!
           {:id          :test/no-handler
            :schema      {:input  [:map [:x :int]]
                          :output [:map [:y :int]]}
            :transitions #{:done}})))
    (is (thrown? Exception
          (cell/register-cell!
           {:id          :test/no-schema
            :handler     (fn [_ d] d)
            :transitions #{:done}})))))
