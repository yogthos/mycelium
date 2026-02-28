(ns mycelium.fragment-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as mycelium]
            [mycelium.fragment :as fragment]
            [mycelium.manifest :as manifest]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Test fragment definitions ---

(def auth-fragment
  {:id    :cookie-auth
   :doc   "Cookie-based auth fragment"
   :entry :extract-session
   :exits [:success :failure]
   :cells
   {:extract-session
    {:id     :auth/extract-cookie-session
     :schema {:input  [:map [:http-request [:map]]]
              :output {:success [:map [:auth-token :string]]
                       :failure [:map [:error-type :keyword]
                                      [:error-message :string]]}}
     :on-error :_exit/failure}
    :validate-session
    {:id     :auth/validate-session
     :schema {:input  [:map [:auth-token :string]]
              :output {:authorized   [:map [:session-valid :boolean]
                                           [:user-id :string]]
                       :unauthorized [:map [:session-valid :boolean]
                                           [:error-type :keyword]
                                           [:error-message :string]]}}
     :on-error :_exit/failure}}
   :edges
   {:extract-session  {:success :validate-session
                       :failure :_exit/failure}
    :validate-session {:authorized   :_exit/success
                       :unauthorized :_exit/failure}}
   :dispatches
   {:extract-session  [[:success (fn [data] (:auth-token data))]
                       [:failure (fn [data] (:error-type data))]]
    :validate-session [[:authorized   (fn [data] (:session-valid data))]
                       [:unauthorized (fn [data] (not (:session-valid data)))]]}})

;; ===== 1. Validate fragment structure =====

(deftest validate-fragment-valid-test
  (testing "Valid fragment passes validation"
    (is (some? (fragment/validate-fragment auth-fragment)))))

;; ===== 2. Fragment with missing :entry → error =====

(deftest validate-fragment-missing-entry-test
  (testing "Fragment missing :entry fails validation"
    (is (thrown-with-msg? Exception #"entry"
          (fragment/validate-fragment (dissoc auth-fragment :entry))))))

;; ===== 3. Fragment with undefined exit in edges → error =====

(deftest validate-fragment-undefined-exit-test
  (testing "Fragment with exit reference not in :exits fails"
    (let [bad (update-in auth-fragment [:edges :extract-session]
                         assoc :failure :_exit/bogus)]
      (is (thrown-with-msg? Exception #"exit.*bogus"
            (fragment/validate-fragment bad))))))

;; ===== 4. Fragment cell name collision with host cell → error =====

(deftest expand-fragment-name-collision-test
  (testing "Fragment cell name collision with host cell is detected"
    ;; :validate-session is not renamed (only entry is renamed),
    ;; so collide on :validate-session
    (let [host-cells {:validate-session {:id :host/clash
                                         :schema {:input [:map] :output [:map]}}}]
      (is (thrown-with-msg? Exception #"[Cc]ollision"
            (fragment/expand-fragment
             auth-fragment
             {:as    :start
              :exits {:success :render-ok
                      :failure :render-error}}
             host-cells))))))

;; ===== 5. Expand fragment: cells merged correctly =====

(deftest expand-fragment-cells-merged-test
  (testing "Fragment cells are merged into host cells"
    (let [result (fragment/expand-fragment
                  auth-fragment
                  {:as    :start
                   :exits {:success :render-dashboard
                           :failure :render-error}}
                  {})]
      ;; Entry cell renamed to :start, non-entry cells keep original name
      (is (contains? (:cells result) :start))
      (is (contains? (:cells result) :validate-session))
      ;; Cell IDs are preserved
      (is (= :auth/extract-cookie-session
             (get-in result [:cells :start :id]))))))

