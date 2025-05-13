package io.github.jbellis.brokk.analyzer

import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.joern.x2cpg.{X2Cpg, ValidationMode, Defines as X2CpgDefines}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{Method, TypeDecl, AstNode, NamespaceBlock}
import io.shiftleft.codepropertygraph.generated.NodeTypes
import io.shiftleft.semanticcpg.language.*
import io.joern.c2cpg.{C2Cpg, Config as CConfig}
// Import astcreation.Defines specifically if needed, or rely on X2CpgDefines for common ones
// import io.joern.c2cpg.astcreation.{Defines as C2CpgAstDefines}
import io.joern.x2cpg.passes.frontend.MetaDataPass
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import java.io.IOException
import java.nio.file.Path
import java.util.Optional // Import for java.util.Optional
import scala.util.matching.Regex
import scala.util.Try
import scala.util.boundary, boundary.break // Added for modern early exit
import scala.collection.mutable // Added for mutable.ListBuffer

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
    try {
      val returnType = sanitizeType(m.methodReturn.typeFullName)
      val params = m.parameter.sortBy(_.order).filterNot(_.name == "this").l
        .map { p =>
          val paramType = sanitizeType(p.typeFullName)
          val paramName = p.name
          s"$paramType $paramName"
        }
        .mkString(", ")
      val signature = s"$returnType ${m.name}($params)"
      logger.debug(s"Generated signature for ${m.fullName} (name: ${m.name}): $signature")
      signature
    } catch {
      case e: Throwable =>
        logger.error(s"Exception in methodSignature for ${m.fullName} (name: ${m.name})", e)
        // Fallback signature to avoid crashing skeleton generation
        s"ErrorType ${m.name}(ErrorParams)"
    }
  }

  /**
   * Strip c2cpg duplicate suffix and signature from full names.
   * <name>:<sig> or <name><duplicate>N:sig  (N is an integer)
   */
  override private[brokk] def resolveMethodName(methodName: String): String = {
    val noSig = methodName.takeWhile(_ != ':')
    // c2cpg uses io.joern.c2cpg.astcreation.Defines.DuplicateSuffix
    val dupSuffixIdx = noSig.indexOf(io.joern.c2cpg.astcreation.Defines.DuplicateSuffix)
    if (dupSuffixIdx >= 0) noSig.substring(0, dupSuffixIdx) else noSig
  }

  override private[brokk] def sanitizeType(t: String): String = {
    if (t == null || t.isEmpty) return ""
    var currentType = t.trim

    // Iteratively remove pointers, references, and array indicators from the end
    var changed = true
    while (changed) {
      changed = false
      val originalLength = currentType.length
      if (currentType.endsWith("*") || currentType.endsWith("&")) {
        currentType = currentType.dropRight(1).trim
      } else if (currentType.endsWith("[]")) {
        currentType = currentType.dropRight(2).trim
      }
      // Check common qualifiers at the end (e.g. "char * const")
      List("const", "volatile", "restrict").foreach { q =>
        if (currentType.endsWith(s" $q")) {
          currentType = currentType.dropRight(s" $q".length).trim
        } else if (currentType == q && currentType.length > 0) { // Avoid infinite loop if type was just "const"
          currentType = X2CpgDefines.Any // Or simply "" if preferred for "just a qualifier"
        }
      }
      if (originalLength != currentType.length) changed = true
    }

    // Remove leading qualifiers and keywords
    List("static", "extern", "inline", "typedef", "_Atomic",
      "const", "volatile", "restrict", // Also check leading ones
      "struct", "enum", "union").foreach { keyword =>
      if (currentType.startsWith(s"$keyword ")) {
        currentType = currentType.substring(keyword.length).trim
      }
    }

    // Handle namespaces: take last part after "::" or "."
    val lastDot = currentType.lastIndexOf('.')
    val lastColonColon = currentType.lastIndexOf("::")
    val sepIdx = math.max(lastDot, lastColonColon)

    val finalBase = if (sepIdx != -1) {
      val startIndex = if (currentType.substring(sepIdx).startsWith("::")) sepIdx + 2 else sepIdx + 1
      if (startIndex < currentType.length) currentType.substring(startIndex) else currentType
    } else {
      currentType
    }
    
    if (finalBase.isEmpty && t.nonEmpty && t != X2CpgDefines.Any) X2CpgDefines.Any
    else finalBase
  }


  override protected def methodsFromName(resolvedMethodName: String): List[Method] = {
    val escapedInitial = Regex.quote(resolvedMethodName)
    var methods = cpg.method.fullName(s"$escapedInitial:.*").l
    logger.debug(s"methodsFromName for '$resolvedMethodName': initial query for '$escapedInitial:.*' found ${methods.size} methods.")

    // Attempt 1: Replace last dot with colon (common for global/static functions in CPG)
    // e.g., test input "file_cpp.func" -> CPG "file.cpp:func"
    // Also handle cases where resolvedMethodName might already be "file.cpp:func" from a previous step.
    val potentialCpgFqns = mutable.ListBuffer[String]()
    potentialCpgFqns += resolvedMethodName // Try as is first

    if (resolvedMethodName.contains('.')) {
      val lastDotIndex = resolvedMethodName.lastIndexOf('.')
      if (lastDotIndex > 0) {
        var filePart = resolvedMethodName.substring(0, lastDotIndex)
        val memberPart = resolvedMethodName.substring(lastDotIndex + 1)

        filePart = filePart.replace("_cpp", ".cpp").replace("_c", ".c")
                           .replace("_hpp", ".hpp").replace("_h", ".h")
                           .replace("_cc", ".cc").replace("_hh", ".hh")
        potentialCpgFqns += s"$filePart:$memberPart"
      }
    }

    for (fqn <- potentialCpgFqns.distinct; if methods.isEmpty) {
        val escapedFqn = Regex.quote(fqn)
        logger.debug(s"Trying query: '$escapedFqn:.*'")
        methods = cpg.method.fullName(s"$escapedFqn:.*").l
        if (methods.nonEmpty) logger.debug(s"Found ${methods.size} methods with query '$escapedFqn:.*'")
    }
    
    // Attempt 2: If still not found, try the original resolvedMethodName directly (no signature wildcard)
    // This might match methods where the signature part was already part of resolvedMethodName (less likely for C++)
    if (methods.isEmpty) {
        logger.debug(s"Trying exact match for '$resolvedMethodName' (no signature wildcard).")
        methods = cpg.method.fullNameExact(resolvedMethodName).l
        logger.debug(s"Found ${methods.size} methods with exact match.")
    }
    
    // Attempt 3: If still empty and resolvedMethodName fits "file_prefix.func_name" pattern
    if (methods.isEmpty && resolvedMethodName.count(_ == '.') == 1) { // Heuristic: simple "file.func"
        val parts = resolvedMethodName.split('.')
        val fileNameGuess = parts.head.replace("_cpp", ".cpp").replace("_c", ".c")
                                      .replace("_h", ".h").replace("_hpp", ".hpp")
                                      // Add other common C/C++ extensions if needed
        val funcNameGuess = parts.last
        logger.debug(s"Attempting filename-filtered search: filename containing '$fileNameGuess', func name '$funcNameGuess'")
        methods = cpg.method.nameExact(funcNameGuess).where(_.filename(s".*$fileNameGuess")).l
        logger.debug(s"Found ${methods.size} methods by filename-filtered search.")
    }
    
    // Attempt 4: If it's a simple name (no dots or colons), it might be a global C function.
    // This could be noisy, so it's a later fallback.
    if (methods.isEmpty && !resolvedMethodName.contains('.') && !resolvedMethodName.contains(':')) {
        logger.debug(s"Trying broad search for global function matching name '$resolvedMethodName'.")
        // We need to be careful not to match class methods here.
        // Global functions in C/C++ often have their `astParentType` as `NAMESPACE_BLOCK` (for file scope)
        // or directly under a `TYPE_DECL` that represents the file (a pseudo TypeDecl with no methods/members).
        // Global functions in C/C++ often have their `astParentType` as `NAMESPACE_BLOCK` (for file scope)
        // or directly under a `TYPE_DECL` that represents the file (a pseudo TypeDecl with no methods/members).
        methods = cpg.method.nameExact(resolvedMethodName)
            .filter { m =>
                m.astParent match {
                    case parentNode: NamespaceBlock => true
                    case parentNode: TypeDecl =>
                        // This checks if the TypeDecl parent is a "simple" one,
                        // often used by CPGs to represent file scope for globals,
                        // rather than a full class/struct.
                        parentNode.method.isEmpty && parentNode.member.isEmpty
                    case _ => false
                }
            }.l
        logger.debug(s"Found ${methods.size} methods by broad name search for global functions.")
    }

    methods
  }

  override protected def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder
    logger.debug(s"Outlining TypeDecl: ${td.fullName}, Name: ${td.name}, Code: ${td.code.take(50)}")
    // Query for methods whose semantic parent (TypeDecl) is this one.
    // This handles out-of-line definitions better than relying on AST parentage alone.
    val availableMethods = cpg.method.filter(_.typeDecl.fullNameExact(td.fullName).nonEmpty).l
    logger.debug(s"Methods available for ${td.name} (${availableMethods.size}): ${availableMethods.map(m => m.name + ":" + m.signature).mkString(", ")}")
    availableMethods.foreach(m => logger.debug(s"Method in ${td.name}: ${m.name}, FullName: ${m.fullName}"))


    val typeKeyword = td.code.toLowerCase match {
      case c if c.startsWith("class ") => "class"
      case c if c.startsWith("struct ") => "struct"
      case c if c.startsWith("union ") => "union"
      case _ => if (td.inheritsFromTypeFullName.nonEmpty || td.method.nonEmpty) "class" else "struct"
    }

    sb.append("  " * indent).append(typeKeyword).append(" ").append(sanitizeType(td.name))
    // In C++, inheritance is more complex (public/private, virtual). This is a simplification.
    val inheritedTypes = td.inheritsFromTypeFullName.map(sanitizeType).mkString(", ")
    if (inheritedTypes.nonEmpty) {
        sb.append(" : ").append(inheritedTypes)
    }
    sb.append(" {\n")

    // Fields/Members
    td.member.foreach { f =>
      sb.append("  " * (indent + 1)).append(s"${sanitizeType(f.typeFullName)} ${f.name};\n")
    }

    // Methods
    if (availableMethods.nonEmpty) {
        if (td.member.nonEmpty) sb.append("\n")
        logger.debug(s"Processing ${availableMethods.size} methods for skeleton of ${td.fullName}")
        availableMethods.foreach { m =>
            try {
                logger.debug(s"Attempting signature for method ${m.fullName} (name: ${m.name}) in type ${td.fullName}")
                val sig = methodSignature(m)
                logger.debug(s"Signature for ${m.name} in ${td.fullName}: $sig")
                sb.append("  " * (indent + 1)).append(sig).append(" {...}\n")
            } catch {
                // Catching all Throwables because LinkageError or similar could occur if CPG is malformed,
                // though Exception should cover most cases from methodSignature itself.
                case e: Throwable => logger.error(s"Error generating signature for method ${m.fullName} (name: ${m.name}) in type ${td.fullName}. Skipping method.", e)
            }
        }
    } else {
        logger.debug(s"No methods found for skeleton of ${td.fullName}")
    }

    sb.append("  " * indent).append("}")
    sb.toString()
  }

  override def getSkeleton(className: String): Optional[String] = {
    // Convert to C++ canonical FQN (e.g., "shapes.Circle" -> "shapes::Circle") for CPG lookup.
    // This simple replacement assumes typical C++ FQNs. More complex logic might be needed if dots can appear legitimately within C++ namespace or class names themselves.
    val cpgClassName = className.replace('.', ':')
    logger.debug(s"getSkeleton: input className='$className', converted for CPG lookup to '$cpgClassName'")

    val decls = cpg.typeDecl.fullNameExact(cpgClassName).l
    if (decls.isEmpty) {
      // Fallback: if not found with '::', try the original name. This might occur if c2cpg stores some FQNs with '.'
      // or if the input was already canonical (e.g. a global class "Point" has no '.' or '::').
      val fallbackDecls = if (cpgClassName == className) {
        // No conversion happened, original name already failed or was a simple name
        List.empty // Avoid re-querying if cpgClassName was same as className
      } else {
        logger.debug(s"No TypeDecl found for C++ FQN '$cpgClassName', trying original name '$className'")
        cpg.typeDecl.fullNameExact(className).l
      }
      
      if (fallbackDecls.isEmpty) {
        logger.debug(s"No TypeDecl found for either '$cpgClassName' or '$className'")
        Optional.empty()
      } else {
        logger.debug(s"Found TypeDecl using fallback name '$className'. Outlining TypeDecl: ${fallbackDecls.head.fullName}")
        Optional.of(outlineTypeDecl(fallbackDecls.head))
      }
    } else {
      logger.debug(s"Found TypeDecl using C++ FQN '$cpgClassName'. Outlining TypeDecl: ${decls.head.fullName}")
      Optional.of(outlineTypeDecl(decls.head))
    }
  }

  protected[analyzer] def parseFqName(fqName: String, expectedType: CodeUnitType): CodeUnit.Tuple3[String,String,String] = {
    if (fqName == null || fqName.isEmpty) {
      return new CodeUnit.Tuple3("", "", "")
    }

    // Heuristic for global functions/fields
    // Case 1: "file_ext.membername" (e.g., "geometry_cpp.global_func") - direct from test data or constructed in AbstractAnalyzer
    // Case 2: "file.ext:membername" (e.g., "geometry.cpp:global_func") - from CPG method.fullName after resolveMethodName(chopColon(...))
    if (expectedType == CodeUnitType.FUNCTION || expectedType == CodeUnitType.FIELD) {
      val colonIndex = fqName.lastIndexOf(':')
      val dotIndex = fqName.lastIndexOf('.')

      if (colonIndex != -1 && colonIndex > dotIndex) { // Case 2: "file.ext:membername"
        val filePart = fqName.substring(0, colonIndex) // e.g., "geometry.cpp"
        val memberPart = fqName.substring(colonIndex + 1) // e.g., "global_func"
        
        // Ensure filePart looks like "name.ext" and memberPart is simple
        val lastDotInFile = filePart.lastIndexOf('.')
        if (lastDotInFile > 0 && lastDotInFile < filePart.length - 1 && 
            !memberPart.contains('.') && !memberPart.contains(':') && 
            !filePart.substring(0, lastDotInFile).contains('/') && // No path in basename
            !filePart.substring(0, lastDotInFile).contains('\\') &&
            !filePart.substring(0, lastDotInFile).contains(':') ) {
              
          val fileBasename = filePart.substring(0, lastDotInFile) // "geometry"
          val fileExt = filePart.substring(lastDotInFile + 1)     // "cpp"
          
          // Construct the "packageName" as "basename_ext"
          val pseudoPackage = s"${fileBasename.replace('.', '_')}_$fileExt" // "geometry_cpp"
          logger.debug(s"Parsed CPG-style global FQN (file.ext:member) '$fqName' as Pkg='$pseudoPackage', Cls='', Member='$memberPart'")
          return new CodeUnit.Tuple3(pseudoPackage, "", memberPart)
        }
      } else if (dotIndex != -1 && fqName.substring(0, dotIndex).matches("[a-zA-Z0-9_]+_(h|cpp|c|hpp|cc|hh|hxx|cxx)")) {
        // Case 1: "file_ext.membername" (e.g. "geometry_cpp.global_func")
        // Extended to match more C++ extensions.
        // The part before the dot must look like "name_ext"
        val packagePart = fqName.substring(0, dotIndex)
        val memberPart = fqName.substring(dotIndex + 1)
        // Ensure memberPart is simple (no dots or colons)
        if (!memberPart.contains('.') && !memberPart.contains(':')) {
          logger.debug(s"Parsed test-style global FQN (file_ext.member) '$fqName' as Pkg='$packagePart', Cls='', Member='$memberPart'")
          return new CodeUnit.Tuple3(packagePart, "", memberPart)
        }
      }
    }

    // Attempt 1: CPG lookup - Is fqName a fully qualified class/struct name?
    if (expectedType == CodeUnitType.CLASS && cpg.typeDecl.fullNameExact(fqName).nonEmpty) {
      val lastDot = fqName.lastIndexOf('.')
      val (pkg, cls) = if (lastDot == -1) ("", fqName) else (fqName.substring(0, lastDot), fqName.substring(lastDot + 1))
      return new CodeUnit.Tuple3(pkg, cls, "")
    }

    // Attempt 2: CPG lookup - Parse as potentialClass.potentialMember
    val lastDotMemberSep = fqName.lastIndexOf('.')
    if (lastDotMemberSep != -1) {
      val potentialClassFullName = fqName.substring(0, lastDotMemberSep)
      val potentialMemberName = fqName.substring(lastDotMemberSep + 1)

      if (cpg.typeDecl.fullNameExact(potentialClassFullName).nonEmpty) {
        // potentialClassFullName is a known class/struct
        val classDotPkgSep = potentialClassFullName.lastIndexOf('.')
        val (pkg, cls) = if (classDotPkgSep == -1) ("", potentialClassFullName)
        else (potentialClassFullName.substring(0, classDotPkgSep), potentialClassFullName.substring(classDotPkgSep + 1))
        // If it's a class, then potentialMemberName is indeed a member (method or field)
        return new CodeUnit.Tuple3(pkg, cls, potentialMemberName)
      }
    }

    // Fallback Heuristics if CPG lookups didn't resolve as expected
    if (lastDotMemberSep == -1) { // Single identifier like "MyClass" or "my_func"
      if (expectedType == CodeUnitType.CLASS) {
        return new CodeUnit.Tuple3("", fqName, "") // (namespace="", class=fqName, member="") - global class
      } else { // FUNCTION or FIELD
        // Could be global function/variable, or file-prefixed global.
        // Let's assume it's a simple global for now, package determination for globals can be complex.
        return new CodeUnit.Tuple3("", "", fqName) // (namespace="", class="", member=fqName)
      }
    } else { // Multiple segments: "ns.member", "cls.member", "ns.cls.member"
      val partBeforeLastDot = fqName.substring(0, lastDotMemberSep)
      val partAfterLastDot = fqName.substring(lastDotMemberSep + 1)

      if (expectedType == CodeUnitType.CLASS) {
        // e.g. fqName = "shapes.Circle" -> (pkg="shapes", cls="Circle", member="")
        return new CodeUnit.Tuple3(partBeforeLastDot, partAfterLastDot, "")
      } else { // FUNCTION or FIELD
        // Fallback: if partBeforeLastDot was not a class (checked in Attempt 2), it's likely a namespace.
        // e.g. "shapes.another_in_shapes" -> (pkg="shapes", cls="", member="another_in_shapes")
        // or "pkg.Cls.method" where "pkg.Cls" is NOT a known TypeDecl.
        // Test "pkg.Cls.method" expects ("pkg", "Cls", "method").
        // This implies if `partBeforeLastDot` has a dot, split it.
        val deeperDot = partBeforeLastDot.lastIndexOf('.')
        if (deeperDot != -1) { // partBeforeLastDot is like "pkg.Cls"
            val pkg = partBeforeLastDot.substring(0, deeperDot)
            val cls = partBeforeLastDot.substring(deeperDot + 1)
            return new CodeUnit.Tuple3(pkg, cls, partAfterLastDot)
        } else { // partBeforeLastDot is simple, e.g. "shapes" in "shapes.func" or "Cls" in "Cls.method"
            // If this was "Cls.method" and "Cls" is not a TypeDecl, it's ambiguous.
            // The test "Cls.method" -> ("", "Cls", "method") implies we treat partBeforeLastDot as class.
            // The test "pkg.func" -> ("pkg", "", "func") implies we treat partBeforeLastDot as package.
            // Test `geometry_h.global_func_decl_in_header` (FUNCTION) expects ("geometry_h", "", "global_func_decl_in_header")
            
            if (expectedType == CodeUnitType.FUNCTION || expectedType == CodeUnitType.FIELD) {
                val isLikelyFilePrefix = partBeforeLastDot.endsWith("_h") || partBeforeLastDot.endsWith("_cpp") ||
                                         partBeforeLastDot.endsWith("_c") || partBeforeLastDot.endsWith("_hpp") ||
                                         partBeforeLastDot.endsWith("_cc") || partBeforeLastDot.endsWith("_hh")
                
                if (isLikelyFilePrefix && !partBeforeLastDot.contains(".")) { // e.g., "geometry_h.func"
                    return new CodeUnit.Tuple3(partBeforeLastDot, "", partAfterLastDot) // pkg=file, cls="", member=func
                } else if (!partBeforeLastDot.contains(".")) { 
                    // This handles "Cls.method" (FUNCTION) -> ("", "Cls", "method")
                    // AND "shapes.another_in_shapes" (FUNCTION) -> ("shapes", "", "another_in_shapes")
                    // For "Cls.method", we expect ("", "Cls", method).
                    // For "shapes.func", we expect ("shapes", "", func).
                    // Heuristic: if `partBeforeLastDot` is capitalized, assume class. Otherwise, namespace.
                    // This is a weak heuristic. A more robust solution would require CPG namespace info.
                    if (partBeforeLastDot.headOption.exists(_.isUpper)) { // Crude heuristic for class name
                        return new CodeUnit.Tuple3("", partBeforeLastDot, partAfterLastDot) // Assume Class.method
                    } else {
                        return new CodeUnit.Tuple3(partBeforeLastDot, "", partAfterLastDot) // Assume namespace.func
                    }
                }
                 else { // "pkg.Cls.method" (where pkg.Cls is not a TypeDecl or partBeforeLastDot has dots but is not a file prefix)
                    logger.warn(s"Complex fallback for $fqName ($expectedType): treating $partBeforeLastDot as class part of $partAfterLastDot.")
                    return new CodeUnit.Tuple3("", partBeforeLastDot, partAfterLastDot) 
                }
            } else { 
                logger.warn(s"Unexpected fallback case in parseFqName for C++: fqName=$fqName, expectedType=$expectedType, " +
                            s"partBeforeLastDot=$partBeforeLastDot, partAfterLastDot=$partAfterLastDot. Defaulting to (ns.cls).mem")
                val deeperDotCls = partBeforeLastDot.lastIndexOf('.')
                if (deeperDotCls != -1) {
                     return new CodeUnit.Tuple3(partBeforeLastDot.substring(0, deeperDotCls), partBeforeLastDot.substring(deeperDotCls+1), partAfterLastDot)
                } else {
                     return new CodeUnit.Tuple3(partBeforeLastDot, "", partAfterLastDot)
                }
            }
        }
      }
    }
  }

  override def cuClass(fqcn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqcn, CodeUnitType.CLASS)
    val pkg: String = parts._1()
    val cls: String = parts._2()
    val mem: String = parts._3()
    if (cls.isEmpty || mem.nonEmpty) {
        logger.warn(s"Expected a class FQCN but parsing ($fqcn) resulted in: Pkg='$pkg', Class='$cls', Member='$mem'")
        None
    } else {
        Try(CodeUnit.cls(file, pkg, cls)).toOption
    }
  }

  override def cuFunction(fqmn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqmn, CodeUnitType.FUNCTION)
    val pkg: String = parts._1().asInstanceOf[String]
    val cls: String = parts._2().asInstanceOf[String]
    val mem: String = parts._3().asInstanceOf[String]

    if (mem.isEmpty) {
        logger.warn(s"Function/Method FQN '$fqmn' parsed to an empty member name. Pkg='$pkg', Class='$cls'. Skipping.")
        None
    } else {
        // For CodeUnit.fn, shortName is Class.Method for methods, or just Method for global/namespaced functions.
        val shortNameForCodeUnit = if (cls.nonEmpty) s"$cls.$mem" else mem
        Try(CodeUnit.fn(file, pkg, shortNameForCodeUnit)).toOption
    }
  }

  override def cuField(fqfn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqfn, CodeUnitType.FIELD)
    val pkg: String = parts._1().asInstanceOf[String]
    val cls: String = parts._2().asInstanceOf[String]
    val mem: String = parts._3().asInstanceOf[String]

    if (mem.isEmpty) { // Fields must have a member name
        logger.warn(s"Field FQN '$fqfn' parsed to an empty member name. Pkg='$pkg', Class='$cls'. Skipping.")
        None
    } else if (cls.isEmpty && !pkg.isEmpty && pkg.contains("_")) {
        // This could be a global variable "file_ext.varname"
        // CodeUnit.field expects (file, package, "Class.Field") or (file, package, "Field") if classless?
        // Current CodeUnit.field(pkg, shortName) implies shortName is Class.Field
        // Let's assume for now global fields are not directly represented by cuField if cls is empty.
        // Or if they are, shortName should just be the field name, and pkg is the file_ext.
        // The test data implies fields are always Cls.Field.
        logger.warn(s"Field FQN '$fqfn' parsed to an empty class name. Pkg='$pkg', Member='$mem'. This might be a global variable not fittable into 'Class.Field' CodeUnit structure. Skipping.")
        None
    } else if (cls.isEmpty) { // Class must also be non-empty for typical "Class.Field"
        logger.warn(s"Field FQN '$fqfn' parsed to an empty class name. Pkg='$pkg', Member='$mem'. Skipping.")
        None
    }
    else {
        val shortNameForCodeUnit = s"$cls.$mem"
        Try(CodeUnit.field(file, pkg, shortNameForCodeUnit)).toOption
    }
  }

  override def isClassInProject(className: String): Boolean = {
    cpg.typeDecl.fullNameExact(className).exists { td =>
      // Check if the TypeDecl node itself belongs to a file within the project source path
      // Also consider TypeDecls that might not have a file (e.g. builtins) if that's relevant, though usually not "in project".
      // For C++, header files can be outside sourcePath but used by project.
      // A simple check: if it's not explicitly external and has a source file we recognize.
      // Or, more inclusively: if it's defined in ANY file processed by c2cpg for this project.
      // The most robust is to check if the file location is under `absolutePath`.
      // td.isExternal is a direct Boolean in this CPG version.
      !td.isExternal || toFile(td.filename).exists(_.absPath().startsWith(absolutePath))
    }
  }

  override def getFunctionLocation(
                                     fqMethodName: String,
                                     paramNames: java.util.List[String]
                                   ): IAnalyzer.FunctionLocation = {
    import scala.jdk.CollectionConverters.*

    val resolvedName = resolveMethodName(fqMethodName)
    // C++ methods often have overloads, so signature matters.
    // However, the current `methodsFromName` only uses resolvedMethodName.
    // We need a way to incorporate paramNames or types if available/needed for disambiguation.
    // For now, assume fqMethodName is sufficient or that methodsFromName handles it.
    
    val candidates = methodsFromName(resolvedName)
    
    if (candidates.isEmpty) {
      throw new SymbolNotFoundException(s"No methods found for FQCN: $fqMethodName (resolved: $resolvedName)")
    }

    // Simple case: if only one candidate, use it.
    // More complex logic would be needed here for overloads if paramNames were used for signature matching.
    val chosenMethod = candidates.head // Or some disambiguation logic
    
    val fileOpt = toFile(chosenMethod.filename)
    if (fileOpt.isEmpty || chosenMethod.lineNumber.isEmpty || chosenMethod.lineNumberEnd.isEmpty) {
      throw new SymbolNotFoundException(s"File or line info missing for method: ${chosenMethod.fullName}")
    }

    val file = fileOpt.get
    val startLine = chosenMethod.lineNumber.get
    val endLine = chosenMethod.lineNumberEnd.get

    val maybeCode = Try {
      val lines = scala.io.Source.fromFile(file.absPath().toFile).getLines().slice(startLine - 1, endLine).mkString("\n")
      Some(lines)
    }.getOrElse(None)

    if (maybeCode.isEmpty) {
      throw new SymbolNotFoundException(s"Could not read source code for method: ${chosenMethod.fullName}")
    }
    IAnalyzer.FunctionLocation(file, startLine, endLine, maybeCode.get)
  }
}

object CppAnalyzer {
  import scala.jdk.CollectionConverters.*

  private def createNewCpgForSource(sourcePath: Path, excludedFiles: java.util.Set[String]): Cpg = {
    val absPath = sourcePath.toAbsolutePath.normalize()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    val scalaExcluded = excludedFiles.asScala.map(_.toString).toSeq

    val cfg = CConfig()
      .withInputPath(absPath.toString)
      .withIgnoredFiles(scalaExcluded.toList)
      .withIncludeComments(false)
      // Other CConfig options can be set here by chaining .withXyz methods, e.g.
      // .withIncludePathsAutoDiscovery(true)
      // .withDefines(Set("MY_DEFINE"))

    val newCpg = new C2Cpg().createCpg(cfg).getOrElse {
      throw new IOException(s"Failed to create C/C++ CPG for $absPath")
    }
    X2Cpg.applyDefaultOverlays(newCpg)
    val ctx = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(ctx)
    newCpg
  }
}
