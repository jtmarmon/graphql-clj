(ns graphql-clj.validator.rules.known-type-names
  "A GraphQL document is only valid if referenced types are defined by the type schema."
  (:require [graphql-clj.visitor :refer [defnodevisitor]]
            [graphql-clj.validator.errors :as ve]
            [clojure.spec :as s]
            [graphql-clj.box :as box]))

(defn- unknown-type-error [{:keys [type-name type-condition]}]
  {:error (format "Unknown type '%s'." (or (box/box->val type-name) (:type-name type-condition)))
   :loc   (ve/extract-loc (meta (or type-name type-condition)))})

;; TODO for schemas?

(defnodevisitor unknown-type-name-variable :pre :variable-definition [{:keys [spec] :as n} s]
  (when-not (s/get-spec spec)
    {:state (ve/update-errors s (unknown-type-error n))
     :break true}))

(defnodevisitor unknown-type-name-fragment :pre :fragment-definition [{:keys [spec] :as n} s]
  (when-not (s/get-spec spec)
    {:state (ve/update-errors s (unknown-type-error n))
     :break true}))

(def rules [unknown-type-name-variable
            unknown-type-name-fragment])
