(ns mycelium.workflow
  "Workflow DSL compiler: transforms workflow definitions into Maestro FSM specs."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [mycelium.validation :as v]
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
   If edges is a map → uses dispatch-vec (vector of [label pred] pairs) to
   produce ordered [[target pred] ...] matching Maestro's format."
  [edges dispatch-vec]
  (if (keyword? edges)
    [[(resolve-state-id edges) (constantly true)]]
    (mapv (fn [[label pred]]
            (let [target (get edges label)]
              (when-not target
                (throw (ex-info (str "No edge target for dispatch label " label)
                                {:label label})))
              [(resolve-state-id target) pred]))
          dispatch-vec)))

;; ===== Schema key utilities =====

(defn- get-map-keys
  "Extracts top-level keys from a Malli :map schema. Returns nil if not a :map schema.
   Skips Malli property maps (e.g. {:closed true} in [:map {:closed true} [:x :int]])."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (set (keep (fn [entry]
                 (when (vector? entry)
                   (first entry)))
               (rest schema)))))

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

(defn- get-join-member-output-keys
  "Gets the union of all output keys from a single join member cell."
  [cell-id]
  (let [cell   (cell/get-cell! cell-id)
        output (get-in cell [:schema :output])]
    (cond
      (nil? output)    #{}
      (vector? output) (or (get-map-keys output) #{})
      (map? output)    (reduce (fn [acc [_ schema]]
                                 (into acc (or (get-map-keys schema) #{})))
                               #{}
                               output))))

;; ===== Join validation =====

(defn- validate-join-defs!
  "Validates join definitions against the workflow."
  [joins cells edges]
  (let [cell-names (set (keys cells))
        join-names (set (keys joins))]
    ;; Join names must not collide with cell names
    (let [collisions (set/intersection cell-names join-names)]
      (when (seq collisions)
        (throw (ex-info (str "Join name collision with cell names: " collisions)
                        {:collisions collisions}))))
    (doseq [[join-name join-def] joins]
      (let [member-cells (:cells join-def)
            strategy     (:strategy join-def :parallel)]
        ;; Empty :cells vector rejected
        (when (empty? member-cells)
          (throw (ex-info (str "Join " join-name " has empty :cells vector")
                          {:join-name join-name})))
        ;; :strategy must be :parallel or :sequential
        (when-not (contains? #{:parallel :sequential} strategy)
          (throw (ex-info (str "Join " join-name " has invalid strategy: " strategy
                               ". Must be :parallel or :sequential")
                          {:join-name join-name :strategy strategy})))
        ;; Member cells must exist in :cells map
        (doseq [member member-cells]
          (when-not (contains? cell-names member)
            (throw (ex-info (str "Join " join-name " references " member
                                 " which is not found in :cells map")
                            {:join-name join-name :member member}))))
        ;; Member cells must NOT have entries in :edges
        (doseq [member member-cells]
          (when (contains? edges member)
            (throw (ex-info (str "Join member " member " in join " join-name
                                 " must not have its own entry in :edges")
                            {:join-name join-name :member member}))))
        ;; :on-failure must reference valid cell/terminal or not be present
        (when-let [on-failure (:on-failure join-def)]
          (when-not (or (contains? cell-names on-failure)
                        (contains? #{:end :error :halt} on-failure)
                        (contains? join-names on-failure))
            (throw (ex-info (str "Join " join-name " :on-failure references "
                                 on-failure " which is not found in :cells or terminal states")
                            {:join-name join-name :on-failure on-failure}))))))))

(defn- validate-join-output-conflicts!
  "For each join, checks that member cells don't produce overlapping output keys
   unless a :merge-fn is provided."
  [joins cells]
  (doseq [[join-name join-def] joins]
    (when-not (:merge-fn join-def)
      (let [member-cells (:cells join-def)
            cell-key-pairs (mapv (fn [member]
                                   (let [cell-id (get cells member)]
                                     [member (get-join-member-output-keys cell-id)]))
                                 member-cells)]
        ;; Check for overlapping keys between any pair
        (doseq [i (range (count cell-key-pairs))
                j (range (inc i) (count cell-key-pairs))]
          (let [[name-a keys-a] (nth cell-key-pairs i)
                [name-b keys-b] (nth cell-key-pairs j)
                conflicts (set/intersection keys-a keys-b)]
            (when (seq conflicts)
              (throw (ex-info (str "Join " join-name " has output key conflict between "
                                   name-a " and " name-b ": " conflicts
                                   ". Provide :merge-fn to resolve.")
                              {:join-name join-name
                               :cell-a    name-a
                               :cell-b    name-b
                               :conflicts conflicts})))))))))

;; ===== Join handler construction =====

(defn- invoke-cell-handler
  "Invokes a cell handler, handling both sync and async cells.
   Returns the result data map (blocking for async cells)."
  [cell resources data]
  (if (:async? cell)
    (let [p (promise)]
      ((:handler cell) resources data
       (fn [result] (deliver p {:ok result}))
       (fn [error]  (deliver p {:error error})))
      (let [v (deref p 30000 {:error (ex-info "Async cell timed out" {:cell-id (:id cell)})})]
        (if (:error v)
          (throw (if (instance? Throwable (:error v))
                   (:error v)
                   (ex-info (str (:error v)) {:cell-id (:id cell)})))
          (:ok v))))
    ((:handler cell) resources data)))

(defn- build-join-handler
  "Builds a synthetic handler function for a join node.
   The handler runs member cells (parallel or sequential) with snapshot semantics,
   merges results, and collects errors."
  [_join-name join-def cells-map]
  (let [member-names (:cells join-def)
        strategy     (:strategy join-def :parallel)
        merge-fn     (:merge-fn join-def)
        member-cells (mapv (fn [m] {:name m :cell (cell/get-cell! (get cells-map m))})
                           member-names)]
    (fn [resources data]
      (let [snapshot data ;; each branch gets the same snapshot
            run-member (fn [{:keys [name cell]}]
                         (let [start-ns (System/nanoTime)]
                           (try
                             (let [result (invoke-cell-handler cell resources snapshot)
                                   dur-ms (/ (- (System/nanoTime) start-ns) 1e6)]
                               {:name     name
                                :cell-id  (:id cell)
                                :result   result
                                :duration-ms dur-ms
                                :status   :ok})
                             (catch Exception e
                               (let [dur-ms (/ (- (System/nanoTime) start-ns) 1e6)]
                                 {:name     name
                                  :cell-id  (:id cell)
                                  :error    (ex-message e)
                                  :duration-ms dur-ms
                                  :status   :error})))))
            outcomes (if (= strategy :parallel)
                       (let [futures (mapv #(future (run-member %)) member-cells)]
                         (mapv (fn [f]
                                 (try
                                   (deref f)
                                   (catch Exception e
                                     ;; Unwrap ExecutionException
                                     (let [cause (or (.getCause e) e)]
                                       {:error    (ex-message cause)
                                        :status   :error
                                        :duration-ms 0}))))
                               futures))
                       ;; Sequential
                       (mapv run-member member-cells))
            errors  (filterv #(= :error (:status %)) outcomes)
            successes (filterv #(= :ok (:status %)) outcomes)
            ;; Build join trace entries
            join-traces (mapv (fn [o]
                                (cond-> {:cell     (:name o)
                                         :cell-id  (:cell-id o)
                                         :duration-ms (:duration-ms o)
                                         :status   (:status o)}
                                  (:error o) (assoc :error (:error o))))
                              outcomes)
            merged-data (if (seq errors)
                          (assoc data :mycelium/join-error
                                 (mapv (fn [e] {:cell (:name e) :error (:error e)}) errors))
                          (if merge-fn
                            (merge-fn data (mapv :result successes))
                            (apply merge data (map :result successes))))]
        (assoc merged-data :mycelium/join-traces join-traces)))))

(defn- build-join-input-schema
  "Builds a synthesized input schema for a join node.
   Union of all member cells' input keys."
  [join-def cells-map]
  (let [member-names (:cells join-def)
        all-keys (reduce (fn [acc member]
                           (let [cell (cell/get-cell! (get cells-map member))
                                 schema (get-in cell [:schema :input])]
                             (into acc (or (get-map-keys schema) #{}))))
                         #{}
                         member-names)]
    (if (empty? all-keys)
      [:map]
      (into [:map] (mapv (fn [k] [k :any]) all-keys)))))

(defn- build-join-output-keys
  "Gets the union of all output keys from all join member cells."
  [join-def cells-map]
  (reduce (fn [acc member]
            (let [cell-id (get cells-map member)]
              (into acc (get-join-member-output-keys cell-id))))
          #{}
          (:cells join-def)))

;; ===== Validation =====

(defn- validate-cells-exist!
  "Checks all cells referenced in :cells exist in the registry."
  [cells]
  (doseq [[_name cell-id] cells]
    (cell/get-cell! cell-id)))

(defn- merge-default-dispatches
  "Merges default dispatches from cell specs into the workflow dispatches.
   For each cell with map edges and no explicit dispatch, checks the cell spec
   for :default-dispatches and uses that as fallback."
  [dispatches-map edges-map cells-map]
  (reduce (fn [acc [cell-name edge-def]]
            (if (and (map? edge-def) (not (get acc cell-name)))
              (let [cell-id (get cells-map cell-name)
                    cell (when cell-id (cell/get-cell cell-id))]
                (if-let [defaults (:default-dispatches cell)]
                  (assoc acc cell-name defaults)
                  acc))
              acc))
          (or dispatches-map {})
          edges-map))

(defn- validate-schema-chain!
  "Walks all paths from :start, accumulating output keys.
   For each cell, checks its input keys are available from upstream outputs or workflow input.
   For per-transition output schemas, only passes the keys from the matching transition's schema
   along each edge.
   Join nodes: validates each member's inputs, then adds the union of all member outputs."
  [edges-map cells-map joins-map]
  (let [get-input-keys  (fn [cell-id]
                          (let [cell   (cell/get-cell! cell-id)
                                schema (get-in cell [:schema :input])]
                            (get-map-keys schema)))
        errors (atom [])
        visit  (fn visit [cell-name available-keys visited]
                 (when-not (or (contains? visited cell-name)
                               (contains? #{:end :error :halt} cell-name))
                   (if-let [join-def (get joins-map cell-name)]
                     ;; This is a join node — validate member inputs and accumulate union of outputs
                     (let [member-names (:cells join-def)]
                       ;; Validate each member's input keys against available
                       (doseq [member member-names]
                         (let [cell-id    (get cells-map member)
                               input-keys (get-input-keys cell-id)
                               missing    (when input-keys
                                            (set/difference input-keys available-keys))]
                           (when (seq missing)
                             (swap! errors conj
                                    {:cell-name     member
                                     :cell-id       cell-id
                                     :missing-keys  missing
                                     :available-keys available-keys}))))
                       ;; Accumulate union of all member output keys
                       (let [out-keys (build-join-output-keys join-def cells-map)
                             new-keys (into available-keys out-keys)
                             edge-def (get edges-map cell-name)]
                         (if (keyword? edge-def)
                           (visit edge-def new-keys (conj visited cell-name))
                           (doseq [[_transition target] edge-def]
                             (visit target new-keys (conj visited cell-name))))))
                     ;; Regular cell
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
                               (visit target new-keys (conj visited cell-name))))))))))]
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

(def ^:private join-default-dispatches
  "Default dispatch predicates for join nodes.
   :failure if :mycelium/join-error is present, :done otherwise."
  [[:failure (fn [d] (some? (:mycelium/join-error d)))]
   [:done    (fn [d] (not (:mycelium/join-error d)))]])

(defn validate-workflow
  "Runs all validations on a workflow definition.
   Merges :default-dispatches from cell specs as fallback for cells without explicit dispatches."
  [{:keys [cells edges dispatches joins]}]
  (validate-cells-exist! cells)
  ;; Validate join definitions
  (let [joins-map (or joins {})]
    (when (seq joins-map)
      (validate-join-defs! joins-map cells edges)
      (validate-join-output-conflicts! joins-map cells))
    ;; For edge-target and reachability validation, include join names as valid targets
    (let [cell-names     (set (keys cells))
          join-names     (set (keys joins-map))
          ;; Members consumed by joins should not be in edges but are valid cells
          join-members   (set (mapcat :cells (vals joins-map)))
          ;; Non-member cell names = cells that appear in edges
          edge-cell-names (set/difference cell-names join-members)
          ;; Valid names for edge targets = non-member cells + join names
          valid-names    (set/union edge-cell-names join-names)]
      (v/validate-edge-targets! edges valid-names)
      (v/validate-reachability! edges valid-names))
    ;; Merge default dispatches — include join default dispatches (filtered to match edge keys)
    (let [join-dispatches (reduce (fn [acc [join-name _]]
                                   (let [edge-def (get edges join-name)]
                                     (if (and (map? edge-def)
                                              (not (get acc join-name)))
                                       (let [edge-keys (set (keys edge-def))
                                             filtered  (filterv (fn [[label _]]
                                                                  (contains? edge-keys label))
                                                                join-default-dispatches)]
                                         (if (seq filtered)
                                           (assoc acc join-name filtered)
                                           acc))
                                       acc)))
                                 {}
                                 joins-map)
          effective-dispatches (merge join-dispatches
                                      (merge-default-dispatches dispatches edges cells))]
      (v/validate-dispatch-coverage! edges effective-dispatches))
    (validate-schema-chain! edges cells joins-map)))

;; ===== Compilation =====

(defn- build-edge-targets
  "Builds a reverse map from edge definitions for the post-interceptor.
   Returns {resolved-state-id → {resolved-target-state-id → transition-label}}.
   Used to infer which transition was taken from the dispatch target."
  [edges]
  (into {}
    (keep (fn [[cell-name edge-def]]
            (when (map? edge-def)
              [(resolve-state-id cell-name)
               (into {}
                 (map (fn [[label target]]
                        [(resolve-state-id target) label]))
                 edge-def)])))
    edges))

(defn compile-workflow
  "Compiles a workflow definition into a Maestro FSM.
   Returns the compiled (ready-to-run) FSM."
  ([workflow] (compile-workflow workflow {}))
  ([{:keys [cells edges dispatches joins] :as workflow} opts]
   ;; Validate
   (validate-workflow workflow)
   (let [joins-map (or joins {})
         ;; Determine which cells are consumed by joins
         join-members (set (mapcat :cells (vals joins-map)))
         ;; Merge default dispatches from cell specs AND join default dispatches (filtered to edge keys)
         join-dispatches (reduce (fn [acc [join-name _]]
                                  (let [edge-def (get edges join-name)]
                                    (if (and (map? edge-def)
                                             (not (get acc join-name)))
                                      (let [edge-keys (set (keys edge-def))
                                            filtered  (filterv (fn [[label _]]
                                                                 (contains? edge-keys label))
                                                               join-default-dispatches)]
                                        (if (seq filtered)
                                          (assoc acc join-name filtered)
                                          acc))
                                      acc)))
                                {}
                                joins-map)
         effective-dispatches (merge join-dispatches
                                     (merge-default-dispatches dispatches edges cells))
         ;; Build state->cell map for non-join-member cells
         state->cell (into {}
                           (keep (fn [[cell-name cell-id]]
                                   (when-not (contains? join-members cell-name)
                                     [(resolve-state-id cell-name)
                                      (cell/get-cell! cell-id)])))
                           cells)
         ;; Build synthetic join cell specs for state->cell
         join-cell-specs (into {}
                               (map (fn [[join-name join-def]]
                                      (let [handler   (build-join-handler join-name join-def cells)
                                            in-schema (build-join-input-schema join-def cells)]
                                        [(resolve-state-id join-name)
                                         {:id       (keyword "mycelium.join" (name join-name))
                                          :handler  handler
                                          :schema   {:input  in-schema
                                                     :output :map}
                                          :default-dispatches join-default-dispatches}])))
                               joins-map)
         state->cell (merge state->cell join-cell-specs)
         ;; Build state->edge-targets for post-interceptor transition lookup
         state->edge-targets (build-edge-targets edges)
         ;; Build state->names for human-readable trace entries
         state->names (merge
                       (into {}
                             (map (fn [[cell-name _]]
                                    [(resolve-state-id cell-name) cell-name]))
                             cells)
                       (into {}
                             (map (fn [[join-name _]]
                                    [(resolve-state-id join-name) join-name]))
                             joins-map))
         ;; Build Maestro FSM states — non-join-member cells only
         fsm-cell-states (into {}
                               (keep (fn [[cell-name cell-id]]
                                       (when-not (contains? join-members cell-name)
                                         (let [cell      (cell/get-cell! cell-id)
                                               state-id  (resolve-state-id cell-name)
                                               edge-def  (get edges cell-name)
                                               dispatch-vec (get effective-dispatches cell-name)]
                                           [state-id
                                            (merge
                                             {:handler    (:handler cell)
                                              :dispatches (compile-edges edge-def dispatch-vec)}
                                             (when (:async? cell)
                                               {:async? true}))]))))
                               cells)
         ;; Build FSM states for joins
         fsm-join-states (into {}
                               (map (fn [[join-name _join-def]]
                                      (let [state-id     (resolve-state-id join-name)
                                            join-cell    (get join-cell-specs state-id)
                                            edge-def     (get edges join-name)
                                            dispatch-vec (get effective-dispatches join-name)]
                                        [state-id
                                         {:handler    (:handler join-cell)
                                          :dispatches (compile-edges edge-def dispatch-vec)}])))
                               joins-map)
         fsm-states (merge fsm-cell-states fsm-join-states)
         ;; Build interceptors — compose custom pre/post with schema interceptors
         schema-pre  (schema/make-pre-interceptor state->cell)
         schema-post (schema/make-post-interceptor state->cell state->edge-targets state->names)
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
                      :post post}}
         ;; Static analysis: catch structural issues Maestro can detect
         analysis (fsm/analyze spec)]
     (when (seq (:no-path-to-end analysis))
       (let [names (mapv #(get state->names % %) (:no-path-to-end analysis))]
         (throw (ex-info (str "States with no path to end: " names)
                         {:no-path-to-end names :analysis analysis}))))
     (fsm/compile spec))))
