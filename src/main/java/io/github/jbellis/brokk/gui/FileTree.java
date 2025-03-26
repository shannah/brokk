package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.RepoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A JTree component specialized for displaying file hierarchies, either from a Git repository
 * or the local file system, with support for lazy loading.
 */
public class FileTree extends JTree {
    private static final Logger logger = LogManager.getLogger(FileTree.class);

    /**
     * Constructs a FileTree.
     *
     * @param project            The current project (can be null if allowExternalFiles is true and no repo context needed).
     * @param allowExternalFiles If true, shows the full file system; otherwise, shows project repo files.
     * @param fileFilter         Optional predicate to filter files shown in the tree (external mode only).
     */
    public FileTree(Project project, boolean allowExternalFiles, Predicate<File> fileFilter) {
        this(project.getRoot().toAbsolutePath(), project.getRepo(), allowExternalFiles, fileFilter);
    }

    public FileTree(Path projectPath, GitRepo repo, boolean allowExternalFiles, Predicate<File> fileFilter) {
        if (!allowExternalFiles && projectPath == null) {
            logger.error("Project root path cannot be null when allowExternalFiles is false");
            throw new IllegalArgumentException("Project root path must be provided if allowExternalFiles is false");
        }

        DefaultMutableTreeNode rootNode;
        TreeModel treeModel;

        if (allowExternalFiles) {
            // Show file system roots
            logger.debug("Building external file system tree view");
            rootNode = new DefaultMutableTreeNode("File System");
            File[] roots = File.listRoots();
            if (roots != null) {
                logger.debug("Found {} file system roots", roots.length);
                for (File root : roots) {
                    logger.trace("Adding file system root: {}", root.getAbsolutePath());
                    DefaultMutableTreeNode driveNode = new DefaultMutableTreeNode(new FileTreeNode(root));
                    // Add a placeholder child to make it expandable initially
                    if (root.isDirectory()) {
                        driveNode.add(new DefaultMutableTreeNode("Loading..."));
                    }
                    rootNode.add(driveNode);
                }
            } else {
                logger.warn("No file system roots found");
            }
            treeModel = new LazyLoadingTreeModel(rootNode, fileFilter, logger);
            setCellRenderer(new FileTreeCellRenderer());
            // Add listener for lazy loading
            addTreeWillExpandListener(new TreeWillExpandListener() {
                @Override
                public void treeWillExpand(TreeExpansionEvent event) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                    if (getModel() instanceof LazyLoadingTreeModel lazyModel) {
                        // Run loading in a background thread to avoid blocking EDT
                        SwingWorker<Void, Void> worker = new SwingWorker<>() {
                            @Override
                            protected Void doInBackground() {
                                lazyModel.loadChildren(node);
                                return null;
                            }
                            @Override
                            protected void done() {
                                // Reloading/structure change is handled within loadChildren
                            }
                        };
                        worker.execute();
                    }
                }

                @Override
                public void treeWillCollapse(TreeExpansionEvent event) {
                    // Do nothing
                }
            });
        } else {
            // Show project file hierarchy 
            logger.debug("Building project file tree view");

            rootNode = new DefaultMutableTreeNode(projectPath.getFileName().toString());
            Map<Path, DefaultMutableTreeNode> folderNodes = new HashMap<>();
            folderNodes.put(projectPath, rootNode); // Map absolute path to node

            if (repo == null) {
                // Recursively walk the file system from the project root
                logger.debug("Walking file system from project root: {}", projectPath);
                try {
                    Files.walk(projectPath)
                        .filter(path -> !Files.isDirectory(path)) // Skip directories as they're created by parent logic
                        .sorted() // Sort for consistent order
                        .forEach(path -> {
                            logger.trace("Processing file: {}", path);
                            addFileToTree(projectPath, path, rootNode, folderNodes);
                        });
                    logger.debug("File system walk complete");
                } catch (Exception e) {
                    logger.error("Error walking file system from root {}", projectPath, e);
                }
            } else {
                // Use tracked files from Git repository
                logger.debug("Using Git tracked files for tree view");
                var tracked = new ArrayList<>(repo.getTrackedFiles());
                logger.debug("Found {} tracked files in repository", tracked.size());
                tracked.sort(Comparator.comparing(RepoFile::toString)); // Sort for consistent order

                for (RepoFile rf : tracked) {
                    logger.trace("Processing repo file: {}", rf);
                    Path absPath = rf.absPath();
                    Path parentAbsPath = absPath.getParent();
                    if (parentAbsPath == null) continue; // Should not happen for files in repo

                    addFileToTree(projectPath, absPath, rootNode, folderNodes);
                }
            }
            treeModel = new DefaultTreeModel(rootNode); // Use standard DefaultTreeModel
            setCellRenderer(new FileTreeCellRenderer()); // Also use custom renderer for repo view icons
            logger.debug("Project tree model created with {} nodes", rootNode.getChildCount());
        }

