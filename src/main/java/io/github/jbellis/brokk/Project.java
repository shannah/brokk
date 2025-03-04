package io.github.jbellis.brokk;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class Project {
    private final Path propertiesFile;
    private final Path root;
    private final IConsoleIO io;
    private final Properties props;
    private final Path styleGuidePath;
    private final Path historyFilePath;

    private static final int DEFAULT_AUTO_CONTEXT_FILE_COUNT = 10;

    public Project(Path root, IConsoleIO io) {
        this.root = root;
        this.propertiesFile = root.resolve(".brokk").resolve("project.properties");
        this.styleGuidePath = root.resolve(".brokk").resolve("style.md");
        this.historyFilePath = root.resolve(".brokk").resolve("linereader.txt");
        this.io = io;
        this.props = new Properties();

        if (Files.exists(propertiesFile)) {
            try (var reader = Files.newBufferedReader(propertiesFile)) {
                props.load(reader);
            } catch (Exception e) {
                io.toolErrorRaw("Error loading project properties: " + e.getMessage());
                props.clear();
            }
        }

        // Set defaults on missing or error
        if (props.isEmpty()) {
            props.setProperty("autoContextFileCount", String.valueOf(DEFAULT_AUTO_CONTEXT_FILE_COUNT));
        }
    }

    public String getBuildCommand() {
        return props.getProperty("buildCommand");
    }

    public void setBuildCommand(String command) {
        props.setProperty("buildCommand", command);
        saveProperties();
    }
    
    private void saveProperties() {
        try {
            Files.createDirectories(propertiesFile.getParent());
            try (var writer = Files.newBufferedWriter(propertiesFile)) {
                props.store(writer, "Brokk project configuration");
            }
        } catch (IOException e) {
            io.toolErrorRaw("Error saving project properties: " + e.getMessage());
        }
    }

    public IConsoleIO getIo() {
        return io;
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
            io.toolErrorRaw("Error reading style guide: " + e.getMessage());
        }
        return null;
    }

    public void saveStyleGuide(String styleGuide) {
        try {
            Files.createDirectories(styleGuidePath.getParent());
            Files.writeString(styleGuidePath, styleGuide);
        } catch (IOException e) {
            io.toolErrorRaw("Error saving style guide: " + e.getMessage());
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
                io.toolErrorRaw("Error loading LLM keys: " + e.getMessage());
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
            io.toolErrorRaw("Error saving LLM keys: " + e.getMessage());
        }
    }
}
