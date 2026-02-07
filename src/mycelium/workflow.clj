(ns mycelium.workflow
  "Workflow DSL compiler: transforms workflow definitions into Maestro FSM specs."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [maestro.core :as fsm]))

;; ===== State ID resolution =====

(def ^:private special-states
  {:start ::fsm/start
   :end   ::fsm/end
   :error ::fsm/error
   :halt  ::fsm/halt})

(defn resolve-state-id
  "Maps special keywords (:start, :end, :error, :halt) to Maestro reserved states.
   Other keywords are namespaced to :mycelium.workflow/name."
  [kw]
  (or (get special-states kw)
      (keyword "mycelium.workflow" (name kw))))

;; ===== Edge compilation =====

(defn compile-edges
  "Compiles edge definitions into Maestro dispatch pairs.
   If edges is a keyword → unconditional dispatch to that target.
   If edges is a map → each entry becomes a predicate checking :mycelium/transition."
  [edges]
  (if (keyword? edges)
    [[(resolve-state-id edges) (constantly true)]]
    (mapv (fn [[transition-kw target]]
            [(resolve-state-id target)
             (fn [data] (= (:mycelium/transition data) transition-kw))])
          edges)))

;; ===== Validation =====

(defn- validate-cells-exist!
  "Checks all cells referenced in :cells exist in the registry."
  [cells]
  (doseq [[_name cell-id] cells]
    (cell/get-cell! cell-id)))

