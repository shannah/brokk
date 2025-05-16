package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic, language-agnostic skeleton extractor backed by Tree-sitter.
 * Stores summarized skeletons for top-level definitions only.
 * <p>
 * Subclasses provide the language–specific bits: which Tree-sitter grammar,
 * which file extensions, which query, and how to map a capture to a {@link CodeUnit}.
 */
public abstract class TreeSitterAnalyzer implements IAnalyzer {
    protected static final Logger log = LoggerFactory.getLogger(TreeSitterAnalyzer.class);
    // Native library loading is assumed automatic by the io.github.bonede.tree_sitter library.

    /* ---------- instance state ---------- */
    protected final TSLanguage tsLanguage;
    private final TSQuery query;
    final Map<ProjectFile, List<CodeUnit>> topLevelDeclarations = new ConcurrentHashMap<>(); // package-private for testing
    final Map<CodeUnit, List<CodeUnit>> childrenByParent = new ConcurrentHashMap<>(); // package-private for testing
    final Map<CodeUnit, List<String>> signatures = new ConcurrentHashMap<>(); // package-private for testing
    private final Map<CodeUnit, List<Range>> sourceRanges = new ConcurrentHashMap<>();
    private final IProject project;
    protected final Set<String> normalizedExcludedFiles;

    protected record LanguageSyntaxProfile(
        Set<String> classLikeNodeTypes,
        Set<String> functionLikeNodeTypes,
        Set<String> fieldLikeNodeTypes,
        Set<String> decoratorNodeTypes,
        String identifierFieldName,
        String bodyFieldName,
        String parametersFieldName,
        String returnTypeFieldName,
        Map<String, SkeletonType> captureConfiguration,
        String asyncKeywordNodeType,
        Set<String> modifierNodeTypes
    ) {
        public LanguageSyntaxProfile {
            Objects.requireNonNull(classLikeNodeTypes);
            Objects.requireNonNull(functionLikeNodeTypes);
            Objects.requireNonNull(fieldLikeNodeTypes);
            Objects.requireNonNull(decoratorNodeTypes);
            Objects.requireNonNull(identifierFieldName);
            Objects.requireNonNull(bodyFieldName);
            Objects.requireNonNull(parametersFieldName);
            Objects.requireNonNull(returnTypeFieldName);    // Can be empty string if not applicable
            Objects.requireNonNull(captureConfiguration);
            Objects.requireNonNull(asyncKeywordNodeType); // Can be empty string if not applicable
            Objects.requireNonNull(modifierNodeTypes);
        }
    }

    private static record Range(int startByte, int endByte, int startLine, int endLine) {}

    private record FileAnalysisResult(List<CodeUnit> topLevelCUs,
                                      Map<CodeUnit, List<CodeUnit>> children,
                                      Map<CodeUnit, List<String>> signatures,
                                      Map<CodeUnit, List<Range>> sourceRanges) {}

    /* ---------- constructor ---------- */
    protected TreeSitterAnalyzer(IProject project, Set<String> excludedFiles) {
        this.project = project;
        this.tsLanguage = getTSLanguage(); // Provided by subclass
        Objects.requireNonNull(tsLanguage, "Tree-sitter TSLanguage must not be null");

        this.normalizedExcludedFiles = (excludedFiles != null ? excludedFiles : Collections.<String>emptySet())
                .stream()
                .map(p -> {
                    Path path = Path.of(p);
                    if (path.isAbsolute()) {
                        return path.normalize().toString();
                    } else {
                        return project.getRoot().resolve(p).toAbsolutePath().normalize().toString();
                    }
                })
                .collect(Collectors.toSet());
        if (!this.normalizedExcludedFiles.isEmpty()) {
            log.debug("Normalized excluded files: {}", this.normalizedExcludedFiles);
        }

        String rawQueryString = loadResource(getQueryResource());
        this.query = new TSQuery(tsLanguage, rawQueryString);

        // Debug log using SLF4J
        log.debug("Initializing TreeSitterAnalyzer for language: {}, query: {}",
                 project.getAnalyzerLanguage(), getQueryResource());


        var validExtensions = project.getAnalyzerLanguage().getExtensions();
        log.trace("Filtering project files for extensions: {}", validExtensions);

        project.getAllFiles().stream()
                .filter(pf -> {
                    var pathStr = pf.absPath().toString();
                    if (this.normalizedExcludedFiles.contains(pathStr)) {
                        log.debug("Skipping excluded file: {}", pf);
                        return false;
                    }
                    return validExtensions.stream().anyMatch(pathStr::endsWith);
                })
                .parallel()
                .forEach(pf -> {
                    log.trace("Processing file: {}", pf);
                    // TSParser is not threadsafe, so we create a parser per thread
                    var localParser = new TSParser();
                    try {
                        if (!localParser.setLanguage(tsLanguage)) {
                            log.error("Failed to set language on thread-local TSParser for language {} in file {}", tsLanguage, pf);
                            return; // Skip this file if parser setup fails
                        }
                        var analysisResult = analyzeFileDeclarations(pf, localParser);
                        if (!analysisResult.topLevelCUs().isEmpty() || !analysisResult.signatures().isEmpty() || !analysisResult.sourceRanges().isEmpty()) {
                            topLevelDeclarations.put(pf, analysisResult.topLevelCUs()); // Already unmodifiable from result

                            analysisResult.children().forEach((parentCU, newChildCUs) -> childrenByParent.compute(parentCU, (p, existingChildCUs) -> {
                                if (existingChildCUs == null) {
                                    return newChildCUs; // Already unmodifiable
                                }
                                List<CodeUnit> combined = new ArrayList<>(existingChildCUs);
                                for (CodeUnit newKid : newChildCUs) {
                                    if (!combined.contains(newKid)) {
                                        combined.add(newKid);
                                    }
                                }
                                if (combined.size() == existingChildCUs.size()) {
                                    boolean changed = false;
                                    for (int i = 0; i < combined.size(); ++i) {
                                        if (!combined.get(i).equals(existingChildCUs.get(i))) {
                                            changed = true;
                                            break;
                                        }
                                    }
                                    if (!changed) return existingChildCUs;
                                }
                                return Collections.unmodifiableList(combined);
                            }));

                            analysisResult.signatures().forEach((cu, newSignaturesList) -> signatures.compute(cu, (key, existingSignaturesList) -> {
                                if (existingSignaturesList == null) {
                                    return newSignaturesList; // Already unmodifiable from result
                                }
                                List<String> combined = new ArrayList<>(existingSignaturesList);
                                combined.addAll(newSignaturesList);
                                // Assuming order from newSignaturesList is appropriate to append.
                                // If global ordering or deduplication of signatures for a CU is needed, add here.
                                return Collections.unmodifiableList(combined);
                            }));

                            analysisResult.sourceRanges().forEach((cu, newRangesList) -> sourceRanges.compute(cu, (key, existingRangesList) -> {
                                if (existingRangesList == null) {
                                    return newRangesList; // Already unmodifiable
                                }
                                List<Range> combined = new ArrayList<>(existingRangesList);
                                combined.addAll(newRangesList);
                                return Collections.unmodifiableList(combined);
                            }));

                            log.trace("Processed file {}: {} top-level CUs, {} signatures, {} parent-child relationships, {} source range entries.",
                                      pf, analysisResult.topLevelCUs().size(), analysisResult.signatures().size(), analysisResult.children().size(), analysisResult.sourceRanges().size());
                        } else {
                            log.trace("analyzeFileDeclarations returned empty result for file: {}", pf);
                        }
                    } catch (Exception e) {
                        log.warn("Error analyzing {}: {}", pf, e, e);
                    }
                });
    }

