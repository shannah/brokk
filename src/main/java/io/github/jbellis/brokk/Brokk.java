package io.github.jbellis.brokk;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import io.github.jbellis.brokk.gui.CheckThreadViolationRepaintManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.StartupDialog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


public class Brokk {
    private static final Logger logger = LogManager.getLogger(Brokk.class);

    private static final ConcurrentHashMap<Path, Chrome> openProjectWindows = new ConcurrentHashMap<>();
    private static final Set<Path> reOpeningProjects = ConcurrentHashMap.newKeySet();
    public static final CompletableFuture<AbstractModel> embeddingModelFuture;
    // key for empty project in the openProjectWindows map, should not be used as a path on disk
    private static final Path EMPTY_PROJECT = Path.of("∅");

    public static final String ICON_RESOURCE = "/brokk-icon.png";

    static {
        embeddingModelFuture = CompletableFuture.supplyAsync(() -> {
            logger.info("Loading embedding model asynchronously...");
            var modelName = "sentence-transformers/all-MiniLM-L6-v2";
            File localModelPath = null;
            try {
                var cacheDir = System.getProperty("user.home") + "/.cache/brokk";
                new File(cacheDir).mkdirs();
                localModelPath = SafeTensorSupport.maybeDownloadModel(cacheDir, modelName);
            } catch (IOException e) {
                logger.warn(e);
                return null;
            }
            // Assuming loadEmbeddingModel returns BertEmbeddingModel or a compatible type
            var model = ModelSupport.loadEmbeddingModel(localModelPath, DType.F32, DType.I8);
            logger.info("Embedding model loading complete.");
            // Cast to the specific type expected by the Future if necessary
            return model;
        });
    }

    /**
     * Main entry point: Start up Brokk with no project loaded,
     * then check if there's a "most recent" project to open.
     */
    public static void main(String[] args) {
        logger.debug("Brokk starting");

        // Set macOS system properties (non-UI, can be immediate)
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            System.setProperty("apple.awt.application.name", "Brokk");
        }

        // Set up thread violation checker for dev mode
        if (Boolean.getBoolean("brokk.devmode")) {
            RepaintManager.setCurrentManager(new CheckThreadViolationRepaintManager());
        }

        // Set application icon
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

