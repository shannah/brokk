package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.RepoFile;
import io.github.jbellis.brokk.GitRepo;
import org.fife.ui.autocomplete.*;
import org.fife.ui.autocomplete.AutoCompletion;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class FileSelectionDialog extends JDialog {

    private final Path rootPath;
    private final JTree fileTree;
    private final JTextField fileInput;
    private final AutoCompletion autoCompletion;

    // The set of files selected in the tree, or typed in the box
    private final Set<RepoFile> selectedFiles = new HashSet<>();

    // Indicate whether user clicked OK
    private boolean confirmed = false;

    public FileSelectionDialog(Frame parent, Path root, String title) {
        super(parent, title, true); // modal dialog
        this.rootPath = root;

        // Layout: split pane or side-by-side
        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build the file tree on the left
        fileTree = buildFileTree();
        JScrollPane treeScrollPane = new JScrollPane(fileTree);
        treeScrollPane.setPreferredSize(new Dimension(250, 400));
        mainPanel.add(treeScrollPane, BorderLayout.WEST);

        // Build text input with autocomplete on the right
        fileInput = new JTextField(30);
        var completionProvider = createFileCompletionProvider();
        autoCompletion = new AutoCompletion(completionProvider);
        autoCompletion.setAutoActivationEnabled(false); // user triggers with ctrl-space
        // First, make sure autoCompletion knows what key should trigger it
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,
                                                            InputEvent.CTRL_DOWN_MASK));
        // Then install it on the text field
        autoCompletion.install(fileInput);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createTitledBorder("Type filename (press Ctrl+Space)"));
        inputPanel.add(fileInput, BorderLayout.CENTER);
        mainPanel.add(inputPanel, BorderLayout.CENTER);

        // Buttons at bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        JButton cancelButton = new JButton("Cancel");
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
     * Build a JTree that shows the contents of the Git-tracked files under root.
     * We group them in a directory hierarchy.
     * Multi-selection is enabled so the user can select multiple files.
     */
    private JTree buildFileTree() {
        // Root node for the tree
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(rootPath.getFileName().toString());
        // Build child nodes from GitRepo’s tracked files
        List<RepoFile> tracked = GitRepo.instance.getTrackedFiles().stream().toList();

        // Build a map from folder -> node
        Map<Path, DefaultMutableTreeNode> folderNodes = new HashMap<>();
        folderNodes.put(rootPath, rootNode);

        for (RepoFile rf : tracked) {
            Path rel = rootPath.relativize(rf.absPath());
            // accumulate subdirectories
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
            // leaf node for the file
            String filename = rel.getFileName().toString();
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(filename);
            parentNode.add(fileNode);
        }

        // build the JTree from this root
        JTree tree = new JTree(rootNode);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(
                TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        );
        return tree;
    }

    /**
     * Create the BobbyLight CompletionProvider for files. We can reuse the
     * logic from Completions for partial expansions.
     */
    private CompletionProvider createFileCompletionProvider() {
        DefaultCompletionProvider provider = new DefaultCompletionProvider() {
            @Override
            protected boolean isValidChar(char ch) {
                // allow typical filename chars plus punctuation
                return super.isValidChar(ch) || ch == '.' || ch == '/' || ch == '\\';
            }
        };

        // Precompute all known filenames for autoComplete
        // E.g. the entire list of tracked files as strings:
        List<RepoFile> tracked = GitRepo.instance.getTrackedFiles().stream().toList();
        for (RepoFile rf : tracked) {
            String relPath = rootPath.relativize(rf.absPath()).toString().replace("\\", "/");
            provider.addCompletion(new BasicCompletion(provider, relPath));
        }

        return provider;
    }

    /**
     * Called when user presses OK. Gather the selected tree nodes plus text field content.
     */
    private void doOk() {
        confirmed = true;
        selectedFiles.clear();

        // 1) Gather selected tree files
        TreePath[] selectionPaths = fileTree.getSelectionPaths();
        if (selectionPaths != null) {
            for (TreePath path : selectionPaths) {
                Object lastComp = path.getLastPathComponent();
                if (lastComp instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                    // reconstruct the path from root
                    StringBuilder rel = new StringBuilder();
                    for (int i = 1; i < path.getPathCount(); i++) {
                        String seg = path.getPathComponent(i).toString();
                        if (i > 1) rel.append("/");
                        rel.append(seg);
                    }
                    // This might be folder or file, but we said isLeaf => file
                    // so create the RepoFile
                    RepoFile r = new RepoFile(rootPath, rel.toString());
                    selectedFiles.add(r);
                }
            }
        }

        // 2) If user typed something in fileInput, expand it
        String typed = fileInput.getText().trim();
        if (!typed.isEmpty()) {
            // The old code used Completions.expandPath(...)
            // so let's do that here:
            var expanded = Completions.expandPath(rootPath, typed);
            for (var bf : expanded) {
                if (bf instanceof RepoFile rf) {
                    selectedFiles.add(rf);
                }
                // if ExternalFile or other types, up to you if you want to add them
            }
        }

        dispose();
    }

    /**
     * Return true if user clicked OK
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Return all selected files from this dialog
     */
    public List<RepoFile> getSelectedFiles() {
        return new ArrayList<>(selectedFiles);
    }
}
