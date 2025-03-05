package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class Project {
    private final Path propertiesFile;
    private final Path root;
    private final Properties props;
    private final Path styleGuidePath;
    private final Path historyFilePath;

    private static final int DEFAULT_AUTO_CONTEXT_FILE_COUNT = 10;
    private static final int DEFAULT_WINDOW_WIDTH = 800;
    private static final int DEFAULT_WINDOW_HEIGHT = 1200;
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LogManager.getLogger(Project.class);

    public Project(Path root) {
        this.root = root;
        this.propertiesFile = root.resolve(".brokk").resolve("project.properties");
        this.styleGuidePath = root.resolve(".brokk").resolve("style.md");
        this.historyFilePath = root.resolve(".brokk").resolve("linereader.txt");
        this.props = new Properties();

        if (Files.exists(propertiesFile)) {
            try (var reader = Files.newBufferedReader(propertiesFile)) {
                props.load(reader);
            } catch (Exception e) {
                logger.error("Error loading project properties: {}", e.getMessage());
                props.clear();
            }
        }

        // Set defaults on missing or error
        if (props.isEmpty()) {
            props.setProperty("autoContextFileCount", String.valueOf(DEFAULT_AUTO_CONTEXT_FILE_COUNT));
            // Create default window positions
            var mainFrame = objectMapper.createObjectNode();
            mainFrame.put("x", -1);
            mainFrame.put("y", -1);
            mainFrame.put("width", DEFAULT_WINDOW_WIDTH);
            mainFrame.put("height", DEFAULT_WINDOW_HEIGHT);
            
            try {
                props.setProperty("mainFrame", objectMapper.writeValueAsString(mainFrame));
            } catch (Exception e) {
                logger.error("Error creating default window settings: {}", e.getMessage());
            }
        }
    }

    public String getBuildCommand() {
        return props.getProperty("buildCommand");
    }

    public void setBuildCommand(String command) {
        props.setProperty("buildCommand", command);
        saveProperties();
    }
    
    public void saveProperties() {
        try {
            // Check if properties file exists
            if (Files.exists(propertiesFile)) {
                // Load existing properties to compare
                Properties existingProps = new Properties();
                try (var reader = Files.newBufferedReader(propertiesFile)) {
                    existingProps.load(reader);
                }
                
                // Compare properties - only save if different
                if (propsEqual(existingProps, props)) {
                    return; // Skip saving if properties are identical
                }
            }
            
            Files.createDirectories(propertiesFile.getParent());
            try (var writer = Files.newBufferedWriter(propertiesFile)) {
                props.store(writer, "Brokk project configuration");
            }
        } catch (IOException e) {
            logger.error("Error saving project properties: {}", e.getMessage());
        }
    }
    
    /**
     * Compares two Properties objects to see if they have the same key-value pairs
     * @return true if properties are equal
     */
    @NotNull
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
        UNSET;
    }

    public CpgRefresh getCpgRefresh() {
        String value = props.getProperty("cpg_refresh");
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
        props.setProperty("cpg_refresh", value.name());
        saveProperties();
    }

    public String getStyleGuide() {
        try {
            if (Files.exists(styleGuidePath)) {
                return Files.readString(styleGuidePath);
            }
        } catch (IOException e) {
            logger.error("Error reading style guide: {}", e.getMessage());
        }
        return null;
    }

    public void saveStyleGuide(String styleGuide) {
        try {
            Files.createDirectories(styleGuidePath.getParent());
            Files.writeString(styleGuidePath, styleGuide);
        } catch (IOException e) {
            logger.error("Error saving style guide: {}", e.getMessage());
        }
    }
    
    /**
     * Returns the path to the command history file
     */
    public Path getHistoryFilePath() {
        return historyFilePath;
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
     * @param keys Map of key names to values
     */
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
            
            props.setProperty(key, objectMapper.writeValueAsString(node));
            saveProperties();
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
            String json = props.getProperty(key);
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
    
    public void saveLlmKeys(Map<String, String> keys) {
        var keysPath = getLlmKeysPath();

        try {
            Files.createDirectories(keysPath.getParent());
            Properties keyProps = new Properties();
            keys.forEach(keyProps::setProperty);

            try (var writer = Files.newBufferedWriter(keysPath)) {
                keyProps.store(writer, "Brokk LLM API keys");
            }
        } catch (IOException e) {
            logger.error("Error saving LLM keys: {}", e.getMessage());
        }
    }
}
