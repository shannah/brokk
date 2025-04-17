package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for extracting and applying before/after search-replace blocks in content.
 */
public class EditBlock {
    private static final Logger logger = LogManager.getLogger(EditBlock.class);

    // Pattern for the start of a search block, capturing the filename
    private static final Pattern SEARCH = Pattern.compile("^\\s*<{5,9} SEARCH\\s*(\\S+?)\\s*$", Pattern.MULTILINE);
    // Pattern for the divider, capturing the filename
    private static final Pattern DIVIDER = Pattern.compile("^\\s*={5,9}\\s*(\\S+?)\\s*$", Pattern.MULTILINE);
    // Pattern for the end of a replace block, capturing the filename
    private static final Pattern REPLACE = Pattern.compile("^\\s*>{5,9} REPLACE\\s*(\\S+?)\\s*$", Pattern.MULTILINE);

    private EditBlock() {
        // utility class
    }

    /**
     * Helper that returns the first code block found between triple backticks.
     * Skips any text on the same line as the opening backticks (like language specifiers)
     * and starts capturing from the next line.
     * Returns an empty string if no valid block is found.
     */
    public static String extractCodeFromTripleBackticks(String text) {
        // Pattern: ``` followed by optional non-newline chars, then newline, then capture until ```
        var matcher = Pattern.compile(
                "```[^\\n]*\\n(.*?)```", // Skip first line, capture starting from the second
                Pattern.DOTALL
        ).matcher(text);

        if (matcher.find()) {
            // group(1) captures the content between the newline and the closing ```
            return matcher.group(1);
        }
        return "";
    }

    public enum EditBlockFailureReason {
        FILE_NOT_FOUND,
        NO_MATCH,
        AMBIGUOUS_MATCH,
        IO_ERROR
    }

    public record EditResult(Map<ProjectFile, String> originalContents, List<FailedBlock> failedBlocks) {
        public boolean hadSuccessfulEdits() {
            return !originalContents.isEmpty();
        }
    }

    public record FailedBlock(SearchReplaceBlock block, EditBlockFailureReason reason, String commentary) {
        public FailedBlock {
            assert block != null;
            assert reason != null;
            assert commentary != null;
        }

        public FailedBlock(SearchReplaceBlock block, EditBlockFailureReason reason) {
            this(block, reason, "");
        }
    }

    // -- Exceptions for file resolution --
    /** Thrown when a filename provided by the LLM cannot be uniquely resolved. */
    public static class SymbolNotFoundException extends Exception {
        public SymbolNotFoundException(String message) {
            super(message);
        }
    }

    /** Thrown when a filename provided by the LLM matches multiple files. */
    public static class SymbolAmbiguousException extends Exception {
        public SymbolAmbiguousException(String message) {
            super(message);
        }
    }

