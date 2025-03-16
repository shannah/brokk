package io.github.jbellis.brokk.analyzer

/**
 * Represents a call site within source code
 *
 * @param signature The fully-qualified method signature
 * @param sourceLine The exact line of code where the call is made (trimmed of whitespace)
 */
case class CallSite(
  signature: String,
  sourceLine: String
)