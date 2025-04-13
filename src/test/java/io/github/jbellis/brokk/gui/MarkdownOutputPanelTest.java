package io.github.jbellis.brokk.gui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MarkdownOutputPanel, focusing on the correct rendering of
 * mixed Markdown, code fences, and Search/Replace blocks.
 * Verifies component counts and textual content, avoiding specific component type checks in assertions.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "GUI tests require a graphical environment")
public class MarkdownOutputPanelTest {

    private MarkdownOutputPanel panel;

    /** Helper to run GUI operations on the Event Dispatch Thread */
    private void runOnEdt(Runnable task) {
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException | InvocationTargetException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        runOnEdt(() -> {
            panel = new MarkdownOutputPanel();
            panel.updateTheme(false); // Use light theme for consistency
            // Ensure panel has a size for layout calculations if needed
            panel.setSize(new Dimension(500, 500));
        });
    }

    // --- Helper Methods to Extract Content ---

    /**
     * Recursively extracts text content from known text-holding components (JEditorPane, RSyntaxTextArea, JLabel)
     * within a given container. Returns text in the order components are encountered (depth-first).
     * @param container The container to search within.
     * @return A List of Strings containing the text found.
     */
    private List<String> getAllVisibleText(Container container) {
        List<String> texts = new ArrayList<>();
        for (Component comp : container.getComponents()) {
            if (comp.isVisible()) { // Only consider visible components
                if (comp instanceof JEditorPane pane) {
                    texts.add(pane.getText());
                } else if (comp instanceof RSyntaxTextArea area) {
                    texts.add(area.getText());
                } else if (comp instanceof JLabel label) {
                    texts.add(label.getText());
                } else if (comp instanceof Container subContainer) {
                    texts.addAll(getAllVisibleText(subContainer));
                }
            }
        }
        return texts;
    }

