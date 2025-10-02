package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.analyzer.CallSite;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.LintResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.lsp.domain.SymbolContext;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface LspClient extends AutoCloseable {

    Logger logger = LoggerFactory.getLogger(LspClient.class);

    @NotNull
    Path getProjectRoot();

    @NotNull
    String getWorkspace();

    @NotNull
    LspServer getServer();

    @NotNull
    default Optional<CountDownLatch> getWorkspaceReadyLatch(String workspace) {
        return this.getServer().getWorkspaceReadyLatch(workspace);
    }

    /** @return the target programming language as per the LSP's setting specs, e.g., "java". */
    @NotNull
    String getLanguage();

    /** @return the language-specific configuration options for the LSP. */
    default @NotNull Map<String, Object> getInitializationOptions() {
        return Collections.emptyMap();
    }

    /** Transform method node fullName to a stable "resolved" name (e.g. removing lambda suffixes). */
    @NotNull
    String resolveMethodName(@NotNull String methodName);

    /** Possibly remove package names from a type string, or do other language-specific cleanup. */
    @NotNull
    String sanitizeType(@NotNull String typeName);

    default void update(@NotNull Set<ProjectFile> changedFiles) {
        getServer().update(changedFiles);
        logger.debug("Sent didChangeWatchedFiles notification for {} files.", changedFiles.size());
    }

    default void update() {
        getServer().refreshWorkspace(getWorkspace()).join();
    }

    default boolean isClassInProject(String className) {
        return !LspAnalyzerHelper.findTypesInWorkspace(className, getWorkspace(), getServer())
                .join()
                .isEmpty();
    }

    default @NotNull Optional<CodeUnit> getDefinition(@Nullable String fqName) {
        return getDefinitionsInWorkspace(fqName).stream()
                .map(this::codeUnitForWorkspaceSymbol)
                .findFirst();
    }

    private List<? extends WorkspaceSymbol> getDefinitionsInWorkspace(@Nullable String fqName) {
        if (fqName == null) {
            return Collections.emptyList();
        } else {
            final var resolvedFqName = resolveMethodName(fqName); // Works for types too
            final var workspace = getWorkspace();
            final var server = getServer();
            // launch both requests at the same time
            final CompletableFuture<List<? extends WorkspaceSymbol>> exactMatchFuture =
                    LspAnalyzerHelper.findSymbolsInWorkspace(resolvedFqName, workspace, server)
                            .thenApply(workspaceSymbols -> workspaceSymbols.stream()
                                    .filter(symbol -> LspAnalyzerHelper.simpleOrFullMatch(symbol, resolvedFqName))
                                    .toList());
            final Stream<CompletableFuture<List<? extends WorkspaceSymbol>>> fallbackFuture =
                    LspAnalyzerHelper.determineMethodName(resolvedFqName, this::resolveMethodName).stream()
                            .map(qualifiedMethod -> {
                                final var methodName = qualifiedMethod.methodName();
                                final var containerName = qualifiedMethod.containerFullName();
                                return LspAnalyzerHelper.findMethodSymbol(
                                                containerName, methodName, workspace, server, this::resolveMethodName)
                                        .thenApply(workspaceSymbols -> workspaceSymbols.stream()
                                                .filter(symbol -> LspAnalyzerHelper.simpleOrFullMatch(
                                                        symbol, fqName, this::resolveMethodName))
                                                .toList());
                            });

            final var exactMatch = exactMatchFuture.join();
            if (!exactMatch.isEmpty()) {
                fallbackFuture.forEach(x -> x.cancel(true));
                return exactMatch;
            } else {
                return fallbackFuture.flatMap(x -> x.join().stream()).toList();
            }
        }
    }

    default @NotNull List<CodeUnit> getUses(@Nullable String rawFqName) {
        if (rawFqName == null || rawFqName.isEmpty()) {
            final var reason = "Symbol '" + rawFqName + "' not found as a method, field, or class";
            throw new IllegalArgumentException(reason);
        }
        var resolvedFqName = resolveMethodName(rawFqName);
        // If the name ends with the same token twice (e.g., "package.Foo.Foo"),
        // collapse the duplicate to "package.Foo".
        final var parts = resolvedFqName.split("\\.");
        if (parts.length >= 2) {
            final String last = parts[parts.length - 1];
            final String secondLast = parts[parts.length - 2];
            if (last.equals(secondLast)) {
                resolvedFqName = String.join(".", Arrays.copyOf(parts, parts.length - 1));
            }
        }
        final String fqName = resolvedFqName;

        // Start with the normal lookup
        var definitions = getDefinitionsInWorkspace(fqName);

        // Fallback: if nothing found and this looks like a fully-qualified field name,
        // try to resolve the container and find a field child with the given name.
        if (definitions.isEmpty() && fqName.contains(".")) {
            final var containerName = fqName.substring(0, fqName.lastIndexOf('.'));

            List<? extends WorkspaceSymbol> containerSymbols = getDefinitionsInWorkspace(containerName);
            // If the generic workspace-symbol search didn't return anything, explicitly try type-symbol lookup,
            // which is often more reliable for classes (especially short/simple names).
            if (containerSymbols.isEmpty()) {
                containerSymbols = LspAnalyzerHelper.findTypesInWorkspace(containerName, getWorkspace(), getServer())
                        .join();
            }

            if (!containerSymbols.isEmpty()) {
                definitions = containerSymbols.stream()
                        // For each matching container symbol, fetch its direct children from the server
                        .flatMap(containerSymbol -> {
                            // Convert to a CodeUnit if possible; some helper paths expect a CodeUnit.
                            final CodeUnit cu = codeUnitForWorkspaceSymbol(containerSymbol);
                            return LspAnalyzerHelper.getDirectChildren(cu, getServer()).join().stream();
                        })
                        .filter(symbol -> {
                            // Only consider field-like symbols
                            if (!LspAnalyzerHelper.FIELD_KINDS.contains(symbol.getKind())) {
                                return false;
                            }
                            return LspAnalyzerHelper.simpleOrFullMatch(symbol, fqName);
                        })
                        .toList();
            }
        }

        if (definitions.isEmpty()) {
            final var reason = "Symbol '" + fqName + "' (resolved: '" + resolveMethodName(fqName)
                    + "') not found as a method, field, or class";
            throw new IllegalArgumentException(reason);
        }

        final var usagesFutures = definitions.stream()
                .flatMap(symbol -> {
                    if (symbol.getLocation().isLeft())
                        return Optional.of(symbol.getLocation().getLeft()).stream();
                    else return Optional.<Location>empty().stream();
                })
                .map(location -> LspAnalyzerHelper.findUsageSymbols(location, getServer())
                        .thenApply(usages -> usages.stream().map(this::codeUnitForWorkspaceSymbol)))
                .toList();
        return CompletableFuture.allOf(usagesFutures.toArray(new CompletableFuture[0]))
                .thenApply(v ->
                        usagesFutures.stream().flatMap(CompletableFuture::join).toList())
                .join();
    }

    default @NotNull List<CodeUnit> getAllDeclarations() {
        return LspAnalyzerHelper.getAllWorkspaceSymbols(getWorkspace(), getServer())
                .thenApply(symbols -> symbols.stream()
                        .filter(s -> LspAnalyzerHelper.TYPE_KINDS.contains(s.getKind()))
                        .map(this::codeUnitForWorkspaceSymbol))
                .join()
                .toList();
    }

    default @NotNull Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        return LspAnalyzerHelper.getWorkspaceSymbolsInFile(getServer(), file.absPath())
                .thenApply(symbols -> symbols.stream().map(this::codeUnitForWorkspaceSymbol))
                .join()
                .collect(Collectors.toSet());
    }

    default @NotNull Optional<ProjectFile> getFileFor(@NotNull String fqName) {
        return LspAnalyzerHelper.typesByName(fqName, getWorkspace(), getServer())
                .thenApply(symbols -> symbols.map(symbol -> {
                    final var symbolUri = URI.create(LspAnalyzerHelper.getUriStringFromLocation(symbol.getLocation()));
                    return new ProjectFile(
                            getProjectRoot(),
                            getProjectRoot().relativize(Path.of(symbolUri).toAbsolutePath()));
                }))
                .join()
                .findFirst();
    }

    default @NotNull Map<String, List<CallSite>> getCallgraphTo(@NotNull String methodName, int depth) {
        final Optional<SymbolContext> methodNameInfo =
                LspAnalyzerHelper.determineMethodName(methodName, this::resolveMethodName);
        if (methodNameInfo.isPresent()) {
            final String className = methodNameInfo.get().containerFullName();
            final String name = methodNameInfo.get().methodName();
            final Map<String, List<CallSite>> callGraph = new HashMap<>();

            final String key = className + "." + name;
            final var functionSymbols = LspAnalyzerHelper.findMethodSymbol(
                            className, name, getWorkspace(), getServer(), this::resolveMethodName)
                    .join();
            functionSymbols.stream()
                    .flatMap(x -> Optional.ofNullable(x.getLocation().getLeft()).stream())
                    .forEach(originMethod -> LspCallGraphHelper.getCallers(getServer(), originMethod)
                            .join()
                            .forEach(
                                    incomingCall -> callGraphEntry(originMethod, callGraph, key, incomingCall, depth)));
            return callGraph;
        } else {
            logger.warn("Method name not found: {}", methodName);
            return new HashMap<>();
        }
    }

    private Map<String, List<CallSite>> getCallgraphTo(CallSite callSite, int depth) {
        if (depth > 0) {
            return getCallgraphTo(callSite.target().fqName(), depth - 1);
        } else {
            return new HashMap<>();
        }
    }

    default @NotNull Map<String, List<CallSite>> getCallgraphFrom(@NotNull String methodName, int depth) {
        final Optional<SymbolContext> methodNameInfo =
                LspAnalyzerHelper.determineMethodName(methodName, this::resolveMethodName);
        if (methodNameInfo.isPresent()) {
            final String className = methodNameInfo.get().containerFullName();
            final String name = methodNameInfo.get().methodName();
            final Map<String, List<CallSite>> callGraph = new HashMap<>();

            final String key = className + "." + name;
            final var functionSymbols = LspAnalyzerHelper.findMethodSymbol(
                            className, name, getWorkspace(), getServer(), this::resolveMethodName)
                    .join();
            functionSymbols.stream()
                    .flatMap(x -> Optional.ofNullable(x.getLocation().getLeft()).stream())
                    .forEach(originMethod -> LspCallGraphHelper.getCallees(getServer(), originMethod)
                            .join()
                            .forEach(
                                    outgoingCall -> callGraphEntry(originMethod, callGraph, key, outgoingCall, depth)));
            return callGraph;
        } else {
            logger.warn("Method name not found: {}", methodName);
            return new HashMap<>();
        }
    }

    private Map<String, List<CallSite>> getCallgraphFrom(CallSite callSite, int depth) {
        if (depth > 0) {
            return getCallgraphFrom(callSite.target().fqName(), depth - 1);
        } else {
            return new HashMap<>();
        }
    }

    private void callGraphEntry(
            Location originMethod, Map<String, List<CallSite>> callGraph, String key, Object someCall, int depth) {
        if (someCall instanceof CallHierarchyIncomingCall incomingCall) {
            final CallSite newCallSite = registerCallItem(
                    key, true, originMethod, incomingCall.getFrom(), incomingCall.getFromRanges(), callGraph);
            // Continue search, and add any new entries
            getCallgraphTo(newCallSite, depth - 1).forEach((k, v) -> {
                final var nestedCallSites = callGraph.getOrDefault(k, new ArrayList<>());
                nestedCallSites.addAll(v);
                callGraph.put(k, nestedCallSites);
            });
        } else if (someCall instanceof CallHierarchyOutgoingCall outgoingCall) {
            final CallSite newCallSite = registerCallItem(
                    key, false, originMethod, outgoingCall.getTo(), outgoingCall.getFromRanges(), callGraph);
            // Continue search, and add any new entries
            getCallgraphFrom(newCallSite, depth - 1).forEach((k, v) -> {
                final var nestedCallSites = callGraph.getOrDefault(k, new ArrayList<>());
                nestedCallSites.addAll(v);
                callGraph.put(k, nestedCallSites);
            });
        }
    }

    private CallSite registerCallItem(
            String key,
            boolean isIncoming,
            Location originMethod,
            CallHierarchyItem callItem,
            List<Range> ranges,
            Map<String, List<CallSite>> callGraph) {
        final var uri = Path.of(URI.create(callItem.getUri()));
        final var projectFile =
                new ProjectFile(this.getProjectRoot(), this.getProjectRoot().relativize(uri));
        final var containerInfo = callItem.getDetail() == null
                ? ""
                : callItem.getDetail(); // TODO: Not sure if null means empty or external
        final var cu = new CodeUnit(
                projectFile,
                LspAnalyzerHelper.codeUnitForSymbolKind(callItem.getKind()),
                containerInfo,
                resolveMethodName(callItem.getName()));
        final var sourceLine = LspAnalyzerHelper.getCodeForCallSite(isIncoming, originMethod, callItem, ranges)
                .orElse(callItem.getName() + "(...)");
        final var callSites = callGraph.getOrDefault(key, new ArrayList<>());
        final var newCallSite = new CallSite(cu, sourceLine);
        callSites.add(newCallSite);
        callGraph.put(key, callSites);
        return newCallSite;
    }

    default CodeUnit codeUnitForWorkspaceSymbol(WorkspaceSymbol symbol) {
        final var uriString = LspAnalyzerHelper.getUriStringFromLocation(symbol.getLocation());
        final var uri = Path.of(URI.create(uriString));
        final var projectFile =
                new ProjectFile(this.getProjectRoot(), this.getProjectRoot().relativize(uri));
        final var codeUnitKind = LspAnalyzerHelper.codeUnitForSymbolKind(symbol.getKind());
        final var containerName = Optional.ofNullable(symbol.getContainerName()).orElse("");
        final String name;
        if (LspAnalyzerHelper.METHOD_KINDS.contains(symbol.getKind())) {
            final var tmpName = resolveMethodName(symbol.getName());
            if (isAnonymousClass(symbol.getKind(), symbol.getName())
                    && symbol.getLocation().isLeft()) {
                name = getAnonymousName(symbol.getLocation().getLeft()).orElse(tmpName);
            } else {
                name = tmpName;
            }
        } else {
            name = symbol.getName();
        }

        return new CodeUnit(projectFile, codeUnitKind, containerName, name);
    }

    /**
     * Heuristically checks if the symbol refers to an anonymous class.
     *
     * @param kind the symbol kind.
     * @param name the given symbol's name.
     * @return true if this is likely an anonymous class, false if otherwise.
     */
    boolean isAnonymousClass(@NotNull SymbolKind kind, @NotNull String name);

    /**
     * Generates a more informative, bytecode-style name for an anonymous class or lambda symbol.
     *
     * @param lambdaLocation The location of the anonymous class or lambda symbol.
     * @return The generated name (e.g., "MyClass.myMethod$anon$42:20") if the location is valid.
     */
    default Optional<String> getAnonymousName(Location lambdaLocation) {
        final Path filePath = Paths.get(URI.create(lambdaLocation.getUri()));
        logger.trace("Determining name for anonymous structure at {}", lambdaLocation);
        return LspAnalyzerHelper.getSymbolsInFile(getServer(), filePath)
                .thenApply(eithers -> eithers.stream()
                        .flatMap(either -> {
                            if (either.isLeft()) {
                                return Optional.<String>empty().stream();
                            }
                            // Find the parent class and method for the anonymous symbol's location
                            return findParentContext(
                                    Collections.singletonList(either.getRight()), lambdaLocation.getRange())
                                    .map(context -> String.format(
                                            // Construct the name using Class.Method$anon$LineNumber:ColumnNumber
                                            "%s.%s$anon$%d:%d",
                                            context.containerFullName(),
                                            context.methodName(),
                                            lambdaLocation.getRange().getStart().getLine(),
                                            lambdaLocation.getRange().getStart().getCharacter()))
                                    .stream();
                        })
                        .findFirst())
                .join();
    }

    /** Recursively searches a symbol tree to find the containing class and method for a given range. */
    private Optional<SymbolContext> findParentContext(List<DocumentSymbol> symbols, Range targetRange) {
        final Deque<DocumentSymbol> contextStack = new ArrayDeque<>();
        return findParentContextRecursive(symbols, targetRange, contextStack);
    }

    private Optional<SymbolContext> findParentContextRecursive(
            List<DocumentSymbol> symbols, Range targetRange, Deque<DocumentSymbol> contextStack) {
        for (final DocumentSymbol symbol : symbols) {
            if (LspAnalyzerHelper.isRangeContained(symbol.getRange(), targetRange)) {
                contextStack.push(symbol);
                if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                    final Optional<SymbolContext> found =
                            findParentContextRecursive(symbol.getChildren(), targetRange, contextStack);
                    if (found.isPresent()) {
                        return found;
                    }
                }
                // We've reached the deepest containing symbol. Now build the context.
                String className = null;
                String methodName = null;
                for (final DocumentSymbol s : contextStack) {
                    if (className == null && LspAnalyzerHelper.TYPE_KINDS.contains(s.getKind())) {
                        if (isAnonymousClass(s.getKind(), s.getName())) {
                            methodName = null; // invalidate method name, keep traversing up
                        } else {
                            className = resolveMethodName(s.getName());
                        }
                    }
                    if (methodName == null && LspAnalyzerHelper.METHOD_KINDS.contains(s.getKind())) {
                        methodName = resolveMethodName(s.getName());
                    }
                }
                contextStack.pop();
                return (className != null && methodName != null)
                        ? Optional.of(new SymbolContext(className, methodName))
                        : Optional.empty();
            }
        }
        contextStack.poll(); // Backtrack
        return Optional.empty();
    }

    default LintResult lintFiles(List<ProjectFile> files) {
        if (files.isEmpty()) {
            return new LintResult(List.of());
        }

        // Get the language client to access diagnostics
        var languageClient = getServer().getLanguageClient();
        if (languageClient == null) {
            logger.warn("JDT language client not available for linting");
            return new LintResult(List.of());
        }

        // Clear existing diagnostics for these files
        languageClient.clearDiagnosticsForFiles(files);
        // Update files
        this.update(new HashSet<>(files));

        // Trigger analysis by refreshing the workspace
        // This will cause the LSP server to re-analyze the files and generate diagnostics
        try {
            getServer().refreshWorkspace(getWorkspace()).join();
        } catch (Exception e) {
            logger.warn("Error refreshing workspace for linting", e);
            return new LintResult(List.of());
        }

        try {
            languageClient.waitForDiagnosticsToSettle().join();
        } catch (Exception e) {
            logger.warn("Error waiting for diagnostics to settle, continuing", e);
        }

        // Collect diagnostics for the specified files
        var diagnostics = languageClient.getDiagnosticsForFiles(files);
        return new LintResult(diagnostics);
    }
}
