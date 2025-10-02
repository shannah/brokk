package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Contains tool implementations related to code analysis and searching, designed to be registered with the
 * ToolRegistry.
 */
public class SearchTools {
    /** Record representing compressed symbols with their common prefix. */
    public record CompressedSymbols(String prefix, List<String> symbols) {}

    private static final Logger logger = LogManager.getLogger(SearchTools.class);

    private final IContextManager contextManager; // Needed for file operations

    public SearchTools(IContextManager contextManager) {
        this.contextManager = contextManager;
    }

    // --- Sanitization Helper Methods
    // These methods strip trailing parentheses like "(params)" from symbol strings.
    // This is necessary because LLMs may incorrectly include them, but the underlying
    // code analysis tools expect clean FQNs or symbol names without parameter lists.

    private static String stripParams(String sym) {
        // Remove trailing (...) if it looks like a parameter list
        return sym.replaceAll("(?<=\\w)\\([^)]*\\)$", "");
    }

    private static List<String> stripParams(List<String> syms) {
        return syms.stream().map(SearchTools::stripParams).toList();
    }

    private IAnalyzer getAnalyzer() {
        return contextManager.getAnalyzerUninterrupted();
    }

    // --- Helper Methods

    /**
     * Compresses a list of fully qualified symbol names by finding the longest common package prefix and removing it
     * from each symbol.
     *
     * @param symbols A list of fully qualified symbol names
     * @return A tuple containing: 1) the common package prefix, 2) the list of compressed symbol names
     */
    public static CompressedSymbols compressSymbolsWithPackagePrefix(List<String> symbols) {
        List<String[]> packageParts = symbols.stream()
                .filter(Objects::nonNull) // Filter nulls just in case
                .map(s -> s.split("\\."))
                .filter(arr -> arr.length > 0) // Ensure split resulted in something
                .toList();

        if (packageParts.isEmpty()) {
            return new CompressedSymbols("", List.of());
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
                    .map(s -> s.startsWith(commonPrefix) ? s.substring(commonPrefix.length()) : s)
                    .collect(Collectors.toList());
            return new CompressedSymbols(commonPrefix, compressedSymbols);
        }

        return new CompressedSymbols("", symbols); // Return original list if no common prefix
    }

    /**
     * Formats a list of symbols with prefix compression if applicable.
     *
     * @param label The label to use in the output (e.g., "Relevant symbols", "Related classes")
     * @param symbols The list of symbols to format
     * @return A formatted string with compressed symbols if possible
     */
    // TODO make this use CodeUnit
    private String formatCompressedSymbols(String label, List<String> symbols) {
        if (symbols.isEmpty()) {
            return label + ": None found";
        }

        var compressionResult = compressSymbolsWithPackagePrefix(symbols);
        String commonPrefix = compressionResult.prefix();
        List<String> compressedSymbols = compressionResult.symbols();

        if (commonPrefix.isEmpty()) {
            // Sort for consistent output when no compression happens
            return label + ": " + symbols.stream().sorted().collect(Collectors.joining(", "));
        }

        // Sort compressed symbols too
        return "%s: [Common package prefix: '%s'. IMPORTANT: you MUST use full symbol names including this prefix for subsequent tool calls] %s"
                .formatted(
                        label, commonPrefix, compressedSymbols.stream().sorted().collect(Collectors.joining(", ")));
    }

    /**
     * Build predicates for each supplied pattern. • If the pattern is a valid regex, the predicate performs
     * {@code matcher.find()}. • If the pattern is an invalid regex, the predicate falls back to
     * {@code String.contains()}.
     */
    private static List<Predicate<String>> compilePatternsWithFallback(List<String> patterns) {
        List<Predicate<String>> predicates = new ArrayList<>();
        for (String pat : patterns) {
            if (pat == null || pat.isBlank()) {
                continue;
            }
            try {
                Pattern regex = Pattern.compile(pat);
                predicates.add(s -> regex.matcher(s).find());

                // Also handle the common "double-escaped dot" case (e.g. .*\\\\.java)
                if (pat.contains("\\\\.")) {
                    String singleEscaped = pat.replaceAll("\\\\\\\\.", "\\\\.");
                    if (!singleEscaped.equals(pat)) {
                        try {
                            Pattern alt = Pattern.compile(singleEscaped);
                            predicates.add(s -> alt.matcher(s).find());
                        } catch (PatternSyntaxException ignored) {
                            // If even the alternative is invalid we silently ignore it.
                        }
                    }
                }
            } catch (PatternSyntaxException ex) {
                // Fallback: simple substring match, but normalize to forward slashes
                predicates.add(s -> s.contains(pat.replace('\\', '/')));
            }
        }
        return predicates;
    }

