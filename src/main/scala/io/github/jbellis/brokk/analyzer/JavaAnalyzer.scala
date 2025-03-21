package io.github.jbellis.brokk.analyzer

import flatgraph.storage.Serialization
import io.github.jbellis.brokk.*
import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.joern.javasrc2cpg.{JavaSrc2Cpg, Config as JavaConfig}
import io.joern.joerncli.CpgBasedTool
import io.joern.pysrc2cpg.Py2Cpg
import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.codepropertygraph.generated.nodes.{Call, Method, TypeDecl}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import java.io.{Closeable, IOException}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.IterableIsParallelizable
import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.jdk.javaapi.CollectionConverters
import scala.util.matching.Regex


class JavaAnalyzer private(sourcePath: java.nio.file.Path, language: Language, cpgInit: Cpg) extends IAnalyzer with Closeable {
  // Convert to absolute filename immediately and verify it's a directory
  private val absolutePath = {
    val path = sourcePath.toAbsolutePath.toRealPath()
    require(path.toFile.isDirectory, s"Source path must be a directory: $path")
    path
  }
  // implicits at the top, you will regret it otherwise
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private implicit val callResolver: ICallResolver = NoResolve

  // Adjacency maps for pagerank
  private var adjacency: Map[String, Map[String, Int]] = Map.empty
  private var reverseAdjacency: Map[String, Map[String, Int]] = Map.empty
  private var classesForPagerank: Set[String] = Set.empty

  private[brokk] var cpg: Cpg = cpgInit
  initializeAnalyzer(cpg)

  def this(sourcePath: java.nio.file.Path, preloadedPath: java.nio.file.Path, language: Language) = {
    this(sourcePath, language, CpgBasedTool.loadFromFile(preloadedPath.toString))
  }

  def this(sourcePath: java.nio.file.Path, language: Language) = {
    this(sourcePath, language, JavaAnalyzer.createNewCpgFor(sourcePath, language))
  }

  def this(sourcePath: java.nio.file.Path) = {
    this(sourcePath, Language.Java)
  }

  def this(sourcePath: java.nio.file.Path, preloadedPath: java.nio.file.Path) = {
    this(sourcePath, preloadedPath, Language.Java)
  }