    protected TreeSitterAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    /* ---------- Helper methods for accessing CodeUnits ---------- */
    /**  All CodeUnits we know about (top-level + children). */
    private Stream<CodeUnit> allCodeUnits() {
        // Stream top-level declarations
        Stream<CodeUnit> topLevelStream = topLevelDeclarations.values().stream().flatMap(Collection::stream);

        // Stream parents from childrenByParent (they might not be in topLevelDeclarations if they are nested)
        Stream<CodeUnit> parentStream = childrenByParent.keySet().stream();

        // Stream children from childrenByParent
        Stream<CodeUnit> childrenStream = childrenByParent.values().stream().flatMap(Collection::stream);

        return Stream.of(topLevelStream, parentStream, childrenStream).flatMap(s -> s);
    }

    /**  De-duplicate and materialise into a List once. */
    private List<CodeUnit> uniqueCodeUnitList() {
        return allCodeUnits().distinct().toList();
    }

    /* ---------- IAnalyzer ---------- */
    @Override public boolean isEmpty() { return topLevelDeclarations.isEmpty() && signatures.isEmpty() && childrenByParent.isEmpty() && sourceRanges.isEmpty(); }

    @Override public boolean isCpg() { return false; }

    @Override
    public Optional<String> getSkeletonHeader(String fqName) {
        return getSkeleton(fqName).map(s ->
            s.lines().findFirst().orElse("").stripTrailing()
        );
    }

    @Override
    public List<CodeUnit> getMembersInClass(String fqClass) {
        Optional<CodeUnit> parent = uniqueCodeUnitList().stream()
                                                        .filter(cu -> cu.fqName().equals(fqClass) && cu.isClass())
                                                        .findFirst();
        return parent.map(p -> List.copyOf(childrenByParent.getOrDefault(p, List.of())))
                     .orElse(List.of());
    }

    @Override
    public Optional<ProjectFile> getFileFor(String fqName) {
        return uniqueCodeUnitList().stream()
                                   .filter(cu -> cu.fqName().equals(fqName))
                                   .map(CodeUnit::source)
                                   .findFirst();
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        return uniqueCodeUnitList().stream()
                                   .filter(cu -> cu.fqName().equals(fqName))
                                   .findFirst();
    }

    @Override
    public List<CodeUnit> searchDefinitions(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return List.of();
        }
        return uniqueCodeUnitList().stream()
                                   .filter(cu -> cu.fqName().contains(pattern))
                                   .toList();
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        Set<CodeUnit> allClasses = new HashSet<>();
        topLevelDeclarations.values().forEach(allClasses::addAll);
        childrenByParent.values().forEach(allClasses::addAll); // Children lists
        allClasses.addAll(childrenByParent.keySet());          // Parent CUs themselves
        return allClasses.stream().filter(CodeUnit::isClass).distinct().toList();
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        List<CodeUnit> topCUs = topLevelDeclarations.getOrDefault(file, List.of());
        if (topCUs.isEmpty()) return Map.of();

