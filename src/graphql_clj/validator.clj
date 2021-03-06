(ns graphql-clj.validator
  (:require [graphql-clj.validator.rules.default-values-of-correct-type]
            [graphql-clj.validator.rules.arguments-of-correct-type]
            [graphql-clj.validator.rules.fields-on-correct-type]
            [graphql-clj.validator.rules.known-argument-names]
            [graphql-clj.validator.rules.known-type-names]
            [graphql-clj.validator.rules.known-fragment-names]
            [graphql-clj.validator.rules.variables-are-input-types]
            [graphql-clj.validator.rules.no-undefined-variables]
            [graphql-clj.validator.rules.no-fragment-cycles]
            [graphql-clj.validator.rules.fragments-on-composite-types]
            [graphql-clj.validator.rules.unique-variable-names]
            [graphql-clj.validator.rules.unique-operation-names]
            [graphql-clj.validator.rules.unique-input-field-names]
            [graphql-clj.validator.rules.unique-fragment-names]
            [graphql-clj.validator.rules.unique-argument-names]
            [graphql-clj.validator.rules.provided-non-null-arguments]
            [graphql-clj.validator.rules.no-unused-variables]
            [graphql-clj.validator.rules.no-unused-fragments]
            [graphql-clj.validator.rules.known-directives]
            [graphql-clj.validator.rules.lone-anonymous-operation]
            [graphql-clj.validator.rules.variables-in-allowed-position]
            [graphql-clj.validator.rules.scalar-leafs]
            [graphql-clj.validator.transformations.unbox]
            [graphql-clj.validator.transformations.cleanup-paths]
            [graphql-clj.validator.transformations.schema :as ts]
            [graphql-clj.validator.transformations.build-arguments]
            [graphql-clj.validator.transformations.expand-fragments]
            [graphql-clj.validator.transformations.inline-types]
            [graphql-clj.visitor :as visitor]
            [graphql-clj.spec :as spec]
            [instaparse.core :as insta]
            [graphql-clj.introspection :as intro]
            [graphql-clj.error :as ge]))

(def first-pass-rules-schema [spec/fix-lists spec/add-spec spec/add-spec-to-map spec/define-specs])

(def second-pass-rules-schema
  (flatten [graphql-clj.validator.rules.unique-input-field-names/schema-rules
            graphql-clj.validator.rules.unique-argument-names/schema-rules
            graphql-clj.validator.transformations.unbox/rules
            graphql-clj.validator.transformations.cleanup-paths/rules]))

(def first-pass-rules-statement [spec/fix-lists spec/add-spec-pre spec/add-spec-to-map spec/define-specs])

(def second-pass-rules-statement
  (flatten [graphql-clj.validator.rules.lone-anonymous-operation/rules
            graphql-clj.validator.rules.known-type-names/rules
            graphql-clj.validator.rules.known-argument-names/rules
            graphql-clj.validator.rules.known-fragment-names/rules
            graphql-clj.validator.rules.no-undefined-variables/rules
            graphql-clj.validator.rules.unique-input-field-names/statement-rules
            graphql-clj.validator.rules.arguments-of-correct-type/rules
            graphql-clj.validator.rules.default-values-of-correct-type/rules
            graphql-clj.validator.rules.variables-are-input-types/rules
            graphql-clj.validator.rules.fields-on-correct-type/rules
            graphql-clj.validator.rules.no-fragment-cycles/rules
            graphql-clj.validator.rules.fragments-on-composite-types/rules
            graphql-clj.validator.rules.unique-variable-names/rules
            graphql-clj.validator.rules.unique-operation-names/rules
            graphql-clj.validator.rules.unique-fragment-names/rules
            graphql-clj.validator.rules.unique-argument-names/statement-rules
            graphql-clj.validator.rules.provided-non-null-arguments/rules
            graphql-clj.validator.rules.no-unused-variables/rules
            graphql-clj.validator.rules.no-unused-fragments/rules
            graphql-clj.validator.rules.known-directives/rules
            graphql-clj.validator.rules.variables-in-allowed-position/rules
            graphql-clj.validator.rules.scalar-leafs/rules
            graphql-clj.validator.transformations.unbox/rules
            graphql-clj.validator.transformations.expand-fragments/rules
            graphql-clj.validator.transformations.build-arguments/rules
            graphql-clj.validator.transformations.inline-types/rules
            graphql-clj.validator.transformations.cleanup-paths/rules]))

(defn- validate [visit-fn]
  (try
    (visit-fn)
    (catch Exception e
      {:state {:errors [(or (ex-data e) {:error (.getMessage e)})]}})))

(defn- guard-parsed [doc-type doc]
  (when (string? doc)
    (ge/throw-error "Strings must be parsed prior to validation"))
  (when (insta/failure? doc)
    (let [msg (format "Syntax error in %s document" doc-type)]
      (ge/throw-error msg {:error msg
                           :loc {:line (:line doc) :column (:column doc)}})))) ;; TODO produces a weird duplicative error message

(defn- inject-introspection-schema
  "Given a schema definition, add internal introspection type system definitions,
   unless we are processing the introspection schema itself."
  [schema]
  (if (= schema intro/introspection-schema)
    schema
    (update schema :type-system-definitions concat (:type-system-definitions intro/introspection-schema))))

(defn- validate-schema*
  "Inject the introspection schema to form a complete schema definition.
   Then, do a 2 pass validation (without error handling):
   - 1) Add specs and validate that all types resolve.
   - 2) Apply validation rules and final transformations."
  [schema rules1 rules2]
  (guard-parsed "schema" schema)
  (let [combined-schema (inject-introspection-schema schema)
        {:keys [document state]} (visitor/initial-state combined-schema)
        {:keys [document state]} (visitor/visit-document document state rules1)
        second-pass (visitor/visit-document document state rules2)]
    (assoc-in second-pass [:state :schema] (ts/mapify-schema (:document second-pass)))))

(defn validate-statement*
  "Do a 2 pass validation of a statement (without error handling)"
  [document' state rules1 rules2]
  (guard-parsed "schema" state)
  (guard-parsed "statement" document')
  (let [s (assoc state :statement-hash (hash document'))
        {:keys [document state]} (visitor/visit-document document' s rules1)]
    (visitor/visit-document document state rules2)))

;; Public API

(defn validate-schema
  "Validate a schema with error handling, but without memoization"
  ([schema]
   (validate-schema schema second-pass-rules-schema))
  ([schema rules2]
   (:state (validate #(validate-schema* schema first-pass-rules-schema rules2))))) ;; Unwrap state - it now encompasses the original schema

(defn validate-statement
  "Validate a statement with error handling, but without memoization"
  ([document state]
   (validate-statement document state second-pass-rules-statement))
  ([document state rules2]
   (if-let [errors (:errors state)] ;; Don't try to validate a statement if the schema is invalid
     {:errors errors}
     (let [{:keys [document state]} (validate #(validate-statement* document state first-pass-rules-statement rules2))]
       (if-let [errors (:errors state)]
         {:errors errors}
         {:document document})))))
