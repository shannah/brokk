package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.doc.StringDocument;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.scroll.DiffScrollComponent;
import io.github.jbellis.brokk.difftool.scroll.ScrollSynchronizer;
import io.github.jbellis.brokk.difftool.search.SearchBarDialog;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;

/**
 * This panel shows the side-by-side file panels, the diff curves, plus search bars.
 * It holds the input documents via a JMDiffNode and calculates the Patch.
 */
public class BufferDiffPanel extends AbstractContentPanel
{
    public static final int LEFT = 0;
    public static final int RIGHT = 1; // Panels are typically indexed 0 and 1
    public static final int NUMBER_OF_PANELS = 2; // We only manage 2 file panels directly

    private final BrokkDiffPanel mainPanel;
    private final boolean isDarkTheme;

    private Patch<String> patch; // Stores the computed diff result
    private AbstractDelta<String> selectedDelta;

    private int selectedLine;
    private SearchBarDialog leftBar;
    private SearchBarDialog rightBar;
    private JCheckBox caseSensitiveCheckBox;

    // The left & right "file panels"
    private final FilePanel[] filePanels = new FilePanel[NUMBER_OF_PANELS];
    private int filePanelSelectedIndex = -1;

    private JMDiffNode diffInput; // Holds the left/right BufferDocumentIF
    private ScrollSynchronizer scrollSynchronizer;
    private JSplitPane splitPane;

    // Placeholder for an empty document, used when a side is missing during diff.
    private static BufferDocumentIF EMPTY_DOC = null;
    private static BufferDocumentIF getEmptyDoc() {
        if (EMPTY_DOC == null) {
            EMPTY_DOC = new StringDocument("", "<empty>", true); // Read-only empty doc
        }
        return EMPTY_DOC;
    }

    public BufferDiffPanel(BrokkDiffPanel mainPanel)
    {
        this(mainPanel, false);
    }

    public BufferDiffPanel(BrokkDiffPanel mainPanel, boolean isDarkTheme)
    {
        this.mainPanel = mainPanel;
        this.isDarkTheme = isDarkTheme;
        // Let the mainPanel keep a reference to us for toolbar/undo/redo interplay
        mainPanel.setBufferDiffPanel(this);
        init();
        setFocusable(true);
    }

    /**
     * Sets the input documents for the diff.
     * Automatically performs the initial diff.
     */
    public void setDiffInput(JMDiffNode diffInput)
    {
        this.diffInput = diffInput;
        refreshDocuments(); // Set documents in panels
        diff(); // Perform initial diff and display
    }

    public JMDiffNode getDiffInput()
    {
        return diffInput;
    }

    /**
     * Binds the left & right documents from the diff input to the file panels.
     */
    private void refreshDocuments()
    {
        BufferDocumentIF leftDocument = null;
        BufferDocumentIF rightDocument = null;

        if (diffInput != null) {
             leftDocument = diffInput.getDocumentLeft();
             rightDocument = diffInput.getDocumentRight();
        }

        // Set the documents into our file panels, handle nulls gracefully:
        if (filePanels[LEFT] != null) {
            filePanels[LEFT].setBufferDocument(leftDocument);
        }
        if (filePanels[RIGHT] != null) {
            filePanels[RIGHT].setBufferDocument(rightDocument);
        }
    }

    /**
     * Rerun the diff calculation using the current documents in the FilePanels.
     * Stores the result in `patch` and updates the display.
     */
    public void diff()
    {
        BufferDocumentIF leftDoc = (filePanels[LEFT] != null) ? filePanels[LEFT].getBufferDocument() : null;
        BufferDocumentIF rightDoc = (filePanels[RIGHT] != null) ? filePanels[RIGHT].getBufferDocument() : null;

        // Use placeholder empty document if a side is missing
        if (leftDoc == null) leftDoc = getEmptyDoc();
        if (rightDoc == null) rightDoc = getEmptyDoc();

        // Get line lists for diffing
        var leftLines = leftDoc.getLineList();
        var rightLines = rightDoc.getLineList();

        // Compute the diff and store it
        try {
            this.patch = DiffUtils.diff(leftLines, rightLines);
        } catch (Exception e) {
            // Handle potential exceptions from the diff library
            System.err.println("Error computing diff: " + e.getMessage());
            this.patch = new Patch<>(); // Assign an empty patch on error
        }
        this.selectedDelta = null; // Reset selection after re-diff
        reDisplay(); // Update UI highlights and curves
    }