    /**
     * Parse the LLM response for SEARCH/REPLACE blocks and apply them.
     */
    public static EditResult applyEditBlocks(IContextManager contextManager, IConsoleIO io, Collection<SearchReplaceBlock> blocks) {
        // Track which blocks succeed or fail during application
        List<FailedBlock> failed = new ArrayList<>();
        Map<SearchReplaceBlock, ProjectFile> succeeded = new HashMap<>();

        // Track original file contents before any changes
        Map<ProjectFile, String> changedFiles = new HashMap<>();

        for (SearchReplaceBlock block : blocks) {
            // 1. Resolve the filename
            ProjectFile file;
            boolean isCreateNew = block.beforeText().trim().isEmpty();
            try {
                if (block.filename() == null || block.filename().isBlank()) {
                    throw new SymbolNotFoundException("Block is missing filename");
                }
                file = resolveProjectFile(contextManager, block.filename(), isCreateNew);
            } catch (SymbolNotFoundException | SymbolAmbiguousException e) {
                logger.debug("File resolution failed for block [{}]: {}", block.filename(), e.getMessage());
                failed.add(new FailedBlock(block, EditBlockFailureReason.FILE_NOT_FOUND));
                continue; // Skip to the next block
            }

            // 2. Apply the edit using replaceInFile
            try {
                // Save original content before attempting change
                if (!changedFiles.containsKey(file)) {
                    changedFiles.put(file, file.exists() ? file.read() : "");
                }

                // Perform the replacement
                replaceInFile(file, block.beforeText(), block.afterText());

                // If successful, add to succeeded list
                succeeded.put(block, file);
                if (isCreateNew) {
                    try {
                        contextManager.addToGit(List.of(file));
                        io.systemOutput("Added to git " + file);
                    } catch (IOException e) {
                        io.systemOutput("Failed to git add " + file + ": " + e.getMessage());
                        // Continue anyway, git add failure is not fatal here
                    }
                }
            } catch (NoMatchException | AmbiguousMatchException e) {
                assert changedFiles.containsKey(file);
                var originalContent = changedFiles.get(file);
                String commentary;
                try {
                    replaceMostSimilarChunk(originalContent, block.afterText, "");
                    // if it didn't throw:
                    commentary = """
                    Note: The replacement text is already present in the file. If we no longer need to apply
                    this block, omit it from your reply.
                    """.stripIndent();
                } catch (NoMatchException | AmbiguousMatchException e2) {
                    commentary = "";
                }
                logger.debug("Edit application failed for file [{}] {}: {}", file, e.getClass().getSimpleName(), e.getMessage());
                var reason = e instanceof NoMatchException ? EditBlockFailureReason.NO_MATCH : EditBlockFailureReason.AMBIGUOUS_MATCH;
                failed.add(new FailedBlock(block, reason, commentary));
                // Restore original content if we saved it and the edit failed
                if (changedFiles.containsKey(file)) {
                    try {
                        file.write(changedFiles.get(file));
                    } catch (IOException writeErr) {
                        io.toolError("Failed restoring original content for " + file + " after failed edit: " + writeErr.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.warn("IO error applying edit to file [{}]: {}", file, e.getMessage());
                io.toolError("Failed reading/writing " + file + ": " + e.getMessage());
                failed.add(new FailedBlock(block, EditBlockFailureReason.IO_ERROR));
                // Restore original content if possible
                if (changedFiles.containsKey(file)) {
                    try {
                        file.write(changedFiles.get(file));
                    } catch (IOException writeErr) {
                        io.toolError("Failed restoring original content for " + file + " after IO error: " + writeErr.getMessage());
                    }
                }
             }
         } // End of for loop

        if (!succeeded.isEmpty()) {
            io.llmOutput("\n" + succeeded.size() + " SEARCH/REPLACE blocks applied.", ChatMessageType.USER);
        }
        changedFiles.keySet().retainAll(succeeded.values());
        return new EditResult(changedFiles, failed);
    }

    /**
     * Simple record storing the parts of a search-replace block.
     * If {@code filename} is non-null, then this block corresponds to a filename’s
     * search/replace
     */
    public record SearchReplaceBlock(String filename, String beforeText, String afterText) {
        public SearchReplaceBlock {
            assert filename != null;
            assert beforeText != null;
            assert  afterText != null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("<<<<<<< SEARCH %s\n".formatted(filename));
            sb.append(beforeText);
            if (!beforeText.endsWith("\n")) {
                sb.append("\n");
            }
            sb.append("======= %s\n".formatted(filename));
            sb.append(afterText);
            if (!afterText.endsWith("\n")) {
                sb.append("\n");
            }
            sb.append(">>>>>>> REPLACE %s\n".formatted(filename));

            return sb.toString();
        }
    }

    public record ParseResult(List<SearchReplaceBlock> blocks, String parseError) { }

    public record ExtendedParseResult(List<OutputBlock> blocks, String parseError) { }
    
    /**
     * Represents a segment of the LLM output, categorized as either plain text,
     * fenced code, or a parsed Edit Block.
     */
    public record OutputBlock(String text, EditBlock.SearchReplaceBlock block) {
        /**
         * Ensures that exactly one of the fields is non-null.
         */
        public OutputBlock {
            assert (text == null) != (block == null);
        }

        /** Convenience constructor for plain text blocks. */
        public static OutputBlock plain(String text) {
            return new OutputBlock(text, null);
        }

        /** Convenience constructor for Edit blocks. */
        public static OutputBlock edit(EditBlock.SearchReplaceBlock block) {
            return new OutputBlock(null, block);
        }
    }

    /**
     * Replace the first occurrence of `beforeText` lines with `afterText` lines in the given file.
     * Throws NoMatchException if `beforeText` is not found in the file content.
     * Throws AmbiguousMatchException if more than one match is found.
     */
    public static void replaceInFile(ProjectFile file, String beforeText, String afterText)
            throws IOException, NoMatchException, AmbiguousMatchException
    {
        String original = file.exists() ? file.read() : "";
        String updated = replaceMostSimilarChunk(original, beforeText, afterText);
        file.write(updated);
    }

    /**
     * Custom exception thrown when no matching location is found in the file.
     */
    public static class NoMatchException extends Exception {
        public NoMatchException(String msg) {
            super(msg);
        }
    }

    /**
     * Thrown when more than one matching location is found.
     */
    public static class AmbiguousMatchException extends Exception {
        public AmbiguousMatchException(String message) {
            super(message);
        }
    }

    /**
     * Parses the given content into a sequence of OutputBlock records (plain text or edit blocks).
     * Supports a "forgiving" divider approach if we do not see a standard "filename =======" line
     * but do see exactly one line of "=======" in the lines between SEARCH and REPLACE.
     * Malformed blocks do not prevent parsing subsequent blocks.
     */
    public static ExtendedParseResult parseAllBlocks(String content)
    {
        var outputBlocks = new ArrayList<OutputBlock>();
        var parseErrors = new StringBuilder();
        var leftoverText = new StringBuilder();

        String[] lines = content.split("\n", -1);
        int i = 0;

        outerLoop:
        while (i < lines.length) {
            // 1) Look for "<<<<<<< SEARCH filename"
            var headMatcher = SEARCH.matcher(lines[i]);
            if (!headMatcher.matches()) {
                // Not a SEARCH line => treat as plain text
                leftoverText.append(lines[i]);
                if (i < lines.length - 1) {
                    leftoverText.append("\n");
                }
                i++;
                continue;
            }

            // We found a SEARCH line
            String currentFilename = headMatcher.group(1).trim();
            int searchLineIndex = i;
            i++;

            var beforeLines = new ArrayList<String>();
            boolean blockSuccess = false;

            blockLoop:
            while (i < lines.length) {
                var dividerMatcher = DIVIDER.matcher(lines[i]);
                var replaceMatcher = REPLACE.matcher(lines[i]);

                // 2a) If we encounter a "filename =======" line, gather "after" lines until REPLACE
                if (dividerMatcher.matches() && dividerMatcher.group(1).trim().equals(currentFilename)) {
                    var afterLines = new ArrayList<String>();
                    i++; // skip the divider line

                    int replaceIndex = -1;
                    while (i < lines.length) {
                        var r2 = REPLACE.matcher(lines[i]);
                        var divider2 = DIVIDER.matcher(lines[i]);

                        if (r2.matches() && r2.group(1).trim().equals(currentFilename)) {
                            replaceIndex = i;
                            break;
                        } else if (divider2.matches() && divider2.group(1).trim().equals(currentFilename)) {
                            // A second named divider => parse error
                            parseErrors.append("Multiple named dividers found for ")
                                    .append(currentFilename).append(" block.\n");

                            // Revert everything
                            revertLinesToLeftover(leftoverText,
                                                  lines[searchLineIndex],
                                                  beforeLines,
                                                  afterLines);
                            leftoverText.append(lines[i]).append("\n");
                            i++;
                            continue outerLoop;
                        }

                        afterLines.add(lines[i]);
                        i++;
                    }

                    if (replaceIndex < 0) {
                        // We never found "filename >>>>>>> REPLACE"
                        parseErrors.append("Expected '")
                                .append(currentFilename)
                                .append(" >>>>>>> REPLACE' marker.\n");
                        revertLinesToLeftover(leftoverText,
                                              lines[searchLineIndex],
                                              beforeLines,
                                              afterLines);
                        continue outerLoop;
                    }

                    // We found a valid block
                    i++; // skip the REPLACE line
                    String beforeJoined = String.join("\n", beforeLines);
                    String afterJoined = String.join("\n", afterLines);
                    if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) {
                        beforeJoined += "\n";
                    }
                    if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) {
                        afterJoined += "\n";
                    }

                    // flush leftover text so it becomes a separate plain block
                    flushLeftoverText(leftoverText, outputBlocks);

                    var block = new SearchReplaceBlock(currentFilename, beforeJoined, afterJoined);
                    outputBlocks.add(OutputBlock.edit(block));
                    blockSuccess = true;
                    break blockLoop;
                }

                // 2b) If we encounter "filename >>>>>>> REPLACE" *before* a divider,
                // we do the single-line "======" fallback approach.
                if (replaceMatcher.matches() && replaceMatcher.group(1).trim().equals(currentFilename)) {
                    // Attempt the fallback approach
                    int partialCount = 0;
                    int partialIdx = -1;
                    for (int idx = 0; idx < beforeLines.size(); idx++) {
                        if (beforeLines.get(idx).matches("^\\s*={5,9}\\s*$")) {
                            partialCount++;
                            partialIdx = idx;
                        }
                    }
                    if (partialCount == 1) {
                        String beforeJoined = String.join("\n", beforeLines.subList(0, partialIdx));
                        String afterJoined = String.join("\n", beforeLines.subList(partialIdx + 1, beforeLines.size()));
                        if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) {
                            beforeJoined += "\n";
                        }
                        if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) {
                            afterJoined += "\n";
                        }

                        flushLeftoverText(leftoverText, outputBlocks);
                        var srBlock = new SearchReplaceBlock(currentFilename, beforeJoined, afterJoined);
                        outputBlocks.add(OutputBlock.edit(srBlock));

                        blockSuccess = true;
                        i++; // skip the REPLACE line
                        break blockLoop;
                    } else {
                        parseErrors.append("Failed to parse block for '")
                                .append(currentFilename)
                                .append("': found ")
                                .append(partialCount)
                                .append(" standalone '=======' lines.\n");

                        revertLinesToLeftover(leftoverText,
                                              lines[searchLineIndex],
                                              beforeLines,
                                              lines[i]);
                        i++;
                        continue outerLoop;
                    }
                }

                // If it's neither a recognized divider nor REPLACE => accumulate in beforeLines
                beforeLines.add(lines[i]);
                i++;
            }

            // If we exit the while loop normally, we never found a divider or fallback => parse error
            if (!blockSuccess) {
                parseErrors.append("Expected '")
                        .append(currentFilename)
                        .append(" =======' divider after '")
                        .append(currentFilename)
                        .append(" <<<<<<< SEARCH' but not found.\n");
                revertLinesToLeftover(leftoverText, lines[searchLineIndex], beforeLines, (String) null);
            }
        }

