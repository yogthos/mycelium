(ns mycelium.interceptor-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as mycelium]
            [mycelium.manifest :as manifest]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Helper: register cells ---

(defn- register-cells! []
  (defmethod cell/cell-spec :intc/start [_]
    {:id      :intc/start
     :handler (fn [_ data] (assoc data :started true))
     :schema  {:input [:map [:x :int]] :output [:map [:started :boolean]]}})
  (defmethod cell/cell-spec :intc/process [_]
    {:id      :intc/process
     :handler (fn [_ data] (assoc data :processed true))
     :schema  {:input [:map [:started :boolean]] :output [:map [:processed :boolean]]}})
  (defmethod cell/cell-spec :ui/render-a [_]
    {:id      :ui/render-a
     :handler (fn [_ data] (assoc data :rendered (get data :injected :default-render)))
     :schema  {:input [:map [:processed :boolean]] :output [:map [:rendered :any]]}})
  (defmethod cell/cell-spec :auth/check [_]
    {:id      :auth/check
     :handler (fn [_ data] (assoc data :checked true))
     :schema  {:input [:map [:x :int]] :output [:map [:checked :boolean]]}}))

(def base-workflow
  {:cells {:start   :intc/start
           :process :intc/process
           :render  :ui/render-a}
   :edges {:start   {:done :process}
           :process {:done :render}
           :render  {:done :end}}
   :dispatches {:start   [[:done (constantly true)]]
                :process [[:done (constantly true)]]
                :render  [[:done (constantly true)]]}})

;; ===== 1. :scope :all interceptor runs on every cell =====

(deftest scope-all-test
  (testing ":scope :all interceptor runs on every cell"
    (register-cells!)
    (let [log (atom [])
          result (mycelium/run-workflow
                  (assoc base-workflow
                         :interceptors
                         [{:id    :logger
                           :scope :all
                           :post  (fn [data]
                                    (swap! log conj :visited)
                                    data)}])
                  {}
                  {:x 1})]
      ;; 3 cells, so 3 visits
      (is (= 3 (count @log)))
      (is (some? (:rendered result))))))

;; ===== 2. :scope {:id-match "ui/*"} only runs on matching cells =====

(deftest scope-id-match-test
  (testing ":scope {:id-match \"ui/*\"} only runs on matching cells"
    (register-cells!)
    (let [log (atom [])
          result (mycelium/run-workflow
                  (assoc base-workflow
                         :interceptors
                         [{:id    :ui-only
                           :scope {:id-match "ui/*"}
                           :pre   (fn [data]
                                    (swap! log conj :ui-hit)
                                    (assoc data :injected "from-interceptor"))}])
                  {}
                  {:x 1})]
      ;; Only ui/render-a matches
      (is (= 1 (count @log)))
      (is (= "from-interceptor" (:rendered result))))))

;; ===== 3. :scope {:cells [:start :render]} only runs on named cells =====

(deftest scope-cells-list-test
  (testing ":scope {:cells [...]} only runs on named cells"
    (register-cells!)
    (let [log (atom [])
          result (mycelium/run-workflow
                  (assoc base-workflow
                         :interceptors
                         [{:id    :selective
                           :scope {:cells [:start :render]}
                           :post  (fn [data]
                                    (swap! log conj :hit)
                                    data)}])
                  {}
                  {:x 1})]
      (is (= 2 (count @log)))
      (is (some? (:rendered result))))))

;; ===== 4. Pre-interceptor modifies data before handler sees it =====

(deftest pre-interceptor-modifies-data-test
  (testing "Pre-interceptor modifies data before handler sees it"
    (register-cells!)
    ;; Inject :started true before the start handler, so handler doesn't need to
    (defmethod cell/cell-spec :intc/start [_]
      {:id      :intc/start
       :handler (fn [_ data] data) ;; no-op, relies on pre
       :schema  {:input [:map [:x :int]] :output [:map [:started :boolean]]}})
    (let [result (mycelium/run-workflow
                  (assoc base-workflow
                         :interceptors
                         [{:id    :inject-started
                           :scope {:cells [:start]}
                           :pre   (fn [data] (assoc data :started true))}])
                  {}
                  {:x 1})]
      (is (true? (:started result))))))

;; ===== 5. Post-interceptor modifies data after handler returns =====

