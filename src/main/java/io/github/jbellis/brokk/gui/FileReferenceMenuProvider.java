package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;

import javax.swing.*;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;

/**
 * Service that provides context menus for file references.
 */
public class FileReferenceMenuProvider {
    private static final String REFERENCED_FILES_MENU_TEXT = "Referenced Files";
    
    /**
     * Creates or updates a context menu with file reference items
     * @param menu The popup menu to update
     * @param references The file references to add
     * @param chrome The Chrome instance for context actions
     * @return true if menu was modified, false otherwise
     */
    public static boolean updateContextMenu(JPopupMenu menu, List<FileReferenceData> references, Chrome chrome) {
        // Remove any existing menu items first
        removeReferencedFilesMenu(menu);
        
        // If we have references, add a new menu
        if (references != null && !references.isEmpty()) {
            menu.add(createReferencedFilesMenu(references, chrome));
            return true;
        }
        
        return false;
    }
    
    /**
     * Removes any "Referenced Files" menus from the popup
     */
    public static void removeReferencedFilesMenu(JPopupMenu menu) {
        for (Component item : menu.getComponents()) {
            if (item instanceof JMenuItem && REFERENCED_FILES_MENU_TEXT.equals(((JMenuItem)item).getText())) {
                menu.remove(item);
            }
        }
    }
    
    /**
     * Creates a "Referenced Files" menu with file actions
     */
    public static JMenu createReferencedFilesMenu(List<FileReferenceData> references, Chrome chrome) {
        JMenu menu = new JMenu(REFERENCED_FILES_MENU_TEXT);
        for (FileReferenceData ref : references) {
            // Create a submenu named after the file
            JMenu fileSubMenu = new JMenu(ref.getFileName());
            
            // Retrieve the standard "Add / Read / Summarize" actions
            List<JMenuItem> actions = createFileActionItems(ref, chrome);

            // Add all actions to our submenu
            for (JMenuItem actionItem : actions) {
                // Add padding to action items
                fileSubMenu.add(actionItem);
            }
            menu.add(fileSubMenu);
        }
        return menu;
    }

    /**
     * Creates action menu items for a file reference
     */
    private static List<JMenuItem> createFileActionItems(FileReferenceData fileRef, Chrome chrome) {
        // Add file to context menu item
        JMenuItem addItem = new JMenuItem("Add " + fileRef.getFullPath());
        addItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.EDIT,
                        List.of(new ContextFragment.RepoPathFragment(fileRef.getRepoFile()))
                );
            } else {
                JOptionPane.showMessageDialog(null,
                        "Cannot add file: " + fileRef.getFullPath() + " - no RepoFile available",
                        "Add File Action",
                        JOptionPane.WARNING_MESSAGE);
            }
        });

        // Read file as read-only menu item
        JMenuItem readItem = new JMenuItem("Read " + fileRef.getFullPath());
        readItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.READ,
                        List.of(new ContextFragment.RepoPathFragment(fileRef.getRepoFile()))
                );
            } else {
                JOptionPane.showMessageDialog(null,
                        "Cannot read file: " + fileRef.getFullPath() + " - no RepoFile available",
                        "Read File Action",
                        JOptionPane.WARNING_MESSAGE);
            }
        });

        // Summarize file menu item
        JMenuItem summarizeItem = new JMenuItem("Summarize " + fileRef.getFullPath());
        summarizeItem.addActionListener(e -> {
            if (fileRef.getCodeUnit() != null && fileRef.getRepoFile() != null) {
                // If we have a code unit and a repo file, create a context action
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.SUMMARIZE,
                        List.of(new ContextFragment.RepoPathFragment(fileRef.getRepoFile()))
                );
            } else {
                JOptionPane.showMessageDialog(null,
                        "Cannot summarize: " + fileRef.getFullPath() + " - file information not available",
                        "Summarize File Action",
                        JOptionPane.WARNING_MESSAGE);
            }
        });

        return List.of(addItem, readItem, summarizeItem);
    }
}
