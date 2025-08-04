package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoableEdit;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom undoable edit that handles both document text changes and patch state changes
 * when applying chunks via runChange or runDelete operations.
 *
 * This ensures that undo/redo operations maintain consistency between document content
 * and the diff patch state.
 */
public class ChunkApplicationEdit extends AbstractUndoableEdit {
    private static final Logger logger = LogManager.getLogger(ChunkApplicationEdit.class);

    private final BufferDiffPanel diffPanel;
    private final AbstractDelta<String> appliedDelta;
    private final List<UndoableEdit> documentEdits;
    @Nullable
    private final Patch<String> patchSnapshot;
    @Nullable
    private final AbstractDelta<String> selectedDeltaSnapshot;
    private final String operationType;

    /**
     * Creates a new chunk application edit that can be undone/redone.
     *
     * @param diffPanel the diff panel where the operation occurred
     * @param appliedDelta the delta that was applied
     * @param documentEdits the document edits that occurred during application
     * @param patchSnapshot snapshot of patch state before the operation
     * @param selectedDeltaSnapshot the selected delta before the operation
     * @param operationType description of the operation (e.g., "Apply Change", "Delete Chunk")
     */
    public ChunkApplicationEdit(BufferDiffPanel diffPanel,
                               AbstractDelta<String> appliedDelta,
                               List<UndoableEdit> documentEdits,
                               @Nullable Patch<String> patchSnapshot,
                               @Nullable AbstractDelta<String> selectedDeltaSnapshot,
                               String operationType) {
        this.diffPanel = diffPanel;
        this.appliedDelta = appliedDelta;
        this.documentEdits = new ArrayList<>(documentEdits);
        this.patchSnapshot = patchSnapshot;
        this.selectedDeltaSnapshot = selectedDeltaSnapshot;
        this.operationType = operationType;
    }

    @Override
    public void undo() throws CannotUndoException {
        super.undo();
        logger.debug("Undoing chunk application: {}", operationType);

        try {
            // First, undo all document changes in reverse order
            for (int i = documentEdits.size() - 1; i >= 0; i--) {
                var edit = documentEdits.get(i);
                if (edit.canUndo()) {
                    edit.undo();
                }
            }

            // Restore the patch state by re-adding the delta that was removed
            var currentPatch = diffPanel.getPatch();
            if (currentPatch != null && !currentPatch.getDeltas().contains(appliedDelta)) {
                // Verify the delta should be restored using the snapshot if available
                boolean shouldRestore = patchSnapshot == null || patchSnapshot.getDeltas().contains(appliedDelta);
                if (shouldRestore) {
                    // Find the correct position to insert the delta back
                    insertDeltaInCorrectPosition(currentPatch, appliedDelta);
                }
            }

            // Restore selected delta state
            diffPanel.setSelectedDelta(selectedDeltaSnapshot);

            // Check if any documents have returned to their original saved state
            recheckDocumentStates();

            // Refresh the UI to reflect the restored state
            diffPanel.diff(false); // Don't auto-scroll during undo

            // Update save status to reflect the reverted state
            diffPanel.recalcDirty();

        } catch (Exception e) {
            logger.error("Failed to undo chunk application", e);
            throw new CannotUndoException();
        }
    }

    @Override
    public void redo() throws CannotRedoException {
        super.redo();
        logger.debug("Redoing chunk application: {}", operationType);

        try {
            // Redo all document changes
            for (var edit : documentEdits) {
                if (edit.canRedo()) {
                    edit.redo();
                }
            }

            // Remove the delta from patch again (simulate reapplication)
            var currentPatch = diffPanel.getPatch();
            if (currentPatch != null && currentPatch.getDeltas().contains(appliedDelta)) {
                currentPatch.getDeltas().remove(appliedDelta);
            }

            // Check if any documents have returned to their original saved state
            recheckDocumentStates();

            // Refresh the UI
            diffPanel.diff(false); // Don't auto-scroll during redo

            // Update save status to reflect the reapplied state
            diffPanel.recalcDirty();

        } catch (Exception e) {
            logger.error("Failed to redo chunk application", e);
            throw new CannotRedoException();
        }
    }

    @Override
    public boolean canUndo() {
        return super.canUndo() && documentEdits.stream().allMatch(UndoableEdit::canUndo);
    }

    @Override
    public boolean canRedo() {
        return super.canRedo() && documentEdits.stream().allMatch(UndoableEdit::canRedo);
    }

    @Override
    public String getPresentationName() {
        return operationType;
    }

    /**
     * Insert the delta back into the patch at the correct position based on line numbers.
     * This maintains the sorted order of deltas in the patch.
     */
    private void insertDeltaInCorrectPosition(Patch<String> patch, AbstractDelta<String> delta) {
        var deltas = patch.getDeltas();
        int insertPosition = 0;

        // Find the correct insertion point to maintain line number ordering
        for (int i = 0; i < deltas.size(); i++) {
            if (deltas.get(i).getSource().getPosition() > delta.getSource().getPosition()) {
                insertPosition = i;
                break;
            }
            insertPosition = i + 1;
        }

        deltas.add(insertPosition, delta);
    }

    /**
     * Check all file panel documents to see if they have returned to their original saved state.
     * If so, reset their changed flags accordingly.
     */
    private void recheckDocumentStates() {
        // Check both left and right file panels
        for (var side : BufferDiffPanel.PanelSide.values()) {
            var filePanel = diffPanel.getFilePanel(side);
            if (filePanel != null) {
                var bufferDoc = filePanel.getBufferDocument();
                if (bufferDoc != null) {
                    bufferDoc.recheckChangedState();
                }
            }
        }
    }
}