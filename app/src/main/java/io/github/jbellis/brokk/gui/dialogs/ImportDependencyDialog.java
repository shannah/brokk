package io.github.jbellis.brokk.gui.dialogs;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionPanel;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import io.github.jbellis.brokk.util.CloneOperationTracker;
import io.github.jbellis.brokk.util.Decompiler;
import io.github.jbellis.brokk.util.FileUtil;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

public class ImportDependencyDialog {
    private static final Logger logger = LogManager.getLogger(ImportDependencyDialog.class);

    private enum SourceType {
        JAR,
        DIRECTORY,
        GIT
    }

    public static void show(Chrome chrome, @Nullable DependenciesPanel.DependencyLifecycleListener listener) {
        assert SwingUtilities.isEventDispatchThread() : "Dialogs should be created on the EDT";
        new DialogHelper(chrome, listener).buildAndShow();
    }

    public static void show(Chrome chrome) {
        show(chrome, null);
    }

    private static class DialogHelper {
        private final Chrome chrome;
        private JDialog dialog = new JDialog();

        @Nullable
        private JRadioButton jarRadioButton;

        @Nullable
        private JRadioButton dirRadioButton;

        @Nullable
        private JRadioButton gitRadioButton;

        @Nullable
        private final DependenciesPanel.DependencyLifecycleListener listener;

        private JPanel contentPanel = new JPanel(new BorderLayout());
        private JButton importButton = new JButton("Import");

        private SourceType currentSourceType = SourceType.JAR;
        private final Path dependenciesRoot;

        // --- File/Dir specific fields
        @Nullable
        private FileSelectionPanel currentFileSelectionPanel;

        @Nullable
        private BrokkFile selectedBrokkFileForImport;

        // --- Git specific fields
        @Nullable
        private JPanel gitPanel;

        @Nullable
        private JTextField gitUrlField;

        @Nullable
        private JButton validateGitRepoButton;

        @Nullable
        private JComboBox<String> gitRefComboBox;

        @Nullable
        private GitRepo.RemoteInfo remoteInfo;

        // --- JAR-specific fields (search + table of known cached jars)
        @Nullable
        private JTextField jarSearchField;

        @Nullable
        private JTable jarTable;

        @Nullable
        private DefaultTableModel jarTableModel;

        @Nullable
        private TableRowSorter<DefaultTableModel> jarSorter;

        @Nullable
        private Map<String, Path> jarNameToPath;

        DialogHelper(Chrome chrome, @Nullable DependenciesPanel.DependencyLifecycleListener listener) {
            this.chrome = chrome;
            this.dependenciesRoot = chrome.getProject()
                    .getRoot()
                    .resolve(AbstractProject.BROKK_DIR)
                    .resolve(AbstractProject.DEPENDENCIES_DIR);
            this.listener = listener;

            // Clean up any orphaned operations from previous crashes
            CloneOperationTracker.cleanupOrphanedClones(dependenciesRoot);
        }

        void buildAndShow() {
            dialog = new JDialog(chrome.getFrame(), "Import Dependency", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setLayout(new BorderLayout(10, 10));

            JPanel mainPanel = new JPanel(new GridBagLayout());
            mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            boolean allowJarImport = chrome.getProject().getAnalyzerLanguages().contains(Language.JAVA);
            currentSourceType = allowJarImport ? SourceType.JAR : SourceType.DIRECTORY;

            // --- Source Type Radio Buttons ---
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 1;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            mainPanel.add(new JLabel("Source Type:"), gbc);

            ButtonGroup sourceTypeGroup = new ButtonGroup();
            JPanel radioPanel = new JPanel();
            radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.PAGE_AXIS));

            if (allowJarImport) {
                jarRadioButton = new JRadioButton("JAR (decompile & add sources)");
                jarRadioButton.setSelected(true);
                jarRadioButton.addActionListener(e -> updateSourceType(SourceType.JAR));
                sourceTypeGroup.add(jarRadioButton);
                radioPanel.add(jarRadioButton);
            }