        setModel(treeModel);
        setRootVisible(true);
        setShowsRootHandles(true);
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        logger.debug("FileTree base configuration complete");

        if (allowExternalFiles) {
            // If external files are allowed, try to expand to the project root if it exists
            if (projectPath != null && Files.exists(projectPath)) {
                logger.debug("Attempting to expand tree to project root: {}", projectPath);
                expandTreeToPath(projectPath);
            } else if (projectPath != null) {
                logger.warn("Project root path does not exist: {}", projectPath);
            }
        }
        logger.debug("FileTree initialization complete");
    }

    /**
     * Expands the tree view to show the specified target path.
     * Assumes the tree model is LazyLoadingTreeModel when called.
     */
    private void expandTreeToPath(Path targetPath) {
        logger.debug("Expanding tree to path: {}", targetPath);
        if (!(getModel() instanceof LazyLoadingTreeModel model)) {
            logger.warn("expandTreeToPath called but model is not LazyLoadingTreeModel");
            return; // Only works with the lazy model
        }

        Path absoluteTargetPath = targetPath.toAbsolutePath().normalize();
        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) model.getRoot();

        // Find the drive/root node that contains the target path
        DefaultMutableTreeNode currentTreeNode = null;
        TreePath currentTreePath = null;
        File currentFile = null;

        // Ensure root node's children (drives) are loaded (they should be, but check)
        // Note: Initial drive loading happens in constructor, not via lazy load mechanism
        // model.loadChildren(rootNode); // This isn't correct for the top "File System" node

        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode driveNode = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (driveNode.getUserObject() instanceof FileTreeNode fileNode) {
                File drive = fileNode.getFile();
                // Use startsWith check on the absolute paths
                try {
                    if (absoluteTargetPath.startsWith(drive.toPath().toAbsolutePath())) {
                        currentTreeNode = driveNode;
                        currentTreePath = new TreePath(new Object[]{rootNode, driveNode});
                        currentFile = drive;
                        break;
                    }
                } catch (InvalidPathException e) {
                    logger.warn("Error comparing paths: {} and {}", absoluteTargetPath, drive.getAbsolutePath(), e);
                }
            }
        }

        if (currentTreeNode == null) {
            logger.warn("Could not find tree root for path: {}", absoluteTargetPath);
            return; // Target path's root not found in the tree
        } else {
            logger.debug("Found root node for target path: {}", currentFile.getAbsolutePath());
        }

        // Traverse the path segments relative to the found drive/root
        Path relativePath;
        try {
            relativePath = currentFile.toPath().toAbsolutePath().relativize(absoluteTargetPath);
        } catch (IllegalArgumentException e) {
            logger.warn("Could not relativize target path {} against root {}", absoluteTargetPath, currentFile.getAbsolutePath(), e);
            return;
        }


        for (Path segment : relativePath) {
            String segmentName = segment.toString();

            // Ensure children of the current node are loaded before searching
            // This needs to happen sequentially and potentially involve EDT waits if loading is slow,
            // but our background worker handles the loading, we just need to find the node after loading.
            // We trigger loading here if needed, but finding happens on the EDT later.
            // For immediate expansion, we might need a synchronous load here, but let's stick to background.
            model.loadChildren(currentTreeNode); // Ensure loading is triggered if not already done.

            DefaultMutableTreeNode nextNode = null;
            // This search might fail if loadChildren is still running in background.
            // A more robust solution might involve waiting or using invokeLater chains.
            // For now, assume loading is fast enough or user interaction triggers expansion.
            for (int i = 0; i < currentTreeNode.getChildCount(); i++) {
                TreeNode child = currentTreeNode.getChildAt(i);
                if (child instanceof DefaultMutableTreeNode childNode) {
                    if (childNode.getUserObject() instanceof FileTreeNode fileNode) {
                        if (fileNode.getFile().getName().equals(segmentName)) {
                            nextNode = childNode;
                            break;
                        }
                    } else if (childNode.getUserObject() instanceof String nodeName) {
                        // Should not happen in external mode, but check just in case
                        if (nodeName.equals(segmentName)) {
                            nextNode = childNode;
                            break;
                        }
                    }
                }
            }

            if (nextNode != null) {
                currentTreeNode = nextNode;
                currentTreePath = currentTreePath.pathByAddingChild(currentTreeNode);
                currentFile = ((FileTreeNode) currentTreeNode.getUserObject()).getFile(); // Update currentFile
                // Expand the path in the tree
                final TreePath pathToExpand = currentTreePath;
                // Ensure expansion happens on the EDT
                SwingUtilities.invokeLater(() -> expandPath(pathToExpand));
                logger.trace("Expanded path to segment: {}", segmentName);
            } else {
                logger.warn("Could not find segment '{}' in tree path for {}", segmentName, absoluteTargetPath);
                logger.debug("Current node children count: {}", currentTreeNode.getChildCount());
                // Path segment not found, stop expanding. This might happen if loadChildren hasn't finished.
                break;
            }
        }

        // After loop, optionally select the final node and scroll to it
        final TreePath finalPath = currentTreePath;
        final DefaultMutableTreeNode finalNode = currentTreeNode;
        SwingUtilities.invokeLater(() -> {
            if (finalPath != null) {
                logger.debug("Setting selection path and scrolling to: {}", 
                             finalNode != null ? finalNode.getUserObject() : "null node");
                setSelectionPath(finalPath);
                scrollPathToVisible(finalPath); // Make sure it's visible
                // If the final node is a directory, ensure its children are loaded for display
                // (loadChildren might need to be called again if the expansion didn't trigger it fully)
                if (finalNode != null && finalNode.getUserObject() instanceof FileTreeNode fn && fn.getFile().isDirectory()) {
                    // Model might need reloading if children weren't fully populated before UI update
                    if (!fn.areChildrenLoaded()) {
                        logger.debug("Loading children for final node: {}", fn.getFile().getAbsolutePath());
                        model.loadChildren(finalNode);
                    }
                }
            } else {
                logger.warn("Final path is null after expansion attempt");
            }
        });
        logger.debug("Tree path expansion process complete");
    }


    /**
     * Helper method to add a file to the tree, creating any parent folders as needed.
     * Used by both Git repo mode and file system walk mode.
     */
    private void addFileToTree(Path projectPath, Path absPath, DefaultMutableTreeNode rootNode, 
                               Map<Path, DefaultMutableTreeNode> folderNodes) {
        Path parentAbsPath = absPath.getParent();
        if (parentAbsPath == null) return; // Should not happen for files in a project
        
        DefaultMutableTreeNode parentNode = rootNode;
        // If parent is not the root, find or create intermediate folder nodes
        if (!parentAbsPath.equals(projectPath)) {
            Path relativeParentPath = projectPath.relativize(parentAbsPath);
            Path currentAbsFolder = projectPath;
            DefaultMutableTreeNode currentFolderNode = rootNode;
            
            for (Path segment : relativeParentPath) {
                currentAbsFolder = currentAbsFolder.resolve(segment);
                DefaultMutableTreeNode existing = folderNodes.get(currentAbsFolder);
                if (existing == null) {
                    existing = new DefaultMutableTreeNode(segment.toString());
                    currentFolderNode.add(existing);
                    folderNodes.put(currentAbsFolder, existing); // Store node by absolute path
                }
                currentFolderNode = existing;
            }
            parentNode = currentFolderNode;
        }
        
        // Add the file node
        String filename = absPath.getFileName().toString();
        DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(filename); // Leaf node stores only filename string
        parentNode.add(fileNode);
    }

    // --- Inner Classes Moved from FileSelectionDialog ---

    /**
     * A node in the file tree that represents a file or directory using java.io.File.
     * Used when allowExternalFiles is true.
     */
    public static class FileTreeNode { // Made public static for access from FSD if needed later (though not currently)
        private final File file;
        // Store whether children have been loaded (relevant for LazyLoadingTreeModel)
        private boolean childrenLoaded = false;

        public FileTreeNode(File file) {
            Objects.requireNonNull(file, "File cannot be null for FileTreeNode");
            this.file = file;
        }

        public File getFile() {
            return file;
        }

        public boolean areChildrenLoaded() {
            return childrenLoaded;
        }

        public void setChildrenLoaded(boolean childrenLoaded) {
            this.childrenLoaded = childrenLoaded;
        }

        @Override
        public String toString() {
            // For root drives (e.g., "C:\"), getName() is empty, use getAbsolutePath().
            String name = file.getName();
            return name.isEmpty() ? file.getAbsolutePath() : name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FileTreeNode that = (FileTreeNode) o;
            return file.equals(that.file);
        }

        @Override
        public int hashCode() {
            return file.hashCode();
        }
    }

    /**
     * Custom cell renderer for the file tree that shows icons for files and folders.
     * Handles both FileTreeNode (external mode) and String (repo mode) user objects.
     */
    private static class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        private final Icon fileIcon = UIManager.getIcon("FileView.fileIcon");
        private final Icon folderIcon = UIManager.getIcon("FileView.directoryIcon");
        private final Icon driveIcon = UIManager.getIcon("FileView.hardDriveIcon");

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            // Use default renderer first to get basic label and selection highlighting
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();

                if (userObject instanceof FileTreeNode fileNode) {
                    // External file system node
                    File file = fileNode.getFile();
                    javax.swing.filechooser.FileSystemView fsv = javax.swing.filechooser.FileSystemView.getFileSystemView();
                    Icon icon = fsv.getSystemIcon(file);
                    if (icon != null) {
                        setIcon(icon);
                    } else {
                        // Fallback icons
                        if (fsv.isDrive(file) || fsv.isFileSystemRoot(file)) {
                            setIcon(driveIcon);
                        } else if (file.isDirectory()) {
                            setIcon(folderIcon);
                        } else {
                            setIcon(fileIcon);
                        }
                    }
                    // Set tooltip to full path
                    setToolTipText(file.getAbsolutePath());
                } else if (userObject instanceof String name) {
                    // Repo file/folder node (or "File System" root)
                    if (node.getParent() == null && name.equals("File System")) {
                        // Keep default (usually a folder) or set a specific computer icon? Let's keep default.
                    } else if (leaf) {
                        setIcon(fileIcon); // Assume repo file if leaf
                    } else {
                        setIcon(folderIcon); // Assume repo folder if not leaf
                    }
                    // Tooltip is just the name itself (could construct full path if needed)
                    setToolTipText(name);
                } else if ("Loading...".equals(userObject)) {
                    // Keep default text, maybe set a specific loading icon? Default is fine.
                    setIcon(null); // Or a spinner icon
                    setText("Loading...");
                    setToolTipText(null);
                }
            }
            return this;
        }
    }


    /**
     * Tree model that loads directory contents on-demand when a node is expanded.
     * Used when allowExternalFiles is true.
     */
    private static class LazyLoadingTreeModel extends DefaultTreeModel {
        private final Predicate<File> fileFilter;
        private final Logger modelLogger;

        public LazyLoadingTreeModel(DefaultMutableTreeNode root, Predicate<File> fileFilter, Logger logger) {
            super(root);
            this.fileFilter = fileFilter; // Can be null
            this.modelLogger = logger;
        }

        @Override
        public boolean isLeaf(Object node) {
            if (node instanceof DefaultMutableTreeNode treeNode) {
                Object userObject = treeNode.getUserObject();
                if (userObject instanceof FileTreeNode fileNode) {
                    // A node is a leaf if its File object is not a directory
                    // or if it's a directory but not readable (cannot list children)
                    File f = fileNode.getFile();
                    return !f.isDirectory() || !f.canRead();
                }
                // The placeholder "Loading..." node is considered a leaf until replaced
                if ("Loading...".equals(userObject)) {
                    return true;
                }
            }
            // Rely on default behavior only if not a FileTreeNode or placeholder
            return super.isLeaf(node);
        }

        /**
         * Load the children of the specified node on demand.
         * Should be called when a node is about to expand. Executed in background thread.
         */
        public synchronized void loadChildren(DefaultMutableTreeNode node) {
            Object userObject = node.getUserObject();
            if (!(userObject instanceof FileTreeNode fileNode)) {
                modelLogger.trace("loadChildren called on non-FileTreeNode node: {}", userObject);
                return; // Not a node representing a File/Directory
            }
            
            modelLogger.debug("Loading children for node: {}", fileNode.getFile().getAbsolutePath());

            File dir = fileNode.getFile();
            // Only load if it's a directory, readable, and children haven't been loaded yet
            if (!dir.isDirectory() || !dir.canRead() || fileNode.areChildrenLoaded()) {
                if (dir.isDirectory() && !dir.canRead()) {
                    modelLogger.warn("Cannot read directory: {}", dir.getAbsolutePath());
                } else if (dir.isDirectory() && fileNode.areChildrenLoaded()){
                    modelLogger.trace("Children already loaded for: {}", dir.getAbsolutePath());
                }
                return;
            }
            modelLogger.debug("Loading children for: {}", dir.getAbsolutePath());

            // Check if the node contains the placeholder "Loading..."
            boolean hadPlaceholder = node.getChildCount() == 1 &&
                    node.getFirstChild() instanceof DefaultMutableTreeNode firstChild &&
                    "Loading...".equals(firstChild.getUserObject());

            if (hadPlaceholder) {
                // Remove placeholder *before* adding actual children
                // This needs to happen on the EDT if listeners are involved.
                final DefaultMutableTreeNode placeholder = (DefaultMutableTreeNode) node.getFirstChild();
                SwingUtilities.invokeLater(() -> removeNodeFromParent(placeholder));
            } else {
                modelLogger.trace("Node {} already processed or had no placeholder.", dir.getName());
            }


            File[] files = dir.listFiles();
            logger.debug("Listed files {}", (Object) files);
            if (files != null) {
                // Sort: directories first, then files, case-insensitive
                Arrays.sort(files, (f1, f2) -> {
                    boolean f1IsDir = f1.isDirectory();
                    boolean f2IsDir = f2.isDirectory();
                    if (f1IsDir && !f2IsDir) return -1;
                    if (!f1IsDir && f2IsDir) return 1;
                    return f1.getName().compareToIgnoreCase(f2.getName());
                });

                final java.util.List<DefaultMutableTreeNode> nodesToAdd = new ArrayList<>();
                for (File file : files) {
                    // Apply filter: always include directories (unless hidden), only include files if they pass the filter
                    if (!file.isHidden()) {
                        boolean include = fileFilter == null || file.isDirectory() || fileFilter.test(file);

                        if (include) {
                            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(new FileTreeNode(file));
                            // If the child is a directory and readable, add a placeholder
                            if (file.isDirectory() && file.canRead()) {
                                childNode.add(new DefaultMutableTreeNode("Loading..."));
                            }
                            nodesToAdd.add(childNode);
                        }
                    }
                }
                // Batch insert nodes on EDT
                SwingUtilities.invokeLater(() -> {
                    for (DefaultMutableTreeNode childNode : nodesToAdd) {
                        insertNodeInto(childNode, node, node.getChildCount());
                    }
                    fileNode.setChildrenLoaded(true); // Mark as loaded after adding nodes
                    // If we started with a placeholder, a reload might be needed instead of incremental inserts.
                    // DefaultTreeModel's insertNodeInto notifies listeners, so reload might be redundant
                    // unless we batch removed/added. Sticking with insertNodeInto for now.
                    // reload(node); // Use this if insertNodeInto causes issues after placeholder removal
                });

            } else {
                modelLogger.warn("Failed to list files for directory: {}", dir.getAbsolutePath());
                // Update UI to show error or empty state on EDT
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren(); // Remove placeholder or previous contents
                    node.add(new DefaultMutableTreeNode("Error listing files"));
                    nodeStructureChanged(node); // Notify tree about the change
                    fileNode.setChildrenLoaded(true); // Mark as loaded (even though it failed) to prevent retry loop
                });
            }
            // Mark as loaded should happen after nodes are added (on EDT)
            // fileNode.setChildrenLoaded(true); // Moved inside invokeLater
        }
    }
}
