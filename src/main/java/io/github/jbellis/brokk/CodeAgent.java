package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.BuildAgent.BuildDetails;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.BuildPrompts;
import io.github.jbellis.brokk.prompts.DefaultPrompts;
import io.github.jbellis.brokk.prompts.QuickEditPrompts;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;


public class CodeAgent {
    private static final Logger logger = LogManager.getLogger(CodeAgent.class);
    private static final int MAX_PARSE_ATTEMPTS = 3;
    private static final int MAX_BUILD_ATTEMPTS = 3; // Max attempts to fix build errors

    /** Enum representing the reason a CodeAgent session concluded. */
    public enum StopReason {
        /** The agent successfully completed the goal. */
        SUCCESS,
        /** The user interrupted the session. */
        INTERRUPTED,
        /** The LLM returned an error after retries. */
        LLM_ERROR,
        /** The LLM returned an empty or blank response after retries. */
        EMPTY_RESPONSE,
        /** The LLM response could not be parsed after retries. */
        PARSE_ERROR_LIMIT,
        /** Applying edits failed after retries. */
        APPLY_ERROR_LIMIT,
        /** Build errors occurred and were not improving after retries. */
        BUILD_ERROR_LIMIT,
        /** The LLM attempted to edit a read-only file. */
        READ_ONLY_EDIT
    }

    /**
     * Represents the outcome of a CodeAgent session, containing all necessary information
     * to update the context history.
     *
     * @param messages The list of chat messages exchanged during the session.
     * @param originalContents A map of project files to their original content before edits.
     * @param actionDescription A description of the user's goal for the session.
     * @param finalLlmOutput The final raw text output from the LLM.
     * @param stopReason The reason the session concluded.
     */
    public record SessionResult(List<ChatMessage> messages, Map<ProjectFile, String> originalContents, String actionDescription, String finalLlmOutput, StopReason stopReason) {}

