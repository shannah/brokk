package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.RepoFile;
import io.github.jbellis.brokk.GitRepo;
import org.fife.ui.autocomplete.*;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * A file selection dialog that presents a tree view and a text input with autocomplete.
 */
public class FileSelectionDialog extends JDialog {

    private final Path rootPath;
    private final JTree fileTree;
    private final JTextField fileInput;
    private final AutoCompletion autoCompletion;
    private final JButton okButton;
    private final JButton cancelButton;

    // The set of files selected from the tree or typed in
    private final Set<RepoFile> selectedFiles = new HashSet<>();

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public FileSelectionDialog(Frame parent, Path root, String title) {
        super(parent, title, true); // modal dialog
        this.rootPath = root;

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build the file tree on the left
        fileTree = buildFileTree();
        JScrollPane treeScrollPane = new JScrollPane(fileTree);
        treeScrollPane.setPreferredSize(new Dimension(250, 400));
        mainPanel.add(treeScrollPane, BorderLayout.WEST);

        // Build text input with autocomplete on the right
        fileInput = new JTextField(30);
        var provider = createFileCompletionProvider();
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,
                                                            InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(fileInput);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createTitledBorder("Type filename (press Ctrl+Space)"));
        inputPanel.add(fileInput, BorderLayout.CENTER);
        mainPanel.add(inputPanel, BorderLayout.CENTER);

        // Buttons at the bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * Build a JTree showing the repository's file hierarchy.
     */
    private JTree buildFileTree() {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(rootPath.getFileName().toString());
        List<RepoFile> tracked = new ArrayList<>(GitRepo.instance.getTrackedFiles());

        Map<Path, DefaultMutableTreeNode> folderNodes = new HashMap<>();
        folderNodes.put(rootPath, rootNode);

        for (RepoFile rf : tracked) {
            Path rel = rootPath.relativize(rf.absPath());
            Path parentFolder = rootPath;
            DefaultMutableTreeNode parentNode = rootNode;
            for (int i = 0; i < rel.getNameCount() - 1; i++) {
                parentFolder = parentFolder.resolve(rel.getName(i));
                var existing = folderNodes.get(parentFolder);
                if (existing == null) {
                    existing = new DefaultMutableTreeNode(parentFolder.getFileName().toString());
                    parentNode.add(existing);
                    folderNodes.put(parentFolder, existing);
                }
                parentNode = existing;
            }
            String filename = rel.getFileName().toString();
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(filename);
            parentNode.add(fileNode);
        }

        JTree tree = new JTree(rootNode);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        return tree;
    }

    /**
     * Create the file completion provider that uses the old logic.
     */
    private CompletionProvider createFileCompletionProvider() {
        Collection<RepoFile> tracked = GitRepo.instance.getTrackedFiles();
        return new FileCompletionProvider(tracked);
    }

    /**
     * When OK is pressed, collect selections from the tree and text input.
     */
    private void doOk() {
        confirmed = true;
        selectedFiles.clear();

        // Gather selections from the tree
        TreePath[] selectionPaths = fileTree.getSelectionPaths();
        if (selectionPaths != null) {
            for (TreePath path : selectionPaths) {
                Object lastComp = path.getLastPathComponent();
                if (lastComp instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                    StringBuilder rel = new StringBuilder();
                    for (int i = 1; i < path.getPathCount(); i++) {
                        String seg = path.getPathComponent(i).toString();
                        if (i > 1) rel.append("/");
                        rel.append(seg);
                    }
                    RepoFile r = new RepoFile(rootPath, rel.toString());
                    selectedFiles.add(r);
                }
            }
        }

        // Also add any file from the text input
        String typed = fileInput.getText().trim();
        if (!typed.isEmpty()) {
            var expanded = Completions.expandPath(rootPath, typed);
            for (var bf : expanded) {
                if (bf instanceof RepoFile rf) {
                    selectedFiles.add(rf);
                }
            }
        }
        dispose();
    }

    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Return the list of selected RepoFiles.
     */
    public List<RepoFile> getSelectedFiles() {
        return new ArrayList<>(selectedFiles);
    }
}
