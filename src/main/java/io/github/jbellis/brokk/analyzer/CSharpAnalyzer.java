package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterCSharp; // Import the specific language class

import java.util.Set;

public final class CSharpAnalyzer extends TreeSitterAnalyzer {

    public CSharpAnalyzer(IProject project) {
        super(project);
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return new TreeSitterCSharp(); // Instantiate the bonede language object
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/c_sharp.scm";
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName) {
        // C# query captures: class.name, interface.name, method.name, field.name, property.name
        // Declaration captures: class.declaration, interface.declaration, method.declaration, field.declaration, property.declaration, constructor.declaration
        // Ignore @annotation explicitly
        return switch (captureName) {
            case "class.declaration", "interface.declaration" -> CodeUnit.cls(file, simpleName);
            case "method.declaration" -> CodeUnit.fn(file, simpleName);
            // Constructor name extraction might need special handling if base class logic isn't enough,
            // but for now, assume it works or can be improved there. Tree-sitter usually identifies constructor nodes.
            // If the base class `extractSimpleName` can't find 'identifier', we might need to override it or adjust the query.
            // Append a unique suffix to the constructor's simple name to avoid collision with class name in the map.
            case "constructor.declaration" -> CodeUnit.fn(file, simpleName + ".<init>");
            case "field.declaration", "property.declaration" -> CodeUnit.field(file, simpleName);
            // Ignore other captures like *.name or annotations handled by getIgnoredCaptures
            default -> null;
        };
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // C# query explicitly captures attributes/annotations to ignore them
        return Set.of("annotation");
    }

    @Override
    protected String bodyPlaceholder() {
        return "{ … }";
    }
}
