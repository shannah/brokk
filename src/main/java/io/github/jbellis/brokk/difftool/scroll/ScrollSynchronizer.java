package io.github.jbellis.brokk.difftool.scroll;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.FilePanel;

import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.AdjustmentListener;

/**
 * Synchronizes the vertical/horizontal scrolling between the left and right FilePanel.
 * Also provides small utility methods for scrolling to line or to a specific delta.
 */
public class ScrollSynchronizer
{
    private final BufferDiffPanel diffPanel;
    private final FilePanel filePanelLeft;
    private final FilePanel filePanelRight;

    private AdjustmentListener horizontalAdjustmentListener;
    private AdjustmentListener verticalAdjustmentListener;

    // Flag to prevent recursive scroll events
    private boolean insideScroll = false;

    public ScrollSynchronizer(BufferDiffPanel diffPanel, FilePanel filePanelLeft, FilePanel filePanelRight)
    {
        this.diffPanel = diffPanel;
        this.filePanelLeft = filePanelLeft;
        this.filePanelRight = filePanelRight;
        init();
    }

    private void init()
    {
        if (filePanelLeft == null || filePanelRight == null) {
            System.err.println("ScrollSynchronizer init failed: FilePanels are null.");
            return;
        }
        // Sync horizontal:
        var barLeftH = filePanelLeft.getScrollPane().getHorizontalScrollBar();
        var barRightH = filePanelRight.getScrollPane().getHorizontalScrollBar();
        barRightH.addAdjustmentListener(getHorizontalAdjustmentListener());
        barLeftH.addAdjustmentListener(getHorizontalAdjustmentListener());

        // Sync vertical:
        var barLeftV = filePanelLeft.getScrollPane().getVerticalScrollBar();
        var barRightV = filePanelRight.getScrollPane().getVerticalScrollBar();
        barRightV.addAdjustmentListener(getVerticalAdjustmentListener());
        barLeftV.addAdjustmentListener(getVerticalAdjustmentListener());
    }

    private AdjustmentListener getHorizontalAdjustmentListener()
    {
        if (horizontalAdjustmentListener == null) {
            horizontalAdjustmentListener = e -> {
                if (insideScroll) return;
                if (filePanelLeft == null || filePanelRight == null) return;

                var leftH = filePanelLeft.getScrollPane().getHorizontalScrollBar();
                var rightH = filePanelRight.getScrollPane().getHorizontalScrollBar();
                var scFrom = (e.getSource() == leftH ? leftH : rightH);
                var scTo = (scFrom == leftH ? rightH : leftH);

                insideScroll = true;
                try {
                    scTo.setValue(scFrom.getValue());
                } finally {
                    insideScroll = false;
                }
            };
        }
        return horizontalAdjustmentListener;
    }

    private AdjustmentListener getVerticalAdjustmentListener()
    {
        if (verticalAdjustmentListener == null) {
            verticalAdjustmentListener = e -> {
                if (insideScroll) return;
                if (filePanelLeft == null || filePanelRight == null) return;

                var leftV = filePanelLeft.getScrollPane().getVerticalScrollBar();
                boolean leftScrolled = (e.getSource() == leftV);
                insideScroll = true;
                try {
                    scroll(leftScrolled);
                } finally {
                    insideScroll = false;
                }
            };
        }
        return verticalAdjustmentListener;
    }

    /**
     * If the left side scrolled, we compute which line is centered and map it to
     * the equivalent line in the right side. If the right side scrolled, we do the reverse.
     */
    private void scroll(boolean leftScrolled)
    {
        var patch = diffPanel.getPatch();
        if (patch == null) {
            return; // No diff info, cannot sync based on lines
        }
        var fp1 = leftScrolled ? filePanelLeft : filePanelRight;
        var fp2 = leftScrolled ? filePanelRight : filePanelLeft;

        if (fp1 == null || fp2 == null) return;

        // Which line is roughly in the center of fp1?
        int line = getCurrentLineCenter(fp1);
        if (line < 0) return; // Could not determine center line

        // Use the patch to map the line number from one side to the other
        int mappedLine = approximateLineMapping(patch, line, leftScrolled);

        // Scroll the other panel to the mapped line
        scrollToLine(fp2, mappedLine);
    }

    /**
     * Basic approximation of line mapping based on the diff patch:
     * If fromOriginal==true, `line` is from the original side, we apply deltas up to that line
     * to see how many lines were inserted or removed, producing a revised line index.
     * If false, we do the reverse (map revised line to original).
     */
    private int approximateLineMapping(com.github.difflib.patch.Patch<String> patch, int line, boolean fromOriginal)
    {
        int currentOriginalLine = 0;
        int currentRevisedLine = 0;
        int targetLine = -1;

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            Chunk<String> source = delta.getSource(); // original chunk
            Chunk<String> target = delta.getTarget(); // revised chunk

            int sourceStart = source.getPosition();
            int sourceSize = source.size();
            int targetStart = target.getPosition();
            int targetSize = target.size();

            // Lines before this delta are equal
            int equalLines = (fromOriginal ? sourceStart : targetStart) - (fromOriginal ? currentOriginalLine : currentRevisedLine);
            if (equalLines > 0) {
                if (fromOriginal) {
                    if (line >= currentOriginalLine && line < currentOriginalLine + equalLines) {
                        targetLine = currentRevisedLine + (line - currentOriginalLine);
                        break;
                    }
                } else {
                    if (line >= currentRevisedLine && line < currentRevisedLine + equalLines) {
                        targetLine = currentOriginalLine + (line - currentRevisedLine);
                        break;
                    }
                }
                currentOriginalLine += equalLines;
                currentRevisedLine += equalLines;
            }

            // Check if the line falls within this delta
            if (fromOriginal) {
                if (line >= sourceStart && line < sourceStart + sourceSize) {
                    // If line is in original chunk, map it to the start of the target chunk
                    targetLine = targetStart;
                    break;
                }
            } else {
                if (line >= targetStart && line < targetStart + targetSize) {
                    // If line is in revised chunk, map it to the start of the original chunk
                    targetLine = sourceStart;
                    break;
                }
            }

            // Advance line counters past this delta
            currentOriginalLine = sourceStart + sourceSize;
            currentRevisedLine = targetStart + targetSize;
        }

