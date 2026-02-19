(ns mycelium.compose
  "Hierarchical composition: wrapping workflows as cells for nesting."
  (:require [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(def workflow-cell-dispatches
  "Default dispatch predicates for composed workflow cells.
   Routes :success when no error, :failure when :mycelium/error is present."
  {:success (fn [data] (not (:mycelium/error data)))
   :failure (fn [data] (some? (:mycelium/error data)))})

(defn workflow->cell
  "Wraps a workflow definition as a cell spec.
   The resulting cell runs the child workflow to completion and returns
   data with :mycelium/error on failure for dispatch routing.

   `cell-id`  - the ID for the resulting cell
   `workflow`  - workflow definition map {:cells ... :edges ... :dispatches ...}
   `schema`    - {:input [...] :output [...]} for the cell"
  [cell-id workflow schema]
  (let [compiled (wf/compile-workflow workflow
                                      {:on-error (fn [_ fsm-state]
                                                   (-> (:data fsm-state)
                                                       (assoc :mycelium/error
                                                              (or (:error fsm-state)
                                                                  (get-in fsm-state [:data :mycelium/schema-error])))
                                                       (assoc :mycelium/child-trace
                                                              (:trace fsm-state))))
                                       :on-end (fn [_ fsm-state]
                                                 (-> (:data fsm-state)
                                                     (assoc :mycelium/child-trace
                                                            (:trace fsm-state))))})
        handler (fn [resources data]
                  (try
                    (let [result (fsm/run compiled resources {:data data})]
                      result)
                    (catch Exception e
                      (-> data
                          (assoc :mycelium/error (ex-message e))))))]
    {:id                 cell-id
     :handler            handler
     ;; Use the provided input schema but make output open (:map)
     ;; because on :failure the output won't match the happy-path schema.
     ;; The child workflow's own interceptors enforce output schemas internally.
     :schema             {:input (:input schema) :output :map}
     :default-dispatches workflow-cell-dispatches}))

(defn register-workflow-cell!
  "Creates a workflow-as-cell and registers it in the cell registry.

   `cell-id`  - the ID for the resulting cell
   `workflow`  - workflow definition map {:cells ... :edges ... :dispatches ...}
   `schema`    - {:input [...] :output [...]} for the cell"
  [cell-id workflow schema]
  (let [spec (workflow->cell cell-id workflow schema)]
    (.addMethod cell/cell-spec cell-id (constantly spec))
    spec))
