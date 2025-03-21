package io.github.jbellis.brokk.analyzer

import flatgraph.storage.Serialization
import io.github.jbellis.brokk.*
import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.joern.javasrc2cpg.{Config, JavaSrc2Cpg}
import io.joern.joerncli.CpgBasedTool
import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.codepropertygraph.generated.nodes.{Call, Method, TypeDecl}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import java.io.{Closeable, IOException}
import java.nio.file.Path
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.IterableIsParallelizable
import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.util.Try
import scala.util.matching.Regex

/**
 * An abstract base for language-specific analyzers.
 * It implements the bulk of "IAnalyzer" using Joern's CPG,
 * but delegates language-specific operations (like building a CPG or
 * constructing method signatures) to concrete subclasses.
 */
abstract class AbstractAnalyzer protected (
                                            sourcePath: Path,
                                            protected val cpg: Cpg
                                          ) extends IAnalyzer with Closeable {

  // Convert to absolute filename immediately and verify it's a directory
  protected val absolutePath: Path = {
    val path = sourcePath.toAbsolutePath.toRealPath()
    require(path.toFile.isDirectory, s"Source path must be a directory: $path")
    path
  }

  // implicits at the top, you will regret it otherwise
  protected implicit val ec: ExecutionContext      = ExecutionContext.global
  protected implicit val callResolver: ICallResolver = NoResolve

  // Adjacency maps for pagerank
  private var adjacency: Map[String, Map[String, Int]]  = Map.empty
  private var reverseAdjacency: Map[String, Map[String, Int]] = Map.empty
  private var classesForPagerank: Set[String]           = Set.empty

  initializePageRank()

  /**
   * Secondary constructor: create a new analyzer, loading a pre-built CPG from `preloadedPath`.
   */
  def this(sourcePath: Path, preloadedPath: Path) = {
    this(sourcePath, CpgBasedTool.loadFromFile(preloadedPath.toString))
  }

  /**
   * Simplest constructor: build a brand new CPG for the given source path.
   */
  def this(sourcePath: Path) = {
    this(sourcePath, Cpg.empty)
  }

  /**
   * Utility constructor that can override the language, or any other parameter,
   * if your subclass needs to handle it differently. The default just calls
   * `this(sourcePath)`.
   */
  def this(sourcePath: Path, maybeUnused: Language) = {
    this(sourcePath)
  }

  /**
   * Return the method signature as a language-appropriate String,
   * e.g. for Java: "public int foo(String bar)"
   */
  protected def methodSignature(m: Method): String

  /**
   * Transform method node fullName to a stable "resolved" name
   * (e.g. removing lambda suffixes).
   * For Java, e.g. "com.foo.Bar$1" -> "com.foo.Bar".
   */
  protected def resolveMethodName(methodName: String): String

  /**
   * Possibly remove package names from a type string, or do
   * other language-specific cleanup.
   */
  protected def sanitizeType(t: String): String

  /**
   * Return all Method nodes that match the given fully qualified method name
   * (subclasses can handle language-specific naming).
   */
  protected def methodsFromName(resolvedMethodName: String): List[Method]

  /**
   * Optional final override if the notion of "class in project" differs
   * by language. By default, we check that the typeDecl is present
   * and has some members or methods.
   */
  override def isClassInProject(className: String): Boolean = {
    val td = cpg.typeDecl.fullNameExact(className).l
    td.nonEmpty && !(td.member.isEmpty && td.method.isEmpty && td.derivedTypeDecl.isEmpty)
  }

  private def initializePageRank(): Unit = {
    if (cpg.metaData.headOption.isEmpty) {
      throw new IllegalStateException("CPG root not found for " + absolutePath)
    }
    // Initialize adjacency map
    adjacency = buildWeightedAdjacency()

    // Build reverse adjacency from the forward adjacency
    val reverseMap = TrieMap[String, TrieMap[String, Int]]()
    adjacency.par.foreach { case (src, tgtMap) =>
      tgtMap.foreach { case (dst, weight) =>
        val targetMap = reverseMap.getOrElseUpdate(dst, TrieMap.empty)
        targetMap.update(src, targetMap.getOrElse(src, 0) + weight)
      }
    }
    reverseAdjacency = reverseMap.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap

    classesForPagerank = (adjacency.keys ++ adjacency.values.flatMap(_.keys)).toSet
  }

  /**
   * Gets the source code for a given method name. If multiple methods match,
   * returns them all concatenated. If none match, returns None.
   */
  override def getMethodSource(methodName: String): Option[String] = {
    val resolvedMethodName = resolveMethodName(methodName)
    val methods = methodsFromName(resolvedMethodName)

    // static constructors often lack line info
    val sources = methods.flatMap { method =>
      for {
        file <- toFile(method.filename)
        startLine <- method.lineNumber
        endLine   <- method.lineNumberEnd
      } yield {
        scala.util.Using(Source.fromFile(file.absPath().toFile)) { source =>
          source
            .getLines()
            .slice(startLine - 1, endLine)
            .mkString("\n")
        }.toOption
      }
    }.flatten

    if (sources.isEmpty) None
    else Some(sources.mkString("\n\n"))
  }

  /**
   * Gets the source code for the entire file containing a class.
   */
  override def getClassSource(className: String): java.lang.String = {
    var classNodes = cpg.typeDecl.fullNameExact(className).l

    // If no exact match, try fuzzy matching
    if (classNodes.isEmpty) {
      // Attempt by simple name
      val simpleClassName = className.split("[.$]").last
      val nameMatches = cpg.typeDecl.name(simpleClassName).l

      if (nameMatches.size == 1) {
        classNodes = nameMatches
      } else if (nameMatches.size > 1) {
        // Second attempt: try replacing $ with .
        val dotClassName = className.replace('$', '.')
        val dotMatches = nameMatches.filter(td => td.fullName.replace('$', '.') == dotClassName)
        if (dotMatches.size == 1) {
          classNodes = dotMatches
        }
      }
    }

    if (classNodes.isEmpty) {
      return null
    }

    val td     = classNodes.head
    val fileOpt = toFile(td.filename)
    if (fileOpt.isEmpty) {
      return null
    }

    val file = fileOpt.get
    val sourceOpt = scala.util.Using(Source.fromFile(file.absPath().toFile)) { _.mkString }.toOption
    sourceOpt.orNull
  }

  /**
   * Recursively builds a structural "skeleton" for a given TypeDecl.
   */
  private def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder

    val className = sanitizeType(td.name)
    sb.append(indentStr(indent))
      .append("class ")
      .append(className)
      .append(" {\n")

    // Methods: skip any whose name starts with "<lambda>"
    td.method
      .filterNot(_.name.startsWith("<lambda>"))
      .foreach { m =>
        sb.append(indentStr(indent + 1))
          .append(methodSignature(m))
          .append(" {...}\n")
      }

    // Fields: skip any whose name is exactly "outerClass"
    td.member
      .filterNot(_.name == "outerClass")
      .foreach { f =>
        sb.append(indentStr(indent + 1))
          .append(s"${sanitizeType(f.typeFullName)} ${f.name};\n")
      }

    // Nested classes: skip any named "<lambda>N" or purely numeric suffix
    td.astChildren.isTypeDecl.filterNot { nested =>
      nested.name.startsWith("<lambda>") ||
        nested.name.split("\\$").exists(_.forall(_.isDigit))
    }.foreach { nested =>
      sb.append(outlineTypeDecl(nested, indent + 1)).append("\n")
    }

    sb.append(indentStr(indent)).append("}")
    sb.toString
  }

  private def indentStr(level: Int) = "  " * level

  /**
   * Builds a structural skeleton for a given class by name (simple or FQCN).
   */
  override def getSkeleton(className: String): Option[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) {
      return None
    }
    val td = decls.head
    Some(outlineTypeDecl(td))
  }

  /**
   * Build a weighted adjacency map at the class level: className -> Map[targetClassName -> weight].
   */
  protected def buildWeightedAdjacency()(implicit callResolver: ICallResolver): Map[String, Map[String, Int]] = {
    val adjacency = TrieMap[String, TrieMap[String, Int]]()

    // Common Java/primitive types we want to skip
    val primitiveTypes = Set(
      "byte", "short", "int", "long", "float", "double",
      "boolean", "char", "void"
    )

    def isRelevant(t: String): Boolean =
      !primitiveTypes.contains(t) && !t.startsWith("java.")

    val typeDecls = cpg.typeDecl.l
    // Build adjacency in parallel
    typeDecls.par.foreach { td =>
      val sourceClass = td.fullName

      // (1) Collect calls
      td.method.call.callee.typeDecl.fullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { tc =>
          increment(adjacency, sourceClass, tc)
        }

      // (2) Collect field references
      td.member.typeFullName
        .map(_.replaceAll("""\[\]""", "")) // array -> base
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { fieldClass =>
          increment(adjacency, sourceClass, fieldClass)
        }

      // (3) Collect "extends"/"implements" edges; these count 5x
      td.inheritsFromTypeFullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { parentClass =>
          increment(adjacency, sourceClass, parentClass, 5)
        }
    }

    adjacency.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap
  }

  /**
   * Increment a (source -> target) edge by `count` in an adjacency map.
   */
  protected def increment(
                           map: TrieMap[String, TrieMap[String, Int]],
                           source: String,
                           target: String,
                           count: Int = 1
                         ): Unit = {
    val targetMap = map.getOrElseUpdate(source, TrieMap.empty)
    targetMap.update(target, targetMap.getOrElse(target, 0) + count)
  }

  override def pathOf(codeUnit: CodeUnit): Option[RepoFile] = {
    if (codeUnit.isClass) {
      cpg.typeDecl.fullNameExact(codeUnit.fqName).headOption.flatMap(toFile)
    } else if (codeUnit.isFunction) {
      val className = codeUnit.fqName.split("\\.").dropRight(1).mkString(".")
      cpg.typeDecl.fullNameExact(className).headOption.flatMap(toFile)
    } else {
      val className = codeUnit.fqName.split("\\.").dropRight(1).mkString(".")
      cpg.typeDecl.fullNameExact(className).headOption.flatMap(toFile)
    }
  }

  private def toFile(td: TypeDecl): Option[RepoFile] = {
    if (td.filename.isEmpty || td.filename == "<empty>" || td.filename == "<unknown>") {
      None
    } else {
      toFile(td.filename)
    }
  }

  protected def toFile(relName: String): Option[RepoFile] = {
    Some(RepoFile(absolutePath, relName))
  }

  // using cpg.all doesn't work because there are always-present nodes for files and the ANY typedecl
  override def isEmpty: Boolean = cpg.member.isEmpty

  override def getAllClasses: java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    val results = cpg.typeDecl
      .filter(toFile(_).isDefined)
      .fullName
      .l
      .map(CodeUnit.cls)
    results.asJava
  }

  /**
   * Returns just the class signature and field declarations, without method details.
   */
  override def getSkeletonHeader(className: String): Option[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) {
      return None
    }
    val td = decls.head
    Some(headerString(td, 0) + "  [... methods not shown ...]\n}")
  }

  private def headerString(td: TypeDecl, indent: Int): String = {
    val sb = new StringBuilder
    val className = sanitizeType(td.name)

    sb.append(indentStr(indent))
      .append("class ")
      .append(className)
      .append(" {\n")

    // Add fields
    td.member.foreach { m =>
      val modifiers = m.modifier.map(_.modifierType.toLowerCase).filter(_.nonEmpty).mkString(" ")
      val modString = if (modifiers.nonEmpty) modifiers + " " else ""
      val typeName  = sanitizeType(m.typeFullName)
      sb.append(indentStr(indent + 1))
        .append(s"$modString$typeName ${m.name};\n")
    }

    sb.toString
  }

  override def getMembersInClass(className: String): java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    val matches = cpg.typeDecl.fullNameExact(className)
    if (matches.isEmpty) {
      throw new IllegalArgumentException(s"Class '$className' not found")
    }
    val typeDecl = matches.head

    // Get all method declarations
    val methods = typeDecl.method
      .filterNot(_.name.startsWith("<")) // skip ctors
      .fullName
      .l
      .map(chopColon)
      .map(CodeUnit.fn)

    // Get all field declarations
    val fields = typeDecl.member.name.l.map(name => CodeUnit.field(className + "." + name))

    // Get all nested types
    val nestedPrefix = className + "\\$.*"
    val nestedTypes = cpg.typeDecl.fullName(nestedPrefix).fullName.l.map(CodeUnit.cls)

    (methods ++ fields ++ nestedTypes).asJava
  }

  private def chopColon(full: String) = full.split(":").head

  /**
   * For a given method node `m`, returns the calling method .fullName (with the suffix after `:` chopped).
   * If `excludeSelfRefs` is true, we skip callers whose TypeDecl matches `m.typeDecl`.
   */
  protected def callersOfMethodNode(m: Method, excludeSelfRefs: Boolean): List[String] = {
    var calls = m.callIn
    if (excludeSelfRefs) {
      val selfSource = m.typeDecl.fullName.head
      calls = calls.filterNot(call => {
        val callerSource = call.method.typeDecl.fullName.head
        partOfClass(selfSource, callerSource)
      })
    }
    calls
      .method
      .fullName
      .map(name => resolveMethodName(chopColon(name)))
      .distinct
      .l
  }

  protected def partOfClass(parentFqcn: String, childFqcn: String): Boolean = {
    childFqcn == parentFqcn || childFqcn.startsWith(parentFqcn + ".") || childFqcn.startsWith(parentFqcn + "$")
  }

  /**
   * Return all methods that reference a given field "classFullName.fieldName".
   */
  protected def referencesToField(
                                   selfSource: String,
                                   fieldName: String,
                                   excludeSelfRefs: Boolean
                                 ): List[String] = {
    var calls = cpg.call
      .nameExact("<operator>.fieldAccess")
      .where(_.argument(1).typ.fullNameExact(selfSource))
      .where(_.argument(2).codeExact(fieldName))

    if (excludeSelfRefs) {
      calls = calls.filterNot(call => {
        partOfClass(selfSource, call.method.typeDecl.fullName.head)
      })
    }
    calls
      .method
      .fullName
      .map(x => resolveMethodName(chopColon(x)))
      .distinct
      .l
  }

  /**
   * Find references to a class used as a type:
   * - fields typed with that class
   * - parameters/locals typed with that class
   * - classes that inherit from that class
   */
  protected def referencesToClassAsType(classFullName: String): List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*

    val typePattern = "^" + Regex.quote(classFullName) + "(\\$.*)?(\\[\\])?"

    // Fields typed with this class → return as field CodeUnits
    val fieldRefs = cpg.member
      .typeFullName(typePattern)
      .astParent
      .isTypeDecl
      .filter(td => !partOfClass(classFullName, td.fullName))
      .flatMap { td =>
        td.member.typeFullName(typePattern).map { member =>
          CodeUnit.field(s"${td.fullName}.${member.name}")
        }.l
      }

    // Params typed with this class → return as function CodeUnits
    val paramRefs = cpg.parameter
      .typeFullName(typePattern)
      .method
      .filter(m => !partOfClass(classFullName, m.typeDecl.fullName.l.head))
      .fullName
      .map(chopColon)
      .map(resolveMethodName)
      .map(CodeUnit.fn)
      .l

    // Locals typed with this class
    val localRefs = cpg.local
      .typeFullName(typePattern)
      .method
      .filter(m => !partOfClass(classFullName, m.typeDecl.fullName.l.head))
      .fullName
      .map(chopColon)
      .map(resolveMethodName)
      .map(CodeUnit.fn)
      .l

    // Classes that inherit from this class
    val inheritingClasses = cpg.typeDecl
      .filter(_.inheritsFromTypeFullName.contains(classFullName))
      .filter(td => !partOfClass(classFullName, td.fullName))
      .fullName
      .map(CodeUnit.cls)
      .l

    (fieldRefs ++ paramRefs ++ localRefs ++ inheritingClasses).toList.distinct
  }

  override def getUses(symbol: String): java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*

    // (1) Method matches?
    val methodMatches = methodsFromName(symbol)
    if (methodMatches.nonEmpty) {
      // collect all callers
      val calls = methodMatches.flatMap(m => callersOfMethodNode(m, excludeSelfRefs = false)).distinct
      return calls.map(CodeUnit.fn).asJava
    }

    // (2) Possibly a field: com.foo.Bar.field
    val lastDot = symbol.lastIndexOf('.')
    if (lastDot > 0) {
      val classPart = symbol.substring(0, lastDot)
      val fieldPart = symbol.substring(lastDot + 1)
      val clsDecls  = cpg.typeDecl.fullNameExact(classPart).l

      if (clsDecls.nonEmpty) {
        val maybeFieldDecl = clsDecls.head.member.nameExact(fieldPart).l
        if (maybeFieldDecl.nonEmpty) {
          val refs = referencesToField(classPart, fieldPart, excludeSelfRefs = false)
          return refs.map(CodeUnit.fn).asJava
        }
      }
    }

    // (3) Otherwise treat as a class
    val classDecls = cpg.typeDecl.fullNameExact(symbol).l
    if (classDecls.isEmpty) {
      throw new IllegalArgumentException(
        s"Symbol '$symbol' not found as a method, field, or class"
      )
    }

    // (3a) Method references from the same class, but exclude self references
    val methodUses = classDecls.flatMap(td => td.method.l.flatMap(m => callersOfMethodNode(m, true)))

    // (3b) Field references from the same class, exclude self references
    val fieldRefUses = classDecls.flatMap(td =>
      td.member.l.flatMap(mem => referencesToField(td.fullName, mem.name, excludeSelfRefs = true))
    )

    // (3c) Type references
    val typeUses = referencesToClassAsType(symbol)

    (methodUses ++ fieldRefUses)
      .map(CodeUnit.fn)
      .distinct
      .++(typeUses)
      .distinct
      .asJava
  }

  /**
   * Builds either a forward or reverse call graph from a starting method up to a given depth.
   */
  private def buildCallGraph(
                              startingMethod: String,
                              isIncoming: Boolean,
                              maxDepth: Int
                            ): java.util.Map[String, java.util.List[CallSite]] = {

    import scala.jdk.CollectionConverters.*

    val result = new java.util.HashMap[String, java.util.List[CallSite]]()

    // Gather the actual Method nodes that match startingMethod
    val startMethods = cpg.method.filter(m => chopColon(m.fullName) == startingMethod).l
    if (startMethods.isEmpty) {
      return result
    }

    val visited = mutable.Set[String]()
    val startMethodNames = startMethods.map(m => resolveMethodName(chopColon(m.fullName))).toSet
    visited ++= startMethodNames

    def shouldIncludeMethod(methodName: String): Boolean = {
      !methodName.startsWith("<operator>") &&
        !methodName.startsWith("java.") &&
        !methodName.startsWith("javax.") &&
        !methodName.startsWith("sun.") &&
        !methodName.startsWith("com.sun.")
    }

    def getSourceLine(call: io.shiftleft.codepropertygraph.generated.nodes.Call): String = {
      call.code.trim.replaceFirst("^this\\.", "")
    }

    def addCallSite(methodName: String, callSite: CallSite): Unit = {
      var callSites = result.get(methodName)
      if (callSites == null) {
        callSites = new java.util.ArrayList[CallSite]()
        result.put(methodName, callSites)
      }
      callSites.add(callSite)
    }

    def explore(methods: List[Method], currentDepth: Int): Unit = {
      if (currentDepth > maxDepth || methods.isEmpty) return

      val nextMethods = mutable.ListBuffer[Method]()

      methods.foreach { method =>
        val methodName = resolveMethodName(chopColon(method.fullName))
        val calls      = if (isIncoming) method.callIn.l else method.call.l

        calls.foreach { call =>
          if (isIncoming) {
            // The caller is the next method
            val callerMethod = call.method
            val callerName   = resolveMethodName(chopColon(callerMethod.fullName))

            if (!visited.contains(callerName) && shouldIncludeMethod(callerName)) {
              val sourceLine = getSourceLine(call)
              addCallSite(methodName, CallSite(CodeUnit.fn(callerName), sourceLine))
              visited += callerName
              nextMethods += callerMethod
            }
          } else {
            // The callee is the next method
            val calleeFullName = chopColon(call.methodFullName)
            val calleeName     = resolveMethodName(calleeFullName)

            if (!visited.contains(calleeName) && shouldIncludeMethod(calleeName)) {
              val calleePattern  = s"""^${Regex.quote(calleeFullName)}.*"""
              val calleeMethods  = cpg.method.fullName(calleePattern).l

              val sourceLine     = getSourceLine(call)
              addCallSite(methodName, CallSite(CodeUnit.fn(calleeName), sourceLine))

              visited += calleeName
              if (calleeMethods.nonEmpty) {
                nextMethods ++= calleeMethods
              }
            }
          }
        }
      }

      explore(nextMethods.toList, currentDepth + 1)
    }

    explore(startMethods, 1)
    result
  }

  override def getCallgraphTo(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] = {
    val resolvedMethodName = resolveMethodName(methodName)
    buildCallGraph(resolvedMethodName, isIncoming = true, maxDepth = depth)
  }

  override def getCallgraphFrom(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] = {
    val resolvedMethodName = resolveMethodName(methodName)
    buildCallGraph(resolvedMethodName, isIncoming = false, maxDepth = depth)
  }

  override def getDefinitions(pattern: String): java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    val ciPattern = "(?i)" + pattern // case-insensitive

    // Classes
    val matchingClasses = cpg.typeDecl
      .name(ciPattern)
      .fullName
      .filter(isClassInProject)
      .map(CodeUnit.cls)
      .l

    // Methods
    val matchingMethods = cpg.method
      .nameNot("<.*>")
      .name(ciPattern)
      .filter(m => {
        val typeNameOpt = m.typeDecl.fullName.headOption
        typeNameOpt.exists(isClassInProject)
      })
      .map(m => CodeUnit.fn(resolveMethodName(chopColon(m.fullName))))
      .l

    // Fields
    val matchingFields = cpg.member
      .name(ciPattern)
      .filter(f => {
        val typeNameOpt = f.typeDecl.fullName.headOption
        typeNameOpt.exists(tn => isClassInProject(tn.toString))
      })
      .map(f => {
        val className = f.typeDecl.fullName.headOption.getOrElse("").toString
        CodeUnit.field(s"$className.${f.name}")
      })
      .l

    (matchingClasses ++ matchingMethods ++ matchingFields).asJava
  }

  /**
   * Weighted PageRank at the class level. If seedClassWeights is non-empty,
   * seeds are assigned according to those weights. Otherwise, all classes
   * are seeded equally.
   */
  override def getPagerank(
                            seedClassWeights: java.util.Map[String, java.lang.Double],
                            k: Int,
                            reversed: Boolean
                          ): java.util.List[(String, java.lang.Double)] = {

    import scala.jdk.CollectionConverters.*

    val seedWeights = seedClassWeights.asScala.view.mapValues(_.doubleValue()).toMap
    val seedSeq     = seedWeights.keys.toSeq

    // restrict to classes that are in the graph
    var validSeeds = seedSeq.filter(classesForPagerank.contains)
    if (validSeeds.isEmpty) {
      validSeeds = classesForPagerank.toSeq
    }

    val danglingNodes = classesForPagerank.par.filter { c =>
      adjacency.get(c).forall(_.isEmpty)
    }

    val damping = 0.85
    val epsilon = 1e-4
    val maxIter = 50

    val scores     = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0)*)
    val nextScores = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0)*)

    val totalWeight = seedWeights.values.sum
    validSeeds.foreach { c =>
      scores(c) = seedWeights.getOrElse(c, 0.0) / (if (totalWeight == 0) 1 else totalWeight)
    }

    var iteration = 0
    var diffSum   = Double.MaxValue

    while (iteration < maxIter && diffSum > epsilon) {
      // zero nextScores
      classesForPagerank.par.foreach { c => nextScores(c) = 0.0 }

      val localDiffs = classesForPagerank.par.map { node =>
        val (inMap, outMap) = if (reversed) (adjacency, reverseAdjacency) else (reverseAdjacency, adjacency)
        val inboundSum = inMap
          .get(node)
          .map { inboundMap =>
            inboundMap.foldLeft(0.0) { case (acc, (u, weight)) =>
              val outLinks  = outMap(u)
              val outWeight = outLinks.values.sum.max(1)
              acc + (scores(u) * weight / outWeight)
            }
          }.getOrElse(0.0)

        var newScore = damping * inboundSum
        if (validSeeds.contains(node)) {
          newScore += (1.0 - damping) * (seedWeights.getOrElse(node, 0.0) / (if (totalWeight == 0) 1 else totalWeight))
        }
        nextScores(node) = newScore
        math.abs(scores(node) - newScore)
      }.sum

      // handle dangling nodes
      val danglingScore = danglingNodes.par.map(scores).sum
      if (danglingScore > 0.0 && validSeeds.nonEmpty) {
        validSeeds.par.foreach { seed =>
          val weight = seedWeights.getOrElse(seed, 0.0) / (if (totalWeight == 0) 1 else totalWeight)
          nextScores(seed) += damping * danglingScore * weight
        }
        // zero out dangling
        danglingNodes.par.foreach { dn =>
          nextScores(dn) = 0.0
        }
      }

      diffSum = localDiffs
      classesForPagerank.foreach { node =>
        scores(node) = nextScores(node)
      }

      iteration += 1
    }

    val sortedAll         = scores.toList.sortBy { case (_, s) => -s }
    val filteredSortedAll = sortedAll.filterNot { case (cls, _) => seedSeq.exists(seed => partOfClass(seed, cls)) }

    def coalesceInnerClasses(initial: List[(String, Double)], k: Int): mutable.Buffer[(String, Double)] = {
      var results = initial.take(k).toBuffer
      var offset  = k
      var changed = true

      while (changed) {
        changed = false
        val toRemove = mutable.Set[String]()

        for (i <- results.indices) {
          val (pClass, _) = results(i)
          for (j <- results.indices if j != i) {
            val (cClass, _) = results(j)
            if (partOfClass(pClass, cClass)) {
              toRemove += cClass
            }
          }
        }

        if (toRemove.nonEmpty) {
          changed = true
          results = results.filterNot { case (cls, _) => toRemove.contains(cls) }
        }

        while (results.size < k && offset < initial.size) {
          val candidate@(cls, _) = initial(offset)
          offset += 1
          if (!results.exists(_._1 == cls) && !toRemove.contains(cls)) {
            results += candidate
            changed = true
          }
        }
      }
      results
    }

    import scala.jdk.CollectionConverters.*
    coalesceInnerClasses(filteredSortedAll, k)
      .map { case (s, d) => (s, java.lang.Double.valueOf(d)) }
      .asJava
  }

  /**
   * Gets all classes in a given file.
   */
  override def getClassesInFile(file: RepoFile): java.util.Set[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    val matches = cpg.typeDecl.l.filter(td => toFile(td).contains(file))
    matches.map(td => CodeUnit.cls(td.fullName)).toSet.asJava
  }

  /**
   * Write the underlying CPG to the specified path.
   */
  def writeCpg(path: java.nio.file.Path): Unit = {
    Serialization.writeGraph(cpg.graph, path)
  }

  override def close(): Unit = {
    cpg.close()
  }
}

