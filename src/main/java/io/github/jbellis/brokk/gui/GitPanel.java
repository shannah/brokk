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
        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        int tableRowHeight = uncommittedFilesTable.getRowHeight();
        int headerHeight = 22; // Approximate header height
        int scrollbarHeight = 3; // Extra padding for scrollbar
        uncommittedScrollPane.setPreferredSize(new Dimension(600, (tableRowHeight * 10) + headerHeight + scrollbarHeight));

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
            String message = commitMessageArea.getText().trim();
            assert !message.isEmpty();

            // Filter out lines starting with # (comments)
            String stashDescription = Arrays.stream(message.split("\n"))
                .filter(line -> !line.trim().startsWith("#"))
                .collect(Collectors.joining("\n"))
                .trim();
            
            // Get selected files if any
            var selectedFiles = getSelectedFilesFromTable();
            
            contextManager.submitUserTask("Stashing changes", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        // Stash all files
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    } else {
                        // For selected files, we have to stage them first, then stash
                        for (String file : selectedFiles) {
                            getRepo().add(file);
                        }
                        // Then create a stash with the staged files
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    }

                    SwingUtilities.invokeLater(() -> {
                        if (selectedFiles.isEmpty()) {
                            chrome.toolOutput("All changes stashed successfully");
                        } else {
                            String fileList = selectedFiles.size() <= 3 ? 
                                String.join(", ", selectedFiles) : 
                                selectedFiles.size() + " files";
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
                String text = commitMessageArea.getText().trim();
                boolean hasNonCommentText = Arrays.stream(text.split("\n"))
                    .anyMatch(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"));
                
                // Only enable commit/stash if there's actual content and there are uncommitted changes
                boolean enableButtons = hasNonCommentText && suggestMessageButton.isEnabled();
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
                        String commitId = contextManager.getProject().getRepo().commitFiles(allDirtyFiles, msg);
                    } else {
                        // Commit selected files using the method that does exist
                        String commitId = contextManager.getProject().getRepo().commitFiles(selectedFiles, msg);
                    }

                    SwingUtilities.invokeLater(() -> {
                        // Get the first line of the commit message for the output
                        String firstLine = msg.contains("\n") ? 
                            msg.substring(0, msg.indexOf('\n')) : msg;
                        
                        try {
                            // Get the latest commit ID to display
                            String shortHash = getRepo().getCurrentCommitId().substring(0, 7);
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
        JPopupMenu stashContextMenu = new JPopupMenu();
        JMenuItem popStashItem = new JMenuItem("Pop Stash");
        JMenuItem applyStashItem = new JMenuItem("Apply Stash");
        JMenuItem dropStashItem = new JMenuItem("Drop Stash");
        
        stashContextMenu.add(popStashItem);
        stashContextMenu.add(applyStashItem);
        stashContextMenu.add(dropStashItem);
        
        stashTable.setComponentPopupMenu(stashContextMenu);
        
        // Use the popup menu listener to ensure selection
        stashContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
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
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        
        // Add action listeners for stash operations
        popStashItem.addActionListener(e -> {
            int selectedRow = stashTable.getSelectedRow();
            if (selectedRow != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(selectedRow, 0));
                popStash(index);
            }
        });
        
        applyStashItem.addActionListener(e -> {
            int selectedRow = stashTable.getSelectedRow();
            if (selectedRow != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(selectedRow, 0));
                applyStash(index);
            }
        });
        
        dropStashItem.addActionListener(e -> {
            int selectedRow = stashTable.getSelectedRow();
            if (selectedRow != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(selectedRow, 0));
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
        
        // Create four panels for the log tab
        var logPanel = new JPanel(new GridLayout(1, 4));
        logTab.add(logPanel, BorderLayout.CENTER);
        
        // Create branch panel
        var branchesPanel = new JPanel(new BorderLayout());
        branchesPanel.setBorder(BorderFactory.createTitledBorder("Branches"));
        branchTableModel = new DefaultTableModel(new Object[]{"", "Branch"}, 0) {
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
        
        branchesPanel.add(new JScrollPane(branchTable), BorderLayout.CENTER);
        
        // Add a button to refresh branches
        var branchButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var refreshBranchesButton = new JButton("Refresh");
        refreshBranchesButton.addActionListener(e -> updateBranchList());
        branchButtonPanel.add(refreshBranchesButton);
        branchesPanel.add(branchButtonPanel, BorderLayout.SOUTH);
        
        // Create commits panel
        var commitsPanel = new JPanel(new BorderLayout());
        commitsPanel.setBorder(BorderFactory.createTitledBorder("Commits"));
        commitsTableModel = new DefaultTableModel(new Object[]{"Message", "Author", "Date", "ID", "Unpushed"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch(columnIndex) {
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
            public Component getTableCellRendererComponent(JTable table, Object value, 
                                                           boolean isSelected, boolean hasFocus, 
                                                           int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                if (!isSelected) {
                    Boolean unpushed = (Boolean) table.getModel().getValueAt(row, 4);
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
                int[] selectedRows = commitsTable.getSelectedRows();
                if (selectedRows.length == 1) {
                    // For single selection, just show that commit's changes using the multiple commits method
                    updateChangesForCommits(selectedRows);
                } else if (selectedRows.length > 1) {
                    // For multiple selection, show the union of changes
                    updateChangesForCommits(selectedRows);
                }
            }
        });
        
        // Add context menu for commits table
        JPopupMenu commitsContextMenu = new JPopupMenu();
        JMenuItem addToContextItem = new JMenuItem("Add Changes to Context");
        JMenuItem compareWithLocalItem = new JMenuItem("Compare with Local");
        JMenuItem revertCommitItem = new JMenuItem("Revert Commit");

        commitsContextMenu.add(addToContextItem);
        commitsContextMenu.add(compareWithLocalItem);
        commitsContextMenu.add(revertCommitItem);

        commitsTable.setComponentPopupMenu(commitsContextMenu);
        
        // Use the popup menu listener to ensure selection and update item states
        commitsContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, commitsTable);
                    int row = commitsTable.rowAtPoint(point);
                    
                    if (row >= 0) {
                        // If not part of existing selection, set to single selection
                        if (!commitsTable.isRowSelected(row)) {
                            commitsTable.setRowSelectionInterval(row, row);
                        }
                        // Otherwise keep the current multiple selection
                    }
                    
                    // Update menu items state based on selection
                    int[] selectedRows = commitsTable.getSelectedRows();
                    compareWithLocalItem.setEnabled(selectedRows.length == 1);
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        
        addToContextItem.addActionListener(e -> {
            int[] selectedRows = commitsTable.getSelectedRows();
            if (selectedRows.length >= 1) {
                addCommitRangeToContext(selectedRows);
            }
        });
        
        compareWithLocalItem.addActionListener(e -> {
            int[] selectedRows = commitsTable.getSelectedRows();
            if (selectedRows.length == 1) {
                // Only active for single commit
                String commitId = (String) commitsTableModel.getValueAt(selectedRows[0], 3);
                String commitMessage = (String) commitsTableModel.getValueAt(selectedRows[0], 0);
                String firstLine = commitMessage.contains("\n") ?
                    commitMessage.substring(0, commitMessage.indexOf('\n')) : commitMessage;
                compareCommitWithLocal(commitId, firstLine);
            }
        });
        
        revertCommitItem.addActionListener(e -> {
            int selectedRow = commitsTable.getSelectedRow();
            if (selectedRow != -1) {
                String commitId = (String) commitsTableModel.getValueAt(selectedRow, 3);
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

        // Create changes panel
        var changesPanel = new JPanel(new BorderLayout());
        changesPanel.setBorder(BorderFactory.createTitledBorder("Changes"));
        changesRootNode = new DefaultMutableTreeNode("Changes");
        changesTreeModel = new DefaultTreeModel(changesRootNode);
        changesTree = new JTree(changesTreeModel);
        changesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        changesPanel.add(new JScrollPane(changesTree), BorderLayout.CENTER);
        
        // Add context menu for changes tree
        JPopupMenu changesContextMenu = new JPopupMenu();
        JMenuItem addFileToContextItem = new JMenuItem("Add Changes to Context");
        JMenuItem compareFileWithLocalItem = new JMenuItem("Compare with Local");
        JMenuItem editFileItem = new JMenuItem("Edit File");

        changesContextMenu.add(addFileToContextItem);
        changesContextMenu.add(compareFileWithLocalItem);
        changesContextMenu.add(editFileItem);
        
        changesTree.setComponentPopupMenu(changesContextMenu);
        
        // Use the popup menu listener to ensure selection and update menu item states
        changesContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, changesTree);
                    int row = changesTree.getRowForLocation(point.x, point.y);
                    
                    if (row >= 0) {
                        // If right-clicked on a node that's not already selected, select just that node
                        if (!changesTree.isRowSelected(row)) {
                            changesTree.setSelectionRow(row);
                        }
                        // Otherwise keep the existing multiple selection
                    }
                    
                    // Update menu item states based on selection
                    TreePath[] paths = changesTree.getSelectionPaths();
                    boolean hasFileSelection = paths != null && paths.length > 0 &&
                                              hasFileNodesSelected(paths);
                    
                    int[] selectedRows = commitsTable.getSelectedRows();
                    boolean isSingleCommit = selectedRows.length == 1;
                    
                    addFileToContextItem.setEnabled(hasFileSelection);
                    compareFileWithLocalItem.setEnabled(hasFileSelection && isSingleCommit);
                    editFileItem.setEnabled(hasFileSelection);
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        
        addFileToContextItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                // Get the selected files paths
                List<String> selectedFiles = getSelectedFilePaths(paths);
                
                if (!selectedFiles.isEmpty()) {
                    // Get current selected commit IDs
                    int[] selectedRows = commitsTable.getSelectedRows();
                    if (selectedRows.length >= 1) {
                        addFilesChangeToContext(selectedRows, selectedFiles);
                    }
                }
            }
        });
        
        compareFileWithLocalItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                // Get the selected files paths
                List<String> selectedFiles = getSelectedFilePaths(paths);
                
                if (!selectedFiles.isEmpty()) {
                    // Get current selected commit ID - only works with single commit selection
                    int[] selectedRows = commitsTable.getSelectedRows();
                    if (selectedRows.length == 1) {
                        String commitId = (String) commitsTableModel.getValueAt(selectedRows[0], 3);
                        compareFilesWithLocal(commitId, selectedFiles);
                    }
                }
            }
        });

        editFileItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                // Get the selected files paths
                List<String> selectedFiles = getSelectedFilePaths(paths);
                
                for (String filePath : selectedFiles) {
                    editFile(filePath);
                }
            }
        });
        
        // Create search panel
        var searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(BorderFactory.createTitledBorder("Search"));
        var searchField = new JTextField();
        var searchButtonPanel = new JPanel(new FlowLayout());
        
        var textSearchButton = new JButton("Text Search");
        textSearchButton.addActionListener(e -> {
            String query = searchField.getText().trim();
            if (!query.isEmpty()) {
                searchCommits(query);
            }
        });
        
        var resetButton = new JButton("Reset");
        resetButton.addActionListener(e -> {
            searchField.setText("");
            // Reset to show all commits for the current branch
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                String branchName = getBranchNameFromDisplay(branchDisplay);
                updateCommitsForBranch(branchName);
            }
        });
        
        var aiSearchButton = new JButton("AI Search");
        
        searchButtonPanel.add(textSearchButton);
        searchButtonPanel.add(resetButton);
        searchButtonPanel.add(aiSearchButton);
        searchPanel.add(searchField, BorderLayout.NORTH);
        searchPanel.add(searchButtonPanel, BorderLayout.CENTER);
        
        // Add action for Enter key in search field
        searchField.addActionListener(e -> {
            String query = searchField.getText().trim();
            if (!query.isEmpty()) {
                searchCommits(query);
            }
        });
        
        // Add the panels to log panel
        logPanel.add(branchesPanel);
        logPanel.add(commitsPanel);
        logPanel.add(changesPanel);
        logPanel.add(searchPanel);
        
        // Initialize branch list and select current branch (call after all UI setup)
        SwingUtilities.invokeLater(() -> {
            updateBranchList();
            updateStashList();
        });
        
        // Add selection listener to branch table
        branchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && branchTable.getSelectedRow() != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(branchTable.getSelectedRow(), 1);
                if (branchDisplay.startsWith("Local: ")) {
                    String branchName = branchDisplay.substring("Local: ".length());
                    updateCommitsForBranch(branchName);
                } else if (branchDisplay.startsWith("Remote: ")) {
                    String branchName = branchDisplay.substring("Remote: ".length());
                    updateCommitsForBranch(branchName);
                }
            }
        });
        
        // Add context menu for branch table
        JPopupMenu branchContextMenu = new JPopupMenu();
        JMenuItem checkoutItem = new JMenuItem("Checkout");
        JMenuItem mergeItem = new JMenuItem("Merge into HEAD");
        JMenuItem renameItem = new JMenuItem("Rename");
        JMenuItem deleteItem = new JMenuItem("Delete");

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
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, branchTable);
                    int row = branchTable.rowAtPoint(point);
                    if (row >= 0) {
                        branchTable.setRowSelectionInterval(row, row);
                    }
                    checkBranchContextMenuState();
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        
        checkoutItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                String branchName = getBranchNameFromDisplay(branchDisplay);
                checkoutBranch(branchName);
            }
        });

        mergeItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                String branchName = getBranchNameFromDisplay(branchDisplay);
                mergeBranchIntoHead(branchName);
            }
        });

        renameItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                if (branchDisplay.startsWith("Local: ")) {
                    String branchName = branchDisplay.substring("Local: ".length());
                    renameBranch(branchName);
                }
            }
        });

        deleteItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                if (branchDisplay.startsWith("Local: ")) {
                    String branchName = branchDisplay.substring("Local: ".length());
                    deleteBranch(branchName);
                }
            }
        });
        
    }

    /**
     * Gets a list of the selected files from the table
     */
    private List<String> getSelectedFilesFromTable() {
        List<String> selectedFiles = new ArrayList<>();
        DefaultTableModel model = (DefaultTableModel) uncommittedFilesTable.getModel();
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();

        for (int row : selectedRows) {
            String filename = (String) model.getValueAt(row, 0);
            String path = (String) model.getValueAt(row, 1);
            String fullPath = path.isEmpty() ? filename : path + "/" + filename;
            selectedFiles.add(fullPath);
        }

        return selectedFiles;
    }

    /**
     * Updates the uncommitted files table and the state of the suggest commit button
     */
    public void updateSuggestCommitButton() {
        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            try {
                List<String> uncommittedFiles = getRepo().getUncommittedFileNames();
                SwingUtilities.invokeLater(() -> {
                    DefaultTableModel model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    model.setRowCount(0);

                    if (uncommittedFiles.isEmpty()) {
                        suggestMessageButton.setEnabled(false);
                        commitButton.setEnabled(false);
                        stashButton.setEnabled(false);
                    } else {
                        for (String filePath : uncommittedFiles) {
                            // Split into filename and path
                            int lastSlash = filePath.lastIndexOf('/');
                            String filename = (lastSlash >= 0) ? filePath.substring(lastSlash + 1) : filePath;
                            String path = (lastSlash >= 0) ? filePath.substring(0, lastSlash) : "";

                            model.addRow(new Object[]{filename, path});
                        }
                        suggestMessageButton.setEnabled(true);
                        
                        // Check if there's content in the commit message area to enable commit/stash buttons
                        String text = commitMessageArea.getText().trim();
                        boolean hasNonCommentText = Arrays.stream(text.split("\n"))
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
                String currentBranch = getRepo().getCurrentBranch();
                List<String> localBranches = getRepo().listLocalBranches();
                List<String> remoteBranches = getRepo().listRemoteBranches();

                SwingUtilities.invokeLater(() -> {
                    branchTableModel.setRowCount(0);
                    int currentBranchRow = -1;

                    // Add local branches with a prefix
                    for (String branch : localBranches) {
                        String checkmark = branch.equals(currentBranch) ? "✓" : "";
                        branchTableModel.addRow(new Object[]{checkmark, "Local: " + branch});
                        
                        // Record the current branch row for later selection
                        if (branch.equals(currentBranch)) {
                            currentBranchRow = branchTableModel.getRowCount() - 1;
                        }
                    }

                    // Add remote branches with a prefix
                    for (String branch : remoteBranches) {
                        branchTableModel.addRow(new Object[]{"", "Remote: " + branch});
                    }

                    // Select current branch automatically and update commits
                    if (currentBranchRow >= 0) {
                        branchTable.setRowSelectionInterval(currentBranchRow, currentBranchRow);
                        updateCommitsForBranch(currentBranch);
                    } else if (branchTableModel.getRowCount() > 0) {
                        // If no current branch found, select the first one
                        branchTable.setRowSelectionInterval(0, 0);
                        String branchDisplay = (String) branchTableModel.getValueAt(0, 1);
                        String branchName = getBranchNameFromDisplay(branchDisplay);
                        updateCommitsForBranch(branchName);
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching branches", e);
                SwingUtilities.invokeLater(() -> {
                    branchTableModel.setRowCount(0);
                    branchTableModel.addRow(new Object[]{"", "Error fetching branches: " + e.getMessage()});

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
                List<CommitInfo> commits = getRepo().listCommitsDetailed(branchName);
                boolean isLocalBranch = branchName.equals(getRepo().getCurrentBranch()) || 
                                       !branchName.contains("/");
                boolean canPush = false;
                
                // Check if this branch has unpushed commits and an upstream branch
                Set<String> unpushedCommitIds = new HashSet<>();
                if (isLocalBranch) {
                    try {
                        unpushedCommitIds.addAll(getRepo().getUnpushedCommitIds(branchName));
                        // Can push if there are unpushed commits and there's an upstream
                        canPush = !unpushedCommitIds.isEmpty() && getRepo().hasUpstreamBranch(branchName);
                    } catch (IOException e) {
                        logger.warn("Could not check for unpushed commits: {}", e.getMessage());
                    }
                }

                // Update push button status
                final boolean finalCanPush = canPush;
                final int unpushedCount = unpushedCommitIds.size();

                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);

                    // Clear changes panel until a commit is selected
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();

                    // Update push button state
                    pushButton.setEnabled(finalCanPush);
                    pushButton.setToolTipText(finalCanPush ?
                        "Push " + unpushedCount + " commit(s) to remote" :
                        "No unpushed commits or no upstream branch configured");

                    if (commits.isEmpty()) {
                        // No commits found
                        return;
                    }

                    // Get today's date for date formatting
                    java.time.LocalDate today = java.time.LocalDate.now();
                    
                    for (CommitInfo commit : commits) {
                        // Format the date
                        String formattedDate = formatCommitDate(commit.date(), today);
                        
                        // Check if this commit is unpushed
                        boolean isUnpushed = unpushedCommitIds.contains(commit.id());
                        
                        commitsTableModel.addRow(new Object[]{
                            commit.message(),
                            commit.author(),
                            formattedDate,
                            commit.id(),  // Store commit ID in hidden column
                            isUnpushed    // Store unpushed status in hidden column
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
            // Parse the date from the commit info
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.parse(
                dateStr.replace(" ", "T").replaceAll(" \\(.+\\)$", ""));

            // Check if it's today
            if (dateTime.toLocalDate().equals(today)) {
                return String.format("%02d:%02d:%02d today",
                    dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond());
            }

            // Otherwise return the original date
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
        if (displayText.startsWith("Local: ")) {
            return displayText.substring("Local: ".length());
        } else if (displayText.startsWith("Remote: ")) {
            return displayText.substring("Remote: ".length());
        } else {
            return displayText;
        }
    }
    
    /**
     * Expands all nodes in a JTree
     */
    private void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; i++) {
            tree.expandRow(i);
        }
        
        // Tree nodes may have changed after expansion, get the updated count
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
        String newName = JOptionPane.showInputDialog(this,
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
        // First check if the branch is already merged
        contextManager.submitUserTask("Checking branch merge status", () -> {
            try {
                boolean isMerged = getRepo().isBranchMerged(branchName);
                
                SwingUtilities.invokeLater(() -> {
                    // If the branch is merged, confirm normal deletion
            if (isMerged) {
                int result = JOptionPane.showConfirmDialog(this,
                        "Are you sure you want to delete branch '" + branchName + "'?",
                        "Delete Branch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

                if (result == JOptionPane.YES_OPTION) {
                    performBranchDeletion(branchName, false);
                }
            } else {
                // Branch is not merged, warn user and offer force delete
                Object[] options = {"Force Delete", "Cancel"};
                int result = JOptionPane.showOptionDialog(this,
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
                // Message contains things like "Failed to delete branch: ..."
                chrome.toolErrorRaw(e.getMessage());
            }
            return null;
        });
    }
    
    /**
     * Helper method to check if any file nodes are selected (not just directory nodes)
     */
    private boolean hasFileNodesSelected(TreePath[] paths) {
        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            // A file node has a parent and is not a direct child of the root
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
        int[] selectedRows = commitsTable.getSelectedRows();
        if (selectedRows.length == 1) {
            try {
                getRepo().revertCommit(commitId);
                chrome.toolOutput("Commit was successfully reverted.");

                // Refresh the current branch's commits
                int branchRow = branchTable.getSelectedRow();
                if (branchRow != -1) {
                    String branchDisplay = (String) branchTableModel.getValueAt(branchRow, 1);
                    String branchName = getBranchNameFromDisplay(branchDisplay);
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
        int selectedRow = branchTable.getSelectedRow();
        if (selectedRow == -1) return;
        
        String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
        String currentBranch = getBranchNameFromDisplay(branchDisplay);

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
        contextManager.submitContextTask("Fetching changes for commits", () -> {
            try {
                Set<String> allChangedFiles = new HashSet<>();
                
                // Collect changed files from all selected commits
                for (int row : selectedRows) {
                    // Verify the row is valid before accessing
                    if (row >= 0 && row < commitsTableModel.getRowCount()) {
                        String commitId = (String) commitsTableModel.getValueAt(row, 3);
                        List<String> changedFiles = getRepo().listChangedFilesInCommit(commitId);
                        allChangedFiles.addAll(changedFiles);
                    }
                }
                
                // Now update the tree with all files
                final Set<String> finalChangedFiles = allChangedFiles;
                SwingUtilities.invokeLater(() -> {
                    changesRootNode.removeAllChildren();
                    
                    if (finalChangedFiles.isEmpty()) {
                        changesTreeModel.reload();
                        return;
                    }
                    
                    // Group files by directory
                    Map<String, List<String>> filesByDir = new HashMap<>();
                    
                    for (String file : finalChangedFiles) {
                        int lastSlash = file.lastIndexOf('/');
                        if (lastSlash > 0) {
                            String dir = file.substring(0, lastSlash);
                            String fileName = file.substring(lastSlash + 1);
                            filesByDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(fileName);
                        } else {
                            // Files in root directory
                            filesByDir.computeIfAbsent("", k -> new ArrayList<>()).add(file);
                        }
                    }
                    
                    // Create tree nodes
                    for (Map.Entry<String, List<String>> entry : filesByDir.entrySet()) {
                        String dirPath = entry.getKey();
                        List<String> files = entry.getValue();
                        
                        DefaultMutableTreeNode dirNode;
                        if (dirPath.isEmpty()) {
                            dirNode = changesRootNode;
                        } else {
                            dirNode = new DefaultMutableTreeNode(dirPath);
                            changesRootNode.add(dirNode);
                        }
                        
                        for (String file : files) {
                            dirNode.add(new DefaultMutableTreeNode(file));
                        }
                    }
                    
                    changesTreeModel.reload();
                    // Expand all nodes in the tree
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
        List<String> selectedFiles = new ArrayList<>();
        
        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            
            // Skip if it's the root node or a directory node (direct child of root)
            if (node == changesRootNode || node.getParent() == changesRootNode) {
                continue;
            }
            
            // Get filename and directory path
            String fileName = node.getUserObject().toString();
            String dirPath = ((DefaultMutableTreeNode) node.getParent()).getUserObject().toString();
            String filePath = dirPath.isEmpty() ? fileName : dirPath + "/" + fileName;
            
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
                // Validate selected rows first
                if (selectedRows.length == 0 || commitsTableModel.getRowCount() == 0) {
                    chrome.toolOutput("No commits selected or commits table is empty");
                    return null;
                }
                
                // Sort rows to get correct order
                int[] sortedRows = selectedRows.clone();
                Arrays.sort(sortedRows);

                // Check that row indices are within bounds
                if (sortedRows[0] < 0 || sortedRows[sortedRows.length - 1] >= commitsTableModel.getRowCount()) {
                    chrome.toolOutput("Invalid commit selection");
                    return null;
                }

                // Get the first and last commit IDs
                String firstCommitId = (String) commitsTableModel.getValueAt(sortedRows[0], 3);
                String lastCommitId = (String) commitsTableModel.getValueAt(sortedRows[sortedRows.length - 1], 3);
                
                // Get diff between first commit's parent and last commit
                String diff = getRepo().showDiff(lastCommitId, firstCommitId + "^");
                
                if (diff.isEmpty()) {
                    chrome.toolOutput("No changes found in the selected commit range");
                    return null;
                }
                
                // Create a summary of the commit range
                String firstShortHash = firstCommitId.substring(0, 7);
                String lastShortHash = lastCommitId.substring(0, 7);
                String description = String.format("git %s..%s: Changes across %d commits",
                    firstShortHash, lastShortHash, selectedRows.length);

                // Create and add virtual fragment
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
                // Validate selected rows first
                if (selectedRows.length == 0 || commitsTableModel.getRowCount() == 0) {
                    chrome.toolOutput("No commits selected or commits table is empty");
                    return null;
                }
                
                // Sort rows to get correct order
                int[] sortedRows = selectedRows.clone();
                Arrays.sort(sortedRows);

                // Check that row indices are within bounds
                if (sortedRows[0] < 0 || sortedRows[sortedRows.length - 1] >= commitsTableModel.getRowCount()) {
                    chrome.toolOutput("Invalid commit selection");
                    return null;
                }

                // Get the first and last commit IDs
                String firstCommitId = (String) commitsTableModel.getValueAt(sortedRows[0], 3);
                String lastCommitId = (String) commitsTableModel.getValueAt(sortedRows[sortedRows.length - 1], 3);
                
                StringBuilder allDiffs = new StringBuilder();
                
                for (String filePath : filePaths) {
                    // Get diff for this file between first commit's parent and last commit
                    String fileDiff = getRepo().showFileDiff(lastCommitId, firstCommitId + "^", filePath);
                    
                    // Skip if no changes
                    if (fileDiff.isEmpty()) {
                        continue;
                    }
                    
                    allDiffs.append(fileDiff).append("\n\n");
                }
                
                if (allDiffs.length() == 0) {
                    chrome.toolOutput("No changes found for the selected files in the commit range");
                    return null;
                }
                
                // Create a summary of the commit range
                String firstShortHash = firstCommitId.substring(0, 7);
                String lastShortHash = lastCommitId.substring(0, 7);
                // Convert filePaths to just the filenames, not full repo paths
                List<String> fileNames = filePaths.stream()
                    .map(path -> {
                        int lastSlash = path.lastIndexOf('/');
                        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                    })
                    .collect(Collectors.toList());
                String fileList = String.join(", ", fileNames);
                String description = String.format("git %s..%s [%s]: Changes across %d commits",
                    firstShortHash, lastShortHash, fileList, selectedRows.length);

                // Create and add virtual fragment
                var fragment = new ContextFragment.StringFragment(allDiffs.toString(), description);
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
                // Get the diff between the commit and the working directory
                String diff = getRepo().showDiff("HEAD", commitId);
                String shortHash = commitId.substring(0, 7);
                
                if (diff.isEmpty()) {
                    chrome.toolOutput("No changes between commit " + shortHash + " and local working copy");
                    return null;
                }
                
                String description = "git local vs " + shortHash + ": " + commitMessage;

                // Create and add virtual fragment
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
                StringBuilder allDiffs = new StringBuilder();
                StringBuilder fileList = new StringBuilder();

                for (String filePath : filePaths) {
                    // Get the diff for this file between the commit and the working directory
                    String fileDiff = getRepo().showFileDiff("HEAD", commitId, filePath);

                    // Skip if no changes
                    if (fileDiff.isEmpty()) {
                        continue;
                    }

                    allDiffs.append(fileDiff).append("\n\n");

                    if (fileList.length() > 0) {
                        fileList.append(", ");
                    }
                    // Extract just the filename, not the full path
                    int lastSlash = filePath.lastIndexOf('/');
                    String fileName = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
                    fileList.append(fileName);
                }
                
                if (allDiffs.length() == 0) {
                    chrome.toolOutput("No differences found between selected files and local working copy");
                    return null;
                }
                
                String shortHash = commitId.substring(0, 7);
                String description = "git local vs " + shortHash + " [" + fileList + "]";

                // Create and add virtual fragment
                var fragment = new ContextFragment.StringFragment(allDiffs.toString(), description);
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
        List<RepoFile> files = new ArrayList<>();
        files.add(contextManager.toFile(filePath));
        contextManager.editFiles(files);
    }
    
    /**
     * Search commits for the given query
     */
    private void searchCommits(String query) {
        contextManager.submitUserTask("Searching commits for: " + query, () -> {
            try {
                List<CommitInfo> searchResults = getRepo().searchCommits(query);
                
                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);
                    
                    // Clear changes panel until a commit is selected
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();
                    
                    if (searchResults.isEmpty()) {
                        chrome.toolOutput("No commits found matching: " + query);
                        return;
                    }
                    
                    for (CommitInfo commit : searchResults) {
                        commitsTableModel.addRow(new Object[]{
                            commit.message(),
                            commit.author(),
                            commit.date(),
                            commit.id()  // Store commit ID in hidden column
                        });
                    }
                    
                    chrome.toolOutput("Found " + searchResults.size() + " commits matching: " + query);
                    
                    // Select first commit automatically
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
                List<GitRepo.StashInfo> stashes = getRepo().listStashes();
                
                SwingUtilities.invokeLater(() -> {
                    stashTableModel.setRowCount(0);
                    
                    if (stashes.isEmpty()) {
                        return;
                    }
                    
                    // Get today's date for date formatting
                    java.time.LocalDate today = java.time.LocalDate.now();
                    
                    for (var stash : stashes) {
                        // Format the date
                        String formattedDate = formatCommitDate(stash.date(), today);
                        
                        stashTableModel.addRow(new Object[]{
                            String.valueOf(stash.index()),
                            stash.message(),
                            stash.author(),
                            formattedDate,
                            stash.id()  // Store stash ID in hidden column
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
        contextManager.submitUserTask("Popping stash", () -> {
            try {
                getRepo().popStash(index);
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
        contextManager.submitUserTask("Applying stash", () -> {
            try {
                getRepo().applyStash(index);
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
        // Confirm before dropping
        int result = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete this stash?",
                "Delete Stash", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                
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
        boolean isLocal = false;
        
        if (selectedRow != -1) {
            String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
            isLocal = branchDisplay != null && branchDisplay.startsWith("Local: ");
        }

        JPopupMenu menu = branchTable.getComponentPopupMenu();
        if (menu != null) {
            // Rename and Delete only available for local branches
            menu.getComponent(2).setEnabled(isLocal); // Rename
            menu.getComponent(3).setEnabled(isLocal); // Delete
        }
    }
}
