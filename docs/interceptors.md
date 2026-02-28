# Workflow-Level Interceptors

Interceptors let you inject cross-cutting concerns (logging, context enrichment, error wrapping) into cell handlers without modifying the handlers themselves.

## Defining Interceptors

Interceptors are declared in the workflow definition under `:interceptors`:

```clojure
{:interceptors
 [{:id    :nav-context
   :scope {:id-match "ui/*"}
   :pre   (fn [data]
            (if (:user-id data)
              (assoc data :logged-in true :nav-items ["Dashboard" "Profile" "Logout"])
              (assoc data :logged-in false :nav-items ["Login" "Register"])))}

  {:id    :request-logging
   :scope :all
   :post  (fn [data]
            (println "Cell completed, keys:" (keys data))
            data)}]

 :cells {...}
 :edges {...}}
```

## Interceptor Fields

| Field | Required | Description |
|-------|----------|-------------|
| `:id` | yes | Keyword identifier for the interceptor |
| `:scope` | yes | Which cells this interceptor applies to (see Scope Options) |
| `:pre` | no | `(fn [data] -> data)` — transforms input data before handler |
| `:post` | no | `(fn [data] -> data)` — transforms output data after handler |

At least one of `:pre` or `:post` must be present.

## Scope Options

### `:all` — every cell in the workflow
```clojure
{:scope :all}
```

### `{:id-match "pattern"}` — glob pattern on cell `:id`
```clojure
{:scope {:id-match "ui/*"}}       ;; matches :ui/render-dashboard, :ui/render-error
{:scope {:id-match "auth/*"}}     ;; matches :auth/validate-session
{:scope {:id-match "*"}}          ;; same as :all
```

The pattern is matched against the full `namespace/name` of the cell's `:id`.

### `{:cells [...]}` — explicit cell name list
```clojure
{:scope {:cells [:render-dashboard :render-error]}}
```

Cell names are the workflow-level names (keys in `:cells` map), not the cell registry IDs.

## Execution Order

Interceptors compose in declaration order (first declared = outermost wrapper):

```clojure
:interceptors [{:id :first, :scope :all, :pre pre-1, :post post-1}
               {:id :second, :scope :all, :pre pre-2, :post post-2}]
```

Execution: `pre-1 → pre-2 → handler → post-2 → post-1`

## How It Works

At `compile-workflow` time, for each cell that matches any interceptor's scope, the cell's handler is wrapped:

```
original-handler
  ↓ wrapped by matching interceptors
intercepted-handler = (fn [resources data]
                        (->> data
                             (apply-pre-interceptors)
                             (original-handler resources)
                             (apply-post-interceptors)))
```

This happens before the FSM is built — the interceptor wrapping is transparent to Maestro.

## Interceptors vs Maestro FSM Interceptors

| | Workflow-Level Interceptors | Maestro FSM Interceptors |
|---|---|---|
| Signature | `(fn [data] -> data)` | `(fn [fsm-state resources] -> fsm-state)` |
| Scope | Per-cell, pattern-matched | Global (all states) |
| Defined in | Workflow `:interceptors` | `run-workflow` opts `:pre`/`:post` |
| Purpose | Cell-specific data transforms | Schema validation, tracing |

Both can coexist. Schema interceptors (pre/post) run at the FSM level and are always present. Workflow interceptors wrap individual cell handlers inside the FSM state.

## Common Use Cases

### Injecting navigation context for UI cells
```clojure
{:id    :nav-context
 :scope {:id-match "ui/*"}
 :pre   (fn [data]
          (assoc data
                 :logged-in (boolean (:user-id data))
                 :nav-items (if (:user-id data)
                              ["Dashboard" "Profile" "Logout"]
                              ["Login"])))}
```

### Request timing
```clojure
{:id    :timing
 :scope :all
 :pre   (fn [data] (assoc data ::start-ns (System/nanoTime)))
 :post  (fn [data]
          (let [elapsed (/ (- (System/nanoTime) (::start-ns data)) 1e6)]
            (println "Cell took" elapsed "ms")
            (dissoc data ::start-ns)))}
```

### Error context enrichment
```clojure
{:id    :error-context
 :scope :all
 :post  (fn [data]
          (if (:error-type data)
            (assoc data :error-context {:timestamp (java.time.Instant/now)
                                         :workflow-id :my-workflow})
            data))}
```
