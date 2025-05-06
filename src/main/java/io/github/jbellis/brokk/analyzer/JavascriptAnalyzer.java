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
                                      String namespaceName)
    {
        // JS uses folder structure as "package" concept, similar to Python.
        var pkg = computePackagePath(file);   // helper below
        return switch (captureName) {
            case "class.definition"    -> CodeUnit.cls(file, pkg, simpleName);
            case "function.definition" -> CodeUnit.fn (file, pkg, simpleName);
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
