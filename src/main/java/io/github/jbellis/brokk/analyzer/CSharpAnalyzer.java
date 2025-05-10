package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import java.util.Collections;
import java.util.Set;

public final class CSharpAnalyzer extends TreeSitterAnalyzer {
    protected static final Logger log = LoggerFactory.getLogger(CSharpAnalyzer.class);

    private static final TSLanguage CS_LANGUAGE = new TreeSitterCSharp();

    public CSharpAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, excludedFiles);
        log.debug("CSharpAnalyzer: Constructor called for project: {} with {} excluded files", project, excludedFiles.size());
    }

    public CSharpAnalyzer(IProject project) {
        this(project, Collections.emptySet());
        log.debug("CSharpAnalyzer: Constructor called for project: {}", project);
    }

    @Override
    protected TSLanguage getTSLanguage() {
        log.trace("CSharpAnalyzer: getTSLanguage() returning cached: {}", CS_LANGUAGE.getClass().getName());
        return CS_LANGUAGE;
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
        // The 'indent' parameter is now "" when called from buildSignatureString.
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
        return signature + " " + bodyPlaceholder(); // Do not prepend indent here
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        // The 'baseIndent' parameter is now "" when called from buildSignatureString.
        // Stored signature should be unindented.
        return signatureText + " {"; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        // C# classes, interfaces, structs, enums, namespaces use { }
        return cu.isClass() ? "}" : ""; // Simplified: assuming only classes need closers for now
    }

    // isClassLike is now implemented in the base class using LanguageSyntaxProfile

    // buildClassMemberSkeletons is no longer directly called for parent skeleton string generation.

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return new LanguageSyntaxProfile(
                Set.of("class_declaration", "interface_declaration", "struct_declaration", "record_declaration", "record_struct_declaration"),
                Set.of("method_declaration", "constructor_declaration", "local_function_statement"), // Added local_function_statement
                Set.of("field_declaration", "property_declaration", "event_field_declaration"), // Added event_field_declaration
                Set.of("attribute_list"),
                "name", // identifierFieldName
                "body", // bodyFieldName
                "parameters", // parametersFieldName
                "type"  // returnTypeFieldName (used by method_declaration for its return type node)
        );
    }
}
