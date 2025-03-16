package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.CodeUnitType

enum CodeUnitType {
  case CLASS, FUNCTION, FIELD
}

object CodeUnitType {
  val ALL: java.util.Set[CodeUnitType] = java.util.Set.of(CLASS, FUNCTION, FIELD)
}
