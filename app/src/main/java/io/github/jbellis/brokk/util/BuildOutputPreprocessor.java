package io.github.jbellis.brokk.util;

import com.google.common.base.Splitter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Llm;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for preprocessing long build outputs to extract only the most relevant errors before sending to LLM agents
 * for analysis and fixes. This helps reduce context window usage while ensuring actionable error information is
 * preserved.
 *
 * <p>Provides both lightweight path sanitization and full LLM-based error extraction with timeout protection. For LLM
 * context optimization only - use raw output for success/failure decisions.
 */
public class BuildOutputPreprocessor {
    private static final Logger logger = LogManager.getLogger(BuildOutputPreprocessor.class);

    /**
     * Minimum number of lines in build output to trigger preprocessing. Below this threshold, the original output is
     * returned unchanged.
     */
    public static final int THRESHOLD_LINES = 200;

    /**
     * Maximum number of errors to extract from the build output. This limits context size while ensuring we capture
     * multiple related issues.
     */
    public static final int MAX_EXTRACTED_ERRORS = 10;

    /**
     * Lightweight path sanitization without LLM processing. Converts absolute paths to relative paths for cleaner
     * output.
     *
     * @param rawBuildOutput The build output to sanitize (use empty string if no output)
     * @param contextManager The context manager to access project root
     * @return Sanitized output with relative paths, or original output if sanitization fails
     */
    public static String sanitizeOnly(String rawBuildOutput, IContextManager contextManager) {
        try {
            return sanitizeBuildOutput(rawBuildOutput, contextManager);
        } catch (Exception e) {
            logger.warn("Exception during build output sanitization: {}. Using original output.", e.getMessage(), e);
            return rawBuildOutput;
        }
    }

    /**
     * Full pipeline: sanitization + LLM-based error extraction for verbose output.
     *
     * @param rawBuildOutput The build output to process (use empty string if no output)
     * @param contextManager The context manager to access project root and LLM
     * @return Processed output with extracted errors, or original output if processing fails
     */
    public static String processForLlm(String rawBuildOutput, IContextManager contextManager)
            throws InterruptedException {
        logger.debug(
                "Processing build output through standard pipeline. Original length: {} chars",
                rawBuildOutput.length());

        // Step 1: Sanitize build output (cosmetic cleanup)
        String sanitized = sanitizeBuildOutput(rawBuildOutput, contextManager);
        logger.debug("After sanitization: {} chars", sanitized.length());

        // Step 2: Preprocess for context optimization
        String processed = maybePreprocessOutput(sanitized, contextManager);
        logger.debug("After preprocessing: {} chars", processed.length());

        return processed;
    }

    /**
     * Preprocesses build output by extracting the most relevant errors when the output is longer than the threshold.
     * Uses the quickest model for fast error extraction, relying on the LLM service's built-in timeout protection.
     *
     * @param buildOutput The raw build output from compilation/test commands (empty string if no output)
     * @param cm The context manager to access the quickest model via getLlm
     * @return Preprocessed output containing only relevant errors, or original output if preprocessing is not needed or
     *     fails. Never returns null - empty input returns empty string.
     */
    public static String maybePreprocessOutput(String buildOutput, IContextManager cm) throws InterruptedException {
        List<String> lines = Splitter.on('\n').splitToList(buildOutput);
        logger.debug("Build output has {} lines, preprocessing threshold is {}", lines.size(), THRESHOLD_LINES);
        if (lines.size() <= THRESHOLD_LINES) {
            return buildOutput;
        }

        // Limit build output to fit within token constraints
        var model = cm.getService().quickestModel();
        var llm = cm.getLlm(model, "BuildOutputPreprocessor");
        String truncatedOutput = truncateToTokenLimit(buildOutput, model, cm);
        return preprocessOutput(truncatedOutput, cm, llm);
    }

