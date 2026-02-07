(ns mycelium.manifest
  "Manifest loading, validation, cell-brief generation, and workflow construction."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [mycelium.cell :as cell]))

;; ===== Schema validation helpers =====

(defn- validate-malli-schema!
  "Validates that a Malli schema definition is well-formed."
  [schema label]
  (try
    (m/schema schema)
    (catch Exception e
      (throw (ex-info (str "Invalid Malli schema in manifest for " label ": " (ex-message e))
                      {:label label :schema schema}
                      e)))))

(defn- validate-cell-def!
  "Validates a single cell definition within a manifest."
  [cell-name cell-def]
  (when-not (:id cell-def)
    (throw (ex-info (str "Cell " cell-name " missing :id") {:cell-name cell-name})))
  (when-not (:schema cell-def)
    (throw (ex-info (str "Cell " cell-name " missing :schema") {:cell-name cell-name})))
  (when-not (get-in cell-def [:schema :input])
    (throw (ex-info (str "Cell " cell-name " missing :schema :input") {:cell-name cell-name})))
  (when-not (get-in cell-def [:schema :output])
    (throw (ex-info (str "Cell " cell-name " missing :schema :output") {:cell-name cell-name})))
  (when (or (nil? (:transitions cell-def)) (empty? (:transitions cell-def)))
    (throw (ex-info (str "Cell " cell-name " missing or empty :transitions") {:cell-name cell-name})))
  (validate-malli-schema! (get-in cell-def [:schema :input]) (str cell-name " :input"))
  (validate-malli-schema! (get-in cell-def [:schema :output]) (str cell-name " :output")))

;; ===== Manifest validation =====

(defn validate-manifest
  "Validates a manifest structure. Returns the manifest if valid, throws otherwise."
  [{:keys [id cells edges] :as manifest}]
  (when-not id
    (throw (ex-info "Manifest missing :id" {:manifest manifest})))
  (when-not cells
    (throw (ex-info "Manifest missing :cells" {:id id})))
  (when-not edges
    (throw (ex-info "Manifest missing :edges" {:id id})))
  ;; Validate each cell definition
  (doseq [[cell-name cell-def] cells]
    (validate-cell-def! cell-name cell-def))
  ;; Validate edge targets reference valid cell names or :end/:error/:halt
  (let [valid-names (into #{:end :error :halt} (keys cells))]
    (doseq [[from edge-def] edges
            target (if (keyword? edge-def) [edge-def] (vals edge-def))]
      (when-not (contains? valid-names target)
        (throw (ex-info (str "Invalid edge target " target " from " from)
                        {:from from :target target :valid valid-names})))))
  manifest)

;; ===== Manifest loading =====

(defn load-manifest
  "Loads and validates a manifest from an EDN file path."
  [path]
  (let [content (slurp path)
        manifest (edn/read-string content)]
    (validate-manifest manifest)))

;; ===== Cell brief generation =====

(defn- generate-example
  "Generates example data from a Malli schema."
  [schema]
  (try
    (mg/generate (m/schema schema) {:size 3 :seed 42})
    (catch Exception _
      {:example "could not generate"})))

(defn cell-brief
  "Extracts a self-contained brief for a single cell from a manifest.
   Returns a map with :id, :doc, :schema, :transitions, :requires, :examples, :prompt."
  [manifest cell-name]
  (let [cell-def    (get-in manifest [:cells cell-name])
        _           (when-not cell-def
                      (throw (ex-info (str "Cell " cell-name " not found in manifest")
                                      {:cell-name cell-name :manifest-id (:id manifest)})))
        {:keys [id doc schema transitions requires]} cell-def
        input-ex    (generate-example (:input schema))
        output-ex   (generate-example (:output schema))
        prompt      (str "## Cell: " id "\n"
                         (when doc (str "\n## Purpose\n" doc "\n"))
                         "\n## Contract\n\n"
                         "Input schema:\n  " (pr-str (:input schema)) "\n\n"
                         "Output schema:\n  " (pr-str (:output schema)) "\n\n"
                         "Required resources: " (if (seq requires)
                                                  (str/join ", " (map pr-str requires))
                                                  "none") "\n\n"
                         "Transition signals: " (str/join ", " (map pr-str transitions)) "\n"
                         "  Return (assoc data :mycelium/transition :<signal>) for each.\n"
                         "\n## Example Data\n\n"
                         "Example input:\n  " (pr-str input-ex) "\n\n"
                         "Example output:\n  " (pr-str (merge output-ex
                                                              {:mycelium/transition (first transitions)})) "\n"
                         "\n## Rules\n"
                         "- Handler signature: (fn [resources data] -> data)\n"
                         "- MUST return data with :mycelium/transition set to one of: "
                         (str/join ", " (map pr-str transitions)) "\n"
                         "- MUST NOT require or call any other cell's namespace\n"
                         "- Output data MUST pass the output schema validation\n")]
    {:id          id
     :doc         doc
     :schema      schema
     :transitions transitions
     :requires    (or requires [])
     :examples    {:input input-ex :output output-ex}
     :prompt      prompt}))

;; ===== Manifest → Workflow =====

(defn manifest->workflow
  "Converts a manifest into a workflow definition map.
   Registers stub handlers for each cell in the registry.
   Returns {:cells ... :edges ...} suitable for `workflow/compile-workflow`."
  [{:keys [cells edges] :as manifest}]
  (let [cell-ids (into {}
                       (map (fn [[cell-name cell-def]]
                              (let [{:keys [id schema transitions requires]} cell-def
                                    ;; Register a stub handler if not already registered
                                    _ (when-not (cell/get-cell id)
                                        (cell/register-cell!
                                         {:id          id
                                          :handler     (fn [_ data]
                                                         (assoc data :mycelium/transition
                                                                (first transitions)))
                                          :schema      schema
                                          :transitions transitions
                                          :requires    (or requires [])
                                          :doc         (:doc cell-def)}))]
                                [cell-name id])))
                       cells)]
    {:cells cell-ids
     :edges edges}))