    /**
     * Implementation of the LLM session that runs in a separate thread.
     * Uses the provided model for the initial request and potentially switches for fixes.
     *
     * @param contextManager The ContextManager instance, providing access to Coder, IO, Project, etc.
     * @param model The model selected by the user for the main task.
     * @param userInput The user's goal/instructions.
     * @return A SessionResult containing the conversation history and original file contents, or null if no history was generated.
     */
    public static SessionResult runSession(ContextManager contextManager, StreamingChatLanguageModel model, String userInput) {
        // Track original contents of files before any changes
        var originalContents = new HashMap<ProjectFile, String>();
       var io = contextManager.getIo();
       var coder = contextManager.getCoder();

        // We'll collect the conversation as ChatMessages to store in context history.
        var sessionMessages = new ArrayList<ChatMessage>();
        // The user's initial request, becomes the prompt for the next LLM call
        var nextRequest = new UserMessage("<goal>\n%s\n</goal>".formatted(userInput.trim()));

        // Start verification command inference concurrently
        CompletableFuture<String> verificationCommandFuture = determineVerificationCommandAsync(contextManager, coder, userInput); // Use cm

        // Reflection loop state tracking
        int parseErrorAttempts = 0;
        int blocksAppliedWithoutBuild = 0;
        List<String> buildErrors = new ArrayList<>();
        List<EditBlock.SearchReplaceBlock> blocks = new ArrayList<>(); // Accumulated blocks from potentially partial responses

        // give user some feedback -- this isn't in the main loop because after the first iteration
        // we give more specific feedback when we need to make another request
        io.systemOutput("Request sent");

        StopReason stopReason = null;
        while (true) {
            // Check for interruption before sending to LLM
            if (Thread.currentThread().isInterrupted()) {
                io.systemOutput("Session interrupted");
                stopReason = StopReason.INTERRUPTED;
                break;
            }

            // refresh with updated file contents
            var reminder = DefaultPrompts.reminderForModel(contextManager.getModels(), model);
            // collect all messages including prior session history
            var allMessages = DefaultPrompts.instance.collectMessages(contextManager, sessionMessages, reminder);
            allMessages.add(nextRequest); // Add the specific request for this turn

            // Actually send the message to the LLM and get the response
            logger.debug("Sending request to model: {}", contextManager.getModels().nameOf(model));
            var streamingResult = coder.sendStreaming(model, allMessages, true);

            // 1) If user cancelled
            if (streamingResult.cancelled()) {
                io.systemOutput("Session interrupted");
                stopReason = StopReason.INTERRUPTED;
                break;
            }

            // 2) Handle errors or empty responses
            if (streamingResult.error() != null) {
                logger.warn("Error from LLM: {}", streamingResult.error().getMessage());
                io.systemOutput("LLM returned an error even after retries. Ending session");
                stopReason = StopReason.LLM_ERROR;
                break;
            }

            var llmResponse = streamingResult.chatResponse();
            if (llmResponse == null) {
                io.systemOutput("Empty LLM response even after retries. Ending session.");
                stopReason = StopReason.EMPTY_RESPONSE;
                break;
            }

            String llmText = llmResponse.aiMessage().text();
            if (llmText.isBlank()) {
                io.systemOutput("Blank LLM response even after retries. Ending session.");
                stopReason = StopReason.EMPTY_RESPONSE;
                break;
            }

            // We got a valid response
            logger.debug("response:\n{}", llmText);

            // Add the request/response to session history
            sessionMessages.add(nextRequest);
            sessionMessages.add(llmResponse.aiMessage());

            // Gather all edit blocks in the reply
            var parseResult = EditBlock.parseUpdateBlocks(llmText);
            var newlyParsedBlocks = parseResult.blocks();
            blocks.addAll(newlyParsedBlocks); // Add newly parsed blocks to the accumulation
            logger.debug("{} total unapplied blocks", blocks.size());

            if (parseResult.parseError() != null) {
                if (newlyParsedBlocks.isEmpty()) {
                    // Error occurred before *any* blocks were parsed in this response
                    nextRequest = new UserMessage(parseResult.parseError());
                    io.systemOutput("Failed to parse LLM response; retrying");
                } else {
                    // Error occurred after *some* blocks were parsed - ask to continue
                    var msg = """
                            It looks like we got cut off. The last block I successfully parsed was
                            <block>
                            %s
                            </block>
                            Please continue from there (WITHOUT repeating that one).
                            """.stripIndent().formatted(newlyParsedBlocks.getLast());
                    nextRequest = new UserMessage(msg);
                    io.systemOutput("Incomplete response after %d blocks parsed; retrying".formatted(newlyParsedBlocks.size()));
                }
                continue; // Retry the LLM call with the new request asking for continuation/fix
            }

            // If we reached here, parsing was successful (or no blocks were found in this response)
            logger.debug("{} total blocks found in current response", newlyParsedBlocks.size());

            if (blocks.isEmpty() && blocksAppliedWithoutBuild == 0) {
                // No blocks found in the latest response AND no blocks were applied in previous reflection loops waiting for a build check
                // This means the LLM thinks it's done *and* we don't have pending edits waiting for a build check.
                io.systemOutput("No edits found in response; ending session");
                stopReason = StopReason.SUCCESS; // Assume completion if LLM stops providing edits and we have nothing pending
                break;
            }
            // Check for interruption before proceeding to edit files
            if (Thread.currentThread().isInterrupted()) {
                io.systemOutput("Session interrupted");
                stopReason = StopReason.INTERRUPTED;
                break;
            }

            // auto-add files referenced in search/replace blocks that are not already editable
            var filesToAdd = blocks.stream()
                    .map(EditBlock.SearchReplaceBlock::filename)
                    .filter(Objects::nonNull)
                    .distinct() // Ensure unique filenames
                    .map(coder.contextManager::toFile) // Convert to ProjectFile
                    .filter(file -> !coder.contextManager.getEditableFiles().contains(file)) // Filter out already editable
                    .toList();

            logger.debug("Auto-adding as editable: {}", filesToAdd);
            if (!filesToAdd.isEmpty()) {
                var readOnlyFilesToAdd = filesToAdd.stream()
                        .filter(file -> coder.contextManager.getReadonlyFiles().contains(file))
                        .toList();
                if (!readOnlyFilesToAdd.isEmpty()) {
                    io.systemOutput("LLM attempted to edit read-only file(s): %s. \nNo edits applied. Mark the files editable or clarify to the LLM how to approach the problem another way.".formatted(readOnlyFilesToAdd));
                    stopReason = StopReason.READ_ONLY_EDIT;
                    break;
                }
                io.systemOutput("Editing additional files " + filesToAdd);
                coder.contextManager.editFiles(filesToAdd);
            }

            // Attempt to apply *all accumulated* code edits from the LLM
            var editResult = EditBlock.applyEditBlocks(coder.contextManager, io, blocks);
            editResult.originalContents().forEach(originalContents::putIfAbsent);
            logger.debug("Failed blocks: {}", editResult.failedBlocks());
            blocksAppliedWithoutBuild += blocks.size() - editResult.failedBlocks().size();

            // Check for parse/match failures first
            var parseReflection = getParseReflection(editResult.failedBlocks(), blocks, contextManager, io);

            // Only increase parse error attempts if no blocks were successfully applied
            if (editResult.failedBlocks().size() == blocks.size()) {
                parseErrorAttempts++;
            } else {
                parseErrorAttempts = 0;
            }
            blocks.clear(); // Don't re-apply the same successful ones on the next loop
            // If there were failed blocks, attempt to fix them
            if (!parseReflection.isEmpty()) {
                if (parseErrorAttempts >= MAX_PARSE_ATTEMPTS) {
                    io.systemOutput("Parse/Apply retry limit reached; ending session");
                    break;
                }
                io.systemOutput("Attempting to fix apply/match errors...");
                nextRequest = new UserMessage(parseReflection);
                continue;
            }

            // If we get here, all accumulated blocks were applied successfully in this round.

            // Check for interruption before checking build
            if (Thread.currentThread().isInterrupted()) {
                io.systemOutput("Session interrupted");
                stopReason = StopReason.INTERRUPTED;
                break;
            }

            // If parsing/application succeeded, check build using the asynchronously inferred command
            String verificationCommand;
            try {
                // Wait for the command inference, with a timeout
                verificationCommand = verificationCommandFuture.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                logger.warn("Failed to get verification command", e);
                var bd = contextManager.getProject().getBuildDetails();
                verificationCommand = bd == null ? null : bd.buildLintCommand();
            }

            boolean buildSucceeded = checkBuild(verificationCommand, contextManager, io, buildErrors);
            blocksAppliedWithoutBuild = 0; // Reset count after build check attempt

            if (buildSucceeded) {
                // Build successful!
                stopReason = StopReason.SUCCESS;
                break;
            }

            // Check if we should continue trying to fix build errors
            if (!(buildErrors.isEmpty() || isBuildProgressing(coder, buildErrors))) {
                io.systemOutput("Build errors are not improving; ending session");
                stopReason = StopReason.BUILD_ERROR_LIMIT;
                break;
            }

            io.systemOutput("Attempting to fix build errors...");
            // Construct the reflection message based on the stored build errors
            nextRequest = new UserMessage(formatBuildErrorsForLLM(buildErrors));
            // Loop back to LLM to fix build errors
        }

        // Session finished (completed, interrupted, or failed)
        assert stopReason != null;
        String finalLlmOutput = sessionMessages.isEmpty() ? "" : Models.getText(sessionMessages.getLast());

        // Mark description based on stop reason
        boolean completedSuccessfully = stopReason == StopReason.SUCCESS;
        String finalActionDescription = completedSuccessfully ? userInput : userInput + " [" + stopReason.name() + "]";

        // Return the result for the caller to add to history
        var sessionResult = new SessionResult(List.copyOf(sessionMessages), Map.copyOf(originalContents), finalActionDescription, finalLlmOutput, stopReason);

        if (completedSuccessfully) {
            io.systemOutput("Session complete!");
        } // otherwise, code that stops the session is responsible for explaining why

        return sessionResult;
    }

