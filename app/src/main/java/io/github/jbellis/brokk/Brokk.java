package io.github.jbellis.brokk;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.exception.OomShutdownHandler;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitRepoFactory;
import io.github.jbellis.brokk.gui.CheckThreadViolationRepaintManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.MenuBar;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.dialogs.AboutDialog;
import io.github.jbellis.brokk.gui.dialogs.BrokkKeyDialog;
import io.github.jbellis.brokk.gui.dialogs.OpenProjectDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.Border;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jetbrains.annotations.Nullable;

public class Brokk {
    private static final Logger logger = LogManager.getLogger(Brokk.class);

    private static final String BANNER =
            """

            ██████╗ ██████╗  ██████╗ ██╗  ██╗██╗  ██╗
            ██╔══██╗██╔══██╗██╔═══██╗██║ ██╔╝██║ ██╔╝
            ██████╔╝██████╔╝██║   ██║█████╔╝ █████╔╝
            ██╔══██╗██╔══██╗██║   ██║██╔═██╗ ██╔═██╗
            ██████╔╝██║  ██║╚██████╔╝██║  ██╗██║  ██╗
            ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝
            """;

    private static void logBanner() {
        var lines = Splitter.on('\n').split(BANNER);
        for (String line : lines) {
            logger.info(line);
        }
        logger.info("Version: {}", BuildInfo.version);
        logger.info("Starting Brokk semantic code assistant...");
        logger.info("");
    }

    @Nullable
    private static JWindow splashScreen = null;

    private static final ConcurrentHashMap<Path, Chrome> openProjectWindows = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<IProject, List<Chrome>> mainToWorktreeChromes = new ConcurrentHashMap<>();
    private static final Set<Path> reOpeningProjects = ConcurrentHashMap.newKeySet();

    public static final String ICON_RESOURCE = "/brokk-icon.png";
    private static final SystemScaleProvider systemScaleProvider = new SystemScaleProviderImpl();

    // Helper record for argument parsing result
    private record ParsedArgs(boolean noProjectFlag, boolean noKeyFlag, @Nullable String projectPathArg) {}

    // Helper record for key validation result
    private record KeyValidationResult(boolean isValid, @Nullable Path dialogProjectPath) {}