;; ===== 6. Expand fragment: :_exit/* replaced with host targets =====

(deftest expand-fragment-exits-replaced-test
  (testing "Fragment :_exit/* references replaced with host targets"
    (let [result (fragment/expand-fragment
                  auth-fragment
                  {:as    :start
                   :exits {:success :render-dashboard
                           :failure :render-error}}
                  {})]
      ;; :_exit/failure should be replaced with :render-error (entry renamed to :start)
      (is (= :render-error
             (get-in result [:edges :start :failure])))
      ;; :_exit/success should be replaced with :render-dashboard
      (is (= :render-dashboard
             (get-in result [:edges :validate-session :authorized]))))))

;; ===== 7. Expand fragment: dispatches merged =====

(deftest expand-fragment-dispatches-merged-test
  (testing "Fragment dispatches are merged into result"
    (let [result (fragment/expand-fragment
                  auth-fragment
                  {:as    :start
                   :exits {:success :render-dashboard
                           :failure :render-error}}
                  {})]
      ;; Entry dispatches renamed to :start
      (is (contains? (:dispatches result) :start))
      (is (contains? (:dispatches result) :validate-session)))))

;; ===== 8. Expand fragment: :as :start maps entry correctly =====

(deftest expand-fragment-as-start-test
  (testing ":as :start renames fragment entry to :start"
    (let [result (fragment/expand-fragment
                  auth-fragment
                  {:as    :start
                   :exits {:success :render-dashboard
                           :failure :render-error}}
                  {})]
      ;; The entry cell (:extract-session) should be renamed to :start
      (is (contains? (:cells result) :start))
      (is (not (contains? (:cells result) :extract-session)))
      ;; Edges should use the renamed key
      (is (contains? (:edges result) :start))
      ;; Internal references should be updated
      (is (= :validate-session
             (get-in result [:edges :start :success]))))))

;; ===== 9. Full manifest with fragment → compiles and runs =====

(deftest full-manifest-with-fragment-test
  (testing "Full manifest with fragment compiles and runs"
    ;; Register cells
    (defmethod cell/cell-spec :frag/extract [_]
      {:id      :frag/extract
       :handler (fn [_ data] (assoc data :auth-token "tok-123"))
       :schema  {:input [:map [:http-request [:map]]]
                 :output {:success [:map [:auth-token :string]]
                          :failure [:map [:error-type :keyword]]}}})
    (defmethod cell/cell-spec :frag/validate [_]
      {:id      :frag/validate
       :handler (fn [_ data] (assoc data :user-id "u1" :session-valid true))
       :schema  {:input [:map [:auth-token :string]]
                 :output {:authorized [:map [:session-valid :boolean] [:user-id :string]]
                          :unauthorized [:map [:session-valid :boolean] [:error-type :keyword]]}}})
    (defmethod cell/cell-spec :frag/render-dashboard [_]
      {:id      :frag/render-dashboard
       :handler (fn [_ data] (assoc data :html "<h1>Dashboard</h1>"))
       :schema  {:input [:map [:user-id :string]] :output [:map [:html :string]]}})
    (defmethod cell/cell-spec :frag/render-error [_]
      {:id      :frag/render-error
       :handler (fn [_ data] (assoc data :html "<h1>Error</h1>"))
       :schema  {:input [:map] :output [:map [:html :string]]}})

    (let [frag {:id    :test-auth
                :entry :extract
                :exits [:success :failure]
                :cells {:extract  {:id     :frag/extract
                                   :schema {:input  [:map [:http-request [:map]]]
                                            :output {:success [:map [:auth-token :string]]
                                                     :failure [:map [:error-type :keyword]]}}}
                        :validate {:id     :frag/validate
                                   :schema {:input  [:map [:auth-token :string]]
                                            :output {:authorized   [:map [:session-valid :boolean] [:user-id :string]]
                                                     :unauthorized [:map [:session-valid :boolean] [:error-type :keyword]]}}}}
                :edges {:extract  {:success :validate
                                   :failure :_exit/failure}
                        :validate {:authorized   :_exit/success
                                   :unauthorized :_exit/failure}}
                :dispatches {:extract  [[:success (fn [d] (:auth-token d))]
                                        [:failure (fn [d] (:error-type d))]]
                             :validate [[:authorized   (fn [d] (:session-valid d))]
                                        [:unauthorized (fn [d] (not (:session-valid d)))]]}}
          host-manifest {:id    :test-dashboard
                         :fragments {:auth {:fragment frag
                                            :as       :start
                                            :exits    {:success :render-dashboard
                                                       :failure :render-error}}}
                         :cells {:render-dashboard {:id     :frag/render-dashboard
                                                    :schema {:input [:map [:user-id :string]]
                                                             :output [:map [:html :string]]}}
                                 :render-error     {:id     :frag/render-error
                                                    :schema {:input [:map]
                                                             :output [:map [:html :string]]}}}
                         :edges {:render-dashboard {:done :end}
                                 :render-error     {:done :end}}
                         :dispatches {:render-dashboard [[:done (fn [_] true)]]
                                      :render-error     [[:done (fn [_] true)]]}}
          expanded (manifest/expand-fragments host-manifest)
          wf-def   (manifest/manifest->workflow expanded)
          result   (mycelium/run-workflow wf-def {} {:http-request {}})]
      (is (= "<h1>Dashboard</h1>" (:html result))))))

;; ===== 10. Two fragments in same manifest → both expanded, no collision =====

(deftest two-fragments-no-collision-test
  (testing "Two fragments in same manifest both expand without collision"
    (let [frag-a {:id    :frag-a
                  :entry :cell-a
                  :exits [:done]
                  :cells {:cell-a {:id     :frag/cell-a
                                   :schema {:input [:map] :output [:map [:a-out :int]]}}}
                  :edges {:cell-a {:done :_exit/done}}
                  :dispatches {:cell-a [[:done (constantly true)]]}}
          frag-b {:id    :frag-b
                  :entry :cell-b
                  :exits [:done]
                  :cells {:cell-b {:id     :frag/cell-b
                                   :schema {:input [:map [:a-out :int]] :output [:map [:b-out :int]]}}}
                  :edges {:cell-b {:done :_exit/done}}
                  :dispatches {:cell-b [[:done (constantly true)]]}}
          host {:id    :two-frag-host
                :fragments {:phase-a {:fragment frag-a
                                      :as       :start
                                      :exits    {:done :phase-b}}
                            :phase-b {:fragment frag-b
                                      :as       :phase-b
                                      :exits    {:done :end-cell}}}
                :cells {:end-cell {:id     :frag/end-cell
                                   :schema {:input [:map [:b-out :int]]
                                            :output [:map [:result :string]]}}}
                :edges {:end-cell {:done :end}}
                :dispatches {:end-cell [[:done (constantly true)]]}}
          expanded (manifest/expand-fragments host)]
      ;; Both fragment cells should be present: :start (from frag-a) and :phase-b (from frag-b)
      (is (contains? (:cells expanded) :start))
      (is (contains? (:cells expanded) :phase-b))
      (is (contains? (:cells expanded) :end-cell)))))

;; ===== 11. Fragment exit not wired in host → error =====

(deftest fragment-unwired-exit-test
  (testing "Fragment exit not wired in host raises error"
    (is (thrown-with-msg? Exception #"exit.*success"
          (fragment/expand-fragment
           auth-fragment
           {:as    :start
            :exits {:failure :render-error}}  ;; missing :success
           {})))))

;; ===== 12. Fragment missing :exits → error =====

(deftest validate-fragment-missing-exits-test
  (testing "Fragment missing :exits fails validation"
    (is (thrown-with-msg? Exception #"exits"
          (fragment/validate-fragment (dissoc auth-fragment :exits))))))

;; ===== 13. Fragment entry not in cells → error =====

(deftest validate-fragment-entry-not-in-cells-test
  (testing "Fragment with :entry not in :cells fails"
    (is (thrown-with-msg? Exception #"entry.*nonexistent"
          (fragment/validate-fragment (assoc auth-fragment :entry :nonexistent))))))

;; ===== 14. Fragment cells missing required keys → error =====

(deftest validate-fragment-bad-cell-def-test
  (testing "Fragment cell without :id fails"
    (is (thrown-with-msg? Exception #"missing :id"
          (fragment/validate-fragment
           (assoc-in auth-fragment [:cells :extract-session] {:schema {:input [:map] :output [:map]}}))))))

;; ===== 15. Schema chain validates across fragment → host boundary =====

(deftest schema-chain-across-fragment-boundary-test
  (testing "Schema chain validates across fragment → host boundary"
    (defmethod cell/cell-spec :frag-chain/start [_]
      {:id      :frag-chain/start
       :handler (fn [_ data] (assoc data :token "abc"))
       :schema  {:input [:map [:x :int]] :output [:map [:token :string]]}})
    (defmethod cell/cell-spec :frag-chain/consumer [_]
      {:id      :frag-chain/consumer
       :handler (fn [_ data] (assoc data :result (str "got-" (:token data))))
       :schema  {:input [:map [:token :string]] :output [:map [:result :string]]}})

    (let [frag {:id    :chain-frag
                :entry :produce
                :exits [:done]
                :cells {:produce {:id     :frag-chain/start
                                  :schema {:input [:map [:x :int]]
                                           :output [:map [:token :string]]}}}
                :edges {:produce {:done :_exit/done}}
                :dispatches {:produce [[:done (constantly true)]]}}
          host {:id    :chain-host
                :fragments {:producer {:fragment frag
                                       :as       :start
                                       :exits    {:done :consumer}}}
                :cells {:consumer {:id     :frag-chain/consumer
                                   :schema {:input  [:map [:token :string]]
                                            :output [:map [:result :string]]}}}
                :edges {:consumer {:done :end}}
                :dispatches {:consumer [[:done (constantly true)]]}}
          expanded (manifest/expand-fragments host)
          wf-def   (manifest/manifest->workflow expanded)
          result   (mycelium/run-workflow wf-def {} {:x 42})]
      (is (= "got-abc" (:result result))))))
