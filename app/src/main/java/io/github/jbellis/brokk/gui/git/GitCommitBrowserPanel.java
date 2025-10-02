package io.github.jbellis.brokk.gui.git;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.SettingsChangeListener;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflow;
import io.github.jbellis.brokk.git.ICommitInfo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.TableUtils;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.dialogs.CreatePullRequestDialog;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

public class GitCommitBrowserPanel extends JPanel implements SettingsChangeListener {

    private static final Logger logger = LogManager.getLogger(GitCommitBrowserPanel.class);
    private static final String STASHES_VIRTUAL_BRANCH = "stashes";

    private static final int COL_ID = 3;
    private static final int COL_UNPUSHED = 4;
    private static final int COL_COMMIT_OBJ = 5;

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final CommitContextReloader reloader;
    private final Options options;
    private final GitWorkflow gitWorkflow;

    public record Options(boolean showSearch, boolean showPushPullButtons, boolean showCreatePrButton) {
        public static final Options FOR_PULL_REQUEST = new Options(true, false, false);
    }

    @FunctionalInterface
    public interface CommitContextReloader {
        void reloadCurrentContext();
    }

    @FunctionalInterface
    private interface StashActionPerformer {
        void perform(int index) throws GitAPIException;
    }

    private JTable commitsTable;
    private DefaultTableModel commitsTableModel;
    private JTree changesTree;
    private DefaultTreeModel changesTreeModel;
    private DefaultMutableTreeNode changesRootNode;
    private JLabel revisionTextLabel;
    private JTextArea revisionIdTextArea;
    private JTextField commitSearchTextField;

    private JMenuItem addToContextItem;
    private JMenuItem softResetItem;
    private JMenuItem revertCommitItem;
    private JMenuItem viewChangesItem;
    private JMenuItem compareAllToLocalItem;
    private JMenuItem popStashCommitItem;
    private JMenuItem applyStashCommitItem;
    private JMenuItem dropStashCommitItem;
    private JMenuItem createBranchFromCommitItem;
    private JMenuItem cherryPickCommitItem;
    private JMenuItem captureWorkspaceSelectionsItem;

    private MaterialButton pullButton;
    private MaterialButton pushButton;
    private MaterialButton createPrButton;
    private MaterialButton viewDiffButton;

    @Nullable
    private String currentBranchOrContextName; // Used by push/pull actions
    // Timer to refresh relative commit date strings once a minute
    private javax.swing.Timer relativeTimeRefreshTimer;

    @SuppressWarnings("NullAway.Init") // Initialization is handled by buildCommitBrowserUI and its helpers
    public GitCommitBrowserPanel(
            Chrome chrome, ContextManager contextManager, CommitContextReloader reloader, Options options) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.reloader = reloader;
        this.options = options;
        this.gitWorkflow = new GitWorkflow(contextManager);
        buildCommitBrowserUI();

