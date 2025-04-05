package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.doc.FileDocument;
import io.github.jbellis.brokk.difftool.doc.StringDocument;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class FileComparison extends SwingWorker<JMDiffNode, Object> {
    private final BrokkDiffPanel mainPanel;
    private final File leftFile;
    private final File rightFile;
    private final String contentLeft;
    private final String contentRight;
    private final String contentLeftTitle;
    private final String contentRightTitle;
    private final boolean isTwoFilesComparison;
    private final String leftFileTitle;
    private final String rightFileTitle;
    private final boolean isDarkTheme;
    private boolean isStringAndFileComparison;

    // Holds the result panel
    private BufferDiffPanel panel;

    // Constructor
    private FileComparison(FileComparisonBuilder builder) {
        this.mainPanel = builder.mainPanel;
        this.leftFile = builder.leftFile;
        this.rightFile = builder.rightFile;
        this.contentLeft = builder.contentLeft;
        this.contentRight = builder.contentRight;
        this.contentLeftTitle = builder.contentLeftTitle;
        this.contentRightTitle = builder.contentRightTitle;
        this.isTwoFilesComparison = builder.isTwoFilesComparison;
        this.isStringAndFileComparison = builder.isStringAndFileComparison;
        this.leftFileTitle = builder.leftFileTitle;
        this.rightFileTitle = builder.rightFileTitle;
        this.isDarkTheme = builder.isDarkTheme;
    }

    // Static Builder class
    public static class FileComparisonBuilder {
        private final BrokkDiffPanel mainPanel;
        private File leftFile;
        private File rightFile;
        private String contentLeft;
        private String contentRight;
        private String contentLeftTitle;
        private String contentRightTitle;
        private boolean isTwoFilesComparison;
        private boolean isStringAndFileComparison;
        private String leftFileTitle = "";
        private String rightFileTitle = "";
        private boolean isDarkTheme = false; // Default to light

        public FileComparisonBuilder(BrokkDiffPanel mainPanel) {
            this.mainPanel = mainPanel;
        }

        public FileComparisonBuilder withComparisonType(boolean isTwoFilesComparison, boolean isStringAndFileComparison) {
            this.isTwoFilesComparison = isTwoFilesComparison;
            this.isStringAndFileComparison = isStringAndFileComparison;
            return this;
        }

        public FileComparisonBuilder withFiles(File leftFile, String leftFileTitle, File rightFile, String rightFileTitle) {
            if (isTwoFilesComparison) {
                this.leftFile = leftFile;
                this.leftFileTitle = leftFileTitle;
                this.rightFile = rightFile;
                this.rightFileTitle = rightFileTitle;
            }
            return this;
        }

        public FileComparisonBuilder withStringAndFile(String contentLeft, String contentLeftTitle, File rightFile, String rightFileTitle) {
            // Assume contentLeft is indeed the left side
            this.contentLeft = contentLeft;
            this.contentLeftTitle = contentLeftTitle;
            this.rightFile = rightFile;
            this.rightFileTitle = rightFileTitle;
            this.isStringAndFileComparison = true;
            return this;
        }

        public FileComparisonBuilder withFileAndString(File leftFile, String leftFileTitle, String contentRight, String contentRightTitle) {
            // Assume leftFile is the left side
            this.leftFile = leftFile;
            this.leftFileTitle = leftFileTitle;
            this.contentRight = contentRight;
            this.contentRightTitle = contentRightTitle;
            this.isStringAndFileComparison = true;
            return this;
        }

        public FileComparisonBuilder withStrings(String contentLeft, String contentLeftTitle, String contentRight, String contentRightTitle) {
            if (!isTwoFilesComparison && !isStringAndFileComparison) {
                this.contentLeft = contentLeft;
                this.contentLeftTitle = contentLeftTitle;
                this.contentRight = contentRight;
                this.contentRightTitle = contentRightTitle;
            }
            return this;
        }

        public FileComparisonBuilder withTheme(boolean isDark) {
            this.isDarkTheme = isDark;
            return this;
        }

        public FileComparison build() {
            return new FileComparison(this);
        }
    }

    public BufferDiffPanel getPanel() {
        return panel;
    }

    @Override
    public JMDiffNode doInBackground() {
        // This method now solely focuses on creating the correct BufferDocumentIF instances
        // and returning them wrapped in a JMDiffNode.
        // The actual diffing happens in BufferDiffPanel after this worker completes.

        BufferDocumentIF leftDoc = null;
        BufferDocumentIF rightDoc = null;
        String comparisonTitle = "Diff"; // Default title

        try {
            if (isTwoFilesComparison) {
                if (leftFile == null || rightFile == null) throw new IllegalArgumentException("One or both files are null for file comparison.");
                leftDoc = new FileDocument(leftFile, leftFileTitle);
                rightDoc = new FileDocument(rightFile, rightFileTitle);
                comparisonTitle = leftFileTitle + " vs " + rightFileTitle;
            } else if (isStringAndFileComparison) {
                if (contentLeft != null && rightFile != null) { // String on Left, File on Right
                    leftDoc = new StringDocument(contentLeft, contentLeftTitle, false); // Assume editable string?
                    rightDoc = new FileDocument(rightFile, rightFileTitle);
                    comparisonTitle = contentLeftTitle + " vs " + rightFileTitle;
                } else if (leftFile != null && contentRight != null) { // File on Left, String on Right
                    leftDoc = new FileDocument(leftFile, leftFileTitle);
                    rightDoc = new StringDocument(contentRight, contentRightTitle, false); // Assume editable string?
                    comparisonTitle = leftFileTitle + " vs " + contentRightTitle;
                } else {
                    throw new IllegalArgumentException("Invalid combination for string/file comparison.");
                }
            } else { // Two Strings comparison
                if (contentLeft == null || contentRight == null) throw new IllegalArgumentException("One or both content strings are null for string comparison.");
                leftDoc = new StringDocument(contentLeft, contentLeftTitle, false); // Assume editable
                rightDoc = new StringDocument(contentRight, contentRightTitle, false); // Assume editable
                comparisonTitle = contentLeftTitle + " vs " + contentRightTitle;
            }

            // Return the JMDiffNode containing the documents and title
            return new JMDiffNode(comparisonTitle, leftDoc, rightDoc);
        } catch (Exception e) {
            // Log error and potentially signal failure by returning null or throwing
            System.err.println("Error preparing documents for comparison: " + e.getMessage());
            // Propagate error to done() method by throwing RuntimeException
            throw new RuntimeException("Failed to create documents for comparison", e);
        }
    }

    private static ImageIcon getScaledIcon() {
        try {
            BufferedImage originalImage = ImageIO.read(Objects.requireNonNull(FileComparison.class.getResource("/images/compare.png")));
            Image scaledImage = originalImage.getScaledInstance(24, 24, Image.SCALE_SMOOTH);
            return new ImageIcon(scaledImage);
        } catch (IOException | NullPointerException e) {
            System.err.println("Image not found: " + "/images/compare.png" + ": " + e.getMessage());
            return null; // Return null if icon fails to load
        }
    }

    @Override
    protected void done() {
        try {
            JMDiffNode diffInput = get(); // Get the result from doInBackground()
            if (diffInput != null) {
                panel = new BufferDiffPanel(mainPanel, isDarkTheme); // Pass theme state
                panel.setDiffInput(diffInput); // Set the input node (which triggers diff)
                ImageIcon resizedIcon = getScaledIcon();
                mainPanel.getTabbedPane().addTab(panel.getTitle(), resizedIcon, panel);
                mainPanel.getTabbedPane().setSelectedComponent(panel);
            } else {
                // This case should ideally not happen if doInBackground throws on error
                JOptionPane.showMessageDialog(mainPanel, "Failed to prepare comparison.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ex) {
            // Handle exceptions during the 'done' phase, including those propagated from doInBackground()
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            System.err.println("Error completing file comparison task: " + cause.getMessage());
            JOptionPane.showMessageDialog(mainPanel, "Error during comparison: " + cause.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
