package io.github.jbellis.brokk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the individual static methods of {@link EditBlock} to pinpoint
 * where behavior diverges from expectations in multi-line replacements
 * with leading blank lines and indentation.
 */
class EditBlockInternalsTest {
    @Test
    void testCountLeadingBlankLines() {
        // 1. No blank lines at start
        String[] lines1 = {
                "line1",
                "line2",
                ""
        };
        assertEquals(0, EditBlock.countLeadingBlankLines(lines1));

        // 2. Some blank lines at start
        String[] lines2 = {
                "",
                "  ",
                "lineA",
                "lineB"
        };
        // Two blank lines at start
        assertEquals(2, EditBlock.countLeadingBlankLines(lines2));
    }

    @Test
    void testCountLeadingSpaces() {
        // Simple checks
        assertEquals("", EditBlock.getLeadingWhitespace("line1\n"));
        assertEquals("    ", EditBlock.getLeadingWhitespace("    line2\n"));
        assertEquals("  ", EditBlock.getLeadingWhitespace("  lineX \n"));
    }

    @Test
    void testAdjustIndentation() {
        // delta < 0 => add spaces
        String line = "hello\n";
        String adjusted = EditBlock.adjustIndentation(line, "  ", "");
        assertEquals("  hello\n", adjusted);

        // delta > 0 => remove spaces (if present)
        String line2 = "    hello\n";
        String adjusted2 = EditBlock.adjustIndentation(line2, "  ", "    "); // remove 2 spaces
        assertEquals("  hello\n", adjusted2);

        // ensure we never go below zero indentation
        String line3 = "  hi\n";
        String adjusted3 = EditBlock.adjustIndentation(line3, "  ", "          "); // tries to remove 10, but has only 2
        assertEquals("hi\n", adjusted3);
    }

    @Test
    void testMatchesIgnoringWhitespace() {
        String[] whole = {
                "    line1\n",
                "        line2\n"
        };
        String[] part = {
                "line1\n",
                "    line2\n"
        };
        // We expect a match ignoring leading spaces
        assertTrue(EditBlock.matchesIgnoringWhitespace(whole, 0, part));

        // If we shift by 1, out of range => false
        assertFalse(EditBlock.matchesIgnoringWhitespace(whole, 1, part));
    }

    @Test
    void testPerfectReplace() {
        String[] whole = { "A\n", "B\n", "C\n" };
        String[] part  = { "B\n" };
        String[] repl  = { "B-REPLACED\n" };

        String result = EditBlock.perfectReplace(whole, part, repl);
        assertNotNull(result);

        // Expect "A\n" + "B-REPLACED\n" + "C\n"
        assertEquals("A\nB-REPLACED\nC\n", result);
    }

    @Test
    void testReplaceIgnoringWhitespace_includingBlankLine() {
        // This is closer to the scenario that breaks in your test:
        // There's an extra blank line in 'search' that doesn't appear in the original.
        String[] whole = {
                "line1\n",
                "    line2\n",
                "    line3\n"
        };
        String[] part = {
                "\n",        // blank line
                "  line2\n"  // partial indentation
        };
        String[] replace = {
                "\n",          // blank line
                "  replaced_line2\n"
        };

        String attempt = EditBlock.replaceIgnoringWhitespace(whole, part, replace);

        // We'll see if it aligns or if we get an extra blank line in between
        // (which might be the bug).
        assertNotNull(attempt);

        // If we want "line2" to become "replaced_line2" with the same indentation as line2 originally had:
        // We expect:
        // line1
        //     replaced_line2
        //     line3
        String expected = """
                line1
                    replaced_line2
                    line3
                """;
        assertEquals(expected, attempt);
    }

    @Test
    void testReplaceMostSimilarChunk() {
        String whole = """
                line1
                    line2
                    line3
                """;
        String part = "\n  line2\n";
        String replace = "\n  replaced_line2\n";

        String result = EditBlock.replaceMostSimilarChunk(whole, part, replace);

        // We'll see if an extra blank line got inserted.
        // We'll check the final lines carefully.
        String expected = """
                line1
                    replaced_line2
                    line3
                """;
        assertEquals(expected, result);
    }

    @Test
    void testDoReplaceWithBlankLineAndIndent() {
        // "doReplace" is a higher-level method that calls stripQuotedWrapping + replaceMostSimilarChunk, etc.
        String original = """
                line1
                    line2
                    line3
                """;
        String before = "\n  line2\n";
        String after = "\n  replaced_line2\n";

        String result = EditBlock.doReplace(original, before, after);

        String expected = """
                line1
                    replaced_line2
                    line3
                """;
        assertEquals(expected, result);
    }
}
