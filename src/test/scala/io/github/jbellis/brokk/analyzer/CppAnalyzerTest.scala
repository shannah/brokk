package io.github.jbellis.brokk.analyzer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

class CppAnalyzerTest {
  private val testProjectPath = Path.of("src/test/resources/testcode-cpp")
  private var analyzer: CppAnalyzer = null // Lateinit

  // Companion object for @BeforeAll
  object CppAnalyzerTest {
    @BeforeAll
    @JvmStatic // Ensure JUnit5 can find it
    def setup(): Unit = {
    }
  }

  private def getAnalyzer: CppAnalyzer = {
    if (analyzer == null) {
      analyzer = new CppAnalyzer(testProjectPath)
    }
    analyzer
  }

  @Test
  def parseFqNameTest(): Unit = {
    val an = getAnalyzer // Use a local var to ensure fresh CPG for this test if needed or rely on cached.

    // Helper to call protected method
    def parse(fqn: String, t: CodeUnitType) = an.parseFqName(fqn, t)

    // Assuming CPG represents MyNamespace::MyClass as MyNamespace.MyClass
    // And global_func in some_file.cpp might be some_file.cpp.global_func or just global_func

    // Global function (simplest name)
    // For test stability, we assume global functions have file-prefixed names 
    assertEquals(new CodeUnit.Tuple3("geometry.h", "", "global_func"), parse("geometry.h.global_func", CodeUnitType.FUNCTION), "Global function from header")
    assertEquals(new CodeUnit.Tuple3("geometry.cpp", "", "uses_global_func"), parse("geometry.cpp.uses_global_func", CodeUnitType.FUNCTION), "Global function from cpp")

    // Class
    assertEquals(new CodeUnit.Tuple3("shapes", "Circle", ""), parse("shapes.Circle", CodeUnitType.CLASS), "Class in namespace")
    assertEquals(new CodeUnit.Tuple3("", "Point", ""), parse("Point", CodeUnitType.CLASS), "Struct in global namespace")

    // Method
    assertEquals(new CodeUnit.Tuple3("shapes", "Circle", "getArea"), parse("shapes.Circle.getArea", CodeUnitType.FUNCTION), "Method in namespaced class")
    assertEquals(new CodeUnit.Tuple3("", "Point", "print"), parse("Point.print", CodeUnitType.FUNCTION), "Method in global struct")

    // Constructor-like names (c2cpg might use <init> or class name)
    // Let's assume c2cpg uses "Circle" for constructor of "Circle" class
    assertEquals(new CodeUnit.Tuple3("shapes", "Circle", "Circle"), parse("shapes.Circle.Circle", CodeUnitType.FUNCTION), "Constructor")


    // Field / Member
    assertEquals(new CodeUnit.Tuple3("shapes", "Circle", "radius"), parse("shapes.Circle.radius", CodeUnitType.FIELD), "Field in namespaced class")
    assertEquals(new CodeUnit.Tuple3("", "Point", "x"), parse("Point.x", CodeUnitType.FIELD), "Field in global struct")

    // Namespace only function
    assertEquals(new CodeUnit.Tuple3("shapes", "", "another_in_shapes"), parse("shapes.another_in_shapes", CodeUnitType.FUNCTION), "Function in namespace")


    // Heuristic fallbacks (if CPG lookups fail or for names not directly in CPG as such)
    // If "unknown_namespace.UnknownClass.unknown_method" is not in CPG:
    //   - if "unknown_namespace.UnknownClass" is a TypeDecl: (unknown_namespace, UnknownClass, unknown_method)
    //   - else: (unknown_namespace.UnknownClass, "", unknown_method)
    assertEquals(new CodeUnit.Tuple3("pkg", "Cls", "method"), parse("pkg.Cls.method", CodeUnitType.FUNCTION), "Fallback method")
    assertEquals(new CodeUnit.Tuple3("pkg", "Cls", ""), parse("pkg.Cls", CodeUnitType.CLASS), "Fallback class")
    assertEquals(new CodeUnit.Tuple3("pkg", "", "func"), parse("pkg.func", CodeUnitType.FUNCTION), "Fallback namespaced func")
    assertEquals(new CodeUnit.Tuple3("", "Cls", "method"), parse("Cls.method", CodeUnitType.FUNCTION), "Fallback global class method")
    assertEquals(new CodeUnit.Tuple3("", "", "func"), parse("func", CodeUnitType.FUNCTION), "Fallback global func")
    assertEquals(new CodeUnit.Tuple3("", "Cls", ""), parse("Cls", CodeUnitType.CLASS), "Fallback global class")
  }

