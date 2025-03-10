package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Panel for showing Git-related information and actions
 */
public class GitPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(GitPanel.class);

    // Parent references
    private final Chrome chrome;
    private final ContextManager contextManager;

    // UI Components
    private JTable uncommittedFilesTable;
    private JButton suggestCommitButton;

    /**
     * Constructor for the Git panel
     */
    public GitPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        // Create panel for uncommitted changes with a suggest commit button to the right
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Uncommitted Changes",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Create table for uncommitted files - fixed to 3 rows with scrollbar
        uncommittedFilesTable = new JTable(new DefaultTableModel(
                new Object[]{"Filename", "Path"}, 0));
        uncommittedFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        uncommittedFilesTable.setRowHeight(18);

        // Set column widths
        uncommittedFilesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        uncommittedFilesTable.getColumnModel().getColumn(1).setPreferredWidth(450);

        // Create a scroll pane with fixed height of 3 rows plus header and scrollbar
        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        int tableRowHeight = uncommittedFilesTable.getRowHeight();
        int headerHeight = 22; // Approximate header height
        int scrollbarHeight = 3; // Extra padding for scrollbar
        uncommittedScrollPane.setPreferredSize(new Dimension(600, (tableRowHeight * 3) + headerHeight + scrollbarHeight));

        // Create the suggest commit button panel on the right
        var commitButtonPanel = new JPanel(new BorderLayout());
        suggestCommitButton = new JButton("Suggest Commit");
        suggestCommitButton.setEnabled(false);
        suggestCommitButton.setMnemonic(KeyEvent.VK_C);
        suggestCommitButton.setToolTipText("Suggest a commit message for the uncommitted changes");
        suggestCommitButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            chrome.currentUserTask = contextManager.performCommitActionAsync();
        });

        // Add button to panel
        commitButtonPanel.add(suggestCommitButton, BorderLayout.NORTH);

        // Add table and button to the panel
        add(uncommittedScrollPane, BorderLayout.CENTER);
        add(commitButtonPanel, BorderLayout.EAST);
    }

    /**
     * Updates the uncommitted files table and the state of the suggest commit button
     */
    public void updateSuggestCommitButton() {
        assert SwingUtilities.isEventDispatchThread();
        if (contextManager == null) {
            suggestCommitButton.setEnabled(false);
            return;
        }

        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            try {
                List<String> uncommittedFiles = contextManager.getProject().getRepo().getUncommittedFileNames();
                SwingUtilities.invokeLater(() -> {
                    DefaultTableModel model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    model.setRowCount(0);

                    if (uncommittedFiles.isEmpty()) {
                        suggestCommitButton.setEnabled(false);
                    } else {
                        for (String filePath : uncommittedFiles) {
                            // Split into filename and path
                            int lastSlash = filePath.lastIndexOf('/');
                            String filename = (lastSlash >= 0) ? filePath.substring(lastSlash + 1) : filePath;
                            String path = (lastSlash >= 0) ? filePath.substring(0, lastSlash) : "";

                            model.addRow(new Object[]{filename, path});
                        }
                        suggestCommitButton.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                // Handle exception in case the repo is no longer available
                SwingUtilities.invokeLater(() -> {
                    suggestCommitButton.setEnabled(false);
                });
            }
            return null;
        });
    }

    /**
     * Set the preferred size of the suggest commit button to match the context panel buttons
     */
    public void setSuggestCommitButtonSize(Dimension preferredSize) {
        suggestCommitButton.setPreferredSize(preferredSize);
        suggestCommitButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
    }
}
