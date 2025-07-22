package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.node.FileNode;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.node.StringNode;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;


public class FileComparison extends SwingWorker<String, String> {
    private static final Logger logger = LogManager.getLogger(FileComparison.class);

    private final BrokkDiffPanel mainPanel;
    private final ContextManager contextManager;
    @Nullable
    private JMDiffNode diffNode;
    @Nullable
    private BufferDiffPanel panel;
    private final BufferSource leftSource;
    private final BufferSource rightSource;
    private final GuiTheme theme;
    private final boolean isMultipleCommitsContext;

    // Progress indication and cancellation support
    private final AtomicReference<JProgressBar> progressBarRef = new AtomicReference<>();
    private final AtomicReference<JButton> cancelButtonRef = new AtomicReference<>();
    private final AtomicReference<Timer> progressTimerRef = new AtomicReference<>();
    private volatile boolean estimatedLargeOperation = false;

    private FileComparison(FileComparisonBuilder builder, GuiTheme theme, ContextManager contextManager) {
        this.mainPanel = builder.mainPanel;
        this.leftSource = Objects.requireNonNull(builder.leftSource);
        this.rightSource = Objects.requireNonNull(builder.rightSource);
        this.theme = theme;
        this.contextManager = contextManager;
        this.isMultipleCommitsContext = builder.isMultipleCommitsContext;
    }

    // Static Builder class
    public static class FileComparisonBuilder {
        private final BrokkDiffPanel mainPanel;
        private final ContextManager contextManager;
        @Nullable
        private BufferSource leftSource;
        @Nullable
        private BufferSource rightSource;
        private GuiTheme theme; // Default to light
        private boolean isMultipleCommitsContext = false;

        public FileComparisonBuilder(BrokkDiffPanel mainPanel, GuiTheme theme, ContextManager contextManager) {
            this.mainPanel = mainPanel;
            this.theme = theme;
            this.contextManager = contextManager;
            this.leftSource = null; // Initialize @Nullable fields
            this.rightSource = null;
        }

        public FileComparisonBuilder withSources(BufferSource left, BufferSource right) {
            this.leftSource = left;
            this.rightSource = right;
            return this;
        }

        public FileComparisonBuilder withTheme(GuiTheme theme) {
            this.theme = theme;
            return this;
        }

        public FileComparisonBuilder setMultipleCommitsContext(boolean isMultipleCommitsContext) {
            this.isMultipleCommitsContext = isMultipleCommitsContext;
            return this;
        }

        public FileComparison build() {
            if (leftSource == null || rightSource == null) {
                throw new IllegalStateException("Both left and right sources must be provided for comparison.");
            }
            var comparison = new FileComparison(this, theme, contextManager);
            comparison.setupProgressIndicationIfNeeded();
            return comparison;
        }
    }

    @Nullable
    public BufferDiffPanel getPanel() {
        return panel;
    }

    @Override
    public @Nullable String doInBackground() {
        try {
            publish("Initializing diff comparison...");

            if (isCancelled()) {
                return "Operation cancelled";
            }

            if (diffNode == null) {
                publish("Creating diff nodes...");
                diffNode = createDiffNode(leftSource, rightSource);
            }

            if (isCancelled()) {
                return "Operation cancelled";
            }

            publish("Computing differences...");

            // For large operations, add progress indication during diff computation
            if (estimatedLargeOperation) {
                // The actual diff computation happens here - we can't easily break it down further
                // but we can at least show we're working on it
                Timer progressTimer = new Timer(500, e -> {
                    if (!isCancelled()) {
                        publish("Still computing differences...");
                    }
                });
                progressTimerRef.set(progressTimer);
                progressTimer.start();

                try {
                    diffNode.diff();
                } finally {
                    stopAndCleanupProgressTimer();
                }
            } else {
                diffNode.diff();
            }

            if (isCancelled()) {
                return "Operation cancelled";
            }

            publish("Finalizing diff panel...");
            return null;

        } catch (Exception ex) {
            logger.error("Error during diff computation", ex);
            return "Error computing diff: " + ex.getMessage();
        }
    }

