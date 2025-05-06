; We capture the whole definition node now (@*.definition) for top-level items.
; The name is still useful (@*.name).

; Class definition (captures at any nesting level initially, Java logic sorts out hierarchy)
(class_definition
  name: (identifier) @class.name) @class.definition

; Function definition at module level (direct child of module)
(module
  (function_definition
    name: (identifier) @function.name) @function.definition)

; Method definition (function_definition directly inside a class's body block)
; This also captures static methods if they are structured as function_definition within class body.
(class_definition
  body: (block
    (function_definition
      name: (identifier) @function.name) @function.definition
  )
)

; Decorated method definition (function_definition directly inside a class's body block, with decorators)
; The decorated_definition node itself is captured as @function.definition for consistency,
; and its inner function_definition's name is @function.name.
(class_definition
  body: (block
    (decorated_definition
      definition: (function_definition
        name: (identifier) @function.name
      )
    ) @function.definition
  )
)


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
