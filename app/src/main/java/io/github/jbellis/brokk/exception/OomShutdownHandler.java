package io.github.jbellis.brokk.exception;

import io.github.jbellis.brokk.ExceptionReporter;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.util.LowMemoryWatcherManager;
import java.lang.Thread.UncaughtExceptionHandler;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OomShutdownHandler implements UncaughtExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(OomShutdownHandler.class);

    @Nullable
    private volatile ExceptionReporter exceptionReporter;

    @Override
    public void uncaughtException(Thread t, Throwable throwable) {
        logger.error("An uncaught exception occurred on thread: {}", t.getName(), throwable);

        // Attempt to report the exception to the server
        tryReportException(throwable);

        // Check if the error is an OutOfMemoryError and handle specially
        if (isOomError(throwable)) {
            logger.error("OutOfMemoryError detected, initiating shutdown with recovery");
            shutdownWithRecovery();
        }
    }

    /**
     * Attempts to report an exception to the server. Uses lazy initialization to create the ExceptionReporter if it
     * doesn't exist yet.
     *
     * @param throwable The exception to report
     */
    private void tryReportException(Throwable throwable) {
        // Lazy initialization of exception reporter
        if (exceptionReporter == null) {
            exceptionReporter = ExceptionReporter.tryCreateFromActiveProject();
        }

        if (exceptionReporter != null) {
            try {
                exceptionReporter.reportException(throwable);
            } catch (Exception e) {
                // Catch any exceptions from the reporting process itself to prevent
                // recursive exception handling
                logger.debug("Failed to report exception to server: {}", e.getMessage());
            }
        } else {
            logger.debug(
                    "Exception reporter not available (no active project or service). Exception will not be reported to server.");
        }
    }

    public static void shutdownWithRecovery() {
        try {
            MainProject.setOomFlag();
        } catch (Exception e) {
            logger.error("Could not persist evidence of OutOfMemoryError in application properties.", e);
            // Can't do much more here.
        } finally {
            // This may or may not be shown to the user
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null,
                    "The application has run out of memory and will now shut down.\n"
                            + "On the next startup, settings will be adjusted to prevent this.",
                    "Critical Memory Error",
                    JOptionPane.ERROR_MESSAGE));

            // A short delay gives the message box a moment to appear.
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                logger.warn("Interrupted while delaying application shutdown.");
            }

            System.exit(1); // Exit with a non-zero status code
        }
    }

    public static void showRecoveryMessage() {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                null,
                String.format(
                        """
                                The application ran out of memory during the last session.
                                Any active projects have been cleared to prevent this from immediately reoccurring.
                                To launch Brokk with more allocated memory, use:
                                    jbang run --java-options -Xmx%dM brokk@brokkai/brokk
                                """,
                        LowMemoryWatcherManager.suggestedHeapSizeMb()),
                "Memory Error Recovery",
                JOptionPane.WARNING_MESSAGE));
    }

    /**
     * Helper to recursively check for OOM in the cause chain.
     *
     * @param e the given throwable
     * @return true if a {@link OutOfMemoryError} is part of the throwable cause chain.
     */
    public static boolean isOomError(Throwable e) {
        if (e instanceof OutOfMemoryError) return true;
        else if (e.getCause() == null) return false;
        else return isOomError(e.getCause());
    }
}