    @Tool(
            """
                            Retrieves summaries (fields and method signatures) for all classes defined within specified project files.
                            Supports glob patterns: '*' matches files in a single directory, '**' matches files recursively.
                            This is a fast and efficient way to read multiple related files at once.
                            (But if you don't know where what you want is located, you should use searchSymbols instead.)
                            """)
    public String getFileSummaries(
            @P(
                            "List of file paths relative to the project root. Supports glob patterns (* for single directory, ** for recursive). E.g., ['src/main/java/com/example/util/*.java', 'tests/foo/**.py']")
                    List<String> filePaths) {
        assert getAnalyzer().as(SkeletonProvider.class).isPresent()
                : "Cannot get summaries: Code Intelligence is not available.";
        if (filePaths.isEmpty()) {
            return "Cannot get summaries: file paths list is empty";
        }

        var project = contextManager.getProject();
        List<ProjectFile> projectFiles = filePaths.stream()
                .flatMap(pattern -> Completions.expandPath(project, pattern).stream())
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .distinct()
                .sorted() // Sort for deterministic output order
                .toList();

        if (projectFiles.isEmpty()) {
            return "No project files found matching the provided patterns: " + String.join(", ", filePaths);
        }

        List<String> allSkeletons = new ArrayList<>();
        List<String> filesProcessed = new ArrayList<>(); // Still useful for the "not found" message
        for (var file : projectFiles) {
            var skeletonsInFile = ((SkeletonProvider) getAnalyzer()).getSkeletons(file);
            if (!skeletonsInFile.isEmpty()) {
                // Add all skeleton strings from this file to the list
                allSkeletons.addAll(skeletonsInFile.values());
                filesProcessed.add(file.toString());
            } else {
                logger.debug("No skeletons found in file: {}", file);
            }
        }

        if (allSkeletons.isEmpty()) {
            // filesProcessed will be empty if no skeletons were found in any matched file
            var processedFilesString = filesProcessed.isEmpty()
                    ? projectFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", "))
                    : String.join(", ", filesProcessed);
            return "No class summaries found in the matched files: " + processedFilesString;
        }

        // Return the combined skeleton strings directly, joined by newlines
        return String.join("\n\n", allSkeletons);
    }

    // --- Tool Methods requiring analyzer

    @Tool(
            """
                            Search for symbols (class/method/field definitions) using static analysis.
                            This should usually be the first step in a search.
                            """)
    public String searchSymbols(
            @P(
                            "Case-insensitive regex patterns to search for code symbols. Since ^ and $ are implicitly included, YOU MUST use explicit wildcarding (e.g., .*Foo.*, Abstract.*, [a-z]*DAO) unless you really want exact matches.")
                    List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        // Sanitize patterns: LLM might add `()` to symbols, Joern regex usually doesn't want that unless intentional.
        patterns = stripParams(patterns);
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search definitions: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            // Tolerate missing reasoning for now, maybe make mandatory later
            logger.warn("Missing reasoning for searchSymbols call");
        }

        Set<CodeUnit> allDefinitions = new HashSet<>();
        for (String pattern : patterns) {
            if (!pattern.isBlank()) {
                allDefinitions.addAll(getAnalyzer().searchDefinitions(pattern));
            }
        }
        logger.debug("Raw definitions: {}", allDefinitions);

        if (allDefinitions.isEmpty()) {
            return "No definitions found for patterns: " + String.join(", ", patterns);
        }

        var references = allDefinitions.stream()
                .map(CodeUnit::fqName)
                .distinct() // Ensure uniqueness
                .sorted() // Consistent order
                .toList();

        return String.join(", ", references);
    }

    @Tool(
            """
                            Returns the source code of blocks where symbols are used. Use this to discover how classes, methods, or fields are actually used throughout the codebase.
                            """)
    public String getUsages(
            @P("Fully qualified symbol names (package name, class name, optional member name) to find usages for")
                    List<String> symbols,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        assert getAnalyzer().as(UsagesProvider.class).isPresent()
                : "Cannot search usages: Current Code Intelligence does not have necessary capabilities.";
        // Sanitize symbols: remove potential `(params)` suffix from LLM.
        symbols = stripParams(symbols);
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Cannot search usages: symbols list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for getUsages call");
        }

        List<CodeUnit> allUses = new ArrayList<>();
        if (getAnalyzer() instanceof UsagesProvider usagesProvider) {
            for (String symbol : symbols) {
                if (!symbol.isBlank()) {
                    allUses.addAll(usagesProvider.getUses(symbol));
                }
            }
        }

        if (allUses.isEmpty()) {
            return "No usages found for: " + String.join(", ", symbols);
        }

        var cwsList = AnalyzerUtil.processUsages(getAnalyzer(), allUses);
        var processedUsages = AnalyzerUtil.CodeWithSource.text(cwsList);
        return "Usages of " + String.join(", ", symbols) + ":\n\n" + processedUsages;
    }

    @Tool(
            """
                            Returns a list of related class names, ordered by relevance (using PageRank).
                            Use this for exploring and also when you're almost done and want to double-check that you haven't missed anything.
                            """)
    public String getRelatedClasses(
            @P("List of fully qualified class names to use as seeds for finding related classes.")
                    List<String> classNames) {
        var skp = getAnalyzer()
                .as(SkeletonProvider.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot find related classes: Code Intelligence is not available."));
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot search pagerank: classNames is empty");
        }

        // Create map of seeds from discovered units
        HashMap<ProjectFile, Double> weightedSeeds = new HashMap<>();
        for (String fqcn : classNames) {
            getAnalyzer().getFileFor(fqcn).ifPresent(f -> weightedSeeds.put(f, 1.0));
        }

        // roll our own ranking instead of using Context methods b/c we want BOTH the summaries AND an expanded class
        // list
        var pageRankResults = AnalyzerUtil.combinedRankingFor(contextManager.getProject(), weightedSeeds);
        if (pageRankResults.isEmpty()) {
            return "No related code found via PageRank for seeds: " + String.join(", ", classNames);
        }

        // Get skeletons for the top few results -- potentially saves a round trip for a few extra tokens
        var skResult = pageRankResults.stream()
                .distinct()
                .limit(10) // padding in case of not defined
                .flatMap(file -> skp.getSkeletons(file).values().stream())
                .limit(5)
                .collect(Collectors.joining("\n\n"));

        var clsResult = pageRankResults.stream()
                .flatMap(file -> AnalyzerUtil.coalesceInnerClasses(getAnalyzer().getDeclarationsInFile(file)).stream())
                .map(CodeUnit::fqName)
                .toList();

        var formattedSkeletons =
                skResult.isEmpty() ? "" : "# Summaries of the top related classes: \n\n" + skResult + "\n\n";
        var formattedClassList = formatCompressedSymbols("# Full list of related classes, up to 50", clsResult);
        return formattedSkeletons + formattedClassList;
    }

    @Tool(
            """
                            Returns an overview of classes' contents, including fields and method signatures.
                            Use this to understand class structures and APIs much faster than fetching full source code.
                            """)
    public String getClassSkeletons(
            @P("Fully qualified class names to get the skeleton structures for") List<String> classNames) {

        assert getAnalyzer().as(SkeletonProvider.class).isPresent()
                : "Cannot get skeletons: Current Code Intelligence does not have necessary capabilities.";
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get skeletons: class names list is empty");
        }

        var result = classNames.stream()
                .distinct()
                .map(fqcn -> ((SkeletonProvider) getAnalyzer()).getSkeleton(fqcn))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.joining("\n\n"));

        if (result.isEmpty()) {
            return "No classes found in: " + String.join(", ", classNames);
        }

        return result;
    }

    @Tool(
            """
                            Returns the full source code of classes.
                            This is expensive, so prefer requesting skeletons or method sources when possible.
                            Use this when you need the complete implementation details, or if you think multiple methods in the classes may be relevant.
                            """)
    public String getClassSources(
            @P("Fully qualified class names to retrieve the full source code for") List<String> classNames,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        assert getAnalyzer().as(SourceCodeProvider.class).isPresent()
                : "Cannot get class sources: Current Code Intelligence does not have necessary capabilities.";
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get class sources: class names list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for getClassSources call");
        }

        StringBuilder result = new StringBuilder();
        Set<String> added = new HashSet<>();

        var analyzer = getAnalyzer();
        for (String className : classNames.stream().distinct().toList()) {
            if (className.isBlank()) {
                continue;
            }
            var cuOpt = analyzer.getDefinition(className);
            if (cuOpt.isPresent() && cuOpt.get().isClass()) {
                var cu = cuOpt.get();
                if (added.add(cu.fqName())) {
                    var fragment = new ContextFragment.CodeFragment(contextManager, cu);
                    var text = fragment.text();
                    if (!text.isEmpty()) {
                        if (!result.isEmpty()) {
                            result.append("\n\n");
                        }
                        result.append(text);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            return "No sources found for classes: " + String.join(", ", classNames);
        }

        return result.toString();
    }

    @Tool(
            """
                            Returns the full source code of specific methods. Use this to examine the implementation of particular methods without retrieving the entire classes.
                            """)
    public String getMethodSources(
            @P("Fully qualified method names (package name, class name, method name) to retrieve sources for")
                    List<String> methodNames) {
        assert getAnalyzer().as(SourceCodeProvider.class).isPresent()
                : "Cannot get method sources: Current Code Intelligence does not have necessary capabilities.";
        // Sanitize methodNames: remove potential `(params)` suffix from LLM.
        methodNames = stripParams(methodNames);
        if (methodNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get method sources: method names list is empty");
        }

        StringBuilder result = new StringBuilder();
        Set<String> added = new HashSet<>();

        var analyzer = getAnalyzer();
        for (String methodName : methodNames.stream().distinct().toList()) {
            if (methodName.isBlank()) {
                continue;
            }
            var cuOpt = analyzer.getDefinition(methodName);
            if (cuOpt.isPresent() && cuOpt.get().isFunction()) {
                var cu = cuOpt.get();
                if (added.add(cu.fqName())) {
                    var fragment = new ContextFragment.CodeFragment(contextManager, cu);
                    var text = fragment.text();
                    if (!text.isEmpty()) {
                        if (!result.isEmpty()) {
                            result.append("\n\n");
                        }
                        result.append(text);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        return result.toString();
    }

    @Tool(
            """
                            Returns the call graph to a depth of 3 showing which methods call the given method and one line of source code for each invocation.
                            Use this to understand method dependencies and how code flows into a method.
                            """)
    public String getCallGraphTo(
            @P("Fully qualified method name (package name, class name, method name) to find callers for")
                    String methodName) {
        final var analyzer = getAnalyzer();
        assert analyzer.as(CallGraphProvider.class).isPresent()
                : "Cannot get call graph: Current Code Intelligence does not have necessary capabilities.";
        // Sanitize methodName: remove potential `(params)` suffix from LLM.
        final var cleanMethodName = stripParams(methodName);
        if (cleanMethodName.isBlank()) {
            throw new IllegalArgumentException("Cannot get call graph: method name is empty");
        }

        var graph = analyzer.as(CallGraphProvider.class)
                .map(cgp -> cgp.getCallgraphTo(cleanMethodName, 3))
                .orElse(Collections.emptyMap());
        String result = AnalyzerUtil.formatCallGraph(graph, cleanMethodName, true);
        if (result.isEmpty()) {
            return "No callers found of method: " + cleanMethodName;
        }
        return result;
    }

    @Tool(
            """
                            Returns the call graph to a depth of 3 showing which methods are called by the given method and one line of source code for each invocation.
                            Use this to understand how a method's logic flows to other parts of the codebase.
                            """)
    public String getCallGraphFrom(
            @P("Fully qualified method name (package name, class name, method name) to find callees for")
                    String methodName) {
        final var analyzer = getAnalyzer();
        assert analyzer.as(CallGraphProvider.class).isPresent()
                : "Cannot get call graph: Current Code Intelligence does not have necessary capabilities.";
        assert (analyzer instanceof CallGraphProvider)
                : "Cannot get call graph: Current Code Intelligence does not have necessary capabilities.";
        // Sanitize methodName: remove potential `(params)` suffix from LLM.
        final var cleanMethodName = stripParams(methodName);
        if (cleanMethodName.isBlank()) {
            throw new IllegalArgumentException("Cannot get call graph: method name is empty");
        }

        var graph = analyzer.as(CallGraphProvider.class)
                .map(cgp -> cgp.getCallgraphFrom(cleanMethodName, 3))
                .orElse(Collections.emptyMap());
        String result = AnalyzerUtil.formatCallGraph(graph, cleanMethodName, false);
        if (result.isEmpty()) {
            return "No calls out made by method: " + cleanMethodName;
        }
        return result;
    }

    @Tool(
            """
                            Search git commit messages using a Java regular expression.
                            Returns matching commits with their message and list of changed files.
                            """)
    public String searchGitCommitMessages(
            @P("Java-style regex pattern to search for within commit messages.") String pattern,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("Cannot search commit messages: pattern is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchGitCommitMessages call");
        }

        var projectRoot = contextManager.getProject().getRoot();
        if (!GitRepo.hasGitRepo(projectRoot)) {
            return "Cannot search commit messages: Git repository not found for this project.";
        }

        List<CommitInfo> matchingCommits;
        try (var gitRepo = new GitRepo(projectRoot)) {
            try {
                matchingCommits = gitRepo.searchCommits(pattern);
            } catch (GitAPIException e) {
                logger.error("Error searching commit messages", e);
                return "Error searching commit messages: " + e.getMessage();
            }
        }

        if (matchingCommits.isEmpty()) {
            return "No commit messages found matching pattern: " + pattern;
        }

        StringBuilder resultBuilder = new StringBuilder();
        for (var commit : matchingCommits) {
            resultBuilder.append("<commit id=\"").append(commit.id()).append("\">\n");
            try {
                // Ensure we always close <message>
                resultBuilder.append("<message>\n");
                try {
                    resultBuilder.append(commit.message().stripIndent()).append("\n");
                } finally {
                    resultBuilder.append("</message>\n");
                }

                // Ensure we always close <edited_files>
                resultBuilder.append("<edited_files>\n");
                try {
                    List<ProjectFile> changedFilesList;
                    try {
                        changedFilesList = commit.changedFiles();
                    } catch (GitAPIException e) {
                        logger.error("Error retrieving changed files for commit {}", commit.id(), e);
                        changedFilesList = List.of();
                    }
                    var changedFiles =
                            changedFilesList.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"));
                    resultBuilder.append(changedFiles).append("\n");
                } finally {
                    resultBuilder.append("</edited_files>\n");
                }
            } finally {
                resultBuilder.append("</commit>\n");
            }
        }

        return resultBuilder.toString();
    }

    // --- Text search tools

    @Tool(
            """
                            Returns file names whose text contents match Java regular expression patterns.
                            This is slower than searchSymbols but can find references to external dependencies and comment strings.
                            """)
    public String searchSubstrings(
            @P(
                            "Java-style regex patterns to search for within file contents. Unlike searchSymbols this does not automatically include any implicit anchors or case insensitivity.")
                    List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search substrings: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchSubstrings call");
        }

        logger.debug("Searching file contents for patterns: {}", patterns);

        List<Predicate<String>> predicates = compilePatternsWithFallback(patterns);
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var matchingFilenames = contextManager.getProject().getAllFiles().parallelStream()
                .map(file -> {
                    if (!file.isText()) {
                        return null;
                    }
                    var fileContentsOpt = file.read(); // Optional<String> from ProjectFile.read()
                    if (fileContentsOpt.isEmpty()) {
                        return null;
                    }
                    String fileContents = fileContentsOpt.get();

                    for (Predicate<String> predicate : predicates) {
                        if (predicate.test(fileContents)) {
                            return file;
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .map(ProjectFile::toString)
                .collect(Collectors.toSet());

        if (matchingFilenames.isEmpty()) {
            return "No files found with content matching patterns: " + String.join(", ", patterns);
        }

        var msg = "Files with content matching patterns: " + String.join(", ", matchingFilenames);
        logger.debug(msg);
        return msg;
    }

    @Tool(
            """
                            Returns filenames (relative to the project root) that match the given Java regular expression patterns.
                            Use this to find configuration files, test data, or source files when you know part of their name.
                            """)
    public String searchFilenames(
            @P("Java-style regex patterns to match against filenames.") List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search filenames: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchFilenames call");
        }

        logger.debug("Searching filenames for patterns: {}", patterns);

        List<Predicate<String>> predicates = compilePatternsWithFallback(patterns);
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var matchingFiles = contextManager.getProject().getAllFiles().stream()
                .map(ProjectFile::toString) // Use relative path from ProjectFile
                .filter(filePath -> {
                    // Normalise to forward slashes so regex like "frontend-mop/.*\\.svelte"
                    // work on Windows paths containing back-slashes.
                    String unixPath = filePath.replace('\\', '/');
                    for (Predicate<String> predicate : predicates) {
                        if (predicate.test(unixPath)) {
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
    }

    @Tool(
            """
                            Returns the full contents of the specified files. Use this after searchFilenames or searchSubstrings, or when you need the content of a non-code file.
                            This can be expensive for large files.
                            """)
    public String getFileContents(
            @P("List of filenames (relative to project root) to retrieve contents for.") List<String> filenames) {
        if (filenames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get file contents: filenames list is empty");
        }

        logger.debug("Getting contents for files: {}", filenames);

        StringBuilder result = new StringBuilder();
        boolean anySuccess = false;

        for (String filename : filenames.stream().distinct().toList()) {
            try {
                var file = contextManager.toFile(filename); // Use contextManager
                if (!file.exists()) {
                    logger.debug("File not found or not a regular file: {}", file);
                    continue;
                }
                var contentOpt = file.read();
                if (contentOpt.isEmpty()) {
                    logger.debug("Skipping unreadable file: {}", filename);
                    continue;
                }
                var content = contentOpt.get();
                if (result.length() > 0) {
                    result.append("\n\n");
                }
                result.append(
                        """
                    ```%s
                    %s
                    ```
                    """
                                .stripIndent()
                                .formatted(filename, content));
                anySuccess = true;
            } catch (Exception e) {
                logger.error("Unexpected error getting content for {}: {}", filename, e.getMessage());
                // Continue to next file
            }
        }

        if (!anySuccess) {
            return "None of the requested files could be read: " + String.join(", ", filenames);
        }

        return result.toString();
    }

    // Only includes project files. Is this what we want?
    @Tool(
            """
                            Lists files within a specified directory relative to the project root.
                            Use '.' for the root directory.
                            """)
    public String listFiles(
            @P("Directory path relative to the project root (e.g., '.', 'src/main/java')") String directoryPath) {
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be empty");
        }

        // Normalize path for filtering (remove leading/trailing slashes, handle '.')
        var normalizedPath = Path.of(directoryPath).normalize();

        logger.debug("Listing files for directory path: '{}' (normalized to `{}`)", directoryPath, normalizedPath);

        var files = contextManager.getProject().getAllFiles().stream()
                .parallel()
                .filter(file -> file.getParent().equals(normalizedPath))
                .sorted()
                .map(ProjectFile::toString)
                .collect(Collectors.joining(", "));

        if (files.isEmpty()) {
            return "No files found in directory: " + directoryPath;
        }

        return "Files in " + directoryPath + ": " + files;
    }

    @Tool(value = "Produce a numbered, incremental task list for implementing the requested code changes.")
    public String createTaskList(
            @P(
                            """
            Produce an ordered list of coding tasks that are each 'right-sized': small enough to complete in one sitting, yet large enough to be meaningful.

            Requirements (apply to EACH task):
            - Scope: one coherent goal; avoid multi-goal items joined by 'and/then'.
            - Size target: ~2 hours for an experienced contributor across < 10 files.
            - Testability: name the verification (unit test name or manual check) at the end in brackets: [Verify: ...].
            - Independence: runnable/reviewable on its own; at most one explicit dependency on a previous task.
            - Output: starts with a strong verb and names concrete artifact(s) (class/method/file, config, test).

            Rubric for slicing:
            - TOO LARGE if it spans multiple subsystems, sweeping refactors, or ambiguous outcomes—split by subsystem or by 'behavior change' vs 'refactor'.
            - TOO SMALL if there is no distinct verification—merge into its nearest parent goal.
            - JUST RIGHT if the diff + test could be reviewed and landed as a single commit without coordination.

            Aim for 8 tasks or fewer. Do not include "external" tasks like PRDs or manual testing.
            """)
                    List<String> tasks) {
        logger.debug("createTaskList selected with {} tasks", tasks.size());
        if (tasks.isEmpty()) {
            return "No tasks provided.";
        }

        var io = contextManager.getIo();
        // Append tasks to Task List Panel (if running in Chrome UI)
        try {
            ((Chrome) io).appendTasksToTaskList(tasks);
        } catch (ClassCastException ignored) {
            // Not running in Chrome UI; skip appending
        }
        io.systemOutput("Added " + tasks.size() + " task" + (tasks.size() == 1 ? "" : "s") + " to Task List");

        var lines = java.util.stream.IntStream.range(0, tasks.size())
                .mapToObj(i -> (i + 1) + ". " + tasks.get(i))
                .collect(java.util.stream.Collectors.joining("\n"));
        var formattedTaskList = "# Task List\n" + lines + "\n";
        io.llmOutput("I've created the following tasks:\n" + formattedTaskList, ChatMessageType.AI, true, false);
        return formattedTaskList;
    }
}
