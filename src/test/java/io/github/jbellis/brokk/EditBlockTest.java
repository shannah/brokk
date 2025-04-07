package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditBlockTest {
    static class TestContextManager implements IContextManager {
        private final Path root;
        private final Set<ProjectFile> validFiles;

        public TestContextManager(Path root, Set<String> validFiles) {
            this.root = root;
            this.validFiles = validFiles.stream().map(f -> new ProjectFile(root, Path.of(f))).collect(Collectors.toSet());
        }

        @Override
        public ProjectFile toFile(String relName) {
            return new ProjectFile(root, Path.of(relName));
        }

        @Override
        public Set<ProjectFile> getEditableFiles() {
            return validFiles;
        }
    }

    static class TestConsoleIO implements IConsoleIO {
        private final StringBuilder outputLog = new StringBuilder();
        private final StringBuilder errorLog = new StringBuilder();

        @Override
        public void actionOutput(String text) {
            outputLog.append(text).append("\n");
        }

        @Override
        public void toolErrorRaw(String msg) {
            errorLog.append(msg).append("\n");
        }

        @Override
        public void llmOutput(String token) {
            // not needed for these tests
        }

        public String getOutputLog() {
            return outputLog.toString();
        }

        public String getErrorLog() {
            return errorLog.toString();
        }
    }

    @Test
    void testFindOriginalUpdateBlocksSimple() {
        String edit = """
                Here's the change:

                ```text
                foo.txt
                <<<<<<< SEARCH
                Two
                =======
                Tooooo
                >>>>>>> REPLACE
                ```

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(1, blocks.length);
        assertEquals("foo.txt", blocks[0].filename().toString());
        assertEquals("Two\n", blocks[0].beforeText());
        assertEquals("Tooooo\n", blocks[0].afterText());
    }

    @Test
    void testFindOriginalUpdateBlocksMultipleSameFile() {
        String edit = """
                Here's the change:

                ```text
                foo.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE

                ...

                <<<<<<< SEARCH
                three
                =======
                four
                >>>>>>> REPLACE
                ```

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(2, blocks.length);
        // first block
        assertEquals("foo.txt", blocks[0].filename().toString());
        assertEquals("one\n", blocks[0].beforeText());
        assertEquals("two\n", blocks[0].afterText());
        // second block
        assertEquals("foo.txt", blocks[1].filename().toString());
        assertEquals("three\n", blocks[1].beforeText());
        assertEquals("four\n", blocks[1].afterText());
    }

    @Test
    void testFindOriginalUpdateBlocksNoFinalNewline() {
        String edit = """
                aider/coder.py
                <<<<<<< SEARCH
                lineA
                =======
                lineB
                >>>>>>> REPLACE

                aider/coder.py
                <<<<<<< SEARCH
                lineC
                =======
                lineD
                >>>>>>> REPLACE"""; // no newline at the end

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("aider/coder.py"));
        assertEquals(2, blocks.length);
        assertEquals("lineA\n", blocks[0].beforeText());
        assertEquals("lineB\n", blocks[0].afterText());
        assertEquals("lineC\n", blocks[1].beforeText());
        assertEquals("lineD\n", blocks[1].afterText());
    }

    @Test
    void testFindOriginalUpdateBlocksNewFileThenExisting() {
        String edit = """
                Here's the change:

                filename/to/a/file2.txt
                ```python
                <<<<<<< SEARCH
                =======
                three
                >>>>>>> REPLACE
                ```

                another change

                filename/to/a/file1.txt
                ```python
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("filename/to/a/file1.txt"));
        assertEquals(2, blocks.length);
        assertEquals("filename/to/a/file2.txt", blocks[0].filename());
        assertEquals("", blocks[0].beforeText().trim());
        assertEquals("three", blocks[0].afterText().trim());
        assertEquals("filename/to/a/file1.txt", blocks[1].filename());
        assertEquals("one", blocks[1].beforeText().trim());
        assertEquals("two", blocks[1].afterText().trim());
    }

    @Test
    void testReplaceFirstOccurrenceOnly() {
        String original = """
                line1
                line2
                line1
                line3
                """;
        String search = "line1\n";
        String replace = "new_line\n";
        String expected = """
                new_line
                line2
                line1
                line3
                """;

        String updated = EditBlock.doReplace(original, search, replace);
        assertEquals(expected, updated);
    }

    @Test
    void testApplyEditsCreatesNewFile(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Original text\n");

        String response = """
                fileA.txt
                <<<<<<< SEARCH
                Original text
                =======
                Updated
                >>>>>>> REPLACE

                newFile.txt
                <<<<<<< SEARCH
                =======
                Created content
                >>>>>>> REPLACE
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.findOriginalUpdateBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // existing filename
        String actualA = Files.readString(existingFile);
        assertTrue(actualA.contains("Updated"));

        // new filename
        Path newFile = tempDir.resolve("newFile.txt");
        String newFileText = Files.readString(newFile);
        assertEquals("Created content\n", newFileText);

        // no errors
        assertTrue(io.getErrorLog().isEmpty(), "No error expected");
    }

    @Test
    void testApplyEditsFailsForUnknownFile(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();

        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Line X\n");

        String response = """
                unknownFile.txt
                <<<<<<< SEARCH
                something
                =======
                replacement
                >>>>>>> REPLACE
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.findOriginalUpdateBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        assertNotEquals(List.of(), result.failedBlocks());
    }

    /**
     * Check that we can parse an unclosed block and fail gracefully.
     * (Similar to python test_find_original_update_blocks_unclosed)
     */
    @Test
    void testFindOriginalUpdateBlocksUnclosed() {
        String edit = """
                Here's the change:

                ```text
                foo.txt
                <<<<<<< SEARCH
                Two
                =======
                Tooooo

                oops! no trailing >>>>>> REPLACE
                """;

        var files = Set.of("foo.txt").stream().map(f -> new ProjectFile(Path.of("/"), Path.of(f))).collect(Collectors.toSet());
        var result = EditBlock.findOriginalUpdateBlocks(edit, files);
        assertNotEquals(null, result.parseError());
    }

    /**
     * Check that we parse blocks that have no filename at all (which is a parse error if we
     * haven't yet established a 'currentFile').
     */
    @Test
    void testFindOriginalUpdateBlocksMissingFilename(@TempDir Path tempDir) {
        String edit = """
                Here's the change:

                ```text
                <<<<<<< SEARCH
                Two
                =======
                Tooooo
                >>>>>>> REPLACE
                ```
                """;

        // Expect an exception about missing filename
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager ctx = new TestContextManager(tempDir, Set.of());
        var blocks = EditBlock.findOriginalUpdateBlocks(edit, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);
        assertNotEquals(List.of(), result.failedBlocks());
    }

    /**
     * Test detection of a possible "mangled" or fuzzy filename match.
     */
    @Test
    void testFindOriginalUpdateBlocksFuzzyFilename() {
        String edit = """
                Here's the change:
                fOo.TXt
                <<<<<<< SEARCH
                alpha
                =======
                beta
                >>>>>>> REPLACE
                """;

        // We only have "foo.txt" in the valid set => fuzzy match
        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(1, blocks.length);
        assertEquals("foo.txt", blocks[0].filename());
        assertEquals("alpha\n", blocks[0].beforeText());
        assertEquals("beta\n", blocks[0].afterText());
    }

    @Test
    void testDidYouMeanSuggestionsOnFailure(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "AAA\nBBB\nCCC\n");

        // The "beforeText" is too different from anything in the file
        String response = """
            fileA.txt
            <<<<<<< SEARCH
            something totally unknown
            =======
            replacement
            >>>>>>> REPLACE
            """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.findOriginalUpdateBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);
        assertNotEquals(List.of(), result.failedBlocks());
    }

    @Test
    void testEditResultContainsOriginalContents(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        String originalContent = "Original text\n";
        Files.writeString(existingFile, originalContent);

        String response = """
        fileA.txt
        <<<<<<< SEARCH
        Original text
        =======
        Updated text
        >>>>>>> REPLACE
        """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.findOriginalUpdateBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Verify original content is preserved
        var fileA = new ProjectFile(tempDir, Path.of("fileA.txt"));
        assertEquals(originalContent, result.originalContents().get(fileA));

        // Verify file was actually changed
        String actualContent = Files.readString(existingFile);
        assertEquals("Updated text\n", actualContent);
    }

    // ----------------------------------------------------
    // Helper methods
    // ----------------------------------------------------
    private EditBlock.SearchReplaceBlock[] parseBlocks(String fullResponse, Set<String> validFilenames) {
        var files = validFilenames.stream().map(f -> new ProjectFile(Path.of("/"), Path.of(f))).collect(Collectors.toSet());
        var blocks = EditBlock.findOriginalUpdateBlocks(fullResponse, files).blocks();
        return blocks.toArray(new EditBlock.SearchReplaceBlock[0]);
    }
}
