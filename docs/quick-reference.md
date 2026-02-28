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

## Compose (Nested Workflows)

```clojure
(require '[mycelium.compose :as compose])

(compose/register-workflow-cell!
  :auth/flow
  {:cells {...} :edges {...} :dispatches {...}}
  {:input [:map ...] :output [:map ...]})
```

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

## Manifest Cell Required Fields

```clojure
{:id       :namespace/name    ;; cell registry ID
 :schema   {:input  [...]     ;; Malli schema
            :output [...]}    ;; single or per-transition
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
