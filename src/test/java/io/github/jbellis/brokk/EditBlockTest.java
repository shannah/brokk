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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void testParseEditBlocksSimple() {
        String edit = """
                Here's the change:

                ```text
                <<<<<<< SEARCH foo.txt
                Two
                ======= foo.txt
                Tooooo
                >>>>>>> REPLACE foo.txt
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
    void testParseEditBlocksMultipleSameFile() {
        String edit = """
                Here's the change:

                ```text
                <<<<<<< SEARCH foo.txt
                one
                ======= foo.txt
                two
                >>>>>>> REPLACE foo.txt

                ...

                <<<<<<< SEARCH foo.txt
                three
                ======= foo.txt
                four
                >>>>>>> REPLACE foo.txt
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
    void testParseEditBlocksNoFinalNewline() {
        String edit = """
                <<<<<<< SEARCH aider/coder.py
                lineA
                ======= aider/coder.py
                lineB
                >>>>>>> REPLACE aider/coder.py

                <<<<<<< SEARCH aider/coder.py
                lineC
                ======= aider/coder.py
                lineD
                >>>>>>> REPLACE aider/coder.py"""; // no newline at the end

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("aider/coder.py"));
        assertEquals(2, blocks.length);
        assertEquals("lineA\n", blocks[0].beforeText());
        assertEquals("lineB\n", blocks[0].afterText());
        assertEquals("lineC\n", blocks[1].beforeText());
        assertEquals("lineD\n", blocks[1].afterText());
    }

    @Test
    void testParseEditBlocksNewFileThenExisting() {
        String edit = """
                Here's the change:

                ```python
                <<<<<<< SEARCH filename/to/a/file2.txt
                ======= filename/to/a/file2.txt
                three
                >>>>>>> REPLACE filename/to/a/file2.txt
                ```

                another change

                ```python
                <<<<<<< SEARCH filename/to/a/file1.txt
                one
                ======= filename/to/a/file1.txt
                two
                >>>>>>> REPLACE filename/to/a/file1.txt
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
    void testApplyEditsCreatesNewFile(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Original text\n");

        String response = """
                <<<<<<< SEARCH fileA.txt
                Original text
                ======= fileA.txt
                Updated text
                >>>>>>> REPLACE fileA.txt

                <<<<<<< SEARCH newFile.txt
                ======= newFile.txt
                Created content
                >>>>>>> REPLACE newFile.txt
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.parseEditBlocks(response).blocks();
        EditBlock.applyEditBlocks(ctx, io, blocks);

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
                <<<<<<< SEARCH unknownFile.txt
                replacement
                ======= unknownFile.txt
                replacement
                >>>>>>> REPLACE unknownFile.txt
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.parseEditBlocks(response).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        assertNotEquals(List.of(), result.failedBlocks());
    }

    /**
     * Check that we can parse an unclosed block and fail gracefully.
     * (Similar to python test_find_original_update_blocks_unclosed)
     */
    @Test
    void testParseEditBlocksUnclosed() {
        String edit = """
                Here's the change:

                ```text
                <<<<<<< SEARCH foo.txt
                Two
                ======= foo.txt
                Tooooo

                oops! no trailing >>>>>> REPLACE foo.txt
                """;

        var result = EditBlock.parseEditBlocks(edit);
        assertNotEquals(null, result.parseError());
    }

    /**
     * Check that we parse blocks that have no filename at all (which is a parse error if we
     * haven't yet established a 'currentFile').
     */
    @Test
    void testParseEditBlocksMissingFilename(@TempDir Path tempDir) {
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
        var files = Set.of("").stream().map(f -> new ProjectFile(tempDir, Path.of(f))).collect(Collectors.toSet());
        var result = EditBlock.parseEditBlocks(edit);

        // It should fail parsing because the filename is missing
        assertEquals(0, result.blocks().size());
    }

    /**
     * Test detection of a possible "mangled" or fuzzy filename match.
     */
    @Test
    void testResolveFilenameIgnoreCase(@TempDir Path tempDir) throws EditBlock.SymbolAmbiguousException, EditBlock.SymbolNotFoundException {
        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var f = EditBlock.resolveProjectFile(ctx, "fOo.TXt", false);
        assertEquals("foo.txt", f.getFileName());
    }

    @Test
    void testNoMatchFailure(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "AAA\nBBB\nCCC\n");

        // The "beforeText" is too different from anything in the file
        String response = """
            <<<<<<< SEARCH fileA.txt
            replacement
            ======= fileA.txt
            replacement
            >>>>>>> REPLACE fileA.txt
            """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.parseEditBlocks(response).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Assert exactly one failure with the correct reason
        assertEquals(1, result.failedBlocks().size(), "Expected exactly one failed block");
        assertEquals(EditBlock.EditBlockFailureReason.NO_MATCH, result.failedBlocks().getFirst().reason(), "Expected failure reason to be NO_MATCH");

        // Assert that the file content remains unchanged after the failed edit
        String finalContent = Files.readString(existingFile);
        assertEquals("AAA\nBBB\nCCC\n", finalContent, "File content should remain unchanged after a NO_MATCH failure.");
    }

    @Test
    void testEditResultContainsOriginalContents(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        String originalContent = "Original text\n";
        Files.writeString(existingFile, originalContent);

        String response = """
        <<<<<<< SEARCH fileA.txt
        Original text
        ======= fileA.txt
        Updated text
        >>>>>>> REPLACE fileA.txt
        """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.parseEditBlocks(response).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Verify original content is returned
        var fileA = new ProjectFile(tempDir, Path.of("fileA.txt"));
        assertEquals(originalContent, result.originalContents().get(fileA));

        // Verify file was actually changed
        String actualContent = Files.readString(existingFile);
        assertEquals("Updated text\n", actualContent);
    }

    @Test
    void testApplyEditsEmptySearchReplacesFile(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path testFile = tempDir.resolve("replaceTest.txt");
        String originalContent = "Initial content.\n";
        Files.writeString(testFile, originalContent);

        String replacementContent = "Replacement text.\n";
        String response = """
        <<<<<<< SEARCH replaceTest.txt
        ======= replaceTest.txt
        %s
        >>>>>>> REPLACE replaceTest.txt
        """.formatted(replacementContent.trim()); // Use trim because EditBlock adds newline

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("replaceTest.txt"));
        var blocks = EditBlock.parseEditBlocks(response).blocks();
        assertEquals(1, blocks.size());
        assertTrue(blocks.getFirst().beforeText().isEmpty()); // Verify search block is empty

        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Verify the file content is now *only* the replacement text
        String actualContent = Files.readString(testFile);
        assertEquals(replacementContent, actualContent); // Expected content is exactly the replacement

        // Verify no failures
        assertTrue(result.failedBlocks().isEmpty(), "No failures expected");
        assertTrue(io.getErrorLog().isEmpty(), "No IO errors expected");
    }

    /**
     * These tests illustrate how the new "forgiving" parse logic works when we
     * don't see a "filename =======" divider between SEARCH and REPLACE, but do
     * see a standalone "=======" line. If exactly one such line is found,
     * we use it as the divider; otherwise it's a non-fatal parse error.
     */
    @Test
    void testForgivingDividerSingleMatch() {
        String content = """
        <<<<<<< SEARCH foo.txt
        old line
        =======
        new line
        >>>>>>> REPLACE foo.txt
        """;

        var result = EditBlock.parseEditBlocks(content);
        // Expect exactly one successfully parsed block, no parse errors
        assertEquals(1, result.blocks().size(), "Should parse a single block");
        assertEquals(null, result.parseError(), "No parse errors expected");

        var block = result.blocks().get(0);
        assertEquals("foo.txt", block.filename());
        assertEquals("old line\n", block.beforeText());
        assertEquals("new line\n", block.afterText());
    }

    @Test
    void testForgivingDividerMultipleMatches() {
        String content = """
        <<<<<<< SEARCH foo.txt
        line A
        =======
        line B
        =======
        line C
        >>>>>>> REPLACE foo.txt
        """;

        var result = EditBlock.parseEditBlocks(content);
        // Because we found more than one standalone "=======" line in the SEARCH->REPLACE block,
        // the parser should treat it as an error for that block, producing zero blocks.
        assertEquals(0, result.blocks().size(), "No successful blocks expected when multiple dividers found");
        assertNotNull(result.parseError(), "Should report parse error on multiple matches");
    }

    @Test
    void testForgivingDividerZeroMatches() {
        String content = """
        <<<<<<< SEARCH foo.txt
        line A
        line B
        >>>>>>> REPLACE foo.txt
        """;

        var result = EditBlock.parseEditBlocks(content);
        // Because there was no "foo.txt =======" nor a single standalone "=======" line,
        // this block also fails to parse, yielding zero blocks and a parse error.
        assertEquals(0, result.blocks().size(), "No successful blocks expected without any divider line");
        assertNotNull(result.parseError(), "Should report parse error on zero matches");
    }

    /**
     * Demonstrates that a malformed block can coexist with a well-formed one:
     * the malformed block is skipped (recorded as an error) but the good block is parsed.
     */
    @Test
    void testForgivingDividerPartialFailure() {
        String content = """
        <<<<<<< SEARCH foo.txt
        line A
        >>>>>>> REPLACE foo.txt

        <<<<<<< SEARCH bar.txt
        some
        ======= bar.txt
        other
        >>>>>>> REPLACE bar.txt
        """;

        var result = EditBlock.parseEditBlocks(content);
        // Expect 1 block to parse OK (the bar.txt block),
        // and 1 parse error from the foo.txt block that has no divider.
        assertEquals(1, result.blocks().size(), "One successfully parsed block");
        assertNotNull(result.parseError(), "Expect parse error for the malformed block");

        var goodBlock = result.blocks().get(0);
        assertEquals("bar.txt", goodBlock.filename());
        assertEquals("some\n", goodBlock.beforeText());
        assertEquals("other\n", goodBlock.afterText());
    }

    @Test
    void testNoMatchFailureWithExistingReplacementText(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        String initialContent = "AAA\nBBB\nCCC\n";
        String alreadyPresentText = "Replacement Text"; // This text is NOT in initialContent yet, but we want it there
        String fileContent = initialContent + alreadyPresentText + "\nDDD\n";
        Files.writeString(existingFile, fileContent);

        // The "beforeText" will not match, but the "afterText" is already in the file
        String response = """
            <<<<<<< SEARCH fileA.txt
            NonExistentBeforeText
            ======= fileA.txt
            %s
            >>>>>>> REPLACE fileA.txt
            """.formatted(alreadyPresentText);

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.parseEditBlocks(response).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Assert exactly one failure with NO_MATCH reason
        assertEquals(1, result.failedBlocks().size(), "Expected exactly one failed block");
        var failedBlock = result.failedBlocks().getFirst();
        assertEquals(EditBlock.EditBlockFailureReason.NO_MATCH, failedBlock.reason(), "Expected failure reason to be NO_MATCH");

        // Assert the specific commentary is present
        assertTrue(failedBlock.commentary().contains("replacement text is already present"), "Expected specific commentary about existing replacement text");

        // Assert that the file content remains unchanged
        String finalContent = Files.readString(existingFile);
        assertEquals(fileContent, finalContent, "File content should remain unchanged after the failed edit");
    }

    // ----------------------------------------------------
    // Helper methods
    // ----------------------------------------------------
    private EditBlock.SearchReplaceBlock[] parseBlocks(String fullResponse, Set<String> validFilenames) {
        var files = validFilenames.stream().map(f -> new ProjectFile(Path.of("/"), Path.of(f))).collect(Collectors.toSet());
        var blocks = EditBlock.parseEditBlocks(fullResponse).blocks();
        return blocks.toArray(new EditBlock.SearchReplaceBlock[0]);
    }
}
