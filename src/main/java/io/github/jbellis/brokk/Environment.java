package io.github.jbellis.brokk;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Environment {
    public static final Environment instance = new Environment();

    private Environment() {
    }
    
    /**
     * Runs a shell command using /bin/sh, returning {stdout, stderr}.
     */
    public ProcessResultInternal runShellCommand(String command) throws IOException {
        Process process = null;
        try {
            process = createProcessBuilder("/bin/sh", "-c", command).start();

            var out = new StringBuilder();
            var err = new StringBuilder();
            try (var scOut = new Scanner(process.getInputStream());
                 var scErr = new Scanner(process.getErrorStream()))
            {
                while (scOut.hasNextLine()) {
                    out.append(scOut.nextLine()).append("\n");
                }
                while (scErr.hasNextLine()) {
                    err.append(scErr.nextLine()).append("\n");
                }
            }

            int exitCode = process.waitFor();
            return new ProcessResultInternal(exitCode, out.toString(), err.toString());
        } catch (InterruptedException e) {
            process.destroy();
            return new ProcessResultInternal(Integer.MIN_VALUE, "", "");
        }
    }

    private static ProcessBuilder createProcessBuilder(String... command) {
        // Redirect input to /dev/null so interactive prompts fail fast
        var pb = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        // Remove environment variables that might interfere with non-interactive operation
        pb.environment().remove("EDITOR");
        pb.environment().remove("VISUAL");
        pb.environment().put("TERM", "dumb");
        return pb;
    }

    /**
     * Run a shell command, returning stdout or stderr in an OperationResult.
     */
    public ProcessResult captureShellCommand(String command) {
        ProcessResultInternal result;
        try {
            result = runShellCommand(command);
        } catch (Exception e) {
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

    public static void createDirIfNotExists(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        if (!path.toFile().mkdir()) {
            throw new IOException("mkdir failed");
        }
    }

    public record ProcessResult(String error, String output) {
        public ProcessResult {
            assert output != null : "Output cannot be null";
        }
    }

    public record ProcessResultInternal(int status, String stdout, String stderr) {}
}
