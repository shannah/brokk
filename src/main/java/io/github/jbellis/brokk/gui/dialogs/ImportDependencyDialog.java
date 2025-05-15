package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.util.Decompiler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class ImportDependencyDialog {

    /**
     * Shows the dialog to import a dependency, offering options to decompile a JAR
     * or copy a directory.
     *
     * @param chrome The main application window and context.
     */
    public static void show(Chrome chrome) {
        assert SwingUtilities.isEventDispatchThread() : "Dialogs should be created on the EDT";
        assert chrome.getContextManager() != null : "ContextManager must not be null to import a dependency";
        assert chrome.getProject() != null : "Project must not be null to import a dependency";

        JDialog dialog = new JDialog(chrome.getFrame(), "Import Dependencies", true); // modal
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTabbedPane tabbedPane = new JTabbedPane();

        JPanel decompileJarPanel = createDecompileJarPanel(chrome);
        tabbedPane.addTab("Decompile JAR", decompileJarPanel);

        JPanel copyDirectoryPanel = createCopyDirectoryPanel(chrome, dialog);
        tabbedPane.addTab("Copy from Directory", copyDirectoryPanel);

        dialog.add(tabbedPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setMinimumSize(new Dimension(550, 350));
        dialog.pack(); // pack after setting minimum size and adding components
        dialog.setLocationRelativeTo(chrome.getFrame());
        dialog.setVisible(true);
    }

    private static JPanel createDecompileJarPanel(Chrome chrome) {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel description = new JLabel("<html>Select a JAR file to decompile its contents.<br>" +
                                        "The decompiled sources will be added as a dependency.</html>");
        panel.add(description, BorderLayout.NORTH);

        JButton selectAndDecompileButton = new JButton("Select JAR and Decompile...");
        selectAndDecompileButton.addActionListener(e -> {
            var cm = chrome.getContextManager();
            var jarCandidates = cm.submitBackgroundTask("Scanning for JAR files", Decompiler::findCommonDependencyJars);

            SwingUtilities.invokeLater(() -> {
                Predicate<File> jarFilter = file -> file.isDirectory() || file.getName().toLowerCase().endsWith(".jar");
                FileSelectionDialog fileDialog = new FileSelectionDialog(
                        chrome.getFrame(),
                        cm.getProject(),
                        "Select JAR Dependency to Decompile",
                        true,
                        jarFilter,
                        jarCandidates
                );
                fileDialog.setVisible(true);

                if (fileDialog.isConfirmed() && fileDialog.getSelectedFile() != null) {
                    var selectedFile = fileDialog.getSelectedFile();
                    Path jarPath = selectedFile.absPath();
                    assert Files.isRegularFile(jarPath) && jarPath.toString().toLowerCase().endsWith(".jar");
                    Decompiler.decompileJar(chrome, jarPath, cm::submitBackgroundTask);
                    // Optionally, inform user or close parentDialog. For now, stays open.
                }
            });
        });

        JPanel buttonContainer = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonContainer.add(selectAndDecompileButton);
        panel.add(buttonContainer, BorderLayout.CENTER);

        return panel;
    }

    private static JPanel createCopyDirectoryPanel(Chrome chrome, JDialog parentDialog) {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel description = new JLabel("<html>Select a directory containing source files or compiled classes.<br>" +
                                        "The directory will be copied into your project's dependency area.</html>");
        panel.add(description, BorderLayout.NORTH);

        JPanel selectionPanel = new JPanel(new BorderLayout(5, 0));
        JTextField selectedDirectoryField = new JTextField(35);
        selectedDirectoryField.setEditable(false);
        JButton selectDirButton = new JButton("Browse...");

        selectionPanel.add(new JLabel("Selected Directory:"), BorderLayout.WEST);
        selectionPanel.add(selectedDirectoryField, BorderLayout.CENTER);
        selectionPanel.add(selectDirButton, BorderLayout.EAST);

        JButton copyButton = new JButton("Copy Directory to Project");
        copyButton.setEnabled(false);

        selectDirButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Directory to Copy");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);

            if (chooser.showOpenDialog(parentDialog) == JFileChooser.APPROVE_OPTION) {
                File selectedDir = chooser.getSelectedFile();
                selectedDirectoryField.setText(selectedDir.getAbsolutePath());
                copyButton.setEnabled(true);
            }
        });

        copyButton.addActionListener(e -> {
            Path sourcePath = Paths.get(selectedDirectoryField.getText());
            if (!Files.isDirectory(sourcePath)) {
                JOptionPane.showMessageDialog(parentDialog, "The selected path is not a valid directory.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            var project = chrome.getProject();
            Path dependenciesRoot = project.getRoot().resolve(".brokk").resolve("dependencies");
            Path targetPath = dependenciesRoot.resolve(sourcePath.getFileName());

            if (Files.exists(targetPath)) {
                int response = JOptionPane.showConfirmDialog(parentDialog,
                        "The destination directory '" + targetPath.getFileName() + "' already exists. Overwrite?",
                        "Confirm Overwrite",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (response == JOptionPane.NO_OPTION) {
                    return;
                }
            }

            copyButton.setEnabled(false); // Disable while copying

            chrome.getContextManager().submitBackgroundTask(
                "Copying directory: " + sourcePath.getFileName(),
                () -> {
                    try {
                        Files.createDirectories(dependenciesRoot);
                        if (Files.exists(targetPath)) {
                            deleteRecursively(targetPath);
                        }

                        var projectLanguage = chrome.getProject().getAnalyzerLanguage();
                        copyDirectoryRecursively(sourcePath, targetPath, projectLanguage.getExtensions());
                        SwingUtilities.invokeLater(() -> {
                            chrome.systemOutput("Directory copied successfully to " + targetPath + " (filtered by project language: " + projectLanguage.name() + "). Reopen project to incorporate the new files.");
                            selectedDirectoryField.setText("");
                            copyButton.setEnabled(false); // Keep disabled until new selection
                        });
                    } catch (IOException ex) {
                        SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(parentDialog, "Error copying directory: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                        );
                    } finally {
                        // Re-enable copy button only if a valid path is still in the field
                        SwingUtilities.invokeLater(() -> {
                            if (!selectedDirectoryField.getText().isEmpty() && Files.isDirectory(Paths.get(selectedDirectoryField.getText()))) {
                                copyButton.setEnabled(true);
                            }
                        });
                    }
                    return null;
                }
            );
        });

        JPanel controlsOuterPanel = new JPanel(new GridBagLayout()); // To center the inner panel
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.anchor = GridBagConstraints.NORTH; // Anchor to the top

        JPanel controlsInnerPanel = new JPanel();
        controlsInnerPanel.setLayout(new BoxLayout(controlsInnerPanel, BoxLayout.Y_AXIS));
        controlsInnerPanel.add(selectionPanel);
        controlsInnerPanel.add(Box.createRigidArea(new Dimension(0, 10))); // Spacer
        
        JPanel copyButtonFlowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER)); // To center the button
        copyButtonFlowPanel.add(copyButton);
        controlsInnerPanel.add(copyButtonFlowPanel);

        controlsOuterPanel.add(controlsInnerPanel, gbc);
        panel.add(controlsOuterPanel, BorderLayout.CENTER);
        return panel;
    }

    private static void copyDirectoryRecursively(Path source, Path destination, List<String> allowedExtensions) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = destination.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot > 0 && lastDot < fileName.length() - 1) { // Ensure dot is not the last character
                    String extension = fileName.substring(lastDot + 1).toLowerCase(); // Get extension without the dot
                    if (allowedExtensions.contains(extension)) {
                        Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                // If no extension or extension not in list, file is skipped. Directories are handled by preVisitDirectory.
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> pathsToDelete = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : pathsToDelete) {
                Files.delete(p);
            }
        }
    }
}
