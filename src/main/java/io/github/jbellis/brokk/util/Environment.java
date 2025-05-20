package io.github.jbellis.brokk.util;


import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class Environment {
    private static final Logger logger = LogManager.getLogger(Environment.class);
    public static final Environment instance = new Environment();

    private TrayIcon brokkTrayIcon = null;

    private Environment() {
    }
    
    /**
     * Runs a shell command using the appropriate shell for the current OS, returning {stdout, stderr}.
     * The command is executed in the directory specified by `root`.
     */
    public ProcessResultInternal runShellCommand(String command, Path root)
    throws IOException, InterruptedException
    {
        Process process = isWindows()
                          ? createProcessBuilder(root, "cmd.exe", "/c", command).start()
                          : createProcessBuilder(root, "/bin/sh", "-c", command).start();

        try {
            // Wait for the process to finish; this call *is* interruptible
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                // TODO need a better way to signal timed out
                return new ProcessResultInternal(-1, "", "");
            }
        } catch (InterruptedException ie) {
            process.destroyForcibly();
            throw ie;
        }

        // Once the process has exited, read its entire output safely
        var stdout = new String(process.getInputStream().readAllBytes());
        var stderr = new String(process.getErrorStream().readAllBytes());

        return new ProcessResultInternal(process.exitValue(), stdout, stderr);
    }

    private static ProcessBuilder createProcessBuilder(Path root, String... command) {
        var pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        // Redirect input from /dev/null (or NUL on Windows) so interactive prompts fail fast
        if (isWindows()) {
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
        } else {
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        }
        // Remove environment variables that might interfere with non-interactive operation
        pb.environment().remove("EDITOR");
        pb.environment().remove("VISUAL");
        pb.environment().put("TERM", "dumb");
        return pb;
    }

    /**
     * Run a shell command in the given root directory, returning stdout or stderr in an OperationResult.
     */
    public ProcessResult captureShellCommand(String command, Path root) throws InterruptedException {
        ProcessResultInternal result;
        try {
            result = runShellCommand(command, root);
        } catch (IOException e) {
            return new ProcessResult(e.getMessage(), "");
        }

        var ANSI_ESCAPE_PATTERN = "\\x1B(?:\\[[;\\d]*[ -/]*[@-~]|\\]\\d+;[^\\x07]*\\x07)";
        var stdout = result.stdout().trim().replaceAll(ANSI_ESCAPE_PATTERN, "");
        var stderr = result.stderr().trim().replaceAll(ANSI_ESCAPE_PATTERN, "");
        var combinedOut = new StringBuilder();
        if (!stdout.isEmpty()) {
            if (!stderr.isEmpty()) {
                combinedOut.append("stdout: ");
            }
            combinedOut.append(stdout);
        }
        if (!stderr.isEmpty()) {
            if (!stdout.isEmpty()) {
                combinedOut.append("\n\n").append("stderr: ");
            }
            combinedOut.append(stderr);
        }
        var output = combinedOut.toString();

        if (result.status() > 0) {
            return new ProcessResult("`%s` returned code %d".formatted(command, result.status()), output);
        }
        return new ProcessResult(null, output);
    }

    public record ProcessResult(String error, String output) {
        public ProcessResult {
            assert output != null : "Output cannot be null";
        }
    }

    public record ProcessResultInternal(int status, String stdout, String stderr) {}
    
    /**
     * Determines if the current operating system is Windows.
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win");
    }

    public static boolean isMacOs() {
        return System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("mac");
    }

    /**
     * Sends a desktop notification asynchronously.
     *
     * @param title    The title of the notification.
     * @param message  The message body of the notification.
     */
    public void sendNotificationAsync(String message) {
        CompletableFuture.runAsync(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (isSystemTrayNotificationSupported()) {
                    sendSystemTrayNotification(message);
                } else if (os.contains("linux")) {
                    sendLinuxNotification(message);
                } else {
                    logger.info("Desktop notifications not supported on this platform ({})", os);
                }
            } catch (Exception e) {
                logger.warn("Failed to send desktop notification", e);
            }
        });
    }

    private boolean isSystemTrayNotificationSupported() {
        return !isMacOs() && SystemTray.isSupported();
    }

    private void sendLinuxNotification(String message) throws IOException {
        runCommandFireAndForget("notify-send", "--category", "Brokk", message);
    }

    private synchronized void sendSystemTrayNotification(String message) {
        assert SystemTray.isSupported(); // caller is responsible for checking

        if (this.brokkTrayIcon == null) {
            // Check if SystemTray is supported before attempting to get it.
            // isSystemTrayNotificationSupported() already checks SystemTray.isSupported()
            // but this method could theoretically be called directly.
            SystemTray tray = SystemTray.getSystemTray();
            var iconUrl = Chrome.class.getResource(Brokk.ICON_RESOURCE);
            if (iconUrl == null) {
                logger.error("Brokk icon resource not found, cannot create TrayIcon.");
                return;
            }
            var image = new ImageIcon(iconUrl).getImage();
            TrayIcon newTrayIcon = new TrayIcon(image, "Brokk");
            newTrayIcon.setImageAutoSize(true);

            try {
                tray.add(newTrayIcon);
                this.brokkTrayIcon = newTrayIcon; // Assign only if add succeeds
            } catch (AWTException e) {
                logger.warn("Could not add TrayIcon to SystemTray: {}", e.getMessage());
                // brokkTrayIcon remains null, message won't be displayed via tray this time.
                // Subsequent calls will attempt to add it again.
                return; // Do not proceed to displayMessage if icon wasn't added
            }
        }

        this.brokkTrayIcon.displayMessage("Brokk", message, TrayIcon.MessageType.INFO);
    }

    private void runCommandFireAndForget(String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        logger.debug("running command: {}", String.join(" ", command));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }
}