  @Test
  def cuCreationTest(): Unit = {
    val an = getAnalyzer
    val dummyFile = ProjectFile(testProjectPath, "geometry.h")

    // Class
    val circleClass = an.cuClass("shapes.Circle", dummyFile).get
    assertEquals("shapes", circleClass.packageName())
    assertEquals("Circle", circleClass.shortName())
    assertEquals("shapes.Circle", circleClass.fqName())

    val pointStruct = an.cuClass("Point", dummyFile).get
    assertEquals("", pointStruct.packageName())
    assertEquals("Point", pointStruct.shortName())
    assertEquals("Point", pointStruct.fqName())

    // Function/Method
    val getAreaMethod = an.cuFunction("shapes.Circle.getArea", dummyFile).get
    assertEquals("shapes", getAreaMethod.packageName())
    assertEquals("Circle.getArea", getAreaMethod.shortName()) // shortName is Class.method
    assertEquals("shapes.Circle.getArea", getAreaMethod.fqName())

    // Global function might be file-prefixed by c2cpg, e.g. geometry.h.global_func
    // Or if not, parseFqName logic for ("","", "global_func")
    val globalFunc = an.cuFunction("geometry.h.global_func", dummyFile).get
    assertEquals("geometry.h", globalFunc.packageName()) // package becomes filename
    assertEquals("global_func", globalFunc.shortName()) // shortName is just func name
    assertEquals("geometry.h.global_func", globalFunc.fqName())
    
    // Namespaced function (not in a class)
    val anotherInShapes = an.cuFunction("shapes.another_in_shapes", dummyFile).get
    assertEquals("shapes", anotherInShapes.packageName())
    assertEquals("another_in_shapes", anotherInShapes.shortName())
    assertEquals("shapes.another_in_shapes", anotherInShapes.fqName())


    // Field
    val radiusField = an.cuField("shapes.Circle.radius", dummyFile).get
    assertEquals("shapes", radiusField.packageName())
    assertEquals("Circle.radius", radiusField.shortName())
    assertEquals("shapes.Circle.radius", radiusField.fqName())
  }

  @Test
  def isClassInProjectTest(): Unit = {
    val an = getAnalyzer
    assertTrue(an.isClassInProject("shapes.Circle"), "shapes.Circle should be in project")
    assertTrue(an.isClassInProject("Point"), "Point struct should be in project")
    assertFalse(an.isClassInProject("std.vector"), "std.vector should not be in project (as a user TypeDecl)")
    assertFalse(an.isClassInProject("NonExistentClass"), "NonExistentClass should not be in project")
  }

  @Test
  def resolveMethodNameTest(): Unit = {
    val an = getAnalyzer
    assertEquals("shapes.Circle.getArea", an.resolveMethodName("shapes.Circle.getArea:double()"))
    assertEquals("global_func", an.resolveMethodName("global_func:void(int)"))
    // c2cpg uses <duplicate>N (N is an int)
    assertEquals("foo", an.resolveMethodName(s"foo${io.joern.c2cpg.astcreation.Defines.DuplicateSuffix}0:int()"))
  }

  @Test
  def sanitizeTypeTest(): Unit = {
    val an = getAnalyzer
    assertEquals("Circle", an.sanitizeType("shapes::Circle"))
    assertEquals("int", an.sanitizeType("int"))
    assertEquals("double", an.sanitizeType("double*")) // drops pointer currently if at end and split by space
    assertEquals("MyType", an.sanitizeType("const MyNamespace::MyType&")) // drops const/ref
    assertEquals("char", an.sanitizeType("char[]")) // drops array if at end and split by space

    // More robust checks based on current sanitizeType in CppAnalyzer
    assertEquals("MyType", an.sanitizeType("MyNamespace.MyType")) // Namespace removed
    assertEquals("MyType", an.sanitizeType("struct MyNamespace.MyType")) // struct keyword and namespace removed
    assertEquals("MyType", an.sanitizeType("MyType*")) // Pointer symbol removed
    assertEquals("MyType", an.sanitizeType("MyType[]")) // Array symbol removed
    assertEquals("MyType", an.sanitizeType("const MyType")) // const removed if it's the last part after split by space/dot
    assertEquals("MyType", an.sanitizeType("MyNamespace.MyType*[]")) // complex, becomes MyType
  }

