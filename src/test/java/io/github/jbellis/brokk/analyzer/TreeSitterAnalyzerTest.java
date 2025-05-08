package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.git.IGitRepo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;


public final class TreeSitterAnalyzerTest {

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
    private static TestProject createTestProject(String subDir, io.github.jbellis.brokk.analyzer.Language lang) { // Use Brokk's Language enum
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    /* -------------------- Python -------------------- */

    @Test
    void testPythonInitializationAndSkeletons() {
        TestProject project = createTestProject("testcode-py", io.github.jbellis.brokk.analyzer.Language.PYTHON); // Use Brokk's Language enum
        IAnalyzer ana = new PythonAnalyzer(project);
        assertInstanceOf(PythonAnalyzer.class, ana);
        PythonAnalyzer analyzer = (PythonAnalyzer) ana; // Cast to PythonAnalyzer
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed Python files");

        ProjectFile fileA = new ProjectFile(project.getRoot(), "a/A.py");
        // Skeletons are now reconstructed. We check CodeUnits first.
        var classesInFileA = analyzer.getDeclarationsInFile(fileA);
        var classA_CU = CodeUnit.cls(fileA, "a", "A");
        assertTrue(classesInFileA.contains(classA_CU), "File A should contain class A.");

        var topLevelDeclsInA = ((TreeSitterAnalyzer)analyzer).topLevelDeclarations.get(fileA); // Accessing internal for test validation
        assertNotNull(topLevelDeclsInA, "Top level declarations for file A should exist.");

        var funcA_CU = CodeUnit.fn(fileA, "a", "A.funcA");
        assertTrue(topLevelDeclsInA.contains(funcA_CU), "File A should contain function funcA as top-level.");
        assertTrue(topLevelDeclsInA.contains(classA_CU), "File A should contain class A as top-level.");

        // Test reconstructed skeletons
        var skelA = analyzer.getSkeletons(fileA);
        assertFalse(skelA.isEmpty(), "Reconstructed skeletons map for file A should not be empty.");

        assertTrue(skelA.containsKey(classA_CU), "Skeleton map should contain class A.");
        String classASkeleton = skelA.get(classA_CU);
        assertTrue(classASkeleton.contains("class A:"), "Class A skeleton content error.");

        assertTrue(skelA.containsKey(funcA_CU), "Skeleton map should contain function funcA.");
        String funcASkeleton = skelA.get(funcA_CU);
        assertTrue(funcASkeleton.contains("def funcA():"), "funcA skeleton content error.");

        var funcASummary = "def funcA(): ...\n";
        assertEquals(funcASummary.trim(), funcASkeleton.trim());

        // Replaced text block with standard string concatenation due to persistent compiler errors
        var classASummary = "class A:\n" +
                            "  def __init__(self): ...\n" +
                            "  def method1(self) -> None: ...\n" +
                            "  def method2(self, input_str: str, other_input: int = None) -> str: ...\n" +
                            "  def method3(self) -> Callable[[int], int]: ...\n" +
                            "  @staticmethod\n" +
                            "  def method4(foo: float, bar: int) -> int: ...\n" +
                            "  def method5(self) -> None: ...\n" +
                            "  def method6(self) -> None: ...\n";
        // Note: PythonAnalyzer.getLanguageSpecificIndent() might affect exact string match if not "  "
        assertEquals(classASummary.trim(), classASkeleton.trim(), "Class A skeleton mismatch.");

        assertEquals(Set.of(classA_CU), analyzer.getDeclarationsInFile(fileA), "getClassesInFile mismatch for file A");
        assertTrue(analyzer.getSkeleton(funcA_CU.fqName()).isPresent(), "Skeleton for funcA_CU should be present");
        assertEquals(funcASummary.trim(), analyzer.getSkeleton(funcA_CU.fqName()).get().trim(), "getSkeleton mismatch for funcA");
    }

    @Test
    void testPythonTopLevelVariables() {
        TestProject project = createTestProject("testcode-py", io.github.jbellis.brokk.analyzer.Language.PYTHON);
        IAnalyzer ana = new PythonAnalyzer(project);
        assertInstanceOf(PythonAnalyzer.class, ana);
        PythonAnalyzer analyzer = (PythonAnalyzer) ana;

        ProjectFile varsPyFile = new ProjectFile(project.getRoot(), "vars.py");
        var skelVars = analyzer.getSkeletons(varsPyFile);

        // vars.py content:
        // TOP_VALUE = 99
        // export_like = "not really"

        // For Python top-level fields, shortName is now "moduleName.fieldName"
        CodeUnit topValueCU = CodeUnit.field(varsPyFile, "", "vars.TOP_VALUE");
        CodeUnit exportLikeCU = CodeUnit.field(varsPyFile, "", "vars.export_like");

        assertTrue(skelVars.containsKey(topValueCU), "Skeletons map should contain vars.TOP_VALUE. Found: " + skelVars.keySet());
        assertEquals("TOP_VALUE = 99", skelVars.get(topValueCU).strip());

        assertTrue(skelVars.containsKey(exportLikeCU), "Skeletons map should contain export_like. Found: " + skelVars.keySet());
        assertEquals("export_like = \"not really\"", skelVars.get(exportLikeCU).strip()); // Note: Query captures the whole assignment

        // Ensure these are not mistaken for classes
        assertFalse(analyzer.getDeclarationsInFile(varsPyFile).contains(topValueCU), "TOP_VALUE should not be in classes list.");
        assertFalse(analyzer.getDeclarationsInFile(varsPyFile).contains(exportLikeCU), "export_like should not be in classes list.");

        // Verify that getTopLevelDeclarations includes these fields
        var topLevelDecls = ((TreeSitterAnalyzer)analyzer).topLevelDeclarations.get(varsPyFile);
        assertNotNull(topLevelDecls, "Top level declarations for vars.py should exist.");
        assertTrue(topLevelDecls.contains(topValueCU), "Top-level declarations should include TOP_VALUE.");
        assertTrue(topLevelDecls.contains(exportLikeCU), "Top-level declarations should include export_like.");
    }


    /* -------------------- C# -------------------- */

    @Test
    void testCSharpInitializationAndSkeletons() {
        TestProject project = createTestProject("testcode-cs", io.github.jbellis.brokk.analyzer.Language.C_SHARP);
        IAnalyzer ana = new CSharpAnalyzer(project);
        assertInstanceOf(CSharpAnalyzer.class, ana);

        CSharpAnalyzer analyzer = (CSharpAnalyzer) ana;
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed C# files");

        ProjectFile fileA = new ProjectFile(project.getRoot(), "A.cs");
        var classA_CU = CodeUnit.cls(fileA, "TestNamespace", "A");

        assertTrue(analyzer.getDeclarationsInFile(fileA).contains(classA_CU), "File A.cs should contain class A.");

        var skelA = analyzer.getSkeletons(fileA);
        assertFalse(skelA.isEmpty(), "Skeletons map for file A.cs should not be empty.");
        assertTrue(skelA.containsKey(classA_CU), "Skeleton map should contain top-level class A. Skeletons found: " + skelA.keySet());

        String classASkeleton = skelA.get(classA_CU);
        assertNotNull(classASkeleton, "Skeleton for class A should not be null.");
        assertTrue(classASkeleton.trim().startsWith("public class A {") || classASkeleton.trim().startsWith("public class A\n{"), // Allow for newline before brace
                   "Class A skeleton should start with 'public class A {'. Actual: '" + classASkeleton.trim() + "'");

        String expectedClassASkeleton = """
        public class A {
          public int MyField;
          public string MyProperty { get; set; }
          public void MethodA() { … }
          public A() { … }
        }
        """.stripIndent();
        java.util.function.Function<String, String> normalize = (String s) -> s.lines().map(String::strip).collect(Collectors.joining("\n"));
        assertEquals(normalize.apply(expectedClassASkeleton), normalize.apply(classASkeleton), "Class A skeleton mismatch.");

        // Check that attribute_list capture does not result in top-level CodeUnits or signatures
        boolean hasAnnotationSignature = ((TreeSitterAnalyzer)analyzer).signatures.keySet().stream()
            .filter(cu -> cu.source().equals(fileA))
            .anyMatch(cu -> "annotation".equals(cu.shortName()) ||
                            (cu.packageName() != null && cu.packageName().equals("annotation")) ||
                             cu.identifier().startsWith("annotation"));
        assertFalse(hasAnnotationSignature, "No signatures from 'annotation' captures expected.");

        assertEquals(Set.of(classA_CU), analyzer.getDeclarationsInFile(fileA), "getClassesInFile mismatch for file A.");
        var classASkeletonOpt = analyzer.getSkeleton(classA_CU.fqName());
        assertTrue(classASkeletonOpt.isPresent(), "Skeleton for classA fqName '" + classA_CU.fqName() + "' should be found.");
        assertEquals(normalize.apply(classASkeleton), normalize.apply(classASkeletonOpt.get()), "getSkeleton for classA fqName mismatch.");
    }

    @Test
    void testCSharpMixedScopesAndNestedNamespaces() {
        TestProject project = createTestProject("testcode-cs", io.github.jbellis.brokk.analyzer.Language.C_SHARP);
        IAnalyzer ana = new CSharpAnalyzer(project);
        assertInstanceOf(CSharpAnalyzer.class, ana);
        CSharpAnalyzer analyzer = (CSharpAnalyzer) ana;

        ProjectFile mixedScopeFile = new ProjectFile(project.getRoot(), "MixedScope.cs");
        var skelMixed = analyzer.getSkeletons(mixedScopeFile); // Triggers parsing and populates internal maps
        assertFalse(skelMixed.isEmpty(), "Skeletons map for MixedScope.cs should not be empty.");

        CodeUnit topLevelClass = CodeUnit.cls(mixedScopeFile, "", "TopLevelClass");
        CodeUnit myTestAttributeClass = CodeUnit.cls(mixedScopeFile, "", "MyTestAttribute");
        CodeUnit namespacedClass = CodeUnit.cls(mixedScopeFile, "NS1", "NamespacedClass");
        CodeUnit nsInterface = CodeUnit.cls(mixedScopeFile, "NS1", "INamespacedInterface"); // Interfaces are classes for CodeUnit
        CodeUnit topLevelStruct = CodeUnit.cls(mixedScopeFile, "", "TopLevelStruct"); // Structs are classes

        // Check if these CUs are present by querying for their skeletons or inclusion in file classes
        assertTrue(skelMixed.containsKey(topLevelClass), "Skeletons should contain TopLevelClass.");
        assertTrue(skelMixed.containsKey(myTestAttributeClass), "Skeletons should contain MyTestAttribute class.");
        assertTrue(skelMixed.containsKey(namespacedClass), "Skeletons should contain NS1.NamespacedClass.");
        assertTrue(skelMixed.containsKey(nsInterface), "Skeletons should contain NS1.INamespacedInterface.");
        assertTrue(skelMixed.containsKey(topLevelStruct), "Skeletons should contain TopLevelStruct.");

        Set<CodeUnit> expectedClassesMixed = Set.of(topLevelClass, myTestAttributeClass, namespacedClass, nsInterface, topLevelStruct);
        assertEquals(expectedClassesMixed, analyzer.getDeclarationsInFile(mixedScopeFile), "getClassesInFile mismatch for MixedScope.cs");

        ProjectFile nestedNamespacesFile = new ProjectFile(project.getRoot(), "NestedNamespaces.cs");
        var skelNested = analyzer.getSkeletons(nestedNamespacesFile);
        assertFalse(skelNested.isEmpty(), "Skeletons map for NestedNamespaces.cs should not be empty.");

        CodeUnit myNestedClass = CodeUnit.cls(nestedNamespacesFile, "Outer.Inner", "MyNestedClass");
        CodeUnit myNestedInterface = CodeUnit.cls(nestedNamespacesFile, "Outer.Inner", "IMyNestedInterface");
        CodeUnit outerClass = CodeUnit.cls(nestedNamespacesFile, "Outer", "OuterClass");
        CodeUnit anotherClass = CodeUnit.cls(nestedNamespacesFile, "AnotherTopLevelNs", "AnotherClass");

        assertTrue(skelNested.containsKey(myNestedClass), "Skeletons should contain Outer.Inner.MyNestedClass.");
        assertTrue(skelNested.containsKey(myNestedInterface), "Skeletons should contain Outer.Inner.IMyNestedInterface.");
        assertTrue(skelNested.containsKey(outerClass), "Skeletons should contain Outer.OuterClass.");
        assertTrue(skelNested.containsKey(anotherClass), "Skeletons should contain AnotherTopLevelNs.AnotherClass.");

        Set<CodeUnit> expectedClassesNested = Set.of(myNestedClass, myNestedInterface, outerClass, anotherClass);
        assertEquals(expectedClassesNested, analyzer.getDeclarationsInFile(nestedNamespacesFile), "getClassesInFile mismatch for NestedNamespaces.cs");
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

        assertEquals(Set.of(jsxClass), analyzer.getDeclarationsInFile(jsxFile), "getClassesInFile mismatch for Hello.jsx");
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
        Set<CodeUnit> classesInFile = analyzer.getDeclarationsInFile(varsJsFile);
        assertFalse(classesInFile.contains(topConstJsCU), "_module_.TOP_CONST_JS should not be in classes list.");
        assertFalse(classesInFile.contains(localVarJsCU), "_module_.localVarJs should not be in classes list.");
    }
}
