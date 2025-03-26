package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.RepoFile;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.CompletionProvider;
import org.fife.ui.autocomplete.DefaultCompletionProvider;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
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
    private final GitRepo repo;
    private final boolean allowExternalFiles;

    // The selected files
    private List<BrokkFile> selectedFiles = new ArrayList<>();

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public FileSelectionDialog(Frame parent, Project project, String title, boolean allowExternalFiles) {
        super(parent, title, true); // modal dialog
        this.rootPath = project.getRoot();
        this.repo = project.getRepo();
        this.allowExternalFiles = allowExternalFiles;

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build text input with autocomplete at the top
        fileInput = new JTextArea(3, 30);
        fileInput.setLineWrap(true);
        fileInput.setWrapStyleWord(true);
        var provider = createFileCompletionProvider();
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space (Always. On Mac cmd-space is Spotlight)
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,
                                                            InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(fileInput);
        
        // Add expand node behavior when selecting node in the tree
        fileTree = buildFileTree();
        
        // Add a tree will expand listener to load children on demand
        if (allowExternalFiles) {
            fileTree.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
                @Override
                public void treeWillExpand(javax.swing.event.TreeExpansionEvent event) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                    ((LazyLoadingTreeModel) fileTree.getModel()).loadChildren(node);
                }
                
                @Override
                public void treeWillCollapse(javax.swing.event.TreeExpansionEvent event) {
                    // Do nothing
                }
            });
        }

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(new JScrollPane(fileInput), BorderLayout.CENTER);
        
        JPanel labelsPanel = new JPanel(new GridLayout(2, 1));
        String hintText = allowExternalFiles ?
            "Ctrl-space to autocomplete project filenames. External files may be selected from the tree" :
            "Ctrl-space to autocomplete project filenames";
        labelsPanel.add(new JLabel(hintText));
        labelsPanel.add(new JLabel("*/? to glob; ** to glob recursively"));
        inputPanel.add(labelsPanel, BorderLayout.SOUTH);
        mainPanel.add(inputPanel, BorderLayout.NORTH);

        // File tree already built in the text input section
        JScrollPane treeScrollPane = new JScrollPane(fileTree);
        treeScrollPane.setPreferredSize(new Dimension(400, 400));
        mainPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Make tree selection update the text field
        fileTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                if (allowExternalFiles && node.getUserObject() instanceof FileTreeNode fileNode) {
                    // For external files, use absolute path
                    appendFilenameToInput(fileNode.getFile().getAbsolutePath());
                } else {
                    // For repo files, build relative path
                    StringBuilder rel = new StringBuilder();
                    for (int i = 1; i < path.getPathCount(); i++) {
                        String seg = path.getPathComponent(i).toString();
                        if (i > 1) rel.append("/");
                        rel.append(seg);
                    }
                    // Get current text and add the new filename with a space separator
                    appendFilenameToInput(rel.toString());
                }
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
    }

    /**
     * Build a JTree showing the repository's file hierarchy.
     * If allowExternalFiles is true, shows a full file system view.
     */
    private JTree buildFileTree() {
        DefaultMutableTreeNode rootNode;

        if (allowExternalFiles) {
            // For external files, show file system roots
            rootNode = new DefaultMutableTreeNode("File System");
            for (File root : File.listRoots()) {
                DefaultMutableTreeNode driveNode = new DefaultMutableTreeNode(new FileTreeNode(root));
                rootNode.add(driveNode);
            }
        } else {
            // For repo files only, show repo hierarchy
            rootNode = new DefaultMutableTreeNode(rootPath.getFileName().toString());
            List<RepoFile> tracked = new ArrayList<>(repo.getTrackedFiles());

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
        }

        JTree tree = new JTree(rootNode);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        if (allowExternalFiles) {
            tree.setCellRenderer(new FileTreeCellRenderer());
            tree.setModel(new LazyLoadingTreeModel(rootNode));

            // When allowing external files, expand the tree to show the project root
            tree.expandRow(0);
            if (allowExternalFiles) {
                expandTreeToProjectRoot(tree);
            }
        }

        // Add double-click handler to select and confirm
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                        tree.setSelectionPath(path);

                        if (allowExternalFiles && node.getUserObject() instanceof FileTreeNode fileNode) {
                            // For external files, use absolute path
                            appendFilenameToInput(fileNode.getFile().getAbsolutePath());
                        } else {
                            // For repo files, build the relative path string
                            StringBuilder rel = new StringBuilder();
                            for (int i = 1; i < path.getPathCount(); i++) {
                                String seg = path.getPathComponent(i).toString();
                                if (i > 1) rel.append("/");
                                rel.append(seg);
                            }
                            // Append to the input area
                            appendFilenameToInput(rel.toString());
                        }
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
        Collection<RepoFile> tracked = repo.getTrackedFiles();
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

            // Use a map to deduplicate files by their absolute path
            Map<Path, BrokkFile> uniqueFiles = new HashMap<>();
            
            for (String filename : filenames) {
                if (filename.isBlank()) continue;
                for (BrokkFile file : Completions.expandPath(repo, filename)) {
                    // Use the absolute path as the key to deduplicate
                    uniqueFiles.put(file.absPath(), file);
                }
            }
            
            // Add all unique files to the selected files list
            selectedFiles.addAll(uniqueFiles.values());
        }
        dispose();
    }

    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    public List<BrokkFile> getSelectedFiles() {
        return selectedFiles;
    }

    /**
     * Custom CompletionProvider for files that replicates the old logic.
     */
    public class FileCompletionProvider extends DefaultCompletionProvider {

        private final Collection<RepoFile> repoFiles;

        public FileCompletionProvider(Collection<RepoFile> repoFiles) {
            super();
            this.repoFiles = repoFiles;
        }

        // only complete the current filename, which allows others in the input area to be left alone
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
            var L = Completions.getFileCompletions(input, repoFiles).stream()
                    .map(this::createCompletion)
                    .toList();

            if (L.isEmpty()) {
                autoCompletion.setShowDescWindow(false);
                return L;
            }

            // Dynamically size the description window based on the longest filename
            var tooltipFont = UIManager.getFont("ToolTip.font");
            var fontMetrics = fileInput.getFontMetrics(tooltipFont);
            int maxWidth = L.stream()
                    .mapToInt(c -> fontMetrics.stringWidth(c.getInputText()))
                    .max()
                    .orElseThrow();
            // this doesn't seem to work at all, maybe it's hardcoded at startup
            autoCompletion.setChoicesWindowSize(maxWidth + 10, 3 * fontMetrics.getHeight() + 10); // 5px margin on each side

            autoCompletion.setShowDescWindow(true);
            int maxDescWidth = L.stream()
                    .mapToInt(c -> fontMetrics.stringWidth(c.getReplacementText()))
                    .max()
                    .orElseThrow();
            // Desc uses a different (monospaced) font but I'm not sure how to infer which
            // So, hack in a 1.2 factor
            autoCompletion.setDescriptionWindowSize((int) (1.2 * maxDescWidth + 10), 3 * fontMetrics.getHeight() + 10);
            return L;
        }

        private Completion createCompletion(RepoFile file) {
            return new ShorthandCompletion(this, file.getFileName(), file.toString() + " ");
        }
    }
    /**
     * A node in the file tree that represents a file or directory.
     */
    private static class FileTreeNode {
        private final File file;
        
        public FileTreeNode(File file) {
            this.file = file;
        }
        
        public File getFile() {
            return file;
        }
        
        @Override
        public String toString() {
            return file.getName().isEmpty() ? file.getPath() : file.getName();
        }
    }
    
    /**
     * Custom cell renderer for the file tree that shows icons for files and folders.
     */
    private static class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        private final Icon fileIcon = UIManager.getIcon("FileView.fileIcon");
        private final Icon folderIcon = UIManager.getIcon("FileView.directoryIcon");
        private final Icon driveIcon = UIManager.getIcon("FileView.hardDriveIcon");
        
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                     boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Component comp = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            
            if (value instanceof DefaultMutableTreeNode node) {
                if (node.getUserObject() instanceof FileTreeNode fileNode) {
                    File file = fileNode.getFile();
                    if (file.isFile()) {
                        setIcon(fileIcon);
                    } else if (file.isDirectory()) {
                        setIcon(folderIcon);
                    } else {
                        // Drive or special folder
                        setIcon(driveIcon);
                    }
                }
            }
            
            return comp;
        }
    }
    
    /**
     * Tree model that loads directory contents on-demand when a node is expanded.
     */
    /**
     * Expands the tree view to show the project root path.
     * This traverses the file system nodes to find the path to the project root.
     */
    private void expandTreeToProjectRoot(JTree tree) {
        // Convert rootPath to absolute path to ensure we get the full path
        Path absoluteRootPath = rootPath.toAbsolutePath().normalize();
        List<String> pathSegments = new ArrayList<>();
        
        // Break the path into segments
        for (Path segment : absoluteRootPath) {
            pathSegments.add(segment.toString());
        }
        
        // Start from root node
        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) tree.getModel().getRoot();
        
        // First level are the filesystem roots
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode driveNode = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (driveNode.getUserObject() instanceof FileTreeNode fileNode) {
                File drive = fileNode.getFile();
                // Check if this drive is part of our project path
                if (absoluteRootPath.startsWith(drive.toPath())) {
                    // This is our drive, expand it
                    TreePath drivePath = new TreePath(new Object[]{rootNode, driveNode});
                    tree.expandPath(drivePath);
                    
                    // Now traverse and expand each segment of the path
                    traverseAndExpandPath(tree, driveNode, drivePath, absoluteRootPath, drive.toPath());
                    break;
                }
            }
        }
    }
    
    /**
     * Recursively traverses and expands tree nodes to reach the project root path.
     */
    private void traverseAndExpandPath(JTree tree, DefaultMutableTreeNode currentNode, 
                                      TreePath currentPath, Path targetPath, Path currentAbsPath) {
        // Load children of the current node
        ((LazyLoadingTreeModel) tree.getModel()).loadChildren(currentNode);
        
        // If we've reached the target, stop
        if (currentAbsPath.equals(targetPath)) {
            return;
        }
        
        // Get the next path segment to look for
        Path nextSegment = null;
        for (Path p : targetPath) {
            Path potential = currentAbsPath.resolve(p);
            if (potential.startsWith(currentAbsPath) && 
                !potential.equals(currentAbsPath) && 
                targetPath.startsWith(potential)) {
                nextSegment = p;
                break;
            }
        }
        
        if (nextSegment == null) {
            // Try to get the relative next segment
            Path relativePath = currentAbsPath.relativize(targetPath);
            if (!relativePath.toString().isEmpty()) {
                nextSegment = relativePath.getName(0);
            }
        }
        
        // If we found a next segment, look for it in the children
        if (nextSegment != null) {
            String segmentName = nextSegment.getFileName().toString();
            
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) currentNode.getChildAt(i);
                if (childNode.getUserObject() instanceof FileTreeNode fileNode) {
                    if (fileNode.getFile().getName().equals(segmentName)) {
                        // Found the next segment, expand it
                        TreePath newPath = currentPath.pathByAddingChild(childNode);
                        tree.expandPath(newPath);
                        
                        // Continue traversing
                        Path newAbsPath = currentAbsPath.resolve(segmentName);
                        traverseAndExpandPath(tree, childNode, newPath, targetPath, newAbsPath);
                        break;
                    }
                }
            }
        }
    }

    private class LazyLoadingTreeModel extends javax.swing.tree.DefaultTreeModel {
        public LazyLoadingTreeModel(DefaultMutableTreeNode root) {
            super(root);
            
            addTreeModelListener(new javax.swing.event.TreeModelListener() {
                @Override
                public void treeNodesChanged(javax.swing.event.TreeModelEvent e) {}
                
                @Override
                public void treeNodesInserted(javax.swing.event.TreeModelEvent e) {}
                
                @Override
                public void treeNodesRemoved(javax.swing.event.TreeModelEvent e) {}
                
                @Override
                public void treeStructureChanged(javax.swing.event.TreeModelEvent e) {}
            });
        }
        
        @Override
        public boolean isLeaf(Object node) {
            if (node instanceof DefaultMutableTreeNode treeNode) {
                Object userObject = treeNode.getUserObject();
                if (userObject instanceof FileTreeNode fileNode) {
                    return fileNode.getFile().isFile();
                }
            }
            return super.isLeaf(node);
        }
        
        /**
         * Load the children of the specified node on demand.
         */
        public void loadChildren(DefaultMutableTreeNode node) {
            Object userObject = node.getUserObject();
            if (userObject instanceof FileTreeNode fileNode) {
                File dir = fileNode.getFile();
                if (dir.isDirectory() && node.getChildCount() == 0) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        // Sort: directories first, then files
                        java.util.Arrays.sort(files, (f1, f2) -> {
                            if (f1.isDirectory() && !f2.isDirectory()) return -1;
                            if (!f1.isDirectory() && f2.isDirectory()) return 1;
                            return f1.getName().compareToIgnoreCase(f2.getName());
                        });
                        
                        for (File file : files) {
                            if (!file.isHidden()) {
                                DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(new FileTreeNode(file));
                                insertNodeInto(childNode, node, node.getChildCount());
                            }
                        }
                    }
                }
            }
        }
    }
}
