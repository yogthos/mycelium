(ns mycelium.orchestrate
  "Agent orchestration: brief generation, reassignment, planning, progress."
  (:require [clojure.string :as str]
            [mycelium.manifest :as manifest]
            [mycelium.dev :as dev]))

(defn cell-briefs
  "Returns a map of cell-name → brief for every cell in the manifest."
  [manifest-data]
  (into {}
        (map (fn [[cell-name _]]
               [cell-name (manifest/cell-brief manifest-data cell-name)]))
        (:cells manifest-data)))

(defn reassignment-brief
  "Generates a targeted brief that includes failure context.
   `error-context` should be {:error \"...\", :input {...}, :output {...}}"
  [manifest-data cell-name {:keys [error input output]}]
  (let [base-brief (manifest/cell-brief manifest-data cell-name)
        error-section (str "\n\n## Previous Implementation Failed\n\n"
                          "Error: " error "\n\n"
                          "Given input: " (pr-str input) "\n\n"
                          "Your handler returned: " (pr-str output) "\n\n"
                          "Fix the handler to satisfy the output schema.\n")]
    (assoc base-brief
           :prompt (str (:prompt base-brief) error-section))))

(defn plan
  "Generates an execution plan from a manifest.
   Identifies which cells can be built in parallel."
  [{:keys [cells edges] :as manifest-data}]
  (let [cell-names (vec (keys cells))
        ;; In the common case, all cells are independent (each can be developed in isolation)
        ;; They only have runtime data dependencies, not development dependencies
        parallel-groups [cell-names]]
    {:scaffold   cell-names
     :parallel   parallel-groups
     :sequential []}))

(defn progress
  "Generates a human-readable progress report string."
  [{:keys [id] :as manifest-data}]
  (let [status (dev/workflow-status manifest-data)
        {:keys [total implemented passing failing pending cells]} status]
    (str "Workflow: " id "\n"
         "Status: " passing "/" total " cells passing"
         " | " total " total | " implemented " implemented | " pending " pending\n\n"
         (str/join "\n"
                   (map (fn [{:keys [id name status error errors]}]
                          (let [tag (case status
                                     :passing "[PASS]"
                                     :failing "[FAIL]"
                                     :pending "[    ]")]
                            (str tag " " name " (" id ")"
                                 (when (= status :failing)
                                   (str " — " (or error
                                                  (some-> errors first :detail :errors str)))))))
                        cells))
         "\n")))
