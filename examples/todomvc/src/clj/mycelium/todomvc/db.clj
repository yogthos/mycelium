(ns mycelium.todomvc.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private query-opts {:builder-fn rs/as-unqualified-lower-maps})

(defn list-todos
  "Returns todos filtered by status. filter is \"all\", \"active\", or \"completed\"."
  [ds filter-str]
  (case filter-str
    "active"    (jdbc/execute! ds
                  ["SELECT id, title, completed, created_at FROM todos WHERE completed = 0 ORDER BY id"]
                  query-opts)
    "completed" (jdbc/execute! ds
                  ["SELECT id, title, completed, created_at FROM todos WHERE completed = 1 ORDER BY id"]
                  query-opts)
    ;; default: all
    (jdbc/execute! ds
      ["SELECT id, title, completed, created_at FROM todos ORDER BY id"]
      query-opts)))

(defn count-stats
  "Returns {:active n :completed n :total n}."
  [ds]
  (let [rows (jdbc/execute! ds
               ["SELECT completed, COUNT(*) as cnt FROM todos GROUP BY completed"]
               query-opts)
        by-status (into {} (map (fn [r] [(:completed r) (:cnt r)])) rows)
        active    (get by-status 0 0)
        completed (get by-status 1 0)]
    {:active    active
     :completed completed
     :total     (+ active completed)}))

(defn create-todo!
  "Inserts a new todo. Returns {:id n}."
  [ds title]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute-one! tx
      ["INSERT INTO todos (title) VALUES (?)" title])
    (let [result (jdbc/execute-one! tx
                   ["SELECT last_insert_rowid() as id"]
                   query-opts)]
      {:id (:id result)})))

(defn toggle-todo!
  "Flips the completed status of a todo."
  [ds id]
  (jdbc/execute-one! ds
    ["UPDATE todos SET completed = CASE WHEN completed = 0 THEN 1 ELSE 0 END WHERE id = ?" id]))

(defn update-todo-title!
  "Updates the title of a todo."
  [ds id title]
  (jdbc/execute-one! ds
    ["UPDATE todos SET title = ? WHERE id = ?" title id]))

(defn delete-todo!
  "Deletes a todo by id."
  [ds id]
  (jdbc/execute-one! ds
    ["DELETE FROM todos WHERE id = ?" id]))

(defn toggle-all!
  "If any are incomplete, mark all complete. Otherwise mark all incomplete."
  [ds]
  (let [stats (count-stats ds)]
    (if (pos? (:active stats))
      (jdbc/execute-one! ds ["UPDATE todos SET completed = 1"])
      (jdbc/execute-one! ds ["UPDATE todos SET completed = 0"]))))

(defn clear-completed!
  "Deletes all completed todos."
  [ds]
  (jdbc/execute-one! ds ["DELETE FROM todos WHERE completed = 1"]))
