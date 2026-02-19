(ns mycelium.cell
  "Cell registry for Mycelium. Cells are registered via `defmethod cell-spec`."
  (:require [clojure.set]
            [malli.core :as m]))

(defmulti cell-spec
  "Multimethod-backed cell registry. Dispatches on cell-id keyword,
   returns the full cell spec map or nil for unknown cells."
  identity)

(defmethod cell-spec :default [_] nil)

(defonce ^:private cell-overrides (atom {}))

(defn clear-registry!
  "Removes all cells from the registry."
  []
  (doseq [k (keys (dissoc (methods cell-spec) :default))]
    (remove-method cell-spec k))
  (reset! cell-overrides {}))

(defn- validate-schema! [schema label]
  (try
    (m/schema schema)
    (catch Exception e
      (throw (ex-info (str "Invalid Malli schema for " label ": " (ex-message e))
                      {:label label :schema schema}
                      e)))))

(defn- validate-output-schema!
  "Validates an output schema which may be a single schema (vector) or per-transition map.
   If a map, validates each value as a Malli schema."
  [output-schema label]
  (cond
    (vector? output-schema)
    (validate-schema! output-schema label)

    (map? output-schema)
    (doseq [[k v] output-schema]
      (validate-schema! v (str label " transition " k)))

    :else
    (validate-schema! output-schema label)))

(defn get-cell
  "Returns the cell spec for the given id, or nil if not found."
  [id]
  (let [spec (cell-spec id)]
    (when spec
      (if-let [overrides (get @cell-overrides id)]
        (merge spec overrides)
        spec))))

(defn get-cell!
  "Returns the cell spec for the given id, or throws if not found."
  [id]
  (or (get-cell id)
      (throw (ex-info (str "Cell " id " not found in registry")
                      {:id id}))))

(defn set-cell-schema!
  "Sets or overwrites the schema for an already-registered cell.
   Validates that the schema is well-formed Malli before updating.
   Throws if the cell is not found or the schema is invalid."
  [cell-id schema]
  (when-not (cell-spec cell-id)
    (throw (ex-info (str "Cell " cell-id " not found in registry")
                    {:id cell-id})))
  (when (:input schema)
    (validate-schema! (:input schema) (str cell-id " :input")))
  (when (:output schema)
    (validate-output-schema! (:output schema) (str cell-id " :output")))
  (swap! cell-overrides update cell-id merge {:schema schema})
  schema)

(defn set-cell-meta!
  "Sets metadata overrides (schema, requires) on a registered cell.
   The manifest calls this to inject metadata into cells that were registered
   without schemas/requires. Validates schema is well-formed.
   Throws if the cell is not found."
  [cell-id {:keys [schema requires] :as meta-map}]
  (when-not (cell-spec cell-id)
    (throw (ex-info (str "Cell " cell-id " not found in registry")
                    {:id cell-id})))
  (when schema
    (when (:input schema)
      (validate-schema! (:input schema) (str cell-id " :input")))
    (when (:output schema)
      (validate-output-schema! (:output schema) (str cell-id " :output"))))
  (let [overrides (cond-> {}
                    schema   (assoc :schema schema)
                    requires (assoc :requires requires))]
    (swap! cell-overrides update cell-id merge overrides))
  meta-map)

(defn list-cells
  "Returns a seq of all registered cell IDs."
  []
  (keys (dissoc (methods cell-spec) :default)))
