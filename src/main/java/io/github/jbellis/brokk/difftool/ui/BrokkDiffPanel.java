package io.github.jbellis.brokk.difftool.ui;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.concurrent.ExecutionException;

public class BrokkDiffPanel extends JPanel implements PropertyChangeListener {
    private final JTabbedPane tabbedPane;
    private boolean started;
    private final JLabel loadingLabel = new JLabel("Processing... Please wait.");
    private final File leftFile;
    private final File rightFile;
    private final String contentLeft;
    private final String contentRight;
    private final String leftFileTitle;
    private final String rightFileTitle;
    private final boolean isTwoFilesComparison;
    private final boolean isStringAndFileComparison;
    private final boolean isDarkTheme;

    public boolean isTwoFilesComparison() {
        return isTwoFilesComparison;
    }

    public boolean isStringAndFileComparison() {
        return isStringAndFileComparison;
    }


    public BrokkDiffPanel(Builder builder) {
        this.leftFile = builder.leftFile;
        this.rightFile = builder.rightFile;
        this.contentLeft = builder.contentLeft;
        this.contentRight = builder.contentRight;
        this.leftFileTitle = builder.leftFileTitle;
        this.rightFileTitle = builder.rightFileTitle;
        this.isTwoFilesComparison = builder.isTwoFilesComparison;
        this.isStringAndFileComparison = builder.isStringAndFileComparison;
        this.isDarkTheme = builder.isDarkTheme;

        // Make the container focusable, so it can handle key events
        setFocusable(true);
        tabbedPane = new JTabbedPane();
        // Add an AncestorListener to trigger 'start()' when the panel is added to a container
        addAncestorListener(new AncestorListener() {
            public void ancestorAdded(AncestorEvent event) {
                start();
            }

            public void ancestorMoved(AncestorEvent event) {
            }

            public void ancestorRemoved(AncestorEvent event) {
            }
        });

        revalidate();
    }

    // Builder Class
    public static class Builder {
        private File leftFile;
        private File rightFile;
        private String contentLeft;
        private String contentRight;
        private String leftFileTitle = "";
        private String rightFileTitle = "";
        private boolean isTwoFilesComparison = false;
        private boolean isStringAndFileComparison = false;
        private boolean isDarkTheme = false; // Default to light theme

        // Compare two files
        public Builder compareFiles(File leftFile, String leftFileTitle, File rightFile, String rightFileTitle) {
            this.leftFile = leftFile;
            this.rightFile = rightFile;
            this.leftFileTitle = leftFileTitle;
            this.rightFileTitle = rightFileTitle;
            this.isTwoFilesComparison = true;
            return this;
        }

        // Compare a string and a file
        public Builder compareStringAndFile(String contentLeft, String contentLeftTitle, File rightFile, String rightFileTitle) {
            this.contentLeft = contentLeft;
            this.leftFileTitle = contentLeftTitle;
            this.rightFile = rightFile;
            this.rightFileTitle = rightFileTitle;
            this.isStringAndFileComparison = true;
            return this;
        }


        // Compare a string and a file
        public Builder compareStringAndFileStringOnTheRight(File leftFile, String leftFileTitle, String contentRight, String contentRightTitle) {
            this.contentRight = contentRight;
            this.rightFileTitle = contentRightTitle;
            this.leftFile = leftFile;
            this.leftFileTitle = leftFileTitle;
            this.isStringAndFileComparison = true;
            return this;
        }

        public Builder withTheme(boolean isDark) {
            this.isDarkTheme = isDark;
            return this;
        }

        // Compare two strings
        public Builder compareStrings(String contentLeft, String contentLeftTitle, String contentRight, String contentRightTitle) {
            this.contentLeft = contentLeft;
            this.contentRight = contentRight;
            this.leftFileTitle = contentLeftTitle;
            this.rightFileTitle = contentRightTitle;
            return this;
        }

        public BrokkDiffPanel build() {
            return new BrokkDiffPanel(this);
        }
    }

    public JTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    private void start() {
        if (started) {
            return;
        }
        started = true;
        getTabbedPane().setFocusable(false);
        setLayout(new BorderLayout());
        launchComparison();

        add(createToolbar(), BorderLayout.NORTH);
        add(getTabbedPane(), BorderLayout.CENTER);
    }

    public JButton getBtnUndo() {
        return btnUndo;
    }

