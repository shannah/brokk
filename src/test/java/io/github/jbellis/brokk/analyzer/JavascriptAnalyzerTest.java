package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.git.IGitRepo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;


public final class JavascriptAnalyzerTest {

    /**
     * Lightweight IProject implementation for unit-testing Tree-sitter analyzers.
     */
    final static class TestProject implements IProject {
        private final Path root;
        private final io.github.jbellis.brokk.analyzer.Language language; // Use Brokk's Language enum

        TestProject(Path root, io.github.jbellis.brokk.analyzer.Language language) {
            this.root     = root;
            this.language = language;
        }

        @Override
        public io.github.jbellis.brokk.analyzer.Language getAnalyzerLanguage() { // Use Brokk's Language enum
            return language;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public IGitRepo getRepo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(p -> new ProjectFile(root, root.relativize(p)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                System.err.printf("ERROR (TestProject.getAllFiles): walk failed on %s: %s%n",
                                  root, e.getMessage());
                e.printStackTrace(System.err);
                return Collections.emptySet();
            }
        }
    }

    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    static TestProject createTestProject(String subDir, io.github.jbellis.brokk.analyzer.Language lang) { // Use Brokk's Language enum
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    /* -------------------- JavaScript / JSX -------------------- */

    @Test
    void testJavascriptJsxSkeletons() {
        TestProject project = createTestProject("testcode-js", io.github.jbellis.brokk.analyzer.Language.JAVASCRIPT);
        IAnalyzer ana = new JavascriptAnalyzer(project);
        assertInstanceOf(JavascriptAnalyzer.class, ana);

        JavascriptAnalyzer analyzer = (JavascriptAnalyzer) ana;
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed JS/JSX files");

        ProjectFile jsxFile = new ProjectFile(project.getRoot(), "Hello.jsx");
        var skelJsx = analyzer.getSkeletons(jsxFile);
        assertFalse(skelJsx.isEmpty(), "Skeletons map for Hello.jsx should not be empty. Found: " + skelJsx.keySet().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        var jsxClass = CodeUnit.cls(jsxFile, "", "JsxClass");
        var jsxArrowFn = CodeUnit.fn(jsxFile, "", "JsxArrowFnComponent");
        var localJsxArrowFn = CodeUnit.fn(jsxFile, "", "LocalJsxArrowFn");
        var plainJsxFunc = CodeUnit.fn(jsxFile, "", "PlainJsxFunc");

        assertTrue(skelJsx.containsKey(jsxClass), "Skeleton map should contain JsxClass. Skeletons: " + skelJsx.keySet());
        assertTrue(skelJsx.containsKey(jsxArrowFn), "Skeleton map should contain JsxArrowFnComponent. Skeletons: " + skelJsx.keySet());
        assertTrue(skelJsx.containsKey(localJsxArrowFn), "Skeleton map should contain LocalJsxArrowFn. Skeletons: " + skelJsx.keySet());
        assertTrue(skelJsx.containsKey(plainJsxFunc), "Skeleton map should contain PlainJsxFunc. Skeletons: " + skelJsx.keySet());

        String expectedJsxClassSkeleton = """
        export class JsxClass {
          function render() ...
        }
        """.stripIndent();
        assertEquals(expectedJsxClassSkeleton.trim(), skelJsx.get(jsxClass).trim(), "JsxClass skeleton mismatch in Hello.jsx.");


        String expectedExportedArrowFnSkeleton = """
        export JsxArrowFnComponent({ name }) => ...
        """.stripIndent();
        assertEquals(expectedExportedArrowFnSkeleton.trim(), skelJsx.get(jsxArrowFn).trim(), "JsxArrowFnComponent skeleton mismatch");

        String expectedLocalArrowFnSkeleton = """
        LocalJsxArrowFn() => ...
        """.stripIndent();
        assertEquals(expectedLocalArrowFnSkeleton.trim(), skelJsx.get(localJsxArrowFn).trim(), "LocalJsxArrowFn skeleton mismatch");

        String expectedPlainJsxFuncSkeleton = """
        function PlainJsxFunc() ...
        """.stripIndent();
        assertEquals(expectedPlainJsxFuncSkeleton.trim(), skelJsx.get(plainJsxFunc).trim(), "PlainJsxFunc skeleton mismatch");

        Set<CodeUnit> declarationsInJsx = analyzer.getDeclarationsInFile(jsxFile);
        assertTrue(declarationsInJsx.contains(jsxClass), "getDeclarationsInFile mismatch for Hello.jsx: missing jsxClass. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(jsxArrowFn), "getDeclarationsInFile mismatch for Hello.jsx: missing jsxArrowFn. Found: " + declarationsInJsx);
        // Add other expected CUs like localJsxArrowFn, plainJsxFunc, and JsxClass.render
        assertTrue(declarationsInJsx.contains(localJsxArrowFn), "getDeclarationsInFile mismatch for Hello.jsx: missing localJsxArrowFn. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(plainJsxFunc), "getDeclarationsInFile mismatch for Hello.jsx: missing plainJsxFunc. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(CodeUnit.fn(jsxFile, "", "JsxClass.render")), "getDeclarationsInFile mismatch for Hello.jsx: missing JsxClass.render. Found: " + declarationsInJsx);

        assertEquals(expectedExportedArrowFnSkeleton.trim(), analyzer.getSkeleton(jsxArrowFn.fqName()).get().trim(), "getSkeleton mismatch for JsxArrowFnComponent FQ name");

        ProjectFile jsFile = new ProjectFile(project.getRoot(), "Hello.js");
        var skelJs = analyzer.getSkeletons(jsFile);
        assertFalse(skelJs.isEmpty(), "Skeletons map for Hello.js should not be empty.");

        var helloClass = CodeUnit.cls(jsFile, "", "Hello");
        var utilFunc = CodeUnit.fn(jsFile, "", "util");

        assertTrue(skelJs.containsKey(helloClass), "Skeleton map should contain Hello class from Hello.js");
        assertTrue(skelJs.containsKey(utilFunc), "Skeleton map should contain util function from Hello.js");

        String expectedHelloClassSkeleton = """
        export class Hello {
          function greet() ...
        }
        """.stripIndent();
        assertEquals(expectedHelloClassSkeleton.trim(), skelJs.get(helloClass).trim(), "Hello class skeleton mismatch in Hello.js.");


        String expectedUtilFuncSkeleton = """
        export function util() ...
        """.stripIndent();
        assertEquals(expectedUtilFuncSkeleton.trim(), skelJs.get(utilFunc).trim());
    }

    @Test
    void testJavascriptGetSymbols() {
        TestProject project = createTestProject("testcode-js", Language.JAVASCRIPT);
        JavascriptAnalyzer analyzer = new JavascriptAnalyzer(project);
        assertFalse(analyzer.isEmpty(), "Analyzer should not be empty after processing JS files");

        ProjectFile helloJsFile = new ProjectFile(project.getRoot(), "Hello.js");
        ProjectFile helloJsxFile = new ProjectFile(project.getRoot(), "Hello.jsx");
        ProjectFile varsJsFile = new ProjectFile(project.getRoot(), "Vars.js");

        // Define CodeUnits relevant to the test
        // These must match the CUs created by the analyzer internally for the maps to be hit correctly.
        CodeUnit helloClass = CodeUnit.cls(helloJsFile, "", "Hello"); // From Hello.js
        CodeUnit jsxArrowFn = CodeUnit.fn(helloJsxFile, "", "JsxArrowFnComponent"); // From Hello.jsx
        CodeUnit topConstJs = CodeUnit.field(varsJsFile, "", "_module_.TOP_CONST_JS"); // From Vars.js

        Set<CodeUnit> sources = Set.of(helloClass, jsxArrowFn, topConstJs);
        Set<String> actualSymbols = analyzer.getSymbols(sources);

        // Expected symbols:
        // "Hello" from helloClass itself
        // "greet" from the greet() method, which is a child of helloClass
        // "JsxArrowFnComponent" from jsxArrowFn itself
        // "TOP_CONST_JS" from topConstJs itself (after stripping _module_.)
        Set<String> expectedSymbols = Set.of(
                "Hello",
                "greet",
                "JsxArrowFnComponent",
                "TOP_CONST_JS"
        );
        assertEquals(expectedSymbols, actualSymbols, "Symbols mismatch for combined sources.");

        // Test with an empty set of sources
        Set<String> emptySymbols = analyzer.getSymbols(Set.of());
        assertTrue(emptySymbols.isEmpty(), "getSymbols with empty sources should return an empty set.");

        // Test with a single top-level function CU
        CodeUnit utilFunc = CodeUnit.fn(helloJsFile, "", "util"); // from Hello.js
        Set<String> utilSymbols = analyzer.getSymbols(Set.of(utilFunc));
        assertEquals(Set.of("util"), utilSymbols, "Symbols mismatch for util function.");

        // Test with a single class CU from a JSX file (should include its methods)
        CodeUnit jsxClass = CodeUnit.cls(helloJsxFile, "", "JsxClass");
        // Expected: "JsxClass" from the class itself, "render" from its method
        Set<String> jsxClassSymbols = analyzer.getSymbols(Set.of(jsxClass));
        assertEquals(Set.of("JsxClass", "render"), jsxClassSymbols, "Symbols mismatch for JsxClass.");

        // Test with a non-exported top-level variable
        CodeUnit localVarJs = CodeUnit.field(varsJsFile, "", "_module_.localVarJs");
        Set<String> localVarSymbols = analyzer.getSymbols(Set.of(localVarJs));
        assertEquals(Set.of("localVarJs"), localVarSymbols, "Symbols mismatch for localVarJs.");

        // Test with multiple sources from the same file
        CodeUnit plainJsxFunc = CodeUnit.fn(helloJsxFile, "", "PlainJsxFunc");
        Set<CodeUnit> jsxSources = Set.of(jsxClass, plainJsxFunc);
        Set<String> jsxCombinedSymbols = analyzer.getSymbols(jsxSources);
        assertEquals(Set.of("JsxClass", "render", "PlainJsxFunc"), jsxCombinedSymbols, "Symbols mismatch for combined JSX sources.");
    }

    @Test
    void testJavascriptTopLevelVariables() {
        TestProject project = createTestProject("testcode-js", Language.JAVASCRIPT);
        IAnalyzer ana = new JavascriptAnalyzer(project);
        assertInstanceOf(JavascriptAnalyzer.class, ana);
        JavascriptAnalyzer analyzer = (JavascriptAnalyzer) ana;

        ProjectFile varsJsFile = new ProjectFile(project.getRoot(), "Vars.js");
        var skelVars = analyzer.getSkeletons(varsJsFile);

        assertFalse(skelVars.isEmpty(), "Skeletons map for Vars.js should not be empty. Found: " + skelVars.keySet());

        CodeUnit topConstJsCU = CodeUnit.field(varsJsFile, "", "_module_.TOP_CONST_JS");
        CodeUnit localVarJsCU = CodeUnit.field(varsJsFile, "", "_module_.localVarJs");

        assertTrue(skelVars.containsKey(topConstJsCU), "Skeletons map should contain _module_.TOP_CONST_JS. Found: " + skelVars.keySet());
        assertEquals("export const TOP_CONST_JS = 123", skelVars.get(topConstJsCU).strip());

        assertTrue(skelVars.containsKey(localVarJsCU), "Skeletons map should contain _module_.localVarJs. Found: " + skelVars.keySet());
        assertEquals("let localVarJs = \"abc\"", skelVars.get(localVarJsCU).strip());

        // Ensure these are not mistaken for classes
        Set<CodeUnit> declarationsInVarsJs = analyzer.getDeclarationsInFile(varsJsFile);
        assertTrue(declarationsInVarsJs.contains(topConstJsCU), "_module_.TOP_CONST_JS should be in declarations list for Vars.js. Found: " + declarationsInVarsJs);
        assertFalse(topConstJsCU.isClass(), "_module_.TOP_CONST_JS CU should not be a class.");
        assertTrue(declarationsInVarsJs.contains(localVarJsCU), "_module_.localVarJs should be in declarations list for Vars.js. Found: " + declarationsInVarsJs);
        assertFalse(localVarJsCU.isClass(), "_module_.localVarJs CU should not be a class.");
    }
}
