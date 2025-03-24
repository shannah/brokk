package io.github.jbellis.brokk.gui;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.io.IOException;

/**
 * A panel that stores Markdown text and displays it as a sequence of Swing components:
 * - JEditorPane for normal text.
 * - RSyntaxTextArea for code fences.
 * 
 * This panel supports both light and dark themes.
 */
class MarkdownOutputPanel extends JPanel implements Scrollable {
    private final java.util.List<Runnable> textChangeListeners = new java.util.ArrayList<>();
    private final StringBuilder markdownBuffer = new StringBuilder();
    private Timer regenerationTimer;
    private static final int REGENERATION_DELAY = 100; // Delay in milliseconds

    private final Parser parser;
    private final HtmlRenderer renderer;
    
    // Theme-related fields
    private boolean isDarkTheme = false;
    private Color textBackgroundColor = null;
    private Color codeBackgroundColor = null;
    private Color codeBorderColor = null;

    public MarkdownOutputPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);

        // Build the Flexmark parser
        parser = Parser.builder().build();
        renderer = HtmlRenderer.builder().build();
        
        // Initialize the regeneration timer
        regenerationTimer = new Timer(REGENERATION_DELAY, e -> {
            doRegenerateComponents();
        });
        regenerationTimer.setRepeats(false);
    }
    
    /**
     * Updates the theme colors used by this panel.  Must be called before adding text.
     * 
     * @param isDark true if dark theme is being used
     */
    public void updateTheme(boolean isDark)
    {
        this.isDarkTheme = isDark;

        if (isDark) {
            textBackgroundColor = new Color(40, 40, 40);
            codeBackgroundColor = new Color(50, 50, 50);
            codeBorderColor = new Color(80, 80, 80);
        } else {
            textBackgroundColor = Color.WHITE;
            codeBackgroundColor = new Color(240, 240, 240);
            codeBorderColor = Color.GRAY;
        }

        // Ensure we paint our own background, then update the theme color
        setOpaque(true);
        setBackground(textBackgroundColor);

        // NEW: Also update the scroll pane’s background if we’re inside a JScrollPane
        var parent = getParent();
        if (parent instanceof JViewport vp) {
            vp.setOpaque(true);
            vp.setBackground(textBackgroundColor);
            var gp = vp.getParent();
            if (gp instanceof JScrollPane sp) {
                sp.setOpaque(true);
                sp.setBackground(textBackgroundColor);
            }
        }

        // Regenerate all components with the new theme
        // (It's okay to do this immediately instead of scheduled)
        doRegenerateComponents();
    }

    /**
     * Clears all text and regenerates the display.
     */
    public void clear() {
        markdownBuffer.setLength(0);
        scheduleRegeneration();
    }

    /**
     * Appends more Markdown text and regenerates display.
     */
    public void append(String text) {
        assert text != null;
        if (!text.isEmpty()) {
            markdownBuffer.append(text);
            scheduleRegeneration();
        }
    }

    /**
     * Sets the entire text buffer to the given Markdown and regenerates.
     */
    public void setText(String text) {
        assert text != null;
        markdownBuffer.setLength(0);
        markdownBuffer.append(text);
        scheduleRegeneration();
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

    private void scheduleRegeneration()
    {
        regenerationTimer.restart();
    }

    /**
     * Completely rebuilds this panel's subcomponents from markdownBuffer.
     */
    private void doRegenerateComponents() {
        removeAll();
        var htmlBuilder = new StringBuilder();

        for (var node : parser.parse(markdownBuffer.toString()).getChildren()) {
            if (node instanceof FencedCodeBlock fenced) {
                if (!htmlBuilder.isEmpty()) {
                    add(createHtmlPane(htmlBuilder.toString()));
                    htmlBuilder.setLength(0);
                }

                var fenceInfo = fenced.getInfo().toString().trim().toLowerCase();
                var content = fenced.getContentChars().toString().stripTrailing();
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
        codeArea.setHighlightCurrentLine(false);
        
        // Apply appropriate theme to the code area
        try {
            if (isDarkTheme) {
                Theme darkTheme = Theme.load(getClass().getResourceAsStream(
                    "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
                darkTheme.apply(codeArea);
            } else {
                Theme lightTheme = Theme.load(getClass().getResourceAsStream(
                    "/org/fife/ui/rsyntaxtextarea/themes/default.xml"));
                lightTheme.apply(codeArea);
            }
        } catch (IOException e) {
            // Fallback to manual colors if theme loading fails
            if (isDarkTheme) {
                codeArea.setBackground(new Color(50, 50, 50));
                codeArea.setForeground(new Color(230, 230, 230));
            }
        }
        
        return codeArea;
    }

    private JPanel codeAreaInPanel(RSyntaxTextArea textArea) {
        var panel = new JPanel(new BorderLayout());
        panel.setBackground(codeBackgroundColor);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, textArea.getPreferredSize().height));

        // Add vertical spacing above code block
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        // Create a nested panel to add padding inside the border
        var textAreaPanel = new JPanel(new BorderLayout());
        textAreaPanel.setBorder(BorderFactory.createEmptyBorder(15, 8, 8, 8));
        textAreaPanel.setBackground(codeBackgroundColor);
        textAreaPanel.add(textArea);
        textAreaPanel.setBorder(BorderFactory.createLineBorder(codeBorderColor, 3, true));

        panel.add(textAreaPanel);
        return panel;
    }

    /**
     * Helper to create JEditorPane from HTML content.
     */
    private JEditorPane createHtmlPane(String htmlContent) {
        var htmlPane = new JEditorPane();
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        htmlPane.setAlignmentX(LEFT_ALIGNMENT);
        htmlPane.setBackground(textBackgroundColor);

        String bgColorHex = String.format("#%02x%02x%02x",
                                          textBackgroundColor.getRed(),
                                          textBackgroundColor.getGreen(),
                                          textBackgroundColor.getBlue());

        String textColor = isDarkTheme ? "#e6e6e6" : "#000000";
        String linkColor = isDarkTheme ? "#88b3ff" : "#0366d6";

        var ss = ((HTMLEditorKit)htmlPane.getEditorKit()).getStyleSheet();
        ss.addRule("body { font-family: sans-serif; background-color: " + bgColorHex + "; color: " + textColor + "; }");
        ss.addRule("a { color: " + linkColor + "; }");
        ss.addRule("code { padding: 2px; background-color: " + (isDarkTheme ? "#3c3f41" : "#f6f8fa") + "; }");

        // Load HTML content wrapped in proper tags
        htmlPane.setText("<html><body>" + htmlContent + "</body></html>");

        htmlPane.setBackground(textBackgroundColor);
        return htmlPane;
    }
}
