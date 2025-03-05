package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.prompts.BuildPrompts;
import io.github.jbellis.brokk.prompts.DefaultPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LLM {
    private static final Logger logger = LogManager.getLogger(LLM.class);
    private static final int MAX_PARSE_ATTEMPTS = 3;

    /**
     * Implementation of the LLM session that runs in a separate thread.
     */
    public static void runSession(Coder coder, IConsoleIO io, StreamingChatLanguageModel model, String userInput) {
        if (!coder.isLlmAvailable()) {
            io.toolError("No LLM available (missing API keys)");
            return;
        }

        // Track original contents of files before any changes
        var originalContents = new HashMap<RepoFile, String>();

        // `messages` is everything we send to the LLM;
        // `pendingHistory` contains user instructions + llm response but omits the Context messages
        List<ChatMessage> pendingHistory = new ArrayList<>();
        var messages = DefaultPrompts.instance.collectMessages((ContextManager) coder.contextManager);
        var requestMsg = new UserMessage("<goal>\n%s\n</goal>".formatted(userInput.trim()));

        // Reflection loop state tracking
        int parseErrorAttempts = 0;
        List<String> buildErrors = new ArrayList<>();
        List<EditBlock.SearchReplaceBlock> blocks = new ArrayList<>();

        // give user some feedback -- this isn't in the main loop because after the first iteration
        // we give more specific feedback when we need to make another request
        io.toolOutput("Request sent");

        while (true) {
            messages.add(requestMsg);

            // Check for interruption before sending to LLM
            if (Thread.currentThread().isInterrupted()) {
                io.toolOutput("Session interrupted");
                break;
            }
            
            // Actually send the message to the LLM and get the response
            logger.debug("Sending to LLM [only last message shown]: {}", requestMsg);
            var llmResponse = coder.sendStreaming(model, messages, true);
            logger.debug("response:\n{}", llmResponse);
            if (llmResponse == null) {
                // Interrupted or error. sendStreaming is responsible for giving feedback to user
                break;
            }

            var llmText = llmResponse.aiMessage().text();
            if (llmText.isBlank()) {
                io.toolError("Empty response from LLM, will retry");
                continue;
            }

            // Add the request/response to pending history
            pendingHistory.addAll(List.of(requestMsg, llmResponse.aiMessage()));
            messages.add(llmResponse.aiMessage());

            // Gather all edit blocks in the reply
            var parseResult = EditBlock.findOriginalUpdateBlocks(llmText, coder.contextManager.getEditableFiles());
            logger.debug("Parsed {} blocks", blocks.size());
            blocks.addAll(parseResult.blocks());
            if (parseResult.parseError() != null) {
                if (parseResult.blocks().isEmpty()) {
                    requestMsg = new UserMessage(parseResult.parseError());
                    io.toolOutput("Failed to parse LLM response; retrying");
                } else {
                    var msg = """
                    It looks like we got cut off.  The last block I successfully parsed was
                    <block>
                    %s
                    </block>
                    Please continue from there (WITHOUT repeating that one).
                    """.stripIndent().formatted(parseResult.blocks().getLast());
                    requestMsg = new UserMessage(msg);
                    io.toolOutput("Incomplete response after %d blocks parsed; retrying".formatted(parseResult.blocks().size()));
                }
                continue;
            }

            logger.debug("{} total blocks", blocks.size());
            // Check for interruption before proceeding to edit files
            if (Thread.currentThread().isInterrupted()) {
                io.toolOutput("Session interrupted");
                break;
            }

            // auto-add files referenced in search/replace blocks that are not already editable
            var filesToAdd = blocks.stream()
                    .filter(block -> block.filename() != null)
                    .filter(block -> !coder.contextManager.getEditableFiles().contains(coder.contextManager.toFile(block.filename())))
                    .distinct()
                    .map(EditBlock.SearchReplaceBlock::filename)
                    .map(coder.contextManager::toFile)
                    .toList();
            logger.debug("Auto-adding as editable: {}", filesToAdd);
            if (!filesToAdd.isEmpty()) {
                io.shellOutput("Editing additional files " + filesToAdd);
                coder.contextManager.addFiles(filesToAdd);
            }

            // Attempt to apply any code edits from the LLM
            var editResult = EditBlock.applyEditBlocks(coder.contextManager, io, blocks);
            editResult.originalContents().forEach(originalContents::putIfAbsent);
            logger.debug("Failed blocks: {}", editResult.failedBlocks());

            // Check for parse/match failures first
            var parseReflection = getParseReflection(editResult.failedBlocks(), blocks, coder.contextManager, io);
            blocks.clear(); // don't re-apply on the next loop
            if (!parseReflection.isEmpty()) {
                io.toolOutput("Attempting to fix parse/match errors...");
                model = coder.models.applyModel();
                requestMsg = new UserMessage(parseReflection);
                parseErrorAttempts++;
                continue;
            } else {
                parseErrorAttempts = 0; // Reset on success
            }

            // Check for interruption before checking build
            if (Thread.currentThread().isInterrupted()) {
                io.toolOutput("Session interrupted");
                break;
            }

            // If parsing succeeded, check build
            var buildReflection = getBuildReflection(coder.contextManager, io, buildErrors);
            if (buildReflection.isEmpty()) {
                break;
            }

            // Check if we should continue trying
            if (!shouldContinue(coder, parseErrorAttempts, buildErrors, io)) {
                break;
            }

            io.toolOutput("Attempting to fix build errors...");
            // Use EDIT model (smarter) for build fixes
            model = coder.models.editModel();
            requestMsg = new UserMessage(buildReflection);
        }

        // Add all pending messages to history in one batch
        if (!pendingHistory.isEmpty()) {
            coder.contextManager.addToHistory(pendingHistory, originalContents);
        }
    }

    /**
     * Generates a reflection message based on parse errors from failed edit blocks
     */
    private static String getParseReflection(List<EditBlock.FailedBlock> failedBlocks,
                                            List<EditBlock.SearchReplaceBlock> blocks,
                                            IContextManager contextManager,
                                            IConsoleIO io) {
        assert !blocks.isEmpty();

        if (failedBlocks.isEmpty()) {
            return "";
        }

        var reflectionMsg = new StringBuilder();

        var suggestions = EditBlock.collectSuggestions(failedBlocks, contextManager);
        var failedApplyMessage = handleFailedBlocks(suggestions, blocks.size() - failedBlocks.size());
        io.shellOutput(failedApplyMessage);
        reflectionMsg.append(failedApplyMessage);

        return reflectionMsg.toString();
    }

    /**
     * Generates a reflection message for build errors
     */
    private static String getBuildReflection(IContextManager cm, IConsoleIO io, List<String> buildErrors) {
        var cmd = cm.getProject().getBuildCommand();
        if (cmd == null || cmd.isBlank()) {
            io.toolOutput("No build command configured");
            return "";
        }

        io.toolOutput("Running " + cmd);
        var result = Environment.instance.captureShellCommand(cmd);
        logger.debug("Build command result: {}", result);
        if (result.error() == null) {
            io.shellOutput("Build successful");
            buildErrors.clear(); // Reset on successful build
            return "";
        }

        io.shellOutput(result.error() + "\n\n" + result.output());
        buildErrors.add(result.error() + "\n\n" + result.output());

        StringBuilder query = new StringBuilder("The build failed. Here is the history of build attempts:\n\n");
        for (int i = 0; i < buildErrors.size(); i++) {
            query.append("=== Attempt ").append(i + 1).append(" ===\n")
                    .append(buildErrors.get(i))
                    .append("\n\n");
        }
        query.append("Please fix these build errors.");
        return query.toString();
    }

    /**
     * Determines whether to continue with reflection passes
     */
    private static boolean shouldContinue(Coder coder, int parseErrorAttempts, List<String> buildErrors, IConsoleIO io) {
        // If we have parse errors, limit to MAX_PARSE_ATTEMPTS attempts
        if (parseErrorAttempts > 0) {
            if (parseErrorAttempts < MAX_PARSE_ATTEMPTS) {
                return true;
            }
            io.toolOutput("Parse retry limit reached, stopping.");
            return false;
        }

        // For build errors, check if we're making progress
        if (buildErrors.size() > 1) {
            if (isBuildProgressing(coder, buildErrors)) {
                return true;
            }
            io.toolOutput("Build errors are not improving, stopping.");
            return false;
        }

        return true;
    }

    /**
     * Helper to get a quick response from the LLM without streaming to determine if build errors are improving
     */
    private static boolean isBuildProgressing(Coder coder, List<String> buildResults) {
        var messages = BuildPrompts.instance.collectMessages(buildResults);
        var response = coder.sendMessage(messages);

        // Keep trying until we get one of our expected tokens
        while (!response.contains("BROKK_PROGRESSING") && !response.contains("BROKK_FLOUNDERING")) {
            messages = new ArrayList<>(messages);
            messages.add(new AiMessage(response));
            messages.add(new UserMessage("Please indicate either BROKK_PROGRESSING or BROKK_FLOUNDERING."));
            response = coder.sendMessage(messages);
        }

        return response.contains("BROKK_PROGRESSING");
    }

    /**
     * Generates a reflection message for failed edit blocks
     */
    private static String handleFailedBlocks(Map<EditBlock.FailedBlock, String> failed, int succeededCount) {
        if (failed.isEmpty()) {
            return "";
        }

        // build an error message
        int count = failed.size();
        boolean singular = (count == 1);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(count).append(" SEARCH/REPLACE block")
          .append(singular ? " " : "s ")
          .append("failed to match!\n");

        for (var entry : failed.entrySet()) {
            var f = entry.getKey();
            String fname = (f.block().filename() == null ? "(none)" : f.block().filename());
            sb.append("## Failed to match in file: ").append(fname).append("\n");
            sb.append("<<<<<<< SEARCH\n").append(f.block().beforeText())
              .append("=======\n").append(f.block().afterText())
              .append(">>>>>>> REPLACE\n\n");

            String suggestion = entry.getValue();
            sb.append(suggestion).append("\n");
        }

        sb.append("The SEARCH text must match exactly the lines in the file.\n");
        if (succeededCount > 0) {
            sb.append("\n# The other ").append(succeededCount).append(" SEARCH/REPLACE block")
              .append(succeededCount == 1 ? " was" : "s were").append(" applied successfully.\n");
            sb.append("Don't re-send them. Just fix the failing blocks above.\n");
        }

        return sb.toString();
    }
}
