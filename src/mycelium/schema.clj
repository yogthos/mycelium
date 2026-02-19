(ns mycelium.schema
  "Schema validation interceptors for Mycelium.
   Provides pre/post interceptors that enforce Malli schemas on cell input/output,
   and async callback wrappers for async cells."
  (:require [malli.core :as m]
            [malli.error :as me]
            [maestro.core :as fsm]))

(def ^:private terminal-states
  #{::fsm/end ::fsm/error ::fsm/halt})

(defn validate-input
  "Validates data against the cell's input schema.
   Returns nil if valid (or if no input schema is defined), or an error map if invalid.
   Uses open-map semantics (extra keys pass through)."
  [cell data]
  (when-let [schema (get-in cell [:schema :input])]
    (when-let [explanation (m/explain schema data)]
      {:cell-id (:id cell)
       :phase   :input
       :errors  (me/humanize explanation)
       :data    data})))

(defn output-schema-for-transition
  "Returns the output schema for a specific transition of a cell.
   If output is nil → nil. If vector → same schema for all transitions.
   If map → schema for that transition key (or nil if not found)."
  [cell transition]
  (let [output (get-in cell [:schema :output])]
    (cond
      (nil? output)    nil
      (vector? output) output
      (map? output)    (get output transition)
      :else            output)))

(defn validate-output
  "Validates data against the cell's output schema.
   When transition is provided and output is a per-transition map, validates
   against that specific transition's schema.
   When transition is nil and output is a map, validates against all schemas
   (passes if any match).
   Returns nil if valid, or an error map if invalid."
  ([cell data]
   (validate-output cell data nil))
  ([cell data transition]
   (let [output (get-in cell [:schema :output])]
     (cond
       ;; No output schema — always passes
       (nil? output) nil

       ;; Per-transition output schema (map)
       (map? output)
       (if transition
         ;; Validate against the specific transition's schema
         (when-let [schema (get output transition)]
           (when-let [explanation (m/explain schema data)]
             {:cell-id (:id cell)
              :phase   :output
              :errors  (me/humanize explanation)
              :data    data}))
         ;; No transition known — validate against all schemas, pass if any matches
         (let [schemas (vals output)]
           (when (every? (fn [schema]
                           (some? (m/explain schema data)))
                         schemas)
             {:cell-id (:id cell)
              :phase   :output
              :errors  (str "Data does not match any output schema for " (:id cell))
              :data    data})))

       ;; Single schema (vector or keyword)
       :else
       (when-let [explanation (m/explain output data)]
         {:cell-id (:id cell)
          :phase   :output
          :errors  (me/humanize explanation)
          :data    data})))))

(defn make-pre-interceptor
  "Creates a Maestro pre-interceptor that validates input schemas.
   `state->cell` is a map of state-id → cell-spec.
   Skips terminal states."
  [state->cell]
  (fn [fsm-state _resources]
    (let [state-id (:current-state-id fsm-state)]
      (if (contains? terminal-states state-id)
        fsm-state
        (if-let [cell (get state->cell state-id)]
          (if-let [error (validate-input cell (:data fsm-state))]
            (-> fsm-state
                (assoc :current-state-id ::fsm/error)
                (assoc-in [:data :mycelium/schema-error] error))
            fsm-state)
          fsm-state)))))

(defn make-post-interceptor
  "Creates a Maestro post-interceptor that validates output schemas.
   Uses :last-state-id to look up the cell that just ran.
   `state->edge-targets` maps state-id → {target-state-id → transition-label},
   used to infer which transition was taken from :current-state-id (set by Maestro
   after dispatch evaluation).
   Skips terminal states."
  [state->cell state->edge-targets]
  (fn [fsm-state _resources]
    (let [state-id (:last-state-id fsm-state)]
      (if (or (nil? state-id)
              (contains? terminal-states state-id))
        fsm-state
        (if-let [cell (get state->cell state-id)]
          (let [transition (when state->edge-targets
                             (get-in state->edge-targets
                                     [state-id (:current-state-id fsm-state)]))
                error (validate-output cell (:data fsm-state) transition)]
            (if error
              (-> fsm-state
                  (assoc :current-state-id ::fsm/error)
                  (assoc-in [:data :mycelium/schema-error] error))
              fsm-state))
          fsm-state)))))

(defn wrap-async-callback
  "Wraps an async cell's callback to validate output before forwarding.
   If validation fails, calls error-callback with an ex-info.
   For per-transition output schemas, falls back to 'any schema matches' validation
   since the transition target is not known until dispatch."
  [cell original-callback error-callback]
  (fn [data]
    (if-let [error (validate-output cell data)]
      (error-callback (ex-info (str "Output validation failed for " (:id cell))
                               error))
      (original-callback data))))
