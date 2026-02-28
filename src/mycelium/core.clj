(ns mycelium.core
  "Public API for the Mycelium framework.
   Re-exports key functions from internal namespaces."
  (:require [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [mycelium.workflow :as workflow]
            [mycelium.compose :as compose]
            [mycelium.manifest :as manifest]
            [mycelium.middleware :as mw]
            [mycelium.system :as sys]
            [malli.core :as m]
            [maestro.core :as fsm]
            [promesa.core :as p]))

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

;; --- Pre-compilation ---

(defn pre-compile
  "Pre-compiles a workflow definition for repeated execution.
   Returns a compiled workflow map that can be passed to `run-compiled`.
   Performs all validation and compilation at call time so that
   `run-compiled` does zero compilation work per request.

   opts — optional map passed to compile-workflow:
     :pre  — additional pre-interceptor (fn [fsm-state resources] -> fsm-state)
     :post — additional post-interceptor (fn [fsm-state resources] -> fsm-state)
     :on-error — custom error handler
     :on-end   — custom end handler"
  ([workflow-def] (pre-compile workflow-def {}))
  ([workflow-def opts]
   (let [compiled-fsm (compile-workflow workflow-def opts)
         input-schema-raw (:input-schema workflow-def)
         input-schema-compiled (when input-schema-raw
                                 (m/schema input-schema-raw))]
     {:compiled-fsm         compiled-fsm
      :input-schema-raw     input-schema-raw
      :input-schema-compiled input-schema-compiled})))

(defn- check-input-schema
  "Validates initial-data against a pre-compiled input schema.
   Returns nil if valid or no schema, error map on failure."
  [{:keys [input-schema-raw input-schema-compiled]} initial-data]
  (when input-schema-compiled
    (when-let [explanation (m/explain input-schema-compiled initial-data)]
      {:schema input-schema-raw
       :errors (:errors explanation)
       :data   initial-data})))

;; --- Execution ---

(defn- deref-if-promise
  "Derefs a Promesa promise to a plain value; returns non-promises as-is."
  [result]
  (if (p/promise? result) @result result))

(defn run-compiled
  "Runs a pre-compiled workflow. Zero compilation overhead per call.
   Use `pre-compile` to create the compiled workflow at startup."
  ([compiled-workflow resources initial-data]
   (if-let [input-error (check-input-schema compiled-workflow initial-data)]
     {:mycelium/input-error input-error}
     (deref-if-promise
      (fsm/run (:compiled-fsm compiled-workflow) resources {:data initial-data}))))
  ([compiled-workflow resources initial-data opts]
   (if-let [input-error (check-input-schema compiled-workflow initial-data)]
     {:mycelium/input-error input-error}
     (deref-if-promise
      (fsm/run (:compiled-fsm compiled-workflow) resources {:data initial-data})))))

(defn run-compiled-async
  "Like run-compiled but returns a future."
  ([compiled-workflow resources initial-data]
   (if-let [input-error (check-input-schema compiled-workflow initial-data)]
     (future {:mycelium/input-error input-error})
     (fsm/run-async (:compiled-fsm compiled-workflow) resources {:data initial-data})))
  ([compiled-workflow resources initial-data opts]
   (if-let [input-error (check-input-schema compiled-workflow initial-data)]
     (future {:mycelium/input-error input-error})
     (fsm/run-async (:compiled-fsm compiled-workflow) resources {:data initial-data}))))

(defn run-workflow
  "Convenience function: compiles and runs a workflow in one step.
   For repeated execution of the same workflow, use `pre-compile` + `run-compiled` instead.
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
   (run-compiled (pre-compile workflow-def opts) resources initial-data)))

(defn run-workflow-async
  "Like run-workflow but returns a future. Deref to get the final data map.
   For repeated execution, use `pre-compile` + `run-compiled-async` instead."
  ([workflow-def]
   (run-workflow-async workflow-def {} {} {}))
  ([workflow-def resources]
   (run-workflow-async workflow-def resources {} {}))
  ([workflow-def resources initial-data]
   (run-workflow-async workflow-def resources initial-data {}))
  ([workflow-def resources initial-data opts]
   (run-compiled-async (pre-compile workflow-def opts) resources initial-data)))

;; --- Middleware ---

(def workflow-handler
  "Creates a Ring handler from a pre-compiled workflow.
   See mycelium.middleware/workflow-handler."
  mw/workflow-handler)

(def html-response
  "Standard HTML response from workflow result.
   See mycelium.middleware/html-response."
  mw/html-response)

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

(def infer-workflow-schema
  "Walks a workflow and reports accumulated schema keys at each cell.
   See mycelium.dev/infer-workflow-schema."
  dev/infer-workflow-schema)
