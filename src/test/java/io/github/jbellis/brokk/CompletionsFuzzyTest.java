package io.github.jbellis.brokk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit‑tests for {@link Completions.FuzzyMatcher}.
 * <p>
 * The tests focus on tricky, real‑world completion scenarios:
 * <ul>
 *   <li>Plain subsequence matches (“abc” ⇒ “aXbYc”).</li>
 *   <li>Camel‑hump abbreviations (“cman” ⇒ “ContextManager”).</li>
 *   <li>Case‑insensitive matching (“http” ⇒ “HttpClient”).</li>
 *   <li>Non‑matches returning {@code Integer.MAX_VALUE}.</li>
 *   <li>Relative scoring – “tighter” matches must score better.</li>
 * </ul>
 */
class CompletionsFuzzyTest {

    @Test
    @DisplayName("Exact match scores better than a match containing gaps")
    void exactMatchOutranksGappyMatch() {
        var matcher = new Completions.FuzzyMatcher("foo");

        int scoreExact = matcher.score("foo");
        int scoreWithGaps = matcher.score("f_o_o_bar");

        assertTrue(scoreExact < scoreWithGaps, "Exact match should have a lower (better) score than one with gaps");
    }

    @Test
    @DisplayName("Subsequence with small gaps outranks one with large gaps")
    void smallGapOutranksLargeGap() {
        var matcher = new Completions.FuzzyMatcher("abc");

        int scoreSmallGap = matcher.score("a_bc");        // minimal gap between b and c
        int scoreLargeGap = matcher.score("a___b___c");   // large gaps everywhere

        assertTrue(scoreSmallGap < scoreLargeGap,
                   "Fewer skipped characters should yield a better score");
    }

    @Test
    @DisplayName("Camel‑hump abbreviation matches (cman → ContextManager)")
    void camelHumpAbbreviationMatches() {
        var matcher = new Completions.FuzzyMatcher("cman");

        assertTrue(matcher.matches("ContextManager"),
                   "'cman' should match 'ContextManager'");
    }

    @Test
    @DisplayName("Abbreviation prefers tighter camel‑hump match")
    void tighterCamelHumpScoresBetter() {
        var matcher = new Completions.FuzzyMatcher("cman");

        int scoreContextManager = matcher.score("ContextManager"); // C…M…a…n
        int scoreLongPath     = matcher.score("src/main/java/io/github/jbellis/brokk/difftool/doc/AbstractBufferDocument.java ");

        assertTrue(scoreContextManager < scoreLongPath, "More compact camel‑hump match should score better");
    }

    @Test
    @DisplayName("Case‑insensitive matching works (HTTP → HttpClient)")
    void caseInsensitiveMatch() {
        var matcher = new Completions.FuzzyMatcher("HTTP");

        assertTrue(matcher.matches("HttpClient"),
                   "Matching should be case‑insensitive");
    }

    @Test
    @DisplayName("Non‑matching pattern returns Integer.MAX_VALUE")
    void nonMatchReturnsMaxValue() {
        var matcher = new Completions.FuzzyMatcher("xyz");

        int score = matcher.score("alphabet");

        assertEquals(Integer.MAX_VALUE, score,
                     "Score must be MAX_VALUE when no match is possible");
    }

    @Test
    @DisplayName("Leading and trailing whitespace in pattern is ignored")
    void trimsWhitespaceInPattern() {
        var matcher = new Completions.FuzzyMatcher("  cli  ");

        assertTrue(matcher.matches("CommandLineInterface"),
                   "Pattern should be trimmed before matching");
    }
}
