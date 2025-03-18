package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.analyzer.{CodeUnit, CodeUnitType}

class CodeUnit(val kind: CodeUnitType, val fqName: String) extends Comparable[CodeUnit] with Serializable {
  /**
   * @return just the last symbol name (a.b.C -> C, a.b.C.foo -> foo)
   */
  def name: String = {
    val lastDotIndex = fqName.lastIndexOf('.')
    if (lastDotIndex == -1) fqName else fqName.substring(lastDotIndex + 1)
  }

  /**
   * @return for classes: just the class name
   *         for functions and fields: className.memberName (last two components)
   */
  def shortName: String = {
    val parts = fqName.split("\\.")
    kind match {
      case CodeUnitType.CLASS => parts.last
      case _ => if (parts.length >= 2) {
        s"${parts(parts.length - 2)}.${parts.last}"
      } else {
        parts.last
      }
    }
  }

  def isClass: Boolean = kind == CodeUnitType.CLASS
  
  def isFunction: Boolean = kind == CodeUnitType.FUNCTION

  /**
   * @return the package name part of the fully qualified name, excluding the shortName
   */
  def packageName: String = {
    kind match {
      case CodeUnitType.CLASS =>
        val lastDotIndex = fqName.lastIndexOf('.')
        if (lastDotIndex == -1) "" else fqName.substring(0, lastDotIndex)
      case _ =>
        val parts = fqName.split("\\.")
        if (parts.length <= 2) ""
        else parts.dropRight(2).mkString(".")
    }
  }

  override def toString: String = kind match {
    case CodeUnitType.CLASS => s"CLASS[$fqName]"
    case CodeUnitType.FUNCTION => s"FUNCTION[$fqName]"
    case CodeUnitType.FIELD => s"FIELD[$fqName]"
  }

  override def hashCode(): Int = fqName.hashCode()

  override def equals(obj: Any): Boolean = obj.isInstanceOf[CodeUnit] && this.fqName == obj.asInstanceOf[CodeUnit].fqName

  override def compareTo(other: CodeUnit): Int = this.fqName.compareTo(other.fqName)
}

object CodeUnit {
  def cls(reference: String): CodeUnit = new CodeUnit(CodeUnitType.CLASS, reference)
  def fn(reference: String): CodeUnit = new CodeUnit(CodeUnitType.FUNCTION, reference)
  def field(reference: String): CodeUnit = new CodeUnit(CodeUnitType.FIELD, reference)
}
