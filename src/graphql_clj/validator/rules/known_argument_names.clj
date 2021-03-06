(ns graphql-clj.validator.rules.known-argument-names
  "A GraphQL field is only valid if all supplied arguments are defined by that field."
  (:require [graphql-clj.visitor :refer [defnodevisitor]]
            [graphql-clj.validator.errors :as ve]
            [clojure.spec :as s]))

(defn- undefined-argument-error [{:keys [argument-name v/parent] :as n}]
  (case (:node-type parent)
    :field
    {:error (format "Unknown argument '%s' on field '%s' of type '%s'."
                    argument-name (name (:spec parent)) (name (s/get-spec (:spec parent))))
     :loc   (ve/extract-loc (meta n))}
    :directive
    {:error (format "Unknown argument '%s' on directive '@%s'." argument-name (-> parent :v/path last))
     :loc   (ve/extract-loc (meta n))}))

(defnodevisitor undefined-argument :pre :argument
  [{:keys [spec value v/path v/parent] :as n} s]
  (when-not (s/get-spec spec)
    (when (#{:field :directive} (:node-type parent))
      {:state (ve/update-errors s (undefined-argument-error n))
       :break true})))

(def rules [undefined-argument])
