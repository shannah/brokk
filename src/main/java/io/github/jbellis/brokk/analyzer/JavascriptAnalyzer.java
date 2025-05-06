package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterJavascript;

import java.util.Set;

public final class JavascriptAnalyzer extends TreeSitterAnalyzer {
    public JavascriptAnalyzer(IProject project) { super(project); }

    @Override protected TSLanguage getTSLanguage() { return new TreeSitterJavascript(); }

    @Override protected String getQueryResource() { return "treesitter/javascript.scm"; }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String namespaceName) // namespaceName is currently not used by JS specific queries
    {
        var pkg = computePackagePath(file);
        return switch (captureName) {
            case "class.definition"    -> CodeUnit.cls(file, pkg, simpleName);
            case "function.definition" -> {
                String finalShortName = simpleName;
                // If pkg is empty (file is in root) and simpleName is not already compound (e.g. from a test),
                // prepend filename to simpleName to satisfy CodeUnit.fn's expectation for non-packaged functions.
                if (pkg.isEmpty() && !simpleName.contains(".")) {
                    String fileName = file.absPath().getFileName().toString();
                    int dotIndex = fileName.lastIndexOf('.');
                    String fileNameWithoutExtension = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
                    finalShortName = fileNameWithoutExtension + "." + simpleName;
                }
                yield CodeUnit.fn(file, pkg, finalShortName);
            }
            default                    -> null;
        };
    }

    @Override protected Set<String> getIgnoredCaptures() { return Set.of(); }

    @Override protected String bodyPlaceholder() { return "..."; }

    // Identical logic to Python’s computePythonPackagePath but without __init__.py check
    private String computePackagePath(ProjectFile file) {
        var rel = getProject().getRoot().relativize(file.absPath().getParent());
        return rel.toString().replace('/', '.').replace('\\', '.');
    }
}
