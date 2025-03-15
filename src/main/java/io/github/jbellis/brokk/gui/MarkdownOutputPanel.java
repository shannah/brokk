package io.github.jbellis.brokk.gui;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;

/**
 * A panel that stores Markdown text and displays it as a sequence of Swing components:
 * - JEditorPane for normal text.
 * - RSyntaxTextArea for code fences.
 */
class MarkdownOutputPanel extends JPanel implements Scrollable {
    private final java.util.List<Runnable> textChangeListeners = new java.util.ArrayList<>();
    private final StringBuilder markdownBuffer = new StringBuilder();

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownOutputPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        // Build the Flexmark parser
        parser = Parser.builder().build();
        renderer = HtmlRenderer.builder().build();
    }

    /**
     * Clears all text and regenerates the display.
     */
    public void clear() {
        markdownBuffer.setLength(0);
        regenerateComponents();
    }

    /**
     * Appends more Markdown text and regenerates display.
     */
    public void append(String text) {
        assert text != null;
        if (!text.isEmpty()) {
            markdownBuffer.append(text);
            regenerateComponents();
        }
    }

    /**
     * Sets the entire text buffer to the given Markdown and regenerates.
     */
    public void setText(String text) {
        assert text != null;
        markdownBuffer.setLength(0);
        markdownBuffer.append(text);
        regenerateComponents();
    }

    /**
     * Returns the entire unrendered Markdown text (for captures, etc.).
     */
    public String getText() {
        return markdownBuffer.toString();
    }

    /**
     * Let callers listen for changes in the text, so we can do reference detection.
     */
    public void addTextChangeListener(Runnable listener) {
        textChangeListeners.add(listener);
    }

    /**
     * Completely rebuilds this panel's subcomponents from markdownBuffer.
     */
    private void regenerateComponents() {
        removeAll();
        var htmlBuilder = new StringBuilder();

        for (var node : parser.parse(markdownBuffer.toString()).getChildren()) {
            if (node instanceof FencedCodeBlock fenced) {
                if (!htmlBuilder.isEmpty()) {
                    add(createHtmlPane(htmlBuilder.toString()));
                    htmlBuilder.setLength(0);
                }

                var fenceInfo = fenced.getInfo().toString().trim().toLowerCase();
                var content = fenced.getContentChars().toString();
                var codeArea = createConfiguredCodeArea(fenceInfo, content);
                add(codeAreaInPanel(codeArea));
            } else {
                htmlBuilder.append(renderer.render(node));
            }
        }

        if (!htmlBuilder.isEmpty()) {
            add(createHtmlPane(htmlBuilder.toString()));
        }

        revalidate();
        repaint();

        textChangeListeners.forEach(Runnable::run);
    }

    // Scrollable interface implementation
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 20; // Default scroll amount
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true; // Always fit width to viewport
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false; // Allow vertical scrolling
    }

    private RSyntaxTextArea createConfiguredCodeArea(String fenceInfo, String content) {
        var codeArea = new org.fife.ui.rsyntaxtextarea.RSyntaxTextArea(content);
        codeArea.setEditable(false);
        codeArea.setLineWrap(true);
        codeArea.setWrapStyleWord(true);
        codeArea.setSyntaxEditingStyle(switch (fenceInfo) {
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "python" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "javascript" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        });
        return codeArea;
    }

    private JPanel codeAreaInPanel(RSyntaxTextArea textArea) {
        var panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(245, 245, 245)); // Match the code area background
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, textArea.getPreferredSize().height));
        
        // Add vertical spacing above code block
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        
        // Create a nested panel to add padding inside the border
        var textAreaPanel = new JPanel(new BorderLayout());
        textAreaPanel.setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));
        textAreaPanel.setBackground(new Color(245, 245, 245));
        textAreaPanel.add(textArea);
        textAreaPanel.setBorder(BorderFactory.createLineBorder(Color.GRAY, 3, true));

        panel.add(textAreaPanel);
        return panel;
    }

    /**
     * Helper to create JEditorPane from HTML content.
     */
    private JEditorPane createHtmlPane(String htmlContent) {
        var htmlPane = new JEditorPane();
        htmlPane.setContentType("text/html");
        
        // Apply stylesheet with word wrapping and white background
        var kit = (HTMLEditorKit) htmlPane.getEditorKit();
        var ss = kit.getStyleSheet();
        ss.addRule("body { font-family: sans-serif; background-color: white; }");
        ss.addRule("p { word-wrap: break-word; }");
        ss.addRule("pre { word-wrap: break-word; }");
        ss.addRule("code { word-wrap: break-word; }");

        // Now load the HTML
        htmlPane.setText("<html><body>" + htmlContent + "</body></html>");
        
        htmlPane.setEditable(false);
        htmlPane.setAlignmentX(LEFT_ALIGNMENT);

        // Let the pane expand vertically under BoxLayout:
        htmlPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));

        return htmlPane;
    }

}
