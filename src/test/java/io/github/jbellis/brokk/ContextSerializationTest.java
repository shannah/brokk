package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import dev.langchain4j.data.message.ChatMessage;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.jbellis.brokk.prompts.EditBlockConflictsParser;
import io.github.jbellis.brokk.prompts.EditBlockParser;

public class ContextSerializationTest {
    @TempDir
    Path tempDir;
    private IContextManager mockContextManager;

    @BeforeEach
    void setup() {
        // Setup mock context manager
        mockContextManager = new IContextManager() {
        };
    }

    @Test
    void testBasicContextSerialization() throws Exception {
        // Create a context with minimal state
        Context context = new Context(mockContextManager, 5);

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify non-transient fields were preserved
        assertEquals(context.getAutoContextFileCount(), deserialized.getAutoContextFileCount());
        assertEquals(context.editableFiles.size(), deserialized.editableFiles.size());
        assertEquals(context.readonlyFiles.size(), deserialized.readonlyFiles.size());
        assertEquals(context.virtualFragments.size(), deserialized.virtualFragments.size());

        // Most transient fields should be initialized to empty
        assertNull(deserialized.contextManager);
        assertNotNull(deserialized.parsedOutput); // Welcome SessionFragment
        assertNotNull(deserialized.originalContents); // Empty Map
        assertNotNull(deserialized.taskHistory); // Empty List (changed from historyMessages)

        // AutoContext should have been preserved
        assertNotNull(deserialized.getAutoContext());

        // We injected output via SessionFragment, check its formatted text
        var expectedOutputText = """
                <message type=custom>
                  stub
                </message>""".stripIndent();
        assertEquals(expectedOutputText, deserialized.parsedOutput.text().strip());
    }