  private def initializeAnalyzer(initialCpg: Cpg): Unit = {
    cpg = initialCpg
    initializePageRank()
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
   * Return the method signature as a String, including return type, name and parameters
   */
  private[brokk] def methodSignature(m: Method): String = {
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
      .map { p =>
        s"${sanitizeType(p.typeFullName)} ${p.name}"
      }.mkString(", ")

    s"$modString$returnType ${m.name}($paramList)"
  }

  private def methodsFromName(resolvedMethodName: String): List[Method] = {
    // Joern's method names look like this
    //   org.apache.cassandra.db.DeletionPurger.shouldPurge:boolean(org.apache.cassandra.db.DeletionTime)
    // constructor of a nested class:
    //   org.apache.cassandra.db.Directories$SSTableLister.<init>:void(org.apache.cassandra.io.util.File[])
    val escapedMethodName = Regex.quote(resolvedMethodName)
    cpg.method.fullName(escapedMethodName + ":.*").l
  }

  /**
   * transform anonymous method names (closures) into the name of the containing method
   */
  private[brokk] def resolveMethodName(methodName: String): String = {
    val segments = methodName.split("\\.")
    // Find the first occurrence of a segment with $ followed by digits, e.g. "FutureCallback$0"
    val idx = segments.indexWhere(_.matches(".*\\$\\d+$"))
    // Keep everything before that index (or all if none found)
    val relevant = if (idx == -1) segments else segments.take(idx)
    relevant.mkString(".")
  }

  override def getMethodSource(methodName: String): Option[String] = {
    val resolvedMethodName = resolveMethodName(methodName)
    val methods = methodsFromName(resolvedMethodName)

    // static constructors are missing filenames and start/end lines, not much we can do about it
    // https://github.com/joernio/joern/issues/5328
    val sources = methods.flatMap { method =>
      for {
        file <- toFile(method.filename)
        startLine <- method.lineNumber
        endLine <- method.lineNumberEnd
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
   *
   * @param className the fully qualified class name
   * @return the full source code of the file containing the class as a String
   */
  override def getClassSource(className: String): java.lang.String = {
    // Try exact match first
    var classNodes = cpg.typeDecl.fullNameExact(className).l

    // If no exact match, try fuzzy matching
    if (classNodes.isEmpty) {
      // First attempt: match against simple name (non-fully qualified)
      val simpleClassName = className.split("[.$]").last
      val nameMatches = cpg.typeDecl.name(simpleClassName).l
      
      if (nameMatches.size == 1) {
        // If there's exactly one match with this simple name, use it
        classNodes = nameMatches
      } else if (nameMatches.size > 1) {
        // Second attempt: try replacing $ with . in matches to see if we get a unique match
        val dotClassName = className.replace('$', '.')
        val dotMatches = nameMatches.filter(td => 
          td.fullName.replace('$', '.') == dotClassName
        )
        
        if (dotMatches.size == 1) {
          classNodes = dotMatches
        }
      }
    }

    // Still no match after fuzzy matching
    if (classNodes.isEmpty) {
      return null
    }

    val td = classNodes.head
    // Get the file information
    val fileOpt = toFile(td.filename)

    if (fileOpt.isEmpty) {
      return null
    }

    // Read the entire file
    val file = fileOpt.get
    val sourceOpt = scala.util.Using(Source.fromFile(file.absPath().toFile)) { source =>
      source.mkString
    }.toOption

    sourceOpt.orNull
  }

  /**
   * A helper to remove package names from a type string.
   * E.g. "java.lang.String[]" => "String[]", "com.foo.Bar" => "Bar".
   */
  private[brokk] def sanitizeType(t: String): String = {
    def processType(input: String): String = {
      val isArray = input.endsWith("[]")
      val base = if (isArray) input.dropRight(2) else input
      val shortName = base.split("\\.").lastOption.getOrElse(base)
      if (isArray) s"$shortName[]" else shortName
    }

    // Handle generic type parameters
    if (t.contains("<")) {
      val mainType = t.substring(0, t.indexOf("<"))
      val genericPart = t.substring(t.indexOf("<") + 1, t.lastIndexOf(">"))
      
      // Process the main type and each generic parameter
      val processedMain = processType(mainType)
      val processedParams = genericPart.split(",").map { param =>
        val trimmed = param.trim
        // Handle nested generic types recursively
        if (trimmed.contains("<")) {
          sanitizeType(trimmed)
        } else {
          processType(trimmed)
        }
      }.mkString(", ")
      
      s"$processedMain<$processedParams>"
    } else {
      processType(t)
    }
  }

  /**
   * Recursively builds a structural "skeleton" for a given TypeDecl,
   * including fields, methods, and nested type declarations.
   */
  // Convert or omit certain CPG modifiers to proper Java keywords
  // (this allows us to ignore Joern adding extra keywords like "virtual")
  private val knownModifiers = Map(
    "public" -> "public",
    "private" -> "private",
    "protected" -> "protected",
    "static" -> "static",
    "final" -> "final",
    "abstract" -> "abstract",
    "native" -> "native",
    "synchronized" -> "synchronized"
  )

  private def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder

    val className = sanitizeType(td.name)

    // Print this class's signature
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

    // Nested classes: skip any named "<lambda>N", skip if a segment is purely numeric (like Runnable$0)
    td.astChildren.isTypeDecl.filterNot { nested =>
      nested.name.startsWith("<lambda>") ||
        nested.name.split("\\$").exists(_.forall(_.isDigit))
    }.foreach { nested =>
      sb.append(outlineTypeDecl(nested, indent + 1))
        .append("\n")
    }

    sb.append(indentStr(indent)).append("}")
    sb.toString
  }

  private def indentStr(level: Int) = "  " * level

  /**
   * Builds a structural skeleton for a given class by name (simple or FQCN),
   * or None if the class is not found (bug in Joern).
   */
  override def getSkeleton(className: String): Option[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) {
      return None // TODO log?
    }
    val td = decls.head
    Some(outlineTypeDecl(td))
  }

  /**
   * Builds a weighted adjacency map at the class level: className -> Map[targetClassName -> weight].
   */
  private[brokk] def buildWeightedAdjacency()(implicit callResolver: ICallResolver): Map[String, Map[String, Int]] = {
    val adjacency = TrieMap[String, TrieMap[String, Int]]()

    // Common Java primitives
    val primitiveTypes = Set("byte", "short", "int", "long", "float", "double", "boolean", "char", "void")

    def isRelevant(t: String): Boolean =
      !primitiveTypes.contains(t) && !t.startsWith("java.")

    val typeDecls = cpg.typeDecl.l
    // Build adjacency in parallel
    typeDecls.par.foreach { td =>
      val sourceClass = td.fullName

      // Collect calls
      td.method.call.callee.typeDecl.fullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { tc =>
          increment(adjacency, sourceClass, tc)
        }

      // Collect field references
      td.member.typeFullName
        .map(_.replaceAll("""\[\]""", "")) // array type -> base type
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { fieldClass =>
          increment(adjacency, sourceClass, fieldClass)
        }

      // 3) Collect "extends"/"implements" edges; these count 5x
      td.inheritsFromTypeFullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { parentClass =>
          increment(adjacency, sourceClass, parentClass, 5)
        }
    }

    // Convert the inner TrieMaps to ordinary Maps
    adjacency.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap
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

  private def increment(map: TrieMap[String, TrieMap[String, Int]], source: String, target: String, count: Int = 1): Unit = {
    val targetMap = map.getOrElseUpdate(source, TrieMap.empty)
    targetMap.update(target, targetMap.getOrElse(target, 0) + count)
  }

  /**
   * Weighted PageRank at the class level, using multiple seed classes.
   * If validSeeds are present, seed scores and random jumps are weighted
   * by lines of code (LOC). Otherwise, use uniform seeds.
   */
  override def getPagerank(seedClassWeights: java.util.Map[String, java.lang.Double], k: Int, reversed: Boolean = false): java.util.List[(String, java.lang.Double)] = {
    import scala.jdk.CollectionConverters.*
    val seedWeights = seedClassWeights.asScala.view.mapValues(_.doubleValue()).toMap
    val seedSeq = seedWeights.keys.toSeq

    // restrict to classes that are in the graph
    var validSeeds = seedSeq.filter(classesForPagerank.contains)
    // if we ended up with no seeds, fall back to standard pagerank
    if (validSeeds.isEmpty) {
      validSeeds = classesForPagerank.l
    }

    // Identify dangling nodes (nodes without outgoing edges)
    val danglingNodes = classesForPagerank.par.filter { c =>
      adjacency.get(c).forall(_.isEmpty)
    }

    val damping = 0.85
    val epsilon = 1e-4
    val maxIter = 50

    val scores = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0)*)
    val nextScores = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0)*)

