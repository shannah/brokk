package io.github.jbellis.brokk.gui.git;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflow;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.CommitDialog;
import io.github.jbellis.brokk.gui.Constants;
import io.github.jbellis.brokk.gui.DiffWindowManager;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.components.ResponsiveButtonPanel;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.gui.widgets.FileStatusTable;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jetbrains.annotations.Nullable;

/**
 * Panel for the "Changes" tab (formerly "Commit" tab) in the Git Panel. Handles displaying uncommitted changes,
 * staging, committing, and stashing.
 */
public class GitCommitTab extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitCommitTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitWorkflow workflowService;

    // Commit tab UI
    private JTable uncommittedFilesTable; // Initialized via fileStatusPane
    private FileStatusTable fileStatusPane;
    private MaterialButton commitButton;
    private MaterialButton stashButton;
    private JPanel buttonPanel;

    @Nullable
    private ProjectFile rightClickedFile = null; // Store the file that was right-clicked

    // Thread-safe cached count for badge updates
    private volatile int cachedModifiedFileCount = 0;

    public GitCommitTab(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.workflowService = new GitWorkflow(contextManager);
        buildCommitTabUI();
    }

    /** Builds the Changes tab UI elements. */
    private void buildCommitTabUI() {
        // Table for uncommitted files
        fileStatusPane = new FileStatusTable();
        uncommittedFilesTable = fileStatusPane.getTable();
        // JTable already obtained above from FileStatusTable
        // Renderer, row height, and column widths are now handled by FileStatusTable.
        uncommittedFilesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Double-click => show diff
        uncommittedFilesTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = uncommittedFilesTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        // keep the visual feedback
                        uncommittedFilesTable.setRowSelectionInterval(row, row);
                        var clickedFile =
                                (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
                        openDiffForAllUncommittedFiles(clickedFile);
                    }
                }
            }
        });

        // Popup menu
        var uncommittedContextMenu = new JPopupMenu();
        uncommittedFilesTable.setComponentPopupMenu(uncommittedContextMenu);

        var captureDiffItem = new JMenuItem("Capture Diff");
        uncommittedContextMenu.add(captureDiffItem);

        var viewDiffItem = new JMenuItem("View Diff (All Files)");
        uncommittedContextMenu.add(viewDiffItem);

        var editFileItem = new JMenuItem("Edit File(s)");
        uncommittedContextMenu.add(editFileItem);

        // Add "View History" item
        var viewHistoryItem = new JMenuItem("View History"); // Declare the variable
        uncommittedContextMenu.add(viewHistoryItem);

        // Add "Rollback Changes" item
        var rollbackChangesItem = new JMenuItem("Rollback Changes");
        uncommittedContextMenu.add(rollbackChangesItem);

        // Select row under right-click
        uncommittedContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    var point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, uncommittedFilesTable);
                    int row = uncommittedFilesTable.rowAtPoint(point);
                    if (row >= 0) {
                        // Store the right-clicked file for later use
                        rightClickedFile =
                                (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
                        if (!uncommittedFilesTable.isRowSelected(row)) {
                            uncommittedFilesTable.setRowSelectionInterval(row, row);
                        }
                    } else {
                        rightClickedFile = null;
                    }
                    // Update menu items
                    updateUncommittedContextMenuState(
                            captureDiffItem, viewDiffItem, editFileItem, viewHistoryItem, rollbackChangesItem);
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                // Reset rightClickedFile if menu is cancelled
                rightClickedFile = null;
            }
        });

        // Context menu actions:
        viewDiffItem.addActionListener(e -> {
            // Use the stored right-clicked file as priority
            openDiffForAllUncommittedFiles(rightClickedFile);
            rightClickedFile = null; // Clear after use
        });

        captureDiffItem.addActionListener(e -> {
            // Unified call:
            var selectedFiles = getSelectedFilesFromTable();
            GitUiUtil.captureUncommittedDiff(contextManager, chrome, selectedFiles);
        });

        editFileItem.addActionListener(e -> {
            var selectedProjectFiles = getSelectedFilesFromTable();
            contextManager.addFiles(selectedProjectFiles);
            rightClickedFile = null; // Clear after use
        });

        // Add action listener for the view history item
        viewHistoryItem.addActionListener(e -> {
            ProjectFile fileToShow = null;
            if (rightClickedFile != null) {
                fileToShow = rightClickedFile;
            } else { // Fallback to selection if rightClickedFile is somehow null
                int row = uncommittedFilesTable.getSelectedRow();
                if (row >= 0) {
                    fileToShow = (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
                }
            }
            if (fileToShow != null) {
                chrome.showFileHistory(fileToShow);
            }
            rightClickedFile = null; // Clear after use
        });

        // Add action listener for the rollback changes item
        rollbackChangesItem.addActionListener(e -> {
            var selectedFiles = getSelectedFilesFromTable();
            rollbackChangesWithUndo(selectedFiles);
        });

        // Selection => update context menu item states
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateUncommittedContextMenuState(
                        captureDiffItem, viewDiffItem, editFileItem, viewHistoryItem, rollbackChangesItem);
            }
        });

        // FileStatusTable is itself a JScrollPane
        // Added to a top-aligned content panel below to avoid stretching the entire tab

        // Bottom panel for buttons
        buttonPanel = new ResponsiveButtonPanel(Constants.H_GAP, Constants.V_GAP);
        buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Commit Button
        commitButton = new MaterialButton("Commit All..."); // Default label with ellipsis
        SwingUtil.applyPrimaryButtonStyle(commitButton);
        commitButton.setToolTipText("Commit files...");
        commitButton.setEnabled(false);
        commitButton.addActionListener(e -> {
            List<ProjectFile> filesToCommit;
            if (uncommittedFilesTable.getSelectedRowCount() > 0) {
                filesToCommit = getSelectedFilesFromTable();
            } else {
                filesToCommit = getAllFilesFromTable();
            }

            if (filesToCommit.isEmpty()) {
                chrome.toolError("No files to commit.", "Commit Error");
                return;
            }

            CommitDialog dialog = new CommitDialog(
                    (Frame) SwingUtilities.getWindowAncestor(this),
                    chrome,
                    contextManager,
                    workflowService,
                    filesToCommit,
                    commitResult -> { // This is the onCommitSuccessCallback
                        chrome.systemOutput("Committed "
                                + getRepo().shortHash(commitResult.commitId())
                                + ": " + commitResult.firstLine());
                        updateCommitPanel(); // Refresh file list
                        chrome.updateLogTab();
                        chrome.selectCurrentBranchInLogTab();
                    });
            dialog.setVisible(true);
        });
        buttonPanel.add(commitButton);

        // Stash Button
        stashButton = new MaterialButton("Stash All"); // Default label
        stashButton.setToolTipText("Save your changes to the stash");
        stashButton.setEnabled(false);
        stashButton.addActionListener(e -> {
            var selectedFiles = getSelectedFilesFromTable();
            int totalRows = uncommittedFilesTable.getRowCount();
            int selectedCount = uncommittedFilesTable.getSelectedRowCount();
            boolean allSelected = selectedCount > 0 && selectedCount == totalRows;
            var filesToStash = allSelected ? List.<ProjectFile>of() : selectedFiles;

            // Stash without asking for a message, using a default one.
            String stashMessage = "Stash created by Brokk";
            contextManager.submitExclusiveAction(() -> {
                try {
                    performStash(filesToStash, stashMessage);
                } catch (GitAPIException ex) {
                    logger.error("Error stashing changes:", ex);
                    SwingUtilities.invokeLater(
                            () -> chrome.toolError("Error stashing changes: " + ex.getMessage(), "Stash Error"));
                }
            });
        });
        buttonPanel.add(stashButton);

        // Table selection => update commit button text and enable/disable buttons
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateCommitButtonText(); // Updates commit button label
                updateButtonEnablement(); // Updates general button enablement
            }
        });

        JPanel titledPanel = new JPanel(new BorderLayout());
        titledPanel.setBorder(BorderFactory.createTitledBorder("Changes"));
        titledPanel.add(fileStatusPane, BorderLayout.CENTER);
        titledPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(titledPanel, BorderLayout.CENTER);
    }

    /** Updates the enabled state of commit and stash buttons based on file changes. */
    private void updateButtonEnablement() {
        // Enablement depends on whether there are changes in the table
        boolean hasChanges = uncommittedFilesTable.getRowCount() > 0;
        commitButton.setEnabled(hasChanges);
        stashButton.setEnabled(hasChanges);
    }

    /** Returns the current GitRepo from ContextManager. */
    private GitRepo getRepo() {
        var repo = contextManager.getProject().getRepo();
        return (GitRepo) repo;
    }

    /** Populates the uncommitted files table and enables/disables commit-related buttons. */
    public void updateCommitPanel() {
        logger.trace("Starting updateCommitPanel");
        // Store currently selected rows before updating
        int[] selectedRowsIndices = uncommittedFilesTable.getSelectedRows();
        List<ProjectFile> previouslySelectedFiles = new ArrayList<>();

        // Store the ProjectFile objects of selected rows to restore selection later
        if (uncommittedFilesTable.getModel().getRowCount() > 0) { // Check if table has rows before accessing
            for (int rowIndex : selectedRowsIndices) {
                if (rowIndex < uncommittedFilesTable.getModel().getRowCount()) { // Bounds check
                    ProjectFile pf =
                            (ProjectFile) uncommittedFilesTable.getModel().getValueAt(rowIndex, 2);
                    if (pf != null) { // getValueAt can return null if model is being cleared
                        previouslySelectedFiles.add(pf);
                    }
                }
            }
        }

        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            try {
                var uncommittedFileStatuses = getRepo().getModifiedFiles();
                logger.trace("Found uncommitted files with statuses: {}", uncommittedFileStatuses.size());

                SwingUtilities.invokeLater(() -> {
                    // Convert Set to List to maintain an order for adding to table and restoring selection by index
                    var uncommittedFilesList = new ArrayList<>(uncommittedFileStatuses);

                    // Populate the table via the reusable FileStatusTable widget
                    // This also populates the statusMap within FileStatusTable
                    fileStatusPane.setFiles(uncommittedFilesList);

                    // Restore selection
                    List<Integer> rowsToSelect = new ArrayList<>();
                    var model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    for (int i = 0; i < model.getRowCount(); i++) {
                        if (previouslySelectedFiles.contains(model.getValueAt(i, 2))) {
                            rowsToSelect.add(i);
                        }
                    }

                    if (!rowsToSelect.isEmpty()) {
                        for (int rowIndex : rowsToSelect) {
                            uncommittedFilesTable.addRowSelectionInterval(rowIndex, rowIndex);
                        }
                    }

                    updateButtonEnablement(); // General button enablement based on table content
                    updateCommitButtonText(); // Updates commit button label specifically

                    // Update cached count and badge after status change
                    updateAfterStatusChange(uncommittedFilesList.size());
                });
            } catch (Exception e) {
                logger.error("Error fetching uncommitted files:", e);
                SwingUtilities.invokeLater(() -> {
                    logger.debug("Disabling commit/stash buttons due to error");
                    commitButton.setEnabled(false);
                    stashButton.setEnabled(false);
                    if (uncommittedFilesTable.getModel() instanceof DefaultTableModel dtm) {
                        dtm.setRowCount(0); // Clear table on error
                    }
                    // Update cached count and badge after error
                    updateAfterStatusChange(0);
                });
            }
            return null;
        });
    }

    /** Adjusts the commit and stash button labels depending on file selection. */
    private void updateCommitButtonText() {
        assert SwingUtilities.isEventDispatchThread() : "updateCommitButtonText must be called on EDT";

        int selectedRowCount = uncommittedFilesTable.getSelectedRowCount();
        int totalRowCount = uncommittedFilesTable.getRowCount();
        boolean anySelected = selectedRowCount > 0;
        boolean allSelected = anySelected && selectedRowCount == totalRowCount;

        // Labels
        var commitLabel = (anySelected && !allSelected) ? "Commit Selected..." : "Commit All...";
        var stashLabel = (anySelected && !allSelected) ? "Stash Selected" : "Stash All";

        // Set plain single-line labels
        commitButton.setText(commitLabel);
        stashButton.setText(stashLabel);

        // Tooltips describe the action
        var commitTooltip = (anySelected && !allSelected) ? "Commit the selected files..." : "Commit all files...";
        var stashTooltip =
                (anySelected && !allSelected) ? "Save selected changes to the stash" : "Save all changes to the stash";
        commitButton.setToolTipText(commitTooltip);
        stashButton.setToolTipText(stashTooltip);

        // Let the horizontal scroll handle overflow; no wrapping or panel-wide revalidation necessary
        buttonPanel.revalidate();
        buttonPanel.repaint();
    }

    /**
     * Updates the enabled state of context menu items for the uncommitted files table based on the current selection.
     */
    private void updateUncommittedContextMenuState(
            JMenuItem captureDiffItem,
            JMenuItem viewDiffItem,
            JMenuItem editFileItem,
            JMenuItem viewHistoryItem,
            JMenuItem rollbackChangesItem) {
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();
        int selectionCount = selectedRows.length;

        if (selectionCount == 0) {
            // No files selected: disable everything
            captureDiffItem.setEnabled(false);
            captureDiffItem.setToolTipText("Select file(s) to capture diff");
            viewDiffItem.setEnabled(false);
            viewDiffItem.setToolTipText("Select file(s) to view diff");
            editFileItem.setEnabled(false);
            editFileItem.setToolTipText("Select file(s) to edit");
            viewHistoryItem.setEnabled(false);
            viewHistoryItem.setToolTipText("Select a single existing file to view its history");
            rollbackChangesItem.setEnabled(false);
            rollbackChangesItem.setToolTipText("Select file(s) to rollback changes");
        } else if (selectionCount == 1) {
            // Exactly one file selected
            captureDiffItem.setEnabled(true);
            captureDiffItem.setToolTipText("Capture diff of selected file to context");
            viewDiffItem.setEnabled(true);
            viewDiffItem.setToolTipText("View diff of selected file");

            // Get file details for conditional enablement
            int row = selectedRows[0];
            ProjectFile projectFile =
                    (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
            String status = fileStatusPane.statusFor(projectFile);

            // Enable Edit File
            editFileItem.setEnabled(true);
            editFileItem.setToolTipText("Edit this file");

            // Conditionally enable View History (disable for new files)
            boolean isNew = "new".equals(status);
            viewHistoryItem.setEnabled(!isNew);
            viewHistoryItem.setToolTipText(
                    isNew ? "Cannot view history for a new file" : "View commit history for this file");

            // Enable Rollback Changes for all files
            rollbackChangesItem.setEnabled(true);
            rollbackChangesItem.setToolTipText("Rollback changes to HEAD state");
        } else { // More than one file selected
            captureDiffItem.setEnabled(true);
            captureDiffItem.setToolTipText("Capture diff of selected files to context");
            viewDiffItem.setEnabled(true); // Enable View Diff for multiple files
            viewDiffItem.setToolTipText("View diff of selected files in multi-file viewer");

            editFileItem.setEnabled(true);
            editFileItem.setToolTipText("Edit selected file(s)");

            viewHistoryItem.setEnabled(false); // Disable View History for multiple files
            viewHistoryItem.setToolTipText("Select a single existing file to view its history");

            rollbackChangesItem.setEnabled(true); // Enable Rollback Changes for multiple files
            rollbackChangesItem.setToolTipText("Rollback changes to HEAD state for selected files");
        }
    }

    /** Helper to get a list of selected files from the uncommittedFilesTable. */
    private List<ProjectFile> getSelectedFilesFromTable() {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        var selectedRows = uncommittedFilesTable.getSelectedRows();
        var files = new ArrayList<ProjectFile>();

        for (var row : selectedRows) {
            // Retrieve ProjectFile directly from the hidden column
            ProjectFile projectFile = (ProjectFile) model.getValueAt(row, 2);
            files.add(projectFile);
        }
        return files;
    }

    /** Helper to get a list of all files from the uncommittedFilesTable. */
    private List<ProjectFile> getAllFilesFromTable() {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        var files = new ArrayList<ProjectFile>();
        int rowCount = model.getRowCount();

        for (int i = 0; i < rowCount; i++) {
            // Retrieve ProjectFile directly from the hidden column
            ProjectFile projectFile = (ProjectFile) model.getValueAt(i, 2);
            files.add(projectFile);
        }
        return files;
    }

    /**
     * Opens a diff view for all uncommitted files in the table.
     *
     * @param priorityFile File to show first (e.g., the double-clicked file), or null for default ordering
     */
    private void openDiffForAllUncommittedFiles(@Nullable ProjectFile priorityFile) {
        var allFiles = getAllFilesFromTable();
        if (allFiles.isEmpty()) {
            return; // nothing to diff
        }

        // Reorder files based on priority
        var orderedFiles = new ArrayList<ProjectFile>();

        if (priorityFile != null && allFiles.contains(priorityFile)) {
            // Priority file goes first
            orderedFiles.add(priorityFile);
            // Then selected files (excluding the priority file if it's already selected)
            var selectedFiles = getSelectedFilesFromTable();
            for (var file : selectedFiles) {
                if (!file.equals(priorityFile)) {
                    orderedFiles.add(file);
                }
            }
            // Finally, all other files
            for (var file : allFiles) {
                if (!file.equals(priorityFile) && !selectedFiles.contains(file)) {
                    orderedFiles.add(file);
                }
            }
        } else {
            // No priority file, use selected files first, then the rest
            var selectedFiles = getSelectedFilesFromTable();
            orderedFiles.addAll(selectedFiles);
            for (var file : allFiles) {
                if (!selectedFiles.contains(file)) {
                    orderedFiles.add(file);
                }
            }
        }

        contextManager.submitExclusiveAction(() -> {
            try {
                var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager);

                for (var file : orderedFiles) {
                    var rightSource = new BufferSource.FileSource(file.absPath().toFile(), file.getFileName());

                    String headContent = "";
                    try {
                        var repo = contextManager.getProject().getRepo();
                        headContent = repo.getFileContent("HEAD", file);
                    } catch (Exception ex) {
                        // new file or retrieval error – treat as empty
                        headContent = "";
                    }

                    var leftSource = new BufferSource.StringSource(headContent, "HEAD", file.getFileName());
                    builder.addComparison(leftSource, rightSource);
                }

                SwingUtilities.invokeLater(() -> {
                    // Create normalized sources for window raising check (use all files in consistent order)
                    var normalizedFiles = allFiles.stream()
                            .sorted((f1, f2) -> f1.getFileName().compareToIgnoreCase(f2.getFileName()))
                            .collect(Collectors.toList());

                    var leftSources = new java.util.ArrayList<BufferSource>();
                    var rightSources = new java.util.ArrayList<BufferSource>();

                    for (var file : normalizedFiles) {
                        var rightSource =
                                new BufferSource.FileSource(file.absPath().toFile(), file.getFileName());
                        String headContent = "";
                        try {
                            var repo = contextManager.getProject().getRepo();
                            headContent = repo.getFileContent("HEAD", file);
                        } catch (Exception ex) {
                            headContent = "";
                        }
                        var leftSource = new BufferSource.StringSource(headContent, "HEAD", file.getFileName());
                        leftSources.add(leftSource);
                        rightSources.add(rightSource);
                    }

                    // Check if we already have a window showing this diff
                    if (DiffWindowManager.tryRaiseExistingWindow(leftSources, rightSources)) {
                        return; // Existing window raised, don't create new one
                    }

                    var panel = builder.build();
                    panel.showInFrame("Uncommitted Changes Diff");
                });
            } catch (Exception ex) {
                chrome.toolError("Error opening diff for all uncommitted files: " + ex.getMessage());
            }
        });
    }

    /**
     * Rollback selected files to their HEAD state with undo support via ContextHistory. Snapshots the workspace before
     * rollback to enable undo.
     */
    private void rollbackChangesWithUndo(List<ProjectFile> selectedFiles) {
        if (selectedFiles.isEmpty()) {
            chrome.toolError("No files selected for rollback");
            return;
        }
        if (selectedFiles.stream().anyMatch(pf -> !pf.isText())) {
            chrome.toolError(
                    "Only text files can be rolled back with the ability to undo and redo the rollback operation");
            return;
        }

        contextManager.submitExclusiveAction(() -> {
            try {
                // 1. Identify which files are not in the workspace.
                var filesNotInWorkspace = selectedFiles.stream()
                        .filter(pf -> contextManager
                                .liveContext()
                                .allFragments()
                                .noneMatch(f -> f.files().contains(pf)))
                        .collect(Collectors.toSet());

                // 2. Add all selected files to the workspace to snapshot their current state.
                contextManager.addFiles(selectedFiles);

                // 3. Separate files into "new" and "other".
                var newFiles = selectedFiles.stream()
                        .filter(pf -> "new".equals(fileStatusPane.statusFor(pf)))
                        .toList();
                var otherFiles = selectedFiles.stream()
                        .filter(pf -> !"new".equals(fileStatusPane.statusFor(pf)))
                        .toList();

                // 4. Identify fragments for "new" files that are now in the workspace to be removed.
                // These fragments were just created by `editFiles`.
                var fragmentsForNewFiles = contextManager
                        .liveContext()
                        .fileFragments()
                        .filter(f ->
                                f instanceof ContextFragment.ProjectPathFragment ppf && newFiles.contains(ppf.file()))
                        .toList();
                var deletedFilesInfo = fragmentsForNewFiles.stream()
                        .map(f -> {
                            var ppf = (ContextFragment.ProjectPathFragment) f;
                            try {
                                return new ContextHistory.DeletedFile(ppf.file(), ppf.text(), true);
                            } catch (java.io.UncheckedIOException e) {
                                logger.error("Could not read content for new file being rolled back: " + ppf.file(), e);
                                return null;
                            }
                        })
                        .filter(java.util.Objects::nonNull)
                        .toList();

                // 5. Drop the fragments for "new" files from the workspace *before* creating the history entry.
                if (!fragmentsForNewFiles.isEmpty()) {
                    contextManager.drop(fragmentsForNewFiles);
                }

                // 6. Perform the actual git rollback.
                if (!otherFiles.isEmpty()) {
                    logger.debug("Rolling back {} modified/deleted files to HEAD", otherFiles.size());
                    getRepo().checkoutFilesFromCommit("HEAD", otherFiles);
                }
                if (!newFiles.isEmpty()) {
                    getRepo().forceRemoveFiles(newFiles);
                }

                // 7. Create a task result for the activity history.
                String fileList = GitUiUtil.formatFileList(selectedFiles);
                var rollbackDescription =
                        otherFiles.isEmpty() ? "Deleted " + fileList : "Rollback " + fileList + " to HEAD";
                var taskResult = new TaskResult(
                        rollbackDescription,
                        new ContextFragment.TaskFragment(contextManager, List.of(), rollbackDescription),
                        new HashSet<>(selectedFiles),
                        new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));

                try (var scope = contextManager.beginTask("", false)) {
                    scope.append(taskResult);
                }

                // 9. Now that the context is pushed, add the EntryInfo for the deleted files.
                if (!deletedFilesInfo.isEmpty()) {
                    var contextHistory = contextManager.getContextHistory();
                    var frozenContext = contextHistory.topContext();
                    var info = new ContextHistory.ContextHistoryEntryInfo(deletedFilesInfo);
                    contextHistory.addEntryInfo(frozenContext.id(), info);
                    contextManager
                            .getProject()
                            .getSessionManager()
                            .saveHistory(contextHistory, contextManager.getCurrentSessionId());
                }

                // 10. Drop the "other" files that were not originally in the workspace.
                var otherFilesToDrop = filesNotInWorkspace.stream()
                        .filter(otherFiles::contains)
                        .collect(Collectors.toSet());
                if (!otherFilesToDrop.isEmpty()) {
                    var fragmentsToDrop = contextManager
                            .liveContext()
                            .fileFragments()
                            .filter(f -> f instanceof ContextFragment.ProjectPathFragment ppf
                                    && otherFilesToDrop.contains(ppf.file()))
                            .toList();
                    if (!fragmentsToDrop.isEmpty()) {
                        contextManager.drop(fragmentsToDrop);
                    }
                }

                // 11. Update UI on EDT.
                SwingUtilities.invokeLater(() -> {
                    String successMessage = "Rolled back " + fileList + " to HEAD state. Use Ctrl+Z to undo.";
                    chrome.systemOutput(successMessage);
                    updateCommitPanel();
                    chrome.updateLogTab();
                });
            } catch (Exception ex) {
                logger.error("Error rolling back files:", ex);
                SwingUtilities.invokeLater(() -> chrome.toolError("Error rolling back files: " + ex.getMessage()));
            }
        });
    }

    /** Performs the actual stash operation and updates the UI. */
    private void performStash(List<ProjectFile> selectedFiles, String stashDescription) throws GitAPIException {
        assert !SwingUtilities.isEventDispatchThread();

        // Take a snapshot before mutating the working tree so the user can undo the stash
        var frozen = contextManager.liveContext().freezeAndCleanup();
        contextManager.getContextHistory().addFrozenContextAndClearRedo(frozen.frozenContext());

        RevCommit stashCommit;
        if (selectedFiles.isEmpty()) {
            stashCommit = getRepo().createStash(stashDescription);
        } else {
            stashCommit = getRepo().createPartialStash(stashDescription, selectedFiles);
        }

        if (stashCommit == null) {
            SwingUtilities.invokeLater(() -> chrome.toolError("No changes to stash.", "Stash Failed"));
            // The `undo` stack will have a no-op change. This is acceptable.
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (selectedFiles.isEmpty()) {
                chrome.systemOutput("All changes stashed successfully: " + stashDescription);
            } else {
                String fileList = GitUiUtil.formatFileList(selectedFiles);
                chrome.systemOutput("Stashed " + fileList + ": " + stashDescription);
            }
            updateCommitPanel(); // Refresh file list
            chrome.updateLogTab(); // Refresh log
        });
    }

    public void disableButtons() {
        stashButton.setEnabled(false);
        commitButton.setEnabled(false);
    }

    public void enableButtons() {
        stashButton.setEnabled(true);
        commitButton.setEnabled(true);
    }

    public int getThreadSafeCachedModifiedFileCount() {
        return cachedModifiedFileCount;
    }

    private void updateAfterStatusChange(int newCount) {
        assert SwingUtilities.isEventDispatchThread() : "updateAfterStatusChange must be called on EDT";

        // Update cached count for thread-safe access
        cachedModifiedFileCount = newCount;

        // Update the git tab badge
        chrome.updateGitTabBadge(newCount);
    }
}
