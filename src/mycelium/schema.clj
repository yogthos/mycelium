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
      (map? output)    (get output transition))))

(defn validate-output
  "Validates data against the cell's output schema and transition.
   Returns nil if valid (or if no output schema is defined), or an error map if invalid.
   Supports both single-schema (vector) and per-transition (map) output schemas.
   Transition validation is always performed regardless of schema presence."
  [cell data]
  (let [transition  (:mycelium/transition data)
        transitions (:transitions cell)]
    (cond
      (nil? transition)
      {:cell-id (:id cell)
       :phase   :output
       :errors  "Missing :mycelium/transition in output data"
       :data    data}

      (not (contains? transitions transition))
      {:cell-id (:id cell)
       :phase   :output
       :errors  (str "Invalid transition " transition
                     ", expected one of " transitions)
       :data    data}

      :else
      (when-let [schema (output-schema-for-transition cell transition)]
        (when-let [explanation (m/explain schema data)]
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
   Skips terminal states."
  [state->cell]
  (fn [fsm-state _resources]
    (let [state-id (:last-state-id fsm-state)]
      (if (or (nil? state-id)
              (contains? terminal-states state-id))
        fsm-state
        (if-let [cell (get state->cell state-id)]
          (if-let [error (validate-output cell (:data fsm-state))]
            (-> fsm-state
                (assoc :current-state-id ::fsm/error)
                (assoc-in [:data :mycelium/schema-error] error))
            fsm-state)
          fsm-state)))))

(defn wrap-async-callback
  "Wraps an async cell's callback to validate output before forwarding.
   If validation fails, calls error-callback with an ex-info."
  [cell original-callback error-callback]
  (fn [data]
    (if-let [error (validate-output cell data)]
      (error-callback (ex-info (str "Output validation failed for " (:id cell))
                               error))
      (original-callback data))))