    // Calculate total weight for normalization
    val totalWeight = seedWeights.values.sum
    
    // Use provided weights directly, normalized
    validSeeds.foreach { c =>
      scores(c) = seedWeights.getOrElse(c, 0.0) / totalWeight
    }

    var iteration = 0
    var diffSum   = Double.MaxValue

    while (iteration < maxIter && diffSum > epsilon) {

      // Zero nextScores in parallel
      classesForPagerank.par.foreach { c => nextScores(c) = 0.0 }

      // Handle graph edges, using either forward or reverse direction
      val localDiffs = classesForPagerank.par.map { node =>
        val (inMap, outMap) = if (reversed) (adjacency, reverseAdjacency) else (reverseAdjacency, adjacency)
        val inboundSum = inMap
          .get(node)
          .map { inboundMap =>
            inboundMap.foldLeft(0.0) { case (acc, (u, weight)) =>
              val outLinks = outMap(u)
              val outWeight = outLinks.values.sum.max(1)
              acc + (scores(u) * weight / outWeight)
            }
          }.getOrElse(0.0)

        // Damping
        var newScore = damping * inboundSum

        // Random jump to seeds with normalized weights
        if (validSeeds.contains(node)) {
          newScore += (1.0 - damping) * (seedWeights.getOrElse(node, 0.0) / totalWeight)
        }

        nextScores(node) = newScore
        math.abs(scores(node) - newScore)
      }.sum

      // Handle dangling nodes: push their entire score to seeds
      val danglingScore = danglingNodes.par.map(scores).sum
      if (danglingScore > 0.0 && validSeeds.nonEmpty) {
        validSeeds.par.foreach { seed =>
          val weight = seedWeights.getOrElse(seed, 0.0) / totalWeight
          nextScores(seed) += damping * danglingScore * weight
        }
        // Zero out dangling
        danglingNodes.par.foreach { dn =>
          nextScores(dn) = 0.0
        }
      }

      // Update main scores from nextScores
      diffSum = localDiffs
      classesForPagerank.foreach { node =>
        scores(node) = nextScores(node)
      }

      iteration += 1
    }