/**
 * A concrete analyzer for Java source code, extending AbstractAnalyzer
 * with Java-specific logic for building the CPG, method signatures, etc.
 */
class JavaAnalyzer private (
                             sourcePath: Path,
                             cpgInit: Cpg
                           ) extends AbstractAnalyzer(sourcePath, cpgInit) {

  import io.joern.javasrc2cpg.{JavaSrc2Cpg, Config => JavaConfig}
  import scala.util.Try
  import java.io.IOException
  import scala.collection.parallel.CollectionConverters.IterableIsParallelizable

  // We keep the same constructors as the original Analyzer for drop-in replacement.

  def this(sourcePath: Path, preloadedPath: Path) = {
    this(sourcePath, CpgBasedTool.loadFromFile(preloadedPath.toString))
  }

  def this(sourcePath: Path) = {
    this(sourcePath, JavaAnalyzer.createNewCpgForSource(sourcePath)) // Give parent a dummy/empty CPG.
  }

  def this(sourcePath: Path, language: Language) = {
    this(sourcePath) // we ignore language or pass it through if needed
  }

  def this(sourcePath: Path, preloadedPath: Path, language: Language) = {
    this(sourcePath, preloadedPath)
  }

  /**
   * Java-specific method signature builder.
   */
  override protected def methodSignature(m: Method): String = {
    val knownModifiers = Map(
      "public" -> "public",
      "private" -> "private",
      "protected" -> "protected",
      "static" -> "static",
      "final" -> "final",
      "abstract" -> "abstract",
      "native" -> "native",
      "synchronized" -> "synchronized"
    )

    val modifiers = m.modifier.map { modNode =>
      val lower = modNode.modifierType.toLowerCase
      knownModifiers.getOrElse(lower, "")
    }.filter(_.nonEmpty)

    val modString  = if (modifiers.nonEmpty) modifiers.mkString(" ") + " " else ""
    val returnType = sanitizeType(m.methodReturn.typeFullName)
    val paramList = m.parameter
      .sortBy(_.order)
      .filterNot(_.name == "this")
      .l
      .map { p => s"${sanitizeType(p.typeFullName)} ${p.name}" }
      .mkString(", ")

    s"$modString$returnType ${m.name}($paramList)"
  }

  /**
   * Java-specific logic for removing lambda suffixes, nested class numeric suffixes, etc.
   */
  override protected def resolveMethodName(methodName: String): String = {
    val segments = methodName.split("\\.")
    val idx      = segments.indexWhere(_.matches(".*\\$\\d+$"))
    val relevant = if (idx == -1) segments else segments.take(idx)
    relevant.mkString(".")
  }

  /**
   * Java often needs type short names, removing packages, arrays, generics, etc.
   */
  override protected def sanitizeType(t: String): String = {
    def processType(input: String): String = {
      val isArray = input.endsWith("[]")
      val base    = if (isArray) input.dropRight(2) else input
      val shortName = base.split("\\.").lastOption.getOrElse(base)
      if (isArray) s"$shortName[]" else shortName
    }

    if (t.contains("<")) {
      val mainType    = t.substring(0, t.indexOf("<"))
      val genericPart = t.substring(t.indexOf("<") + 1, t.lastIndexOf(">"))
      val processedMain = processType(mainType)
      val processedParams = genericPart.split(",").map { param =>
        val trimmed = param.trim
        if (trimmed.contains("<")) sanitizeType(trimmed)
        else processType(trimmed)
      }.mkString(", ")
      s"$processedMain<$processedParams>"
    } else {
      processType(t)
    }
  }

  /**
   * Java-specific way of finding methods from a method fullName.
   */
  override protected def methodsFromName(resolvedMethodName: String): List[Method] = {
    // For Java: cpg.method.fullName("ClassName.methodName:ReturnType(...)")
    val escaped = Regex.quote(resolvedMethodName)
    cpg.method.fullName(escaped + ":.*").l
  }

  /**
   * Java-specific version of "class in project" check
   * (falls back to the default if needed).
   */
  override def isClassInProject(className: String): Boolean = {
    // We can either rely on the default or do more checks if desired.
    super.isClassInProject(className)
  }
}

object JavaAnalyzer {
  private def createNewCpgForSource(sourcePath: Path): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    // Build the CPG
    val config = Config()
      .withInputPath(absPath.toString)
      .withEnableTypeRecovery(true)
    
    val newCpg = JavaSrc2Cpg().createCpg(config).getOrElse {
      throw new IOException("Failed to create Java CPG")
    }

    X2Cpg.applyDefaultOverlays(newCpg)
    val context = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(context)
    newCpg
  }
}