    @Test
    void testContextWithFragmentsSerialization() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);

        var projectFile = new ProjectFile(repoRoot, "src/main/java/Test.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class Test {}");

        var externalFile = new ExternalFile(tempDir.resolve("external.txt").toAbsolutePath());
        Files.writeString(externalFile.absPath(), "This is external content");

        // Create context with fragments
        Context context = new Context(mockContextManager, 5)
                .addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(projectFile)))
                .addReadonlyFiles(List.of(new ContextFragment.ExternalPathFragment(externalFile)))
                .addVirtualFragment(new ContextFragment.StringFragment("virtual content", "Virtual Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify fragment counts
        assertEquals(1, deserialized.editableFiles.size());
        assertEquals(1, deserialized.readonlyFiles.size());
        assertEquals(1, deserialized.virtualFragments.size());

        // Check paths were properly serialized
        ContextFragment.ProjectPathFragment repoFragment = deserialized.editableFiles.get(0);
        assertEquals(projectFile.toString(), repoFragment.file().toString());
        assertEquals(repoRoot.toString(), repoFragment.file().absPath().getParent().getParent().getParent().getParent().toString());

        ContextFragment.PathFragment externalFragment = deserialized.readonlyFiles.get(0);
        assertEquals(externalFile.toString(), externalFragment.file().toString());

        // Verify the ExternalPathFragment can be read correctly after deserialization
        assertDoesNotThrow(() -> {
            String content = externalFragment.text();
            assertEquals("This is external content", content);
            String formatted = externalFragment.format();
            assertTrue(formatted.contains("external.txt"));
            assertTrue(formatted.contains("This is external content"));
        });
    }

    @Test
    void testAllVirtualFragmentTypes() throws Exception {
        Context context = new Context(mockContextManager, 5);

        // Create mock RepoFile for CodeUnit construction
        ProjectFile mockFile = new ProjectFile(tempDir, "Mock.java");

        // Add examples of each *serializable* VirtualFragment type
        // SearchFragment extends SessionFragment which holds non-serializable ChatMessages, so it's excluded here.
        context = context
                .addVirtualFragment(new ContextFragment.StringFragment("string content", "String Fragment", SyntaxConstants.SYNTAX_STYLE_NONE))
                // .addVirtualFragment(new ContextFragment.SearchFragment("query", "explanation", Set.of(CodeUnit.cls(mockFile, "Test")))) // Cannot serialize ChatMessage
                .addVirtualFragment(new ContextFragment.SkeletonFragment(
                        Map.of(CodeUnit.cls(mockFile, "com.test.Test"), "class Test {}")
                ));

        // Add fragments that use Future
        CompletableFuture<String> descFuture = CompletableFuture.completedFuture("description");
        context = context.addPasteFragment(
                new ContextFragment.PasteTextFragment("paste content", descFuture),
                descFuture
        );

        // Add fragment with usage
        context = context.addVirtualFragment(
                new ContextFragment.UsageFragment("Uses", "Test.method", Set.of(CodeUnit.cls(mockFile, "com.test.Test")), "Test.method()")
        );

        // Add stacktrace fragment
        context = context.addVirtualFragment(
                new ContextFragment.StacktraceFragment(
                        Set.of(CodeUnit.cls(mockFile, "com.test.Test")),
                        "original",
                        "NPE",
                        "code"
                )
        );

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify all serializable fragments were preserved (excluding SearchFragment)
        assertEquals(5, deserialized.virtualFragments.size());

        // Verify fragment types
        var fragmentTypes = deserialized.virtualFragments.stream()
                .map(Object::getClass)
                .toList();

        assertTrue(fragmentTypes.contains(ContextFragment.StringFragment.class));
        // assertTrue(fragmentTypes.contains(ContextFragment.SearchFragment.class)); // Excluded due to serialization issues
        assertTrue(fragmentTypes.contains(ContextFragment.SkeletonFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.PasteTextFragment.class));
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
        Context context = new Context(mockContextManager, 5)
                .setAutoContextFiles(10);

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify autoContextFileCount was preserved
        assertEquals(10, deserialized.getAutoContextFileCount());
        assertTrue(deserialized.isAutoContextEnabled());
        assertNotNull(deserialized.getAutoContext());
    }

    @Test
    void testExternalPathFragmentSerialization() throws Exception {
        // Create an external file
        Path externalPath = tempDir.resolve("serialization-test.txt").toAbsolutePath();
        String testContent = "External file content for serialization test";
        Files.writeString(externalPath, testContent);

        ExternalFile externalFile = new ExternalFile(externalPath);
        ContextFragment.ExternalPathFragment fragment = new ContextFragment.ExternalPathFragment(externalFile);

        // Add to context
        Context context = new Context(mockContextManager, 5)
                .addReadonlyFiles(List.of(fragment));

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify the fragment was properly deserialized
        assertEquals(1, deserialized.readonlyFiles.size());
        ContextFragment.PathFragment deserializedFragment = deserialized.readonlyFiles.get(0);

        // Check if it's the correct type
        assertInstanceOf(ContextFragment.ExternalPathFragment.class, deserializedFragment);

        // Verify path and content
        assertEquals(externalPath.toString(), deserializedFragment.file().toString());

        // Should be able to read the file
        assertEquals(testContent, deserializedFragment.text());

        // Check format method works
        String formatted = deserializedFragment.format();
        assertTrue(formatted.contains("serialization-test.txt"));
        assertTrue(formatted.contains(testContent));
    }

    @Test
    void testRoundTripOfLargeContext() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("largeRepo");
        Files.createDirectories(repoRoot);

        // Create many files
        List<ContextFragment.ProjectPathFragment> editableFiles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ProjectFile file = new ProjectFile(repoRoot, "src/main/java/Test" + i + ".java");
            Files.createDirectories(file.absPath().getParent());
            Files.writeString(file.absPath(), "public class Test" + i + " {}");
            editableFiles.add(new ContextFragment.ProjectPathFragment(file));
        }

        // Create many virtual fragments
        List<ContextFragment.VirtualFragment> virtualFragments = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            virtualFragments.add(new ContextFragment.StringFragment("content " + i, "Fragment " + i, SyntaxConstants.SYNTAX_STYLE_NONE));
        }

        Context context = new Context(mockContextManager, 50);

        // Add all fragments
        context = context.addEditableFiles(editableFiles);
        for (var fragment : virtualFragments) {
            context = context.addVirtualFragment(fragment);
        }

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify counts
        assertEquals(20, deserialized.editableFiles.size());
        assertEquals(15, deserialized.virtualFragments.size());

        // Check content of fragments
        for (int i = 0; i < 15; i++) {
            assertTrue(deserialized.virtualFragments.get(i).text().contains("content"));
            assertTrue(deserialized.virtualFragments.get(i).description().contains("Fragment"));
        }
    }

    @Test
    void testTaskHistorySerialization() throws Exception {
        // Create context
        Context context = new Context(mockContextManager, 5);

        // Create sample chat messages for a full session (User + AI)
        List<ChatMessage> sessionMessages = List.of(
                dev.langchain4j.data.message.UserMessage.from("What is the capital of France?"),
                dev.langchain4j.data.message.AiMessage.from("The capital of France is Paris.")
        );

        // Add history using fromSession, which extracts the first UserMessage as description
        // Provide dummy original contents and parsed output as they are not the focus here
        var originalContents = Map.<ProjectFile, String>of();
        var parsedOutput = new ContextFragment.TaskFragment(sessionMessages, "Test Task");
        Future<String> action = CompletableFuture.completedFuture("Test Task");
        var result = new SessionResult("What is the capital of France?", parsedOutput, Map.of(), new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS));
        var taskEntry = context.createTaskEntry(result);
        context = context.addHistoryEntry(taskEntry, parsedOutput, action, originalContents);

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify task history count
        assertEquals(1, deserialized.getTaskHistory().size(), "Deserialized context should have one task history entry.");
        TaskEntry deserializedTask = deserialized.getTaskHistory().getFirst();

        // Verify TaskMessages content (log field contains the messages)
        assertNotNull(deserializedTask.log(), "Task log should not be null after deserialization.");
        // Compare the messages list within the TaskFragment
        assertEquals(sessionMessages, deserializedTask.log().messages(), "Task log messages should contain the original session messages.");
        // Check the content of the first message specifically if needed
        assertTrue(deserializedTask.log().messages().getFirst() instanceof dev.langchain4j.data.message.UserMessage, "First message should be a UserMessage.");
        assertEquals("What is the capital of France?", ((dev.langchain4j.data.message.UserMessage) deserializedTask.log().messages().getFirst()).singleText(), "First message content should match.");
            assertNull(deserializedTask.summary(), "Task should not be summarized (summary field should be null).");

            // Verify the parser instance (should be the default EditBlockParser.instance)
            assertInstanceOf(ContextFragment.TaskFragment.class, deserializedTask.log());
            assertSame(EditBlockParser.instance, ((ContextFragment.TaskFragment) deserializedTask.log()).parser(), "Parser should be EditBlockParser.instance after deserialization");
        }

        @Test
        void testTaskFragmentWithConflictsParserSerialization() throws Exception {
            // Create context
            Context context = new Context(mockContextManager, 5);

            // Sample messages
            List<ChatMessage> sessionMessages = List.of(
                    dev.langchain4j.data.message.UserMessage.from("Request needing conflict parser"),
                    dev.langchain4j.data.message.AiMessage.from("Response with conflict markers")
            );

            // Create TaskFragment using EditBlockConflictsParser
            var conflictParser = EditBlockConflictsParser.instance;
            var taskFragment = new ContextFragment.TaskFragment(conflictParser, sessionMessages, "Conflict Task");

            // Add history entry
            Future<String> action = CompletableFuture.completedFuture("Conflict Task");
            var result = new SessionResult("Request needing conflict parser", taskFragment, Map.of(), new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS));
            var taskEntry = context.createTaskEntry(result);
            context = context.addHistoryEntry(taskEntry, taskFragment, action, Map.of());

            // Serialize and deserialize
            byte[] serialized = Context.serialize(context);
            Context deserialized = Context.deserialize(serialized, "stub");

            // Verify task history count
            assertEquals(1, deserialized.getTaskHistory().size(), "Deserialized context should have one task history entry.");
            TaskEntry deserializedTask = deserialized.getTaskHistory().getFirst();

            // Verify the parser instance (should be EditBlockConflictsParser.instance)
            assertNotNull(deserializedTask.log(), "Task log (TaskFragment) should not be null.");
            assertInstanceOf(ContextFragment.TaskFragment.class, deserializedTask.log());
            assertSame(EditBlockConflictsParser.instance, ((ContextFragment.TaskFragment) deserializedTask.log()).parser(), "Parser should be EditBlockConflictsParser.instance after deserialization");

            // Verify other TaskFragment details
            assertEquals(sessionMessages, deserializedTask.log().messages(), "Task log messages should match.");
            assertEquals("Conflict Task", ((ContextFragment.TaskFragment) deserializedTask.log()).description(), "Session name should match.");
        }
    }
