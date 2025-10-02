package io.github.jbellis.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import io.github.jbellis.brokk.util.HistoryIo;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ContextSerializationTest {
    @TempDir
    Path tempDir;

    private IContextManager mockContextManager;

    @BeforeEach
    void setup() throws IOException {
        mockContextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        // Clear the intern pool before each test to ensure isolation
        FrozenFragment.clearInternPoolForTesting();
        // Reset fragment ID counter for test isolation
        ContextFragment.nextId.set(1);

        // Clean .brokk/sessions directory for session tests
        Path sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        if (Files.exists(sessionsDir)) {
            try (var stream = Files.walk(sessionsDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Log but continue - test isolation is best effort
                    }
                });
            }
        }
    }

    private BufferedImage createTestImage(Color color, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private byte[] imageToBytes(Image image) throws IOException {
        if (image == null) return null;
        BufferedImage bufferedImage = (image instanceof BufferedImage)
                ? (BufferedImage) image
                : new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        if (!(image instanceof BufferedImage)) {
            Graphics2D bGr = bufferedImage.createGraphics();
            bGr.drawImage(image, 0, 0, null);
            bGr.dispose();
        }
        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "PNG", baos);
            return baos.toByteArray();
        }
    }

    // --- Tests for HistoryIo ---

    @Test
    void testWriteReadEmptyHistory() throws IOException {
        var history = new ContextHistory(Context.EMPTY);
        Path zipFile = tempDir.resolve("empty_history.zip");

        HistoryIo.writeZip(history, zipFile);
        assertTrue(Files.exists(zipFile));

        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);
        assertNotNull(loadedHistory);
        assertFalse(loadedHistory.getHistory().isEmpty());
    }

    @Test
    void testReadNonExistentZip() throws IOException {
        Path zipFile = tempDir.resolve("non_existent.zip");
        assertThrows(IOException.class, () -> HistoryIo.readZip(zipFile, mockContextManager));
    }

    @Test
    void testWriteReadHistoryWithSingleContext_NoFragments() throws IOException {
        var history = new ContextHistory(new Context(mockContextManager, "Initial welcome."));

        Path zipFile = tempDir.resolve("single_context_no_fragments.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertEquals(1, loadedHistory.getHistory().size());
        // Further assertions can be added to compare context details if necessary,
        // focusing on serializable aspects.
        // For a "no fragments" context, primarily the task history (welcome message) is relevant.
        Context loadedCtx = loadedHistory.getHistory().get(0);
        assertNull(loadedCtx.getParsedOutput());
    }

    @Test
    void testWriteReadHistoryWithComplexContent() throws Exception {
        // Context 1: Project file, string fragment
        var projectFile1 = new ProjectFile(tempDir, "src/File1.java");
        var context1 = new Context(mockContextManager, "Context 1 started")
                .addPathFragments(List.of(new ContextFragment.ProjectPathFragment(projectFile1, mockContextManager)))
                .addVirtualFragment(new ContextFragment.StringFragment(
                        mockContextManager, "Virtual content 1", "VC1", SyntaxConstants.SYNTAX_STYLE_JAVA));
        ContextHistory originalHistory = new ContextHistory(context1);
        Files.createDirectories(projectFile1.absPath().getParent());
        Files.writeString(projectFile1.absPath(), "public class File1 {}");

        // Context 2: Image fragment, task history
        var image1 = createTestImage(Color.RED, 10, 10);
        var pasteImageFragment1 = new ContextFragment.AnonymousImageFragment(
                mockContextManager, image1, CompletableFuture.completedFuture("Pasted Red Image"));

        var context2 = new Context(mockContextManager, "Context 2 started").addVirtualFragment(pasteImageFragment1);

        List<ChatMessage> taskMessages = List.of(UserMessage.from("User query"), AiMessage.from("AI response"));
        var taskFragment = new ContextFragment.TaskFragment(mockContextManager, taskMessages, "Test Task");
        context2 = context2.addHistoryEntry(
                new TaskEntry(1, taskFragment, null),
                taskFragment,
                CompletableFuture.completedFuture("Action for task"));

        originalHistory.addFrozenContextAndClearRedo(context2.freeze());

        Path zipFile = tempDir.resolve("complex_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Assertions
        assertEquals(
                originalHistory.getHistory().size(), loadedHistory.getHistory().size());

        // Compare Context 1 (which will be frozen)
        Context originalCtx1Frozen = originalHistory.getHistory().get(0); // This is already frozen by ContextHistory
        Context loadedCtx1 = loadedHistory.getHistory().get(0);
        assertContextsEqual(originalCtx1Frozen, loadedCtx1);

        // Compare Context 2 (which will be frozen)
        Context originalCtx2Frozen = originalHistory.getHistory().get(1); // This is already frozen by ContextHistory
        Context loadedCtx2 = loadedHistory.getHistory().get(1);
        assertContextsEqual(originalCtx2Frozen, loadedCtx2);

        // Verify image content from the image fragment in loadedCtx2
        var loadedImageFragmentOpt = loadedCtx2
                .virtualFragments()
                .filter(f -> !f.isText() && "Pasted Red Image".equals(f.description()))
                .findFirst();
        assertTrue(loadedImageFragmentOpt.isPresent(), "Pasted Red Image fragment not found in loaded context 2");
        var loadedImageFragment = loadedImageFragmentOpt.get();

        byte[] imageBytesContent;
        if (loadedImageFragment instanceof FrozenFragment ff) {
            imageBytesContent = ff.imageBytesContent();
        } else if (loadedImageFragment instanceof ContextFragment.AnonymousImageFragment pif) {
            imageBytesContent = imageToBytes(pif.image());
        } else {
            throw new AssertionError("Unexpected fragment type for pasted image: " + loadedImageFragment.getClass());
        }

        assertNotNull(imageBytesContent);
        assertTrue(imageBytesContent.length > 0);
        Image loadedImage = ImageIO.read(new java.io.ByteArrayInputStream(imageBytesContent));
        assertNotNull(loadedImage);
        assertEquals(10, loadedImage.getWidth(null));
        assertEquals(10, loadedImage.getHeight(null));
        // Could do a pixel-by-pixel comparison if necessary
    }

    private void assertContextsEqual(Context expected, Context actual) throws IOException, InterruptedException {
        // Compare all fragments sorted by ID
        var expectedFragments = expected.allFragments()
                .sorted(java.util.Comparator.comparing(ContextFragment::id))
                .toList();
        var actualFragments = actual.allFragments()
                .sorted(java.util.Comparator.comparing(ContextFragment::id))
                .toList();

        assertEquals(expectedFragments.size(), actualFragments.size(), "Fragments count mismatch");
        for (int i = 0; i < expectedFragments.size(); i++) {
            assertContextFragmentsEqual(expectedFragments.get(i), actualFragments.get(i));
        }

        // Compare task history
        assertEquals(expected.getTaskHistory().size(), actual.getTaskHistory().size(), "Task history size mismatch");
        for (int i = 0; i < expected.getTaskHistory().size(); i++) {
            assertTaskEntriesEqual(
                    expected.getTaskHistory().get(i), actual.getTaskHistory().get(i));
        }
    }

    private void assertContextFragmentsEqual(ContextFragment expected, ContextFragment actual)
            throws IOException, InterruptedException {
        assertEquals(expected.id(), actual.id(), "Fragment ID mismatch");
        assertEquals(expected.getType(), actual.getType(), "Fragment type mismatch for ID " + expected.id());
        assertEquals(
                expected.description(), actual.description(), "Fragment description mismatch for ID " + expected.id());
        assertEquals(
                expected.shortDescription(),
                actual.shortDescription(),
                "Fragment shortDescription mismatch for ID " + expected.id());
        assertEquals(expected.isText(), actual.isText(), "Fragment isText mismatch for ID " + expected.id());
        assertEquals(
                expected.syntaxStyle(), actual.syntaxStyle(), "Fragment syntaxStyle mismatch for ID " + expected.id());

        if (expected.isText()) {
            assertEquals(expected.text(), actual.text(), "Fragment text content mismatch for ID " + expected.id());
        } else {
            // For image fragments, compare byte content if both are FrozenFragment or can provide bytes
            if (expected instanceof FrozenFragment expectedFf && actual instanceof FrozenFragment actualFf) {
                assertArrayEquals(
                        expectedFf.imageBytesContent(),
                        actualFf.imageBytesContent(),
                        "FrozenFragment imageBytesContent mismatch for ID " + expected.id());
            } else if (expected.image() != null
                    && actual.image() != null) { // Fallback for non-frozen, if any after freezing
                assertArrayEquals(
                        imageToBytes(expected.image()),
                        imageToBytes(actual.image()),
                        "Fragment image content mismatch for ID " + expected.id());
            }
        }

        // Compare additional serialized top-level methods
        assertEquals(expected.repr(), actual.repr(), "Fragment repr mismatch for ID " + expected.id());

        // Compare files (sources are not snapshotted into FrozenFragment directly)
        assertEquals(
                expected.files().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                actual.files().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                "Fragment files mismatch for ID " + expected.id());

        if (expected instanceof FrozenFragment expectedFf && actual instanceof FrozenFragment actualFf) {
            assertEquals(
                    expectedFf.originalClassName(),
                    actualFf.originalClassName(),
                    "FrozenFragment originalClassName mismatch for ID " + expected.id());
            assertEquals(expectedFf.meta(), actualFf.meta(), "FrozenFragment meta mismatch for ID " + expected.id());
        }
    }

    private void assertTaskEntriesEqual(TaskEntry expected, TaskEntry actual) {
        assertEquals(expected.sequence(), actual.sequence());
        assertEquals(expected.isCompressed(), actual.isCompressed());
        if (expected.isCompressed()) {
            assertEquals(expected.summary(), actual.summary());
        } else {
            assertNotNull(expected.log());
            assertNotNull(actual.log());
            assertEquals(expected.log().description(), actual.log().description());
            assertEquals(
                    expected.log().messages().size(), actual.log().messages().size());
            for (int i = 0; i < expected.log().messages().size(); i++) {
                ChatMessage expectedMsg = expected.log().messages().get(i);
                ChatMessage actualMsg = actual.log().messages().get(i);
                assertEquals(expectedMsg.type(), actualMsg.type());
                assertEquals(Messages.getRepr(expectedMsg), Messages.getRepr(actualMsg));
            }
        }
    }

    @Test
    void testWriteReadHistoryWithSharedImageFragment() throws Exception {
        // Create a shared image
        var sharedImage = createTestImage(Color.BLUE, 8, 8);

        // Create two PasteImageFragments with identical content and description
        // This should result in the same FrozenFragment instance due to interning
        var sharedDescription = "Shared Blue Image";
        var liveImageFrag1 = new ContextFragment.AnonymousImageFragment(
                mockContextManager, sharedImage, CompletableFuture.completedFuture(sharedDescription));
        var liveImageFrag2 = new ContextFragment.AnonymousImageFragment(
                mockContextManager, sharedImage, CompletableFuture.completedFuture(sharedDescription));

        // Context 1 with first image fragment
        var ctx1 = new Context(mockContextManager, "Context 1 with shared image").addVirtualFragment(liveImageFrag1);
        var originalHistory = new ContextHistory(ctx1);

        // Context 2 with second image fragment (same content, should intern to same FrozenFragment)
        var ctx2 = new Context(mockContextManager, "Context 2 with shared image").addVirtualFragment(liveImageFrag2);
        originalHistory.addFrozenContextAndClearRedo(ctx2.freeze());

        // Write to ZIP - this should NOT throw ZipException: duplicate entry
        Path zipFile = tempDir.resolve("shared_image_history.zip");

        // The main test: writeZip should not throw ZipException
        assertDoesNotThrow(() -> HistoryIo.writeZip(originalHistory, zipFile));

        // Read back and verify
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Verify we have 2 contexts
        assertEquals(2, loadedHistory.getHistory().size());

        // Verify both contexts contain the shared image fragment
        var loadedCtx1 = loadedHistory.getHistory().get(0);
        var loadedCtx2 = loadedHistory.getHistory().get(1);

        // Find the image fragments in each context
        var fragment1 = loadedCtx1
                .virtualFragments()
                .filter(f -> !f.isText() && "Shared Blue Image".equals(f.description()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Image fragment not found in loaded context 1"));

        var fragment2 = loadedCtx2
                .virtualFragments()
                .filter(f -> !f.isText() && "Shared Blue Image".equals(f.description()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Image fragment not found in loaded context 2"));

        byte[] imageBytes1, imageBytes2;
        if (fragment1 instanceof FrozenFragment ff1) {
            imageBytes1 = ff1.imageBytesContent();
        } else if (fragment1 instanceof ContextFragment.AnonymousImageFragment pif1) {
            imageBytes1 = imageToBytes(pif1.image());
        } else {
            throw new AssertionError("Unexpected fragment type for image in ctx1: " + fragment1.getClass());
        }

        if (fragment2 instanceof FrozenFragment ff2) {
            imageBytes2 = ff2.imageBytesContent();
        } else if (fragment2 instanceof ContextFragment.AnonymousImageFragment pif2) {
            imageBytes2 = imageToBytes(pif2.image());
        } else {
            throw new AssertionError("Unexpected fragment type for image in ctx2: " + fragment2.getClass());
        }

        // Verify image content
        assertNotNull(imageBytes1);
        assertNotNull(imageBytes2);
        assertTrue(imageBytes1.length > 0);
        assertTrue(imageBytes2.length > 0);

        // Verify the image can be read back
        var reconstructedImage1 = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes1));
        var reconstructedImage2 = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes2));
        assertNotNull(reconstructedImage1);
        assertNotNull(reconstructedImage2);
        assertEquals(8, reconstructedImage1.getWidth());
        assertEquals(8, reconstructedImage1.getHeight());
        assertEquals(8, reconstructedImage2.getWidth());
        assertEquals(8, reconstructedImage2.getHeight());

        // Verify descriptions
        assertEquals(sharedDescription, fragment1.description());
        assertEquals(sharedDescription, fragment2.description());
    }

    @Test
    void testFragmentIdContinuityAfterLoad() throws IOException {
        var projectFile = new ProjectFile(tempDir, "dummy.txt");
        Files.createDirectories(projectFile.absPath().getParent()); // Ensure parent directory exists
        Files.writeString(projectFile.absPath(), "content");

        // ID of ctxFragment will be "1" (String)
        var ctxFragment = new ContextFragment.ProjectPathFragment(projectFile, mockContextManager);
        // ID of strFragment will be a hash string
        var strFragment = new ContextFragment.StringFragment(
                mockContextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

        var context = new Context(mockContextManager, "Initial")
                .addPathFragments(List.of(ctxFragment))
                .addVirtualFragment(strFragment);
        var history = new ContextHistory(context);

        Path zipFile = tempDir.resolve("id_continuity_history.zip");
        HistoryIo.writeZip(history, zipFile);

        // Save the next available numeric ID *before* loading, then load.
        // Loading process (fragment constructors) will update ContextFragment.nextId.
        // For this test, we want to see what the next available numeric ID *was* before any new fragment creations
        // post-load.
        // ContextFragment.getCurrentMaxId() gives the *next* ID to be used.
        // After loading, ContextFragment.getCurrentMaxId() should be correctly set based on the max numeric ID found.
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        int maxNumericIdInLoadedHistory = 0;
        for (Context loadedCtx : loadedHistory.getHistory()) {
            for (ContextFragment frag : loadedCtx.allFragments().toList()) {
                try {
                    // Only consider numeric IDs from dynamic fragments
                    int numericId = Integer.parseInt(frag.id());
                    if (numericId > maxNumericIdInLoadedHistory) {
                        maxNumericIdInLoadedHistory = numericId;
                    }
                } catch (NumberFormatException e) {
                    // Non-numeric ID (hash), ignore for max numeric ID calculation
                }
            }
        }

        // The nextId counter should be at least maxNumericIdInLoadedHistory + 1.
        // If no numeric IDs were found (e.g. all fragments were content-hashed or history was empty),
        // then getCurrentMaxId() would be whatever it was set to initially (e.g. 1, or higher if other tests ran before
        // without reset)
        // or what it became after loading any initial numeric IDs from other fragments.
        int nextAvailableNumericId = ContextFragment.getCurrentMaxId();
        if (maxNumericIdInLoadedHistory > 0) {
            assertTrue(
                    nextAvailableNumericId > maxNumericIdInLoadedHistory,
                    "ContextFragment.nextId (numeric counter) should be greater than the max numeric ID found in loaded fragments.");
        } else {
            // If no numeric IDs, nextAvailableNumericId should be at least 1 (or whatever it was reset to)
            assertTrue(nextAvailableNumericId >= 1, "ContextFragment.nextId should be at least 1.");
        }

        // Create a new *dynamic* fragment; it should get a string representation of `nextAvailableNumericId`
        var newDynamicFragment = new ContextFragment.ProjectPathFragment(
                new ProjectFile(tempDir, "new_dynamic.txt"), mockContextManager);
        assertEquals(
                String.valueOf(nextAvailableNumericId),
                newDynamicFragment.id(),
                "New dynamic fragment should get the expected next numeric ID as a string.");
        assertEquals(
                nextAvailableNumericId + 1,
                ContextFragment.getCurrentMaxId(),
                "ContextFragment.nextId (numeric counter) should increment after new dynamic fragment creation.");
    }

    @Test
    void testActionPersistenceAcrossSerializationRoundTrip() throws Exception {
        var context1 = new Context(mockContextManager, "Initial context");

        // Create context with a completed action
        var projectFile = new ProjectFile(tempDir, "test.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class Test {}");
        var fragment = new ContextFragment.ProjectPathFragment(projectFile, mockContextManager);

        var updatedContext1 = context1.addPathFragments(List.of(fragment));
        var history = new ContextHistory(updatedContext1);

        // Create context with a slow-resolving action (simulates async operation)
        var slowFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000); // 1 second delay
                return "Slow operation completed";
            } catch (InterruptedException e) {
                return "Interrupted";
            }
        });

        var context2 = new Context(mockContextManager, "Second context").withAction(slowFuture);
        history.addFrozenContextAndClearRedo(context2.freeze());

        // Create context with a very slow action that should timeout
        var timeoutFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(10000); // 10 second delay - longer than 5s timeout
                return "This should timeout";
            } catch (InterruptedException e) {
                return "Interrupted";
            }
        });

        var context3 = new Context(mockContextManager, "Third context").withAction(timeoutFuture);
        history.addFrozenContextAndClearRedo(context3.freeze());

        // Wait for the slow future to complete before serialization
        Thread.sleep(1500);

        // Serialize to ZIP
        Path zipFile = tempDir.resolve("action_persistence_test.zip");
        HistoryIo.writeZip(history, zipFile);

        // Deserialize from ZIP
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Verify we have the same number of contexts
        assertEquals(3, loadedHistory.getHistory().size());

        // Verify action descriptions are preserved
        var loadedContext1 = loadedHistory.getHistory().get(0);
        var loadedContext2 = loadedHistory.getHistory().get(1);
        var loadedContext3 = loadedHistory.getHistory().get(2);

        // First context should have the edit action preserved
        assertEquals("Edit test.java", loadedContext1.getAction());

        // Second context should have preserved the completed slow action
        assertEquals("Slow operation completed", loadedContext2.getAction());

        // Third context should show timeout message since it took longer than 5s
        assertEquals("(Summary Unavailable)", loadedContext3.getAction());

        // Verify that the actions are immediately available (completed futures)
        assertTrue(loadedContext1.action.isDone());
        assertTrue(loadedContext2.action.isDone());
        assertTrue(loadedContext3.action.isDone());
    }

    @Test
    void testFragmentInterningDuringDeserialization() throws IOException {
        var context1 = new Context(mockContextManager, "Context 1");
        var projectFile = new ProjectFile(tempDir, "shared.txt");
        Files.writeString(projectFile.absPath(), "shared content");

        // Live ProjectPathFragment (dynamic)
        var liveProjectPathFragment = new ContextFragment.ProjectPathFragment(projectFile, mockContextManager);

        // Live StringFragment (non-dynamic, content-hashed)
        var liveStringFragment = new ContextFragment.StringFragment(
                mockContextManager,
                "unique string fragment content for interning test",
                "StringFragDesc",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        String stringFragmentContentHashId = liveStringFragment.id();

        // Context 1
        var updatedContext1 =
                context1.addPathFragments(List.of(liveProjectPathFragment)).addVirtualFragment(liveStringFragment);
        var history = new ContextHistory(updatedContext1);

        // Context 2 also uses the same live instances
        var context2 = new Context(mockContextManager, "Context 2")
                .addPathFragments(List.of(liveProjectPathFragment))
                .addVirtualFragment(liveStringFragment);
        history.addFrozenContextAndClearRedo(context2.freeze());

        Path zipFile = tempDir.resolve("interning_test_history.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertEquals(2, loadedHistory.getHistory().size());
        Context loadedCtx1 = loadedHistory.getHistory().get(0);
        Context loadedCtx2 = loadedHistory.getHistory().get(1);

        // Verify ProjectPathFragment (which becomes FrozenFragment)
        // After freezeOnly(), liveProjectPathFragment is turned into a FrozenFragment.
        // Both contexts will reference the *same instance* of this FrozenFragment due to interning by content hash.
        var frozenPathFrag1 = loadedCtx1
                .allFragments()
                .filter(f -> f instanceof FrozenFragment
                        && ((FrozenFragment) f)
                                .originalClassName()
                                .equals(ContextFragment.ProjectPathFragment.class.getName()))
                .map(f -> (FrozenFragment) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Frozen ProjectPathFragment not found in loadedCtx1"));

        var frozenPathFrag2 = loadedCtx2
                .allFragments()
                .filter(f -> f instanceof FrozenFragment
                        && ((FrozenFragment) f)
                                .originalClassName()
                                .equals(ContextFragment.ProjectPathFragment.class.getName()))
                .map(f -> (FrozenFragment) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Frozen ProjectPathFragment not found in loadedCtx2"));

        assertSame(
                frozenPathFrag1,
                frozenPathFrag2,
                "Frozen versions of the same dynamic ProjectPathFragment should be the same instance after deserialization and interning.");
        // Verify meta for ProjectPathFragment
        assertEquals(projectFile.getRoot().toString(), frozenPathFrag1.meta().get("repoRoot"));
        assertEquals(projectFile.getRelPath().toString(), frozenPathFrag1.meta().get("relPath"));

        // Verify StringFragment (which remains StringFragment, non-dynamic, content-hashed ID)
        var loadedStringFrag1 = loadedCtx1
                .virtualFragments()
                .filter(f -> f instanceof ContextFragment.StringFragment
                        && java.util.Objects.equals(f.id(), stringFragmentContentHashId))
                .map(f -> (ContextFragment.StringFragment) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shared StringFragment not found in loadedCtx1"));

        var loadedStringFrag2 = loadedCtx2
                .virtualFragments()
                .filter(f -> f instanceof ContextFragment.StringFragment
                        && java.util.Objects.equals(f.id(), stringFragmentContentHashId))
                .map(f -> (ContextFragment.StringFragment) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shared StringFragment not found in loadedCtx2"));

        assertSame(
                loadedStringFrag1,
                loadedStringFrag2,
                "StringFragments with the same content-hash ID should be the same instance after deserialization.");
        assertEquals("unique string fragment content for interning test", loadedStringFrag1.text());

        /* ---------- shared TaskFragment via TaskEntry ---------- */
        var taskMessages = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var sharedTaskFragment = new ContextFragment.TaskFragment(
                mockContextManager, taskMessages, "Shared Task Log"); // Content-hashed ID
        String sharedTaskFragmentId = sharedTaskFragment.id();

        var ctxWithTask1 = new Context(mockContextManager, "CtxTask1");
        var taskEntry = new TaskEntry(1, sharedTaskFragment, null);

        var updatedCtxWithTask1 = ctxWithTask1.addHistoryEntry(
                taskEntry, sharedTaskFragment, CompletableFuture.completedFuture("action1"));
        var origHistoryWithTask = new ContextHistory(updatedCtxWithTask1);

        var ctxWithTask2 = new Context(mockContextManager, "CtxTask2")
                .addHistoryEntry(taskEntry, sharedTaskFragment, CompletableFuture.completedFuture("action2"));
        origHistoryWithTask.addFrozenContextAndClearRedo(ctxWithTask2.freeze());

        Path taskZipFile = tempDir.resolve("interning_task_history.zip");
        HistoryIo.writeZip(origHistoryWithTask, taskZipFile);
        ContextHistory loadedHistoryWithTask = HistoryIo.readZip(taskZipFile, mockContextManager);

        var loadedTaskCtx1 = loadedHistoryWithTask.getHistory().get(0);
        var loadedTaskCtx2 = loadedHistoryWithTask.getHistory().get(1);

        var taskLog1 = loadedTaskCtx1.getTaskHistory().get(0).log();
        var taskLog2 = loadedTaskCtx2.getTaskHistory().get(0).log();

        assertNotNull(taskLog1);
        assertNotNull(taskLog2);
        assertEquals(sharedTaskFragmentId, taskLog1.id(), "TaskLog1 ID mismatch");
        assertEquals(sharedTaskFragmentId, taskLog2.id(), "TaskLog2 ID mismatch");
        assertSame(taskLog1, taskLog2, "Shared TaskFragment logs should be the same instance after deserialization.");
    }

    // --- Tests for individual fragment type round-trips ---

    private CodeUnit createTestCodeUnit(String fqName, ProjectFile pf) {
        String shortName = fqName.substring(fqName.lastIndexOf('.') + 1);
        String packageName = fqName.contains(".") ? fqName.substring(0, fqName.lastIndexOf('.')) : "";
        // Use CLASS as a generic kind for testing, specific kind might not be critical for serialization tests
        return new CodeUnit(pf, io.github.jbellis.brokk.analyzer.CodeUnitType.CLASS, packageName, shortName);
    }

    @Test
    void testRoundTripGitFileFragment() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/GitFile.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class GitFile {}");

        var fragment = new ContextFragment.GitFileFragment(projectFile, "abcdef1234567890", "content for git file");

        var context = new Context(mockContextManager, "Test GitFileFragment").addPathFragments(List.of(fragment));
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_gitfile_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        // Verify specific GitFileFragment properties after general assertion
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.GitFileFragment) loadedCtx
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.GIT_FILE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                projectFile.absPath().toString(),
                loadedFragment.file().absPath().toString());
        assertEquals("abcdef1234567890", loadedFragment.revision());
        assertEquals("content for git file", loadedFragment.content());
    }

    @Test
    void testRoundTripExternalPathFragment() throws Exception {
        Path externalFilePath = tempDir.resolve("external_file.txt");
        Files.writeString(externalFilePath, "External file content");
        var externalFile = new io.github.jbellis.brokk.analyzer.ExternalFile(externalFilePath);
        var fragment = new ContextFragment.ExternalPathFragment(externalFile, mockContextManager);

        var context = new Context(mockContextManager, "Test ExternalPathFragment").addPathFragments(List.of(fragment));
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_externalpath_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .allFragments()
                .filter(f -> f.getType()
                        == ContextFragment.FragmentType.EXTERNAL_PATH) // getType on FrozenFragment returns originalType
                .findFirst()
                .orElseThrow();

        assertTrue(
                loadedRawFragment instanceof FrozenFragment, "ExternalPathFragment should be loaded as FrozenFragment");
        var loadedFrozenFragment = (FrozenFragment) loadedRawFragment;
        assertEquals(ContextFragment.FragmentType.EXTERNAL_PATH, loadedFrozenFragment.getType());
        assertEquals(ContextFragment.ExternalPathFragment.class.getName(), loadedFrozenFragment.originalClassName());
        assertEquals(externalFilePath.toString(), loadedFrozenFragment.meta().get("absPath"));
    }

    @Test
    void testRoundTripImageFileFragment() throws Exception {
        Path imageFilePath = tempDir.resolve("test_image.png");
        var testImage = createTestImage(Color.GREEN, 20, 20);
        ImageIO.write(testImage, "PNG", imageFilePath.toFile());
        var brokkImageFile = new io.github.jbellis.brokk.analyzer.ProjectFile(
                tempDir, tempDir.relativize(imageFilePath)); // Treat as project file for test
        var fragment = new ContextFragment.ImageFileFragment(brokkImageFile, mockContextManager);

        var context = new Context(mockContextManager, "Test ImageFileFragment").addPathFragments(List.of(fragment));
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_imagefile_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .allFragments()
                .filter(f -> f.getType()
                        == ContextFragment.FragmentType.IMAGE_FILE) // getType on FrozenFragment returns originalType
                .findFirst()
                .orElseThrow();

        assertTrue(loadedRawFragment instanceof FrozenFragment, "ImageFileFragment should be loaded as FrozenFragment");
        var loadedFrozenFragment = (FrozenFragment) loadedRawFragment;
        assertEquals(ContextFragment.FragmentType.IMAGE_FILE, loadedFrozenFragment.getType());
        assertEquals(ContextFragment.ImageFileFragment.class.getName(), loadedFrozenFragment.originalClassName());

        // Check path from meta
        String loadedAbsPath = loadedFrozenFragment.meta().get("absPath");
        assertNotNull(loadedAbsPath, "absPath not found in FrozenFragment meta for ImageFileFragment");
        assertEquals(imageFilePath.toString(), loadedAbsPath);

        // Check image content from bytes
        byte[] imageBytes = loadedFrozenFragment.imageBytesContent();
        assertNotNull(imageBytes, "Image bytes not found in FrozenFragment for ImageFileFragment");
        Image loadedImageFromBytes = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        assertNotNull(loadedImageFromBytes);
        assertEquals(20, loadedImageFromBytes.getWidth(null));
        assertEquals(20, loadedImageFromBytes.getHeight(null));
    }

    @Test
    void testRoundTripBuildFragment() throws Exception {
        var buildFragment = new ContextFragment.BuildFragment(mockContextManager, "Build successful\nAll tests passed");

        var context = new Context(mockContextManager, "Test BuildFragment").addVirtualFragment(buildFragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_buildfrag_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedBuildFrag = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.BUILD_LOG)
                .findFirst()
                .orElseThrow(() -> new AssertionError("BUILD_LOG fragment not found after round-trip"));

        assertTrue(loadedBuildFrag.isText());
        assertTrue(loadedBuildFrag.text().contains("Build successful\nAll tests passed"));
    }

    @Test
    void testRoundTripSearchFragment() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/SearchTarget.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class SearchTarget {}");
        var codeUnit = createTestCodeUnit("com.example.SearchTarget", projectFile);
        var messages = List.of(UserMessage.from("user query"), AiMessage.from("ai response"));
        var fragment =
                new ContextFragment.SearchFragment(mockContextManager, "Search: foobar", messages, Set.of(codeUnit));

        var context = new Context(mockContextManager, "Test SearchFragment").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_search_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.SearchFragment) loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.SEARCH)
                .findFirst()
                .orElseThrow();
        assertEquals("Search: foobar", loadedFragment.description());
        assertEquals(2, loadedFragment.messages().size());
        assertEquals(1, loadedFragment.sources().size());
        assertEquals(
                codeUnit.fqName(), loadedFragment.sources().iterator().next().fqName());
    }

    @Test
    void testRoundTripSkeletonFragment() throws Exception {
        var targetIds = List.of("com.example.ClassA", "com.example.ClassB");
        var fragment = new ContextFragment.SkeletonFragment(
                mockContextManager, targetIds, ContextFragment.SummaryType.CODEUNIT_SKELETON);

        var context = new Context(mockContextManager, "Test SkeletonFragment").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_skeleton_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.SKELETON)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragment.SkeletonFragment loadedFragment) {
            assertEquals(targetIds, loadedFragment.getTargetIdentifiers());
            assertEquals(ContextFragment.SummaryType.CODEUNIT_SKELETON, loadedFragment.getSummaryType());
        } else if (loadedRawFragment instanceof FrozenFragment loadedFrozenFragment) {
            assertEquals(ContextFragment.FragmentType.SKELETON, loadedFrozenFragment.getType());
            assertEquals(ContextFragment.SkeletonFragment.class.getName(), loadedFrozenFragment.originalClassName());
            assertEquals(
                    String.join(";", targetIds), loadedFrozenFragment.meta().get("targetIdentifiers"));
            assertEquals(
                    ContextFragment.SummaryType.CODEUNIT_SKELETON.name(),
                    loadedFrozenFragment.meta().get("summaryType"));
        } else {
            fail("Expected SkeletonFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripUsageFragment() throws Exception {
        var fragment = new ContextFragment.UsageFragment(mockContextManager, "com.example.MyClass.myMethod");

        var context = new Context(mockContextManager, "Test UsageFragment").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_usage_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.USAGE)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragment.UsageFragment loadedFragment) {
            assertEquals("com.example.MyClass.myMethod", loadedFragment.targetIdentifier());
        } else if (loadedRawFragment instanceof FrozenFragment loadedFrozenFragment) {
            assertEquals(ContextFragment.FragmentType.USAGE, loadedFrozenFragment.getType());
            assertEquals(ContextFragment.UsageFragment.class.getName(), loadedFrozenFragment.originalClassName());
            assertEquals(
                    "com.example.MyClass.myMethod", loadedFrozenFragment.meta().get("targetIdentifier"));
        } else {
            fail("Expected UsageFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripUsageFragmentIncludeTestFiles() throws Exception {
        var fragment = new ContextFragment.UsageFragment(mockContextManager, "com.example.MyClass.myMethod", true);

        var context = new Context(mockContextManager, "Test UsageFragment include").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_usage_include_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.USAGE)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragment.UsageFragment loadedFragment) {
            assertTrue(loadedFragment.includeTestFiles(), "includeTestFiles should be preserved as true");
            assertEquals("com.example.MyClass.myMethod", loadedFragment.targetIdentifier());
        } else if (loadedRawFragment instanceof FrozenFragment ff) {
            assertEquals(ContextFragment.FragmentType.USAGE, ff.getType());
        } else {
            fail("Expected UsageFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripCallGraphFragment() throws Exception {
        var fragment =
                new ContextFragment.CallGraphFragment(mockContextManager, "com.example.MyClass.doStuff", 3, true);

        var context = new Context(mockContextManager, "Test CallGraphFragment").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_callgraph_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.CALL_GRAPH)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragment.CallGraphFragment loadedFragment) {
            assertEquals("com.example.MyClass.doStuff", loadedFragment.getMethodName());
            assertEquals(3, loadedFragment.getDepth());
            assertTrue(loadedFragment.isCalleeGraph());
        } else if (loadedRawFragment instanceof FrozenFragment loadedFrozenFragment) {
            assertEquals(ContextFragment.FragmentType.CALL_GRAPH, loadedFrozenFragment.getType());
            assertEquals(ContextFragment.CallGraphFragment.class.getName(), loadedFrozenFragment.originalClassName());
            assertEquals(
                    "com.example.MyClass.doStuff", loadedFrozenFragment.meta().get("methodName"));
            assertEquals("3", loadedFrozenFragment.meta().get("depth"));
            assertEquals("true", loadedFrozenFragment.meta().get("isCalleeGraph"));
        } else {
            fail("Expected CallGraphFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripHistoryFragment() throws Exception {
        var taskMessages = List.<ChatMessage>of(UserMessage.from("Task user"), AiMessage.from("Task AI"));
        var taskFragment = new ContextFragment.TaskFragment(mockContextManager, taskMessages, "Test Task Log");
        var taskEntry = new TaskEntry(1, taskFragment, null);
        var fragment = new ContextFragment.HistoryFragment(mockContextManager, List.of(taskEntry));

        var context = new Context(mockContextManager, "Test HistoryFragment").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_history_frag_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.HistoryFragment) loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.HISTORY)
                .findFirst()
                .orElseThrow();
        assertEquals(1, loadedFragment.entries().size());
        assertTaskEntriesEqual(taskEntry, loadedFragment.entries().get(0));
    }

    @Test
    void testRoundTripPasteTextFragment() throws Exception {
        var fragment = new ContextFragment.PasteTextFragment(
                mockContextManager,
                "Pasted text content",
                CompletableFuture.completedFuture("Pasted text summary"),
                CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_MARKDOWN));

        var context = new Context(mockContextManager, "Test PasteTextFragment").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_pastetext_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.PasteTextFragment) loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PASTE_TEXT)
                .findFirst()
                .orElseThrow();
        assertEquals("Pasted text content", loadedFragment.text());
        assertEquals("Paste of Pasted text summary", loadedFragment.description());
    }

    @Test
    void testRoundTripStacktraceFragment() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/ErrorSource.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class ErrorSource {}");
        var codeUnit = createTestCodeUnit("com.example.ErrorSource", projectFile);
        var fragment = new ContextFragment.StacktraceFragment(
                mockContextManager,
                Set.of(codeUnit),
                "Full stacktrace original text",
                "NullPointerException",
                "ErrorSource.java:10");

        var context = new Context(mockContextManager, "Test StacktraceFragment").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_stacktrace_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.StacktraceFragment) loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.STACKTRACE)
                .findFirst()
                .orElseThrow();
        assertEquals("stacktrace of NullPointerException", loadedFragment.description());
        assertTrue(loadedFragment.text().contains("Full stacktrace original text"));
        assertTrue(loadedFragment.text().contains("ErrorSource.java:10"));
        assertEquals(1, loadedFragment.sources().size());
        assertEquals(
                codeUnit.fqName(), loadedFragment.sources().iterator().next().fqName());
    }

    @Test
    void testVirtualFragmentDeduplicationAfterSerialization() throws Exception {
        var context = new Context(mockContextManager, "Test Deduplication");

        // Add virtual fragments, some with duplicate text content
        // The IDs will be 3, 4, 5, 6, 7 based on current setup
        var vf1 = new ContextFragment.StringFragment(
                mockContextManager,
                "uniqueText1",
                "Description for uniqueText1 (first)",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        var vf2 = new ContextFragment.StringFragment(
                mockContextManager,
                "duplicateText",
                "Description for duplicateText (first)",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        var vf3 = new ContextFragment.StringFragment(
                mockContextManager, "uniqueText2", "Description for uniqueText2", SyntaxConstants.SYNTAX_STYLE_NONE);
        var vf4_duplicate_of_vf2 = new ContextFragment.StringFragment(
                mockContextManager,
                "duplicateText",
                "Description for duplicateText (second, different desc)",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        var vf5_duplicate_of_vf1 = new ContextFragment.StringFragment(
                mockContextManager,
                "uniqueText1",
                "Description for uniqueText1 (second, different desc)",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        context = context.addVirtualFragment(vf1);
        context = context.addVirtualFragment(vf2);
        context = context.addVirtualFragment(vf3);
        context = context.addVirtualFragment(vf4_duplicate_of_vf2);
        context = context.addVirtualFragment(vf5_duplicate_of_vf1);

        ContextHistory originalHistory = new ContextHistory(context);

        // Serialize and deserialize
        Path zipFile = tempDir.resolve("deduplication_test_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertNotNull(loadedHistory);
        assertEquals(1, loadedHistory.getHistory().size());
        Context deserializedContext = loadedHistory.getHistory().get(0);

        // Verify deduplication behavior of virtualFragments()
        List<ContextFragment.VirtualFragment> deduplicatedFragments =
                deserializedContext.virtualFragments().collect(Collectors.toList());

        // Expected: 3 unique fragments based on text content.
        // The ones kept should be vf1, vf2, vf3 because they were added first for their respective texts.
        assertEquals(3, deduplicatedFragments.size(), "Should be 3 unique virtual fragments after deduplication.");

        Set<String> actualTexts = deduplicatedFragments.stream()
                .map(ContextFragment.VirtualFragment::text)
                .collect(Collectors.toSet());
        assertEquals(
                Set.of("uniqueText1", "duplicateText", "uniqueText2"),
                actualTexts,
                "Texts of deduplicated fragments do not match expected unique texts.");

        // Verify that the specific fragments kept are the first ones encountered
        assertTrue(
                deduplicatedFragments.stream()
                        .anyMatch(f -> "uniqueText1".equals(f.text())
                                && "Description for uniqueText1 (first)".equals(f.description())),
                "Expected first instance of 'uniqueText1' to be present.");
        assertTrue(
                deduplicatedFragments.stream()
                        .anyMatch(f -> "duplicateText".equals(f.text())
                                && "Description for duplicateText (first)".equals(f.description())),
                "Expected first instance of 'duplicateText' to be present.");
        assertTrue(
                deduplicatedFragments.stream()
                        .anyMatch(f -> "uniqueText2".equals(f.text())
                                && "Description for uniqueText2".equals(f.description())),
                "Expected 'uniqueText2' to be present.");
    }

    @Test
    void testWriteReadHistoryWithGitState() throws IOException {
        // 1. Setup context 1 with a git state that has a diff
        var context1 = new Context(mockContextManager, "Context with git state");
        var history = new ContextHistory(context1);
        var context1Id = context1.id();
        var diffContent =
                """
                          diff --git a/file.txt b/file.txt
                          --- a/file.txt
                          +++ b/file.txt
                          @@ -1 +1 @@
                          -hello
                          +world
                          """;
        var gitState1 = new ContextHistory.GitState("test-commit-hash-1", diffContent);
        history.addGitState(context1Id, gitState1);

        // 2. Setup context 2 with a git state that has a null diff
        var context2 = new Context(mockContextManager, "Context with null diff git state");
        history.addFrozenContextAndClearRedo(context2.freeze());
        var context2Id = context2.id();
        var gitState2 = new ContextHistory.GitState("test-commit-hash-2", null);
        history.addGitState(context2Id, gitState2);

        // 3. Write to ZIP
        Path zipFile = tempDir.resolve("history_with_git_state.zip");
        HistoryIo.writeZip(history, zipFile);

        // 4. Read from ZIP
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // 5. Assertions
        assertNotNull(loadedHistory);
        assertEquals(2, loadedHistory.getHistory().size());

        // Verify first context and its git state
        assertEquals(context1Id, loadedHistory.getHistory().get(0).id());
        var loadedGitState1 = loadedHistory.getGitState(context1Id);
        assertTrue(loadedGitState1.isPresent());
        assertEquals("test-commit-hash-1", loadedGitState1.get().commitHash());
        assertEquals(diffContent, loadedGitState1.get().diff());

        // Verify second context and its git state
        assertEquals(context2Id, loadedHistory.getHistory().get(1).id());
        var loadedGitState2 = loadedHistory.getGitState(context2Id);
        assertTrue(loadedGitState2.isPresent());
        assertEquals("test-commit-hash-2", loadedGitState2.get().commitHash());
        assertNull(loadedGitState2.get().diff());
    }

    @Test
    void testRoundTripCodeFragment() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/CodeFragmentTarget.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class CodeFragmentTarget {}");
        var codeUnit = createTestCodeUnit("com.example.CodeFragmentTarget", projectFile);

        var fragment = new ContextFragment.CodeFragment(mockContextManager, codeUnit);

        var context = new Context(mockContextManager, "Test CodeFragment").addVirtualFragment(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_codefragment_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.CODE)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragment.CodeFragment loadedFragment) {
            assertEquals(codeUnit.fqName(), loadedFragment.getCodeUnit().fqName());
        } else if (loadedRawFragment instanceof FrozenFragment loadedFrozenFragment) {
            assertEquals(ContextFragment.FragmentType.CODE, loadedFrozenFragment.getType());
            assertEquals(ContextFragment.CodeFragment.class.getName(), loadedFrozenFragment.originalClassName());
            assertEquals(
                    "com.example.CodeFragmentTarget",
                    loadedFrozenFragment.meta().get("fqName"));
            assertEquals(
                    projectFile.getRoot().toString(),
                    loadedFrozenFragment.meta().get("repoRoot"));
            assertEquals(
                    projectFile.getRelPath().toString(),
                    loadedFrozenFragment.meta().get("relPath"));
        } else {
            fail("Expected CodeFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }
}
