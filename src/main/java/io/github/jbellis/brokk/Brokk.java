package io.github.jbellis.brokk;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import io.github.jbellis.brokk.gui.Chrome;
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

        // Set macOS to use system menu bar
        System.setProperty("apple.laf.useScreenMenuBar", "true");

        // 1) Load your icon
        var iconUrl = Brokk.class.getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);

            // 2) Attempt to set icon in macOS Dock & Windows taskbar (Java 9+)
            if (Taskbar.isTaskbarSupported()) {
                var taskbar = Taskbar.getTaskbar();
                try {
                    taskbar.setIconImage(icon.getImage());
                } catch (UnsupportedOperationException | SecurityException e) {
                    System.err.println("Unable to set taskbar icon: " + e.getMessage());
                }
            }
        }

        // 3) On macOS, optionally set the app name for the Dock:
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            System.setProperty("apple.awt.application.name", "Brokk");
        }

        SwingUtilities.invokeLater(() -> {
            // 1) Set FlatLaf Look & Feel - we'll use light as default initially
            try {
                com.formdev.flatlaf.FlatLightLaf.setup();
            } catch (Exception e) {
                logger.warn("Failed to set LAF, using default", e);
            }

            // Check for command-line flags
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

            // Determine initial project path
            Path determinedProjectPath = null;
            if (projectPathArg != null) {
                determinedProjectPath = Path.of(projectPathArg).toAbsolutePath().normalize();
            } else if (!noProjectFlag) { // only check recent if not --no-project
                var openProjects = Project.getOpenProjects();
                if (!openProjects.isEmpty()) {
                    determinedProjectPath = openProjects.getFirst();
                }
            }

            // Validate Brokk API Key, unless --no-key is specified
            String currentBrokkKey = "";
            boolean keyIsValid = false;
            if (!noKeyFlag) {
                currentBrokkKey = Project.getBrokkKey();
                if (!currentBrokkKey.isEmpty()) {
                    try {
                        Models.validateKey(currentBrokkKey);
                        keyIsValid = true;
                    } catch (IOException e) {
                        // Network error during startup validation. Key might be valid, but we can't confirm.
                        // Treat as invalid for now to ensure dialog handles re-validation or offline use.
                        logger.error("Network error validating existing Brokk key at startup. Will re-evaluate in dialog.", e);
                    } catch (IllegalArgumentException e) {
                        logger.warn("Existing Brokk key is invalid: {}", e.getMessage());
                    }
                }
            } // If noKeyFlag is true, currentBrokkKey is "" and keyIsValid is false.

            // Validate project path
            boolean projectPathIsValid = false;
            if (determinedProjectPath != null) {
                try {
                    Path realProjectPath = determinedProjectPath.toRealPath(); // Resolve symlinks
                    if (Files.isDirectory(realProjectPath)) {
                        projectPathIsValid = true;
                        determinedProjectPath = realProjectPath; // Use the real path
                    } else {
                        logger.warn("Project path is not a directory: {}", realProjectPath);
                    }
                } catch (IOException e) { // Includes NoSuchFileException
                    logger.warn("Project path is not valid or not accessible: {}. Error: {}", determinedProjectPath, e.getMessage());
                }
            }

            // Exit if a specific project path was given via CLI and it's invalid
            if (projectPathArg != null && !projectPathIsValid) {
                System.out.printf("Specified project path `%s` does not appear to be a valid directory%n", projectPathArg);
                System.exit(1);
            }

            // Attempt to open directly if all conditions met
            if ((keyIsValid || noKeyFlag) && projectPathIsValid) {
                openProject(determinedProjectPath);
                return;
            }

            // One or both are missing/invalid, show the startup dialog
            StartupDialog.DialogMode mode;
            Path initialProjectPathForDialog = projectPathIsValid ? determinedProjectPath : null;

            if (!keyIsValid && !projectPathIsValid) {
                mode = StartupDialog.DialogMode.REQUIRE_BOTH;
            } else if (!keyIsValid) {
                mode = StartupDialog.DialogMode.REQUIRE_KEY_ONLY;
            } else { // keyIsValid (implicitly true here due to outer if) && !projectPathIsValid
                mode = StartupDialog.DialogMode.REQUIRE_PROJECT_ONLY;
            }

            Path projectToOpen = StartupDialog.showDialog(null, currentBrokkKey, keyIsValid, initialProjectPathForDialog, mode);
            if (projectToOpen != null) {
                openProject(projectToOpen);
            } else {
                logger.info("Startup dialog was closed or exited. Shutting down.");
                System.exit(0);
            }
        });
    }

    /**
     * Opens the given project folder in Brokk, or brings existing window to front.
     * The folder must contain a .git subdirectory or else we will show an error.
     */
    public static void openProject(Path path)
    {
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

        Project.updateRecentProject(projectPath);

        var contextManager = new ContextManager(projectPath);

        var io = new Chrome(contextManager);
        io.systemOutput("Opening project at " + projectPath);
        contextManager.resolveCircularReferences(io);

        // Check and potentially force setting Data Retention Policy *before* showing main window fully
        // but *after* the project and UI frame are created
        var project = contextManager.getProject();
        if (project.getDataRetentionPolicy() == Project.DataRetentionPolicy.UNSET) {
            logger.debug("Project {} has no Data Retention Policy set. Showing dialog.", projectPath.getFileName());
            // Run the dialog on the EDT after the main frame setup might have happened
            SwingUtilities.invokeLater(() -> {
                SettingsDialog.showStandaloneDataRetentionDialog(project, io.getFrame());
                // After dialog is closed (policy is set), ensure UI reflects any consequences if needed
                // e.g., update model list if policy affects it. (Future enhancement)
                // Models.refreshAvailableModels(); // TODO: Implement model refresh based on policy
                io.systemOutput("Data Retention Policy set to: " + project.getDataRetentionPolicy());
            });
        }

        io.onComplete(); // Finalize UI setup

        openProjectWindows.put(projectPath, io);

        // Window listener that removes from maps; only exit if not in re-opening and zero windows remain
        io.getFrame().addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e)
            {
                openProjectWindows.remove(projectPath).close();

                // Only exit if we now have no windows open and we are NOT re-opening a project
                if (openProjectWindows.isEmpty() && reOpeningProjects.isEmpty()) {
                    System.exit(0);
                }

                Project.removeFromOpenProjects(projectPath);
                logger.debug("Removed project from open windows map: {}", projectPath);

                if (reOpeningProjects.contains(projectPath)) {
                    reOpeningProjects.remove(projectPath);
                    openProject(projectPath);
                }
            }
        });

        // remove placeholder frame if present
        if (openProjectWindows.get(EMPTY_PROJECT) != null) {
            openProjectWindows.remove(EMPTY_PROJECT).close();
        }
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