    // Sort results, ignoring seed classes in final listing
    val sortedAll = scores.toList.sortBy { case (_, score) => -score }
    val filteredSortedAll = sortedAll.filterNot { case (cls, _) => seedSeq.exists(seed => partOfClass(seed, cls)) }

    // Coalesce inner classes while maintaining k results
    def coalesceInnerClasses(initial: List[(String, Double)], k: Int): mutable.Buffer[(String, Double)] = {
      var results = initial.take(k).toBuffer
      var offset = k
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

    CollectionConverters.asJava(coalesceInnerClasses(filteredSortedAll, k).map { case (s, d) => (s, java.lang.Double.valueOf(d)) })
  }

  /**
   * Returns a set of all classes in the given .java filename.
   */
  override def getClassesInFile(file: RepoFile): java.util.Set[CodeUnit] = {
    val matches = cpg.typeDecl.l.filter { td =>
      toFile(td).contains(file)
    }
    CollectionConverters.asJava(matches.map(td => CodeUnit.cls(td.fullName)).toSet)
  }

  private def toFile(td: TypeDecl): Option[RepoFile] = {
    if (td.filename.isEmpty || td.filename == "<empty>" || td.filename == "<unknown>") {
      None
    } else {
      toFile(td.filename)
    }
  }

  private[brokk] def toFile(relName: String): Option[RepoFile] = {
    Some(RepoFile(absolutePath, relName))
  }

  // using cpg.all doesn't work because there are always-present nodes for files and the ANY typedecl
  override def isEmpty: Boolean = cpg.member.isEmpty

  override def isClassInProject(className: String): Boolean = {
    val td = cpg.typeDecl.fullNameExact(className).l
    td.nonEmpty && !(td.member.isEmpty && td.method.isEmpty && td.derivedTypeDecl.isEmpty)
  }

  override def getAllClasses: java.util.List[CodeUnit] = {
    val results = cpg.typeDecl
      .filter(toFile(_).isDefined)
      .fullName
      .l
      .map(CodeUnit.cls)
    CollectionConverters.asJava(results)
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
    Some(headerString(td, 0)  + "  [... methods not shown ...]\n}")
  }

  // does not include closing brace
  private def headerString(td: TypeDecl, indent: Int): String = {
    val sb = new StringBuilder
    val className = sanitizeType(td.name)

    // I think it's okay to leave visibility out at the class level
    sb.append(indentStr(indent))
      .append("class ")
      .append(className)
      .append(" {\n")

    // Add fields
    td.member.foreach { m =>
      val modifiers = m.modifier.map(_.modifierType.toLowerCase).filter(_.nonEmpty).mkString(" ")
      val modString = if (modifiers.nonEmpty) modifiers + " " else ""
      val typeName = sanitizeType(m.typeFullName)
      sb.append(indentStr(indent + 1))
        .append(s"$modString$typeName ${m.name};\n")
    }

    sb.toString
  }

  override def getMembersInClass(className: String): java.util.List[CodeUnit] = {
    val matches = cpg.typeDecl.fullNameExact(className)
    if (matches.isEmpty) {
      throw new IllegalArgumentException(s"Class '$className' not found")
    }
    val typeDecl = matches.head

    // Get all method declarations as FunctionType
    val methods = typeDecl.method
      .filterNot(_.name.startsWith("<")) // skip constructors/initializers
      .fullName.l
      .map(chopColon)
      .map(CodeUnit.fn)

    // Get all field declarations as FieldType
    val fields = typeDecl.member.name.l.map(name => CodeUnit.field(className + "." + name))

    // Get all nested types as ClassType
    // typeDecl.typeDecl doesn't exist and typeDecl.derivedTypeDecl is empty, so search by name instead
    val nestedPrefix = className + "\\$.*"
    val nestedTypes = cpg.typeDecl.fullName(nestedPrefix).fullName.l.map(CodeUnit.cls)

    // Combine all members and convert to Java list
    CollectionConverters.asJava(methods ++ fields ++ nestedTypes)
  }

