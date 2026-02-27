(ns mycelium.join-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== Helpers =====

(defn- register-join-cells!
  "Registers cells used across multiple join tests."
  []
  ;; A start cell that produces :user-id
  (defmethod cell/cell-spec :join/start [_]
    {:id      :join/start
     :handler (fn [_ data] (assoc data :user-id "u123"))
     :schema  {:input  [:map [:x :int]]
               :output [:map [:user-id :string]]}})
  ;; Fetch profile — produces :profile
  (defmethod cell/cell-spec :join/fetch-profile [_]
    {:id      :join/fetch-profile
     :handler (fn [_ data]
                (assoc data :profile {:name "Alice" :email "a@b.com"}))
     :schema  {:input  [:map [:user-id :string]]
               :output [:map [:profile [:map [:name :string] [:email :string]]]]}})
  ;; Fetch orders — produces :orders
  (defmethod cell/cell-spec :join/fetch-orders [_]
    {:id      :join/fetch-orders
     :handler (fn [_ data]
                (assoc data :orders [{:id 1 :item "Widget"}]))
     :schema  {:input  [:map [:user-id :string]]
               :output [:map [:orders [:vector :map]]]}})
  ;; Render summary — needs both :profile and :orders
  (defmethod cell/cell-spec :join/render-summary [_]
    {:id      :join/render-summary
     :handler (fn [_ data]
                (assoc data :html (str "Summary for " (get-in data [:profile :name])
                                       " with " (count (:orders data)) " orders")))
     :schema  {:input  [:map
                        [:profile [:map [:name :string] [:email :string]]]
                        [:orders [:vector :map]]]
               :output [:map [:html :string]]}})
  ;; Render error — simple error page
  (defmethod cell/cell-spec :join/render-error [_]
    {:id      :join/render-error
     :handler (fn [_ data]
                (assoc data :html (str "Error: " (:mycelium/join-error data))))
     :schema  {:input  [:map]
               :output [:map [:html :string]]}}))

;; ===== Compile-time validation tests =====

(deftest join-compiles-basic-test
  (testing "Basic join workflow compiles successfully"
    (register-join-cells!)
    (let [workflow {:cells {:start          :join/start
                            :fetch-profile  :join/fetch-profile
                            :fetch-orders   :join/fetch-orders
                            :render-summary :join/render-summary
                            :render-error   :join/render-error}
                    :joins {:fetch-data {:cells    [:fetch-profile :fetch-orders]
                                         :strategy :parallel}}
                    :edges {:start       {:done :fetch-data, :failure :render-error}
                            :fetch-data  {:done :render-summary, :failure :render-error}
                            :render-summary {:done :end}
                            :render-error   {:done :end}}
                    :dispatches {:start [[:done (constantly true)]
                                         [:failure (constantly false)]]
                                 :render-summary [[:done (constantly true)]]
                                 :render-error   [[:done (constantly true)]]}}]
      (is (some? (wf/compile-workflow workflow))))))

(deftest join-rejects-output-key-conflict-test
  (testing "Join rejects cells with overlapping output keys when no :merge-fn"
    ;; Two cells both producing :result
    (defmethod cell/cell-spec :join/conflict-a [_]
      {:id      :join/conflict-a
       :handler (fn [_ data] (assoc data :result "a"))
       :schema  {:input  [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :join/conflict-b [_]
      {:id      :join/conflict-b
       :handler (fn [_ data] (assoc data :result "b"))
       :schema  {:input  [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :join/simple-start [_]
      {:id      :join/simple-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"[Cc]onflict"
          (wf/compile-workflow
           {:cells {:start    :join/simple-start
                    :conf-a   :join/conflict-a
                    :conf-b   :join/conflict-b}
            :joins {:j1 {:cells [:conf-a :conf-b]}}
            :edges {:start {:done :j1}
                    :j1    {:done :end}}
            :dispatches {:start [[:done (constantly true)]]}})))))

(deftest join-allows-conflict-with-merge-fn-test
  (testing "Join allows overlapping output keys when :merge-fn is provided"
    (defmethod cell/cell-spec :join/overlap-a [_]
      {:id      :join/overlap-a
       :handler (fn [_ data] (assoc data :items ["a1"]))
       :schema  {:input  [:map] :output [:map [:items [:vector :string]]]}})
    (defmethod cell/cell-spec :join/overlap-b [_]
      {:id      :join/overlap-b
       :handler (fn [_ data] (assoc data :items ["b1"]))
       :schema  {:input  [:map] :output [:map [:items [:vector :string]]]}})
    (defmethod cell/cell-spec :join/noop-start [_]
      {:id      :join/noop-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (is (some? (wf/compile-workflow
                {:cells {:start   :join/noop-start
                         :ov-a    :join/overlap-a
                         :ov-b    :join/overlap-b}
                 :joins {:j1 {:cells    [:ov-a :ov-b]
                               :merge-fn (fn [data results]
                                           (assoc data :items
                                                  (vec (mapcat :items results))))}}
                 :edges {:start {:done :j1}
                         :j1    {:done :end}}
                 :dispatches {:start [[:done (constantly true)]]}})))))

(deftest join-rejects-missing-cell-refs-test
  (testing "Join rejects references to cells not in :cells map"
    (defmethod cell/cell-spec :join/only-start [_]
      {:id      :join/only-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"not found in :cells"
          (wf/compile-workflow
           {:cells {:start :join/only-start}
            :joins {:j1 {:cells [:nonexistent-a :nonexistent-b]}}
            :edges {:start {:done :j1}
                    :j1    {:done :end}}
            :dispatches {:start [[:done (constantly true)]]}})))))

(deftest join-rejects-name-collision-test
  (testing "Join name must not collide with cell name"
    (defmethod cell/cell-spec :join/col-start [_]
      {:id      :join/col-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :join/col-cell [_]
      {:id      :join/col-cell
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"[Cc]ollision|[Cc]onflict"
          (wf/compile-workflow
           {:cells {:start   :join/col-start
                    :my-join :join/col-cell}
            :joins {:my-join {:cells [:start]}}
            :edges {:start    {:done :my-join}
                    :my-join  {:done :end}}
            :dispatches {:start [[:done (constantly true)]]}})))))

(deftest join-rejects-member-cells-in-edges-test
  (testing "Join member cells must not have their own entries in :edges"
    (register-join-cells!)
    (is (thrown-with-msg? Exception #"[Mm]ember.*edges|edges.*member"
          (wf/compile-workflow
           {:cells {:start          :join/start
                    :fetch-profile  :join/fetch-profile
                    :fetch-orders   :join/fetch-orders
                    :render-summary :join/render-summary}
            :joins {:fetch-data {:cells [:fetch-profile :fetch-orders]}}
            :edges {:start          {:done :fetch-data}
                    :fetch-profile  {:done :render-summary}  ;; not allowed!
                    :fetch-data     {:done :render-summary}
                    :render-summary {:done :end}}
            :dispatches {:start          [[:done (constantly true)]]
                         :fetch-profile  [[:done (constantly true)]]
                         :render-summary [[:done (constantly true)]]}})))))

(deftest join-rejects-empty-cells-test
  (testing "Join rejects empty :cells vector"
    (defmethod cell/cell-spec :join/empty-start [_]
      {:id      :join/empty-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"[Ee]mpty"
          (wf/compile-workflow
           {:cells {:start :join/empty-start}
            :joins {:j1 {:cells []}}
            :edges {:start {:done :j1}
                    :j1    {:done :end}}
            :dispatches {:start [[:done (constantly true)]]}})))))

(deftest join-rejects-invalid-strategy-test
  (testing "Join rejects invalid :strategy value"
    (defmethod cell/cell-spec :join/strat-start [_]
      {:id      :join/strat-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :join/strat-cell [_]
      {:id      :join/strat-cell
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"[Ss]trategy"
          (wf/compile-workflow
           {:cells {:start  :join/strat-start
                    :cell-a :join/strat-cell}
            :joins {:j1 {:cells [:cell-a] :strategy :random}}
            :edges {:start {:done :j1}
                    :j1    {:done :end}}
            :dispatches {:start [[:done (constantly true)]]}})))))

;; ===== Runtime: Parallel execution =====

(deftest join-parallel-basic-merge-test
  (testing "Parallel join merges results from all branches"
    (register-join-cells!)
    (let [workflow {:cells {:start          :join/start
                            :fetch-profile  :join/fetch-profile
                            :fetch-orders   :join/fetch-orders
                            :render-summary :join/render-summary
                            :render-error   :join/render-error}
                    :joins {:fetch-data {:cells    [:fetch-profile :fetch-orders]
                                         :strategy :parallel}}
                    :edges {:start          {:done :fetch-data, :failure :render-error}
                            :fetch-data     {:done :render-summary, :failure :render-error}
                            :render-summary {:done :end}
                            :render-error   {:done :end}}
                    :dispatches {:start          [[:done (constantly true)]
                                                   [:failure (constantly false)]]
                                 :render-summary [[:done (constantly true)]]
                                 :render-error   [[:done (constantly true)]]}}
          compiled (wf/compile-workflow workflow)
          result   (fsm/run compiled {} {:data {:x 1}})]
      ;; Both :profile and :orders should be present
      (is (= {:name "Alice" :email "a@b.com"} (:profile result)))
      (is (= [{:id 1 :item "Widget"}] (:orders result)))
      ;; render-summary ran successfully
      (is (string? (:html result))))))

(deftest join-snapshot-semantics-test
  (testing "Each branch receives the same input snapshot, not accumulated data"
    ;; cell-a reads :input-val and writes :a-out
    (defmethod cell/cell-spec :join/snap-a [_]
      {:id      :join/snap-a
       :handler (fn [_ data]
                  (assoc data :a-out (:input-val data)
                              :saw-b-out (:b-out data)))
       :schema  {:input  [:map [:input-val :int]]
                 :output [:map [:a-out :int]]}})
    ;; cell-b reads :input-val and writes :b-out
    (defmethod cell/cell-spec :join/snap-b [_]
      {:id      :join/snap-b
       :handler (fn [_ data]
                  (assoc data :b-out (:input-val data)
                              :saw-a-out (:a-out data)))
       :schema  {:input  [:map [:input-val :int]]
                 :output [:map [:b-out :int]]}})
    (defmethod cell/cell-spec :join/snap-start [_]
      {:id      :join/snap-start
       :handler (fn [_ data] (assoc data :input-val 42))
       :schema  {:input [:map] :output [:map [:input-val :int]]}})
    (let [compiled (wf/compile-workflow
                    {:cells {:start  :join/snap-start
                             :snap-a :join/snap-a
                             :snap-b :join/snap-b}
                     :joins {:j1 {:cells [:snap-a :snap-b]
                                   :strategy :sequential}}
                     :edges {:start {:done :j1}
                             :j1    {:done :end}}
                     :dispatches {:start [[:done (constantly true)]]}})
          result (fsm/run compiled {} {:data {}})]
      ;; Both should see :input-val from upstream
      (is (= 42 (:a-out result)))
      (is (= 42 (:b-out result)))
      ;; Neither should see the other's output (snapshot semantics)
      (is (nil? (:saw-b-out result)))
      (is (nil? (:saw-a-out result))))))

(deftest join-one-branch-failure-test
  (testing "One branch failing puts :mycelium/join-error on data, routes via :failure"
    (defmethod cell/cell-spec :join/ok-cell [_]
      {:id      :join/ok-cell
       :handler (fn [_ data] (assoc data :ok-result "fine"))
       :schema  {:input [:map] :output [:map [:ok-result :string]]}})
    (defmethod cell/cell-spec :join/fail-cell [_]
      {:id      :join/fail-cell
       :handler (fn [_ _data] (throw (Exception. "branch exploded")))
       :schema  {:input [:map] :output [:map [:fail-result :string]]}})
    (defmethod cell/cell-spec :join/err-start [_]
      {:id      :join/err-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :join/err-handler [_]
      {:id      :join/err-handler
       :handler (fn [_ data]
                  (assoc data :handled true))
       :schema  {:input [:map] :output [:map [:handled :boolean]]}})
    (let [compiled (wf/compile-workflow
                    {:cells {:start   :join/err-start
                             :ok-c    :join/ok-cell
                             :fail-c  :join/fail-cell
                             :handler :join/err-handler}
                     :joins {:j1 {:cells       [:ok-c :fail-c]
                                   :on-failure :handler}}
                     :edges {:start   {:done :j1}
                             :j1      {:done :end, :failure :handler}
                             :handler {:done :end}}
                     :dispatches {:start   [[:done (constantly true)]]
                                  :handler [[:done (constantly true)]]}})
          result (fsm/run compiled {} {:data {}})]
      (is (= true (:handled result)))
      (is (some? (:mycelium/join-error result))))))

(deftest join-both-branches-failure-test
  (testing "Both branches failing collects all errors"
    (defmethod cell/cell-spec :join/fail-a [_]
      {:id      :join/fail-a
       :handler (fn [_ _data] (throw (Exception. "a failed")))
       :schema  {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :join/fail-b [_]
      {:id      :join/fail-b
       :handler (fn [_ _data] (throw (Exception. "b failed")))
       :schema  {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :join/fail-start [_]
      {:id      :join/fail-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :join/fail-handler [_]
      {:id      :join/fail-handler
       :handler (fn [_ data] (assoc data :caught true))
       :schema  {:input [:map] :output [:map [:caught :boolean]]}})
    (let [compiled (wf/compile-workflow
                    {:cells {:start   :join/fail-start
                             :fail-a  :join/fail-a
                             :fail-b  :join/fail-b
                             :handler :join/fail-handler}
                     :joins {:j1 {:cells [:fail-a :fail-b]}}
                     :edges {:start   {:done :j1}
                             :j1      {:done :end, :failure :handler}
                             :handler {:done :end}}
                     :dispatches {:start   [[:done (constantly true)]]
                                  :handler [[:done (constantly true)]]}})
          result (fsm/run compiled {} {:data {}})]
      (is (= true (:caught result)))
      ;; Should have errors from both branches
      (let [errors (:mycelium/join-error result)]
        (is (vector? errors))
        (is (= 2 (count errors)))))))

(deftest join-custom-merge-fn-test
  (testing "Custom merge-fn is used to combine results"
    (defmethod cell/cell-spec :join/items-a [_]
      {:id      :join/items-a
       :handler (fn [_ data] (assoc data :items ["a1" "a2"]))
       :schema  {:input  [:map] :output [:map [:items [:vector :string]]]}})
    (defmethod cell/cell-spec :join/items-b [_]
      {:id      :join/items-b
       :handler (fn [_ data] (assoc data :items ["b1"]))
       :schema  {:input  [:map] :output [:map [:items [:vector :string]]]}})
    (defmethod cell/cell-spec :join/merge-start [_]
      {:id      :join/merge-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (let [compiled (wf/compile-workflow
                    {:cells {:start   :join/merge-start
                             :items-a :join/items-a
                             :items-b :join/items-b}
                     :joins {:j1 {:cells    [:items-a :items-b]
                                   :merge-fn (fn [data results]
                                               (assoc data :items
                                                      (vec (mapcat :items results))))}}
                     :edges {:start {:done :j1}
                             :j1    {:done :end}}
                     :dispatches {:start [[:done (constantly true)]]}})
          result (fsm/run compiled {} {:data {}})]
      (is (= ["a1" "a2" "b1"] (:items result))))))

;; ===== Runtime: Sequential execution =====

(deftest join-sequential-basic-test
  (testing "Sequential join runs members in order, merges results"
    (register-join-cells!)
    (let [workflow {:cells {:start          :join/start
                            :fetch-profile  :join/fetch-profile
                            :fetch-orders   :join/fetch-orders
                            :render-summary :join/render-summary
                            :render-error   :join/render-error}
                    :joins {:fetch-data {:cells    [:fetch-profile :fetch-orders]
                                         :strategy :sequential}}
                    :edges {:start          {:done :fetch-data, :failure :render-error}
                            :fetch-data     {:done :render-summary, :failure :render-error}
                            :render-summary {:done :end}
                            :render-error   {:done :end}}
                    :dispatches {:start          [[:done (constantly true)]
                                                   [:failure (constantly false)]]
                                 :render-summary [[:done (constantly true)]]
                                 :render-error   [[:done (constantly true)]]}}
          compiled (wf/compile-workflow workflow)
          result   (fsm/run compiled {} {:data {:x 1}})]
      (is (= {:name "Alice" :email "a@b.com"} (:profile result)))
      (is (= [{:id 1 :item "Widget"}] (:orders result)))
      (is (string? (:html result))))))

(deftest join-sequential-snapshot-test
  (testing "Sequential join still uses snapshot semantics — each member gets original data"
    (defmethod cell/cell-spec :join/seq-a [_]
      {:id      :join/seq-a
       :handler (fn [_ data]
                  (assoc data :a-val 100 :saw-b (:b-val data)))
       :schema  {:input [:map] :output [:map [:a-val :int]]}})
    (defmethod cell/cell-spec :join/seq-b [_]
      {:id      :join/seq-b
       :handler (fn [_ data]
                  (assoc data :b-val 200 :saw-a (:a-val data)))
       :schema  {:input [:map] :output [:map [:b-val :int]]}})
    (defmethod cell/cell-spec :join/seq-start [_]
      {:id      :join/seq-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (let [compiled (wf/compile-workflow
                    {:cells {:start :join/seq-start
                             :seq-a :join/seq-a
                             :seq-b :join/seq-b}
                     :joins {:j1 {:cells    [:seq-a :seq-b]
                                   :strategy :sequential}}
                     :edges {:start {:done :j1}
                             :j1    {:done :end}}
                     :dispatches {:start [[:done (constantly true)]]}})
          result (fsm/run compiled {} {:data {}})]
      ;; Neither should see the other's output
      (is (nil? (:saw-b result)))
      (is (nil? (:saw-a result)))
      (is (= 100 (:a-val result)))
      (is (= 200 (:b-val result))))))

;; ===== Schema chain validation with joins =====

(deftest join-schema-chain-valid-test
  (testing "Schema chain validates join member inputs against upstream available keys"
    (register-join-cells!)
    ;; fetch-profile needs :user-id, fetch-orders needs :user-id
    ;; start produces :user-id — should pass
    (is (some? (wf/compile-workflow
                {:cells {:start          :join/start
                         :fetch-profile  :join/fetch-profile
                         :fetch-orders   :join/fetch-orders
                         :render-summary :join/render-summary}
                 :joins {:fetch-data {:cells [:fetch-profile :fetch-orders]}}
                 :edges {:start      {:done :fetch-data}
                         :fetch-data {:done :render-summary}
                         :render-summary {:done :end}}
                 :dispatches {:start [[:done (constantly true)]]
                              :render-summary [[:done (constantly true)]]}})))))

(deftest join-schema-chain-missing-key-test
  (testing "Schema chain catches missing input keys for join members"
    (defmethod cell/cell-spec :join/needs-z [_]
      {:id      :join/needs-z
       :handler (fn [_ data] (assoc data :z-out true))
       :schema  {:input  [:map [:z :string]]
                 :output [:map [:z-out :boolean]]}})
    (defmethod cell/cell-spec :join/sc-start [_]
      {:id      :join/sc-start
       :handler (fn [_ data] (assoc data :x-out true))
       :schema  {:input [:map] :output [:map [:x-out :boolean]]}})
    (defmethod cell/cell-spec :join/sc-ok [_]
      {:id      :join/sc-ok
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"[Ss]chema chain"
          (wf/compile-workflow
           {:cells {:start  :join/sc-start
                    :ok-c   :join/sc-ok
                    :needs-z :join/needs-z}
            :joins {:j1 {:cells [:ok-c :needs-z]}}
            :edges {:start {:done :j1}
                    :j1    {:done :end}}
            :dispatches {:start [[:done (constantly true)]]}})))))

(deftest join-schema-chain-downstream-gets-union-test
  (testing "Downstream cell gets union of all join member output keys"
    (register-join-cells!)
    ;; render-summary needs :profile AND :orders
    ;; fetch-profile produces :profile, fetch-orders produces :orders
    ;; Union should satisfy render-summary's input
    (is (some? (wf/compile-workflow
                {:cells {:start          :join/start
                         :fetch-profile  :join/fetch-profile
                         :fetch-orders   :join/fetch-orders
                         :render-summary :join/render-summary}
                 :joins {:fetch-data {:cells [:fetch-profile :fetch-orders]}}
                 :edges {:start          {:done :fetch-data}
                         :fetch-data     {:done :render-summary}
                         :render-summary {:done :end}}
                 :dispatches {:start          [[:done (constantly true)]]
                              :render-summary [[:done (constantly true)]]}})))))

;; ===== Trace recording =====

(deftest join-trace-entry-test
  (testing "Join records a single trace entry with sub-traces from members"
    (register-join-cells!)
    (let [compiled (wf/compile-workflow
                    {:cells {:start          :join/start
                             :fetch-profile  :join/fetch-profile
                             :fetch-orders   :join/fetch-orders
                             :render-summary :join/render-summary}
                     :joins {:fetch-data {:cells [:fetch-profile :fetch-orders]}}
                     :edges {:start          {:done :fetch-data}
                             :fetch-data     {:done :render-summary}
                             :render-summary {:done :end}}
                     :dispatches {:start          [[:done (constantly true)]]
                                  :render-summary [[:done (constantly true)]]}})
          result (fsm/run compiled {} {:data {:x 1}})
          trace  (:mycelium/trace result)]
      ;; Should have 3 trace entries: start, fetch-data (join), render-summary
      (is (= 3 (count trace)))
      ;; The join trace entry should have sub-traces
      (let [join-entry (second trace)]
        (is (= :fetch-data (:cell join-entry)))
        (is (vector? (:join-traces join-entry)))
        (is (= 2 (count (:join-traces join-entry)))))
      ;; :mycelium/join-traces should NOT be on the data
      (is (nil? (:mycelium/join-traces result))))))

;; ===== Async cell in join =====

(deftest join-async-cell-test
  (testing "Async cells work within joins"
    (defmethod cell/cell-spec :join/async-fetch [_]
      {:id      :join/async-fetch
       :handler (fn [_ data callback _error-cb]
                  (future
                    (Thread/sleep 10)
                    (callback (assoc data :async-result "fetched"))))
       :schema  {:input  [:map]
                 :output [:map [:async-result :string]]}
       :async?  true})
    (defmethod cell/cell-spec :join/sync-fetch [_]
      {:id      :join/sync-fetch
       :handler (fn [_ data]
                  (assoc data :sync-result "also fetched"))
       :schema  {:input  [:map]
                 :output [:map [:sync-result :string]]}})
    (defmethod cell/cell-spec :join/async-start [_]
      {:id      :join/async-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (let [compiled (wf/compile-workflow
                    {:cells {:start      :join/async-start
                             :async-f    :join/async-fetch
                             :sync-f     :join/sync-fetch}
                     :joins {:j1 {:cells [:async-f :sync-f]}}
                     :edges {:start {:done :j1}
                             :j1    {:done :end}}
                     :dispatches {:start [[:done (constantly true)]]}})
          result (fsm/run compiled {} {:data {}})]
      (is (= "fetched" (:async-result result)))
      (is (= "also fetched" (:sync-result result))))))

;; ===== Default strategy =====

(deftest join-default-strategy-is-parallel-test
  (testing "Omitting :strategy defaults to :parallel"
    (register-join-cells!)
    (let [compiled (wf/compile-workflow
                    {:cells {:start          :join/start
                             :fetch-profile  :join/fetch-profile
                             :fetch-orders   :join/fetch-orders
                             :render-summary :join/render-summary}
                     :joins {:fetch-data {:cells [:fetch-profile :fetch-orders]}}
                     :edges {:start          {:done :fetch-data}
                             :fetch-data     {:done :render-summary}
                             :render-summary {:done :end}}
                     :dispatches {:start          [[:done (constantly true)]]
                                  :render-summary [[:done (constantly true)]]}})
          result (fsm/run compiled {} {:data {:x 1}})]
      (is (= {:name "Alice" :email "a@b.com"} (:profile result)))
      (is (= [{:id 1 :item "Widget"}] (:orders result))))))

;; ===== on-failure validation =====

(deftest join-on-failure-must-be-valid-test
  (testing ":on-failure must reference a valid cell name or terminal state"
    (defmethod cell/cell-spec :join/onf-start [_]
      {:id      :join/onf-start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :join/onf-cell [_]
      {:id      :join/onf-cell
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"on-failure|not found"
          (wf/compile-workflow
           {:cells {:start :join/onf-start
                    :cell-a :join/onf-cell}
            :joins {:j1 {:cells      [:cell-a]
                          :on-failure :nonexistent-handler}}
            :edges {:start {:done :j1}
                    :j1    {:done :end}}
            :dispatches {:start [[:done (constantly true)]]}})))))