    private @Nullable String getDisplayTitleForSource(BufferSource source) {
        String originalTitle = source.title();

        if (source instanceof BufferSource.FileSource) {
            return "Working Tree"; // Represent local files as "Working Tree"
        }

        // For StringSource, originalTitle is typically a commit ID or "HEAD"
        if (originalTitle.isBlank() || originalTitle.equals("HEAD") || originalTitle.startsWith("[No Parent]")) {
            return originalTitle; // Handle special markers or blank as is
        }

        // Attempt to treat as commitId and fetch message
        IGitRepo repo = contextManager.getProject().getRepo();
        if (repo instanceof GitRepo gitRepo) { // Ensure it's our GitRepo implementation
            try {
                String commitIdToLookup = originalTitle.endsWith("^") ? originalTitle.substring(0, originalTitle.length() - 1) : originalTitle;
                Optional<CommitInfo> commitInfoOpt = gitRepo.getLocalCommitInfo(commitIdToLookup);
                if (commitInfoOpt.isPresent()) {
                    return commitInfoOpt.get().message(); // This is already the short/first line
                }
            } catch (GitAPIException e) {
                // Fall through to return originalTitle
            }
        }
        return originalTitle; // Fallback to original commit ID if message not found or repo error
    }

    private JMDiffNode createDiffNode(BufferSource left, BufferSource right) {
        Objects.requireNonNull(left, "Left source cannot be null");
        Objects.requireNonNull(right, "Right source cannot be null");

        String leftDocSyntaxHint = left.title();
        if (left instanceof BufferSource.StringSource stringSourceLeft && stringSourceLeft.filename() != null) {
            leftDocSyntaxHint = stringSourceLeft.filename();
        } else if (left instanceof BufferSource.FileSource fileSourceLeft) {
            leftDocSyntaxHint = fileSourceLeft.file().getName();
        }

        String rightDocSyntaxHint = right.title();
        if (right instanceof BufferSource.StringSource stringSourceRight && stringSourceRight.filename() != null) {
            rightDocSyntaxHint = stringSourceRight.filename();
        } else if (right instanceof BufferSource.FileSource fileSourceRight) {
            rightDocSyntaxHint = fileSourceRight.file().getName();
        }

        String leftFileDisplay = leftDocSyntaxHint;
        String nodeTitle;
        if (this.isMultipleCommitsContext) {
            nodeTitle = leftFileDisplay;
        } else {
            nodeTitle = "%s (%s)".formatted(leftFileDisplay, getDisplayTitleForSource(left));
        }
        var node = new JMDiffNode(nodeTitle, true);

        if (left instanceof BufferSource.FileSource fileSourceLeft) {
            node.setBufferNodeLeft(new FileNode(leftDocSyntaxHint, fileSourceLeft.file()));
        } else if (left instanceof BufferSource.StringSource stringSourceLeft) {
            node.setBufferNodeLeft(new StringNode(leftDocSyntaxHint, stringSourceLeft.content()));
        }

        if (right instanceof BufferSource.FileSource fileSourceRight) {
            node.setBufferNodeRight(new FileNode(rightDocSyntaxHint, fileSourceRight.file()));
        } else if (right instanceof BufferSource.StringSource stringSourceRight) {
            node.setBufferNodeRight(new StringNode(rightDocSyntaxHint, stringSourceRight.content()));
        }

        return node;
    }

    private static @Nullable ImageIcon getScaledIcon() {
        try {
            BufferedImage originalImage = ImageIO.read(Objects.requireNonNull(FileComparison.class.getResource("/images/compare.png")));
            Image scaledImage = originalImage.getScaledInstance(24, 24, Image.SCALE_SMOOTH);
            return new ImageIcon(scaledImage);
        } catch (IOException | NullPointerException e) {
            return null;
        }
    }

    /**
     * Sets up progress indication if this is estimated to be a large operation.
     */
    private void setupProgressIndicationIfNeeded() {
        // Estimate if this will be a large operation based on file sizes
        long leftSize = estimateSourceSize(leftSource);
        long rightSize = estimateSourceSize(rightSource);
        long maxSize = Math.max(leftSize, rightSize);

        estimatedLargeOperation = maxSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES;

        if (estimatedLargeOperation) {
            logger.info("Setting up progress indication for large file comparison: {}B", maxSize);
            SwingUtilities.invokeLater(this::createProgressUI);
        }
    }

    /**
     * Estimates the size of a BufferSource for progress indication decisions.
     */
    private long estimateSourceSize(BufferSource source) {
        try {
            if (source instanceof BufferSource.FileSource fileSource) {
                var file = fileSource.file();
                return file.exists() ? file.length() : 0L;
            } else if (source instanceof BufferSource.StringSource stringSource) {
                return stringSource.content().length() * 2L; // Approximate UTF-16 bytes
            }
        } catch (Exception ex) {
            logger.warn("Error estimating source size", ex);
        }
        return 1024L; // Default small size
    }

