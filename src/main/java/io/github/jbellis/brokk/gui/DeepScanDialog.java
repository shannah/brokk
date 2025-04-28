package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.agents.ValidationAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * Handles the execution of the Deep Scan agents (Context and Validation)
 * and presents the results in a modal dialog for user selection.
 */
class DeepScanDialog {
    private static final Logger logger = LogManager.getLogger(DeepScanDialog.class);

    // Action options for the dropdowns
    private static final String OMIT = "Omit";
    private static final String SUMMARIZE = "Summarize";
    private static final String EDIT = "Edit";
    private static final String READ_ONLY = "Read-only";

    /**
     * Triggers the Deep Scan agents (Context and Validation) in the background,
     * waits for their results, and then shows a modal dialog for user selection.
     * Handles errors and interruptions gracefully.
     *
     * @param chrome The main application window reference.
     * @param goal   The user's instruction/goal for the scan.
     */
    public static void triggerDeepScan(Chrome chrome, String goal) {
        var contextManager = chrome.getContextManager();
        if (goal.isBlank() || contextManager == null || contextManager.getProject() == null) {
            chrome.toolErrorRaw("Please enter instructions before running Deep Scan.");
            return;
        }

        // Disable input and deep scan button while scanning
        chrome.getInstructionsPanel().setCommandInputAndDeepScanEnabled(false);
        chrome.systemOutput("Starting Deep Scan");

        // Submit the overall task to the user action queue for cancellation handling.
        // The individual agent tasks run via submitBackgroundTask.
        contextManager.submitUserTask("Deep Scan context analysis", () -> {
            try {
                // Context Agent Task using submitBackgroundTask
                Future<List<ProjectFile>> contextFuture = contextManager.submitBackgroundTask("Deep Scan: ContextAgent", () -> {
                    logger.debug("Deep Scan: Running ContextAgent...");
                    var model = contextManager.getAskModel(); // Use ask model for quality context
                    // Use full workspace context for deep scan
                    var agent = new ContextAgent(contextManager, model, goal, true);
                    var recommendations = agent.getRecommendations(20); // Increase limit for deep scan
                    var files = recommendations.stream()
                            .flatMap(f -> f.files(contextManager.getProject()).stream())
                            .distinct()
                            .toList();
                    logger.debug("Deep Scan: ContextAgent found {} files.", files.size());
                    return files;
                });

                // Validation Agent Task using submitBackgroundTask
                Future<List<ProjectFile>> validationFuture = contextManager.submitBackgroundTask("Deep Scan: ValidationAgent", () -> {
                    logger.debug("Deep Scan: Running ValidationAgent...");
                    var agent = new ValidationAgent(contextManager);
                    var relevantTestResults = agent.execute(goal); // ValidationAgent finds relevant tests
                    var files = relevantTestResults.stream()
                            .distinct()
                            .toList();
                    logger.debug("Deep Scan: ValidationAgent found {} relevant test files.", files.size());
                    return files;
                });

                // Get results from futures - this will block until completion
                var contextFiles = contextFuture.get(); // Can throw ExecutionException or InterruptedException
                var validationFiles = validationFuture.get(); // Can throw ExecutionException or InterruptedException

                // Check for interruption *after* getting results (if get() didn't throw InterruptedException)
                if (Thread.currentThread().isInterrupted()) {
                    logger.debug("Deep Scan task interrupted after agent completion.");
                    chrome.systemOutput("Deep Scan interrupted.");
                    return;
                }
                // Combine and deduplicate results
                var allSuggestedFiles = Stream.concat(contextFiles.stream(), validationFiles.stream())
                        .distinct()
                        .sorted(Comparator.comparing(ProjectFile::toString))
                        .toList();

                logger.debug("Deep Scan finished. Found {} unique files total.", allSuggestedFiles.size());

                if (allSuggestedFiles.isEmpty()) {
                    chrome.systemOutput("Deep Scan complete: No relevant files found.");
                } else {
                    chrome.systemOutput("Deep Scan complete: Found %d relevant files. Showing selection dialog...".formatted(allSuggestedFiles.size()));
                    // Show the dialog on the EDT
                    SwingUtil.runOnEDT(() -> showDialog(chrome, allSuggestedFiles));
                }
            } // End of the try block
            catch (ExecutionException ee) {
                // Handle exceptions thrown by the background tasks (inside future.get())
                if (ee.getCause() instanceof InterruptedException) {
                    logger.debug("Deep Scan agent task interrupted during execution: {}", ee.getMessage());
                    chrome.systemOutput("Deep Scan interrupted.");
                    Thread.currentThread().interrupt(); // Re-interrupt the user task thread
                } else {
                    logger.error("Error during Deep Scan agent execution", ee.getCause());
                    chrome.toolErrorRaw("Error during Deep Scan execution: " + ee.getCause().getMessage());
                }
            } catch (InterruptedException ie) {
                // Handle interruption of the user task thread (e.g., while waiting on future.get())
                logger.debug("Deep Scan user task explicitly interrupted: {}", ie.getMessage());
                chrome.systemOutput("Deep Scan interrupted.");
                Thread.currentThread().interrupt(); // Re-interrupt
            } finally {
                // Re-enable input components after task completion, error, or interruption
                SwingUtilities.invokeLater(() -> chrome.getInstructionsPanel().setCommandInputAndDeepScanEnabled(true));
            }
        }); // End of submitUserTask lambda
    }


