(ns mycelium.system
  "System-level compilation: aggregates multiple manifests into a queryable system description.
   Provides bird's-eye view of all workflows, cells, resources, and cross-workflow analysis."
  (:require [clojure.string :as str]))

(defn- extract-cell-ids
  "Extracts all cell :id values from a manifest, including join members."
  [{:keys [cells]}]
  (set (map :id (vals cells))))

(defn- extract-requires
  "Extracts all :requires from a manifest's cells."
  [{:keys [cells]}]
  (reduce (fn [acc [_ cell-def]]
            (into acc (or (:requires cell-def) [])))
          #{}
          cells))

(defn- extract-cell-schemas
  "Extracts a map of {cell-id -> schema} from a manifest."
  [{:keys [cells]}]
  (into {}
        (map (fn [[_ cell-def]]
               [(:id cell-def) (:schema cell-def)]))
        cells))

(defn compile-system
  "Compiles a system from a route→manifest map.
   Returns a queryable system description with routes, cells, shared-cells,
   resources-needed, and warnings."
  [route-manifest-map]
  (if (empty? route-manifest-map)
    {:routes {} :cells {} :shared-cells #{} :resources-needed #{} :warnings []}
    (let [;; Build per-route info
          routes (into {}
                       (map (fn [[route manifest]]
                              [route {:manifest-id (:manifest-id manifest (:id manifest))
                                      :cells       (extract-cell-ids manifest)
                                      :requires    (extract-requires manifest)
                                      :input-schema (:input-schema manifest)}]))
                       route-manifest-map)
          ;; Build cell → routes reverse index
          cell-routes (reduce (fn [acc [route {:keys [cells]}]]
                                (reduce (fn [a cell-id]
                                          (update a cell-id (fnil conj []) route))
                                        acc
                                        cells))
                              {}
                              routes)
          ;; Build cell info with schemas per route
          cell-schemas (reduce (fn [acc [route manifest]]
                                 (let [schemas (extract-cell-schemas manifest)]
                                   (reduce (fn [a [cell-id schema]]
                                             (update a cell-id (fnil conj [])
                                                     {:route route :schema schema}))
                                           acc
                                           schemas)))
                               {}
                               route-manifest-map)
          ;; Identify shared cells (used in 2+ workflows)
          shared-cells (set (keep (fn [[cell-id routes-list]]
                                    (when (> (count routes-list) 1)
                                      cell-id))
                                  cell-routes))
          ;; Aggregate all resources
          resources-needed (reduce (fn [acc [_ route-info]]
                                     (into acc (:requires route-info)))
                                   #{}
                                   routes)
          ;; Detect schema conflicts
          warnings (reduce (fn [acc [cell-id schema-entries]]
                             (if (> (count schema-entries) 1)
                               (let [distinct-schemas (distinct (map :schema schema-entries))]
                                 (if (> (count distinct-schemas) 1)
                                   (conj acc (str "Cell " cell-id " has different schemas across: "
                                                  (str/join ", " (map :route schema-entries))))
                                   acc))
                               acc))
                           []
                           cell-schemas)
          ;; Build cells index
          cells-index (into {}
                            (map (fn [[cell-id routes-list]]
                                   [cell-id {:used-by routes-list
                                             :schema  (:schema (first (get cell-schemas cell-id)))}]))
                            cell-routes)]
      {:routes          routes
       :cells           cells-index
       :shared-cells    shared-cells
       :resources-needed resources-needed
       :warnings        warnings})))

;; ===== Query functions =====

(defn cell-usage
  "Returns the list of routes that use a given cell."
  [system cell-id]
  (get-in system [:cells cell-id :used-by] []))

(defn route-cells
  "Returns the set of cell IDs used by a given route."
  [system route]
  (get-in system [:routes route :cells] #{}))

(defn route-resources
  "Returns the set of resources needed by a given route."
  [system route]
  (get-in system [:routes route :requires] #{}))

(defn schema-conflicts
  "Returns a seq of conflict maps for cells with different schemas across workflows.
   Each map: {:cell-id :warning}"
  [system]
  (keep
   (fn [warning]
     (when-let [[_ cell-id-str] (re-find #"Cell (\S+) has different schemas" warning)]
       (let [clean (if (str/starts-with? cell-id-str ":")
                     (subs cell-id-str 1)
                     cell-id-str)]
         {:cell-id (keyword clean)
          :warning warning})))
   (:warnings system)))

(defn system->dot
  "Generates a DOT graph string for the full system.
   Routes are shown as clusters, cells as nodes, shared cells highlighted."
  [system]
  (let [sb (StringBuilder.)]
    (.append sb "digraph system {\n")
    (.append sb "  rankdir=LR;\n")
    (.append sb "  compound=true;\n")
    (.append sb "  node [shape=box];\n\n")
    ;; Shared cells as special nodes
    (doseq [cell-id (:shared-cells system)]
      (.append sb (str "  \"" cell-id "\" [style=filled fillcolor=lightyellow];\n")))
    (.append sb "\n")
    ;; Route clusters
    (doseq [[route route-info] (:routes system)]
      (let [cluster-name (str/replace route #"[^a-zA-Z0-9]" "_")]
        (.append sb (str "  subgraph cluster_" cluster-name " {\n"))
        (.append sb (str "    label=\"" route "\";\n"))
        (.append sb "    style=rounded;\n")
        (doseq [cell-id (:cells route-info)]
          (when-not (contains? (:shared-cells system) cell-id)
            (.append sb (str "    \"" route "/" cell-id "\" [label=\"" cell-id "\"];\n"))))
        (.append sb "  }\n\n")))
    (.append sb "}\n")
    (str sb)))