    /**
     * Creates the progress UI components for large file operations.
     */
    private void createProgressUI() {
        if (!estimatedLargeOperation) return;

        // Create progress bar
        var progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setString("Preparing diff comparison...");
        progressBar.setStringPainted(true);
        progressBarRef.set(progressBar);

        // Create cancel button
        var cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            logger.info("User cancelled diff operation");
            stopAndCleanupProgressTimer(); // Clean up timer before cancelling
            cancel(true);
            hideProgressUI();
        });
        cancelButtonRef.set(cancelButton);

        // Add to the UI (e.g., status bar or a temporary panel)
        var progressPanel = new JPanel(new BorderLayout());
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(cancelButton, BorderLayout.EAST);
        progressPanel.setBorder(BorderFactory.createTitledBorder("Large File Diff Progress"));

        // Add to main panel (this is a simplified approach - in a real implementation,
        // you might want to add this to a dedicated progress area)
        mainPanel.add(progressPanel, BorderLayout.SOUTH);
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    /**
     * Stops and properly disposes the progress timer.
     */
    private void stopAndCleanupProgressTimer() {
        Timer timer = progressTimerRef.getAndSet(null);
        if (timer != null) {
            if (timer.isRunning()) {
                timer.stop();
            }
            // Swing Timer cleanup - remove all action listeners to prevent memory leaks
            var listeners = timer.getActionListeners();
            for (var listener : listeners) {
                timer.removeActionListener(listener);
            }
            logger.debug("Progress timer stopped and cleaned up");
        }
    }

    /**
     * Hides the progress UI components and cleans up timers.
     */
    private void hideProgressUI() {
        // Ensure any running timers are stopped
        stopAndCleanupProgressTimer();

        SwingUtilities.invokeLater(() -> {
            var progressBar = progressBarRef.get();

            if (progressBar != null && progressBar.getParent() != null) {
                var parent = progressBar.getParent().getParent(); // Get the progressPanel's parent
                if (parent != null) {
                    parent.remove(progressBar.getParent());
                    parent.revalidate();
                    parent.repaint();
                }
            }

            progressBarRef.set(null);
            cancelButtonRef.set(null);
        });
    }


    @Override
    protected void process(java.util.List<String> chunks) {
        // Update progress text with the latest message
        if (!chunks.isEmpty()) {
            String latestMessage = chunks.getLast();
            var progressBar = progressBarRef.get();
            if (progressBar != null) {
                progressBar.setString(latestMessage);
                logger.debug("Progress update: {}", latestMessage);
            }
        }
    }

    @Override
    protected void done() {
        // Always hide progress UI when done
        hideProgressUI();

        try {
            String result = get();
            if (result != null) {
                if (isCancelled()) {
                    logger.info("Diff operation was cancelled by user");
                    // Don't show error for user cancellation
                    if (!result.contains("cancelled")) {
                        mainPanel.getConsoleIO().toolError(result, "Error opening file");
                    }
                } else {
                    mainPanel.getConsoleIO().toolError(result, "Error opening file");
                }
            } else if (!isCancelled()) {
                // Create panel outside of invokeLater to avoid race condition
                panel = new BufferDiffPanel(mainPanel, theme);
                panel.setDiffNode(diffNode);

                var createdPanel = panel;

                SwingUtilities.invokeLater(() -> {
                    ImageIcon resizedIcon = getScaledIcon();
                    mainPanel.getTabbedPane().addTab(createdPanel.getTitle(), resizedIcon, createdPanel);
                    mainPanel.getTabbedPane().setSelectedComponent(createdPanel);
                    createdPanel.applyTheme(theme);

                    if (estimatedLargeOperation) {
                        logger.info("Large file diff completed successfully");
                    }
                });
            } else {
                logger.info("Diff operation cancelled, no panel created");
            }
        } catch (java.util.concurrent.CancellationException ex) {
            logger.info("Diff operation was cancelled");
            // Don't show error for cancellation
        } catch (Exception ex) {
            logger.error("Error finalizing comparison", ex);
            mainPanel.getConsoleIO().toolError("Error finalizing comparison: " + ex.getMessage(), "Error");
        }
    }
}
