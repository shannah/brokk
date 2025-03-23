package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public class Project implements IProject {
    private final Path propertiesFile;
    private final Path workspacePropertiesFile;
    private final Path root;
    private final Properties projectProps;
    private final Properties workspaceProps;
    private final Path styleGuidePath;
    private final AnalyzerWrapper analyzerWrapper;
    private final GitRepo repo;

    private static final int DEFAULT_AUTO_CONTEXT_FILE_COUNT = 10;
    private static final int DEFAULT_WINDOW_WIDTH = 800;
    private static final int DEFAULT_WINDOW_HEIGHT = 1200;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LogManager.getLogger(Project.class);

    public Project(Path root, AnalyzerWrapper.TaskRunner runner, AnalyzerListener analyzerListener) {
        this.repo = new GitRepo(root);
        this.root = root;
        this.propertiesFile = root.resolve(".brokk").resolve("project.properties");
        this.workspacePropertiesFile = root.resolve(".brokk").resolve("workspace.properties");
        this.styleGuidePath = root.resolve(".brokk").resolve("style.md");
        this.projectProps = new Properties();
        this.workspaceProps = new Properties();

        // Load project properties
        if (Files.exists(propertiesFile)) {
            try (var reader = Files.newBufferedReader(propertiesFile)) {
                projectProps.load(reader);
            } catch (Exception e) {
                logger.error("Error loading project properties: {}", e.getMessage());
                projectProps.clear();
            }
        }

        // Load workspace properties
        if (Files.exists(workspacePropertiesFile)) {
            try (var reader = Files.newBufferedReader(workspacePropertiesFile)) {
                workspaceProps.load(reader);
            } catch (Exception e) {
                logger.error("Error loading workspace properties: {}", e.getMessage());
                workspaceProps.clear();
            }
        }

        // Create the analyzer wrapper
        this.analyzerWrapper = new AnalyzerWrapper(this, runner, analyzerListener);

        // Set defaults for workspace properties if missing
        if (workspaceProps.isEmpty()) {
            workspaceProps.setProperty("autoContextFileCount", String.valueOf(DEFAULT_AUTO_CONTEXT_FILE_COUNT));
            // Create default window positions
            var mainFrame = objectMapper.createObjectNode();
            mainFrame.put("x", -1);
            mainFrame.put("y", -1);
            mainFrame.put("width", DEFAULT_WINDOW_WIDTH);
            mainFrame.put("height", DEFAULT_WINDOW_HEIGHT);

            try {
                workspaceProps.setProperty("mainFrame", objectMapper.writeValueAsString(mainFrame));
            } catch (Exception e) {
                logger.error("Error creating default window settings: {}", e.getMessage());
            }
        }
    }

    @Override
    public GitRepo getRepo() {
        return repo;
    }

    public String getBuildCommand() {
        return projectProps.getProperty("buildCommand");
    }

    public void setBuildCommand(String command) {
        projectProps.setProperty("buildCommand", command);
        saveProjectProperties();
    }

    /**
     * Saves project-specific properties (buildCommand, cpg_refresh)
     */
    public void saveProjectProperties() {
        saveProperties(propertiesFile, projectProps, "Brokk project configuration");
    }

    /**
     * Saves workspace-specific properties (window positions, etc.)
     */
    public void saveWorkspaceProperties() {
        saveProperties(workspacePropertiesFile, workspaceProps, "Brokk workspace configuration");
    }

    /**
     * Generic method to save properties to a file
     */
    private void saveProperties(Path file, Properties properties, String comment) {
        try {
            // Check if properties file exists
            if (Files.exists(file)) {
                // Load existing properties to compare
                Properties existingProps = new Properties();
                try (var reader = Files.newBufferedReader(file)) {
                    existingProps.load(reader);
                }

                // Compare properties - only save if different
                if (propsEqual(existingProps, properties)) {
                    return; // Skip saving if properties are identical
                }
            }

            // Use atomic save method
            FileUtil.atomicSaveProperties(file, properties, comment);
        } catch (IOException e) {
            logger.error("Error saving properties to {}: {}", file, e.getMessage());
        }
    }

    /**
     * Compares two Properties objects to see if they have the same key-value pairs
     * @return true if properties are equal
     */
    private boolean propsEqual(Properties p1, Properties p2) {
        if (p1.size() != p2.size()) {
            return false;
        }

        return p1.entrySet().stream()
                .allMatch(e -> {
                    String key = (String) e.getKey();
                    String value = (String) e.getValue();
                    return value.equals(p2.getProperty(key));
                });
    }

    public Path getRoot() {
        return root;
    }

    public enum CpgRefresh {
    AUTO,
    MANUAL,
    UNSET
}

/**
 * Check if .brokk entries exist in .gitignore
 * @return true if .gitignore contains entries for .brokk
 */
public boolean isGitIgnoreSet() {
    try {
        var gitignorePath = root.resolve(".gitignore");
        if (Files.exists(gitignorePath)) {
            var content = Files.readString(gitignorePath);
            return content.contains(".brokk/") || content.contains(".brokk/**");
        }
    } catch (IOException e) {
        logger.error("Error checking .gitignore: {}", e.getMessage());
    }
    return false;
}

    public CpgRefresh getCpgRefresh() {
        String value = projectProps.getProperty("cpg_refresh");
        if (value == null) {
            return CpgRefresh.UNSET;
        }
        try {
            return CpgRefresh.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CpgRefresh.UNSET;
        }
    }

    public void setCpgRefresh(CpgRefresh value) {
        assert value != null;
        projectProps.setProperty("cpg_refresh", value.name());
        saveProjectProperties();
    }

    public String getStyleGuide() {
        try {
            if (Files.exists(styleGuidePath)) {
                return Files.readString(styleGuidePath);
            }
        } catch (IOException e) {
            logger.error("Error reading style guide: {}", e.getMessage());
        }
        return "";
    }

    public void saveStyleGuide(String styleGuide) {
        try {
            Files.createDirectories(styleGuidePath.getParent());
            FileUtil.atomicOverwrite(styleGuidePath, styleGuide);
        } catch (IOException e) {
            logger.error("Error saving style guide: {}", e.getMessage());
        }
    }

    /**
     * Saves a serialized Context object to the workspace properties
     */
    public void saveContext(Context context) {
        try {
            byte[] serialized = Context.serialize(context);
            String encoded = java.util.Base64.getEncoder().encodeToString(serialized);
            workspaceProps.setProperty("context", encoded);
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving context: {}", e.getMessage());
        }
    }

    /**
     * Loads a serialized Context object from the workspace properties
     * @return The loaded Context, or null if none exists
     */
    public Context loadContext(IContextManager contextManager, String welcomeMessage) {
        try {
            String encoded = workspaceProps.getProperty("context");
            if (encoded != null && !encoded.isEmpty()) {
                byte[] serialized = java.util.Base64.getDecoder().decode(encoded);
                return Context.deserialize(serialized, welcomeMessage).withContextManager(contextManager);
            }
        } catch (Exception e) {
            logger.error("Error loading context: {}", e.getMessage());
            clearSavedContext();
        }
        return null;
    }

    private void clearSavedContext() {
        workspaceProps.remove("context");
        saveWorkspaceProperties();
        logger.debug("Cleared saved context from workspace properties");
    }

    /**
     * Saves a list of text history items to workspace properties
     * @param historyItems The list of text history items to save (newest first)
     * @param maxItems Maximum number of items to store (older items are trimmed)
     */
    public void saveTextHistory(List<String> historyItems, int maxItems) {
        try {
            // Limit the list to the specified maximum size
            var limitedItems = historyItems.stream()
                    .limit(maxItems)
                    .collect(Collectors.toList());

            // Convert to JSON and store in properties
            String json = objectMapper.writeValueAsString(limitedItems);
            workspaceProps.setProperty("textHistory", json);
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving text history: {}", e.getMessage());
        }
    }

    /**
     * Loads the saved text history items
     * @return List of text history items (newest first), or empty list if none found
     */
    public List<String> loadTextHistory() {
        try {
            String json = workspaceProps.getProperty("textHistory");
            if (json != null && !json.isEmpty()) {
                List<String> result = objectMapper.readValue(json,
                                                             objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                logger.debug("Loaded {} history items", result.size());
                return result;
            }
        } catch (Exception e) {
            logger.error("Error loading text history: {}", e.getMessage(), e);
        }
        logger.debug("No text history found, returning empty list");
        return new ArrayList<>();
    }

    /**
     * Adds a new item to the text history, maintaining the maximum size
     * @param item New item to add to history
     * @param maxItems Maximum history size
     * @return The updated history list
     */
    public List<String> addToTextHistory(String item, int maxItems) {
        if (item == null || item.trim().isEmpty()) {
            return loadTextHistory(); // Don't add empty items
        }

        var history = new ArrayList<>(loadTextHistory());

        // Remove item if it already exists to avoid duplicates
        history.removeIf(i -> i.equals(item));

        // Add the new item at the beginning (newest first)
        history.addFirst(item);

        // Trim to max size
        if (history.size() > maxItems) {
            history = new ArrayList<>(history.subList(0, maxItems));
        }

        // Save and return the updated list
        saveTextHistory(history, maxItems);
        return history;
    }

    /**
     * Returns the LLM API keys stored in ~/.brokk/config/keys.properties
     * @return Map of key names to values, or empty map if file doesn't exist
     */
    public Map<String, String> getLlmKeys() {
        Map<String, String> keys = new HashMap<>();
        var keysPath = getLlmKeysPath();

        if (Files.exists(keysPath)) {
            try (var reader = Files.newBufferedReader(keysPath)) {
                Properties keyProps = new Properties();
                keyProps.load(reader);
                for (String name : keyProps.stringPropertyNames()) {
                    keys.put(name, keyProps.getProperty(name));
                }
            } catch (IOException e) {
                logger.error("Error loading LLM keys: {}", e.getMessage());
            }
        }

        return keys;
    }

    public static Path getLlmKeysPath() {
        return Path.of(System.getProperty("user.home"), ".config", "brokk", "keys.properties");
    }

    /**
     * Save a window's position and size
     * @param key identifier for the window
     * @param window the window to save position for
     */
    public void saveWindowBounds(String key, JFrame window) {
        if (window == null || !window.isDisplayable() ||
                window.getExtendedState() != java.awt.Frame.NORMAL) {
            return;
        }

        try {
            var node = objectMapper.createObjectNode();
            node.put("x", window.getX());
            node.put("y", window.getY());
            node.put("width", window.getWidth());
            node.put("height", window.getHeight());

            workspaceProps.setProperty(key, objectMapper.writeValueAsString(node));
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving window bounds: {}", e.getMessage());
        }
    }

    /**
     * Get the saved window bounds as a Rectangle
     * @param key identifier for the window
     * @param defaultWidth default width if not found
     * @param defaultHeight default height if not found
     * @return Rectangle with the window bounds
     */
    public java.awt.Rectangle getWindowBounds(String key, int defaultWidth, int defaultHeight) {
        var result = new java.awt.Rectangle(-1, -1, defaultWidth, defaultHeight);

        try {
            String json = workspaceProps.getProperty(key);
            if (json != null) {
                var node = objectMapper.readValue(json, ObjectNode.class);

                if (node.has("width") && node.has("height")) {
                    result.width = node.get("width").asInt();
                    result.height = node.get("height").asInt();
                }

                if (node.has("x") && node.has("y")) {
                    result.x = node.get("x").asInt();
                    result.y = node.get("y").asInt();
                }
            }
        } catch (Exception e) {
            logger.error("Error reading window bounds: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Gets the saved main window bounds
     */
    public java.awt.Rectangle getMainWindowBounds() {
        return getWindowBounds("mainFrame", DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
    }

    /**
     * Gets the saved preview window bounds
     */
    public java.awt.Rectangle getPreviewWindowBounds() {
        return getWindowBounds("previewFrame", 600, 400);
    }

    /**
     * Gets the saved diff window bounds
     */
    public java.awt.Rectangle getDiffWindowBounds() {
        return getWindowBounds("diffFrame", 900, 600);
    }

    /**
     * Save main window bounds
     */
    public void saveMainWindowBounds(JFrame window) {
        saveWindowBounds("mainFrame", window);
    }

    /**
     * Save preview window bounds
     */
    public void savePreviewWindowBounds(JFrame window) {
        saveWindowBounds("previewFrame", window);
    }

    /**
     * Save diff window bounds
     */
    public void saveDiffWindowBounds(JDialog dialog) {
        if (dialog == null || !dialog.isDisplayable()) {
            return;
        }

        try {
            var node = objectMapper.createObjectNode();
            node.put("x", dialog.getX());
            node.put("y", dialog.getY());
            node.put("width", dialog.getWidth());
            node.put("height", dialog.getHeight());

            workspaceProps.setProperty("diffFrame", objectMapper.writeValueAsString(node));
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving diff window bounds: {}", e.getMessage());
        }
    }

    /**
     * Store the GitHub token in workspace properties.
     */
    public void setGitHubToken(String token)
    {
        workspaceProps.setProperty("githubToken", token);
        saveWorkspaceProperties();
    }

    /**
     * Retrieve the GitHub token from workspace properties (may be null).
     */
    public String getGitHubToken()
    {
        return workspaceProps.getProperty("githubToken");
    }

    /**
     * Save vertical split pane position
     */
    public void saveVerticalSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("verticalSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get vertical split pane position
     */
    public int getVerticalSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("verticalSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Save history split pane position
     */
    public void saveHistorySplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("historySplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get history split pane position
     */
    public int getHistorySplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("historySplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    /**
     * Save context/git split pane position
     */
    public void saveContextGitSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("contextGitSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get context/git split pane position
     */
    public int getContextGitSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("contextGitSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Gets the current UI theme (dark or light)
     * @return "dark" or "light" (defaults to "dark" if not set)
     */
    public String getTheme() {
        return workspaceProps.getProperty("theme", "light");
    }

    /**
     * Sets the UI theme
     * @param theme "dark" or "light"
     */
    public void setTheme(String theme) {
        workspaceProps.setProperty("theme", theme);
        saveWorkspaceProperties();
    }

    public void saveLlmKeys(Map<String, String> keys) {
        var keysPath = getLlmKeysPath();

        try {
            Properties keyProps = new Properties();
            keys.forEach(keyProps::setProperty);
            FileUtil.atomicSaveProperties(keysPath, keyProps, "Brokk LLM API keys");
        } catch (IOException e) {
            logger.error("Error saving LLM keys: {}", e.getMessage());
        }
    }

    public void rebuildAnalyzer() {
        analyzerWrapper.requestRebuild();
    }

    /**
     * Gets the analyzer, blocking if necessary while it's being built
     */
    @Override
    public IAnalyzer getAnalyzer() {
        return analyzerWrapper.get();
    }

    /**
     * Gets the analyzer without blocking, may return null if not available
     */
    @Override
    public IAnalyzer getAnalyzerNonBlocking() {
        return analyzerWrapper.getNonBlocking();
    }

    private static final Path RECENT_PROJECTS_PATH = Path.of(System.getProperty("user.home"),
                                                             ".config", "brokk", "projects.properties");

    /**
     * Reads the recent projects list from ~/.config/brokk/projects.properties.
     * Returns a map of projectPath -> lastOpenedMillis.
     * If the file doesn't exist or can't be read, returns an empty map.
     */
    public static Map<String, Long> loadRecentProjects() {
        var result = new HashMap<String, Long>();
        if (!Files.exists(RECENT_PROJECTS_PATH)) {
            return result;
        }

        var props = new Properties();
        try (var reader = Files.newBufferedReader(RECENT_PROJECTS_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            logger.warn("Unable to read recent projects file: {}", e.getMessage());
            return result;
        }

        for (String key : props.stringPropertyNames()) {
            try {
                var value = Long.parseLong(props.getProperty(key));
                result.put(key, value);
            } catch (NumberFormatException nfe) {
                logger.warn("Invalid timestamp for key {} in projects.properties", key);
            }
        }
        return result;
    }

    /**
     * Saves the given map of projectPath -> lastOpenedMillis to
     * ~/.config/brokk/projects.properties, trimming to the 10 most recent.
     */
    public static void saveRecentProjects(Map<String, Long> projects) {
        // Sort entries by lastOpened descending
        var sorted = projects.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .toList();

        var props = new Properties();
        for (var e : sorted) {
            props.setProperty(e.getKey(), Long.toString(e.getValue()));
        }

        try {
            FileUtil.atomicSaveProperties(RECENT_PROJECTS_PATH, props, "Recently opened Brokk projects");
        } catch (IOException e) {
            logger.error("Error saving recent projects: {}", e.getMessage());
        }
    }

    /**
     * Updates the projects.properties with a single entry for the given directory path,
     * setting last opened to the current time.
     */
    public static void updateRecentProject(Path projectDir) {
        var abs = projectDir.toAbsolutePath().toString();
        var currentMap = loadRecentProjects();
        currentMap.put(abs, System.currentTimeMillis());
        saveRecentProjects(currentMap);
    }
}
