# System-Level Compilation

The system compilation module provides a bird's-eye view of all workflows in an application, enabling cross-workflow queries and conflict detection at startup.

## Compiling a System

```clojure
(require '[mycelium.core :as myc])
(require '[mycelium.manifest :as manifest])

(def system
  (myc/compile-system
    {"/dashboard"  (manifest/load-manifest (str (io/resource "workflows/dashboard.edn")))
     "/orders"     (manifest/load-manifest (str (io/resource "workflows/order-summary.edn")))
     "/users"      (manifest/load-manifest (str (io/resource "workflows/user-list.edn")))
     "/login"      (manifest/load-manifest (str (io/resource "workflows/login-page.edn")))}))
```

Input: a map of route (or name) → validated manifest.

## System Structure

```clojure
{:routes
 {"/dashboard" {:manifest-id :dashboard
                :cells #{:auth/extract-cookie-session :auth/validate-session
                         :user/fetch-profile :ui/render-dashboard :ui/render-error}
                :requires #{:db}
                :input-schema [:map [:http-request [:map]]]}}

 :cells
 {:auth/extract-cookie-session {:used-by ["/dashboard" "/orders" "/users"]
                                 :schema {:input [...] :output {...}}}
  :ui/render-error              {:used-by ["/dashboard" "/orders" "/users" "/login"]
                                 :schema {:input [...] :output [...]}}}

 :shared-cells #{:auth/extract-cookie-session :auth/validate-session :ui/render-error}
 :resources-needed #{:db}
 :warnings [...]}
```

## Query Functions

```clojure
(require '[mycelium.system :as sys])

;; Which routes use a specific cell?
(sys/cell-usage system :auth/validate-session)
;; => ["/dashboard" "/orders" "/users"]

;; What cells does a route use?
(sys/route-cells system "/orders")
;; => #{:auth/extract-cookie-session :auth/validate-session
;;      :user/fetch-profile :user/fetch-orders :ui/render-order-summary :ui/render-error}

;; What resources does a route need?
(sys/route-resources system "/orders")
;; => #{:db}

;; Find cells with different schemas across workflows
(sys/schema-conflicts system)
;; => [{:cell-id :auth/validate-session
;;      :routes {"/dashboard" {:input [...] :output {...}}
;;               "/orders"    {:input [...] :output {...}}}}]
;; Empty vector = no conflicts

;; Generate DOT graph of the full system
(sys/system->dot system)
;; => "digraph system { ... }" — pipe to `dot -Tpng`
```

## Use Cases

### Startup health check
```clojure
(let [system (myc/compile-system route-manifest-map)]
  (when (seq (:warnings system))
    (log/warn "System warnings:" (:warnings system)))
  (log/info "System compiled:"
            (count (:routes system)) "routes,"
            (count (:cells system)) "unique cells,"
            (count (:shared-cells system)) "shared cells,"
            "resources needed:" (:resources-needed system)))
```

### Schema drift detection
```clojure
(let [conflicts (sys/schema-conflicts system)]
  (when (seq conflicts)
    (throw (ex-info "Schema conflicts detected across workflows"
                    {:conflicts conflicts}))))
```

### Dependency analysis
```clojure
;; Find all routes that need a specific resource
(for [[route info] (:routes system)
      :when (contains? (:requires info) :db)]
  route)
```
