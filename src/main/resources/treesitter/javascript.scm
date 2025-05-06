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

; Ignore decorators / modifiers for now