    /**
     * Tells each FilePanel to re-apply highlights based on the current patch,
     * then repaint the parent panel and the DiffScrollComponent.
     */
    private void reDisplay()
    {
        if (filePanels != null) {
            for (var fp : filePanels) {
                if (fp != null) {
                    fp.reDisplay();
                }
            }
        }
        // Repaint the component drawing the curves
        if (splitPane != null) {
            Component centerComponent = findDiffScrollComponent(splitPane);
            if (centerComponent != null) {
                centerComponent.repaint();
            }
        }
        mainPanel.repaint(); // Repaint the main container
        mainPanel.updateUndoRedoButtons(); // Update button states
    }

    // Helper to find the DiffScrollComponent within the split pane setup
    private Component findDiffScrollComponent(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof DiffScrollComponent) {
                return comp;
            } else if (comp instanceof Container) {
                // Recurse only if it's not the component we are looking for
                Component found = findDiffScrollComponent((Container) comp);
                if (found != null) return found;
            }
        }
        return null; // Not found in this branch
    }


    public String getTitle()
    {
         if (diffInput != null && diffInput.getName() != null && !diffInput.getName().isEmpty()) {
            return diffInput.getName(); // Use the name from JMDiffNode if available
         }

        // Fallback to constructing title from documents
        var titles = new ArrayList<String>();
        for (var fp : filePanels) {
            if (fp == null) continue;
            var bd = fp.getBufferDocument();
            if (bd != null) {
                titles.add(bd.getShortName());
            } else {
                titles.add("<No Doc>");
            }
        }
        if (titles.isEmpty()) {
            return "No files";
        }
        if (titles.size() == 1) {
            return titles.get(0);
        }
        // Handle potential null short names
        String title1 = titles.get(0) != null ? titles.get(0) : "<Left>";
        String title2 = titles.get(1) != null ? titles.get(1) : "<Right>";
        if (title1.equals(title2)) {
            return title1;
        }
        return title1 + " - " + title2;
    }

    public boolean isDarkTheme()
    {
        return isDarkTheme;
    }

    /**
     * The top-level UI for the left & right file panels plus the “diff scroll component”.
     */
    private void init()
    {
        // Adjusted column layout slightly for the two panels
        var columns = "3px, pref, 3px, 0:grow, 5px, min, 60px, min, 5px, 0:grow, 3px, pref, 3px";
        var rows = "6px, pref, 3px, fill:0:grow, pref";

        setLayout(new BorderLayout());

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, activateBarDialog(), buildFilePanel(columns, rows));
        splitPane.setResizeWeight(0.05); // Give less space to the top search bar part
        add(splitPane, BorderLayout.CENTER);

        // Create the scroll synchronizer AFTER file panels are created
        if (filePanels[LEFT] != null && filePanels[RIGHT] != null) {
             scrollSynchronizer = new ScrollSynchronizer(this, filePanels[LEFT], filePanels[RIGHT]);
             setSelectedPanel(filePanels[LEFT]);
        } else {
             System.err.println("Warning: FilePanels not initialized before ScrollSynchronizer.");
        }
        mainPanel.updateUndoRedoButtons();
    }

    public JCheckBox getCaseSensitiveCheckBox()
    {
        return caseSensitiveCheckBox;
    }

    /**
     * Build the top row that holds search bars plus a "Case Sensitive" checkbox.
     */
    public JPanel activateBarDialog()
    {
        var barContainer = new JPanel(new BorderLayout());

        caseSensitiveCheckBox = new JCheckBox("Case Sensitive");
        caseSensitiveCheckBox.setFocusable(false);

        leftBar = new SearchBarDialog(mainPanel, this);
        rightBar = new SearchBarDialog(mainPanel, this);

        var leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        leftPanel.add(Box.createHorizontalStrut(5));
        leftPanel.add(leftBar);

        var topLinePanelLeft = new JPanel(new FlowLayout(FlowLayout.LEADING));
        topLinePanelLeft.add(caseSensitiveCheckBox);

        var topLinePanelDown = new JPanel(new FlowLayout(FlowLayout.CENTER));
        topLinePanelDown.add(new JLabel(""));

        var leftMostPane = new JPanel();
        leftMostPane.setLayout(new BoxLayout(leftMostPane, BoxLayout.Y_AXIS));
        leftMostPane.add(topLinePanelLeft);
        leftMostPane.add(topLinePanelDown);
        leftMostPane.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        var rightPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        rightPanel.add(rightBar);
        rightPanel.add(Box.createHorizontalStrut(5));

        barContainer.add(leftMostPane, BorderLayout.WEST);
        barContainer.add(leftPanel, BorderLayout.CENTER);
        barContainer.add(rightPanel, BorderLayout.EAST);

        // Re-run search whenever user toggles "Case Sensitive"
        caseSensitiveCheckBox.addActionListener(e -> {
            if (filePanels[LEFT] != null) filePanels[LEFT].doSearch();
            if (filePanels[RIGHT] != null) filePanels[RIGHT].doSearch();
        });

        return barContainer;
    }

    /**
     * Build the actual file-panels and the center "diff scroll curves".
     */
    private JPanel buildFilePanel(String columns, String rows)
    {
        var layout = new FormLayout(columns, rows);
        var cc = new CellConstraints();
        var panel = new JPanel(layout);

        // Create FilePanels
        filePanels[LEFT] = new FilePanel(this, BufferDocumentIF.ORIGINAL, leftBar);
        filePanels[RIGHT] = new FilePanel(this, BufferDocumentIF.REVISED, rightBar);

        // Left side revision bar
        panel.add(new RevisionBar(this, filePanels[LEFT], true), cc.xy(2, 4));
        panel.add(new JLabel(""), cc.xy(2, 2)); // for spacing

        panel.add(filePanels[LEFT].getScrollPane(), cc.xy(4, 4)); // Left panel takes column 4

        // The middle area for drawing the linking curves
        // Indices are LEFT (0) and RIGHT (1)
        var diffScrollComponent = new DiffScrollComponent(this, LEFT, RIGHT);
        panel.add(diffScrollComponent, cc.xywh(6, 4, 3, 1)); // Spans columns 6, 7, 8

        panel.add(filePanels[RIGHT].getScrollPane(), cc.xy(10, 4)); // Right panel takes column 10

        // Right side revision bar
        panel.add(new RevisionBar(this, filePanels[RIGHT], false), cc.xy(12, 4));

        panel.setMinimumSize(new Dimension(300, 200));
        return panel;
    }

    public ScrollSynchronizer getScrollSynchronizer()
    {
        return scrollSynchronizer;
    }

    public BrokkDiffPanel getMainPanel()
    {
        return mainPanel;
    }

    /**
     * Returns the current diff patch.
     */
    public Patch<String> getPatch()
    {
        return patch;
    }

    /**
     * Return whichever delta is considered “selected” in the UI.
     */
    public AbstractDelta<String> getSelectedDelta()
    {
        return selectedDelta;
    }

    /**
     * Called by `DiffScrollComponent` or `RevisionBar` to set which delta has been clicked.
     */
    public void setSelectedDelta(AbstractDelta<String> newDelta)
    {
        this.selectedDelta = newDelta;
        setSelectedLine(newDelta != null ? newDelta.getSource().getPosition() : 0);
        reDisplay(); // Repaint to show selection change
    }

    public void setSelectedLine(int line)
    {
        selectedLine = line;
    }

    public int getSelectedLine()
    {
        return selectedLine;
    }

    public FilePanel getFilePanel(int index)
    {
        if (filePanels == null || index < 0 || index >= filePanels.length) return null;
        return filePanels[index];
    }

    void setSelectedPanel(FilePanel fp)
    {
        var oldIndex = filePanelSelectedIndex;
        var newIndex = -1;
        for (int i = 0; i < filePanels.length; i++) {
            if (filePanels[i] == fp) {
                newIndex = i;
                break;
            }
        }
        if (newIndex != oldIndex) {
            if (oldIndex != -1 && filePanels[oldIndex] != null) {
                filePanels[oldIndex].setSelected(false);
            }
            filePanelSelectedIndex = newIndex;
            if (newIndex != -1 && filePanels[newIndex] != null) {
                filePanels[newIndex].setSelected(true);
            }
            mainPanel.updateUndoRedoButtons(); // Update based on newly selected panel's undo state
        }
    }

    /**
     * Called by the top-level toolbar “Next” or “Previous” or by mouse wheel in DiffScrollComponent.
     */
    public void toNextDelta(boolean next)
    {
        if (patch == null || patch.getDeltas().isEmpty()) {
            return;
        }
        var deltas = patch.getDeltas();
        AbstractDelta<String> deltaToSelect = null;

        if (selectedDelta == null) {
            // If nothing selected, pick first or last
            deltaToSelect = next ? deltas.getFirst() : deltas.getLast();
        } else {
            var idx = deltas.indexOf(selectedDelta);
            if (idx < 0) {
                // The current selection is not in the patch list (e.g., after merge), pick first/last
                 deltaToSelect = next ? deltas.getFirst() : deltas.getLast();
            } else {
                var newIdx = next ? idx + 1 : idx - 1;
                // Wrap around
                if (newIdx < 0) newIdx = deltas.size() - 1;
                if (newIdx >= deltas.size()) newIdx = 0;
                deltaToSelect = deltas.get(newIdx);
            }
        }
        setSelectedDelta(deltaToSelect);
        showSelectedDelta();
    }

    /**
     * Scroll so the selectedDelta is visible in the left panel, then the ScrollSynchronizer
     * will sync the right side.
     */
    private void showSelectedDelta()
    {
        if (selectedDelta == null || scrollSynchronizer == null) return;
        scrollSynchronizer.showDelta(selectedDelta);
    }

    /**
     * The “change” operation from left->right or right->left.
     */
    public void runChange(int fromPanelIndex, int toPanelIndex, boolean shift)
    {
        var delta = getSelectedDelta();
        if (delta == null) return;

        var fromFilePanel = filePanels[fromPanelIndex];
        var toFilePanel = filePanels[toPanelIndex];
        if (fromFilePanel == null || toFilePanel == null) return;

        var fromDoc = fromFilePanel.getBufferDocument();
        var toDoc = toFilePanel.getBufferDocument();
        if (fromDoc == null || toDoc == null || toDoc.isReadonly()) return; // Check if target is readonly

        // Decide which side is "source" vs "target" chunk based on indices
        var sourceChunk = (fromPanelIndex == LEFT) ? delta.getSource() : delta.getTarget();
        var targetChunk = (fromPanelIndex == LEFT) ? delta.getTarget() : delta.getSource();

        try {
            // Get text to insert from the source document
            var fromPlainDoc = fromDoc.getDocument();
            var sourceStartOffset = fromDoc.getOffsetForLine(sourceChunk.getPosition());
            var sourceEndOffset = fromDoc.getOffsetForLine(sourceChunk.getPosition() + sourceChunk.size());
            if (sourceStartOffset < 0 || sourceEndOffset < 0) return; // Invalid source offsets
            var replacedText = fromPlainDoc.getText(sourceStartOffset, sourceEndOffset - sourceStartOffset);

            // Determine offsets in the target document
            var toPlainDoc = toDoc.getDocument();
            var targetStartOffset = toDoc.getOffsetForLine(targetChunk.getPosition());
            var targetEndOffset = toDoc.getOffsetForLine(targetChunk.getPosition() + targetChunk.size());
            if (targetStartOffset < 0 || targetEndOffset < 0) return; // Invalid target offsets

            // Perform the replacement/insertion in the target document
            getUndoHandler().start("Apply Change"); // Group edits for undo
            if (!shift) {
                // Replace the target chunk's content
                toPlainDoc.remove(targetStartOffset, targetEndOffset - targetStartOffset);
                toPlainDoc.insertString(targetStartOffset, replacedText, null);
            } else {
                // Append after the target chunk
                toPlainDoc.insertString(targetEndOffset, replacedText, null);
            }
            getUndoHandler().end("Apply Change");

            setSelectedDelta(null);
            setSelectedLine(targetChunk.getPosition()); // Select target line after change

            // Re-diff and display
            diff(); // Re-calculates patch
            doSave(); // Save changes

        } catch (BadLocationException ex) {
            getUndoHandler().end("Apply Change"); // Ensure undo group is closed on error
            throw new RuntimeException("Error applying change operation", ex);
        }
    }

    /**
     * The “delete” operation: remove the chunk corresponding to the selected delta
     * from the specified panel index side.
     */
    public void runDelete(int panelIndexToDeleteFrom, int otherPanelIndex) {
        var delta = getSelectedDelta();
        if (delta == null) return;

        var filePanelToDelete = filePanels[panelIndexToDeleteFrom];
        if (filePanelToDelete == null) return;

        var docToDelete = filePanelToDelete.getBufferDocument();
        if (docToDelete == null || docToDelete.isReadonly()) return; // Check if readonly

        // Get the chunk corresponding to the panel we are deleting from
        var chunkToDelete = (panelIndexToDeleteFrom == LEFT) ? delta.getSource() : delta.getTarget();

        var fromLine = chunkToDelete.getPosition();
        var size = chunkToDelete.size();
        if (size == 0) return; // Cannot delete zero lines

        var fromOffset = docToDelete.getOffsetForLine(fromLine);
        var toOffset = docToDelete.getOffsetForLine(fromLine + size);
        if (fromOffset < 0 || toOffset < 0 || toOffset < fromOffset) return; // Invalid offsets

        try {
             var plainDocToDelete = docToDelete.getDocument();
             getUndoHandler().start("Delete Chunk");
             plainDocToDelete.remove(fromOffset, toOffset - fromOffset);
             getUndoHandler().end("Delete Chunk");

             setSelectedDelta(null);
             setSelectedLine(chunkToDelete.getPosition()); // Keep line selection near deletion point

             // Re-diff and display
             diff(); // Re-calculates patch
             doSave(); // Save changes

        } catch (BadLocationException ex) {
             getUndoHandler().end("Delete Chunk");
            throw new RuntimeException("Error applying delete operation", ex);
        }
    }

    /**
     * Writes out any changed documents to disk. Typically invoked after applying changes or undo/redo.
     */
    public void doSave()
    {
        for (var fp : filePanels) {
            if (fp == null) continue;
            var doc = fp.getBufferDocument();
            if (doc == null || !doc.isChanged() || doc.isReadonly()) continue;
            try {
                doc.write(); // write() now resets the changed flag internally
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(mainPanel,
                                              "Can't save file: " + doc.getName() + "\n" + ex.getMessage(),
                                              "Problem writing file", JOptionPane.ERROR_MESSAGE);
            }
        }
        reDisplay(); // Refresh UI after save (might clear changed status indicators)
    }

    /**
     * The “down arrow” in the toolbar calls doDown().
     */
    @Override
    public void doDown()
    {
        toNextDelta(true);
    }

    /**
     * The “up arrow” in the toolbar calls doUp().
     */
    @Override
    public void doUp()
    {
        toNextDelta(false);
    }

    @Override
    public void doUndo()
    {
        if (getUndoHandler().canUndo()) {
            getUndoHandler().undo();
            diff(); // Re-diff after undo
            doSave(); // Save the undone state
        } else {
             System.out.println("Cannot undo");
        }
    }

    @Override
    public void doRedo()
    {
         if (getUndoHandler().canRedo()) {
            getUndoHandler().redo();
            diff(); // Re-diff after redo
            doSave(); // Save the redone state
         } else {
              System.out.println("Cannot redo");
         }
    }

     // Override getUndoHandler to provide access to the currently focused panel's undo manager
     @Override
     public MyUndoManager getUndoHandler() {
         FilePanel selectedPanel = getFilePanel(filePanelSelectedIndex);
         if (selectedPanel != null && selectedPanel.getBufferDocument() != null) {
             // Return the undo manager associated with the selected panel's document.
             // Assumes the document's undo listener is correctly wired to this manager.
             return super.getUndoHandler(); // Return the panel's shared undo manager
         }
         // Fallback or default behavior if no panel is selected or doc is null
         return super.getUndoHandler();
     }
}
