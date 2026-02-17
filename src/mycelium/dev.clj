(ns mycelium.dev
  "Development utilities: cell testing, scaffolding, visualization."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]))

(defn test-cell
  "Runs a single cell in isolation with full schema validation.
   Options:
     :input               - input data map
     :resources           - resources map (default {})
     :expected-transition  - if set, verifies the handler returned this transition
   Returns:
     {:pass? bool :errors [...] :output {...} :duration-ms n}"
  [cell-id {:keys [input resources expected-transition] :or {resources {}}}]
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
              output-err  (schema/validate-output cell output)
              actual-trans (:mycelium/transition output)
              trans-err   (when (and expected-transition
                                     (not= expected-transition actual-trans))
                            {:phase :transition
                             :detail (str "Expected transition " expected-transition
                                          " but got " actual-trans)})
              all-errors  (cond-> []
                            output-err (conj {:phase :output :detail output-err})
                            trans-err  (conj trans-err))]
          (if (seq all-errors)
            {:pass?       false
             :errors      all-errors
             :output      output
             :duration-ms duration-ms}
            {:pass?       true
             :errors      []
             :output      output
             :duration-ms duration-ms}))
        (catch Exception e
          {:pass?       false
           :errors      [{:phase :handler :detail (ex-message e)}]
           :output      nil
           :duration-ms (/ (- (System/nanoTime) start-time) 1e6)})))))

(defn test-transitions
  "Tests a cell across multiple transitions.
   cases: {transition-kw {:input ... :resources ...}}
   Returns: {transition-kw test-result}"
  [cell-id cases]
  (into {}
        (map (fn [[transition opts]]
               [transition (test-cell cell-id (assoc opts :expected-transition transition))]))
        cases))

(defn enumerate-paths
  "Enumerates all paths from :start to terminal states in a manifest.
   Returns seq of paths, each a vector of {:cell :transition :target}."
  [{:keys [cells edges]}]
  (let [terminal? #{:end :error :halt}]
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
                  new-visited (conj visited cell-name)]
              (if (keyword? edge-def)
                ;; Unconditional — use :unconditional as transition label
                (let [step {:cell cell-name :transition :unconditional :target edge-def}]
                  (recur (conj (vec rest-queue)
                               [edge-def (conj path-so-far step) new-visited])
                         paths))
                ;; Map edges
                (let [next-items (mapv (fn [[transition target]]
                                         (let [step {:cell cell-name :transition transition :target target}]
                                           [target (conj path-so-far step) new-visited]))
                                       edge-def)]
                  (recur (into (vec rest-queue) next-items)
                         paths))))))))))

(defn- dot-id
  "Converts a keyword name to a valid DOT identifier by quoting it."
  [kw]
  (str "\"" (name kw) "\""))

(defn workflow->dot
  "Generates a DOT graph string from a manifest for visualization."
  [{:keys [cells edges]}]
  (let [sb (StringBuilder.)]
    (.append sb "digraph {\n")
    (.append sb "  rankdir=LR;\n")
    (.append sb "  node [shape=box];\n")
    ;; Add nodes
    (doseq [[cell-name _cell-def] cells]
      (.append sb (str "  " (dot-id cell-name) ";\n")))
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
  [{:keys [cells] :as manifest}]
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
