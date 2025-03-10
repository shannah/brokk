package io.github.jbellis.brokk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scala.Option;
import scala.Tuple2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class ContextSerializationTest {
    @TempDir
    Path tempDir;
    private IProject mockProject;

    @BeforeEach
    void setup() {
        // Setup mock project
        mockProject = new IProject() {
            @Override
            public IAnalyzer getAnalyzer() {
                return new IAnalyzer() {
                    @Override
                    public Set<CodeUnit> getClassesInFile(RepoFile file) {
                        return Set.of();
                    }

                    @Override
                    public List<Tuple2<String, Double>> getPagerank(Map<String, Double> seedClassWeights, int k, boolean reversed) {
                        return List.of();
                    }
                };
            }

            @Override
            public IAnalyzer getAnalyzerNonBlocking() {
                return getAnalyzer();
            }

            @Override
            public IGitRepo getRepo() {
                return new IGitRepo() {
                    @Override
                    public Path getRoot() {
                        return Path.of(tempDir.toString());
                    }

                    @Override
                    public List<RepoFile> getTrackedFiles() {
                        return List.of();
                    }
                };
            }
        };
    }
    
    @Test
    void testBasicContextSerialization() throws Exception {
        // Create a context with minimal state
        Context context = new Context(mockProject, 5);
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized);
        
        // Verify non-transient fields were preserved
        assertEquals(context.getAutoContextFileCount(), deserialized.getAutoContextFileCount());
        assertEquals(context.editableFiles.size(), deserialized.editableFiles.size());
        assertEquals(context.readonlyFiles.size(), deserialized.readonlyFiles.size());
        assertEquals(context.virtualFragments.size(), deserialized.virtualFragments.size());
        
        // Transient fields should be null or empty
        assertNull(deserialized.project);
        assertNull(deserialized.parsedOutput);
        assertNull(deserialized.originalContents);
        assertNull(deserialized.historyMessages);
    }
    
    @Test
    void testContextWithFragmentsSerialization() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);
        
        RepoFile repoFile = new RepoFile(repoRoot, "src/main/java/Test.java");
        Files.createDirectories(repoFile.absPath().getParent());
        Files.writeString(repoFile.absPath(), "public class Test {}");
        
        ExternalFile externalFile = new ExternalFile(tempDir.resolve("external.txt").toAbsolutePath());
        Files.writeString(externalFile.absPath(), "This is external content");
        
        // Create context with fragments
        Context context = new Context(mockProject, 5)
            .addEditableFiles(List.of(new ContextFragment.RepoPathFragment(repoFile)))
            .addReadonlyFiles(List.of(new ContextFragment.ExternalPathFragment(externalFile)))
            .addVirtualFragment(new ContextFragment.StringFragment("virtual content", "Virtual Fragment"));
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized);
        
        // Verify fragment counts
        assertEquals(1, deserialized.editableFiles.size());
        assertEquals(1, deserialized.readonlyFiles.size());
        assertEquals(1, deserialized.virtualFragments.size());
        
        // Check paths were properly serialized
        ContextFragment.RepoPathFragment repoFragment = deserialized.editableFiles.get(0);
        assertEquals(repoFile.toString(), repoFragment.file().toString());
        assertEquals(repoRoot.toString(), repoFragment.file().absPath().getParent().getParent().getParent().getParent().toString());
        
        ContextFragment.PathFragment externalFragment = deserialized.readonlyFiles.get(0);
        assertEquals(externalFile.toString(), externalFragment.file().toString());
    }
    
    @Test
    void testAllVirtualFragmentTypes() throws Exception {
        Context context = new Context(mockProject, 5);
        
        // Add examples of each VirtualFragment type
        context = context
            .addVirtualFragment(new ContextFragment.StringFragment("string content", "String Fragment"))
            .addVirtualFragment(new ContextFragment.SearchFragment("query", "explanation", Set.of(CodeUnit.cls("Test"))))
            .addVirtualFragment(new ContextFragment.SkeletonFragment(
                List.of("Test"), Set.of(CodeUnit.cls("com.test.Test")), "class Test {}")
            );
        
        // Add fragments that use Future
        CompletableFuture<String> descFuture = CompletableFuture.completedFuture("description");
        context = context.addPasteFragment(
            new ContextFragment.PasteFragment("paste content", descFuture), 
            descFuture
        );
        
        // Add fragment with usage
        context = context.addUsageFragment(
            new ContextFragment.UsageFragment("Test.method", Set.of(CodeUnit.cls("com.test.Test")), "Test.method()")
        );
        
        // Add stacktrace fragment
        context = context.addVirtualFragment(
            new ContextFragment.StacktraceFragment(
                Set.of(CodeUnit.cls("com.test.Test")), 
                "original", 
                "NPE", 
                "code"
            )
        );
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized);
        
        // Verify all fragments were preserved
        assertEquals(6, deserialized.virtualFragments.size());
        
        // Verify fragment types
        List<Class<?>> fragmentTypes = deserialized.virtualFragments.stream()
            .map(Object::getClass)
            .toList();
        
        assertTrue(fragmentTypes.contains(ContextFragment.StringFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.SearchFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.SkeletonFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.PasteFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.UsageFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.StacktraceFragment.class));
        
        // Verify text content for a fragment
        var stringFragment = deserialized.virtualFragments.stream()
            .filter(f -> f instanceof ContextFragment.StringFragment)
            .findFirst()
            .orElseThrow();
        assertEquals("string content", stringFragment.text());
    }
    
    @Test
    void testAutoContextSerialization() throws Exception {
        // Create a context with auto-context
        Context context = new Context(mockProject, 5)
            .setAutoContextFiles(10);
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized);
        
        // Verify autoContextFileCount was preserved
        assertEquals(10, deserialized.getAutoContextFileCount());
        assertTrue(deserialized.isAutoContextEnabled());
    }

    @Test
    void testRoundTripOfLargeContext() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("largeRepo");
        Files.createDirectories(repoRoot);
        
        // Create many files
        List<ContextFragment.RepoPathFragment> editableFiles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            RepoFile file = new RepoFile(repoRoot, "src/main/java/Test" + i + ".java");
            Files.createDirectories(file.absPath().getParent());
            Files.writeString(file.absPath(), "public class Test" + i + " {}");
            editableFiles.add(new ContextFragment.RepoPathFragment(file));
        }
        
        // Create many virtual fragments
        List<ContextFragment.VirtualFragment> virtualFragments = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            virtualFragments.add(new ContextFragment.StringFragment(
                "content " + i, 
                "Fragment " + i
            ));
        }
        
        Context context = new Context(mockProject, 50);
        
        // Add all fragments
        context = context.addEditableFiles(editableFiles);
        for (var fragment : virtualFragments) {
            context = context.addVirtualFragment(fragment);
        }
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized);
        
        // Verify counts
        assertEquals(20, deserialized.editableFiles.size());
        assertEquals(15, deserialized.virtualFragments.size());
        
        // Check content of fragments
        for (int i = 0; i < 15; i++) {
            assertTrue(deserialized.virtualFragments.get(i).text().contains("content"));
            assertTrue(deserialized.virtualFragments.get(i).description().contains("Fragment"));
        }
    }
}
