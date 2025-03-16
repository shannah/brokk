package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.Language

sealed trait Language

object Language {
  case object Java extends Language

  case object Python extends Language
}
