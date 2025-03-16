package io.github.jbellis.brokk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnalyzerWrapperTest {

    private TestAnalyzer analyzer;

    @BeforeEach
    public void setup() {
        analyzer = new TestAnalyzer();
    }
    
    @Test
    public void testFormatCallGraphTo_FullOutput() {
        // Setup a simple call graph
        analyzer.addCallTo("Main.process", "Client.callProcess", "process(data); // process the data");
        
        String result = AnalyzerUtil.formatCallGraphTo(analyzer, "Main.process");
        
        // Use triple quotes with stripIndent for better readability
        String expected = """
            Root: Main.process
              <- Client.callProcess
              ```
              process(data); // process the data
              ```
            """.stripIndent();
        
        assertEquals(expected, result);
    }
    
    @Test
    public void testFormatCallGraphFrom_FullOutput() {
        // Setup a simple call graph
        analyzer.addCallFrom("Controller.execute", "Service.performAction", "service.performAction(request);");
        
        String result = AnalyzerUtil.formatCallGraphFrom(analyzer, "Controller.execute");
        
        // Use triple quotes with stripIndent for better readability
        String expected = """
            Root: Controller.execute
              -> Service.performAction
              ```
              service.performAction(request);
              ```
            """.stripIndent();
        
        assertEquals(expected, result);
    }
    
    @Test
    public void testFormatCallGraphTo_WithNestedCallsFullOutput() {
        // Setup a call graph with nested calls
        analyzer.addCallTo("App.start", "Main.initialize", "app.start();");
        analyzer.addCallTo("Main.initialize", "Config.load", "initialize();");
        
        String result = AnalyzerUtil.formatCallGraphTo(analyzer, "App.start");
        
        // Use triple quotes with stripIndent for better readability
        String expected = """
            Root: App.start
              <- Main.initialize
              ```
              app.start();
              ```
               <- Config.load
               ```
               initialize();
               ```
            """.stripIndent();
        
        assertEquals(expected, result);
    }

    @Test
    public void testFormatCallGraphTo_NoCallers() {
        String result = AnalyzerUtil.formatCallGraphTo(analyzer, "nonexistent.method");
        assertEquals("No callers found for: nonexistent.method", result);
    }

    @Test
    public void testFormatCallGraphTo_WithCallers() {
        // Add test data to our analyzer
        analyzer.addCallTo("CyclicMethods.methodA", 
                          "CyclicMethods.methodC", 
                          "methodA(); // creates a cycle A->B->C->A");
        
        analyzer.addCallTo("CyclicMethods.methodB", 
                          "CyclicMethods.methodA", 
                          "methodB();");
        
        analyzer.addCallTo("CyclicMethods.methodC", 
                          "CyclicMethods.methodB", 
                          "methodC();");

        String result = AnalyzerUtil.formatCallGraphTo(analyzer, "CyclicMethods.methodA");
        System.out.println("Call graph TO result:\n" + result);
        
        // Debug the data structure to see what's happening
        System.out.println("DEBUG - callsTo map content for CyclicMethods.methodA:");
        analyzer.getCallgraphTo("CyclicMethods.methodA").forEach((k, v) -> 
            System.out.println("  Key: " + k + ", Value: CallSite(" + v.signature() + ", " + v.sourceLine() + ")"));
        
        System.out.println("DEBUG - callsTo map content for CyclicMethods.methodB:");
        analyzer.getCallgraphTo("CyclicMethods.methodB").forEach((k, v) -> 
            System.out.println("  Key: " + k + ", Value: CallSite(" + v.signature() + ", " + v.sourceLine() + ")"));
        
        System.out.println("DEBUG - callsTo map content for CyclicMethods.methodC:");
        analyzer.getCallgraphTo("CyclicMethods.methodC").forEach((k, v) -> 
            System.out.println("  Key: " + k + ", Value: CallSite(" + v.signature() + ", " + v.sourceLine() + ")"));
        
        // Verify the output
        assertTrue(result.startsWith("Root: CyclicMethods.methodA"), "Should start with root method");
        assertTrue(result.contains(" <- CyclicMethods.methodC"), "Should show CyclicMethods.methodC as caller");
        assertTrue(result.contains("methodA(); // creates a cycle A->B->C->A"), "Should show source line");
    }

    @Test
    public void testFormatCallGraphFrom_NoCallees() {
        String result = AnalyzerUtil.formatCallGraphFrom(analyzer, "nonexistent.method");
        assertEquals("No callees found for: nonexistent.method", result);
    }

    @Test
    public void testFormatCallGraphFrom_WithCallees() {
        // Add test data to our analyzer
        analyzer.addCallFrom("CyclicMethods.methodA", 
                            "CyclicMethods.methodB", 
                            "methodB();");
        
        analyzer.addCallFrom("CyclicMethods.methodB", 
                            "CyclicMethods.methodC", 
                            "methodC();");
        
        analyzer.addCallFrom("CyclicMethods.methodC", 
                            "CyclicMethods.methodA", 
                            "methodA(); // creates a cycle A->B->C->A");

        String result = AnalyzerUtil.formatCallGraphFrom(analyzer, "CyclicMethods.methodA");
        System.out.println("Call graph FROM result:\n" + result);
        
        // Verify the output
        assertTrue(result.startsWith("Root: CyclicMethods.methodA"));
        assertTrue(result.contains(" -> CyclicMethods.methodB"));
        assertTrue(result.contains("methodB();"));
    }

    @Test
    public void testFormatCallGraphTo_DeepNesting() {
        // Test with deeper nesting - simple chain A <- B <- C <- D
        analyzer.addCallTo("Method.A", "Method.B", "a();");
        analyzer.addCallTo("Method.B", "Method.C", "b();");
        analyzer.addCallTo("Method.C", "Method.D", "c();");
        
        String result = AnalyzerUtil.formatCallGraphTo(analyzer, "Method.A");
        System.out.println("Deep nesting TO result:\n" + result);
        
        // Verify the output shows all levels
        assertTrue(result.contains(" <- Method.B"));
        assertTrue(result.contains("  <- Method.C"));
        assertTrue(result.contains("   <- Method.D"));
    }

    @Test
    public void testFormatCallGraphFrom_DeepNesting() {
        // Test with deeper nesting - simple chain A -> B -> C -> D
        analyzer.addCallFrom("Method.A", "Method.B", "b();");
        analyzer.addCallFrom("Method.B", "Method.C", "c();");
        analyzer.addCallFrom("Method.C", "Method.D", "d();");
        
        String result = AnalyzerUtil.formatCallGraphFrom(analyzer, "Method.A");
        System.out.println("Deep nesting FROM result:\n" + result);
        
        // Verify the output shows all levels
        assertTrue(result.contains(" -> Method.B"));
        assertTrue(result.contains("  -> Method.C"));
        assertTrue(result.contains("   -> Method.D"));
    }

    @Test
    public void testFormatCallGraphTo_MultipleCallers() {
        // Test with multiple callers at the same level
        analyzer.addCallTo("Method.target", "Method.caller1", "target();");
        analyzer.addCallTo("Method.target", "Method.caller2", "target();");
        analyzer.addCallTo("Method.target", "Method.caller3", "target();");
        
        String result = AnalyzerUtil.formatCallGraphTo(analyzer, "Method.target");
        System.out.println("Multiple callers result:\n" + result);
        
        // Verify all callers are listed
        assertTrue(result.contains(" <- Method.caller1"));
        assertTrue(result.contains(" <- Method.caller2"));
        assertTrue(result.contains(" <- Method.caller3"));
    }

    /**
     * Test analyzer implementation that can be configured with test data
     * The Map needs to be structured with:
     * - Key: caller method name
     * - Value: CallSite with signature = callee method name
     */
    private static class TestAnalyzer implements IAnalyzer {
        // For getCallgraphTo: Map<target, Map<caller, CallSite(target)>>
        private final Map<String, Map<String, CallSite>> callsTo = new HashMap<>();
        
        // For getCallgraphFrom: Map<source, Map<callee, CallSite(callee)>>
        private final Map<String, Map<String, CallSite>> callsFrom = new HashMap<>();
        
        /**
         * Add a caller to a target method
         * @param target The target method that is being called
         * @param caller The caller method that calls the target
         * @param sourceLine The source line showing the call
         */
        public void addCallTo(String target, String caller, String sourceLine) {
            Map<String, CallSite> callerMap = callsTo.computeIfAbsent(target, k -> new HashMap<>());
            callerMap.put(caller, new CallSite(target, sourceLine));
        }
        
        /**
         * Add a callee to a source method
         * @param source The source method that makes the call
         * @param callee The callee method that is called by the source
         * @param sourceLine The source line showing the call
         */
        public void addCallFrom(String source, String callee, String sourceLine) {
            Map<String, CallSite> calleeMap = callsFrom.computeIfAbsent(source, k -> new HashMap<>());
            calleeMap.put(callee, new CallSite(callee, sourceLine));
        }
        
        /**
         * Get all methods that call the specified method, with cycle detection
         */
        @Override
        public Map<String, CallSite> getCallgraphTo(String methodName) {
            // Simple way to handle test cases with cycles - each test directly sets up
            // the data structure, so we don't need to do cycle detection at runtime
            return callsTo.getOrDefault(methodName, new HashMap<>());
        }
        
        /**
         * Get all methods called by the specified method, with cycle detection
         */
        @Override
        public Map<String, CallSite> getCallgraphFrom(String methodName) {
            // Simple way to handle test cases with cycles - each test directly sets up
            // the data structure, so we don't need to do cycle detection at runtime
            return callsFrom.getOrDefault(methodName, new HashMap<>());
        }
    }
}