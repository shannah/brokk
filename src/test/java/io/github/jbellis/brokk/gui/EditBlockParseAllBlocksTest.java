package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.EditBlock.OutputBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditBlockParseAllBlocksTest {
    @Test
    void testParseEmptyString() {
        var result = EditBlock.parseAllBlocks("").blocks();
        assertEquals(0, result.size());
    }

    @Test
    void testParsePlainTextOnly() {
        String input = "This is just plain text.";
        var result = EditBlock.parseAllBlocks(input).blocks();
        assertEquals(1, result.size());
        assertEquals(OutputBlock.plain(input), result.getFirst());
    }
    
    @Test
    void testParseSimpleEditBlock() {
        String input = """
                <<<<<<< SEARCH build.gradle
                dependencies {
                    implementation("a:b:1.0")
                }
                ======= build.gradle
                dependencies {
                    implementation("a:b:2.0") // updated
                }
                >>>>>>> REPLACE build.gradle
                """;
        var result = EditBlock.parseAllBlocks(input).blocks();

        assertEquals(1, result.size());
        assertEquals("build.gradle", result.get(0).block().filename());
        assertTrue(result.get(0).block().beforeText().contains("a:b:1.0"));
        assertTrue(result.get(0).block().afterText().contains("a:b:2.0"));
    }

    @Test
    void testParseEditBlockInsideText() {
        String input = """
        Some introductory text.
        <<<<<<< SEARCH build.gradle
        dependencies {
            implementation("a:b:1.0")
        }
        ======= build.gradle
        dependencies {
            implementation("a:b:2.0") // updated
        }
        >>>>>>> REPLACE build.gradle
        Some concluding text.
        """;
        var result = EditBlock.parseAllBlocks(input).blocks();

        assertEquals(3, result.size());
        assertTrue(result.get(0).text().contains("introductory")); 
        assertEquals("build.gradle", result.get(1).block().filename());
        assertTrue(result.get(1).block().beforeText().contains("a:b:1.0"));
        assertTrue(result.get(1).block().afterText().contains("a:b:2.0"));
        assertTrue(result.get(2).text().contains("concluding"));
    }

    @Test
    void testParseMultipleValidEditBlocks() {
        String input = """
                Text prologue
                <<<<<<< SEARCH file1.txt
                abc
                ======= file1.txt
                def
                >>>>>>> REPLACE file1.txt
                
                <<<<<<< SEARCH file2.java
                class A {}
                ======= file2.java
                class B {}
                >>>>>>> REPLACE file2.java
                Text epilogue
                """;
        var result = EditBlock.parseAllBlocks(input).blocks();

        assertEquals(4, result.size()); // prologue, s/r, s/r, epilogue
        // TODO flesh out asserts
    }


    @Test
    void testParseMalformedEditBlockFallsBackToText() {
        // Missing ======= divider
        String input = """
                Some introductory text.
                <<<<<<< SEARCH build.gradle
                dependencies {
                    implementation("a:b:1.0")
                }
                >>>>>>> REPLACE build.gradle
                Some concluding text.
                """;
        var editParseResult = EditBlock.parseEditBlocks(input);
        assertNotNull(editParseResult.parseError(), "EditBlock parser should report an error");
        assertTrue(editParseResult.blocks().isEmpty(), "EditBlock parser should find no valid blocks");

        // LlmOutputParser should fall back to plain/code parsing
        var result = EditBlock.parseAllBlocks(input).blocks();
        assertEquals(1, result.size());
        assertNotNull(result.get(0).text());
    }
}