(deftest post-interceptor-modifies-data-test
  (testing "Post-interceptor modifies data after handler returns"
    (register-cells!)
    (let [result (mycelium/run-workflow
                  (assoc base-workflow
                         :interceptors
                         [{:id    :annotate
                           :scope {:cells [:render]}
                           :post  (fn [data] (assoc data :annotated true))}])
                  {}
                  {:x 1})]
      (is (true? (:annotated result))))))

;; ===== 6. Multiple interceptors compose in order =====

(deftest multiple-interceptors-compose-test
  (testing "Multiple interceptors compose in declaration order"
    (register-cells!)
    (let [log (atom [])
          result (mycelium/run-workflow
                  (assoc base-workflow
                         :interceptors
                         [{:id    :first
                           :scope :all
                           :pre   (fn [data]
                                    (swap! log conj :first-pre)
                                    data)
                           :post  (fn [data]
                                    (swap! log conj :first-post)
                                    data)}
                          {:id    :second
                           :scope :all
                           :pre   (fn [data]
                                    (swap! log conj :second-pre)
                                    data)
                           :post  (fn [data]
                                    (swap! log conj :second-post)
                                    data)}])
                  {}
                  {:x 1})]
      ;; For each cell: first-pre, second-pre, handler, first-post, second-post
      ;; 3 cells × 4 interceptor calls = 12
      (is (= 12 (count @log)))
      ;; Within each cell group, order is: first-pre, second-pre, first-post, second-post
      (let [first-cell (take 4 @log)]
        (is (= [:first-pre :second-pre :first-post :second-post] first-cell))))))

;; ===== 7. Workflow without :interceptors → backwards compatible =====

(deftest no-interceptors-backwards-compatible-test
  (testing "Workflow without :interceptors runs normally"
    (register-cells!)
    (let [result (mycelium/run-workflow base-workflow {} {:x 1})]
      (is (some? (:rendered result))))))

;; ===== 8. Interceptor with only :pre (no :post) → works =====

(deftest pre-only-interceptor-test
  (testing "Interceptor with only :pre (no :post) works"
    (register-cells!)
    (let [result (mycelium/run-workflow
                  (assoc base-workflow
                         :interceptors
                         [{:id    :pre-only
                           :scope :all
                           :pre   (fn [data] (update data :pre-count (fnil inc 0)))}])
                  {}
                  {:x 1})]
      (is (= 3 (:pre-count result))))))

;; ===== 9. Interceptor with only :post (no :pre) → works =====

(deftest post-only-interceptor-test
  (testing "Interceptor with only :post (no :pre) works"
    (register-cells!)
    (let [result (mycelium/run-workflow
                  (assoc base-workflow
                         :interceptors
                         [{:id    :post-only
                           :scope :all
                           :post  (fn [data] (update data :post-count (fnil inc 0)))}])
                  {}
                  {:x 1})]
      (is (= 3 (:post-count result))))))

;; ===== 10. Interceptor wrapping preserves async cell behavior =====

(deftest async-cell-with-interceptor-test
  (testing "Interceptor wrapping works with async cells"
    (register-cells!)
    ;; Register an async cell
    (defmethod cell/cell-spec :intc/async-step [_]
      {:id      :intc/async-step
       :handler (fn [_ data callback _error-callback]
                  (future (callback (assoc data :async-result 42))))
       :schema  {:input [:map [:x :int]] :output [:map [:async-result :int]]}
       :async?  true})
    (let [log (atom [])
          result (mycelium/run-workflow
                  {:cells {:start :intc/start
                           :async-step :intc/async-step}
                   :edges {:start {:done :async-step}
                           :async-step {:done :end}}
                   :dispatches {:start [[:done (constantly true)]]
                                :async-step [[:done (constantly true)]]}
                   :interceptors [{:id    :logger
                                   :scope :all
                                   :pre   (fn [data]
                                            (swap! log conj :visited)
                                            data)}]}
                  {}
                  {:x 1})]
      (is (= 42 (:async-result result)))
      (is (= 2 (count @log))))))

;; ===== 11. Manifest with :interceptors passes validation and through to workflow =====

(deftest manifest-interceptors-passthrough-test
  (testing "Manifest with :interceptors passes validation and through to workflow"
    (let [m {:id :test/intc-manifest
             :cells {:start {:id     :intc/m-cell
                              :schema {:input [:map [:x :int]]
                                       :output [:map [:y :int]]}}}
             :edges {:start {:done :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :interceptors [{:id    :test-intc
                             :scope :all
                             :pre   (fn [d] d)}]}
          wf-def (manifest/manifest->workflow m)]
      (is (= 1 (count (:interceptors wf-def)))))))
