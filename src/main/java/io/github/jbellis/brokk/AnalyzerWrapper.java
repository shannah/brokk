package io.github.jbellis.brokk;

import io.github.jbellis.brokk.Project.CpgRefresh;
import java.awt.KeyboardFocusManager;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class AnalyzerWrapper {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    private static final long DEBOUNCE_DELAY_MS = 500;
    private static final long POLL_TIMEOUT_FOCUSED_MS = 100;
    private static final long POLL_TIMEOUT_UNFOCUSED_MS = 1000;

    private final AnalyzerListener listener; // can be null if no one is listening
    private final Path root;
    private final TaskRunner runner;
    private final BlockingQueue<DirectoryChangeEvent> eventQueue = new LinkedBlockingQueue<>();
    private final Project project;

    private volatile boolean running = true;

    private volatile Future<Analyzer> future;
    private volatile boolean rebuildInProgress = false;
    private volatile boolean externalRebuildRequested = false;
    private volatile boolean rebuildPending = false;

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

    /**
     * Helper method to schedule a task that waits for the future to complete
     * and then notifies the listener.
     */
    private void notifyOnCompletion(Future<Analyzer> future) {
        runner.submit("Updating with new Code Intelligence", () -> {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("Error waiting for analyzer build", e);
            }
            return null;
        });
    }

    /**
     * Create a new orchestrator. (We assume the analyzer executor is provided externally.)
     */
    public AnalyzerWrapper(Project project, TaskRunner runner, AnalyzerListener listener) {
        this.project = project;
        this.root = project.getRoot();
        this.runner = runner;
        this.listener = listener;

        // build the initial Analyzer
        future = runner.submit("Initializing code intelligence", this::loadOrCreateAnalyzer);
        notifyOnCompletion(future);
    }

    private void beginWatching(Path root) {
        try {
            future.get(); // Wait for the initial analyzer to be built
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        logger.debug("Setting up directoryWatcher");
        DirectoryWatcher directoryWatcher;
        try {
            directoryWatcher = DirectoryWatcher.builder()
                    .path(root)
                    .listener(event -> {
                        // Check for overflow
                        if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
                            return;
                        }

                        // Filter out changes in .brokk or the log file (also in .brokk but doesn't show that way in the events)
                        Path changedAbs = event.path();
                        String changedAbsStr = changedAbs.toString();
                        if (changedAbsStr.contains("${sys:logfile.path}")
                                || changedAbs.startsWith(root.resolve(".brokk"))) {
                            return;
                        }

                        logger.trace("Directory event: {} on {}", event.eventType(), changedAbs);
                        boolean offered = eventQueue.offer(event);
                        assert offered;
                    })
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // start watching!
        directoryWatcher.watchAsync();
        eventLoop();
    }

    /**
     * The single-threaded loop that drains the eventQueue, applies debouncing,
     * and triggers rebuild when needed.
     */
    private void eventLoop() {
        while (running) {
            try {
                // Wait for the first event (with a timeout to let the loop exit if idle)
                // Use longer poll time if application is not in focus
                long pollTimeout = isApplicationFocused() ? POLL_TIMEOUT_FOCUSED_MS : POLL_TIMEOUT_UNFOCUSED_MS;
                DirectoryChangeEvent firstEvent = eventQueue.poll(pollTimeout, TimeUnit.MILLISECONDS);
                if (firstEvent == null) {
                    if (externalRebuildRequested && !rebuildInProgress) {
                        logger.debug("External rebuild requested");
                        rebuild();
                    }
                    continue; // Nothing arrived within 1s; loop again
                }

                // Once we get an event, gather more events arriving during the debounce window.
                Set<DirectoryChangeEvent> batch = new HashSet<>();
                batch.add(firstEvent);
                long deadline = System.currentTimeMillis() + DEBOUNCE_DELAY_MS;

                while (true) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    DirectoryChangeEvent next = eventQueue.poll(remaining, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }

                // We have a batch of events that arrived within DEBOUNCE_DELAY_MS
                handleBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Orchestrator thread interrupted; shutting down");
                running = false;
            }
        }
    }

    /**
     * Check if changes in this batch of events require a .git refresh and/or analyzer rebuild.
     */
    private void handleBatch(Set<DirectoryChangeEvent> batch) {
        // 1) Possibly refresh Git
        boolean needsGitRefresh = batch.stream().anyMatch(event -> {
            Path gitDir = root.resolve(".git");
            return event.path().startsWith(gitDir)
                    && (event.eventType() == DirectoryChangeEvent.EventType.CREATE
                    || event.eventType() == DirectoryChangeEvent.EventType.DELETE
                    || event.eventType() == DirectoryChangeEvent.EventType.MODIFY);
        });
        if (needsGitRefresh) {
            logger.debug("Refreshing git due to changes in .git directory");
            project.getRepo().refresh();
        }

        // 2) Check if any *tracked* files changed
        Set<Path> trackedPaths = project.getRepo().getTrackedFiles().stream()
                .map(RepoFile::absPath)
                .collect(Collectors.toSet());

        boolean needsAnalyzerRefresh = batch.stream()
                .anyMatch(event -> trackedPaths.contains(event.path()));

        if (needsAnalyzerRefresh) {
            logger.debug("Rebuilding analyzer due to changes in tracked files: {}",
                         batch.stream()
                                 .filter(e -> trackedPaths.contains(e.path()))
                                 .map(e -> e.path().toString())
                                 .collect(Collectors.joining(", "))
            );
            rebuild();
        } else {
            logger.debug("No tracked files changed; skipping analyzer rebuild");
        }
    }

    /**
     * Synchronously load or create an Analyzer:
     *   1) If the .brokk/joern.cpg file is up to date, reuse it;
     *   2) Otherwise, rebuild a fresh Analyzer.
     */
    private Analyzer loadOrCreateAnalyzer() {
        logger.debug("Loading/creating analyzer");
        Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
        if (project.getCpgRefresh() == CpgRefresh.UNSET) {
            logger.debug("First startup: timing CPG creation");
            long start = System.currentTimeMillis();
            var analyzer = createAndSaveAnalyzer();
            long duration = System.currentTimeMillis() - start;
            if (duration > 5000) {
                project.setCpgRefresh(CpgRefresh.MANUAL);
                var msg = """
                CPG creation was slow (%,d ms); code intelligence will only refresh when explicitly requested via File menu.
                (Code intelligence will still refresh once automatically at startup.)
                You can change this with the cpg_refresh parameter in .brokk/project.properties.
                """.stripIndent().formatted(duration);
                listener.onFirstBuild(msg);
                logger.info(msg);
            } else {
                project.setCpgRefresh(CpgRefresh.AUTO);
                var msg = """
                CPG creation was fast (%,d ms); code intelligence will refresh automatically when changes are made to tracked files.
                You can change this with the cpg_refresh parameter in .brokk/project.properties.
                """.stripIndent().formatted(duration);
                listener.onFirstBuild(msg);
                logger.info(msg);
                startWatcher();
            }
            return analyzer;
        }

        var analyzer = loadCachedAnalyzer(analyzerPath);
        if (analyzer == null) {
            analyzer = createAndSaveAnalyzer();
        }
        if (project.getCpgRefresh() == CpgRefresh.AUTO) {
            startWatcher();
        }
        return analyzer;
    }

    private Analyzer createAndSaveAnalyzer() {
        Analyzer newAnalyzer = new Analyzer(root);
        Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
        newAnalyzer.writeCpg(analyzerPath);
        logger.debug("Analyzer (re)build completed");
        return newAnalyzer;
    }

    /** Load a cached analyzer if it is up to date; otherwise return null. */
    private Analyzer loadCachedAnalyzer(Path analyzerPath) {
        if (!Files.exists(analyzerPath)) {
            return null;
        }

        List<RepoFile> trackedFiles = project.getRepo().getTrackedFiles();
        long cpgMTime;
        try {
            cpgMTime = Files.getLastModifiedTime(analyzerPath).toMillis();
        } catch (IOException e) {
            throw new RuntimeException("Error reading analyzer file timestamp", e);
        }
        long maxTrackedMTime = 0L;
        for (RepoFile rf : trackedFiles) {
            try {
                long fileMTime = Files.getLastModifiedTime(rf.absPath()).toMillis();
                maxTrackedMTime = Math.max(maxTrackedMTime, fileMTime);
            } catch (IOException e) {
                // probable cause: file exists in git but is removed
                logger.debug("Error reading analyzer file timestamp", e);
            }
        }
        if (cpgMTime > maxTrackedMTime) {
            logger.debug("Using cached code intelligence data ({} > {})", cpgMTime, maxTrackedMTime);
            try {
                return new Analyzer(root, analyzerPath);
            } catch (Throwable th) {
                logger.info("Error loading analyzer", th);
                // fall through to return null
            }
        }
        return null;
    }

    /**
     * Force a fresh rebuild of the analyzer by scheduling a job on the analyzerExecutor.
     * Avoids concurrent rebuilds by setting a flag, but if a change is detected during
     * the rebuild, a new rebuild will be scheduled immediately afterwards.
     */
    private synchronized void rebuild() {
        // If a rebuild is already running, just mark that another rebuild is pending.
        if (rebuildInProgress) {
            rebuildPending = true;
            return;
        }

        rebuildInProgress = true;
        logger.debug("Rebuilding analyzer");
        future = runner.submit("Rebuilding code intelligence", () -> {
            try {
                return createAndSaveAnalyzer();
            } finally {
                synchronized (AnalyzerWrapper.this) {
                    rebuildInProgress = false;
                    // If another rebuild got requested while we were busy, immediately start a new one.
                    if (rebuildPending) {
                        rebuildPending = false;
                        logger.debug("rebuilding immediately");
                        rebuild();
                    } else {
                        externalRebuildRequested = false;
                    }
                }
            }
        });
        notifyOnCompletion(future);
    }

    /**
     * Get the analyzer, showing a spinner UI while waiting if requested.
     */
    private Analyzer get(boolean notifyWhenBlocked) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new UnsupportedOperationException("Never call blocking get() from EDT");
        }

        if (!future.isDone() && notifyWhenBlocked) {
            if (logger.isDebugEnabled()) {
                Exception e = new Exception("Stack trace");
                logger.debug("Blocking on analyzer creation", e);
            }
            listener.onBlocked();
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching analyzer", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create analyzer", e);
        }
    }

    /**
     * Get the analyzer, showing a spinner UI while waiting.
     * For use in user-facing operations.
     */
    public Analyzer get() {
        return get(true);
    }

    /**
     * @return null if analyzer is not ready yet
     */
    public Analyzer getNonBlocking() {
        try {
            // Try to get with zero timeout - returns null if not done
            return future.get(0, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Not done yet
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while checking analyzer", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create analyzer", e);
        }
    }

    public void requestRebuild() {
        externalRebuildRequested = true;
    }

    /**
     * Checks if any window in the application currently has focus
     * @return true if any application window has focus, false otherwise
     */
    private boolean isApplicationFocused() {
        var focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        return focusedWindow != null;
    }

    private void startWatcher() {
        Thread watcherThread = new Thread(() -> beginWatching(root), "DirectoryWatcher");
        watcherThread.start();
    }

    public record CodeWithSource(String code, Set<CodeUnit> sources) {
    }
}
