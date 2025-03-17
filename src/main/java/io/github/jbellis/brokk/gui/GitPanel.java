package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.analyzer.RepoFile;
import java.io.IOException;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // History tabs
    private JTabbedPane tabbedPane;
    private final Map<String, JTable> fileHistoryTables = new HashMap<>();
    
    // Stash tab UI
    private JTable stashTable;
    private DefaultTableModel stashTableModel;
    
    // PR tab UI
    private JTable prTable;
    private DefaultTableModel prTableModel;
    private JTable prCommitsTable;
    private DefaultTableModel prCommitsTableModel;
    private JButton checkoutPrButton;

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

        // Tabbed pane
        tabbedPane = new JTabbedPane();
        add(tabbedPane, BorderLayout.CENTER);

        // 1) Commit tab
        JPanel commitTab = buildCommitTab();
        tabbedPane.addTab("Commit", commitTab);

        // 2) Stash tab
        JPanel stashTab = buildStashTab();
        tabbedPane.addTab("Stash", stashTab);
        
        // 3) PR tab
        JPanel prTab = buildPrTab();
        tabbedPane.addTab("Pull Requests", prTab);

        // 4) Log tab (moved into GitLogPanel)
        gitLogPanel = new GitLogPanel(chrome, contextManager);
        tabbedPane.addTab("Log", gitLogPanel);

        // After UI is built, asynchronously refresh data
        SwingUtilities.invokeLater(() -> {
            updateStashList();
            updatePrList();
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

        // Add double-click handler to show diff for uncommitted files
        uncommittedFilesTable.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                if (e.getClickCount() == 2)
                {
                    int row = uncommittedFilesTable.rowAtPoint(e.getPoint());
                    if (row >= 0)
                    {
                        uncommittedFilesTable.setRowSelectionInterval(row, row);
                        viewDiffForUncommittedRow(row);
                    }
                }
            }
        });

        // Popup menu for uncommitted files
        var uncommittedContextMenu = new JPopupMenu();
        uncommittedFilesTable.setComponentPopupMenu(uncommittedContextMenu);

        // "Show Diff" menu item
        var viewDiffItem = new JMenuItem("Show Diff");
        uncommittedContextMenu.add(viewDiffItem);

        // When the menu appears, select the row under the cursor so the right-click target is highlighted
        uncommittedContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
        {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e)
            {
                SwingUtilities.invokeLater(() -> {
                    var point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, uncommittedFilesTable);
                    int row = uncommittedFilesTable.rowAtPoint(point);
                    if (row >= 0 && !uncommittedFilesTable.isRowSelected(row))
                    {
                        uncommittedFilesTable.setRowSelectionInterval(row, row);
                    }
                });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        // Hook up "Show Diff" action
        viewDiffItem.addActionListener(e -> {
            int row = uncommittedFilesTable.getSelectedRow();
            if (row >= 0) {
                viewDiffForUncommittedRow(row);
            }
        });

        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        commitTab.add(uncommittedScrollPane, BorderLayout.CENTER);

        // Commit message + buttons at bottom
        JPanel commitBottomPanel = new JPanel(new BorderLayout());
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.add(new JLabel("Commit/Stash Description:"), BorderLayout.NORTH);

        commitMessageArea = new JTextArea(2, 50);
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
            List<RepoFile> selectedFiles = getSelectedFilesFromTable();
            contextManager.submitBackgroundTask("Suggesting commit message", () -> {
                try {
                    var diff = selectedFiles.isEmpty()
                            ? getRepo().diff()
                            : getRepo().diffFiles(selectedFiles);
                    if (diff.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            chrome.actionOutput("No changes to commit");
                            chrome.enableUserActionButtons();
                        });
                        return null;
                    }
                    // Trigger LLM-based commit message generation
                    contextManager.inferCommitMessageAsync(diff);
                    SwingUtilities.invokeLater(chrome::enableUserActionButtons);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        chrome.actionOutput("Error suggesting commit message: " + ex.getMessage());
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
            List<RepoFile> selectedFiles = getSelectedFilesFromTable();

            contextManager.submitUserTask("Stashing changes", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    } else {
                        getRepo().add(selectedFiles);
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (selectedFiles.isEmpty()) {
                            chrome.systemOutput("All changes stashed successfully");
                        } else {
                            String fileList = selectedFiles.size() <= 3
                                    ? selectedFiles.stream().map(Object::toString).collect(Collectors.joining(", "))
                                    : selectedFiles.size() + " files";
                            chrome.systemOutput("Stashed " + fileList);
                        }
                        commitMessageArea.setText("");
                        updateCommitPanel();
                        updateStashList();
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        chrome.actionOutput("Error stashing changes: " + ex.getMessage());
                        chrome.enableUserActionButtons();
                    });
                }
            });
        });
        buttonPanel.add(stashButton);

        // "Commit" button
        commitButton = new JButton("Commit All");
        commitButton.setToolTipText("Commit files with the message");
        commitButton.setEnabled(false);
        commitButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            List<RepoFile> selectedFiles = getSelectedFilesFromTable();
            String msg = commitMessageArea.getText().trim();
            if (msg.isEmpty()) {
                chrome.enableUserActionButtons();
                return;
            }
            contextManager.submitUserTask("Committing files", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        var allDirtyFiles = getRepo().getUncommittedFiles();
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
                            chrome.systemOutput("Committed " + shortHash + ": " + firstLine);
                        } catch (Exception ex) {
                            chrome.systemOutput("Changes committed successfully");
                        }
                        commitMessageArea.setText("");
                        updateCommitPanel();
                        // The GitLogPanel can refresh branches/commits:
                        gitLogPanel.updateBranchList();
                    // Select the newly checked out branch in the log panel
                    gitLogPanel.selectCurrentBranch();
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        chrome.actionOutput("Error committing files: " + ex.getMessage());
                        chrome.enableUserActionButtons();
                    });
                }
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
    /**
     * Builds the Pull Requests tab.
     */
    private JPanel buildPrTab() {
        JPanel prTab = new JPanel(new BorderLayout());
        
        // Split panel with PRs on left (smaller) and commits on right (larger)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.4); // 40% for PR list, 60% for commits
        
        // Left side - Pull Requests table
        JPanel prListPanel = new JPanel(new BorderLayout());
        prListPanel.setBorder(BorderFactory.createTitledBorder("Open Pull Requests"));
        
        prTableModel = new DefaultTableModel(new Object[]{"#", "Title"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        prTable = new JTable(prTableModel);
        prTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        prTable.setRowHeight(18);
        prTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        prTable.getColumnModel().getColumn(1).setPreferredWidth(350);
        prListPanel.add(new JScrollPane(prTable), BorderLayout.CENTER);
        
        // Button panel for PRs
        JPanel prButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        checkoutPrButton = new JButton("Check Out");
        checkoutPrButton.setToolTipText("Check out this PR branch locally");
        checkoutPrButton.setEnabled(false);
        checkoutPrButton.addActionListener(e -> checkoutSelectedPr());
        prButtonPanel.add(checkoutPrButton);
        
        JButton refreshPrButton = new JButton("Refresh");
        refreshPrButton.addActionListener(e -> updatePrList());
        prButtonPanel.add(refreshPrButton);
        
        prListPanel.add(prButtonPanel, BorderLayout.SOUTH);
        
        // Right side - Commits in the selected PR
        JPanel prCommitsPanel = new JPanel(new BorderLayout());
        prCommitsPanel.setBorder(BorderFactory.createTitledBorder("Commits in Pull Request"));
        
        prCommitsTableModel = new DefaultTableModel(new Object[]{"Message", "Author", "Date", "ID"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };
        prCommitsTable = new JTable(prCommitsTableModel);
        prCommitsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        prCommitsTable.setRowHeight(18);
        
        // Set percentages for columns (60%/20%/20%)
        int tableWidth = prCommitsTable.getWidth();
        prCommitsTable.getColumnModel().getColumn(0).setPreferredWidth((int)(tableWidth * 0.6)); // message
        prCommitsTable.getColumnModel().getColumn(1).setPreferredWidth((int)(tableWidth * 0.2)); // author
        prCommitsTable.getColumnModel().getColumn(2).setPreferredWidth((int)(tableWidth * 0.2)); // date
        
        // Hide ID column
        prCommitsTable.getColumnModel().getColumn(3).setMinWidth(0);
        prCommitsTable.getColumnModel().getColumn(3).setMaxWidth(0);
        prCommitsTable.getColumnModel().getColumn(3).setWidth(0);
        
        prCommitsPanel.add(new JScrollPane(prCommitsTable), BorderLayout.CENTER);
        
        // Add the panels to the split pane
        splitPane.setLeftComponent(prListPanel);
        splitPane.setRightComponent(prCommitsPanel);
        
        prTab.add(splitPane, BorderLayout.CENTER);
        
        // Listen for PR selection changes
        prTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && prTable.getSelectedRow() != -1) {
                int row = prTable.getSelectedRow();
                String prNumberText = (String) prTableModel.getValueAt(row, 0);
                if (prNumberText.startsWith("#")) {
                    try {
                        int prNumber = Integer.parseInt(prNumberText.substring(1));
                        updateCommitsForPullRequest(prNumber);
                        checkoutPrButton.setEnabled(true);
                    } catch (NumberFormatException nfe) {
                        logger.warn("Invalid PR number: {}", prNumberText);
                        checkoutPrButton.setEnabled(false);
                    }
                } else {
                    checkoutPrButton.setEnabled(false);
                }
            } else {
                checkoutPrButton.setEnabled(false);
            }
        });
        
        return prTab;
    }

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
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(stashContextMenu);
        } else {
            // Register this popup menu later when the theme manager is available
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(stashContextMenu);
                }
            });
        }
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
    public void updateCommitPanel()
    {
        // Store currently selected rows before updating
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();
        List<String> selectedFiles = new ArrayList<>();
        
        // Store the filenames of selected rows to restore selection later
        for (int row : selectedRows) {
            String filename = (String) uncommittedFilesTable.getValueAt(row, 0);
            String path = (String) uncommittedFilesTable.getValueAt(row, 1);
            String fullPath = path.isEmpty() ? filename : path + "/" + filename;
            selectedFiles.add(fullPath);
        }
        
        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            try {
                var uncommittedFiles = getRepo().getUncommittedFiles();
                SwingUtilities.invokeLater(() -> {
                    var model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    model.setRowCount(0);

                    if (uncommittedFiles.isEmpty()) {
                        suggestMessageButton.setEnabled(false);
                        commitButton.setEnabled(false);
                        stashButton.setEnabled(false);
                    } else {
                        // Track row indices for files that were previously selected
                        List<Integer> rowsToSelect = new ArrayList<>();
                        
                        for (int i = 0; i < uncommittedFiles.size(); i++) {
                            var file = uncommittedFiles.get(i);
                            model.addRow(new Object[]{file.getFileName(), file.getParent()});
                            
                            // Check if this file was previously selected
                            String fullPath = file.getParent().isEmpty() ? 
                                file.getFileName() : file.getParent() + "/" + file.getFileName();
                            if (selectedFiles.contains(fullPath)) {
                                rowsToSelect.add(i);
                            }
                        }
                        
                        // Restore selection if any previously selected files are still present
                        if (!rowsToSelect.isEmpty()) {
                            for (int row : rowsToSelect) {
                                uncommittedFilesTable.addRowSelectionInterval(row, row);
                            }
                        }
                        
                        suggestMessageButton.setEnabled(true);

                        var text = commitMessageArea.getText().trim();
                        var hasNonCommentText = Arrays.stream(text.split("\n"))
                                                  .anyMatch(line -> !line.trim().isEmpty()
                                                                    && !line.trim().startsWith("#"));
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
    private List<RepoFile> getSelectedFilesFromTable()
    {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        var selectedRows = uncommittedFilesTable.getSelectedRows();
        var files = new ArrayList<RepoFile>();

        for (var row : selectedRows) {
            var filename = (String) model.getValueAt(row, 0);
            var path     = (String) model.getValueAt(row, 1);
            // Combine them to get the relative path
            var combined = path.isEmpty() ? filename : path + "/" + filename;
            files.add(new RepoFile(contextManager.getRoot(), combined));
        }
        return files;
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
    
    /**
     * Creates a new tab showing the history of a specific file
     */
    public void addFileHistoryTab(RepoFile file) {
        String filePath = file.toString();
        
        // If we already have a tab for this file, just select it
        if (fileHistoryTables.containsKey(filePath)) {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                if (tabbedPane.getTitleAt(i).equals(getFileTabName(filePath))) {
                    tabbedPane.setSelectedIndex(i);
                    return;
                }
            }
        }

        // Create a new tab with the file's name
        JPanel fileHistoryPanel = new JPanel(new BorderLayout());

        // Create a history table similar to the commits table but with different column proportions
        DefaultTableModel fileHistoryModel = new DefaultTableModel(
            new Object[]{"Message", "Author", "Date", "ID"}, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };

        JTable fileHistoryTable = new JTable(fileHistoryModel);
        fileHistoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileHistoryTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        fileHistoryTable.setRowHeight(18);

        // Set column widths to 80%/10%/10%
        fileHistoryTable.getColumnModel().getColumn(0).setPreferredWidth(800); // message (80%)
        fileHistoryTable.getColumnModel().getColumn(1).setPreferredWidth(100); // author (10%)
        fileHistoryTable.getColumnModel().getColumn(2).setPreferredWidth(100); // date (10%)

        // Hide ID column
        fileHistoryTable.getColumnModel().getColumn(3).setMinWidth(0);
        fileHistoryTable.getColumnModel().getColumn(3).setMaxWidth(0);
        fileHistoryTable.getColumnModel().getColumn(3).setWidth(0);

        // Add a context menu with same options as Changes tree
        JPopupMenu historyContextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(historyContextMenu);
        } else {
            // Register this popup menu later when the theme manager is available
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(historyContextMenu);
                }
            });
        }
        JMenuItem addToContextItem = new JMenuItem("Add Changes to Context");
        JMenuItem compareWithLocalItem = new JMenuItem("Compare with Local");
        JMenuItem viewInLogItem = new JMenuItem("View in Log");
        JMenuItem editFileItem = new JMenuItem("Edit File");

        historyContextMenu.add(addToContextItem);
        historyContextMenu.add(compareWithLocalItem);
        historyContextMenu.add(viewInLogItem);
        historyContextMenu.add(editFileItem);

        // Make sure right-clicking selects row under cursor first
        historyContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, fileHistoryTable);
                    int row = fileHistoryTable.rowAtPoint(point);
                    if (row >= 0) {
                        fileHistoryTable.setRowSelectionInterval(row, row);
                    }
                });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        fileHistoryTable.setComponentPopupMenu(historyContextMenu);
        
        // Add double-click listener to show diff
        fileHistoryTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = fileHistoryTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        fileHistoryTable.setRowSelectionInterval(row, row);
                        String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                        showFileHistoryDiff(commitId, filePath);
                    }
                }
            }
        });

        // Add listeners to context menu items
        addToContextItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                addFileChangeToContext(commitId, filePath);
            }
        });

        compareWithLocalItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                compareFileWithLocal(commitId, filePath);
            }
        });

        viewInLogItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                showCommitInLogTab(commitId);
            }
        });

        editFileItem.addActionListener(e -> editFile(filePath));

        fileHistoryPanel.add(new JScrollPane(fileHistoryTable), BorderLayout.CENTER);

        // Add to tab pane with a filename title and close button
        String tabName = getFileTabName(filePath);

        // Create a custom tab component with close button
        JPanel tabComponent = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabComponent.setOpaque(false);
        JLabel titleLabel = new JLabel(tabName);
        titleLabel.setOpaque(false);

        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        closeButton.setPreferredSize(new Dimension(24, 24));
        closeButton.setMargin(new Insets(0, 0, 0, 0));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setToolTipText("Close");

        // Add visual feedback on mouse events
        closeButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                closeButton.setForeground(Color.RED);
                closeButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                closeButton.setForeground(null); // Reset to default color
                closeButton.setCursor(Cursor.getDefaultCursor());
            }
        });

        closeButton.addActionListener(e -> {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                if (tabbedPane.getComponentAt(i) == fileHistoryPanel) {
                    tabbedPane.remove(i);
                    fileHistoryTables.remove(filePath);
                    break;
                }
            }
        });

        tabComponent.add(titleLabel);
        tabComponent.add(closeButton);

        tabbedPane.addTab(tabName, fileHistoryPanel);
        int tabIndex = tabbedPane.indexOfComponent(fileHistoryPanel);
        tabbedPane.setTabComponentAt(tabIndex, tabComponent);
        tabbedPane.setSelectedComponent(fileHistoryPanel);
        fileHistoryTables.put(filePath, fileHistoryTable);

        // Load the file history
        loadFileHistory(file, fileHistoryModel);
    }
    
    private String getFileTabName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }
    
    /**
     * Switches to the Log tab and highlights the specified commit.
     */
    private void showCommitInLogTab(String commitId) {
        // Switch to Log tab
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Log")) {
                tabbedPane.setSelectedIndex(i);
                break;
            }
        }
        
        // Find and select the commit in gitLogPanel
        gitLogPanel.selectCommitById(commitId);
    }
    
    private void loadFileHistory(RepoFile file, DefaultTableModel model) {
        contextManager.submitBackgroundTask("Loading file history: " + file, () -> {
            try {
                var history = getRepo().getFileHistory(file);
                SwingUtilities.invokeLater(() -> {
                    model.setRowCount(0);
                    if (history.isEmpty()) {
                        model.addRow(new Object[]{"No history found", "", "", ""});
                        return;
                    }

                    var today = java.time.LocalDate.now();
                    for (var commit : history) {
                        var formattedDate = formatCommitDate(commit.date(), today);
                        model.addRow(new Object[]{
                            commit.message(),
                            commit.author(),
                            formattedDate,
                            commit.id()
                        });
                    }
                });
            } catch (Exception e) {
                logger.error("Error loading file history for: {}", file, e);
                SwingUtilities.invokeLater(() -> {
                    model.setRowCount(0);
                    model.addRow(new Object[]{
                        "Error loading history: " + e.getMessage(), "", "", ""
                    });
                });
            }
            return null;
        });
    }
    
    private void addFileChangeToContext(String commitId, String filePath)
    {
        contextManager.submitContextTask("Adding file change to context", () -> {
            try {
                var repoFile = new RepoFile(contextManager.getRoot(), filePath);
                var diff = getRepo().showFileDiff("HEAD", commitId, repoFile);

                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found for " + filePath);
                    return;
                }

                var shortHash  = commitId.substring(0, 7);
                var fileName   = getFileTabName(filePath);
                var description= "git %s (single file)".formatted(shortHash);

                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for " + fileName + " to context");
            } catch (Exception e) {
                logger.error("Error adding file change to context", e);
                chrome.toolErrorRaw("Error adding file change to context: " + e.getMessage());
            }
        });
    }
    
    private void compareFileWithLocal(String commitId, String filePath) {
        contextManager.submitUserTask("Comparing file with local", () -> {
            try {
                var repoFile = new RepoFile(contextManager.getRoot(), filePath);
                var diff = getRepo().showFileDiff("HEAD", commitId, repoFile);

                if (diff.isEmpty()) {
                    chrome.systemOutput("No differences found between " + filePath + " and local working copy");
                    return;
                }

                var shortHash = commitId.substring(0, 7);
                var fileName = getFileTabName(filePath);
                var description = "git local vs " + shortHash + " [" + fileName + "]";

                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added comparison with local for " + fileName + " to context");
            } catch (Exception e) {
                logger.error("Error comparing file with local", e);
                chrome.toolErrorRaw("Error comparing file with local: " + e.getMessage());
            }
        });
    }
    
    private void editFile(String filePath) {
        List<RepoFile> files = new ArrayList<>();
        files.add(contextManager.toFile(filePath));
        contextManager.editFiles(files);
    }
    
    /**
     * Shows the diff for a file at a specific commit from file history.
     */
    private void showFileHistoryDiff(String commitId, String filePath) {
        RepoFile file = new RepoFile(contextManager.getRoot(), filePath);
        java.awt.Rectangle bounds = contextManager.getProject().getDiffWindowBounds();
        DiffPanel diffPanel = new DiffPanel(contextManager, bounds);

        String shortCommitId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
        String dialogTitle = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";

        diffPanel.showFileDiff(commitId, file);
        diffPanel.showInDialog(this, dialogTitle);
    }

    // ================ Stash Methods ==================

    public void updateStashList() {
        contextManager.submitBackgroundTask("Fetching stashes", () -> {
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
                    chrome.systemOutput("Stash popped successfully");
                    updateStashList();
                    updateCommitPanel();
                });
            } catch (Exception e) {
                logger.error("Error popping stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error popping stash: " + e.getMessage()));
            }
        });
    }

    private void applyStash(int index) {
        contextManager.submitUserTask("Applying stash", () -> {
            try {
                getRepo().applyStash(index);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Stash applied successfully");
                    updateCommitPanel();
                });
            } catch (Exception e) {
                logger.error("Error applying stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error applying stash: " + e.getMessage()));
            }
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
                    chrome.systemOutput("Stash dropped successfully");
                    updateStashList();
                });
            } catch (Exception e) {
                logger.error("Error dropping stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error dropping stash: " + e.getMessage()));
            }
        });
    }

    /**
     * Shows the diff for an uncommitted file.
     */
    private void viewDiffForUncommittedRow(int row)
    {
        var filename = (String) uncommittedFilesTable.getValueAt(row, 0);
        var path = (String) uncommittedFilesTable.getValueAt(row, 1);
        var filePath = path.isEmpty() ? filename : path + "/" + filename;
        showUncommittedFileDiff(filePath);
    }

    /**
     * Shows the diff for an uncommitted file.
     */
    private void showUncommittedFileDiff(String filePath) {
        RepoFile file = new RepoFile(contextManager.getRoot(), filePath);
        java.awt.Rectangle bounds = contextManager.getProject().getDiffWindowBounds();
        DiffPanel diffPanel = new DiffPanel(contextManager, bounds);

        String dialogTitle = "Uncommitted Changes: " + file.getFileName();
        diffPanel.showFileDiff("UNCOMMITTED", file);
        diffPanel.showInDialog(this, dialogTitle);
    }
    
    /**
     * Holds a parsed "owner" and "repo" from a Git remote URL
     */
    private record OwnerRepo(String owner, String repo) {}

    /**
     * Parse a Git remote URL of form:
     *   - https://github.com/OWNER/REPO.git
     *   - git@github.com:OWNER/REPO.git
     *   - ssh://github.com/OWNER/REPO
     *   - or any variant that ends with OWNER/REPO(.git)
     * This attempts to extract the last two path segments
     * as "owner" and "repo". Returns null if it cannot.
     */
    private OwnerRepo parseOwnerRepoFromUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            logger.warn("Remote URL is blank or null");
            return null;
        }

        // Strip trailing ".git" if present
        String cleaned = remoteUrl.endsWith(".git")
                ? remoteUrl.substring(0, remoteUrl.length() - 4)
                : remoteUrl;
        logger.debug("Cleaned repo url is {}", cleaned);

        // Remove leading protocol-like segments, e.g. "ssh://", "https://", "git@", etc.
        // Then we will split on '/' or ':' to capture path segments.
        // e.g. "git@github.com:owner/repo" => "github.com owner repo"
        // e.g. "ssh://github.com/owner/repo" => "github.com owner repo"
        // e.g. "https://somehost/owner/repo" => "somehost owner repo"

        // Normalize any backslashes, just in case
        cleaned = cleaned.replace('\\', '/');

        // If there's a '://' pattern, drop everything up through that
        int protocolIndex = cleaned.indexOf("://");
        if (protocolIndex >= 0) {
            cleaned = cleaned.substring(protocolIndex + 3);
        }

        // If there's a '@' pattern (ssh form), drop everything up through that
        // e.g. "git@github.com:owner/repo" -> "github.com:owner/repo"
        int atIndex = cleaned.indexOf('@');
        if (atIndex >= 0) {
            cleaned = cleaned.substring(atIndex + 1);
        }

        // Now split on '/' or ':'
        // e.g. "github.com:owner/repo" => ["github.com","owner","repo"]
        // e.g. "somehost/owner/repo" => ["somehost","owner","repo"]
        var segments = cleaned.split("[/:]+");

        if (segments.length < 2) {
            logger.warn("Unable to parse owner/repo from remote URL: {}", remoteUrl);
            return null;
        }

        // The last 2 entries are presumed to be owner, repo
        String repo = segments[segments.length - 1];
        String owner = segments[segments.length - 2];
        logger.debug("Parsed repo as {} owned by {}", repo, owner);

        // Basic sanity checks
        if (owner.isBlank() || repo.isBlank()) {
            logger.warn("Parsed blank owner/repo from remote URL: {}", remoteUrl);
            return null;
        }

        return new OwnerRepo(owner, repo);
    }
    
    /**
     * Fetches open GitHub pull requests and populates the PR table.
     */
    private void updatePrList() {
        contextManager.submitBackgroundTask("Fetching GitHub Pull Requests", () -> {
            try {
                // 1) Parse the GitHub remote URL
                var remoteUrl = getRepo().getRemoteUrl();
                var ownerRepo = parseOwnerRepoFromUrl(remoteUrl);
                if (ownerRepo == null) {
                    throw new IOException("Could not parse 'owner/repo' from remote: " + remoteUrl);
                }

                // 2) Use GitHubAuth which handles both auth and anonymous fallback
                var pullRequests = GitHubAuth.listOpenPullRequests(
                        contextManager.getProject(),
                        ownerRepo.owner(),
                        ownerRepo.repo()
                );

                // 3) Update the PR table in Swing
                SwingUtilities.invokeLater(() -> {
                    prTableModel.setRowCount(0);
                    if (pullRequests.isEmpty()) {
                        prTableModel.addRow(new Object[]{"", "No open PRs found"});
                        checkoutPrButton.setEnabled(false);
                        return;
                    }
                    for (var pr : pullRequests) {
                        prTableModel.addRow(new Object[]{
                                "#" + pr.getNumber(),
                                pr.getTitle()
                        });
                    }
                });
            } catch (Exception ex) {
                logger.error("Failed to fetch pull requests", ex);
                SwingUtilities.invokeLater(() -> {
                    prTableModel.setRowCount(0);
                    prTableModel.addRow(new Object[]{
                            "", "Error fetching PRs: " + ex.getMessage()
                    });
                    checkoutPrButton.setEnabled(false);
                });
            }
            return null;
        });
    }
    
    /**
     * Loads commits for the given pull request.
     */
    private void updateCommitsForPullRequest(int prNumber) {
        contextManager.submitBackgroundTask("Fetching commits for PR #" + prNumber, () -> {
            try {
                var remoteUrl = getRepo().getRemoteUrl();
                var ownerRepo = parseOwnerRepoFromUrl(remoteUrl);
                if (ownerRepo == null) {
                    throw new IOException("Could not parse 'owner/repo' from remote: " + remoteUrl);
                }
                String owner = ownerRepo.owner();
                String repo = ownerRepo.repo();
                
                // Get the commits for this PR
                var pointers = GitHubAuth.listPullRequestCommits(
                    contextManager.getProject(), 
                    owner, 
                    repo, 
                    prNumber
                );
                
                var commitInfoList = new ArrayList<GitRepo.CommitInfo>();
                for (var ptr : pointers) {
                    String sha = ptr.getSha();
                    String message = "Pull Request commit";
                    var date = new java.util.Date(); // placeholder
                    var author = ptr.getRepository().getOwnerName();
                    commitInfoList.add(new GitRepo.CommitInfo(sha, message, author, date));
                }
                
                SwingUtilities.invokeLater(() -> {
                    // Clear existing table
                    prCommitsTableModel.setRowCount(0);
                    
                    if (commitInfoList.isEmpty()) {
                        prCommitsTableModel.addRow(new Object[]{
                                "No commits found for PR #" + prNumber,
                                "",
                                "",
                                ""
                        });
                        return;
                    }
                    
                    // Insert rows
                    var today = java.time.LocalDate.now();
                    for (var commit : commitInfoList) {
                        var formattedDate = formatCommitDate(commit.date(), today);
                        prCommitsTableModel.addRow(new Object[]{
                                commit.message(),
                                commit.author(),
                                formattedDate,
                                commit.id()
                        });
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching commits for PR #{}", prNumber, e);
                SwingUtilities.invokeLater(() -> {
                    prCommitsTableModel.setRowCount(0);
                    prCommitsTableModel.addRow(new Object[]{
                            "Error: " + e.getMessage(), "", "", ""
                    });
                });
            }
            return null;
        });
    }
    
    /**
     * Checkout the currently selected PR branch
     */
    private void checkoutSelectedPr() {
        int row = prTable.getSelectedRow();
        if (row == -1) return;

        String prNumberText = (String) prTableModel.getValueAt(row, 0);
        if (!prNumberText.startsWith("#")) return;

        int prNumber;
        try {
            prNumber = Integer.parseInt(prNumberText.substring(1));
        } catch (NumberFormatException e) {
            logger.warn("Invalid PR number: {}", prNumberText);
            return;
        }

        logger.info("Starting checkout of PR #{}", prNumber);
        contextManager.submitUserTask("Checking out PR #" + prNumber, () -> {
            try {
                var remoteUrl = getRepo().getRemoteUrl();
                logger.debug("Remote URL for current repo: {}", remoteUrl);
                
                var ownerRepo = parseOwnerRepoFromUrl(remoteUrl);
                if (ownerRepo == null) {
                    throw new IOException("Could not parse 'owner/repo' from remote: " + remoteUrl);
                }
                logger.debug("Parsed owner: {}, repo: {}", ownerRepo.owner(), ownerRepo.repo());

                // Get PR branch info
                logger.debug("Fetching commit information for PR #{}", prNumber);
                var pointers = GitHubAuth.listPullRequestCommits(
                    contextManager.getProject(),
                    ownerRepo.owner(),
                    ownerRepo.repo(),
                    prNumber
                );

                if (pointers.isEmpty()) {
                    throw new IOException("No branch information found for PR #" + prNumber);
                }
                logger.debug("Found {} commits for PR #{}", pointers.size(), prNumber);

                // Get the branch reference from the PR
                var pointer = pointers.get(0);
                String prBranchName = pointer.getRef();
                String remoteName = "origin";
                logger.debug("PR branch name: {}", prBranchName);

                // Extract just the branch name without the refs/heads/ prefix if present
                if (prBranchName.startsWith("refs/heads/")) {
                    prBranchName = prBranchName.substring("refs/heads/".length());
                }

                // Determine if this is from the same repo or a fork
                String repoFullName = pointer.getRepository().getFullName();
                logger.debug("PR repository full name: {}", repoFullName);
                logger.debug("Current repository full name: {}/{}", ownerRepo.owner(), ownerRepo.repo());
                
                String remoteBranchRef;

                if (repoFullName.equals(ownerRepo.owner() + "/" + ownerRepo.repo())) {
                    // Same repo - we can check out directly
                    remoteBranchRef = remoteName + "/" + prBranchName;
                    logger.debug("PR is from same repository. Using remote branch: {}", remoteBranchRef);
                } else {
                    // It's a fork - need to add the remote
                    remoteName = "pr-" + prNumber;
                    String prUrl = pointer.getRepository().getHtmlUrl().toString();
                    logger.debug("PR is from fork. Repository URL: {}", prUrl);
                    
                    try {
                        logger.debug("Adding remote '{}' with URL: {}", remoteName, prUrl);
                        // Add the remote if it doesn't exist
                        var remoteAddCommand = getRepo().getGit().remoteAdd()
                            .setName(remoteName)
                            .setUri(new org.eclipse.jgit.transport.URIish(prUrl));

                        // Log what we're about to do
                        logger.debug("Executing remote add command: {}", remoteAddCommand);
                        remoteAddCommand.call();
                        logger.debug("Remote added successfully");

                        // We need to do a direct fetch using the PR's URL to avoid transport protocol mismatches
                        logger.debug("Fetching from remote: {}", remoteName);
                        
                        var refSpec = new org.eclipse.jgit.transport.RefSpec(
                            "+refs/heads/" + prBranchName + ":refs/remotes/" + remoteName + "/" + prBranchName);
                        
                        logger.debug("Using refspec: {}", refSpec);
                        
                        var fetchCommand = getRepo().getGit().fetch()
                            .setRemote(prUrl)
                            .setRefSpecs(refSpec);

                        logger.debug("Executing fetch command: {}", fetchCommand);
                        fetchCommand.call();
                        logger.debug("Fetch completed successfully");
                    } catch (Exception e) {
                        logger.error("Failed to set up remote", e);
                        throw new IOException("Error setting up remote for PR: " + e.getMessage(), e);
                    }

                    // We've now fetched this specific branch
                    remoteBranchRef = remoteName + "/" + prBranchName;
                    logger.debug("Using fork remote branch: {}", remoteBranchRef);
                    
                    // Verify the ref exists
                    var ref = getRepo().getGit().getRepository().findRef(remoteBranchRef);
                    if (ref == null) {
                        logger.error("Remote branch not found: {}", remoteBranchRef);
                        throw new IOException("Error: Could not find branch " + prBranchName + " in the PR repository");
                    }
                    logger.debug("Successfully verified remote branch exists: {}", remoteBranchRef);
                }

                // Choose a unique local branch name
                // Use PR number and avoid conflicts with existing branches
                String baseBranchName = "pr-" + prNumber;
                String localBranchName = baseBranchName;
                
                // Make sure the branch name is unique
                List<String> localBranches = getRepo().listLocalBranches();
                int suffix = 1;
                while (localBranches.contains(localBranchName)) {
                    localBranchName = baseBranchName + "-" + suffix;
                    suffix++;
                }
                logger.debug("Will create local branch: {}", localBranchName);

                // Check out the branch
                logger.debug("Checking out remote branch: {} to local branch: {}", remoteBranchRef, localBranchName);
                try {
                    getRepo().checkoutRemoteBranch(remoteBranchRef, localBranchName);
                    logger.debug("Successfully checked out branch");
                } catch (Exception e) {
                    logger.error("Failed during branch checkout", e);
                    throw new IOException("Error during checkout: " + e.getMessage(), e);
                }

                // Update UI
                SwingUtilities.invokeLater(() -> {
                    logger.debug("Updating UI after checkout");
                    // Switch to the Log tab and update branches
                    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                        if (tabbedPane.getTitleAt(i).equals("Log")) {
                            tabbedPane.setSelectedIndex(i);
                            break;
                        }
                    }
                    gitLogPanel.updateBranchList();
                });

                chrome.systemOutput("Checked out PR #" + prNumber + " as local branch");
                logger.info("Successfully checked out PR #{}", prNumber);
            } catch (Exception e) {
                logger.error("Failed to check out PR #{}: {}", prNumber, e.getMessage(), e);
                chrome.toolErrorRaw("Error checking out PR: " + e.getMessage());
            }
        });
    }

    protected String formatCommitDate(Date date, java.time.LocalDate today) {
        try {
            java.time.LocalDate commitDate = date.toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();

            String timeStr = new java.text.SimpleDateFormat("HH:mm:ss").format(date);

            if (commitDate.equals(today)) {
                // If it's today's date, just show the time with "today"
                return "Today " + timeStr;
            } else if (commitDate.equals(today.minusDays(1))) {
                // If it's yesterday
                return "Yesterday " + timeStr;
            } else if (commitDate.isAfter(today.minusDays(7))) {
                // If within the last week, show day of week
                String dayName = commitDate.getDayOfWeek().toString();
                dayName = dayName.substring(0, 1).toUpperCase() + dayName.substring(1).toLowerCase();
                return dayName + " " + timeStr;
            }

            // Otherwise, show the standard date format
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
        } catch (Exception e) {
            logger.debug("Could not format date: {}", date, e);
            return date.toString();
        }
    }
}
