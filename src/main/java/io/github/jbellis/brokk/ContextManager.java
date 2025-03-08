package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Context.ParsedOutput;
import io.github.jbellis.brokk.ContextFragment.PathFragment;
import io.github.jbellis.brokk.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionDialog;
import io.github.jbellis.brokk.gui.LoggingExecutorService;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.SymbolSelectionDialog;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.AskPrompts;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.NotNull;
import scala.Option;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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

    // Convert a throwable to a string with full stack trace
    private String getStackTraceAsString(Throwable throwable) {
        var sw = new java.io.StringWriter();
        var pw = new java.io.PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    // Run main user-driven tasks in background (Code/Ask/Search/Run)
    // Only one of these can run at a time
    private final ExecutorService userActionExecutor = createLoggingExecutorService(Executors.newSingleThreadExecutor());

    @NotNull
    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap) {
        return new LoggingExecutorService(toWrap, th -> {
           var thread = Thread.currentThread();
           logger.error("Uncaught exception in thread {}", thread.getName(), th);
           if (chrome != null) {
               chrome.shellOutput("Uncaught exception in thread %s. This shouldn't happen, please report a bug!\n%s"
                                          .formatted(thread.getName(), getStackTraceAsString(th)));
           }
       });
    }

    // Context modification tasks (Edit/Read/Summarize/Drop/etc)
    // Multiple of these can run concurrently
    private final ExecutorService contextActionExecutor = createLoggingExecutorService(Executors.newFixedThreadPool(2));

    // Internal background tasks (unrelated to user actions)
    private final ExecutorService backgroundTasks = createLoggingExecutorService(Executors.newFixedThreadPool(2));

    private Project project;
    private final Path root;
    
    private Mode mode = Mode.EDIT;

    public void editSources(ContextFragment fragment) {
        contextActionExecutor.submit(() -> {
            var analyzer = getAnalyzer();
            Set<CodeUnit> sources = fragment.sources(analyzer);
            if (!sources.isEmpty()) {
                Set<RepoFile> files = sources.stream()
                        .map(analyzer::pathOf)
                        .filter(Option::isDefined)
                        .map(Option::get)
                        .collect(Collectors.toSet());

                if (!files.isEmpty()) {
                    addFiles(files);
                }
            }
        });
    }

    public Coder getCoder() {
        return coder;
    }

    public enum Mode { EDIT, APPLY }

    // Keep contexts for undo/redo
    private static final int MAX_UNDO_DEPTH = 100;
    // Package-private to allow Chrome to access for context history display
    final List<Context> contextHistory = new ArrayList<>();
    private final List<Context> redoHistory = new ArrayList<>();

    // Possibly store an inferred buildCommand
    private Future<BuildCommand> buildCommand;

    /**
     * Minimal constructor called from Brokk
     */
    public ContextManager(Path root)
    {
        this.root = root.toAbsolutePath();
        this.project = new Project(root);
    }

    /**
     * Called from Brokk to finish wiring up references to Chrome and Coder
     */
    public void resolveCircularReferences(Chrome chrome, Coder coder)
    {
        this.chrome = chrome;
        this.coder = coder;
        this.analyzerWrapper = new AnalyzerWrapper(project, chrome, backgroundTasks);
        // Context's analyzer reference is retained for the whole chain so wait until we have that ready
        // before adding the Context sentinel to history
        var initialContext = new Context(analyzerWrapper, 5);
        contextHistory.add(initialContext);
        chrome.setContext(initialContext);

        ensureStyleGuide();
        ensureBuildCommand(coder);
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
        return contextHistory.isEmpty() ? null : contextHistory.getLast();
    }

    /**
     * Return the main analyzer, building it if needed
     */
    public Analyzer getAnalyzer()
    {
        return analyzerWrapper.get();
    }

    public Analyzer getAnalyzerNonBlocking() {
        return analyzerWrapper.getNonBlocking();
    }

    public Path getRoot()
    {
        return root;
    }

    public Future<?> runRunCommandAsync(String input)
    {
        assert chrome != null;
        return userActionExecutor.submit(() -> {
            try {
                chrome.toolOutput("Executing: " + input);
                var result = Environment.instance.captureShellCommand(input);
                String output = result.output().isBlank() ? "[operation completed with no output]" : result.output();
                chrome.shellOutput(output);
                
                // Add to context history with the output text
                pushContext(ctx -> {
                    var runFrag = new ContextFragment.StringFragment(output, "Run " + input);
                    var parsed = new ParsedOutput(chrome.getLlmOutputText(), runFrag);
                    return ctx.withParsedOutput(parsed, CompletableFuture.completedFuture("Run " + input));
                });
            } finally {
                chrome.enableUserActionButtons();
            }
        });
    }

    public Future<?> runCodeCommandAsync(String input)
    {
        assert chrome != null;
        return userActionExecutor.submit(() -> {
            try {
                LLM.runSession(coder, chrome, getCurrentModel(coder.models), input);
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
                chrome.toolOutput("Request sent");
                var response = coder.sendStreaming(getCurrentModel(coder.models), messages, true);
                if (response.chatResponse() != null) {
                    addToHistory(List.of(messages.getLast(), response.chatResponse().aiMessage()), Map.of(), question);
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
        assert chrome != null;
        return userActionExecutor.submit(() -> {
            try {
                if (query.isBlank()) {
                    chrome.toolErrorRaw("Please provide a search query");
                    return;
                }
                // run a search agent
                var agent = new SearchAgent(query, this, coder, chrome);
                var result = agent.execute();
                if (result == null) {
                    chrome.toolOutput("Search was interrupted");
                } else {
                    chrome.llmOutput("\n\n# Anaswer" + "\n\n" + result.text() + "\n");
                    // The search agent already creates the right fragment type
                    addSearchFragment(result);
                }
            } catch (CancellationException cex) {
                chrome.toolOutput("Search command canceled.");
            } finally {
                chrome.enableUserActionButtons();
            }
        });
    }

    // ------------------------------------------------------------------
    // Asynchronous context actions: add/read/copy/edit/summarize/drop
    // ------------------------------------------------------------------

    /**
     * Shows the symbol selection dialog and adds usage information for the selected symbol.
     */
    public Future<?> findSymbolUsageAsync()
    {
        assert chrome != null;
        return contextActionExecutor.submit(() -> {
            try {
                String symbol = showSymbolSelectionDialog("Select Symbol");
                if (symbol != null && !symbol.isBlank()) {
                    usageForIdentifier(symbol);
                } else {
                    chrome.toolOutput("No symbol selected.");
                }
            } catch (CancellationException cex) {
                chrome.toolOutput("Symbol selection canceled.");
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
        try {
            if (dialog.isConfirmed()) {
                return dialog.getSelectedFiles();
            }
            return List.of();
        } finally {
            chrome.focusInput();
        }
    }
    
    /**
     * Show the symbol selection dialog
     */
    private String showSymbolSelectionDialog(String title)
    {
        var dialog = new SymbolSelectionDialog(null, getAnalyzer(), title);
        SwingUtil.runOnEDT(() -> {
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), 400);
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
        });
        try {
            if (dialog.isConfirmed()) {
                return dialog.getSelectedSymbol();
            }
            return null;
        } finally {
            chrome.focusInput();
        }
    }

    /**
     * Performed by the action buttons in the context panel: "edit / read / copy / drop / summarize"
     * If selectedFragments is empty, it means "All". We handle logic accordingly.
     */
    public Future<?> performContextActionAsync(Chrome.ContextAction action, List<ContextFragment> selectedFragments)
    {
        return contextActionExecutor.submit(() -> {
            try {
                switch (action) {
                    case EDIT -> doEditAction(selectedFragments);
                    case READ -> doReadAction(selectedFragments);
                    case COPY -> doCopyAction(selectedFragments);
                    case DROP -> doDropAction(selectedFragments);
                    case SUMMARIZE -> doSummarizeAction(selectedFragments);
                    case PASTE -> doPasteAction();
                }
            } catch (CancellationException cex) {
                chrome.toolOutput(action + " canceled.");
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }
    
    /**
     * Asynchronous action for suggesting a commit message
     */
    public Future<?> performCommitActionAsync()
    {
        return contextActionExecutor.submit(() -> {
            try {
                doCommitAction();
            } catch (CancellationException cex) {
                chrome.toolOutput("Commit action canceled.");
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }

    private void doEditAction(List<ContextFragment> selectedFragments)
    {
        if (selectedFragments.isEmpty()) {
            // Show a file selection dialog to add new files
            var files = showFileSelectionDialog("Add Context");
            if (!files.isEmpty()) {
                addFiles(files);
            } else {
                chrome.toolOutput("No files selected.");
            }
        } else {
            var files = new HashSet<RepoFile>();
            for (var fragment : selectedFragments) {
                files.addAll(getFilesFromFragment(fragment));
            }
            addFiles(files);
        }
    }

    private void doReadAction(List<ContextFragment> selectedFragments)
    {
        if (selectedFragments.isEmpty()) {
            // Show a file selection dialog for read-only
            var files = showFileSelectionDialog("Read Context");
            if (!files.isEmpty()) {
                addReadOnlyFiles(files);
            } else {
                chrome.toolOutput("No files selected.");
            }
        } else {
            var files = new HashSet<RepoFile>();
            for (var fragment : selectedFragments) {
                files.addAll(getFilesFromFragment(fragment));
            }
            addReadOnlyFiles(files);
        }
    }

    private void doCopyAction(List<ContextFragment> selectedFragments) {
        String content;
        if (selectedFragments.isEmpty()) {
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
            var sb = new StringBuilder();
            for (var frag : selectedFragments) {
                try {
                    sb.append(frag.text()).append("\n\n");
                } catch (IOException e) {
                    removeBadFragment(frag, e);
                    chrome.toolErrorRaw("Error reading fragment: " + e.getMessage());
                }
            }
            content = sb.toString();
        }

        var sel = new java.awt.datatransfer.StringSelection(content);
        var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(sel, sel);
        chrome.toolOutput("Content copied to clipboard");
    }

    private void doPasteAction()
    {
        // Get text from clipboard
        String clipboardText;
        try {
            var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            var contents = clipboard.getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                chrome.toolErrorRaw("No text on clipboard");
                return;
            }
            clipboardText = (String) contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (clipboardText.isBlank()) {
                chrome.toolErrorRaw("Clipboard is empty");
                return;
            }
        } catch (Exception e) {
            chrome.toolErrorRaw("Failed to read clipboard: " + e.getMessage());
            return;
        }

        // First try to parse as stacktrace
        var stacktrace = StackTrace.parse(clipboardText);
        if (stacktrace != null) {
            addStacktraceFragment(clipboardText);
            return;
        }

        // If not a stacktrace, add as string fragment
        addPasteFragment("Pasted text", submitSummarizeTaskForPaste(clipboardText));
        chrome.toolOutput("Clipboard content added as text");
    }

    private void doDropAction(List<ContextFragment> selectedFragments)
    {
        if (selectedFragments.isEmpty()) {
            if (currentContext().isEmpty()) {
                chrome.toolErrorRaw("No context to drop");
                return;
            }
            dropAll();
            chrome.toolOutput("Dropped all context");
        } else {
            var pathFragsToRemove = new ArrayList<ContextFragment.PathFragment>();
            var virtualToRemove = new ArrayList<ContextFragment.VirtualFragment>();
            boolean clearHistory = false;

            for (var frag : selectedFragments) {
                if (frag instanceof ContextFragment.ConversationFragment) {
                    clearHistory = true;
                } else if (frag instanceof ContextFragment.AutoContext) {
                    setAutoContextFiles(0);
                } else if (frag instanceof ContextFragment.PathFragment pf) {
                    pathFragsToRemove.add(pf);
                } else {
                    assert frag instanceof ContextFragment.VirtualFragment: frag;
                    virtualToRemove.add((VirtualFragment) frag);
                }
            }
            
            if (clearHistory) {
                clearHistory();
                chrome.toolOutput("Cleared conversation history");
            }
            
            drop(pathFragsToRemove, virtualToRemove);
            
            if (!pathFragsToRemove.isEmpty() || !virtualToRemove.isEmpty()) {
                chrome.toolOutput("Dropped " + (pathFragsToRemove.size() + virtualToRemove.size()) + " items");
            }
        }
    }

    /**
     * Generate a commit message using the LLM and prefill the command input
     */
    private void doCommitAction() {
        var messages = CommitPrompts.instance.collectMessages(this);
        if (messages.isEmpty()) {
            chrome.toolErrorRaw("Nothing to commit");
            return;
        }

        chrome.toolOutput("Inferring commit message");
        String commitMsg = coder.sendMessage(messages);
        if (commitMsg.isEmpty()) {
            chrome.toolErrorRaw("LLM did not provide a commit message");
            return;
        }

        // Escape quotes in the commit message
        commitMsg = commitMsg.replace("\"", "\\\"");

        // Prefill the command input field
        String finalCommitMsg = commitMsg;
        chrome.prefillCommand("git commit -a -m \"" + finalCommitMsg + "\"");
    }
    
    private void doSummarizeAction(List<ContextFragment> selectedFragments) {
        HashSet<CodeUnit> sources = new HashSet<>();
        String sourceDescription;

        if (selectedFragments.isEmpty()) {
            // Show file selection dialog when nothing is selected
            var files = showFileSelectionDialog("Summarize Files");
            if (files.isEmpty()) {
                chrome.toolOutput("No files selected for summarization");
                return;
            }

            for (var file : files) {
                sources.addAll(getAnalyzer().getClassesInFile(file));
            }
            sourceDescription = files.size() + " files";
        } else {
            // Extract sources from selected fragments
            for (var frag : selectedFragments) {
                sources.addAll(frag.sources(getAnalyzer()));
            }
            sourceDescription = selectedFragments.size() + " fragments";
        }

        if (sources.isEmpty()) {
            chrome.toolErrorRaw("No classes found in the selected " + (selectedFragments.isEmpty() ? "files" : "fragments"));
            return;
        }

        boolean success = summarizeClasses(sources);
        if (success) {
            chrome.toolOutput("Summarized " + sources.size() + " classes from " + sourceDescription);
        } else {
            chrome.toolErrorRaw("Failed to summarize classes");
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
        return undoContextAsync(1);
    }
    
    /** undo multiple context changes to reach a specific point in history */
    public Future<?> undoContextAsync(int stepsToUndo)
    {
        int finalStepsToUndo = Math.min(stepsToUndo, contextHistory.size() - 1);
        return contextActionExecutor.submit(() -> {
            try {
                if (contextHistory.size() <= 1) {
                    chrome.toolErrorRaw("no undo state available");
                    return;
                }
                
                for (int i = 0; i < finalStepsToUndo; i++) {
                    var popped = contextHistory.removeLast();
                    var redoContext = undoAndInvertChanges(popped);
                    redoHistory.add(redoContext);
                }
                
                chrome.setContext(currentContext());
                chrome.toolOutput("Undid " + finalStepsToUndo + " step" + (finalStepsToUndo > 1 ? "s" : "") + "!");
            } catch (CancellationException cex) {
                chrome.toolOutput("Undo canceled.");
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }

    /** redo last undone context */
    public Future<?> redoContextAsync()
    {
        return contextActionExecutor.submit(() -> {
            try {
                if (redoHistory.isEmpty()) {
                    chrome.toolErrorRaw("no redo state available");
                    return;
                }
                var popped = redoHistory.removeLast();
                var undoContext = undoAndInvertChanges(popped);
                contextHistory.add(undoContext);
                chrome.setContext(currentContext());
                chrome.toolOutput("Redo!");
            } catch (CancellationException cex) {
                chrome.toolOutput("Redo canceled.");
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
            return ctx.addPasteFragment(fragment, summaryFuture);
        });
        chrome.toolOutput("Added pasted content");
    }

    /** Add search fragment from agent result */
    public void addSearchFragment(VirtualFragment fragment)
    {
        Future<String> query;
        if (fragment.description().split("\\s").length > 10) {
            query = submitSummarizeTaskForConversation(fragment.description());
        } else {
            query = CompletableFuture.completedFuture(fragment.description());
        }
        pushContext(ctx -> ctx.addSearchFragment(fragment, query, chrome.getLlmOutputText()));
    }

    public void addStringFragment(String description, String content)
    {
        pushContext(ctx -> {
            var fragment = new ContextFragment.StringFragment(content, description);
            return ctx.addVirtualFragment(fragment);
        });
    }
    
    /**
     * Adds any virtual fragment directly 
     */
    public void addVirtualFragment(VirtualFragment fragment)
    {
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }

    /**
     * Captures text from the LLM output area and adds it to the context.
     * Called from Chrome's capture button.
     */
    public void captureTextFromContextAsync()
    {
        contextActionExecutor.submit(() -> {
            try {
                // Use reflection or pass chrome reference in constructor to avoid direct dependency
                var selectedCtx = chrome.getSelectedContext();
                if (selectedCtx != null) {
                    addVirtualFragment(selectedCtx.getParsedOutput().parsedFragment());
                    chrome.toolOutput("Content captured from output");
                } else {
                    chrome.toolErrorRaw("No content to capture");
                }
            } catch (CancellationException cex) {
                chrome.toolOutput("Capture canceled.");
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }

    /**
     * Finds sources in the current output text and edits them.
     * Called from Chrome's edit files button.
     */
    public void editFilesFromContextAsync()
    {
        contextActionExecutor.submit(() -> {
            try {
                // Use reflection or pass chrome reference in constructor to avoid direct dependency
                var selectedCtx = chrome.getSelectedContext();
                if (selectedCtx != null && selectedCtx.getParsedOutput() != null) {
                    var fragment = selectedCtx.getParsedOutput().parsedFragment();
                    editSources(fragment);
                    chrome.toolOutput("Editing files referenced in output");
                } else {
                    chrome.toolErrorRaw("No content with file references to edit");
                }
            } catch (CancellationException cex) {
                chrome.toolOutput("Edit files canceled.");
            } finally {
                chrome.enableContextActionButtons();
                chrome.enableUserActionButtons();
            }
        });
    }

    /** usage for identifier */
    public void usageForIdentifier(String identifier)
    {
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
    }

    /** parse stacktrace */
    public void addStacktraceFragment(String stacktraceText)
    {
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
    }

    public List<ChatMessage> getHistoryMessages()
    {
        return currentContext().getHistory();
    }
    
    /**
     * Shutdown all executors
     */
    public void shutdown() {
        userActionExecutor.shutdown();
        contextActionExecutor.shutdown();
        backgroundTasks.shutdown();
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

    /**
     * push context changes with a function that modifies the current context
     */
    private void pushContext(Function<Context, Context> contextGenerator)
    {
        // Check if there's a history selection that's not the current context
        int selectedIndex = getSelectedHistoryIndex();
        if (selectedIndex >= 0 && selectedIndex < contextHistory.size() - 1) {
            // Truncate history to the selected point (without adding to redo)
            int currentSize = contextHistory.size();
            for (int i = currentSize - 1; i > selectedIndex; i--) {
                contextHistory.removeLast();
            }
            // Current context is now at the selected point
        }
        
        var newContext = contextGenerator.apply(currentContext());
        if (newContext == currentContext()) {
            return;
        }

        contextHistory.add(newContext);
        if (contextHistory.size() > MAX_UNDO_DEPTH) {
            contextHistory.removeFirst();
        }
        redoHistory.clear();
        chrome.toolOutput(newContext.getAction());
        chrome.setContext(newContext);
        chrome.clearContextHistorySelection();
        chrome.updateCaptureButtons(newContext);
    }
    
    /**
     * Gets the currently selected index in the history table, or -1 if none selected
     * May be called on or off the Swing EDT
     */
    private int getSelectedHistoryIndex() {
        assert chrome != null;
        if (SwingUtilities.isEventDispatchThread()) {
            return chrome.getContextHistoryTable().getSelectedRow();
        }
        return SwingUtil.runOnEDT(() -> chrome.getContextHistoryTable().getSelectedRow(), null);
    }

    /**
     * Return the set of files for the given fragment
     */
    public Set<RepoFile> getFilesFromFragment(ContextFragment fragment)
    {
        var classnames = fragment.sources(getAnalyzer());
        var files = classnames.stream()
                .map(cu -> getAnalyzer().pathOf(cu))
                .filter(Option::isDefined)
                .map(Option::get)
                .collect(Collectors.toSet());

        // If it's a PathFragment, make sure to include its file
        if (fragment instanceof ContextFragment.PathFragment pf && pf.file() instanceof RepoFile rf) {
            files.add(rf);
        }

        return files;
    }

    private String formattedOrNull(ContextFragment fragment)
    {
        try {
            return fragment.format();
        } catch (IOException e) {
            removeBadFragment(fragment, e);
            return null;
        }
    }

    public void removeBadFragment(ContextFragment f, IOException th)
    {
        logger.warn("Removing unreadable fragment {}", f.description(), th);
        chrome.toolErrorRaw("Removing unreadable fragment " + f.description());
        pushContext(c -> c.removeBadFragment(f));
    }

    private final AtomicInteger activeTaskCount = new AtomicInteger(0);

    public SwingWorker<String, Void> submitSummarizeTaskForPaste(String pastedContent) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                // This runs in background thread
                var msgs = List.of(
                        new UserMessage("Please summarize this content in 12 words or fewer:"),
                        new AiMessage("Ok, let's see it."),
                        new UserMessage(pastedContent)
                );
                return coder.sendMessage(msgs);
            }

            @Override
            protected void done() {
                chrome.updateContextTable();
                chrome.updateContextHistoryTable();
            }
        };

        worker.execute();
        return worker;
    }

    public SwingWorker<String, Void> submitSummarizeTaskForConversation(String input) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                // This runs in background thread
                var msgs = List.of(
                        new UserMessage("Please summarize this question or task in 5 words or fewer:"),
                        new AiMessage("Ok, let's see it."),
                        new UserMessage(input)
                );
                return coder.sendMessage(msgs);
            }

            @Override
            protected void done() {
                chrome.updateContextHistoryTable();
            }
        };

        worker.execute();
        return worker;
    }

    /**
     * Submits a background task to the internal background executor (non-user actions).
     */
    public <T> Future<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        // Increment counter before submitting
        activeTaskCount.incrementAndGet();

        return backgroundTasks.submit(() -> {
            try {
                chrome.spin(taskDescription);
                return task.call();
            } finally {
                // Decrement counter when done
                int remaining = activeTaskCount.decrementAndGet();
                SwingUtilities.invokeLater(() -> {
                    if (remaining <= 0) {
                        chrome.spinComplete();
                    } else {
                        chrome.spin("Tasks running: " + remaining);
                    }
                });
            }
        });
    }

    private void ensureBuildCommand(Coder coder)
    {
        var loadedCommand = project.getBuildCommand();
        if (loadedCommand != null) {
            buildCommand = CompletableFuture.completedFuture(BuildCommand.success(loadedCommand));
            // TODO show this to user somehow
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
                    var pathOption = analyzer.pathOf(CodeUnit.cls(fqcn));
                    if (pathOption.isEmpty()) continue;
                    var path = pathOption.get();
                    String chunk;
                    try {
                        chunk = "<file path=%s>\n%s\n</file>\n".formatted(pathOption, path.read());
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
    @Override
    public void addToHistory(List<ChatMessage> messages, Map<RepoFile, String> originalContents, Future<String> action)
    {
        pushContext(ctx -> ctx.addHistory(messages, originalContents, chrome.getLlmOutputText(), action));
    }

    @Override
    public void addToHistory(List<ChatMessage> messages, Map<RepoFile, String> originalContents, String action)
    {
        pushContext(ctx -> ctx.addHistory(messages, originalContents, chrome.getLlmOutputText(), submitSummarizeTaskForConversation(action)));
    }

    public List<Context> getContextHistory() {
        return contextHistory;
    }
}
