(ns mycelium.cell
  "Cell registry and defcell macro for Mycelium."
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
   If a map, validates each value as a Malli schema and optionally checks keys match transitions."
  [output-schema transitions label]
  (cond
    (vector? output-schema)
    (validate-schema! output-schema label)

    (map? output-schema)
    (do
      (doseq [[k v] output-schema]
        (validate-schema! v (str label " transition " k)))
      (when transitions
        (let [schema-keys  (set (keys output-schema))
              trans-set    (set transitions)
              extra        (clojure.set/difference schema-keys trans-set)
              missing      (clojure.set/difference trans-set schema-keys)]
          (when (seq extra)
            (throw (ex-info (str "Output schema has keys " extra
                                 " not in transitions " trans-set " for " label)
                            {:label label :extra extra :transitions trans-set})))
          (when (seq missing)
            (throw (ex-info (str "Output schema missing keys " missing
                                 " for transitions " trans-set " in " label)
                            {:label label :missing missing :transitions trans-set}))))))

    :else
    (validate-schema! output-schema label)))

(defn register-cell!
  "Validates and registers a cell spec in the global registry.
   Cell spec shape:
     {:id :ns/name, :handler fn, :schema {:input malli :output malli},
      :transitions #{:kw ...}, :requires [:resource ...], :async? bool, :doc \"\"}
   Schema and transitions are optional at registration time — the manifest is the
   single source of truth and will attach them via `set-cell-meta!`.
   Options:
     :replace? - if true, allows overwriting an existing cell (default false)"
  ([spec] (register-cell! spec {}))
  ([{:keys [id handler schema transitions] :as spec} {:keys [replace?]}]
   (when-not id
     (throw (ex-info "Cell spec missing :id" {:spec spec})))
   (when-not handler
     (throw (ex-info "Cell spec missing :handler" {:id id})))
   (when (and (some? transitions) (empty? transitions))
     (throw (ex-info (str "Cell " id " must declare non-empty transitions")
                     {:id id})))
   (when schema
     (when (:input schema)
       (validate-schema! (:input schema) (str id " :input")))
     (when (:output schema)
       (validate-output-schema! (:output schema) transitions (str id " :output"))))
   (when (and (not replace?) (cell-spec id))
     (throw (ex-info (str "Cell " id " already registered")
                     {:id id})))
   (.addMethod cell-spec id (constantly spec))
   (swap! cell-overrides dissoc id)
   spec))

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
  (let [transitions (:transitions (get-cell cell-id))]
    (when (:input schema)
      (validate-schema! (:input schema) (str cell-id " :input")))
    (when (:output schema)
      (validate-output-schema! (:output schema) transitions (str cell-id " :output"))))
  (swap! cell-overrides update cell-id merge {:schema schema})
  schema)

(defn set-cell-meta!
  "Sets metadata overrides (schema, transitions, requires) on a registered cell.
   The manifest calls this to inject metadata into cells that were registered
   without transitions/requires. Validates transitions are non-empty and schema
   is well-formed. Throws if the cell is not found."
  [cell-id {:keys [schema transitions requires] :as meta-map}]
  (when-not (cell-spec cell-id)
    (throw (ex-info (str "Cell " cell-id " not found in registry")
                    {:id cell-id})))
  (when (and (some? transitions) (empty? transitions))
    (throw (ex-info (str "Cell " cell-id " must declare non-empty transitions")
                    {:id cell-id})))
  (when schema
    (let [effective-transitions (or transitions (:transitions (get-cell cell-id)))]
      (when (:input schema)
        (validate-schema! (:input schema) (str cell-id " :input")))
      (when (:output schema)
        (validate-output-schema! (:output schema) effective-transitions (str cell-id " :output")))))
  (let [overrides (cond-> {}
                    schema      (assoc :schema schema)
                    transitions (assoc :transitions transitions)
                    requires    (assoc :requires requires))]
    (swap! cell-overrides update cell-id merge overrides))
  meta-map)

(defn list-cells
  "Returns a seq of all registered cell IDs."
  []
  (keys (dissoc (methods cell-spec) :default)))

(defmacro defcell
  "Registers a cell and defines its handler.
   Usage:
     (defcell :ns/name
       {:doc \"...\" :transitions #{...}}
       [resources data]
       body...)

   Schema and transitions are provided by the manifest via `set-cell-meta!`, not in defcell.
   For async cells, add :async? true to opts and use [resources data callback error-callback]."
  [id opts bindings & body]
  `(let [handler# (fn ~bindings ~@body)]
     (register-cell!
      (merge {:id      ~id
              :handler handler#}
             ~opts)
      {:replace? true})))
