; Class: Capture node and its name field
(class_declaration
  name: (identifier) @class.name) @class.definition

; Interface: Capture node and its name field
(interface_declaration
  name: (identifier) @interface.name) @interface.definition

; Struct: Capture node and its name field
(struct_declaration
  name: (identifier) @struct.name) @struct.definition

; Method: Capture node and its name field
(method_declaration
  name: (identifier) @method.name) @method.definition

; Field: Capture the field.declaration node and the identifier within variable_declarator's name field
(field_declaration
  (variable_declaration
    (variable_declarator
       name: (identifier) @field.name))) @field.definition

; Property: Capture node and its name field
(property_declaration
  name: (identifier) @property.name) @property.definition

; Constructor: Capture the whole node (name extraction handled in Java)
(constructor_declaration
  name: (identifier) @constructor.name) @constructor.definition

; Attributes/Annotations to ignore in skeleton map
(attribute_list) @annotation
