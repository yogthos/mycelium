# Working with Manifests

Manifests are EDN files that define complete workflows as pure data. They serve as the single source of truth for workflow structure, cell schemas, and routing logic.

## Manifest Structure

```clojure
{:id            :workflow-name              ;; required
 :doc           "Description"               ;; optional
 :input-schema  [:map [:http-request [:map]]] ;; optional, validates workflow input
 :fragments     {...}                       ;; optional, see workflow-fragments.md
 :cells         {...}                       ;; required
 :edges         {...}                       ;; required
 :dispatches    {...}                       ;; required (for conditional edges)
 :joins         {...}                       ;; optional
 :interceptors  [...]                       ;; optional
}
```

## Cell Definitions in Manifests

Each cell in the `:cells` map must have:

```clojure
{:cell-name
 {:id       :namespace/cell-id      ;; cell registry ID
  :doc      "What this cell does"   ;; optional
  :schema   {:input  [:map ...]     ;; Malli input schema
             :output [:map ...]     ;; or {:transition-label [:map ...], ...}
            }
  :on-error :error-cell             ;; required: target cell keyword or nil
  :requires [:db]}}                 ;; optional: resource dependencies
```

### The `:on-error` Declaration

Every cell must declare `:on-error`:
- `:on-error :render-error` — declares that errors route to `:render-error`
- `:on-error nil` — explicitly declares "no error handling needed"

The target must be a valid cell name in the manifest. This is a **declaration** that the manifest author has considered error handling — it does not auto-generate edges.

## Loading Manifests

```clojure
(require '[mycelium.manifest :as manifest])

;; Load from file path — expands fragments, validates
(def m (manifest/load-manifest "path/to/workflow.edn"))

;; Validate an in-memory manifest
(manifest/validate-manifest manifest-map)

;; With options
(manifest/validate-manifest manifest-map {:strict? false}) ;; allow missing :on-error
```

`load-manifest` automatically:
1. Parses the EDN file
2. Expands `:fragments` if present
3. Validates the manifest structure

## Converting to Workflow

```clojure
(def workflow-def (manifest/manifest->workflow m))
```

This:
- Registers stub handlers for cells not yet implemented
- Applies manifest metadata (schema, requires) to already-registered cells
- Returns a workflow definition map suitable for `run-workflow`

## Input Schema Validation

Add `:input-schema` to validate data before any cell runs:

```clojure
{:id :dashboard
 :input-schema [:map [:http-request [:map [:cookies map?]]]]
 :cells {...}}
```

When `run-workflow` receives data that doesn't match:
```clojure
{:mycelium/input-error {:schema [:map [:http-request [:map [:cookies map?]]]]
                        :errors [{:path [:http-request :cookies], ...}]
                        :data   {:http-request {:headers {}}}}}
```

## Dispatch Predicates in EDN

Predicates are `(fn ...)` forms evaluated by Maestro's SCI (Small Clojure Interpreter):

```clojure
:dispatches
{:start [[:success (fn [data] (:auth-token data))]
         [:failure (fn [data] (:error-type data))]]}
```

Available in predicates: core Clojure functions. No `require` or namespace access.

## Cell Briefs (for LLM Agents)

Generate a self-contained prompt for implementing a single cell:

```clojure
(manifest/cell-brief m :validate-session)
;; => {:id :auth/validate-session
;;     :doc "Check credentials against the session store"
;;     :schema {:input [...], :output {...}}
;;     :requires [:db]
;;     :examples {:input {...}, :output {...}}
;;     :prompt "## Cell: :auth/validate-session\n..."}
```

The `:prompt` string contains everything an LLM needs to implement the cell: schema, examples, dispatch rules, and coding constraints.

## Manifest Validation Rules

`validate-manifest` checks:
- `:id` is present
- `:cells` is present and non-empty
- `:edges` is present
- Every cell has valid `:id`, `:schema` (with `:input` and `:output`), and `:on-error`
- `:on-error` targets exist in `:cells`
- All edge targets point to valid cells, joins, or terminal states (`:end`, `:error`, `:halt`)
- Every non-join-member cell has an edge entry
- Dispatch coverage: every edge label has a predicate, every predicate has an edge
- All cells are reachable from `:start`
- `:input-schema` is well-formed Malli (if present)
- Join members have no edge entries
- Join names don't collide with cell names

## Example: Complete Manifest

```clojure
{:id :user-profile
 :doc "Authenticate, fetch user by ID, render profile page"
 :input-schema [:map [:http-request [:map]]]

 :fragments
 {:auth {:ref   "fragments/cookie-auth.edn"
         :as    :start
         :exits {:success :fetch-profile-by-id
                 :failure :render-error}}}

 :cells
 {:fetch-profile-by-id
  {:id       :user/fetch-profile-by-id
   :schema   {:input  [:map [:http-request [:map [:path-params [:map [:id :string]]]]]]
              :output {:found     [:map [:profile map?]]
                       :not-found [:map [:error-type :keyword]
                                        [:error-message :string]]}}
   :on-error :render-error
   :requires [:db]}

  :render-profile
  {:id       :ui/render-user-profile
   :schema   {:input  [:map [:profile [:map [:id :string] [:name :string] [:email :string]]]]
              :output [:map [:html :string]]}
   :on-error :render-error
   :requires []}

  :render-error
  {:id       :ui/render-error
   :schema   {:input  [:map]
              :output [:map [:html :string] [:error-status :int]]}
   :on-error nil
   :requires []}}

 :edges
 {:fetch-profile-by-id {:found :render-profile, :not-found :render-error}
  :render-profile      {:done :end}
  :render-error        {:done :end}}

 :dispatches
 {:fetch-profile-by-id [[:found     (fn [data] (:profile data))]
                         [:not-found (fn [data] (:error-type data))]]
  :render-profile      [[:done (fn [_] true)]]
  :render-error        [[:done (fn [_] true)]]}}
```

## Typical Workflow Loader Pattern

```clojure
(ns app.workflows.dashboard
  (:require [mycelium.manifest :as manifest]
            [mycelium.core :as myc]
            [clojure.java.io :as io]
            [app.cells.auth]       ;; load cell registrations
            [app.cells.user]
            [app.cells.ui]))

(def manifest-data
  (manifest/load-manifest
   (str (io/resource "workflows/dashboard.edn"))))

(def workflow-def
  (manifest/manifest->workflow manifest-data))

(defn run-dashboard [db request]
  (myc/run-workflow
   workflow-def
   {:db db}
   {:http-request {:query-params (or (:query-params request) {})
                   :cookies      (or (:cookies request) {})}}))
```
