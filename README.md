# Mycelium

Schema-enforced, composable workflow components for Clojure. Built on [Maestro](https://github.com/yogthos/maestro).

Mycelium structures applications as directed graphs of pure data transformations. Each node (cell) has explicit input/output schemas and declared transitions. Cells are developed and tested in complete isolation, then composed into workflows that are validated at compile time.

## Why

LLM coding agents fail at large codebases for the same reason humans do: unbounded context. Mycelium solves this by constraining each component to a fixed scope with an explicit contract. An agent implementing a cell never needs to see the rest of the application — just its schema and test harness.

## Quick Start

```clojure
;; deps.edn
{:deps {io.github.yogthos/mycelium {:git/url "https://github.com/yogthos/mycelium"
                                     :git/sha "..."}}}
```

### Define Cells

A cell is a pure function with schema contracts:

```clojure
(require '[mycelium.core :as myc])

(myc/defcell :math/double
  {:schema      {:input  [:map [:x :int]]
                 :output [:map [:result :int]]}
   :transitions #{:done}}
  [_resources data]
  (assoc data :result (* 2 (:x data)) :mycelium/transition :done))

(myc/defcell :math/add-ten
  {:schema      {:input  [:map [:result :int]]
                 :output [:map [:result :int]]}
   :transitions #{:done}}
  [_resources data]
  (assoc data :result (+ 10 (:result data)) :mycelium/transition :done))
```

Cells must:
- Return the data map with `:mycelium/transition` set to one of their declared transitions
- Produce output satisfying their `:output` schema on every path
- Only use resources passed via the first argument

### Compose into Workflows

```clojure
(let [result (myc/run-workflow
               {:cells {:start  :math/double
                        :add    :math/add-ten}
                :edges {:start {:done :add}
                        :add   {:done :end}}}
               {}          ;; resources
               {:x 5})]    ;; initial data
  (:result result))
;; => 20
```

### Branching

Edges map transition keywords to targets:

```clojure
(myc/defcell :check/threshold
  {:schema      {:input  [:map [:value :int]]
                 :output [:map]}
   :transitions #{:high :low}}
  [_ data]
  (assoc data :mycelium/transition
         (if (> (:value data) 10) :high :low)))

(myc/run-workflow
  {:cells {:start :check/threshold
           :big   :process/big-values
           :small :process/small-values}
   :edges {:start {:high :big, :low :small}
           :big   {:done :end}
           :small {:done :end}}}
  {} {:value 42})
```

### Per-Transition Output Schemas

Cells with multiple transitions can declare different output schemas for each transition:

```clojure
(myc/defcell :user/fetch
  {:transitions #{:found :not-found}
   :requires [:db]}
  [{:keys [db]} data]
  (if-let [profile (get-user db (:user-id data))]
    (assoc data :profile profile :mycelium/transition :found)
    (assoc data :error-message "Not found" :mycelium/transition :not-found)))
```

In the manifest, use a map instead of a vector for `:output`:

```clojure
;; Single schema (all transitions must satisfy it)
:output [:map [:profile map?]]

;; Per-transition schemas (each transition has its own contract)
:output {:found     [:map [:profile [:map [:name :string] [:email :string]]]]
         :not-found [:map [:error-message :string]]}
```

The schema chain validator tracks which keys are available on each path independently, so a downstream cell on the `:found` path can require `:profile` without the `:not-found` path needing to produce it.

### Resources

External dependencies are injected, never acquired by cells:

```clojure
(myc/defcell :user/fetch
  {:transitions #{:found :not-found}
   :requires [:db]}
  [{:keys [db]} data]
  (if-let [profile (get-user db (:user-id data))]
    (assoc data :profile profile :mycelium/transition :found)
    (assoc data :error-message "Not found" :mycelium/transition :not-found)))

;; Resources are passed at run time
(myc/run-workflow workflow {:db my-db-conn} {:user-id "alice"})
```

### Async Cells

```clojure
(myc/defcell :api/fetch-data
  {:schema      {:input  [:map [:url :string]]
                 :output [:map [:response map?]]}
   :transitions #{:ok :error}
   :async?      true}
  [_resources data callback error-callback]
  (future
    (try
      (let [resp (http/get (:url data))]
        (callback (assoc data :response resp :mycelium/transition :ok)))
      (catch Exception e
        (error-callback e)))))
```

## Compile-Time Validation

`compile-workflow` validates before any code runs:

- **Cell existence** — all referenced cells must be registered
- **Edge targets** — all targets must point to valid cells or `:end`/`:error`/`:halt`
- **Reachability** — every cell must be reachable from `:start`
- **Transition coverage** — every declared transition must have a corresponding edge
- **Dead edge detection** — edge keys must match declared transitions
- **Schema chain** — each cell's input keys must be available from upstream outputs

```
Schema chain error: :user/fetch-profile at :fetch-profile requires keys #{:user-id}
but only #{:http-request} available
```

## Runtime Schema Enforcement

Pre and post interceptors validate every transition automatically:

- **Pre**: validates input data against the cell's `:input` schema before the handler runs
- **Post**: validates output data against `:output` schema and checks `:mycelium/transition` is declared

Schema violations redirect to the error state with detailed diagnostics attached at `:mycelium/schema-error`.

## Hierarchical Composition

Workflows can be nested as cells in parent workflows:

```clojure
(require '[mycelium.compose :as compose])

;; Wrap a child workflow as a cell
(compose/register-workflow-cell!
  :auth/flow
  {:cells {:start :auth/parse, :validate :auth/check}
   :edges {:start {:ok :validate, :fail :error}
           :validate {:ok :end, :fail :error}}}
  {:input  [:map [:http-request map?]]
   :output [:map [:user-id :string]]})

;; Use it in a parent workflow
(myc/run-workflow
  {:cells {:start :auth/flow
           :main  :app/dashboard}
   :edges {:start {:success :main, :failure :error}
           :main  {:done :end}}}
  resources initial-data)
```

Child workflows produce `:success` or `:failure` transitions. On failure, `:mycelium/error` is attached to the data. Child execution traces are preserved at `:mycelium/child-trace`.

## Manifest System

Define workflows as pure data in `.edn` files:

```clojure
;; workflows/user-onboarding.edn
{:id :user-onboarding
 :cells {:start {:id :auth/parse-request
                 :doc "Extract credentials from HTTP request"
                 :schema {:input  [:map [:http-request map?]]
                          :output [:map [:user-id :string] [:auth-token :string]]}
                 :transitions #{:success :failure}}
         :validate {:id :auth/validate-session
                    :doc "Check credentials"
                    :schema {:input  [:map [:user-id :string] [:auth-token :string]]
                             :output [:map [:session-valid :boolean]]}
                    :requires [:db]
                    :transitions #{:authorized :unauthorized}}}
 :edges {:start    {:success :validate, :failure :error}
         :validate {:authorized :end, :unauthorized :error}}}
```

Load and validate:

```clojure
(require '[mycelium.manifest :as manifest])

(def m (manifest/load-manifest "workflows/user-onboarding.edn"))
```

Generate a cell brief for an agent:

```clojure
(manifest/cell-brief m :start)
;; => {:id :auth/parse-request
;;     :prompt "## Cell: :auth/parse-request\n..."
;;     :examples {:input {...} :output {...}}
;;     ...}
```

Convert to a compilable workflow (registers stub handlers for unimplemented cells):

```clojure
(def workflow-def (manifest/manifest->workflow m))
```

## Dev Tooling

### Test a Cell in Isolation

```clojure
(require '[mycelium.dev :as dev])

(dev/test-cell :auth/parse-request
  {:input     {:http-request {:headers {} :body {"username" "alice"}}}
   :resources {}})
;; => {:pass? true, :output {...}, :errors [], :duration-ms 0.42}

;; Verify expected transition
(dev/test-cell :auth/parse-request
  {:input               {:http-request {:headers {} :body {}}}
   :expected-transition :failure})
;; => {:pass? true, ...} if handler returns :failure
```

### Test Multiple Transitions

```clojure
(dev/test-transitions :user/fetch-profile
  {:found     {:input {:user-id "alice" :session-valid true}
               :resources {:db my-db}}
   :not-found {:input {:user-id "nobody" :session-valid true}
               :resources {:db my-db}}})
;; => {:found     {:pass? true, :output {...}}
;;     :not-found {:pass? true, :output {...}}}
```

### Enumerate Workflow Paths

```clojure
(dev/enumerate-paths manifest)
;; => [[{:cell :start, :transition :success, :target :validate}
;;      {:cell :validate, :transition :authorized, :target :end}]
;;     [{:cell :start, :transition :failure, :target :error}]
;;     ...]
```

### Visualize a Workflow

```clojure
(dev/workflow->dot manifest)
;; => "digraph { ... }" — pipe to `dot -Tpng` to render
```

### Check Implementation Status

```clojure
(dev/workflow-status manifest)
;; => {:total 4, :passing 2, :failing 1, :pending 1, :cells [...]}
```

## Agent Orchestration

```clojure
(require '[mycelium.orchestrate :as orch])

;; Generate briefs for all cells
(orch/cell-briefs manifest)

;; Generate a targeted brief after failure
(orch/reassignment-brief manifest :validate
  {:error "Output missing key :session-valid"
   :input {:user-id "alice" :auth-token "tok_abc"}
   :output {:mycelium/transition :authorized}})

;; Execution plan
(orch/plan manifest)
;; => {:scaffold [:start :validate ...], :parallel [[...]], :sequential []}

;; Progress report
(println (orch/progress manifest))
```

## Architecture

```
mycelium/
├── src/mycelium/
│   ├── cell.clj          ;; Cell registry, defcell macro
│   ├── schema.clj        ;; Malli pre/post interceptors
│   ├── workflow.clj       ;; DSL → Maestro compiler
│   ├── compose.clj        ;; Hierarchical workflow nesting
│   ├── manifest.clj       ;; EDN manifest loading, cell briefs
│   ├── dev.clj            ;; Testing harness, visualization
│   ├── orchestrate.clj    ;; Agent orchestration helpers
│   └── core.clj           ;; Public API
└── test/mycelium/         ;; 97 tests, 188 assertions
```

## License

Copyright (c) Dmitri Sotnikov. All rights reserved.
