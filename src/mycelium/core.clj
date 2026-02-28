(ns mycelium.core
  "Public API for the Mycelium framework.
   Re-exports key functions from internal namespaces."
  (:require [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [mycelium.workflow :as workflow]
            [mycelium.compose :as compose]
            [mycelium.manifest :as manifest]
            [mycelium.system :as sys]
            [mycelium.validation :as v]
            [malli.core :as m]
            [maestro.core :as fsm]))

;; --- Cell registry ---

(def cell-spec
  "Multimethod for cell registration. Use (defmethod myc/cell-spec :id [_] {...}).
   See mycelium.cell/cell-spec."
  cell/cell-spec)

;; --- Workflow compilation ---

(def compile-workflow
  "Compiles a workflow definition into a Maestro FSM.
   See mycelium.workflow/compile-workflow."
  workflow/compile-workflow)

;; --- Composition ---

(def workflow->cell
  "Wraps a workflow as a cell for hierarchical composition.
   See mycelium.compose/workflow->cell."
  compose/workflow->cell)

;; --- Manifest ---

(def load-manifest
  "Loads and validates a manifest from an EDN file.
   See mycelium.manifest/load-manifest."
  manifest/load-manifest)

(def cell-brief
  "Generates a self-contained brief for a cell from a manifest.
   See mycelium.manifest/cell-brief."
  manifest/cell-brief)

;; --- Input schema validation ---

(defn- validate-input-schema
  "Validates initial-data against workflow's :input-schema.
   Returns nil if valid or no schema, or an error map on failure."
  [workflow-def initial-data]
  (when-let [input-schema (:input-schema workflow-def)]
    (v/validate-malli-schema! input-schema "input-schema")
    (let [schema (m/schema input-schema)
          explanation (m/explain schema initial-data)]
      (when explanation
        {:schema input-schema
         :errors (:errors explanation)
         :data   initial-data}))))

;; --- Execution ---

(defn run-workflow
  "Convenience function: compiles and runs a workflow in one step.
   Returns the final data map.
   opts — optional map passed to compile-workflow:
     :pre  — additional pre-interceptor (fn [fsm-state resources] -> fsm-state)
     :post — additional post-interceptor (fn [fsm-state resources] -> fsm-state)
     :on-error — custom error handler
     :on-end   — custom end handler"
  ([workflow-def]
   (run-workflow workflow-def {} {} {}))
  ([workflow-def resources]
   (run-workflow workflow-def resources {} {}))
  ([workflow-def resources initial-data]
   (run-workflow workflow-def resources initial-data {}))
  ([workflow-def resources initial-data opts]
   (if-let [input-error (validate-input-schema workflow-def initial-data)]
     {:mycelium/input-error input-error}
     (let [compiled (compile-workflow workflow-def opts)]
       (fsm/run compiled resources {:data initial-data})))))

(defn run-workflow-async
  "Like run-workflow but returns a future. Deref to get the final data map."
  ([workflow-def]
   (run-workflow-async workflow-def {} {} {}))
  ([workflow-def resources]
   (run-workflow-async workflow-def resources {} {}))
  ([workflow-def resources initial-data]
   (run-workflow-async workflow-def resources initial-data {}))
  ([workflow-def resources initial-data opts]
   (if-let [input-error (validate-input-schema workflow-def initial-data)]
     (future {:mycelium/input-error input-error})
     (let [compiled (compile-workflow workflow-def opts)]
       (fsm/run-async compiled resources {:data initial-data})))))

;; --- System compilation ---

(def compile-system
  "Compiles a system from a route→manifest map for bird's-eye view.
   See mycelium.system/compile-system."
  sys/compile-system)

;; --- Dev tools ---

(def test-transitions
  "Tests a cell across multiple transitions.
   See mycelium.dev/test-transitions."
  dev/test-transitions)

(def enumerate-paths
  "Enumerates all paths from :start to terminal states.
   See mycelium.dev/enumerate-paths."
  dev/enumerate-paths)

(def analyze-workflow
  "Runs Maestro's static analysis on a workflow definition.
   See mycelium.dev/analyze-workflow."
  dev/analyze-workflow)
