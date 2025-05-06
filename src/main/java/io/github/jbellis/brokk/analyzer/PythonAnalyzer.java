package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPython;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class PythonAnalyzer extends TreeSitterAnalyzer {

    public PythonAnalyzer(IProject project) {
        super(project);
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return new TreeSitterPython(); // Instantiate the bonede language object
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/python.scm";
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName, String namespaceName) {
        // Calculate package path based on directory structure relative to project root
        // The namespaceName parameter is ignored for Python, as its packaging is directory-based.
        String packagePath = computePythonPackagePath(file); // e.g., "a" for "a/A.py"

        // Extract module name from filename using the inherited getFileName() method
        String moduleName = file.getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3); // e.g., "A"
        }

        try {
            return switch (captureName) {
                // e.g., file=a/A.py, packagePath=a, simpleName=A
                // -> CodeUnit.cls(file, "a", "A") => fqName="a.A"
                case "class.definition" -> CodeUnit.cls(file, packagePath, simpleName);

                // e.g., file=a/A.py, packagePath=a, simpleName=funcA (top-level function)
                // -> CodeUnit.fn(file, "a", "A.funcA") => fqName="a.A.funcA"
                // Note: We use ModuleName as the "class" context for top-level functions' shortName.
                case "function.definition" -> CodeUnit.fn(file, packagePath, moduleName + "." + simpleName);

                // TODO: Handle methods within classes correctly if the query captures them
                // TODO: Add case for "field.definition" if the query is updated to capture fields

                default -> {
                    // Log or handle unexpected captures if necessary
                    log.debug("Ignoring capture: {} with name: {}", captureName, simpleName);
                    yield null; // Returning null ignores the capture
                }
            };
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create CodeUnit for capture '{}', name '{}', file '{}': {}",
                     captureName, simpleName, file, e.getMessage());
            return null; // Return null on error to avoid breaking the analysis stream
        }
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // Python query uses "@obj (#eq? @obj \"self\")" predicate helper, ignore the @obj capture
        return Set.of("obj");
    }

    @Override
    protected String bodyPlaceholder() {
        return "…";
    }

    @Override
    protected SkeletonType getSkeletonTypeForCapture(String captureName) {
        return switch (captureName) {
            case "class.definition" -> SkeletonType.CLASS_LIKE;
            case "function.definition" -> SkeletonType.FUNCTION_LIKE;
            default -> SkeletonType.UNSUPPORTED;
        };
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String paramsText, String returnTypeText, String indent) {
        String pyReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? " -> " + returnTypeText : "";
        String signature = String.format("%s%sdef %s%s%s:", exportPrefix, asyncPrefix, functionName, paramsText, pyReturnTypeSuffix);

        TSNode bodyNode = funcNode.getChildByFieldName("body");
        boolean hasMeaningfulBody = bodyNode != null && !bodyNode.isNull() &&
                                    (bodyNode.getNamedChildCount() > 1 ||
                                     (bodyNode.getNamedChildCount() == 1 && !"pass_statement".equals(bodyNode.getNamedChild(0).getType())));

        if (hasMeaningfulBody) {
            return indent + signature + " " + bodyPlaceholder();
        } else {
            return indent + signature;
        }
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signature, String baseIndent) {
        // Python's signature from textSlice up to body usually includes the colon.
        // exportPrefix is expected to be empty for Python from the default getVisibilityPrefix.
        return baseIndent + signature;
    }

    @Override
    protected String renderClassFooter(TSNode classNode, String src, String baseIndent) {
        return null; // Python classes do not have a closing brace/keyword
    }

    @Override
    protected void buildClassMemberSkeletons(TSNode classBodyNode, String src, String memberIndent, List<String> lines) {
        for (int i = 0; i < classBodyNode.getNamedChildCount(); i++) {
            TSNode memberNode = classBodyNode.getNamedChild(i);
            String memberType = memberNode.getType();

            if ("function_definition".equals(memberType)) {
                super.buildFunctionSkeleton(memberNode, Optional.empty(), src, memberIndent, lines);
            } else if ("decorated_definition".equals(memberType)) {
                TSNode functionDefNode = null;
                // Add decorators first, then the function definition
                for (int j = 0; j < memberNode.getChildCount(); j++) {
                    TSNode child = memberNode.getChild(j);
                    if (child == null || child.isNull()) continue;
                    if ("decorator".equals(child.getType())) {
                        lines.add(memberIndent + textSlice(child, src));
                    } else if ("function_definition".equals(child.getType())) {
                        functionDefNode = child;
                    }
                }
                if (functionDefNode != null) {
                    // Pass the original decorated_definition node for context if needed by buildFunctionSkeleton,
                    // but the actual function details come from functionDefNode.
                    // However, buildFunctionSkeleton primarily uses the passed funcNode for name, params, body.
                    // For decorators, they are added before this call now.
                    super.buildFunctionSkeleton(functionDefNode, Optional.empty(), src, memberIndent, lines);
                } else {
                    log.warn("decorated_definition node found without an inner function_definition: {}", textSlice(memberNode, src).lines().findFirst().orElse(""));
                }
            } else if ("expression_statement".equals(memberType)) {
                TSNode expr = memberNode.getChild(0);
                if (expr != null && "assignment".equals(expr.getType())) {
                    TSNode left = expr.getChildByFieldName("left");
                    if (left != null && "attribute".equals(left.getType())) {
                        TSNode object = left.getChildByFieldName("object");
                        if (object != null && "identifier".equals(object.getType()) && "self".equals(textSlice(object, src))) {
                            lines.add(memberIndent + textSlice(memberNode, src).strip());
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes the Python package path based on directory structure relative to project root and __init__.py files.
     * Does *not* include the filename/module name itself.
     * @param file The file to get the package path for
     * @return The package path (e.g., "a" for "a/A.py", "" for top-level "B.py")
     */
    private String computePythonPackagePath(ProjectFile file) {
        var absPath = file.absPath();
        var projectRoot = getProject().getRoot();
        var parentDir = absPath.getParent();

        // If the file is directly in the project root, the package path is empty
        if (parentDir == null || parentDir.equals(projectRoot)) {
            return "";
        }

        // Find the effective package root by checking for __init__.py files upwards
        var current = parentDir;
        var packageRoot = projectRoot; // Default to project root if no __init__.py found above

        while (current != null && !current.equals(projectRoot)) {
            if (Files.exists(current.resolve("__init__.py"))) {
                // Found an __init__.py, this directory is part of the package path, continue checking parent
                 packageRoot = current; // Keep track of the highest dir with __init__.py that is *below* parentDir
            } else {
                 // No __init__.py here, the package path effectively stops *below* this directory
                 // If packageRoot is still projectRoot, it means no __init__.py was found at all in the hierarchy.
                 // If packageRoot points to a directory below 'current', that's our limit.
                 break; // Stop searching upwards
            }
             // Special case: If the immediate parent has __init__.py, its parent is the effective root.
             // Need to find the highest directory containing __init__.py *between* projectRoot and parentDir.
            current = current.getParent();
        }


        // Find the highest directory containing __init__.py between project root and the file's parent
        var effectivePackageRoot = projectRoot;
        current = parentDir;
        while (current != null && !current.equals(projectRoot)) {
            if (Files.exists(current.resolve("__init__.py"))) {
                effectivePackageRoot = current; // Found a potential root, keep checking higher
            }
            current = current.getParent();
        }


        // Calculate the relative path from the effective package root's PARENT
        // to the file's parent directory.
        Path rootForRelativize = effectivePackageRoot.equals(projectRoot) ? projectRoot : effectivePackageRoot.getParent();
        if (rootForRelativize == null) rootForRelativize = projectRoot; // Safety for top-level package roots


        // Calculate the path parts that form the package name
        var relPath = rootForRelativize.relativize(parentDir);

        // Convert path separators to dots for package name
        // Handle edge case where parentDir is the rootForRelativize (empty relative path)
        if (relPath.toString().isEmpty()) {
            return "";
        } else {
            return relPath.toString().replace('/', '.').replace('\\', '.');
        }
    }
}
