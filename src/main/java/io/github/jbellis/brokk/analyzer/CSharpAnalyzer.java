package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class CSharpAnalyzer extends TreeSitterAnalyzer {
    protected static final Logger log = LoggerFactory.getLogger(CSharpAnalyzer.class);

    public CSharpAnalyzer(IProject project) {
        super(project);
        log.debug("CSharpAnalyzer: Constructor called for project: {}", project);
    }

    @Override
    protected TSLanguage getTSLanguage() {
        var lang = new TreeSitterCSharp(); // Instantiate the bonede language object
        log.trace("CSharpAnalyzer: getTSLanguage() returning: {}", lang.getClass().getName());
        return lang;
    }

    @Override
    protected String getQueryResource() {
        var resource = "treesitter/c_sharp.scm";
        log.trace("CSharpAnalyzer: getQueryResource() returning: {}", resource);
        return resource;
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String namespaceName,
                                      String classChain) {
        // C# doesn't have standard package structure like Java/Python based on folders.
        // Namespaces are declared in code. The namespaceName parameter provides this.
        // The classChain parameter is used for Joern-style short name generation.
        String packageName = namespaceName;

        CodeUnit result;
        try {
            result = switch (captureName) {
                case "class.definition", "interface.definition", "struct.definition" -> {
                    String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                    yield CodeUnit.cls(file, packageName, finalShortName);
                }
                case "method.definition" -> {
                    String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "constructor.definition" -> { // simpleName is the class name itself
                    String finalShortName = (classChain.isEmpty() ? "" : classChain + "$") + simpleName + ".<init>";
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "field.definition", "property.definition" -> {
                    String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                    yield CodeUnit.field(file, packageName, finalShortName);
                }
                // Ignore other captures
                default -> {
                    log.warn("Unhandled capture name in CSharpAnalyzer.createCodeUnit: '{}' for simple name '{}', namespace '{}', classChain '{}' in file {}. Returning null.",
                             captureName, simpleName, namespaceName, classChain, file);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("Exception in CSharpAnalyzer.createCodeUnit for capture '{}', name '{}', file '{}', namespace '{}', classChain '{}': {}",
                     captureName, simpleName, file, namespaceName, classChain, e.getMessage(), e);
            return null;
        }
        log.trace("CSharpAnalyzer.createCodeUnit: returning {}", result);
        return result;
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // C# query explicitly captures attributes/annotations to ignore them
        var ignored = Set.of("annotation");
        log.trace("CSharpAnalyzer: getIgnoredCaptures() returning: {}", ignored);
        return ignored;
    }

    @Override
    protected String bodyPlaceholder() {
        var placeholder = "{ … }";
        log.trace("CSharpAnalyzer: bodyPlaceholder() returning: {}", placeholder);
        return placeholder;
    }

    @Override
    protected SkeletonType getSkeletonTypeForCapture(String captureName) {
        return switch (captureName) {
            case "class.definition", "interface.definition", "struct.definition" -> SkeletonType.CLASS_LIKE;
            case "method.definition", "constructor.definition" -> SkeletonType.FUNCTION_LIKE;
            case "field.definition", "property.definition" -> SkeletonType.FIELD_LIKE;
            default -> SkeletonType.UNSUPPORTED;
        };
    }

    // getTopLevelBlockerNodeTypes is removed as the base method in TreeSitterAnalyzer is removed.

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String paramsText, String returnTypeText, String indent) {
        TSNode body = funcNode.getChildByFieldName("body");
        String signature;

        if (body != null && !body.isNull()) {
            signature = textSlice(funcNode.getStartByte(), body.getStartByte(), src).stripTrailing();
        } else {
            TSNode paramsNode = funcNode.getChildByFieldName("parameters");
            if (paramsNode != null && !paramsNode.isNull()) {
                 signature = textSlice(funcNode.getStartByte(), paramsNode.getEndByte(), src).stripTrailing();
            } else {
                 signature = textSlice(funcNode, src).lines().findFirst().orElse("").stripTrailing();
                 log.debug("renderFunctionDeclaration for C# (node type {}): body and params not found, using fallback signature '{}'", funcNode.getType(), signature);
            }
        }
        // C# function declarations in skeletons don't typically include export/async prefixes in the same way JS/Python might.
        // The signature extracted from source already contains visibility modifiers like "public", "async" etc.
        return indent + signature + " " + bodyPlaceholder(); // bodyPlaceholder() for C# is "{ ... }"
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signature, String baseIndent) {
        // exportPrefix is expected to be empty for C# from the default getVisibilityPrefix,
        // as C# visibility modifiers are part of the 'signature' text slice.
        return baseIndent + signature + " {";
    }

    @Override
    protected String renderClassFooter(TSNode classNode, String src, String baseIndent) {
        return baseIndent + "}";
    }

    @Override
    protected void buildClassMemberSkeletons(TSNode classBodyNode, String src, String memberIndent, List<String> lines) {
        // C# class members (methods, properties, fields, nested types) are typically captured by
        // separate query patterns (e.g., method.definition, class.definition) if they are to be
        // summarized as individual CodeUnits or included in skeletons.
        // This method is for summarizing members that are *only* found by iterating the class body
        // and might not be independently captured by the main query (e.g., if we only wanted to list
        // method signatures within a class skeleton without making them full CodeUnits).

        // For C#, the current query captures top-level methods, classes, etc., and `buildClassSkeleton`
        // itself doesn't iterate members to produce detailed inner skeletons beyond the class signature
        // and placeholder. Tests do not expect detailed member summaries *within* the class skeleton string
        // (e.g. listing all methods of a class within its own skeleton).
        // If specific C# members needed to be summarized here (e.g. private helpers not caught by main query),
        // logic similar to Python/JS would be added.
        for (int i = 0; i < classBodyNode.getNamedChildCount(); i++) {
            TSNode memberNode = classBodyNode.getNamedChild(i);
            if (memberNode == null || memberNode.isNull()) {
                continue;
            }

            String memberType = memberNode.getType();

            if (isClassLike(memberNode)) {
                // Recursively build skeleton for nested class-like structures
                this.buildClassSkeleton(memberNode, src, memberIndent, lines);
            } else {
                // Handle other members like methods, constructors
                switch (memberType) {
                    case "method_declaration":
                    case "constructor_declaration":
                        // Delegate to the generic function skeleton builder
                        super.buildFunctionSkeleton(memberNode, Optional.empty(), src, memberIndent, lines);
                        break;
                    case "field_declaration":
                    case "property_declaration":
                    // Potentially also "event_declaration", "event_field_declaration", "indexer_declaration"
                    // if they should be listed in the class skeleton.
                        lines.add(memberIndent + textSlice(memberNode, src).strip());
                        break;
                    default:
                        log.trace("CSharpAnalyzer.buildClassMemberSkeletons: Ignoring member type: {} in class body: {}", memberType, classBodyNode.getType());
                        break;
                }
            }
        }
    }

    @Override
    protected boolean isClassLike(TSNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        return switch (node.getType()) {
            case "class_declaration", "interface_declaration", "struct_declaration", "record_declaration", "record_struct_declaration" -> true;
            default -> false;
        };
    }
}
