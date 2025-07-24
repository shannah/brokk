package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.DeltaType;
import io.github.jbellis.brokk.difftool.utils.Colors;
import org.jetbrains.annotations.NotNull;

import javax.swing.text.Highlighter;

/**
 * Factory for creating appropriate highlight painters based on delta type and state.
 * Centralizes the color and painter selection logic that was previously duplicated
 * in HighlightOriginal and HighlightRevised.
 */
public final class DiffPainterFactory {
    
    private DiffPainterFactory() {} // Utility class
    
    /**
     * Creates the appropriate painter for a delta based on its type and characteristics.
     * 
     * @param type The delta type (INSERT, DELETE, CHANGE)
     * @param isDark Whether dark theme is active
     * @param isEmpty Whether the chunk is empty (size 0)
     * @param isEndAndNewline Whether the chunk ends at document end with a newline
     * @return The appropriate highlight painter
     */
    public static @NotNull Highlighter.HighlightPainter create(@NotNull DeltaType type,
                                                               boolean isDark,
                                                               boolean isEmpty,
                                                               boolean isEndAndNewline) {
        return switch (type) {
            case INSERT -> createInsertPainter(isDark, isEmpty, isEndAndNewline);
            case DELETE -> createDeletePainter(isDark, isEmpty, isEndAndNewline);
            case CHANGE -> createChangePainter(isDark, isEndAndNewline);
            case EQUAL -> throw new IllegalArgumentException("EQUAL deltas should not be highlighted");
        };
    }
    
    private static @NotNull Highlighter.HighlightPainter createInsertPainter(boolean isDark,
                                                                             boolean isEmpty,
                                                                             boolean isEndAndNewline) {
        var color = Colors.getAdded(isDark);
        
        if (isEmpty) {
            return new JMHighlightPainter.JMHighlightLinePainter(color);
        } else if (isEndAndNewline) {
            return new JMHighlightPainter.JMHighlightNewLinePainter(color);
        } else {
            return new JMHighlightPainter(color);
        }
    }
    
    private static @NotNull Highlighter.HighlightPainter createDeletePainter(boolean isDark,
                                                                             boolean isEmpty,
                                                                             boolean isEndAndNewline) {
        var color = Colors.getDeleted(isDark);
        
        if (isEmpty) {
            return new JMHighlightPainter.JMHighlightLinePainter(color);
        } else if (isEndAndNewline) {
            return new JMHighlightPainter.JMHighlightNewLinePainter(color);
        } else {
            return new JMHighlightPainter(color);
        }
    }
    
    private static @NotNull Highlighter.HighlightPainter createChangePainter(boolean isDark,
                                                                             boolean isEndAndNewline) {
        var color = Colors.getChanged(isDark);
        
        if (isEndAndNewline) {
            return new JMHighlightPainter.JMHighlightNewLinePainter(color);
        } else {
            return new JMHighlightPainter(color);
        }
    }
}
