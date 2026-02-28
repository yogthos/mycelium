(ns mycelium.compose
  "Hierarchical composition: wrapping workflows as cells for nesting."
  (:require [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]
            [promesa.core :as p]))

(defn- get-map-entries
  "Extracts top-level entries from a Malli :map schema.
   Returns a vector of [key type] pairs, or nil if not a :map schema."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (vec (keep (fn [entry]
                 (when (vector? entry)
                   [(first entry) (second entry)]))
               (rest schema)))))

(defn- collect-end-reaching-output-entries
  "Walks workflow edges to find cells routing to :end and collects their output entries.
   Returns a vector of [key type] pairs representing the union of all outputs."
  [edges cells]
  (let [entries (atom {})]
    (doseq [[cell-name edge-def] edges]
      (let [cell-id (get cells cell-name)]
        (when cell-id
          (let [cell (cell/get-cell cell-id)
                output (when cell (get-in cell [:schema :output]))]
            (when output
              (cond
                ;; Unconditional edge to :end
                (= :end edge-def)
                (when-let [es (cond
                                (vector? output) (get-map-entries output)
                                (map? output) (mapcat (fn [[_ s]] (or (get-map-entries s) [])) output))]
                  (doseq [[k t] es]
                    (swap! entries (fn [m] (if (contains? m k) m (assoc m k t))))))

                ;; Map edges — check which transitions route to :end
                (map? edge-def)
                (let [end-transitions (set (keep (fn [[transition target]]
                                                   (when (= :end target) transition))
                                                 edge-def))]
                  (when (seq end-transitions)
                    (let [es (cond
                               ;; vector output: all transitions share the same schema
                               (vector? output)
                               (get-map-entries output)

                               ;; map output: only take entries from transitions routing to :end
                               (map? output)
                               (mapcat (fn [t]
                                         (when-let [s (get output t)]
                                           (or (get-map-entries s) [])))
                                       end-transitions))]
                      (doseq [[k t] es]
                        (swap! entries (fn [m] (if (contains? m k) m (assoc m k t))))))))))))))
    (vec @entries)))

(defn- infer-workflow-output-schema
  "Infers a per-transition output schema for a composed cell.
   :success path gets the union of output keys from cells routing to :end.
   :failure path gets [:map [:mycelium/error :any]].
   Only infers when the caller's :output schema is bare :map keyword.
   If the caller provides a proper [:map ...] vector, uses that directly."
  [workflow schema]
  (let [output (:output schema)]
    (if (and (not (keyword? output)) (vector? output))
      ;; Caller provided a real schema — use it as :success, add :failure
      {:success output
       :failure [:map [:mycelium/error :any]]}
      ;; Infer from child workflow
      (let [entries (collect-end-reaching-output-entries (:edges workflow) (:cells workflow))]
        (if (seq entries)
          {:success (into [:map] entries)
           :failure [:map [:mycelium/error :any]]}
          ;; Fall back to bare :map
          :map)))))

(def workflow-cell-dispatches
  "Default dispatch predicates for composed workflow cells.
   Ordered vector — :success checked first, :failure as fallback."
  [[:success (fn [data] (not (:mycelium/error data)))]
   [:failure (fn [data] (some? (:mycelium/error data)))]])

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
                      (if (p/promise? result) @result result))
                    (catch Exception e
                      (-> data
                          (assoc :mycelium/error (ex-message e))))))]
    {:id                 cell-id
     :handler            handler
     ;; Infer per-transition output schema from child workflow's end-reaching cells.
     ;; If the caller provided a proper [:map ...] vector, that takes precedence.
     :schema             {:input (:input schema)
                          :output (infer-workflow-output-schema workflow schema)}
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
