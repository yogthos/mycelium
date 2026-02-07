(ns mycelium.core
  "Public API for the Mycelium framework.
   Re-exports key functions from internal namespaces."
  (:require [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [mycelium.workflow :as workflow]
            [mycelium.compose :as compose]
            [mycelium.manifest :as manifest]
            [maestro.core :as fsm]))

;; --- Cell registry ---

(defmacro defcell
  "Registers a cell and defines its handler (without schema).
   Schema is provided by the manifest via `set-cell-schema!`.
   See mycelium.cell/defcell."
  [id opts bindings & body]
  `(cell/defcell ~id ~opts ~bindings ~@body))

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
   (let [compiled (compile-workflow workflow-def opts)]
     (fsm/run compiled resources {:data initial-data}))))

;; --- Dev tools ---

(def test-transitions
  "Tests a cell across multiple transitions.
   See mycelium.dev/test-transitions."
  dev/test-transitions)

(def enumerate-paths
  "Enumerates all paths from :start to terminal states.
   See mycelium.dev/enumerate-paths."
  dev/enumerate-paths)
