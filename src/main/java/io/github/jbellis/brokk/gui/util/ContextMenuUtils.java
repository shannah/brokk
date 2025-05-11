package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.TableUtils;
import io.github.jbellis.brokk.gui.TableUtils.FileReferenceList.FileReferenceData;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;

/**
 * Utility class for creating and displaying context menus for file references.
 */
public final class ContextMenuUtils {
    
    /**
     * Handles mouse clicks on file reference badges in a table.
     * Shows the appropriate context menu or overflow popup based on the click location and type.
     *
     * @param e The mouse event
     * @param table The table containing file reference badges
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void handleFileReferenceClick(MouseEvent e, JTable table, Chrome chrome, Runnable onRefreshSuggestions) {
        assert SwingUtilities.isEventDispatchThread();
        
        Point p = e.getPoint();
        int row = table.rowAtPoint(p);
        if (row < 0) return;

        // Always select the row for visual feedback
        table.requestFocusInWindow();
        table.setRowSelectionInterval(row, row);
        
        @SuppressWarnings("unchecked")
        var fileRefs = (List<FileReferenceData>)
                table.getValueAt(row, 0);

        if (fileRefs == null || fileRefs.isEmpty()) return;
        
        // Get the renderer and extract visible/hidden files using reflection
        Component renderer = table.prepareRenderer(
            table.getCellRenderer(row, 0), row, 0);
        
        // Extract needed data from renderer via reflection
        List<FileReferenceData> visibleFiles;
        List<FileReferenceData> hiddenFiles = List.of();
        boolean hasOverflow = false;
        
        try {
            var getVisibleFilesMethod = renderer.getClass().getMethod("getVisibleFiles");
            @SuppressWarnings("unchecked")
            var visibleFilesResult = (List<FileReferenceData>) getVisibleFilesMethod.invoke(renderer);
            visibleFiles = visibleFilesResult;
            
            var hasOverflowMethod = renderer.getClass().getMethod("hasOverflow");
            hasOverflow = (Boolean) hasOverflowMethod.invoke(renderer);
            
            if (hasOverflow) {
                var getHiddenFilesMethod = renderer.getClass().getMethod("getHiddenFiles");
                @SuppressWarnings("unchecked")
                var hiddenFilesResult = (List<FileReferenceData>) getHiddenFilesMethod.invoke(renderer);
                hiddenFiles = hiddenFilesResult;
            }
        } catch (Exception ex) {
            // Fallback if reflection fails - use all files as visible
            visibleFiles = fileRefs;
            // Log the issue but continue (avoid breaking the UI)
            System.err.println("Error accessing renderer methods: " + ex.getMessage());
        }
        
        // Check what kind of mouse event we're handling
        if (e.isPopupTrigger()) {
            // Right-click (context menu)
            var targetRef = findClickedReference(p, row, table, visibleFiles);
            
            // Right-click on overflow badge?
            if (targetRef == null && hasOverflow) {
                TableUtils.showOverflowPopup(chrome, table, hiddenFiles);
                return;
            }
            
            // Default to first file if click wasn't on a specific badge
            if (targetRef == null) targetRef = fileRefs.get(0);
            
            // Show the context menu
            showFileRefMenu(
                table, 
                targetRef, 
                chrome, 
                onRefreshSuggestions
            );
        } else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
            // Left-click
            var targetRef = findClickedReference(p, row, table, visibleFiles);
            
            // If no visible badge was clicked AND we have overflow
            if (targetRef == null && hasOverflow) {
                // Show the overflow popup with only the hidden files
                TableUtils.showOverflowPopup(chrome, table, hiddenFiles);
            }
        }
    }
    
    /**
     * Resolves which FileReferenceData badge is under the supplied mouse location.
     * 
     * @param pointInTableCoords The point in table coordinates
     * @param row The row index
     * @param table The table containing the badges
     * @param visibleReferences The list of visible file references
     * @return The FileReferenceData under the point, or null if none
     */
    private static FileReferenceData findClickedReference(Point pointInTableCoords,
                                                   int row,
                                                   JTable table,
                                                   List<FileReferenceData> visibleReferences)
    {
        // Convert to cell-local coordinates
        Rectangle cellRect = table.getCellRect(row, 0, false);
        int xInCell = pointInTableCoords.x - cellRect.x;
        int yInCell = pointInTableCoords.y - cellRect.y;
        if (xInCell < 0 || yInCell < 0) return null;

        // Badge layout parameters – keep in sync with FileReferenceList
        final int hgap = 4;     // FlowLayout hgap in FileReferenceList

        // Font used inside the badges (85 % of table font size) - must match FileReferenceList.createBadgeLabel
        var baseFont = table.getFont();
        var badgeFont = baseFont.deriveFont(Font.PLAIN, baseFont.getSize() * 0.85f);
        var fm = table.getFontMetrics(badgeFont);

        int currentX = 0;
        // Calculate insets based on BORDER_THICKNESS and text padding (matching createBadgeLabel)
        int borderStrokeInset = (int) Math.ceil(TableUtils.FileReferenceList.BORDER_THICKNESS);
        int textPaddingHorizontal = 6; // As defined in createBadgeLabel's EmptyBorder logic
        int totalInsetsPerSide = borderStrokeInset + textPaddingHorizontal;

        for (var ref : visibleReferences) {
            int textWidth = fm.stringWidth(ref.getFileName());
            // Label width is text width + total left inset + total right inset
            int labelWidth = textWidth + (2 * totalInsetsPerSide);
            if (xInCell >= currentX && xInCell <= currentX + labelWidth) {
                return ref;
            }
            currentX += labelWidth + hgap;
        }
        return null;
    }
   
    // Private constructor to prevent instantiation
    private ContextMenuUtils() {
    }
    
    /**
     * Shows a context menu for a file reference.
     *
     * @param owner The component that owns the popup (where it will be displayed)
     * @param fileRefData The file reference data for which to show the menu
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void showFileRefMenu(Component owner, Object fileRefData, Chrome chrome, Runnable onRefreshSuggestions) {
        showFileRefMenu(owner, 0, 0, fileRefData, chrome, onRefreshSuggestions);
    }
    
    /**
     * Shows a context menu for a file reference at the specified position.
     *
     * @param owner The component that owns the popup (where it will be displayed)
     * @param x The x position for the menu relative to the owner
     * @param y The y position for the menu relative to the owner
     * @param fileRefData The file reference data for which to show the menu
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void showFileRefMenu(Component owner, int x, int y, Object fileRefData, Chrome chrome, Runnable onRefreshSuggestions) {
        // Convert to our FileReferenceData if needed
        TableUtils.FileReferenceList.FileReferenceData targetRef;
        if (fileRefData instanceof TableUtils.FileReferenceList.FileReferenceData) {
            targetRef = (TableUtils.FileReferenceList.FileReferenceData) fileRefData;
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
                
                targetRef = new TableUtils.FileReferenceList.FileReferenceData(fileName, fullPath, projectFile);
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
        menu.show(owner, x, y);
    }
    
    /**
     * Helper method to detach context listener temporarily while performing operations.
     */
    private static void withTemporaryListenerDetachment(Chrome chrome, ContextManager cm, Runnable action, String taskDescription) {
        // Access the contextManager from Chrome and call submitContextTask on it
        chrome.getContextManager().submitContextTask(taskDescription, action);
    }
}
