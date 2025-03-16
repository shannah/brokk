package io.github.jbellis.brokk

class CodeUnit(val kind: CodeUnit.CodeUnitType, val fqName: String) extends Comparable[CodeUnit] with Serializable {
  def name: String = {
    val lastDotIndex = fqName.lastIndexOf('.')
    if (lastDotIndex == -1) fqName else fqName.substring(lastDotIndex + 1)
  }

  def isClass: Boolean = kind == CodeUnit.CodeUnitType.CLASS
  
  def isFunction: Boolean = kind == CodeUnit.CodeUnitType.FUNCTION

  override def toString: String = kind match {
    case CodeUnit.CodeUnitType.CLASS => s"CLASS[$fqName]"
    case CodeUnit.CodeUnitType.FUNCTION => s"FUNCTION[$fqName]"
    case CodeUnit.CodeUnitType.FIELD => s"FIELD[$fqName]"
  }

  override def hashCode(): Int = fqName.hashCode()

  override def equals(obj: Any): Boolean = obj.isInstanceOf[CodeUnit] && this.fqName == obj.asInstanceOf[CodeUnit].fqName

  override def compareTo(other: CodeUnit): Int = this.fqName.compareTo(other.fqName)
}

object CodeUnit {
  enum CodeUnitType {
    case CLASS, FUNCTION, FIELD
  }

  def cls(reference: String): CodeUnit = new CodeUnit(CodeUnitType.CLASS, reference)

  def fn(reference: String): CodeUnit = new CodeUnit(CodeUnitType.FUNCTION, reference)

  def field(reference: String): CodeUnit = new CodeUnit(CodeUnitType.FIELD, reference)
}