(defn- validate-edge-targets!
  "Checks all edge targets reference valid cell names or :end/:error/:halt."
  [edges-map cells-map]
  (let [valid-names (into #{:end :error :halt} (keys cells-map))]
    (doseq [[from edge-def] edges-map
            target (if (keyword? edge-def) [edge-def] (vals edge-def))]
      (when-not (contains? valid-names target)
        (throw (ex-info (str "Invalid edge target " target " from " from
                             ". Valid targets: " valid-names)
                        {:from from :target target :valid valid-names}))))))

(defn- validate-reachability!
  "BFS from :start, checks all cells are reachable."
  [edges-map cells-map]
  (let [cell-names (set (keys cells-map))
        ;; Build adjacency: cell-name -> #{target-cell-names}
        adjacency  (into {}
                         (map (fn [[from edge-def]]
                                [from (if (keyword? edge-def)
                                        #{edge-def}
                                        (set (vals edge-def)))]))
                         edges-map)
        ;; BFS
        reachable  (loop [queue  (conj clojure.lang.PersistentQueue/EMPTY :start)
                          visited #{}]
                     (if (empty? queue)
                       visited
                       (let [node (peek queue)
                             queue (pop queue)]
                         (if (visited node)
                           (recur queue visited)
                           (let [neighbors (get adjacency node #{})]
                             (recur (into queue neighbors)
                                    (conj visited node)))))))]
    (let [unreachable (set/difference cell-names reachable)]
      (when (seq unreachable)
        (throw (ex-info (str "Unreachable cells: " unreachable)
                        {:unreachable unreachable}))))))

(defn- validate-transition-coverage!
  "Checks that all declared cell transitions are covered by edges,
   and that all edge keys match declared transitions."
  [edges-map cells-map]
  (doseq [[cell-name cell-id] cells-map]
    (let [cell       (cell/get-cell! cell-id)
          edge-def   (get edges-map cell-name)
          covered    (if (keyword? edge-def)
                       ;; Unconditional edge covers all transitions
                       (:transitions cell)
                       (set (keys edge-def)))
          declared   (:transitions cell)
          uncovered  (set/difference declared covered)]
      (when (seq uncovered)
        (throw (ex-info (str "Cell " cell-name " (" cell-id ") declares transition(s) "
                             uncovered " not covered by edges")
                        {:cell-name cell-name
                         :cell-id   cell-id
                         :uncovered uncovered
                         :declared  declared
                         :covered   covered})))
      ;; Check for dead edges (edge keys that don't match any declared transition)
      (when (map? edge-def)
        (let [dead-edges (set/difference covered declared)]
          (when (seq dead-edges)
            (throw (ex-info (str "Cell " cell-name " (" cell-id ") has edge(s) "
                                 dead-edges " that don't match any declared transition "
                                 declared)
                            {:cell-name  cell-name
                             :cell-id    cell-id
                             :dead-edges dead-edges
                             :declared   declared}))))))))

(defn- get-map-keys
  "Extracts top-level keys from a Malli :map schema. Returns nil if not a :map schema."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (set (map first (rest schema)))))

(defn- get-output-keys-for-transition
  "Gets output keys for a specific transition of a cell.
   For vector output schema, returns all keys for any transition.
   For map output schema, returns keys for that specific transition."
  [cell-id transition]
  (let [cell   (cell/get-cell! cell-id)
        output (get-in cell [:schema :output])]
    (cond
      (nil? output)    nil
      (vector? output) (get-map-keys output)
      (map? output)    (when-let [schema (get output transition)]
                         (get-map-keys schema)))))

(defn- get-all-output-keys
  "Gets the union of all output keys across all transitions.
   Used for unconditional edges where all transitions route to the same target."
  [cell-id]
  (let [cell   (cell/get-cell! cell-id)
        output (get-in cell [:schema :output])]
    (cond
      (nil? output)    nil
      (vector? output) (get-map-keys output)
      (map? output)    (reduce (fn [acc [_ schema]]
                                 (into acc (get-map-keys schema)))
                               #{}
                               output))))

(defn- validate-schema-chain!
  "Walks all paths from :start, accumulating output keys.
   For each cell, checks its input keys are available from upstream outputs or workflow input.
   For per-transition output schemas, only passes the keys from the matching transition's schema
   along each edge."
  [edges-map cells-map]
  (let [get-input-keys  (fn [cell-id]
                          (let [cell   (cell/get-cell! cell-id)
                                schema (get-in cell [:schema :input])]
                            (get-map-keys schema)))
        errors (atom [])
        visit  (fn visit [cell-name available-keys visited]
                 (when-not (or (contains? visited cell-name)
                               (contains? #{:end :error :halt} cell-name))
                   (let [cell-id     (get cells-map cell-name)
                         input-keys  (get-input-keys cell-id)
                         missing     (when input-keys
                                       (set/difference input-keys available-keys))]
                     (when (seq missing)
                       (swap! errors conj
                              {:cell-name     cell-name
                               :cell-id       cell-id
                               :missing-keys  missing
                               :available-keys available-keys}))
                     ;; Traverse edges, using per-transition output keys
                     (let [edge-def (get edges-map cell-name)]
                       (if (keyword? edge-def)
                         ;; Unconditional edge — use union of all output keys
                         (let [out-keys (get-all-output-keys cell-id)
                               new-keys (into available-keys (or out-keys #{}))]
                           (visit edge-def new-keys (conj visited cell-name)))
                         ;; Map edges — per-transition output keys
                         (doseq [[transition target] edge-def]
                           (let [out-keys (get-output-keys-for-transition cell-id transition)
                                 new-keys (into available-keys (or out-keys #{}))]
                             (visit target new-keys (conj visited cell-name)))))))))]
    ;; Start with :start cell
    (let [start-cell-id    (get cells-map :start)
          start-input-keys (when start-cell-id (get-input-keys start-cell-id))
          initial-keys     (or start-input-keys #{})
          edge-def         (get edges-map :start)]
      (if (keyword? edge-def)
        (let [out-keys (get-all-output-keys start-cell-id)
              new-keys (into initial-keys (or out-keys #{}))]
          (visit edge-def new-keys #{:start}))
        (doseq [[transition target] edge-def]
          (let [out-keys (get-output-keys-for-transition start-cell-id transition)
                new-keys (into initial-keys (or out-keys #{}))]
            (visit target new-keys #{:start})))))
    (when (seq @errors)
      (let [msg (str "Schema chain error: "
                     (str/join
                      "; "
                      (map (fn [{:keys [cell-name cell-id missing-keys available-keys]}]
                             (str cell-id " at " cell-name
                                  " requires keys " missing-keys
                                  " but only " available-keys " available"))
                           @errors)))]
        (throw (ex-info msg {:errors @errors}))))))

(defn validate-workflow
  "Runs all validations on a workflow definition."
  [{:keys [cells edges]}]
  (validate-cells-exist! cells)
  (validate-edge-targets! edges cells)
  (validate-reachability! edges cells)
  (validate-transition-coverage! edges cells)
  (validate-schema-chain! edges cells))

;; ===== Compilation =====

(defn compile-workflow
  "Compiles a workflow definition into a Maestro FSM.
   Returns the compiled (ready-to-run) FSM."
  ([workflow] (compile-workflow workflow {}))
  ([{:keys [cells edges] :as workflow} opts]
   ;; Validate
   (validate-workflow workflow)
   ;; Build state->cell map (resolved-state-id -> cell-spec)
   (let [state->cell (into {}
                           (map (fn [[cell-name cell-id]]
                                  [(resolve-state-id cell-name)
                                   (cell/get-cell! cell-id)]))
                           cells)
         ;; Build Maestro FSM states
         fsm-states (into {}
                          (map (fn [[cell-name cell-id]]
                                 (let [cell      (cell/get-cell! cell-id)
                                       state-id  (resolve-state-id cell-name)
                                       edge-def  (get edges cell-name)]
                                   [state-id
                                    (merge
                                     {:handler    (:handler cell)
                                      :dispatches (compile-edges edge-def)}
                                     (when (:async? cell)
                                       {:async? true}))])))
                          cells)
         ;; Build interceptors — compose custom pre/post with schema interceptors
         schema-pre  (schema/make-pre-interceptor state->cell)
         schema-post (schema/make-post-interceptor state->cell)
         pre  (if-let [custom-pre (:pre opts)]
                (fn [fsm-state resources]
                  (-> (schema-pre fsm-state resources)
                      (custom-pre resources)))
                schema-pre)
         post (if-let [custom-post (:post opts)]
                (fn [fsm-state resources]
                  (-> (schema-post fsm-state resources)
                      (custom-post resources)))
                schema-post)
         ;; Merge custom on-end / on-error handlers
         spec {:fsm  (merge fsm-states
                            (when-let [on-error (:on-error opts)]
                              {::fsm/error {:handler on-error}})
                            (when-let [on-end (:on-end opts)]
                              {::fsm/end {:handler on-end}}))
               :opts {:pre  pre
                      :post post}}]
     (fsm/compile spec))))