  /**
   * For a given methodName, find all distinct methods that call it.
   */
  private[brokk] def getCallersOfMethod(methodName: String): List[String] = {
    val calls = cpg.call
      .methodFullName(s"""^${Regex.quote(methodName)}.*""")
      .l

    calls
      .map(_.method.fullName)
      .map(chopColon)
      .distinct
  }

  import scala.util.matching.Regex

  /**
   * Searches for classes, methods, and fields matching a given regular expression pattern.
   * 
   * @param pattern A regular expression to match against class, method, and field names.
   *                Note: Jeoern's patterns includeimplicit ^ and $ at the start and end of the pattern
   * @return A list of CodeUnit objects representing the matches
   */
  override def getDefinitions(pattern: String): java.util.List[CodeUnit] = {
    // Compile the regex pattern
    val ciPattern = "(?i)" + pattern // case-insensitive

    // Find matching classes (typeDecl) that are in the project
    val matchingClasses = cpg.typeDecl
      .name(ciPattern)
      .fullName
      .filter(isClassInProject)
      .map(CodeUnit.cls)
      .l

    // Find matching methods (by name, not fullName) that belong to project classes
    val matchingMethods = cpg.method
      .nameNot("<.*>") // Filter out constructors and special methods
      .name(ciPattern)
      .filter(m => {
        val typeNameOpt = m.typeDecl.fullName.headOption
        typeNameOpt.exists(typeName => isClassInProject(typeName))
      })
      .map(m => CodeUnit.fn(resolveMethodName(chopColon(m.fullName))))
      .l

    // Find matching fields that belong to project classes
    val matchingFields = cpg.member
      .name(ciPattern)
      .filter(f => {
        val typeNameOpt = f.typeDecl.fullName.headOption
        typeNameOpt.exists(typeName => isClassInProject(typeName.toString))
      })
      .map(f => {
        val className = f.typeDecl.fullName.headOption.getOrElse("").toString
        CodeUnit.field(s"$className.${f.name}")
      })
      .l

    // Combine all results
    val combined = matchingClasses ++ matchingMethods ++ matchingFields
    CollectionConverters.asJava(combined)
  }
  
  /**
   * For a given method node `m`, returns the calling method .fullName (with the suffix after `:` chopped off).
   * If `excludeSelfRefs` is true, we skip callers whose TypeDecl matches `m.typeDecl`.
   */
  private def callersOfMethodNode(m: Method, excludeSelfRefs: Boolean): List[String] = {
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
      .map((name: String) => resolveMethodName(chopColon(name)))
      .distinct
      .l
  }

  private def partOfClass(parentFqcn: String, childFqcn: String) = {
    childFqcn == parentFqcn || childFqcn.startsWith(parentFqcn + ".") || childFqcn.startsWith(parentFqcn + "$")
  }

  /**
   * Return all methods that reference a given field "classFullName.fieldName".
   * If `excludeSelfRefs` is true, references from the same class are omitted.
   */
  private def referencesToField(selfSource: String,
                                 fieldName: String,
                                 excludeSelfRefs: Boolean): List[String] = {
    var calls = cpg.call
      .nameExact("<operator>.fieldAccess")
      // The first argument is the "base" or receiver,
      // typed with `classFullName` for an instance, or referencing the class for a static field.
      .where(_.argument(1).typ.fullNameExact(selfSource))
      // The second argument is the field identifier code
      .where(_.argument(2).codeExact(fieldName))

    if (excludeSelfRefs) {
      calls = calls.filterNot(call => {
        partOfClass(selfSource, call.method.typeDecl.fullName.head)
      })
    }
    calls
      .method
      .fullName
      // Chop off everything after the colon, e.g. "Foo.bar:void()" → "Foo.bar"
      .map(x => resolveMethodName(chopColon(x)))
      .distinct
      .l
  }

