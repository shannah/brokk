package io.github.jbellis.brokk

sealed trait CodeUnit extends Comparable[CodeUnit] with Serializable {
  def fqName: String
  
  def name: String = {
    val lastDotIndex = fqName.lastIndexOf('.')
    if (lastDotIndex == -1) fqName else fqName.substring(lastDotIndex + 1)
  }

  def isClass: Boolean = this match {
    case _: CodeUnit.ClassType => true
    case _ => false
  }

  def isFunction: Boolean = this match {
    case _: CodeUnit.FunctionType => true
    case _ => false
  }

  override def toString: String = this match {
    case CodeUnit.ClassType(ref) => s"CLASS[$ref]"
    case CodeUnit.FunctionType(ref) => s"FUNCTION[$ref]"
    case CodeUnit.FieldType(ref) => s"FIELD[$ref]"
  }

  override def hashCode(): Int = fqName.hashCode()

  override def equals(obj: Any): Boolean = obj.isInstanceOf[CodeUnit] && this.fqName == obj.asInstanceOf[CodeUnit].fqName

  override def compareTo(other: CodeUnit): Int = this.fqName.compareTo(other.fqName)
}

object CodeUnit {
  case class ClassType(fqName: String) extends CodeUnit

  case class FunctionType(fqName: String) extends CodeUnit

  case class FieldType(fqName: String) extends CodeUnit

  def cls(reference: String): CodeUnit = ClassType(reference)

  def fn(reference: String): CodeUnit = FunctionType(reference)

  def field(reference: String): CodeUnit = FieldType(reference)
}
