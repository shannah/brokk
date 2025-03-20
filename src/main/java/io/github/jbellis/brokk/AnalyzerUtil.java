package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.CallSite;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
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
     * @param depth      The maximum depth to traverse in the call graph
     * @return A formatted string representing the call graph
     */
    public static String formatCallGraphTo(IAnalyzer analyzer, String methodName, int depth) {
        var callgraph = analyzer.getCallgraphTo(methodName, depth);
        if (callgraph.isEmpty()) {
            return "No callers found for: " + methodName;
        }

        return formatCallGraph(callgraph, methodName, true);
    }

    private record StackEntry(String method, int depth) {}

    /**
     * Helper method to recursively format the call graph (both callers and callees)
     */
    private static String formatCallGraph(Map<String, List<CallSite>> callgraph,
                                          String rootMethodName,
                                          boolean isCallerGraph)
    {
        var result = new StringBuilder();
        String arrow = isCallerGraph ? "<-" : "->";

        var visited = new HashSet<String>();
        var stack = new ArrayDeque<>(List.of(new StackEntry(rootMethodName, 0)));

        // Process each method
        result.append(rootMethodName).append("\n");
        while (!stack.isEmpty()) {
            var entry = stack.pop();
            var sites = callgraph.get(entry.method);
            if (sites == null) {
                continue;
            }
            sites.stream().sorted().forEach(site -> {
                result.append("""
                 %s %s
                 ```
                 %s
                 ```
                """.stripIndent().indent(2 * entry.depth)
                                      .formatted(arrow, site.target().fqName(), site.sourceLine()));

                // Process this method's callers/callees (if not already processed)
                if (visited.add(site.target().fqName())) {
                    stack.push(new StackEntry(site.target().fqName(), entry.depth + 1));
                }
            });
        }

        return result.toString();
    }

    /**
     * Formats the call graph for methods called by the specified method.
     *
     * @param analyzer   The analyzer to use
     * @param methodName The fully-qualified name of the method
     * @param depth      The maximum depth to traverse in the call graph
     * @return A formatted string representing the call graph
     */
    public static String formatCallGraphFrom(IAnalyzer analyzer, String methodName, int depth) {
        var callgraph = analyzer.getCallgraphFrom(methodName, depth);
        if (callgraph.isEmpty()) {
            return "No callees found for: " + methodName;
        }

        // Format the call graph
        return formatCallGraph(callgraph, methodName, false);
    }

}