        Map<CodeUnit, String> resultSkeletons = new HashMap<>();
        for (CodeUnit cu : topCUs) {
            resultSkeletons.put(cu, reconstructFullSkeleton(cu));
        }
        log.trace("getSkeletons: file={}, count={}", file, resultSkeletons.size());
        return Collections.unmodifiableMap(resultSkeletons);
    }

    @Override
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        List<CodeUnit> topCUs = topLevelDeclarations.getOrDefault(file, List.of());
        if (topCUs.isEmpty()) return Set.of();

        Set<CodeUnit> classesInFile = new HashSet<>();
        Queue<CodeUnit> toProcess = new LinkedList<>(topCUs);
        Set<CodeUnit> visited = new HashSet<>(topCUs); // Track visited to avoid cycles and redundant processing

        while(!toProcess.isEmpty()) {
            CodeUnit current = toProcess.poll();
            if (current.isClass()) {
                classesInFile.add(current);
            }
            childrenByParent.getOrDefault(current, List.of()).forEach(child -> {
                if (visited.add(child)) { // Add to queue only if not visited
                    toProcess.add(child);
                }
            });
        }
        log.trace("getClassesInFile: file={}, count={}", file, classesInFile.size());
        return Collections.unmodifiableSet(classesInFile);
    }

    private String reconstructFullSkeleton(CodeUnit cu) {
        StringBuilder sb = new StringBuilder();
        reconstructSkeletonRecursive(cu, "", sb);
        return sb.toString().stripTrailing();
    }

    private void reconstructSkeletonRecursive(CodeUnit cu, String indent, StringBuilder sb) {
        List<String> sigList = signatures.get(cu);
        if (sigList == null || sigList.isEmpty()) {
            log.warn("Missing or empty signature list for CU: {}. Skipping in skeleton reconstruction.", cu);
            return;
        }

        for (String individualFullSignature : sigList) {
            if (individualFullSignature == null || individualFullSignature.isBlank()) {
                log.warn("Encountered null or blank signature in list for CU: {}. Skipping this signature.", cu);
                continue;
            }
            // Apply indent to each line of the current signature
            String[] signatureLines = individualFullSignature.split("\n", -1); // Use -1 limit
            for (String line : signatureLines) {
                sb.append(indent).append(line).append('\n');
            }
        }

        List<CodeUnit> kids = childrenByParent.getOrDefault(cu, List.of());
        // Only add children and closer if the CU can have them (e.g. class, or function that can nest)
        // For simplicity now, always check for children. Specific languages might refine this.
        if (!kids.isEmpty() || (cu.isClass() && getLanguageSpecificCloser(cu).length() > 0)) { // also add closer for empty classes
            String childIndent = indent + getLanguageSpecificIndent();
            for (CodeUnit kid : kids) {
                reconstructSkeletonRecursive(kid, childIndent, sb);
            }
            String closer = getLanguageSpecificCloser(cu);
            if (!closer.isEmpty()) {
                sb.append(indent).append(closer).append('\n');
            }
        }
    }


    @Override
    public Optional<String> getSkeleton(String fqName) {
        Optional<CodeUnit> cuOpt = signatures.keySet().stream()
                                          .filter(c -> c.fqName().equals(fqName))
                                          .findFirst();
        if (cuOpt.isPresent()) {
            String skeleton = reconstructFullSkeleton(cuOpt.get());
            log.trace("getSkeleton: fqName='{}', found=true", fqName);
            return Optional.of(skeleton);
        }
        log.trace("getSkeleton: fqName='{}', found=false", fqName);
        return Optional.empty();
    }

    @Override
    public String getClassSource(String fqName) {
        var cu = getDefinition(fqName)
                         .filter(CodeUnit::isClass)
                         .orElseThrow(() -> new SymbolNotFoundException("Class not found: " + fqName));

        var ranges = sourceRanges.get(cu);
        if (ranges == null || ranges.isEmpty()) {
            throw new SymbolNotFoundException("Source range not found for class: " + fqName);
        }

        // For classes, expect one primary definition range.
        var range = ranges.getFirst();
        String src = null;
        try {
            src = cu.source().read();
        } catch (IOException e) {
            return "";
        }
        return src.substring(range.startByte(), range.endByte());
    }

    @Override
    public Optional<String> getMethodSource(String fqName) {
        return getDefinition(fqName) // Finds the single CodeUnit representing this FQN (due to CodeUnit equality for overloads)
            .filter(CodeUnit::isFunction)
            .flatMap(cu -> {
                List<Range> rangesForOverloads = sourceRanges.get(cu);
                if (rangesForOverloads == null || rangesForOverloads.isEmpty()) {
                    log.warn("No source ranges found for CU {} (fqName {}) although definition was found.", cu, fqName);
                    return Optional.empty();
                }

                String fileContent;
                try {
                    fileContent = cu.source().read();
                } catch (IOException e) {
                    log.warn("Could not read source for CU {} (fqName {}): {}", cu, fqName, e.getMessage());
                    return Optional.empty();
                }

                List<String> individualMethodSources = new ArrayList<>();
                for (Range range : rangesForOverloads) {
                    // Ensure range is within fileContent bounds, though it should be by construction.
                    int startByte = Math.max(0, range.startByte());
                    int endByte = Math.min(fileContent.length(), range.endByte());
                    if (startByte < endByte) {
                        individualMethodSources.add(fileContent.substring(startByte, endByte));
                    } else {
                        log.warn("Invalid range [{}, {}] for CU {} (fqName {}) in file of length {}. Skipping this range.",
                                 range.startByte(), range.endByte(), cu, fqName, fileContent.length());
                    }
                }

                if (individualMethodSources.isEmpty()) {
                    log.warn("After processing ranges, no valid method sources found for CU {} (fqName {}).", cu, fqName);
                    return Optional.empty();
                }
                return Optional.of(String.join("\n\n", individualMethodSources));
            });
    }


    /* ---------- abstract hooks ---------- */
    /** Tree-sitter TSLanguage grammar to use (e.g. {@code new TreeSitterPython()}). */
    protected abstract TSLanguage getTSLanguage();

    /** Provides the language-specific syntax profile. */
    protected abstract LanguageSyntaxProfile getLanguageSyntaxProfile();

    /** Class-path resource for the query (e.g. {@code "treesitter/python.scm"}). */
    protected abstract String getQueryResource();

    /**
     * Defines the general type of skeleton that should be built for a given capture.
     */
    public enum SkeletonType {
        CLASS_LIKE,
        FUNCTION_LIKE,
        FIELD_LIKE,
        UNSUPPORTED
    }

    /**
     * Determines the {@link SkeletonType} for a given capture name.
     * This allows subclasses to map their specific query capture names (e.g., "class.definition", "method.declaration")
     * to a general category for skeleton building.
     *
     * @param captureName The name of the capture from the Tree-sitter query.
     * @return The {@link SkeletonType} indicating how to process this capture for skeleton generation.
     */
    protected SkeletonType getSkeletonTypeForCapture(String captureName) {
        var profile = getLanguageSyntaxProfile();
        return profile.captureConfiguration().getOrDefault(captureName, SkeletonType.UNSUPPORTED);
    }

    /**
     * Translate a capture produced by the query into a {@link CodeUnit}.
     * Return {@code null} to ignore this capture.
     */
    protected abstract CodeUnit createCodeUnit(ProjectFile file,
                                               String captureName,
                                               String simpleName,
                                               String packageName,
                                               String classChain);

    /**
     * Determines the package or namespace name for a given definition.
     *
     * @param file The project file being analyzed.
     * @param definitionNode The TSNode representing the definition (e.g., class, function).
     * @param rootNode The root TSNode of the file's syntax tree.
     * @param src The source code of the file.
     * @return The package or namespace name, or an empty string if not applicable.
     */
    protected abstract String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src);

    /**
     * Checks if the given AST node represents a class-like declaration
     * (e.g., class, interface, struct) in the specific language.
     * Subclasses must implement this to guide class chain extraction.
     *
     * @param node The TSNode to check.
     * @return true if the node is a class-like declaration, false otherwise.
     */
    protected boolean isClassLike(TSNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        return getLanguageSyntaxProfile().classLikeNodeTypes().contains(node.getType());
    }

    /** Captures that should be ignored entirely. */
    protected Set<String> getIgnoredCaptures() { return Set.of(); }

    /** Language-specific indentation string, e.g., "  " or "    ". */
    protected String getLanguageSpecificIndent() { return "  "; } // Default

    /** Language-specific closing token for a class or namespace (e.g., "}"). Empty if none. */
    protected abstract String getLanguageSpecificCloser(CodeUnit cu);


    /**
     * Get the project this analyzer is associated with.
     */
    protected IProject getProject() {
        return project;
    }

    /* ---------- core parsing ---------- */
    /** Analyzes a single file and extracts declaration information. */
    private FileAnalysisResult analyzeFileDeclarations(ProjectFile file, TSParser localParser) throws IOException {
        log.trace("analyzeFileDeclarations: Parsing file: {}", file);
        String src = Files.readString(file.absPath(), StandardCharsets.UTF_8);

        List<CodeUnit> localTopLevelCUs = new ArrayList<>();
        Map<CodeUnit, List<CodeUnit>> localChildren = new HashMap<>();
        Map<CodeUnit, List<String>> localSignatures = new HashMap<>();
        Map<CodeUnit, List<Range>> localSourceRanges = new HashMap<>();
        Map<String, CodeUnit> localCuByFqName = new HashMap<>(); // For parent lookup within the file

        TSTree tree = localParser.parseString(null, src);
        TSNode rootNode = tree.getRootNode();
        if (rootNode.isNull()) {
            log.warn("Parsing failed or produced null root node for {}", file);
            return new FileAnalysisResult(List.of(), Map.of(), Map.of(), Map.of());
        }
        // Log root node type
        String rootNodeType = rootNode.getType();
        log.trace("Root node type for {}: {}", file, rootNodeType);


        // Map to store potential top-level declaration nodes found during the query.
        // The value is a Map.Entry: key = primary capture name (e.g., "class.definition"), value = simpleName.
        Map<TSNode, Map.Entry<String, String>> declarationNodes = new HashMap<>();

        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(this.query, rootNode); // Use the query field, execute on root node

        TSQueryMatch match = new TSQueryMatch(); // Reusable match object
        while (cursor.nextMatch(match)) {
            log.trace("Match ID: {}", match.getId());
            // Group nodes by capture name for this specific match
            Map<String, TSNode> capturedNodes = new HashMap<>();
            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = this.query.getCaptureNameForId(capture.getIndex());
                if (getIgnoredCaptures().contains(captureName)) continue;

                TSNode node = capture.getNode();
                if (node != null && !node.isNull()) {
                    log.trace("  Capture: '{}', Node: {} '{}'", captureName, node.getType(), textSlice(node, src).lines().findFirst().orElse("").trim());
                    // Store the first non-null node found for this capture name in this match
                    // Note: Overwrites if multiple nodes have the same capture name in one match.
                    // The old code implicitly took the first from a list; this takes the last encountered.
                    // If specific handling of multiple nodes per capture/match is needed, adjust here.
                    capturedNodes.put(captureName, node);
                }
            }

            // Process each potential definition found in the match
            for (var captureEntry : capturedNodes.entrySet()) {
                String captureName = captureEntry.getKey();
                TSNode definitionNode = captureEntry.getValue();

                if (captureName.endsWith(".definition")) {
                    String simpleName;
                    String expectedNameCapture = captureName.replace(".definition", ".name");
                    TSNode nameNode = capturedNodes.get(expectedNameCapture);

                    if (nameNode != null && !nameNode.isNull()) {
                        simpleName = textSlice(nameNode, src);
                    } else {
                        log.warn("Expected name capture '{}' not found for definition '{}' in match for file {}. Falling back to extractSimpleName on definition node.",
                                 expectedNameCapture, captureName, file);
                        simpleName = extractSimpleName(definitionNode, src).orElse(null); // extractSimpleName is now non-static
                    }

                    if (simpleName != null && !simpleName.isBlank()) {
                        declarationNodes.putIfAbsent(definitionNode, Map.entry(captureName, simpleName));
                        log.trace("MATCH [{}]: Found potential definition: Capture [{}], Node Type [{}], Simple Name [{}] -> Storing with determined name.",
                                  match.getId(), captureName, definitionNode.getType(), simpleName);
                    } else {
                        log.warn("Could not determine simple name for definition capture {} (Node Type [{}], Line {}) in file {} using explicit capture and fallback.",
                                 captureName, definitionNode.getType(), definitionNode.getStartPoint().getRow() + 1, file);
                    }
                }
            }
        } // End main query loop

        // Sort declaration nodes by their start byte to process outer definitions before inner ones.
        // This is crucial for parent lookup.
        if (file.getFileName().equals("vars.py")) {
            log.info("[vars.py DEBUG] declarationNodes for vars.py: {}", declarationNodes.entrySet().stream()
                .map(entry -> String.format("Node: %s (%s), Capture: %s, Name: %s", 
                                        entry.getKey().getType(), 
                                        textSlice(entry.getKey(), src).lines().findFirst().orElse("").trim(), 
                                        entry.getValue().getKey(), 
                                        entry.getValue().getValue()))
                .collect(Collectors.toList()));
            if (declarationNodes.isEmpty()) {
                log.info("[vars.py DEBUG] declarationNodes for vars.py is EMPTY after query execution.");
            }
        }
        List<Map.Entry<TSNode, Map.Entry<String, String>>> sortedDeclarationEntries =
            declarationNodes.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().getStartByte()))
                .collect(Collectors.toList());

        TSNode currentRootNode = tree.getRootNode(); // Used for namespace and class chain extraction

        for (var entry : sortedDeclarationEntries) {
            TSNode node = entry.getKey();
            Map.Entry<String, String> defInfo = entry.getValue();
            String primaryCaptureName = defInfo.getKey();
            String simpleName = defInfo.getValue();

            if (simpleName == null || simpleName.isBlank()) {
                log.warn("Simple name was null/blank for node type {} (capture: {}) in file {}. Skipping.",
                         node.getType(), primaryCaptureName, file);
                continue;
            }

            log.trace("Processing definition: Name='{}', Capture='{}', Node Type='{}'",
                      simpleName, primaryCaptureName, node.getType());

            String packageName = determinePackageName(file, node, currentRootNode, src);
            List<String> enclosingClassNames = new ArrayList<>();
            TSNode tempParent = node.getParent();
            while (tempParent != null && !tempParent.isNull() && !tempParent.equals(currentRootNode)) {
                if (isClassLike(tempParent)) {
                    extractSimpleName(tempParent, src).ifPresent(parentName -> { // extractSimpleName is now non-static
                        if (!parentName.isBlank()) enclosingClassNames.add(0, parentName);
                    });
                }
                tempParent = tempParent.getParent();
            }
            String classChain = String.join("$", enclosingClassNames);
            log.trace("Computed classChain for simpleName='{}': '{}'", simpleName, classChain);

            CodeUnit cu = createCodeUnit(file, primaryCaptureName, simpleName, packageName, classChain);
            log.trace("createCodeUnit returned: {}", cu);

            if (cu == null) {
                log.warn("createCodeUnit returned null for node {} ({})", simpleName, primaryCaptureName);
                continue;
            }

            String signature = buildSignatureString(node, simpleName, src, primaryCaptureName);
            log.trace("Built signature for '{}': [{}]", simpleName, signature == null ? "NULL" : signature.isBlank() ? "BLANK" : signature.lines().findFirst().orElse("EMPTY"));

            if (file.getFileName().equals("vars.py") && primaryCaptureName.equals("field.definition")) {
                log.info("[vars.py DEBUG] Processing entry for vars.py field: Node Type='{}', SimpleName='{}', CaptureName='{}', PackageName='{}', ClassChain='{}'",
                         node.getType(), simpleName, primaryCaptureName, packageName, classChain);
                log.info("[vars.py DEBUG] CU created: {}, Signature: [{}]", cu, signature == null ? "NULL_SIG" : signature.isBlank() ? "BLANK_SIG" : signature.lines().findFirst().orElse("EMPTY_SIG"));
            }

            if (signature == null || signature.isBlank()) {
                // buildSignatureString might legitimately return blank for some nodes that don't form part of a textual skeleton but create a CU.
                // However, if it's blank, it shouldn't be added to signatures map.
                log.debug("buildSignatureString returned empty/null for node {} ({}), simpleName {}. This CU might not have a direct textual signature.", node.getType(), primaryCaptureName, simpleName);
                continue;
            }
            
            // Handle potential duplicates (e.g. JS export and direct lexical declaration)
            // This logic might need adjustment if a single CodeUnit (due to fqName equality)
            // can arise from both an exported and non-exported declaration, and we are now
            // collecting multiple signatures. For now, we assume `computeIfAbsent` for signatures handles accumulation,
            // and this "export" preference applies if different `CodeUnit` instances (which are not `equals()`)
            // somehow map to the same `fqName` in `localCuByFqName` before `cu` itself is unified.
            // If overloads result in CodeUnits that are `equals()`, this block is less relevant for them.
            CodeUnit existingCUforKeyLookup = localCuByFqName.get(cu.fqName());
            if (existingCUforKeyLookup != null && !existingCUforKeyLookup.equals(cu)) {
                // This case implies two distinct CodeUnit objects map to the same FQName.
                // The export preference logic might apply here.
                // However, if `cu` is for an overload of `existingCUforKeyLookup` and they are `equals()`,
                // then this block should ideally not be the primary path for handling overload signatures.
                // The current approach of `localSignatures.computeIfAbsent` will handle accumulation for equal CUs.
                // This existing block seems more for replacing a less specific CU with a more specific one (e.g. exported).
                // For now, let's assume this logic remains for such non-overload "duplicate FQName" cases.
                // If it causes issues with overloads, it needs refinement.
                List<String> existingSignatures = localSignatures.get(existingCUforKeyLookup); // Existing signatures for the *other* CU instance
                boolean newIsExported = signature.trim().startsWith("export");
                boolean oldIsExported = (existingSignatures != null && !existingSignatures.isEmpty()) && existingSignatures.getFirst().trim().startsWith("export"); // Check first existing

                if (newIsExported && !oldIsExported) {
                    log.warn("Replacing non-exported CU/signature list for {} with new EXPORTED signature.", cu.fqName());
                    localSignatures.remove(existingCUforKeyLookup); // Remove old CU's signatures
                    // The new signature for `cu` will be added below.
                } else if (!newIsExported && oldIsExported) {
                    log.trace("Keeping existing EXPORTED CU/signature list for {}. Discarding new non-exported signature for current CU.", cu.fqName());
                    continue; // Skip adding this new signature if an exported one exists for a CU with the same FQName
                } else {
                    log.warn("Duplicate CU FQName {} (distinct instances). New signature will be added. Review if this is expected.", cu.fqName());
                }
            }

            if (signature != null && !signature.isBlank()) { // Only add non-blank signatures
                List<String> sigsForCu = localSignatures.computeIfAbsent(cu, k -> new ArrayList<>());
                if (!sigsForCu.contains(signature)) { // Avoid duplicate signature strings for the same CU
                    sigsForCu.add(signature);
                }
            }
            var currentRange = new Range(node.getStartByte(), node.getEndByte(),
                                         node.getStartPoint().getRow(), node.getEndPoint().getRow());
            localSourceRanges.computeIfAbsent(cu, k -> new ArrayList<>()).add(currentRange);
            localCuByFqName.put(cu.fqName(), cu); // Add/overwrite current CU by its FQ name
            localChildren.putIfAbsent(cu, new ArrayList<>()); // Ensure every CU can be a parent

            if (classChain.isEmpty()) {
                localTopLevelCUs.add(cu);
            } else {
                // Parent's shortName is the classChain string itself.
                String parentFqName = cu.packageName().isEmpty() ? classChain
                                     : cu.packageName() + "." + classChain;
                CodeUnit parentCu = localCuByFqName.get(parentFqName);
                if (parentCu != null) {
                    List<CodeUnit> kids = localChildren.computeIfAbsent(parentCu, k -> new ArrayList<>());
                    if (!kids.contains(cu)) { // Prevent adding duplicate children
                        kids.add(cu);
                    }
                } else {
                    log.warn("Could not resolve parent CU for {} using parent FQ name candidate '{}' (derived from classChain '{}'). Treating as top-level for this file.", cu, parentFqName, classChain);
                    localTopLevelCUs.add(cu); // Fallback
                }
            }
            log.trace("Stored/Updated info for CU: {}", cu);
        }

        log.trace("Finished analyzing {}: found {} top-level CUs, {} total signatures, {} parent entries, {} source range entries.",
                  file, localTopLevelCUs.size(), localSignatures.size(), localChildren.size(), localSourceRanges.size());

        // Make internal lists unmodifiable before returning in FileAnalysisResult
        Map<CodeUnit, List<CodeUnit>> finalLocalChildren = new HashMap<>();
        localChildren.forEach((p, kids) -> finalLocalChildren.put(p, Collections.unmodifiableList(kids)));

        Map<CodeUnit, List<Range>> finalLocalSourceRanges = new HashMap<>();
        localSourceRanges.forEach((c, ranges) -> finalLocalSourceRanges.put(c, Collections.unmodifiableList(ranges)));

        return new FileAnalysisResult(Collections.unmodifiableList(localTopLevelCUs),
                                      finalLocalChildren, // Values (lists) are already unmodifiable
                                      localSignatures,    // Values (strings) are inherently unmodifiable
                                      finalLocalSourceRanges); // Values (lists) are already unmodifiable
    }


    /* ---------- Signature Building Logic ---------- */

    /** Calculates the leading whitespace indentation for the line the node starts on. */
    private String computeIndentation(TSNode node, String src) {
        int startByte = node.getStartByte();
        int lineStartByte = src.lastIndexOf('\n', startByte - 1);
        if (lineStartByte == -1) {
            lineStartByte = 0; // Start of file
        } else {
            lineStartByte++; // Move past the newline character
        }

        // Find the first non-whitespace character on the line
        int firstCharByte = lineStartByte;
        while (firstCharByte < startByte && Character.isWhitespace(src.charAt(firstCharByte))) {
            firstCharByte++;
        }
        // Ensure we don't include indentation from within the node itself if it starts mid-line after whitespace
        int effectiveStart = Math.min(startByte, firstCharByte);

        // The indentation is the substring from the line start up to the first non-whitespace character.
        // Safety check: ensure lineStartByte <= firstCharByte and firstCharByte is within src bounds
        if (lineStartByte > firstCharByte || firstCharByte > src.length()) {
             log.warn("Indentation calculation resulted in invalid range [{}, {}] for node starting at byte {}",
                      lineStartByte, firstCharByte, startByte);
             return ""; // Return empty string on error
        }
        String indentResult = src.substring(lineStartByte, firstCharByte);
        log.trace("computeIndentation: Node={}, Indent='{}'", node.getType(), indentResult.replace("\t", "\\t").replace("\n", "\\n"));
        return indentResult;
    }


    /**
     * Builds a signature string for a given definition node.
     * This includes decorators and the main declaration line (e.g., class header or function signature).
     * @param simpleName The simple name of the definition, pre-determined by query captures.
     */
    private String buildSignatureString(TSNode definitionNode, String simpleName, String src, String primaryCaptureName) {
        List<String> signatureLines = new ArrayList<>();
        var profile = getLanguageSyntaxProfile();

        TSNode nodeForContent = definitionNode; // Node used for extracting the main signature text
        // TSNode nodeForContext = definitionNode; // Node for contextual things like overall export (kept as definitionNode for now)

        // 1. Handle language-specific structural unwrapping (e.g., export statements, Python's decorated_definition)
        if (project.getAnalyzerLanguage() == Language.JAVASCRIPT && "export_statement".equals(definitionNode.getType())) {
            TSNode declarationInExport = definitionNode.getChildByFieldName("declaration");
            if (declarationInExport != null && !declarationInExport.isNull()) {
                nodeForContent = declarationInExport;
            }
        } else if (project.getAnalyzerLanguage() == Language.PYTHON && "decorated_definition".equals(definitionNode.getType())) {
            // Python's decorated_definition: decorators and actual def are children.
            // Process decorators directly here and identify the actual content node.
            for (int i = 0; i < definitionNode.getNamedChildCount(); i++) {
                TSNode child = definitionNode.getNamedChild(i);
                if (profile.decoratorNodeTypes().contains(child.getType())) {
                    signatureLines.add(textSlice(child, src).stripLeading());
                } else if (profile.functionLikeNodeTypes().contains(child.getType()) || profile.classLikeNodeTypes().contains(child.getType())) {
                    nodeForContent = child; // This will be used for the main signature body
                }
            }
        }

        // 2. Handle decorators for languages where they precede the definition
        //    (Skip if Python already handled its specific decorator structure)
        if (!(project.getAnalyzerLanguage() == Language.PYTHON && "decorated_definition".equals(definitionNode.getType()))) {
            List<TSNode> decorators = getPrecedingDecorators(nodeForContent); // Decorators precede the actual content node
            for (TSNode decoratorNode : decorators) {
                signatureLines.add(textSlice(decoratorNode, src).stripLeading());
            }
        }

        // 3. Determine export/visibility prefix based on the actual content node and its context
        //    (e.g. if nodeForContent's parent is an export statement)
        String exportPrefix = getVisibilityPrefix(nodeForContent, src);

        // 4. Build main signature based on type, using nodeForContent
        SkeletonType skeletonType = getSkeletonTypeForCapture(primaryCaptureName);
        switch (skeletonType) {
            case CLASS_LIKE: {
                TSNode bodyNode = nodeForContent.getChildByFieldName(profile.bodyFieldName());
                String classSignatureText;
                if (bodyNode != null && !bodyNode.isNull()) {
                    classSignatureText = textSlice(nodeForContent.getStartByte(), bodyNode.getStartByte(), src).stripTrailing();
                } else {
                    classSignatureText = textSlice(nodeForContent.getStartByte(), nodeForContent.getEndByte(), src).stripTrailing();
                    if (classSignatureText.endsWith("{")) classSignatureText = classSignatureText.substring(0, classSignatureText.length() - 1).stripTrailing();
                    else if (classSignatureText.endsWith(";")) classSignatureText = classSignatureText.substring(0, classSignatureText.length() - 1).stripTrailing();
                }
                String headerLine = assembleClassSignature(nodeForContent, src, exportPrefix, classSignatureText, "");
                if (headerLine != null && !headerLine.isBlank()) signatureLines.add(headerLine);
                break;
            }
            case FUNCTION_LIKE:
                // Pass determined exportPrefix to buildFunctionSkeleton
                buildFunctionSkeleton(nodeForContent, Optional.of(simpleName), src, "", signatureLines, exportPrefix);
                break;
            case FIELD_LIKE: {
                // The signature is exportPrefix + text_of_field_declaration (from nodeForContent)
                signatureLines.add(exportPrefix + textSlice(nodeForContent, src).stripLeading().strip());
                break;
            }
            case UNSUPPORTED:
            default:
                 log.debug("Unsupported capture name '{}' for signature building (type {}). Using raw text slice, stripped.", primaryCaptureName, skeletonType);
                 signatureLines.add(textSlice(definitionNode, src).stripLeading());
                 break;
        }

        String result = String.join("\n", signatureLines).stripTrailing(); // stripTrailing still useful for multi-line sigs
        log.trace("buildSignatureString: DefNode={}, Capture='{}', Signature (first line): '{}'",
                  definitionNode.getType(), primaryCaptureName, (result.isEmpty() ? "EMPTY" : result.lines().findFirst().orElse("EMPTY")));
        return result;
    }

    /** Renders the opening part of a class-like structure (e.g., "public class Foo {"). */
    protected abstract String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent);
    // renderClassFooter is removed, replaced by getLanguageSpecificCloser
    // buildClassMemberSkeletons is removed from this direct path; children are handled by recursive reconstruction.

    /* ---------- Granular Signature Rendering Callbacks (Formatting) ---------- */
    protected String getFunctionDeclarationKeyword(TSNode funcNode, String src) { return ""; }

    /**
     * Formats the parameter list for a function. Subclasses may override to provide
     * language-specific formatting using the full AST subtree. The default
     * implementation simply returns the raw text of {@code parametersNode}.
     *
     * @param parametersNode The TSNode representing the parameter list.
     * @param src The source code.
     * @return The formatted parameter list text.
     */
    protected String formatParameterList(TSNode parametersNode, String src) {
        return parametersNode == null || parametersNode.isNull() ? "" : textSlice(parametersNode, src);
    }

    /**
     * @deprecated Use {@link #formatParameterList(TSNode, String)} instead for AST-aware formatting.
     * This method is kept for backward compatibility for subclasses that might still override it.
     */
    @Deprecated
    protected String formatParameterList(String paramsText) { return paramsText; }

    /**
     * Formats the return-type portion of a function signature. Subclasses may
     * override to provide language-specific formatting. The default implementation
     * returns the raw text of {@code returnTypeNode} (or an empty string if the
     * node is null).
     *
     * @param returnTypeNode The TSNode representing the return type.
     * @param src The source code.
     * @return The formatted return type text.
     */
    protected String formatReturnType(TSNode returnTypeNode, String src) {
        return returnTypeNode == null || returnTypeNode.isNull() ? "" : textSlice(returnTypeNode, src);
    }

    /**
     * @deprecated Use {@link #formatReturnType(TSNode, String)} instead for AST-aware formatting.
     * This method is kept for backward compatibility for subclasses that might still override it.
     */
    @Deprecated
    protected String formatReturnType(String returnTypeText) { return returnTypeText == null ? "" : returnTypeText; }

    protected String getClassDeclarationKeyword(TSNode classNode, String src) { return ""; }
    protected String formatHeritage(String signatureText) { return signatureText; }

    /* ---------- Granular Signature Rendering Callbacks (Assembly) ---------- */
    protected String assembleFunctionSignature(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String paramsText, String returnTypeText, String indent) {
        return renderFunctionDeclaration(funcNode, src, exportPrefix, asyncPrefix, functionName, paramsText, returnTypeText, indent);
    }
    protected String assembleClassSignature(TSNode classNode, String src, String exportPrefix, String classSignatureText, String baseIndent) {
        return renderClassHeader(classNode, src, exportPrefix, classSignatureText, baseIndent);
    }

    /**
     * Determines a visibility or export prefix (e.g., "export ", "public ") for a given node.
     * Subclasses can override this to provide language-specific logic.
     * The default implementation returns an empty string.
     *
     * @param node The node to check for visibility/export modifiers.
     * @param src  The source code.
     * @return The visibility or export prefix string.
     */
    protected String getVisibilityPrefix(TSNode node, String src) {
        return ""; // Default implementation returns an empty string
    }

    /**
     * Builds the function signature lines.
     * @param funcNode The TSNode for the function definition.
     * @param providedNameOpt Optional pre-determined name (e.g. from a specific capture).
     * @param src Source code.
     * @param indent Indentation string.
     * @param lines List to add signature lines to.
     * @param outerExportPrefix Pre-determined export prefix from the broader context (e.g. if function is in an export statement).
     */
    protected void buildFunctionSkeleton(TSNode funcNode, Optional<String> providedNameOpt, String src, String indent, List<String> lines, String outerExportPrefix) {
        var profile = getLanguageSyntaxProfile();
        String functionName;
        TSNode nameNode = funcNode.getChildByFieldName(profile.identifierFieldName());

        if (nameNode != null && !nameNode.isNull()) {
            functionName = textSlice(nameNode, src);
        } else if (providedNameOpt.isPresent()) {
            functionName = providedNameOpt.get();
        } else {
            // Try to extract name using extractSimpleName as a last resort if the specific field isn't found/helpful
            // This could happen for anonymous functions or if identifierFieldName isn't 'name' and not directly on funcNode.
            Optional<String> extractedNameOpt = extractSimpleName(funcNode, src);
            if (extractedNameOpt.isPresent()) {
                functionName = extractedNameOpt.get();
            } else {
                String funcNodeText = textSlice(funcNode, src);
                log.warn("Function node type {} has no name field '{}' and no name was provided or extracted. Raw text: {}",
                         funcNode.getType(), profile.identifierFieldName(), funcNodeText.lines().findFirst().orElse(""));
                lines.add(indent + funcNodeText);
                log.warn("-> Falling back to raw text slice for function skeleton due to missing name.");
                return;
            }
        }

        TSNode paramsNode = funcNode.getChildByFieldName(profile.parametersFieldName());
        TSNode returnTypeNode = null;
        if (!profile.returnTypeFieldName().isEmpty()) {
            returnTypeNode = funcNode.getChildByFieldName(profile.returnTypeFieldName());
        }
        TSNode bodyNode = funcNode.getChildByFieldName(profile.bodyFieldName());

        // Parameter and body nodes are usually essential for a valid function signature.
        // Return type can often be omitted.
        if (paramsNode == null || paramsNode.isNull()) {
              String funcNodeText = textSlice(funcNode, src);
              log.warn("Could not find parameters node (field '{}') for function node type '{}', name '{}'. Raw text: {}",
                       profile.parametersFieldName(), funcNode.getType(), functionName, funcNodeText.lines().findFirst().orElse(""));
              lines.add(indent + funcNodeText);
              log.warn("-> Falling back to raw text slice for function skeleton for '{}' due to missing parameters.", functionName);
              return;
        }
        // Body node is essential for some languages to distinguish declaration from call or for placeholder
        if (bodyNode == null || bodyNode.isNull()) {
            // This might be acceptable for abstract methods or interface methods in some languages
            // but renderFunctionDeclaration typically expects it for the placeholder.
            // For now, we'll log and proceed, as some renderers might handle it (e.g. C# method_declaration can lack body for extern).
            log.debug("Body node (field '{}') not found for function node type '{}', name '{}'. Renderer must handle this.",
                     profile.bodyFieldName(), funcNode.getType(), functionName);
        }

        // Use the passed-in outerExportPrefix. If it's empty, then allow getVisibilityPrefix to check funcNode itself for local modifiers.
        String exportPrefix = (outerExportPrefix != null && !outerExportPrefix.isEmpty()) ? outerExportPrefix : getVisibilityPrefix(funcNode, src);
        String asyncPrefix = "";
        TSNode firstChildOfFunc = funcNode.getChild(0);
        String asyncKWType = profile.asyncKeywordNodeType();
        if (!asyncKWType.isEmpty() && firstChildOfFunc != null && !firstChildOfFunc.isNull() && asyncKWType.equals(firstChildOfFunc.getType())) {
            asyncPrefix = textSlice(firstChildOfFunc, src).strip() + " "; // Capture "async " or similar from source
        }

        String paramsText = formatParameterList(paramsNode, src);
        String returnTypeText = formatReturnType(returnTypeNode, src);

        String functionLine = assembleFunctionSignature(funcNode, src, exportPrefix, asyncPrefix, functionName, paramsText, returnTypeText, indent);
        if (functionLine != null && !functionLine.isBlank()) {
            lines.add(functionLine);
        }
    }

    protected abstract String bodyPlaceholder();

    /**
     * Renders the complete declaration line for a function, including any prefixes, name, parameters,
     * return type, and language-specific syntax like "def" or "function" keywords, colons, or braces.
     * Implementations are responsible for constructing the entire line, including indentation and any
     * language-specific body placeholder if the function body is not empty or trivial.
     *
     * @param funcNode The Tree-sitter node representing the function.
     * @param src The source code of the file.
     * @param exportPrefix The export prefix (e.g., "export ") if applicable, otherwise empty.
     * @param asyncPrefix The async prefix (e.g., "async ") if applicable, otherwise empty.
     * @param functionName The name of the function.
     * @param paramsText The text content of the function's parameters.
     * @param returnTypeText The text content of the function's return type, or empty if none.
     * @param indent The base indentation string for this line.
     * @return The fully rendered function declaration line, or null/blank if it should not be added.
     */
    protected abstract String renderFunctionDeclaration(TSNode funcNode,
                                                        String src,
                                                        String exportPrefix,
                                                        String asyncPrefix,
                                                        String functionName,
                                                        String paramsText,
                                                        String returnTypeText,
                                                        String indent);

    /** Finds decorator nodes immediately preceding a given node. */
    private List<TSNode> getPrecedingDecorators(TSNode decoratedNode) {
        List<TSNode> decorators = new ArrayList<>();
        var decoratorNodeTypes = getLanguageSyntaxProfile().decoratorNodeTypes();
        if (decoratorNodeTypes.isEmpty()) {
            return decorators;
        }
        TSNode current = decoratedNode.getPrevSibling();
        while (current != null && !current.isNull() && decoratorNodeTypes.contains(current.getType())) {
            decorators.add(current);
            current = current.getPrevSibling();
        }
        Collections.reverse(decorators); // Decorators should be in source order
        return decorators;
    }


    /** Extracts a substring from the source code based on node boundaries. */
    protected String textSlice(TSNode node, String src) {
        if (node == null || node.isNull()) return "";
        return src.substring(node.getStartByte(), node.getEndByte());
    }

     /** Extracts a substring from the source code based on byte offsets. */
    protected String textSlice(int startByte, int endByte, String src) {
        return src.substring(startByte, Math.min(endByte, src.length()));
    }


    /* ---------- helpers ---------- */

    /**
     * Fallback to extract a simple name from a declaration node when an explicit `.name` capture isn't found.
     * Tries finding a child node with field name specified in LanguageSyntaxProfile.
     * Needs the source string `src` for substring extraction.
     */
    private Optional<String> extractSimpleName(TSNode decl, String src) {
        Optional<String> nameOpt = Optional.empty();
        String identifierFieldName = getLanguageSyntaxProfile().identifierFieldName();
        if (identifierFieldName.isEmpty()) {
            log.warn("Identifier field name is empty in LanguageSyntaxProfile for node type {} at line {}. Cannot extract simple name by field.",
                     decl.getType(), decl.getStartPoint().getRow() + 1);
            return Optional.empty();
        }

        try {
            TSNode nameNode = decl.getChildByFieldName(identifierFieldName);
            if (nameNode != null && !nameNode.isNull()) {
                nameOpt = Optional.of(src.substring(nameNode.getStartByte(), nameNode.getEndByte()));
            } else {
                 log.warn("getChildByFieldName('{}') returned null or isNull for node type {} at line {}",
                          identifierFieldName, decl.getType(), decl.getStartPoint().getRow() + 1);
            }
        } catch (Exception e) {
             log.warn("Error extracting simple name using field '{}' from node type {} for node starting with '{}...': {}",
                      identifierFieldName, decl.getType(), src.substring(decl.getStartByte(), Math.min(decl.getEndByte(), decl.getStartByte() + 20)), e.getMessage());
        }

        if (nameOpt.isEmpty()) {
            log.warn("extractSimpleName: Failed using getChildByFieldName('{}') for node type {} at line {}",
                     identifierFieldName, decl.getType(), decl.getStartPoint().getRow() + 1);
        }
        log.trace("extractSimpleName: DeclNode={}, IdentifierField='{}', ExtractedName='{}'",
                  decl.getType(), identifierFieldName, nameOpt.orElse("N/A"));
        return nameOpt;
    }

    private static String loadResource(String path) {
        try (InputStream in = TreeSitterAnalyzer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Set<String> getSymbols(Set<CodeUnit> sources) {
        Set<String> symbols = new HashSet<>();
        Queue<CodeUnit> toProcess = new LinkedList<>(sources);
        Set<CodeUnit> visited = new HashSet<>();

        while (!toProcess.isEmpty()) {
            CodeUnit current = toProcess.poll();
            if (!visited.add(current)) { // If already visited, skip
                continue;
            }

            String shortName = current.shortName();
            if (shortName == null || shortName.isEmpty()) {
                continue;
            }

            int lastDot = shortName.lastIndexOf('.');
            int lastDollar = shortName.lastIndexOf('$');
            int lastSeparator = Math.max(lastDot, lastDollar);

            String unqualifiedName;
            if (lastSeparator == -1) {
                unqualifiedName = shortName;
            } else {
                // Ensure there's something after the separator
                if (lastSeparator < shortName.length() - 1) {
                    unqualifiedName = shortName.substring(lastSeparator + 1);
                } else {
                    // This case (e.g., shortName ends with '.') should ideally not happen
                    // with valid CodeUnit shortNames, but handle defensively.
                    unqualifiedName = ""; // Or log a warning
                }
            }

            if (!unqualifiedName.isEmpty()) {
                symbols.add(unqualifiedName);
            }

            List<CodeUnit> children = childrenByParent.getOrDefault(current, Collections.emptyList());
            for (CodeUnit child : children) {
                if (!visited.contains(child)) { // Add to queue only if not already processed or in queue
                    toProcess.add(child);
                }
            }
        }
        return symbols;
    }
}
