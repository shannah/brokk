package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Set;

/**
 * Utility class for creating and displaying context menus for file references.
 */
public final class ContextMenuUtils {
    
    /**
     * Represents a file reference with metadata for context menu usage.
     * This is a copy of TableUtils.FileReferenceList.FileReferenceData made public.
     */
    public static class FileReferenceData {
        private final String fileName;
        private final String fullPath;
        private final ProjectFile projectFile;

        public FileReferenceData(String fileName, String fullPath, ProjectFile projectFile) {
            this.fileName = fileName;
            this.fullPath = fullPath;
            this.projectFile = projectFile;
        }

        public String getFileName() {
            return fileName;
        }

        public String getFullPath() {
            return fullPath;
        }

        public ProjectFile getRepoFile() {
            return projectFile;
        }
    }
    
    // Private constructor to prevent instantiation
    private ContextMenuUtils() {
    }
    
    /**
     * Shows a context menu for a file reference.
     *
     * @param owner The component that owns the popup (where it will be displayed)
     * @param targetRef The file reference data for which to show the menu
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void showFileRefMenu(Component owner, Object fileRefData, Chrome chrome, Runnable onRefreshSuggestions) {
        // Convert to our FileReferenceData if needed
        FileReferenceData targetRef;
        if (fileRefData instanceof FileReferenceData) {
            targetRef = (FileReferenceData) fileRefData;
        } else {
            // Assume it's the internal TableUtils.FileReferenceList.FileReferenceData
            // and extract the fields using reflection or conversion methods
            try {
                var method = fileRefData.getClass().getMethod("getFileName");
                String fileName = (String) method.invoke(fileRefData);
                
                method = fileRefData.getClass().getMethod("getFullPath");
                String fullPath = (String) method.invoke(fileRefData);
                
                method = fileRefData.getClass().getMethod("getRepoFile");
                ProjectFile projectFile = (ProjectFile) method.invoke(fileRefData);
                
                targetRef = new FileReferenceData(fileName, fullPath, projectFile);
            } catch (Exception e) {
                throw new RuntimeException("Failed to convert file reference data", e);
            }
        }
        
        var cm = chrome.getContextManager();
        JPopupMenu menu = new JPopupMenu();

        JMenuItem showContentsItem = new JMenuItem("Show Contents");
        showContentsItem.addActionListener(e1 -> {
            if (targetRef.getRepoFile() != null) {
                chrome.openFragmentPreview(new ContextFragment.ProjectPathFragment(targetRef.getRepoFile()));
            }
        });
        menu.add(showContentsItem);
        menu.addSeparator();

        // Edit option
        JMenuItem editItem = new JMenuItem("Edit " + targetRef.getFullPath());
        editItem.addActionListener(e1 -> {
            withTemporaryListenerDetachment(chrome, cm, () -> {
                if (targetRef.getRepoFile() != null) {
                    cm.editFiles(List.of(targetRef.getRepoFile()));
                } else {
                    chrome.toolErrorRaw("Cannot edit file: " + targetRef.getFullPath() + " - no ProjectFile available");
                }
            }, "Edit files");
        });
        // Disable for dependency projects
        if (cm.getProject() != null && !cm.getProject().hasGit()) {
            editItem.setEnabled(false);
            editItem.setToolTipText("Editing not available without Git");
        }
        menu.add(editItem);

        // Read option
        JMenuItem readItem = new JMenuItem("Read " + targetRef.getFullPath());
        readItem.addActionListener(e1 -> {
            withTemporaryListenerDetachment(chrome, cm, () -> {
                if (targetRef.getRepoFile() != null) {
                    cm.addReadOnlyFiles(List.of(targetRef.getRepoFile()));
                } else {
                    chrome.toolErrorRaw("Cannot read file: " + targetRef.getFullPath() + " - no ProjectFile available");
                }
            }, "Read files");
        });
        menu.add(readItem);

        // Summarize option
        JMenuItem summarizeItem = new JMenuItem("Summarize " + targetRef.getFullPath());
        summarizeItem.addActionListener(e1 -> {
            withTemporaryListenerDetachment(chrome, cm, () -> {
                if (targetRef.getRepoFile() != null) {
                    boolean success = cm.addSummaries(Set.of(targetRef.getRepoFile()), Set.of());
                    if (success) {
                        chrome.systemOutput("Summarized " + targetRef.getFullPath());
                    } else {
                        chrome.toolErrorRaw("No summarizable code found");
                    }
                } else {
                    chrome.toolErrorRaw("Cannot summarize: " + targetRef.getFullPath() + " - ProjectFile information not available");
                }
            }, "Summarize files");
        });
        menu.add(summarizeItem);
        menu.addSeparator();

        JMenuItem refreshSuggestionsItem = new JMenuItem("Refresh Suggestions");
        refreshSuggestionsItem.addActionListener(e1 -> onRefreshSuggestions.run());
        menu.add(refreshSuggestionsItem);

        // Theme management will be handled by the caller
        menu.show(owner, 0, 0);
    }
    
    /**
     * Attaches a context menu to a component containing file references.
     *
     * @param parent The component to which the context menu should be attached
     * @param refs The list of file references to use in the menu
     * @param chrome The Chrome instance for UI integration
     * @param onRefresh Runnable to call when "Refresh Suggestions" is selected
     */
    public static void attachFileRefContextMenu(
            JComponent parent,
            List<?> refs,
            Chrome chrome,
            Runnable onRefresh) {
        parent.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                maybeShowPopup(e);
            }
            
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                maybeShowPopup(e);
            }
            
            private void maybeShowPopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                // Logic to find which badge was clicked would go here
                // For now we'll just use the first reference if any exist
                if (refs == null || refs.isEmpty()) return;
                showFileRefMenu(parent, refs.get(0), chrome, onRefresh);
            }
        });
    }
    
    /**
     * Helper method to detach context listener temporarily while performing operations.
     */
    private static void withTemporaryListenerDetachment(Chrome chrome, ContextManager cm, Runnable action, String taskDescription) {
        // Access the contextManager from Chrome and call submitContextTask on it
        chrome.getContextManager().submitContextTask(taskDescription, action);
    }
}
