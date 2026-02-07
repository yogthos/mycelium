(ns mycelium.cell
  "Cell registry and defcell macro for Mycelium."
  (:require [malli.core :as m]))

(defonce ^:private registry (atom {}))

(defn clear-registry!
  "Removes all cells from the registry."
  []
  (reset! registry {}))

(defn- validate-schema! [schema label]
  (try
    (m/schema schema)
    (catch Exception e
      (throw (ex-info (str "Invalid Malli schema for " label ": " (ex-message e))
                      {:label label :schema schema}
                      e)))))

(defn register-cell!
  "Validates and registers a cell spec in the global registry.
   Cell spec shape:
     {:id :ns/name, :handler fn, :schema {:input malli :output malli},
      :transitions #{:kw ...}, :requires [:resource ...], :async? bool, :doc \"\"}
   Schema is optional at registration time — the manifest is the single source of truth
   for schemas and will attach them via `set-cell-schema!`.
   Options:
     :replace? - if true, allows overwriting an existing cell (default false)"
  ([spec] (register-cell! spec {}))
  ([{:keys [id handler schema transitions] :as spec} {:keys [replace?]}]
   (when-not id
     (throw (ex-info "Cell spec missing :id" {:spec spec})))
   (when-not handler
     (throw (ex-info "Cell spec missing :handler" {:id id})))
   (when (or (nil? transitions) (empty? transitions))
     (throw (ex-info (str "Cell " id " must declare non-empty transitions")
                     {:id id})))
   (when schema
     (when (:input schema)
       (validate-schema! (:input schema) (str id " :input")))
     (when (:output schema)
       (validate-schema! (:output schema) (str id " :output"))))
   (swap! registry
          (fn [reg]
            (when (and (not replace?) (get reg id))
              (throw (ex-info (str "Cell " id " already registered")
                              {:id id})))
            (assoc reg id spec)))
   spec))

(defn get-cell
  "Returns the cell spec for the given id, or nil if not found."
  [id]
  (get @registry id))

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
  (when-not (get @registry cell-id)
    (throw (ex-info (str "Cell " cell-id " not found in registry")
                    {:id cell-id})))
  (when (:input schema)
    (validate-schema! (:input schema) (str cell-id " :input")))
  (when (:output schema)
    (validate-schema! (:output schema) (str cell-id " :output")))
  (swap! registry assoc-in [cell-id :schema] schema)
  schema)

(defn list-cells
  "Returns a seq of all registered cell IDs."
  []
  (keys @registry))

(defmacro defcell
  "Registers a cell and defines its handler.
   Usage:
     (defcell :ns/name
       {:doc \"...\" :transitions #{...}}
       [resources data]
       body...)

   Schema is provided by the manifest via `set-cell-schema!`, not in defcell.
   For async cells, add :async? true to opts and use [resources data callback error-callback]."
  [id opts bindings & body]
  `(let [handler# (fn ~bindings ~@body)]
     (register-cell!
      (merge {:id      ~id
              :handler handler#}
             ~opts)
      {:replace? true})))
