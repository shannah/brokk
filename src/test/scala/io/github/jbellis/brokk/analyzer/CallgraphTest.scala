package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.analyzer.Analyzer
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.semanticcpg.language.*
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

import java.nio.file.Path
import scala.jdk.javaapi.*
import scala.jdk.javaapi.CollectionConverters.asScala

class CallgraphTest {
  implicit val callResolver: ICallResolver = NoResolve
  
  @Test
  def testGetCallgraphTo(): Unit = {
    val analyzer = getAnalyzer
    val callgraph = analyzer.getCallgraphTo("A.method1")
    
    // Verify we have the expected callers
    val callsites = asScala(callgraph).toMap
    
    // Expect B.callsIntoA calls A.method1
    assertTrue(callsites.contains("B.callsIntoA"), "Should contain call from B.callsIntoA")
    assertEquals("a.method1()", callsites("B.callsIntoA").sourceLine)
    
    // Expect D.methodD1 calls A.method1
    assertTrue(callsites.contains("D.methodD1"), "Should contain call from D.methodD1")
    assertEquals("a.method1()", callsites("D.methodD1").sourceLine)
    
    // Verify we get appropriate depth - we expect to see calls to D.methodD1 (one level deep)
    assertTrue(callsites.contains("D.methodD2"), "Should contain indirect call from D.methodD2")
    assertEquals("this.methodD1()", callsites("D.methodD2").sourceLine)
  }
  
  @Test
  def testGetCallgraphFrom(): Unit = {
    val analyzer = getAnalyzer
    val callgraph = analyzer.getCallgraphFrom("D.methodD1")
    
    // Verify we have the expected callees
    val callsites = asScala(callgraph).toMap
    
    // D.methodD1 calls A.method1
    assertTrue(callsites.contains("A.method1"), "Should contain call to A.method1")
    assertEquals("a.method1()", callsites("A.method1").sourceLine)
    
    // D.methodD1 calls B.callsIntoA
    assertTrue(callsites.contains("B.callsIntoA"), "Should contain call to B.callsIntoA")
    assertEquals("b.callsIntoA()", callsites("B.callsIntoA").sourceLine)
    
    // We expect appropriate depth - B.callsIntoA then calls A.method1 and A.method2
    assertTrue(callsites.contains("A.method2"), "Should contain indirect call to A.method2")
    assertTrue(callsites.keySet.size >= 3, "Should contain at least three methods in call graph")
  }
  
  @Test
  def testGetCallgraphNonexistent(): Unit = {
    val analyzer = getAnalyzer
    
    // Test with non-existent method
    val emptyCallgraphTo = analyzer.getCallgraphTo("NonExistentClass.method")
    assertEquals(0, emptyCallgraphTo.size(), "Callgraph to non-existent method should be empty")
    
    val emptyCallgraphFrom = analyzer.getCallgraphFrom("NonExistentClass.method")
    assertEquals(0, emptyCallgraphFrom.size(), "Callgraph from non-existent method should be empty")
  }
  
  @Test
  def testGetCallgraphRecursion(): Unit = {
    val analyzer = getAnalyzer
    
    // Call A.method5 which has a self-reference
    val callgraphFrom = analyzer.getCallgraphFrom("A.method5")
    assertTrue(callgraphFrom.size() > 0, "Should have some calls even with recursion")
    
    // Call D.methodD2 which calls methodD1 which indirectly calls back to D via B.callsIntoA
    val callgraphTo = analyzer.getCallgraphTo("D.methodD1")
    assertTrue(callgraphTo.containsKey("D.methodD2"), "Should detect D.methodD2 calls D.methodD1")
  }
  
  @Test
  def testCallCycles(): Unit = {
    val analyzer = getAnalyzer
    
    // CyclicMethods has a cycle: methodA -> methodB -> methodC -> methodA
    // Starting from methodA, we should see all three methods in the callgraph
    val callgraphFrom = analyzer.getCallgraphFrom("CyclicMethods.methodA")
    
    // Print out all entries for debugging
    val callsites = asScala(callgraphFrom).toMap
    println("Call graph entries: " + callsites.keySet.mkString(", "))
    
    // Check that we have exactly the expected methods (methodB, methodC, and methodA itself)
    assertEquals(3, callgraphFrom.size(), "Should have methodB, methodC, and methodA")
    assertTrue(callgraphFrom.containsKey("CyclicMethods.methodB"), "Should contain methodB")
    assertTrue(callgraphFrom.containsKey("CyclicMethods.methodC"), "Should contain methodC")
    
    // Verify the source lines are preserved correctly
    assertEquals("this.methodB()", callgraphFrom.get("CyclicMethods.methodB").sourceLine)
    
    // Test the other direction - methodC should show as calling methodA
    val callgraphTo = analyzer.getCallgraphTo("CyclicMethods.methodA")
    assertTrue(callgraphTo.containsKey("CyclicMethods.methodC"), "Should detect methodC calls methodA")
    assertEquals("this.methodA()", callgraphTo.get("CyclicMethods.methodC").sourceLine)
  }
  
  @Test
  def testGetCallgraphFullyQualified(): Unit = {
    val analyzer = getAnalyzer
    
    // Test with the packaged class io.github.jbellis.brokk.Foo
    val callgraphTo = analyzer.getCallgraphTo("io.github.jbellis.brokk.Foo.bar")
    
    // Verify we get the caller with its fully-qualified name
    assertFalse(callgraphTo.isEmpty, "Should have callers to the packaged method")
    assertTrue(callgraphTo.containsKey("UsePackaged.callPackagedMethod"), 
      "Should contain call from UsePackaged.callPackagedMethod")
    assertEquals("foo.bar()", callgraphTo.get("UsePackaged.callPackagedMethod").sourceLine)
    
    // Test the other direction
    val callgraphFrom = analyzer.getCallgraphFrom("UsePackaged.callPackagedMethod")
    
    // Verify we get the callee with its fully-qualified name
    assertFalse(callgraphFrom.isEmpty, "Should have callees from UsePackaged.callPackagedMethod")
    assertTrue(callgraphFrom.containsKey("io.github.jbellis.brokk.Foo.bar"), 
      "Should contain call to io.github.jbellis.brokk.Foo.bar with fully-qualified name")
    assertEquals("foo.bar()", callgraphFrom.get("io.github.jbellis.brokk.Foo.bar").sourceLine)
  }
  
  /** Helper to get a prebuilt analyzer */
  private def getAnalyzer = {
    Analyzer(Path.of("src/test/resources/testcode"))
  }
}