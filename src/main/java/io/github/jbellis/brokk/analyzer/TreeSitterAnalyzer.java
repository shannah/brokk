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
    private final Map<ProjectFile, Map<CodeUnit, String>> skeletons = new ConcurrentHashMap<>();
    private final Map<ProjectFile, Set<CodeUnit>> fileClasses = new ConcurrentHashMap<>();
    private final IProject project;

    /* ---------- constructor ---------- */
    protected TreeSitterAnalyzer(IProject project) {
        this.project = project;
        this.tsLanguage = getTSLanguage(); // Provided by subclass
        Objects.requireNonNull(tsLanguage, "Tree-sitter TSLanguage must not be null");

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
                        var map = parseAndExtractSkeletons(pf, localParser);
                        log.trace("Skeletons found for {}: {}", pf, map.size());
                        if (!map.isEmpty()) {
                             skeletons.put(pf, Collections.unmodifiableMap(new HashMap<>(map)));
                             // Extract and store classes for this file
                             var classesInFile = map.keySet().stream()
                                 .filter(CodeUnit::isClass)
                                 .collect(Collectors.toSet());
                             log.trace("Classes found for {}: {}", pf, classesInFile.size());
                             if (!classesInFile.isEmpty()) {
                                 fileClasses.put(pf, Collections.unmodifiableSet(new HashSet<>(classesInFile)));
                             }
                        } else {
                             // Explicitly log when the returned map is empty
                             log.debug("parseAndExtractSkeletons returned an empty map for file: {}", pf);
                        }
                    } catch (Exception e) {
                        log.warn("Error parsing {}: {}", pf, e, e);
                    }
                });
    }

    /* ---------- IAnalyzer ---------- */
    @Override public boolean isEmpty() { return skeletons.isEmpty(); }

    @Override
    public List<CodeUnit> getAllClasses() {
        return fileClasses.values().stream()
                .flatMap(Collection::stream)
                .toList();
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        var m = skeletons.get(file);
        log.trace("getSkeletons: file={}, count={}", file, (m == null ? 0 : m.size()));
        return m == null ? Map.of() : Collections.unmodifiableMap(m);
    }

    @Override
    public Set<CodeUnit> getClassesInFile(ProjectFile file) {
        var classes = fileClasses.get(file);
        log.trace("getClassesInFile: file={}, count={}", file, (classes == null ? 0 : classes.size()));
        return classes == null ? Set.of() : Collections.unmodifiableSet(classes);
    }

    @Override
    public scala.Option<String> getSkeleton(String fqName) {
        scala.Option<String> result = scala.Option.empty();
        for (var fileSkeletons : skeletons.values()) {
            for (var entry : fileSkeletons.entrySet()) {
                // Compute the fqName from the CodeUnit's components before comparing
                if (entry.getKey().fqName().equals(fqName)) {
                    result = scala.Option.apply(entry.getValue());
                    break; // Found, no need to check further
                }
            }
            if (result.isDefined()) {
                break; // Found in this file, no need to check other files
            }
        }
        log.trace("getSkeleton: fqName='{}', found={}", fqName, result.isDefined());
        return result;
    }

    /* ---------- abstract hooks ---------- */
    /** Tree-sitter TSLanguage grammar to use (e.g. {@code new TreeSitterPython()}). */
    protected abstract TSLanguage getTSLanguage();

    /** Class-path resource for the query (e.g. {@code "treesitter/python.scm"}). */
    protected abstract String getQueryResource();

    /**
     * Defines the general type of skeleton that should be built for a given capture.
     */
    public enum SkeletonType {
        CLASS_LIKE,
        FUNCTION_LIKE,
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
    protected abstract SkeletonType getSkeletonTypeForCapture(String captureName);

    /**
     * Translate a capture produced by the query into a {@link CodeUnit}.
     * Return {@code null} to ignore this capture.
     */
    protected abstract CodeUnit createCodeUnit(ProjectFile file,
                                               String captureName,
                                               String simpleName,
                                               String namespaceName);

    /** Captures that should be ignored entirely. */
    protected Set<String> getIgnoredCaptures() { return Set.of(); }

    /**
     * Get the project this analyzer is associated with.
     */
    protected IProject getProject() {
        return project;
    }

    /* ---------- core parsing ---------- */
    /** Parses a single file and extracts skeletons using the provided thread-local parser. */
    private Map<CodeUnit, String> parseAndExtractSkeletons(ProjectFile file, TSParser localParser) throws IOException {
        log.trace("parseAndExtractSkeletons: Parsing file: {}", file);
        String src = Files.readString(file.absPath(), StandardCharsets.UTF_8);
        // Map<CodeUnit, String> result = new HashMap<>(); // Not used directly for final output from this function.

        // TSTree does not implement AutoCloseable, rely on GC + Cleaner for resource management
        TSTree tree = localParser.parseString(null, src);
        TSNode rootNode = tree.getRootNode();
        if (rootNode.isNull()) {
            log.warn("Parsing failed or produced null root node for {}", file);
            return Map.of();
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
                    String simpleName = null;
                    String expectedNameCapture = captureName.replace(".definition", ".name");
                    TSNode nameNode = capturedNodes.get(expectedNameCapture);

                    if (nameNode != null && !nameNode.isNull()) {
                        simpleName = textSlice(nameNode, src);
                    } else {
                        log.warn("Expected name capture '{}' not found for definition '{}' in match for file {}. Falling back to extractSimpleName on definition node.",
                                 expectedNameCapture, captureName, file);
                        simpleName = extractSimpleName(definitionNode, src).orElse(null);
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

        // Now, build skeletons only for top-level definitions
        Map<CodeUnit, String> finalSkeletons = new HashMap<>();
        TSNode root = tree.getRootNode(); // Get root node again for isTopLevel check

        for (var entry : declarationNodes.entrySet()) {
            TSNode node = entry.getKey();
            Map.Entry<String, String> defInfo = entry.getValue();
            String primaryCaptureName = defInfo.getKey();
            String simpleName = defInfo.getValue();

            log.trace("Checking node type {} for top-level status.", node.getType());

            boolean nodeIsTopLevel = isTopLevel(node, root);
            log.trace("Node isTopLevel={}, simpleName='{}' for node type {}", nodeIsTopLevel, (simpleName != null ? simpleName : "N/A"), node.getType());

            if (nodeIsTopLevel) {
                 log.trace("Node is top-level: {}", textSlice(node, src).lines().findFirst().orElse(""));

                 if (simpleName != null && !simpleName.isBlank()) {
                     log.trace("Processing top-level definition: Name='{}', Capture='{}', Node Type='{}'",
                               simpleName, primaryCaptureName, node.getType());

                      String namespace = extractNamespace(node, root, src);
                      log.trace("Calling createCodeUnit for simpleName='{}', capture='{}', namespace='{}'", simpleName, primaryCaptureName, namespace);

                      CodeUnit cu = createCodeUnit(file, primaryCaptureName, simpleName, namespace);
                      log.trace("createCodeUnit returned: {}", cu);

                      if (cu != null) {
                          String skeleton = buildSkeletonString(node, simpleName, src, primaryCaptureName);
                          log.trace("Built skeleton for '{}':\n{}", simpleName, skeleton);
                          log.trace("buildSkeletonString result for '{}': [{}]", simpleName, skeleton == null ? "NULL" : skeleton.isBlank() ? "BLANK" : skeleton.lines().findFirst().orElse("EMPTY"));

                          if (skeleton != null && !skeleton.isBlank()) {
                              log.trace("Storing TOP-LEVEL skeleton for {} in {} | Skeleton starts with: '{}'",
                                        cu, file, skeleton.lines().findFirst().orElse(""));
                              finalSkeletons.compute(cu, (currentCU, existingSkeleton) -> {
                                  if (existingSkeleton == null) {
                                      log.trace("Storing NEW skeleton for {} in {} | Skeleton starts with: '{}'",
                                                currentCU, file, skeleton.lines().findFirst().orElse(""));
                                      return skeleton;
                                  }
                                  // Prefer skeleton that starts with "export" if current one doesn't and new one does.
                                  boolean newIsExported = skeleton.trim().startsWith("export");
                                  boolean oldIsExported = existingSkeleton.trim().startsWith("export");

                                  if (newIsExported && !oldIsExported) {
                                      log.warn("Overwriting non-exported skeleton for {} with EXPORTED version. Old: '{}', New: '{}'",
                                               currentCU, existingSkeleton.lines().findFirst().orElse(""), skeleton.lines().findFirst().orElse(""));
                                      return skeleton;
                                  } else if (!newIsExported && oldIsExported) {
                                      log.trace("Keeping existing EXPORTED skeleton for {}. Discarding new non-exported: '{}'",
                                                currentCU, skeleton.lines().findFirst().orElse(""));
                                      return existingSkeleton;
                                  } else {
                                      // Both have same export status (either both exported or both not)
                                      // or some other complex scenario. Log and keep the one that was already there
                                      // (effectively making the processing order of declarationNodes relevant for ties).
                                      // This could be made more deterministic (e.g. shortest/longest, but "export" is main concern).
                                      // For now, if they are equally "good" (e.g. both exported), a warning implies potential duplicate query match.
                                      log.warn("Duplicate skeleton processing for {}. Export-status new: {}, old: {}. Keeping existing. Existing: '{}', New (discarded): '{}'",
                                               currentCU, newIsExported, oldIsExported,
                                               existingSkeleton.lines().findFirst().orElse(""), skeleton.lines().findFirst().orElse(""));
                                      return existingSkeleton;
                                  }
                              });
                             log.trace("Stored/Updated skeleton for CU: {}", cu);
                         } else {
                             log.warn("buildSkeletonString returned empty/null for top-level node {} ({})", simpleName, primaryCaptureName);
                         }
                     } else {
                         log.warn("createCodeUnit returned null for top-level node {} ({})", simpleName, primaryCaptureName);
                     }
                 } else {
                     // This case implies simpleName was null/blank after the first loop's determination attempts.
                     log.warn("Simple name was null/blank for top-level node type {} (capture: {}) in file {}. Skeleton not generated.",
                              node.getType(), primaryCaptureName, file);
                  }
             } else {
                  TSNode parent = node.getParent();
                  String parentType = (parent == null || parent.isNull()) ? "null" : parent.getType();
                  log.trace("Node is NOT top-level: Type='{}', ParentType='{}'. First line: '{}'",
                            node.getType(), parentType, textSlice(node, src).lines().findFirst().orElse(""));
             }
         }

        log.trace("Finished parsing {}: found {} top-level skeletons.", file, finalSkeletons.size());
        return finalSkeletons;
    }

    // Removed findCaptureInMatch as it was unused and flawed.

    /* ---------- Skeleton Building Logic ---------- */

    /**
     * Returns a set of node types that, if encountered as an ancestor of a candidate node
     * before reaching the root node, would disqualify the candidate node from being top-level.
     * The default implementation returns an empty set, meaning no node types act as blockers
     * unless overridden by a subclass.
     */
    protected Set<String> getTopLevelBlockerNodeTypes() {
        return Set.of(); // Default implementation returns an empty set
    }

    /** Checks if a node is considered top-level by walking up its parent chain. */
    private boolean isTopLevel(TSNode node, TSNode rootNode) {
        if (node == null || node.isNull()) {
            log.trace("isTopLevel: Node is null. Result=false (Initial Null Check)");
            return false;
        }
        if (rootNode == null || rootNode.isNull()) {
            log.trace("isTopLevel: Root is null for Node={}. Result=false (Initial Null Check for root)", node.getType());
            return false;
        }

        if (node.equals(rootNode)) {
            log.trace("isTopLevel: PASSED [Node is Root] - Node='{}'", node.getType());
            return true;
        }

        String rootNodeType = rootNode.getType();
        Set<String> blockerNodeTypes = getTopLevelBlockerNodeTypes();
        TSNode current = node.getParent();

        while (current != null && !current.isNull()) {
            String currentType = current.getType();

            // If the current parent IS the root node (by type match), then 'node' is effectively top-level.
            if (currentType.equals(rootNodeType)) {
                log.trace("isTopLevel: PASSED [Ancestor is Root Type] - Node='{}', Ancestor='{}' (is root type), Root='{}'",
                         node.getType(), currentType, rootNodeType);
                return true;
            }

            // If we encounter a language-specific blocking declaration type before reaching the root,
            // then 'node' is nested and not top-level.
            if (!blockerNodeTypes.isEmpty() && blockerNodeTypes.contains(currentType)) {
                log.trace("isTopLevel: DENIED [Ancestor is Blocker] - Node='{}' is nested inside Blocker='{}' (Language: {})",
                         node.getType(), currentType, getProject().getAnalyzerLanguage());
                return false;
            }

            current = current.getParent();
        }

        // If the loop finishes, 'current' became null. This means we traversed all parents
        // without matching the root node type or hitting a defined blocker.
        log.debug("isTopLevel: DENIED [Reached Null Ancestor or Loop Exhausted] - Node='{}' (Target Root='{}'). Parent traversal did not confirm top-level status against specified root or blockers.",
                 node.getType(), rootNodeType);
        return false;
    }

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
     * Builds a summarized skeleton string for a given top-level definition node.
     * Uses tree traversal to find relevant parts like signature and body.
     * @param simpleName The simple name of the definition, pre-determined by query captures.
     */
    private String buildSkeletonString(TSNode definitionNode, String simpleName, String src, String primaryCaptureName) {
        List<String> lines = new ArrayList<>();
        String baseIndent = computeIndentation(definitionNode, src);

        // Common elements: decorators
        List<TSNode> decorators = getPrecedingDecorators(definitionNode, src);
        for (TSNode decoratorNode : decorators) {
            lines.add(baseIndent + textSlice(decoratorNode, src));
        }

        SkeletonType skeletonType = getSkeletonTypeForCapture(primaryCaptureName);
        switch (skeletonType) {
            case CLASS_LIKE:
                buildClassSkeleton(definitionNode, src, baseIndent, lines);
                break;
            case FUNCTION_LIKE:
                buildFunctionSkeleton(definitionNode, Optional.of(simpleName), src, baseIndent, lines);
                break;
            case UNSUPPORTED:
            default: // Also handles null if getSkeletonTypeForCapture could somehow return null
                 log.debug("Unsupported capture name '{}' for skeleton building (resolved to type {}). Falling back to raw text slice.", primaryCaptureName, skeletonType);
                 return textSlice(definitionNode, src);
        }

        String result = String.join("\n", lines);
        log.trace("buildSkeletonString: DefNode={}, Capture='{}', Skeleton Output (first line): '{}'", definitionNode.getType(), primaryCaptureName, (result.isEmpty() ? "EMPTY" : result.lines().findFirst().orElse("EMPTY")));
        return result;
    }

    private void buildClassSkeleton(TSNode classNode, String src, String baseIndent, List<String> lines) {
        TSNode nameNode = classNode.getChildByFieldName("name");
        TSNode bodyNode = classNode.getChildByFieldName("body"); // Usually a block node

        if (nameNode == null || nameNode.isNull() || bodyNode == null || bodyNode.isNull()) {
             String classNodeText = textSlice(classNode, src).lines().findFirst().orElse("");
             log.warn("Could not find name or body for class node: {}", classNodeText);
             lines.add(baseIndent + textSlice(classNode, src)); // Fallback
             log.warn("-> Falling back to raw text slice for class skeleton."); // Add log
             return;
         }

        // Class signature line
        String signature = textSlice(classNode.getStartByte(), bodyNode.getStartByte(), src).stripTrailing();
        log.trace("buildClassSkeleton: ClassNode={}, Signature line: '{}'", classNode.getType(), signature);

        String exportPrefix = getVisibilityPrefix(classNode, src);
        String headerLine = renderClassHeader(classNode, src, exportPrefix, signature, baseIndent);
        if (headerLine != null && !headerLine.isBlank()) {
            lines.add(headerLine);
        }

        String memberIndent = baseIndent + "  "; // Indent members further

        // Delegate member skeleton building to language-specific implementation
        buildClassMemberSkeletons(bodyNode, src, memberIndent, lines);

        String footerLine = renderClassFooter(classNode, src, baseIndent);
        if (footerLine != null && !footerLine.isBlank()) {
            lines.add(footerLine);
        }
    }

    protected abstract String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signature, String baseIndent);
    protected abstract String renderClassFooter(TSNode classNode, String src, String baseIndent);

    /**
     * Builds skeletons for the members within a class body.
     * This method is responsible for iterating through the children of the `classBodyNode`,
     * identifying relevant members (methods, inner classes, fields, etc.),
     * and appending their skeleton strings to the `lines` list.
     * Implementations will typically call `buildFunctionSkeleton` or other helper methods.
     *
     * @param classBodyNode The TSNode representing the body of the class.
     * @param src The source code of the file.
     * @param memberIndent The indentation string to be used for each member.
     * @param lines The list to which skeleton lines for members should be added.
     */
    protected abstract void buildClassMemberSkeletons(TSNode classBodyNode, String src, String memberIndent, List<String> lines);

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

    protected void buildFunctionSkeleton(TSNode funcNode, Optional<String> providedNameOpt, String src, String indent, List<String> lines) {
        String functionName;
        TSNode nameNode = funcNode.getChildByFieldName("name");

        if (nameNode != null && !nameNode.isNull()) {
            functionName = textSlice(nameNode, src);
        } else if (providedNameOpt.isPresent()) {
            functionName = providedNameOpt.get();
        } else {
            String funcNodeText = textSlice(funcNode, src);
            log.warn("Function node type {} has no 'name' field and no name was provided. Raw text: {}", funcNode.getType(), funcNodeText.lines().findFirst().orElse(""));
            lines.add(indent + funcNodeText);
            log.warn("-> Falling back to raw text slice for function skeleton due to missing name.");
            return;
        }

        TSNode paramsNode = funcNode.getChildByFieldName("parameters");
        TSNode returnTypeNode = funcNode.getChildByFieldName("return_type"); // Might be null for JS/TS
        TSNode bodyNode = funcNode.getChildByFieldName("body"); // Used by language-specific renderers

        if (paramsNode == null || paramsNode.isNull() || bodyNode == null || bodyNode.isNull()) {
              String funcNodeText = textSlice(funcNode, src);
              log.warn("Could not find essential parts (params, body) for function node type '{}', name '{}'. Raw text: {}",
                       funcNode.getType(), functionName, funcNodeText.lines().findFirst().orElse(""));
              lines.add(indent + funcNodeText); // Use funcNodeText which is already sliced
              log.warn("-> Falling back to raw text slice for function skeleton for '{}'.", functionName);
              return;
         }

        String exportPrefix = getVisibilityPrefix(funcNode, src);
        String asyncPrefix = "";
        TSNode firstChildOfFunc = funcNode.getChild(0); // Check for 'async' keyword
        if (firstChildOfFunc != null && !firstChildOfFunc.isNull() && "async".equals(firstChildOfFunc.getType())) {
            asyncPrefix = "async ";
        }
        
        String paramsText = textSlice(paramsNode, src);
        String returnTypeText = (returnTypeNode != null && !returnTypeNode.isNull()) ? textSlice(returnTypeNode, src) : "";
        
        String functionLine = renderFunctionDeclaration(funcNode, src, exportPrefix, asyncPrefix, functionName, paramsText, returnTypeText, indent);
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

    /** Finds decorator nodes immediately preceding a function or class node. */
    private List<TSNode> getPrecedingDecorators(TSNode decoratedNode, String src) {
        List<TSNode> decorators = new ArrayList<>();
        TSNode current = decoratedNode.getPrevSibling();
        while (current != null && !current.isNull() && "decorator".equals(current.getType())) {
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
     * Tries finding a child node with field name "name", then falls back to the first child of type "identifier".
     * Needs the source string `src` for substring extraction.
     */
    private static Optional<String> extractSimpleName(TSNode decl, String src) {
        Optional<String> nameOpt = Optional.empty();
        try {
            // Try finding a child node with field name "name" first.
            TSNode nameNode = decl.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                nameOpt = Optional.of(src.substring(nameNode.getStartByte(), nameNode.getEndByte()));
            } else {
                 // Log failure specific to getChildByFieldName before falling back or failing
                 log.warn("getChildByFieldName('name') returned null or isNull for node type {} at line {}",
                          decl.getType(), decl.getStartPoint().getRow() + 1);
            }
            // Fallback removed. If getChildByFieldName fails, we assume name extraction isn't straightforward.
        } catch (Exception e) {
             log.warn("Error extracting simple name from node type {} for node starting with '{}...': {}",
                      decl.getType(), src.substring(decl.getStartByte(), Math.min(decl.getEndByte(), decl.getStartByte() + 20)), e.getMessage());
        }
        // If we reach here, it means the try block finished without returning (i.e., getChildByFieldName failed or an exception occurred).
        if (nameOpt.isEmpty()) {
            log.warn("extractSimpleName: Failed using getChildByFieldName('name') for node type {} at line {}",
                     decl.getType(), decl.getStartPoint().getRow() + 1);
        }
        log.trace("extractSimpleName: DeclNode={}, ExtractedName='{}'", decl.getType(), nameOpt.orElse("N/A"));
        return nameOpt;
    }

    private String extractNamespace(TSNode definitionNode, TSNode rootNode, String src) {
        List<String> namespaceParts = new ArrayList<>();
        TSNode current = definitionNode.getParent(); // Start from the parent of the definition node

        while (current != null && !current.isNull() && !current.equals(rootNode)) {
            if ("namespace_declaration".equals(current.getType())) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    String nsPart = textSlice(nameNode, src);
                    namespaceParts.add(nsPart); // Added from innermost to outermost
                }
            }
            current = current.getParent();
        }
        Collections.reverse(namespaceParts); // Reverse to get outermost.innermost
        return String.join(".", namespaceParts);
    }

    /**
     * Computes a package path based on the file's directory structure relative to the project root.
     * Path separators are replaced with dots.
     * @param file The file for which to compute the package path.
     * @return The package path string, or an empty string if the file is in the project root.
     */
    protected String computePackagePath(ProjectFile file) {
        Path projectRoot = getProject().getRoot();
        Path filePath = file.absPath();
        Path parentDir = filePath.getParent();

        if (parentDir == null || parentDir.equals(projectRoot)) {
            return ""; // File is in the project root
        }

        var rel = projectRoot.relativize(parentDir);
        return rel.toString().replace('/', '.').replace('\\', '.');
    }

    private static String loadResource(String path) {
        try (InputStream in = TreeSitterAnalyzer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Removed parseQueryPatterns as TSQuery constructor takes the raw query string.
}
