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
  [output-schema transitions cell-name]
  (cond
    (vector? output-schema)
    (validate-malli-schema! output-schema (str cell-name " :output"))

    (map? output-schema)
    (do
      (doseq [[k v] output-schema]
        (validate-malli-schema! v (str cell-name " :output transition " k)))
      (let [schema-keys (set (keys output-schema))
            trans-set   (set transitions)
            extra       (cset/difference schema-keys trans-set)
            missing     (cset/difference trans-set schema-keys)]
        (when (seq extra)
          (throw (ex-info (str "Output schema has keys " extra
                               " not in transitions " trans-set " for " cell-name)
                          {:cell-name cell-name :extra extra :transitions trans-set})))
        (when (seq missing)
          (throw (ex-info (str "Output schema missing keys " missing
                               " for transitions " trans-set " in " cell-name)
                          {:cell-name cell-name :missing missing :transitions trans-set})))))

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
  (when (or (nil? (:transitions cell-def)) (empty? (:transitions cell-def)))
    (throw (ex-info (str "Cell " cell-name " missing or empty :transitions") {:cell-name cell-name})))
  (validate-malli-schema! (get-in cell-def [:schema :input]) (str cell-name " :input"))
  (validate-output-schema! (get-in cell-def [:schema :output]) (:transitions cell-def) cell-name))

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
  ;; Validate all cells have edges defined
  (doseq [[cell-name _] cells]
    (when-not (get edges cell-name)
      (throw (ex-info (str "Cell " cell-name " has no edges defined")
                      {:cell-name cell-name}))))
  ;; Validate edge keys match declared transitions
  (doseq [[cell-name cell-def] cells]
    (let [edge-def    (get edges cell-name)
          declared    (:transitions cell-def)
          edge-keys   (when (map? edge-def) (set (keys edge-def)))]
      (when edge-keys
        (let [uncovered (cset/difference declared edge-keys)]
          (when (seq uncovered)
            (throw (ex-info (str "Cell " cell-name " declares transition(s) "
                                 uncovered " not covered by edges")
                            {:cell-name cell-name :uncovered uncovered}))))
        (let [dead (cset/difference edge-keys declared)]
          (when (seq dead)
            (throw (ex-info (str "Cell " cell-name " has edge(s) " dead
                                 " that don't match any declared transition " declared)
                            {:cell-name cell-name :dead dead})))))))
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
   Handles both single-schema (vector) and per-transition (map) formats."
  [output-schema transitions]
  (if (map? output-schema)
    (str "Output schemas (per transition):\n"
         (str/join "\n"
                   (map (fn [t]
                          (str "  " (pr-str t) ": " (pr-str (get output-schema t))))
                        (sort transitions))))
    (str "Output schema:\n  " (pr-str output-schema))))

(defn- generate-output-examples
  "Generates output examples. For per-transition maps, generates one per transition."
  [output-schema transitions]
  (if (map? output-schema)
    (into {}
          (map (fn [t]
                 [t (merge (generate-example (get output-schema t))
                           {:mycelium/transition t})]))
          (sort transitions))
    (merge (generate-example output-schema)
           {:mycelium/transition (first (sort transitions))})))

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
        output-ex   (generate-output-examples (:output schema) transitions)
        output-section (format-output-schema-section (:output schema) transitions)
        example-section (if (map? (:output schema))
                          (str/join "\n\n"
                                    (map (fn [[t ex]]
                                           (str "Example output (" (pr-str t) "):\n  " (pr-str ex)))
                                         (sort-by first output-ex)))
                          (str "Example output:\n  " (pr-str output-ex)))
        prompt      (str "## Cell: " id "\n"
                         (when doc (str "\n## Purpose\n" doc "\n"))
                         "\n## Contract\n\n"
                         "Input schema:\n  " (pr-str (:input schema)) "\n\n"
                         output-section "\n\n"
                         "Required resources: " (if (seq requires)
                                                  (str/join ", " (map pr-str requires))
                                                  "none") "\n\n"
                         "Transition signals: " (str/join ", " (map pr-str transitions)) "\n"
                         "  Return (assoc data :mycelium/transition :<signal>) for each.\n"
                         "\n## Example Data\n\n"
                         "Example input:\n  " (pr-str input-ex) "\n\n"
                         example-section "\n"
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
   For each cell in the manifest:
   - If not already registered, registers a stub handler with the manifest schema.
   - If already registered, applies the manifest schema via `set-cell-schema!`.
   The manifest is the single source of truth for schemas.
   Returns {:cells ... :edges ...} suitable for `workflow/compile-workflow`."
  [{:keys [cells edges] :as manifest}]
  (let [cell-ids (into {}
                       (map (fn [[cell-name cell-def]]
                              (let [{:keys [id schema transitions requires]} cell-def]
                                (if (cell/get-cell id)
                                  ;; Cell already registered — apply manifest schema
                                  (cell/set-cell-schema! id schema)
                                  ;; Not registered — register stub handler with schema
                                  (cell/register-cell!
                                   {:id          id
                                    :handler     (fn [_ data]
                                                   (assoc data :mycelium/transition
                                                          (first (sort transitions))))
                                    :schema      schema
                                    :transitions transitions
                                    :requires    (or requires [])
                                    :doc         (:doc cell-def)}))
                                [cell-name id])))
                       cells)]
    {:cells cell-ids
     :edges edges}))
