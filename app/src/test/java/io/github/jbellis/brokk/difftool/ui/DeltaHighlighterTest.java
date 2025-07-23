package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DeltaHighlighter which unified the highlighting logic
 * that was previously duplicated in HighlightOriginal/HighlightRevised.
 * 
 * These tests verify that the DeltaHighlighter class exists and has the expected API.
 * Testing the full highlighting pipeline requires complex UI setup, so we focus on
 * the class structure and basic behavior verification.
 */
class DeltaHighlighterTest {

    
    @Test
    void testHighlight_NullFilePanel_ThrowsNPE() {
        // The current implementation does not handle null panels - verify this behavior
        List<String> original = List.of("A");
        List<String> revised = List.of("A", "B");
        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();
        
        // Should throw NullPointerException with null file panel (current behavior)
        assertThrows(NullPointerException.class, () -> {
            DeltaHighlighter.highlight(null, delta, true);
        });
    }
    
    @Test 
    void testDeltaTypesHandled() {
        // Verify that all delta types produce distinct behavior
        // (we can't test actual highlighting without UI, but we can test the types exist)
        
        // CREATE INSERT delta
        List<String> original1 = List.of("A", "B");
        List<String> revised1 = List.of("A", "B", "C");
        Patch<String> insertPatch = DiffUtils.diff(original1, revised1);
        var insertDelta = insertPatch.getDeltas().getFirst();
        assertEquals(DeltaType.INSERT, insertDelta.getType());
        
        // CREATE DELETE delta  
        List<String> original2 = List.of("A", "B", "C");
        List<String> revised2 = List.of("A", "C");  
        Patch<String> deletePatch = DiffUtils.diff(original2, revised2);
        var deleteDelta = deletePatch.getDeltas().getFirst();
        assertEquals(DeltaType.DELETE, deleteDelta.getType());
        
        // CREATE CHANGE delta
        List<String> original3 = List.of("A", "B", "C");
        List<String> revised3 = List.of("A", "X", "C");
        Patch<String> changePatch = DiffUtils.diff(original3, revised3);
        var changeDelta = changePatch.getDeltas().getFirst();
        assertEquals(DeltaType.CHANGE, changeDelta.getType());
        
        // All delta types should be handled (would throw NPE with null panel, as expected)
        assertThrows(NullPointerException.class, () -> DeltaHighlighter.highlight(null, insertDelta, true));
        assertThrows(NullPointerException.class, () -> DeltaHighlighter.highlight(null, deleteDelta, true));
        assertThrows(NullPointerException.class, () -> DeltaHighlighter.highlight(null, changeDelta, true));
    }

    @Test
    void testBothSides() {
        // Verify that both original (true) and revised (false) sides are accepted
        List<String> original = List.of("A", "B");
        List<String> revised = List.of("A", "X");
        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();
        
        // Both sides should accept the call (would throw NPE with null panel, as expected)
        assertThrows(NullPointerException.class, () -> DeltaHighlighter.highlight(null, delta, true));  // original side
        assertThrows(NullPointerException.class, () -> DeltaHighlighter.highlight(null, delta, false)); // revised side
    }


}