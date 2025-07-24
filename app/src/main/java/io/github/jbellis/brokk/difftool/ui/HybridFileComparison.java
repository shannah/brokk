package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smart hybrid file comparison that uses synchronous processing for small files
 * and asynchronous processing only for large files that benefit from background computation.
 *
 * This replaces the over-engineered SwingWorker approach with intelligent decision-making
 * based on file size and content type.
 */
public class HybridFileComparison {
    private static final Logger logger = LogManager.getLogger(HybridFileComparison.class);

    // Performance monitoring
    private static final AtomicLong totalSyncOperations = new AtomicLong(0);
    private static final AtomicLong totalAsyncOperations = new AtomicLong(0);
    private static final AtomicLong totalSyncTime = new AtomicLong(0);
    private static final AtomicLong totalAsyncTime = new AtomicLong(0);
    private static final AtomicInteger sizeEstimationErrors = new AtomicInteger(0);

    /**
     * Creates and displays a diff panel using the optimal sync/async strategy.
     *
     * @param leftSource left side content source
     * @param rightSource right side content source
     * @param mainPanel parent panel for display
     * @param theme GUI theme to apply
     * @param contextManager context manager for operations
     * @param isMultipleCommitsContext whether this is a multi-commit comparison
     * @param fileIndex index for caching the result panel
     */
    public static void createDiffPanel(BufferSource leftSource, BufferSource rightSource,
                                     BrokkDiffPanel mainPanel, GuiTheme theme,
                                     ContextManager contextManager, boolean isMultipleCommitsContext,
                                     int fileIndex) {

        long startTime = System.currentTimeMillis();

        SizeEstimation leftEstimation = estimateSizeIntelligent(leftSource);
        SizeEstimation rightEstimation = estimateSizeIntelligent(rightSource);

        long maxSize = Math.max(leftEstimation.estimatedBytes(), rightEstimation.estimatedBytes());
        boolean isLowConfidence = leftEstimation.confidence() == SizeConfidence.LOW ||
                                 rightEstimation.confidence() == SizeConfidence.LOW;

        logger.debug("Size estimation: left={}B ({}), right={}B ({}), max={}B",
                    leftEstimation.estimatedBytes(), leftEstimation.confidence(),
                    rightEstimation.estimatedBytes(), rightEstimation.confidence(),
                    maxSize);

        // Use async for large files OR when size estimation is uncertain
        boolean useAsync = maxSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES ||
                          (isLowConfidence && maxSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES / 4);

        if (useAsync) {
            logger.info("Using async processing: size={}B, lowConfidence={}", maxSize, isLowConfidence);
            totalAsyncOperations.incrementAndGet();
            createAsyncDiffPanel(leftSource, rightSource, mainPanel, theme, contextManager, isMultipleCommitsContext, fileIndex, startTime);
        } else {
            logger.debug("Using sync processing: size={}B", maxSize);
            totalSyncOperations.incrementAndGet();
            createSyncDiffPanel(leftSource, rightSource, mainPanel, theme, contextManager, isMultipleCommitsContext, fileIndex, startTime);
        }
    }

    /**
     * Synchronous diff creation for small files - faster and simpler.
     */
    private static void createSyncDiffPanel(BufferSource leftSource, BufferSource rightSource,
                                          BrokkDiffPanel mainPanel, GuiTheme theme,
                                          ContextManager contextManager, boolean isMultipleCommitsContext,
                                          int fileIndex, long startTime) {

        SwingUtilities.invokeLater(() -> {
            try {
                // Create diff node and compute diff synchronously
                var diffNode = FileComparisonHelper.createDiffNode(leftSource, rightSource, contextManager, isMultipleCommitsContext);
                diffNode.diff(); // Fast for small files

                // Create and configure panel
                var panel = new BufferDiffPanel(mainPanel, theme);
                panel.setDiffNode(diffNode);

                // Cache the panel
                mainPanel.cachePanel(fileIndex, panel);

                // Display using the proper method that updates navigation buttons
                mainPanel.displayAndRefreshPanel(fileIndex, panel);

                // Performance monitoring
                long elapsedTime = System.currentTimeMillis() - startTime;
                totalSyncTime.addAndGet(elapsedTime);

                if (elapsedTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS) {
                    logger.warn("Slow sync diff creation: {}ms", elapsedTime);
                } else {
                    logger.debug("Sync diff panel created successfully in {}ms", elapsedTime);
                }

            } catch (RuntimeException ex) {
                logger.error("Error creating sync diff panel", ex);
                mainPanel.getConsoleIO().toolError("Error creating diff: " + ex.getMessage(), "Error");
            }
        });
    }

