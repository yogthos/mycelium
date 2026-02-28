(ns loan.workflows-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            loan.cells
            loan.workflows))

;; Re-require if cells got cleared by another test namespace
(use-fixtures :each (fn [f]
                      (when-not (cell/get-cell :loan/validate-application)
                        (require 'loan.cells :reload)
                        (require 'loan.workflows :reload))
                      (f)))

;; ===== Test Data =====

(def credit-db
  {"Alice" {:accounts 5 :late-payments 0 :bankruptcies 0 :years-of-history 10}
   "Bob"   {:accounts 3 :late-payments 1 :bankruptcies 0 :years-of-history 5}
   "Carol" {:accounts 1 :late-payments 5 :bankruptcies 1 :years-of-history 2}})

(defn make-resources []
  {:credit-db credit-db
   :app-store (atom {})})

;; ===== Workflow 2: Loan Application =====

(deftest auto-approve-path-test
  (testing "Alice, good credit, $30k → auto-approve"
    (let [resources (make-resources)
          result    (loan.workflows/run-loan-application
                     resources
                     {:applicant-name "Alice" :income 80000 :loan-amount 30000})]
      (is (= :approved (:application-status result)))
      (is (number? (:interest-rate result)))
      (is (some? (:notification result)))
      (is (seq (:audit-trail result)))
      ;; Verify stored in app-store
      (is (= :approved (get-in @(:app-store resources) ["Alice" :application-status]))))))

(deftest auto-reject-path-test
  (testing "Carol, bad credit → auto-reject"
    (let [resources (make-resources)
          result    (loan.workflows/run-loan-application
                     resources
                     {:applicant-name "Carol" :income 40000 :loan-amount 15000})]
      (is (= :rejected (:application-status result)))
      (is (string? (:decision-reason result)))
      (is (some? (:notification result)))
      (is (seq (:audit-trail result))))))

(deftest manual-review-medium-risk-test
  (testing "Bob, medium risk, $25k → manual review"
    (let [resources (make-resources)
          result    (loan.workflows/run-loan-application
                     resources
                     {:applicant-name "Bob" :income 50000 :loan-amount 25000})]
      (is (= :pending-review (:application-status result)))
      (is (= :standard (:review-queue result)))
      (is (some? (:notification result)))
      (is (seq (:audit-trail result))))))

(deftest manual-review-large-amount-test
  (testing "Alice, low risk, $100k → manual review (large amount)"
    (let [resources (make-resources)
          result    (loan.workflows/run-loan-application
                     resources
                     {:applicant-name "Alice" :income 200000 :loan-amount 100000})]
      (is (= :pending-review (:application-status result)))
      (is (= :senior (:review-queue result)))
      (is (some? (:notification result))))))

(deftest invalid-application-test
  (testing "Missing required fields → ends early with validation errors"
    (let [resources (make-resources)
          result    (loan.workflows/run-loan-application
                     resources
                     {:applicant-name "" :income nil :loan-amount nil})]
      (is (= :invalid (:validation-status result)))
      (is (seq (:validation-errors result)))
      ;; Should NOT have credit assessment or notification
      (is (nil? (:credit-score result)))
      (is (nil? (:notification result))))))

;; ===== Workflow 1: Credit Assessment Standalone =====

(deftest credit-assessment-standalone-test
  (testing "credit assessment subworkflow runs standalone"
    (let [result (myc/run-workflow
                  loan.workflows/credit-assessment-workflow
                  {:credit-db credit-db}
                  {:applicant-name "Alice"})]
      (is (number? (:credit-score result)))
      (is (>= (:credit-score result) 720))
      (is (= :low (:risk-level result))))))

(deftest credit-assessment-unknown-applicant-test
  (testing "unknown applicant gets default empty history"
    (let [result (myc/run-workflow
                  loan.workflows/credit-assessment-workflow
                  {:credit-db credit-db}
                  {:applicant-name "Unknown"})]
      (is (number? (:credit-score result)))
      ;; Empty history → base score 500 → high risk
      (is (= :high (:risk-level result))))))

;; ===== Workflow 3: Status Check =====

(deftest status-check-found-test
  (testing "status check finds stored application"
    (let [store     (atom {"Alice" {:application-status :approved
                                    :loan-amount 30000
                                    :credit-score 780
                                    :interest-rate 3.5
                                    :applicant-name "Alice"}})
          resources {:app-store store}
          result    (loan.workflows/run-status-check
                     resources
                     {:applicant-name "Alice"})]
      (is (= :found (:fetch-status result)))
      (is (string? (:status-report result)))
      (is (clojure.string/includes? (:status-report result) "Alice"))
      (is (seq (:audit-trail result))))))

(deftest status-check-not-found-test
  (testing "status check handles missing application"
    (let [store     (atom {})
          resources {:app-store store}
          result    (loan.workflows/run-status-check
                     resources
                     {:applicant-name "Nobody"})]
      (is (= :not-found (:fetch-status result)))
      (is (string? (:error-message result)))
      ;; Should NOT have status report or audit trail (ends early)
      (is (nil? (:status-report result))))))

;; ===== Trace Verification =====

(deftest trace-verification-test
  (testing "workflow produces trace with expected cells"
    (let [resources (make-resources)
          result    (loan.workflows/run-loan-application
                     resources
                     {:applicant-name "Alice" :income 80000 :loan-amount 30000})
          trace     (:mycelium/trace result)]
      (is (vector? trace))
      (is (pos? (count trace)))
      ;; Verify key cells appear in trace
      (let [cell-ids (set (map :cell-id trace))]
        (is (contains? cell-ids :loan/validate-application))
        (is (contains? cell-ids :loan/credit-assessment))
        (is (contains? cell-ids :loan/eligibility-decision))
        (is (contains? cell-ids :loan/auto-approve))
        (is (contains? cell-ids :loan/audit-log))
        (is (contains? cell-ids :loan/send-notification))))))

(deftest trace-reject-path-test
  (testing "reject path trace contains expected cells"
    (let [resources (make-resources)
          result    (loan.workflows/run-loan-application
                     resources
                     {:applicant-name "Carol" :income 40000 :loan-amount 15000})
          trace     (:mycelium/trace result)
          cell-ids  (set (map :cell-id trace))]
      (is (contains? cell-ids :loan/auto-reject))
      (is (not (contains? cell-ids :loan/auto-approve)))
      (is (not (contains? cell-ids :loan/queue-for-review))))))

;; ===== End-to-End: Apply then Check Status =====

(deftest apply-then-check-status-test
  (testing "full flow: apply for loan, then check status"
    (let [resources (make-resources)
          ;; Step 1: Apply
          app-result (loan.workflows/run-loan-application
                      resources
                      {:applicant-name "Alice" :income 80000 :loan-amount 30000})
          ;; Step 2: Check status
          status-result (loan.workflows/run-status-check
                         resources
                         {:applicant-name "Alice"})]
      (is (= :approved (:application-status app-result)))
      (is (= :found (:fetch-status status-result)))
      (is (clojure.string/includes? (:status-report status-result) "approved")))))