    private static String preprocessOutput(String truncatedOutput, IContextManager contextManager, Llm llm)
            throws InterruptedException {

        var systemMessage = new SystemMessage(
                """
            You are familiar with common build and lint tools. Extract the most relevant compilation
            and build errors from verbose output.

            EXAMPLES OF TOOLS YOU MAY ENCOUNTER:
            Compilers: javac, tsc (TypeScript), rustc, gcc
            Linters: eslint, pylint, spotless, checkstyle

            Focus on up to %d actionable errors that developers need to fix:
            1. Compilation errors (syntax errors, type errors, missing imports)
            2. Test failures with specific failure reasons
            3. Dependency resolution failures
            4. Build configuration errors

            For each error, include:
            - File path and line number when available
            - Specific error message
            - 2-3 lines of context when helpful
            - Relevant stack trace snippets (not full traces)

            ERROR HANDLING RULES:
            - Include each error message verbatim
            - When you see multiple errors in the same file with the same cause, give only the first
            - IGNORE verbose progress messages, successful compilation output,
              general startup/shutdown logs, and non-blocking warnings

            Return the extracted errors in a clean, readable format.
            """
                        .stripIndent()
                        .formatted(MAX_EXTRACTED_ERRORS));

        var userMessage = new UserMessage(
                """
            Please extract the most relevant compilation/build errors from this build output:

            ```
            %s
            ```
            """
                        .stripIndent()
                        .formatted(truncatedOutput));
        var messages = List.of(systemMessage, userMessage);

        var result = llm.sendRequest(messages, false);
        if (result.error() != null) {
            logPreprocessingError(result.error(), contextManager);
            return truncatedOutput;
        }

        String extractedErrors = result.text().trim();
        if (extractedErrors.isBlank()) {
            logger.warn("Build output preprocessing returned empty result. Using original output.");
            return truncatedOutput;
        }

        return extractedErrors;
    }

    /**
     * Truncates build output to fit within conservative token limits by repeatedly halving the line count. Uses a
     * conservative estimate since our tokenizer is approximate and we want to avoid exceeding limits.
     *
     * @param buildOutput The original build output
     * @param model
     * @return Truncated output that should fit within token constraints
     */
    @SuppressWarnings("UnusedVariable")
    private static String truncateToTokenLimit(String buildOutput, StreamingChatModel model, IContextManager cm) {
        int targetTokens = cm.getService().getMaxInputTokens(model) / 2;
        logger.debug("Using conservative target of {} tokens for build output", targetTokens);

        List<String> lines = Splitter.on('\n').splitToList(buildOutput);
        int currentLineCount = lines.size();

        // Repeatedly halve line count until we're under target
        while (lines.size() > 1) {
            var approximateTokens = Messages.getApproximateTokens(buildOutput);
            if (approximateTokens <= targetTokens) {
                logger.debug(
                        "Build output estimated at {} tokens, under target of {}", approximateTokens, targetTokens);
                return buildOutput;
            }

            currentLineCount = currentLineCount / 2;
            var truncatedLines = lines.subList(0, currentLineCount);
            buildOutput = String.join("\n", truncatedLines);
        }

        return buildOutput;
    }

    private static void logPreprocessingError(@Nullable Throwable error, IContextManager contextManager) {
        if (error == null) {
            logger.warn("Build output preprocessing failed with null error. Using original output.");
            return;
        }

        boolean isTimeout = error.getMessage() != null && error.getMessage().contains("timed out");
        if (isTimeout) {
            logger.warn(
                    "Build output preprocessing timed out (quickest model: {}). Using original output.",
                    contextManager.getService().quickestModel().getClass().getSimpleName());
        } else {
            logger.warn("Error during build output preprocessing: {}. Using original output.", error.getMessage());
        }
    }

    /**
     * Converts absolute paths to relative paths for LLM consumption. Handles Windows/Unix paths and prevents accidental
     * partial matches.
     */
    private static String sanitizeBuildOutput(String text, IContextManager contextManager) {
        var root = contextManager.getProject().getRoot().toAbsolutePath().normalize();
        var rootAbs = root.toString();

        // Build forward- and back-slash variants with a trailing separator
        var rootFwd = rootAbs.replace('\\', '/');
        if (!rootFwd.endsWith("/")) {
            rootFwd = rootFwd + "/";
        }
        var rootBwd = rootAbs.replace('/', '\\');
        if (!rootBwd.endsWith("\\")) {
            rootBwd = rootBwd + "\\";
        }

        // Case-insensitive replacement and boundary-checked:
        // - (?<![A-Za-z0-9._-]) ensures the match is not preceded by a typical path/token character.
        // - The trailing separator in rootFwd/rootBwd ensures we only match a directory prefix of a larger path.
        // - (?=\S) ensures there is at least one non-whitespace character following the prefix (i.e., a larger path).
        var sanitized = text;
        var forwardPattern = Pattern.compile("(?i)(?<![A-Za-z0-9._-])" + Pattern.quote(rootFwd) + "(?=\\S)");
        var backwardPattern = Pattern.compile("(?i)(?<![A-Za-z0-9._-])" + Pattern.quote(rootBwd) + "(?=\\S)");

        sanitized = forwardPattern.matcher(sanitized).replaceAll("");
        sanitized = backwardPattern.matcher(sanitized).replaceAll("");

        return sanitized;
    }
}
