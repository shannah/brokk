package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.CallSite;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AnalyzerUtil {
    @NotNull
    static AnalyzerWrapper.CodeWithSource processUsages(IAnalyzer analyzer, List<CodeUnit> uses) {
        StringBuilder code = new StringBuilder();
        Set<CodeUnit> sources = new HashSet<>();

        // method uses
        var methodUses = uses.stream()
                .filter(CodeUnit::isFunction)
                .sorted()
                .toList();
        // type uses
        var typeUses = uses.stream()
                .filter(CodeUnit::isClass)
                .sorted()
                .toList();

        if (!methodUses.isEmpty()) {
            Map<String, List<String>> groupedMethods = new LinkedHashMap<>();
            for (var cu : methodUses) {
                var source = analyzer.getMethodSource(cu.fqName());
                if (source.isDefined()) {
                    String classname = ContextFragment.toClassname(cu.fqName());
                    groupedMethods.computeIfAbsent(classname, k -> new ArrayList<>()).add(source.get());
                    sources.add(cu);
                }
            }
            if (!groupedMethods.isEmpty()) {
                code.append("Method uses:\n\n");
                for (var entry : groupedMethods.entrySet()) {
                    var methods = entry.getValue();
                    if (!methods.isEmpty()) {
                        code.append("In ").append(entry.getKey()).append(":\n\n");
                        for (String ms : methods) {
                            code.append(ms).append("\n\n");
                        }
                    }
                }
            }
        }

        if (!typeUses.isEmpty()) {
            code.append("Type uses:\n\n");
            for (var cu : typeUses) {
                var skeletonHeader = analyzer.getSkeletonHeader(cu.fqName());
                if (skeletonHeader.isEmpty()) {
                    continue;
                }
                code.append(skeletonHeader.get()).append("\n");
                sources.add(cu);
            }
        }

        return new AnalyzerWrapper.CodeWithSource(code.toString(), sources);
    }

    public static List<String> combinedPageRankFor(IAnalyzer analyzer, Map<String, Double> weightedSeeds) {
        // do forward and reverse pagerank passes
        var forwardResults = analyzer.getPagerank(weightedSeeds, 3 * Context.MAX_AUTO_CONTEXT_FILES, false);
        var reverseResults = analyzer.getPagerank(weightedSeeds, 3 * Context.MAX_AUTO_CONTEXT_FILES, true);

        // combine results by summing scores
        var combinedScores = new HashMap<String, Double>();
        forwardResults.forEach(pair -> combinedScores.put(pair._1, pair._2));
        reverseResults.forEach(pair -> combinedScores.merge(pair._1, pair._2, Double::sum));

        // sort by combined score
        return combinedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .filter(analyzer::isClassInProject)
                .toList();
    }

    /**
     * Formats the call graph for methods that call the specified method.
     *
     * @param analyzer   The analyzer to use
     * @param methodName The fully-qualified name of the method
     * @param depth
     * @return A formatted string representing the call graph
     */
    public static String formatCallGraphTo(IAnalyzer analyzer, String methodName, int depth) {
        var callgraph = analyzer.getCallgraphTo(methodName, depth);
        if (callgraph.isEmpty()) {
            return "No callers found for: " + methodName;
        }

        StringBuilder result = new StringBuilder();
        result.append("Root: ").append(methodName).append("\n");

        // Cache of already processed methods to avoid cycles
        Set<String> processedMethods = new HashSet<>();
        processedMethods.add(methodName); // Add root to avoid processing it

        // Format the call graph
        formatCallers(result, callgraph, methodName, 1, processedMethods, analyzer);

        return result.toString();
    }

    /**
     * Helper method to recursively format callers
     */
    private static void formatCallers(StringBuilder result, Map<String, CallSite> callgraph,
                                      String currentMethod, int depth, Set<String> processedMethods,
                                      IAnalyzer analyzer) {
        String indent = " ".repeat(depth);

        // Process each direct caller of the current method
        // In the map, callers are keys and the values are CallSites
        List<String> callers = new ArrayList<>(callgraph.keySet());

        // Sort callers for consistent output
        callers.sort(String::compareTo);

        // Process each caller
        for (String caller : callers) {
            CallSite callSite = callgraph.get(caller);

            // Add caller with arrow
            result.append(indent).append(" <- ").append(caller).append("\n");

            // Add source line in a code block
            result.append(indent).append(" ```\n");
            result.append(indent).append(" ").append(callSite.sourceLine()).append("\n");
            result.append(indent).append(" ```\n");

            // Recursively process this caller's callers (if not already processed)
            if (!processedMethods.contains(caller)) {
                processedMethods.add(caller);
                // FIXME this is broken
            }
        }
    }

    /**
     * Formats the call graph for methods called by the specified method.
     *
     * @param analyzer   The analyzer to use
     * @param methodName The fully-qualified name of the method
     * @param depth
     * @return A formatted string representing the call graph
     */
    public static String formatCallGraphFrom(IAnalyzer analyzer, String methodName, int depth) {
        var callgraph = analyzer.getCallgraphFrom(methodName, depth);
        if (callgraph.isEmpty()) {
            return "No callees found for: " + methodName;
        }

        StringBuilder result = new StringBuilder();
        result.append("Root: ").append(methodName).append("\n");

        // Cache of already processed methods to avoid cycles
        Set<String> processedMethods = new HashSet<>();
        processedMethods.add(methodName); // Add root to avoid processing it

        // Format the call graph
        formatCallees(result, callgraph, methodName, 1, processedMethods, analyzer);

        return result.toString();
    }

    /**
     * Helper method to recursively format callees
     */
    private static void formatCallees(StringBuilder result, Map<String, CallSite> callgraph,
                                      String currentMethod, int depth, Set<String> processedMethods,
                                      IAnalyzer analyzer) {
        String indent = " ".repeat(depth);

        // Process each direct callee of the current method
        // In the map, callees are keys and the values are CallSites
        List<String> callees = new ArrayList<>(callgraph.keySet());

        // Sort callees for consistent output
        callees.sort(String::compareTo);

        // Process each callee
        for (String callee : callees) {
            CallSite callSite = callgraph.get(callee);

            // Add callee with arrow
            result.append(indent).append(" -> ").append(callee).append("\n");

            // Add source line in a code block
            result.append(indent).append(" ```\n");
            result.append(indent).append(" ").append(callSite.sourceLine()).append("\n");
            result.append(indent).append(" ```\n");

            // Recursively process this callee's callees (if not already processed)
            if (!processedMethods.contains(callee)) {
                processedMethods.add(callee);
                // Get the callee's own callees from the analyzer
                // FIXME
            }
        }
    }
}
