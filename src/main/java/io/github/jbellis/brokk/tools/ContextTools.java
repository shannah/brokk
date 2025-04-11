package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.HtmlToMarkdown;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Provides tools for manipulating the context (adding/removing files and fragments)
 * and adding analysis results (usages, skeletons, sources, call graphs) as context fragments.
 */
public class ContextTools {
    private static final Logger logger = LogManager.getLogger(ContextTools.class);

    // Changed field type to concrete ContextManager
    private final ContextManager contextManager;

    // Changed constructor parameter type to concrete ContextManager
    public ContextTools(ContextManager contextManager) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
    }

    @Tool("Add project files to the editable context. Use this when you intend to modify these files.")
    public String addEditableFiles(
            @P("List of file paths relative to the project root (e.g., 'src/main/java/com/example/MyClass.java').")
            List<String> relativePaths
    ) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            throw new IllegalArgumentException("File paths list cannot be empty.");
        }

        List<ProjectFile> projectFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String path : relativePaths) {
            if (path == null || path.isBlank()) {
                errors.add("Null or blank path provided.");
                continue;
            }
            try {
                // ContextManager.toFile handles normalization and root path joining
                ProjectFile pf = contextManager.toFile(path);
                // Basic check if it looks like it's outside the project root still?
                // toFile creates the ProjectFile object regardless of existence check
                if (!pf.absPath().startsWith(contextManager.getRoot())) {
                     errors.add("Path seems outside project root: " + path);
                     continue;
                }
                // Maybe add existence check? contextManager.editFiles handles it later anyway.
                projectFiles.add(pf);
            } catch (InvalidPathException e) {
                errors.add("Invalid path format: " + path + " (" + e.getMessage() + ")");
            } catch (Exception e) {
                errors.add("Error processing path '" + path + "': " + e.getMessage());
                logger.error("Error processing path for edit: {}", path, e);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Errors encountered processing paths: " + String.join("; ", errors));
        }

        if (projectFiles.isEmpty()) {
            throw new IllegalArgumentException("No valid project files provided to add.");
        }

        contextManager.editFiles(projectFiles);
        String fileNames = projectFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", "));
        return "Added %d file(s) to editable context: [%s]".formatted(projectFiles.size(), fileNames);
    }

    // Removed addReadOnlyProjectFiles and addReadOnlyExternalFiles

    @Tool("Add files to the read-only context. Use this for files you need to reference but not modify. Paths starting with '/' or '~/' or drive letters (e.g., 'C:') are treated as absolute external paths; others are treated as project-relative.")
    public String addReadOnlyFiles(
            @P("List of file paths. Examples: 'README.md', '/etc/hosts', '~/config.txt', 'C:\\Users\\Me\\file.txt'.")
            List<String> paths
    ) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("File paths list cannot be empty.");
        }

        List<BrokkFile> filesToAdd = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Path projectRoot = contextManager.getRoot();

        for (String pathStr : paths) {
            if (pathStr == null || pathStr.isBlank()) {
                errors.add("Null or blank path provided.");
                continue;
            }

            try {
                // Check if it looks like an absolute path
                Path path = Paths.get(pathStr); // Try parsing first
                boolean isAbsolute = path.isAbsolute() || pathStr.startsWith("~" + System.getProperty("file.separator")) || (System.getProperty("os.name").toLowerCase().contains("win") && pathStr.matches("^[a-zA-Z]:\\\\.*"));

                if (isAbsolute) {
                    // Handle potential ~ expansion simply
                    if (pathStr.startsWith("~" + System.getProperty("file.separator"))) {
                        path = Paths.get(System.getProperty("user.home"), pathStr.substring(2)).toAbsolutePath();
                    } else {
                         path = path.toAbsolutePath(); // Ensure it's absolute
                    }

                    if (path.startsWith(projectRoot)) {
                        errors.add("Absolute path is inside the project root, provide it as a relative path instead: " + pathStr);
                    } else if (!Files.exists(path)) {
                        errors.add("External file does not exist: " + pathStr);
                    } else if (!Files.isRegularFile(path)) {
                        errors.add("External path is not a regular file: " + pathStr);
                    } else {
                        filesToAdd.add(new ExternalFile(path));
                    }
                } else {
                    // Treat as project-relative
                    ProjectFile pf = contextManager.toFile(pathStr);
                    if (!Files.exists(pf.absPath())) {
                         errors.add("Project file does not exist: " + pathStr);
                    } else if (!Files.isRegularFile(pf.absPath())) {
                        errors.add("Project path is not a regular file: " + pathStr);
                    } else {
                        filesToAdd.add(pf);
                    }
                }
            } catch (InvalidPathException e) {
                errors.add("Invalid path format: " + pathStr + " (" + e.getMessage() + ")");
            } catch (Exception e) {
                errors.add("Error processing path '" + pathStr + "': " + e.getMessage());
                logger.error("Error processing path for read-only: {}", pathStr, e);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Errors encountered processing paths: " + String.join("; ", errors));
        }

        if (filesToAdd.isEmpty()) {
            throw new IllegalArgumentException("No valid files provided to add.");
        }

        contextManager.addReadOnlyFiles(filesToAdd);
        String fileNames = filesToAdd.stream().map(BrokkFile::toString).collect(Collectors.joining(", "));
        return "Added %d file(s) to read-only context: [%s]".formatted(filesToAdd.size(), fileNames);
    }

    @Tool("Fetch content from a URL (e.g., documentation, issue tracker) and add it as a read-only text fragment. HTML content will be converted to Markdown.")
    public String addUrlContents(
            @P("The full URL to fetch content from (e.g., 'https://example.com/docs/page').")
            String urlString
    ) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty.");
        }

        String content;
        URI uri;
        try {
            uri = new URI(urlString);
            logger.debug("Fetching content from URL: {}", urlString);
            content = fetchUrlContent(uri);
            logger.debug("Fetched {} characters from {}", content.length(), urlString);
            // Directly call the utility method for conversion
            content = HtmlToMarkdown.maybeConvertToMarkdown(content);
            logger.debug("Content length after potential markdown conversion: {}", content.length());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL format: " + urlString, e);
        } catch (IOException e) {
            logger.error("Failed to fetch or process URL content: {}", urlString, e);
            throw new RuntimeException("Failed to fetch URL content for " + urlString + ": " + e.getMessage(), e);
        } catch (Exception e) {
             logger.error("Unexpected error processing URL: {}", urlString, e);
             throw new RuntimeException("Unexpected error processing URL " + urlString + ": " + e.getMessage(), e);
        }

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Fetched content from URL is empty: " + urlString);
        }

        // Use the ContextManager's method to add the string fragment
        String description = "Content from " + urlString;
        // ContextManager handles pushing the context update
        contextManager.addStringFragment(content, description);

        return "Added content from URL [%s] as a read-only text fragment.".formatted(urlString);
    }

    @Tool("Add an arbitrary block of text (e.g., user input, notes, configuration snippet) as a read-only virtual fragment.")
    public String addTextFragment(
            @P("The text content to add as a fragment.")
            String content,
            @P("A short, descriptive label for this text fragment (e.g., 'User Requirements', 'API Key Snippet').")
            String description
    ) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Content cannot be empty.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description cannot be empty.");
        }

        // Use the ContextManager's method to add the string fragment
        contextManager.addStringFragment(content, description);

        return "Added text fragment '%s'.".formatted(description);
    }

    @Tool("Remove specified context fragments (files, text snippets, analysis results) from the context using their unique integer IDs.")
    public String dropFragments(
            @P("List of integer IDs corresponding to the fragments visible in the context view that you want to remove.")
            List<Integer> fragmentIds
    ) {
        if (fragmentIds == null || fragmentIds.isEmpty()) {
            throw new IllegalArgumentException("Fragment IDs list cannot be empty.");
        }

        var currentContext = contextManager.topContext();
        var allFragments = currentContext.getAllFragmentsInDisplayOrder();
        var pathFragsToRemove = new ArrayList<ContextFragment.PathFragment>();
        var virtualToRemove = new ArrayList<ContextFragment.VirtualFragment>();
        var idsToDropSet = new HashSet<>(fragmentIds);
        List<Integer> foundIds = new ArrayList<>();
        boolean autoContextDropped = false;

        for (var frag : allFragments) {
            if (idsToDropSet.contains(frag.id())) {
                foundIds.add(frag.id());
                if (frag instanceof ContextFragment.AutoContext) {
                    // Special handling for AutoContext: disable it via ContextManager
                    contextManager.setAutoContextFiles(0);
                    autoContextDropped = true;
                } else if (frag instanceof ContextFragment.PathFragment pf) {
                    pathFragsToRemove.add(pf);
                } else if (frag instanceof ContextFragment.VirtualFragment vf) {
                    virtualToRemove.add(vf);
                } else {
                    logger.warn("Fragment with ID {} has unexpected type {} and cannot be dropped via this tool.", frag.id(), frag.getClass().getName());
                }
            }
        }

        List<Integer> notFoundIds = fragmentIds.stream()
                .filter(id -> !foundIds.contains(id))
                .toList();

        if (!notFoundIds.isEmpty()) {
             // Throw error if *any* requested ID wasn't found? Or just log? Let's throw.
            throw new IllegalArgumentException("Fragment IDs not found in current context: " + notFoundIds);
        }

        // Perform the drop operation if there's anything other than AutoContext to drop
        if (!pathFragsToRemove.isEmpty() || !virtualToRemove.isEmpty()) {
            contextManager.drop(pathFragsToRemove, virtualToRemove);
        }

        int droppedCount = pathFragsToRemove.size() + virtualToRemove.size() + (autoContextDropped ? 1 : 0);
        if (droppedCount == 0) {
            // This can happen if only invalid IDs were provided, or only AutoContext was requested but failed to drop
             throw new IllegalStateException("No valid fragments found to drop for the given IDs: " + fragmentIds);
        }

        return "Dropped %d fragment(s) with IDs: [%s]".formatted(droppedCount, foundIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
    }

    @Tool("Clear the entire conversation history, including intermediate steps and summaries.")
    public String clearHistory() {
        contextManager.clearHistory();
        return "Cleared conversation history.";
    }

    // --- Helper Methods ---

    /**
     * Fetches content from a given URL. Public static for reuse.
     * @param url The URL to fetch from.
     * @return The content as a String.
     * @throws IOException If fetching fails.
     */
    public static String fetchUrlContent(URI url) throws IOException { // Changed to public static
        var connection = url.toURL().openConnection();
        // Set reasonable timeouts
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        // Set a user agent
        connection.setRequestProperty("User-Agent", "Brokk-Agent/1.0 (ContextTools)");

        try (var reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    // Removed redundant maybeConvertToMarkdown helper method.
    // The call site now uses HtmlToMarkdown.maybeConvertToMarkdown directly.

    // --- Analysis Result Tools (Add Fragments) ---

    private IAnalyzer getAnalyzer() {
        // Assuming ContextManager provides access to the analyzer
        return contextManager.getProject().getAnalyzer();
    }

    @Tool(value = """
    Finds usages of a specific symbol (class, method, field) and adds the relevant source code snippets as a read-only context fragment.
    Useful for understanding how a piece of code is utilized across the project.
    """)
    public String addUsagesFragment(
            @P("Fully qualified symbol name (e.g., 'com.example.MyClass', 'com.example.MyClass.myMethod', 'com.example.MyClass.myField') to find usages for.")
            String symbol
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add usages fragment: Code analyzer is not available.";
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Cannot add usages fragment: symbol cannot be empty");
        }
        // Removed reasoning check

        List<CodeUnit> uses = getAnalyzer().getUses(symbol);
        if (uses.isEmpty()) {
            throw new IllegalStateException("No usages found for symbol: " + symbol);
        }

        var result = AnalyzerUtil.processUsages(getAnalyzer(), uses);
        if (result.code().isEmpty()) {
            // This might happen if processUsages filters out everything found by getUsagesData
            throw new IllegalStateException("No relevant usage code found for symbol: " + symbol);
        }

        var fragment = new ContextFragment.UsageFragment("Uses", symbol, result.sources(), result.code());
        contextManager.addVirtualFragment(fragment);

        return "Added usages fragment for symbol '%s'.".formatted(symbol);
    }

    @Tool(value = """
    Retrieves summaries (fields and method signatures) for specified classes and adds them as a single read-only context fragment.
    Provides a quick overview of class APIs without the full source code.
    """)
    public String addClassSkeletonsFragment(
            @P("List of fully qualified class names (e.g., ['com.example.ClassA', 'org.another.ClassB']) to get skeletons for.")
            List<String> classNames
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add skeletons fragment: Code analyzer is not available.";
        if (classNames == null || classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot add skeletons fragment: class names list is empty");
        }

        var skeletonsData = AnalyzerUtil.getClassSkeletonsData(getAnalyzer(), classNames);
        if (skeletonsData.isEmpty()) {
            throw new IllegalStateException("No skeletons found for classes: " + String.join(", ", classNames));
        }

        // We need to filter CodeUnits potentially added by the Util method if they are inner classes whose parent is also present
        // Re-apply the coalescing logic here, or rely on the caller providing coalesced names? Let's apply here for safety.
        // However, getClassSkeletonsData returns Map<CodeUnit, String>, so we need to adapt.
        Set<CodeUnit> unitsWithSkeletons = skeletonsData.keySet();
        Set<CodeUnit> coalescedUnits = unitsWithSkeletons.stream()
                .filter(cu -> {
                    String name = cu.fqName();
                    if (!name.contains("$")) return true; // Keep non-inner classes
                    String parent = name.substring(0, name.indexOf('$'));
                    // Keep if the parent skeleton is *not* also in the map
                    return unitsWithSkeletons.stream().noneMatch(other -> other.fqName().equals(parent));
                })
                .collect(Collectors.toSet());

        // Filter the original map based on coalesced units
        Map<CodeUnit, String> coalescedSkeletons = skeletonsData.entrySet().stream()
                .filter(entry -> coalescedUnits.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (coalescedSkeletons.isEmpty()) {
             // This could happen if only inner classes were requested and their parents were also found
             throw new IllegalStateException("No primary skeletons found after coalescing for classes: " + String.join(", ", classNames));
        }

        var fragment = new ContextFragment.SkeletonFragment(coalescedSkeletons);
        contextManager.addVirtualFragment(fragment);

        String addedClasses = coalescedSkeletons.keySet().stream().map(CodeUnit::fqName).sorted().collect(Collectors.joining(", "));
        return "Added skeleton fragment for %d class(es): [%s]".formatted(coalescedSkeletons.size(), addedClasses);
    }


    @Tool(value = """
    Retrieves the full source code of specific methods and adds each as a separate read-only text fragment.
    Useful for examining the implementation details of individual methods.
    """)
    public String addMethodSourcesFragment(
            @P("List of fully qualified method names (e.g., ['com.example.ClassA.method1', 'org.another.ClassB.processData']) to retrieve sources for.")
            List<String> methodNames
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add method sources: Code analyzer is not available.";
        if (methodNames == null || methodNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot add method sources: method names list is empty");
        }

        var sourcesData = AnalyzerUtil.getMethodSourcesData(getAnalyzer(), methodNames);
        if (sourcesData.isEmpty()) {
            throw new IllegalStateException("No sources found for methods: " + String.join(", ", methodNames));
        }

        // Add each method source as a separate StringFragment
        int count = 0;
        for (var entry : sourcesData.entrySet()) {
            String methodName = entry.getKey();
            String sourceCodeWithHeader = entry.getValue();
            String description = "Source for method " + methodName;
            // Create and add the fragment
            var fragment = new ContextFragment.StringFragment(sourceCodeWithHeader, description);
            contextManager.addVirtualFragment(fragment);
            count++;
        }

        return "Added %d method source fragment(s) for: [%s]".formatted(count, String.join(", ", sourcesData.keySet()));
    }

    @Tool(value = """
    Retrieves the full source code of specified classes and adds each as a separate read-only text fragment.
    Use this when you need the complete implementation details of one or more classes in the context. Prefer `addClassSkeletonsFragment` or `addMethodSourcesFragment` if possible.
    """)
    public String addClassSourcesFragment(
            @P("List of fully qualified class names (e.g., ['com.example.ClassA', 'org.another.ClassB']) to retrieve the full source code for.")
            List<String> classNames
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add class sources: Code analyzer is not available.";
        if (classNames == null || classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot add class sources: class names list is empty");
        }
        // Removed reasoning check

        var sourcesData = AnalyzerUtil.getClassSourcesData(getAnalyzer(), classNames);
        if (sourcesData.isEmpty()) {
            throw new IllegalStateException("No sources found for classes: " + String.join(", ", classNames));
        }

        // Add each class source as a separate StringFragment
        int count = 0;
        for (var entry : sourcesData.entrySet()) {
            String className = entry.getKey();
            String sourceCodeWithHeader = entry.getValue();
            String description = "Source for class " + className;
            // Create and add the fragment
            var fragment = new ContextFragment.StringFragment(sourceCodeWithHeader, description);
            contextManager.addVirtualFragment(fragment);
            count++;
        }

        return "Added %d class source fragment(s) for: [%s]".formatted(count, String.join(", ", sourcesData.keySet()));
    }

    @Tool(value = """
    Generates a call graph showing methods that call the specified target method (callers) up to a certain depth, and adds it as a read-only context fragment.
    Helps understand how a method is invoked.
    """)
    public String addCallGraphToFragment(
            @P("Fully qualified target method name (e.g., 'com.example.MyClass.targetMethod') to find callers for.")
            String methodName,
            @P("Maximum depth of the call graph to retrieve (e.g., 3 or 5). Higher depths can be large.")
            int depth // Added depth parameter
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add call graph: Code analyzer is not available.";
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("Cannot add call graph: method name is empty");
        }
        if (depth <= 0) {
             throw new IllegalArgumentException("Cannot add call graph: depth must be positive");
        }

        var graphData = getAnalyzer().getCallgraphTo(methodName, depth);
        if (graphData.isEmpty()) {
            throw new IllegalStateException("No call graph available (callers) for method: " + methodName);
        }

        String formattedGraph = AnalyzerUtil.formatCallGraph(graphData, methodName, false); // false = callers (arrows point TO method)
        if (formattedGraph.isEmpty()) {
            // Should not happen if graphData is not empty, but check defensively
             throw new IllegalStateException("Failed to format non-empty call graph (callers) for method: " + methodName);
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        var sourceFile = getAnalyzer().getFileFor(className);
        sourceFile.foreach(pf -> sources.add(CodeUnit.cls(pf, className))); // Use foreach for Option

        // Use UsageFragment to represent the call graph
        String type = "Callers (depth " + depth + ")";
        var fragment = new ContextFragment.UsageFragment(type, methodName, sources, formattedGraph);
        contextManager.addVirtualFragment(fragment);

        int totalCallSites = graphData.values().stream().mapToInt(List::size).sum();
        return "Added call graph fragment (%d sites) for callers of '%s' (depth %d).".formatted(totalCallSites, methodName, depth);
    }

     @Tool(value = """
    Generates a call graph showing methods called by the specified source method (callees) up to a certain depth, and adds it as a read-only context fragment.
    Helps understand what other methods a given method depends on.
    """)
    public String addCallGraphFromFragment(
            @P("Fully qualified source method name (e.g., 'com.example.MyClass.sourceMethod') to find callees for.")
            String methodName,
            @P("Maximum depth of the call graph to retrieve (e.g., 3 or 5). Higher depths can be large.")
            int depth // Added depth parameter
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add call graph: Code analyzer is not available.";
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("Cannot add call graph: method name is empty");
        }
         if (depth <= 0) {
             throw new IllegalArgumentException("Cannot add call graph: depth must be positive");
        }

         var graphData = getAnalyzer().getCallgraphFrom(methodName, depth);
        if (graphData.isEmpty()) {
            throw new IllegalStateException("No call graph available (callees) for method: " + methodName);
        }

        String formattedGraph = AnalyzerUtil.formatCallGraph(graphData, methodName, true); // true = callees (arrows point FROM method)
        if (formattedGraph.isEmpty()) {
             // Should not happen if graphData is not empty, but check defensively
             throw new IllegalStateException("Failed to format non-empty call graph (callees) for method: " + methodName);
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        var sourceFile = getAnalyzer().getFileFor(className);
        sourceFile.foreach(pf -> sources.add(CodeUnit.cls(pf, className))); // Use foreach for Option

        // Use UsageFragment to represent the call graph
        String type = "Callees (depth " + depth + ")";
        var fragment = new ContextFragment.UsageFragment(type, methodName, sources, formattedGraph);
        contextManager.addVirtualFragment(fragment);

        int totalCallSites = graphData.values().stream().mapToInt(List::size).sum();
        return "Added call graph fragment (%d sites) for callees of '%s' (depth %d).".formatted(totalCallSites, methodName, depth);
    }
}
