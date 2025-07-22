package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;

/**
 * Smart hybrid file comparison that uses synchronous processing for small files
 * and asynchronous processing only for large files that benefit from background computation.
 * 
 * This replaces the over-engineered SwingWorker approach with intelligent decision-making
 * based on file size and content type.
 */
public class HybridFileComparison {
    private static final Logger logger = LogManager.getLogger(HybridFileComparison.class);
    
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
        
        long leftSize = estimateSize(leftSource);
        long rightSize = estimateSize(rightSource);
        long maxSize = Math.max(leftSize, rightSize);
        
        logger.debug("Estimated sizes: left={}, right={}, max={}", leftSize, rightSize, maxSize);
        
        if (maxSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES) {
            logger.info("Using async processing for large files: {} bytes", maxSize);
            createAsyncDiffPanel(leftSource, rightSource, mainPanel, theme, contextManager, isMultipleCommitsContext, fileIndex);
        } else {
            logger.debug("Using sync processing for normal files: {} bytes", maxSize);
            createSyncDiffPanel(leftSource, rightSource, mainPanel, theme, contextManager, isMultipleCommitsContext, fileIndex);
        }
    }
    
    /**
     * Synchronous diff creation for small files - faster and simpler.
     */
    private static void createSyncDiffPanel(BufferSource leftSource, BufferSource rightSource,
                                          BrokkDiffPanel mainPanel, GuiTheme theme,
                                          ContextManager contextManager, boolean isMultipleCommitsContext,
                                          int fileIndex) {
        
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
                
                // Display immediately  
                var resizedIcon = FileComparisonHelper.getScaledIcon();
                mainPanel.getTabbedPane().addTab(panel.getTitle(), resizedIcon, panel);
                mainPanel.getTabbedPane().setSelectedComponent(panel);
                panel.applyTheme(theme);
                
                logger.debug("Sync diff panel created successfully");
                
            } catch (Exception ex) {
                logger.error("Error creating sync diff panel", ex);
                mainPanel.getConsoleIO().toolError("Error creating diff: " + ex.getMessage(), "Error");
            }
        });
    }
    
    /**
     * Asynchronous diff creation for large files - prevents UI blocking.
     */
    private static void createAsyncDiffPanel(BufferSource leftSource, BufferSource rightSource,
                                           BrokkDiffPanel mainPanel, GuiTheme theme,
                                           ContextManager contextManager, boolean isMultipleCommitsContext,
                                           int fileIndex) {
        
        // Use the existing FileComparison for large files
        var fileComparison = new FileComparison.FileComparisonBuilder(mainPanel, theme, contextManager)
                .withSources(leftSource, rightSource)
                .setMultipleCommitsContext(isMultipleCommitsContext)
                .build();
        
        fileComparison.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName()) && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                mainPanel.handleFileComparisonResult(evt, fileIndex);
            }
        });
        
        fileComparison.execute();
    }
    
    /**
     * Estimates the content size for a BufferSource.
     * Uses heuristics for different source types.
     */
    private static long estimateSize(BufferSource source) {
        if (source instanceof BufferSource.FileSource fileSource) {
            return fileSource.file().length();
        } else if (source instanceof BufferSource.StringSource stringSource) {
            // String content is already in memory, estimate based on character count
            return stringSource.content().length() * 2L; // Approximate bytes (UTF-16)
        }
        
        // Conservative default - assume small
        return 1024; // 1KB
    }
}