  @Test
  def getSkeletonTest(): Unit = {
    val an = getAnalyzer
    val circleSkeletonOpt = an.getSkeleton("shapes.Circle")
    assertTrue(circleSkeletonOpt.isPresent)
    val circleSkeleton = circleSkeletonOpt.get()
    // Example: Check for class name and a method signature
    assertTrue(circleSkeleton.contains("class Circle {"), s"Skeleton: $circleSkeleton")
    assertTrue(circleSkeleton.contains("double getArea() {...}"), s"Skeleton: $circleSkeleton")
    assertTrue(circleSkeleton.contains("double radius;"), s"Skeleton: $circleSkeleton")

    val pointSkeletonOpt = an.getSkeleton("Point")
    assertTrue(pointSkeletonOpt.isPresent)
    val pointSkeleton = pointSkeletonOpt.get()
    assertTrue(pointSkeleton.contains("struct Point {"), s"Skeleton: $pointSkeleton") // c2cpg might make it a class if it has methods
    assertTrue(pointSkeleton.contains("void print() {...}"), s"Skeleton: $pointSkeleton")
    assertTrue(pointSkeleton.contains("int x;"), s"Skeleton: $pointSkeleton")
  }
  
  @Test
  def getMethodSourceTest(): Unit = {
    val an = getAnalyzer
    // Method in a namespace
    val getAreaOpt = an.getMethodSource("shapes.Circle.getArea")
    assertTrue(getAreaOpt.isPresent, "Could not find source for shapes.Circle.getArea")
    assertTrue(getAreaOpt.get.contains("return M_PI * radius * radius;"))

    // Global function
    val globalFuncOpt = an.getMethodSource("geometry.cpp.global_func") // or geometry.h.global_func
    assertTrue(globalFuncOpt.isPresent, "Could not find source for global_func")
    assertTrue(globalFuncOpt.get.contains("global_var = val;"))
  }


  @Test
  def getDeclarationsInFileTest(): Unit = {
    val an = getAnalyzer
    // Test declarations in header file
    val fileH = Try(ProjectFile(testProjectPath, "geometry.h")).toOption
    assumeTrue(fileH.isDefined, "geometry.h project file could not be created")

    val declsInH = an.getDeclarationsInFile(fileH.get).asScala.toSet
    
    // Expected fully qualified names

    val expectedFqns = Set(
      "shapes.Circle", // class
      "Point",         // struct (class)
      "shapes.Circle.Circle", // constructor
      "shapes.Circle.getArea", // method
      "shapes.Circle.getObjectType", // static method
      "shapes.Circle.radius", // field
      "Point.x", // field
      "Point.y", // field
      "Point.print", // method
      // functions primarily associated with the header file
    )
    
    val foundFqns = declsInH.map(_.fqName)
    // Check key declarations
    assertTrue(foundFqns.contains("shapes.Circle"), "Missing shapes.Circle class")
    assertTrue(foundFqns.contains("Point"), "Missing Point struct")
    assertTrue(foundFqns.contains("shapes.Circle.getArea"), "Missing shapes.Circle.getArea method")
    assertTrue(foundFqns.contains("Point.x"), "Missing Point.x field")

    // Test for geometry.cpp
    val fileCpp = Try(ProjectFile(testProjectPath, "geometry.cpp")).toOption
    assumeTrue(fileCpp.isDefined, "geometry.cpp project file could not be created")
    val declsInCpp = an.getDeclarationsInFile(fileCpp.get).asScala.toSet
    val foundFqnsCpp = declsInCpp.map(_.fqName)

    // Functions defined in geometry.cpp
    assertTrue(foundFqnsCpp.exists(_.endsWith("global_func")), "Missing global_func from geometry.cpp")
    assertTrue(foundFqnsCpp.exists(_.endsWith("uses_global_func")), "Missing uses_global_func from geometry.cpp")
    assertTrue(foundFqnsCpp.exists(_.endsWith("another_in_shapes")), "Missing shapes.another_in_shapes from geometry.cpp")

  }
  
