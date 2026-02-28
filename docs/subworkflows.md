# Subworkflows (Nested Composition)

Subworkflows let you wrap an entire workflow as a single cell, creating hierarchical composition where a parent workflow delegates to a child workflow as one opaque step.

## Creating a Subworkflow Cell

```clojure
(require '[mycelium.compose :as compose])

;; Register a workflow as a cell
(compose/register-workflow-cell!
  :payment/process-order
  {:cells      {:start :payment/validate-card
                :charge :payment/charge-card
                :receipt :payment/generate-receipt}
   :edges      {:start  {:valid :charge, :invalid :_error}
                :charge {:success :receipt, :failure :_error}
                :receipt {:done :end}}
   :dispatches {:start   [[:valid   (fn [d] (:card-valid d))]
                          [:invalid (fn [d] (not (:card-valid d)))]]
                :charge  [[:success (fn [d] (:charge-id d))]
                          [:failure (fn [d] (:error-type d))]]
                :receipt [[:done (constantly true)]]}}
  {:input  [:map [:card-number :string] [:amount :int]]
   :output [:map [:receipt-id :string]]})
```

Or create the spec without registering:

```clojure
(def payment-cell-spec
  (compose/workflow->cell
    :payment/process-order
    workflow-def
    {:input  [:map [:card-number :string] [:amount :int]]
     :output [:map [:receipt-id :string]]}))
```

## Using in a Parent Workflow

The subworkflow cell is used like any other cell:

```clojure
(myc/run-workflow
  {:cells      {:start   :order/prepare
                :payment :payment/process-order   ;; the subworkflow cell
                :confirm :order/send-confirmation}
   :edges      {:start   {:ready :payment}
                :payment {:success :confirm, :failure :end}
                :confirm {:done :end}}
   :dispatches {:start   [[:ready (constantly true)]]
                :payment [[:success (fn [d] (not (:mycelium/error d)))]
                          [:failure (fn [d] (some? (:mycelium/error d)))]]
                :confirm [[:done (constantly true)]]}}
  {:db db}
  {:order-id "ord-123" :card-number "4111..." :amount 5000})
```

## Default Dispatches

Subworkflow cells provide default dispatches automatically:

```clojure
[[:success (fn [data] (not (:mycelium/error data)))]
 [:failure (fn [data] (some? (:mycelium/error data)))]]
```

If the child workflow completes normally, `:success` matches. If it hits an error state or throws, `:mycelium/error` is set on the data and `:failure` matches.

You don't need to declare these dispatches explicitly in the parent workflow's `:dispatches` map -- they are injected as `:default-dispatches` on the cell spec.

## How It Works

At registration time:
1. The child workflow is compiled once into a Maestro FSM
2. A handler function is created that runs the child FSM synchronously
3. Custom `on-error` and `on-end` handlers extract the child's trace into `:mycelium/child-trace`
4. The output schema is set to open `:map` (not the declared happy-path schema) because failure paths produce different keys

At runtime:
1. The parent FSM reaches the subworkflow cell
2. The cell handler calls `fsm/run` with the pre-compiled child FSM
3. The child runs to completion (all its cells, schema validation, tracing)
4. The result data flows back into the parent's data map
5. The parent dispatches on `:success` or `:failure`

## Accessing the Child Trace

The child workflow's execution trace is available as `:mycelium/child-trace` on the result data:

```clojure
(let [result (myc/run-workflow parent-wf {} initial-data)]
  ;; Parent trace shows one entry for the subworkflow cell
  (:mycelium/trace result)
  ;; => [{:cell :start, ...}
  ;;     {:cell :payment, :cell-id :payment/process-order, ...}
  ;;     {:cell :confirm, ...}]

  ;; Child trace shows the internal execution
  (:mycelium/child-trace result)
  ;; => [{:cell :start, :cell-id :payment/validate-card, ...}
  ;;     {:cell :charge, :cell-id :payment/charge-card, ...}
  ;;     {:cell :receipt, :cell-id :payment/generate-receipt, ...}]
  )
```

## Error Handling

When the child workflow fails:
- Schema validation errors inside the child set `:mycelium/error` with the schema error details
- Exceptions thrown by child cell handlers are caught and set `:mycelium/error` with the exception message
- The parent can dispatch on `:failure` and route to its own error handling cell

```clojure
;; In the parent, check for subworkflow errors
:dispatches {:payment [[:success (fn [d] (not (:mycelium/error d)))]
                       [:failure (fn [d] (some? (:mycelium/error d)))]]}
```

---

# Fragments vs Subworkflows

Fragments and subworkflows both enable reuse, but they work at different levels and serve different purposes.

## Fragments: Compile-Time Inlining

Fragments are expanded at manifest load time. The fragment's cells, edges, and dispatches are merged directly into the host manifest. After expansion, there is no fragment boundary at runtime -- it's as if you wrote all those cells in the host manifest by hand.

```
Host manifest + fragment  -->  expand-all-fragments  -->  flat manifest with all cells
```

See [workflow-fragments.md](workflow-fragments.md) for the full fragment API.

## Subworkflows: Runtime Nesting

`workflow->cell` wraps an entire workflow as a single opaque cell. The parent workflow sees it as one cell with `:success`/`:failure` dispatches. The child workflow runs to completion inside the cell's handler.

```
Parent workflow  -->  [subworkflow-cell]  -->  next cell
                            |
                      child FSM runs internally
```

## Comparison

| | Fragments | Subworkflows |
|---|---|---|
| **When** | Load time (manifest expansion) | Runtime (cell handler) |
| **Boundary** | Dissolved -- no boundary at runtime | Preserved -- child is opaque to parent |
| **Schema chain** | Validated as one flat workflow across fragment/host | Validated separately: parent and child each validate their own chain |
| **Trace** | Each fragment cell appears individually in the trace | One trace entry with nested `:mycelium/child-trace` |
| **Error routing** | Fragment cells use `:_exit/failure` to route to host error cell | Child errors surface as `:mycelium/error`, parent dispatches on `:failure` |
| **Output schema** | Each cell has its own precise schema | Subworkflow cell uses open `:map` (failure path won't match happy-path schema) |
| **Interceptors** | Host workflow interceptors apply to fragment cells (they're inlined) | Parent interceptors do NOT apply to child cells (separate FSM) |
| **Reuse mechanism** | EDN file on classpath or inline data | Registered cell in the cell registry |

## When to Use Each

**Use fragments when:**
- You're eliminating copy-paste of the same cells/edges/dispatches across manifests (e.g., an auth flow shared by 6+ routes)
- You want the host workflow's interceptors to apply to the reused cells
- You want full schema chain validation across the boundary
- You want each cell to appear individually in the trace for debugging

**Use subworkflows when:**
- You want to encapsulate a workflow behind a clean interface (input schema in, result out)
- The child workflow is a self-contained process that shouldn't leak implementation details to the parent
- You want independent schema validation (child validates internally, parent only sees the interface)
- You're composing workflows from different teams or modules where the internals should be hidden

**Rule of thumb:** If you'd copy-paste the cells into every manifest that needs them, use a fragment. If you'd call it as a function and only care about the inputs and outputs, use a subworkflow.
