; Top-level class (non-exported)
((class_declaration
  name: (identifier) @class.name) @class.definition)

; Top-level function (non-exported)
((function_declaration
  name: (identifier) @function.name) @function.definition)

; Exported class
(export_statement
  declaration: ((class_declaration
    name: (identifier) @class.name) @class.definition))

; Exported function
(export_statement
  declaration: ((function_declaration
    name: (identifier) @function.name) @function.definition))

; Top-level const/let/var MyComponent = () => { ... }
((lexical_declaration
  (variable_declarator
    name: (identifier) @function.name
    value: ((arrow_function) @function.definition))))

; Exported const/let/var MyComponent = () => { ... }
(export_statement
  declaration: (lexical_declaration
                 (variable_declarator
                   name: (identifier) @function.name
                   value: ((arrow_function) @function.definition))))

; TODO: Revisit JavaScript field queries. Current attempts cause TSQueryErrorField.
; ; Class public field (within class_declaration or export_statement context)
; ; Case 1: field name is a property_identifier
; (class_declaration
;   body: (class_body
;           (public_field_definition
;             name: (property_identifier) @field.name
;             ; value: _ ; value is optional
;           ) @field.definition
;         )
; )
; ; Case 2: field name is a simple identifier (e.g. for `foo = 1` not `this.foo = 1`)
; (class_declaration
;   body: (class_body
;           (public_field_definition
;             name: (identifier) @field.name
;             ; value: _ ; value is optional
;           ) @field.definition
;         )
; )
; 
; (export_statement
;   declaration: (class_declaration
;     body: (class_body
;             (public_field_definition
;               name: (property_identifier) @field.name
;             ) @field.definition
;           )
;   )
; )
; (export_statement
;   declaration: (class_declaration
;     body: (class_body
;             (public_field_definition
;               name: (identifier) @field.name
;             ) @field.definition
;           )
;   )
; )
; 
; ; Class private field (within class_declaration or export_statement context)
; ; private_field_definition itself is captured as @field.definition
; ; its 'name' child (which must be a private_property_identifier) is captured as @field.name
; (class_declaration
;   body: (class_body
;           (private_field_definition
;             name: (_) @field.name ; Capture the node assigned to 'name' field
;           ) @field.definition
;           (#match? @field.name "^#") ; Ensure the captured name starts with # (like a private_property_identifier)
;         )
; )
; 
; (export_statement
;   declaration: (class_declaration
;     body: (class_body
;             (private_field_definition
;               name: (_) @field.name
;             ) @field.definition
;             (#match? @field.name "^#")
;           )
;   )
; )


; Ignore decorators / modifiers for now