        // If the line is after all deltas
        if (targetLine == -1) {
            if (fromOriginal) {
                targetLine = currentRevisedLine + (line - currentOriginalLine);
            } else {
                targetLine = currentOriginalLine + (line - currentRevisedLine);
            }
        }

        // Ensure the mapped line is non-negative
        return Math.max(0, targetLine);
    }


    /**
     * Determine which line is in the vertical center of the FilePanel's visible region.
     * Returns -1 if unable to determine.
     */
    private int getCurrentLineCenter(FilePanel fp)
    {
        if (fp == null) return -1;
        var editor = fp.getEditor();
        var viewport = fp.getScrollPane().getViewport();
        var viewPos = viewport.getViewPosition();
        var viewSize = viewport.getSize();

        // Calculate center Y coordinate relative to the editor component
        int centerY = viewPos.y + viewSize.height / 2;
        Point centerPoint = new Point(0, centerY); // X doesn't matter for line num

        int offset = editor.viewToModel2D(centerPoint);
        if (offset < 0) {
             // Try edge cases if center fails
             offset = editor.viewToModel2D(new Point(0, viewPos.y)); // Top edge
             if (offset < 0) offset = editor.viewToModel2D(new Point(0, viewPos.y + viewSize.height - 1)); // Bottom edge
             if (offset < 0) return -1; // Still couldn't find a valid offset
        }

        var bd = fp.getBufferDocument();
        if (bd == null) return -1;
        return bd.getLineForOffset(offset);
    }

    /**
     * Scrolls the given FilePanel so that the specified line is roughly centered vertically.
     */
    public void scrollToLine(FilePanel fp, int line)
    {
        if (fp == null || line < 0) return;

        var bd = fp.getBufferDocument();
        if (bd == null) return;

        var offset = bd.getOffsetForLine(line);
        if (offset < 0) {
             // Try to scroll to end if line is beyond last line
             if (line >= bd.getNumberOfLines()) {
                  offset = bd.getDocument().getLength();
             } else {
                  System.err.println("ScrollToLine: Invalid offset for line " + line);
                  return;
             }
        }

        var viewport = fp.getScrollPane().getViewport();
        var editor = fp.getEditor();
        try {
            // Get the rectangle for the target offset
            Rectangle rect = editor.modelToView(offset);
            if (rect == null) {
                // Fallback if modelToView fails for the offset
                rect = editor.modelToView(bd.getOffsetForLine(Math.max(0, line - 1)));
                if (rect != null) rect.y += editor.getFontMetrics(editor.getFont()).getHeight(); // Estimate position
                else return; // Cannot get view rect
            }

            // Calculate desired view position to center the line
            int viewHeight = viewport.getHeight();
            int desiredY = rect.y - (viewHeight / 2) + (rect.height / 2);
            desiredY = Math.max(0, desiredY); // Don't scroll past the top

            // Ensure we don't scroll past the bottom
            int maxViewY = Math.max(0, editor.getHeight() - viewHeight);
            desiredY = Math.min(desiredY, maxViewY);

            // Create the new view position point
            Point p = new Point(viewport.getViewPosition().x, desiredY);

            // Set the new view position (this will trigger adjustment listeners if changed)
            viewport.setViewPosition(p);

        } catch (BadLocationException ex) {
            // This usually means the offset is invalid for the document model
            System.err.println("ScrollToLine Error: Bad location for offset " + offset + " in line " + line + ": " + ex.getMessage());
            // Consider alternative scrolling, e.g., using JTextArea.setCaretPosition and ensureRectVisible
            // editor.setCaretPosition(offset);
            // editor.scrollRectToVisible(new Rectangle(0, editor.getCaret().getMagicCaretPosition().y, 1, viewport.getHeight()));
        } catch (Exception e) {
             System.err.println("ScrollToLine Error: Unexpected exception: " + e.getMessage());
        }
    }

    /**
     * Called by BufferDiffPanel when the user picks a specific delta.
     * We attempt to show the original chunk in the left side, then scroll the right side.
     */
    public void showDelta(AbstractDelta<String> delta)
    {
        if (delta == null || filePanelLeft == null || filePanelRight == null) return;
        // We assume we want to scroll based on the 'source' chunk (original side).
        var source = delta.getSource();
        int lineToScroll = source.getPosition();

        // Scroll the left panel first
        scrollToLine(filePanelLeft, lineToScroll);

        // The vertical adjustment listener should automatically sync the right panel
        // based on the scroll of the left panel.
    }

    // Removed toNextDelta - logic moved to BufferDiffPanel
}
