(ns mycelium.compose-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [mycelium.compose :as compose]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. workflow->cell produces valid cell spec =====

(deftest workflow-to-cell-produces-valid-spec-test
  (testing "workflow->cell produces valid cell spec with :success/:failure transitions"
    (cell/defcell :comp/step1
      {:transitions #{:done}}
      [_ data]
      (assoc data :y (* 2 (:x data)) :mycelium/transition :done))
    (cell/set-cell-schema! :comp/step1
                           {:input [:map [:x :int]]
                            :output [:map [:y :int]]})

    (let [child-workflow {:cells {:start :comp/step1}
                          :edges {:start {:done :end}}}
          cell-spec (compose/workflow->cell :comp/child child-workflow
                                           {:input [:map [:x :int]]
                                            :output [:map [:y :int]]})]
      (is (= :comp/child (:id cell-spec)))
      (is (fn? (:handler cell-spec)))
      (is (= #{:success :failure} (:transitions cell-spec)))
      (is (some? (:schema cell-spec))))))

;; ===== 2. Child workflow runs inside parent =====

(deftest child-workflow-runs-inside-parent-test
  (testing "Child workflow runs inside parent, data flows through"
    (cell/defcell :comp/doubler
      {:transitions #{:done}}
      [_ data]
      (assoc data :doubled (* 2 (:x data)) :mycelium/transition :done))
    (cell/set-cell-schema! :comp/doubler
                           {:input [:map [:x :int]]
                            :output [:map [:doubled :int]]})

    (let [child-workflow {:cells {:start :comp/doubler}
                          :edges {:start {:done :end}}}]
      ;; Register the child workflow as a cell
      (compose/register-workflow-cell! :comp/child-wf child-workflow
                                       {:input [:map [:x :int]]
                                        :output [:map [:doubled :int]]})

      ;; Parent cell that sets up data
      (cell/defcell :comp/setup
        {:transitions #{:next}}
        [_ data]
        (assoc data :x (:raw data) :mycelium/transition :next))
      (cell/set-cell-schema! :comp/setup
                             {:input [:map [:raw :int]]
                              :output [:map [:x :int]]})

      (let [parent (wf/compile-workflow
                    {:cells {:start :comp/setup
                             :child :comp/child-wf}
                     :edges {:start {:next :child}
                             :child {:success :end, :failure :error}}})
            result (fsm/run parent {} {:data {:raw 21}})]
        (is (= 42 (:doubled result)))))))

;; ===== 3. Child failure propagates as parent :failure transition =====

(deftest child-failure-propagates-test
  (testing "Child failure propagates as parent :failure transition"
    (cell/defcell :comp/failing-cell
      {:transitions #{:done}}
      [_ _data]
      (throw (Exception. "intentional failure")))
    (cell/set-cell-schema! :comp/failing-cell
                           {:input [:map [:x :int]]
                            :output [:map [:y :int]]})

    (let [child-workflow {:cells {:start :comp/failing-cell}
                          :edges {:start {:done :end}}}]
      (compose/register-workflow-cell! :comp/failing-wf child-workflow
                                       {:input [:map [:x :int]]
                                        :output [:map [:y :int]]})

      (cell/defcell :comp/error-handler
        {:transitions #{:done}}
        [_ data]
        (assoc data :error-handled true :mycelium/transition :done))
      (cell/set-cell-schema! :comp/error-handler
                             {:input [:map]
                              :output [:map [:error-handled :boolean]]})

      (let [parent (wf/compile-workflow
                    {:cells {:start  :comp/failing-wf
                             :handle :comp/error-handler}
                     :edges {:start  {:success :end, :failure :handle}
                             :handle {:done :end}}})
            result (fsm/run parent {} {:data {:x 1}})]
        (is (= true (:error-handled result)))))))

;; ===== 4. Three-level nesting =====

(deftest three-level-nesting-test
  (testing "Three-level nesting (grandchild→child→parent) works"
    (cell/defcell :comp/adder
      {:transitions #{:done}}
      [_ data]
      (assoc data :n (+ (:n data) 10) :mycelium/transition :done))
    (cell/set-cell-schema! :comp/adder
                           {:input [:map [:n :int]]
                            :output [:map [:n :int]]})

    ;; Grandchild workflow
    (let [grandchild-wf {:cells {:start :comp/adder}
                         :edges {:start {:done :end}}}]
      (compose/register-workflow-cell! :comp/grandchild grandchild-wf
                                       {:input [:map [:n :int]]
                                        :output [:map [:n :int]]})

      ;; Child workflow containing grandchild
      (let [child-wf {:cells {:start :comp/grandchild}
                      :edges {:start {:success :end, :failure :error}}}]
        (compose/register-workflow-cell! :comp/child-nested child-wf
                                         {:input [:map [:n :int]]
                                          :output [:map [:n :int]]})

        ;; Parent workflow containing child
        (let [parent (wf/compile-workflow
                      {:cells {:start :comp/child-nested}
                       :edges {:start {:success :end, :failure :error}}})
              result (fsm/run parent {} {:data {:n 0}})]
          (is (= 10 (:n result))))))))

;; ===== 5. Child trace preserved in output data =====

(deftest child-trace-preserved-test
  (testing "Child trace preserved in output data"
    (cell/defcell :comp/traced
      {:transitions #{:done}}
      [_ data]
      (assoc data :mycelium/transition :done))
    (cell/set-cell-schema! :comp/traced
                           {:input [:map [:x :int]]
                            :output [:map [:x :int]]})

    (let [child-wf {:cells {:start :comp/traced}
                    :edges {:start {:done :end}}}]
      (compose/register-workflow-cell! :comp/traced-wf child-wf
                                       {:input [:map [:x :int]]
                                        :output [:map [:x :int]]})

      (let [parent (wf/compile-workflow
                    {:cells {:start :comp/traced-wf}
                     :edges {:start {:success :end, :failure :error}}})
            result (fsm/run parent {} {:data {:x 1}})]
        (is (vector? (:mycelium/child-trace result)))))))

;; ===== 6. Resources pass through to child cells =====

(deftest resources-pass-to-child-test
  (testing "Resources pass through to child cells"
    (cell/defcell :comp/resource-user
      {:transitions #{:done}
       :requires [:config]}
      [{:keys [config]} data]
      (assoc data :from-config (:value config) :mycelium/transition :done))
    (cell/set-cell-schema! :comp/resource-user
                           {:input [:map [:x :int]]
                            :output [:map [:from-config :string]]})

    (let [child-wf {:cells {:start :comp/resource-user}
                    :edges {:start {:done :end}}}]
      (compose/register-workflow-cell! :comp/resource-wf child-wf
                                       {:input [:map [:x :int]]
                                        :output [:map [:from-config :string]]})

      (let [parent    (wf/compile-workflow
                       {:cells {:start :comp/resource-wf}
                        :edges {:start {:success :end, :failure :error}}})
            resources {:config {:value "from-parent"}}
            result    (fsm/run parent resources {:data {:x 1}})]
        (is (= "from-parent" (:from-config result)))))))
