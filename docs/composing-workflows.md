# Composing Workflows

Workflows are directed graphs of cells connected by edges and dispatch predicates.

## Workflow Definition Structure

```clojure
{:cells       {cell-name cell-id-or-spec, ...}
 :edges       {cell-name edge-def, ...}
 :dispatches  {cell-name [[label predicate], ...], ...}
 :joins       {join-name join-spec, ...}           ;; optional
 :input-schema malli-schema                         ;; optional
 :interceptors [interceptor-spec, ...]              ;; optional
}
```

## Minimal Example

```clojure
(require '[mycelium.core :as myc])

(myc/run-workflow
  {:cells {:start :math/double
           :add   :math/add-ten}
   :edges {:start {:done :add}
           :add   {:done :end}}
   :dispatches {:start [[:done (constantly true)]]
                :add   [[:done (constantly true)]]}}
  {}          ;; resources
  {:x 5})    ;; initial data
;; => {:x 5, :result 20, :mycelium/trace [...]}
```

## Edges

Edges define transitions from one cell to another. Two forms:

**Unconditional** — single keyword target (no dispatches needed):
```clojure
:edges {:start :next-cell}
```

**Conditional** — map of label→target (requires dispatches):
```clojure
:edges {:start {:success :dashboard, :failure :error-page}}
```

Special targets:
- `:end` — workflow completes successfully
- `:error` — workflow terminates with error (Maestro terminal state)
- `:halt` — workflow halts (Maestro terminal state)

## Dispatches

Dispatch predicates examine the data map and determine which edge to take. They are checked in order; the first truthy result wins:

```clojure
:dispatches {:start [[:success (fn [data] (:auth-token data))]
                     [:failure (fn [data] (:error-type data))]]}
```

- Predicate receives the `data` map (not the full FSM state)
- Labels must match the edge map keys for that cell
- First truthy match wins — order matters

## Accumulating Data Model

Cells communicate through an accumulating data map. Every cell receives ALL keys produced by every prior cell in the path:

```
start → validate → fetch-profile → render
         adds :user-id   adds :profile     needs :user-id AND :profile
```

`:render` can access `:user-id` even though `:fetch-profile` is between them — keys persist through the chain.

## Running Workflows

```clojure
;; Sync
(myc/run-workflow workflow-def resources initial-data)
(myc/run-workflow workflow-def resources initial-data opts)

;; Async
(myc/run-workflow-async workflow-def resources initial-data)
(myc/run-workflow-async workflow-def resources initial-data opts)
```

**opts map** (optional):
```clojure
{:pre  (fn [fsm-state resources] -> fsm-state)  ;; FSM-level pre interceptor
 :post (fn [fsm-state resources] -> fsm-state)  ;; FSM-level post interceptor
 :on-error (fn [resources fsm-state] -> fsm-state)} ;; global error handler
```

## Join Nodes (Parallel Execution)

When multiple cells can run concurrently:

```clojure
{:cells {:start          :auth/validate
         :fetch-profile  :user/fetch-profile     ;; join member
         :fetch-orders   :user/fetch-orders      ;; join member
         :render-summary :ui/render-summary}

 :joins {:fetch-data {:cells    [:fetch-profile :fetch-orders]
                      :strategy :parallel}}

 :edges {:start          {:authorized :fetch-data}
         :fetch-data     {:done :render-summary, :failure :render-error}
         :render-summary {:done :end}}}
```

Key rules:
- Join members exist in `:cells` but have NO entries in `:edges` — the join consumes them
- The join name appears in `:edges` like a regular cell
- Each member receives the same input snapshot (branches can't see each other's output)
- Default dispatches `:done` / `:failure` are injected automatically
- Output keys from members must be disjoint unless `:merge-fn` is provided

## Input Schema Validation

Validate data entering the workflow before any cell runs:

```clojure
{:input-schema [:map [:http-request [:map [:cookies map?]]]]
 :cells {...}
 :edges {...}}
```

If validation fails, `run-workflow` returns immediately with:
```clojure
{:mycelium/input-error {:schema [...], :errors [...], :data {...}}}
```

## Workflow-Level Interceptors

Transform data before/after cell handlers, scoped to matching cells:

```clojure
{:interceptors
 [{:id    :nav-context
   :scope {:id-match "ui/*"}           ;; applies to cells with :id matching glob
   :pre   (fn [data]
            (assoc data :logged-in true))}

  {:id    :request-logging
   :scope :all                          ;; applies to every cell
   :post  (fn [data]
            (println "Cell done" (keys data))
            data)}]
 :cells {...}}
```

Scope options:
- `:all` — every cell
- `{:id-match "ui/*"}` — cells whose `:id` matches the glob pattern
- `{:cells [:render-dashboard :render-error]}` — explicit cell name list

Interceptors are `(fn [data] -> data)`, distinct from Maestro's FSM-level interceptors.

## Compile-Time Validation

`compile-workflow` validates:
- Cell existence — all cell IDs must be registered
- Edge targets — all targets must be valid cells, joins, or terminal states
- Reachability — every cell/join must be reachable from `:start`
- Dispatch coverage — every edge label must have a dispatch predicate
- Schema chain — each cell's input keys must be available from upstream outputs
- Join validation — member cells exist, no name collisions, disjoint outputs

## Workflow Trace

Every run produces `:mycelium/trace` in the result:

```clojure
(:mycelium/trace result)
;; => [{:cell :start, :cell-id :auth/parse, :transition :success, :data {...}}
;;     {:cell :validate, :cell-id :auth/check, :transition :authorized, :data {...}}]
```
