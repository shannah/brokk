package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPython;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

public final class PythonAnalyzer extends TreeSitterAnalyzer {

    private static final TSLanguage PY_LANGUAGE = new TreeSitterPython();

    public PythonAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, excludedFiles);
    }

    public PythonAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return PY_LANGUAGE;
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/python.scm";
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String namespaceName,
                                      String classChain) {
        // Calculate package path based on directory structure relative to project root
        // The namespaceName parameter is ignored for Python, as its packaging is directory-based.
        // The classChain parameter is used for Joern-style short name generation.
        String packagePath = computePythonPackagePath(file); // e.g., "a" for "a/A.py"

        // Extract module name from filename using the inherited getFileName() method
        String moduleName = file.getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3); // e.g., "A"
        }

        try {
            return switch (captureName) {
                case "class.definition" -> {
                    String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                    yield CodeUnit.cls(file, packagePath, finalShortName);
                }
                case "function.definition" -> {
                    String finalShortName = classChain.isEmpty() ? (moduleName + "." + simpleName) : (classChain + "." + simpleName);
                    yield CodeUnit.fn(file, packagePath, finalShortName);
                }
                case "field.definition" -> { // For class attributes or top-level variables
                    if (file.getFileName().equals("vars.py")) {
                        log.info("[vars.py DEBUG PythonAnalyzer.createCodeUnit] file: {}, captureName: {}, simpleName: {}, namespaceName: {}, classChain: {}, packagePath: {}, moduleName: {}",
                                 file.getFileName(), captureName, simpleName, namespaceName, classChain, packagePath, moduleName);
                    }
                    String finalShortName;
                    if (classChain.isEmpty()) {
                        // For top-level variables, use "moduleName.variableName" to satisfy CodeUnit.field's expectation of a "."
                        // This also makes it consistent with how top-level functions are named (moduleName.funcName)
                        finalShortName = moduleName + "." + simpleName;
                    } else {
                        finalShortName = classChain + "." + simpleName;
                    }
                    yield CodeUnit.field(file, packagePath, finalShortName);
                }
                default -> {
                    // Log or handle unexpected captures if necessary
                    log.debug("Ignoring capture: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
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
        return "...";
    }

    @Override
    protected SkeletonType getSkeletonTypeForCapture(String captureName) {
        return switch (captureName) {
            case "class.definition" -> SkeletonType.CLASS_LIKE;
            case "function.definition" -> SkeletonType.FUNCTION_LIKE;
            case "field.definition" -> SkeletonType.FIELD_LIKE; // For class attributes
            // field.declaration (for self.x=...) is not a primary definition, so won't hit this from main loop
            default -> SkeletonType.UNSUPPORTED;
        };
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String paramsText, String returnTypeText, String indent) {
        String pyReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? " -> " + returnTypeText : "";
        // The 'indent' parameter is now "" when called from buildSignatureString,
        // so it's effectively ignored here for constructing the stored signature.
        String signature = String.format("%s%sdef %s%s%s:", exportPrefix, asyncPrefix, functionName, paramsText, pyReturnTypeSuffix);

        TSNode bodyNode = funcNode.getChildByFieldName("body");
        boolean hasMeaningfulBody = bodyNode != null && !bodyNode.isNull() &&
                                    (bodyNode.getNamedChildCount() > 1 ||
                                     (bodyNode.getNamedChildCount() == 1 && !"pass_statement".equals(bodyNode.getNamedChild(0).getType())));

        if (hasMeaningfulBody) {
            return signature + " " + bodyPlaceholder(); // Do not prepend indent here
        } else {
            return signature; // Do not prepend indent here
        }
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        // The 'baseIndent' parameter is now "" when called from buildSignatureString.
        // Stored signature should be unindented.
        return signatureText; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return ""; // Python uses indentation, no explicit closer for classes/functions
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

    // isClassLike is now implemented in the base class using LanguageSyntaxProfile.
    // buildClassMemberSkeletons is no longer directly called for parent skeleton string generation.

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return new LanguageSyntaxProfile(
                Set.of("class_definition"),
                Set.of("function_definition"),
                Set.of("assignment", "typed_parameter"), // 'assignment' for module/class level, 'typed_parameter' if self.x for instance vars
                Set.of("decorator"),
                "name",        // identifierFieldName (for functions, classes. Fields are handled by query)
                "body",        // bodyFieldName
                "parameters",  // parametersFieldName
                "return_type"  // returnTypeFieldName
        );
    }
}
