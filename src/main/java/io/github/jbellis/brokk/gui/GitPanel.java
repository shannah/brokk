package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.GitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Panel for showing Git-related information and actions, excluding the "Log" tab
 * (which is handled by GitLogPanel).
 */
public class GitPanel extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;

    // Commit tab UI
    private JTable uncommittedFilesTable;
    private JButton suggestMessageButton;
    private JTextArea commitMessageArea;
    private JButton commitButton;
    private JButton stashButton;

    // Stash tab UI
    private JTable stashTable;
    private DefaultTableModel stashTableModel;

    // Reference to the extracted Log tab
    private GitLogPanel gitLogPanel;  // The separate class

    /**
     * Constructor for the Git panel
     */
    public GitPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Git",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        int rows = 15;
        int rowHeight = 18;
        int overhead = 100;
        int totalHeight = rows * rowHeight + overhead;
        int preferredWidth = 1000;
        Dimension panelSize = new Dimension(preferredWidth, totalHeight);
        setPreferredSize(panelSize);
        setMaximumSize(panelSize);

        // Tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();
        add(tabbedPane, BorderLayout.CENTER);

        // 1) Commit tab
        JPanel commitTab = buildCommitTab();
        tabbedPane.addTab("Commit", commitTab);

        // 2) Stash tab
        JPanel stashTab = buildStashTab();
        tabbedPane.addTab("Stash", stashTab);

        // 3) Log tab (moved into GitLogPanel)
        gitLogPanel = new GitLogPanel(chrome, contextManager);
        tabbedPane.addTab("Log", gitLogPanel);

        // After UI is built, asynchronously refresh data
        SwingUtilities.invokeLater(() -> {
            updateStashList();
            gitLogPanel.updateBranchList(); // load branches, commits, etc.
        });
    }

    /**
     * Builds the Commit tab.
     */
    private JPanel buildCommitTab() {
        JPanel commitTab = new JPanel(new BorderLayout());

        // Table for uncommitted files
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Filename", "Path"}, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) { return String.class; }
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        uncommittedFilesTable = new JTable(model);
        uncommittedFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        uncommittedFilesTable.setRowHeight(18);
        uncommittedFilesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        uncommittedFilesTable.getColumnModel().getColumn(1).setPreferredWidth(450);
        uncommittedFilesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        commitTab.add(uncommittedScrollPane, BorderLayout.CENTER);

        // Commit message + buttons at bottom
        JPanel commitBottomPanel = new JPanel(new BorderLayout());
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.add(new JLabel("Commit/Stash Description:"), BorderLayout.NORTH);

        commitMessageArea = new JTextArea(5, 50);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);
        messagePanel.add(new JScrollPane(commitMessageArea), BorderLayout.CENTER);

        commitBottomPanel.add(messagePanel, BorderLayout.CENTER);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        // "Suggest Message" button
        suggestMessageButton = new JButton("Suggest Message");
        suggestMessageButton.setToolTipText("Suggest a commit message for the selected files");
        suggestMessageButton.setEnabled(false);
        suggestMessageButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            var selectedFiles = getSelectedFilesFromTable();
            contextManager.submitBackgroundTask("Suggesting commit message", () -> {
                try {
                    String diff;
                    if (selectedFiles.isEmpty()) {
                        diff = getRepo().diff();
                    } else {
                        diff = getRepo().diffFiles(selectedFiles);
                    }
                    if (diff.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this,
                                                          "No changes to commit",
                                                          "Error",
                                                          JOptionPane.ERROR_MESSAGE);
                            chrome.enableUserActionButtons();
                        });
                        return null;
                    }
                    // Trigger LLM-based commit message generation
                    contextManager.performCommitActionAsync(diff);
                    SwingUtilities.invokeLater(chrome::enableUserActionButtons);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                                      "Error suggesting commit message: " + ex.getMessage(),
                                                      "Error", JOptionPane.ERROR_MESSAGE);
                        chrome.enableUserActionButtons();
                    });
                }
                return null;
            });
        });
        buttonPanel.add(suggestMessageButton);

        // "Stash" button
        stashButton = new JButton("Stash All");
        stashButton.setToolTipText("Save your changes to the stash");
        stashButton.setEnabled(false);
        stashButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            String message = commitMessageArea.getText().trim();
            if (message.isEmpty()) {
                chrome.enableUserActionButtons();
                return;
            }
            // Filter out comment lines
            String stashDescription = Arrays.stream(message.split("\n"))
                    .filter(line -> !line.trim().startsWith("#"))
                    .collect(Collectors.joining("\n"))
                    .trim();
            var selectedFiles = getSelectedFilesFromTable();

            contextManager.submitUserTask("Stashing changes", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    } else {
                        for (var file : selectedFiles) {
                            getRepo().add(file);
                        }
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (selectedFiles.isEmpty()) {
                            chrome.toolOutput("All changes stashed successfully");
                        } else {
                            String fileList = selectedFiles.size() <= 3
                                    ? String.join(", ", selectedFiles)
                                    : selectedFiles.size() + " files";
                            chrome.toolOutput("Stashed " + fileList);
                        }
                        commitMessageArea.setText("");
                        updateSuggestCommitButton();
                        updateStashList();
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                                      "Error stashing changes: " + ex.getMessage(),
                                                      "Error", JOptionPane.ERROR_MESSAGE);
                        chrome.enableUserActionButtons();
                    });
                }
                return null;
            });
        });
        buttonPanel.add(stashButton);

        // "Commit" button
        commitButton = new JButton("Commit All");
        commitButton.setToolTipText("Commit files with the message");
        commitButton.setEnabled(false);
        commitButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            var selectedFiles = getSelectedFilesFromTable();
            String msg = commitMessageArea.getText().trim();
            if (msg.isEmpty()) {
                chrome.enableUserActionButtons();
                return;
            }
            contextManager.submitUserTask("Committing files", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        var allDirtyFiles = getRepo().getUncommittedFileNames();
                        contextManager.getProject().getRepo().commitFiles(allDirtyFiles, msg);
                    } else {
                        contextManager.getProject().getRepo().commitFiles(selectedFiles, msg);
                    }
                    SwingUtilities.invokeLater(() -> {
                        try {
                            String shortHash = getRepo().getCurrentCommitId().substring(0, 7);
                            // show first line of commit
                            String firstLine = msg.contains("\n")
                                    ? msg.substring(0, msg.indexOf('\n'))
                                    : msg;
                            chrome.toolOutput("Committed " + shortHash + ": " + firstLine);
                        } catch (Exception ex) {
                            chrome.toolOutput("Changes committed successfully");
                        }
                        commitMessageArea.setText("");
                        updateSuggestCommitButton();
                        // The GitLogPanel can refresh branches/commits:
                        gitLogPanel.updateBranchList();
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                                      "Error committing files: " + ex.getMessage(),
                                                      "Commit Error", JOptionPane.ERROR_MESSAGE);
                        chrome.enableUserActionButtons();
                    });
                }
                return null;
            });
        });
        buttonPanel.add(commitButton);

        // Commit message area updates
        commitMessageArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateCommitButtonState(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateCommitButtonState(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateCommitButtonState(); }
            private void updateCommitButtonState() {
                String text = commitMessageArea.getText().trim();
                boolean hasNonCommentText = Arrays.stream(text.split("\n"))
                        .anyMatch(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"));
                boolean enable = hasNonCommentText && suggestMessageButton.isEnabled();
                commitButton.setEnabled(enable);
                stashButton.setEnabled(enable);
            }
        });

        // Listen for selection changes to update commit button text
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateCommitButtonText();
        });

        commitBottomPanel.add(buttonPanel, BorderLayout.SOUTH);
        commitTab.add(commitBottomPanel, BorderLayout.SOUTH);

        return commitTab;
    }

    /**
     * Builds the Stash tab.
     */
    private JPanel buildStashTab() {
        JPanel stashTab = new JPanel(new BorderLayout());

        stashTableModel = new DefaultTableModel(
                new Object[]{"Index", "Message", "Author", "Date", "ID"}, 0
        ) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
            @Override public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };
        stashTable = new JTable(stashTableModel);
        stashTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        stashTable.setRowHeight(18);

        stashTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        stashTable.getColumnModel().getColumn(1).setPreferredWidth(400);
        stashTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        stashTable.getColumnModel().getColumn(3).setPreferredWidth(150);
        // Hide ID column
        stashTable.getColumnModel().getColumn(4).setMinWidth(0);
        stashTable.getColumnModel().getColumn(4).setMaxWidth(0);
        stashTable.getColumnModel().getColumn(4).setWidth(0);

        stashTab.add(new JScrollPane(stashTable), BorderLayout.CENTER);

        // Context menu for stash
        JPopupMenu stashContextMenu = new JPopupMenu();
        JMenuItem popStashItem = new JMenuItem("Pop Stash");
        JMenuItem applyStashItem = new JMenuItem("Apply Stash");
        JMenuItem dropStashItem = new JMenuItem("Drop Stash");
        stashContextMenu.add(popStashItem);
        stashContextMenu.add(applyStashItem);
        stashContextMenu.add(dropStashItem);

        stashTable.setComponentPopupMenu(stashContextMenu);
        stashContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, stashTable);
                    int row = stashTable.rowAtPoint(point);
                    if (row >= 0) {
                        stashTable.setRowSelectionInterval(row, row);
                        int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                        // only enable "Pop" for stash@{0}
                        popStashItem.setEnabled(index == 0);
                    }
                });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        popStashItem.addActionListener(e -> {
            int row = stashTable.getSelectedRow();
            if (row != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                popStash(index);
            }
        });
        applyStashItem.addActionListener(e -> {
            int row = stashTable.getSelectedRow();
            if (row != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                applyStash(index);
            }
        });
        dropStashItem.addActionListener(e -> {
            int row = stashTable.getSelectedRow();
            if (row != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                dropStash(index);
            }
        });

        // Refresh button
        JPanel stashButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshStashButton = new JButton("Refresh");
        refreshStashButton.addActionListener(e -> updateStashList());
        stashButtonPanel.add(refreshStashButton);

        stashTab.add(stashButtonPanel, BorderLayout.SOUTH);

        return stashTab;
    }

    /**
     * Returns the current GitRepo from ContextManager.
     */
    private GitRepo getRepo() {
        return contextManager.getProject().getRepo();
    }

    /**
     * Populates the uncommitted files table and enables/disables commit-related buttons.
     */
    public void updateSuggestCommitButton() {
        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            try {
                var uncommittedFiles = getRepo().getUncommittedFileNames();
                SwingUtilities.invokeLater(() -> {
                    var model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    model.setRowCount(0);
                    if (uncommittedFiles.isEmpty()) {
                        suggestMessageButton.setEnabled(false);
                        commitButton.setEnabled(false);
                        stashButton.setEnabled(false);
                    } else {
                        for (String filePath : uncommittedFiles) {
                            int slash = filePath.lastIndexOf('/');
                            String filename = (slash >= 0) ? filePath.substring(slash + 1) : filePath;
                            String path = (slash >= 0) ? filePath.substring(0, slash) : "";
                            model.addRow(new Object[]{filename, path});
                        }
                        suggestMessageButton.setEnabled(true);

                        String text = commitMessageArea.getText().trim();
                        boolean hasNonCommentText = Arrays.stream(text.split("\n"))
                                .anyMatch(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"));

                        commitButton.setEnabled(hasNonCommentText);
                        stashButton.setEnabled(hasNonCommentText);
                    }
                    updateCommitButtonText();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    suggestMessageButton.setEnabled(false);
                    commitButton.setEnabled(false);
                });
            }
            return null;
        });
    }

    /**
     * Adjusts the commit/stash button label/text depending on selected vs all.
     */
    private void updateCommitButtonText() {
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();
        if (selectedRows.length > 0) {
            commitButton.setText("Commit Selected");
            commitButton.setToolTipText("Commit the selected files with the message");
            stashButton.setText("Stash Selected");
            stashButton.setToolTipText("Save your selected changes to the stash");
        } else {
            commitButton.setText("Commit All");
            commitButton.setToolTipText("Commit all files with the message");
            stashButton.setText("Stash All");
            stashButton.setToolTipText("Save all your changes to the stash");
        }
    }

    /**
     * Helper to get a list of selected files from the uncommittedFilesTable.
     */
    private java.util.List<String> getSelectedFilesFromTable() {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        return Arrays.stream(uncommittedFilesTable.getSelectedRows())
                .mapToObj(row -> {
                    String filename = (String) model.getValueAt(row, 0);
                    String path = (String) model.getValueAt(row, 1);
                    return path.isEmpty() ? filename : path + "/" + filename;
                })
                .collect(Collectors.toList());
    }

    /**
     * Sets the text in the commit message area (used by LLM suggestions).
     */
    public void setCommitMessageText(String message) {
        commitMessageArea.setText(message);
    }

    /**
     * Allows external code to size the commit-related buttons consistently.
     */
    public void setSuggestCommitButtonSize(Dimension preferredSize) {
        suggestMessageButton.setPreferredSize(preferredSize);
        suggestMessageButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        commitButton.setPreferredSize(preferredSize);
        commitButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        stashButton.setPreferredSize(preferredSize);
        stashButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
    }

    // ================ Stash Methods ==================

    public void updateStashList() {
        contextManager.submitUserTask("Fetching stashes", () -> {
            try {
                var stashes = getRepo().listStashes();
                SwingUtilities.invokeLater(() -> {
                    stashTableModel.setRowCount(0);
                    if (stashes.isEmpty()) return;

                    java.time.LocalDate today = java.time.LocalDate.now();
                    for (var stash : stashes) {
                        String formattedDate = gitLogPanel // reuse helper from GitLogPanel or local:
                                .formatCommitDate(stash.date(), today); // Or replicate that code here
                        stashTableModel.addRow(new Object[]{
                                String.valueOf(stash.index()),
                                stash.message(),
                                stash.author(),
                                formattedDate,
                                stash.id()
                        });
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching stashes", e);
                SwingUtilities.invokeLater(() -> {
                    stashTableModel.setRowCount(0);
                    stashTableModel.addRow(new Object[]{
                            "", "Error fetching stashes: " + e.getMessage(), "", "", ""
                    });
                });
            }
            return null;
        });
    }

    private void popStash(int index) {
        contextManager.submitUserTask("Popping stash", () -> {
            try {
                getRepo().popStash(index);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolOutput("Stash popped successfully");
                    updateStashList();
                    updateSuggestCommitButton();
                });
            } catch (Exception e) {
                logger.error("Error popping stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error popping stash: " + e.getMessage()));
            }
            return null;
        });
    }

    private void applyStash(int index) {
        contextManager.submitUserTask("Applying stash", () -> {
            try {
                getRepo().applyStash(index);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolOutput("Stash applied successfully");
                    updateSuggestCommitButton();
                });
            } catch (Exception e) {
                logger.error("Error applying stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error applying stash: " + e.getMessage()));
            }
            return null;
        });
    }

    private void dropStash(int index) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete this stash?",
                "Delete Stash",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        contextManager.submitUserTask("Dropping stash", () -> {
            try {
                getRepo().dropStash(index);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolOutput("Stash dropped successfully");
                    updateStashList();
                });
            } catch (Exception e) {
                logger.error("Error dropping stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error dropping stash: " + e.getMessage()));
            }
            return null;
        });
    }
}
