(ns mycelium.workflow
  "Workflow DSL compiler: transforms workflow definitions into Maestro FSM specs."
  (:require [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [maestro.core :as fsm]
            [malli.core :as m]))

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

(defn- collect-edge-targets
  "Returns all cell-name targets mentioned in edges (excluding :end, :error, :halt)."
  [edges-map]
  (into #{}
        (comp (mapcat (fn [[_ edge-def]]
                        (if (keyword? edge-def)
                          [edge-def]
                          (vals edge-def))))
              (remove #{:end :error :halt}))
        edges-map))

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
    (let [unreachable (clojure.set/difference cell-names reachable)]
      (when (seq unreachable)
        (throw (ex-info (str "Unreachable cells: " unreachable)
                        {:unreachable unreachable}))))))

(defn- validate-transition-coverage!
  "Checks that all declared cell transitions are covered by edges."
  [edges-map cells-map]
  (doseq [[cell-name cell-id] cells-map]
    (let [cell       (cell/get-cell! cell-id)
          edge-def   (get edges-map cell-name)
          covered    (if (keyword? edge-def)
                       ;; Unconditional edge covers all transitions
                       (:transitions cell)
                       (set (keys edge-def)))
          declared   (:transitions cell)
          uncovered  (clojure.set/difference declared covered)]
      (when (seq uncovered)
        (throw (ex-info (str "Cell " cell-name " (" cell-id ") declares transition(s) "
                             uncovered " not covered by edges")
                        {:cell-name cell-name
                         :cell-id   cell-id
                         :uncovered uncovered
                         :declared  declared
                         :covered   covered}))))))

(defn- validate-schema-chain!
  "Walks all paths from :start, accumulating output keys.
   For each cell, checks its input keys are available from upstream outputs or workflow input."
  [edges-map cells-map]
  (let [get-input-keys  (fn [cell-id]
                          (let [cell   (cell/get-cell! cell-id)
                                schema (get-in cell [:schema :input])]
                            (when (and (vector? schema) (= :map (first schema)))
                              (set (map first (rest schema))))))
        get-output-keys (fn [cell-id]
                          (let [cell   (cell/get-cell! cell-id)
                                schema (get-in cell [:schema :output])]
                            (when (and (vector? schema) (= :map (first schema)))
                              (set (map first (rest schema))))))
        ;; DFS collecting available keys at each node
        errors (atom [])
        visit  (fn visit [cell-name available-keys visited]
                 (when-not (or (contains? visited cell-name)
                               (contains? #{:end :error :halt} cell-name))
                   (let [cell-id     (get cells-map cell-name)
                         input-keys  (get-input-keys cell-id)
                         missing     (when input-keys
                                       (clojure.set/difference input-keys available-keys))]
                     (when (seq missing)
                       (swap! errors conj
                              {:cell-name     cell-name
                               :cell-id       cell-id
                               :missing-keys  missing
                               :available-keys available-keys}))
                     ;; Accumulate output keys
                     (let [new-keys (into available-keys (get-output-keys cell-id))
                           edge-def (get edges-map cell-name)
                           targets  (if (keyword? edge-def) [edge-def] (vals edge-def))]
                       (doseq [target targets]
                         (visit target new-keys (conj visited cell-name)))))))]
    ;; Start with :start cell's input keys as "available" (provided by workflow input)
    (let [start-cell-id  (get cells-map :start)
          start-out-keys (when start-cell-id (get-output-keys start-cell-id))
          ;; The workflow's initial data provides whatever :start needs
          ;; Start's outputs become available for next cells
          start-input-keys (when start-cell-id (get-input-keys start-cell-id))
          initial-keys   (into (or start-input-keys #{})
                               (or start-out-keys #{}))
          edge-def       (get edges-map :start)
          targets        (if (keyword? edge-def) [edge-def] (vals edge-def))]
      (doseq [target targets]
        (visit target initial-keys #{:start})))
    (when (seq @errors)
      (let [msg (str "Schema chain error: "
                     (clojure.string/join
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
         ;; Build interceptors
         pre  (schema/make-pre-interceptor state->cell)
         post (schema/make-post-interceptor state->cell)
         ;; Merge custom on-end / on-error handlers
         spec {:fsm  (merge fsm-states
                            (when-let [on-error (:on-error opts)]
                              {::fsm/error {:handler on-error}})
                            (when-let [on-end (:on-end opts)]
                              {::fsm/end {:handler on-end}}))
               :opts {:pre  pre
                      :post post}}]
     (fsm/compile spec))))
