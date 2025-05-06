package io.github.jbellis.brokk.analyzer;

// Treesitter imports

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
                             skeletons.put(pf, map);
                             // Extract and store classes for this file
                             var classesInFile = map.keySet().stream()
                                 .filter(CodeUnit::isClass)
                                 .collect(Collectors.toSet());
                             log.trace("Classes found for {}: {}", pf, classesInFile.size());
                             if (!classesInFile.isEmpty()) {
                                 fileClasses.put(pf, classesInFile);
                             }
                        } else {
                             // Explicitly log when the returned map is empty
                             log.warn("parseAndExtractSkeletons returned an empty map for file: {}", pf);
                        }
                    } catch (Exception e) {
                        log.warn("Error parsing {}: {}", pf, e, e);
                    }
                });
    }

    /* ---------- IAnalyzer ---------- */
    @Override public boolean isEmpty() { return skeletons.isEmpty(); }

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
                          String skeleton = buildSkeletonString(node, src, primaryCaptureName);
                          log.trace("Built skeleton for '{}':\n{}", simpleName, skeleton);
                          log.trace("buildSkeletonString result for '{}': [{}]", simpleName, skeleton == null ? "NULL" : skeleton.isBlank() ? "BLANK" : skeleton.lines().findFirst().orElse("EMPTY"));
                          if (skeleton != null && !skeleton.isBlank()) {
                              log.trace("Storing TOP-LEVEL skeleton for {} in {} | Skeleton starts with: '{}'",
                                        cu, file, skeleton.lines().findFirst().orElse(""));
                              if (finalSkeletons.containsKey(cu)) {
                                 log.warn("Overwriting skeleton for {} in {}. Old: '{}', New: '{}'",
                                          cu, file, finalSkeletons.get(cu).lines().findFirst().orElse(""), skeleton.lines().findFirst().orElse(""));
                             }
                             finalSkeletons.put(cu, skeleton);
                             log.trace("Storing skeleton for CU: {}", cu);
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

    // Note: Add other types as needed, e.g. for C-like languages or others with similar "blocking" constructs
    private static final Set<String> CSHARP_BLOCKER_NODE_TYPES = Set.of(
            "class_declaration",
            "struct_declaration",
            "interface_declaration",
            "enum_declaration",
            "delegate_declaration",
            "method_declaration",
            "constructor_declaration",
            "destructor_declaration",
            "property_declaration",
            "indexer_declaration",
            "event_declaration",
            "operator_declaration",
            "field_declaration"
            // Note: "namespace_declaration" is NOT a blocker, it's a transparent container for top-level.
            // "declaration_list" is also not a blocker, it's often an artifact of the parser.
    );

    /** Checks if a node is considered top-level by walking up its parent chain. */
    private boolean isTopLevel(TSNode node, TSNode rootNode) {
        if (node == null || node.isNull()) {
            log.trace("isTopLevel: Node is null. Result=false (Initial Null Check)");
            return false;
        }
        if (rootNode == null || rootNode.isNull()) {
            log.trace("isTopLevel: Root is null for Node={}. Result=false (Initial Null Check)", node.getType());
            return false;
        }

        String rootNodeType = rootNode.getType();
        TSNode current = node.getParent();
        // String originalNodeParentType = (current == null || current.isNull()) ? "null" : current.getType(); // originalNodeParentType is not used by new logging

        while (current != null && !current.isNull()) {
            String currentType = current.getType();

            // If the current parent IS the root node (by type match), then 'node' is effectively top-level.
            // This handles cases where the direct parent might be a transparent wrapper like 'declaration_list'.
            if (currentType.equals(rootNodeType)) {
                log.trace("isTopLevel: PASSED [Ancestor is Root Type] - Node='{}', Ancestor='{}' (is root type), Root='{}'",
                         node.getType(), currentType, rootNodeType);
                return true;
            }

            // For C# (and similar languages where root is 'compilation_unit'),
            // if we encounter a blocking declaration type before reaching the root,
            // then 'node' is nested and not top-level.
            if ("compilation_unit".equals(rootNodeType) && CSHARP_BLOCKER_NODE_TYPES.contains(currentType)) {
                log.trace("isTopLevel: DENIED [Ancestor is Blocker] - Node='{}' (Target Root='{}') is nested inside Blocker='{}'",
                         node.getType(), rootNodeType, currentType);
                return false;
            }
            // For other languages, or if not a C# blocker, continue up.
            // Python's root is "module". If we hit "class_definition" or "function_definition"
            // before "module", it's nested. This logic might need refinement if
            // BLOCKER_NODE_TYPES needs to be language-specific beyond C#.
            // For now, CSHARP_BLOCKER_NODE_TYPES only applies if rootType is "compilation_unit".

            current = current.getParent();
        }

        // If the loop finishes, it means 'current' became null before we hit the root node type.
        // This could happen if 'node' itself was the root, or an unexpected tree structure.
        // If node is the root node, its parent is null, loop won't run, we fall here.
        // If node is a direct child of root (parser gives direct parentage), current becomes root, first check passes.
        log.warn("isTopLevel: DENIED [Reached Null Ancestor] - Node='{}' (Target Root='{}'). Traversed all parents without matching root type or hitting a C# blocker.",
                 node.getType(), rootNodeType);
        return false; // Default to false if we exhaust parents without conclusion.
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
     */
    private String buildSkeletonString(TSNode definitionNode, String src, String primaryCaptureName) {
        List<String> lines = new ArrayList<>();
        String baseIndent = computeIndentation(definitionNode, src);

        // Common elements: decorators
        List<TSNode> decorators = getPrecedingDecorators(definitionNode, src);
        for (TSNode decoratorNode : decorators) {
            lines.add(baseIndent + textSlice(decoratorNode, src));
        }

        if (primaryCaptureName.startsWith("class")) {
            buildClassSkeleton(definitionNode, src, baseIndent, lines);
        } else if (primaryCaptureName.startsWith("function")) {
            buildFunctionSkeleton(definitionNode, src, baseIndent, lines);
        } else {
             log.warn("Unsupported top-level definition type for skeleton: {}", primaryCaptureName);
             return textSlice(definitionNode, src); // Fallback: return raw text
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
        lines.add(baseIndent + signature + " {");

        String memberIndent = baseIndent + "  "; // Indent members further

        // Iterate through direct children of the body node
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode memberNode = bodyNode.getNamedChild(i);
            String memberType = memberNode.getType();

            // Handle potentially decorated methods/functions within the class
            if ("function_definition".equals(memberType)) {
                 // Handle undecorated functions (should have no preceding decorators in this context)
                 // Pass empty placeholder `""` so method bodies use { ... }
                 buildFunctionSkeleton(memberNode, src, memberIndent, lines);
            } else if ("decorated_definition".equals(memberType)) {
                 // Handle decorated functions
                 TSNode functionDefNode = null;
                 // Add decorators first
                 for (int j = 0; j < memberNode.getChildCount(); j++) {
                     TSNode child = memberNode.getChild(j);
                     if (child == null || child.isNull()) continue;
                      if ("decorator".equals(child.getType())) {
                          // Decorator should use the standard member indentation for skeleton output
                          lines.add(memberIndent + textSlice(child, src));
                      } else if ("function_definition".equals(child.getType())) {
                          functionDefNode = child;
                          // Don't break, might be other non-decorator children theoretically
                     }
                 }
                 // Then add the function skeleton
                 if (functionDefNode != null) {
                     // Pass empty placeholder `""` so method bodies use { ... }
                     buildFunctionSkeleton(functionDefNode, src, memberIndent, lines);
                 } else {
                     log.warn("decorated_definition node found without an inner function_definition: {}", textSlice(memberNode, src).lines().findFirst().orElse(""));
                 }
            }
            // Handle field assignments (like self.x = ...) - check if it's an assignment targeting 'self'
            else if ("expression_statement".equals(memberType)) {
                 TSNode expr = memberNode.getChild(0);
                 if (expr != null && "assignment".equals(expr.getType())) {
                    TSNode left = expr.getChildByFieldName("left");
                    if (left != null && "attribute".equals(left.getType())) {
                         TSNode object = left.getChildByFieldName("object");
                         if (object != null && "identifier".equals(object.getType()) && "self".equals(textSlice(object, src))) {
                             // It's an assignment like self.x = ...
                             lines.add(memberIndent + textSlice(memberNode, src).strip());
                         }
                    }
                 }
            }
            // Potentially handle nested classes, etc. here if needed
        }


        lines.add(baseIndent + "}");
    }

     private void buildFunctionSkeleton(TSNode funcNode, String src, String indent, List<String> lines) {
        TSNode nameNode = funcNode.getChildByFieldName("name");
        TSNode paramsNode = funcNode.getChildByFieldName("parameters");
        TSNode returnTypeNode = funcNode.getChildByFieldName("return_type"); // Might be null
        TSNode bodyNode = funcNode.getChildByFieldName("body"); // The block containing statements

        if (nameNode == null || nameNode.isNull() || paramsNode == null || paramsNode.isNull() || bodyNode == null || bodyNode.isNull()) {
              String funcNodeText = textSlice(funcNode, src).lines().findFirst().orElse("");
              log.warn("Could not find essential parts (name, params, body) for function node: {}", funcNodeText);
              lines.add(indent + textSlice(funcNode, src)); // Fallback
              log.warn("-> Falling back to raw text slice for function skeleton."); // Add log
              return;
         }

        // Handle async keyword
        TSNode firstChild = funcNode.getChild(0);
        String prefix = "";
        if (firstChild != null && "async".equals(firstChild.getType())) {
            prefix = "async ";
        }

        // Reconstruct signature parts from their specific nodes
        String name = textSlice(nameNode, src);
        String params = textSlice(paramsNode, src);
        String returnType = (returnTypeNode != null && !returnTypeNode.isNull()) ? " -> " + textSlice(returnTypeNode, src) : "";

        String reconstructedSignature = String.format("%sdef %s%s%s:", prefix, name, params, returnType);
        log.trace("buildFunctionSkeleton: FuncNode={}, Reconstructed Sig: '{}'", funcNode.getType(), reconstructedSignature);


        // Check if the body is more than just 'pass' or empty
        boolean hasMeaningfulBody = bodyNode.getNamedChildCount() > 1 ||
                                    (bodyNode.getNamedChildCount() == 1 && !"pass_statement".equals(bodyNode.getNamedChild(0).getType()));



        // Add the reconstructed line
        if (hasMeaningfulBody) {
             lines.add(indent + reconstructedSignature + " " + bodyPlaceholder());
         } else {
             lines.add(indent + reconstructedSignature); // Simple signature line without suffix
         }
    }

    protected abstract String bodyPlaceholder();

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
    private String textSlice(TSNode node, String src) {
        if (node == null || node.isNull()) return "";
        return src.substring(node.getStartByte(), node.getEndByte());
    }

     /** Extracts a substring from the source code based on byte offsets. */
    private String textSlice(int startByte, int endByte, String src) {
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