            dirRadioButton = new JRadioButton("Directory");
            dirRadioButton.setSelected(!allowJarImport);
            dirRadioButton.addActionListener(e -> updateSourceType(SourceType.DIRECTORY));
            sourceTypeGroup.add(dirRadioButton);
            radioPanel.add(dirRadioButton);

            gitRadioButton = new JRadioButton("Git Repository");
            gitRadioButton.addActionListener(e -> updateSourceType(SourceType.GIT));
            sourceTypeGroup.add(gitRadioButton);
            radioPanel.add(gitRadioButton);

            gbc.gridx = 1;
            mainPanel.add(radioPanel, gbc);

            // --- Content Panel (for FSP or Git panel) ---
            gbc.gridy = 1;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTH;
            gbc.weightx = 1.0;
            gbc.weighty = 0;
            mainPanel.add(contentPanel, gbc);

            dialog.add(mainPanel, BorderLayout.CENTER);

            // --- Buttons ---
            importButton.setEnabled(false);
            importButton.addActionListener(e -> performImport());
            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> dialog.dispose());

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(importButton);
            buttonPanel.add(cancelButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);

            updateContentPanel();

            dialog.pack();
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
        }

        private void updateSourceType(SourceType newType) {
            if (currentSourceType == newType) return;
            currentSourceType = newType;
            selectedBrokkFileForImport = null;
            remoteInfo = null;
            importButton.setEnabled(false);
            updateContentPanel();
            dialog.pack();
        }

        private void updateContentPanel() {
            contentPanel.removeAll();
            if (currentSourceType == SourceType.GIT) {
                contentPanel.add(createGitPanel(), BorderLayout.CENTER);
            } else if (currentSourceType == SourceType.JAR) {
                contentPanel.add(createJarSelectionPanel(), BorderLayout.CENTER);
            } else {
                contentPanel.add(createFileSelectionPanel(), BorderLayout.CENTER);
            }
            contentPanel.revalidate();
            contentPanel.repaint();
        }

        private JPanel createGitPanel() {
            gitPanel = new JPanel(new GridBagLayout());
            gitUrlField = new JTextField();
            gitUrlField.setColumns(30);
            validateGitRepoButton = new JButton("Load Tags & Branches");
            gitRefComboBox = new JComboBox<>();
            gitRefComboBox.setEnabled(false);
            gitRefComboBox.addActionListener(e -> updateGitImportButtonState());

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 2, 2, 2);
            gbc.anchor = GridBagConstraints.WEST;

            // Row 0: URL Label
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 0;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.NONE;
            gitPanel.add(new JLabel("Repo URL:"), gbc);

            // Row 0: URL Field
            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.weightx = 1.0;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gitPanel.add(gitUrlField, gbc);

            // Row 1: Button
            gbc.gridx = 1;
            gbc.gridy = 1;
            // gbc constraints from URL Field are reused to make button stretch
            gitPanel.add(validateGitRepoButton, gbc);

            // Row 2: Branch/Tag Label
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.weightx = 0;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.NONE;
            gitPanel.add(new JLabel("Branch/Tag:"), gbc);

            // Row 2: Branch/Tag Combo
            gbc.gridx = 1;
            gbc.gridy = 2;
            gbc.weightx = 1.0;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gitPanel.add(gitRefComboBox, gbc);

            gitPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

            validateGitRepoButton.addActionListener(e -> validateRepository());
            gitUrlField.getDocument().addDocumentListener(new SimpleDocumentListener() {
                @Override
                public void update(DocumentEvent e) {
                    remoteInfo = null;
                    // Clear repository info on URL change
                    requireNonNull(gitRefComboBox).removeAllItems();
                    gitRefComboBox.setEnabled(false);
                    importButton.setEnabled(false);
                }
            });

            return gitPanel;
        }

        private void validateRepository() {
            String url = requireNonNull(gitUrlField).getText().trim();
            if (url.isEmpty()) {
                chrome.toolError("Git repository URL cannot be empty.", "Validation Error");
                return;
            }

            requireNonNull(validateGitRepoButton).setEnabled(false);

            chrome.getContextManager().submitBackgroundTask("Loading branches and tags", () -> {
                try {
                    // Normalize URL for display and use
                    String normalizedUrl = url;
                    if (!normalizedUrl.endsWith(".git")) {
                        // Avoid adding .git to SSH URLs like git@github.com:user/repo
                        if (normalizedUrl.startsWith("http")) {
                            normalizedUrl += ".git";
                        }
                    }
                    final String finalUrl = normalizedUrl;
                    SwingUtilities.invokeLater(() -> requireNonNull(gitUrlField).setText(finalUrl));

                    var info = GitRepo.listRemoteRefs(finalUrl);
                    this.remoteInfo = info;

                    SwingUtilities.invokeLater(() -> {
                        populateGitRefComboBox(info);
                        chrome.systemOutput("Repository validated successfully. Select a branch or tag to import.");
                        updateGitImportButtonState();
                    });
                } catch (Exception ex) {
                    logger.warn("Failed to validate git repo {}", url, ex);
                    this.remoteInfo = null;
                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError(
                                "Failed to access remote repository:\n" + ex.getMessage(), "Validation Failed");
                        importButton.setEnabled(false);
                    });
                } finally {
                    SwingUtilities.invokeLater(
                            () -> requireNonNull(validateGitRepoButton).setEnabled(true));
                }
                return null;
            });
        }

        private void populateGitRefComboBox(GitRepo.RemoteInfo info) {
            var combo = requireNonNull(gitRefComboBox);
            combo.removeAllItems();

            // Add default branch first
            if (info.defaultBranch() != null) {
                combo.addItem(info.defaultBranch() + " (default)");
            }

            // Add other branches
            for (String branch : info.branches()) {
                if (!branch.equals(info.defaultBranch())) {
                    combo.addItem(branch);
                }
            }

            // Add tags
            for (String tag : info.tags()) {
                combo.addItem(tag);
            }

            // Enable combo box and select first item if available
            combo.setEnabled(combo.getItemCount() > 0);
            if (combo.getItemCount() > 0) {
                combo.setSelectedIndex(0);
            }
        }

        private void updateGitImportButtonState() {
            boolean isReady =
                    remoteInfo != null && requireNonNull(gitRefComboBox).getSelectedItem() != null;
            importButton.setEnabled(isReady);
        }

        private FileSelectionPanel createFileSelectionPanel() {
            Predicate<File> filter;
            Future<List<Path>> candidates;
            String helpText;

            if (currentSourceType == SourceType.JAR) {
                assert chrome.getProject().getAnalyzerLanguages().contains(Language.JAVA)
                        : "JAR source type should only be possible for Java projects";
                filter = file -> file.isDirectory()
                        || file.getName().toLowerCase(Locale.ROOT).endsWith(".jar");
                candidates = chrome.getContextManager()
                        .submitBackgroundTask(
                                "Scanning for JAR files",
                                () -> Language.JAVA.getDependencyCandidates(chrome.getProject()));
                helpText =
                        "Ctrl+Space to autocomplete common dependency JARs.\nSelected JAR will be decompiled and its sources added to the project.";
            } else { // DIRECTORY
                filter = File::isDirectory;
                candidates = CompletableFuture.completedFuture(List.of());
                helpText =
                        "Select a directory containing sources.\nSelected directory will be copied into the project.";
            }

            var fspConfig = new FileSelectionPanel.Config(
                    chrome.getProject(),
                    true,
                    filter,
                    candidates,
                    false,
                    this::handleFspSingleFileConfirmed,
                    false,
                    helpText);

            currentFileSelectionPanel = new FileSelectionPanel(fspConfig);

            currentFileSelectionPanel
                    .getFileInputComponent()
                    .getDocument()
                    .addDocumentListener(new SimpleDocumentListener() {
                        @Override
                        public void update(DocumentEvent e) {
                            onFspInputTextChange();
                        }
                    });
            return currentFileSelectionPanel;
        }

        private JPanel createJarSelectionPanel() {
            var panel = new JPanel(new BorderLayout(5, 5));

            // Search bar with placeholder hint
            jarSearchField = new JTextField();
            jarSearchField.setColumns(30);
            jarSearchField.setToolTipText("Type to filter JARs");
            jarSearchField.putClientProperty("JTextField.placeholderText", "Search");
            panel.add(jarSearchField, BorderLayout.NORTH);

            // Table model and table
            jarTableModel = new DefaultTableModel(new Object[] {"Name", "Files"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }

                @Override
                public Class<?> getColumnClass(int columnIndex) {
                    return columnIndex == 1 ? Long.class : String.class;
                }
            };
            jarTable = new JTable(jarTableModel);
            jarTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

            // Sorter + search filter
            jarSorter = new TableRowSorter<>(jarTableModel);
            jarTable.setRowSorter(jarSorter);
            // Default sort by name (column 0) ascending
            jarSorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));

            // Configure "Files" column max width early to avoid layout reflow when focused.
            if (jarTable.getColumnModel().getColumnCount() > 1) {
                TableColumn filesColumn = jarTable.getColumnModel().getColumn(1);
                // Compute a conservative max width based on header preferred width
                int headerWidth = jarTable.getTableHeader()
                        .getDefaultRenderer()
                        .getTableCellRendererComponent(jarTable, filesColumn.getHeaderValue(), false, false, -1, 1)
                        .getPreferredSize().width;
                filesColumn.setMaxWidth(headerWidth + 20);
                // Right-align values initially to reserve space
                var rightAlign = new DefaultTableCellRenderer();
                rightAlign.setHorizontalAlignment(SwingConstants.RIGHT);
                filesColumn.setCellRenderer(rightAlign);
            }

            // Seed with a placeholder row while loading
            requireNonNull(jarTableModel).setRowCount(0);
            requireNonNull(jarTableModel).addRow(new Object[] {"Loading...", null});
            importButton.setEnabled(false);

            requireNonNull(jarSearchField).getDocument().addDocumentListener(new SimpleDocumentListener() {
                @Override
                public void update(DocumentEvent e) {
                    var text = requireNonNull(jarSearchField).getText().trim();
                    if (text.isEmpty()) {
                        requireNonNull(jarSorter).setRowFilter(null);
                    } else {
                        String expr = "(?i)" + Pattern.quote(text);
                        requireNonNull(jarSorter).setRowFilter(RowFilter.regexFilter(expr));
                    }
                }
            });

            // Selection behavior to enable Import and set selected file
            jarTable.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                int viewRow = requireNonNull(jarTable).getSelectedRow();
                if (viewRow >= 0 && jarNameToPath != null) {
                    int modelRow = requireNonNull(jarTable).convertRowIndexToModel(viewRow);
                    String jarName = (String) requireNonNull(jarTableModel).getValueAt(modelRow, 0);
                    Path path = jarNameToPath.get(jarName);
                    if (path != null) {
                        selectedBrokkFileForImport = new io.github.jbellis.brokk.analyzer.ExternalFile(path);
                        importButton.setEnabled(true);
                    } else {
                        selectedBrokkFileForImport = null;
                        importButton.setEnabled(false);
                    }
                } else {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                }
            });

            // Double-click to import
            jarTable.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && importButton.isEnabled()) {
                        performImport();
                    }
                }
            });

            // Put table in scroll pane
            var scroll = new JScrollPane(jarTable);
            panel.add(scroll, BorderLayout.CENTER);

            // Load candidates in background and populate table on EDT
            chrome.getContextManager().submitBackgroundTask("Scanning for JAR files", () -> {
                try {
                    var paths = Language.JAVA.getDependencyCandidates(chrome.getProject());
                    SwingUtilities.invokeLater(() -> populateJarTable(paths));
                } catch (Exception ex) {
                    logger.warn("Error scanning for JAR files", ex);
                    SwingUtilities.invokeLater(() -> chrome.toolError(
                            "Error scanning for JAR files: " + ex.getMessage(), "Scan Error"));
                }
                return null;
            });

            return panel;
        }

        private void populateJarTable(List<Path> paths) {
            // Build and populate in background (dedupe + count), then update EDT
            chrome.getContextManager().submitBackgroundTask("Preparing JAR list", () -> {
                // Deduplicate by filename (keep first occurrence)
                var byFilename = new LinkedHashMap<String, Path>();
                for (var p : paths) {
                    var filename = p.getFileName().toString();
                    byFilename.putIfAbsent(filename, p);
                }

                // Map pretty display name -> path
                var nameToPath = new LinkedHashMap<String, Path>();
                for (var p : byFilename.values()) {
                    nameToPath.put(prettyJarName(p), p);
                }

                // Count files for each JAR
                var counts = new HashMap<String, Long>();
                for (var entry : nameToPath.entrySet()) {
                    counts.put(entry.getKey(), countJarFiles(entry.getValue()));
                }

                SwingUtilities.invokeLater(() -> {
                    jarNameToPath = nameToPath;

                    var model = requireNonNull(jarTableModel);
                    model.setRowCount(0);
                    for (var entry : nameToPath.entrySet()) {
                        String name = entry.getKey();
                        long files = counts.getOrDefault(name, 0L);
                        model.addRow(new Object[] {name, files});
                    }

                    // Configure renderer for the "Files" column so counts show with commas
                    // but sorting remains based on the underlying Long model values.
                    var filesRenderer = new DefaultTableCellRenderer() {
                        private final java.text.NumberFormat fmt = java.text.NumberFormat.getIntegerInstance();
                        @Override
                        protected void setValue(Object value) {
                            if (value instanceof Number number) {
                                setText(fmt.format(number.longValue()));
                            } else {
                                setText("");
                            }
                        }
                    };
                    filesRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

                    if (requireNonNull(jarTable).getColumnModel().getColumnCount() > 1) {
                        TableColumn filesColumn = jarTable.getColumnModel().getColumn(1);
                        filesColumn.setCellRenderer(filesRenderer);
                        // Set max width to the preferred width now that content is known.
                        int pref = filesColumn.getPreferredWidth();
                        filesColumn.setMaxWidth(pref);
                    }

                    // Ensure sorter applies the default sort now that data is available
                    if (jarSorter != null) {
                        jarSorter.sort();
                    }
                    // Select first row if available to pre-enable import
                    if (model.getRowCount() > 0) {
                        requireNonNull(jarTable).setRowSelectionInterval(0, 0);
                    }
                });
                return null;
            });
        }

        private static String prettyJarName(Path jarPath) {
            String fileName = jarPath.getFileName().toString();
            int dot = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
            return (dot > 0) ? fileName.substring(0, dot) : fileName;
        }

        private static long countJarFiles(Path jarPath) {
            try (var zip = new ZipFile(jarPath.toFile())) {
                return zip.stream()
                        .filter(e -> !e.isDirectory())
                        .map(e -> e.getName().toLowerCase(Locale.ROOT))
                        .filter(name -> name.endsWith(".class") || name.endsWith(".java"))
                        .count();
            } catch (IOException e) {
                // On any error reading the jar, count as 0
                return 0L;
            }
        }

        private void onFspInputTextChange() {
            SwingUtilities.invokeLater(() -> {
                if (currentFileSelectionPanel == null) {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                    return;
                }
                String text = currentFileSelectionPanel.getInputText();
                if (text.isEmpty()) {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                    return;
                }

                Path path;
                try {
                    path = Paths.get(text);
                } catch (InvalidPathException e) {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                    return;
                }

                Path resolvedPath =
                        path.isAbsolute() ? path : chrome.getProject().getRoot().resolve(path);
                if (Files.exists(resolvedPath)) {
                    BrokkFile bf = resolvedPath.startsWith(chrome.getProject().getRoot())
                            ? new io.github.jbellis.brokk.analyzer.ProjectFile(
                                    chrome.getProject().getRoot(),
                                    chrome.getProject().getRoot().relativize(resolvedPath))
                            : new io.github.jbellis.brokk.analyzer.ExternalFile(resolvedPath);
                    updatePreviewAndButtonState(bf);
                } else {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                }
            });
        }

        private void handleFspSingleFileConfirmed(BrokkFile file) {
            if (currentFileSelectionPanel != null) {
                currentFileSelectionPanel.setInputText(file.absPath().toString());
            }
            updatePreviewAndButtonState(file);
            if (importButton.isEnabled()) {
                performImport();
            }
        }

        private void updatePreviewAndButtonState(BrokkFile file) {
            selectedBrokkFileForImport = null;
            importButton.setEnabled(false);

            Path path = file.absPath();
            if (!Files.exists(path)) {
                return;
            }

            if (currentSourceType == SourceType.JAR) {
                if (Files.isRegularFile(path)
                        && path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    selectedBrokkFileForImport = file;
                    importButton.setEnabled(true);
                }
            } else { // DIRECTORY
                if (Files.isDirectory(path)) {
                    selectedBrokkFileForImport = file;
                    importButton.setEnabled(true);
                }
            }
        }

        private void performImport() {
            importButton.setEnabled(false);
            if (currentSourceType == SourceType.JAR || currentSourceType == SourceType.DIRECTORY) {
                performFileBasedImport();
            } else if (currentSourceType == SourceType.GIT) {
                performGitImport();
            }
        }

        private void performGitImport() {
            if (remoteInfo == null) {
                JOptionPane.showMessageDialog(
                        dialog, "No valid Git repository selected.", "Import Error", JOptionPane.ERROR_MESSAGE);
                importButton.setEnabled(true);
                return;
            }

            final String repoUrl = remoteInfo.url();

            // Extract selected branch/tag from combo box
            String selectedRef = (String) requireNonNull(gitRefComboBox).getSelectedItem();
            final String branchOrTag = selectedRef != null && selectedRef.endsWith(" (default)")
                    ? selectedRef.replace(" (default)", "")
                    : selectedRef;

            final String repoName =
                    repoUrl.substring(repoUrl.lastIndexOf('/') + 1).replace(".git", "");
            final Path targetPath = dependenciesRoot.resolve(repoName);

            if (Files.exists(targetPath)) {
                int overwriteResponse = JOptionPane.showConfirmDialog(
                        dialog,
                        "The destination '" + targetPath.getFileName() + "' already exists. Overwrite?",
                        "Confirm Overwrite",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (overwriteResponse == JOptionPane.NO_OPTION) {
                    importButton.setEnabled(true);
                    return;
                }
            }

            if (listener != null) {
                SwingUtilities.invokeLater(() -> listener.dependencyImportStarted(repoName));
            }
            dialog.dispose();

            chrome.getContextManager().submitBackgroundTask("Cloning repository: " + repoUrl, () -> {
                try {
                    Files.createDirectories(dependenciesRoot);

                    // Clean up any existing target directory
                    if (Files.exists(targetPath)) {
                        boolean deleted = FileUtil.deleteRecursively(targetPath);
                        if (!deleted && Files.exists(targetPath)) {
                            throw new IOException("Failed to delete existing destination: " + targetPath);
                        }
                    }

                    // Clone directly to target using GitRepo.cloneRepo() with selected branch/tag
                    // Note: targetPath must not exist or be empty for GitRepo.cloneRepo() to work
                    GitRepo.cloneRepo(repoUrl, targetPath, 1, branchOrTag); // depth=1 for shallow clone

                    // Mark clone as in-progress now that directory exists with content
                    CloneOperationTracker.createInProgressMarker(targetPath, repoUrl, branchOrTag);
                    CloneOperationTracker.registerCloneOperation(targetPath);

                    try {
                        // Remove .git directory (dependencies don't need git history)
                        Path gitInternalDir = targetPath.resolve(".git");
                        if (Files.exists(gitInternalDir)) {
                            FileUtil.deleteRecursively(gitInternalDir);
                        }

                        // Mark clone as complete
                        CloneOperationTracker.createCompleteMarker(targetPath, repoUrl, branchOrTag);
                        CloneOperationTracker.unregisterCloneOperation(targetPath);

                        SwingUtilities.invokeLater(() -> {
                            chrome.systemOutput("Repository " + repoName + " imported successfully.");
                            if (listener != null) {
                                listener.dependencyImportFinished(repoName);
                            }
                        });

                    } catch (Exception postCloneException) {
                        CloneOperationTracker.unregisterCloneOperation(targetPath);
                        throw postCloneException;
                    }

                } catch (Exception ex) {
                    logger.error("Error cloning Git repository {}", repoUrl, ex);

                    // Cleanup on any error
                    if (Files.exists(targetPath)) {
                        try {
                            FileUtil.deleteRecursively(targetPath);
                        } catch (Exception cleanupEx) {
                            logger.warn("Failed to cleanup target directory after clone failure", cleanupEx);
                        }
                    }

                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError("Failed to import repository: " + ex.getMessage(), "Import Error");
                        importButton.setEnabled(true);
                    });
                }
                return null;
            });
        }

        private void performFileBasedImport() {
            if (selectedBrokkFileForImport == null) {
                JOptionPane.showMessageDialog(
                        dialog, "No valid source selected.", "Import Error", JOptionPane.ERROR_MESSAGE);
                importButton.setEnabled(true);
                return;
            }

            Path sourcePath = selectedBrokkFileForImport.absPath();
            String depName = sourcePath.getFileName().toString();

            if (listener != null) {
                SwingUtilities.invokeLater(() -> listener.dependencyImportStarted(depName));
            }
            dialog.dispose();

            if (currentSourceType == SourceType.JAR) {
                Decompiler.decompileJar(
                        chrome,
                        sourcePath,
                        chrome.getContextManager()::submitBackgroundTask,
                        () -> SwingUtilities.invokeLater(() -> {
                            if (listener != null) listener.dependencyImportFinished(depName);
                        }));
            } else { // DIRECTORY
                var project = chrome.getProject();
                if (project.getAnalyzerLanguages().stream().anyMatch(lang -> lang.isAnalyzed(project, sourcePath))) {
                    int proceedResponse = JOptionPane.showConfirmDialog(
                            dialog,
                            "The selected directory might already be part of the project's analyzed sources. Proceed?",
                            "Confirm Import",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (proceedResponse == JOptionPane.NO_OPTION) {
                        importButton.setEnabled(true);
                        return;
                    }
                }

                Path targetPath = dependenciesRoot.resolve(sourcePath.getFileName());
                if (Files.exists(targetPath)) {
                    int overwriteResponse = JOptionPane.showConfirmDialog(
                            dialog,
                            "The destination '" + targetPath.getFileName() + "' already exists. Overwrite?",
                            "Confirm Overwrite",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (overwriteResponse == JOptionPane.NO_OPTION) {
                        importButton.setEnabled(true);
                        return;
                    }
                }

                chrome.getContextManager()
                        .submitBackgroundTask("Copying directory: " + sourcePath.getFileName(), () -> {
                            try {
                                Files.createDirectories(dependenciesRoot);
                                if (Files.exists(targetPath)) {
                                    boolean deleted = FileUtil.deleteRecursively(targetPath);
                                    if (!deleted && Files.exists(targetPath)) {
                                        throw new IOException("Failed to delete existing destination: " + targetPath);
                                    }
                                }
                                List<String> allowedExtensions = project.getAnalyzerLanguages().stream()
                                        .flatMap(lang -> lang.getExtensions().stream())
                                        .distinct()
                                        .toList();
                                copyDirectoryRecursively(sourcePath, targetPath, allowedExtensions);
                                SwingUtilities.invokeLater(() -> {
                                    chrome.systemOutput("Directory copied to " + targetPath
                                            + ". Reopen project to incorporate the new files.");
                                    if (listener != null) listener.dependencyImportFinished(depName);
                                });
                            } catch (IOException ex) {
                                logger.error("Error copying directory {} to {}", sourcePath, targetPath, ex);
                                SwingUtilities.invokeLater(() -> {
                                    JOptionPane.showMessageDialog(
                                            dialog,
                                            "Error copying directory: " + ex.getMessage(),
                                            "Error",
                                            JOptionPane.ERROR_MESSAGE);
                                    importButton.setEnabled(true);
                                });
                            }
                            return null;
                        });
            }
        }
    }

    private interface SimpleDocumentListener extends DocumentListener {
        void update(DocumentEvent e);

        @Override
        default void insertUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void removeUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void changedUpdate(DocumentEvent e) {
            update(e);
        }
    }

    private static void copyDirectoryRecursively(Path source, Path destination, List<String> allowedExtensions)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot > 0 && lastDot < fileName.length() - 1) {
                    if (allowedExtensions.contains(
                            fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT))) {
                        Files.copy(
                                file,
                                destination.resolve(source.relativize(file)),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
