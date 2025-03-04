package io.github.jbellis.brokk;

import javax.swing.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Brokk is the main entry point containing the REPL.
 */
public class Brokk {
    private static Chrome io;
    private static ContextManager contextManager;
    private static Coder coder;
    private static Commands commands;

    public static void main(String[] args) {
        // Find the repository root
        Path sourceRoot;
        try {
            sourceRoot = GitRepo.instance.getRoot();
        } catch (Throwable th) {
            System.out.println("Please run Brokk from within a git repository");
            System.exit(1);
            return;
        }

        // Make sure we can create .brokk/
        var brokkDir = sourceRoot.resolve(".brokk");
        try {
            Environment.createDirIfNotExists(brokkDir);
            // Set up debug logging to .brokk/debug.log
            String logPath = brokkDir.resolve("debug.log").toAbsolutePath().toString();
            System.setProperty("logfile.path", logPath);
        } catch (IOException e) {
            System.out.println("Unable to create " + brokkDir);
            System.exit(1);
        }

        // Create the ContextManager (holds chat context, code references, etc.)
        contextManager = new ContextManager(sourceRoot);

        // Create Commands object and build command list
        commands = new Commands(contextManager);

        SwingUtilities.invokeLater(() -> {
            // Create Lanterna-based ConsoleIO
            io = new Chrome();

            // Load models early
            Models models;
            try {
                models = Models.load();
            } catch (Throwable th) {
                io.toolError("Error loading models: " + th.getMessage());
                io.toolError("AI will not be available this session");
                models = Models.disabled();
            }

            // Create the Coder
            coder = new Coder(models, io, sourceRoot, contextManager);

            // Resolve circular references
            contextManager.resolveCircularReferences(io, coder);
            commands.resolveCircularReferences(io, coder);
            io.resolveCircularReferences(contextManager, coder);

            // No additional references needed for ConsoleIO

            // Get version info
            String version;
            try {
                Properties props = new Properties();
                props.load(Brokk.class.getResourceAsStream("/version.properties"));
                version = props.getProperty("version");
            } catch (IOException | NullPointerException e) {
                version = "[unknown]";
            }

            // Show welcome message
            try (var welcomeStream = Brokk.class.getResourceAsStream("/WELCOME.md")) {
                if (welcomeStream != null) {
                    io.shellOutput(new String(welcomeStream.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            // Output initial info to the command result area
            io.shellOutput("\nEnvironment:");
            io.shellOutput("Brokk %s".formatted(version));
            io.shellOutput("Editor model: " + models.editModelName());
            io.shellOutput("Apply model: " + models.applyModelName());
            io.shellOutput("Quick model: " + models.quickModelName());
            io.shellOutput("Git repo at %s with %d files".formatted(sourceRoot, GitRepo.instance.getTrackedFiles().size()));
        });
    }
}
