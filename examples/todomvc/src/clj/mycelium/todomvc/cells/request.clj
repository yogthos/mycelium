(ns mycelium.todomvc.cells.request
  (:require [mycelium.cell :as cell]
            [clojure.string :as str]))

(defmethod cell/cell-spec :request/parse-todo [_]
  {:id      :request/parse-todo
   :doc     "Extract todo-related params from an HTTP request"
   :handler (fn [_resources data]
              (let [req         (:http-request data)
                    path-params (or (:path-params req) {})
                    form-params (or (:form-params req) (:params req) {})
                    query-params (or (:query-params req) {})
                    headers     (or (:headers req) {})
                    ;; Parse todo-id from path params (support both string and keyword keys)
                    raw-id      (or (get path-params :id)
                                    (get path-params "id"))
                    todo-id     (when raw-id
                                  (try (Long/parseLong (str raw-id))
                                       (catch Exception _ nil)))
                    ;; Extract title from form params
                    raw-title   (or (get form-params :title)
                                    (get form-params "title"))
                    title       (when raw-title (str/trim (str raw-title)))
                    ;; Extract filter from query params
                    filter-val  (or (get query-params :filter)
                                    (get query-params "filter")
                                    "all")
                    ;; Check if HTMX request
                    htmx?       (some? (or (get headers "hx-request")
                                           (get headers "HX-Request")))]
                (cond-> (assoc data :filter filter-val :htmx? htmx?)
                  todo-id (assoc :todo-id todo-id)
                  (and title (not (str/blank? title))) (assoc :title title))))})
