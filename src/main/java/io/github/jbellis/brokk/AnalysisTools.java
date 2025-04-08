package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scala.Option;
import scala.Tuple2;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Contains tool implementations related to code analysis and searching,
 * designed to be registered with the ToolRegistry.
 */
public class AnalysisTools {
    private static final Logger logger = LogManager.getLogger(AnalysisTools.class);

    private final IAnalyzer analyzer;
    private final IContextManager contextManager; // Needed for file operations

    public AnalysisTools(IAnalyzer analyzer, IContextManager contextManager) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer cannot be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
    }

    // --- Tool Methods (mostly moved from SearchAgent) ---

    @Tool(value = """
    Search for symbols (class/method/field definitions) using Joern.
    This should usually be the first step in a search.
    """)
    public String searchSymbols(
            @P("Case-insensitive Joern regex patterns to search for code symbols. Since ^ and $ are implicitly included, YOU MUST use explicit wildcarding (e.g., .*Foo.*, Abstract.*, [a-z]*DAO) unless you really want exact matches.")
            List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        if (analyzer.isEmpty()) {
            return "Cannot search definitions: Code analyzer is not available.";
        }
        if (patterns.isEmpty()) {
            return "Cannot search definitions: patterns list is empty";
        }
        if (reasoning.isBlank()) {
            // Tolerate missing reasoning for now, maybe make mandatory later
            logger.warn("Missing reasoning for searchSymbols call");
            // return "Cannot search definitions: missing or empty reasoning parameter";
        }

        Set<CodeUnit> allDefinitions = new HashSet<>();
        for (String pattern : patterns) {
            if (!pattern.isBlank()) {
                allDefinitions.addAll(analyzer.getDefinitions(pattern));
            }
        }

        if (allDefinitions.isEmpty()) {
            return "No definitions found for patterns: " + String.join(", ", patterns);
        }

        logger.debug("Raw definitions: {}", allDefinitions);

        var references = allDefinitions.stream()
                .map(CodeUnit::fqName)
                .distinct() // Ensure uniqueness
                .sorted()   // Consistent order
                .toList();

        return String.join(", ", references);
    }

    @Tool(value = """
    Returns the source code of blocks where symbols are used. Use this to discover how classes, methods, or fields are actually used throughout the codebase.
    """)
    public String getUsages(
            @P("Fully qualified symbol names (package name, class name, optional member name) to find usages for")
            List<String> symbols,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        if (analyzer.isEmpty()) {
            return "Cannot search usages: Code analyzer is not available.";
        }
        if (symbols.isEmpty()) {
            return "Cannot search usages: symbols list is empty";
        }
        if (reasoning.isBlank()) {
             logger.warn("Missing reasoning for getUsages call");
            // return "Cannot search usages: missing or empty reasoning parameter";
        }

        List<CodeUnit> allUses = new ArrayList<>();
        for (String symbol : symbols) {
            if (!symbol.isBlank()) {
                allUses.addAll(analyzer.getUses(symbol));
            }
        }

        if (allUses.isEmpty()) {
            return "No usages found for: " + String.join(", ", symbols);
        }

        var processedUsages = AnalyzerUtil.processUsages(analyzer, allUses).code();
        return "Usages of " + String.join(", ", symbols) + ":\n\n" + processedUsages;
    }

    @Tool(value = """
    Returns a list of related class names, ordered by relevance (using PageRank).
    Use this for exploring and also when you're almost done and want to double-check that you haven't missed anything.
    """)
    public String getRelatedClasses(
            @P("List of fully qualified class names to use as seeds for finding related classes.")
            List<String> classNames
    ) {
        if (analyzer.isEmpty()) {
            return "Cannot find related classes: Code analyzer is not available.";
        }
        if (classNames.isEmpty()) {
            return "Cannot search pagerank: classNames is empty";
        }

        // Create map of seeds from discovered units
        HashMap<String, Double> weightedSeeds = new HashMap<>();
        for (String className : classNames) {
            weightedSeeds.put(className, 1.0);
        }

        var pageRankUnits = AnalyzerUtil.combinedPagerankFor(analyzer, weightedSeeds);

        if (pageRankUnits.isEmpty()) {
            return "No related code found via PageRank for seeds: " + String.join(", ", classNames);
        }

        var pageRankResults = pageRankUnits.stream().map(CodeUnit::fqName).toList();

        // Get skeletons for the top few *original* seed classes, not the PR results
        var prResult = classNames.stream().distinct()
                .limit(10) // padding in case of not defined
                .map(analyzer::getSkeleton)
                .filter(Option::isDefined)
                .limit(5)
                .map(Option::get)
                .collect(Collectors.joining("\n\n"));
        var formattedPrResult = prResult.isEmpty() ? "" : "# Summaries of top 5 seed classes: \n\n" + prResult + "\n\n";

        // Format the compressed list of related classes found by pagerank
        List<String> resultsList = pageRankResults.stream().limit(50).toList();
        var formattedResults = formatCompressedSymbols("# List of related classes (up to 50)", resultsList);

        return formattedPrResult + formattedResults;
    }

    @Tool(value = """
    Returns an overview of classes' contents, including fields and method signatures.
    Use this to understand class structures and APIs much faster than fetching full source code.
    """)
    public String getClassSkeletons(
            @P("Fully qualified class names to get the skeleton structures for")
            List<String> classNames
    ) {
        if (analyzer.isEmpty()) {
            return "Cannot get skeletons: Code analyzer is not available.";
        }
        if (classNames.isEmpty()) {
            return "Cannot get skeletons: class names list is empty";
        }

        var result = classNames.stream().distinct().map(analyzer::getSkeleton)
                .filter(Option::isDefined)
                .map(Option::get)
                .collect(Collectors.joining("\n\n"));

        if (result.isEmpty()) {
            return "No skeletons found for classes: " + String.join(", ", classNames);
        }

        return result;
    }

    @Tool(value = """
    Returns the full source code of classes.
    This is expensive, so prefer requesting skeletons or method sources when possible.
    Use this when you need the complete implementation details, or if you think multiple methods in the classes may be relevant.
    """)
    public String getClassSources(
            @P("Fully qualified class names to retrieve the full source code for")
            List<String> classNames,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        if (analyzer.isEmpty()) {
            return "Cannot get class sources: Code analyzer is not available.";
        }
        if (classNames.isEmpty()) {
            return "Cannot get class sources: class names list is empty";
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for getClassSources call");
            // return "Cannot get class sources: missing or empty reasoning parameter";
        }

        StringBuilder result = new StringBuilder();
        Set<String> processedSources = new HashSet<>(); // Avoid duplicates if multiple names map to same source

        for (String className : classNames) {
            if (!className.isBlank()) {
                // analyzer.getClassSource returns Option<String>
                var classSource = analyzer.getClassSource(className);
                if (classSource != null) { // Check the Option
                     if (!classSource.isEmpty() && processedSources.add(classSource)) {
                         if (!result.isEmpty()) {
                             result.append("\n\n");
                         }
                         // Include filename from analyzer if possible
                         String filename = analyzer.getFileFor(className).map(ProjectFile::toString).getOrElse(() -> "unknown file");
                         result.append("Source code of ").append(className)
                               .append(" (from ").append(filename).append("):\n\n")
                               .append(classSource);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            return "No sources found for classes: " + String.join(", ", classNames);
        }

        return result.toString();
    }

    @Tool(value = """
    Returns the full source code of specific methods. Use this to examine the implementation of particular methods without retrieving the entire classes.
    """)
    public String getMethodSources(
            @P("Fully qualified method names (package name, class name, method name) to retrieve sources for")
            List<String> methodNames
    ) {
         if (analyzer.isEmpty()) {
            return "Cannot get method sources: Code analyzer is not available.";
        }
        if (methodNames.isEmpty()) {
            return "Cannot get method sources: method names list is empty";
        }

        StringBuilder result = new StringBuilder();
        Set<String> processedMethodSources = new HashSet<>();

        for (String methodName : methodNames) {
            if (!methodName.isBlank()) {
                var methodSourceOpt = analyzer.getMethodSource(methodName);
                if (methodSourceOpt.isDefined()) {
                    String methodSource = methodSourceOpt.get();
                    if (!processedMethodSources.contains(methodSource)) {
                        processedMethodSources.add(methodSource);
                        if (!result.isEmpty()) {
                            result.append("\n\n");
                        }
                        result.append("// Source for ").append(methodName).append("\n");
                        result.append(methodSource);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        return result.toString();
    }

    @Tool(value = """
    Returns the call graph to a depth of 5 showing which methods call the given method and one line of source code for each invocation.
    Use this to understand method dependencies and how code flows into a method.
    """)
    public String getCallGraphTo(
            @P("Fully qualified method name (package name, class name, method name) to find callers for")
            String methodName
    ) {
        if (analyzer.isEmpty()) {
            return "Cannot get call graph: Code analyzer is not available.";
        }
        if (methodName.isBlank()) {
            return "Cannot get call graph: method name is empty";
        }

        var graph = analyzer.getCallgraphTo(methodName, 5);
        return AnalyzerUtil.formatCallGraph(graph, methodName, false);
    }

    @Tool(value = """
    Returns the call graph to a depth of 5 showing which methods are called by the given method and one line of source code for each invocation.
    Use this to understand how a method's logic flows to other parts of the codebase.
    """)
    public String getCallGraphFrom(
            @P("Fully qualified method name (package name, class name, method name) to find callees for")
            String methodName
    ) {
        if (analyzer.isEmpty()) {
            return "Cannot get call graph: Code analyzer is not available.";
        }
        if (methodName.isBlank()) {
            return "Cannot get call graph: method name is empty";
        }

        var graph = analyzer.getCallgraphFrom(methodName, 5); // Use correct analyzer method
        return AnalyzerUtil.formatCallGraph(graph, methodName, true);
    }

    // Text search tools remain largely the same but need contextManager
    @Tool(value = """
    Returns file names whose text contents match Java regular expression patterns.
    This is slower than searchSymbols but can find references to external dependencies and comment strings.
    """)
    public String searchSubstrings(
            @P("Java-style regex patterns to search for within file contents. Unlike searchSymbols this does not automatically include any implicit anchors or case insensitivity.")
            List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        if (patterns.isEmpty()) {
            return "Cannot search substrings: patterns list is empty";
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchSubstrings call");
           // return "Cannot search substrings: missing or empty reasoning parameter";
        }

        logger.debug("Searching file contents for patterns: {}", patterns);

        try {
            List<Pattern> compiledPatterns = patterns.stream()
                    .filter(p -> !p.isBlank())
                    .map(Pattern::compile)
                    .toList();

            if (compiledPatterns.isEmpty()) {
                return "No valid patterns provided";
            }

            var matchingFilenames = contextManager.getProject().getFiles().parallelStream().map(file -> {
                        try {
                            if (!file.isText()) {
                                return null;
                            }
                            String fileContents = file.read(); // Use ProjectFile.read()

                            for (Pattern compiledPattern : compiledPatterns) {
                                if (compiledPattern.matcher(fileContents).find()) {
                                    return file;
                                }
                            }
                            return null;
                        } catch (Exception e) {
                            logger.debug("Error processing file {}", file, e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .map(ProjectFile::toString)
                    .collect(Collectors.toSet());

            if (matchingFilenames.isEmpty()) {
                var msg = "No files found with content matching patterns: " + String.join(", ", patterns);
                logger.debug(msg);
                return msg;
            }

            var msg = "Files with content matching patterns: " + String.join(", ", matchingFilenames);
            logger.debug(msg);
            return msg;
        } catch (Exception e) {
            logger.error("Error searching file contents", e);
            return "Error searching file contents: " + e.getMessage();
        }
    }

    @Tool(value = """
    Returns filenames (relative to the project root) that match the given Java regular expression patterns.
    Use this to find configuration files, test data, or source files when you know part of their name.
    """)
    public String searchFilenames(
            @P("Java-style regex patterns to match against filenames.")
            List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        if (patterns.isEmpty()) {
            return "Cannot search filenames: patterns list is empty";
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchFilenames call");
            // return "Cannot search filenames: missing or empty reasoning parameter";
        }

        logger.debug("Searching filenames for patterns: {}", patterns);

        try {
            List<Pattern> compiledPatterns = patterns.stream()
                    .filter(p -> !p.isBlank())
                    .map(Pattern::compile)
                    .toList();

            if (compiledPatterns.isEmpty()) {
                return "No valid patterns provided";
            }

            var matchingFiles = contextManager.getProject().getFiles().stream()
                    .map(ProjectFile::toString) // Use relative path from ProjectFile
                    .filter(filePath -> {
                        for (Pattern compiledPattern : compiledPatterns) {
                            if (compiledPattern.matcher(filePath).find()) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .collect(Collectors.toList());

            if (matchingFiles.isEmpty()) {
                return "No filenames found matching patterns: " + String.join(", ", patterns);
            }

            return "Matching filenames: " + String.join(", ", matchingFiles);
        } catch (Exception e) {
            logger.error("Error searching filenames", e);
            return "Error searching filenames: " + e.getMessage();
        }
    }

    @Tool(value = """
    Returns the full contents of the specified files. Use this after searchFilenames or searchSubstrings, or when you need the content of a non-code file.
    This can be expensive for large files.
    """)
    public String getFileContents(
            @P("List of filenames (relative to project root) to retrieve contents for.")
            List<String> filenames
    ) {
        if (filenames.isEmpty()) {
            return "Cannot get file contents: filenames list is empty";
        }

        logger.debug("Getting contents for files: {}", filenames);

        return filenames.stream()
                .distinct()
                .map(filename -> {
                    try {
                        var file = contextManager.toFile(filename); // Use contextManager
                        if (!file.exists()) {
                            logger.debug("File not found or not a regular file: {}", file);
                            return "<file name=\"%s\">\nError: File not found or not a regular file.\n</file>".formatted(filename);
                        }
                        var content = file.read();
                        return "<file name=\"%s\">\n%s\n</file>".formatted(filename, content);
                    } catch (IOException e) {
                        logger.error("Error reading file content for {}: {}", filename, e.getMessage());
                        return "<file name=\"%s\">\nError reading file: %s\n</file>".formatted(filename, e.getMessage());
                    } catch (Exception e) {
                        logger.error("Unexpected error getting content for {}: {}", filename, e.getMessage());
                        return "<file name=\"%s\">\nUnexpected error: %s\n</file>".formatted(filename, e.getMessage());
                    }
                })
                .collect(Collectors.joining("\n\n"));
    }

    // Agent control tools remain specific to the agent's logic flow,
    // but are defined here to be discoverable by the registry.
    // Their execution doesn't do much here; the agent interprets their *selection* by the LLM.
    @Tool(value = "Provide a final answer to the query. Use this when you have enough information to fully address the query.")
    public String answer(
            @P("Comprehensive explanation that answers the query. Include relevant source code snippets and explain how they relate to the query. Format the entire explanation with Markdown.")
            String explanation,
            @P("List of fully qualified class names (FQCNs) of ALL classes relevant to the explanation. Do not skip even minor details!")
            List<String> classNames
    ) {
        if (explanation.isBlank()) {
            // LLM might sometimes fail to provide explanation, handle gracefully.
             logger.warn("Answer tool called with empty explanation.");
             return "Placeholder: Answer requested, processing...";
           // throw new IllegalArgumentException("Empty or missing explanation parameter");
        }
        if (classNames == null || classNames.isEmpty()) {
             logger.warn("Answer tool called with empty classNames list.");
             // Maybe allow this? Or enforce it? For now, allow.
        }

        logger.debug("Answer tool selected by LLM. Explanation hint: {}", explanation);
        logger.debug("Answer tool selected by LLM. Referenced classes hint: {}", classNames);

        // The actual return value isn't crucial here, as the SearchAgent intercepts
        // the selection of this tool. We return the explanation for potential logging/history.
        return explanation;
    }

    @Tool(value = """
    Abort the search process when you determine the question is not relevant to this codebase or when an answer cannot be found.
    Use this as a last resort when you're confident no useful answer can be provided.
    """)
    public String abort(
            @P("Explanation of why the question cannot be answered or is not relevant to this codebase")
            String explanation
    ) {
        if (explanation.isBlank()) {
             logger.warn("Abort tool called with empty explanation.");
             return "Placeholder: Abort requested, processing...";
            // throw new IllegalArgumentException("Empty or missing explanation parameter");
        }

        logger.debug("Abort tool selected by LLM: {}", explanation);

        // Similar to answer, the agent intercepts the selection.
        return "SEARCH ABORTED: " + explanation;
    }


    // --- Helper Methods (also moved from SearchAgent) ---

    /**
     * Compresses a list of fully qualified symbol names by finding the longest common package prefix
     * and removing it from each symbol.
     * @param symbols A list of fully qualified symbol names
     * @return A tuple containing: 1) the common package prefix, 2) the list of compressed symbol names
     */
    static Tuple2<String, List<String>> compressSymbolsWithPackagePrefix(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return new Tuple2<>("", List.of());
        }

        List<String[]> packageParts = symbols.stream()
                .filter(Objects::nonNull) // Filter nulls just in case
                .map(s -> s.split("\\."))
                .filter(arr -> arr.length > 0) // Ensure split resulted in something
                .toList();

        if (packageParts.isEmpty()) {
             return new Tuple2<>("", List.of());
        }

        String[] firstParts = packageParts.getFirst();
        int maxPrefixLength = 0;

        for (int i = 0; i < firstParts.length - 1; i++) { // Stop before last part (class/method)
            boolean allMatch = true;
            for (String[] parts : packageParts) {
                // Ensure current part exists and matches
                if (i >= parts.length - 1 || !parts[i].equals(firstParts[i])) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                maxPrefixLength = i + 1;
            } else {
                break;
            }
        }

        if (maxPrefixLength > 0) {
            String commonPrefix = String.join(".", Arrays.copyOfRange(firstParts, 0, maxPrefixLength)) + ".";
            List<String> compressedSymbols = symbols.stream()
                    .map(s -> s != null && s.startsWith(commonPrefix) ? s.substring(commonPrefix.length()) : s)
                    .collect(Collectors.toList());
            return new Tuple2<>(commonPrefix, compressedSymbols);
        }

        return new Tuple2<>("", symbols); // Return original list if no common prefix
    }

    /**
     * Formats a list of symbols with prefix compression if applicable.
     * @param label The label to use in the output (e.g., "Relevant symbols", "Related classes")
     * @param symbols The list of symbols to format
     * @return A formatted string with compressed symbols if possible
     */
    private String formatCompressedSymbols(String label, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return label + ": None found";
        }

        var compressionResult = compressSymbolsWithPackagePrefix(symbols);
        String commonPrefix = compressionResult._1();
        List<String> compressedSymbols = compressionResult._2();

        if (commonPrefix.isEmpty()) {
            // Sort for consistent output when no compression happens
            return label + ": " + symbols.stream().sorted().collect(Collectors.joining(", "));
        }

        // Sort compressed symbols too
        return "%s: [Common package prefix: '%s'. IMPORTANT: you MUST use full symbol names including this prefix for subsequent tool calls] %s"
                .formatted(label, commonPrefix, compressedSymbols.stream().sorted().collect(Collectors.joining(", ")));
     }

    // Internal helper for formatting the compressed string. Public static.
     public static String formatCompressedSymbolsInternal(String label, List<String> compressedSymbols, String commonPrefix) {
          if (commonPrefix.isEmpty()) {
              // Sort for consistent output when no compression happens
              return label + ": " + compressedSymbols.stream().sorted().collect(Collectors.joining(", "));
          }
          // Sort compressed symbols too
          return "%s: [Common package prefix: '%s'. IMPORTANT: you MUST use full symbol names including this prefix for subsequent tool calls] %s"
                  .formatted(label, commonPrefix, compressedSymbols.stream().sorted().collect(Collectors.joining(", ")));
     }
}
