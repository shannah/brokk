package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflow;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.components.MaterialLoadingButton;
import io.github.jbellis.brokk.gui.git.GitCommitBrowserPanel;
import io.github.jbellis.brokk.gui.widgets.FileStatusTable;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

public class CreatePullRequestDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(CreatePullRequestDialog.class);
    private static final int PROJECT_FILE_MODEL_COLUMN_INDEX = 2;

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitWorkflow workflowService;
    private JComboBox<String> sourceBranchComboBox;
    private JComboBox<String> targetBranchComboBox;
    private JTextField titleField;
    private JTextArea descriptionArea;
    private JLabel descriptionHintLabel; // Hint for description generation source
    private GitCommitBrowserPanel commitBrowserPanel;
    private FileStatusTable fileStatusTable;
    private JLabel branchFlowLabel;
    private MaterialLoadingButton createPrButton; // Field for the Create PR button
    private Runnable flowUpdater;
    private List<CommitInfo> currentCommits = Collections.emptyList();

    @Nullable
    private String mergeBaseCommit = null;

    private boolean sourceBranchNeedsPush = false;

    /**
     * Optional branch name that should be pre-selected as the source branch when the dialog opens. May be {@code null}.
     */
    @Nullable
    private final String preselectedSourceBranch;

    @SuppressWarnings("NullAway.Init")
    public CreatePullRequestDialog(
            @Nullable Frame owner,
            Chrome chrome,
            ContextManager contextManager,
            @Nullable String preselectedSourceBranch) {
        super(owner, "Create a Pull Request", false);
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.workflowService = new GitWorkflow(contextManager);
        this.preselectedSourceBranch = preselectedSourceBranch;

        initializeDialog();
        buildLayout();
    }

    @Nullable
    private SuggestPrDetailsWorker currentSuggestPrDetailsWorker;

    @Nullable
    private ScheduledFuture<?> pendingDebounceTask;

    private final ScheduledExecutorService debounceExec =
            Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = Executors.defaultThreadFactory().newThread(r);
                    t.setDaemon(true);
                    t.setName("pr-description-debouncer-" + threadNumber.getAndIncrement());
                    return t;
                }
            });
    // private ScheduledFuture<?> pendingDebounceTask; // Removed duplicate
    private static final long DEBOUNCE_MS = 400;

    public CreatePullRequestDialog(Frame owner, Chrome chrome, ContextManager contextManager) {
        this(owner, chrome, contextManager, null); // delegate
    }

    private void initializeDialog() {
        setSize(1000, 1000);
        setLocationRelativeTo(getOwner());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); // Use WindowConstants
    }

    @Override
    public void dispose() {
        if (currentSuggestPrDetailsWorker != null) {
            currentSuggestPrDetailsWorker.cancel(true);
        }
        debounceExec.shutdownNow();
        super.dispose();
    }

    private void buildLayout() {
        if (getContentPane() instanceof JPanel cp) {
            cp.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Add padding
        }
        setLayout(new BorderLayout());

        // --- top panel: branch selectors -------------------------------------------------------
        var topPanel = new JPanel(new BorderLayout());
        var branchSelectorPanel = createBranchSelectorPanel();
        topPanel.add(branchSelectorPanel, BorderLayout.NORTH);

        // --- title and description panel ----------------------------------------------------
        var prInfoPanel = createPrInfoPanel();
        topPanel.add(prInfoPanel, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        // --- middle: commit browser and file list ---------------------------------------------
        commitBrowserPanel = new GitCommitBrowserPanel(
                chrome,
                contextManager,
                () -> {
                    /* no-op */
                },
                GitCommitBrowserPanel.Options.FOR_PULL_REQUEST);
        // The duplicate initializations that were here have been removed.
        // commitBrowserPanel and fileStatusTable are now initialized once above.
        fileStatusTable = new FileStatusTable();

        var middleTabbedPane = new JTabbedPane();
        middleTabbedPane.addTab("Commits", null, commitBrowserPanel, "Commits included in this pull request");
        middleTabbedPane.addTab("Changes", null, fileStatusTable, "Files changed in this pull request");

        // --- bottom: buttons ------------------------------------------------------------------
        var buttonPanel = createButtonPanel();
        add(middleTabbedPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        // flow-label updater
        this.flowUpdater = createFlowUpdater();

        // initial data load
        loadBranches();
        setupInputListeners(); // Setup listeners for title and description
        updateCreatePrButtonState(); // Initial state for PR button based on (empty) title/desc
        setupFileStatusTableInteractions(); // Wire up diff viewer interactions
    }

    private void setupFileStatusTableInteractions() {
        var tbl = fileStatusTable.getTable();

        // Double-click listener
        tbl.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = tbl.rowAtPoint(e.getPoint());
                    if (viewRow >= 0) {
                        tbl.setRowSelectionInterval(viewRow, viewRow);
                        int modelRow = tbl.convertRowIndexToModel(viewRow);
                        ProjectFile clickedFile =
                                (ProjectFile) tbl.getModel().getValueAt(modelRow, PROJECT_FILE_MODEL_COLUMN_INDEX);
                        openPrDiffViewer(clickedFile);
                    }
                }
            }
        });

        // Context Menu
        var popupMenu = new JPopupMenu();
        var viewDiffItem = new JMenuItem("View Diff");
        viewDiffItem.addActionListener(e -> openPrDiffViewer(null)); // Passing null for priorityFile
        popupMenu.add(viewDiffItem);
        tbl.setComponentPopupMenu(popupMenu);
    }

    private JPanel createPrInfoPanel() {
        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.anchor = GridBagConstraints.WEST;

        // Title
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Title:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        titleField = new JTextField();
        panel.add(titleField, gbc);

        // Description
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST; // Align label to top
        panel.add(new JLabel("Description:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0; // Allow description to take vertical space
        gbc.fill = GridBagConstraints.BOTH;
        descriptionArea = new JTextArea(10, 20); // Initial rows and columns
        var scrollPane = new JScrollPane(descriptionArea);
        panel.add(scrollPane, gbc);

        // Hint label for description generation source
        descriptionHintLabel =
                new JLabel("<html>Description generated from commit messages as the diff was too large.</html>");
        descriptionHintLabel.setFont(descriptionHintLabel
                .getFont()
                .deriveFont(Font.ITALIC, descriptionHintLabel.getFont().getSize() * 0.9f));
        descriptionHintLabel.setVisible(false); // Initially hidden
        gbc.gridx = 1;
        gbc.gridy = 2; // Position below the description area
        gbc.weighty = 0; // Don't take extra vertical space
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST; // Align to top-left of its cell
        panel.add(descriptionHintLabel, gbc);

        return panel;
    }

    private JPanel createBranchSelectorPanel() {
        var branchPanel = new JPanel(new GridBagLayout());
        var row = 0;

        // Create combo boxes first
        targetBranchComboBox = new JComboBox<>();
        sourceBranchComboBox = new JComboBox<>();

        // Then add them to the panel
        row = addBranchSelectorToPanel(branchPanel, "Target branch:", targetBranchComboBox, row);
        row = addBranchSelectorToPanel(branchPanel, "Source branch:", sourceBranchComboBox, row);

        this.branchFlowLabel = createBranchFlowIndicator(branchPanel, row); // Assign to field

        // setupBranchListeners is now called in loadBranches after defaults are set
        // loadBranches is called after the main layout is built

        return branchPanel;
    }

    private int addBranchSelectorToPanel(JPanel parent, String labelText, JComboBox<String> comboBox, int row) {
        var gbc = createGbc(0, row);
        parent.add(new JLabel(labelText), gbc);

        gbc = createGbc(1, row);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        parent.add(comboBox, gbc);

        return row + 1;
    }

    private JLabel createBranchFlowIndicator(JPanel parent, int row) {
        var branchFlowLabel = new JLabel("", SwingConstants.CENTER);
        branchFlowLabel.setFont(branchFlowLabel.getFont().deriveFont(Font.BOLD));

        var gbc = createGbc(0, row);
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        parent.add(branchFlowLabel, gbc);

        return branchFlowLabel;
    }

    /** Simple immutable holder for commits and changed files between two branches. */
    // Fix the UnnecessaryLambda warning by implementing updateBranchFlow as a method
    private void updateBranchFlow() {
        var target = (String) targetBranchComboBox.getSelectedItem();
        var source = (String) sourceBranchComboBox.getSelectedItem();
        if (target != null && source != null) {
            String baseText = target + " ← " + source;
            String suffix = " (" + currentCommits.size() + " commits)";
            if (sourceBranchNeedsPush) {
                suffix += " ⚠ needs push";
                branchFlowLabel.setForeground(Color.ORANGE);
                branchFlowLabel.setToolTipText("Local branch is ahead of its remote – push first");
            } else {
                branchFlowLabel.setForeground(UIManager.getColor("Label.foreground"));
                branchFlowLabel.setToolTipText(null);
            }
            this.branchFlowLabel.setText(baseText + suffix);
        } else {
            this.branchFlowLabel.setText(""); // Clear if branches not selected
        }
    }

    private Runnable createFlowUpdater() {
        return this::updateBranchFlow;
    }

    private void setupBranchListeners() {
        ActionListener branchChangedListener = e -> {
            // Immediately update flow label for responsiveness before async refresh.
            this.currentCommits = Collections.emptyList();
            this.flowUpdater.run();
            // Full UI update, including button state, will be handled by refreshCommitList.
            refreshCommitList();
        };
        targetBranchComboBox.addActionListener(branchChangedListener);
        sourceBranchComboBox.addActionListener(branchChangedListener);
    }

    private void updateCommitRelatedUI(
            List<CommitInfo> newCommits, List<GitRepo.ModifiedFile> newFiles, String commitPanelMessage) {
        Collections.reverse(newCommits);
        this.currentCommits = newCommits;
        commitBrowserPanel.setCommits(newCommits, Set.of(), false, false, commitPanelMessage);
        fileStatusTable.setFiles(newFiles);
        this.flowUpdater.run();
        updateCreatePrButtonState();
    }

    private void refreshCommitList() {
        this.mergeBaseCommit = null; // Ensure merge base is reset for each refresh

        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        var targetBranch = (String) targetBranchComboBox.getSelectedItem();

        if (sourceBranch == null || targetBranch == null) {
            updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), "Select branches");
            return;
        }

        var contextName = targetBranch + " ← " + sourceBranch;

        if (sourceBranch.equals(targetBranch)) {
            updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), contextName);
            return;
        }

        contextManager.submitBackgroundTask("Fetching commits for " + contextName, () -> {
            try {
                var repo = contextManager.getProject().getRepo();
                if (!(repo instanceof GitRepo gitRepo)) {
                    String nonGitMessage = "Project is not a Git repository.";
                    SwingUtilities.invokeLater(() ->
                            updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), nonGitMessage));
                    return Collections.emptyList();
                }

                // Use GitWorkflowService to get branch diff information
                var branchDiff = workflowService.diffBetweenBranches(sourceBranch, targetBranch);
                this.mergeBaseCommit = branchDiff.mergeBase(); // Store merge base from service
                logger.debug(
                        "Calculated merge base between {} and {}: {}",
                        sourceBranch,
                        targetBranch,
                        this.mergeBaseCommit);

                // Check if source branch needs push (for UI indicator)
                this.sourceBranchNeedsPush = gitRepo.branchNeedsPush(sourceBranch);

                if (branchDiff.commits().isEmpty()) {
                    // Nothing to describe; stop any ongoing generation and clear fields.
                    cancelGenerationWorkersAndClearFields();
                } else {
                    // If commits exist, trigger debounced PR suggestion
                    debounceSuggestPrDetails(sourceBranch, targetBranch);
                }

                SwingUtilities.invokeLater(
                        () -> updateCommitRelatedUI(branchDiff.commits(), branchDiff.files(), contextName));
                return branchDiff.commits();
            } catch (Exception e) {
                logger.error("Error fetching branch diff or suggesting PR details for " + contextName, e);
                SwingUtilities.invokeLater(() -> {
                    updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), contextName + " (error)");
                    cancelGenerationWorkersAndClearFields(); // Also clear fields on error
                    descriptionArea.setText("(Could not generate PR details due to error)");
                    titleField.setText("");
                });
                throw e;
            }
        });
    }

    private void updateCreatePrButtonState() {
        List<String> blockers = getCreatePrBlockers();
        createPrButton.setEnabled(blockers.isEmpty());
        createPrButton.setToolTipText(blockers.isEmpty() ? null : formatBlockersTooltip(blockers));
    }

    private List<String> getCreatePrBlockers() {
        var blockers = new ArrayList<String>();
        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        var targetBranch = (String) targetBranchComboBox.getSelectedItem();

        if (currentCommits.isEmpty()) {
            blockers.add("No commits to include in the pull request.");
        }
        if (sourceBranch == null) {
            blockers.add("Source branch not selected.");
        }
        if (targetBranch == null) {
            blockers.add("Target branch not selected.");
        }
        // This check should only be added if both branches are selected, otherwise it's redundant.
        if (sourceBranch != null && sourceBranch.equals(targetBranch)) {
            blockers.add("Source and target branches cannot be the same.");
        }
        if (titleField.getText() == null || titleField.getText().trim().isEmpty()) {
            blockers.add("Title cannot be empty.");
        }
        if (descriptionArea.getText() == null
                || descriptionArea.getText().trim().isEmpty()) {
            blockers.add("Description cannot be empty.");
        }
        if (sourceBranchNeedsPush) {
            blockers.add("Local branch is ahead of its remote – push first.");
        }
        return List.copyOf(blockers);
    }

    private @Nullable String formatBlockersTooltip(List<String> blockers) {
        if (blockers.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder("<html>");
        for (String blocker : blockers) {
            sb.append("• ").append(blocker).append("<br>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    /** Checks whether the dialog has sufficient information to enable PR creation. */
    private boolean isCreatePrReady() {
        return getCreatePrBlockers().isEmpty();
    }

    private void setupInputListeners() {
        javax.swing.event.DocumentListener inputChangedListener = new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateCreatePrButtonState();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateCreatePrButtonState();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateCreatePrButtonState();
            }
        };
        titleField.getDocument().addDocumentListener(inputChangedListener);
        descriptionArea.getDocument().addDocumentListener(inputChangedListener);
    }

    private JPanel createButtonPanel() {
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        this.createPrButton = new MaterialLoadingButton("Create PR", null, chrome, null); // Assign to field
        this.createPrButton.addActionListener(e -> createPullRequest());

        // Style Create PR button as primary action (bright blue with white text)
        io.github.jbellis.brokk.gui.SwingUtil.applyPrimaryButtonStyle(this.createPrButton);
        buttonPanel.add(this.createPrButton);

        var cancelButton = new MaterialButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        return buttonPanel;
    }

    private GridBagConstraints createGbc(int x, int y) {
        var gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    private void loadBranches() {
        var repo = contextManager.getProject().getRepo();
        if (!(repo instanceof GitRepo gitRepo)) {
            logger.warn("PR dialog opened for non-Git repository");
            return;
        }

        try {
            var localBranches = gitRepo.listLocalBranches();
            var remoteBranches = gitRepo.listRemoteBranches();

            var targetBranches = getTargetBranches(remoteBranches);
            var sourceBranches = getSourceBranches(localBranches, remoteBranches);

            populateBranchDropdowns(targetBranches, sourceBranches);
            setDefaultBranchSelections(gitRepo, targetBranches, sourceBranches, localBranches);

            // If caller asked for a specific source branch, honour it *after*
            // defaults have been applied (so this wins).
            if (preselectedSourceBranch != null && sourceBranches.contains(preselectedSourceBranch)) {
                sourceBranchComboBox.setSelectedItem(preselectedSourceBranch);
            }

            // Listeners must be set up AFTER default items are selected to avoid premature firing.
            setupBranchListeners();

            this.flowUpdater.run(); // Update label based on defaults
            refreshCommitList(); // Load commits based on defaults, which will also call flowUpdater and update button
            // state
            // updateCreatePrButtonState(); // No longer needed here, refreshCommitList handles it
        } catch (GitAPIException e) {
            logger.error("Error loading branches for PR dialog", e);
            // Ensure UI is updated consistently on error, using the new helper method
            SwingUtilities.invokeLater(() ->
                    updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), "Error loading branches"));
        }
    }

    private List<String> getTargetBranches(List<String> remoteBranches) {
        return remoteBranches.stream()
                .filter(branch -> branch.startsWith("origin/"))
                .filter(branch -> !branch.equals("origin/HEAD"))
                .toList();
    }

    private List<String> getSourceBranches(List<String> localBranches, List<String> remoteBranches) {
        return java.util.stream.Stream.concat(localBranches.stream(), remoteBranches.stream())
                .distinct()
                .sorted()
                .toList();
    }

    private void populateBranchDropdowns(List<String> targetBranches, List<String> sourceBranches) {
        targetBranchComboBox.setModel(new DefaultComboBoxModel<>(targetBranches.toArray(new String[0])));
        sourceBranchComboBox.setModel(new DefaultComboBoxModel<>(sourceBranches.toArray(new String[0])));
    }

    private void setDefaultBranchSelections(
            GitRepo gitRepo, List<String> targetBranches, List<String> sourceBranches, List<String> localBranches)
            throws GitAPIException {
        var defaultTarget = findDefaultTargetBranch(targetBranches);
        if (defaultTarget != null) {
            targetBranchComboBox.setSelectedItem(defaultTarget);
        }

        selectDefaultSourceBranch(gitRepo, sourceBranches, localBranches);
    }

    @Nullable
    private String findDefaultTargetBranch(List<String> targetBranches) {
        if (targetBranches.contains("origin/main")) {
            return "origin/main";
        }
        if (targetBranches.contains("origin/master")) {
            return "origin/master";
        }
        return targetBranches.isEmpty() ? null : targetBranches.getFirst();
    }

    private void selectDefaultSourceBranch(GitRepo gitRepo, List<String> sourceBranches, List<String> localBranches)
            throws GitAPIException {
        var currentBranch = gitRepo.getCurrentBranch();
        if (sourceBranches.contains(currentBranch)) {
            sourceBranchComboBox.setSelectedItem(currentBranch);
        } else if (!localBranches.isEmpty()) {
            sourceBranchComboBox.setSelectedItem(localBranches.getFirst());
        } else if (!sourceBranches.isEmpty()) {
            sourceBranchComboBox.setSelectedItem(sourceBranches.getFirst());
        }
    }

    public static void show(Frame owner, Chrome chrome, ContextManager contextManager) {
        CreatePullRequestDialog dialog = new CreatePullRequestDialog(owner, chrome, contextManager);
        dialog.setVisible(true);
    }

    /** Convenience helper to open the dialog with a pre-selected source branch. */
    public static void show(
            @Nullable Frame owner, Chrome chrome, ContextManager contextManager, @Nullable String sourceBranch) {
        CreatePullRequestDialog dialog = new CreatePullRequestDialog(owner, chrome, contextManager, sourceBranch);
        dialog.setVisible(true);
    }

    private List<ProjectFile> getAllFilesFromFileStatusTable() {
        var files = new ArrayList<ProjectFile>();
        var model = fileStatusTable.getTable().getModel();
        for (int i = 0; i < model.getRowCount(); i++) {
            files.add((ProjectFile) model.getValueAt(i, PROJECT_FILE_MODEL_COLUMN_INDEX));
        }
        return files;
    }

    private BufferSource.StringSource createBufferSource(GitRepo repo, String commitSHA, ProjectFile f)
            throws GitAPIException {
        var content = repo.getFileContent(commitSHA, f);
        return new BufferSource.StringSource(content, commitSHA, f.toString());
    }

    private List<ProjectFile> getOrderedFilesForDiff(@Nullable ProjectFile priorityFile) {
        var allFilesInTable = getAllFilesFromFileStatusTable();
        var selectedFiles = fileStatusTable.getSelectedFiles();
        var orderedFiles = new ArrayList<ProjectFile>();

        if (priorityFile != null) {
            orderedFiles.add(priorityFile);
        }

        selectedFiles.stream()
                .filter(selected -> !orderedFiles.contains(selected))
                .forEach(orderedFiles::add);

        allFilesInTable.stream()
                .filter(fileInTable -> !orderedFiles.contains(fileInTable))
                .forEach(orderedFiles::add);

        return orderedFiles;
    }

    private record FileComparisonSources(BufferSource left, BufferSource right) {}

    private FileComparisonSources createFileComparisonSources(
            GitRepo gitRepo, String mergeBaseSha, String sourceCommitIsh, ProjectFile file, String status)
            throws GitAPIException {
        BufferSource leftSrc;
        BufferSource rightSrc;

        if ("deleted".equals(status)) {
            leftSrc = createBufferSource(gitRepo, mergeBaseSha, file);
            rightSrc = new BufferSource.StringSource("", "Source Branch (Deleted)", file.toString());
        } else {
            // For "added" files, createBufferSource(gitRepo, mergeBaseSha, file) will attempt to load from merge base.
            // Assumes GitRepo.getFileContent handles non-existent files gracefully (e.g., returns empty string).
            leftSrc = createBufferSource(gitRepo, mergeBaseSha, file);
            rightSrc = createBufferSource(gitRepo, sourceCommitIsh, file);
        }
        return new FileComparisonSources(leftSrc, rightSrc);
    }

    private void buildAndShowDiffPanel(
            List<ProjectFile> orderedFiles, String mergeBaseCommitSha, String sourceBranchName) {
        try {
            var repo = contextManager.getProject().getRepo();
            if (!(repo instanceof GitRepo gitRepo)) {
                chrome.toolError("Repository is not a Git repository.", "Diff Error");
                return;
            }

            var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager);

            for (var f : orderedFiles) {
                String status = fileStatusTable.statusFor(f);
                var sources = createFileComparisonSources(gitRepo, mergeBaseCommitSha, sourceBranchName, f, status);
                builder.addComparison(sources.left(), sources.right());
            }
            SwingUtilities.invokeLater(() -> builder.build().showInFrame("Pull Request Diff"));
        } catch (Exception ex) {
            logger.error("Unable to open PR diff viewer", ex);
            chrome.toolError("Unable to open diff: " + ex.getMessage(), "Diff Error");
        }
    }

    private void openPrDiffViewer(@Nullable ProjectFile priorityFile) {
        List<ProjectFile> orderedFiles = getOrderedFilesForDiff(priorityFile);

        final String currentMergeBase = this.mergeBaseCommit;
        final String currentSourceBranch = (String) sourceBranchComboBox.getSelectedItem();

        if (currentSourceBranch == null) {
            chrome.toolError("Source branch is not selected.", "Diff Error");
            return;
        }
        if (currentMergeBase == null) {
            chrome.toolError("Merge base could not be determined. Cannot show diff.", "Diff Error");
            logger.warn(
                    "Merge base is null when trying to open PR diff viewer. Source: {}, Target: {}",
                    currentSourceBranch,
                    targetBranchComboBox.getSelectedItem());
            return;
        }

        contextManager.submitExclusiveAction(() -> {
            buildAndShowDiffPanel(orderedFiles, currentMergeBase, currentSourceBranch);
            return null;
        });
    }

    /** SwingWorker to suggest PR title and description using GitWorkflowService. */
    private class SuggestPrDetailsWorker extends SwingWorker<GitWorkflow.PrSuggestion, Void> {
        private final String sourceBranch;
        private final String targetBranch;

        SuggestPrDetailsWorker(String sourceBranch, String targetBranch) {
            this.sourceBranch = sourceBranch;
            this.targetBranch = targetBranch;
        }

        @Override
        protected GitWorkflow.PrSuggestion doInBackground() throws Exception {
            if (isCancelled()) {
                throw new InterruptedException("Worker cancelled before starting");
            }
            // This service call is blocking and includes LLM interactions.
            return workflowService.suggestPullRequestDetails(sourceBranch, targetBranch);
        }

        @Override
        protected void done() {
            try {
                GitWorkflow.PrSuggestion suggestion =
                        get(); // This will throw specific exceptions if cancelled/interrupted.
                SwingUtilities.invokeLater(() -> {
                    setTextAndResetCaret(titleField, suggestion.title());
                    setTextAndResetCaret(descriptionArea, suggestion.description());
                    showDescriptionHint(suggestion.usedCommitMessages());
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                logger.warn("SuggestPrDetailsWorker interrupted for {} -> {}", sourceBranch, targetBranch, e);
                SwingUtilities.invokeLater(() -> {
                    setTextAndResetCaret(titleField, "(suggestion interrupted)");
                    setTextAndResetCaret(descriptionArea, "(suggestion interrupted)");
                    showDescriptionHint(false);
                });
            } catch (java.util.concurrent.CancellationException e) {
                logger.warn("SuggestPrDetailsWorker cancelled for {} -> {}", sourceBranch, targetBranch, e);
                SwingUtilities.invokeLater(() -> {
                    setTextAndResetCaret(titleField, "(suggestion cancelled)");
                    setTextAndResetCaret(descriptionArea, "(suggestion cancelled)");
                    showDescriptionHint(false);
                });
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InterruptedException interruptedException) {
                    Thread.currentThread().interrupt(); // Preserve interrupt status
                    logger.warn(
                            "SuggestPrDetailsWorker execution failed due to underlying interruption for {} -> {}",
                            sourceBranch,
                            targetBranch,
                            interruptedException);
                    SwingUtilities.invokeLater(() -> {
                        setTextAndResetCaret(titleField, "(suggestion interrupted)");
                        setTextAndResetCaret(descriptionArea, "(suggestion interrupted)");
                        showDescriptionHint(false);
                    });
                } else {
                    logger.warn(
                            "SuggestPrDetailsWorker failed with ExecutionException for {} -> {}: {}",
                            sourceBranch,
                            targetBranch,
                            (cause != null ? cause.getMessage() : e.getMessage()),
                            cause);
                    SwingUtilities.invokeLater(() -> {
                        String errorMessage =
                                (cause != null && cause.getMessage() != null) ? cause.getMessage() : e.getMessage();
                        errorMessage = (errorMessage == null) ? "Unknown error" : errorMessage;
                        setTextAndResetCaret(titleField, "(suggestion failed)");
                        setTextAndResetCaret(descriptionArea, "(suggestion failed: " + errorMessage + ")");
                        showDescriptionHint(false);
                    });
                }
            }
        }
    }

    private static void setTextAndResetCaret(javax.swing.text.JTextComponent textComponent, String text) {
        textComponent.setText(text);
        textComponent.setCaretPosition(0);
    }

    private void createPullRequest() {
        if (!isCreatePrReady()) {
            // This should ideally not happen if button state is managed correctly,
            // but as a safeguard:
            chrome.toolError(
                    "Cannot create Pull Request. Please check details and ensure branch is pushed.",
                    "PR Creation Error");
            return;
        }

        createPrButton.setLoading(true, "Creating PR…");

        contextManager.submitExclusiveAction(() -> {
            try {
                // Gather details on the EDT before going to background if they come from Swing components
                final String title = titleField.getText().trim();
                final String body = descriptionArea.getText().trim();
                // Removed duplicate declarations of title and body
                final String sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
                final String targetBranch = (String) targetBranchComboBox.getSelectedItem();

                // Ensure selectedItem calls are safe
                if (sourceBranch == null || targetBranch == null) {
                    throw new IllegalStateException("Source or target branch not selected.");
                }

                var prUrl = workflowService.createPullRequest(sourceBranch, targetBranch, title, body);

                // Success: Open in browser and dispose dialog
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(prUrl);
                } else {
                    logger.warn("Desktop browse action not supported, cannot open PR URL automatically: {}", prUrl);
                    SwingUtilities.invokeLater(() -> chrome.systemNotify(
                            "PR created: " + prUrl, "Pull Request Created", JOptionPane.INFORMATION_MESSAGE));
                }
                SwingUtilities.invokeLater(this::dispose);

            } catch (Exception ex) {
                logger.error("Pull Request creation failed", ex);
                // The service method might throw GitRepo.GitPushRejectedException or other specific exceptions.
                // For now, catch generic Exception and display its message.
                SwingUtilities.invokeLater(() -> {
                    chrome.toolError("Unable to create Pull Request:\n" + ex.getMessage(), "PR Creation Error");
                    // Reset button only if dialog is still displayable (it wasn't disposed on success)
                    if (isDisplayable()) {
                        createPrButton.setLoading(false, null);
                    }
                });
            }
            return null; // Void task
        });
    }

    // Cancels any pending title/description generation tasks and resets the UI.
    private void cancelGenerationWorkersAndClearFields() {
        if (pendingDebounceTask != null) {
            pendingDebounceTask.cancel(false);
            pendingDebounceTask = null;
        }
        if (currentSuggestPrDetailsWorker != null) {
            currentSuggestPrDetailsWorker.cancel(true);
            currentSuggestPrDetailsWorker = null;
        }
        SwingUtilities.invokeLater(() -> {
            titleField.setText("");
            descriptionArea.setText("");
            showDescriptionHint(false); // Hide hint when clearing
            updateCreatePrButtonState();
        });
    }

    private void showDescriptionHint(boolean show) {
        SwingUtilities.invokeLater(() -> {
            descriptionHintLabel.setVisible(show);
        });
    }

    /**
     * Debounces the call to generate PR suggestions. The actual decision of using diff vs commit messages is now inside
     * the service.
     */
    private void debounceSuggestPrDetails(String sourceBranch, String targetBranch) {
        SwingUtilities.invokeLater(
                () -> { // Ensure UI updates are on EDT
                    setTextAndResetCaret(descriptionArea, "Generating PR details...");
                    setTextAndResetCaret(titleField, "Generating PR details...");
                    // The hint will be set by the worker's done() method based on service response.
                    // For now, ensure it's hidden during generation.
                    showDescriptionHint(false);
                });

        if (pendingDebounceTask != null) {
            pendingDebounceTask.cancel(false);
        }
        pendingDebounceTask = debounceExec.schedule(
                () -> spawnSuggestPrDetailsWorker(sourceBranch, targetBranch), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void spawnSuggestPrDetailsWorker(String sourceBranch, String targetBranch) {
        // Cancel previous background LLM calls
        if (currentSuggestPrDetailsWorker != null) {
            currentSuggestPrDetailsWorker.cancel(true);
        }

        currentSuggestPrDetailsWorker = new SuggestPrDetailsWorker(sourceBranch, targetBranch);
        currentSuggestPrDetailsWorker.execute();
    }
}
