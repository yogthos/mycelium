(ns mycelium.compose
  "Hierarchical composition: wrapping workflows as cells for nesting."
  (:require [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(defn workflow->cell
  "Wraps a workflow definition as a cell spec.
   The resulting cell runs the child workflow to completion and returns
   :success or :failure transition.

   `cell-id`  - the ID for the resulting cell
   `workflow`  - workflow definition map {:cells ... :edges ...}
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
                      (if (:mycelium/error result)
                        (assoc result :mycelium/transition :failure)
                        (assoc result :mycelium/transition :success)))
                    (catch Exception e
                      (-> data
                          (assoc :mycelium/error (ex-message e)
                                 :mycelium/transition :failure)))))]
    {:id          cell-id
     :handler     handler
     ;; Use the provided input schema but make output open (:map)
     ;; because on :failure the output won't match the happy-path schema.
     ;; The child workflow's own interceptors enforce output schemas internally.
     :schema      {:input (:input schema) :output :map}
     :transitions #{:success :failure}}))

(defn register-workflow-cell!
  "Creates a workflow-as-cell and registers it in the cell registry.

   `cell-id`  - the ID for the resulting cell
   `workflow`  - workflow definition map {:cells ... :edges ...}
   `schema`    - {:input [...] :output [...]} for the cell"
  [cell-id workflow schema]
  (let [spec (workflow->cell cell-id workflow schema)]
    (.addMethod cell/cell-spec cell-id (constantly spec))
    spec))
