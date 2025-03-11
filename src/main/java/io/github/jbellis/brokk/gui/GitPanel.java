package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.GitRepo.CommitInfo;
import io.github.jbellis.brokk.RepoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private JButton suggestMessageButton;
    private JTextArea commitMessageArea;
    private JButton commitButton;
    private JButton stashButton;
    private JTable branchTable;
    private DefaultTableModel branchTableModel;
    private JTable remoteBranchTable;
    private DefaultTableModel remoteBranchTableModel;
    private JTable commitsTable;
    private DefaultTableModel commitsTableModel;
    private JTree changesTree;
    private DefaultTreeModel changesTreeModel;
    private DefaultMutableTreeNode changesRootNode;
    private JButton pushButton;
    private JTable stashTable;
    private DefaultTableModel stashTableModel;

    /**
     * Constructor for the Git panel
     */
    public GitPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        // Enforce an overall preferred size corresponding to ~15 table rows in height
        int rows = 15;
        int rowHeight = 18;
        int overhead = 100; // Approx overhead for tab header, borders, buttons, etc.
        int totalHeight = rows * rowHeight + overhead;
        // Let width be fairly wide so the 4 panels in the log tab can fit
        int preferredWidth = 1000;

        Dimension panelSize = new Dimension(preferredWidth, totalHeight);
        setPreferredSize(panelSize);
        setMaximumSize(panelSize);

        this.chrome = chrome;
        this.contextManager = contextManager;
        assert chrome != null;
        assert contextManager != null;

        // Set the overall panel border
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Git",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Create tabbed pane
        var tabbedPane = new JTabbedPane();
        add(tabbedPane, BorderLayout.CENTER);

        // Create Commit tab
        var commitTab = new JPanel(new BorderLayout());
        tabbedPane.addTab("Commit", commitTab);

        // Create table for uncommitted files
        var model = new DefaultTableModel(
                new Object[]{"Filename", "Path"}, 0
        ) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        uncommittedFilesTable = new JTable(model);
        uncommittedFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        uncommittedFilesTable.setRowHeight(18);

        // Set column widths
        uncommittedFilesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        uncommittedFilesTable.getColumnModel().getColumn(1).setPreferredWidth(450);

        // Enable multi-selection for the uncommitted files table
        uncommittedFilesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Create a scroll pane with fixed height of 10 rows plus header and scrollbar
        var uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        int tableRowHeight = uncommittedFilesTable.getRowHeight();
        int headerHeight = 22; // Approximate header height
        int scrollbarHeight = 3; // Extra padding for scrollbar

        // Add table to commit tab
        commitTab.add(uncommittedScrollPane, BorderLayout.CENTER);

        // Create bottom panel for commit message and buttons
        var commitBottomPanel = new JPanel(new BorderLayout());

        // Create label for commit message area
        var commitMessageLabel = new JLabel("Commit/Stash Description:");

        // Create commit message area
        commitMessageArea = new JTextArea(5, 50);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);
        commitMessageArea.setText("");

        // Create a panel to hold label and message area
        var messagePanel = new JPanel(new BorderLayout());
        messagePanel.add(commitMessageLabel, BorderLayout.NORTH);
        messagePanel.add(new JScrollPane(commitMessageArea), BorderLayout.CENTER);

        commitBottomPanel.add(messagePanel, BorderLayout.CENTER);

        // Create button panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        // Create stash button
        stashButton = new JButton("Stash All");
        stashButton.setEnabled(false);
        stashButton.setToolTipText("Save your changes to the stash");
        stashButton.addActionListener(e -> {
            chrome.disableUserActionButtons();

            // Get message from commit message area
            var message = commitMessageArea.getText().trim();
            assert !message.isEmpty();

            // Filter out lines starting with # (comments)
            var stashDescription = Arrays.stream(message.split("\n"))
                    .filter(line -> !line.trim().startsWith("#"))
                    .collect(Collectors.joining("\n"))
                    .trim();

            // Get selected files if any
            var selectedFiles = getSelectedFilesFromTable();

            contextManager.submitUserTask("Stashing changes", () -> {
                try {
                    logger.debug("Stashing changes, selected files: {}", selectedFiles);
                    if (selectedFiles.isEmpty()) {
                        // Stash all files
                        logger.debug("Stashing all files with description: {}", stashDescription);
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    } else {
                        // For selected files, we have to stage them first, then stash
                        logger.debug("Stashing selected files: {}", selectedFiles);
                        for (var file : selectedFiles) {
                            logger.debug("Adding file to index: {}", file);
                            getRepo().add(file);
                        }
                        // Then create a stash with the staged files
                        logger.debug("Creating stash with staged files, description: {}", stashDescription);
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    }

                    SwingUtilities.invokeLater(() -> {
                        if (selectedFiles.isEmpty()) {
                            chrome.toolOutput("All changes stashed successfully");
                        } else {
                            var fileList = selectedFiles.size() <= 3
                                    ? String.join(", ", selectedFiles)
                                    : selectedFiles.size() + " files";
                            chrome.toolOutput("Stashed " + fileList);
                        }
                        // Clear the commit message area
                        commitMessageArea.setText("");

                        updateSuggestCommitButton();  // Update uncommitted files table
                        updateStashList();           // Update stash tab
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

        // Create the suggest message button
        suggestMessageButton = new JButton("Suggest Message");
        suggestMessageButton.setEnabled(false);
        suggestMessageButton.setMnemonic(KeyEvent.VK_M);
        suggestMessageButton.setToolTipText("Suggest a commit message for the selected files");
        suggestMessageButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            var selectedFiles = getSelectedFilesFromTable();
            contextManager.submitBackgroundTask("Suggesting commit message", () -> {
                try {
                    // Get the diff for only selected files or all files
                    String diff;
                    if (selectedFiles.isEmpty()) {
                        diff = getRepo().diff();
                    } else {
                        // Use the specialized method for getting diff of specific files
                        diff = getRepo().diffFiles(selectedFiles);
                    }

                    if (diff.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this,
                                                          "No changes to commit",
                                                          "Error", JOptionPane.ERROR_MESSAGE);
                            chrome.enableUserActionButtons();
                        });
                        return null;
                    }

                    // Need to call performCommitActionAsync with the specific diff
                    contextManager.performCommitActionAsync(diff);
                    SwingUtilities.invokeLater(() -> {
                        // The commit message will be filled by the LLM response through ContextManager
                        chrome.enableUserActionButtons();
                    });
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

        // Create commit button
        commitButton = new JButton("Commit All");
        commitButton.setEnabled(false);
        commitButton.setToolTipText("Commit files with the message");

        // Add document listener to commit message area to enable/disable buttons based on content
        commitMessageArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateCommitButtonState();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateCommitButtonState();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateCommitButtonState();
            }

            private void updateCommitButtonState() {
                var text = commitMessageArea.getText().trim();
                var hasNonCommentText = Arrays.stream(text.split("\n"))
                        .anyMatch(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"));

                // Only enable commit/stash if there's actual content and there are uncommitted changes
                var enableButtons = hasNonCommentText && suggestMessageButton.isEnabled();
                commitButton.setEnabled(enableButtons);
                stashButton.setEnabled(enableButtons);
            }
        });
        commitButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            var selectedFiles = getSelectedFilesFromTable();
            var msg = commitMessageArea.getText().trim();

            if (msg.isEmpty()) {
                chrome.enableUserActionButtons();
                return;
            }

            contextManager.submitUserTask("Committing files", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        // Commit all files by getting the uncommitted file names
                        var allDirtyFiles = getRepo().getUncommittedFileNames();
                        contextManager.getProject().getRepo().commitFiles(allDirtyFiles, msg);
                    } else {
                        // Commit selected files
                        contextManager.getProject().getRepo().commitFiles(selectedFiles, msg);
                    }

                    SwingUtilities.invokeLater(() -> {
                        // Get the first line of the commit message for the output
                        var firstLine = msg.contains("\n")
                                ? msg.substring(0, msg.indexOf('\n'))
                                : msg;

                        try {
                            // Get the latest commit ID to display
                            var shortHash = getRepo().getCurrentCommitId().substring(0, 7);
                            chrome.toolOutput("Committed " + shortHash + ": " + firstLine);
                        } catch (Exception ex) {
                            // Fall back to a generic success message if we can't get the commit ID
                            chrome.toolOutput("Changes committed successfully");
                        }

                        // Clear the commit message area
                        commitMessageArea.setText("");

                        updateSuggestCommitButton();
                        // Refresh the log tab
                        updateBranchList();
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Error committing files: " + ex.getMessage(),
                                                      "Commit Error", JOptionPane.ERROR_MESSAGE);
                        chrome.enableUserActionButtons();
                    });
                }
                return null;
            });
        });


        // Add buttons to button panel
        buttonPanel.add(suggestMessageButton);
        buttonPanel.add(stashButton);
        buttonPanel.add(commitButton);

        // Add button panel to bottom panel
        commitBottomPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Add bottom panel to commit tab
        commitTab.add(commitBottomPanel, BorderLayout.SOUTH);

        // Create Stash tab
        var stashTab = new JPanel(new BorderLayout());
        tabbedPane.addTab("Stash", stashTab);

        // Create stash table
        stashTableModel = new DefaultTableModel(
                new Object[]{"Index", "Message", "Author", "Date", "ID"}, 0
        ) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        stashTable = new JTable(stashTableModel);
        stashTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        stashTable.setRowHeight(18);

        // Set column widths
        stashTable.getColumnModel().getColumn(0).setPreferredWidth(40);   // Index
        stashTable.getColumnModel().getColumn(1).setPreferredWidth(400);  // Message
        stashTable.getColumnModel().getColumn(2).setPreferredWidth(100);  // Author
        stashTable.getColumnModel().getColumn(3).setPreferredWidth(150);  // Date

        // Hide ID column
        stashTable.getColumnModel().getColumn(4).setMinWidth(0);
        stashTable.getColumnModel().getColumn(4).setMaxWidth(0);
        stashTable.getColumnModel().getColumn(4).setWidth(0);

        stashTab.add(new JScrollPane(stashTable), BorderLayout.CENTER);

        // Create stash context menu
        var stashContextMenu = new JPopupMenu();
        var popStashItem = new JMenuItem("Pop Stash");
        var applyStashItem = new JMenuItem("Apply Stash");
        var dropStashItem = new JMenuItem("Drop Stash");

        stashContextMenu.add(popStashItem);
        stashContextMenu.add(applyStashItem);
        stashContextMenu.add(dropStashItem);

        stashTable.setComponentPopupMenu(stashContextMenu);

        // Use the popup menu listener to ensure selection
        stashContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    var point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, stashTable);
                    int row = stashTable.rowAtPoint(point);
                    if (row >= 0) {
                        stashTable.setRowSelectionInterval(row, row);

                        // Enable pop only for top stash (index 0), apply for others
                        int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                        popStashItem.setEnabled(index == 0);
                    }
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        // Add action listeners for stash operations
        popStashItem.addActionListener(e -> {
            int selectedRow = stashTable.getSelectedRow();
            if (selectedRow != -1) {
                var index = Integer.parseInt((String) stashTableModel.getValueAt(selectedRow, 0));
                popStash(index);
            }
        });

        applyStashItem.addActionListener(e -> {
            int selectedRow = stashTable.getSelectedRow();
            if (selectedRow != -1) {
                var index = Integer.parseInt((String) stashTableModel.getValueAt(selectedRow, 0));
                applyStash(index);
            }
        });

        dropStashItem.addActionListener(e -> {
            int selectedRow = stashTable.getSelectedRow();
            if (selectedRow != -1) {
                var index = Integer.parseInt((String) stashTableModel.getValueAt(selectedRow, 0));
                dropStash(index);
            }
        });

        // Create button panel for stash tab
        var stashButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var refreshStashButton = new JButton("Refresh");
        refreshStashButton.addActionListener(e -> updateStashList());
        stashButtonPanel.add(refreshStashButton);
        stashTab.add(stashButtonPanel, BorderLayout.SOUTH);

        // Create Log tab
        var logTab = new JPanel(new BorderLayout());
        tabbedPane.addTab("Log", logTab);

        // Create panel with percentage-based layout
        var logPanel = new JPanel(new GridBagLayout());
        logTab.add(logPanel, BorderLayout.CENTER);

        // Create branch panel (15% width)
        var branchesPanel = new JPanel(new BorderLayout());
        branchesPanel.setBorder(BorderFactory.createTitledBorder("Branches"));

        // Create split panel for local and remote branches
        var branchSplitPanel = new JPanel(new GridLayout(2, 1));

        // Create local branches panel
        var localBranchPanel = new JPanel(new BorderLayout());
        localBranchPanel.setBorder(BorderFactory.createTitledBorder("Local"));
        branchTableModel = new DefaultTableModel(new Object[]{"✓", "Branch"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        branchTable = new JTable(branchTableModel);
        branchTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        branchTable.setRowHeight(18);

        // Make the checkmark column small and fixed
        branchTable.getColumnModel().getColumn(0).setMaxWidth(20);
        branchTable.getColumnModel().getColumn(0).setMinWidth(20);
        branchTable.getColumnModel().getColumn(0).setPreferredWidth(20);

        localBranchPanel.add(new JScrollPane(branchTable), BorderLayout.CENTER);

        // Create remote branches table
        var remoteBranchPanel = new JPanel(new BorderLayout());
        remoteBranchPanel.setBorder(BorderFactory.createTitledBorder("Remote"));
        remoteBranchTableModel = new DefaultTableModel(new Object[]{"Branch"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        remoteBranchTable = new JTable(remoteBranchTableModel);
        remoteBranchTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        remoteBranchTable.setRowHeight(18);
        remoteBranchPanel.add(new JScrollPane(remoteBranchTable), BorderLayout.CENTER);

        // Add tables to the branch split panel
        branchSplitPanel.add(localBranchPanel);
        branchSplitPanel.add(remoteBranchPanel);
        branchesPanel.add(branchSplitPanel, BorderLayout.CENTER);

        // Add a button to refresh branches
        var branchButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var refreshBranchesButton = new JButton("Refresh");
        refreshBranchesButton.addActionListener(e -> updateBranchList());
        branchButtonPanel.add(refreshBranchesButton);
        branchesPanel.add(branchButtonPanel, BorderLayout.SOUTH);

        // Create commits panel (45% width)
        var commitsPanel = new JPanel(new BorderLayout());
        commitsPanel.setBorder(BorderFactory.createTitledBorder("Commits"));
        commitsTableModel = new DefaultTableModel(new Object[]{"Message", "Author", "Date", "ID", "Unpushed"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 4 -> Boolean.class;  // For the unpushed status (hidden)
                    default -> String.class;
                };
            }
        };
        commitsTable = new JTable(commitsTableModel);
        // Enable multi-selection in the commits table
        commitsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // Hide the ID and Unpushed columns
        commitsTable.getColumnModel().getColumn(3).setMinWidth(0);
        commitsTable.getColumnModel().getColumn(3).setMaxWidth(0);
        commitsTable.getColumnModel().getColumn(3).setWidth(0);
        commitsTable.getColumnModel().getColumn(4).setMinWidth(0);
        commitsTable.getColumnModel().getColumn(4).setMaxWidth(0);
        commitsTable.getColumnModel().getColumn(4).setWidth(0);

        // Set custom cell renderer for highlighting unpushed commits
        commitsTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                if (!isSelected) {
                    var unpushed = (Boolean) table.getModel().getValueAt(row, 4);
                    if (unpushed != null && unpushed) {
                        c.setBackground(new Color(220, 255, 220)); // Light green
                    } else {
                        c.setBackground(table.getBackground());
                    }
                }

                return c;
            }
        });

        commitsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && commitsTable.getSelectedRow() != -1) {
                var selectedRows = commitsTable.getSelectedRows();
                if (selectedRows.length >= 1) {
                    updateChangesForCommits(selectedRows);
                }
            }
        });

        // Add context menu for commits table
        var commitsContextMenu = new JPopupMenu();
        var addToContextItem = new JMenuItem("Add Changes to Context");
        var compareWithLocalItem = new JMenuItem("Compare with Local");
        var revertCommitItem = new JMenuItem("Revert Commit");

        commitsContextMenu.add(addToContextItem);
        commitsContextMenu.add(compareWithLocalItem);
        commitsContextMenu.add(revertCommitItem);

        commitsTable.setComponentPopupMenu(commitsContextMenu);

        // Use the popup menu listener to ensure selection and update item states
        commitsContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    var point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, commitsTable);
                    int row = commitsTable.rowAtPoint(point);

                    if (row >= 0) {
                        // If not part of existing selection, set to single selection
                        if (!commitsTable.isRowSelected(row)) {
                            commitsTable.setRowSelectionInterval(row, row);
                        }
                    }

                    // Update menu items state based on selection
                    var selectedRows = commitsTable.getSelectedRows();
                    compareWithLocalItem.setEnabled(selectedRows.length == 1);
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        addToContextItem.addActionListener(e -> {
            var selectedRows = commitsTable.getSelectedRows();
            if (selectedRows.length >= 1) {
                addCommitRangeToContext(selectedRows);
            }
        });

        compareWithLocalItem.addActionListener(e -> {
            var selectedRows = commitsTable.getSelectedRows();
            if (selectedRows.length == 1) {
                // Only active for single commit
                var commitId = (String) commitsTableModel.getValueAt(selectedRows[0], 3);
                var commitMessage = (String) commitsTableModel.getValueAt(selectedRows[0], 0);
                var firstLine = commitMessage.contains("\n")
                        ? commitMessage.substring(0, commitMessage.indexOf('\n'))
                        : commitMessage;
                compareCommitWithLocal(commitId, firstLine);
            }
        });

        revertCommitItem.addActionListener(e -> {
            int selectedRow = commitsTable.getSelectedRow();
            if (selectedRow != -1) {
                var commitId = (String) commitsTableModel.getValueAt(selectedRow, 3);
                revertCommit(commitId);
            }
        });

        // Create a panel for the push button
        var commitsPanelButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        // Create push button
        pushButton = new JButton("Push");
        pushButton.setEnabled(false);
        pushButton.setToolTipText("Push commits to remote repository");
        pushButton.addActionListener(e -> {
            pushBranch();
        });

        commitsPanelButtons.add(pushButton);

        // Add components to the commits panel
        commitsPanel.add(new JScrollPane(commitsTable), BorderLayout.CENTER);
        commitsPanel.add(commitsPanelButtons, BorderLayout.SOUTH);

        // Add selection listener to uncommitted files table to update commit button text
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateCommitButtonText();
            }
        });

        // Create changes panel (25% width)
        var changesPanel = new JPanel(new BorderLayout());
        changesPanel.setBorder(BorderFactory.createTitledBorder("Changes"));
        changesRootNode = new DefaultMutableTreeNode("Changes");
        changesTreeModel = new DefaultTreeModel(changesRootNode);
        changesTree = new JTree(changesTreeModel);
        changesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        changesPanel.add(new JScrollPane(changesTree), BorderLayout.CENTER);

        // Add context menu for changes tree
        var changesContextMenu = new JPopupMenu();
        var addFileToContextItem = new JMenuItem("Add Changes to Context");
        var compareFileWithLocalItem = new JMenuItem("Compare with Local");
        var editFileItem = new JMenuItem("Edit File");

        changesContextMenu.add(addFileToContextItem);
        changesContextMenu.add(compareFileWithLocalItem);
        changesContextMenu.add(editFileItem);

        changesTree.setComponentPopupMenu(changesContextMenu);

        // Use the popup menu listener to ensure selection and update menu item states
        changesContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    var point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, changesTree);
                    int row = changesTree.getRowForLocation(point.x, point.y);

                    if (row >= 0) {
                        // If right-clicked on a node that's not already selected, select just that node
                        if (!changesTree.isRowSelected(row)) {
                            changesTree.setSelectionRow(row);
                        }
                    }

                    // Update menu item states based on selection
                    var paths = changesTree.getSelectionPaths();
                    var hasFileSelection = paths != null && paths.length > 0 && hasFileNodesSelected(paths);

                    var selectedRows = commitsTable.getSelectedRows();
                    var isSingleCommit = (selectedRows.length == 1);

                    addFileToContextItem.setEnabled(hasFileSelection);
                    compareFileWithLocalItem.setEnabled(hasFileSelection && isSingleCommit);
                    editFileItem.setEnabled(hasFileSelection);
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        addFileToContextItem.addActionListener(e -> {
            var paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                // Get the selected files paths
                var selectedFiles = getSelectedFilePaths(paths);

                if (!selectedFiles.isEmpty()) {
                    // Get current selected commit IDs
                    var selectedRows = commitsTable.getSelectedRows();
                    if (selectedRows.length >= 1) {
                        addFilesChangeToContext(selectedRows, selectedFiles);
                    }
                }
            }
        });

        compareFileWithLocalItem.addActionListener(e -> {
            var paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                var selectedFiles = getSelectedFilePaths(paths);

                if (!selectedFiles.isEmpty()) {
                    var selectedRows = commitsTable.getSelectedRows();
                    if (selectedRows.length == 1) {
                        var commitId = (String) commitsTableModel.getValueAt(selectedRows[0], 3);
                        compareFilesWithLocal(commitId, selectedFiles);
                    }
                }
            }
        });

        editFileItem.addActionListener(e -> {
            var paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                var selectedFiles = getSelectedFilePaths(paths);

                for (var filePath : selectedFiles) {
                    editFile(filePath);
                }
            }
        });

        // Create search panel (15% width)
        var searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(BorderFactory.createTitledBorder("Search"));

        // Use JTextArea instead of JTextField
        var searchField = new JTextArea(3, 10);
        searchField.setLineWrap(true);
        searchField.setWrapStyleWord(true);
        searchPanel.add(new JScrollPane(searchField), BorderLayout.CENTER);

        // Create button panel with vertical layout
        var searchButtonPanel = new JPanel();
        searchButtonPanel.setLayout(new BoxLayout(searchButtonPanel, BoxLayout.Y_AXIS));

        var textSearchButton = new JButton("Text Search");
        textSearchButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, textSearchButton.getPreferredSize().height));
        textSearchButton.addActionListener(e -> {
            var query = searchField.getText().trim();
            if (!query.isEmpty()) {
                searchCommits(query);
            }
        });

        var resetButton = new JButton("Reset");
        resetButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, resetButton.getPreferredSize().height));
        resetButton.addActionListener(e -> {
            searchField.setText("");
            // Reset to show all commits for the current branch
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                var branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                var branchName = getBranchNameFromDisplay(branchDisplay);
                updateCommitsForBranch(branchName);
            }
        });

        var aiSearchButton = new JButton("AI Search");
        aiSearchButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, aiSearchButton.getPreferredSize().height));

        // Add some vertical padding between buttons
        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchButtonPanel.add(textSearchButton);
        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchButtonPanel.add(resetButton);
        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchButtonPanel.add(aiSearchButton);
        searchButtonPanel.add(Box.createVerticalStrut(5));

        searchPanel.add(searchButtonPanel, BorderLayout.SOUTH);

        // Add the panels to log panel with percentage-based layout
        var constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weightx = 0.15;  // 15%
        constraints.weighty = 1.0;
        constraints.gridx = 0;
        constraints.gridy = 0;
        logPanel.add(branchesPanel, constraints);
        
        constraints.gridx = 1;
        constraints.weightx = 0.50;  // 50%
        logPanel.add(commitsPanel, constraints);
        
        constraints.gridx = 2;
        constraints.weightx = 0.25;  // 25%
        logPanel.add(changesPanel, constraints);
        
        constraints.gridx = 3;
        constraints.weightx = 0.10;  // 10%
        logPanel.add(searchPanel, constraints);

        // Initialize branch list and select current branch (call after all UI setup)
        SwingUtilities.invokeLater(() -> {
            updateBranchList();
            updateStashList();
        });

        // Add selection listener to branch table
        branchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && branchTable.getSelectedRow() != -1) {
                var branchName = (String) branchTableModel.getValueAt(branchTable.getSelectedRow(), 1);
                updateCommitsForBranch(branchName);
            }
        });

        // Add selection listener to remote branch table
        remoteBranchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && remoteBranchTable.getSelectedRow() != -1) {
                var branchName = (String) remoteBranchTableModel.getValueAt(remoteBranchTable.getSelectedRow(), 0);
                updateCommitsForBranch(branchName);

                // When remote branch is selected, clear selection in local branch table
                branchTable.clearSelection();
            }
        });

        // When local branch is selected, clear selection in remote branch table
        branchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && branchTable.getSelectedRow() != -1) {
                remoteBranchTable.clearSelection();
            }
        });

        // Add context menu for branch table
        var branchContextMenu = new JPopupMenu();
        var checkoutItem = new JMenuItem("Checkout");
        var mergeItem = new JMenuItem("Merge into HEAD");
        var renameItem = new JMenuItem("Rename");
        var deleteItem = new JMenuItem("Delete");

        branchContextMenu.add(checkoutItem);
        branchContextMenu.add(mergeItem);
        branchContextMenu.add(renameItem);
        branchContextMenu.add(deleteItem);

        // Set up the popup menu and listener
        branchTable.setComponentPopupMenu(branchContextMenu);

        // Use the popup menu listener to ensure selection
        branchContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    var point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, branchTable);
                    int row = branchTable.rowAtPoint(point);
                    if (row >= 0) {
                        branchTable.setRowSelectionInterval(row, row);
                    }
                    checkBranchContextMenuState();
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        checkoutItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                var branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                var branchName = getBranchNameFromDisplay(branchDisplay);
                checkoutBranch(branchName);
            }
        });

        mergeItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                var branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                var branchName = getBranchNameFromDisplay(branchDisplay);
                mergeBranchIntoHead(branchName);
            }
        });

        renameItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                var branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                if (branchDisplay.startsWith("Local: ")) {
                    var branchName = branchDisplay.substring("Local: ".length());
                    renameBranch(branchName);
                }
            }
        });

        deleteItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                var branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                if (branchDisplay.startsWith("Local: ")) {
                    var branchName = branchDisplay.substring("Local: ".length());
                    deleteBranch(branchName);
                }
            }
        });
    }

    /**
     * Gets a list of the selected files from the table
     */
    private List<String> getSelectedFilesFromTable() {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        return Arrays.stream(uncommittedFilesTable.getSelectedRows())
                .mapToObj(row -> {
                    var filename = (String) model.getValueAt(row, 0);
                    var path = (String) model.getValueAt(row, 1);
                    return path.isEmpty() ? filename : path + "/" + filename;
                })
                .collect(Collectors.toList());
    }

    /**
     * Updates the uncommitted files table and the state of the suggest commit button
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
                        uncommittedFiles.forEach(filePath -> {
                            int lastSlash = filePath.lastIndexOf('/');
                            var filename = (lastSlash >= 0) ? filePath.substring(lastSlash + 1) : filePath;
                            var path = (lastSlash >= 0) ? filePath.substring(0, lastSlash) : "";
                            model.addRow(new Object[]{filename, path});
                        });

                        suggestMessageButton.setEnabled(true);

                        // Check if there's content in the commit message area to enable commit/stash buttons
                        var text = commitMessageArea.getText().trim();
                        var hasNonCommentText = Arrays.stream(text.split("\n"))
                                .anyMatch(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"));

                        commitButton.setEnabled(hasNonCommentText);
                        stashButton.setEnabled(hasNonCommentText);
                    }

                    // Update the commit button text based on selection
                    updateCommitButtonText();
                });
            } catch (Exception e) {
                // Handle exception in case the repo is no longer available
                SwingUtilities.invokeLater(() -> {
                    suggestMessageButton.setEnabled(false);
                    commitButton.setEnabled(false);
                });
            }
            return null;
        });
    }

    /**
     * Updates the commit and stash button text based on table selection
     */
    private void updateCommitButtonText() {
        var selectedRows = uncommittedFilesTable.getSelectedRows();
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
     * Set the preferred size of the suggest commit button to match the context panel buttons
     */
    public void setSuggestCommitButtonSize(Dimension preferredSize) {
        suggestMessageButton.setPreferredSize(preferredSize);
        suggestMessageButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        // Also set size for the commit button
        commitButton.setPreferredSize(preferredSize);
        commitButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        // And for the stash button
        stashButton.setPreferredSize(preferredSize);
        stashButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
    }

    /**
     * Sets the text in the commit message area
     */
    public void setCommitMessageText(String message) {
        commitMessageArea.setText(message);
    }

    /**
     * Updates the branch table in the log tab
     */
    public void updateBranchList() {
        contextManager.submitBackgroundTask("Fetching git branches", () -> {
            try {
                var currentBranch = getRepo().getCurrentBranch();
                var localBranches = getRepo().listLocalBranches();
                var remoteBranches = getRepo().listRemoteBranches();

                SwingUtilities.invokeLater(() -> {
                    // We already have remoteBranchTable and remoteBranchTableModel as class fields
                    
                    // Clear both tables
                    branchTableModel.setRowCount(0);
                    remoteBranchTableModel.setRowCount(0);
                    int currentBranchRow = -1;

                    // Add local branches (without prefix)
                    for (var branch : localBranches) {
                        var checkmark = branch.equals(currentBranch) ? "✓" : "";
                        branchTableModel.addRow(new Object[]{checkmark, branch});

                        // Record the current branch row for later selection
                        if (branch.equals(currentBranch)) {
                            currentBranchRow = branchTableModel.getRowCount() - 1;
                        }
                    }

                    // Add remote branches (without prefix)
                    for (var branch : remoteBranches) {
                        remoteBranchTableModel.addRow(new Object[]{branch});
                    }

                    // Select current branch automatically and update commits
                    if (currentBranchRow >= 0) {
                        branchTable.setRowSelectionInterval(currentBranchRow, currentBranchRow);
                        updateCommitsForBranch(currentBranch);
                    } else if (branchTableModel.getRowCount() > 0) {
                        // If no current branch found, select the first one
                        branchTable.setRowSelectionInterval(0, 0);
                        var branchName = (String) branchTableModel.getValueAt(0, 1);
                        updateCommitsForBranch(branchName);
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching branches", e);
                SwingUtilities.invokeLater(() -> {
                    branchTableModel.setRowCount(0);
                    branchTableModel.addRow(new Object[]{"", "Error fetching branches: " + e.getMessage()});

                    // We already have remoteBranchTable and remoteBranchTableModel as class fields
                    remoteBranchTableModel.setRowCount(0);

                    // Clear other panels
                    commitsTableModel.setRowCount(0);
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();
                });
            }
            return null;
        });
    }

    /**
     * Updates the commits list for the selected branch
     */
    private void updateCommitsForBranch(String branchName) {
        contextManager.submitBackgroundTask("Fetching commits for " + branchName, () -> {
            try {
                var commits = getRepo().listCommitsDetailed(branchName);
                var isLocalBranch = branchName.equals(getRepo().getCurrentBranch()) || !branchName.contains("/");
                var unpushedCommitIds = new HashSet<String>();
                var canPush = false;

                // Check if this branch has unpushed commits and an upstream branch
                if (isLocalBranch) {
                    try {
                        unpushedCommitIds.addAll(getRepo().getUnpushedCommitIds(branchName));
                        // Can push if there are unpushed commits and there's an upstream
                        canPush = !unpushedCommitIds.isEmpty() && getRepo().hasUpstreamBranch(branchName);
                    } catch (IOException e) {
                        logger.warn("Could not check for unpushed commits: {}", e.getMessage());
                    }
                }

                var finalCanPush = canPush;
                var unpushedCount = unpushedCommitIds.size();

                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);

                    // Clear changes panel until a commit is selected
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();

                    // Update push button state
                    pushButton.setEnabled(finalCanPush);
                    pushButton.setToolTipText(finalCanPush
                                                      ? "Push " + unpushedCount + " commit(s) to remote"
                                                      : "No unpushed commits or no upstream branch configured");

                    if (commits.isEmpty()) {
                        // No commits found
                        return;
                    }

                    var today = java.time.LocalDate.now();

                    for (var commit : commits) {
                        // Format the date
                        var formattedDate = formatCommitDate(commit.date(), today);

                        // Check if this commit is unpushed
                        var isUnpushed = unpushedCommitIds.contains(commit.id());

                        commitsTableModel.addRow(new Object[]{
                                commit.message(),
                                commit.author(),
                                formattedDate,
                                commit.id(),  // Store commit ID in hidden column
                                isUnpushed
                        });
                    }

                    // Select first commit automatically
                    if (commitsTableModel.getRowCount() > 0) {
                        commitsTable.setRowSelectionInterval(0, 0);
                        updateChangesForCommits(new int[]{0});
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching commits for branch: " + branchName, e);
                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(new Object[]{
                            "Error fetching commits: " + e.getMessage(), "", "", ""
                    });

                    // Clear changes panel
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();
                });
            }
            return null;
        });
    }

    /**
     * Format commit date to show "HH:MM:SS today" for today's dates
     */
    private String formatCommitDate(String dateStr, java.time.LocalDate today) {
        try {
            var dateTime = java.time.LocalDateTime.parse(
                    dateStr.replace(" ", "T").replaceAll(" \\(.+\\)$", ""));

            if (dateTime.toLocalDate().equals(today)) {
                return String.format("%02d:%02d:%02d today",
                                     dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond());
            }
            return dateStr;
        } catch (Exception e) {
            // If there's any error in parsing, return the original date
            return dateStr;
        }
    }

    private GitRepo getRepo() {
        return contextManager.getProject().getRepo();
    }

    /**
     * Helper method to get branch name from display text
     */
    private String getBranchNameFromDisplay(String displayText) {
        return displayText;
    }

    /**
     * Expands all nodes in a JTree
     */
    private void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; i++) {
            tree.expandRow(i);
        }

        if (tree.getRowCount() > rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount());
        }
    }

    /**
     * Checkout a branch
     */
    private void checkoutBranch(String branchName) {
        contextManager.submitUserTask("Checking out branch: " + branchName, () -> {
            try {
                if (branchName.startsWith("origin/") || branchName.contains("/")) {
                    // This is a remote branch, use the special checkout method
                    getRepo().checkoutRemoteBranch(branchName);
                    chrome.toolOutput("Created local tracking branch for " + branchName);
                } else {
                    // This is a local branch, use regular checkout
                    getRepo().checkout(branchName);
                }
                updateBranchList();
            } catch (IOException e) {
                logger.error("Error checking out branch: {}", branchName, e);
                chrome.toolErrorRaw(e.getMessage());
            }
            return null;
        });
    }

    /**
     * Merge a branch into HEAD
     */
    private void mergeBranchIntoHead(String branchName) {
        contextManager.submitUserTask("Merging branch: " + branchName, () -> {
            try {
                getRepo().mergeIntoHead(branchName);
                chrome.toolOutput("Branch '" + branchName + "' was successfully merged into HEAD.");
                updateBranchList();
            } catch (IOException e) {
                logger.error("Error merging branch: {}", branchName, e);
                chrome.toolErrorRaw(e.getMessage());
            }
            return null;
        });
    }

    /**
     * Rename a branch
     */
    private void renameBranch(String branchName) {
        var newName = JOptionPane.showInputDialog(this,
                                                  "Enter new name for branch '" + branchName + "':",
                                                  "Rename Branch", JOptionPane.QUESTION_MESSAGE);

        if (newName != null && !newName.trim().isEmpty()) {
            contextManager.submitUserTask("Renaming branch: " + branchName, () -> {
                try {
                    getRepo().renameBranch(branchName, newName);
                    updateBranchList();
                } catch (IOException e) {
                    logger.error("Error renaming branch: {}", branchName, e);
                    chrome.toolErrorRaw("Error renaming branch: " + e.getMessage());
                }
                return null;
            });
        }
    }

    /**
     * Delete a branch
     */
    private void deleteBranch(String branchName) {
        contextManager.submitUserTask("Checking branch merge status", () -> {
            try {
                var isMerged = getRepo().isBranchMerged(branchName);

                SwingUtilities.invokeLater(() -> {
                    if (isMerged) {
                        var result = JOptionPane.showConfirmDialog(this,
                                                                   "Are you sure you want to delete branch '" + branchName + "'?",
                                                                   "Delete Branch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

                        if (result == JOptionPane.YES_OPTION) {
                            performBranchDeletion(branchName, false);
                        }
                    } else {
                        // Branch is not merged, warn user and offer force delete
                        Object[] options = {"Force Delete", "Cancel"};
                        var result = JOptionPane.showOptionDialog(this,
                                                                  "Branch '" + branchName + "' is not fully merged.\nChanges on this branch will be lost if deleted.\nDo you want to force delete it?",
                                                                  "Unmerged Branch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                                                                  null, options, options[1]);

                        if (result == 0) { // Force Delete
                            performBranchDeletion(branchName, true);
                        }
                    }
                });
            } catch (IOException e) {
                logger.error("Error checking branch merge status: {}", branchName, e);
                chrome.toolErrorRaw("Error checking branch status: " + e.getMessage());
            }
            return null;
        });
    }

    /**
     * Perform the actual branch deletion
     */
    private void performBranchDeletion(String branchName, boolean force) {
        contextManager.submitUserTask("Deleting branch: " + branchName, () -> {
            try {
                if (force) {
                    getRepo().forceDeleteBranch(branchName);
                } else {
                    getRepo().deleteBranch(branchName);
                }
                updateBranchList();
                chrome.toolOutput("Branch '" + branchName + "' " + (force ? "force " : "") + "deleted successfully.");
            } catch (IOException e) {
                logger.error("Error deleting branch: {}", branchName, e);
                chrome.toolErrorRaw(e.getMessage());
            }
            return null;
        });
    }

    /**
     * Helper method to check if any file nodes are selected (not just directory nodes)
     */
    private boolean hasFileNodesSelected(TreePath[] paths) {
        for (var path : paths) {
            var node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node != changesRootNode && node.getParent() != null && node.getParent() != changesRootNode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Revert a commit
     */
    private void revertCommit(String commitId) {
        var selectedRows = commitsTable.getSelectedRows();
        if (selectedRows.length == 1) {
            try {
                getRepo().revertCommit(commitId);
                chrome.toolOutput("Commit was successfully reverted.");

                // Refresh the current branch's commits
                var branchRow = branchTable.getSelectedRow();
                if (branchRow != -1) {
                    var branchDisplay = (String) branchTableModel.getValueAt(branchRow, 1);
                    var branchName = getBranchNameFromDisplay(branchDisplay);
                    updateCommitsForBranch(branchName);
                }
            } catch (IOException e) {
                logger.error("Error reverting commit: {}", commitId, e);
                chrome.toolErrorRaw("Error reverting commit: " + e.getMessage());
            }
        } else {
            chrome.toolErrorRaw("Can only revert a single commit at a time");
        }
    }

    /**
     * Push the current branch to remote
     */
    private void pushBranch() {
        var selectedRow = branchTable.getSelectedRow();
        if (selectedRow == -1) return;

        var branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
        var currentBranch = getBranchNameFromDisplay(branchDisplay);

        contextManager.submitBackgroundTask("Pushing branch " + currentBranch, () -> {
            try {
                getRepo().push();
                SwingUtilities.invokeLater(() -> {
                    chrome.toolOutput("Successfully pushed " + currentBranch + " to remote");
                    // Refresh the commits list to update unpushed status
                    updateCommitsForBranch(currentBranch);
                });
            } catch (IOException e) {
                logger.error("Error pushing branch: {}", currentBranch, e);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolErrorRaw("Error pushing branch: " + e.getMessage());
                });
            }
            return null;
        });
    }

    /**
     * Update the changes tree for multiple selected commits
     */
    private void updateChangesForCommits(int[] selectedRows) {
        contextManager.submitBackgroundTask("Fetching changes for commits", () -> {
            try {
                var allChangedFiles = new HashSet<String>();

                for (var row : selectedRows) {
                    if (row >= 0 && row < commitsTableModel.getRowCount()) {
                        var commitId = (String) commitsTableModel.getValueAt(row, 3);
                        var changedFiles = getRepo().listChangedFilesInCommit(commitId);
                        allChangedFiles.addAll(changedFiles);
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    changesRootNode.removeAllChildren();

                    if (allChangedFiles.isEmpty()) {
                        changesTreeModel.reload();
                        return;
                    }

                    Map<String, List<String>> filesByDir = new HashMap<>();
                    for (var file : allChangedFiles) {
                        int lastSlash = file.lastIndexOf('/');
                        if (lastSlash > 0) {
                            var dir = file.substring(0, lastSlash);
                            var fileName = file.substring(lastSlash + 1);
                            filesByDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(fileName);
                        } else {
                            filesByDir.computeIfAbsent("", k -> new ArrayList<>()).add(file);
                        }
                    }

                    for (var entry : filesByDir.entrySet()) {
                        var dirPath = entry.getKey();
                        var files = entry.getValue();

                        DefaultMutableTreeNode dirNode;
                        if (dirPath.isEmpty()) {
                            dirNode = changesRootNode;
                        } else {
                            dirNode = new DefaultMutableTreeNode(dirPath);
                            changesRootNode.add(dirNode);
                        }

                        for (var f : files) {
                            dirNode.add(new DefaultMutableTreeNode(f));
                        }
                    }

                    changesTreeModel.reload();
                    expandAllNodes(changesTree, 0, changesTree.getRowCount());
                });
            } catch (Exception e) {
                logger.error("Error fetching changes for multiple commits", e);
                SwingUtilities.invokeLater(() -> {
                    changesRootNode.removeAllChildren();
                    changesRootNode.add(new DefaultMutableTreeNode("Error: " + e.getMessage()));
                    changesTreeModel.reload();
                });
            }
            return null;
        });
    }

    /**
     * Helper method to get file paths from selected tree nodes
     */
    private List<String> getSelectedFilePaths(TreePath[] paths) {
        var selectedFiles = new ArrayList<String>();

        for (var path : paths) {
            var node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node == changesRootNode || node.getParent() == changesRootNode) {
                continue;
            }

            var fileName = node.getUserObject().toString();
            var dirPath = ((DefaultMutableTreeNode) node.getParent()).getUserObject().toString();
            var filePath = dirPath.isEmpty() ? fileName : dirPath + "/" + fileName;

            selectedFiles.add(filePath);
        }

        return selectedFiles;
    }

    /**
     * Add a commit range to context
     */
    private void addCommitRangeToContext(int[] selectedRows) {
        contextManager.submitContextTask("Adding commit range to context", () -> {
            try {
                if (selectedRows.length == 0 || commitsTableModel.getRowCount() == 0) {
                    chrome.toolOutput("No commits selected or commits table is empty");
                    return null;
                }

                var sortedRows = selectedRows.clone();
                Arrays.sort(sortedRows);

                if (sortedRows[0] < 0 || sortedRows[sortedRows.length - 1] >= commitsTableModel.getRowCount()) {
                    chrome.toolOutput("Invalid commit selection");
                    return null;
                }

                var firstCommitId = (String) commitsTableModel.getValueAt(sortedRows[0], 3);
                var lastCommitId = (String) commitsTableModel.getValueAt(sortedRows[sortedRows.length - 1], 3);

                var diff = getRepo().showDiff(lastCommitId, firstCommitId + "^");
                if (diff.isEmpty()) {
                    chrome.toolOutput("No changes found in the selected commit range");
                    return null;
                }

                var firstShortHash = firstCommitId.substring(0, 7);
                var lastShortHash = lastCommitId.substring(0, 7);
                var description = String.format("git %s..%s: Changes across %d commits",
                                                firstShortHash, lastShortHash, selectedRows.length);

                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.toolOutput("Added changes for commit range to context");
            } catch (Exception ex) {
                logger.error("Error adding commit range to context", ex);
                chrome.toolErrorRaw("Error adding commit range to context: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Add multiple files from a commit range to context
     */
    private void addFilesChangeToContext(int[] selectedRows, List<String> filePaths) {
        contextManager.submitContextTask("Adding file changes from range to context", () -> {
            try {
                if (selectedRows.length == 0 || commitsTableModel.getRowCount() == 0) {
                    chrome.toolOutput("No commits selected or commits table is empty");
                    return null;
                }

                var sortedRows = selectedRows.clone();
                Arrays.sort(sortedRows);

                if (sortedRows[0] < 0 || sortedRows[sortedRows.length - 1] >= commitsTableModel.getRowCount()) {
                    chrome.toolOutput("Invalid commit selection");
                    return null;
                }

                var firstCommitId = (String) commitsTableModel.getValueAt(sortedRows[0], 3);
                var lastCommitId = (String) commitsTableModel.getValueAt(sortedRows[sortedRows.length - 1], 3);

                var diffs = filePaths.stream()
                        .map(filePath -> getRepo().showFileDiff(lastCommitId, firstCommitId + "^", filePath))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n\n"));

                if (diffs.isEmpty()) {
                    chrome.toolOutput("No changes found for the selected files in the commit range");
                    return null;
                }

                var firstShortHash = firstCommitId.substring(0, 7);
                var lastShortHash = lastCommitId.substring(0, 7);
                var fileNames = filePaths.stream()
                        .map(path -> {
                            int slashPos = path.lastIndexOf('/');
                            return slashPos >= 0 ? path.substring(slashPos + 1) : path;
                        })
                        .collect(Collectors.toList());
                var fileList = String.join(", ", fileNames);
                var description = String.format("git %s..%s [%s]: Changes across %d commits",
                                                firstShortHash, lastShortHash, fileList, selectedRows.length);

                var fragment = new ContextFragment.StringFragment(diffs, description);
                contextManager.addVirtualFragment(fragment);
                chrome.toolOutput("Added changes for selected files in commit range to context");
            } catch (Exception ex) {
                logger.error("Error adding file changes from range to context", ex);
                chrome.toolErrorRaw("Error adding file changes from range to context: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Compare a commit with local working copy
     */
    private void compareCommitWithLocal(String commitId, String commitMessage) {
        contextManager.submitContextTask("Comparing commit with local", () -> {
            try {
                var diff = getRepo().showDiff("HEAD", commitId);
                var shortHash = commitId.substring(0, 7);

                if (diff.isEmpty()) {
                    chrome.toolOutput("No changes between commit " + shortHash + " and local working copy");
                    return null;
                }

                var description = "git local vs " + shortHash + ": " + commitMessage;

                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.toolOutput("Added comparison with local to context");
            } catch (Exception ex) {
                logger.error("Error comparing commit with local: {}", commitId, ex);
                chrome.toolErrorRaw("Error comparing commit with local: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Compare files with local working copy
     */
    private void compareFilesWithLocal(String commitId, List<String> filePaths) {
        contextManager.submitContextTask("Comparing files with local", () -> {
            try {
                var allDiffs = filePaths.stream()
                        .map(filePath -> getRepo().showFileDiff("HEAD", commitId, filePath))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n\n"));

                if (allDiffs.isEmpty()) {
                    chrome.toolOutput("No differences found between selected files and local working copy");
                    return null;
                }

                var shortHash = commitId.substring(0, 7);
                var fileList = new StringBuilder();
                filePaths.forEach(fp -> {
                    if (fileList.length() > 0) fileList.append(", ");
                    int lastSlash = fp.lastIndexOf('/');
                    fileList.append(lastSlash >= 0 ? fp.substring(lastSlash + 1) : fp);
                });
                var description = "git local vs " + shortHash + " [" + fileList + "]";

                var fragment = new ContextFragment.StringFragment(allDiffs, description);
                contextManager.addVirtualFragment(fragment);
                chrome.toolOutput("Added comparison with local for " + filePaths.size() + " file(s) to context");
            } catch (Exception ex) {
                logger.error("Error comparing files with local", ex);
                chrome.toolErrorRaw("Error comparing files with local: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Edit a specific file
     */
    private void editFile(String filePath) {
        var files = new ArrayList<RepoFile>();
        files.add(contextManager.toFile(filePath));
        contextManager.editFiles(files);
    }

    /**
     * Search commits for the given query
     */
    private void searchCommits(String query) {
        contextManager.submitUserTask("Searching commits for: " + query, () -> {
            try {
                var searchResults = getRepo().searchCommits(query);

                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);

                    // Clear changes panel until a commit is selected
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();

                    if (searchResults.isEmpty()) {
                        chrome.toolOutput("No commits found matching: " + query);
                        return;
                    }

                    for (var commit : searchResults) {
                        commitsTableModel.addRow(new Object[]{
                                commit.message(),
                                commit.author(),
                                commit.date(),
                                commit.id()
                        });
                    }

                    chrome.toolOutput("Found " + searchResults.size() + " commits matching: " + query);

                    if (commitsTableModel.getRowCount() > 0) {
                        commitsTable.setRowSelectionInterval(0, 0);
                        updateChangesForCommits(new int[]{0});
                    }
                });
            } catch (Exception e) {
                logger.error("Error searching commits: {}", query, e);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolErrorRaw("Error searching commits: " + e.getMessage());
                });
            }
            return null;
        });
    }

    /**
     * Update the stash table with latest stashes from repo
     */
    private void updateStashList() {
        contextManager.submitUserTask("Fetching stashes", () -> {
            try {
                var stashes = getRepo().listStashes();

                SwingUtilities.invokeLater(() -> {
                    stashTableModel.setRowCount(0);

                    if (stashes.isEmpty()) {
                        return;
                    }

                    var today = java.time.LocalDate.now();

                    for (var stash : stashes) {
                        var formattedDate = formatCommitDate(stash.date(), today);

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

    /**
     * Pop (apply and remove) the stash at the given index
     */
    private void popStash(int index) {
        logger.debug("Attempting to pop stash at index: {}", index);
        contextManager.submitUserTask("Popping stash", () -> {
            try {
                getRepo().popStash(index);
                logger.debug("Stash popped successfully, index: {}", index);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolOutput("Stash popped successfully");
                    updateStashList();
                    updateSuggestCommitButton();  // Refresh the uncommitted files panel
                });
            } catch (Exception e) {
                logger.error("Error popping stash", e);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolErrorRaw("Error popping stash: " + e.getMessage());
                });
            }
            return null;
        });
    }

    /**
     * Apply the stash at the given index (without removing it)
     */
    private void applyStash(int index) {
        logger.debug("Attempting to apply stash at index: {}", index);
        contextManager.submitUserTask("Applying stash", () -> {
            try {
                getRepo().applyStash(index);
                logger.debug("Stash applied successfully, index: {}", index);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolOutput("Stash applied successfully");
                    updateSuggestCommitButton();  // Refresh the uncommitted files panel
                });
            } catch (Exception e) {
                logger.error("Error applying stash", e);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolErrorRaw("Error applying stash: " + e.getMessage());
                });
            }
            return null;
        });
    }

    /**
     * Drop (delete) the stash at the given index
     */
    private void dropStash(int index) {
        logger.debug("Preparing to drop stash at index: {}", index);
        var result = JOptionPane.showConfirmDialog(this,
                                                   "Are you sure you want to delete this stash?",
                                                   "Delete Stash",
                                                   JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (result != JOptionPane.YES_OPTION) {
            logger.debug("User cancelled stash drop operation");
            return;
        }

        contextManager.submitUserTask("Dropping stash", () -> {
            try {
                logger.debug("Executing stash drop for index: {}", index);
                getRepo().dropStash(index);
                logger.debug("Stash dropped successfully");
                SwingUtilities.invokeLater(() -> {
                    chrome.toolOutput("Stash dropped successfully");
                    updateStashList();
                });
            } catch (Exception e) {
                logger.error("Error dropping stash", e);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolErrorRaw("Error dropping stash: " + e.getMessage());
                });
            }
            return null;
        });
    }

    /**
     * Check branch context menu state based on selection
     */
    private void checkBranchContextMenuState() {
                int selectedRow = branchTable.getSelectedRow();
                var isLocal = selectedRow != -1;  // If selected in local branch table, it's a local branch

                var menu = branchTable.getComponentPopupMenu();
                if (menu != null) {
                    menu.getComponent(2).setEnabled(isLocal); // Rename
                    menu.getComponent(3).setEnabled(isLocal); // Delete
                }
            }
}