  private def chopColon(full: String) = full.split(":").head

  /**
   * Find all references to a given class used as a type (inheritance, fields, parameters, locals).
   * Returns CodeUnit objects with appropriate types based on reference kind:
   * - CLASS for inheriting classes
   * - FIELD for field references
   * - FUNCTION for parameter/local references
   */
  private def referencesToClassAsType(classFullName: String): List[CodeUnit] = {
    val typePattern = "^" + Regex.quote(classFullName) + "(\\$.*)?(\\[\\])?"

    // Fields typed with this class → parent is a TypeDecl.
    // Return as field CodeUnits
    val fieldRefs = cpg.member
      .typeFullName(typePattern)
      .astParent
      .isTypeDecl
      .filter(td => !partOfClass(classFullName, td.fullName))
      .flatMap { td => 
        // Get the member again to access its name
        td.member.typeFullName(typePattern).map { member =>
          CodeUnit.field(s"${td.fullName}.${member.name}")
        }.l
      }

    // Parameters/locals typed with this class → parent is a Method.
    // Return as function CodeUnits
    val paramRefs = cpg.parameter
      .typeFullName(typePattern)
      .method
      .filter(m => !partOfClass(classFullName, m.typeDecl.fullName.l.head))
      .fullName
      .map(chopColon)
      .map(resolveMethodName)
      .map(CodeUnit.fn)
      .l

    // Locals typed with this class → parent is a Method.
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
    // Return as class CodeUnits
    val inheritingClasses = cpg.typeDecl
      .filter(_.inheritsFromTypeFullName.contains(classFullName))
      .filter(td => !partOfClass(classFullName, td.fullName))
      .fullName
      .map(CodeUnit.cls)
      .l

    // Combine all reference types and remove duplicates
    (fieldRefs ++ paramRefs ++ localRefs ++ inheritingClasses).toList.distinct
  }

  /**
   * IntelliJ-style "find usages" for a given symbol. The symbol may be:
   *  - A method (fully qualified, e.g. "com.foo.Bar.baz:int(java.lang.String)"),
   *  - A field (fully qualified, e.g. "com.foo.Bar.fieldName"),
   *  - Or a class (fully qualified, e.g. "com.foo.Bar").
   *
   * Returns a SymbolUsages containing:
   *  - methodUses: references to methods if symbol is a method, or references to static methods if symbol is a class.
   *  - fieldUses: references to the field if symbol is a field, or references to static fields if symbol is a class.
   *  - typeUses: references to the class as a type (field declarations, parameters, locals), if symbol is a class.
   *  - inheritanceUses: classes that inherit from the given class (extends, implements), if symbol is a class.
   *
   * If symbol is not found at all, throws IllegalArgumentException.
   */
  override def getUses(symbol: String): java.util.List[CodeUnit] = {
    //
    // 1) If symbol is recognized as a method name
    //
    val methodMatches = methodsFromName(symbol)
    if (methodMatches.nonEmpty) {
      // Do NOT exclude self references in the method branch
      val calls = methodMatches.flatMap(m => callersOfMethodNode(m, excludeSelfRefs = false)).distinct
      val methodUnits = calls.map(CodeUnit.fn)
      return CollectionConverters.asJava(methodUnits)
    }

    //
    // 2) If symbol is recognized as a field, e.g. com.foo.Bar.fieldName
    //
    val lastDot = symbol.lastIndexOf('.')
    if (lastDot > 0) {
      val classPart = symbol.substring(0, lastDot)
      val fieldPart = symbol.substring(lastDot + 1)
      val clsDecls = cpg.typeDecl.fullNameExact(classPart).l

      if (clsDecls.nonEmpty) {
        // Check that the class actually has a member named `fieldPart`
        val maybeFieldDecl = clsDecls.head.member.nameExact(fieldPart).l
        if (maybeFieldDecl.nonEmpty) {
          // Do NOT exclude self references in the field branch
          val refs = referencesToField(classPart, fieldPart, excludeSelfRefs = false)
          val refUnits = refs.map(CodeUnit.fn)
          return CollectionConverters.asJava(refUnits)
        }
      }
    }

    //
    // 3) Otherwise, treat `symbol` as a class name.
    //    Gather references to all methods in that class, references to its fields,
    //    plus references to the class as a type. DO exclude self references.
    //
    val classDecls = cpg.typeDecl.fullNameExact(symbol).l
    if (classDecls.isEmpty) {
      throw new IllegalArgumentException(
        s"Symbol '$symbol' not found as a method, field, or class"
      )
    }

    // Get method and field references as function CodeUnits
    val methodUses = classDecls.flatMap(td =>
      td.method.l.flatMap(m => callersOfMethodNode(m, excludeSelfRefs = true))
    ).map(CodeUnit.fn) 
    
    val fieldRefUses = classDecls.flatMap(td =>
      td.member.l.flatMap(mem => referencesToField(td.fullName, mem.name, excludeSelfRefs = true))
    ).map(CodeUnit.fn)
    
    // Get type references
    val typeUses = referencesToClassAsType(symbol)

    // Combine all uses
    val combined = (methodUses ++ fieldRefUses ++ typeUses).distinct
    CollectionConverters.asJava(combined)
  }

