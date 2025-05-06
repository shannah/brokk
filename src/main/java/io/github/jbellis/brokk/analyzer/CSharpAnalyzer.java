package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterCSharp; // Import the specific language class

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
}