    static {
        // Register Bouncy Castle provider for JGit SSH operations if not already present.
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
            logger.info("Bouncy Castle security provider registered.");
        } else {
            logger.info("Bouncy Castle security provider was already registered.");
        }
    }

    private static void setupSystemPropertiesAndIcon() {
        if (!Environment.isMacOs()) {
            var existing = System.getProperty("sun.java2d.uiScale");
            if (existing != null) {
                logger.info("sun.java2d.uiScale already set to {}. Respecting user override.", existing);
            } else {
                // Apply saved UI preference first (takes precedence over detection)
                String uiPref = MainProject.getUiScalePref();
                boolean appliedPref = false;
                if (!"auto".equalsIgnoreCase(uiPref)) {
                    try {
                        double custom = Double.parseDouble(uiPref);
                        if (custom > 0.0) {
                            double normalized = SystemScaleDetector.normalizeUiScaleToAllowed(custom);
                            System.setProperty("sun.java2d.uiScale", Double.toString(normalized));
                            logger.info("Applied user UI scale preference (normalized): {}", normalized);
                            appliedPref = true;
                        } else {
                            logger.warn(
                                    "Ignoring non-positive UI scale preference '{}'; falling back to detection.",
                                    uiPref);
                        }
                    } catch (NumberFormatException nfe) {
                        logger.warn("Invalid UI scale preference '{}'; falling back to detection.", uiPref);
                    }
                }

                if (!appliedPref) {
                    if (Environment.isLinux()) {
                        var detected = SystemScaleDetector.detectLinuxUiScale(systemScaleProvider);
                        if (detected != null && detected > 0.0) {
                            System.setProperty("sun.java2d.uiScale", Double.toString(detected));
                            logger.info("Applied sun.java2d.uiScale from environment: {}", detected);
                        } else {
                            logger.info("No reliable Linux UI scale detected; leaving sun.java2d.uiScale unset.");
                        }
                    } else if (Environment.isWindows()) {
                        var detected = SystemScaleDetector.tryDetectScaleViaWindows(systemScaleProvider);
                        if (detected != null && detected > 0.0) {
                            var normalized = SystemScaleDetector.normalizeUiScaleToAllowed(detected);
                            System.setProperty("sun.java2d.uiScale", Double.toString(normalized));
                            logger.info("Applied sun.java2d.uiScale from environment: {}", normalized);
                        } else {
                            logger.info("No reliable Windows UI scale detected; leaving sun.java2d.uiScale unset.");
                        }
                    } else {
                        logger.info("Unknown platform detected. Leaving sun.java2d.uiScale unset.");
                    }
                }
            }
        }
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            System.setProperty("apple.awt.application.name", "Brokk");
        }

        if (Boolean.getBoolean("brokk.devmode")) {
            RepaintManager.setCurrentManager(new CheckThreadViolationRepaintManager());
        }

        var iconUrl = Brokk.class.getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);
            if (Taskbar.isTaskbarSupported()) {
                var taskbar = Taskbar.getTaskbar();
                try {
                    taskbar.setIconImage(icon.getImage());
                } catch (UnsupportedOperationException | SecurityException e) {
                    logger.warn("Unable to set taskbar icon: {}", e.getMessage());
                }
            }
        }

        // Initialize JavaFX platform to prevent deadlocks during MOPWebViewHost creation
        // See: https://docs.oracle.com/javase/8/javafx/interoperability-tutorial/swing-fx-interoperability.htm
        try {
            javafx.application.Platform.startup(() -> {});
            // Prevent JavaFX thread from dying when JFXPanels are removed/hidden
            javafx.application.Platform.setImplicitExit(false);
            logger.debug("JavaFX platform initialized at startup");
        } catch (IllegalStateException e) {
            var message = e.getMessage();
            if (message != null && message.contains("Toolkit already initialized")) {
                logger.debug("JavaFX platform already initialized");
                // Still set implicit exit to false even if already initialized
                javafx.application.Platform.setImplicitExit(false);
            } else {
                logger.warn("Failed to initialize JavaFX platform: {}", message);
            }
        }
    }

    private static void initializeLookAndFeelAndSplashScreen(boolean isDark) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    if (isDark) {
                        com.formdev.flatlaf.FlatDarculaLaf.setup();
                    } else {
                        com.formdev.flatlaf.FlatIntelliJLaf.setup();
                    }
                } catch (Exception e) {
                    logger.warn("Failed to set LAF, using default", e);
                }
                showSplashScreen();
            });
        } catch (Exception e) { // Catches InterruptedException and InvocationTargetException
            logger.fatal("Failed to initialize SplashScreen. Exiting.", e);
            System.exit(1);
        }

        // set this globally since (so far) we never want a file chooser to make changes
        UIManager.put("FileChooser.readOnly", true);
    }

    private static ParsedArgs parseArguments(String[] args) {
        boolean noProjectFlag = false;
        boolean noKeyFlag = false;
        String projectPathArg = null;
        for (String arg : args) {
            if (arg.equals("--no-project")) {
                noProjectFlag = true;
            } else if (arg.equals("--no-key")) {
                noKeyFlag = true;
            } else if (!arg.startsWith("--")) {
                projectPathArg = arg;
            }
        }
        return new ParsedArgs(noProjectFlag, noKeyFlag, projectPathArg);
    }

    private static KeyValidationResult performKeyValidationLoop(boolean noKeyFlag) {
        boolean keyIsValid = false;

        // 1 – silent validation for an already-persisted key (unless --no-key).
        if (!noKeyFlag) {
            var existingKey = MainProject.getBrokkKey();
            if (!existingKey.isEmpty()) {
                try {
                    Service.validateKey(existingKey);
                    keyIsValid = true;
                } catch (IOException e) {
                    logger.warn("Network error validating existing Brokk key; assuming valid for now.", e);
                    keyIsValid = true; // allow offline use
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            null,
                            "Network error validating Brokk key. AI services may be unavailable.",
                            "Network Validation Warning",
                            JOptionPane.WARNING_MESSAGE));
                } catch (IllegalArgumentException e) {
                    logger.warn("Existing Brokk key is invalid: {}", e.getMessage());
                }
            }
        }

        // 2 – interactive loop until we have a valid key.
        while (!keyIsValid) {
            SwingUtil.runOnEdt(Brokk::hideSplashScreen);

            String newKey = SwingUtil.runOnEdt(() -> BrokkKeyDialog.showDialog(null, MainProject.getBrokkKey()), null);
            if (newKey == null) { // user cancelled
                logger.info("Key entry dialog cancelled; shutting down.");
                return new KeyValidationResult(false, null);
            }

            // BrokkKeyDialog has already validated and persisted the key.
            keyIsValid = true;
        }

        return new KeyValidationResult(true, null);
    }

    private static List<Path> determineInitialProjectsToOpen(
            ParsedArgs parsedArgs, @Nullable Path dialogProjectPathFromKey) {
        List<Path> projectsToAttemptOpen = new ArrayList<>();
        if (parsedArgs.projectPathArg != null) {
            Path pathFromArg =
                    Path.of(parsedArgs.projectPathArg).toAbsolutePath().normalize();
            if (isValidDirectory(pathFromArg)) {
                projectsToAttemptOpen.add(pathFromArg);
            } else {
                SwingUtil.runOnEdt(Brokk::hideSplashScreen);
                System.err.printf(
                        "Specified project path `%s` does not appear to be a valid directory%n",
                        parsedArgs.projectPathArg);
                System.exit(1); // Exit if specified path is invalid
            }
        } else if (dialogProjectPathFromKey != null && isValidDirectory(dialogProjectPathFromKey)) {
            // If a project was selected during key validation, prioritize it
            projectsToAttemptOpen.add(dialogProjectPathFromKey);
        } else if (!parsedArgs.noProjectFlag) {
            if (MainProject.getStartupOpenMode() == MainProject.StartupOpenMode.ALL) {
                projectsToAttemptOpen.addAll(MainProject.getOpenProjects());
            } else {
                var recent = MainProject.loadRecentProjects();
                if (!recent.isEmpty()) {
                    var mostRecent = recent.entrySet().stream()
                            .max(Comparator.comparingLong(e -> e.getValue().lastOpened()));
                    mostRecent.ifPresent(e -> projectsToAttemptOpen.add(e.getKey()));
                }
            }
        }
        return projectsToAttemptOpen;
    }

    private static boolean attemptOpenProjects(List<Path> projectsToAttemptOpen) {
        Map<Boolean, List<Path>> partitionedProjects = projectsToAttemptOpen.stream()
                .filter(Brokk::isValidDirectory)
                .peek(p -> {
                    if (!isValidDirectory(p)) logger.warn("Skipping invalid path: {}", p);
                })
                .collect(Collectors.partitioningBy(p -> {
                    if (GitRepoFactory.hasGitRepo(p)) {
                        try (GitRepo tempR = new GitRepo(p)) {
                            return tempR.isWorktree();
                        } catch (Exception e) {
                            logger.warn(
                                    "Error checking worktree status for {}: {}. Assuming not a worktree for ordering.",
                                    p,
                                    e.getMessage());
                            return false;
                        }
                    }
                    return false;
                }));

        List<Path> mainRepoPaths = castNonNull(partitionedProjects.get(false));
        List<Path> worktreePaths = castNonNull(partitionedProjects.get(true));

        boolean successfulOpenOccurred = false;

        for (Path mainPath : mainRepoPaths) {
            try {
                if (new OpenProjectBuilder(mainPath).open().get()) {
                    successfulOpenOccurred = true;
                }
            } catch (Exception e) {
                logger.error("Failed to open main project {}: {}", mainPath, e.getMessage(), e);
            }
        }

        for (Path worktreePath : worktreePaths) {
            MainProject parentProject = null;
            if (GitRepoFactory.hasGitRepo(worktreePath)) { // Redundant check, but safe
                try (GitRepo wtRepo = new GitRepo(worktreePath)) { // isWorktree already confirmed by partitioning
                    Path gitTopLevel = wtRepo.getGitTopLevel();
                    parentProject = (MainProject) findOpenProjectByPath(gitTopLevel);
                    if (parentProject == null) {
                        logger.warn(
                                "During startup, could not find an already open parent project for worktree {} (expected at {}). "
                                        + "Worktree will attempt to find/open its parent or open standalone if necessary.",
                                worktreePath.getFileName(),
                                gitTopLevel.getFileName());
                    }
                } catch (Exception e) {
                    logger.warn(
                            "Error determining parent for worktree {} during startup: {}. Proceeding without explicit parent.",
                            worktreePath.getFileName(),
                            e.getMessage());
                }
            }
            try {
                if (new OpenProjectBuilder(worktreePath)
                        .parent(parentProject)
                        .open()
                        .get()) {
                    successfulOpenOccurred = true;
                }
            } catch (Exception e) {
                logger.error("Failed to open worktree project {}: {}", worktreePath, e.getMessage(), e);
            }
        }
        return successfulOpenOccurred;
    }

    /** Main entry point. */
    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler(new OomShutdownHandler());

        logBanner();
        logger.debug("Brokk starting");
        setupSystemPropertiesAndIcon();

        if (MainProject.initializeOomFlag()) {
            logger.warn("Detected OutOfMemoryError from last session, clearing active sessions.");
            MainProject.clearActiveSessions();
            OomShutdownHandler.showRecoveryMessage();
        }

        MainProject.loadRecentProjects(); // Load and potentially clean recent projects list
        ParsedArgs parsedArgs = parseArguments(args);

        boolean isDark = MainProject.getTheme().equals("dark");
        initializeLookAndFeelAndSplashScreen(isDark);

        // Register native macOS handlers (only if running on macOS)
        if (Environment.isMacOs()) {
            SwingUtilities.invokeLater(() -> {
                try {
                    Desktop.getDesktop().setAboutHandler(e -> AboutDialog.showAboutDialog(null));
                } catch (UnsupportedOperationException ignored) {
                    // AboutHandler not supported on this platform/JVM – safe to ignore
                }

                // Register OpenFilesHandler for directory associations
                try {
                    Desktop.getDesktop().setOpenFileHandler(e -> {
                        List<Path> pathsToOpen = e.getFiles().stream()
                                .filter(java.io.File::isDirectory)
                                .map(java.io.File::toPath)
                                .toList();

                        if (!pathsToOpen.isEmpty()) {
                            // Open projects asynchronously to avoid blocking the handler
                            SwingUtilities.invokeLater(() -> {
                                hideSplashScreen();
                                for (Path path : pathsToOpen) {
                                    new OpenProjectBuilder(path).open().exceptionally(ex -> {
                                        logger.error("Failed to open project from file handler: {}", path, ex);
                                        return false;
                                    });
                                }
                            });
                        }
                    });
                } catch (UnsupportedOperationException ignored) {
                    // OpenFileHandler not supported – safe to ignore
                }
            });

            // Set up global preferences handler for all Chrome windows
            MenuBar.setupGlobalMacOSPreferencesHandler();
        }

        // run this after we show the splash screen, it's expensive
        Thread.ofPlatform().start(Messages::init);

        KeyValidationResult keyResult = performKeyValidationLoop(parsedArgs.noKeyFlag);
        Path dialogProjectPathFromKey = keyResult.dialogProjectPath();

        if (!keyResult.isValid()) {
            System.exit(0); // User cancelled key dialog or validation failed critically
        }
        // dialogProjectPathFromKey is already set above

        List<Path> projectsToAttemptOpen = determineInitialProjectsToOpen(parsedArgs, dialogProjectPathFromKey);

        boolean successfulOpenOccurred = attemptOpenProjects(projectsToAttemptOpen);

        if (!successfulOpenOccurred) {
            // No projects auto-opened; give the user the Open-Project dialog.
            promptAndOpenProject(null).thenAccept(opened -> {
                if (!opened && openProjectWindows.isEmpty()) {
                    logger.info("User closed Open Project dialog without opening anything. Exiting.");
                    System.exit(0);
                }
            });
        }
    }

    /**
     * Shows a modal dialog letting the user pick a project and opens it. If the user cancels the dialog, no project is
     * opened.
     *
     * @param owner The parent frame (may be {@code null}).
     * @return a CompletableFuture that completes with true if a project was opened, false otherwise.
     */
    public static CompletableFuture<Boolean> promptAndOpenProject(@Nullable Frame owner) {
        SwingUtil.runOnEdt(Brokk::hideSplashScreen); // Ensure splash screen is hidden before dialog
        var selectedPathOpt =
                requireNonNull(SwingUtil.runOnEdt(() -> OpenProjectDialog.showDialog(owner), Optional.<Path>empty()));

        if (selectedPathOpt.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        return new OpenProjectBuilder(selectedPathOpt.get()).open().exceptionally(ex -> {
            logger.error("Failed to open project selected via dialog: {}", selectedPathOpt.get(), ex);
            return false;
        });
    }

    private static void showSplashScreen() {
        assert SwingUtilities.isEventDispatchThread();
        if (splashScreen != null) {
            splashScreen.dispose(); // Should not happen if logic is correct
        }
        splashScreen = new JWindow();
        Chrome.applyIcon(splashScreen); // Sets window icon for taskbar if applicable

        var panel = new JPanel(new BorderLayout(15, 0)); // Horizontal gap
        Border lineBorder = BorderFactory.createLineBorder(Color.GRAY);
        Border emptyBorder = BorderFactory.createEmptyBorder(30, 50, 30, 50); // Padding
        panel.setBorder(BorderFactory.createCompoundBorder(lineBorder, emptyBorder));

        var iconUrl = Brokk.class.getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);
            // Scale icon to a reasonable size for splash, e.g., 64x64
            Image scaledImage = icon.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
            JLabel iconLabel = new JLabel(new ImageIcon(scaledImage)); // Alignment handled by BorderLayout
            panel.add(iconLabel, BorderLayout.WEST);
        }

        var label = new JLabel("Brokk " + BuildInfo.version, SwingConstants.LEFT); // Align text left
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f)); // Larger font
        panel.add(label, BorderLayout.CENTER);

        splashScreen.add(panel);
        splashScreen.pack();
        splashScreen.setLocationRelativeTo(null); // Center on screen
        splashScreen.setVisible(true);
        splashScreen.toFront(); // Ensure it's on top
    }

    private static void hideSplashScreen() {
        assert SwingUtilities.isEventDispatchThread();
        if (splashScreen != null) {
            splashScreen.setVisible(false);
            splashScreen.dispose();
            splashScreen = null;
        }
    }

    private static boolean isValidDirectory(Path path) {
        try {
            return Files.isDirectory(path.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static CompletableFuture<Void> createAndShowGui(Path projectPath, ContextManager contextManager) {
        assert SwingUtilities.isEventDispatchThread();

        var contextFuture = contextManager.createGui();
        var io = (Chrome) contextManager.getIo();

        // Log the current data retention policy.
        // This is called after any necessary dialog has been shown and policy confirmed.
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Data Retention Policy set to: " + contextManager.getProject().getDataRetentionPolicy());

        openProjectWindows.put(projectPath, io);

        io.getFrame().addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // Worktree-specific closing dialog removed.
                // Standard window closing procedure will be followed.
                performWindowClose(projectPath);
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                // This now only handles cleanup after the window is actually closed
                // The main logic has moved to windowClosing
            }
        });

        return contextFuture;
    }

    /**
     * Checks if a project window is already open for the given path.
     *
     * @param path The project path.
     * @return true if the project window is open, false otherwise.
     */
    public static boolean isProjectOpen(Path path) {
        return openProjectWindows.containsKey(path.toAbsolutePath().normalize());
    }

    /**
     * Brings the existing window for the given project path to the front and focuses it. Does nothing if the project is
     * not currently open.
     *
     * @param path The project path.
     */
    public static void focusProjectWindow(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Chrome existingWindow = openProjectWindows.get(normalizedPath);
        if (existingWindow != null) {
            SwingUtilities.invokeLater(() -> {
                JFrame frame = existingWindow.getFrame();
                frame.setState(Frame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            });
        }
    }

    /**
     * Finds the Chrome window for a given project path by checking all open windows. This method searches through all
     * open project windows and compares their project root paths.
     *
     * @param projectPath The project path to search for
     * @return The Chrome window for the project, or null if not found
     */
    public static @Nullable Chrome findOpenProjectWindow(@Nullable Path projectPath) {
        if (projectPath == null) {
            return null;
        }
        Path normalizedPath = projectPath.toAbsolutePath().normalize();
        // Check direct map first for exact path match
        Chrome directMatch = openProjectWindows.get(normalizedPath);
        if (directMatch != null) {
            // Verify the project root just in case, though it should match if the key matches
            ContextManager cm = directMatch.getContextManager();
            var p = cm.getProject();
            if (p.getRoot().toAbsolutePath().normalize().equals(normalizedPath)) {
                return directMatch;
            }
        }
        // Fallback: Iterate if direct match is not conclusive or not found (e.g. path aliases)
        for (Map.Entry<Path, Chrome> entry : openProjectWindows.entrySet()) {
            ContextManager cm = entry.getValue().getContextManager();
            var p = cm.getProject();
            if (p.getRoot().toAbsolutePath().normalize().equals(normalizedPath)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static @Nullable IProject findOpenProjectByPath(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Chrome chrome = openProjectWindows.get(normalizedPath); // Check direct path first
        if (chrome != null) {
            IProject project = chrome.getContextManager().getProject();
            if (project.getRoot().toAbsolutePath().normalize().equals(normalizedPath)) {
                return project;
            }
        }
        // Fallback if direct match is not conclusive or path is for parent of worktree
        for (Chrome openChrome : openProjectWindows.values()) {
            IProject project = openChrome.getContextManager().getProject();
            if (project.getRoot().toAbsolutePath().normalize().equals(normalizedPath)) {
                return project;
            }
        }
        return null;
    }

    /** Builder for opening projects. */
    public static class OpenProjectBuilder {
        private final Path path;
        private @Nullable MainProject parent;
        private @Nullable Consumer<Chrome> initialTask;
        private @Nullable Context sourceContextForSession;

        public OpenProjectBuilder(Path path) {
            this.path = path;
        }

        public OpenProjectBuilder parent(@Nullable MainProject parent) {
            this.parent = parent;
            return this;
        }

        public OpenProjectBuilder initialTask(@Nullable Consumer<Chrome> initialTask) {
            this.initialTask = initialTask;
            return this;
        }

        public OpenProjectBuilder sourceContextForSession(@Nullable Context sourceContextForSession) {
            this.sourceContextForSession = sourceContextForSession;
            return this;
        }

        public CompletableFuture<Boolean> open() {
            return Brokk.doOpenProject(this);
        }

        @Override
        public String toString() {
            return "OpenProjectBuilder{" + "path="
                    + path + ", parent="
                    + (parent == null ? "null" : parent.getRoot().toString()) + ", initialTask="
                    + initialTask + ", sourceContextForSession="
                    + sourceContextForSession + '}';
        }
    }

    private static CompletableFuture<Boolean> doOpenProject(OpenProjectBuilder builder) {
        final Path projectPath = builder.path.toAbsolutePath().normalize();
        logger.debug("Attempting to open project with {}", builder);

        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            focusProjectWindow(projectPath);
            if (builder.initialTask != null) {
                logger.warn("Initial task provided for already open project {}, task will not be run.", projectPath);
            }
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> openCompletionFuture = new CompletableFuture<>();

        initializeProjectAndContextManager(builder)
                .thenAcceptAsync(
                        contextManagerOpt -> {
                            if (contextManagerOpt.isEmpty()) {
                                SwingUtil.runOnEdt(Brokk::hideSplashScreen);
                                openCompletionFuture.complete(false);
                                return;
                            }

                            ContextManager contextManager = contextManagerOpt.get();
                            var project = contextManager.getProject();
                            Path actualProjectPath = project.getRoot();

                            if (project.getDataRetentionPolicy() == MainProject.DataRetentionPolicy.UNSET) {
                                logger.debug(
                                        "Project {} has no Data Retention Policy set. Showing dialog.",
                                        actualProjectPath.getFileName());
                                boolean policySetAndConfirmed =
                                        SettingsDialog.showStandaloneDataRetentionDialog(project, new JFrame());
                                if (!policySetAndConfirmed) {
                                    logger.info(
                                            "Data retention dialog cancelled for project {}. Aborting open.",
                                            actualProjectPath.getFileName());
                                    hideSplashScreen();
                                    openCompletionFuture.complete(false);
                                    return;
                                }
                                logger.info(
                                        "Data Retention Policy set to: {} for project {}",
                                        project.getDataRetentionPolicy(),
                                        actualProjectPath.getFileName());
                            }

                            hideSplashScreen();
                            var guiFuture = createAndShowGui(actualProjectPath, contextManager);
                            Chrome chromeInstance = openProjectWindows.get(actualProjectPath);
                            assert chromeInstance != null
                                    : "Chrome instance should be available after createAndShowGui is called";

                            // Associate worktree windows. This can happen once chromeInstance is available.
                            if (project instanceof MainProject) {
                                mainToWorktreeChromes.putIfAbsent(project, new CopyOnWriteArrayList<>());
                                logger.debug(
                                        "Registered main project {} for worktree tracking",
                                        actualProjectPath.getFileName());
                            } else {
                                IProject actualParentProject = project.getParent();
                                mainToWorktreeChromes
                                        .computeIfAbsent(actualParentProject, k -> new CopyOnWriteArrayList<>())
                                        .add(chromeInstance);
                                logger.debug(
                                        "Associated worktree window {} with main project {}",
                                        actualProjectPath.getFileName(),
                                        actualParentProject.getRoot().getFileName());
                            }

                            // Chain initialTask execution to guiFuture's completion
                            guiFuture.whenCompleteAsync(
                                    (Void result, @Nullable Throwable guiEx) -> {
                                        if (guiEx != null) {
                                            // if we have a half-finished gui we're kind of screwed
                                            throw new RuntimeException(guiEx);
                                        }

                                        if (builder.initialTask != null) {
                                            logger.debug(
                                                    "Executing initial task for project {}",
                                                    actualProjectPath.getFileName());
                                            builder.initialTask.accept(chromeInstance);
                                        }
                                        openCompletionFuture.complete(
                                                true); // Project opened, GUI ready, initial task (if any) attempted.
                                    },
                                    SwingUtilities::invokeLater); // Ensure this block runs on EDT
                        },
                        SwingUtilities::invokeLater)
                .exceptionally(ex -> {
                    logger.error(
                            "Exception during project opening pipeline for {}: {}", projectPath, ex.getMessage(), ex);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String errorMessage =
                            """
                                          A critical error occurred while trying to open the project:
                                          %s

                                          Please check the logs at ~/.brokk/debug.log and consider filing a bug report.
                                          """
                                    .formatted(cause.getMessage())
                                    .stripIndent();
                    SwingUtil.runOnEdt(() -> {
                        hideSplashScreen(); // Hide splash before showing error dialog
                        JOptionPane.showMessageDialog(
                                null, errorMessage, "Project Open Error", JOptionPane.ERROR_MESSAGE);
                    });
                    openCompletionFuture.complete(false);
                    return null;
                });

        return openCompletionFuture;
    }

    private static void performWindowClose(Path projectPath) {
        Chrome ourChromeInstance = openProjectWindows.get(projectPath);
        IProject projectBeingClosed = null;
        if (ourChromeInstance != null) {
            if (ourChromeInstance.getContextManager().isLlmTaskInProgress()) {
                int choice = ourChromeInstance.showConfirmDialog(
                        "An AI task is in progress. Are you sure you want to close this project?",
                        "Task in Progress",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice == JOptionPane.NO_OPTION) {
                    return;
                }
            }
            projectBeingClosed = ourChromeInstance.getContextManager().getProject();
        }

        if (projectBeingClosed != null) {
            if (projectBeingClosed instanceof MainProject) { // Closing a main project
                logger.debug(
                        "Main project {} is closing. Closing its associated worktree windows.",
                        projectPath.getFileName());
                List<Chrome> worktreeChromes = mainToWorktreeChromes.remove(projectBeingClosed);
                if (worktreeChromes != null) {
                    for (Chrome worktreeChrome : worktreeChromes) {
                        Path worktreeChromePath =
                                worktreeChrome.getContextManager().getProject().getRoot();
                        logger.debug("Closing worktree window: {}", worktreeChromePath.getFileName());
                        // Standard way to close a window: dispatch event, then it will call performWindowClose for
                        // itself.
                        SwingUtilities.invokeLater(() -> {
                            JFrame frame = worktreeChrome.getFrame();
                            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                        });
                        // DO NOT remove from openProjectWindows here; the worktree's own performWindowClose will handle
                        // that.
                    }
                }
            } else { // Closing a worktree project
                var parentOfWorktree = projectBeingClosed.getParent();
                List<Chrome> worktreeListOfMain = mainToWorktreeChromes.get(parentOfWorktree);
                if (worktreeListOfMain != null) {
                    if (worktreeListOfMain.remove(ourChromeInstance)) {
                        logger.debug(
                                "Removed worktree window {} from main project {}'s tracking list.",
                                projectPath.getFileName(),
                                parentOfWorktree.getRoot().getFileName());
                    }
                    // If the list becomes empty, we could remove the main project's entry from mainToWorktreeChromes,
                    // but computeIfAbsent handles recreation, so it's not strictly necessary for correctness.
                    // However, for cleanliness:
                    if (worktreeListOfMain.isEmpty()) {
                        mainToWorktreeChromes.remove(parentOfWorktree);
                        logger.debug(
                                "Removed main project {} from worktree tracking map as its list of worktrees is now empty.",
                                parentOfWorktree.getRoot().getFileName());
                    }
                }
            }
        }

        // Standard cleanup
        Chrome removedChrome = openProjectWindows.remove(projectPath);
        if (removedChrome != null) {
            removedChrome.close();
        }
        logger.debug("Removed project from open windows map: {}", projectPath);

        if (reOpeningProjects.contains(projectPath)) {
            CompletableFuture.runAsync(() -> MainProject.removeFromOpenProjectsListAndClearActiveSession(projectPath))
                    .exceptionally(ex -> {
                        logger.error(
                                "Error removing project (before reopen) from open projects list: {}", projectPath, ex);
                        return null;
                    });

            new OpenProjectBuilder(projectPath)
                    .open()
                    .whenCompleteAsync(
                            (@Nullable Boolean success, @Nullable Throwable reopenEx) -> {
                                reOpeningProjects.remove(projectPath);
                                if (reopenEx != null) {
                                    logger.error(
                                            "Exception occurred while trying to reopen project: {}",
                                            projectPath,
                                            reopenEx);
                                } else if (success == null || !success) {
                                    logger.warn("Failed to reopen project: {}. It will not be reopened.", projectPath);
                                }
                                if (openProjectWindows.isEmpty() && reOpeningProjects.isEmpty()) {
                                    logger.info(
                                            "All projects closed after reopen attempt of {}. Exiting.", projectPath);
                                    System.exit(0);
                                }
                            },
                            SwingUtilities::invokeLater);
            return;
        }

        // Dispose the frame to actually close the window
        if (ourChromeInstance != null) {
            ourChromeInstance.getFrame().dispose();
        }

        boolean noMainProjectsOpen = openProjectWindows.values().stream().noneMatch(chrome -> {
            IProject p = chrome.getContextManager().getProject();
            return p instanceof MainProject; // Check if it's a main project
        });
        boolean appIsExiting = noMainProjectsOpen && reOpeningProjects.isEmpty();
        if (appIsExiting) {
            // We are about to exit the application.
            // Do NOT remove this project from the persistent "open projects" list.
            logger.info("Last project window ({}) closed. App exiting. It remains MRU.", projectPath);
            System.exit(0);
        } else {
            // Other projects are still open or other projects are pending reopening.
            // This one is just closing, so remove it from the persistent "open projects" list.
            CompletableFuture.runAsync(() -> MainProject.removeFromOpenProjectsListAndClearActiveSession(projectPath))
                    .exceptionally(ex -> {
                        logger.error("Error removing project from open projects list: {}", projectPath, ex);
                        return null;
                    });
            // No System.exit(0) here, as other windows/tasks are active.
        }
    }

    private static CompletableFuture<Optional<ContextManager>> initializeProjectAndContextManager(
            OpenProjectBuilder builder) {
        Path projectPath = builder.path;
        MainProject parent = builder.parent;

        try {
            MainProject.updateRecentProject(projectPath);
            var project = AbstractProject.createProject(projectPath, parent);

            if (project.getRepo().isWorktree() && parent == null) {
                logger.warn(
                        "User attempted to open a worktree ({}) directly as a project without specific internal trigger. Denying.",
                        projectPath);
                final Path finalProjectPath = projectPath;
                SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                        null,
                        "The selected path (" + finalProjectPath.getFileName() + ") is a Git worktree.\n"
                                + "Worktrees should be managed via the 'Worktrees' tab in the main repository window, or created by Architect.",
                        "Cannot Open Worktree Directly",
                        JOptionPane.WARNING_MESSAGE));
                return CompletableFuture.completedFuture(Optional.empty());
            }

            if (!project.hasGit()) {
                int response = castNonNull(SwingUtil.runOnEdt(
                        () -> JOptionPane.showConfirmDialog(
                                null,
                                """
                        This project is not under Git version control. Would you like to initialize a new Git repository here?

                        Without Git, the project will be read-only, and some features may be limited.""",
                                "Initialize Git Repository?",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE),
                        JOptionPane.NO_OPTION));

                if (response == JOptionPane.YES_OPTION) {
                    try {
                        logger.info("Initializing Git repository at {}...", project.getRoot());
                        GitRepoFactory.initRepo(project.getRoot());
                        project = AbstractProject.createProject(projectPath, parent); // Re-create project
                        logger.info("Git repository initialized successfully at {}.", project.getRoot());
                    } catch (Exception e) {
                        logger.error(
                                "Failed to initialize Git repository at {}: {}", project.getRoot(), e.getMessage(), e);
                        final String errorMsg = e.getMessage();
                        SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                                null,
                                "Failed to initialize Git repository: " + errorMsg
                                        + "\nThe project will be opened as read-only.",
                                "Git Initialization Error",
                                JOptionPane.ERROR_MESSAGE));
                    }
                } else {
                    logger.info(
                            "User declined Git initialization for project {}. Proceeding as read-only.",
                            project.getRoot());
                }
            }

            if (project instanceof MainProject mp) {
                mp.reserveSessionsForKnownWorktrees();
            }

            // TODO nothing is actually async here
            var contextManager = new ContextManager(project); // Project must be final or effectively final for lambda
            if (builder.sourceContextForSession != null) {
                // Asynchronously create session from workspace, then complete the future
                String newSessionName =
                        "Architect Session (" + project.getRoot().getFileName() + ")"; // Or generate based on branch
                contextManager.createSessionWithoutGui(builder.sourceContextForSession, newSessionName);
            }
            return CompletableFuture.completedFuture(Optional.of(contextManager));
        } catch (Exception e) {
            logger.error("Failed to initialize project for path {}: {}", projectPath, e.getMessage(), e);
            final String errorMsg = e.getMessage();
            SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                    null,
                    "Could not open project " + projectPath.getFileName() + ":\n" + errorMsg
                            + "\nPlease check the logs for more details.",
                    "Project Initialization Failed",
                    JOptionPane.ERROR_MESSAGE));
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * Closes the project if it's already open, then opens it again fresh. Useful for reloading after external changes
     * or config updates.
     */
    public static void reOpenProject(Path path) {
        assert SwingUtilities.isEventDispatchThread();

        final Path projectPath = path.toAbsolutePath().normalize();
        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            // Mark as re-opening, the windowClosed listener will do the rest
            reOpeningProjects.add(projectPath);
            // Programatically close the window
            var chrome = requireNonNull(openProjectWindows.get(projectPath), "No Chrome found for project path");
            var frame = chrome.getFrame();
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        } else {
            // If not open, just open it directly.
            new OpenProjectBuilder(projectPath)
                    .open()
                    .whenCompleteAsync(
                            (@Nullable Boolean success, @Nullable Throwable ex) -> {
                                if (ex != null) {
                                    logger.error("Error reopening project {}: {}", projectPath, ex);
                                } else if (success == null || !success) {
                                    logger.warn(
                                            "Failed to reopen project {}. It will not be automatically reopened.",
                                            projectPath);
                                    MainProject.removeFromOpenProjectsListAndClearActiveSession(projectPath);
                                }
                            },
                            SwingUtilities::invokeLater);
        }
    }

    public static ConcurrentHashMap<Path, Chrome> getOpenProjectWindows() {
        return openProjectWindows;
    }

    /**
     * Finds the focused or active Chrome window among open project windows. First tries to find a focused window, then
     * falls back to an active (non-minimized) window, and finally returns any displayable and showing Chrome instance.
     *
     * @return the focused or active Chrome window, or null if no suitable window is found
     */
    @Nullable
    public static Chrome getActiveWindow() {
        var openChromeInstances = getOpenProjectWindows().values();
        if (openChromeInstances.isEmpty()) {
            return null;
        }

        // First try to find the focused window that is displayable and showing
        var focusedWindow = openChromeInstances.stream()
                .filter(chrome -> {
                    var frame = chrome.getFrame();
                    return frame.isFocused() && frame.isDisplayable() && frame.isShowing();
                })
                .findFirst();
        if (focusedWindow.isPresent()) {
            return focusedWindow.get();
        }

        // Fallback: try to find the active window (not minimized) that is displayable and showing
        var activeWindow = openChromeInstances.stream()
                .filter(chrome -> {
                    var frame = chrome.getFrame();
                    return frame.isActive()
                            && (frame.getExtendedState() & Frame.ICONIFIED) == 0
                            && frame.isDisplayable()
                            && frame.isShowing();
                })
                .findFirst();
        if (activeWindow.isPresent()) {
            return activeWindow.get();
        }

        // Last resort: return any displayable and showing Chrome instance
        return openChromeInstances.stream()
                .filter(chrome -> {
                    var frame = chrome.getFrame();
                    return frame.isDisplayable() && frame.isShowing();
                })
                .findFirst()
                .orElse(null);
    }

    public static List<Chrome> getWorktreeChromes(IProject mainProject) {
        return mainToWorktreeChromes.getOrDefault(mainProject, List.of());
    }

    /**
     * Gracefully exit the application: - request each open project's ContextManager to close with a timeout - wait for
     * all closes to complete - then terminate the JVM
     */
    public static void exit() {
        logger.info("Exit requested. Closing all open project contexts...");
        var futures = openProjectWindows.values().stream()
                .map(chrome -> {
                    try {
                        return chrome.getContextManager().closeAsync(1_000);
                    } catch (Throwable th) {
                        logger.warn(
                                "Error initiating close for project {}",
                                chrome.getContextManager().getProject().getRoot(),
                                th);
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).join();
            logger.info("All project contexts closed. Exiting.");
        } finally {
            System.exit(0);
        }
    }
}
