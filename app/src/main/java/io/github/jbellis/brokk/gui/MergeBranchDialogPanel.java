package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import java.awt.*;
import java.awt.event.ActionListener;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/** Self-contained merge dialog that handles displaying the merge options, conflict checking, and user interaction. */
public class MergeBranchDialogPanel extends JDialog {
    private static final Logger logger = LogManager.getLogger(MergeBranchDialogPanel.class);

    private final JComboBox<GitRepo.MergeMode> mergeModeComboBox;
    private final JLabel conflictStatusLabel;

    @Nullable
    private JLabel dirtyWorkingTreeLabel = null;

    private final String sourceBranch;
    private final String targetBranch;

    private final MaterialButton okButton;
    private boolean dialogResult = false;

    public MergeBranchDialogPanel(Frame parent, String sourceBranch, String targetBranch) {
        super(parent, "Merge Options", true);
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;

        // Initialize fields
        this.mergeModeComboBox = new JComboBox<>(GitRepo.MergeMode.values());
        this.conflictStatusLabel = new JLabel(" ");

        okButton = new MaterialButton("OK");
        initializeDialog();
    }

    private void initializeDialog() {
        setLayout(new BorderLayout());

        // Create main content panel
        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.weightx = 1.0;

        var title = new JLabel(String.format("Merge branch '%s' into '%s'", sourceBranch, targetBranch));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        contentPanel.add(title, gbc);

        // --- merge mode selector -------------------------------------------------
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        contentPanel.add(new JLabel("Merge strategy:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        // mergeModeComboBox already initialized in constructor
        mergeModeComboBox.setSelectedItem(GitRepo.MergeMode.MERGE_COMMIT);
        contentPanel.add(mergeModeComboBox, gbc);

        // --- conflict status label ----------------------------------------------
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(10, 10, 15, 10); // Add extra bottom spacing
        // conflictStatusLabel already initialized in constructor
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground"));
        contentPanel.add(conflictStatusLabel, gbc);

        add(contentPanel, BorderLayout.CENTER);

        // Create button panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var cancelButton = new MaterialButton("Cancel");

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);

        // Set up button actions
        okButton.addActionListener(e -> {
            dialogResult = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
    }

    public static ActionListener createMergeModePersistenceListener(
            JComboBox<GitRepo.MergeMode> mergeModeComboBox, MainProject project) {
        return e -> {
            var selectedMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();
            if (selectedMode != null) {
                project.setLastMergeMode(selectedMode);
            }
        };
    }

    /** Result record for the merge dialog interaction. */
    public record MergeDialogResult(
            boolean confirmed, GitRepo.MergeMode mergeMode, boolean hasConflicts, String conflictMessage) {}

    /**
     * Shows the merge dialog and returns the user's choice.
     *
     * @param gitRepo The git repository for conflict checking
     * @param contextManager The context manager for background tasks
     * @return MergeDialogResult containing the user's choice and conflict status
     */
    public MergeDialogResult showDialog(GitRepo gitRepo, ContextManager contextManager) {
        // Set up merge mode from last-used, and persist changes
        var mainProject = contextManager.getProject().getMainProject();
        var lastMergeMode = mainProject.getLastMergeMode().orElse(GitRepo.MergeMode.MERGE_COMMIT);
        mergeModeComboBox.setSelectedItem(lastMergeMode);

        // To prevent adding duplicate listeners, clear any existing ones.
        for (var listener : mergeModeComboBox.getActionListeners()) {
            mergeModeComboBox.removeActionListener(listener);
        }
        // Add listener to persist selection.
        mergeModeComboBox.addActionListener(createMergeModePersistenceListener(mergeModeComboBox, mainProject));

        // Check for uncommitted changes that prevent merging
        checkDirtyWorkingTree(gitRepo);

        // Set up conflict checking (this will add its own listener)
        Runnable conflictChecker = () -> checkConflictsAsync(gitRepo, contextManager);
        mergeModeComboBox.addActionListener(e -> conflictChecker.run());
        conflictChecker.run(); // Initial check

        // Configure and show dialog
        pack();
        setLocationRelativeTo(getParent());
        setVisible(true);

        // Determine result
        var selectedMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();
        String conflictText = conflictStatusLabel.getText();
        boolean hasConflicts = conflictText != null
                && !conflictText.startsWith("No conflicts detected")
                && !conflictText.trim().isEmpty()
                && !conflictText.equals("Checking for conflicts...");

        // Override dialog result if working tree is dirty to prevent merge
        boolean hasDirtyWorkingTree = dirtyWorkingTreeLabel != null
                && !dirtyWorkingTreeLabel.getText().trim().isEmpty();
        boolean finalResult = dialogResult && !hasDirtyWorkingTree;

        return new MergeDialogResult(finalResult, selectedMode, hasConflicts, conflictText);
    }

    private void checkDirtyWorkingTree(GitRepo gitRepo) {
        try {
            var modifiedFiles = gitRepo.getModifiedFiles();
            if (!modifiedFiles.isEmpty()) {
                var fileCount = modifiedFiles.size();
                var fileWord = fileCount == 1 ? "file" : "files";
                addDirtyWorkingTreeLabel(String.format(
                        "❌ Cannot merge: %d uncommitted %s must be committed or stashed first", fileCount, fileWord));
                // Disable OK button to prevent merge
                okButton.setEnabled(false);
            }
            // If no dirty files, don't add the label at all - keeps layout compact
        } catch (Exception e) {
            logger.warn("Failed to check for uncommitted changes", e);
            addDirtyWorkingTreeLabel("❌ Cannot verify working tree status - please commit or stash changes first");
            // Disable OK button on error to be safe
            okButton.setEnabled(false);
        }
    }

    private void addDirtyWorkingTreeLabel(String message) {
        if (dirtyWorkingTreeLabel == null) {
            dirtyWorkingTreeLabel = new JLabel(message);
            dirtyWorkingTreeLabel.setForeground(Color.RED);

            // Insert the dirty working tree label before the conflict status label
            var contentPanel =
                    (JPanel) ((BorderLayout) getContentPane().getLayout()).getLayoutComponent(BorderLayout.CENTER);
            var gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 2; // Insert at position 2
            gbc.gridwidth = GridBagConstraints.REMAINDER;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(10, 10, 10, 10);

            // Shift conflict status label down to position 3
            var components = contentPanel.getComponents();
            for (var component : components) {
                if (component == conflictStatusLabel) {
                    contentPanel.remove(component);
                    var conflictGbc = new GridBagConstraints();
                    conflictGbc.gridx = 0;
                    conflictGbc.gridy = 3;
                    conflictGbc.gridwidth = GridBagConstraints.REMAINDER;
                    conflictGbc.weightx = 1.0;
                    conflictGbc.fill = GridBagConstraints.HORIZONTAL;
                    conflictGbc.insets = new Insets(10, 10, 15, 10);
                    contentPanel.add(component, conflictGbc);
                    break;
                }
            }

            contentPanel.add(dirtyWorkingTreeLabel, gbc);
            pack(); // Repack to accommodate new component
        } else {
            dirtyWorkingTreeLabel.setText(message);
        }
    }

    private void checkConflictsAsync(GitRepo gitRepo, ContextManager contextManager) {
        okButton.setEnabled(false);
        conflictStatusLabel.setText("Checking for conflicts...");
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground"));

        var selectedMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();

        contextManager.submitBackgroundTask("Checking merge conflicts", () -> {
            String conflictResult;
            try {
                conflictResult = gitRepo.checkMergeConflicts(sourceBranch, targetBranch, selectedMode);
            } catch (GitAPIException e) {
                logger.error("Error checking merge conflicts", e);
                conflictResult = "Error: " + e.getMessage();
            }

            final String finalConflictResult = conflictResult;
            SwingUtilities.invokeLater(() -> {
                if (finalConflictResult != null && !finalConflictResult.isBlank()) {
                    conflictStatusLabel.setText(finalConflictResult);
                    conflictStatusLabel.setForeground(Color.RED);
                    okButton.setEnabled(false);
                } else {
                    conflictStatusLabel.setText("No conflicts detected.");
                    conflictStatusLabel.setForeground(new Color(0, 128, 0)); // Green
                    // Only enable OK button if working tree is clean (no dirty working tree message)
                    if (dirtyWorkingTreeLabel == null
                            || dirtyWorkingTreeLabel.getText().trim().isEmpty()) {
                        okButton.setEnabled(true);
                    }
                }
            });
            return null;
        });
    }
}