  /**
   * Helper method to build a call graph with a maximum depth
   *
   * @param startingMethod The method to start from
   * @param isIncoming Whether to trace incoming calls (true) or outgoing calls (false)
   * @param maxDepth The maximum depth to traverse
   * @return A map of method signatures to lists of call sites
   */
  private def buildCallGraph(
      startingMethod: String,
      isIncoming: Boolean,
      maxDepth: Int = 5
  ): java.util.Map[String, java.util.List[CallSite]] = {
    val result = new java.util.HashMap[String, java.util.List[CallSite]]()

    // Find methods that match our starting method
    val startMethods = cpg.method.filter(m => chopColon(m.fullName) == startingMethod).l
    if (startMethods.isEmpty) {
      return result
    }

    // Set to track visited methods to prevent cycles
    val visited = mutable.Set[String]()

    // Add our starting methods to the visited set to avoid processing them again
    val startMethodNames = startMethods.map(m => resolveMethodName(chopColon(m.fullName))).toSet
    visited ++= startMethodNames

    // Helper to check if a method name should be included in results
    def shouldIncludeMethod(methodName: String): Boolean = {
      !methodName.startsWith("<operator>") &&
      !methodName.startsWith("java.") &&
      !methodName.startsWith("javax.") &&
      !methodName.startsWith("sun.") &&
      !methodName.startsWith("com.sun.")
    }

    def getSourceLine(call: io.shiftleft.codepropertygraph.generated.nodes.Call): String = {
      val trimmed = call.code.trim
      trimmed.replaceFirst("^this\\.", "")
    }

    // Helper to add a call site to the result map
    def addCallSite(methodName: String, callSite: CallSite): Unit = {
      var callSites = result.get(methodName)
      if (callSites == null) {
        callSites = new java.util.ArrayList[CallSite]()
        result.put(methodName, callSites)
      }
      callSites.add(callSite)
    }

    // Explore in a breadth-first manner
    def explore(methods: List[Method], currentDepth: Int): Unit = {
      if (currentDepth > maxDepth || methods.isEmpty) {
        return
      }

      val nextMethods = mutable.ListBuffer[Method]()

      methods.foreach { method =>
        val methodName = resolveMethodName(chopColon(method.fullName))
        val calls = if (isIncoming) {
          // For incoming calls, we look at calls TO this method
          method.callIn.l
        } else {
          // For outgoing calls, we look at calls FROM this method
          method.call.l
        }

        calls.foreach { call =>
          if (isIncoming) {
            // For incoming, the caller is the nextMethod
            val callerMethod = call.method
            val callerName = resolveMethodName(chopColon(callerMethod.fullName))

            // Skip if we've already processed this method or it should be excluded
            if (!visited.contains(callerName) && shouldIncludeMethod(callerName)) {
              // Add call to result map - signature is the caller
              val sourceLine = getSourceLine(call)
              addCallSite(methodName, CallSite(CodeUnit.fn(callerName), sourceLine))

              // Mark as visited and add to next methods to explore
              visited += callerName
              nextMethods += callerMethod
            }
          } else {
            // For outgoing, the callee is the nextMethod
            val calleeFullName = chopColon(call.methodFullName)
            val calleeName = resolveMethodName(calleeFullName)

            // Skip if we've already processed this method or it should be excluded
            if (!visited.contains(calleeName) && shouldIncludeMethod(calleeName)) {
              // Find method node for callee
              val calleePattern = s"""^${Regex.quote(calleeFullName)}.*"""
              val calleeMethods = cpg.method.fullName(calleePattern).l

              // Add call to result map - signature is the callee
              val sourceLine = getSourceLine(call)
              addCallSite(methodName, CallSite(CodeUnit.fn(calleeName), sourceLine))

              // Mark as visited
              visited += calleeName

              // Add callee to next methods to explore if we found it
              if (calleeMethods.nonEmpty) {
                nextMethods ++= calleeMethods
              }
            }
          }
        }
      }

      // Explore next level
      explore(nextMethods.toList, currentDepth + 1)
    }

    // Start exploration
    explore(startMethods, 1)

    result
  }

