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

; Ignore decorators / modifiers for now
