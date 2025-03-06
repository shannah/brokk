package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.RepoFile;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.CompletionProvider;
import org.fife.ui.autocomplete.DefaultCompletionProvider;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
    private final JTextArea fileInput;
    private final AutoCompletion autoCompletion;
    private final JButton okButton;
    private final JButton cancelButton;

    // The selected files
    private List<RepoFile> selectedFiles = new ArrayList<>();

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public FileSelectionDialog(Frame parent, Path root, String title) {
        super(parent, title, true); // modal dialog
        this.rootPath = root;

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build text input with autocomplete at the top
        fileInput = new JTextArea(3, 30);
        fileInput.setLineWrap(true);
        fileInput.setWrapStyleWord(true);
        var provider = createFileCompletionProvider();
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,
                                                            InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(fileInput);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(new JScrollPane(fileInput), BorderLayout.CENTER);
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
                // Get current text and add the new filename with a space separator
                appendFilenameToInput(rel.toString());
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
            selectedFiles.clear();
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as the default button (responds to Enter key)
        getRootPane().setDefaultButton(okButton);

        // Add a tooltip to indicate Enter key functionality
        fileInput.setToolTipText("Enter a filename and press Enter to confirm");

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(parent);
        // Make the autocomplete popup match the width of the dialog
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                autoCompletion.setChoicesWindowSize((int) (mainPanel.getWidth() * 0.9), 300);
            }
        });
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
                        // Build the relative path string
                        StringBuilder rel = new StringBuilder();
                        for (int i = 1; i < path.getPathCount(); i++) {
                            String seg = path.getPathComponent(i).toString();
                            if (i > 1) rel.append("/");
                            rel.append(seg);
                        }
                        // Append to the input area instead of closing dialog
                        appendFilenameToInput(rel.toString());
                    }
                }
            }
        });

        return tree;
    }

    /**
     * Appends a filename to the input text area with space separator
     */
    private void appendFilenameToInput(String filename) {
        String currentText = fileInput.getText();
        if (currentText.isEmpty()) {
            fileInput.setText(filename + " ");
        } else if (currentText.endsWith(" ")) {
            fileInput.setText(currentText + filename + " ");
        } else {
            fileInput.setText(currentText + " " + filename + " ");
        }
        fileInput.requestFocusInWindow();
    }

    /**
     * Create the file completion provider that uses the old logic.
     */
    private CompletionProvider createFileCompletionProvider()
    {
        Collection<RepoFile> tracked = GitRepo.instance.getTrackedFiles();
        return new FileCompletionProvider(tracked);
    }

    /**
     * When OK is pressed, get the files from the text input.
     */
    private void doOk() {
        confirmed = true;
        selectedFiles.clear();

        String typed = fileInput.getText().trim();
        if (!typed.isEmpty()) {
            // Split by whitespace to get multiple filenames
            String[] filenames = typed.split("\\s+");
            
            for (String filename : filenames) {
                if (filename.isBlank()) continue;
                
                var expanded = Completions.expandPath(rootPath, filename);
                for (var bf : expanded) {
                    if (bf instanceof RepoFile rf) {
                        selectedFiles.add(rf);
                        break;
                    }
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
     * Return the first selected RepoFile or null if none.
     * This maintains backward compatibility with code that expects a single file.
     */
    public RepoFile getSelectedFile() {
        return selectedFiles.isEmpty() ? null : selectedFiles.get(0);
    }

    /**
     * Return a list of all selected files.
     */
    public List<RepoFile> getSelectedFiles() {
        return new ArrayList<>(selectedFiles);
    }

    /**
     * Custom CompletionProvider for files that replicates the old logic.
     */
    public static class FileCompletionProvider extends DefaultCompletionProvider {

        private final Collection<RepoFile> repoFiles;

        public FileCompletionProvider(Collection<RepoFile> repoFiles) {
            super();
            this.repoFiles = repoFiles;
        }

        @Override
        public String getAlreadyEnteredText(javax.swing.text.JTextComponent comp)
        {
            // Get the word under cursor (which can be blank if on whitespace).
            String text = comp.getText();
            int caretPos = comp.getCaretPosition();

            // Search backward for whitespace
            int start = caretPos;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }

            // Search forward for whitespace
            int end = caretPos;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }

            if (start == end) {
                return "";
            }
            return text.substring(start, end);
        }

        @Override
        public List<Completion> getCompletions(JTextComponent tc) {
            var input = getAlreadyEnteredText(tc);
            String partialLower = input.toLowerCase();
            Map<String, RepoFile> baseToFullPath = new HashMap<>();
            Map<String, Completion> uniqueCompletions = new HashMap<>();

            for (RepoFile p : repoFiles) {
                baseToFullPath.put(p.getFileName(), p);
            }

            // Matching base filenames (priority 1)
            baseToFullPath.forEach((base, path) -> {
                if (base.toLowerCase().startsWith(partialLower)) {
                    uniqueCompletions.put(path.toString(), createCompletion(path));
                }
            });

            // Camel-case completions (priority 2)
            baseToFullPath.forEach((base, path) -> {
                String capitals = Completions.extractCapitals(base);
                if (capitals.toLowerCase().startsWith(partialLower)) {
                    uniqueCompletions.putIfAbsent(path.toString(), createCompletion(path));
                }
            });

            // Matching full paths (priority 3)
            for (RepoFile p : repoFiles) {
                if (p.toString().toLowerCase().startsWith(partialLower)) {
                    uniqueCompletions.putIfAbsent(p.toString(), createCompletion(p));
                }
            }

            // Sort completions by filename, then by full path
            return uniqueCompletions.values().stream()
                .sorted((c1, c2) -> {
                    // Compare filenames first
                    int result = c1.getSummary().compareTo(c2.getSummary());
                    if (result == 0) {
                        // If filenames match, compare by full path
                        return c1.getReplacementText().compareTo(c2.getReplacementText());
                    }
                    return result;
                })
                .toList();
        }

        private Completion createCompletion(RepoFile file) {
            String replacement = file.toString() + " ";
            return new BasicCompletion(this, replacement, file.getFileName(), file.toString());
        }
    }
}