  @Test
  def getUsesTest(): Unit = {
    val an = getAnalyzer
    // Test uses of global_func
    val usesGlobalFunc = an.getUses("geometry.cpp.global_func").asScala.map(_.fqName).toSet
    // Expected callers: main.cpp.main, geometry.cpp.uses_global_func, main.cpp.main_calls_lib
    assertTrue(usesGlobalFunc.exists(_.endsWith("main")), "main should use global_func")
    assertTrue(usesGlobalFunc.exists(_.endsWith("uses_global_func")), "uses_global_func should use global_func")
    assertTrue(usesGlobalFunc.exists(_.endsWith("main_calls_lib")), "main_calls_lib should use global_func")


    // Test uses of shapes.Circle.getArea
    val usesGetArea = an.getUses("shapes.Circle.getArea").asScala.map(_.fqName).toSet
    // Expected callers: main.cpp.main, geometry.cpp.shapes.another_in_shapes, main.cpp.main_calls_lib
    assertTrue(usesGetArea.exists(_.endsWith("main")), "main should use shapes.Circle.getArea")
    assertTrue(usesGetArea.exists(_.endsWith("another_in_shapes")), "another_in_shapes should use shapes.Circle.getArea")
    assertTrue(usesGetArea.exists(_.endsWith("main_calls_lib")), "main_calls_lib should use shapes.Circle.getArea")
    
    // Test uses of class Point (as a type)
    val usesPoint = an.getUses("Point").asScala.map(_.fqName).toSet
    // main function in main.cpp declares a Point p.
    assertTrue(usesPoint.exists(_.endsWith("main")), "main function in main.cpp uses Point type")
  }

  @Test
  def searchDefinitionsTest(): Unit = {
    val an = getAnalyzer
    // Search for "Circle"
    val circleDefs = an.searchDefinitions("Circle").asScala
    assertTrue(circleDefs.exists(cu => cu.fqName == "shapes.Circle" && cu.isClass), "Did not find shapes.Circle class")

    // Search for "global"
    val globalDefs = an.searchDefinitions("global").asScala
    assertTrue(globalDefs.exists(cu => cu.fqName.endsWith("global_func") && cu.isFunction), "Did not find global_func")
    // assertTrue(globalDefs.exists(cu => cu.fqName.endsWith("global_var") && !cu.isFunction && !cu.isClass ), "Did not find global_var")

    // Search for "getArea" method
    val getAreaDefs = an.searchDefinitions("getArea").asScala
    assertTrue(getAreaDefs.exists(cu => cu.fqName == "shapes.Circle.getArea" && cu.isFunction), "Did not find shapes.Circle.getArea")
  }

  @Test
  def getDefinitionTest(): Unit = {
    val an = getAnalyzer
    // Class
    val circleDefOpt = an.getDefinition("shapes.Circle")
    assertTrue(circleDefOpt.isPresent)
    assertEquals("shapes.Circle", circleDefOpt.get.fqName)
    assertTrue(circleDefOpt.get.isClass)

    // Method
    val getAreaDefOpt = an.getDefinition("shapes.Circle.getArea")
    assertTrue(getAreaDefOpt.isPresent)
    assertEquals("shapes.Circle.getArea", getAreaDefOpt.get.fqName)
    assertTrue(getAreaDefOpt.get.isFunction)

    // Global function (assuming FQN geometry.cpp.global_func)
    val globalFuncDefOpt = an.getDefinition("geometry.cpp.global_func")
    assertTrue(globalFuncDefOpt.isPresent, "Failed to find geometry.cpp.global_func")
    assertEquals("geometry.cpp.global_func", globalFuncDefOpt.get.fqName)
    assertTrue(globalFuncDefOpt.get.isFunction)
    
    // Field
    val radiusDefOpt = an.getDefinition("shapes.Circle.radius")
    assertTrue(radiusDefOpt.isPresent)
    assertEquals("shapes.Circle.radius", radiusDefOpt.get.fqName)
    assertFalse(radiusDefOpt.get.isClass)
    assertFalse(radiusDefOpt.get.isFunction)

    // Non-existent
    val nonExistentOpt = an.getDefinition("NonExistent.Symbol")
    assertFalse(nonExistentOpt.isPresent)
  }
  
    @Test
    def getFunctionLocationTest(): Unit = {
        val an = getAnalyzer
        // Global function in geometry.cpp
        val globalFuncLoc = an.getFunctionLocation("geometry.cpp.global_func", java.util.List.of("val"))
        assertTrue(globalFuncLoc.code.contains("global_var = val;"))
        assertEquals(ProjectFile(testProjectPath, "geometry.cpp"), globalFuncLoc.file)

        // Method in class
        val getAreaLoc = an.getFunctionLocation("shapes.Circle.getArea", java.util.Collections.emptyList())
        assertTrue(getAreaLoc.code.contains("return M_PI * radius * radius;"))
        assertEquals(ProjectFile(testProjectPath, "geometry.cpp"), getAreaLoc.file) // Definition is in .cpp
    }
}
