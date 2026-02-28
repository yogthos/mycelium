(ns user
  "Userspace functions you can run by default in your local REPL."
  (:require
    [clojure.pprint]
    [clojure.spec.alpha :as s]
    [clojure.tools.namespace.repl :as repl]
    [criterium.core :as c]                                  ;; benchmarking
    [expound.alpha :as expound]
    [integrant.core :as ig]
    [integrant.repl :refer [clear go halt prep init reset reset-all]]
    [integrant.repl.state :as state]
    [kit.api :as kit]
    [lambdaisland.classpath :as licp]
    [mycelium.todomvc.core :refer [start-app]]))

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(add-tap (bound-fn* clojure.pprint/pprint))

(defn dev-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (mycelium.todomvc.config/system-config {:profile :dev})
                                  (ig/expand)))))

(defn test-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (mycelium.todomvc.config/system-config {:profile :test})
                                  (ig/expand)))))

;; Can change this to test-prep! if want to run tests as the test profile in your repl
;; You can run tests in the dev profile, too, but there are some differences between
;; the two profiles.
(dev-prep!)

(repl/set-refresh-dirs "src/clj")

(def refresh repl/refresh)



(defn update-deps
  "Refresh classpath to pick up deps.edn changes."
  []
  (licp/update-classpath! {:aliases [:dev :test]}))

(comment
  (go)
  (reset)
  (update-deps))
