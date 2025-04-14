package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.BuildAgent.BuildDetails;
import io.github.jbellis.brokk.Context.ParsedOutput;
import io.github.jbellis.brokk.ContextFragment.PathFragment;
import io.github.jbellis.brokk.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.ContextHistory.UndoResult;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CallSite;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.tools.ContextTools;
import io.github.jbellis.brokk.tools.SearchTools;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.LoggingExecutorService;
import io.github.jbellis.brokk.util.StackTrace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
public class ContextManager implements IContextManager, AutoCloseable {
    private final Logger logger = LogManager.getLogger(ContextManager.class);

    private Chrome io; // for UI feedback - Initialized in resolveCircularReferences

    // Run main user-driven tasks in background (Code/Ask/Search/Run)
    // Only one of these can run at a time
    private final ExecutorService userActionExecutor = createLoggingExecutorService(Executors.newSingleThreadExecutor());
    private final AtomicReference<Thread> userActionThread = new AtomicReference<>();

    @NotNull
    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap) {
        return new LoggingExecutorService(toWrap, th -> {
            var thread = Thread.currentThread();
            logger.error("Uncaught exception in thread {}", thread.getName(), th);
            if (io != null) {
                io.systemOutput("Uncaught exception in thread %s. This shouldn't happen, please report a bug!\n%s"
                                       .formatted(thread.getName(), getStackTraceAsString(th)));
            }
        });
    }

    // Context modification tasks (Edit/Read/Summarize/Drop/etc)
    // Multiple of these can run concurrently
    private final ExecutorService contextActionExecutor = createLoggingExecutorService(
            new ThreadPoolExecutor(2, 2,
                                   60L, TimeUnit.SECONDS,
                                   new LinkedBlockingQueue<>(), // Unbounded queue
                                   Executors.defaultThreadFactory()));

    // Internal background tasks (unrelated to user actions)
    // Lots of threads allowed since AutoContext updates get dropped here
    // Use unbounded queue to prevent task rejection
    private final ExecutorService backgroundTasks = createLoggingExecutorService(
            new ThreadPoolExecutor(3, 12,
                                   60L, TimeUnit.SECONDS,
                                   new LinkedBlockingQueue<>(), // Unbounded queue to prevent rejection
                                   Executors.defaultThreadFactory()));

    private Project project; // Initialized in resolveCircularReferences
    private final Path root;
    private ToolRegistry toolRegistry; // Initialized in resolveCircularReferences
    private Models models; // Instance of Models

    // Context history for undo/redo functionality
    private final ContextHistory contextHistory;

    public ExecutorService getBackgroundTasks() {
        return backgroundTasks;
    }

    /**
     * Minimal constructor called from Brokk
     */
    public ContextManager(Path root)
    {
        this.root = root.toAbsolutePath().normalize();
        this.contextHistory = new ContextHistory();
        this.models = new Models();
        userActionExecutor.submit(() -> {
            userActionThread.set(Thread.currentThread());
        });
    }

    /**
     * Called from Brokk to finish wiring up references to Chrome and Coder
     */
    public void resolveCircularReferences(Chrome chrome) {
        this.io = chrome;

        // Set up the listener for analyzer events
        var analyzerListener = new AnalyzerListener() {
            @Override
            public void onBlocked() {
                if (Thread.currentThread() == userActionThread.get()) {
                    SwingUtilities.invokeLater(() -> io.actionOutput("Waiting for Code Intelligence"));
                }
            }

            @Override
            public void afterFirstBuild(String msg) {
                if (msg.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(
                            io.getFrame(),
                            "Code Intelligence is empty. Probably this means your language is not yet supported. File-based tools will continue to work.",
                            "Code Intelligence Warning",
                            JOptionPane.WARNING_MESSAGE
                        );
                    });
                } else {
                    SwingUtilities.invokeLater(() -> io.systemOutput(msg));
                }
            }

            @Override
            public void onRepoChange() {
                project.getRepo().refresh();
                io.updateGitRepo();
            }

            @Override
            public void onTrackedFileChange() {
                io.updateCommitPanel();
            }
        };
        this.project = new Project(root, this::submitBackgroundTask, analyzerListener);
        // Initialize models after project is created, passing the data retention policy
        var dataRetentionPolicy = project.getDataRetentionPolicy();
        if (dataRetentionPolicy == Project.DataRetentionPolicy.UNSET) {
            // Handle unset policy, e.g., prompt the user or default to MINIMAL
            // For now, let's default to MINIMAL if unset. Consider prompting later.
            logger.warn("Data Retention Policy is UNSET for project {}. Defaulting to MINIMAL.", root.getFileName());
            dataRetentionPolicy = Project.DataRetentionPolicy.MINIMAL;
            // Optionally save the default back to the project
            // project.setDataRetentionPolicy(dataRetentionPolicy);
        }
        this.models.reinit(dataRetentionPolicy);
        this.toolRegistry = new ToolRegistry();
         // Register standard tools
         this.toolRegistry.register(new SearchTools(this));
         this.toolRegistry.register(new ContextTools(this));

         // Load saved context or create a new one
         var welcomeMessage = buildWelcomeMessage();
        var initialContext = project.loadContext(this, welcomeMessage);
        if (initialContext == null) {
            initialContext = new Context(this, 10, welcomeMessage); // Default autocontext size
        } else {
            // Not sure why this is necessary -- for some reason AutoContext doesn't survive deserialization
            initialContext = initialContext.refresh();
        }
        contextHistory.setInitialContext(initialContext);
        chrome.updateContextHistoryTable(initialContext); // Update UI with loaded/new context

        // Ensure style guide and build details are loaded/generated asynchronously
        ensureStyleGuide();
        ensureBuildDetailsAsync(); // Changed from ensureBuildCommand
    }

    @Override
    public void replaceContext(Context context, Context replacement) {
        contextHistory.replaceContext(context, replacement);
        io.updateContextHistoryTable();
        io.updateContextTable();
    }

    public Project getProject() {
        return project;
    }

    @Override
    public Coder getCoder(StreamingChatLanguageModel model, String taskDescription) {
        return new Coder(model, taskDescription, this);
    }

    @Override
    public ProjectFile toFile(String relName)
    {
        return new ProjectFile(root, relName);
    }

    /**
     * Return the top context in the history stack
     */
    public Context topContext()
    {
        return contextHistory.topContext();
    }

    /**
     * Return the currently selected context in the UI, or the top context if none selected
     */
    public Context selectedContext()
    {
        return contextHistory.getSelectedContext();
    }

    public Path getRoot()
    {
        return root;
    }

    /** Returns the Models instance associated with this context manager. */
    @Override
    public Models getModels() {
        return models;
    }

    public Future<?> submitAction(String action, String input, Runnable task) {
        return userActionExecutor.submit(() -> {
            io.setLlmOutput("# %s\n%s\n\n# %s\n".formatted(action, input, action.equals("Run") ? "Output" : "Response"));
            io.disableHistoryPanel();

            try {
                task.run();
            } catch (CancellationException cex) {
                io.systemOutput("Canceled!");
            } catch (Exception e) {
                logger.error("Error in " + action, e);
                io.toolErrorRaw("Error in " + action + " processing: " + e.getMessage());
            } finally {
                io.hideOutputSpinner(); // in the case of error or stop
                io.actionComplete();
                io.enableUserActionButtons();
                io.enableHistoryPanel();
            }
        });
    }

    // TODO split this out from the Action executor?
    public Future<?> submitUserTask(String description, Runnable task) {
        return userActionExecutor.submit(() -> {
            try {
                io.actionOutput(description);
                task.run();
            } catch (CancellationException cex) {
                io.systemOutput(description + " canceled.");
            } catch (Exception e) {
                logger.error("Error while " + description, e);
                io.toolErrorRaw("Error while " + description + ": " + e.getMessage());
            } finally {
                io.actionComplete();
                io.enableUserActionButtons();
            }
        });
    }

    public <T> Future<T> submitUserTask(String description, Callable<T> task) {
        return userActionExecutor.submit(() -> {
            try {
                io.actionOutput(description);
                return task.call();
            } catch (CancellationException cex) {
                io.systemOutput(description + " canceled.");
                throw cex;
            } catch (Exception e) {
                logger.error("Error while " + description, e);
                io.toolErrorRaw("Error while " + description + ": " + e.getMessage());
                throw e;
            } finally {
                io.actionComplete();
                io.enableUserActionButtons();
            }
        });
    }

    public Future<?> submitContextTask(String description, Runnable task) {
        return contextActionExecutor.submit(() -> {
            try {
                task.run();
            } catch (CancellationException cex) {
                io.systemOutput(description + " canceled.");
            } catch (Exception e) {
                logger.error("Error while " + description, e);
                io.toolErrorRaw("Error while " + description + ": " + e.getMessage());
            } finally {
                io.enableUserActionButtons();
            }
        });
    }

    // ------------------------------------------------------------------
    // Asynchronous context actions: add/read/copy/edit/summarize/drop
    // ------------------------------------------------------------------
    // Core context manipulation logic called by ContextPanel / Chrome
    // ------------------------------------------------------------------

    public void setPlan(ContextFragment.PlanFragment plan) {
        pushContext(ctx -> ctx.withPlan(plan));
    }

    /** Add the given files to editable. */
    @Override
    public void editFiles(Collection<ProjectFile> files)
    {
        // Create the new fragments to be added as editable
        var proposedEditableFragments = files.stream().map(ContextFragment.ProjectPathFragment::new).toList();
        // Find existing read-only fragments that correspond to these files
        var currentReadOnlyFiles = topContext().readonlyFiles().collect(Collectors.toSet());
        var filesToEditSet = new HashSet<>(files);
        var existingReadOnlyFragmentsToRemove = currentReadOnlyFiles.stream()
                .filter(pf -> filesToEditSet.contains(pf.file()))
                .toList();
        // Find existing editable fragments that correspond to these files to avoid duplicates
        var currentEditableFileSet = topContext().editableFiles().map(PathFragment::file).collect(Collectors.toSet());
        var uniqueNewEditableFragments = proposedEditableFragments.stream()
                .filter(frag -> !currentEditableFileSet.contains(frag.file()))
                .toList();

        // Push the context update: remove the *existing* read-only fragments and add the *new, unique* editable ones
        pushContext(ctx -> ctx.removeReadonlyFiles(existingReadOnlyFragmentsToRemove)
                              .addEditableFiles(uniqueNewEditableFragments));
    }

    /** Add read-only files. */
    public void addReadOnlyFiles(Collection<? extends BrokkFile> files)
    {
        // Create the new fragments to be added as read-only
        var proposedReadOnlyFragments = files.stream().map(ContextFragment::toPathFragment).toList();
        // Find existing editable fragments that correspond to these files
        var currentEditableFiles = topContext().editableFiles().collect(Collectors.toSet());
        var filesToReadSet = files.stream().map(ContextFragment::toPathFragment).map(PathFragment::file).collect(Collectors.toSet());
        var existingEditableFragmentsToRemove = currentEditableFiles.stream()
                .filter(pf -> filesToReadSet.contains(pf.file()))
                .map(PathFragment.class::cast) // Cast to the required supertype
                .toList();
        // Find existing read-only fragments that correspond to these files to avoid duplicates
        var currentReadOnlyFileSet = topContext().readonlyFiles().map(PathFragment::file).collect(Collectors.toSet());
        var uniqueNewReadOnlyFragments = proposedReadOnlyFragments.stream()
                .filter(frag -> !currentReadOnlyFileSet.contains(frag.file()))
                .toList();

        // Push the context update: remove the *existing* editable fragments and add the *new, unique* read-only ones
        pushContext(ctx -> ctx.removeEditableFiles(existingEditableFragmentsToRemove)
                              .addReadonlyFiles(uniqueNewReadOnlyFragments));
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
        project.getRepo().refresh();
        project.rebuildAnalyzer();
    }

    /** undo last context change */
    public Future<?> undoContextAsync()
    {
        return undoContextAsync(1);
    }

    /** undo multiple context changes to reach a specific point in history */
    public Future<?> undoContextAsync(int stepsToUndo)
    {
        return contextActionExecutor.submit(() -> {
            try {
                UndoResult result = contextHistory.undo(stepsToUndo, io);
                if (result.wasUndone()) {
                    var currentContext = contextHistory.topContext();
                    io.updateContextHistoryTable(currentContext);
                    io.systemOutput("Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!");
                } else {
                    io.toolErrorRaw("no undo state available");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Undo canceled.");
            } finally {
                io.enableUserActionButtons();
            }
        });
    }
    
    /** undo changes until we reach the target context */
    public Future<?> undoContextUntilAsync(Context targetContext)
    {
        return contextActionExecutor.submit(() -> {
            try {
                UndoResult result = contextHistory.undoUntil(targetContext, io);
                if (result.wasUndone()) {
                    var currentContext = contextHistory.topContext();
                    io.updateContextHistoryTable(currentContext);
                    io.systemOutput("Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!");
                } else {
                    io.toolErrorRaw("Context not found or already at that point");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Undo canceled.");
            } finally {
                io.enableUserActionButtons();
            }
        });
    }

    /** redo last undone context */
    public Future<?> redoContextAsync()
    {
        return contextActionExecutor.submit(() -> {
            try {
                boolean wasRedone = contextHistory.redo(io);
                if (wasRedone) {
                    var currentContext = contextHistory.topContext();
                    io.updateContextHistoryTable(currentContext);
                    io.systemOutput("Redo!");
                } else {
                    io.toolErrorRaw("no redo state available");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Redo canceled.");
            } finally {
                io.enableUserActionButtons();
            }
        });
    }
    
    /** Reset the context to match the files and fragments from a historical context */
    public Future<?> resetContextToAsync(Context targetContext)
    {
        return contextActionExecutor.submit(() -> {
            try {
                pushContext(ctx -> Context.createFrom(targetContext, ctx));
                io.systemOutput("Reset context to match historical state!");
            } catch (CancellationException cex) {
                io.systemOutput("Reset context canceled.");
            } finally {
                io.enableUserActionButtons();
            }
        });
    }


    /**
     * Add search fragment from agent result
     *
     * @return a summary of the search
     */
    public Future<String> addSearchFragment(VirtualFragment fragment)
    {
        Future<String> query;
        if (fragment.description().split("\\s").length > 10) {
            query = submitSummarizeTaskForConversation(fragment.description());
        } else {
            query = CompletableFuture.completedFuture(fragment.description());
        }

        var llmOutputText = io.getLlmOutputText();
        if (llmOutputText == null) {
            io.systemOutput("Interrupted!");
            return query;
        }

        var parsed = new ParsedOutput(llmOutputText, fragment);
        pushContext(ctx -> ctx.addSearchFragment(query, parsed));
        return query;
    }

    /**
     * Adds any virtual fragment directly
     */
    public void addVirtualFragment(VirtualFragment fragment)
    {
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }

    /**
     * Adds a simple string content as a virtual fragment.
     * @param content The text content.
     * @param description A description for the fragment.
     */
    public void addStringFragment(String content, String description) {
        // This directly pushes a new context state with the added fragment.
        // Instantiate the StringFragment and add it via addVirtualFragment.
        pushContext(ctx -> {
            var fragment = new ContextFragment.StringFragment(content, description);
            return ctx.addVirtualFragment(fragment);
        });
    }

    /**
     * Adds a specific PathFragment (like GitHistoryFragment) to the read-only context.
     *
     * @param fragment The PathFragment to add.
     */
    public void addReadOnlyFragment(PathFragment fragment)
    {
        // Use the existing addReadonlyFiles method in Context, which takes a collection
        pushContext(ctx -> ctx.addReadonlyFiles(List.of(fragment)));
    }

    /**
     * Captures text from the LLM output area and adds it to the context.
     * Called from Chrome's capture button.
     */
    public void captureTextFromContextAsync()
    {
        contextActionExecutor.submit(() -> {
            try {
                var selectedCtx = selectedContext();
                if (selectedCtx != null && selectedCtx.getParsedOutput() != null) {
                    addVirtualFragment(selectedCtx.getParsedOutput().parsedFragment());
                    io.systemOutput("Content captured from output");
                } else {
                    io.toolErrorRaw("No content to capture");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Capture canceled.");
            } finally {
                io.enableUserActionButtons();
            }
        });
    }

    /** usage for identifier */
    public void usageForIdentifier(String identifier)
    {
        var uses = getAnalyzer().getUses(identifier);
        if (uses.isEmpty()) {
            io.systemOutput("No uses found for " + identifier);
            return;
        }
        var result = AnalyzerUtil.processUsages(getAnalyzer(), uses);
        if (result.code().isEmpty()) {
            io.systemOutput("No relevant uses found for " + identifier);
            return;
        }
        var combined = result.code();
        var fragment = new ContextFragment.UsageFragment("Uses", identifier, result.sources(), combined);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }
    
    /** callers for method */
    public void callersForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph)
    {
        if (callgraph == null || callgraph.isEmpty()) {
            io.systemOutput("No callers found for " + methodName);
            return;
        }

        String formattedCallGraph = AnalyzerUtil.formatCallGraph(callgraph, methodName, true);
        if (formattedCallGraph.isEmpty()) {
            io.systemOutput("No callers found for " + methodName);
            return;
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        var sourceFile = getAnalyzer().getFileFor(className);
        if (sourceFile.isDefined()) {
            sources.add(CodeUnit.cls(sourceFile.get(), className));
        }

        // The output is similar to UsageFragment, so we'll use that
        var fragment = new ContextFragment.UsageFragment("Callers (depth " + depth + ")", methodName, sources, formattedCallGraph);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));

        int totalCallSites = callgraph.values().stream().mapToInt(List::size).sum();
        io.systemOutput("Added call graph with " + totalCallSites + " call sites for callers of " + methodName + " with depth " + depth);
    }
    
    /** callees for method */
    public void calleesForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph)
    {
        if (callgraph == null || callgraph.isEmpty()) {
            io.systemOutput("No callees found for " + methodName);
            return;
        }

        String formattedCallGraph = AnalyzerUtil.formatCallGraph(callgraph, methodName, false);
        if (formattedCallGraph.isEmpty()) {
            io.systemOutput("No callees found for " + methodName);
            return;
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        var sourceFile = getAnalyzer().getFileFor(className);
        if (sourceFile.isDefined()) {
            sources.add(CodeUnit.cls(sourceFile.get(), className));
        }

        // The output is similar to UsageFragment, so we'll use that
        var fragment = new ContextFragment.UsageFragment("Callees (depth " + depth + ")", methodName, sources, formattedCallGraph);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));

        int totalCallSites = callgraph.values().stream().mapToInt(List::size).sum();
        io.systemOutput("Added call graph with " + totalCallSites + " call sites for methods called by " + methodName + " with depth " + depth);
    }

    /** parse stacktrace */
    public boolean addStacktraceFragment(StackTrace stacktrace)
    {
        assert stacktrace != null;

        var exception = stacktrace.getExceptionType();
        var content = new StringBuilder();
        var sources = new HashSet<CodeUnit>();

        for (var element : stacktrace.getFrames()) {
            var methodFullName = element.getClassName() + "." + element.getMethodName();
            var methodSource = getAnalyzer().getMethodSource(methodFullName);
            if (methodSource.isDefined()) {
                String className = ContextFragment.toClassname(methodFullName);
                var sourceFile = getAnalyzer().getFileFor(className);
                if (sourceFile.isDefined()) {
                    sources.add(CodeUnit.cls(sourceFile.get(), className));
                }
                content.append(methodFullName).append(":\n");
                content.append(methodSource.get()).append("\n\n");
            }
        }
        if (content.isEmpty()) {
            io.toolErrorRaw("No relevant methods found in stacktrace -- adding as text");
            return false;
        }
        pushContext(ctx -> {
            var fragment = new ContextFragment.StacktraceFragment(sources, stacktrace.getOriginalText(), exception, content.toString());
            return ctx.addVirtualFragment(fragment);
        });
        return true;
    }

    /** Summarize classes => adds skeleton fragments */
    public boolean summarizeClasses(Set<CodeUnit> classes) {
        if (getAnalyzer().isEmpty()) {
            io.toolErrorRaw("Code Intelligence is empty; nothing to add");
            return false;
        }

        var coalescedUnits = coalesceInnerClasses(classes);
        var skeletons = coalescedUnits.stream()
                .map(cu -> Map.entry(cu, getAnalyzer().getSkeleton(cu.fqName())))
                .filter(entry -> entry.getValue().isDefined())
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get())); // Rely on type inference
        if (skeletons.isEmpty()) {
            return false;
        }
        var skeletonFragment = new ContextFragment.SkeletonFragment(skeletons);
        addVirtualFragment(skeletonFragment);
        return true;
    }

    @NotNull
    private static Set<CodeUnit> coalesceInnerClasses(Set<CodeUnit> classes)
    {
        return classes.stream()
                .filter(cu -> {
                    var name = cu.fqName();
                    if (!name.contains("$")) return true;
                    var parent = name.substring(0, name.indexOf('$'));
                    return classes.stream().noneMatch(other -> other.fqName().equals(parent));
                })
                .collect(Collectors.toSet());
    }

    /**
     * Update auto-context file count on the current executor thread (for background operations)
     */
    public void setAutoContextFiles(int fileCount)
    {
        pushContext(ctx -> ctx.setAutoContextFiles(fileCount));
    }

    /**
     * Asynchronous version of setAutoContextFiles to avoid blocking the UI thread
     */
    public Future<?> setAutoContextFilesAsync(int fileCount)
    {
        return contextActionExecutor.submit(() -> {
            try {
                setAutoContextFiles(fileCount);
            } catch (CancellationException cex) {
                io.systemOutput("Auto-context update canceled.");
            } finally {
                io.enableUserActionButtons();
            }
        });
    }

    /**
      * @return A list containing two messages: a UserMessage with the string representation of the task history,
      *         and an AiMessage acknowledging it. Returns an empty list if there is no history.
      */
     public List<ChatMessage> getHistoryMessages()
     {
         var taskHistory = selectedContext().getTaskHistory();
         if (taskHistory.isEmpty()) {
             return List.of();
         }
 
         // Concatenate the string representation of each TaskHistory
         String historyString = taskHistory.stream()
                                           .map(TaskEntry::toString)
                                           .collect(Collectors.joining("\n\n"));
 
         // Create the UserMessage containing the history and the AI acknowledgment
         var historyUserMessage = new UserMessage("Here is our work history so far:\n<history>\n%s\n</history>".formatted(historyString));
         var historyAiMessage = new AiMessage("Ok, I see the history."); // Simple acknowledgment
 
         return List.of(historyUserMessage, historyAiMessage);
     }
 
     /**
     * Build a welcome message with environment information.
     * Uses statically available model info.
     */
    private String buildWelcomeMessage() {
        String welcomeMarkdown;
        var mdPath = "/WELCOME.md";
        try (var welcomeStream = Brokk.class.getResourceAsStream(mdPath)) {
            if (welcomeStream != null) {
                welcomeMarkdown = new String(welcomeStream.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                logger.warn("WELCOME.md resource not found.");
                welcomeMarkdown = "Welcome to Brokk!";
            }
        } catch (IOException e1) {
            throw new UncheckedIOException(e1);
        }

        Properties props = new Properties();
        try {
            props.load(getClass().getResourceAsStream("/version.properties"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var version = props.getProperty("version", "unknown");

        // Get available models for display
        var availableModels = models.getAvailableModels(); // Use instance field
        String modelsList = availableModels.isEmpty()
                ? "  - No models loaded (Check network connection)"
                : availableModels.entrySet().stream()
                    .map(e -> "  - %s (%s)".formatted(e.getKey(), e.getValue()))
                    .sorted()
                    .collect(Collectors.joining("\n"));
        String quickModelName = models.nameOf(models.quickModel()); // Use instance field

        return """
              %s

              ## Environment
              - Brokk version: %s
              - Quick model: %s
              - Project: %s (%d native files, %d total including dependencies)
              - Analyzer language: %s
              - Available models:
              %s
              """.stripIndent().formatted(welcomeMarkdown,
                            version,
                            quickModelName.equals("unknown") ? "(Unavailable)" : quickModelName,
                            project.getRoot().getFileName(), // Show just the folder name
                            project.getRepo().getTrackedFiles().size(),
                            project.getFiles().size(),
                            project.getAnalyzerLanguage(),
                            modelsList);
        /* Duplicate declaration removed:
        String quickModelName = models.nameOf(models.quickModel()); // Use instance field
        */

        // Unreachable return block removed
    }

    /**
     * Shutdown all executors
     */
    public void close() {
        userActionExecutor.shutdown();
        contextActionExecutor.shutdown();
        backgroundTasks.shutdown();
        project.close();
    }

    public Collection<ChatMessage> getWorkspaceContentsMessages() {
        var c = selectedContext();

        var readOnly = Streams.concat(c.readonlyFiles(),
                                      c.virtualFragments(),
                                      Stream.of(c.getAutoContext()))
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .filter(st -> !st.isBlank())
                .collect(Collectors.joining("\n\n"));
        if (!readOnly.isEmpty()) {
              readOnly = """
                <readonly>
                  Here are some READ ONLY files and code fragments, provided for your reference.
                  Do not edit this code!

                  %s
                </readonly>
                """.stripIndent().formatted(readOnly.indent(2)).indent(2);
          }

        var editable = selectedContext().editableFiles()
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        if (editable.isEmpty()) {
              editable = """
                <editable>
                  I have *added these files to the workspace* so you can go ahead and edit them.

                  *Trust this message as the true contents of these files!*
                  Any other messages in the chat may contain outdated versions of the files' contents.

                  %s
                </editable>
                """.stripIndent().formatted(editable.indent(2)).indent(2);
          }
        var workspace = """
            <workspace>
              %s
              %s
            </workspace>
            """.stripIndent().formatted(readOnly.indent(2), editable.indent(2));
        return List.of(new UserMessage(workspace), new AiMessage("Thank you for providing the workspace contents."));
    }

    /**
     * Gets the current plan as ChatMessages for the LLM, if a plan exists.
     * Includes an acknowledgement message from the AI.
     * @return List containing UserMessage with plan and AiMessage ack, or empty list.
     */
    public List<ChatMessage> getPlanMessages() {
        var currentPlan = selectedContext().getPlan();
        if (currentPlan == null) {
            return List.of();
        }
        try {
            // PlanFragment.format() already includes <plan> tags and fragmentid
            var planContent = currentPlan.format();
            var userMsg = """
                  Here is the high-level plan to follow:
                  %s
                  """.stripIndent().formatted(planContent);
            return List.of(new UserMessage(userMsg), new AiMessage("Ok, I will follow this plan."));
        } catch (IOException e) {
            // This shouldn't happen for PlanFragment unless there's a deeper issue
            logger.error("IOException formatting PlanFragment (should not happen)", e);
            removeBadFragment(currentPlan, e); // Attempt recovery
            return List.of();
        }
    }

    public String getReadOnlySummary()
    {
        var c = selectedContext();
        return Streams.concat(c.readonlyFiles().map(f -> f.file().toString()),
                              c.virtualFragments().map(vf -> "'" + vf.description() + "'"),
                              Stream.of(c.getAutoContext().isEmpty() ? "" : c.getAutoContext().description()))
                .filter(st -> !st.isBlank())
                .collect(Collectors.joining(", "));
    }

    public String getEditableSummary()
    {
        return selectedContext().editableFiles()
                .map(p -> p.file().toString())
                .collect(Collectors.joining(", "));
    }

    public Set<ProjectFile> getEditableFiles()
    {
        return selectedContext().editableFiles()
                .map(ContextFragment.ProjectPathFragment::file)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<BrokkFile> getReadonlyFiles() {
        return selectedContext().readonlyFiles()
                .map(ContextFragment.PathFragment::file)
                .collect(Collectors.toSet());
    }

    /**
     * push context changes with a function that modifies the current context
     */
    public void pushContext(Function<Context, Context> contextGenerator)
    {
        Context newContext = contextHistory.pushContext(contextGenerator);
        if (newContext != null) {
            io.updateContextHistoryTable(newContext);
            project.saveContext(newContext);

            if (newContext.getTaskHistory().isEmpty()) {
                return;
            }

            var cf = new ContextFragment.ConversationFragment(newContext.getTaskHistory());
            int tokenCount = Models.getApproximateTokens(cf.format());
            if (tokenCount > 32 * 1024) {
                // Show a dialog asking if we should compress the history
                SwingUtilities.invokeLater(() -> {
                    int choice = JOptionPane.showConfirmDialog(io.getFrame(),
                            """
                            The conversation history is getting long (%,d lines or about %,d tokens).
                            Compressing it can improve performance and reduce cost.

                            Compress history now?
                            """.formatted(cf.format().split("\n").length, tokenCount),
                            "Compress History?",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);

                    if (choice == JOptionPane.YES_OPTION) {
                        // Call the async compression method if user agrees
                        compressHistoryAsync();
                    }
                });
            }
        }
    }

    /**
     * Updates the selected context in history from the UI
     * Called by Chrome when the user selects a row in the history table
     */
    public void setSelectedContext(Context context) {
        contextHistory.setSelectedContext(context);
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
        io.toolErrorRaw("Removing unreadable fragment " + f.description());
        pushContext(c -> c.removeBadFragment(f));
    }

    private final ConcurrentMap<Callable<?>, String> taskDescriptions = new ConcurrentHashMap<>();

    public SwingWorker<String, Void> submitSummarizeTaskForPaste(String pastedContent) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
              protected String doInBackground() {
                  var msgs = SummarizerPrompts.instance.collectMessages(pastedContent, 12);
                  // Use quickModel for summarization
                  var result = getCoder(models.quickestModel(), "Summarize paste").sendMessage(msgs); // Use instance field
                   if (result.cancelled() || result.error() != null || result.chatResponse() == null) {
                      logger.warn("Summarization failed or was cancelled.");
                      return "Summarization failed.";
                 }
                 return result.chatResponse().aiMessage().text();
            }

            @Override
            protected void done() {
                io.updateContextTable();
                io.updateContextHistoryTable();
            }
        };

        worker.execute();
        return worker;
    }

    public SwingWorker<String, Void> submitSummarizeTaskForConversation(String input) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
              protected String doInBackground() {
                  var msgs =  SummarizerPrompts.instance.collectMessages(input, 5);
                   // Use quickModel for summarization
                  var result = getCoder(models.quickestModel(), input).sendMessage(msgs); // Use instance field
                   if (result.cancelled() || result.error() != null || result.chatResponse() == null) {
                       logger.warn("Summarization failed or was cancelled.");
                       return "Summarization failed.";
                 }
                 return result.chatResponse().aiMessage().text();
            }

            @Override
            protected void done() {
                io.updateContextHistoryTable();
            }
        };

        worker.execute();
        return worker;
    }

    /**
     * Asynchronously determines the best verification command based on the user goal,
     * workspace summary, and stored BuildDetails. Uses the quickest model.
     *
     /**
     * Submits a background task to the internal background executor (non-user actions).
     */
    @Override
    public <T> Future<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        assert taskDescription != null;
        assert !taskDescription.isBlank();
        Future<T> future = backgroundTasks.submit(() -> {
            try {
                io.backgroundOutput(taskDescription);
                return task.call();
            } finally {
                // Remove this task from the map
                taskDescriptions.remove(task);
                int remaining = taskDescriptions.size();
                SwingUtilities.invokeLater(() -> {
                     if (remaining <= 0) {
                         io.backgroundOutput("");
                     } else if (remaining == 1) {
                        // Find the last remaining task description. If there's a race just end the spin
                        var lastTaskDescription = taskDescriptions.values().stream().findFirst().orElse("");
                        io.backgroundOutput(lastTaskDescription);
                     } else {
                         io.backgroundOutput("Tasks running: " + remaining);
                    }
                 });
            }
        });

        // Track the future with its description
        taskDescriptions.put(task, taskDescription);
        return future;
    }

    /**
     * Ensures build details are loaded or inferred using BuildAgent if necessary.
     * Runs asynchronously in the background.
     */
  private void ensureBuildDetailsAsync() {
      BuildDetails currentDetails = project.getBuildDetails();
      if (currentDetails != null) {
          logger.debug("Loaded existing build details {}", currentDetails);
          return;
      }

      // No details found, run the BuildAgent asynchronously
      submitBackgroundTask("Inferring build details", () -> {
          BuildAgent agent = new BuildAgent(getCoder(models.systemModel(), "Infer build details"), toolRegistry);
          BuildDetails inferredDetails = null;
          try {
              inferredDetails = agent.execute(); // This runs the agent loop
            } catch (Exception e) {
                logger.error("BuildAgent execution failed", e);
                io.toolError("Build Information Agent failed: " + e.getMessage());
            }

            if (inferredDetails == null) {
                logger.warn("BuildAgent did not complete successfully (aborted or errored). Build details not saved.");
            } else {
                project.saveBuildDetails(inferredDetails);
                io.systemOutput("Build details inferred and saved.");
                logger.debug("Successfully inferred and saved build details.");
            }
            return inferredDetails; // Return details for potential chaining, though not used here
        });
    }


    @FunctionalInterface
    public interface TaskRunner {
        /**
         * Submits a background task with the given description.
         *
         * @param taskDescription a description of the task
         * @param task the task to execute
         * @param <T> the result type of the task
         * @return a {@link Future} representing pending completion of the task
         */
        <T> Future<T> submit(String taskDescription, Callable<T> task);
    }

    // Removed BuildCommand record

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
                io.systemOutput("Generating project style guide...");
                var analyzer = project.getAnalyzer();
                 // Use a reasonable limit for style guide generation context
                 var topClasses = AnalyzerUtil.combinedPagerankFor(analyzer, Map.of()).stream().limit(10).toList();

                 if (topClasses.isEmpty()) {
                     io.systemOutput("No classes found via PageRank for style guide generation.");
                     project.saveStyleGuide("# Style Guide\n\n(Could not be generated automatically - no relevant classes found)\n");
                     return null;
                 }

                var codeForLLM = new StringBuilder();
                var tokens = 0;
                int MAX_STYLE_TOKENS = 30000; // Limit context size for style guide
                for (var fqcnUnit : topClasses) {
                    var fileOption = analyzer.getFileFor(fqcnUnit.fqName()); // Use fqName() here
                    if (fileOption.isEmpty()) continue;
                    var file = fileOption.get();
                    String chunk; // Declare chunk once outside the try-catch
                    // Use project root for relative path display if possible
                    var relativePath = project.getRoot().relativize(file.absPath()).toString();
                    try {
                        chunk = "<file path=\"%s\">\n%s\n</file>\n".formatted(relativePath, file.read());
                        // Calculate tokens and check limits *inside* the try block, only if read succeeds
                        var chunkTokens = Models.getApproximateTokens(chunk);
                        if (tokens > 0 && tokens + chunkTokens > MAX_STYLE_TOKENS) { // Check if adding exceeds limit
                            logger.debug("Style guide context limit ({}) reached after {} tokens.", MAX_STYLE_TOKENS, tokens);
                            break; // Exit the loop if limit reached
                        }
                        if (chunkTokens > MAX_STYLE_TOKENS) { // Skip single large files
                            logger.debug("Skipping large file {} ({} tokens) for style guide context.", relativePath, chunkTokens);
                            continue; // Skip to next file
                        }
                        // Append chunk if within limits
                        codeForLLM.append(chunk);
                        tokens += chunkTokens;
                        logger.trace("Added {} ({} tokens, total {}) to style guide context", relativePath, chunkTokens, tokens);
                    } catch (IOException e) {
                        logger.error("Failed to read {}: {}", relativePath, e.getMessage());
                        // Skip this file on error
                        continue; // Ensure we continue to the next file even on error
                    }
                }

                if (codeForLLM.isEmpty()) {
                    io.systemOutput("No relevant code found for style guide generation");
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

                  var result = getCoder(models.quickestModel(), "Generate style guide").sendMessage(messages); // Use instance field
                   if (result.cancelled() || result.error() != null || result.chatResponse() == null) {
                       io.systemOutput("Failed to generate style guide: " + (result.error() != null ? result.error().getMessage() : "LLM unavailable or cancelled"));
                       project.saveStyleGuide("# Style Guide\n\n(Generation failed)\n");
                     return null;
                 }
                var styleGuide = result.chatResponse().aiMessage().text();
                 if (styleGuide == null || styleGuide.isBlank()) {
                     io.systemOutput("LLM returned empty style guide.");
                     project.saveStyleGuide("# Style Guide\n\n(LLM returned empty result)\n");
                     return null;
                }
                project.saveStyleGuide(styleGuide);
                io.systemOutput("Style guide generated and saved to .brokk/style.md");
            } catch (Exception e) {
                logger.error("Error generating style guide", e);
            }
            return null;
        });
    }

    /**
     * Compresses a single TaskEntry into a summary string using the quickest model.
     *
     * @param entry    The TaskEntry to compress.
     * @return A new compressed TaskEntry, or the original entry (with updated sequence) if compression fails.
     */
    public TaskEntry compressHistory(TaskEntry entry) {
        // If already compressed, return as is
        if (entry.isCompressed()) {
            return entry;
        }

        // Compress
          var historyString = entry.toString();
          var msgs = SummarizerPrompts.instance.compressHistory(historyString);
          var result = getCoder(models.quickModel(), "Compress history entry").sendMessage(msgs);

          if (result.cancelled() || result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
              logger.warn("History compression failed for entry '{}': {}", entry.description(),
                        result.error() != null ? result.error().getMessage() : "LLM unavailable or cancelled");
            return entry;
        }

        String summary = result.chatResponse().aiMessage().text();
        if (summary == null || summary.isBlank()) {
            logger.warn("History compression for entry '{}' resulted in empty summary.", entry.description());
            return entry;
        }

        logger.debug("Compressed history entry '{}' to summary: {}", entry.description(), summary);
        return TaskEntry.fromCompressed(entry.sequence(), summary);
    }

    /**
     * Adds a completed CodeAgent session result to the context history.
     * This is the primary method for adding history after a CodeAgent run.
     *
     * @param result   The result object from CodeAgent.runSession. Can be null.
     * @param compress
     */
    public void addToHistory(CodeAgent.SessionResult result, boolean compress) {
        assert result != null;
        if (result.messages().isEmpty()) {
            logger.debug("Skipping adding empty session result to history.");
            return;
        }

        var messages = result.messages();
        var originalContents = result.originalContents();
        var action = result.actionDescription(); // This already includes the stop reason if not SUCCESS
        var llmOutputText = result.finalLlmOutput();

        // Use the final LLM output text from the result to create ParsedOutput
        // Ensure llmOutputText is not null, default to empty string if necessary
        var parsed = new ParsedOutput(llmOutputText, new ContextFragment.StringFragment(llmOutputText, "ai Response"));

        logger.debug("Adding session result to history. Action: '{}', Changed files: {}, Reason: {}", action, originalContents.size(), result.stopDetails());

        // Push the new context state with the added history entry
        TaskEntry newEntry = topContext().createTaskEntry(messages);
        var finalEntry = compress ? compressHistory(newEntry) : newEntry;
        Future<String> actionFuture = submitSummarizeTaskForConversation(action);
        pushContext(ctx -> ctx.addHistoryEntry(finalEntry, parsed, actionFuture, originalContents));
    }

    public List<Context> getContextHistory() {
        return contextHistory.getHistory();
    }

    @Override
    public void addToGit(List<ProjectFile> files) throws IOException {
        project.getRepo().add(files);
    }

    // Convert a throwable to a string with full stack trace
    private String getStackTraceAsString(Throwable throwable) {
        var sw = new java.io.StringWriter();
        var pw = new java.io.PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    @Override
    public IConsoleIO getIo() {
        return io;
    }

    @Override
    public ToolRegistry getToolRegistry() {
        assert toolRegistry != null : "ToolRegistry accessed before initialization";
        return toolRegistry;
    }

    /**
     * Asynchronously compresses the entire conversation history of the currently selected context.
     * Replaces the history with summarized versions of each task entry.
     * This runs as a user action because it visibly modifies the context history.
     */
    public Future<?> compressHistoryAsync() {
        return submitUserTask("Compressing History", () -> {
            io.disableHistoryPanel(); // Disable history navigation during compression
            try {
                var currentContext = selectedContext(); // Operate on the selected context
                var history = currentContext.getTaskHistory();

                io.systemOutput("Compressing conversation history...");
                List<TaskEntry> compressedHistory = new ArrayList<>(history.size());
                boolean changed = false;
                for (TaskEntry entry : history) {
                    // Pass the sequence number (i + 1) for the new entry in the compressed list
                    TaskEntry compressedEntry = compressHistory(entry);
                    compressedHistory.add(compressedEntry);
                    if (!entry.equals(compressedEntry)) { // Check if the entry actually changed (e.g., wasn't already compressed)
                        changed = true;
                    }
                }

                // Only push if changes were made
                if (!changed) {
                     io.systemOutput("Unable to compress history");
                     return;
                 }

                // Create a new context with the compressed history
                pushContext(ctx -> ctx.withCompressedHistory(List.copyOf(compressedHistory)));
                io.systemOutput("Conversation history compressed successfully.");
            } finally {
                 // Re-enable history navigation *after* pushContext updates the UI
                 SwingUtilities.invokeLater(io::enableHistoryPanel);
                 // TODO clean up enable/disable of UI elements in usertasks
                 // enableUserActionButtons is handled by submitUserTask
            }
        });
    }
}