    /**
     * Shows a modal dialog for the user to select files suggested by Deep Scan.
     * Files can be added as read-only, editable, or summarized.
     * This method MUST be called on the Event Dispatch Thread (EDT).
     *
     * @param chrome          The main application window reference.
     * @param suggestedFiles List of unique ProjectFiles suggested by ContextAgent and ValidationAgent.
     */
    private static void showDialog(Chrome chrome, List<ProjectFile> suggestedFiles) {
        assert SwingUtilities.isEventDispatchThread(); // Ensure called on EDT

        var contextManager = chrome.getContextManager();
        var testFiles = new HashSet<>(contextManager.getTestFiles());
        Project project = contextManager.getProject();
        boolean hasGit = project != null && project.hasGit();

        // Separate files into code and tests
        var projectCodeFiles = suggestedFiles.stream()
                .filter(f -> !testFiles.contains(f))
                .toList();
        var testCodeFiles = suggestedFiles.stream()
                .filter(testFiles::contains)
                .toList();

        JDialog dialog = new JDialog(chrome.getFrame(), "Deep Scan Results", true); // Modal dialog
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setMinimumSize(new Dimension(400, 400)); // Increased width for dropdowns

        // Main panel to hold the two sections
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Maps to hold dropdowns and their corresponding files
        Map<JComboBox<String>, ProjectFile> projectCodeComboboxMap = new LinkedHashMap<>();
        Map<JComboBox<String>, ProjectFile> testCodeComboboxMap = new LinkedHashMap<>();

        // Options for project code files
        String[] projectOptions = {OMIT, SUMMARIZE, EDIT, READ_ONLY};
        // Options for test code files (no Summarize)
        String[] testOptions = {OMIT, EDIT, READ_ONLY};

        // Helper function to create a file row panel
        BiFunction<ProjectFile, String[], JPanel> createFileRow = (file, options) -> {
            JPanel rowPanel = new JPanel(new BorderLayout(5, 0)); // Use BorderLayout for better alignment
            rowPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0)); // Padding between rows

