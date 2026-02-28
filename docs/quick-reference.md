# Mycelium Quick Reference

## Core API

```clojure
(require '[mycelium.core :as myc])

;; Pre-compile once at startup (recommended for production)
(def compiled (myc/pre-compile workflow-def opts))

;; Run a pre-compiled workflow (zero compilation overhead)
(myc/run-compiled compiled resources initial-data)
(myc/run-compiled-async compiled resources initial-data)  ;; returns a future

;; Convenience: compile + run in one step (re-compiles every call)
(myc/run-workflow workflow-def resources initial-data)
(myc/run-workflow workflow-def resources initial-data opts)

;; Async convenience (returns a future)
(myc/run-workflow-async workflow-def resources initial-data)

;; Compile a system (all workflows)
(myc/compile-system {"/route" manifest, ...})
```

## Cell Registration

```clojure
(require '[mycelium.cell :as cell])

(defmethod cell/cell-spec :namespace/cell-id [_]
  {:id       :namespace/cell-id
   :handler  (fn [resources data] (assoc data :result "value"))
   :schema   {:input  [:map [:key :type]]
              :output [:map [:result :string]]}
   :requires [:db]         ;; optional
   :async?   true          ;; optional
   :doc      "..."})       ;; optional
```

## Workflow Definition

```clojure
{:cells       {:start :cell/id, :step2 :cell/id2}
 :edges       {:start {:label :step2}, :step2 {:done :end}}
 :dispatches  {:start [[:label (fn [data] (:key data))]]
               :step2 [[:done (constantly true)]]}
 :joins       {:join-name {:cells [:a :b] :strategy :parallel}}  ;; optional
 :input-schema [:map [:key :type]]                                ;; optional
 :interceptors [{:id :x :scope :all :pre (fn [d] d)}]}           ;; optional
```

### Pipeline Shorthand (Manifests)

```clojure
;; Instead of :edges + :dispatches for linear flows:
{:id :my-pipeline
 :pipeline [:start :process :render]
 :cells {:start   {...} :process {...} :render {...}}}
;; Expands to :edges {:start :process, :process :render, :render :end}
;; Mutually exclusive with :edges, :dispatches, :fragments, :joins
```

## Manifest Loading

```clojure
(require '[mycelium.manifest :as manifest])

(def m (manifest/load-manifest "path/to/file.edn"))
(def wf (manifest/manifest->workflow m))
(manifest/cell-brief m :cell-name)  ;; LLM-friendly prompt
```

## Fragment API

```clojure
(require '[mycelium.fragment :as fragment])

(fragment/load-fragment "fragments/auth.edn")              ;; load from classpath
(fragment/validate-fragment fragment-data)                  ;; validate structure
(fragment/expand-fragment frag mapping host-cells)          ;; expand one
(fragment/expand-all-fragments manifest)                    ;; expand all
```

## Dev Tools

```clojure
(require '[mycelium.dev :as dev])

(dev/test-cell :cell/id {:input {...} :resources {...}})
(dev/test-transitions :cell/id {:path1 {...} :path2 {...}})
(dev/enumerate-paths manifest)
(dev/workflow->dot manifest)
(dev/workflow-status manifest)
```

## System Queries

```clojure
(require '[mycelium.system :as sys])

(sys/cell-usage system :cell/id)        ;; => ["/route1" "/route2"]
(sys/route-cells system "/route")       ;; => #{:cell/a :cell/b}
(sys/route-resources system "/route")   ;; => #{:db}
(sys/schema-conflicts system)           ;; => [{:cell-id ... :routes ...}]
(sys/system->dot system)                ;; => DOT graph string
```

## Subworkflows (Nested Composition)

Wrap a workflow as a single opaque cell. See [subworkflows.md](subworkflows.md).

```clojure
(require '[mycelium.compose :as compose])

;; Register a workflow as a reusable cell
(compose/register-workflow-cell!
  :payment/flow
  {:cells {...} :edges {...} :dispatches {...}}
  {:input [:map ...] :output [:map ...]})

;; Or create spec without registering
(compose/workflow->cell :payment/flow workflow-def schema)
```

Default dispatches `:success` / `:failure` are provided automatically based on `:mycelium/error`.

## Edge Targets

| Target | Meaning |
|--------|---------|
| `:cell-name` | Next cell in workflow |
| `:join-name` | Enter a join node |
| `:end` | Workflow completes successfully |
| `:error` | Workflow terminates with error |
| `:halt` | Workflow halts |
| `:_exit/name` | Fragment exit reference (resolved during expansion) |

## Output Schema Formats

```clojure
;; Single (all transitions)
:output [:map [:result :int]]

;; Per-transition
:output {:success [:map [:data :string]]
         :failure [:map [:error :string]]}
```

## Ring Middleware

```clojure
(require '[mycelium.middleware :as mw])

;; Create a Ring handler from a pre-compiled workflow
(mw/workflow-handler compiled {:resources {:db db}})

;; With custom input/output transforms
(mw/workflow-handler compiled
  {:resources {:db db}
   :input-fn  (fn [req] {:http-request req})   ;; default
   :output-fn mw/html-response})               ;; default

;; Standard HTML response helper
(mw/html-response result)  ;; => {:status 200 :body (:html result) ...}
```

## Manifest Cell Required Fields

```clojure
{:id       :namespace/name    ;; cell registry ID
 :schema   {:input  [...]     ;; Malli schema
            :output [...]}    ;; single or per-transition — or :inherit
 :on-error :cell-name}        ;; or nil — required in strict mode (default)
```

## Workflow Result Keys

| Key | Description |
|-----|-------------|
| `:mycelium/trace` | Vector of execution trace entries |
| `:mycelium/input-error` | Input schema validation failure (workflow didn't run) |
| `:mycelium/schema-error` | Runtime schema violation details |
| `:mycelium/join-error` | Join node error details |
| `:mycelium/child-trace` | Nested workflow trace (composed cells) |