    /**
     * Asynchronously determines the best verification command based on the user goal,
     * workspace summary, and stored BuildDetails. Uses the quickest model.
     * Runs on the ContextManager's background task executor.
     *
     * @param cm        The ContextManager instance.
     * @param coder     The Coder instance.
     * @param userGoal  The original user goal for the session.
     * @return A CompletableFuture containing the suggested verification command string (or null if none/error/no details).
     */
    private static CompletableFuture<String> determineVerificationCommandAsync(ContextManager cm, Coder coder, String userGoal) {
        return CompletableFuture.supplyAsync(() -> {
            BuildDetails details = cm.getProject().getBuildDetails();
            if (details == null) {
                logger.warn("No build details available, cannot determine verification command.");
                // Return null to indicate no command could be determined due to missing details
                return null;
            }

            String workspaceSummary = DefaultPrompts.formatWorkspaceSummary(cm);

            // Construct the prompt for the quick model
            var systemMessage = new SystemMessage("""
            You are a build assistant. Based on the user's goal, the project workspace, and known build details,
            determine the best single shell command to run as a minimal "smoke test" verifying that the changes achieve the goal.
            This should usually involve a few specific tests, but if the project is small, running all tests is reasonable;
            if no tests look relevant, it's fine to simply compile or lint the project without tests.
            IF A TEST FILE ISN'T SHOWN, IT DOES NOT EXIST, DO NOT GUESS AT ADDITIONAL TESTS.
            """.stripIndent());

            // Run getTestFiles synchronously within this async task
            List<ProjectFile> testFiles = getTestFiles(cm, coder);
            String testFilesString = testFiles.isEmpty() ? "(none found)" :
                                     testFiles.stream().map(ProjectFile::toString).collect(Collectors.joining("\\n"));

            var userMessage = new UserMessage("""
            **Build Details:**
            Commands: %s
            Instructions: %s

            **Workspace Summary:**
            %s

            **Test files**
            %s

            **User Goal:**
            %s

            Respond ONLY with the raw shell command, no explanation, formatting, or markdown. If no verification is needed or possible, respond with an empty string.
            """.stripIndent().formatted(
                    details.buildLintCommand(),
                    details.instructions(),
                    workspaceSummary,
                    testFilesString,
                    userGoal
            ));

            List<ChatMessage> messages = List.of(systemMessage, userMessage);

            // Call the quick model synchronously within the async task
            var result = coder.sendMessage(cm.getModels().quickestModel(), messages);

            if (result.cancelled() || result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
                logger.warn("Failed to determine verification command: {}",
                            result.error() != null ? result.error().getMessage() : "LLM unavailable or cancelled");
                // Fallback to build/lint command on LLM failure
                return details.buildLintCommand();
            }

            String command = result.chatResponse().aiMessage().text();
            // Check for null/blank/unavailable *before* trimming, as trim() on null throws NPE
            if (command == null || command.isBlank() || command.equals(Models.UNAVAILABLE)) {
                 logger.warn("LLM returned unusable verification command: '{}'. Falling back to build/lint command.", command);
                 return details.buildLintCommand(); // Fallback to default build/lint
             }

            // Basic cleanup: remove potential markdown backticks
            command = command.trim().replace("```", "");
            logger.info("Determined verification command: `{}`", command);
            return command;

        }, cm.getBackgroundTasks()); // Ensure this runs on the CM's background executor
    }