    private JButton btnUndo;

    public JButton getBtnRedo() {
        return btnRedo;
    }

    private JButton btnRedo;
    private BufferDiffPanel bufferDiffPanel;

    public void setBufferDiffPanel(BufferDiffPanel bufferDiffPanel) {
        this.bufferDiffPanel = bufferDiffPanel;
    }

    private BufferDiffPanel getBufferDiffPanel() {
        return bufferDiffPanel;
    }

    private JToolBar createToolbar() {
        // Create toolbar
        JToolBar toolBar = new JToolBar();

        // Create buttons
        JButton btnNext = new JButton("Next Change");
        JButton btnPrevious = new JButton("Previous Change");
        btnUndo = new JButton("Undo");
        btnRedo = new JButton("Redo");

        btnNext.addActionListener(e -> {
            getCurrentContentPanel().doDown();
            repaint();
        });
        btnPrevious.addActionListener(e -> {
            getCurrentContentPanel().doUp();
            repaint();
        });
        btnUndo.addActionListener(e -> {
            getCurrentContentPanel().doUndo();
            repaint();
            getBufferDiffPanel().doSave();
        });
        btnRedo.addActionListener(e -> {
            getCurrentContentPanel().doRedo();
            repaint();
            getBufferDiffPanel().doSave();
        });
        // Add buttons to toolbar with spacing
        toolBar.add(btnPrevious);
        toolBar.add(Box.createHorizontalStrut(10)); // 10px spacing
        toolBar.add(btnNext);
        toolBar.add(Box.createHorizontalStrut(20)); // 20px spacing
        toolBar.addSeparator(); // Adds space between groups
        toolBar.add(Box.createHorizontalStrut(10)); // 10px spacing
        toolBar.add(btnUndo);
        toolBar.add(Box.createHorizontalStrut(10)); // 10px spacing
        toolBar.add(btnRedo);

        return toolBar;
    }

    public void updateUndoRedoButtons() {
        if (getCurrentContentPanel() != null) {
            boolean canUndo = getCurrentContentPanel().isUndoEnabled();
            boolean canRedo = getCurrentContentPanel().isRedoEnabled();
            getBtnUndo().setEnabled(canUndo);
            getBtnRedo().setEnabled(canRedo);
        }
    }

    public void launchComparison() {
        loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.BOLD));
        add(loadingLabel, BorderLayout.SOUTH);
        revalidate();
        repaint();
        compare(); // Pass the stored parameters to compare()

    }

    private void compare() {
        FileComparison fileComparison = new FileComparison.FileComparisonBuilder(this)
                .withComparisonType(isTwoFilesComparison, isStringAndFileComparison)
                .withFiles(leftFile, leftFileTitle, rightFile, rightFileTitle)
                .withStringAndFile(contentLeft, leftFileTitle, rightFile, rightFileTitle)
                .withStringAndFile(leftFile, leftFileTitle, contentRight, rightFileTitle)
                .withStrings(contentLeft, leftFileTitle, contentRight, rightFileTitle)
                .withTheme(this.isDarkTheme) // Pass theme state
                .build();

        fileComparison.addPropertyChangeListener(this);
        fileComparison.execute();
    }


    public AbstractContentPanel getCurrentContentPanel() {
        return (AbstractContentPanel) getTabbedPane().getSelectedComponent();
    }


    public void propertyChange(PropertyChangeEvent evt) {
        if ("state".equals(evt.getPropertyName()) &&
                SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
            try {
                String result = (String) ((SwingWorker<?, ?>) evt.getSource()).get();
                if (result != null) {
                    compare(); // Ensure compare() gets the correct parameters
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            } finally {
                remove(loadingLabel);
                revalidate();
                repaint();
            }
        }
    }

    /**
     * Shows the diff panel in a frame.
     *
     * @param contextManager The context manager for accessing project settings (like window bounds)
     * @param title The frame title
     */
    public void showInFrame(io.github.jbellis.brokk.ContextManager contextManager, String title) {
        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(this);

        // Get saved bounds from Project
        var bounds = contextManager.getProject().getDiffWindowBounds();
        frame.setBounds(bounds);

        // Save window position and size when closing
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                contextManager.getProject().saveDiffWindowBounds(frame);
            }
        });

        frame.setVisible(true);
    }
}
