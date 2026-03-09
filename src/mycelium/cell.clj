(ns mycelium.cell
  "Cell registry for Mycelium. Cells are registered via `defmethod cell-spec`."
  (:require [mycelium.validation :as v]))

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
    (v/validate-malli-schema! (:input schema) (str cell-id " :input")))
  (when (:output schema)
    (v/validate-output-schema! (:output schema) (str cell-id " :output")))
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
      (v/validate-malli-schema! (:input schema) (str cell-id " :input")))
    (when (:output schema)
      (v/validate-output-schema! (:output schema) (str cell-id " :output"))))
  (let [overrides (cond-> {}
                    schema   (assoc :schema schema)
                    requires (assoc :requires requires))]
    (swap! cell-overrides update cell-id merge overrides))
  meta-map)

(defn list-cells
  "Returns a seq of all registered cell IDs."
  []
  (keys (dissoc (methods cell-spec) :default)))

(defn defcell
  "Registers a cell with less boilerplate than the raw defmethod.
   Eliminates ID duplication — the cell-id is specified once.

   Arities:
     (defcell :ns/id handler-fn)
     (defcell :ns/id schema-map handler-fn)

   schema-map is {:input [...] :output [...]} with optional :doc, :requires, :async?.
   The :input/:output keys become the cell's :schema. Extra keys (:doc, :requires,
   :async?) are lifted to the top-level spec.

   Examples:
     ;; Minimal — no schema
     (defcell :order/compute-tax
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))

     ;; With schema
     (defcell :order/compute-tax
       {:input  [:map [:subtotal :double]]
        :output [:map [:tax :double]]}
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))

     ;; With schema + opts
     (defcell :order/compute-tax
       {:input    [:map [:subtotal :double]]
        :output   [:map [:tax :double]]
        :doc      \"Computes tax\"
        :requires [:tax-rates]}
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))"
  ([cell-id handler-fn]
   (defcell cell-id nil handler-fn))
  ([cell-id opts handler-fn]
   (let [schema-keys #{:input :output}
         opt-keys    #{:doc :requires :async?}
         schema      (when opts
                       (let [s (select-keys opts schema-keys)]
                         (when (seq s) s)))
         extra       (when opts (select-keys opts opt-keys))
         spec        (cond-> {:id cell-id :handler handler-fn}
                       schema (assoc :schema schema)
                       (:doc extra) (assoc :doc (:doc extra))
                       (:requires extra) (assoc :requires (:requires extra))
                       (:async? extra) (assoc :async? (:async? extra)))]
     (.addMethod cell-spec cell-id (constantly spec))
     spec)))
