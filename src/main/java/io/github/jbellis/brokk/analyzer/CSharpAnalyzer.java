package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import java.util.List;
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
    protected CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName, String namespaceName) {
        // C# doesn't have standard package structure like Java/Python based on folders.
        // Namespaces are declared in code. The namespaceName parameter provides this.
        String packageName = namespaceName;

        // Simple name is the identifier (class name, method name, field name).
        // For methods/fields, the shortName should ideally include the class.
        // For constructors, simpleName is the class name.
        // We need to construct the appropriate shortName based on the context (which isn't fully available here).
        // Let's use simpleName as shortName for now, similar to Python, understanding this might be incomplete for members.
        String shortName = simpleName;

        CodeUnit result = switch (captureName) {
            // Pass packageName and simpleName as shortName
            case "class.definition", "interface.definition", "struct.definition" -> CodeUnit.cls(file, packageName, shortName);
            // Use simpleName (method identifier) as shortName. Class prefix missing.
            case "method.definition" -> CodeUnit.fn(file, packageName, shortName);
            // simpleName is class name. Use "ClassName.<init>" as shortName for constructor function.
            case "constructor.definition" -> CodeUnit.fn(file, packageName, shortName + ".<init>");
            // Use simpleName (field/property identifier) as shortName. Class prefix missing.
            case "field.definition", "property.definition" -> CodeUnit.field(file, packageName, shortName);
            // Ignore other captures
            default -> null;
        };
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
            // Other .definition captures like field.definition, property.definition
            // will correctly fall into UNSUPPORTED, maintaining existing behavior
            // where buildSkeletonString does not generate specific skeletons for them.
            default -> SkeletonType.UNSUPPORTED;
        };
    }

    @Override
    protected Set<String> getTopLevelBlockerNodeTypes() {
        return Set.of(
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
        );
    }

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
        // For instance, to add method summaries:
        /*
        for (int i = 0; i < classBodyNode.getNamedChildCount(); i++) {
            TSNode memberNode = classBodyNode.getNamedChild(i);
            if ("method_declaration".equals(memberNode.getType())) {
                // Potentially call super.buildFunctionSkeleton or a C#-specific variant
                // super.buildFunctionSkeleton(memberNode, Optional.empty(), src, memberIndent, lines);
                // However, this might duplicate work if method_declaration is already a top-level capture.
                // Need to be careful about what this method's responsibility is vs. top-level captures.
            }
        }
        */
        log.trace("CSharpAnalyzer.buildClassMemberSkeletons: Called for Body node: {}. No specific C# member summarization implemented here at this time, as members are typically separate CodeUnits.", classBodyNode.getType());
    }
}