    /**
     * Asynchronous diff creation for large files - prevents UI blocking.
     * Uses simple background thread instead of over-engineered SwingWorker.
     */
    private static void createAsyncDiffPanel(BufferSource leftSource, BufferSource rightSource,
                                           BrokkDiffPanel mainPanel, GuiTheme theme,
                                           ContextManager contextManager, boolean isMultipleCommitsContext,
                                           int fileIndex, long startTime) {

        var taskDescription = "Computing diff: %s".formatted(mainPanel.fileComparisons.get(fileIndex).getDisplayName());
        logger.debug("Starting async diff computation for large file: {} vs {}",
                    leftSource.title(), rightSource.title());

        contextManager.submitBackgroundTask(taskDescription, () -> {
            try {
                // Create diff node and compute diff in background
                var diffNode = FileComparisonHelper.createDiffNode(leftSource, rightSource, contextManager, isMultipleCommitsContext);

                logger.debug("Computing diff for large file in background thread");
                diffNode.diff(); // This is the potentially slow operation for large files

                // Create panel on EDT after diff computation is complete
                SwingUtilities.invokeLater(() -> {
                    try {
                        var panel = new BufferDiffPanel(mainPanel, theme);
                        panel.setDiffNode(diffNode);

                        // Cache the panel
                        mainPanel.cachePanel(fileIndex, panel);

                        // Display using the proper method that updates navigation buttons
                        mainPanel.displayAndRefreshPanel(fileIndex, panel);

                        // Performance monitoring
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        totalAsyncTime.addAndGet(elapsedTime);

                        if (elapsedTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS * 5) {
                            logger.warn("Slow async diff creation: {}ms", elapsedTime);
                        } else {
                            logger.debug("Async diff panel created successfully in {}ms", elapsedTime);
                        }

                    } catch (RuntimeException ex) {
                        logger.error("Error creating async diff panel on EDT", ex);
                        mainPanel.getConsoleIO().toolError("Error creating diff: " + ex.getMessage(), "Error");
                    }
                });

            } catch (RuntimeException ex) {
                logger.error("Error computing diff in background thread", ex);
                SwingUtilities.invokeLater(() -> {
                    mainPanel.getConsoleIO().toolError("Error computing diff: " + ex.getMessage(), "Error");
                });
            }
        });
    }

    /**
     * Intelligently estimates the content size for a BufferSource with confidence assessment.
     * Uses different strategies based on source type and available information.
     */
    private static SizeEstimation estimateSizeIntelligent(BufferSource source) {
        try {
            if (source instanceof BufferSource.FileSource fileSource) {
                var file = fileSource.file();
                if (file.exists() && file.isFile()) {
                    long fileSize = file.length();
                    // High confidence for existing files
                    return new SizeEstimation(fileSize, SizeConfidence.HIGH, "file.length()");
                } else {
                    // File doesn't exist or isn't a regular file
                    sizeEstimationErrors.incrementAndGet();
                    logger.warn("File does not exist or is not regular file: {}", file);
                    return new SizeEstimation(0L, SizeConfidence.LOW, "file not found");
                }

            } else if (source instanceof BufferSource.StringSource stringSource) {
                var content = stringSource.content();
                // Accurate size calculation for string content
                long byteSize = content.getBytes(StandardCharsets.UTF_8).length;
                return new SizeEstimation(byteSize, SizeConfidence.HIGH, "UTF-8 byte count");

            } else {
                // Unknown BufferSource type - use conservative estimate
                sizeEstimationErrors.incrementAndGet();
                logger.warn("Unknown BufferSource type: {}", source.getClass().getName());
                return new SizeEstimation(PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES / 8,
                                        SizeConfidence.LOW, "unknown source type");
            }

        } catch (RuntimeException ex) {
            sizeEstimationErrors.incrementAndGet();
            logger.error("Error estimating size for source: {}", source, ex);
            return new SizeEstimation(PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES / 4,
                                    SizeConfidence.LOW, "estimation error: " + ex.getMessage());
        }
    }

    /**
     * Gets performance statistics for monitoring and optimization.
     */
    public static String getPerformanceStats() {
        long syncOps = totalSyncOperations.get();
        long asyncOps = totalAsyncOperations.get();
        long totalOps = syncOps + asyncOps;

        if (totalOps == 0) {
            return "No operations performed yet";
        }

        long avgSyncTime = syncOps > 0 ? totalSyncTime.get() / syncOps : 0;
        long avgAsyncTime = asyncOps > 0 ? totalAsyncTime.get() / asyncOps : 0;
        int errors = sizeEstimationErrors.get();

        return String.format("""
                Hybrid File Comparison Performance:
                - Total operations: %d (%.1f%% sync, %.1f%% async)
                - Average sync time: %dms
                - Average async time: %dms
                - Size estimation errors: %d
                """,
                totalOps,
                (syncOps * 100.0 / totalOps),
                (asyncOps * 100.0 / totalOps),
                avgSyncTime,
                avgAsyncTime,
                errors);
    }

    /**
     * Resets performance counters for testing or monitoring periods.
     */
    public static void resetPerformanceStats() {
        totalSyncOperations.set(0);
        totalAsyncOperations.set(0);
        totalSyncTime.set(0);
        totalAsyncTime.set(0);
        sizeEstimationErrors.set(0);
        logger.info("Performance statistics reset");
    }

    /**
     * Represents a size estimation with confidence level and method used.
     */
    private record SizeEstimation(long estimatedBytes, SizeConfidence confidence, @Nullable String method) {}

    /**
     * Confidence level in size estimation accuracy.
     */
    private enum SizeConfidence {
        HIGH,   // Exact or very accurate (e.g., file.length(), string.getBytes().length)
        MEDIUM, // Good approximation (e.g., string.length() * avg_char_size)
        LOW     // Rough estimate or fallback (e.g., unknown types, errors)
    }
}
