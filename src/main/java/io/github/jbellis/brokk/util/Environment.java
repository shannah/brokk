package io.github.jbellis.brokk.util;


import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class Environment {
    public static final Environment instance = new Environment();

    private Environment() {
    }
    
    /**
     * Runs a shell command using the appropriate shell for the current OS, returning {stdout, stderr}.
     */
    public ProcessResultInternal runShellCommand(String command) throws IOException {
        Process process;
        if (isWindows()) {
            process = createProcessBuilder("cmd.exe", "/c", command).start();
        } else {
            process = createProcessBuilder("/bin/sh", "-c", command).start();
        }

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

        int exitCode;
        while (true) {
            try {
                if (process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    break;
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            } catch (InterruptedException e) {
                process.destroy();
                return new ProcessResultInternal(Integer.MIN_VALUE, "", "");
            }
        }
        return new ProcessResultInternal(exitCode, out.toString(), err.toString());
    }

    private static ProcessBuilder createProcessBuilder(String... command) {
        var pb = new ProcessBuilder(command);
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
}
