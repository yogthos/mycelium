(ns mycelium.dev
  "Development utilities: cell testing, scaffolding, visualization."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(defn test-cell
  "Runs a single cell in isolation with full schema validation.
   Options:
     :input               - input data map
     :resources           - resources map (default {})
     :dispatches          - [[label pred-fn] ...] for dispatch-aware output validation
     :expected-dispatch   - if set, verifies the matched dispatch label
   Returns:
     {:pass? bool :errors [...] :output {...} :duration-ms n :matched-dispatch kw-or-nil}"
  [cell-id {:keys [input resources dispatches expected-dispatch] :or {resources {}}}]
  (let [cell       (cell/get-cell! cell-id)
        start-time (System/nanoTime)
        ;; Validate input
        input-err  (schema/validate-input cell input)]
    (if input-err
      {:pass?       false
       :errors      [{:phase :input :detail input-err}]
       :output      nil
       :duration-ms 0}
      (try
        (let [output      ((:handler cell) resources input)
              duration-ms (/ (- (System/nanoTime) start-time) 1e6)
              ;; Determine matched dispatch label first
              matched     (when dispatches
                            (some (fn [[label pred]]
                                    (when (pred output) label))
                                  dispatches))
              output-err  (schema/validate-output cell output matched)
              dispatch-err (when (and expected-dispatch
                                      (not= expected-dispatch matched))
                             {:phase :dispatch
                              :detail (str "Expected dispatch " expected-dispatch
                                           " but got " matched)})
              all-errors  (cond-> []
                            output-err   (conj {:phase :output :detail output-err})
                            dispatch-err (conj dispatch-err))]
          (if (seq all-errors)
            {:pass?            false
             :errors           all-errors
             :output           output
             :duration-ms      duration-ms
             :matched-dispatch matched}
            {:pass?            true
             :errors           []
             :output           output
             :duration-ms      duration-ms
             :matched-dispatch matched}))
        (catch Exception e
          {:pass?       false
           :errors      [{:phase :handler :detail (ex-message e)}]
           :output      nil
           :duration-ms (/ (- (System/nanoTime) start-time) 1e6)})))))

(defn test-transitions
  "Tests a cell across multiple dispatch paths.
   cases: {dispatch-label {:input ... :resources ... :dispatches ...}}
   When a case provides :dispatches, :expected-dispatch is set to the label.
   When no :dispatches, the cell is tested for output only (no dispatch check).
   Returns: {dispatch-label test-result}"
  [cell-id cases]
  (into {}
        (map (fn [[label opts]]
               [label (test-cell cell-id
                        (if (:dispatches opts)
                          (assoc opts :expected-dispatch label)
                          opts))]))
        cases))

(defn enumerate-paths
  "Enumerates all paths from :start to terminal states in a manifest.
   Join nodes are treated as single steps (their internal members are not expanded).
   Returns seq of paths, each a vector of {:cell :transition :target} (with optional :join? true)."
  [{:keys [cells edges joins]}]
  (let [terminal?  #{:end :error :halt}
        join-names (set (keys (or joins {})))]
    (loop [queue [[:start [] #{}]]
           paths []]
      (if (empty? queue)
        paths
        (let [[cell-name path-so-far visited] (first queue)
              rest-queue (rest queue)]
          (cond
            (terminal? cell-name)
            (recur rest-queue (conj paths path-so-far))

            (contains? visited cell-name)
            (recur rest-queue paths)

            :else
            (let [edge-def    (get edges cell-name)
                  new-visited (conj visited cell-name)
                  is-join?    (contains? join-names cell-name)]
              (if (keyword? edge-def)
                ;; Unconditional — use :unconditional as transition label
                (let [step (cond-> {:cell cell-name :transition :unconditional :target edge-def}
                             is-join? (assoc :join? true))]
                  (recur (conj (vec rest-queue)
                               [edge-def (conj path-so-far step) new-visited])
                         paths))
                ;; Map edges
                (let [next-items (mapv (fn [[transition target]]
                                         (let [step (cond-> {:cell cell-name :transition transition :target target}
                                                      is-join? (assoc :join? true))]
                                           [target (conj path-so-far step) new-visited]))
                                       edge-def)]
                  (recur (into (vec rest-queue) next-items)
                         paths))))))))))

(defn- dot-id
  "Converts a keyword name to a valid DOT identifier by quoting it."
  [kw]
  (str "\"" (name kw) "\""))

(defn workflow->dot
  "Generates a DOT graph string from a manifest for visualization.
   Join nodes are rendered as subgraph clusters containing their member cells."
  [{:keys [cells edges joins]}]
  (let [sb (StringBuilder.)
        joins-map   (or joins {})
        join-members (set (mapcat :cells (vals joins-map)))]
    (.append sb "digraph {\n")
    (.append sb "  rankdir=LR;\n")
    (.append sb "  node [shape=box];\n")
    ;; Add regular cell nodes (excluding join members)
    (doseq [[cell-name _cell-def] cells]
      (when-not (contains? join-members cell-name)
        (.append sb (str "  " (dot-id cell-name) ";\n"))))
    ;; Add join subgraph clusters
    (doseq [[join-name join-def] joins-map]
      (.append sb (str "  subgraph cluster_" (name join-name) " {\n"))
      (.append sb (str "    label=\"" (name join-name) " (join)\";\n"))
      (.append sb "    style=dashed;\n")
      (.append sb "    color=blue;\n")
      ;; The join node itself
      (.append sb (str "    " (dot-id join-name) " [shape=diamond];\n"))
      ;; Member cells inside the cluster
      (doseq [member (:cells join-def)]
        (.append sb (str "    " (dot-id member) " [shape=box style=filled fillcolor=lightyellow];\n"))
        (.append sb (str "    " (dot-id join-name) " -> " (dot-id member) " [style=dashed];\n")))
      (.append sb "  }\n"))
    ;; Terminal nodes
    (.append sb "  \"end\" [shape=doublecircle];\n")
    (.append sb "  \"error\" [shape=doubleoctagon color=red];\n")
    (.append sb "  \"halt\" [shape=octagon color=orange];\n")
    ;; Add edges
    (doseq [[from edge-def] edges]
      (if (keyword? edge-def)
        (.append sb (str "  " (dot-id from) " -> " (dot-id edge-def) ";\n"))
        (doseq [[label target] edge-def]
          (.append sb (str "  " (dot-id from) " -> " (dot-id target)
                       " [label=\"" (name label) "\"];\n")))))
    (.append sb "}\n")
    (str sb)))

(defn- generate-test-input
  "Generates test input data from a cell's input schema using Malli generators."
  [cell]
  (try
    (mg/generate (m/schema (get-in cell [:schema :input])) {:size 3 :seed 42})
    (catch Exception _
      {})))

(defn workflow-status
  "Reports implementation status across all cells in a manifest.
   Returns a map with :total, :implemented, :passing, :failing, :pending, :cells."
  [{:keys [cells]}]
  (let [cell-statuses
        (mapv (fn [[cell-name cell-def]]
                (let [cell-id (:id cell-def)
                      cell    (cell/get-cell cell-id)]
                  (if cell
                    (try
                      (let [input  (generate-test-input cell)
                            result (test-cell cell-id {:input     input
                                                       :resources {}})]
                        {:id     cell-id
                         :name   cell-name
                         :status (if (:pass? result) :passing :failing)
                         :errors (:errors result)})
                      (catch Exception e
                        {:id     cell-id
                         :name   cell-name
                         :status :failing
                         :error  (ex-message e)}))
                    {:id     cell-id
                     :name   cell-name
                     :status :pending})))
              cells)]
    {:total       (count cell-statuses)
     :implemented (count (filter #(not= :pending (:status %)) cell-statuses))
     :passing     (count (filter #(= :passing (:status %)) cell-statuses))
     :failing     (count (filter #(= :failing (:status %)) cell-statuses))
     :pending     (count (filter #(= :pending (:status %)) cell-statuses))
     :cells       cell-statuses}))

(defn analyze-workflow
  "Runs Maestro's static analysis on a workflow definition.
   Returns {:reachable #{...} :unreachable #{...} :no-path-to-end #{...} :cycles [...]}
   with state IDs reverse-resolved to cell name keywords for readability.
   Join-aware: join names appear as regular states, join members are excluded."
  [{:keys [cells edges dispatches joins] :as workflow-def}]
  (let [joins-map (or joins {})
        join-members (set (mapcat :cells (vals joins-map)))
        ;; Build state->names for reverse resolution
        state->names (merge
                      (into {}
                            (map (fn [[cell-name _]]
                                   [(wf/resolve-state-id cell-name) cell-name]))
                            cells)
                      (into {}
                            (map (fn [[join-name _]]
                                   [(wf/resolve-state-id join-name) join-name]))
                            joins-map))
        resolve-name (fn [sid] (get state->names sid sid))
        ;; Build the FSM spec (same as compile-workflow, minus interceptors)
        effective-dispatches (reduce (fn [acc [cell-name edge-def]]
                                      (if (and (map? edge-def) (not (get acc cell-name)))
                                        (let [cell-id (get cells cell-name)
                                              c (when cell-id (cell/get-cell cell-id))]
                                          (if-let [defaults (:default-dispatches c)]
                                            (assoc acc cell-name defaults)
                                            acc))
                                        acc))
                                    (or dispatches {})
                                    edges)
        ;; Add join default dispatches (filtered to edge keys)
        join-default-dispatches [[:failure (fn [d] (some? (:mycelium/join-error d)))]
                                 [:done    (fn [d] (not (:mycelium/join-error d)))]]
        effective-dispatches (reduce (fn [acc [join-name _]]
                                      (let [edge-def (get edges join-name)]
                                        (if (and (map? edge-def) (not (get acc join-name)))
                                          (let [edge-keys (set (keys edge-def))
                                                filtered (filterv (fn [[label _]]
                                                                    (contains? edge-keys label))
                                                                  join-default-dispatches)]
                                            (if (seq filtered)
                                              (assoc acc join-name filtered)
                                              acc))
                                          acc)))
                                    effective-dispatches
                                    joins-map)
        ;; Build FSM states for non-join-member cells
        fsm-cell-states (into {}
                              (keep (fn [[cell-name cell-id]]
                                      (when-not (contains? join-members cell-name)
                                        (let [c        (cell/get-cell! cell-id)
                                              state-id (wf/resolve-state-id cell-name)
                                              edge-def (get edges cell-name)
                                              dispatch-vec (get effective-dispatches cell-name)]
                                          [state-id
                                           (merge
                                            {:handler    (:handler c)
                                             :dispatches (wf/compile-edges edge-def dispatch-vec)}
                                            (when (:async? c) {:async? true}))]))))
                              cells)
        ;; Build FSM states for joins (synthetic handler)
        fsm-join-states (into {}
                              (map (fn [[join-name _join-def]]
                                     (let [state-id     (wf/resolve-state-id join-name)
                                           edge-def     (get edges join-name)
                                           dispatch-vec (get effective-dispatches join-name)]
                                       [state-id
                                        {:handler    (fn [_ data] data) ;; dummy for analysis
                                         :dispatches (wf/compile-edges edge-def dispatch-vec)}])))
                              joins-map)
        fsm-states (merge fsm-cell-states fsm-join-states)
        spec     {:fsm fsm-states}
        analysis (fsm/analyze spec)]
    {:reachable      (set (map resolve-name (:reachable analysis)))
     :unreachable    (set (map resolve-name (:unreachable analysis)))
     :no-path-to-end (set (map resolve-name (:no-path-to-end analysis)))
     :cycles         (mapv (fn [cycle] (mapv resolve-name cycle)) (:cycles analysis))}))

(defn- get-map-keys
  "Extracts top-level key names from a Malli :map schema. Returns #{} if not a :map schema."
  [schema]
  (if (and (vector? schema) (= :map (first schema)))
    (set (keep (fn [entry]
                 (when (vector? entry)
                   (first entry)))
               (rest schema)))
    #{}))

(defn- cell-output-keys
  "Returns the union of all output keys for a cell across all transitions."
  [cell-id]
  (let [cell   (cell/get-cell cell-id)
        output (when cell (get-in cell [:schema :output]))]
    (cond
      (nil? output)    #{}
      (vector? output) (get-map-keys output)
      (map? output)    (reduce (fn [acc [_ s]] (set/union acc (get-map-keys s))) #{} output)
      :else            #{})))

(defn- cell-input-keys
  "Returns the set of input keys for a cell."
  [cell-id]
  (let [cell (cell/get-cell cell-id)]
    (when cell
      (get-map-keys (get-in cell [:schema :input])))))

(defn- join-output-keys
  "Returns the union of output keys from all member cells of a join."
  [join-def cells-map]
  (reduce (fn [acc member]
            (let [cell-id (get cells-map member)]
              (set/union acc (cell-output-keys cell-id))))
          #{}
          (:cells join-def)))

(defn infer-workflow-schema
  "Walks a workflow definition and returns a map showing the accumulated schema
   at each cell. For each cell, reports:
     :available-before — keys available when the cell starts
     :adds            — keys this cell adds (output keys)
     :available-after  — keys available after the cell completes

   Handles branching by taking the union of available keys from all incoming paths.
   Join nodes: union of all member output keys."
  [{:keys [cells edges joins] :as _workflow-def}]
  (let [joins-map (or joins {})
        terminal? #{:end :error :halt}
        ;; Accumulate per-cell info via BFS
        result (atom {})
        ;; Track best available-before per cell (union across all incoming edges)
        available-before (atom {})
        ;; Start cell's input keys are the initial set
        start-cell-id (get cells :start)
        start-input (or (cell-input-keys start-cell-id) #{})
        queue (atom [[:start start-input]])]
    (swap! available-before assoc :start start-input)
    (loop []
      (when (seq @queue)
        (let [[cell-name avail] (first @queue)]
          (swap! queue #(vec (rest %)))
          (when-not (terminal? cell-name)
            ;; Merge incoming available keys
            (let [prev-avail (get @available-before cell-name #{})
                  merged-avail (set/union prev-avail avail)]
              (swap! available-before assoc cell-name merged-avail)
              ;; Compute output keys
              (let [adds (if-let [join-def (get joins-map cell-name)]
                           (join-output-keys join-def cells)
                           (let [cell-id (get cells cell-name)]
                             (cell-output-keys cell-id)))
                    after (set/union merged-avail adds)]
                ;; Record if first visit or if available-before expanded
                (let [prev-record (get @result cell-name)]
                  (when (or (nil? prev-record)
                            (not= merged-avail (:available-before prev-record)))
                    (swap! result assoc cell-name
                           {:available-before merged-avail
                            :adds            adds
                            :available-after  after})
                    ;; Traverse edges
                    (let [edge-def (get edges cell-name)]
                      (if (keyword? edge-def)
                        (swap! queue conj [edge-def after])
                        (when (map? edge-def)
                          (doseq [[_ target] edge-def]
                            (swap! queue conj [target after])))))))))))
        (recur)))
    @result))
