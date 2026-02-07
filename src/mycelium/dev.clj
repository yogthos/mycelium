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
     :input     - input data map
     :resources - resources map (default {})
   Returns:
     {:pass? bool :errors [...] :output {...} :duration-ms n}"
  [cell-id {:keys [input resources] :or {resources {}}}]
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
              output-err  (schema/validate-output cell output)]
          (if output-err
            {:pass?       false
             :errors      [{:phase :output :detail output-err}]
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