    /**
     * Identifies test files within the project using the quickest LLM.
     * This method runs synchronously as it's expected to be called within an async task.
     * No caching is performed here.
     *
     * @param cm     The ContextManager instance.
     * @param coder  The Coder instance.
     * @return A list of ProjectFile objects identified as test files. Returns an empty list if none are found or an error occurs.
     */
    private static List<ProjectFile> getTestFiles(ContextManager cm, Coder coder) {
        // Get all files from the project
        Set<ProjectFile> allProjectFiles = cm.getProject().getFiles();
        if (allProjectFiles.isEmpty()) {
            logger.debug("No files found in project to identify test files.");
            return List.of();
        }

        // Format file paths for the LLM prompt
        String fileListString = allProjectFiles.stream()
                .map(ProjectFile::toString) // Use relative path
                .sorted()
                .collect(Collectors.joining("\n"));

        // Construct the prompt for the quick model
        var systemMessage = new SystemMessage("""
        You are a file analysis assistant. Your task is to identify test files from the provided list.
        Analyze the file paths and names. Return ONLY the paths of the files that appear to be test files.
        Each test file path should be on a new line. Do not include any explanation, headers, or formatting.
        If no test files are found, return an empty response.
        """.stripIndent());

        var userMessage = new UserMessage("""
        Project Files:
        %s

        Identify the test files from the list above and return their full paths, one per line.
        """.stripIndent().formatted(fileListString));

        List<ChatMessage> messages = List.of(systemMessage, userMessage);

        // Call the quick model synchronously
        var result = coder.sendMessage(cm.getModels().quickestModel(), messages);

        if (result.cancelled() || result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.error("Failed to get test files from LLM: {}",
                         result.error() != null ? result.error().getMessage() : "LLM unavailable or cancelled");
            return List.of();
        }

        String responseText = result.chatResponse().aiMessage().text();
        if (responseText == null || responseText.isBlank()) {
            logger.debug("LLM did not identify any test files.");
            return List.of();
        } else {
            // Use contains for robustness against minor formatting variations from the LLM
            var testFiles = allProjectFiles.stream()
                    .parallel() // Can parallelize the filtering
                    .filter(filename -> responseText.contains(filename.toString()))
                    .toList(); // Collect to list
            logger.debug("Identified {} test files via LLM.", testFiles.size());
            return testFiles;
        }
    }


