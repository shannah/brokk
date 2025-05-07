; Top-level class (non-exported)
((class_declaration
  name: (identifier) @class.name) @class.definition)

; Top-level function (non-exported)
((function_declaration
  name: (identifier) @function.name) @function.definition)

; Top-level const/let/var MyComponent = () => { ... }
((lexical_declaration
  (variable_declarator
    name: (identifier) @function.name
    value: ((arrow_function) @function.definition))))

; Class method
; Captures method_definition within a class_body. Name can be property_identifier or identifier.
(
  (class_declaration
    body: (class_body
            (method_definition
              name: [(property_identifier) (identifier)] @function.name
            ) @function.definition
          )
  )
)

; TODO: Revisit JavaScript field queries. Current attempts cause TSQueryErrorField.
; (field definitions are commented out as per original)


; Ignore decorators / modifiers for now
