package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterPython; // Import the specific language class

import java.nio.file.Files;
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
    protected CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName) {
        // Calculate proper package name based on directory structure
        String packageName = computePythonPackageName(file);
        
        // Create fully qualified name using package name
        String fqName = packageName + "." + simpleName;
        
        // Map the capture type to the appropriate CodeUnit type
        return switch (captureName) {
            case "class.definition" -> CodeUnit.cls(file, fqName);
            case "function.definition" -> CodeUnit.fn(file, fqName);
            default -> {
                // Log or handle unexpected captures if necessary, but returning null ignores them
                yield null;
            }
        };
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

    /**
     * Find the Python package root by walking up the directory tree.
     * @param file The file to get the package for
     * @return The package name based on directory structure, __init__.py files, and the filename (without extension)
     */
    private String computePythonPackageName(ProjectFile file) {
        var absPath = file.absPath();
        var projectRoot = getProject().getRoot();
        var parent = absPath.getParent();
        var packagePath = parent;
        var lastValidPackagePath = parent;
        
        // Get filename without .py extension to include in package
        var filename = absPath.getFileName().toString();
        if (filename.endsWith(".py")) {
            filename = filename.substring(0, filename.length() - 3);
        }

        // Walk up the directory tree until we reach project root or find no more __init__.py files
        while (parent != null && !parent.equals(projectRoot)) {
            if (Files.exists(parent.resolve("__init__.py"))) {
                lastValidPackagePath = parent;
            }
            parent = parent.getParent();
        }

        // If no __init__.py was found, use the file's direct parent directory
        String packageName;
        if (lastValidPackagePath.equals(packagePath)) {
            packageName = lastValidPackagePath.getFileName().toString();
        } else {
            // Calculate relative path from last valid package path to project root
            var relPath = projectRoot.relativize(lastValidPackagePath);
            // Convert path separators to dots for package name
            packageName = relPath.toString().replace('/', '.').replace('\\', '.');
        }
        
        // Append the filename to the package
        return packageName + "." + filename;
    }
}
