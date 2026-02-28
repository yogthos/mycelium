# Testing Mycelium Applications

## Testing Cells in Isolation

Cells are designed to be tested independently. Use `dev/test-cell`:

```clojure
(require '[mycelium.dev :as dev])

;; Basic test — validates input/output schemas
(dev/test-cell :auth/parse-request
  {:input     {:http-request {:body {"auth-token" "tok_abc"}}}
   :resources {}})
;; => {:pass? true, :output {...}, :errors [], :duration-ms 0.42}

;; With dispatch verification — confirms which edge the output matches
(dev/test-cell :auth/parse-request
  {:input      {:http-request {:body {"auth-token" "tok_abc"}}}
   :resources  {}
   :dispatches [[:success (fn [d] (:auth-token d))]
                [:failure (fn [d] (:error-type d))]]
   :expected-dispatch :success})
;; => {:pass? true, :matched-dispatch :success, ...}
```

## Testing Multiple Transitions

When a cell has multiple outgoing paths, test them all:

```clojure
(dev/test-transitions :user/fetch-profile
  {:found     {:input {:user-id "alice" :session-valid true}
               :resources {:db test-db}
               :dispatches [[:found     (fn [d] (:profile d))]
                            [:not-found (fn [d] (:error-type d))]]}
   :not-found {:input {:user-id "nobody" :session-valid true}
               :resources {:db test-db}
               :dispatches [[:found     (fn [d] (:profile d))]
                            [:not-found (fn [d] (:error-type d))]]}})
;; => {:found     {:pass? true, :matched-dispatch :found, ...}
;;     :not-found {:pass? true, :matched-dispatch :not-found, ...}}
```

## Testing Workflow Execution

Run a complete workflow and assert on the result and trace:

```clojure
(deftest dashboard-workflow-test
  (let [result (myc/run-workflow
                 workflow-def
                 {:db test-db}
                 {:http-request {:cookies {"session-token" {:value "valid-tok"}}}})]
    ;; Check output
    (is (string? (:html result)))
    ;; Check execution path via trace
    (let [trace (:mycelium/trace result)]
      (is (= [:start :validate-session :fetch-profile :render-dashboard]
             (mapv :cell trace))))))
```

## Testing Input Schema Validation

```clojure
(deftest input-schema-rejects-missing-keys
  (let [result (myc/run-workflow
                 workflow-def
                 {:db test-db}
                 {:wrong-key "data"})]    ;; missing :http-request
    (is (contains? result :mycelium/input-error))
    (is (= [:map [:http-request [:map]]]
           (get-in result [:mycelium/input-error :schema])))))
```

## Testing Fragments

Test that fragment expansion produces the expected cells and edges:

```clojure
(require '[mycelium.fragment :as fragment])

(deftest fragment-expansion-test
  (let [frag (fragment/load-fragment "fragments/cookie-auth.edn")
        result (fragment/expand-fragment frag
                 {:as :start, :exits {:success :dashboard, :failure :error}}
                 #{})]
    ;; Entry cell renamed
    (is (contains? (:cells result) :start))
    ;; Exit references resolved
    (is (= :error (get-in result [:edges :start :failure])))
    (is (= :dashboard (get-in result [:edges :fetch-profile :found])))
    ;; :on-error resolved
    (is (= :error (get-in result [:cells :start :on-error])))))
```

## Test Fixture Pattern for Example Apps

Example app cells register via `defmethod` at namespace load time. If test fixtures clear global state, re-require with `:reload`:

```clojure
(use-fixtures :each
  (fn [f]
    (require '[app.cells.auth] :reload)
    (require '[app.cells.user] :reload)
    (require '[app.cells.ui] :reload)
    (f)))
```

## Asserting on Traces

```clojure
(let [trace (:mycelium/trace result)]
  ;; Execution order
  (is (= [:start :validate :render] (mapv :cell trace)))
  ;; Transitions taken
  (is (= [:success :authorized :done] (mapv :transition trace)))
  ;; Data at specific step
  (is (= "alice" (get-in (second trace) [:data :user-id]))))
```

## Enumerating All Paths

Get all possible execution paths through a workflow:

```clojure
(dev/enumerate-paths manifest)
;; => [[{:cell :start, :transition :success, :target :validate}
;;      {:cell :validate, :transition :authorized, :target :end}]
;;     [{:cell :start, :transition :failure, :target :error}]
;;     ...]
```

## Checking Implementation Status

```clojure
(dev/workflow-status manifest)
;; => {:total 4, :passing 2, :failing 1, :pending 1, :cells [...]}
```

## Visualizing Workflows

```clojure
;; Single workflow
(dev/workflow->dot manifest)
;; => "digraph { ... }"

;; Full system
(require '[mycelium.system :as sys])
(sys/system->dot system)
;; => "digraph system { ... }"
```

Pipe to Graphviz: `echo "digraph { ... }" | dot -Tpng -o workflow.png`