    /**
     * Finds the first RSyntaxTextArea within a container and returns it.
     * Useful for checking syntax style.
     * @param container The container to search within.
     * @return The first RSyntaxTextArea found, or null.
     */
    private RSyntaxTextArea findFirstCodeArea(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof RSyntaxTextArea area) {
                return area;
            } else if (comp instanceof Container subContainer) {
                RSyntaxTextArea found = findFirstCodeArea(subContainer);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Gets text content from direct children components of the panel.
     * Assumes children are either text/code panes or wrappers containing them.
     */
    private List<String> getTextFromPanelComponents() {
        List<String> content = new ArrayList<>();
        for (int i = 0; i < panel.getComponentCount(); i++) {
            Component comp = panel.getComponent(i);
            if (comp instanceof JEditorPane pane) {
                content.add(pane.getText());
            } else if (comp instanceof Container wrapper && !(wrapper instanceof SpinnerIndicatorPanel)) {
                // Assume code areas or edit blocks are wrapped in standard Containers, but ignore the spinner
                content.addAll(getAllVisibleText(wrapper));
            }
            // Ignore other component types like separators or the SpinnerIndicatorPanel itself
        }
        return content;
    }


    // --- Test Cases ---

    @Test
    void testMarkdownWithCodeFence() {
        String markdown = """
                Intro text.
                ```java
                System.out.println("Hello");
                // Comment
                ```
                Outro text.
                """;
        runOnEdt(() -> panel.setText(markdown));

        assertEquals(3, panel.getComponentCount(), "Expected 3 components (text, code, text)");
        List<String> texts = getTextFromPanelComponents();
        assertEquals(3, texts.size(), "Expected text from 3 components");
        assertTrue(texts.get(0).contains("Intro text."), "Intro text mismatch");
        assertTrue(texts.get(1).contains("System.out.println(\"Hello\");"), "Code content mismatch");
        assertTrue(texts.get(1).contains("// Comment"), "Code comment mismatch");
        assertTrue(texts.get(2).contains("Outro text."), "Outro text mismatch");

        // Verify syntax style without asserting component type directly
        assertTrue(panel.getComponent(1) instanceof Container, "Component 1 should be a wrapper");
        RSyntaxTextArea codeArea = findFirstCodeArea((Container)panel.getComponent(1));
        assertNotNull(codeArea, "Could not find code area in component 1");
        assertEquals(SyntaxConstants.SYNTAX_STYLE_JAVA, codeArea.getSyntaxEditingStyle());
    }

    @Test
    void testMarkdownStartingWithCodeFence() {
        String markdown = """
                ```python
                print("Start")
                ```
                Trailing text.
                """;
        runOnEdt(() -> panel.setText(markdown));

        assertEquals(2, panel.getComponentCount(), "Expected 2 components (code, text)");
        List<String> texts = getTextFromPanelComponents();
        assertEquals(2, texts.size(), "Expected text from 2 components");
        assertTrue(texts.get(0).contains("print(\"Start\")"), "Code content mismatch");
        assertTrue(texts.get(1).contains("Trailing text."), "Trailing text mismatch");

        assertTrue(panel.getComponent(0) instanceof Container, "Component 0 should be a wrapper");
        RSyntaxTextArea codeArea = findFirstCodeArea((Container)panel.getComponent(0));
        assertNotNull(codeArea, "Could not find code area in component 0");
        assertEquals(SyntaxConstants.SYNTAX_STYLE_PYTHON, codeArea.getSyntaxEditingStyle());
    }

    @Test
    void testMarkdownEndingWithCodeFence() {
        String markdown = """
                Leading text.
                ```
                Plain code block
                ```
                """; // No trailing newline after fence
        runOnEdt(() -> panel.setText(markdown));

        assertEquals(2, panel.getComponentCount(), "Expected 2 components (text, code)");
        List<String> texts = getTextFromPanelComponents();
        assertEquals(2, texts.size(), "Expected text from 2 components");
        assertTrue(texts.get(0).contains("Leading text."), "Leading text mismatch");
        assertTrue(texts.get(1).contains("Plain code block"), "Code content mismatch");

        assertTrue(panel.getComponent(1) instanceof Container, "Component 1 should be a wrapper");
        RSyntaxTextArea codeArea = findFirstCodeArea((Container)panel.getComponent(1));
        assertNotNull(codeArea, "Could not find code area in component 1");
        assertEquals(SyntaxConstants.SYNTAX_STYLE_NONE, codeArea.getSyntaxEditingStyle());
    }

    @Test
    void testMultipleCodeFences() {
        String markdown = """
                Text A
                ```java
                Code A
                ```
                Text B
                ```python
                Code B
                ```
                Text C
                """;
         runOnEdt(() -> panel.setText(markdown));

         assertEquals(5, panel.getComponentCount(), "Expected 5 components (text, code, text, code, text)");
         List<String> texts = getTextFromPanelComponents();
         assertEquals(5, texts.size(), "Expected text from 5 components");
         assertTrue(texts.get(0).contains("Text A"));
         assertTrue(texts.get(1).contains("Code A"));
         assertTrue(texts.get(2).contains("Text B"));
         assertTrue(texts.get(3).contains("Code B"));
         assertTrue(texts.get(4).contains("Text C"));

         RSyntaxTextArea codeArea1 = findFirstCodeArea((Container)panel.getComponent(1));
         assertNotNull(codeArea1);
         assertEquals(SyntaxConstants.SYNTAX_STYLE_JAVA, codeArea1.getSyntaxEditingStyle());

         RSyntaxTextArea codeArea2 = findFirstCodeArea((Container)panel.getComponent(3));
         assertNotNull(codeArea2);
         assertEquals(SyntaxConstants.SYNTAX_STYLE_PYTHON, codeArea2.getSyntaxEditingStyle());
    }

    @Test
    void testSingleValidEditBlock() {
        String editBlockText = """
                <<<<<<< SEARCH src/Foo.java
                Search Line 1
                Search Line 2
                ======= src/Foo.java
                Replace Line 1
                Replace Line 2
                >>>>>>> REPLACE src/Foo.java""";
        runOnEdt(() -> panel.setText(editBlockText));

        // Should render as ONE component (the edit block panel)
        assertEquals(1, panel.getComponentCount(), "Expected 1 component for the edit block");

        // Extract all text pieces from within the edit block panel
        List<String> texts = getTextFromPanelComponents();

        // Expected text: Filename label, "SEARCH" label, Search content, "REPLACE" label, Replace content
        assertEquals(5, texts.size(), "Expected 5 text components within the edit block");
        assertTrue(texts.get(0).contains("File: src/Foo.java"), "Filename label mismatch");
        assertEquals("SEARCH", texts.get(1), "SEARCH label mismatch");
        assertTrue(texts.get(2).contains("Search Line 1\nSearch Line 2"), "Search content mismatch");
        assertEquals("REPLACE", texts.get(3), "REPLACE label mismatch");
        assertTrue(texts.get(4).contains("Replace Line 1\nReplace Line 2"), "Replace content mismatch");
    }

    @Test
    void testMultipleValidEditBlocks() {
        String editBlockText = """
                <<<<<<< SEARCH src/Foo.java
                foo search
                ======= src/Foo.java
                foo replace
                >>>>>>> REPLACE src/Foo.java

                <<<<<<< SEARCH src/Bar.java
                bar search
                ======= src/Bar.java
                bar replace
                >>>>>>> REPLACE src/Bar.java""";
         runOnEdt(() -> panel.setText(editBlockText));

         assertEquals(2, panel.getComponentCount(), "Expected 2 components for two edit blocks");

         // Verify Block 1 content
         assertTrue(panel.getComponent(0) instanceof Container);
         List<String> texts1 = getAllVisibleText((Container) panel.getComponent(0));
         assertEquals(5, texts1.size());
         assertTrue(texts1.get(0).contains("File: src/Foo.java"));
         assertEquals("SEARCH", texts1.get(1));
         assertTrue(texts1.get(2).contains("foo search"));
         assertEquals("REPLACE", texts1.get(3));
         assertTrue(texts1.get(4).contains("foo replace"));

         // Verify Block 2 content
         assertTrue(panel.getComponent(1) instanceof Container);
         List<String> texts2 = getAllVisibleText((Container) panel.getComponent(1));
         assertEquals(5, texts2.size());
         assertTrue(texts2.get(0).contains("File: src/Bar.java"));
         assertEquals("SEARCH", texts2.get(1));
         assertTrue(texts2.get(2).contains("bar search"));
         assertEquals("REPLACE", texts2.get(3));
         assertTrue(texts2.get(4).contains("bar replace"));
    }

    @Test
    void testMalformedEditBlockRenderedAsPlainText() {
        // Missing divider renders as plain text
        String malformedText = """
                <<<<<<< SEARCH src/Foo.java
                Search Text
                >>>>>>> REPLACE src/Foo.java""";
        runOnEdt(() -> panel.setText(malformedText));

        // Expect 1 component (plain text view)
        assertEquals(1, panel.getComponentCount(), "Expected 1 component for plain text fallback");
        List<String> texts = getTextFromPanelComponents();
        assertEquals(1, texts.size());
        // Check that the original malformed text is displayed literally
        assertTrue(texts.get(0).contains("<<<<<<< SEARCH src/Foo.java"), "Should contain literal SEARCH marker");
        assertTrue(texts.get(0).contains("Search Text"), "Should contain literal content");
        assertFalse(texts.get(0).contains("======="), "Should NOT contain divider marker");
        assertTrue(texts.get(0).contains(">>>>>>> REPLACE src/Foo.java"), "Should contain literal REPLACE marker");
    }

    @Test
    void testMixedMarkdownAndEditBlockRendersEditBlockOnly() {
        // If EditBlock.parseEditBlocks succeeds, it renders *only* the block,
        // even if there was surrounding text in the input string.
        String mixedText = """
                Some markdown text.
                <<<<<<< SEARCH src/Mix.java
                search mix
                ======= src/Mix.java
                replace mix
                >>>>>>> REPLACE src/Mix.java
                More markdown text.
                """;
        runOnEdt(() -> panel.setText(mixedText));

        // Expect ONE component: the edit block panel
        assertEquals(1, panel.getComponentCount(), "Expected 1 component (edit block) for mixed content");
        List<String> texts = getTextFromPanelComponents();
        assertEquals(5, texts.size(), "Expected 5 text components within the edit block");
        assertTrue(texts.get(0).contains("File: src/Mix.java"));
        assertEquals("SEARCH", texts.get(1));
        assertTrue(texts.get(2).contains("search mix"));
        assertEquals("REPLACE", texts.get(3));
        assertTrue(texts.get(4).contains("replace mix"));
        // Crucially, the surrounding markdown text should NOT be present
        assertFalse(texts.stream().anyMatch(s -> s.contains("Some markdown text.")), "Surrounding markdown should not be rendered");
        assertFalse(texts.stream().anyMatch(s -> s.contains("More markdown text.")), "Surrounding markdown should not be rendered");
    }

    @Test
    void testAppendUpdatesContentCorrectly() {
        runOnEdt(() -> {
            // 1. Append text
            panel.append("Initial text.\n");
            assertEquals(1, panel.getComponentCount(), "Append 1: Text component count");
            List<String> texts1 = getTextFromPanelComponents();
            assertEquals(1, texts1.size(), "Append 1: Text content count");
            assertTrue(texts1.get(0).contains("Initial text."));

            // 2. Append code block
            panel.append("```java\nCode block\n```\n");
            assertEquals(2, panel.getComponentCount(), "Append 2: Text+Code component count");
             List<String> texts2 = getTextFromPanelComponents();
            assertEquals(2, texts2.size(), "Append 2: Text content count");
            assertTrue(texts2.get(0).contains("Initial text.")); // Preserves previous
            assertTrue(texts2.get(1).contains("Code block"));   // Adds new code

            // 3. Append more text
            panel.append("Final text.");
            assertEquals(3, panel.getComponentCount(), "Append 3: Text+Code+Text component count");
            List<String> texts3 = getTextFromPanelComponents();
            assertEquals(3, texts3.size(), "Append 3: Text content count");
            assertTrue(texts3.get(0).contains("Initial text."));
            assertTrue(texts3.get(1).contains("Code block"));
            assertTrue(texts3.get(2).contains("Final text.")); // Adds final text
        });
    }

    @Test
    void testClearRemovesComponentsAndText() {
        runOnEdt(() -> {
            panel.setText("Some text\n```\ncode\n```\nMore text");
            assertTrue(panel.getComponentCount() > 0, "Panel should have components before clear");
            assertTrue(panel.getText().length() > 0, "Panel should have text before clear");

            panel.clear();

            assertEquals(0, panel.getComponentCount(), "Panel should have 0 components after clear");
            assertEquals("", panel.getText(), "Panel text should be empty after clear");
        });
    }
}
