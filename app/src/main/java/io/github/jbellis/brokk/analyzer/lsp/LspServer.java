package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.BuildInfo;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.ExecutorServiceUtil;
import io.github.jbellis.brokk.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LspServer {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Nullable
    private Process serverProcess;

    @Nullable
    private LanguageServer languageServer;

    @Nullable
    private CompletableFuture<Void> serverInitialized;

    @Nullable
    private ExecutorService lspExecutor;

    @Nullable
    private Thread shutdownHook;

    /**
     * Channel to the lock file that coordinates access to the shared LSP cache across JVMs. The channel is kept open
     * for the entire lifetime of the language-server process so the corresponding {@link #cacheLock} remains valid
     * until {@link #shutdownServer()}.
     */
    @Nullable
    private FileChannel lockChannel;
    /**
     * The exclusive lock held on the cache lock file. It is acquired in {@link #startServer(Path, String, Path, Map)}
     * and released in {@link #shutdownServer()}. While this lock is held, no other JVM can start another
     * language-server instance against the same cache directory.
     */
    @Nullable
    private FileLock cacheLock;

    /**
     * If this JVM could not obtain the primary cache lock, it starts the language-server using a temporary cache
     * directory. The path to that directory is stored here so it can be cleaned up during shutdown.
     */
    @Nullable
    private Path temporaryCachePath;

    /** The type of server this is, e.g, JDT */
    private final SupportedLspServer serverType;
    /** Language identifier used when the server was started (e.g. "java"). */
    @Nullable
    private String languageId;

    private final AtomicInteger clientCounter = new AtomicInteger(0);

    @NotNull
    private CountDownLatch serverReadyLatch = new CountDownLatch(1);

    @Nullable
    private LspLanguageClient languageClient;

    protected final Set<Path> activeWorkspaces = ConcurrentHashMap.newKeySet();
    private final Map<Path, Set<String>> workspaceExclusions = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> workspaceReadyLatches = new ConcurrentHashMap<>();

    @NotNull
    public Optional<CountDownLatch> getWorkspaceReadyLatch(String workspace) {
        return Optional.ofNullable(this.workspaceReadyLatches.get(workspace));
    }

    protected LspServer(SupportedLspServer serverType) {
        this.serverType = serverType;
    }

    /**
     * Executes a callback function asynchronously with the LanguageServer instance once the server is initialized. This
     * method is non-blocking. This is the intended way of accessing the language server for server-related tasks.
     *
     * @param callback The function to execute, which accepts the @NotNull LanguageServer instance.
     */
    protected void whenInitialized(@NotNull Consumer<LanguageServer> callback) {
        if (serverInitialized == null) {
            logger.warn("Server is not running or initializing; cannot execute callback.");
        } else {
            serverInitialized
                    .thenRunAsync(() -> {
                        // this.languageServer should be non-null here
                        assert this.languageServer != null;
                        try {
                            // Indexing generally completes within a couple of seconds, but larger projects need grace
                            if (!serverReadyLatch.await(1, TimeUnit.MINUTES)) {
                                logger.warn("Server is taking longer than expected to complete startup, continuing");
                            }
                        } catch (InterruptedException e) {
                            logger.debug(
                                    "Interrupted while waiting for initialization, the server may not be properly indexed",
                                    e);
                        }
                        callback.accept(this.languageServer);
                    })
                    .exceptionally(ex -> {
                        logger.error("Failed to execute callback after server initialization", ex);
                        return null; // Complete the exceptionally stage
                    })
                    .join();
        }
    }

    /**
     * Asynchronously executes a query against the language server once it's initialized. This is the intended way of
     * accessing the language server for operations that return a value.
     *
     * @param callback The function to execute, which accepts the @NotNull LanguageServer instance and returns a value.
     * @param <T> The type of the value returned by the callback.
     * @return A CompletableFuture that will be completed with the result of the callback.
     */
    public <T> CompletableFuture<T> query(@NotNull Function<LanguageServer, T> callback) {
        if (serverInitialized == null) {
            logger.warn("Server is not running or initializing; cannot execute query.");
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Server is not running or has been shut down."));
        }

        // Chain the callback to run after the serverInitialized future completes.
        // If serverInitialized fails, the exception will automatically propagate to the returned future.
        return serverInitialized.thenApplyAsync(ignoredVoid -> {
            assert this.languageServer != null;
            return callback.apply(this.languageServer);
        });
    }

    public void sendDidOpen(Path filePath, String language) {
        try {
            final var content = Files.readString(filePath);
            final var uri = filePath.toUri().toString();

            final var params = new DidOpenTextDocumentParams();
            final var item = new TextDocumentItem(uri, language, 1, content);
            params.setTextDocument(item);

            // Send a notification to the server. This is non-blocking.
            whenInitialized(server -> server.getTextDocumentService().didOpen(params));
            logger.info("Sent didOpen notification for {}", filePath);
        } catch (IOException ex) {
            logger.error("Error while trying to open text document at '{}'", filePath, ex);
        }
    }

    public void sendDidClose(Path filePath) {
        final var uri = filePath.toUri().toString();

        final var params = new DidCloseTextDocumentParams();
        final var item = new TextDocumentIdentifier(uri);
        params.setTextDocument(item);

        // Send a notification to the server. This is non-blocking.
        whenInitialized(server -> server.getTextDocumentService().didClose(params));
        logger.info("Sent didClose notification for {}", filePath);
    }

    /**
     * @return a process builder configured to launch the implementing LSP server.
     * @throws IOException if any work related to building the process occurs.
     */
    protected abstract ProcessBuilder createProcessBuilder(Path cache) throws IOException;

    /**
     * @param language the target programming language.
     * @return a language client to monitor and handle server communication.
     */
    protected abstract LspLanguageClient getLanguageClient(
            String language, CountDownLatch serverReadyLatch, Map<String, CountDownLatch> workspaceReadyLatchMap);

    public @Nullable LspLanguageClient getLanguageClient() {
        return this.languageClient;
    }

    protected void startServer(
            Path initialWorkspace, String language, Path cache, Map<String, Object> initializationOptions)
            throws IOException {
        logger.info("First client connected. Starting {} Language Server...", serverType.name());
        this.languageId = language;

        // Use this reference for whichever cache directory we eventually decide to use
        Path cacheDir = cache;

        // Acquire an exclusive lock on the shared cache so only one JVM
        // starts/uses the language server at a time.
        try {
            if (Files.notExists(cache)) {
                Files.createDirectories(cache);
            }
            final Path lockFile = cache.resolve(".cache.lock");
            this.lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            // Try to obtain the lock without blocking.  If it is already held by
            // another JVM we will fall back to a temporary cache.
            this.cacheLock = this.lockChannel.tryLock();
            if (this.cacheLock == null) {
                logger.info(
                        "Primary LSP cache at {} is locked by another process. Falling back to a temporary cache.",
                        cache.toAbsolutePath());
                // Close the channel associated with the primary lock so we do not
                // keep an unverifiable handle open.
                try {
                    this.lockChannel.close();
                } catch (IOException closeEx) {
                    logger.warn("Failed to close primary cache lock channel", closeEx);
                }
                this.lockChannel = null;

                // Create an isolated temporary cache directory
                this.temporaryCachePath = Files.createTempDirectory("brokk-lsp-cache-");
                cacheDir = this.temporaryCachePath;
            } else {
                // Successfully obtained exclusive lock on the primary cache
                logger.debug("Obtained cache lock on {}", lockFile.toAbsolutePath());
                this.temporaryCachePath = null;
            }
        } catch (IOException e) {
            logger.error("Unable to obtain cache lock for {}", cache, e);
            throw e;
        }

        final ProcessBuilder pb = createProcessBuilder(cacheDir);
        // In case the JVM doesn't shut down gracefully. If graceful shutdown is successful, this is removed.
        // See LspServer::shutdown
        this.shutdownHook = new Thread(() -> {
            logger.warn("LSP process could not close gracefully; destroying the LSP process forcibly.");
            if (this.serverProcess != null && this.serverProcess.isAlive()) {
                this.serverProcess.destroyForcibly();
            }
        });
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);

        final Path errorLog = cacheDir.resolve("error.log");
        pb.redirectError(errorLog.toFile());
        this.serverProcess = pb.start();

        // Create a dedicated thread pool for the LSP client
        this.lspExecutor = ExecutorServiceUtil.newFixedThreadExecutor(4, "lsp-client-thread-");

        // will be reduced by one when server signals readiness
        this.serverReadyLatch = new CountDownLatch(1);
        this.languageClient = getLanguageClient(language, this.serverReadyLatch, this.workspaceReadyLatches);
        final Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                languageClient,
                serverProcess.getInputStream(),
                serverProcess.getOutputStream(),
                this.lspExecutor,
                wrapper -> wrapper);
        this.languageServer = launcher.getRemoteProxy();
        launcher.startListening();

        final var params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(
                initialWorkspace.toUri().toString(),
                initialWorkspace.getFileName().toString())));
        params.setClientInfo(new ClientInfo("Brokk", BuildInfo.version));
        params.setInitializationOptions(initializationOptions);

        final var capabilities = getCapabilities();
        logger.trace("Setting {} capabilities {}", serverType.name(), capabilities);
        params.setCapabilities(capabilities);

        if (!this.serverProcess.isAlive()) {
            throw new IOException("LSP process failed shortly after starting with exit code "
                    + this.serverProcess.exitValue() + ". See " + errorLog.toAbsolutePath() + " for more details.");
        }

        this.serverInitialized = languageServer.initialize(params).thenApply(result -> {
            logger.debug("LSP server initialized with info {}", result.getServerInfo());
            if (this.languageServer != null) {
                languageServer.initialized(new InitializedParams());
                logger.info("LSP client Initialized ");
            } else {
                throw new IllegalStateException("LSP could not be initialized.");
            }
            return null; // complete the future
        });

        try {
            serverInitialized.join();
            addWorkspaceFolder(initialWorkspace);
            logger.debug("Server initialization confirmed with initial workspace: {}", initialWorkspace);
        } catch (CompletionException e) {
            logger.error("Server initialization failed", e);
            throw e; // Re-throw the exception
        }
    }

    protected ClientCapabilities getCapabilities() {
        // 1. Create a fully-featured ClientCapabilities object.
        ClientCapabilities capabilities = new ClientCapabilities();
        WorkspaceClientCapabilities workspaceCapabilities = new WorkspaceClientCapabilities();

        // 2. Explicitly declare support for workspace symbols.
        SymbolCapabilities symbolCapabilities = new SymbolCapabilities();

        // 3. Declare that we support all kinds of symbols (Class, Method, Field, etc.).
        SymbolKindCapabilities symbolKindCapabilities = new SymbolKindCapabilities();
        symbolKindCapabilities.setValueSet(Arrays.stream(SymbolKind.values()).collect(Collectors.toList()));
        symbolCapabilities.setSymbolKind(symbolKindCapabilities);
        workspaceCapabilities.setSymbol(symbolCapabilities);
        workspaceCapabilities.setWorkspaceFolders(true);

        capabilities.setWorkspace(workspaceCapabilities);

        // Explicitly declare support for text document synchronization
        TextDocumentClientCapabilities textDocumentCapabilities = new TextDocumentClientCapabilities();
        final var syncCapabilities = new SynchronizationCapabilities();
        syncCapabilities.setDidSave(true);
        syncCapabilities.setWillSave(true);
        syncCapabilities.setWillSaveWaitUntil(true);
        textDocumentCapabilities.setSynchronization(syncCapabilities);

        textDocumentCapabilities.setDefinition(new DefinitionCapabilities());
        textDocumentCapabilities.setReferences(new ReferencesCapabilities());

        // Explicitly add support for call hierarchy and type hierarchy
        textDocumentCapabilities.setCallHierarchy(new CallHierarchyCapabilities(true));
        textDocumentCapabilities.setTypeHierarchy(new TypeHierarchyCapabilities(true));

        // Add support for textDocument/documentSymbol
        DocumentSymbolCapabilities documentSymbolCapabilities = new DocumentSymbolCapabilities();
        documentSymbolCapabilities.setHierarchicalDocumentSymbolSupport(true); // Request a tree structure
        textDocumentCapabilities.setDocumentSymbol(documentSymbolCapabilities);

        // Set the text document capabilities on the main capabilities object
        capabilities.setTextDocument(textDocumentCapabilities);

        return capabilities;
    }

    /**
     * Force-clears the on-disk cache when it has become corrupted. The server is first shut down (releasing any file
     * lock) and the cache directory is then deleted so the next startup will rebuild it from scratch.
     */
    public synchronized void clearCache() {
        logger.warn("Clearing LSP cache due to detected corruption.");
        // Stop the running server and free the file-lock
        shutdownServer();
        // Resolve the cache path (fall back to serverType if languageId is null)
        String lang = languageId != null ? languageId : serverType.name().toLowerCase(Locale.ROOT);
        Path cacheDir = getCacheForLsp(lang);
        FileUtil.deleteRecursively(cacheDir);
        logger.warn("Deleted cache at {}", cacheDir);
    }

    protected void shutdownServer() {
        logger.info("Last client disconnected. Shutting down JDT Language Server...");
        try {
            if (languageServer != null) {
                languageServer.shutdown().get(10, TimeUnit.SECONDS);
                languageServer.exit();
            }
            logger.info("LSP client shut down successfully.");
        } catch (TimeoutException e) {
            logger.info("Timed out while waiting for client to shut down gracefully.");
            languageServer.exit();
        } catch (InterruptedException e) {
            logger.info("Interrupted while waiting for client to shut down gracefully.");
            languageServer.exit();
        } catch (ExecutionException e) {
            logger.error("Error shutting down LSP client", e);
            languageServer.exit();
        } finally {
            this.languageServer = null;
            this.serverInitialized = null;
        }
        try {
            if (lspExecutor != null && !lspExecutor.isShutdown()) {
                if (!lspExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("LSP Executor service tasks did not terminate in 5 seconds. Continuing to shutdown.");
                }
                lspExecutor.shutdown();
            }
            logger.info("LSP Executor service shut down successfully.");
        } catch (Exception e) {
            logger.error("Error shutting down LSP client", e);
        } finally {
            this.languageServer = null;
            this.serverInitialized = null;
        }
        try {
            if (serverProcess != null && serverProcess.isAlive()) {
                serverProcess.destroy();
                if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
                    logger.warn("LSP process did not terminate in 5 seconds. Destroying forcibly.");
                    serverProcess.destroyForcibly();
                }
            }
            if (this.shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
                this.shutdownHook = null;
            }
            logger.info("LSP process shut down successfully.");
        } catch (Exception e) {
            logger.error("Error shutting down LSP process", e);
        } finally {
            this.serverProcess = null;
        }

        // Release the cross-process cache lock, if held.
        if (this.cacheLock != null) {
            try {
                this.cacheLock.release();
                logger.debug("Released cache lock.");
            } catch (IOException e) {
                logger.warn("Failed to release cache lock cleanly", e);
            }
        }
        if (this.lockChannel != null) {
            try {
                this.lockChannel.close();
            } catch (IOException e) {
                logger.warn("Failed to close lock channel cleanly", e);
            }
        }
        this.cacheLock = null;
        this.lockChannel = null;

        // Clean up any temporary cache directory we created for this JVM.
        // deleteDirectoryRecursively handles its own IOExceptions internally.
        if (this.temporaryCachePath != null) {
            FileUtil.deleteRecursively(this.temporaryCachePath);
            logger.debug("Deleted temporary LSP cache at {}", this.temporaryCachePath);
            this.temporaryCachePath = null;
        }
    }

    /**
     * Returns the path for the cache where the LSP for a given language would be placed.
     *
     * @param language the target language of the LSP, e.g., java.
     * @return the Path where the cache and logs would live.
     */
    public static Path getCacheForLsp(String language) {
        final var cacheName = language + "-lsp"; // assuming we use one LSP per language
        return Path.of(System.getProperty("user.home"), AbstractProject.BROKK_DIR, AbstractProject.CACHE_DIR, cacheName)
                .toAbsolutePath();
    }

    /**
     * Registers a new client (e.g., JdtAnalyzer instance). Starts the server if this is the first client.
     *
     * @param projectPath The workspace path for the new client.
     * @param excludePatterns A set of glob patterns to exclude for this workspace.
     */
    public synchronized void registerClient(
            Path projectPath, Set<String> excludePatterns, Map<String, Object> initializationOptions, String language)
            throws IOException {
        final var projectPathAbsolute = projectPath.toAbsolutePath().normalize();
        logger.debug(
                "Attempting to registered workspace: {}. Active clients: {}", projectPathAbsolute, clientCounter.get());
        final Path cache = getCacheForLsp(language);
        if (cache.getParent() != null && !Files.isDirectory(cache.getParent())) {
            Files.createDirectories(cache.getParent());
        }

        workspaceExclusions.put(projectPathAbsolute, excludePatterns);
        if (clientCounter.getAndIncrement() == 0) {
            try {
                startServer(projectPathAbsolute, language, cache, initializationOptions);
            } catch (IOException e) {
                logger.error("Error starting server, cache may be corrupt, retrying with fresh cache.", e);
                FileUtil.deleteRecursively(cache); // start on a fresh cache
                startServer(projectPathAbsolute, language, cache, initializationOptions);
            }
        } else {
            addWorkspaceFolder(projectPathAbsolute);
        }
        activeWorkspaces.add(projectPathAbsolute);
        updateServerConfiguration(initializationOptions, language); // Send combined configuration
        logger.debug("Registered workspace: {}. Active clients: {}", projectPathAbsolute, clientCounter.get());
    }

    /**
     * Unregisters a client. Shuts down the server if this is the last client.
     *
     * @param projectPath The workspace path of the client being closed.
     */
    public synchronized void unregisterClient(
            Path projectPath, Map<String, Object> initializationOptions, String language) {
        final var projectPathAbsolute = projectPath.toAbsolutePath().normalize();
        logger.debug(
                "Attempting to unregister workspace: {}. Active clients: {}", projectPathAbsolute, clientCounter.get());
        try {
            removeWorkspaceFolder(projectPathAbsolute);
            logger.debug("Unregistered workspace: {}. Active clients: {}", projectPathAbsolute, clientCounter.get());
        } finally {
            activeWorkspaces.remove(projectPathAbsolute);
            workspaceExclusions.remove(projectPathAbsolute);
            if (clientCounter.decrementAndGet() == 0) {
                shutdownServer();
            } else {
                updateServerConfiguration(initializationOptions, language); // Update config after removal
            }
        }
    }

    private void addWorkspaceFolder(Path folderPath) {
        if (activeWorkspaces.contains(folderPath)) return;
        workspaceReadyLatches.put(folderPath.toUri().toString(), new CountDownLatch(1));
        whenInitialized((server) -> {
            WorkspaceFolder newFolder = new WorkspaceFolder(
                    folderPath.toUri().toString(), folderPath.getFileName().toString());
            WorkspaceFoldersChangeEvent event = new WorkspaceFoldersChangeEvent(List.of(newFolder), List.of());
            server.getWorkspaceService().didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(event));
            logger.debug("Added workspace folder: {}", folderPath);
        });
    }

    private void removeWorkspaceFolder(Path folderPath) {
        whenInitialized((server) -> {
            WorkspaceFolder folderToRemove = new WorkspaceFolder(
                    folderPath.toUri().toString(), folderPath.getFileName().toString());
            WorkspaceFoldersChangeEvent event = new WorkspaceFoldersChangeEvent(List.of(), List.of(folderToRemove));
            server.getWorkspaceService().didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(event));
            logger.debug("Removed workspace folder: {}", folderPath);
        });
    }

    /** Builds a combined configuration from all active workspaces and sends it to the server. */
    @SuppressWarnings("unchecked")
    private void updateServerConfiguration(Map<String, Object> initializationOptions, String language) {
        whenInitialized((server) -> {
            // Combine all exclusion patterns from all workspaces into one map
            final var combinedExclusions = new HashMap<String, Boolean>();
            for (final Set<String> patterns : workspaceExclusions.values()) {
                for (final var pattern : patterns) {
                    if (pattern.startsWith(File.separator)) {
                        // Relativize the paths if necessary
                        combinedExclusions.put("." + pattern, true);
                    } else {
                        combinedExclusions.put(pattern, true);
                    }
                }
            }

            final var options = new HashMap<>(initializationOptions);
            final var settings = (Map<String, Object>) options.getOrDefault(language, new HashMap<String, Object>());
            settings.put("files", Map.of("exclude", combinedExclusions));

            // Send the didChangeConfiguration notification
            final var params = new DidChangeConfigurationParams(options);
            server.getWorkspaceService().didChangeConfiguration(params);
            logger.debug("Updated server configuration with combined exclusions: {}", combinedExclusions.keySet());
        });
    }

    /**
     * Needed to cause a "refresh" on the server for the given workspace.
     *
     * @param workspacePath the workspace to refresh.
     * @return a completable future tied to the refresh.
     * @see <a
     *     href="https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/commands/DiagnosticsCommand.java#L47">DiagnosticsCommand</a>
     */
    public CompletableFuture<Object> refreshWorkspace(String workspacePath) {
        logger.debug("Refreshing workspace");
        return query((server) -> {
            ExecuteCommandParams params = new ExecuteCommandParams(
                    "java.project.refreshDiagnostics", Arrays.asList(workspacePath, null, false));
            return server.getWorkspaceService().executeCommand(params);
        });
    }

    /**
     * Notifies the server of specific file changes.
     *
     * @param changedFiles A set of files that have been created, modified, or deleted.
     */
    public void update(@NotNull Set<ProjectFile> changedFiles) {
        // Create a list of FileEvent objects from the set of changed files.
        final List<FileEvent> events = changedFiles.stream()
                .map(projectFile -> {
                    String uri = projectFile.absPath().toUri().toString();
                    // Infer the change type based on whether the file still exists.
                    FileChangeType type = Files.exists(projectFile.absPath())
                            ? FileChangeType.Changed // Covers both creation and modification
                            : FileChangeType.Deleted;
                    return new FileEvent(uri, type);
                })
                .collect(Collectors.toList());

        final var params = new DidChangeWatchedFilesParams(events);
        whenInitialized(server -> server.getWorkspaceService().didChangeWatchedFiles(params));
    }

    /**
     * Update the active workspace so the LSP language-server builds the project with the supplied JDK. The change is
     * applied asynchronously once the server is ready.
     *
     * @param jdkPath absolute path to the desired JDK directory
     */
    public CompletableFuture<Object> updateWorkspaceJdk(@NotNull Path workspace, @NotNull Path jdkPath) {
        if (!Files.isDirectory(jdkPath)) {
            logger.error("Provided JDK path is not a valid directory: {}", jdkPath);
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid JDK path."));
        }

        return query(server -> {
            ExecuteCommandParams params = new ExecuteCommandParams(
                    "java.project.updateJdk",
                    // Arguments: [projectUri, jdkPath]
                    List.of(workspace.toUri().toString(), jdkPath.toString()));
            return server.getWorkspaceService().executeCommand(params);
        });
    }
}