    /**
     * Runs a quick-edit session where we:
     * 1) Gather the entire file content plus related context (buildAutoContext)
     * 2) Use QuickEditPrompts to ask for a single fenced code snippet
     * 3) Replace the old text with the new snippet in the file
     *
     * @return The new text snippet that was applied, or the original file content if failed.
     */
    public static String runQuickSession(ContextManager cm,
                                         IConsoleIO io,
                                         ProjectFile file,
                                         String oldText,
                                         String instructions)
    {
        var coder = cm.getCoder();
        var analyzer = cm.getAnalyzer();

        // Use up to 5 related classes as context
        var seeds = analyzer.getClassesInFile(file).stream()
                .collect(Collectors.toMap(CodeUnit::fqName, cls -> 1.0));
        var relatedCode = Context.buildAutoContext(analyzer, seeds, Set.of(), 5);

        String fileContents;
        try {
            fileContents = file.read();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        var styleGuide = cm.getProject().getStyleGuide();

        // Build the prompt messages
        var messages = QuickEditPrompts.instance.collectMessages(fileContents, relatedCode, styleGuide);

        // The user instructions
        var instructionsMsg = QuickEditPrompts.instance.formatInstructions(oldText, instructions);
        messages.add(new UserMessage(instructionsMsg));

        // Record the original content so we can undo if necessary
        var originalContents = Map.of(file, fileContents);

        // Initialize pending history with the instruction
        var pendingHistory = new ArrayList<ChatMessage>();
        pendingHistory.add(new UserMessage(instructionsMsg));

        // No echo for Quick Edit, use instance quickModel
        var result = coder.sendStreaming(cm.getModels().quickModel(), messages, false);

        if (result.cancelled() || result.error() != null || result.chatResponse() == null) {
            io.toolErrorRaw("Quick edit failed or was cancelled.");
            // Add to history even if canceled, so we can potentially undo any partial changes
            cm.addToHistory(new SessionResult(pendingHistory, originalContents, "Quick Edit (canceled): " + file.getFileName(), "", StopReason.INTERRUPTED));
            return fileContents; // Return original content
        }
        var responseText = result.chatResponse().aiMessage().text();
        if (responseText == null || responseText.isBlank()) {
            io.toolErrorRaw("LLM returned empty response for quick edit.");
            // Add to history even if it failed
            cm.addToHistory(new SessionResult(pendingHistory, originalContents, "Quick Edit (failed): " + file.getFileName(), responseText, StopReason.EMPTY_RESPONSE));
            return fileContents; // Return original content
        }

        // Add the response to pending history
        pendingHistory.add(new AiMessage(responseText));

        // Extract the new snippet
        var newSnippet = EditBlock.extractCodeFromTripleBackticks(responseText).trim();
        if (newSnippet.isEmpty()) {
            io.toolErrorRaw("Could not parse a fenced code snippet from LLM response.");
            // Add to history even if it failed
             cm.addToHistory(new SessionResult(pendingHistory, originalContents, "Quick Edit (failed parse): " + file.getFileName(), responseText, StopReason.PARSE_ERROR_LIMIT));
            return fileContents; // Return original content
        }

        String newStripped = newSnippet.stripLeading();
        try {
            EditBlock.replaceInFile(file, oldText.stripLeading(), newStripped);
            // Save to context history - pendingHistory already contains both the instruction and the response
            cm.addToHistory(new SessionResult(pendingHistory, originalContents, "Quick Edit: " + instructions, responseText, StopReason.SUCCESS));
            return newStripped; // Return the new snippet that was applied
        } catch (EditBlock.NoMatchException | EditBlock.AmbiguousMatchException e) {
            io.toolErrorRaw("Failed to replace text: " + e.getMessage());
            // Add to history even if it failed
             cm.addToHistory(new SessionResult(pendingHistory, originalContents, "Quick Edit (failed match): " + file.getFileName(), responseText, StopReason.APPLY_ERROR_LIMIT));
            return fileContents; // Return original content on failure
        } catch (IOException e) {
            io.toolErrorRaw("Failed writing updated file: " + e.getMessage());
            // Add to history even if it failed
            cm.addToHistory(new SessionResult(pendingHistory, originalContents, "Quick Edit (failed write): " + file.getFileName(), responseText, StopReason.APPLY_ERROR_LIMIT));
            return fileContents; // Return original content on failure
        }
    }

    /** Formats the history of build errors for the LLM reflection prompt. */
    private static String formatBuildErrorsForLLM(List<String> buildErrors) {
        if (buildErrors.isEmpty()) {
            return ""; // Should not happen if checkBuild returned false, but defensive check.
        }
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
     * Generates a reflection message based on parse/apply errors from failed edit blocks
     */
    private static String getParseReflection(List<EditBlock.FailedBlock> failedBlocks,
                                             List<EditBlock.SearchReplaceBlock> originalBlocks, // All blocks attempted in this round
                                             IContextManager contextManager,
                                             IConsoleIO io)
    {
        if (failedBlocks.isEmpty()) {
            return "";
        }

        // Provide suggestions for failed blocks
        var suggestions = EditBlock.collectSuggestions(failedBlocks, contextManager);

        // Calculate how many succeeded in this round
        int succeededCount = originalBlocks.size() - failedBlocks.size();

        // Generate the message for the LLM
        var failedApplyMessage = handleFailedBlocks(suggestions, succeededCount);
        io.llmOutput("\n" + failedApplyMessage); // Show the user what we're telling the LLM

        return failedApplyMessage; // Return the message to be sent to the LLM
    }


    /**
     * Executes the verification command and updates build error history.
     * @return true if the build was successful or skipped, false otherwise.
     */
    private static boolean checkBuild(String verificationCommand, IContextManager cm, IConsoleIO io, List<String> buildErrors) {
        if (verificationCommand == null) {
            io.systemOutput("No verification command specified, skipping build check.");
            buildErrors.clear(); // Clear errors if skipping
            return true; // Treat skipped build as success for workflow purposes
        }

        io.systemOutput("Running verification command: " + verificationCommand);
        var result = Environment.instance.captureShellCommand(verificationCommand, cm.getProject().getRoot());
        logger.debug("Verification command result: {}", result);

        if (result.error() == null) {
            io.systemOutput("Verification successful!");
            buildErrors.clear(); // Reset on successful build
            return true;
        }

        // Build failed
        io.llmOutput("""
        **Verification Failed:** %s
        ```
        %s
        ```
        """.stripIndent().formatted(result.error(), result.output()));
        io.systemOutput("Verification failed (details above)");
        // Add the combined error and output to the history for reflection
        buildErrors.add(result.error() + "\n\n" + result.output());
        return false;
    }


    /**
     * Helper to get a quick response from the LLM without streaming to determine if build errors are improving
     */
    private static boolean isBuildProgressing(Coder coder, List<String> buildResults) {
        if (buildResults.size() < 2) {
            return true;
        }

        var messages = BuildPrompts.instance.collectBuildProgressingMessages(buildResults);
        var response = coder.sendMessage(coder.contextManager.getModels().quickModel(), messages).chatResponse().aiMessage().text().trim();

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
        var failedText = failed.entrySet().stream()
                .map(entry -> {
                    var f = entry.getKey();
                    String fname = (f.block().filename() == null ? "(none)" : f.block().filename());
                    return """
                    ## Failed to match in file: `%s` (Reason: %s)
                    ```
                    <<<<<<< SEARCH
                    %s
                    =======
                    %s
                    >>>>>>> REPLACE
                    ```

                    %s
                    """.stripIndent().formatted(fname,
                                                f.reason(),
                                                f.block().beforeText(),
                                                f.block().afterText(),
                                                entry.getValue());
                })
                .collect(Collectors.joining("\n"));
        var successfulText = succeededCount > 0
                ? "\n# The other %d SEARCH/REPLACE block%s applied successfully. Don't re-send them. Just fix the failing blocks above.\n".formatted(succeededCount, succeededCount == 1 ? " was" : "s were")
                : "";
        var pluralize = singular ? "" : "s";
        return """
        # %d SEARCH/REPLACE block%s failed to match!

        %s

        Take a look at the CURRENT state of the relevant file%s in the workspace; if these edit%s are still needed,
        please correct them. Remember that the SEARCH text must match EXACTLY the lines in the file. If the SEARCH text looks correct,
        check the filename carefully.
        %s
        """.formatted(count, pluralize, failedText, pluralize, pluralize, successfulText).stripIndent();
    }
}