        // Ensure L&F is set on EDT before any UI is created.
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    com.formdev.flatlaf.FlatLightLaf.setup();
                } catch (Exception e) {
                    logger.warn("Failed to set LAF, using default", e);
                }
            });
        } catch (Exception e) { // Catches InterruptedException and InvocationTargetException
            logger.fatal("Failed to initialize Look and Feel on EDT. Exiting.", e);
            System.exit(1);
        }

        // Argument parsing and initial validation (off-EDT / main thread)
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

        Path determinedProjectPath = null;
        if (projectPathArg != null) {
            determinedProjectPath = Path.of(projectPathArg).toAbsolutePath().normalize();
        } else if (!noProjectFlag) {
            var openProjects = Project.getOpenProjects(); // I/O
            if (!openProjects.isEmpty()) {
                determinedProjectPath = openProjects.getFirst();
            }
        }

        String currentBrokkKey = "";
        boolean keyIsValid = false;
        if (!noKeyFlag) {
            currentBrokkKey = Project.getBrokkKey(); // I/O (prefs)
            if (currentBrokkKey != null && !currentBrokkKey.isEmpty()) {
                try {
                    Models.validateKey(currentBrokkKey); // I/O (network)
                    keyIsValid = true;
                } catch (IOException e) {
                    logger.warn("Network error validating existing Brokk key at startup. Assuming valid for now.", e);
                    keyIsValid = true; // Assume valid to allow offline use / project access
                    // Show error on EDT
                    SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(null,
                                                      "Network error validating Brokk key. AI services may be unavailable.",
                                                      "Network Validation Warning",
                                                      JOptionPane.WARNING_MESSAGE));
                } catch (IllegalArgumentException e) {
                    logger.warn("Existing Brokk key is invalid: {}", e.getMessage());
                    // keyIsValid remains false
                }
            }
        }

        boolean projectPathIsValid = false;
        if (determinedProjectPath != null) {
            try {
                Path realProjectPath = determinedProjectPath.toRealPath(); // I/O
                if (Files.isDirectory(realProjectPath)) { // I/O
                    projectPathIsValid = true;
                    determinedProjectPath = realProjectPath;
                } else {
                    logger.warn("Project path is not a directory: {}", realProjectPath);
                }
            } catch (IOException e) { // Includes NoSuchFileException
                logger.warn("Project path not valid or not accessible: {}. Error: {}", determinedProjectPath, e.getMessage());
            }
        }

        if (projectPathArg != null && !projectPathIsValid) {
            System.err.printf("Specified project path `%s` does not appear to be a valid directory%n", projectPathArg);
            System.exit(1);
        }

        // Attempt to open directly if all conditions met
        if (keyIsValid && projectPathIsValid) {
            openProject(determinedProjectPath); // Called from main thread
            return;
        }

        // One or both are missing/invalid, show the startup dialog on EDT
        final String finalCurrentBrokkKey = currentBrokkKey;
        final boolean finalKeyIsValid = keyIsValid;
        final Path finalInitialProjectPathForDialog = projectPathIsValid ? determinedProjectPath : null;
        final boolean finalProjectPathIsValid = projectPathIsValid; // Need this for mode determination

        Path projectToOpenFromDialog;
        try {
            projectToOpenFromDialog = SwingUtil.runOnEdt(() -> {
                StartupDialog.DialogMode mode;
                if (!finalKeyIsValid && !finalProjectPathIsValid) {
                    mode = StartupDialog.DialogMode.REQUIRE_BOTH;
                } else if (!finalKeyIsValid) {
                    mode = StartupDialog.DialogMode.REQUIRE_KEY_ONLY;
                } else { // finalKeyIsValid && !finalProjectPathIsValid
                    mode = StartupDialog.DialogMode.REQUIRE_PROJECT_ONLY;
                }
                return StartupDialog.showDialog(null, finalCurrentBrokkKey, finalKeyIsValid, finalInitialProjectPathForDialog, mode);
            }, null);
        } catch (Exception e) { // Catches InterruptedException and InvocationTargetException
            logger.error("Error showing StartupDialog or it was interrupted. Shutting down.", e);
            System.exit(1);
            return; // Unreachable
        }

        if (projectToOpenFromDialog != null) {
            openProject(projectToOpenFromDialog); // Called from main thread
        } else {
            logger.info("Startup dialog was closed or exited. Shutting down.");
            System.exit(0);
        }
    }

    private static void createAndShowGui(Path projectPath, ContextManager contextManager) {
        assert SwingUtilities.isEventDispatchThread();

        var io = new Chrome(contextManager);
        io.systemOutput("Opening project at " + projectPath);
        contextManager.resolveCircularReferences(io);

        var project = contextManager.getProject();
        if (project.getDataRetentionPolicy() == Project.DataRetentionPolicy.UNSET) {
            logger.debug("Project {} has no Data Retention Policy set. Showing dialog.", projectPath.getFileName());
            // This dialog is modal and runs on the EDT.
            SettingsDialog.showStandaloneDataRetentionDialog(project, io.getFrame());
            io.systemOutput("Data Retention Policy set to: " + project.getDataRetentionPolicy());
        }

        io.onComplete(); // Finalize UI setup
        openProjectWindows.put(projectPath, io);

        io.getFrame().addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                // This listener is invoked on the EDT.
                Chrome closedChrome = openProjectWindows.remove(projectPath);
                if (closedChrome != null) {
                    closedChrome.close(); // Assumes Chrome.close() is EDT-safe
                }
                logger.debug("Removed project from open windows map: {}", projectPath);

                if (openProjectWindows.isEmpty() && reOpeningProjects.isEmpty()) {
                    System.exit(0);
                }

                // I/O part of closing, run in background
                CompletableFuture.runAsync(() -> Project.removeFromOpenProjects(projectPath))
                        .exceptionally(ex -> {
                            logger.error("Error removing project from open projects list: {}", projectPath, ex);
                            return null;
                        });

                if (reOpeningProjects.contains(projectPath)) {
                    Path pathToReopen = projectPath; // Effectively final for lambda
                    reOpeningProjects.remove(pathToReopen);
                    // openProject is called from EDT here. It will internally dispatch I/O.
                    openProject(pathToReopen);
                }
            }
        });

        // remove placeholder frame if present
        if (openProjectWindows.get(EMPTY_PROJECT) != null) {
            openProjectWindows.remove(EMPTY_PROJECT).close();
        }
    }

    /**
     * Opens the given project folder in Brokk, or brings existing window to front.
     * The folder must contain a .git subdirectory or else we will show an error.
     */
    public static void openProject(Path path) {
        final Path projectPath = path.toAbsolutePath().normalize();
        logger.debug("Opening project at " + projectPath);

        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            logger.debug("Project already open: {}. Bringing window to front.", projectPath);
            SwingUtilities.invokeLater(() -> {
                var frame = existingWindow.getFrame();
                frame.setState(Frame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            });
            return;
        }

        CompletableFuture<ContextManager> ioOperation = CompletableFuture.supplyAsync(() -> {
            try {
                return initializeProjectAndContextManager(projectPath);
            } catch (Exception e) {
                // Wrap checked exceptions for CompletableFuture
                throw new RuntimeException("Failed during project initialization: " + e.getMessage(), e);
            }
        });

        CompletableFuture<Void> uiOperation = ioOperation.thenAccept((ContextManager cm) -> {
            SwingUtilities.invokeLater(() -> createAndShowGui(projectPath, cm));
        });

        uiOperation.exceptionally(ex -> {
            logger.fatal("Fatal error opening project: {}", projectPath, ex);
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String errorMessage = """
                                  A fatal error occurred during project initialization:
                                  %s

                                  Please file a bug report including ~/.brokk/debug.log.
                                  
                                  The application will now exit.
                                  """.formatted(cause.getMessage()).stripIndent();
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null,
                                              errorMessage,
                                              "Fatal Project Initialization Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            });
            return null;
        });
    }

    /**
     * Handles the core logic of initializing a project, including Git setup and ContextManager creation.
     * This method performs I/O and may show dialogs (which are correctly dispatched to EDT).
     *
     * @param projectPath The path to the project.
     * @return A new ContextManager instance, or null if initialization is aborted (e.g., user declines Git init for a non-Git project and we decide not to proceed).
     */
    private static ContextManager initializeProjectAndContextManager(Path projectPath) {
        Project.updateRecentProject(projectPath);
        Project project = new Project(projectPath);

        // Git Initialization Logic
        if (!project.hasGit()) {
            // This JOptionPane runs on EDT due to SwingUtil.runOnEdt
            int response = SwingUtil.runOnEdt(() -> JOptionPane.showConfirmDialog(
                    null,
                    "This project is not under Git version control. Would you like to initialize a new Git repository here?"
                    + "\n\nWithout Git, the project will be read-only, and some features may be limited.",
                    "Initialize Git Repository?",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE), JOptionPane.NO_OPTION);

            if (response == JOptionPane.YES_OPTION) {
                try {
                    logger.info("Initializing Git repository at " + project.getRoot() + "...");
                    io.github.jbellis.brokk.git.GitRepo.initRepo(project.getRoot());
                    project = new Project(project.getRoot()); // Re-create project to reflect new .git dir
                    logger.info("Git repository initialized successfully.");
                } catch (Exception e) {
                    logger.error("Failed to initialize Git repository at {}: {}", project.getRoot(), e.getMessage(), e);
                    final String errorMessage = e.getMessage();
                    // This JOptionPane also runs on EDT
                    SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                            null,
                            "Failed to initialize Git repository: " + errorMessage +
                            "\nThe project will be opened as read-only.",
                            "Git Initialization Error",
                            JOptionPane.ERROR_MESSAGE));
                    // Project remains the initial non-Git project, continue as read-only.
                }
            } else {
                logger.info("User declined Git initialization for project {}. Proceeding as read-only.", project.getRoot());
            }
        }
        return new ContextManager(project);
    }

    /**
     * Closes the project if it's already open, then opens it again fresh.
     * Useful for reloading after external changes or config updates.
     */
    public static void reOpenProject(Path path)
    {
        assert SwingUtilities.isEventDispatchThread();

        final Path projectPath = path.toAbsolutePath().normalize();
        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            // Mark as re-opening, the windowClosed listener will do the rest
            reOpeningProjects.add(projectPath);
            // Programatically close the window
            var frame = openProjectWindows.get(projectPath).getFrame();
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        }
    }
}
