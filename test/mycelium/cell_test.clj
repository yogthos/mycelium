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
  (testing "Reject explicitly empty transitions set → throws"
    (is (thrown-with-msg? Exception #"transitions"
          (cell/register-cell!
           {:id          :test/no-trans
            :handler     (fn [_ d] d)
            :schema      {:input  [:map [:x :int]]
                          :output [:map [:y :int]]}
            :transitions #{}})))))

(deftest register-without-transitions-test
  (testing "Cell registered without :transitions is OK and retrievable"
    (cell/register-cell!
     {:id      :test/no-trans-ok
      :handler (fn [_ d] d)})
    (let [spec (cell/get-cell :test/no-trans-ok)]
      (is (some? spec))
      (is (= :test/no-trans-ok (:id spec)))
      (is (nil? (:transitions spec))))))

(deftest defcell-macro-test
  (testing "defcell macro registers and creates working handler (without schema)"
    (cell/defcell :test/macro-cell
      {:doc         "A test cell"
       :transitions #{:ok}}
      [_resources data]
      (assoc data :b (* 2 (:a data)) :mycelium/transition :ok))
    (let [spec (cell/get-cell :test/macro-cell)]
      (is (some? spec))
      (is (= :test/macro-cell (:id spec)))
      (is (nil? (:schema spec)))
      (is (= {:b 10 :a 5 :mycelium/transition :ok}
             ((:handler spec) {} {:a 5}))))))

(deftest defcell-async-test
  (testing "defcell with :async? true works with callback"
    (cell/defcell :test/async-cell
      {:doc         "An async cell"
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
  (testing "Reject missing required fields (no handler)"
    (is (thrown? Exception
          (cell/register-cell!
           {:id          :test/no-handler
            :transitions #{:done}})))))

(deftest schema-optional-at-registration-test
  (testing "Cell can be registered without schema"
    (cell/register-cell!
     {:id          :test/no-schema
      :handler     (fn [_ d] d)
      :transitions #{:done}})
    (let [spec (cell/get-cell :test/no-schema)]
      (is (some? spec))
      (is (nil? (:schema spec))))))

(deftest set-cell-schema-test
  (testing "set-cell-schema! attaches schema to existing cell"
    (cell/register-cell!
     {:id          :test/schema-later
      :handler     (fn [_ d] d)
      :transitions #{:done}})
    (cell/set-cell-schema! :test/schema-later
                           {:input  [:map [:x :int]]
                            :output [:map [:y :int]]})
    (let [spec (cell/get-cell :test/schema-later)]
      (is (= [:map [:x :int]] (get-in spec [:schema :input])))
      (is (= [:map [:y :int]] (get-in spec [:schema :output]))))))

(deftest set-cell-schema-throws-not-found-test
  (testing "set-cell-schema! throws if cell not registered"
    (is (thrown-with-msg? Exception #"not found"
          (cell/set-cell-schema! :test/nonexistent {:input [:map] :output [:map]})))))

(deftest set-cell-schema-throws-invalid-schema-test
  (testing "set-cell-schema! throws on invalid Malli schema"
    (cell/register-cell!
     {:id          :test/bad-schema-later
      :handler     (fn [_ d] d)
      :transitions #{:done}})
    (is (thrown? Exception
          (cell/set-cell-schema! :test/bad-schema-later
                                {:input  [:not-a-real-schema-type]
                                 :output [:map [:y :int]]})))))

;; ===== Per-transition output schema in register-cell! =====

(deftest register-cell-map-output-schema-test
  (testing "Map output schema accepted in register-cell!"
    (cell/register-cell!
     {:id          :test/map-out
      :handler     (fn [_ d] d)
      :schema      {:input  [:map [:x :int]]
                    :output {:found     [:map [:profile :map]]
                             :not-found [:map [:error-message :string]]}}
      :transitions #{:found :not-found}})
    (let [spec (cell/get-cell :test/map-out)]
      (is (map? (get-in spec [:schema :output])))
      (is (= #{:found :not-found} (set (keys (get-in spec [:schema :output]))))))))

(deftest register-cell-map-output-invalid-malli-test
  (testing "Invalid Malli in one transition value → rejected"
    (is (thrown? Exception
          (cell/register-cell!
           {:id          :test/bad-trans-schema
            :handler     (fn [_ d] d)
            :schema      {:input  [:map [:x :int]]
                          :output {:ok   [:map [:y :int]]
                                   :fail [:not-a-real-schema-type]}}
            :transitions #{:ok :fail}})))))

(deftest register-cell-map-output-key-mismatch-test
  (testing "Schema map keys that don't match transitions → rejected"
    (is (thrown-with-msg? Exception #"not in transitions"
          (cell/register-cell!
           {:id          :test/key-mismatch
            :handler     (fn [_ d] d)
            :schema      {:input  [:map [:x :int]]
                          :output {:ok   [:map [:y :int]]
                                   :typo [:map [:z :string]]}}
            :transitions #{:ok :fail}})))))

(deftest register-cell-map-output-missing-transition-key-test
  (testing "Schema map missing a transition key → rejected"
    (is (thrown-with-msg? Exception #"missing keys"
          (cell/register-cell!
           {:id          :test/missing-key
            :handler     (fn [_ d] d)
            :schema      {:input  [:map [:x :int]]
                          :output {:ok [:map [:y :int]]}}
            :transitions #{:ok :fail}})))))

;; ===== Per-transition output schema in set-cell-schema! =====

(deftest set-cell-schema-map-output-test
  (testing "set-cell-schema! accepts map format"
    (cell/register-cell!
     {:id          :test/set-map-out
      :handler     (fn [_ d] d)
      :transitions #{:found :not-found}})
    (cell/set-cell-schema! :test/set-map-out
                           {:input  [:map [:x :int]]
                            :output {:found     [:map [:profile :map]]
                                     :not-found [:map [:error :string]]}})
    (let [spec (cell/get-cell :test/set-map-out)]
      (is (map? (get-in spec [:schema :output]))))))

(deftest register-replace-clears-schema-override-test
  (testing "Re-registering a cell with :replace? clears any schema override"
    (cell/register-cell!
     {:id          :test/override-clear
      :handler     (fn [_ d] d)
      :transitions #{:done}})
    (cell/set-cell-schema! :test/override-clear
                           {:input  [:map [:x :int]]
                            :output [:map [:y :int]]})
    (is (some? (:schema (cell/get-cell :test/override-clear))))
    ;; Re-register with :replace? — should clear the schema override
    (cell/register-cell!
     {:id          :test/override-clear
      :handler     (fn [_ d] d)
      :transitions #{:done}}
     {:replace? true})
    (is (nil? (:schema (cell/get-cell :test/override-clear))))))

;; ===== set-cell-meta! tests =====

(deftest set-cell-meta-test
  (testing "set-cell-meta! sets schema, transitions, and requires on a cell"
    (cell/register-cell!
     {:id      :test/meta-cell
      :handler (fn [_ d] d)})
    (cell/set-cell-meta! :test/meta-cell
                         {:schema      {:input  [:map [:x :int]]
                                        :output [:map [:y :int]]}
                          :transitions #{:done}
                          :requires    [:db]})
    (let [spec (cell/get-cell :test/meta-cell)]
      (is (= [:map [:x :int]] (get-in spec [:schema :input])))
      (is (= [:map [:y :int]] (get-in spec [:schema :output])))
      (is (= #{:done} (:transitions spec)))
      (is (= [:db] (:requires spec))))))

(deftest set-cell-meta-clears-on-replace-test
  (testing "Re-registering a cell with :replace? clears all overrides (not just schema)"
    (cell/register-cell!
     {:id      :test/meta-replace
      :handler (fn [_ d] d)})
    (cell/set-cell-meta! :test/meta-replace
                         {:schema      {:input [:map [:x :int]] :output [:map [:y :int]]}
                          :transitions #{:done}
                          :requires    [:db]})
    (is (= #{:done} (:transitions (cell/get-cell :test/meta-replace))))
    ;; Re-register with :replace? — should clear all overrides
    (cell/register-cell!
     {:id      :test/meta-replace
      :handler (fn [_ d] d)}
     {:replace? true})
    (let [spec (cell/get-cell :test/meta-replace)]
      (is (nil? (:schema spec)))
      (is (nil? (:transitions spec)))
      (is (nil? (:requires spec))))))

(deftest set-cell-meta-validates-transitions-test
  (testing "set-cell-meta! rejects empty transitions"
    (cell/register-cell!
     {:id      :test/meta-bad-trans
      :handler (fn [_ d] d)})
    (is (thrown-with-msg? Exception #"transitions"
          (cell/set-cell-meta! :test/meta-bad-trans
                               {:transitions #{}})))))

(deftest set-cell-meta-validates-schema-test
  (testing "set-cell-meta! rejects invalid Malli schema"
    (cell/register-cell!
     {:id      :test/meta-bad-schema
      :handler (fn [_ d] d)})
    (is (thrown? Exception
          (cell/set-cell-meta! :test/meta-bad-schema
                               {:schema {:input [:not-a-real-schema-type]
                                         :output [:map [:y :int]]}
                                :transitions #{:done}})))))
