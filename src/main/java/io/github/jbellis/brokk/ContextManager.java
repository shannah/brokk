package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.ContextFragment.PathFragment;
import io.github.jbellis.brokk.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.gui.FileSelectionDialog;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.AskPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the current and previous context, along with other state like prompts and message history.
 *
 * Updated to:
 *   - Remove OperationResult,
 *   - Move UI business logic from Chrome to here as asynchronous tasks,
 *   - Directly call into Chrome’s UI methods from background tasks (via invokeLater),
 *   - Provide separate async methods for “Go”, “Ask”, “Search”, context additions, etc.
 */
public class ContextManager implements IContextManager
{
    private final Logger logger = LogManager.getLogger(ContextManager.class);

    private AnalyzerWrapper analyzerWrapper;
    private Chrome chrome; // for UI feedback
    private Coder coder;

    // Run user-driven tasks in background
    private final ExecutorService userActionExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "UserActionThread");
        t.setDaemon(true);
        return t;
    });

    // Internal background tasks
    private final ExecutorService backgroundTasks = Executors.newFixedThreadPool(2);

    private Project project;
    private final Path root;

    private Mode mode = Mode.EDIT;
    public enum Mode { EDIT, APPLY }

    // Keep contexts for undo/redo
    private static final int MAX_UNDO_DEPTH = 100;
    private final List<Context> contextHistory = new ArrayList<>();
    private final List<Context> redoHistory = new ArrayList<>();

    // Possibly store an inferred buildCommand
    private Future<BuildCommand> buildCommand;

    // Shell output
    private String lastShellOutput;

    // The message we might pass directly to LLM next
    private String constructedMessage;

    /**
     * Minimal constructor called from Brokk
     */
    public ContextManager(Path root)
    {
        this.root = root.toAbsolutePath();
    }

    /**
     * Called from Brokk to finish wiring up references to Chrome and Coder
     */
    public void resolveCircularReferences(Chrome chrome, Coder coder)
    {
        this.chrome = chrome;
        this.coder = coder;
        this.project = new Project(root, chrome);
        this.analyzerWrapper = new AnalyzerWrapper(project, backgroundTasks);

        // Start with blank context
        var newContext = new Context(this.analyzerWrapper, 5);
        contextHistory.add(newContext);

        ensureStyleGuide();
        ensureBuildCommand(coder);
    }

    /**
     * Called immediately after Chrome is created, so it can pass itself in again if needed.
     */
    public void setChrome(Chrome chrome)
    {
        this.chrome = chrome;
    }

    /**
     * Get the current mode
     */
    public Mode getMode()
    {
        return mode;
    }

    /**
     * Set the current mode
     */
    public void setMode(Mode mode)
    {
        this.mode = mode;
    }

    /**
     * Return the streaming chat model used for the current mode
     */
    public StreamingChatLanguageModel getCurrentModel(Models models)
    {
        return (mode == Mode.EDIT) ? models.editModel() : models.applyModel();
    }

    public Project getProject()
    {
        return project;
    }

    @Override
    public RepoFile toFile(String relName)
    {
        return new RepoFile(root, relName);
    }

    /**
     * Return the current context
     */
    public Context currentContext()
    {
        return contextHistory.get(contextHistory.size() - 1);
    }

    /**
     * Return the main analyzer, building it if needed
     */
    public Analyzer getAnalyzer()
    {
        return analyzerWrapper.get();
    }

    public Path getRoot()
    {
        return root;
    }

    /**
     * Asynchronous “Go” command:
     *   - If it starts with ‘$$’, run shell + capture into snippet
     *   - Else if it starts with ‘$’, run shell + show output
     *   - Else if it starts with ‘/’, show error
     *   - Else treat as user request to LLM
     */
    public Future<?> runGoCommandAsync(String input)
    {
        assert chrome != null;
        return userActionExecutor.submit(() -> {
            try {
                if (input.startsWith("$$")) {
                    var command = input.substring(2).trim();
                    chrome.toolOutput("Executing: " + command);
                    var result = Environment.instance.captureShellCommand(command);
                    if (!result.output().isBlank()) {
                        addStringFragment(command, result.output());
                    }
                    // show output in LLM area
                    chrome.shellOutput(result.output().isBlank() ? "[operation completed with no output]" : result.output());
                }
                else if (input.startsWith("$")) {
                    var command = input.substring(1).trim();
                    chrome.toolOutput("Executing: " + command);
                    var result = Environment.instance.captureShellCommand(command);
                    lastShellOutput = result.output().isBlank() ? null : result.output();
                    chrome.shellOutput(result.output().isBlank() ? "[operation completed with no output]" : result.output());
                }
                else if (input.startsWith("/")) {
                    chrome.toolErrorRaw("Slash commands are disabled. Please use the menu options instead.");
                }
                else {
                    // treat as user request to LLM
                    runSessionWithLLM(input);
                }
            } catch (CancellationException cex) {
                chrome.toolOutput("Go command canceled.");
            } catch (Exception e) {
                logger.error("Error in Go command", e);
                chrome.toolErrorRaw("Error in Go command: " + e.getMessage());
            } finally {
                chrome.enableUserActionButtons();
            }
        });
    }

    /**
     * Asynchronous “Ask” command
     */
    public Future<?> runAskAsync(String question)
    {
        return userActionExecutor.submit(() -> {
            try {
                if (chrome == null) return;
                if (question.isBlank()) {
                    chrome.toolErrorRaw("Please provide a question");
                    return;
                }
                // Provide the prompt messages
                var messages = new LinkedList<>(AskPrompts.instance.collectMessages(this));
                messages.add(new UserMessage("<question>\n%s\n</question>".formatted(question.trim())));

                // stream from coder
                var response = coder.sendStreaming(getCurrentModel(coder.models), messages, true);
                if (response != null) {
                    addToHistory(List.of(messages.getLast(), response.aiMessage()));
                }
            } catch (CancellationException cex) {
                chrome.toolOutput("Ask command canceled.");
            } catch (Exception e) {
                logger.error("Error in ask command", e);
                chrome.toolErrorRaw("Error in ask command: " + e.getMessage());
            } finally {
                chrome.enableUserActionButtons();
            }
        });
    }

    /**
     * Asynchronous “Search” command
     */
    public Future<?> runSearchAsync(String query)
    {
        return userActionExecutor.submit(() -> {
            try {
                if (chrome == null) return;
                if (query.isBlank()) {
                    chrome.toolErrorRaw("Please provide a search query");
                    return;
                }
                // run a search agent
                chrome.spin("Searching code");
                var agent = new SearchAgent(query, this, coder, chrome);
                var result = agent.execute();
                if (result == null) {
                    chrome.toolOutput("Search was interrupted");
                } else {
                    chrome.llmOutput(result.text() + "\n");
                    addSearchFragment(result);
                }
            } catch (CancellationException cex) {
                chrome.toolOutput("Search command canceled.");
            } catch (Exception e) {
                logger.error("Error in search command", e);
                chrome.toolErrorRaw("Error in search command: " + e.getMessage());
            } finally {
                chrome.spinComplete();
                chrome.enableUserActionButtons();
            }
        });
    }

    /**
     * Move the old slash-command handling from Chrome to here, if needed.
     * For now, we simply note that slash commands are disabled.
     */
    public void handleSlashCommand(String input)
    {
        chrome.toolErrorRaw("Slash commands are disabled. Use the menu options instead.");
    }

    /**
     * Start a new LLM session with the user’s input
     */
    private void runSessionWithLLM(String input)
    {
        LLM.runSession(coder, chrome, getCurrentModel(coder.models), input);
    }

    // ------------------------------------------------------------------
    // Asynchronous context actions: add/read/copy/edit/summarize/drop
    // ------------------------------------------------------------------

    /**
     * Called from the "File" menu -> "Add context"
     */
    public Future<?> addContextViaDialogAsync()
    {
        assert chrome != null;
        return userActionExecutor.submit(() -> {
            try {
                var files = showFileSelectionDialog("Add Context");
                if (!files.isEmpty()) {
                    addFiles(files);
                    chrome.toolOutput("Added: " + files);
                } else {
                    chrome.toolOutput("No files selected.");
                }
            } catch (CancellationException cex) {
                chrome.toolOutput("Add context canceled.");
            } catch (Exception e) {
                logger.error("Error adding context", e);
                chrome.toolErrorRaw("Error adding context: " + e.getMessage());
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }

    /**
     * Show the custom file selection dialog
     */
    private List<RepoFile> showFileSelectionDialog(String title)
    {
        var dialog = new FileSelectionDialog(null, getRoot(), title);
        SwingUtil.runOnEDT(() -> {
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), 400);
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
        });
        if (dialog.isConfirmed()) {
            return dialog.getSelectedFiles();
        }
        return List.of();
    }

    /**
     * Sub-action for reading files (pop up a standard JFileChooser).
     * (Not used from “Read context” button in the final UI, but we keep it for reference.)
     */
    public Future<?> readContextViaDialogAsync()
    {
        return userActionExecutor.submit(() -> {
            try {
                if (chrome == null) return;
                SwingUtilities.invokeLater(() -> {
                    var chooser = new JFileChooser(getRoot().toFile());
                    chooser.setMultiSelectionEnabled(true);
                    var result = chooser.showOpenDialog(null);
                    if (result == JFileChooser.APPROVE_OPTION) {
                        var files = chooser.getSelectedFiles();
                        if (files.length == 0) {
                            chrome.toolOutput("No files selected");
                            return;
                        }
                        var repoFiles = new ArrayList<RepoFile>();
                        for (var f : files) {
                            var rel = getRoot().relativize(f.toPath()).toString();
                            repoFiles.add(toFile(rel));
                        }
                        addReadOnlyFiles(repoFiles);
                        chrome.toolOutput("Added read-only " + repoFiles);
                    }
                    chrome.enableContextActionButtons();
                    chrome.enableUserActionButtons();
                });
            } catch (Exception e) {
                logger.error("Error reading context", e);
                chrome.toolErrorRaw("Error reading context: " + e.getMessage());
            }
        });
    }

    /**
     * Performed by the action buttons in the context panel: “edit / read / copy / drop / summarize”
     * If selectedIndices is empty, it means “All”. We handle logic accordingly.
     */
    public Future<?> performContextActionAsync(String action, List<Integer> selectedIndices)
    {
        return userActionExecutor.submit(() -> {
            try {
                switch (action) {
                    case "edit" -> doEditAction(selectedIndices);
                    case "read" -> doReadAction(selectedIndices);
                    case "copy" -> doCopyAction(selectedIndices);
                    case "drop" -> doDropAction(selectedIndices);
                    case "summarize" -> doSummarizeAction(selectedIndices);
                    default -> chrome.toolErrorRaw("Unknown action: " + action);
                }
            } catch (CancellationException cex) {
                chrome.toolOutput(action + " canceled.");
            } catch (Exception e) {
                logger.error("Error in " + action + " action", e);
                chrome.toolErrorRaw("Error in " + action + " action: " + e.getMessage());
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }

    private void doEditAction(List<Integer> selectedIndices)
    {
        if (selectedIndices.isEmpty()) {
            // Show a file selection dialog to add new files
            var files = showFileSelectionDialog("Add Context");
            if (!files.isEmpty()) {
                addFiles(files);
                chrome.toolOutput("Added: " + files);
            } else {
                chrome.toolOutput("No files selected.");
            }
        } else {
            var files = new HashSet<RepoFile>();
            for (var idx : selectedIndices) {
                files.addAll(getFilesFromFragmentIndex(idx));
            }
            addFiles(files);
            chrome.toolOutput("Converted " + files.size() + " files to editable.");
        }
    }

    private void doReadAction(List<Integer> selectedIndices)
    {
        if (selectedIndices.isEmpty()) {
            // Show a file selection dialog for read-only
            var files = showFileSelectionDialog("Read Context");
            if (!files.isEmpty()) {
                addReadOnlyFiles(files);
                chrome.toolOutput("Added read-only " + files);
            } else {
                chrome.toolOutput("No files selected.");
            }
        } else {
            var files = new HashSet<RepoFile>();
            for (var idx : selectedIndices) {
                files.addAll(getFilesFromFragmentIndex(idx));
            }
            addReadOnlyFiles(files);
            chrome.toolOutput("Added " + files.size() + " read-only files");
        }
    }

    private void doCopyAction(List<Integer> selectedIndices)
    {
        // If none are selected, copy ALL
        String content;
        if (selectedIndices.isEmpty()) {
            // gather entire context
            var msgs = ArchitectPrompts.instance.collectMessages(this);
            var combined = new StringBuilder();
            for (var m : msgs) {
                if (!(m instanceof dev.langchain4j.data.message.AiMessage)) {
                    combined.append(Models.getText(m)).append("\n\n");
                }
            }
            combined.append("\n<goal>\n\n</goal>");
            content = combined.toString();
        } else {
            // copy only selected fragments
            var ctx = currentContext();
            var allFrags = ctx.getAllFragmentsInDisplayOrder();
            var sb = new StringBuilder();
            for (var idx : selectedIndices) {
                if (idx >= 0 && idx < allFrags.size()) {
                    var frag = allFrags.get(idx);
                    try {
                        sb.append(frag.text()).append("\n\n");
                    } catch (IOException e) {
                        removeBadFragment(frag, e);
                        chrome.toolErrorRaw("Error reading fragment: " + e.getMessage());
                    }
                }
            }
            content = sb.toString();
        }

        try {
            var sel = new java.awt.datatransfer.StringSelection(content);
            var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(sel, sel);
            chrome.toolOutput("Content copied to clipboard");
        } catch (Exception e) {
            chrome.toolErrorRaw("Failed to copy: " + e.getMessage());
        }
    }

    private void doDropAction(List<Integer> selectedIndices)
    {
        if (selectedIndices.isEmpty()) {
            if (currentContext().isEmpty()) {
                chrome.toolErrorRaw("No context to drop");
                return;
            }
            dropAll();
            chrome.toolOutput("Dropped all context");
        } else {
            var ctx = currentContext();
            var allFrags = ctx.getAllFragmentsInDisplayOrder();
            
            // Special case: If only the autocontext (index 0) is selected, set autocontext size to 0
            if (selectedIndices.size() == 1 && selectedIndices.get(0) == 0) {
                assert allFrags.get(0) == ctx.getAutoContext() : allFrags.get(0);
                setAutoContextFiles(0);
                return;
            }
            
            // Regular drop behavior for other fragments
            var pathFragsToRemove = new ArrayList<ContextFragment.PathFragment>();
            var virtualToRemove = new ArrayList<ContextFragment.VirtualFragment>();

            for (var idx : selectedIndices) {
                if (idx >= 0 && idx < allFrags.size()) {
                    var frag = allFrags.get(idx);
                    if (frag instanceof ContextFragment.PathFragment pf) {
                        pathFragsToRemove.add(pf);
                    } else if (frag instanceof ContextFragment.VirtualFragment vf) {
                        virtualToRemove.add(vf);
                    }
                }
            }
            drop(pathFragsToRemove, virtualToRemove);
            chrome.toolOutput("Dropped " + selectedIndices.size() + " items");
        }
    }

    private void doSummarizeAction(List<Integer> selectedIndices)
    {
        var ctx = currentContext();
        if (selectedIndices.isEmpty()) {
            // Summarize all eligible
            var allFrags = ctx.getAllFragmentsInDisplayOrder().stream()
                    .filter(ContextFragment::isEligibleForAutoContext)
                    .collect(Collectors.toSet());
            if (allFrags.isEmpty()) {
                chrome.toolErrorRaw("No eligible items to summarize");
                return;
            }
            var sources = new HashSet<CodeUnit>();
            for (var f : allFrags) {
                sources.addAll(f.sources(getAnalyzer()));
            }
            var success = summarizeClasses(sources);
            if (success) {
                chrome.toolOutput("Summarized " + sources.size() + " classes");
            } else {
                chrome.toolErrorRaw("Failed to summarize classes");
            }
        } else {
            var allFrags = ctx.getAllFragmentsInDisplayOrder();
            var selectedFrags = new HashSet<ContextFragment>();
            for (var idx : selectedIndices) {
                if (idx >= 0 && idx < allFrags.size()) {
                    selectedFrags.add(allFrags.get(idx));
                }
            }
            if (selectedFrags.isEmpty()) {
                chrome.toolErrorRaw("No items to summarize");
                return;
            }
            var sources = new HashSet<CodeUnit>();
            for (var frag : selectedFrags) {
                sources.addAll(frag.sources(getAnalyzer()));
            }
            var success = summarizeClasses(sources);
            if (success) {
                chrome.toolOutput("Summarized from " + selectedFrags.size() + " fragments");
            } else {
                chrome.toolErrorRaw("Failed to summarize classes");
            }
        }
    }

    // ------------------------------------------------------------------
    // Existing business logic from the old code
    // ------------------------------------------------------------------

    /** Add the given files to editable. */
    @Override
    public void addFiles(Collection<RepoFile> files)
    {
        var fragments = files.stream().map(ContextFragment.RepoPathFragment::new).toList();
        pushContext(ctx -> ctx.removeReadonlyFiles(fragments).addEditableFiles(fragments));
    }

    /** Add read-only files. */
    public void addReadOnlyFiles(Collection<? extends BrokkFile> files)
    {
        var fragments = files.stream().map(ContextFragment::toPathFragment).toList();
        pushContext(ctx -> ctx.removeEditableFiles(fragments).addReadonlyFiles(fragments));
    }

    /** Drop all context. */
    public void dropAll()
    {
        pushContext(Context::removeAll);
    }

    /** Drop the given fragments. */
    public void drop(List<PathFragment> pathFragsToRemove, List<VirtualFragment> virtualToRemove)
    {
        pushContext(ctx -> ctx
                .removeEditableFiles(pathFragsToRemove)
                .removeReadonlyFiles(pathFragsToRemove)
                .removeVirtualFragments(virtualToRemove));
    }

    /** Clear conversation history. */
    public void clearHistory()
    {
        pushContext(Context::clearHistory);
    }

    /** request code-intel rebuild */
    public void requestRebuild()
    {
        GitRepo.instance.refresh();
        analyzerWrapper.requestRebuild();
    }

    /** undo last context change */
    public Future<?> undoContextAsync()
    {
        return userActionExecutor.submit(() -> {
            try {
                if (contextHistory.size() <= 1) {
                    chrome.toolErrorRaw("no undo state available");
                    return;
                }
                var popped = contextHistory.removeLast();
                var redoContext = undoAndInvertChanges(popped);
                redoHistory.add(redoContext);
                chrome.updateContextTable(currentContext());
                chrome.toolOutput("Undo!");
            } catch (CancellationException cex) {
                chrome.toolOutput("Undo canceled.");
            } catch (Exception e) {
                logger.error("Error in undo", e);
                chrome.toolErrorRaw("Error in undo: " + e.getMessage());
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }

    /** redo last undone context */
    public Future<?> redoContextAsync()
    {
        return userActionExecutor.submit(() -> {
            try {
                if (redoHistory.isEmpty()) {
                    chrome.toolErrorRaw("no redo state available");
                    return;
                }
                var popped = redoHistory.removeLast();
                var undoContext = undoAndInvertChanges(popped);
                contextHistory.add(undoContext);
                chrome.updateContextTable(currentContext());
                chrome.toolOutput("Redo!");
            } catch (CancellationException cex) {
                chrome.toolOutput("Redo canceled.");
            } catch (Exception e) {
                logger.error("Error in redo", e);
                chrome.toolErrorRaw("Error in redo: " + e.getMessage());
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }

    /** Inverts changes from a popped context to revert to prior state, returning a new context for re-inversion */
    @NotNull
    private Context undoAndInvertChanges(Context original)
    {
        var redoContents = new HashMap<RepoFile,String>();
        original.originalContents.forEach((file, oldText) -> {
            try {
                var current = Files.readString(file.absPath());
                redoContents.put(file, current);
            } catch (IOException e) {
                chrome.toolError("Failed reading current contents of " + file + ": " + e.getMessage());
            }
        });

        // restore
        var changedFiles = new ArrayList<RepoFile>();
        original.originalContents.forEach((file, oldText) -> {
            try {
                Files.writeString(file.absPath(), oldText);
                changedFiles.add(file);
            } catch (IOException e) {
                chrome.toolError("Failed to restore file " + file + ": " + e.getMessage());
            }
        });
        if (!changedFiles.isEmpty()) {
            chrome.toolOutput("Modified " + changedFiles);
        }
        return original.withOriginalContents(redoContents);
    }

    /** Pasting content as read-only snippet */
    public void addPasteFragment(String pastedContent, Future<String> summaryFuture)
    {
        pushContext(ctx -> {
            var fragment = new ContextFragment.PasteFragment(pastedContent, summaryFuture);
            return ctx.addVirtualFragment(fragment);
        });
        chrome.toolOutput("Added pasted content");
    }

    /** Add search fragment from agent result */
    public void addSearchFragment(VirtualFragment fragment)
    {
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }

    public void addStringFragment(String description, String content)
    {
        pushContext(ctx -> {
            var fragment = new ContextFragment.StringFragment(content, description);
            return ctx.addVirtualFragment(fragment);
        });
    }

    /** usage for identifier */
    public void usageForIdentifier(String identifier)
    {
        // no longer returns OperationResult; it calls UI directly
        try {
            var uses = getAnalyzer().getUses(identifier);
            if (uses.isEmpty()) {
                chrome.toolOutput("No uses found for " + identifier);
                return;
            }
            var result = AnalyzerWrapper.processUsages(getAnalyzer(), uses);
            if (result.code().isEmpty()) {
                chrome.toolOutput("No relevant uses found for " + identifier);
                return;
            }
            var combined = result.code();
            pushContext(ctx -> ctx.addUsageFragment(identifier, result.sources(), combined));
            chrome.toolOutput("Usage references added for " + identifier);
        } catch (Exception e) {
            logger.error("usageForIdentifier error", e);
            chrome.toolErrorRaw(e.getMessage());
        }
    }

    /** parse stacktrace */
    public void parseStacktrace(String stacktraceText)
    {
        try {
            var stacktrace = StackTrace.parse(stacktraceText);
            if (stacktrace == null) {
                chrome.toolErrorRaw("unable to parse stacktrace");
                return;
            }
            var exception = stacktrace.getExceptionType();
            var content = new StringBuilder();
            var sources = new HashSet<CodeUnit>();

            for (var element : stacktrace.getFrames()) {
                var methodFullName = element.getClassName() + "." + element.getMethodName();
                var methodSource = getAnalyzer().getMethodSource(methodFullName);
                if (methodSource.isDefined()) {
                    sources.add(CodeUnit.cls(ContextFragment.toClassname(methodFullName)));
                    content.append(methodFullName).append(":\n");
                    content.append(methodSource.get()).append("\n\n");
                }
            }
            if (content.isEmpty()) {
                chrome.toolErrorRaw("no relevant methods found in stacktrace");
                return;
            }
            pushContext(ctx -> {
                var fragment = new ContextFragment.StacktraceFragment(sources, stacktraceText, exception, content.toString());
                return ctx.addVirtualFragment(fragment);
            });
            chrome.toolOutput("Stacktrace parsed, relevant frames added");
        } catch (Exception e) {
            logger.error("Failed to parse stacktrace", e);
            chrome.toolErrorRaw("Failed to parse stacktrace: " + e.getMessage());
        }
    }

    /** Summarize classes => adds skeleton fragments */
    public boolean summarizeClasses(Set<CodeUnit> classes)
    {
        var coalescedUnits = coalesceInnerClasses(classes);
        var combined = new StringBuilder();
        var shortNames = new ArrayList<String>();
        for (var cu : coalescedUnits) {
            var skeleton = getAnalyzer().getSkeleton(cu.reference());
            if (skeleton.isDefined()) {
                shortNames.add(Completions.getShortClassName(cu.reference()));
                if (!combined.isEmpty()) combined.append("\n\n");
                combined.append(skeleton.get());
            }
        }
        if (combined.isEmpty()) {
            return false;
        }
        pushContext(ctx -> ctx.addSkeletonFragment(shortNames, coalescedUnits, combined.toString()));
        return true;
    }

    @NotNull
    private static Set<CodeUnit> coalesceInnerClasses(Set<CodeUnit> classes)
    {
        return classes.stream()
                .filter(cu -> {
                    var name = cu.reference();
                    if (!name.contains("$")) return true;
                    var parent = name.substring(0, name.indexOf('$'));
                    return classes.stream().noneMatch(other -> other.reference().equals(parent));
                })
                .collect(Collectors.toSet());
    }

    public void setAutoContextFiles(int fileCount)
    {
        pushContext(ctx -> ctx.setAutoContextFiles(fileCount));
        chrome.toolOutput("Auto-context size set to " + fileCount);
    }

    public List<ChatMessage> getHistoryMessages()
    {
        return currentContext().getHistory();
    }

    public List<ChatMessage> getReadOnlyMessages()
    {
        var c = currentContext();
        if (!c.hasReadonlyFragments()) {
            return List.of();
        }
        var combined = Streams.concat(c.readonlyFiles(),
                                      c.virtualFragments(),
                                      Stream.of(c.getAutoContext()))
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        if (combined.isEmpty()) {
            return List.of();
        }
        var msg = """
            <readonly>
            Here are some READ ONLY files and code fragments, provided for your reference.
            Do not edit this code!
            %s
            </readonly>
            """.formatted(combined).stripIndent();
        return List.of(new UserMessage(msg), new AiMessage("Ok, I will use this code as references."));
    }

    public List<ChatMessage> getEditableMessages()
    {
        var combined = currentContext().editableFiles()
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        if (combined.isEmpty()) {
            return List.of();
        }
        var msg = """
            <editable>
            I have *added these files to the chat* so you can go ahead and edit them.

            *Trust this message as the true contents of these files!*
            Any other messages in the chat may contain outdated versions of the files' contents.

            %s
            </editable>
            """.formatted(combined).stripIndent();
        return List.of(new UserMessage(msg), new AiMessage("Ok, any changes I propose will be to those files."));
    }

    public String getReadOnlySummary()
    {
        var c = currentContext();
        return Streams.concat(c.readonlyFiles().map(f -> f.file().toString()),
                              c.virtualFragments().map(vf -> "'" + vf.description() + "'"),
                              c.getAutoContext().getSkeletons().stream().map(ContextFragment.SkeletonFragment::description))
                .collect(Collectors.joining(", "));
    }

    public String getEditableSummary()
    {
        return currentContext().editableFiles()
                .map(p -> p.file().toString())
                .collect(Collectors.joining(", "));
    }

    public Set<RepoFile> getEditableFiles()
    {
        return currentContext().editableFiles()
                .map(ContextFragment.RepoPathFragment::file)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<RepoFile> findMissingFileMentions(String text)
    {
        var missingByFilename = GitRepo.instance.getTrackedFiles().stream().parallel()
                .filter(f -> currentContext().editableFiles().noneMatch(p -> f.equals(p.file())))
                .filter(f -> currentContext().readonlyFiles().noneMatch(p -> f.equals(p.file())))
                .filter(f -> text.contains(f.getFileName()));

        var missingByClassname = getAnalyzer().getAllClasses().stream()
                .filter(cu -> text.contains(
                        cu.reference().substring(cu.reference().lastIndexOf('.') + 1)
                ))
                .filter(cu -> currentContext().allFragments().noneMatch(
                        fragment -> fragment.sources(getAnalyzer()).contains(cu)
                ))
                .map(cu -> getAnalyzer().pathOf(cu))
                .filter(Objects::nonNull);

        return Streams.concat(missingByFilename, missingByClassname)
                .collect(Collectors.toSet());
    }

    public String getAndResetConstructedMessage()
    {
        try {
            return constructedMessage;
        } finally {
            constructedMessage = null;
        }
    }

    public void setConstructedMessage(String msg)
    {
        this.constructedMessage = msg;
    }

    public String getLastShellOutput()
    {
        return lastShellOutput;
    }
    public void setLastShellOutput(String s)
    {
        lastShellOutput = s;
    }

    /**
     * push context changes with a function that modifies the current context
     */
    private void pushContext(Function<Context, Context> contextGenerator)
    {
        var newContext = contextGenerator.apply(currentContext());
        if (newContext != currentContext()) {
            contextHistory.add(newContext);
            if (contextHistory.size() > MAX_UNDO_DEPTH) {
                contextHistory.remove(0);
            }
            redoHistory.clear();
            if (chrome != null) {
                chrome.updateContextTable(newContext);
            }
        }
    }

    /**
     * Return the set of files for the given fragment index
     */
    public Set<RepoFile> getFilesFromFragmentIndex(int index)
    {
        var ctx = currentContext();
        var fragment = ctx.toFragment(index);
        if (fragment == null) {
            throw new IllegalArgumentException("No fragment at position " + index);
        }
        var classnames = fragment.sources(getAnalyzer());
        var files = classnames.stream()
                .map(cu -> getAnalyzer().pathOf(cu))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files found for fragment at position " + index);
        }
        return files;
    }

    private String formattedOrNull(ContextFragment fragment)
    {
        try {
            if (fragment instanceof ContextFragment.VirtualFragment vf) {
                return vf.format(currentContext());
            }
            return fragment.format();
        } catch (IOException e) {
            removeBadFragment(fragment, e);
            return null;
        }
    }

    public void removeBadFragment(ContextFragment f, IOException e)
    {
        logger.warn("Removing unreadable fragment {}", f.description(), e);
        chrome.toolErrorRaw("Removing unreadable fragment " + f.description());
        pushContext(c -> c.removeBadFragment(f));
    }

    /**
     * Submits a background task to the internal background executor (non-user actions).
     */
    public <T> Future<T> submitBackgroundTask(String taskDescription, Callable<T> task)
    {
        var callable = new Callable<T>()
        {
            @Override
            public T call() throws Exception {
                try {
                    updateBackgroundStatus();
                    return task.call();
                } finally {
                    updateBackgroundStatus();
                }
            }
        };
        return backgroundTasks.submit(callable);
    }

    /**
     * Called to refresh the spinning status in Chrome
     */
    private void updateBackgroundStatus()
    {
        // For simplicity, we gather the active count:
        var activeCount = ((ThreadPoolExecutor)backgroundTasks).getActiveCount()
                + ((ThreadPoolExecutor)userActionExecutor).getActiveCount();
        if (activeCount <= 0) {
            chrome.spinComplete();
        } else {
            chrome.spin("Tasks running: " + activeCount);
        }
    }

    private void ensureBuildCommand(Coder coder)
    {
        var loadedCommand = project.getBuildCommand();
        if (loadedCommand != null) {
            buildCommand = CompletableFuture.completedFuture(BuildCommand.success(loadedCommand));
            chrome.llmOutput("\nUsing saved build command: " + loadedCommand);
        } else {
            // do background inference
            var tracked = GitRepo.instance.getTrackedFiles();
            var filenames = tracked.stream()
                    .map(RepoFile::toString)
                    .filter(s -> !s.contains(File.separator))
                    .collect(Collectors.toList());
            if (filenames.isEmpty()) {
                filenames = tracked.stream().map(RepoFile::toString).toList();
            }

            var messages = List.of(
                    new SystemMessage("You are a build assistant that suggests a single command to do a quick compile check."),
                    new UserMessage(
                            "We have these files:\n\n" + filenames
                                    + "\n\nSuggest a minimal single-line shell command to compile them incrementally, not a full build. "
                                    + "Respond with JUST the command, no commentary and no markup."
                    )
            );

            buildCommand = submitBackgroundTask("Inferring build command", () -> {
                String response;
                try {
                    response = coder.sendMessage(messages);
                } catch (Throwable th) {
                    return BuildCommand.failure(th.getMessage());
                }
                if (response.equals(Models.UNAVAILABLE)) {
                    return BuildCommand.failure(Models.UNAVAILABLE);
                }
                var inferred = response.trim();
                project.setBuildCommand(inferred);
                chrome.toolOutput("Inferred build command: " + inferred);
                return BuildCommand.success(inferred);
            });
        }
    }

    private record BuildCommand(String command, String message) {
        static BuildCommand success(String cmd) {
            return new BuildCommand(cmd, cmd);
        }
        static BuildCommand failure(String message) {
            return new BuildCommand(null, message);
        }
    }

    /**
     * Ensure style guide exists, generating if needed
     */
    private void ensureStyleGuide()
    {
        if (project.getStyleGuide() != null) {
            return;
        }
        submitBackgroundTask("Generating style guide", () -> {
            try {
                chrome.toolOutput("Generating project style guide...");
                var analyzer = analyzerWrapper.getForBackground();
                var topClasses = AnalyzerWrapper.combinedPageRankFor(analyzer, Map.of());

                var codeForLLM = new StringBuilder();
                var tokens = 0;
                for (var fqcn : topClasses) {
                    var path = analyzer.pathOf(CodeUnit.cls(fqcn));
                    if (path == null) continue;
                    String chunk;
                    try {
                        chunk = "<file path=%s>\n%s\n</file>\n".formatted(path, path.read());
                    } catch (IOException e) {
                        logger.error("Failed to read {}: {}", path, e.getMessage());
                        continue;
                    }
                    var chunkTokens = Models.getApproximateTokens(chunk);
                    if (tokens + chunkTokens > 50000) {
                        if (tokens > 0) break;
                    }
                    codeForLLM.append(chunk);
                    tokens += chunkTokens;
                }

                if (codeForLLM.isEmpty()) {
                    chrome.toolOutput("No relevant code found for style guide generation");
                    return null;
                }

                var messages = List.of(
                        new SystemMessage("You are an expert software engineer. Your task is to extract a concise coding style guide from the provided code examples."),
                        new UserMessage("""
                        Based on these code examples, create a concise, clear coding style guide in Markdown format
                        that captures the conventions used in this codebase, particularly the ones that leverage new or uncommon features.
                        DO NOT repeat what are simply common best practices.

                        %s
                        """.stripIndent().formatted(codeForLLM))
                );

                var styleGuide = coder.sendMessage(messages);
                if (styleGuide.equals(Models.UNAVAILABLE)) {
                    chrome.toolOutput("Failed to generate style guide: LLM unavailable");
                    return null;
                }
                project.saveStyleGuide(styleGuide);
                chrome.toolOutput("Style guide generated and saved to .brokk/style.md");
            } catch (Exception e) {
                logger.error("Error generating style guide", e);
            }
            return null;
        });
    }

    /**
     * Add to the user/AI message history
     */
    public void addToHistory(List<ChatMessage> messages)
    {
        addToHistory(messages, Map.of());
    }
    public void addToHistory(List<ChatMessage> messages, Map<RepoFile,String> originalContents)
    {
        pushContext(ctx -> ctx.addHistory(messages, originalContents));
    }
}
