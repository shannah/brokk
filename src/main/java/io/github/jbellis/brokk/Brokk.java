package io.github.jbellis.brokk;

import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;


public class Brokk {
    private static final Logger logger = LogManager.getLogger(Brokk.class);
    
    private static final ConcurrentHashMap<Path, Chrome> openProjectWindows = new ConcurrentHashMap<>();
    private static final Path EMPTY_PROJECT = Path.of(":EMPTY:");

    public static final String ICON_RESOURCE = "/brokk-icon.png";

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
            // Check for --no-project flag
            boolean noProjectFlag = false;
            String projectPathArg = null;
            
            for (String arg : args) {
                if (arg.equals("--no-project")) {
                    noProjectFlag = true;
                } else if (!arg.startsWith("--")) {
                    projectPathArg = arg;
                }
            }
            
            // If a project path is provided, use it
            if (projectPathArg != null) {
                var projectPath = Path.of(projectPathArg);
                openProject(projectPath);
            } else {
                // No project specified - attempt to load open projects if any
                var openProjects = Project.getOpenProjects();

                if (noProjectFlag || openProjects.isEmpty()) {
                    var io = new Chrome(null);
                    io.onComplete();
                    openProjectWindows.put(EMPTY_PROJECT, io);
                } else {
                    // Open all previously open projects
                    logger.info("Opening {} previously open projects", openProjects.size());
                    for (var projectPath : openProjects) {
                        openProject(projectPath);
                    }
                }
            }
        });
    }

    /**
     * Opens the given project folder in Brokk, or brings existing window to front.
     * The folder must contain a .git subdirectory or else we will show an error.
     */
    public static void openProject(Path path) {
        // Normalize the path to handle potential inconsistencies (e.g., trailing slashes)
        final Path projectPath = path.toAbsolutePath().normalize();

        // Check if this project is already open
        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            logger.info("Project already open: {}. Bringing window to front.", projectPath);
            SwingUtilities.invokeLater(() -> {
                var frame = existingWindow.getFrame();
                frame.setState(Frame.NORMAL); // Restore if minimized
                frame.toFront();
                frame.requestFocus();
                existingWindow.focusInput();
            });
            return;
        }

        // Save to recent projects and mark as open
        Project.updateRecentProject(projectPath);

        var contextManager = new ContextManager(projectPath);
        Models models;
        String modelsError = null;
        try {
            models = Models.load();
        } catch (Throwable th) {
            modelsError = th.getMessage();
            models = Models.disabled();
        }

        // Create a new Chrome instance with the fresh ContextManager
        var io = new Chrome(contextManager);

        // Create the Coder with the new IO
        var coder = new Coder(models, io, projectPath, contextManager);

        // Resolve circular references
        contextManager.resolveCircularReferences(io, coder);
        io.onComplete();
        io.systemOutput("Opened project at " + projectPath);

        if (!coder.isLlmAvailable()) {
            io.toolError("\nError loading models: " + modelsError);
            io.toolError("AI will not be available this session");
        }
        io.focusInput();
        
        // Add to open projects map
        openProjectWindows.put(projectPath, io);
        
        // Add window listener to remove from map when closed
        io.getFrame().addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                openProjectWindows.remove(projectPath).close();
                if (openProjectWindows.isEmpty()) {
                    System.exit(0);
                }
                Project.removeFromOpenProjects(projectPath);
                logger.debug("Removed project from open windows map: {}", projectPath);
            }
        });

        // remove placeholder frame if present
        if (openProjectWindows.get(EMPTY_PROJECT) != null) {
            openProjectWindows.remove(EMPTY_PROJECT).close();
        }
    }
}
