(ns loan.workflows
  "Workflow definitions for the loan processing example.
   Three workflows demonstrating branching, cell reuse, and subworkflow composition."
  (:require [mycelium.core :as myc]
            [mycelium.compose :as compose]
            ;; Trigger cell registrations
            loan.cells))

;; ===== Workflow 1: Credit Assessment (subworkflow) =====
;; Linear pipeline: credit-bureau-lookup → calculate-score → classify-risk
;; Registered as a cell via register-workflow-cell! for embedding in the main workflow.

(def credit-assessment-workflow
  {:cells      {:start         :loan/credit-bureau-lookup
                :calc-score    :loan/calculate-score
                :classify-risk :loan/classify-risk}
   :edges      {:start         :calc-score
                :calc-score    :classify-risk
                :classify-risk :end}
   :dispatches {}})

(compose/register-workflow-cell!
 :loan/credit-assessment
 credit-assessment-workflow
 {:input  [:map [:applicant-name :string]]
  :output [:map [:credit-score :int] [:risk-level [:enum :low :medium :high]]]})

;; ===== Workflow 2: Loan Application (main branching workflow) =====
;; validate-application → credit-assessment → eligibility-decision
;;   ├── auto-approve  ──┐
;;   ├── auto-reject   ──┼── audit-log → send-notification → end
;;   └── queue-review  ──┘

(def loan-application-workflow
  {:cells
   {:start              :loan/validate-application
    :credit-assessment  :loan/credit-assessment
    :eligibility        :loan/eligibility-decision
    :auto-approve       :loan/auto-approve
    :auto-reject        :loan/auto-reject
    :queue-review       :loan/queue-for-review
    :audit-log          :loan/audit-log
    :send-notification  :loan/send-notification}

   :edges
   {:start             {:valid   :credit-assessment
                        :invalid :end}
    :credit-assessment {:success :eligibility
                        :failure :end}
    :eligibility       {:approve :auto-approve
                        :reject  :auto-reject
                        :review  :queue-review}
    :auto-approve      :audit-log
    :auto-reject       :audit-log
    :queue-review      :audit-log
    :audit-log         :send-notification
    :send-notification :end}

   :dispatches
   {:start      [[:valid   (fn [d] (= :valid (:validation-status d)))]
                 [:invalid (fn [d] (= :invalid (:validation-status d)))]]
    :eligibility [[:approve (fn [d] (= :approve (:decision d)))]
                  [:reject  (fn [d] (= :reject (:decision d)))]
                  [:review  (fn [d] (= :review (:decision d)))]]}})

(def compiled-loan-application
  (myc/pre-compile loan-application-workflow))

(defn run-loan-application
  "Runs the loan application workflow.
   resources: {:credit-db {\"name\" {...}} :app-store (atom {})}
   application: {:applicant-name \"...\" :income N :loan-amount N}"
  [resources application]
  (let [result (myc/run-compiled compiled-loan-application resources application)]
    ;; Store the result for later status checks
    (when-let [store (:app-store resources)]
      (when (:application-status result)
        (swap! store assoc (:applicant-name application)
               (select-keys result [:application-status :loan-amount :credit-score
                                    :interest-rate :risk-level :review-queue
                                    :decision-reason :applicant-name]))))
    result))

;; ===== Workflow 3: Application Status Check =====
;; fetch-application → format-status → audit-log → end
;;   └── (not-found) → end

(def status-check-workflow
  {:cells
   {:start         :loan/fetch-application
    :format-status :loan/format-status
    :audit-log     :loan/audit-log}

   :edges
   {:start         {:found     :format-status
                    :not-found :end}
    :format-status :audit-log
    :audit-log     :end}

   :dispatches
   {:start [[:found     (fn [d] (= :found (:fetch-status d)))]
            [:not-found (fn [d] (= :not-found (:fetch-status d)))]]}})

(def compiled-status-check
  (myc/pre-compile status-check-workflow))

(defn run-status-check
  "Runs the status check workflow.
   resources: {:app-store (atom {...})}
   query: {:applicant-name \"...\"}"
  [resources query]
  (myc/run-compiled compiled-status-check resources query))
