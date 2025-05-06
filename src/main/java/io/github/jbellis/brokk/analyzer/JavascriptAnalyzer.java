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
        String tsReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? ": " + returnTypeText : "";
        String signature;
        String bodySuffix = " " + bodyPlaceholder(); // bodyPlaceholder() for JS is "..."

        String nodeType = funcNode.getType();
        
        if ("arrow_function".equals(nodeType)) {
             signature = String.format("%s%s%s%s%s =>", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        } else { // Assumes "function_declaration", etc.
             signature = String.format("%s%sfunction %s%s%s", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        }
        return indent + signature + bodySuffix;
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
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signature, String baseIndent) {
        return baseIndent + exportPrefix + signature + " {";
    }

    @Override
    protected String renderClassFooter(TSNode classNode, String src, String baseIndent) {
        return baseIndent + "}";
    }

    @Override
    protected void buildClassMemberSkeletons(TSNode classBodyNode, String src, String memberIndent, List<String> lines) {
        for (int i = 0; i < classBodyNode.getNamedChildCount(); i++) {
            TSNode memberNode = classBodyNode.getNamedChild(i);
            String memberType = memberNode.getType();

            // In JavaScript, class methods are typically 'method_definition'
            // This also correctly handles getter, setter, and async methods if they are 'method_definition'
            if ("method_definition".equals(memberType)) {
                // Decorators for methods/accessors are children of method_definition in estree for stage 3 decorators.
                // Handle decorators if they are part of the memberNode itself, e.g. by getPrecedingDecorators
                // or if renderFunctionDeclaration is made aware.
                // For now, assuming decorators are handled by getPrecedingDecorators if they are siblings,
                // or implicitly by textSlice if they are part of method_definition's signature.
                // Standard JS class methods don't have decorators as separate sibling nodes typically.
                // The current buildFunctionSkeleton handles `async` prefix, and renderFunctionDeclaration for JS builds the signature.
                super.buildFunctionSkeleton(memberNode, Optional.empty(), src, memberIndent, lines);
            } else if (isClassLike(memberNode)) {
                // Recursively build skeleton for nested class-like structures
                this.buildClassSkeleton(memberNode, src, memberIndent, lines);
            } else if ("public_field_definition".equals(memberType) ||
                       "private_field_definition".equals(memberType)) {
                lines.add(memberIndent + textSlice(memberNode, src).strip());
            }
            // TODO: Add other JS-specific class member handling like static blocks if needed.
        }
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
}
