; We capture the whole definition node now (@*.definition) for top-level items.
; The name is still useful (@*.name).
; Nested items (methods, fields) will be found by traversing the definition node's children in Java code.

; Top-level Class definition
(class_definition
  name: (identifier) @class.name) @class.definition

; Top-level Function definition
(function_definition
  name: (identifier) @function.name) @function.definition

; Capture field assignments like `self.x = ...` to help identify them during skeleton building.
; We need the helper `obj` capture for the predicate.
(assignment
  left: (attribute
    object: (identifier) @obj (#eq? @obj "self")
    attribute: (identifier) @field.name)) @field.declaration

; Python class attribute
; Captures assignments like `CLS_VAR = value` directly under class definition
(class_definition
  name: _ @_.class_name ; Anchor to ensure we are inside a class
  body: (block . (expression_statement (assignment
                                          left: (identifier) @field.name
                                          right: _) @field.definition)))
