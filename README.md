# Mycelium

[![Tests](https://github.com/yogthos/mycelium/actions/workflows/test.yml/badge.svg)](https://github.com/yogthos/mycelium/actions/workflows/test.yml)

Schema-enforced, composable workflow components for Clojure. Built on [Maestro](https://github.com/yogthos/maestro).

Mycelium structures applications as directed graphs of pure data transformations. Each node (cell) has explicit input/output schemas. Cells are developed and tested in complete isolation, then composed into workflows that are validated at compile time. Routing between cells is determined by dispatch predicates defined at the workflow level — handlers compute data, the graph decides where it goes.

## Why

LLM coding agents fail at large codebases for the same reason humans do: unbounded context. Mycelium solves this by constraining each component to a fixed scope with an explicit contract. An agent implementing a cell never needs to see the rest of the application — just its schema and test harness.

## Quick Start

```clojure
;; deps.edn
{:deps {io.github.yogthos/mycelium {:git/url "https://github.com/yogthos/mycelium"
                                     :git/sha "..."}}}
```

### Define Cells

A cell is a pure function with schema contracts, registered via `defmethod`:

```clojure
(require '[mycelium.cell :as cell])

(defmethod cell/cell-spec :math/double [_]
  {:id          :math/double
   :handler     (fn [_resources data]
                  (assoc data :result (* 2 (:x data))))
   :schema      {:input  [:map [:x :int]]
                 :output [:map [:result :int]]}})

(defmethod cell/cell-spec :math/add-ten [_]
  {:id          :math/add-ten
   :handler     (fn [_resources data]
                  (assoc data :result (+ 10 (:result data))))
   :schema      {:input  [:map [:result :int]]
                 :output [:map [:result :int]]}})
```

Cells must:
- Return the data map with any computed values added
- Produce output satisfying their `:output` schema on every path
- Only use resources passed via the first argument

### Compose into Workflows

```clojure
(require '[mycelium.core :as myc])

(let [result (myc/run-workflow
               {:cells {:start  :math/double
                        :add    :math/add-ten}
                :edges {:start {:done :add}
                        :add   {:done :end}}
                :dispatches {:start [[:done (constantly true)]]
                             :add   [[:done (constantly true)]]}}
               {}          ;; resources
               {:x 5})]    ;; initial data
  (:result result))
;; => 20
```

### Branching

Edges map transition labels to targets. Dispatch predicates examine the data to determine which edge to take:

```clojure
(defmethod cell/cell-spec :check/threshold [_]
  {:id          :check/threshold
   :handler     (fn [_ data]
                  (assoc data :above-threshold (> (:value data) 10)))
   :schema      {:input  [:map [:value :int]]
                 :output [:map]}})

(myc/run-workflow
  {:cells {:start :check/threshold
           :big   :process/big-values
           :small :process/small-values}
   :edges {:start {:high :big, :low :small}
           :big   {:done :end}
           :small {:done :end}}
   :dispatches {:start [[:high (fn [data] (:above-threshold data))]
                        [:low  (fn [data] (not (:above-threshold data)))]]
                :big   [[:done (constantly true)]]
                :small [[:done (constantly true)]]}}
  {} {:value 42})
```

Handlers compute data; dispatch predicates decide the route. This keeps business logic decoupled from graph navigation.

### Per-Transition Output Schemas

Cells with multiple outgoing edges can declare different output schemas for each transition:

```clojure
(defmethod cell/cell-spec :user/fetch [_]
  {:id          :user/fetch
   :handler     (fn [{:keys [db]} data]
                  (if-let [profile (get-user db (:user-id data))]
                    (assoc data :profile profile)
                    (assoc data :error-message "Not found")))
   :schema      {:input  [:map [:user-id :string]]
                 :output {:found     [:map [:profile [:map [:name :string] [:email :string]]]]
                          :not-found [:map [:error-message :string]]}}
   :requires    [:db]})
```

In the workflow, per-transition schemas are validated based on which dispatch matched:

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
(defmethod cell/cell-spec :user/fetch [_]
  {:id          :user/fetch
   :handler     (fn [{:keys [db]} data]
                  (if-let [profile (get-user db (:user-id data))]
                    (assoc data :profile profile)
                    (assoc data :error-message "Not found")))
   :schema      {:input  [:map [:user-id :string]]
                 :output {:found     [:map [:profile [:map [:name :string] [:email :string]]]]
                          :not-found [:map [:error-message :string]]}}
   :requires    [:db]})

;; Resources are passed at run time
(myc/run-workflow workflow {:db my-db-conn} {:user-id "alice"})
```

### Async Cells

```clojure
(defmethod cell/cell-spec :api/fetch-data [_]
  {:id          :api/fetch-data
   :handler     (fn [_resources data callback error-callback]
                  (future
                    (try
                      (let [resp (http/get (:url data))]
                        (callback (assoc data :response resp)))
                      (catch Exception e
                        (error-callback e)))))
   :schema      {:input  [:map [:url :string]]
                 :output [:map [:response map?]]}
   :async?      true})
```

## Compile-Time Validation

`compile-workflow` validates before any code runs:

- **Cell existence** — all referenced cells must be registered
- **Edge targets** — all targets must point to valid cells or `:end`/`:error`/`:halt`
- **Reachability** — every cell must be reachable from `:start`
- **Dispatch coverage** — every edge label must have a corresponding dispatch predicate, and vice versa
- **Schema chain** — each cell's input keys must be available from upstream outputs

```
Schema chain error: :user/fetch-profile at :fetch-profile requires keys #{:user-id}
but only #{:http-request} available
```

## Runtime Schema Enforcement

Pre and post interceptors validate every transition automatically:

- **Pre**: validates input data against the cell's `:input` schema before the handler runs
- **Post**: validates output data against `:output` schema, using the dispatch target to select per-transition schemas

Schema violations redirect to the error state with detailed diagnostics attached at `:mycelium/schema-error`.

## Workflow Trace

Every workflow run produces a `:mycelium/trace` vector in the result data — a step-by-step record of which cells ran, what transition was taken, and what the data looked like after each step.

```clojure
(let [result (myc/run-workflow
               {:cells {:start :math/double, :add :math/add-ten}
                :edges {:start {:done :add}, :add {:done :end}}
                :dispatches {:start [[:done (constantly true)]]
                             :add   [[:done (constantly true)]]}}
               {} {:x 5})]
  (:mycelium/trace result))
;; => [{:cell :start, :cell-id :math/double, :transition :done,
;;      :data {:x 5, :result 10}}
;;     {:cell :add, :cell-id :math/add-ten, :transition :done,
;;      :data {:x 5, :result 20}}]
```

Each trace entry contains:

| Key | Description |
|-----|-------------|
| `:cell` | Workflow cell name (e.g. `:start`, `:validate`) |
| `:cell-id` | Cell registry ID (e.g. `:auth/validate`) |
| `:transition` | Dispatch label taken (`nil` for unconditional edges) |
| `:data` | Data snapshot after the handler ran |
| `:error` | Schema error details (only present on validation failure) |

Data snapshots exclude `:mycelium/trace` itself to avoid recursive nesting.

### Asserting on Traces in Tests

```clojure
(let [result (fsm/run compiled {} {:data {:count 0}})
      trace  (:mycelium/trace result)]
  ;; Verify execution order
  (is (= [:start :validate :finish] (mapv :cell trace)))
  ;; Verify transitions taken
  (is (= [:ok :authorized :done] (mapv :transition trace)))
  ;; Verify data at each step
  (is (= 42 (get-in (last trace) [:data :result]))))
```

### Error Traces

When a schema violation occurs, the trace entry for the failing step includes an `:error` key with the validation details:

```clojure
(let [error-data (atom nil)
      compiled   (wf/compile-workflow workflow
                   {:on-error (fn [_ fsm-state]
                                (reset! error-data (:data fsm-state))
                                (:data fsm-state))})]
  (fsm/run compiled {} {:data {:x 1}})
  (let [trace (:mycelium/trace @error-data)
        fail  (last trace)]
    (is (some? (:error fail)))
    (is (= :step2 (:cell fail)))))
```

### Composed Workflow Traces

Child workflow traces flow through automatically — the parent result's `:mycelium/trace` contains the child's step-by-step entries followed by the parent's own entry for the composed cell.

## Hierarchical Composition

Workflows can be nested as cells in parent workflows:

```clojure
(require '[mycelium.compose :as compose])

;; Wrap a child workflow as a cell
(compose/register-workflow-cell!
  :auth/flow
  {:cells {:start :auth/parse, :validate :auth/check}
   :edges {:start {:ok :validate, :fail :error}
           :validate {:ok :end, :fail :error}}
   :dispatches {:start    [[:ok   (fn [data] (:user-id data))]
                            [:fail (fn [data] (:error data))]]
                :validate [[:ok   (fn [data] (:session-valid data))]
                           [:fail (fn [data] (not (:session-valid data)))]]}}
  {:input  [:map [:http-request map?]]
   :output [:map [:user-id :string]]})

;; Use it in a parent workflow — composed cells provide :default-dispatches
(myc/run-workflow
  {:cells {:start :auth/flow
           :main  :app/dashboard}
   :edges {:start {:success :main, :failure :error}
           :main  {:done :end}}
   :dispatches {:main [[:done (constantly true)]]}}
  resources initial-data)
```

Child workflows produce `:success` or `:failure` based on whether `:mycelium/error` is present in the data. Composed cells carry `:default-dispatches` that `compile-workflow` uses as fallback when no explicit dispatches are provided for that position. Child execution traces are preserved at `:mycelium/child-trace`.

## Manifest System

Define workflows as pure data in `.edn` files:

```clojure
;; workflows/user-onboarding.edn
{:id :user-onboarding
 :cells {:start {:id :auth/parse-request
                 :doc "Extract credentials from HTTP request"
                 :schema {:input  [:map [:http-request map?]]
                          :output [:map [:user-id :string] [:auth-token :string]]}}
         :validate {:id :auth/validate-session
                    :doc "Check credentials"
                    :schema {:input  [:map [:user-id :string] [:auth-token :string]]
                             :output [:map [:session-valid :boolean]]}
                    :requires [:db]}}
 :edges {:start    {:success :validate, :failure :error}
         :validate {:authorized :end, :unauthorized :error}}
 :dispatches {:start    [[:success (fn [data] (:user-id data))]
                         [:failure (fn [data] (not (:user-id data)))]]
              :validate [[:authorized   (fn [data] (:session-valid data))]
                         [:unauthorized (fn [data] (not (:session-valid data)))]]}}
```

Dispatch predicates in EDN are `(fn ...)` forms compiled by Maestro's built-in SCI evaluator.

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

;; With dispatch predicates to verify which edge matches
(dev/test-cell :auth/parse-request
  {:input      {:http-request {:headers {} :body {"username" "alice"}}}
   :dispatches [[:success (fn [d] (:user-id d))]
                [:failure (fn [d] (not (:user-id d)))]]
   :expected-dispatch :success})
;; => {:pass? true, :matched-dispatch :success, ...}
```

### Test Multiple Transitions

```clojure
(dev/test-transitions :user/fetch-profile
  {:found     {:input {:user-id "alice" :session-valid true}
               :resources {:db my-db}
               :dispatches [[:found     (fn [d] (:profile d))]
                            [:not-found (fn [d] (:error-type d))]]}
   :not-found {:input {:user-id "nobody" :session-valid true}
               :resources {:db my-db}
               :dispatches [[:found     (fn [d] (:profile d))]
                            [:not-found (fn [d] (:error-type d))]]}})
;; => {:found     {:pass? true, :matched-dispatch :found, :output {...}}
;;     :not-found {:pass? true, :matched-dispatch :not-found, :output {...}}}
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
   :output {:session-valid nil}})

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
│   ├── cell.clj          ;; Cell registry (multimethod-based)
│   ├── schema.clj        ;; Malli pre/post interceptors
│   ├── workflow.clj       ;; DSL → Maestro compiler
│   ├── compose.clj        ;; Hierarchical workflow nesting
│   ├── manifest.clj       ;; EDN manifest loading, cell briefs
│   ├── dev.clj            ;; Testing harness, visualization
│   ├── orchestrate.clj    ;; Agent orchestration helpers
│   └── core.clj           ;; Public API
└── test/mycelium/         ;; 115 tests, 254 assertions
```

## License

Copyright (c) Dmitri Sotnikov. All rights reserved.
