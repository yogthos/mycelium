(ns loan.cells-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            loan.cells))

;; Ensure cells are registered even with random test ordering
(use-fixtures :each (fn [f]
                      (when-not (cell/get-cell :loan/validate-application)
                        (require 'loan.cells :reload))
                      (f)))

;; ===== Dispatch predicate helpers =====

(def validation-dispatches
  [[:valid   (fn [d] (= :valid (:validation-status d)))]
   [:invalid (fn [d] (= :invalid (:validation-status d)))]])

(def decision-dispatches
  [[:approve (fn [d] (= :approve (:decision d)))]
   [:reject  (fn [d] (= :reject (:decision d)))]
   [:review  (fn [d] (= :review (:decision d)))]])

(def fetch-dispatches
  [[:found     (fn [d] (= :found (:fetch-status d)))]
   [:not-found (fn [d] (= :not-found (:fetch-status d)))]])

;; ===== validate-application =====

(deftest validate-application-valid-test
  (testing "validates a complete, correct application"
    (let [result (dev/test-cell :loan/validate-application
                  {:input      {:applicant-name "Alice" :income 80000 :loan-amount 30000}
                   :dispatches validation-dispatches})]
      (is (:pass? result))
      (is (= :valid (:matched-dispatch result)))
      (is (= :valid (get-in result [:output :validation-status]))))))

(deftest validate-application-invalid-test
  (testing "rejects application with missing fields"
    (let [result (dev/test-cell :loan/validate-application
                  {:input      {:applicant-name "" :income nil :loan-amount nil}
                   :dispatches validation-dispatches})]
      (is (:pass? result))
      (is (= :invalid (:matched-dispatch result)))
      (is (= :invalid (get-in result [:output :validation-status])))
      (is (seq (get-in result [:output :validation-errors]))))))

(deftest validate-application-transitions-test
  (testing "both transitions with test-transitions"
    (let [results (dev/test-transitions :loan/validate-application
                    {:valid   {:input      {:applicant-name "Bob" :income 50000 :loan-amount 20000}
                               :dispatches validation-dispatches}
                     :invalid {:input      {:applicant-name nil :income -1 :loan-amount 0}
                               :dispatches validation-dispatches}})]
      (is (get-in results [:valid :pass?]))
      (is (get-in results [:invalid :pass?])))))

;; ===== credit-bureau-lookup =====

(deftest credit-bureau-lookup-known-test
  (testing "returns credit history for a known applicant"
    (let [credit-db {"Alice" {:accounts 5 :late-payments 0
                               :bankruptcies 0 :years-of-history 10}}
          result    (dev/test-cell :loan/credit-bureau-lookup
                     {:input     {:applicant-name "Alice"}
                      :resources {:credit-db credit-db}})]
      (is (:pass? result))
      (is (= 5 (get-in result [:output :credit-history :accounts]))))))

(deftest credit-bureau-lookup-unknown-test
  (testing "returns empty history for unknown applicant"
    (let [result (dev/test-cell :loan/credit-bureau-lookup
                   {:input     {:applicant-name "Unknown"}
                    :resources {:credit-db {}}})]
      (is (:pass? result))
      (is (= 0 (get-in result [:output :credit-history :accounts]))))))

;; ===== calculate-score =====

(deftest calculate-score-good-history-test
  (testing "good credit history produces high score"
    (let [result (dev/test-cell :loan/calculate-score
                   {:input {:credit-history {:accounts 5 :late-payments 0
                                             :bankruptcies 0 :years-of-history 10}}})]
      (is (:pass? result))
      (is (>= (get-in result [:output :credit-score]) 720)))))

(deftest calculate-score-bad-history-test
  (testing "bad credit history produces low score"
    (let [result (dev/test-cell :loan/calculate-score
                   {:input {:credit-history {:accounts 1 :late-payments 5
                                             :bankruptcies 1 :years-of-history 1}}})]
      (is (:pass? result))
      (is (<= (get-in result [:output :credit-score]) 620)))))

(deftest calculate-score-clamped-test
  (testing "score is clamped to 300-850 range"
    (let [result-low (dev/test-cell :loan/calculate-score
                       {:input {:credit-history {:accounts 0 :late-payments 10
                                                 :bankruptcies 3 :years-of-history 0}}})
          result-high (dev/test-cell :loan/calculate-score
                        {:input {:credit-history {:accounts 10 :late-payments 0
                                                  :bankruptcies 0 :years-of-history 20}}})]
      (is (= 300 (get-in result-low [:output :credit-score])))
      (is (= 850 (get-in result-high [:output :credit-score]))))))

;; ===== classify-risk =====

(deftest classify-risk-test
  (testing "risk classification based on score thresholds via test-transitions"
    (let [results (dev/test-transitions :loan/classify-risk
                    {:low    {:input {:credit-score 750}}
                     :medium {:input {:credit-score 650}}
                     :high   {:input {:credit-score 500}}})]
      (is (get-in results [:low :pass?]))
      (is (= :low (get-in results [:low :output :risk-level])))
      (is (get-in results [:medium :pass?]))
      (is (= :medium (get-in results [:medium :output :risk-level])))
      (is (get-in results [:high :pass?]))
      (is (= :high (get-in results [:high :output :risk-level]))))))

;; ===== eligibility-decision =====

(deftest eligibility-decision-approve-test
  (testing "low risk + small amount → approve"
    (let [result (dev/test-cell :loan/eligibility-decision
                   {:input      {:risk-level :low :loan-amount 30000}
                    :dispatches decision-dispatches})]
      (is (:pass? result))
      (is (= :approve (:matched-dispatch result))))))

(deftest eligibility-decision-reject-test
  (testing "high risk → reject"
    (let [result (dev/test-cell :loan/eligibility-decision
                   {:input      {:risk-level :high :loan-amount 10000}
                    :dispatches decision-dispatches})]
      (is (:pass? result))
      (is (= :reject (:matched-dispatch result))))))

(deftest eligibility-decision-review-medium-test
  (testing "medium risk → review"
    (let [result (dev/test-cell :loan/eligibility-decision
                   {:input      {:risk-level :medium :loan-amount 25000}
                    :dispatches decision-dispatches})]
      (is (:pass? result))
      (is (= :review (:matched-dispatch result))))))

(deftest eligibility-decision-review-large-amount-test
  (testing "low risk + large amount → review"
    (let [result (dev/test-cell :loan/eligibility-decision
                   {:input      {:risk-level :low :loan-amount 100000}
                    :dispatches decision-dispatches})]
      (is (:pass? result))
      (is (= :review (:matched-dispatch result))))))

;; ===== auto-approve =====

(deftest auto-approve-test
  (testing "sets approved status with interest rate"
    (let [result (dev/test-cell :loan/auto-approve
                   {:input {:credit-score 780}})]
      (is (:pass? result))
      (is (= :approved (get-in result [:output :application-status])))
      (is (= 3.5 (get-in result [:output :interest-rate]))))))

;; ===== auto-reject =====

(deftest auto-reject-test
  (testing "sets rejected status with reason"
    (let [result (dev/test-cell :loan/auto-reject
                   {:input {:risk-level :high}})]
      (is (:pass? result))
      (is (= :rejected (get-in result [:output :application-status])))
      (is (string? (get-in result [:output :decision-reason]))))))

;; ===== queue-for-review =====

(deftest queue-for-review-standard-test
  (testing "medium risk → standard queue"
    (let [result (dev/test-cell :loan/queue-for-review
                   {:input {:risk-level :medium :loan-amount 25000}})]
      (is (:pass? result))
      (is (= :pending-review (get-in result [:output :application-status])))
      (is (= :standard (get-in result [:output :review-queue]))))))

(deftest queue-for-review-senior-test
  (testing "large amount → senior queue"
    (let [result (dev/test-cell :loan/queue-for-review
                   {:input {:risk-level :low :loan-amount 100000}})]
      (is (:pass? result))
      (is (= :senior (get-in result [:output :review-queue]))))))

;; ===== send-notification =====

(deftest send-notification-approved-test
  (testing "generates approval notification"
    (let [result (dev/test-cell :loan/send-notification
                   {:input {:applicant-name "Alice"
                            :application-status :approved
                            :interest-rate 3.5}})]
      (is (:pass? result))
      (is (= "Alice" (get-in result [:output :notification :to])))
      (is (clojure.string/includes?
           (get-in result [:output :notification :body]) "approved")))))

(deftest send-notification-rejected-test
  (testing "generates rejection notification"
    (let [result (dev/test-cell :loan/send-notification
                   {:input {:applicant-name "Carol"
                            :application-status :rejected
                            :decision-reason "High risk"}})]
      (is (:pass? result))
      (is (clojure.string/includes?
           (get-in result [:output :notification :body]) "declined")))))

;; ===== audit-log =====

(deftest audit-log-appends-test
  (testing "appends to existing audit trail"
    (let [result (dev/test-cell :loan/audit-log
                   {:input {:applicant-name "Alice"
                            :application-status :approved
                            :audit-trail [{:action :previous}]}})]
      (is (:pass? result))
      (is (= 2 (count (get-in result [:output :audit-trail])))))))

(deftest audit-log-creates-trail-test
  (testing "creates audit trail when none exists"
    (let [result (dev/test-cell :loan/audit-log
                   {:input {:applicant-name "Bob"
                            :application-status :pending-review}})]
      (is (:pass? result))
      (is (= 1 (count (get-in result [:output :audit-trail])))))))

;; ===== fetch-application =====

(deftest fetch-application-found-test
  (testing "finds an existing application"
    (let [store  (atom {"Alice" {:application-status :approved :loan-amount 30000}})
          result (dev/test-cell :loan/fetch-application
                   {:input      {:applicant-name "Alice"}
                    :resources  {:app-store store}
                    :dispatches fetch-dispatches})]
      (is (:pass? result))
      (is (= :found (:matched-dispatch result)))
      (is (= :approved (get-in result [:output :application-status]))))))

(deftest fetch-application-not-found-test
  (testing "handles missing application"
    (let [store  (atom {})
          result (dev/test-cell :loan/fetch-application
                   {:input      {:applicant-name "Unknown"}
                    :resources  {:app-store store}
                    :dispatches fetch-dispatches})]
      (is (:pass? result))
      (is (= :not-found (:matched-dispatch result)))
      (is (string? (get-in result [:output :error-message]))))))

;; ===== format-status =====

(deftest format-status-test
  (testing "formats a status report"
    (let [result (dev/test-cell :loan/format-status
                   {:input {:applicant-name "Alice"
                            :application-status :approved
                            :loan-amount 30000
                            :credit-score 780
                            :interest-rate 3.5}})]
      (is (:pass? result))
      (is (string? (get-in result [:output :status-report])))
      (is (clojure.string/includes?
           (get-in result [:output :status-report]) "Alice")))))