        // After processing all lines, flush leftover text
        flushLeftoverText(leftoverText, outputBlocks);

        String errorText = parseErrors.isEmpty() ? null : parseErrors.toString();
        return new ExtendedParseResult(outputBlocks, errorText);
    }

    /**
     * If leftover text is non-blank, add it as a plain-text block; then reset it.
     */
    private static void flushLeftoverText(StringBuilder leftover, List<OutputBlock> outputBlocks) {
        var text = leftover.toString();
        if (!text.isBlank()) {
            outputBlocks.add(OutputBlock.plain(text));
        }
        leftover.setLength(0);
    }

    /**
     * Reverts the lines belonging to a malformed block back into leftover text (as plain text),
     * including the original "SEARCH filename" line, any collected lines, and an optional trailing line.
     */
    private static void revertLinesToLeftover(StringBuilder leftover,
                                              String searchLine,
                                              List<String> collectedLines,
                                              String trailingLine) {
        leftover.append(searchLine).append("\n");
        for (var ln : collectedLines) {
            leftover.append(ln).append("\n");
        }
        if (trailingLine != null) {
            leftover.append(trailingLine).append("\n");
        }
    }

    /**
     * Reverts the lines belonging to a malformed block back into leftover text,
     * including the original "SEARCH filename" line, any collected lines, and
     * a list of "afterLines".
     */
    private static void revertLinesToLeftover(StringBuilder leftover,
                                              String searchLine,
                                              List<String> beforeLines,
                                              List<String> afterLines)
    {
        leftover.append(searchLine).append("\n");
        for (var ln : beforeLines) {
            leftover.append(ln).append("\n");
        }
        if (afterLines != null) {
            for (var ln : afterLines) {
                leftover.append(ln).append("\n");
            }
        }
    }

    /**
     * Parses the given content into ParseResult
     */
    public static ParseResult parseEditBlocks(String content) {
        var all = parseAllBlocks(content);
        var editBlocks = all.blocks.stream().filter(b -> b.block != null).map(b -> b.block).toList();
        return new ParseResult(editBlocks, all.parseError);
    }

    /**
     * Attempts perfect/whitespace replacements, then tries "...", then fuzzy.
     * Returns the post-replacement content, or null if no match found.
     * Throws AmbiguousMatchException if multiple matches are found.
     */
    static String replaceMostSimilarChunk(String content, String target, String replace)
            throws AmbiguousMatchException, NoMatchException
    {
        // 1) prep for line-based matching
        ContentLines originalCL = prep(content);
        ContentLines targetCl = prep(target);
        ContentLines replaceCL = prep(replace);

        // 2) perfect or whitespace approach
        String attempt = perfectOrWhitespace(originalCL.lines, targetCl.lines, replaceCL.lines);
        if (attempt != null) {
            return attempt;
        }

        // 3) handle triple-dot expansions
        try {
            attempt = tryDotdotdots(content, target, replace);
            if (attempt != null) {
                return attempt;
            }
        } catch (IllegalArgumentException e) {
            // ignore if it fails
        }

        // 3a) If that failed, attempt dropping a spurious leading blank line from the "search" block:
        if (targetCl.lines.length > 2 && targetCl.lines[0].trim().isEmpty()) {
            String[] splicedTarget = Arrays.copyOfRange(targetCl.lines, 1, targetCl.lines.length);
            String[] splicedReplace = Arrays.copyOfRange(replaceCL.lines, 1, replaceCL.lines.length);

            attempt = perfectOrWhitespace(originalCL.lines, splicedTarget, splicedReplace);
            if (attempt != null) {
                return attempt;
            }

            attempt = tryDotdotdots(content,
                                    String.join("", splicedTarget),
                                    String.join("", splicedReplace));
            if (attempt != null) {
                return attempt;
            }
        }

        throw new NoMatchException("No matching oldLines found in content");
    }

    /** Counts how many leading lines in 'lines' are completely blank (trim().isEmpty()). */
    static int countLeadingBlankLines(String[] lines) {
        int c = 0;
        for (String ln : lines) {
            if (ln.trim().isEmpty()) {
                c++;
            } else {
                break;
            }
        }
        return c;
    }

    /**
     * If the search/replace has lines of "..." as placeholders, do naive partial replacements.
     */
    public static String tryDotdotdots(String whole, String target, String replace) throws NoMatchException {
        // If there's no "..." in target or whole, skip
        if (!target.contains("...") && !whole.contains("...")) {
            return null;
        }
        // splits on lines of "..."
        Pattern dotsRe = Pattern.compile("(?m)^\\s*\\.\\.\\.\\s*$");

        String[] targetPieces = dotsRe.split(target);
        String[] replacePieces = dotsRe.split(replace);

        if (targetPieces.length != replacePieces.length) {
            throw new IllegalArgumentException("Unpaired ... usage in search/replace");
        }

        String result = whole;
        for (int i = 0; i < targetPieces.length; i++) {
            String pp = targetPieces[i];
            String rp = replacePieces[i];

            if (pp.isEmpty() && rp.isEmpty()) {
                // no content
                continue;
            }
            if (!pp.isEmpty()) {
                if (!result.contains(pp)) {
                    throw new NoMatchException("Partial replacement failed: target piece not found: " + pp);
                }
                // replace only the first occurrence
                result = result.replaceFirst(Pattern.quote(pp), Matcher.quoteReplacement(rp));
            } else {
                // target piece empty, but replace piece is not -> just append
                result += rp;
            }
        }
        return result;
    }

    /**
     * Tries perfect replace first, then leading-whitespace-insensitive.
     * Throws AmbiguousMatchException if more than one match is found in either step,
     * or NoMatchException if no matches are found
     */
    public static String perfectOrWhitespace(String[] originalLines,
                                             String[] targetLines,
                                             String[] replaceLines)
            throws AmbiguousMatchException, NoMatchException {
        try {
            return perfectReplace(originalLines, targetLines, replaceLines);
        } catch (NoMatchException e) {
            return replaceIgnoringWhitespace(originalLines, targetLines, replaceLines);
        }
    }

    /**
     * Tries exact line-by-line match. Returns the post-replacement lines on success.
     * Throws AmbiguousMatchException if multiple exact matches are found.
     * Throws NoMatchException if no exact match is found.
     */
    public static String perfectReplace(String[] originalLines,
                                        String[] targetLines,
                                        String[] replaceLines)
            throws AmbiguousMatchException, NoMatchException
    {
        // special-case replace entire file (empty target)
        if (targetLines.length == 0) {
            return String.join("", replaceLines);
        }

        List<Integer> matches = new ArrayList<>();

        outer:
        for (int i = 0; i <= originalLines.length - targetLines.length; i++) {
            for (int j = 0; j < targetLines.length; j++) {
                if (!Objects.equals(originalLines[i + j], targetLines[j])) {
                    continue outer;
                }
            }
            matches.add(i);
            if (matches.size() > 1) {
                throw new AmbiguousMatchException("Multiple exact matches found for the oldLines");
            }
        }

        if (matches.isEmpty()) {
            throw new NoMatchException("No exact matches found for the search block");
        }

        // Exactly one match
        int matchStart = matches.getFirst();
        List<String> newLines = new ArrayList<>();
        // everything before
        newLines.addAll(Arrays.asList(originalLines).subList(0, matchStart));
        // add replacement
        newLines.addAll(Arrays.asList(replaceLines));
        // everything after
        newLines.addAll(Arrays.asList(originalLines)
                                .subList(matchStart + targetLines.length, originalLines.length));

        return String.join("", newLines);
    }

    /**
     * Attempt a line-for-line match ignoring whitespace. If found, replace that
     * slice by adjusting each replacement line's indentation to preserve the relative
     * indentation from the 'search' lines.
     * Throws AmbiguousMatchException if multiple matches are found ignoring whitespace.
     * Throws NoMatchException if no match is found ignoring whitespace, or if the search block contained only whitespace.
     */
    static String replaceIgnoringWhitespace(String[] originalLines,
                                            String[] targetLines,
                                            String[] replaceLines)
            throws AmbiguousMatchException, NoMatchException
    {
        var truncatedTarget = removeLeadingTrailingEmptyLines(targetLines);
        var truncatedReplace = removeLeadingTrailingEmptyLines(replaceLines);

        if (truncatedTarget.length == 0) {
            // Empty target is handled by perfectReplace -- this means we just had whitespace, so fail the edit
            return null;
        }

        List<Integer> matches = new ArrayList<>();
        int needed = truncatedTarget.length;

        for (int start = 0; start <= originalLines.length - needed; start++) {
            if (matchesIgnoringWhitespace(originalLines, start, truncatedTarget)) {
                matches.add(start);
                if (matches.size() > 1) {
                    throw new AmbiguousMatchException("No exact matches found, and multiple matches found ignoring whitespace");
                }
            }
        }

        if (matches.isEmpty()) {
            throw new NoMatchException("No matches found ignoring whitespace");
        }

        // Exactly one match
        int matchStart = matches.getFirst();

        List<String> newLines = new ArrayList<>(Arrays.asList(originalLines).subList(0, matchStart));
        if (truncatedReplace.length > 0) {
            var adjusted = getLeadingWhitespace(originalLines[matchStart])
                    + truncatedReplace[0].trim() + "\n";
            newLines.add(adjusted);
            newLines.addAll(Arrays.asList(truncatedReplace).subList(1, truncatedReplace.length));
        }
        newLines.addAll(Arrays.asList(originalLines)
                                .subList(matchStart + needed, originalLines.length));
        return String.join("", newLines);
    }

    private static String[] removeLeadingTrailingEmptyLines(String[] targetLines) {
        int pStart = 0;
        while (pStart < targetLines.length && targetLines[pStart].trim().isEmpty()) {
            pStart++;
        }
        // Skip trailing blank lines in the search block
        int pEnd = targetLines.length;
        while (pEnd > pStart && targetLines[pEnd - 1].trim().isEmpty()) {
            pEnd--;
        }
        return Arrays.copyOfRange(targetLines, pStart, pEnd);
    }

    /**
     * return true if the targetLines match the originalLines starting at 'start', ignoring whitespace.
     */
    static boolean matchesIgnoringWhitespace(String[] originalLines, int start, String[] targetLines) {
        if (start + targetLines.length > originalLines.length) {
            return false;
        }
        for (int i = 0; i < targetLines.length; i++) {
            if (!nonWhitespace(originalLines[start + i]).equals(nonWhitespace(targetLines[i]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the non-whitespace characters in `line`
     */
    private static String nonWhitespace(String line) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isWhitespace(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * @return the whitespace prefix in this line.
     */
    static String getLeadingWhitespace(String line) {
        assert line.endsWith("\n");
        int count = 0;
        for (int i = 0; i < line.length() - 1; i++) { // -1 because we threw newline onto everything
            if (Character.isWhitespace(line.charAt(i))) {
                count++;
            } else {
                break;
            }
        }
        return line.substring(0, count);
    }

    /**
     * Scanning for a filename up to 3 lines above the HEAD block index. If none found, fallback to
     * currentFilename if it's not null.
     */
    private static ContentLines prep(String content) {
        Objects.requireNonNull(content, "Content cannot be null");
        // ensure it ends with newline
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content += "\n";
        }
        String[] lines = content.split("\n", -1);
        lines = Arrays.copyOf(lines, lines.length - 1); // chop off empty element at the end
        // re-add newlines
        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i] + "\n";
        }
        return new ContentLines(content, lines);
    }

    private record ContentLines(String original, String[] lines) { }

    /**
     * Resolves a filename string to a ProjectFile.
     * Handles partial paths, checks against editable files, tracked files, and project files.
     *
     * @param cm         The context manager.
     * @param filename   The filename string to resolve (potentially partial).
     * @param createNew  If true, allow resolving to a non-existent file path for creation.
     * @return The resolved ProjectFile.
     * @throws SymbolNotFoundException if the file cannot be found.
     * @throws SymbolAmbiguousException if the filename matches multiple files.
     */
    static ProjectFile resolveProjectFile(IContextManager cm, String filename, boolean createNew)
            throws SymbolNotFoundException, SymbolAmbiguousException
    {
        var file = cm.toFile(filename);

        // 1. Exact match (common case)
        if (file.exists() || createNew) {
            return file;
        }

        // 2. Check editable files (case-insensitive basename match)
        var editableMatches = cm.getEditableFiles().stream()
                .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                .toList();
        if (editableMatches.size() == 1) {
            logger.debug("Resolved partial filename [{}] to editable file [{}]", filename, editableMatches.getFirst());
            return editableMatches.getFirst();
        }
        if (editableMatches.size() > 1) {
            throw new SymbolAmbiguousException("Filename '%s' matches multiple editable files: %s".formatted(filename, editableMatches));
        }

        // 3. Check tracked files in git repo (substring match)
        var repo = cm.getRepo();
        if (repo != null) {
            var trackedMatches = repo.getTrackedFiles().stream()
                    .filter(f -> f.toString().contains(filename))
                    .toList();
            if (trackedMatches.size() == 1) {
                logger.debug("Resolved partial filename [{}] to tracked file [{}]", filename, trackedMatches.getFirst());
                return trackedMatches.getFirst();
            }
            if (trackedMatches.size() > 1) {
                // Prefer exact basename match if available among tracked files
                var exactBaseMatches = trackedMatches.stream()
                        .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                        .toList();
                if (exactBaseMatches.size() == 1) {
                     logger.debug("Resolved ambiguous tracked filename [{}] to exact basename match [{}]", filename, exactBaseMatches.getFirst());
                     return exactBaseMatches.getFirst();
                }
                throw new SymbolAmbiguousException("Filename '%s' matches multiple tracked files: %s".formatted(filename, trackedMatches));
            }
        }

        // 4. Check all project files (case-insensitive basename match) - last resort
        var project = cm.getProject();
        if (project != null) {
            var projectFileMatches = project.getFiles().stream()
                    .filter(Objects::nonNull)
                    .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                    .toList();
            if (projectFileMatches.size() == 1) {
                logger.debug("Resolved partial filename [{}] to project file [{}]", filename, projectFileMatches.getFirst());
                return projectFileMatches.getFirst();
            }
            if (projectFileMatches.size() > 1) {
                throw new SymbolAmbiguousException("Filename '%s' matches multiple project files: %s".formatted(filename, projectFileMatches));
            }
        }

        // 5. Not found anywhere
        throw new SymbolNotFoundException("Filename '%s' could not be resolved to an existing file.".formatted(filename));
    }
}
