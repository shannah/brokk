package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.DeltaType;
import org.junit.jupiter.api.Test;

import javax.swing.text.Highlighter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DiffPainterFactory which centralizes the painter selection logic
 * that was previously duplicated in highlighting classes.
 */
class DiffPainterFactoryTest {

    @Test
    void testCreate_EqualDeltaType() {
        // EQUAL deltas should not be highlighted - should throw exception
        assertThrows(IllegalArgumentException.class, () -> {
            DiffPainterFactory.create(DeltaType.EQUAL, false, false, false);
        });
    }
    
    @Test
    void testCreate_AllCombinations() {
        // Test all valid combinations don't throw exceptions
        DeltaType[] types = {DeltaType.INSERT, DeltaType.DELETE, DeltaType.CHANGE};
        boolean[] darkValues = {false, true};
        boolean[] emptyValues = {false, true};
        boolean[] newlineValues = {false, true};
        
        for (DeltaType type : types) {
            for (boolean isDark : darkValues) {
                for (boolean isEmpty : emptyValues) {
                    for (boolean isEndAndNewline : newlineValues) {
                        assertDoesNotThrow(() -> {
                            Highlighter.HighlightPainter painter = DiffPainterFactory.create(
                                type, isDark, isEmpty, isEndAndNewline);
                            assertNotNull(painter, 
                                String.format("Painter should not be null for %s, dark=%s, empty=%s, newline=%s",
                                    type, isDark, isEmpty, isEndAndNewline));
                        });
                    }
                }
            }
        }
    }
}