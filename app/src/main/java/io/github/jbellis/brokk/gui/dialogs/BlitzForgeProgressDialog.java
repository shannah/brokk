package io.github.jbellis.brokk.gui.dialogs;

import static java.util.Objects.requireNonNull;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.RelevanceClassifier;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.InstructionsPanel;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.util.AdaptiveExecutor;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.TokenAware;
import java.awt.*;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class BlitzForgeProgressDialog extends JDialog {

    private final ContextManager cm;
    private final StreamingChatModel model;
    private final String instructions;
    private final String action;
    private final @Nullable Integer relatedK;
    private final @Nullable String perFileCommandTemplate;
    private final boolean includeWorkspace;
    private final String contextFilter;

    public enum PostProcessingOption {
        NONE,
        ARCHITECT,
        ASK
    }

    /** How much of the parallel-processing output to include when passing the results to the post-processing phase. */
    public enum ParallelOutputMode {
        NONE,
        ALL,
        CHANGED
    }

    private static final Logger logger = LogManager.getLogger(BlitzForgeProgressDialog.class);
    private final JProgressBar progressBar;
    private final JTextArea outputTextArea;
    private final MaterialButton cancelButton;
    private final SwingWorker<TaskResult, ProgressData> worker;
    private final int totalFiles;
    private final AtomicInteger processedFileCount = new AtomicInteger(0); // Tracks files processed
    private final AtomicInteger llmLineCount = new AtomicInteger(0); // Tracks LLM output lines for all files
    private final javax.swing.Timer llmLineCountTimer; // Periodically updates llmLineCountLabel
    private final JLabel llmLineCountLabel; // Displays total LLM output lines
    private final ExecutorService executorService; // Thread pool for parallel processing

    // Record to communicate progress from doInBackground to process
    private record ProgressData(int processedCount, String fileName, @Nullable String errorMessage) {}

    // Record to hold results from processing a single file
    private record FileProcessingResult(
            ProjectFile file, @Nullable String errorMessage, String llmOutput, boolean edited) {}

    /**
     * Helper method to compute file size in bytes; if unavailable, falls back to Long.MAX_VALUE to push it to the end.
     */
    private static long fileSize(ProjectFile file) {
        try {
            return Files.size(java.nio.file.Path.of(file.toString()));
        } catch (Exception e) {
            logger.warn("Unable to determine size for {} – {}", file, e.toString());
            return Long.MAX_VALUE;
        }
    }

    /** Encapsulates the per-file processing logic previously inlined in SwingWorker. */
    private FileProcessingResult processSingleFile(ProjectFile file, Context ctx) {
        var dialogConsoleIO = new DialogConsoleIO(this, file.toString());
        String errorMessage = null;

        if (Thread.currentThread().isInterrupted() || worker.isCancelled()) {
            errorMessage = "Cancelled by user.";
            dialogConsoleIO.systemOutput("Processing cancelled by user for file: " + file);
            return new FileProcessingResult(file, errorMessage, "", false);
        }

        List<ChatMessage> readOnlyMessages = new ArrayList<>();
        try {
            if (includeWorkspace) {
                readOnlyMessages.addAll(CodePrompts.instance.getWorkspaceContentsMessages(ctx));
                readOnlyMessages.addAll(CodePrompts.instance.getHistoryMessages(ctx));
            }
            if (relatedK != null) {
                // can't use `ctx` here b/c frozen context does not implement `sources` for buildAutoContext
                var acFragment = cm.liveContext().buildAutoContext(relatedK);
                if (!acFragment.text().isBlank()) {
                    var msgText =
                            """
                            <related_classes>
                            The user requested to include the top %d related classes.

                            %s
                            </related_classes>
                            """
                                    .stripIndent()
                                    .formatted(relatedK, acFragment.text());
                    readOnlyMessages.add(new UserMessage(msgText));
                }
            }

            // Execute per-file command if provided
            if (perFileCommandTemplate != null && !perFileCommandTemplate.isBlank()) {
                MustacheFactory mf = new DefaultMustacheFactory();
                Mustache mustache = mf.compile(new StringReader(perFileCommandTemplate), "perFileCommand");
                StringWriter writer = new StringWriter();
                Map<String, Object> scope = new HashMap<>();
                scope.put("filepath", file.toString()); // Using relative path
                mustache.execute(writer, scope);
                String finalCommand = writer.toString();

                dialogConsoleIO.systemOutput("Executing per-file command: " + finalCommand);
                logger.info("Executing per-file command for {}: {}", file, finalCommand);
                String commandOutputText;
                try {
                    String output = Environment.instance.runShellCommand(
                            finalCommand,
                            cm.getProject().getRoot(),
                            line -> {}, // No live consumer for now
                            Environment.UNLIMITED_TIMEOUT);
                    commandOutputText =
                            """
                            <per_file_command_output command="%s">
                            %s
                            </per_file_command_output>
                            """
                                    .stripIndent()
                                    .formatted(finalCommand, output);
                } catch (Environment.SubprocessException ex) {
                    logger.warn("Per-file command failed for {}: {}", file, finalCommand, ex);
                    commandOutputText =
                            """
                            <per_file_command_output command="%s">
                            Error executing command: %s
                            Output (if any):
                            %s
                            </per_file_command_output>
                            """
                                    .stripIndent()
                                    .formatted(finalCommand, ex.getMessage(), ex.getOutput());
                    dialogConsoleIO.toolError(
                            "Per-file command failed: " + ex.getMessage() + "\nOutput (if any):\n" + ex.getOutput(),
                            "Command Execution Error");
                }
                readOnlyMessages.add(new UserMessage(commandOutputText));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorMessage = "Interrupted during message preparation.";
            dialogConsoleIO.systemOutput("Interrupted during message preparation for file: " + file);
        }

        if (errorMessage != null) {
            return new FileProcessingResult(file, errorMessage, dialogConsoleIO.getLlmOutput(), false);
        }

        // run the task
        TaskResult result;
        if (action.equals("Ask")) {
            var messages = CodePrompts.instance.getSingleFileAskMessages(cm, file, readOnlyMessages, instructions);
            var llm = cm.getLlm(model, "Ask", true);
            llm.setOutput(dialogConsoleIO);
            result = InstructionsPanel.executeAskCommand(llm, messages, cm, instructions);
        } else { // "Code"
            var agent = new CodeAgent(cm, model, dialogConsoleIO);
            result = agent.runSingleFileEdit(file, instructions, readOnlyMessages, Set.of());
        }
        // output the result
        if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
            Thread.currentThread().interrupt(); // Preserve interrupt status
            errorMessage = "Processing interrupted.";
            dialogConsoleIO.systemOutput("File processing for " + file + " was interrupted.");
        } else if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            errorMessage = "Processing failed: " + result.stopDetails().reason();
            String explanation = result.stopDetails().explanation();
            if (!explanation.isEmpty()) {
                errorMessage += " - " + explanation;
            }
            dialogConsoleIO.toolError(errorMessage, "Agent Processing Error");
        }
        // else publish() logs success

        boolean edited = result.changedFiles().contains(file);
        // optional context filtering
        String finalLlmOutput = dialogConsoleIO.getLlmOutput();
        if (!contextFilter.isBlank() && !finalLlmOutput.isBlank()) {
            try {
                var quickestModel = cm.getService().quickestModel();
                var filterLlm = cm.getLlm(quickestModel, "ContextFilter");

                boolean keep = RelevanceClassifier.isRelevant(filterLlm, contextFilter, finalLlmOutput);
                if (!keep) {
                    finalLlmOutput = "";
                }
            } catch (Exception e) {
                logger.warn("Context filtering failed for {}: {}", file, e.toString());
            }
        }

        return new FileProcessingResult(file, errorMessage, finalLlmOutput, edited);
    }

    public BlitzForgeProgressDialog(
            String instructions,
            String action,
            Service.FavoriteModel selectedFavorite,
            List<ProjectFile> filesToProcess,
            Chrome chrome,
            @Nullable Integer relatedK,
            @Nullable String perFileCommandTemplate,
            boolean includeWorkspace,
            PostProcessingOption runOption,
            String contextFilter,
            ParallelOutputMode outputMode,
            boolean buildFirst,
            String postProcessingInstructions) {
        super(chrome.getFrame(), "BlitzForge Progress", false);
        this.instructions = instructions;
        this.action = action;
        this.relatedK = relatedK;
        this.perFileCommandTemplate = perFileCommandTemplate;
        this.includeWorkspace = includeWorkspace;
        this.contextFilter = contextFilter;
        chrome.disableActionButtons();
        this.totalFiles = filesToProcess.size();

        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(600, 400));

        progressBar = new JProgressBar(0, totalFiles);
        progressBar.setStringPainted(true);
        progressBar.setString("0 of " + totalFiles + " files processed");

        outputTextArea = new JTextArea();
        outputTextArea.setEditable(false);
        outputTextArea.setLineWrap(true);
        outputTextArea.setWrapStyleWord(true);
        outputTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane outputScrollPane = new JScrollPane(outputTextArea);

        JPanel outputPanel = new JPanel(new BorderLayout(5, 5));
        outputPanel.add(new JLabel("Processing Output and Errors:"), BorderLayout.NORTH);
        outputPanel.add(outputScrollPane, BorderLayout.CENTER);
        outputPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        cancelButton = new MaterialButton("Cancel");

        llmLineCountLabel = new JLabel("Lines received: 0");
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(new JLabel("Processing files..."), BorderLayout.NORTH);
        topPanel.add(progressBar, BorderLayout.CENTER);
        topPanel.add(llmLineCountLabel, BorderLayout.SOUTH);
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        add(topPanel, BorderLayout.NORTH);

        this.llmLineCountTimer =
                new javax.swing.Timer(100, e -> llmLineCountLabel.setText("Lines received: " + llmLineCount.get()));
        this.llmLineCountTimer.setRepeats(true); // Make it a repeating timer
        this.llmLineCountTimer.setCoalesce(true); // Optimize for EDT

        add(outputPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        add(buttonPanel, BorderLayout.SOUTH);

        // set up executorService
        cm = chrome.getContextManager();
        var service = cm.getService();
        model = requireNonNull(service.getModel(selectedFavorite.config()));

        executorService = AdaptiveExecutor.create(service, model, filesToProcess.size());

        worker = new SwingWorker<>() {
            @Override
            protected TaskResult doInBackground() {
                var analyzerWrapper = cm.getAnalyzerWrapper();
                analyzerWrapper.pause();
                try {
                    var frozenContext = cm.topContext();
                    List<FileProcessingResult> results = new ArrayList<>();

                    // ---- 1.  Sort by on-disk size, smallest first ----
                    var sortedFiles = filesToProcess.stream()
                            .sorted(Comparator.comparingLong(BlitzForgeProgressDialog::fileSize))
                            .toList();

                    // if the workspace is included, run the smallest task first to warm the prefix cache
                    int parallelStartIndex = 0;
                    if (includeWorkspace) {
                        SwingUtilities.invokeLater(() -> progressBar.setString("Preloading prefix cache..."));

                        // Process the very first file synchronously to warm up caches
                        if (!isCancelled()) {
                            var firstResult = processSingleFile(sortedFiles.getFirst(), frozenContext);
                            processedFileCount.set(1); // Mark the first file as processed
                            // Publish result for the first file
                            publish(new ProgressData(
                                    processedFileCount.get(),
                                    firstResult.file().toString(),
                                    firstResult.errorMessage()));
                            results.add(firstResult);
                            parallelStartIndex = 1; // we already handled the first file
                        }

                        // Early exit if user cancelled during first file processing
                        if (isCancelled()) {
                            var sd = new TaskResult.StopDetails(
                                    TaskResult.StopReason.INTERRUPTED, "User cancelled operation.");
                            return new TaskResult(cm, instructions, List.of(), new HashSet<>(filesToProcess), sd);
                        }
                    }

                    // Submit the remaining files for concurrent processing
                    CompletionService<FileProcessingResult> completionService =
                            new ExecutorCompletionService<>(executorService);
                    // Skip files already processed synchronously (0 or 1 depending on warm-up)
                    sortedFiles.stream().skip(parallelStartIndex).forEach(file -> {
                        if (!isCancelled()) {
                            interface TokenAwareCallable extends Callable<FileProcessingResult>, TokenAware {}

                            completionService.submit(new TokenAwareCallable() {
                                @Override
                                public int tokens() {
                                    int tokens;
                                    var readOnly = new ArrayList<ChatMessage>();
                                    if (includeWorkspace) {
                                        readOnly.addAll(
                                                CodePrompts.instance.getWorkspaceContentsMessages(frozenContext));
                                        readOnly.addAll(CodePrompts.instance.getHistoryMessages(frozenContext));
                                    }
                                    tokens = Messages.getApproximateTokens(readOnly)
                                            + Messages.getApproximateTokens(
                                                    file.read().orElse(""));
                                    return tokens;
                                }

                                @Override
                                public FileProcessingResult call() {
                                    return processSingleFile(file, frozenContext);
                                }
                            });
                        }
                    });

                    // Number of tasks we expect to retrieve from the completion service
                    int tasksSubmitted = filesToProcess.size() - parallelStartIndex;
                    // Collect results for the tasks submitted. If tasksSubmitted is 0, this loop won't run.
                    for (int i = 0; i < tasksSubmitted; i++) {
                        if (isCancelled()) {
                            break; // Exit loop if worker is cancelled
                        }
                        try {
                            Future<FileProcessingResult> future =
                                    completionService.take(); // Blocks until a task completes
                            FileProcessingResult result = future.get(); // Retrieves result or re-throws exception
                            results.add(result);
                            // Increment count and publish progress for the completed file
                            publish(new ProgressData(
                                    processedFileCount.incrementAndGet(),
                                    result.file().toString(),
                                    result.errorMessage()));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // Restore interrupt status
                            break; // Exit loop due to interruption
                        } catch (ExecutionException e) {
                            // Log unexpected errors from file processing tasks, and increment count to show progress
                            logger.error("Error processing file", e.getCause());
                            processedFileCount.incrementAndGet();
                        }
                    }

                    // union of all files actually edited
                    var changedFilesSet = results.stream()
                            .filter(FileProcessingResult::edited)
                            .map(FileProcessingResult::file)
                            .collect(Collectors.toSet());

                    // build the markdown-formatted parallel-LLM output
                    var uiMessageText = results.stream()
                            .filter(r -> !r.llmOutput().isBlank())
                            .map(r -> "## " + r.file() + "\n" + r.llmOutput() + "\n\n")
                            .collect(Collectors.joining());

                    var uiMessages = uiMessageText.isEmpty()
                            ? List.<ChatMessage>of()
                            : List.of(
                                    new UserMessage(instructions),
                                    CodePrompts.redactAiMessage(new AiMessage(uiMessageText), EditBlockParser.instance)
                                            .orElse(new AiMessage("")));

                    List<String> failures = results.stream()
                            .filter(r -> r.errorMessage() != null)
                            .map(r -> r.file() + ": " + r.errorMessage())
                            .toList();

                    TaskResult.StopDetails sd;
                    if (failures.isEmpty() && !isCancelled()) {
                        sd = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
                    } else if (isCancelled()) {
                        sd = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED, "User cancelled operation.");
                    } else {
                        sd = new TaskResult.StopDetails(TaskResult.StopReason.TOOL_ERROR, String.join("\n", failures));
                    }

                    return new TaskResult(cm, instructions, uiMessages, changedFilesSet, sd);
                } finally {
                    executorService.shutdownNow();
                    analyzerWrapper.resume();
                }
            }

            @Override
            protected void process(List<ProgressData> chunks) {
                for (var data : chunks) {
                    // Update progress bar and its string based on actual processed count
                    progressBar.setValue(data.processedCount());
                    progressBar.setString(String.format("%d of %d files processed", data.processedCount(), totalFiles));

                    // Append messages to the output area
                    if (!data.fileName().isEmpty()) { // This chunk relates to a specific file
                        if (data.errorMessage() != null) {
                            outputTextArea.append(
                                    "Error processing " + data.fileName() + ": " + data.errorMessage() + "\n");
                        } else {
                            outputTextArea.append("Processed: " + data.fileName() + "\n");
                        }
                    }
                    outputTextArea.setCaretPosition(outputTextArea.getDocument().getLength());
                }
            }

            @Override
            protected void done() {
                chrome.enableActionButtons();
                llmLineCountTimer.stop(); // Stop the timer for LLM line count updates
                cancelButton.setText("Close"); // Change button text to "Close"
                // Remove existing action listener and add one to simply close the dialog
                Arrays.stream(cancelButton.getActionListeners()).forEach(cancelButton::removeActionListener);
                cancelButton.addActionListener(e -> setVisible(false));

                // Handle cancellation scenario
                if (isCancelled()) {
                    progressBar.setString(
                            "Cancelled. " + processedFileCount.get() + " of " + totalFiles + " files processed.");
                    outputTextArea.append("\n``` Operation Cancelled by User ```\n");
                    return;
                }

                TaskResult result;
                try {
                    result = get(); // Retrieve the final result from doInBackground
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Error during background task execution", e);
                    // Re-throw as RuntimeException, as caught in caller
                    throw new RuntimeException("Task failed: " + e.getMessage(), e);
                }

                // Add task result to history (single scope)
                var contextManager = chrome.getContextManager();
                try (var scope = contextManager.beginTask("", true)) {
                    scope.append(result);
                }
                var mainIo = contextManager.getIo();

                llmLineCountLabel.setText("Lines received: " + llmLineCount.get()); // Final update to LLM line count
                progressBar.setValue(totalFiles); // Ensure progress bar shows 100% completion
                progressBar.setString(
                        "Completed. " + totalFiles + " of " + totalFiles + " files processed."); // Final progress text

                outputTextArea.append("\nParallel processing complete.\n");
                // If no post-processing option is selected, we are done
                if (runOption == PostProcessingOption.NONE) {
                    return;
                }

                CompletableFuture<String> buildFailureFuture;
                if (buildFirst) {
                    buildFailureFuture = contextManager.submitBackgroundTask("Run verification build", () -> {
                        try {
                            return BuildAgent.runVerification(contextManager);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return "Build command was interrupted.";
                        }
                    });
                } else {
                    buildFailureFuture = CompletableFuture.completedFuture("");
                }

                buildFailureFuture.thenAccept(buildFailureText -> SwingUtilities.invokeLater(() -> {
                    if (postProcessingInstructions.isEmpty() && buildFailureText.isEmpty()) {
                        logger.debug("Build successful or not run, and parallel output processing was not requested");
                        return;
                    }

                    var files =
                            filesToProcess.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"));

                    var messages = result.output().messages();
                    assert messages.isEmpty() || messages.size() == 2 : messages.size(); // by construction
                    var effectiveOutputMode = messages.isEmpty() ? ParallelOutputMode.NONE : outputMode;
                    var parallelDetails =
                            switch (effectiveOutputMode) {
                                case NONE ->
                                    "The task was applied to the following files:\n```\n%s```".formatted(files);
                                case CHANGED -> {
                                    var output = Messages.getText(messages.getLast());
                                    yield "The parallel processing made changes to the following files:\n```\n%s```"
                                            .formatted(output);
                                }
                                default -> { // "all"
                                    var output = Messages.getText(messages.getLast());
                                    yield "The output from the parallel processing was:\n```\n%s```".formatted(output);
                                }
                            };

                    var effectiveGoal = postProcessingInstructions.isBlank()
                            ? "Please fix the problems."
                            : "Here are the postprocessing instructions:\n```\n%s```"
                                    .formatted(postProcessingInstructions);

                    // Build the agent instructions WITHOUT embedding raw build output; Architect should consult
                    // the Build Results fragment in the session context for full build logs/details.
                    var agentInstructions =
                            """
                                            I just finished a parallel upgrade task with the following instructions:
                                            ```
                                            %s
                                            ```

                                            %s

                                            Build details and verification output are available in the session's Build Results fragment;
                                            please consult it when fixing any remaining issues.

                                            %s
                                            """
                                    .formatted(instructions, parallelDetails, effectiveGoal);

                    if (runOption == PostProcessingOption.ASK) {
                        outputTextArea.append("Ask command has been invoked. You can close this window.\n");
                        chrome.getInstructionsPanel().runAskCommand(agentInstructions);
                        return;
                    }

                    outputTextArea.append("Architect has been invoked. You can close this window.\n");
                    // Submit the Architect task after running a Search for relevant information
                    contextManager.submitLlmAction(() -> {
                        // FIXME handle interruptions
                        var scanModel = contextManager.getService().getScanModel();
                        SearchAgent agent = new SearchAgent(
                                agentInstructions,
                                contextManager,
                                scanModel,
                                EnumSet.of(SearchAgent.Terminal.WORKSPACE));
                        TaskResult searchResult = agent.execute();
                        if (searchResult.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                            logger.debug("Search failed: {}", searchResult.stopDetails());
                            mainIo.toolError(
                                    "Search phase failed; not invoking Architect. " + searchResult.stopDetails(),
                                    "Post-processing");
                        }

                        chrome.getInstructionsPanel().runArchitectCommand(agentInstructions);
                    });
                }));
            }
        };

        // Removed SwingWorker's default progress listener as we handle progress manually via publish/process to avoid
        // scaling issues.
        // The progress bar now directly reflects processed file count via ProgressData.

        cancelButton.addActionListener(e -> {
            if (!worker.isDone()) {
                worker.cancel(true);
            } else { // Worker is done, button is "Close"
                setVisible(false);
            }
        });

        setDefaultCloseOperation(
                JDialog.DO_NOTHING_ON_CLOSE); // Prevent closing via 'X' until worker is done or explicitly cancelled
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                if (!worker.isDone()) {
                    int choice = chrome.showConfirmDialog(
                            BlitzForgeProgressDialog.this,
                            "Are you sure you want to cancel the upgrade process?",
                            "Confirm Cancel",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) {
                        worker.cancel(true);
                    }
                } else {
                    setVisible(false);
                }
            }
        });

        pack();
        setLocationRelativeTo(chrome.getFrame());
        worker.execute();
    }

    /** */
    private class DialogConsoleIO implements IConsoleIO {
        private final BlitzForgeProgressDialog dialog;
        private final String fileContext;
        private final StringBuilder llmOutput = new StringBuilder();

        /** @param fileContext To prefix messages related to a specific file */
        private DialogConsoleIO(BlitzForgeProgressDialog dialog, String fileContext) {
            this.dialog = dialog;
            this.fileContext = fileContext;
        }

        public String getLlmOutput() {
            return llmOutput.toString();
        }

        private void appendToOutput(String text) {
            SwingUtilities.invokeLater(() -> {
                String prefix = "[%s] ".formatted(fileContext);
                dialog.outputTextArea.append(prefix + text + "\n");
                dialog.outputTextArea.setCaretPosition(
                        dialog.outputTextArea.getDocument().getLength());
            });
        }

        @Override
        public void toolError(String message, String title) {
            var msg = "[%s] %s: %s\n".formatted(fileContext, title, message);
            logger.error(msg);
            SwingUtilities.invokeLater(() -> {
                outputTextArea.append(msg);
                outputTextArea.setCaretPosition(outputTextArea.getDocument().getLength());
            });
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
            llmOutput.append(token);
            long newLines = token.chars().filter(c -> c == '\n').count();
            if (newLines > 0) {
                llmLineCount.addAndGet((int) newLines);
                // Start the timer if it's not already running
                SwingUtilities.invokeLater(() -> {
                    if (!dialog.llmLineCountTimer.isRunning()) {
                        dialog.llmLineCountTimer.start();
                    }
                });
            }
        }

        @Override
        public void systemOutput(String message) {
            appendToOutput(message);
        }

        @Override
        public void systemNotify(String message, String title, int messageType) {
            appendToOutput(title + ": " + message);
        }

        @Override
        public List<ChatMessage> getLlmRawMessages() {
            return List.of();
        }
    }
}