  /**
   * Gets the call graph to a specified method.
   *
   * Finds all methods that call the specified method, up to the specified depth.
   * Each method in the result includes its fully-qualified signature and
   * the actual source lines where the calls are made (trimmed of whitespace).
   *
   * @param methodName The fully-qualified name of the target method
   * @return A map where keys are fully-qualified method signatures and values are lists of CallSite objects
   */
  override def getCallgraphTo(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] = {
    val resolvedMethodName = resolveMethodName(methodName)
    buildCallGraph(resolvedMethodName, true, depth)
  }

  /**
   * Gets the call graph from a specified method.
   *
   * Finds all methods that are called by the specified method, up to the specified depth.
   * Each method in the result includes its fully-qualified signature and
   * the actual source lines where the calls are made (trimmed of whitespace).
   *
   * @param methodName The fully-qualified name of the source method
   * @return A map where keys are fully-qualified method signatures and values are lists of CallSite objects
   */
  override def getCallgraphFrom(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] = {
    val resolvedMethodName = resolveMethodName(methodName)
    buildCallGraph(resolvedMethodName, false, depth)
  }

  def writeCpg(path: java.nio.file.Path): Unit = {
    Serialization.writeGraph(cpg.graph, path)
  }
  
  override def close(): Unit = {
    cpg.close()
  }
}
object JavaAnalyzer {
  private def createCpgWithRetry[T](callable: => scala.util.Try[T], maxAttempts: Int = 3): T = {
    var attempt = 0
    var result: Option[T] = None
    var lastException: Throwable = null
    
    while (attempt < maxAttempts && result.isEmpty) {
      attempt += 1
      try {
        result = callable.toOption
      } catch {
        case e: java.io.IOException =>
          lastException = e
          // Wait briefly before retrying
          Thread.sleep(500)
      }
    }
    
    result.getOrElse {
      throw new IOException(s"Failed to create CPG after $maxAttempts attempts", lastException)
    }
  }
  
  private def createNewCpgFor(sourcePath: java.nio.file.Path, language: Language): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")
    val newCpg = language match {
      case Language.Java =>
        val config = JavaConfig()
          .withInputPath(absPath.toString)
          .withEnableTypeRecovery(true)
        createCpgWithRetry(JavaSrc2Cpg().createCpg(config))
      case Language.Python =>
        val cpg = Cpg.empty
        import scala.collection.JavaConverters.*
        val files = java.nio.file.Files.walk(absPath).iterator().asScala.toList
        val pythonFiles = files.filter(f => f.toString.endsWith(".py") && f.toFile.isFile).map(absPath.relativize(_))
        val inputProviders = pythonFiles.map { relPath =>
          val absFile = absPath.resolve(relPath)
          () => Py2Cpg.InputPair(scala.io.Source.fromFile(absFile.toFile).mkString, relPath.toString)
        }
        createCpgWithRetry {
          scala.util.Try {
            new Py2Cpg(
              inputProviders,
              cpg,
              absPath.toString,
              "requirements.txt",
              ValidationMode.Enabled,
              false
            ).buildCpg()
          }
        }
        cpg
    }
    X2Cpg.applyDefaultOverlays(newCpg)
    val context = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(context)
    newCpg
  }
}
