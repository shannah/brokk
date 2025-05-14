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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class Environment {
    private static final Logger logger = LogManager.getLogger(Environment.class);
    public static final Environment instance = new Environment();

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
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
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
                if (SystemTray.isSupported()) {
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

    private void sendLinuxNotification(String message) throws IOException {
        runCommandFireAndForget("notify-send", "--category", "Brokk", message);
    }
    
    private void sendSystemTrayNotification(String message) {
        SystemTray tray = SystemTray.getSystemTray();
        var iconUrl = Chrome.class.getResource(Brokk.ICON_RESOURCE);
        assert iconUrl != null;
        var image = new ImageIcon(iconUrl).getImage();
        TrayIcon trayIcon = new TrayIcon(image, "Brokk"); // Tooltip is the title
        trayIcon.setImageAutoSize(true);

        try {
            tray.add(trayIcon);
            trayIcon.displayMessage("Brokk", message, TrayIcon.MessageType.INFO);
            // The tray icon will be removed by the system or when the application exits.
            // For temporary notifications, some systems auto-remove. If it lingers,
            // a mechanism to remove it after a delay might be needed, but adds complexity.
            // Let's keep it simple for now.
        } catch (AWTException e) {
            logger.warn("Could not add TrayIcon for notification: {}", e.getMessage());
        }
    }

    private void runCommandFireAndForget(String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        logger.debug("running command: {}", String.join(" ", command));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }
}
