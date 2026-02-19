(ns mycelium.manifest
  "Manifest loading, validation, cell-brief generation, and workflow construction."
  (:require [clojure.edn :as edn]
            [clojure.set :as cset]
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

(defn- validate-output-schema!
  "Validates an output schema which may be a single schema (vector) or per-transition map."
  [output-schema cell-name]
  (cond
    (vector? output-schema)
    (validate-malli-schema! output-schema (str cell-name " :output"))

    (map? output-schema)
    (doseq [[k v] output-schema]
      (validate-malli-schema! v (str cell-name " :output transition " k)))

    :else
    (validate-malli-schema! output-schema (str cell-name " :output"))))

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
  (validate-malli-schema! (get-in cell-def [:schema :input]) (str cell-name " :input"))
  (validate-output-schema! (get-in cell-def [:schema :output]) cell-name))

;; ===== Dispatch validation =====

(defn- validate-dispatch-coverage!
  "For each cell with map edges, checks dispatch labels match edge keys exactly.
   Dispatches are vectors of [label pred] pairs. Unconditional edges (keyword) need no dispatch."
  [edges dispatches]
  (doseq [[cell-name edge-def] edges]
    (when (map? edge-def)
      (let [edge-keys     (set (keys edge-def))
            dispatch-vec  (get dispatches cell-name)
            dispatch-keys (when dispatch-vec (set (map first dispatch-vec)))]
        (when-not dispatch-vec
          (throw (ex-info (str "Cell " cell-name " has map edges but no dispatch defined")
                          {:cell-name cell-name :edge-keys edge-keys})))
        (let [missing (cset/difference edge-keys dispatch-keys)]
          (when (seq missing)
            (throw (ex-info (str "Cell " cell-name " has edge(s) " missing
                                  " with no dispatch predicates")
                            {:cell-name cell-name :missing missing}))))
        (let [extra (cset/difference dispatch-keys edge-keys)]
          (when (seq extra)
            (throw (ex-info (str "Cell " cell-name " has dispatch(es) " extra
                                  " that don't match any edge")
                            {:cell-name cell-name :extra extra}))))))))

;; ===== Manifest validation =====

(defn validate-manifest
  "Validates a manifest structure. Returns the manifest if valid, throws otherwise."
  [{:keys [id cells edges dispatches] :as manifest}]
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
  ;; Validate all cells have edges defined
  (doseq [[cell-name _] cells]
    (when-not (get edges cell-name)
      (throw (ex-info (str "Cell " cell-name " has no edges defined")
                      {:cell-name cell-name}))))
  ;; Validate dispatch coverage for map edges
  (validate-dispatch-coverage! edges (or dispatches {}))
  ;; Validate reachability (BFS from :start)
  (let [cell-names (set (keys cells))
        adjacency  (into {}
                         (map (fn [[from edge-def]]
                                [from (if (keyword? edge-def)
                                        #{edge-def}
                                        (set (vals edge-def)))]))
                         edges)
        reachable  (loop [queue   (conj clojure.lang.PersistentQueue/EMPTY :start)
                          visited #{}]
                     (if (empty? queue)
                       visited
                       (let [node  (peek queue)
                             queue (pop queue)]
                         (if (visited node)
                           (recur queue visited)
                           (recur (into queue (get adjacency node #{}))
                                  (conj visited node))))))
        unreachable (cset/difference cell-names reachable)]
    (when (seq unreachable)
      (throw (ex-info (str "Unreachable cells in manifest: " unreachable)
                      {:unreachable unreachable}))))
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

(defn- format-output-schema-section
  "Formats output schema section for the cell-brief prompt.
   Handles both single-schema (vector) and per-edge (map) formats."
  [output-schema edge-labels]
  (if (map? output-schema)
    (str "Output schemas (per edge):\n"
         (str/join "\n"
                   (map (fn [label]
                          (str "  " (pr-str label) ": " (pr-str (get output-schema label))))
                        (sort edge-labels))))
    (str "Output schema:\n  " (pr-str output-schema))))

(defn- generate-output-examples
  "Generates output examples. For per-edge schema maps, generates one per edge."
  [output-schema edge-labels]
  (if (map? output-schema)
    (into {}
          (map (fn [label]
                 [label (generate-example (get output-schema label))]))
          (sort edge-labels))
    (generate-example output-schema)))

(defn cell-brief
  "Extracts a self-contained brief for a single cell from a manifest.
   Returns a map with :id, :doc, :schema, :requires, :examples, :prompt."
  [manifest cell-name]
  (let [cell-def    (get-in manifest [:cells cell-name])
        _           (when-not cell-def
                      (throw (ex-info (str "Cell " cell-name " not found in manifest")
                                      {:cell-name cell-name :manifest-id (:id manifest)})))
        {:keys [id doc schema requires]} cell-def
        edge-def    (get-in manifest [:edges cell-name])
        edge-labels (when (map? edge-def) (set (keys edge-def)))
        dispatches  (get-in manifest [:dispatches cell-name])
        input-ex    (generate-example (:input schema))
        output-ex   (generate-output-examples (:output schema) edge-labels)
        output-section (format-output-schema-section (:output schema) edge-labels)
        example-section (if (map? (:output schema))
                          (str/join "\n\n"
                                    (map (fn [[label ex]]
                                           (str "Example output (" (pr-str label) "):\n  " (pr-str ex)))
                                         (sort-by first output-ex)))
                          (str "Example output:\n  " (pr-str output-ex)))
        dispatch-section (when dispatches
                           (str "Dispatch predicates (checked in order, first match wins):\n"
                                (str/join "\n"
                                          (map (fn [[label _]]
                                                 (str "  " (pr-str label) " — predicate evaluates your output"))
                                               dispatches))
                                "\n"))
        prompt      (str "## Cell: " id "\n"
                         (when doc (str "\n## Purpose\n" doc "\n"))
                         "\n## Contract\n\n"
                         "Input schema:\n  " (pr-str (:input schema)) "\n\n"
                         output-section "\n\n"
                         "Required resources: " (if (seq requires)
                                                  (str/join ", " (map pr-str requires))
                                                  "none") "\n\n"
                         (when dispatch-section
                           (str dispatch-section "\n"))
                         "\n## Example Data\n\n"
                         "Example input:\n  " (pr-str input-ex) "\n\n"
                         example-section "\n"
                         "\n## Rules\n"
                         "- Handler signature: (fn [resources data] -> data)\n"
                         "- Return enriched data — dispatch predicates in the workflow determine routing\n"
                         "- MUST NOT require or call any other cell's namespace\n"
                         "- Output data MUST pass the output schema validation\n")]
    {:id          id
     :doc         doc
     :schema      schema
     :requires    (or requires [])
     :examples    {:input input-ex :output output-ex}
     :prompt      prompt}))

;; ===== Manifest → Workflow =====

(defn manifest->workflow
  "Converts a manifest into a workflow definition map.
   For each cell in the manifest:
   - If not already registered, registers a stub handler with the full manifest spec.
   - If already registered, applies manifest metadata via `set-cell-meta!`.
   The manifest is the single source of truth for schemas and requires.
   Returns {:cells ... :edges ... :dispatches ...} suitable for `workflow/compile-workflow`."
  [{:keys [cells edges dispatches] :as manifest}]
  (let [cell-ids (into {}
                       (map (fn [[cell-name cell-def]]
                              (let [{:keys [id schema requires]} cell-def]
                                (if (cell/get-cell id)
                                  ;; Cell already registered — apply manifest metadata
                                  (cell/set-cell-meta! id {:schema   schema
                                                           :requires (or requires [])})
                                  ;; Not registered — register stub handler with full spec
                                  (let [stub {:id      id
                                              :handler (fn [_ data] data)
                                              :schema  schema
                                              :requires (or requires [])
                                              :doc     (:doc cell-def)}]
                                    (.addMethod cell/cell-spec id (constantly stub))
                                    stub))
                                [cell-name id])))
                       cells)]
    (cond-> {:cells cell-ids
             :edges edges}
      dispatches (assoc :dispatches dispatches))))
