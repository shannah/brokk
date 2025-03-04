package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.RepoFile;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.CompletionProvider;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // The selected file
    private RepoFile selectedFile = null;

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public FileSelectionDialog(Frame parent, Path root, String title) {
        super(parent, title, true); // modal dialog
        this.rootPath = root;

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build text input with autocomplete at the top
        fileInput = new JTextField(30);
        var provider = createFileCompletionProvider();
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,
                                                            InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(fileInput);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(fileInput, BorderLayout.CENTER);
        inputPanel.add(new JLabel("Ctrl-space to autocomplete filenames"), BorderLayout.SOUTH);
        mainPanel.add(inputPanel, BorderLayout.NORTH);

        // Build the file tree in the center
        fileTree = buildFileTree();
        JScrollPane treeScrollPane = new JScrollPane(fileTree);
        treeScrollPane.setPreferredSize(new Dimension(400, 400));
        mainPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Make tree selection update the text field
        fileTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                StringBuilder rel = new StringBuilder();
                for (int i = 1; i < path.getPathCount(); i++) {
                    String seg = path.getPathComponent(i).toString();
                    if (i > 1) rel.append("/");
                    rel.append(seg);
                }
                fileInput.setText(rel.toString());
            }
        });

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

        // Handle escape key to close dialog
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().registerKeyboardAction(e -> {
            confirmed = false;
            selectedFile = null;
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as the default button (responds to Enter key)
        getRootPane().setDefaultButton(okButton);

        // Add a tooltip to indicate Enter key functionality
        fileInput.setToolTipText("Enter a filename and press Enter to confirm");

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
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        // Add double-click handler to select and confirm
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                        tree.setSelectionPath(path);
                        doOk();
                    }
                }
            }
        });

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
     * When OK is pressed, get the file from the text input.
     */
    private void doOk() {
        confirmed = true;
        selectedFile = null;

        String typed = fileInput.getText().trim();
        if (!typed.isEmpty()) {
            var expanded = Completions.expandPath(rootPath, typed);
            for (var bf : expanded) {
                if (bf instanceof RepoFile rf) {
                    selectedFile = rf;
                    break;
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
     * Return the selected RepoFile or null if none.
     */
    public RepoFile getSelectedFile() {
        return selectedFile;
    }

    /**
     * Return a list containing the selected file, or empty list if none.
     * This maintains backward compatibility.
     */
    public List<RepoFile> getSelectedFiles() {
        return selectedFile != null ? List.of(selectedFile) : List.of();
    }
}