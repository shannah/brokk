package io.github.jbellis.brokk.analyzer

import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.joern.x2cpg.{X2Cpg, ValidationMode}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{Method, TypeDecl}
import io.shiftleft.semanticcpg.language.*
import io.joern.c2cpg.{C2Cpg, Config as CConfig}
import io.joern.x2cpg.passes.frontend.MetaDataPass
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import java.io.IOException
import java.nio.file.Path
import scala.util.matching.Regex
import scala.util.Try

/** Analyzer for C and C++ source files (leveraging joern c2cpg). */
class CppAnalyzer private(sourcePath: Path, cpgInit: Cpg)
  extends AbstractAnalyzer(sourcePath, cpgInit) {

  def this(sourcePath: Path, preloadedPath: Path) =
    this(sourcePath, io.joern.joerncli.CpgBasedTool.loadFromFile(preloadedPath.toString))

  def this(sourcePath: Path, excludedFiles: java.util.Set[String]) =
    this(sourcePath, CppAnalyzer.createNewCpgForSource(sourcePath, excludedFiles))

  def this(sourcePath: Path) = this(sourcePath, java.util.Collections.emptySet[String]())

  override def isCpg: Boolean = true

  //---------------------------------------------------------------------
  // Language-specific helpers
  //---------------------------------------------------------------------

  override protected def methodSignature(m: Method): String = {
    // Very simple signature renderer for now – just return type and name.
    val returnType = sanitizeType(m.methodReturn.typeFullName)
    val params = m.parameter.sortBy(_.order).filterNot(_.name == "this").l
      .map(p => s"${sanitizeType(p.typeFullName)} ${p.name}")
      .mkString(", ")
    s"$returnType ${m.name}($params)"
  }

  /**
   * Strip c2cpg duplicate suffix and signature from full names.
   * <name>:<sig> or <name><duplicate>n:sig
   */
  override private[brokk] def resolveMethodName(methodName: String): String = {
    val noSig = methodName.takeWhile(_ != ':')
    val dupSuffixIdx = noSig.indexOf(io.joern.c2cpg.astcreation.Defines.DuplicateSuffix)
    if dupSuffixIdx >= 0 then noSig.substring(0, dupSuffixIdx) else noSig
  }

  override private[brokk] def sanitizeType(t: String): String = {
    // Basic sanitization: drop namespaces and struct/enum keywords
    if (t == null) return ""
    val noArray = if t.endsWith("[]") then sanitizeType(t.dropRight(2)) + "[]" else t
    val base = noArray
      .replaceFirst("^struct ", "")
      .replaceFirst("^enum ", "")
      .replaceFirst("^union ", "")
      .replaceAll("::", ".") // unify separator with dot like in CPG
    val shortName = base.split("[. ]").lastOption.getOrElse(base)
    shortName
  }

  override protected def methodsFromName(resolvedMethodName: String): List[Method] = {
    val escaped = Regex.quote(resolvedMethodName)
    cpg.method.fullName(s"$escaped:.*").l
  }

  override protected def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder
    val typeKeyword = if td.inheritsFromTypeFullName.nonEmpty then "class" else "struct"
    sb.append("  " * indent).append(typeKeyword).append(" ").append(sanitizeType(td.name)).append(" {\n")
    // members
    td.member.foreach { f =>
      sb.append("  " * (indent + 1)).append(s"${sanitizeType(f.typeFullName)} ${f.name};\n")
    }
    // methods
    td.method.foreach { m =>
      sb.append("  " * (indent + 1)).append(methodSignature(m)).append(" {...}\n")
    }
    sb.append("  " * indent).append("}")
    sb.toString
  }

  protected[analyzer] def parseFqName(fqName: String, expectedType: CodeUnitType): CodeUnit.Tuple3[String,String,String] = {
    if (fqName == null || fqName.isEmpty) return new CodeUnit.Tuple3("", "", "")

    // First: exact class match if caller expects a class
    if (expectedType == CodeUnitType.CLASS && cpg.typeDecl.fullNameExact(fqName).nonEmpty) {
      val lastDot = fqName.lastIndexOf('.')
      val (pkg, cls) = if (lastDot == -1) ("", fqName) else (fqName.substring(0,lastDot), fqName.substring(lastDot+1))
      return new CodeUnit.Tuple3(pkg, cls, "")
    }

    val lastDot = fqName.lastIndexOf('.')
    if (lastDot == -1) {
      // no dots at all -> could be global function or class in default namespace
      expectedType match {
        case CodeUnitType.CLASS => new CodeUnit.Tuple3("", fqName, "")
        case CodeUnitType.FUNCTION | CodeUnitType.FIELD => new CodeUnit.Tuple3("", "", fqName)
        case _ => new CodeUnit.Tuple3("", "", fqName)
      }
    } else {
      val before = fqName.substring(0, lastDot)
      val after  = fqName.substring(lastDot+1)

      // If `before` is a known class/struct in the CPG then treat as class member
      if (cpg.typeDecl.fullNameExact(before).nonEmpty) {
        val pkgSplit = before.lastIndexOf('.')
        val (pkg, cls) = if (pkgSplit == -1) ("", before) else (before.substring(0, pkgSplit), before.substring(pkgSplit+1))
        new CodeUnit.Tuple3(pkg, cls, after)
      } else {
        // Treat as namespace.function  (global or static)
        new CodeUnit.Tuple3(before, "", after)
      }
    }
  }


  override def cuClass(fqcn: String, file: ProjectFile): Option[CodeUnit] = {
    val (pkg, cls, mem) = parseFqName(fqcn, CodeUnitType.CLASS)
    if (cls.isEmpty || mem.nonEmpty) {
        // Log or handle error: Expected class, but parsing resulted in empty class or non-empty member
        None
    } else {
        Try(CodeUnit.cls(file, pkg, cls)).toOption
    }
  }

  override def cuFunction(fqmn: String, file: ProjectFile): Option[CodeUnit] = {
    val (pkg, cls, mem) = parseFqName(fqmn, CodeUnitType.FUNCTION)
     if (mem.isEmpty) {
        // Log or handle error: Expected function, but parsing resulted in empty member name
        None
    } else {
        val shortName = if (cls.nonEmpty) s"$cls.$mem" else mem // For global functions, cls might be empty
        Try(CodeUnit.fn(file, pkg, shortName)).toOption
    }
  }

  override def cuField(fqfn: String, file: ProjectFile): Option[CodeUnit] = {
    val (pkg, cls, mem) = parseFqName(fqfn, CodeUnitType.FIELD)
     if (mem.isEmpty || cls.isEmpty) {
        // Log or handle error: Expected field, but parsing resulted in empty member or class name
        None
    } else {
        val shortName = s"$cls.$mem" // Fields are always part of a class/struct
        Try(CodeUnit.field(file, pkg, shortName)).toOption
    }
  }

  override def isClassInProject(className: String): Boolean = {
    cpg.typeDecl.fullNameExact(className).nonEmpty
  }
}

object CppAnalyzer {
  import scala.jdk.CollectionConverters.*

  private def createNewCpgForSource(sourcePath: Path, excludedFiles: java.util.Set[String]): Cpg = {
    val absPath = sourcePath.toAbsolutePath.normalize()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    val scalaExcluded = excludedFiles.asScala.map(_.toString).toSeq // Ensure strings for c2cpg

    val cfg = CConfig()
      .withInputPath(absPath.toString)
      .withIgnoredFiles(scalaExcluded)
      .withIncludeComments(false)
      // Add any other C/C++ specific configurations needed for c2cpg
      // For example: .withIncludePathsAutoDiscovery(true) or specific defines/includes

    val newCpg = C2Cpg().createCpg(cfg).getOrElse {
      throw new IOException("Failed to create C/C++ CPG")
    }
    X2Cpg.applyDefaultOverlays(newCpg)
    val ctx = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(ctx)
    newCpg
  }
}
