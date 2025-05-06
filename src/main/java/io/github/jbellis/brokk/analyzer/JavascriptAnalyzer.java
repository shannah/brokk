package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJavascript;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class JavascriptAnalyzer extends TreeSitterAnalyzer {
    public JavascriptAnalyzer(IProject project) { super(project); }

    @Override protected TSLanguage getTSLanguage() { return new TreeSitterJavascript(); }

    @Override protected String getQueryResource() { return "treesitter/javascript.scm"; }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String namespaceName, // namespaceName is currently not used by JS specific queries
                                      String classChain)
    {
        var pkg = computePackagePath(file);
        return switch (captureName) {
            case "class.definition" -> {
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                yield CodeUnit.cls(file, pkg, finalShortName);
            }
            case "function.definition" -> {
                String finalShortName;
                if (!classChain.isEmpty()) { // It's a method within a class structure
                    finalShortName = classChain + "." + simpleName;
                } else { // It's a top-level function in the file
                    finalShortName = simpleName;
                }
                yield CodeUnit.fn(file, pkg, finalShortName);
            }
            case "field.definition" -> { // For class fields
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                yield CodeUnit.field(file, pkg, finalShortName);
            }
            default -> {
                log.debug("Ignoring capture in JavascriptAnalyzer: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                yield null;
            }
        };
    }

    @Override protected Set<String> getIgnoredCaptures() { return Set.of(); }

    @Override protected String bodyPlaceholder() { return "..."; }

    @Override
    protected SkeletonType getSkeletonTypeForCapture(String captureName) {
        // The primaryCaptureName from the query is expected to be "class.definition"
        // or "function.definition" for relevant skeleton-producing captures.
        return switch (captureName) {
            case "class.definition" -> SkeletonType.CLASS_LIKE;
            case "function.definition" -> SkeletonType.FUNCTION_LIKE;
            case "field.definition" -> SkeletonType.FIELD_LIKE;
            default -> SkeletonType.UNSUPPORTED;
        };
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String paramsText, String returnTypeText, String indent) {
        // The 'indent' parameter is now "" when called from buildSignatureString.
        String tsReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? ": " + returnTypeText : "";
        String signature;
        String bodySuffix = " " + bodyPlaceholder();

        String nodeType = funcNode.getType();

        if ("arrow_function".equals(nodeType)) {
             signature = String.format("%s%s%s%s%s =>", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        } else { // Assumes "function_declaration", "method_definition" etc.
             signature = String.format("%s%sfunction %s%s%s", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        }
        return signature + bodySuffix; // Do not prepend indent here
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        TSNode parent = node.getParent();
        if (parent != null && !parent.isNull()) {
            // Direct export of class_declaration, function_declaration, etc.
            if ("export_statement".equals(parent.getType())) {
                return "export ";
            }
            // Export of a variable declaration like `export const Foo = ...` or `export let Bar = class ...`
            // `node` would be arrow_function or class_declaration node.
            // Parent is variable_declarator. Grandparent is lexical_declaration. Great-grandparent is export_statement.
            if ("variable_declarator".equals(parent.getType())) {
                TSNode lexicalDeclNode = parent.getParent();
                if (lexicalDeclNode != null && !lexicalDeclNode.isNull() && "lexical_declaration".equals(lexicalDeclNode.getType())) {
                    TSNode exportStatementNode = lexicalDeclNode.getParent();
                    if (exportStatementNode != null && !exportStatementNode.isNull() && "export_statement".equals(exportStatementNode.getType())) {
                        return "export ";
                    }
                }
            }
        }
        return "";
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        // The 'baseIndent' parameter is now "" when called from buildSignatureString.
        // Stored signature should be unindented.
        return exportPrefix + signatureText + " {"; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected boolean isClassLike(TSNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        // JavaScript classes can be declarations or expressions.
        return switch (node.getType()) {
            case "class_declaration", "class_expression", "class" -> true; // "class" for older/generic tree-sitter grammars
            default -> false;
        };
    }

    // buildClassMemberSkeletons is no longer directly called for parent skeleton string generation.
    // If JS needs to identify children not caught by main query for the childrenByParent map,
    // that logic would need to be integrated into analyzeFileDeclarations or a new helper.
    // For now, assume main query captures are sufficient for JS CUs.
}