            String fullPath = file.toString();
            String fileName = file.getFileName();
            JLabel fileLabel = new JLabel(fileName);
            fileLabel.setToolTipText(fullPath); // Show full path in tooltip
            fileLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10)); // Right padding for label

            JComboBox<String> comboBox = new JComboBox<>(options);
            comboBox.setSelectedItem(OMIT); // Default action

            if (!hasGit) {
                comboBox.setToolTipText("'" + EDIT + "' option requires a Git repository");
            }

            // Set preferred width for the combo box
            comboBox.setPreferredSize(new Dimension(120, comboBox.getPreferredSize().height));

            rowPanel.add(fileLabel, BorderLayout.CENTER);
            rowPanel.add(comboBox, BorderLayout.EAST);

            return rowPanel;
        };


        // --- Project Code Section ---
        JPanel projectCodeSection = new JPanel();
        projectCodeSection.setLayout(new BoxLayout(projectCodeSection, BoxLayout.Y_AXIS));
        projectCodeSection.setBorder(BorderFactory.createTitledBorder("Project Code"));
        if (projectCodeFiles.isEmpty()) {
            projectCodeSection.add(new JLabel("No relevant files found"));
        } else {
            for (ProjectFile file : projectCodeFiles) {
                JPanel row = createFileRow.apply(file, projectOptions);
                JComboBox<String> comboBox = (JComboBox<String>) row.getComponent(1); // Assuming combo is the second component
                projectCodeComboboxMap.put(comboBox, file);
                projectCodeSection.add(row);
            }
        }
        // Wrap project code section in a scroll pane
        JScrollPane projectCodeScrollPane = new JScrollPane(projectCodeSection);
        projectCodeScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        projectCodeScrollPane.setBorder(projectCodeSection.getBorder()); // Use the section's border for the scroll pane
        projectCodeSection.setBorder(null); // Remove border from the section itself
        mainPanel.add(projectCodeScrollPane);

        // Add spacing between sections
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // --- Test Code Section ---
        JPanel testCodeSection = new JPanel();
        testCodeSection.setLayout(new BoxLayout(testCodeSection, BoxLayout.Y_AXIS));
        testCodeSection.setBorder(BorderFactory.createTitledBorder("Tests"));
        if (testCodeFiles.isEmpty()) {
            testCodeSection.add(new JLabel("No relevant files found"));
        } else {
            for (ProjectFile file : testCodeFiles) {
                JPanel row = createFileRow.apply(file, testOptions);
                JComboBox<String> comboBox = (JComboBox<String>) row.getComponent(1); // Assuming combo is the second component
                testCodeComboboxMap.put(comboBox, file);
                testCodeSection.add(row);
            }
        }
        // Wrap test code section in a scroll pane
        JScrollPane testCodeScrollPane = new JScrollPane(testCodeSection);
        testCodeScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        testCodeScrollPane.setBorder(testCodeSection.getBorder()); // Use the section's border for the scroll pane
        testCodeSection.setBorder(null); // Remove border from the section itself
        mainPanel.add(testCodeScrollPane);

        // Add the main panel (containing the scroll panes) to the dialog's center.
        // No need for an extra outer scroll pane here anymore.
        dialog.add(mainPanel, BorderLayout.CENTER);

        // --- Button Panel ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton applyButton = new JButton("Apply Selections");
        JButton cancelButton = new JButton("Cancel");

        buttonPanel.add(applyButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        // --- Actions ---
        applyButton.addActionListener(e -> {
            Set<ProjectFile> filesToSummarize = new HashSet<>();
            List<ProjectFile> filesToEdit = new ArrayList<>();
            List<ProjectFile> filesToReadOnly = new ArrayList<>();

            // Process project code selections
            projectCodeComboboxMap.forEach((comboBox, file) -> {
                String selectedAction = (String) comboBox.getSelectedItem();
                switch (selectedAction) {
                    case SUMMARIZE:
                        filesToSummarize.add(file);
                        break;
                    case EDIT:
                        if (hasGit) filesToEdit.add(file);
                        // else: silently ignore if Git not present (button should ideally be disabled)
                        break;
                    case READ_ONLY:
                        filesToReadOnly.add(file);
                        break;
                    case OMIT: // Do nothing
                    default:
                        break;
                }
            });

            // Process test code selections
            testCodeComboboxMap.forEach((comboBox, file) -> {
                String selectedAction = (String) comboBox.getSelectedItem();
                switch (selectedAction) {
                    // SUMMARIZE is not an option for tests
                    case EDIT:
                        if (hasGit) filesToEdit.add(file);
                        break;
                    case READ_ONLY:
                        filesToReadOnly.add(file);
                        break;
                    case OMIT: // Do nothing
                    default:
                        break;
                }
            });

            int count = 0;
            // Suppress suggestions triggered by context changes below is handled implicitly
            // by the fact that these actions are performed within the context of the user task.
            // No explicit flag needed here.

            if (!filesToSummarize.isEmpty()) {
                contextManager.submitContextTask("Summarize Files", () -> {
                    boolean success = contextManager.addSummaries(filesToSummarize, Set.of());
                    if (!success) {
                        chrome.toolErrorRaw("No summarizable code found in selected files");
                    }
                });
                chrome.systemOutput("Requested summarization for " + filesToSummarize.size() + " file(s) from Deep Scan.");
                count += filesToSummarize.size();
            }
            if (!filesToEdit.isEmpty()) {
                contextManager.editFiles(filesToEdit);
                chrome.systemOutput("Added " + filesToEdit.size() + " file(s) as editable from Deep Scan.");
                count += filesToEdit.size();
            }
            if (!filesToReadOnly.isEmpty()) {
                contextManager.addReadOnlyFiles(filesToReadOnly);
                chrome.systemOutput("Added " + filesToReadOnly.size() + " file(s) as read-only from Deep Scan.");
                count += filesToReadOnly.size();
            }

            if (count == 0) {
                chrome.systemOutput("No files selected for action from Deep Scan.");
            }

            dialog.dispose();
            chrome.getInstructionsPanel().enableButtons(); // Re-enable buttons after dialog closes
        });


        cancelButton.addActionListener(e -> {
            dialog.dispose();
            chrome.getInstructionsPanel().enableButtons(); // Re-enable buttons after dialog closes
        });

        dialog.pack();
        dialog.setLocationRelativeTo(chrome.getFrame());
        chrome.getInstructionsPanel().disableButtons(); // Disable main buttons while dialog is showing
        dialog.setVisible(true); // Blocks until closed (on EDT)
        // Buttons are re-enabled by the apply/cancel listeners via chrome.getInstructionsPanel().enableButtons()
    }
}
