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
    final Map<ProjectFile, List<CodeUnit>> topLevelDeclarations = new ConcurrentHashMap<>(); // package-private for testing
    final Map<CodeUnit, List<CodeUnit>> childrenByParent = new ConcurrentHashMap<>(); // package-private for testing
    final Map<CodeUnit, String> signatures = new ConcurrentHashMap<>(); // package-private for testing
    private final IProject project;

    private record FileAnalysisResult(List<CodeUnit> topLevelCUs, Map<CodeUnit, List<CodeUnit>> children, Map<CodeUnit, String> signatures) {}

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
                        var analysisResult = analyzeFileDeclarations(pf, localParser);
                        if (!analysisResult.topLevelCUs().isEmpty() || !analysisResult.signatures().isEmpty()) {
                            topLevelDeclarations.put(pf, Collections.unmodifiableList(new ArrayList<>(analysisResult.topLevelCUs())));
                            // Merge children, ensuring lists are unmodifiable and free of duplicates.
                            analysisResult.children().forEach((parentCU, newChildCUs) -> {
                                childrenByParent.compute(parentCU, (p, existingChildCUs) -> {
                                    if (existingChildCUs == null) {
                                        // Ensure newChildCUs itself is a list of unique CUs if it comes from localChildren
                                        // localChildren lists are already managed to avoid duplicates by the fix below.
                                        return Collections.unmodifiableList(new ArrayList<>(newChildCUs));
                                    }
                                    // Merge, avoiding duplicates. existingChildCUs is already unmodifiable.
                                    List<CodeUnit> combined = new ArrayList<>(existingChildCUs);
                                    for (CodeUnit newKid : newChildCUs) {
                                        if (!combined.contains(newKid)) {
                                            combined.add(newKid);
                                        }
                                    }
                                    // If no new kids were added that weren't already there, can return original existingKids
                                    if (combined.size() == existingChildCUs.size()) {
                                        boolean changed = false; // Check if order or content actually changed before creating new list
                                        for(int i=0; i<combined.size(); ++i) { if (!combined.get(i).equals(existingChildCUs.get(i))) {changed=true; break;}}
                                        if (!changed) return existingChildCUs;
                                    }
                                    return Collections.unmodifiableList(combined);
                                });
                            });
                            signatures.putAll(analysisResult.signatures());
                            log.trace("Processed file {}: {} top-level CUs, {} signatures, {} parent-child relationships.",
                                      pf, analysisResult.topLevelCUs().size(), analysisResult.signatures().size(), analysisResult.children().size());
                        } else {
                            log.trace("analyzeFileDeclarations returned empty result for file: {}", pf);
                        }
                    } catch (Exception e) {
                        log.warn("Error analyzing {}: {}", pf, e, e);
                    }
                });
    }

    /* ---------- IAnalyzer ---------- */
    @Override public boolean isEmpty() { return topLevelDeclarations.isEmpty() && signatures.isEmpty(); }

    @Override
    public List<CodeUnit> getAllClasses() {
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
    public Set<CodeUnit> getClassesInFile(ProjectFile file) {
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
        String signature = signatures.get(cu);
        if (signature == null) {
            log.warn("Missing signature for CU: {}. Skipping in skeleton reconstruction.", cu);
            return;
        }

        // Apply indent to each line of the signature
        String[] signatureLines = signature.split("\n", -1); // Use -1 limit to keep trailing empty strings if necessary
        for (String line : signatureLines) {
            sb.append(indent).append(line).append('\n');
        }
        // If signature itself was empty or only newlines, the above loop might not add a final newline.
        // However, signatures are expected to have content. If signature ends with \n, split might produce an empty string at the end.
        // The logic implies signature is one or more content lines, each terminated by \n by the loop.

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
    public scala.Option<String> getSkeleton(String fqName) {
        Optional<CodeUnit> cuOpt = signatures.keySet().stream()
                                          .filter(c -> c.fqName().equals(fqName))
                                          .findFirst();
        if (cuOpt.isPresent()) {
            String skeleton = reconstructFullSkeleton(cuOpt.get());
            log.trace("getSkeleton: fqName='{}', found=true", fqName);
            return scala.Option.apply(skeleton);
        }
        log.trace("getSkeleton: fqName='{}', found=false", fqName);
        return scala.Option.empty();
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
    protected abstract SkeletonType getSkeletonTypeForCapture(String captureName);

    /**
     * Translate a capture produced by the query into a {@link CodeUnit}.
     * Return {@code null} to ignore this capture.
     */
    protected abstract CodeUnit createCodeUnit(ProjectFile file,
                                               String captureName,
                                               String simpleName,
                                               String namespaceName,
                                               String classChain);

    /**
     * Checks if the given AST node represents a class-like declaration
     * (e.g., class, interface, struct) in the specific language.
     * Subclasses must implement this to guide class chain extraction.
     *
     * @param node The TSNode to check.
     * @return true if the node is a class-like declaration, false otherwise.
     */
    protected abstract boolean isClassLike(TSNode node);

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
        Map<CodeUnit, String> localSignatures = new HashMap<>();
        Map<String, CodeUnit> localCuByFqName = new HashMap<>(); // For parent lookup within the file

        TSTree tree = localParser.parseString(null, src);
        TSNode rootNode = tree.getRootNode();
        if (rootNode.isNull()) {
            log.warn("Parsing failed or produced null root node for {}", file);
            return new FileAnalysisResult(List.of(), Map.of(), Map.of());
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

            String namespace = extractNamespace(node, currentRootNode, src);
            List<String> enclosingClassNames = new ArrayList<>();
            TSNode tempParent = node.getParent();
            while (tempParent != null && !tempParent.isNull() && !tempParent.equals(currentRootNode)) {
                if (isClassLike(tempParent)) {
                    extractSimpleName(tempParent, src).ifPresent(parentName -> {
                        if (!parentName.isBlank()) enclosingClassNames.add(0, parentName);
                    });
                }
                tempParent = tempParent.getParent();
            }
            String classChain = String.join("$", enclosingClassNames);
            log.trace("Computed classChain for simpleName='{}': '{}'", simpleName, classChain);

            CodeUnit cu = createCodeUnit(file, primaryCaptureName, simpleName, namespace, classChain);
            log.trace("createCodeUnit returned: {}", cu);

            if (cu == null) {
                log.warn("createCodeUnit returned null for node {} ({})", simpleName, primaryCaptureName);
                continue;
            }

            String signature = buildSignatureString(node, simpleName, src, primaryCaptureName);
            log.trace("Built signature for '{}': [{}]", simpleName, signature == null ? "NULL" : signature.isBlank() ? "BLANK" : signature.lines().findFirst().orElse("EMPTY"));

            if (file.getFileName().equals("vars.py") && primaryCaptureName.equals("field.definition")) {
                log.info("[vars.py DEBUG] Processing entry for vars.py field: Node Type='{}', SimpleName='{}', CaptureName='{}', Namespace='{}', ClassChain='{}'", 
                         node.getType(), simpleName, primaryCaptureName, namespace, classChain);
                log.info("[vars.py DEBUG] CU created: {}, Signature: [{}]", cu, signature == null ? "NULL_SIG" : signature.isBlank() ? "BLANK_SIG" : signature.lines().findFirst().orElse("EMPTY_SIG"));
            }

            if (signature == null || signature.isBlank()) {
                log.warn("buildSignatureString returned empty/null for node {} ({})", simpleName, primaryCaptureName);
                continue;
            }
            
            // Handle potential duplicates (e.g. JS export and direct lexical declaration)
            // Prefer exported version if a CU already exists.
            CodeUnit existingCU = localCuByFqName.get(cu.fqName());
            if (existingCU != null) {
                String existingSignature = localSignatures.get(existingCU);
                boolean newIsExported = signature.trim().startsWith("export");
                boolean oldIsExported = (existingSignature != null) && existingSignature.trim().startsWith("export");

                if (newIsExported && !oldIsExported) {
                    log.warn("Replacing non-exported CU/signature for {} with EXPORTED version.", cu.fqName());
                    // Update will happen below. If logic needs explicit removal of old from localChildren's values, add here.
                } else if (!newIsExported && oldIsExported) {
                    log.trace("Keeping existing EXPORTED CU/signature for {}. Discarding new non-exported.", cu.fqName());
                    continue; // Skip adding/processing this non-exported duplicate
                } else {
                    log.warn("Duplicate CU processing for {}. Signatures might differ. Keeping first encountered or based on export status.", cu.fqName());
                    //Potentially skip if signatures are identical or other tie-breaking logic. For now, allow overwrite if not export-related.
                }
            }


            localSignatures.put(cu, signature);
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

        log.trace("Finished analyzing {}: found {} top-level CUs, {} total signatures, {} parent entries.",
                  file, localTopLevelCUs.size(), localSignatures.size(), localChildren.size());
        return new FileAnalysisResult(localTopLevelCUs, localChildren, localSignatures);
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
        // String baseIndent = computeIndentation(definitionNode, src); // Original indentation is not prepended to stored signature.

        TSNode effectiveDefinitionNode = definitionNode; // Default to the given node

        // Handle Python's decorated_definition structure specifically for decorators
        if ("decorated_definition".equals(definitionNode.getType()) && project.getAnalyzerLanguage() == Language.PYTHON) {
            for (int i = 0; i < definitionNode.getNamedChildCount(); i++) {
                TSNode child = definitionNode.getNamedChild(i);
                if ("decorator".equals(child.getType())) {
                    signatureLines.add(textSlice(child, src).stripLeading());
                } else if ("function_definition".equals(child.getType()) || "class_definition".equals(child.getType())) {
                    // In Python, the 'definition' field of a decorated_definition holds the actual func/class def.
                    // Check if the query gave us decorated_definition but named a function_definition inside it.
                    // The primaryCaptureName would be function.definition in that case.
                    // Use the actual function_definition node for building the main signature part.
                    effectiveDefinitionNode = child; 
                }
            }
            // If the @function.name capture was from the inner function_definition, simpleName is already correct.
            // effectiveDefinitionNode is now set to the actual function_definition.
        } else {
            // General decorator handling for other languages (or non-decorated Python items)
            List<TSNode> decorators = getPrecedingDecorators(definitionNode, src);
            for (TSNode decoratorNode : decorators) {
                signatureLines.add(textSlice(decoratorNode, src).stripLeading());
            }
        }

        SkeletonType skeletonType = getSkeletonTypeForCapture(primaryCaptureName);
        switch (skeletonType) {
            case CLASS_LIKE: {
                String exportPrefix = getVisibilityPrefix(effectiveDefinitionNode, src);
                TSNode bodyNode = effectiveDefinitionNode.getChildByFieldName("body");
                String classSignatureText;
                if (bodyNode != null && !bodyNode.isNull()) {
                    classSignatureText = textSlice(effectiveDefinitionNode.getStartByte(), bodyNode.getStartByte(), src).stripTrailing();
                } else { 
                    classSignatureText = textSlice(effectiveDefinitionNode, src);
                    if (classSignatureText.endsWith("{")) classSignatureText = classSignatureText.substring(0, classSignatureText.length() - 1).stripTrailing();
                    else if (classSignatureText.endsWith(";")) classSignatureText = classSignatureText.substring(0, classSignatureText.length() - 1).stripTrailing();
                }
                String headerLine = renderClassHeader(effectiveDefinitionNode, src, exportPrefix, classSignatureText, "");
                if (headerLine != null && !headerLine.isBlank()) signatureLines.add(headerLine);
                break;
            }
            case FUNCTION_LIKE:
                buildFunctionSkeleton(effectiveDefinitionNode, Optional.of(simpleName), src, "", signatureLines);
                break;
            case FIELD_LIKE: {
                String exportPrefix = getVisibilityPrefix(definitionNode, src);
                signatureLines.add(exportPrefix + textSlice(definitionNode, src).stripLeading().strip());
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
