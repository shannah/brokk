package io.github.jbellis.brokk.analyzer;

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


public final class TreeSitterAnalyzerTest {

    /**
     * Lightweight IProject implementation for unit-testing Tree-sitter analyzers.
     */
    final static class TestProject implements IProject {
        private final Path root;
        private final io.github.jbellis.brokk.analyzer.Language language; // Use Brokk's Language enum
        private IAnalyzer analyzer;      // lazy-initialised

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
        public synchronized IAnalyzer getAnalyzer() {
            if (analyzer == null) {
                analyzer = switch (language) { // Use Brokk's Language enum
                    case PYTHON -> new PythonAnalyzer(this);
                    case C_SHARP -> new CSharpAnalyzer(this);
                    // add more languages as needed
                    default      -> throw new IllegalArgumentException(
                            "No TreeSitterAnalyzer registered for: " + language);
                };
            }
            return analyzer;
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

        @Override
        public IAnalyzer getAnalyzerUninterrupted() {
            return getAnalyzer();
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
        IAnalyzer ana = Optional.ofNullable(project.getAnalyzer()).orElseThrow();
        assertInstanceOf(PythonAnalyzer.class, ana);

        TreeSitterAnalyzer analyzer = (TreeSitterAnalyzer) ana;
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed Python files");

        ProjectFile fileA = new ProjectFile(project.getRoot(), "a/A.py");
        var skelA = analyzer.getSkeletons(fileA);
        assertFalse(skelA.isEmpty(), "Skeletons map for file A should not be empty.");

        // Use the 3-parameter factory methods with corrected Python structure:
        // Package path is directory-based (e.g., "a").
        // Class shortName is just the class name ("A"). fqName = "a.A".
        // Function shortName includes module ("A.funcA"). fqName = "a.A.funcA".

        var classA = CodeUnit.cls(fileA, "a", "A"); // package="a", shortName="A"
        assertEquals("A", classA.shortName(), "Class A shortName mismatch");
        assertEquals("a.A", classA.fqName(), "Class A fqName mismatch"); // Corrected fqName
        assertTrue(skelA.containsKey(classA), "Skeleton map should contain class A.");
        assertTrue(skelA.get(classA).contains("class A:"));

        // Function shortName includes module name ("A.funcA")
        var funcA = CodeUnit.fn(fileA, "a", "A.funcA"); // package="a", shortName="A.funcA"
        assertEquals("A.funcA", funcA.shortName(), "Function funcA shortName mismatch"); // Corrected shortName
        assertEquals("a.A.funcA", funcA.fqName(), "Function funcA fqName mismatch"); // fqName remains the same
        assertTrue(skelA.containsKey(funcA), "Skeleton map should contain function funcA.");
        assertTrue(skelA.get(funcA).contains("def funcA():"));


        // Expected skeleton for the top-level function funcA
        var funcASummary = """
        def funcA(): …
        """;
        assertEquals(funcASummary.trim(), skelA.get(funcA).trim());


        // Expected skeleton for the top-level class A
        var classASummary = """
        class A: {
          def __init__(self): …
          def method1(self) -> None: …
          def method2(self, input_str: str, other_input: int = None) -> str: …
          def method3(self) -> Callable[[int], int]: …
          @staticmethod
          def method4(foo: float, bar: int) -> int: …
          def method5(self) -> None: …
          def method6(self) -> None: …
        }""".stripIndent(); // Using stripIndent for cleaner comparison
        assertEquals(classASummary.trim(), skelA.get(classA).trim(), "Class A skeleton mismatch");

        // test getClassesInFile
        assertEquals(Set.of(classA), analyzer.getClassesInFile(fileA), "getClassesInFile mismatch for file A");
        // non-py files are now filtered out during construction, so no need to check ignore.md

        // test getSummary - use fqName() method
        assertEquals(funcASummary.trim(), analyzer.getSkeleton(funcA.fqName()).get(), "getSkeleton mismatch for funcA");
    }


    /* -------------------- C# -------------------- */

    @Test
    void testCSharpInitializationAndSkeletons() {
        TestProject project = createTestProject("testcode-cs", io.github.jbellis.brokk.analyzer.Language.C_SHARP); // Use Brokk's Language enum
        IAnalyzer ana = Optional.ofNullable(project.getAnalyzer()).orElseThrow();
        assertInstanceOf(CSharpAnalyzer.class, ana);

        CSharpAnalyzer analyzer = (CSharpAnalyzer) ana;
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed C# files");

        ProjectFile fileA = new ProjectFile(project.getRoot(), "A.cs");
        var skelA = analyzer.getSkeletons(fileA);
        assertFalse(skelA.isEmpty(), "Skeletons map for file A.cs should not be empty.");

        var classA = CodeUnit.cls(fileA, "TestNamespace", "A"); // Namespace is derived from A.cs
        assertEquals("A", classA.shortName());
        assertEquals("TestNamespace.A", classA.fqName()); // fqName includes the namespace
        assertTrue(skelA.containsKey(classA), "Skeleton map should contain top-level class A. Skeletons found: " + skelA.keySet());
        
        String classASkeleton = skelA.get(classA);
        assertNotNull(classASkeleton, "Skeleton for class A should not be null.");
        assertTrue(classASkeleton.trim().startsWith("public class A"), "Class A skeleton should start with 'public class A'. Actual: '" + classASkeleton.trim() + "'");
        // Deferring detailed skeleton content checks for C# members until skeleton building is C#-aware.
        // For now, the skeleton is minimal: "public class A { }"

        // Ensure attribute_list capture (aliased as "annotation") did not result in a CodeUnit.
        // The getIgnoredCaptures() method in CSharpAnalyzer should prevent this.
        boolean containsAnnotationCaptureAsCodeUnit = skelA.keySet().stream()
                .anyMatch(cu -> "annotation".equals(cu.shortName()) ||
                                (cu.packageName() != null && cu.packageName().equals("annotation")) ||
                                cu.identifier().startsWith("annotation")); // Check common ways it might be named
        assertFalse(containsAnnotationCaptureAsCodeUnit, "No CodeUnits from 'annotation' (attribute_list) captures expected in skeletons.");

        // test getClassesInFile should still correctly identify the top-level class 'A'.
        assertEquals(Set.of(classA), analyzer.getClassesInFile(fileA), "getClassesInFile mismatch for file A. Expected: " + Set.of(classA) + ", Got: " + analyzer.getClassesInFile(fileA));

        // test getSkeleton for the top-level class 'A' using its fully qualified name.
        var classASkeletonOpt = analyzer.getSkeleton(classA.fqName());
        assertTrue(classASkeletonOpt.isDefined(), "Skeleton for classA fqName '" + classA.fqName() + "' should be found.");
        assertEquals(classASkeleton.trim(), classASkeletonOpt.get().trim(), "getSkeleton for classA fqName mismatch.");
    }

    @Test
    void testCSharpMixedScopesAndNestedNamespaces() {
        TestProject project = createTestProject("testcode-cs", io.github.jbellis.brokk.analyzer.Language.C_SHARP);
        IAnalyzer ana = Optional.ofNullable(project.getAnalyzer()).orElseThrow();
        assertInstanceOf(CSharpAnalyzer.class, ana);
        CSharpAnalyzer analyzer = (CSharpAnalyzer) ana;

        // 1. Test MixedScope.cs
        ProjectFile mixedScopeFile = new ProjectFile(project.getRoot(), "MixedScope.cs");
        var skelMixed = analyzer.getSkeletons(mixedScopeFile);
        assertFalse(skelMixed.isEmpty(), "Skeletons map for MixedScope.cs should not be empty.");

        CodeUnit topLevelClass = CodeUnit.cls(mixedScopeFile, "", "TopLevelClass");
        assertEquals("TopLevelClass", topLevelClass.fqName());
        assertTrue(skelMixed.containsKey(topLevelClass), "Skeletons should contain TopLevelClass. Found: " + skelMixed.keySet());

        CodeUnit myTestAttributeClass = CodeUnit.cls(mixedScopeFile, "", "MyTestAttribute");
        assertEquals("MyTestAttribute", myTestAttributeClass.fqName());
        assertTrue(skelMixed.containsKey(myTestAttributeClass), "Skeletons should contain MyTestAttribute class. Found: " + skelMixed.keySet());

        CodeUnit namespacedClass = CodeUnit.cls(mixedScopeFile, "NS1", "NamespacedClass");
        assertEquals("NS1.NamespacedClass", namespacedClass.fqName());
        assertTrue(skelMixed.containsKey(namespacedClass), "Skeletons should contain NS1.NamespacedClass. Found: " + skelMixed.keySet());

        CodeUnit nsInterface = CodeUnit.cls(mixedScopeFile, "NS1", "INamespacedInterface");
        assertEquals("NS1.INamespacedInterface", nsInterface.fqName());
        assertTrue(skelMixed.containsKey(nsInterface), "Skeletons should contain NS1.INamespacedInterface. Found: " + skelMixed.keySet());

        CodeUnit topLevelStruct = CodeUnit.cls(mixedScopeFile, "", "TopLevelStruct");
        assertEquals("TopLevelStruct", topLevelStruct.fqName());
        assertTrue(skelMixed.containsKey(topLevelStruct), "Skeletons should contain TopLevelStruct. Found: " + skelMixed.keySet());

        Set<CodeUnit> expectedClassesMixed = Set.of(topLevelClass, myTestAttributeClass, namespacedClass, nsInterface, topLevelStruct);
        assertEquals(expectedClassesMixed, analyzer.getClassesInFile(mixedScopeFile), "getClassesInFile mismatch for MixedScope.cs");

        // Verify that attribute_list captures (aliased as "annotation") do not create CodeUnits
        boolean containsAnnotationCaptureAsCodeUnitMixed = skelMixed.keySet().stream()
                .anyMatch(cu -> "annotation".equals(cu.shortName()) ||
                                (cu.packageName() != null && cu.packageName().contains("annotation")) ||
                                cu.identifier().startsWith("annotation"));
        assertFalse(containsAnnotationCaptureAsCodeUnitMixed, "No CodeUnits from 'annotation' (attribute_list) captures expected in MixedScope.cs skeletons.");


        // 2. Test NestedNamespaces.cs
        ProjectFile nestedNamespacesFile = new ProjectFile(project.getRoot(), "NestedNamespaces.cs");
        var skelNested = analyzer.getSkeletons(nestedNamespacesFile);
        assertFalse(skelNested.isEmpty(), "Skeletons map for NestedNamespaces.cs should not be empty.");

        CodeUnit myNestedClass = CodeUnit.cls(nestedNamespacesFile, "Outer.Inner", "MyNestedClass");
        assertEquals("Outer.Inner.MyNestedClass", myNestedClass.fqName());
        assertTrue(skelNested.containsKey(myNestedClass), "Skeletons should contain Outer.Inner.MyNestedClass. Found: " + skelNested.keySet());

        CodeUnit myNestedInterface = CodeUnit.cls(nestedNamespacesFile, "Outer.Inner", "IMyNestedInterface");
        assertEquals("Outer.Inner.IMyNestedInterface", myNestedInterface.fqName());
        assertTrue(skelNested.containsKey(myNestedInterface), "Skeletons should contain Outer.Inner.IMyNestedInterface. Found: " + skelNested.keySet());

        CodeUnit outerClass = CodeUnit.cls(nestedNamespacesFile, "Outer", "OuterClass");
        assertEquals("Outer.OuterClass", outerClass.fqName());
        assertTrue(skelNested.containsKey(outerClass), "Skeletons should contain Outer.OuterClass. Found: " + skelNested.keySet());

        CodeUnit anotherClass = CodeUnit.cls(nestedNamespacesFile, "AnotherTopLevelNs", "AnotherClass");
        assertEquals("AnotherTopLevelNs.AnotherClass", anotherClass.fqName());
        assertTrue(skelNested.containsKey(anotherClass), "Skeletons should contain AnotherTopLevelNs.AnotherClass. Found: " + skelNested.keySet());

        Set<CodeUnit> expectedClassesNested = Set.of(myNestedClass, myNestedInterface, outerClass, anotherClass);
        assertEquals(expectedClassesNested, analyzer.getClassesInFile(nestedNamespacesFile), "getClassesInFile mismatch for NestedNamespaces.cs");
    }
}
