package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.analyzer.{CodeUnit, CodeUnitType}

class CodeUnit(val kind: CodeUnitType, val fqName: String) extends Comparable[CodeUnit] with Serializable {
  def name: String = {
    val lastDotIndex = fqName.lastIndexOf('.')
    if (lastDotIndex == -1) fqName else fqName.substring(lastDotIndex + 1)
  }

  def isClass: Boolean = kind == CodeUnitType.CLASS
  
  def isFunction: Boolean = kind == CodeUnitType.FUNCTION

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