        // Start a repaint timer so the relative date strings stay up-to-date
        relativeTimeRefreshTimer = new javax.swing.Timer(60_000, e -> commitsTable.repaint());
        relativeTimeRefreshTimer.setRepeats(true);
        relativeTimeRefreshTimer.start();
        MainProject.addSettingsChangeListener(this);
    }

    private void buildCommitBrowserUI() {
        setLayout(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weighty = 1.0;

        JPanel commitsPanel = buildCommitsPanel();
        JPanel changesPanel = buildChangesPanel();

        Dimension nominalPreferredSize = new Dimension(1, 1);
        commitsPanel.setPreferredSize(nominalPreferredSize);
        changesPanel.setPreferredSize(nominalPreferredSize);

        // Commits panel – occupies upper portion
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1.0;
        constraints.weighty = 0.60; // about 60% of height
        add(commitsPanel, constraints);

        // Changes panel – occupies remaining lower portion
        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.weightx = 1.0;
        constraints.weighty = 0.40; // about 40% of height
        add(changesPanel, constraints);
    }

    private JPanel buildCommitsPanel() {
        JPanel commitsPanel = new JPanel(new BorderLayout());
        commitsPanel.setBorder(BorderFactory.createTitledBorder("Commits"));

        if (this.options.showSearch()) {
            JPanel commitSearchInputPanel = new JPanel(new BorderLayout(5, 0));
            commitSearchInputPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            commitSearchTextField = new JTextField();
            commitSearchInputPanel.add(commitSearchTextField, BorderLayout.CENTER);

            MaterialButton commitSearchButton = new MaterialButton("Search");
            Runnable searchAction = () -> {
                String query = commitSearchTextField.getText().trim();
                if (!query.isEmpty()) {
                    searchCommitsInPanel(query);
                } else {
                    reloader.reloadCurrentContext();
                }
            };

            commitSearchButton.addActionListener(e -> searchAction.run());
            commitSearchTextField.addActionListener(e -> searchAction.run());

            commitSearchInputPanel.add(commitSearchButton, BorderLayout.EAST);
            commitsPanel.add(commitSearchInputPanel, BorderLayout.NORTH);
        } else {
            commitSearchTextField = new JTextField(); // Keep it initialized to avoid NPEs if accessed
            commitSearchTextField.setVisible(false);
        }

        setupCommitsTable();
        commitsPanel.add(new JScrollPane(commitsTable), BorderLayout.CENTER);

        // Initialize buttons as they are class members and might be accessed by configureButton
        pullButton = new MaterialButton();
        pullButton.setIcon(Icons.DOWNLOAD);
        pullButton.setToolTipText("Pull changes from remote repository");
        pullButton.setEnabled(false);

        pushButton = new MaterialButton();
        pushButton.setIcon(Icons.PUBLISH);
        pushButton.setToolTipText("Push commits to remote repository");
        pushButton.setEnabled(false);

        createPrButton = new MaterialButton();
        createPrButton.setIcon(Icons.ADD_DIAMOND);
        createPrButton.setToolTipText("Create a pull request for the current branch");
        createPrButton.setEnabled(false);
        createPrButton.addActionListener(e -> {
            String branch = currentBranchOrContextName;

            if (branch == null) {
                chrome.toolError("Cannot create PR: No branch is currently selected.");
                return;
            }
            if (GitWorkflow.isSyntheticBranchName(branch)) {
                chrome.toolError("Select a local branch before creating a PR. Synthetic views are not supported.");
                return;
            }
            if (getRepo().isRemoteBranch(branch)) {
                chrome.toolError("Select a local branch before creating a PR. Remote branches are not supported.");
                return;
            }
            // branch is non-null, safe for CreatePullRequestDialog.
            CreatePullRequestDialog.show(chrome.getFrame(), chrome, contextManager, branch);
        });

        viewDiffButton = new MaterialButton();
        viewDiffButton.setIcon(Icons.DIFFERENCE);
        viewDiffButton.setToolTipText("View changes in the selected commit");
        viewDiffButton.setEnabled(false); // Initially disabled, enabled by selection listener
        viewDiffButton.addActionListener(e -> {
            if (commitsTable.getSelectedRowCount() == 1) {
                var ci = (ICommitInfo) commitsTableModel.getValueAt(commitsTable.getSelectedRow(), COL_COMMIT_OBJ);
                if (ci.stashIndex().isEmpty()) { // Only for non-stash commits
                    GitUiUtil.openCommitDiffPanel(contextManager, chrome, ci);
                }
            }
        });

        if (this.options.showPushPullButtons() || this.options.showCreatePrButton()) {
            JPanel buttonBar = new JPanel(new BorderLayout(5, 0)); // Main bar for buttons

            // Left-aligned panel (for View Diff)
            JPanel leftButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            leftButtonsPanel.add(viewDiffButton);
            buttonBar.add(leftButtonsPanel, BorderLayout.WEST);

            // Right-aligned panel (for existing buttons)
            JPanel rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            if (this.options.showCreatePrButton()) {
                rightButtonsPanel.add(createPrButton);
            }
            if (this.options.showPushPullButtons()) {
                rightButtonsPanel.add(pullButton);
                rightButtonsPanel.add(pushButton);
            }
            if (rightButtonsPanel.getComponentCount() > 0) { // Only add if it has components
                buttonBar.add(rightButtonsPanel, BorderLayout.EAST);
            }
            commitsPanel.add(buttonBar, BorderLayout.SOUTH);
        }

        return commitsPanel;
    }

    private JPanel buildChangesPanel() {
        JPanel changesPanel = new JPanel(new BorderLayout());
        changesPanel.setBorder(BorderFactory.createTitledBorder("Changes"));

        JPanel revisionDisplayPanel = new JPanel(new BorderLayout(5, 0));
        revisionTextLabel = new JLabel("Revision:");
        revisionIdTextArea = new JTextArea("N/A", 1, 1);
        revisionIdTextArea.setEditable(false);
        revisionIdTextArea.setLineWrap(true);
        revisionIdTextArea.setWrapStyleWord(true);
        revisionIdTextArea.setBackground(UIManager.getColor("Label.background"));
        revisionIdTextArea.setForeground(UIManager.getColor("Label.foreground"));
        revisionIdTextArea.setFont(UIManager.getFont("Label.font"));
        revisionIdTextArea.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        revisionDisplayPanel.add(revisionTextLabel, BorderLayout.WEST);
        revisionDisplayPanel.add(revisionIdTextArea, BorderLayout.CENTER);
        changesPanel.add(revisionDisplayPanel, BorderLayout.NORTH);

        setupChangesTree();
        changesPanel.add(new JScrollPane(changesTree), BorderLayout.CENTER);

        return changesPanel;
    }

    private void setupCommitsTable() {
        commitsTableModel =
                new DefaultTableModel(new Object[] {"Message", "Author", "Date", "ID", "Unpushed", "CommitObject"}, 0) {
                    @Override
                    public Class<?> getColumnClass(int columnIndex) {
                        return switch (columnIndex) {
                            case 2 -> java.time.Instant.class;
                            case 4 -> Boolean.class;
                            case 5 -> ICommitInfo.class;
                            default -> String.class;
                        };
                    }

                    @Override
                    public boolean isCellEditable(int row, int column) {
                        return false;
                    }
                };

        commitsTable = new JTable(commitsTableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row < 0) {
                    return super.getToolTipText(e);
                }
                var commitObj = (ICommitInfo) getModel().getValueAt(row, COL_COMMIT_OBJ);
                if (commitObj == null) {
                    return super.getToolTipText(e);
                }
                return GitCommitBrowserPanel.this.buildTooltip(commitObj);
            }
        };
        commitsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Hide ID, Unpushed, CommitObject columns
        var idColumn = commitsTable.getColumnModel().getColumn(COL_ID);
        idColumn.setMinWidth(0);
        idColumn.setMaxWidth(0);
        idColumn.setWidth(0);

        var unpushedColumn = commitsTable.getColumnModel().getColumn(COL_UNPUSHED);
        unpushedColumn.setMinWidth(0);
        unpushedColumn.setMaxWidth(0);
        unpushedColumn.setWidth(0);

        var commitObjectColumn = commitsTable.getColumnModel().getColumn(COL_COMMIT_OBJ);
        commitObjectColumn.setMinWidth(0);
        commitObjectColumn.setMaxWidth(0);
        commitObjectColumn.setWidth(0);

        commitsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                boolean isDark = chrome.getTheme().isDarkTheme();
                boolean unpushed = (boolean) table.getModel().getValueAt(row, COL_UNPUSHED);

                if (!isSelected) {
                    c.setBackground(unpushed ? Colors.getChanged(isDark) : table.getBackground());
                    c.setForeground(table.getForeground());
                } else {
                    c.setBackground(unpushed ? Colors.getChanged(isDark).darker() : table.getSelectionBackground());
                    c.setForeground(table.getSelectionForeground());
                }
                if (column == 2 && value instanceof java.time.Instant instant) {
                    var today = java.time.LocalDate.now(java.time.ZoneId.systemDefault());
                    setText(GitUiUtil.formatRelativeDate(instant, today));
                } else {
                    setValue(value);
                }
                return c;
            }
        });

        setupCommitSelectionListener();
        setupCommitContextMenu();
        setupCommitDoubleClick(); // Add call to new method
    }

    private void setupCommitDoubleClick() {
        commitsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = commitsTable.rowAtPoint(e.getPoint());
                    // Check if click was on a valid row and only one row is selected
                    if (row != -1 && commitsTable.getSelectedRowCount() == 1) {
                        // Ensure the clicked row is indeed the selected one
                        if (commitsTable.getSelectedRow() == row) {
                            var ci = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
                            if (ci.stashIndex().isEmpty()) { // Action only for non-stash commits
                                GitUiUtil.openCommitDiffPanel(contextManager, chrome, ci);
                            }
                        }
                    }
                }
            }
        });
    }

    private void setupChangesTree() {
        changesRootNode = new DefaultMutableTreeNode("Changes");
        changesTreeModel = new DefaultTreeModel(changesRootNode);
        changesTree = new JTree(changesTreeModel);
        changesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        setupChangesTreeContextMenu();
        setupChangesTreeDoubleClick();
    }

    private static Stream<ProjectFile> safeChangedFiles(ICommitInfo c) {
        try {
            List<ProjectFile> changedFilesList = c.changedFiles();
            return changedFilesList.stream();
        } catch (GitAPIException ex) {
            String commitIdStr = "unknown";
            try {
                commitIdStr = c.id();
            } catch (Exception idEx) {
                logger.warn("Could not get ID for commit during GitAPIException logging", idEx);
            }
            logger.error("GitAPIException fetching changed files for commit {}: {}", commitIdStr, ex.getMessage());
            return Stream.empty();
        } catch (Exception ex) {
            String commitIdStr = "unknown";
            try {
                commitIdStr = c.id();
            } catch (Exception idEx) {
                logger.warn("Could not get ID for commit during general exception logging", idEx);
            }
            logger.error(
                    "Unexpected exception fetching changed files for commit {}: {}", commitIdStr, ex.getMessage(), ex);
            return Stream.empty();
        }
    }

    private void setupCommitSelectionListener() {
        commitsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;

            int[] selectedRows = commitsTable.getSelectedRows();
            final List<ICommitInfo> allSelectedCommitsFlat;
            String labelPrefixToSet;
            String idTextToSet;

            if (selectedRows.length == 0) {
                allSelectedCommitsFlat = List.of();
                labelPrefixToSet = "Revision:";
                idTextToSet = "N/A";
            } else {
                selectedRows = Arrays.stream(selectedRows).sorted().toArray();
                allSelectedCommitsFlat = Arrays.stream(selectedRows)
                        .mapToObj(row -> (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ))
                        .toList();

                var labelParts = new ArrayList<String>();
                if (selectedRows.length == 1) {
                    labelParts.add(getShortId(allSelectedCommitsFlat.getFirst().id()));
                } else {
                    var contiguousRowIndexGroups = GitUiUtil.groupContiguous(selectedRows);
                    for (var rowIndexGroup : contiguousRowIndexGroups) {
                        var firstCommitInGroup =
                                (ICommitInfo) commitsTableModel.getValueAt(rowIndexGroup.getFirst(), COL_COMMIT_OBJ);
                        var firstShortId = getShortId(firstCommitInGroup.id());
                        if (rowIndexGroup.size() == 1) {
                            labelParts.add(firstShortId);
                        } else {
                            var lastCommitInGroup =
                                    (ICommitInfo) commitsTableModel.getValueAt(rowIndexGroup.getLast(), COL_COMMIT_OBJ);
                            labelParts.add(String.format("%s..%s", firstShortId, getShortId(lastCommitInGroup.id())));
                        }
                    }
                }
                idTextToSet = String.join(", ", labelParts);
                labelPrefixToSet = (labelParts.size() <= 1) ? "Revision:" : "Revisions:";
            }
            revisionTextLabel.setText(labelPrefixToSet);
            revisionIdTextArea.setText(idTextToSet);
            updateChangesForCommits(allSelectedCommitsFlat);

            // Update View Diff button state
            boolean singleCommitSelected = selectedRows.length == 1;
            boolean isStashSelected = false;
            if (singleCommitSelected) {
                var firstCommitInfo = (ICommitInfo) commitsTableModel.getValueAt(selectedRows[0], COL_COMMIT_OBJ);
                isStashSelected = firstCommitInfo.stashIndex().isPresent();
            }
            viewDiffButton.setEnabled(singleCommitSelected && !isStashSelected);
        });
    }

    private void setupCommitContextMenu() {
        var commitsContextMenu = new JPopupMenu();
        registerMenu(commitsContextMenu);

        addToContextItem = new JMenuItem("Capture Diff");
        captureWorkspaceSelectionsItem = new JMenuItem("Capture workspace selections at this revision");
        softResetItem = new JMenuItem("Soft Reset to Here");
        revertCommitItem = new JMenuItem("Revert Commit");
        viewChangesItem = new JMenuItem("View Diff");
        compareAllToLocalItem = new JMenuItem("Compare All to Local");
        popStashCommitItem = new JMenuItem("Apply and Remove");
        applyStashCommitItem = new JMenuItem("Apply Stash");
        dropStashCommitItem = new JMenuItem("Drop Stash");
        createBranchFromCommitItem = new JMenuItem("Create Branch From Commit");
        cherryPickCommitItem = new JMenuItem("Cherry pick into ...");

        commitsContextMenu.add(addToContextItem);
        commitsContextMenu.add(captureWorkspaceSelectionsItem);
        commitsContextMenu.add(viewChangesItem);
        commitsContextMenu.add(compareAllToLocalItem);
        commitsContextMenu.add(softResetItem);
        commitsContextMenu.add(revertCommitItem);
        commitsContextMenu.add(cherryPickCommitItem);
        commitsContextMenu.add(createBranchFromCommitItem);
        commitsContextMenu.add(popStashCommitItem);
        commitsContextMenu.add(applyStashCommitItem);
        commitsContextMenu.add(dropStashCommitItem);

        setupCommitContextMenuActions();
        setupCommitContextMenuListener(commitsContextMenu);
    }

    private void setupCommitContextMenuListener(JPopupMenu commitsContextMenu) {
        commitsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = commitsTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        if (!commitsTable.isRowSelected(row)) {
                            commitsTable.setRowSelectionInterval(row, row);
                        } else {
                            boolean clickedRowInSelection = Arrays.stream(commitsTable.getSelectedRows())
                                    .anyMatch(sr -> sr == row); // boolean preferred by style guide
                            if (!clickedRowInSelection) commitsTable.setRowSelectionInterval(row, row);
                        }
                    }
                    updateCommitContextMenuState();
                    SwingUtil.runOnEdt(() -> {
                        if (commitsTable.rowAtPoint(e.getPoint()) >= 0)
                            commitsContextMenu.show(commitsTable, e.getX(), e.getY());
                    });
                }
            }
        });
    }

    private void updateCommitContextMenuState() {
        int[] selectedRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
        if (selectedRows.length == 0) {
            setAllCommitMenuContextItemsVisible(false);
            return;
        }
        setAllCommitMenuContextItemsVisible(true); // Default to visible, then adjust

        var firstCommitInfo = (ICommitInfo) commitsTableModel.getValueAt(selectedRows[0], COL_COMMIT_OBJ);
        boolean isStash = firstCommitInfo.stashIndex().isPresent(); // boolean preferred by style guide
        boolean hasWorkspaceSelections =
                !chrome.getContextPanel().getSelectedProjectFiles().isEmpty();

        viewChangesItem.setEnabled(selectedRows.length == 1);
        compareAllToLocalItem.setEnabled(selectedRows.length == 1 && !isStash);
        captureWorkspaceSelectionsItem.setVisible(!isStash);
        captureWorkspaceSelectionsItem.setEnabled(selectedRows.length == 1 && !isStash && hasWorkspaceSelections);
        softResetItem.setVisible(!isStash);
        softResetItem.setEnabled(selectedRows.length == 1 && !isStash);
        revertCommitItem.setVisible(!isStash);
        revertCommitItem.setEnabled(
                !isStash); // Git revert doesn't directly do ranges. Enable if any non-stash selected.
        createBranchFromCommitItem.setVisible(!isStash);
        createBranchFromCommitItem.setEnabled(selectedRows.length == 1 && !isStash);

        String cpBranch;
        try {
            cpBranch = getRepo().getCurrentBranch();
        } catch (Exception ex) {
            cpBranch = "current branch";
        }
        cherryPickCommitItem.setText("Cherry pick into " + cpBranch);
        cherryPickCommitItem.setVisible(!isStash);
        cherryPickCommitItem.setEnabled(!allSelectedCommitsReachableFromHead());

        boolean allSelectedAreStashes = Arrays.stream(selectedRows) // boolean preferred by style guide
                .mapToObj(r -> (ICommitInfo) commitsTableModel.getValueAt(r, COL_COMMIT_OBJ))
                .allMatch(ci -> ci.stashIndex().isPresent());

        popStashCommitItem.setVisible(allSelectedAreStashes);
        popStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1);
        applyStashCommitItem.setVisible(allSelectedAreStashes);
        applyStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1);
        dropStashCommitItem.setVisible(allSelectedAreStashes);
        dropStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1);
        // Hide non-stash items if all are stashes
        if (allSelectedAreStashes) {
            softResetItem.setVisible(false);
            revertCommitItem.setVisible(false);
            compareAllToLocalItem.setVisible(false);
            createBranchFromCommitItem.setVisible(false);
            cherryPickCommitItem.setVisible(false);
            captureWorkspaceSelectionsItem.setVisible(false);
        }
        // Hide stash items if not all are stashes (or none are)
        if (!allSelectedAreStashes) {
            popStashCommitItem.setVisible(false);
            applyStashCommitItem.setVisible(false);
            dropStashCommitItem.setVisible(false);
        }
    }

    private void setAllCommitMenuContextItemsVisible(boolean visible) {
        addToContextItem.setVisible(visible);
        viewChangesItem.setVisible(visible);
        compareAllToLocalItem.setVisible(visible);
        softResetItem.setVisible(visible);
        revertCommitItem.setVisible(visible);
        popStashCommitItem.setVisible(visible);
        applyStashCommitItem.setVisible(visible);
        dropStashCommitItem.setVisible(visible);
        createBranchFromCommitItem.setVisible(visible);
        cherryPickCommitItem.setVisible(visible);
        captureWorkspaceSelectionsItem.setVisible(visible);
    }

    private void setupCommitContextMenuActions() {
        addToContextItem.addActionListener(e -> {
            int[] selectedRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
            if (selectedRows.length == 0) return;
            selectedRows = Arrays.stream(selectedRows).sorted().toArray();
            var contiguousRowGroups = GitUiUtil.groupContiguous(selectedRows);

            for (var group : contiguousRowGroups) {
                if (group.isEmpty()) continue;
                ICommitInfo newestCommitInGroup =
                        (ICommitInfo) commitsTableModel.getValueAt(group.getFirst(), COL_COMMIT_OBJ);
                ICommitInfo oldestCommitInGroup =
                        (ICommitInfo) commitsTableModel.getValueAt(group.getLast(), COL_COMMIT_OBJ);
                GitUiUtil.addCommitRangeToContext(contextManager, chrome, newestCommitInGroup, oldestCommitInGroup);
            }
        });

        captureWorkspaceSelectionsItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow();
            if (row == -1) return;

            var ci = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
            if (ci == null || ci.stashIndex().isPresent()) {
                chrome.systemOutput("Capture is only available for standard commits.");
                return;
            }
            final String commitId = ci.id();
            final String shortId = getRepo().shortHash(commitId);

            // Gather selected project files from the workspace
            var selectedFiles = chrome.getContextPanel().getSelectedProjectFiles();
            if (selectedFiles.isEmpty()) {
                chrome.systemOutput("No project files selected in the workspace to capture.");
                return;
            }

            contextManager.submitExclusiveAction(() -> {
                int success = 0;
                for (var pf : selectedFiles) {
                    try {
                        final String content = getRepo().getFileContent(commitId, pf);
                        var fragment = new ContextFragment.GitFileFragment(pf, shortId, content);
                        contextManager.addPathFragmentAsync(fragment);
                        success++;
                    } catch (GitAPIException ex) {
                        logger.warn("Error capturing {} at {}: {}", pf, commitId, ex.getMessage());
                    }
                }
                final int captured = success;
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput("Captured " + captured + " file(s) at " + shortId + ".");
                    chrome.updateWorkspace();
                });
            });
        });

        softResetItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow(); // int preferred by style guide
            if (row != -1) {
                var ci = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
                var firstLine = ci.message().split("\n", 2)[0];
                softResetToCommitInternal(ci.id(), firstLine);
            }
        });
        viewChangesItem.addActionListener(e -> {
            if (commitsTable.getSelectedRowCount() == 1) {
                var ci = (ICommitInfo) commitsTableModel.getValueAt(commitsTable.getSelectedRow(), COL_COMMIT_OBJ);
                GitUiUtil.openCommitDiffPanel(contextManager, chrome, ci);
            }
        });

        revertCommitItem.addActionListener(e -> {
            int row =
                    commitsTable
                            .getSelectedRow(); // int preferred by style guide // Assuming single selection for revert,
            // or first
            // of multiple
            if (row != -1) {
                var ci = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
                revertCommitInternal(ci.id());
            }
        });

        cherryPickCommitItem.addActionListener(e -> {
            int[] rows = commitsTable.getSelectedRows();
            if (rows.length == 0) return;
            rows = Arrays.stream(rows).sorted().toArray();

            // Collect commit IDs, oldest first (reverse table order)
            var commitIds = new ArrayList<String>(rows.length);
            for (int i = rows.length - 1; i >= 0; i--) {
                var ci = (ICommitInfo) commitsTableModel.getValueAt(rows[i], COL_COMMIT_OBJ);
                commitIds.add(ci.id());
            }
            if (commitIds.isEmpty()) return;

            String branchTmp;
            try {
                branchTmp = getRepo().getCurrentBranch();
            } catch (GitAPIException ex) {
                throw new IllegalStateException(ex);
            }
            final String branchLabel = branchTmp;

            contextManager.submitExclusiveAction(() -> {
                int applied = 0;
                for (var cid : commitIds) {
                    CherryPickResult res;
                    try {
                        res = getRepo().cherryPickCommit(cid);
                    } catch (GitAPIException gae) {
                        logger.warn("Cherry-pick threw GitAPIException for {}: {}", cid, gae.getMessage());
                        chrome.toolError(
                                "Cherry-pick failed for " + getShortId(cid) + ": " + gae.getMessage(),
                                "Cherry-pick Error");
                        break;
                    }

                    var status = res.getStatus();
                    if (status == CherryPickResult.CherryPickStatus.OK) {
                        applied++;
                    } else if (status == CherryPickResult.CherryPickStatus.CONFLICTING) {
                        chrome.toolError(
                                "Cherry-pick resulted in conflicts. Please resolve and commit.\nConflicts: "
                                        + String.join(
                                                ", ", res.getFailingPaths().keySet()),
                                "Cherry-pick Conflicts");
                        break;
                    } else {
                        logger.warn("Cherry-pick returned status {} for {}.", status, cid);
                        chrome.toolError(
                                "Cherry-pick failed for " + getShortId(cid) + ": " + status, "Cherry-pick Error");
                        break;
                    }
                }

                int finalApplied = applied;
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput("Cherry-picked " + finalApplied + " commit(s) into '" + branchLabel + "'.");
                    refreshCurrentViewAfterGitOp();
                    chrome.updateCommitPanel();
                });
            });
        });

        popStashCommitItem.addActionListener(e -> handleStashAction(ICommitInfo::stashIndex, this::popStashInternal));
        applyStashCommitItem.addActionListener(
                e -> handleStashAction(ICommitInfo::stashIndex, this::applyStashInternal));
        dropStashCommitItem.addActionListener(e -> handleStashAction(ICommitInfo::stashIndex, this::dropStashInternal));

        compareAllToLocalItem.addActionListener(e -> {
            if (commitsTable.getSelectedRowCount() == 1) {
                var ci = (ICommitInfo) commitsTableModel.getValueAt(commitsTable.getSelectedRow(), COL_COMMIT_OBJ);
                GitUiUtil.compareCommitToLocal(contextManager, chrome, ci);
            }
        });

        createBranchFromCommitItem.addActionListener(e -> {
            int[] selectedRows = commitsTable.getSelectedRows();
            if (selectedRows.length == 1) {
                ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selectedRows[0], COL_COMMIT_OBJ);
                // Show dialog to create branch
                io.github.jbellis.brokk.gui.dialogs.CreateBranchDialog.showDialog(
                        (Frame) SwingUtilities.getWindowAncestor(this),
                        contextManager,
                        chrome,
                        commitInfo.id(),
                        getShortId(commitInfo.id()));
            }
        });
    }

    private void handleStashAction(
            java.util.function.Function<ICommitInfo, Optional<Integer>> stashIndexExtractor,
            java.util.function.IntConsumer stashOperation) {
        int row = commitsTable.getSelectedRow(); // int preferred by style guide
        if (row != -1) {
            var commitInfo = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
            var stashIdxOpt = stashIndexExtractor.apply(commitInfo);
            stashIdxOpt.ifPresentOrElse(
                    stashOperation::accept,
                    () -> logger.warn("Stash action triggered on a non-stash commit: {}", commitInfo.id()));
        }
    }

    private void setupChangesTreeContextMenu() {
        var changesContextMenu = new JPopupMenu();
        registerMenu(changesContextMenu);

        var addFileToContextItem = new JMenuItem("Capture Diff");
        var compareFileWithLocalItem = new JMenuItem("Compare with Local");
        var viewFileAtRevisionItem = new JMenuItem("View File at Revision");
        var viewDiffItem = new JMenuItem("View Diff");
        var viewHistoryItem = new JMenuItem("View History");
        var editFileItem = new JMenuItem("Edit File(s)");
        var comparePrevWithLocalItem = new JMenuItem("Compare Previous with Local");
        JMenuItem rollbackFilesItem = new JMenuItem("Rollback Files to This Commit");

        changesContextMenu.add(addFileToContextItem);
        changesContextMenu.add(editFileItem);
        changesContextMenu.addSeparator();
        changesContextMenu.add(rollbackFilesItem);
        changesContextMenu.addSeparator();
        changesContextMenu.add(viewHistoryItem);
        changesContextMenu.addSeparator();
        changesContextMenu.add(viewFileAtRevisionItem);
        changesContextMenu.add(viewDiffItem);
        changesContextMenu.add(compareFileWithLocalItem);
        changesContextMenu.add(comparePrevWithLocalItem);

        setupChangesTreeContextMenuListener(
                addFileToContextItem,
                compareFileWithLocalItem,
                viewFileAtRevisionItem,
                viewDiffItem,
                viewHistoryItem,
                editFileItem,
                comparePrevWithLocalItem,
                rollbackFilesItem,
                changesContextMenu);
        setupChangesTreeContextMenuActions(
                addFileToContextItem,
                compareFileWithLocalItem,
                viewFileAtRevisionItem,
                viewDiffItem,
                viewHistoryItem,
                editFileItem,
                comparePrevWithLocalItem,
                rollbackFilesItem);
    }

    private void setupChangesTreeContextMenuListener(
            JMenuItem addFileToContextItem,
            JMenuItem compareFileWithLocalItem,
            JMenuItem viewFileAtRevisionItem,
            JMenuItem viewDiffItem,
            JMenuItem viewHistoryItem,
            JMenuItem editFileItem,
            JMenuItem comparePrevWithLocalItem,
            JMenuItem rollbackFilesItem,
            JPopupMenu changesContextMenu) {
        changesTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleChangesPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleChangesPopup(e);
            }

            private void handleChangesPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = changesTree.getRowForLocation(e.getX(), e.getY()); // int preferred by style guide
                    if (row >= 0 && !changesTree.isRowSelected(row)) changesTree.setSelectionRow(row);

                    var paths = changesTree.getSelectionPaths();
                    boolean hasFileSelection = (paths != null
                            && paths.length > 0
                            && hasFileNodesSelected(paths)); // boolean preferred by style guide
                    boolean isSingleCommit =
                            (commitsTable.getSelectedRowCount() == 1); // boolean preferred by style guide
                    boolean singleFileSelected = (paths != null
                            && paths.length == 1
                            && hasFileSelection); // boolean preferred by style guide

                    viewHistoryItem.setEnabled(singleFileSelected);
                    addFileToContextItem.setEnabled(hasFileSelection);
                    editFileItem.setEnabled(hasFileSelection);
                    rollbackFilesItem.setEnabled(hasFileSelection && isSingleCommit);
                    viewFileAtRevisionItem.setEnabled(singleFileSelected && isSingleCommit);
                    viewDiffItem.setEnabled(singleFileSelected && isSingleCommit);
                    compareFileWithLocalItem.setEnabled(singleFileSelected && isSingleCommit);
                    comparePrevWithLocalItem.setEnabled(singleFileSelected && isSingleCommit);
                    if (changesTree.getRowForLocation(e.getX(), e.getY())
                            >= 0) { // Ensure a node is actually under the cursor
                        changesContextMenu.show(changesTree, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private void setupChangesTreeContextMenuActions(
            JMenuItem addFileToContextItem,
            JMenuItem compareFileWithLocalItem,
            JMenuItem viewFileAtRevisionItem,
            JMenuItem viewDiffItem,
            JMenuItem viewHistoryItem,
            JMenuItem editFileItem,
            JMenuItem comparePrevWithLocalItem,
            JMenuItem rollbackFilesItem) {
        addFileToContextItem.addActionListener(e -> {
            var files = getSelectedFilePathsFromTree();
            if (!files.isEmpty() && commitsTable.getSelectedRowCount() >= 1) {
                int[] selRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
                selRows = Arrays.stream(selRows).sorted().toArray();
                var firstCid = ((ICommitInfo) commitsTableModel.getValueAt(selRows[0], COL_COMMIT_OBJ)).id();
                var lastCid =
                        ((ICommitInfo) commitsTableModel.getValueAt(selRows[selRows.length - 1], COL_COMMIT_OBJ)).id();
                GitUiUtil.addFilesChangeToContext(
                        contextManager,
                        chrome,
                        firstCid,
                        lastCid,
                        files.stream().map(contextManager::toFile).toList());
            }
        });

        compareFileWithLocalItem.addActionListener(e -> handleSingleFileSingleCommitAction(
                (cid, fp) -> GitUiUtil.showDiffVsLocal(contextManager, chrome, cid, fp, false)));
        comparePrevWithLocalItem.addActionListener(e -> handleSingleFileSingleCommitAction(
                (cid, fp) -> GitUiUtil.showDiffVsLocal(contextManager, chrome, cid, fp, true)));
        viewFileAtRevisionItem.addActionListener(e -> handleSingleFileSingleCommitAction(
                (cid, fp) -> GitUiUtil.viewFileAtRevision(contextManager, chrome, cid, fp)));
        viewDiffItem.addActionListener(e -> handleSingleFileSingleCommitAction(
                (cid, fp) -> GitUiUtil.showFileHistoryDiff(contextManager, chrome, cid, contextManager.toFile(fp))));

        viewHistoryItem.addActionListener(
                e -> getSelectedFilePathsFromTree().forEach(fp -> chrome.addFileHistoryTab(contextManager.toFile(fp))));
        editFileItem.addActionListener(
                e -> getSelectedFilePathsFromTree().forEach(fp -> GitUiUtil.editFile(contextManager, fp)));
        rollbackFilesItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            int[] selRows = commitsTable.getSelectedRows();
            if (paths != null && paths.length > 0 && selRows.length == 1) {
                List<String> selectedFilePaths = getSelectedFilePathsFromTree();
                if (!selectedFilePaths.isEmpty()) {
                    // Get CommitInfo from hidden column 5
                    ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selRows[0], COL_COMMIT_OBJ);
                    String commitId = commitInfo.id();

                    // Convert file paths to ProjectFile objects
                    List<ProjectFile> projectFiles = selectedFilePaths.stream()
                            .map(fp -> new ProjectFile(contextManager.getRoot(), fp))
                            .toList();

                    GitUiUtil.rollbackFilesToCommit(contextManager, chrome, commitId, projectFiles);
                }
            }
        });
    }

    private void handleSingleFileSingleCommitAction(java.util.function.BiConsumer<String, String> action) {
        var paths = changesTree.getSelectionPaths();
        int[] selRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
        if (paths != null
                && paths.length == 1
                && selRows.length == 1
                && TreeNodeInfo.fromPath(paths[0], changesRootNode).isFile()) {
            var filePath = TreeNodeInfo.fromPath(paths[0], changesRootNode).filePath();
            var commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selRows[0], COL_COMMIT_OBJ);
            action.accept(commitInfo.id(), filePath);
        }
    }

    private void setupChangesTreeDoubleClick() {
        changesTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleSingleFileSingleCommitAction((cid, fp) ->
                            GitUiUtil.showFileHistoryDiff(contextManager, chrome, cid, contextManager.toFile(fp)));
                }
            }
        });
    }

    private void updateChangesForCommits(List<ICommitInfo> commits) {
        if (commits.isEmpty()) {
            changesRootNode.removeAllChildren();
            changesTreeModel.reload();
            return;
        }

        contextManager.submitBackgroundTask("Fetching changes for commits", () -> {
            var allChangedFiles = commits.stream()
                    .flatMap(GitCommitBrowserPanel::safeChangedFiles)
                    .collect(Collectors.toSet());
            var newRootNode = new DefaultMutableTreeNode("Changes");
            var filesByDir = new HashMap<Path, List<String>>();
            for (var file : allChangedFiles) {
                filesByDir
                        .computeIfAbsent(file.getParent(), k -> new ArrayList<>())
                        .add(file.getFileName());
            }
            var sortedDirs = new ArrayList<>(filesByDir.keySet());
            sortedDirs.sort(Comparator.comparing(Path::toString));
            for (var dirPath : sortedDirs) {
                var files = filesByDir.get(dirPath);
                if (files != null) { // files can be null if dirPath was removed concurrently, though unlikely here
                    files.sort(String::compareTo);
                    var dirNode = dirPath.equals(Path.of("")) ? newRootNode : new DefaultMutableTreeNode(dirPath);
                    if (dirNode != newRootNode) newRootNode.add(dirNode);
                    for (var f : files) dirNode.add(new DefaultMutableTreeNode(f));
                }
            }

            SwingUtil.runOnEdt(() -> {
                changesRootNode = newRootNode;
                changesTreeModel = new DefaultTreeModel(changesRootNode);
                changesTree.setModel(changesTreeModel);
                expandAllNodes(changesTree, 0, changesTree.getRowCount());
            });
        });
    }

    private void softResetToCommitInternal(String commitId, String commitMessage) {
        contextManager.submitExclusiveAction(() -> {
            var oldHeadId = getOldHeadId();
            try {
                getRepo().softReset(commitId);
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput("Soft reset from " + getShortId(oldHeadId) + " to " + getShortId(commitId)
                            + ": " + commitMessage);
                    refreshCurrentViewAfterGitOp(); // Assumes this method exists or is adapted
                });
            } catch (GitAPIException e) {
                logger.error("Error soft reset to {}: {}", commitId, e.getMessage());
                SwingUtil.runOnEdt(() -> chrome.toolError("Error soft reset: " + e.getMessage()));
            }
        });
    }

    private void revertCommitInternal(String commitId) {
        contextManager.submitExclusiveAction(() -> {
            try {
                getRepo().revertCommit(commitId);
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput("Commit " + getShortId(commitId) + " reverted.");
                    refreshCurrentViewAfterGitOp();
                });
            } catch (GitAPIException e) {
                logger.error("Error reverting {}: {}", commitId, e.getMessage());
                SwingUtil.runOnEdt(() -> chrome.toolError("Error reverting commit: " + e.getMessage()));
            }
        });
    }

    private void performStashOp(
            int idx, String description, StashActionPerformer repoCall, String successMsg, boolean refreshView) {
        contextManager.submitExclusiveAction(() -> {
            try {
                repoCall.perform(idx);
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput(successMsg);
                    if (refreshView) {
                        refreshCurrentViewAfterGitOp();
                    }
                });
            } catch (Exception e) {
                logger.error("{} failed: {}", description, e.getMessage(), e);
                SwingUtil.runOnEdt(() -> chrome.toolError(description + " error: " + e.getMessage()));
            }
        });
    }

    private void popStashInternal(int stashIndex) {
        performStashOp(stashIndex, "Popping stash", getRepo()::popStash, "Stash popped successfully.", true);
    }

    private void applyStashInternal(int stashIndex) {
        performStashOp(stashIndex, "Applying stash", getRepo()::applyStash, "Stash applied successfully.", false);
    }

    private void dropStashInternal(int stashIndex) {
        int result = chrome.showConfirmDialog(
                this,
                "Delete stash@{" + stashIndex + "}?",
                "Confirm Drop",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE); // int preferred by style guide
        if (result != JOptionPane.YES_OPTION) return;
        performStashOp(stashIndex, "Dropping stash", getRepo()::dropStash, "Stash dropped successfully.", true);
    }

    // This is a placeholder. The actual refresh logic might be more complex
    // or handled by the parent component (GitLogTab or CreatePullRequestDialog).
    private void refreshCurrentViewAfterGitOp() {
        if (currentBranchOrContextName != null) {
            // Re-fetch and display commits for the current context.
            // This is a simplified call; actual parameters for unpushed, canPush/Pull might need re-evaluation.
            // For now, assume a simple refresh of the commit list.
            if (STASHES_VIRTUAL_BRANCH.equals(currentBranchOrContextName)) {
                loadStashesInPanel();
            } else {
                loadCommitsForBranchInPanel(currentBranchOrContextName);
            }
        }
    }

    private void loadStashesInPanel() {
        contextManager.submitBackgroundTask("Fetching stashes", () -> {
            try {
                var stashes = getRepo().listStashes();
                setCommits(stashes, Collections.emptySet(), false, false, STASHES_VIRTUAL_BRANCH);
            } catch (Exception e) {
                logger.error("Error fetching stashes for panel", e);
                SwingUtil.runOnEdt(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(
                            new Object[] {"Error loading stashes: " + e.getMessage(), "", "", "", false, null});
                });
            }
            return null;
        });
    }

    private void loadCommitsForBranchInPanel(String branchName) {
        contextManager.submitBackgroundTask("Fetching commits and state for " + branchName, () -> {
            try {
                var commits = getRepo().listCommitsDetailed(branchName);
                // branchName here is guaranteed not to be STASHES_VIRTUAL_BRANCH or starting with "Search:"
                // as those are handled by loadStashesInPanel and searchCommitsInPanel respectively.
                // The gitWorkflowService.evaluatePushPull method handles branchName.contains("/") internally.
                var pps = gitWorkflow.evaluatePushPull(branchName);
                setCommits(commits, pps.unpushedCommitIds(), pps.canPush(), pps.canPull(), branchName);
            } catch (GitAPIException e) {
                logger.error(
                        "Error fetching commits or push/pull state for panel (branch: {}): {}",
                        branchName,
                        e.getMessage(),
                        e);
                SwingUtil.runOnEdt(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(
                            new Object[] {"Error loading commits: " + e.getMessage(), "", "", "", false, null});
                    chrome.toolError("Error loading commits for " + branchName + ": " + e.getMessage());
                });
            } catch (Exception e) { // Catch other potential runtime exceptions
                logger.error(
                        "Unexpected error fetching commits for panel (branch: {}): {}", branchName, e.getMessage(), e);
                SwingUtil.runOnEdt(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(
                            new Object[] {"Unexpected error: " + e.getMessage(), "", "", "", false, null});
                    chrome.toolError("Unexpected error loading commits for " + branchName + ": " + e.getMessage());
                });
            }
            return null;
        });
    }

    private void searchCommitsInPanel(String query) {
        contextManager.submitBackgroundTask("Searching commits for: " + query, () -> {
            try {
                var searchResults = getRepo().searchCommits(query);
                // For search results, unpushed status and push/pull buttons are less relevant,
                // so we pass empty/false for them.
                setCommits(searchResults, Collections.emptySet(), false, false, "Search: " + query);
                SwingUtil.runOnEdt(() -> {
                    if (searchResults.isEmpty()) chrome.systemOutput("No commits found matching: " + query);
                    else chrome.systemOutput("Found " + searchResults.size() + " commits matching: " + query);
                });
            } catch (Exception e) {
                logger.error("Error searching commits for panel: {}", query, e);
                SwingUtil.runOnEdt(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(
                            new Object[] {"Error searching: " + e.getMessage(), "", "", "", false, null});
                    chrome.toolError("Error searching commits: " + e.getMessage());
                });
            }
            return null;
        });
    }

    public void clearCommitView() {
        this.currentBranchOrContextName = null;
        SwingUtil.runOnEdt(() -> {
            var selectionModel = commitsTable.getSelectionModel();
            selectionModel.setValueIsAdjusting(true);
            try {
                commitsTableModel.setRowCount(0);
                changesRootNode.removeAllChildren();
                changesTreeModel.reload();
                revisionTextLabel.setText("Revision:");
                revisionIdTextArea.setText("N/A");
                if (this.options.showPushPullButtons()) {
                    pullButton.setEnabled(false);
                    pushButton.setEnabled(false);
                }
                if (this.options.showCreatePrButton()) {
                    createPrButton.setEnabled(false);
                }
                viewDiffButton.setEnabled(false);
                clearSearchField();
            } finally {
                selectionModel.setValueIsAdjusting(false);
            }
        });
    }

    public void clearSearchField() {
        if (this.options.showSearch()) {
            SwingUtil.runOnEdt(() -> commitSearchTextField.setText(""));
        }
    }

    public void setCommits(
            List<? extends ICommitInfo> commits,
            Set<String> unpushedCommitIds,
            boolean canPush,
            boolean canPull,
            String activeBranchOrContextName) {
        this.currentBranchOrContextName = activeBranchOrContextName;

        var viewKind = ViewKind.determine(activeBranchOrContextName, getRepo());
        var commitRows = buildCommitRows(commits, unpushedCommitIds);
        var buttonStates =
                determineButtonStates(viewKind, canPush, canPull, unpushedCommitIds, activeBranchOrContextName);

        SwingUtil.runOnEdt(() -> applyStateToUiComponents(commitRows, buttonStates, viewKind));
    }

    private enum ViewKind {
        STASH,
        SEARCH,
        REMOTE,
        LOCAL;

        static ViewKind determine(@Nullable String activeBranchOrContextName, GitRepo repo) {
            if (activeBranchOrContextName == null) {
                // If no branch/context is active, treat as REMOTE for button enablement (disables most actions).
                return REMOTE;
            }
            if (STASHES_VIRTUAL_BRANCH.equals(activeBranchOrContextName)) {
                return STASH;
            }
            if (activeBranchOrContextName.startsWith("Search:")) { // Safe due to null check above
                return SEARCH;
            }
            // activeBranchOrContextName is non-null here.
            if (repo.isRemoteBranch(activeBranchOrContextName)) {
                return REMOTE;
            }
            // If not null, not STASH, not SEARCH, not REMOTE -> must be LOCAL
            return LOCAL;
        }
    }

    private List<Object[]> buildCommitRows(List<? extends ICommitInfo> commits, Set<String> unpushedCommitIds) {
        var commitRows = new ArrayList<Object[]>(commits.size());
        for (ICommitInfo commit : commits) {
            var commitDate = commit.date();
            commitRows.add(new Object[] {
                commit.message(), commit.author(), commitDate,
                commit.id(), unpushedCommitIds.contains(commit.id()), commit
            });
        }
        return commitRows;
    }

    private record ButtonConfig(boolean enabled, String tooltip, @Nullable ActionListener listener) {}

    private record CommitPanelButtonStates(ButtonConfig pull, ButtonConfig push, ButtonConfig createPr) {}

    private CommitPanelButtonStates determineButtonStates(
            ViewKind viewKind,
            boolean serviceCanPush,
            boolean serviceCanPull,
            Set<String> unpushedCommitIds,
            String activeBranchOrContextName) {
        var pullConfig = buildPullConfig(viewKind, serviceCanPull, activeBranchOrContextName);
        var pushConfig = buildPushConfig(viewKind, serviceCanPush, unpushedCommitIds, activeBranchOrContextName);
        var createPrConfig = buildCreatePrConfig(viewKind, activeBranchOrContextName);
        return new CommitPanelButtonStates(pullConfig, pushConfig, createPrConfig);
    }

    private boolean allowPullPush(ViewKind viewKind) {
        return options.showPushPullButtons() && viewKind != ViewKind.STASH && viewKind != ViewKind.SEARCH;
    }

    private ButtonConfig buildPullConfig(ViewKind viewKind, boolean canPullFromService, String branch) {
        if (!options.showPushPullButtons()) {
            return new ButtonConfig(false, "", null);
        }

        boolean enabled = allowPullPush(viewKind) && canPullFromService;
        String tooltip = enabled ? "Pull changes for " + branch : "Cannot pull " + branch;
        ActionListener listener = enabled ? e -> handlePullAction(branch) : null;
        return new ButtonConfig(enabled, tooltip, listener);
    }

    private boolean isNewLocalBranchWithCommits(String branch, Set<String> unpushedIds) {
        if (!unpushedIds
                .isEmpty()) { // If there are unpushed commit IDs, it means an upstream exists or it's not "new" in that
            // sense
            return false;
        }

        var repo = getRepo();
        try {
            // A new local branch has commits but no upstream branch set yet.
            // unpushedCommitIds being empty is a strong indicator if no upstream.
            return !repo.listCommitsDetailed(branch).isEmpty() && !repo.hasUpstreamBranch(branch);
        } catch (GitAPIException ex) {
            logger.warn("Could not determine if branch {} is new local with commits: {}", branch, ex.getMessage());
            return false; // Fallback on error
        }
    }

    private String tooltipForEnabledPush(ViewKind viewKind, String branch, Set<String> unpushedIds) {
        if (viewKind == ViewKind.LOCAL && isNewLocalBranchWithCommits(branch, unpushedIds)) {
            return "Push and set upstream for " + branch;
        }
        return "Push " + unpushedIds.size() + " commit(s) for " + branch;
    }

    private ButtonConfig buildPushConfig(
            ViewKind viewKind, boolean canPushFromService, Set<String> unpushedIds, String branch) {
        if (!options.showPushPullButtons()) {
            return new ButtonConfig(false, "", null);
        }

        boolean enabled = allowPullPush(viewKind) && canPushFromService;
        String tooltip;
        if (enabled) {
            tooltip = tooltipForEnabledPush(viewKind, branch, unpushedIds);
        } else {
            tooltip = "Nothing to push for " + branch;
        }
        ActionListener listener = enabled ? e -> handlePushAction(branch) : null;
        return new ButtonConfig(enabled, tooltip, listener);
    }

    private ButtonConfig buildCreatePrConfig(ViewKind viewKind, String branch) {
        if (!options.showCreatePrButton()) {
            return new ButtonConfig(false, "", null);
        }

        if (viewKind != ViewKind.LOCAL) {
            return new ButtonConfig(
                    false, "Cannot create PR for " + viewKind.name().toLowerCase(Locale.ROOT) + " views", null);
        }

        // Token check first, as it's cheap and local.
        if (!GitHubAuth.tokenPresent()) {
            return new ButtonConfig(
                    false,
                    "A GitHub token is required to create pull requests. Please configure it in settings.",
                    null);
        }

        // Now the expensive check which requires network.
        try {
            if (!GitHubAuth.getOrCreateInstance(contextManager.getProject()).hasWriteAccess()) {
                return new ButtonConfig(
                        false, "Your GitHub token does not have write permissions for this repository.", null);
            }
        } catch (IOException e) {
            logger.warn(
                    "Could not check GitHub repository permissions (e.g. invalid token or network issue). Disabling Create PR button.",
                    e);
            // Fail safe: if we can't check permissions, assume we don't have them. This also handles cases where token
            // is invalid.
            return new ButtonConfig(
                    false, "Your GitHub token does not have write permissions for this repository.", null);
        }

        // If all checks pass
        String tooltip = "Create a pull request for branch " + branch;
        // Listener for createPrButton is static and set during initialization
        return new ButtonConfig(true, tooltip, null);
    }

    private void handlePullAction(String branchName) {
        contextManager.submitExclusiveAction(() -> {
            try {
                String msg = gitWorkflow.pull(branchName);
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput(msg);
                    refreshCurrentViewAfterGitOp();
                    chrome.updateCommitPanel(); // For uncommitted changes
                });
            } catch (GitAPIException ex) {
                logger.error("Error pulling {}: {}", branchName, ex.getMessage());
                SwingUtil.runOnEdt(() -> chrome.toolError("Pull error for " + branchName + ": " + ex.getMessage()));
            }
        });
    }

    private void handlePushAction(String branchName) {
        contextManager.submitExclusiveAction(() -> {
            try {
                String msg = gitWorkflow.push(branchName);
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput(msg);
                    refreshCurrentViewAfterGitOp();
                });
            } catch (GitRepo.GitPushRejectedException ex) {
                logger.warn("Push rejected for {}: {}", branchName, ex.getMessage());
                SwingUtil.runOnEdt(() -> chrome.toolError(
                        "Push rejected for " + branchName + ". Tip: Pull changes first.\nDetails: " + ex.getMessage(),
                        "Push Rejected"));
            } catch (GitAPIException ex) {
                logger.error("Error pushing {}: {}", branchName, ex.getMessage());
                SwingUtil.runOnEdt(() -> chrome.toolError("Push error for " + branchName + ": " + ex.getMessage()));
            }
        });
    }

    private void applyStateToUiComponents(
            List<Object[]> commitRows, CommitPanelButtonStates buttonStates, ViewKind viewKind) {
        commitsTableModel.setRowCount(0);
        changesRootNode.removeAllChildren();
        changesTreeModel.reload();

        if (options.showPushPullButtons()) {
            configureButton(
                    pullButton,
                    buttonStates.pull().enabled(),
                    buttonStates.pull().tooltip(),
                    buttonStates.pull().listener());
            configureButton(
                    pushButton,
                    buttonStates.push().enabled(),
                    buttonStates.push().tooltip(),
                    buttonStates.push().listener());
        }

        if (options.showCreatePrButton()) {
            configureButton(
                    createPrButton,
                    buttonStates.createPr().enabled(),
                    buttonStates.createPr().tooltip(),
                    buttonStates.createPr().listener());
        }

        if (commitRows.isEmpty()) {
            revisionTextLabel.setText("Revision:");
            revisionIdTextArea.setText("N/A");
            if (viewKind != ViewKind.SEARCH) { // Don't show "no commits" for empty search
                commitsTableModel.addRow(new Object[] {"No commits found.", "", "", "", false, null});
            }
            viewDiffButton.setEnabled(false);
            return;
        }

        var selectionModel = commitsTable.getSelectionModel();
        selectionModel.setValueIsAdjusting(true);
        try {
            for (Object[] rowData : commitRows) commitsTableModel.addRow(rowData);
            TableUtils.fitColumnWidth(commitsTable, 1); // Author
            TableUtils.fitColumnWidth(commitsTable, 2); // Date

            if (commitsTableModel.getRowCount() > 0) {
                commitsTable.setRowSelectionInterval(0, 0);
            } else { // Should be covered by commitRows.isEmpty() check, but defensive
                revisionTextLabel.setText("Revision:");
                revisionIdTextArea.setText("N/A");
            }
        } finally {
            selectionModel.setValueIsAdjusting(false);
        }
    }

    // Helper methods from GitLogTab (static or instance methods if they don't depend on GitLogTab's specific state)
    private String getShortId(String commitId) {
        return commitId.length() >= 7 ? commitId.substring(0, 7) : commitId;
    }

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }

    private String getOldHeadId() {
        try {
            return getRepo().getCurrentCommitId();
        } catch (GitAPIException e) {
            return "unknown";
        }
    }

    private void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; i++) tree.expandRow(i);
        if (tree.getRowCount() > rowCount) expandAllNodes(tree, rowCount, tree.getRowCount());
    }

    private boolean hasFileNodesSelected(TreePath[] paths) {
        return Arrays.stream(paths)
                .map(p -> TreeNodeInfo.fromPath(p, changesRootNode))
                .anyMatch(TreeNodeInfo::isFile);
    }

    private List<String> getSelectedFilePathsFromTree() {
        var paths = changesTree.getSelectionPaths();
        if (paths == null) return List.of();
        return Arrays.stream(paths)
                .map(p -> TreeNodeInfo.fromPath(p, changesRootNode).filePath())
                .filter(Objects::nonNull)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    // Helper record for TreePath analysis
    private record TreeNodeInfo(
            @Nullable DefaultMutableTreeNode node,
            DefaultMutableTreeNode rootNode,
            boolean isFile,
            @Nullable String filePath) {
        public static TreeNodeInfo fromPath(@Nullable TreePath path, DefaultMutableTreeNode rootNode) {
            if (path == null) {
                return new TreeNodeInfo(null, rootNode, false, null);
            }
            var node = (DefaultMutableTreeNode) path.getLastPathComponent();

            // A node is a file if (and only if) it is a leaf and not the synthetic root
            boolean isFileResult = node != rootNode && node.isLeaf();

            // Build the full path by concatenating every component except the synthetic root label
            var components = new ArrayList<String>();
            for (var o : path.getPath()) {
                var n = (DefaultMutableTreeNode) o;
                if (n == rootNode) continue; // skip synthetic root
                components.add(n.getUserObject().toString());
            }
            String calculatedFilePath = String.join("/", components);

            return new TreeNodeInfo(node, rootNode, isFileResult, calculatedFilePath);
        }
    } // <-- close TreeNodeInfo record

    public void selectCommitById(String commitId) {
        SwingUtil.runOnEdt(() -> {
            for (int i = 0; i < commitsTableModel.getRowCount(); i++) {
                ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(i, COL_COMMIT_OBJ);
                if (commitInfo != null && commitId.equals(commitInfo.id())) {
                    commitsTable.setRowSelectionInterval(i, i);
                    commitsTable.scrollRectToVisible(commitsTable.getCellRect(i, 0, true));
                    // The selection listener will handle updating changes and revision display
                    return;
                }
            }
            chrome.systemOutput("Commit " + getShortId(commitId) + " not found in current commit browser view.");
        });
    }

    public List<ICommitInfo> getSelectedCommits() {
        int[] selectedRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
        if (selectedRows.length == 0) {
            return List.of();
        }
        return Arrays.stream(selectedRows)
                .mapToObj(row -> (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ))
                .toList();
    }

    /**
     * Returns true if every selected commit is already reachable from HEAD. If reachability cannot be determined,
     * returns false (to favor enabling the action).
     */
    private boolean allSelectedCommitsReachableFromHead() {
        int[] rows = commitsTable.getSelectedRows();
        if (rows.length == 0) {
            return false;
        }
        try {
            for (int row : rows) {
                var ci = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
                if (ci == null) {
                    return false;
                }
                if (!getRepo().isCommitReachableFrom(ci.id(), "HEAD")) {
                    return false;
                }
            }
            return true;
        } catch (GitAPIException e) {
            logger.warn("Failed to evaluate reachability for selected commits: {}", e.getMessage());
            return false;
        }
    }

    /** Builds a git-style tooltip string for a commit. */
    private String buildTooltip(ICommitInfo commit) {
        // Author
        String author = commit.author();

        // Date (git log default format)
        String dateStr = "";
        if (commit.date() != null) {
            dateStr = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy Z", java.util.Locale.US)
                    .format(ZonedDateTime.ofInstant(commit.date(), ZoneId.systemDefault()));
        }

        // Full commit message (fallback to short message if full unavailable)
        String fullMessage;
        try {
            fullMessage = getRepo().getCommitFullMessage(commit.id());
        } catch (Exception e) {
            fullMessage = commit.message();
        }

        // Indent every message line by four spaces
        String indentedMsg = java.util.Arrays.stream(fullMessage.split("\\R"))
                .map(line -> "    " + line)
                .collect(java.util.stream.Collectors.joining("\n"));

        return "Author: " + author + "\n" + "Date:   " + dateStr + "\n\n" + indentedMsg;
    }

    private void configureButton(
            MaterialButton button, boolean enabled, String tooltip, @Nullable ActionListener listener) {
        button.setEnabled(enabled);
        // Visibility is now controlled at a higher level (when adding to panel)
        // and should not be changed here if options.showPushPullButtons or options.showCreatePrButton is true.
        button.setToolTipText(tooltip);
        // Only modify listeners if a new one is provided (e.g., for dynamic behavior, not used by createPrButton here)
        if (listener != null) {
            for (var l : button.getActionListeners()) {
                button.removeActionListener(l);
            }
            if (enabled) { // Only add listener if enabled AND listener is provided
                button.addActionListener(listener);
            }
        }
    }

    private void registerMenu(JPopupMenu menu) {
        chrome.getTheme().registerPopupMenu(menu);
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        MainProject.removeSettingsChangeListener(this);
        relativeTimeRefreshTimer.stop();
    }

    @Override
    public void gitHubTokenChanged() {
        // We need to re-evaluate whether the create PR button should be enabled,
        // which happens as part of loading commits.
        SwingUtil.runOnEdt(this::refreshCurrentViewAfterGitOp);
    }
}
