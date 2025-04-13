package io.github.jbellis.brokk.gui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.jbellis.brokk.EditBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * A Swing JPanel designed to display formatted text content which may include
 * standard Markdown, Markdown code fences (```), and Brokk-specific `SEARCH/REPLACE` edit blocks.
 *
 * Rendering logic prioritizes Edit Blocks:
 * <ol>
 *   <li>The panel attempts to parse the entire text content using {@link EditBlock#parseEditBlocks(String)}.</li>
 *   <li>If parsing is successful and one or more valid {@link EditBlock.SearchReplaceBlock} instances are found,
 *       the panel renders *only* these edit blocks, each within its own visually distinct section.
 *       Any text outside the recognized blocks is ignored in this mode.</li>
 *   <li>If parsing fails (e.g., malformed block syntax) or if no valid blocks are found, the panel
 *       falls back to rendering the *entire* original text content. In this fallback mode:
 *          - If a parsing error occurred, the content is rendered as plain text to show the user the raw,
 *            potentially malformed input.
 *          - Otherwise (no blocks found, but no parse error), the content is rendered as Markdown,
 *            with standard ``` code fences being displayed as themed, syntax-highlighted code areas.</li>
 * </ol>
 * The display is updated completely whenever {@link #append(String)} or {@link #setText(String)} is called.
 */
class MarkdownOutputPanel extends JPanel implements Scrollable {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    // Holds *all* the raw text (Markdown or Edit Blocks) appended so far.
    private final StringBuilder contentBuffer = new StringBuilder();

    // Listeners to notify whenever text changes
    private final List<Runnable> textChangeListeners = new ArrayList<>();

    // Flexmark parser and renderer for Markdown segments
    private final Parser parser;
    private final HtmlRenderer renderer;

    // Theme-related fields
    private boolean isDarkTheme = false;
    private Color textBackgroundColor = null;
    private Color codeBackgroundColor = null;
    private Color codeBorderColor = null;

    public MarkdownOutputPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);

        // Build the Flexmark parser for normal text blocks
        parser = Parser.builder().build();
        renderer = HtmlRenderer.builder().build();
    }

    /**
     * Updates the theme colors used by this panel. Must be called before adding text,
     * or whenever you want to re-theme existing blocks.
     */
    public void updateTheme(boolean isDark) {
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

        setOpaque(true);
        setBackground(textBackgroundColor);

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

        // Update spinner background if visible
        if (spinnerPanel != null) {
            spinnerPanel.updateBackgroundColor(textBackgroundColor);
        }

        revalidate();
        repaint();
    }

    /**
     * Clears all text and displayed components. The parse state is reset.
     */
    public void clear() {
        logger.debug("Clearing all content from MarkdownOutputPanel");
        contentBuffer.setLength(0);
        removeAll();
        // Spinner state is reset by removeAll(); hideSpinner handles the reference
        spinnerPanel = null;
        revalidate();
        repaint();
        textChangeListeners.forEach(Runnable::run); // Notify listeners about the change
    }

    /**
     * Appends more Markdown text. We parse only the newly appended segment
     * and update the active text/code block(s) as needed.
     */
    public void append(String text) {
        assert text != null;
        if (!text.isEmpty()) {
            contentBuffer.append(text);
            rerender(); // Re-parse and render the entire content
            textChangeListeners.forEach(Runnable::run);
        }
    }

    /**
     * Sets the entire text buffer to the given Markdown, re-parsing from scratch.
     */
    public void setText(String text) {
        assert text != null;
        clear();
        append(text);
    }

    /**
     * Returns the entire raw text content buffer.
     */
    public String getText() {
        return contentBuffer.toString();
    }

    /**
     * Let callers listen for changes in the text.
     */
    public void addTextChangeListener(Runnable listener) {
        textChangeListeners.add(listener);
    }

    /**
     * Re-parses the entire contentBuffer and updates the displayed components.
     * It first attempts to parse using EditBlock.parseUpdateBlocks. If successful,
     * it renders the edit blocks. Otherwise, it renders the content as Markdown.
     */
    private void rerender() {
        // Preserve the spinner if it's showing
        Component potentialSpinner = null;
        if (getComponentCount() > 0) {
             var lastComponent = getComponent(getComponentCount() - 1);
             if (lastComponent == spinnerPanel) { // spinnerPanel is null if not showing
                 potentialSpinner = lastComponent;
             }
        }
        // Remove all components except the spinner
        removeAll();

        String content = contentBuffer.toString();
        // Attempt to parse the content as a series of SEARCH/REPLACE blocks.
        // Note: Using parseEditBlocks based on original goal and build errors with parseUpdateBlocks
        var parseResult = EditBlock.parseEditBlocks(content);

        if (parseResult.parseError() == null && !parseResult.blocks().isEmpty()) {
            // Option 1: Content parsed successfully as EditBlocks
            logger.debug("Rendering content as EditBlocks");
            for (var block : parseResult.blocks()) {
                add(renderEditBlockComponent(block));
            }
        } else {
            // Option 2: Content is not valid EditBlocks (or contains surrounding text), treat as raw text/Markdown.
            // If there was a parse error, render literally to show the user the malformed input.
            // Otherwise, render as Markdown.
            if (parseResult.parseError() != null) {
                 logger.debug("Rendering content as plain text due to EditBlock parse error");
                 JEditorPane textPane = createPlainTextPane(content); // Render literally
                 add(textPane);
            } else {
                logger.debug("Rendering content as Markdown (EditBlock parse returned empty or content wasn't solely blocks)");
                renderMarkdownContent(content); // Render as potentially rich Markdown
            }
        }

        // Re-add spinner at the end if it was preserved
        if (potentialSpinner != null) {
             add(potentialSpinner);
        }

        revalidate();
        repaint();
    }

    /**
     * Renders a string containing Markdown, handling ``` code fences.
     * Adds the resulting JEditorPane (for text) and RSyntaxTextArea (for code) components
     * directly to this panel.
     *
     * @param markdownContent The Markdown content to render.
     */
    private void renderMarkdownContent(String markdownContent) {
        // Regex to find code blocks ```optional_info \n content ```
        // DOTALL allows . to match newline characters
        // reluctant quantifier *? ensures it finds the *next* ```
        Pattern codeBlockPattern = Pattern.compile("(?s)```(\\w*)[\\r\\n]?(.*?)```");
        Matcher matcher = codeBlockPattern.matcher(markdownContent);

        int lastMatchEnd = 0;

        while (matcher.find()) {
            // 1. Render the text segment before the code block
            String textSegment = markdownContent.substring(lastMatchEnd, matcher.start());
            if (!textSegment.isEmpty()) {
                JEditorPane textPane = createHtmlPane();
                var html = renderer.render(parser.parse(textSegment));
                textPane.setText("<html><body>" + html + "</body></html>");
                add(textPane);
            }

            // 2. Render the code block
            String fenceInfo = matcher.group(1).toLowerCase();
            String codeContent = matcher.group(2);
            RSyntaxTextArea codeArea = createConfiguredCodeArea(fenceInfo, codeContent);
            add(codeAreaInPanel(codeArea));

            lastMatchEnd = matcher.end();
        }

        // Render any remaining text segment after the last code block
        String remainingText = markdownContent.substring(lastMatchEnd).trim(); // Trim whitespace
        if (!remainingText.isEmpty()) {
            JEditorPane textPane = createHtmlPane();
            // Render potentially trimmed segment as HTML
            var html = renderer.render(parser.parse(remainingText));
            textPane.setText("<html><body>" + html + "</body></html>");
            add(textPane);
        }
    }

    /**
     * Creates a JPanel visually representing a single SEARCH/REPLACE block.
     *
     * @param block The SearchReplaceBlock to render.
     * @return A JPanel containing components for the block.
     */
    private JPanel renderEditBlockComponent(EditBlock.SearchReplaceBlock block) {
        var blockPanel = new JPanel();
        blockPanel.setLayout(new BoxLayout(blockPanel, BoxLayout.Y_AXIS));
        blockPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(5, 0, 5, 0), // Outer margin
                BorderFactory.createLineBorder(isDarkTheme ? Color.DARK_GRAY: Color.LIGHT_GRAY, 1) // Border
        ));
        blockPanel.setBackground(textBackgroundColor); // Match overall background
        blockPanel.setAlignmentX(LEFT_ALIGNMENT); // Align components to the left

        // Header label (Filename)
        var headerLabel = new JLabel(String.format("File: %s", block.filename()));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)); // Padding
        headerLabel.setAlignmentX(LEFT_ALIGNMENT);
        blockPanel.add(headerLabel);

        // Separator
        blockPanel.add(new JSeparator());

        // "SEARCH" section
        blockPanel.add(createEditBlockSectionLabel("SEARCH"));
        var searchArea = createConfiguredCodeArea("", block.beforeText()); // Use "none" syntax
        searchArea.setBackground(isDarkTheme ? new Color(55, 55, 55) : new Color(245, 245, 245)); // Slightly different background
        blockPanel.add(codeAreaInPanel(searchArea, 1)); // Use thinner border for inner parts

        // Separator
        blockPanel.add(new JSeparator());

        // "REPLACE" section
        blockPanel.add(createEditBlockSectionLabel("REPLACE"));
        var replaceArea = createConfiguredCodeArea("", block.afterText()); // Use "none" syntax
        replaceArea.setBackground(isDarkTheme ? new Color(55, 55, 55) : new Color(245, 245, 245)); // Slightly different background
        blockPanel.add(codeAreaInPanel(replaceArea, 1)); // Use thinner border for inner parts

        // Adjust panel size
        blockPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, blockPanel.getPreferredSize().height));

        return blockPanel;
    }

    /** Helper to create consistent labels for SEARCH/REPLACE sections */
    private JLabel createEditBlockSectionLabel(String title) {
         var label = new JLabel(title);
         label.setFont(label.getFont().deriveFont(Font.ITALIC));
         label.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5)); // Padding
         label.setAlignmentX(LEFT_ALIGNMENT);
         return label;
    }

    /**
     * Creates a JEditorPane configured for plain text display.
     * Ensures background color matches the theme.
     */
     private JEditorPane createPlainTextPane(String text) {
         var plainPane = new JEditorPane();
         DefaultCaret caret = (DefaultCaret) plainPane.getCaret();
         caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
         plainPane.setContentType("text/plain"); // Set content type to plain text
         plainPane.setText(text); // Set text directly
         plainPane.setEditable(false);
         plainPane.setAlignmentX(LEFT_ALIGNMENT);
         if (textBackgroundColor != null) {
             plainPane.setBackground(textBackgroundColor);
             // Set foreground based on theme for plain text
             plainPane.setForeground(isDarkTheme ? new Color(230, 230, 230) : Color.BLACK);
         }
         return plainPane;
     }

    /**
     * Creates an RSyntaxTextArea for a code block, setting the syntax style and theme.
     */
    private RSyntaxTextArea createConfiguredCodeArea(String fenceInfo, String content) {
        var codeArea = new RSyntaxTextArea(content);
        codeArea.setEditable(false);
        codeArea.setLineWrap(true);
        codeArea.setWrapStyleWord(true);
        DefaultCaret caret = (DefaultCaret) codeArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        codeArea.setSyntaxEditingStyle(switch (fenceInfo) {
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "python" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "javascript" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        });
        codeArea.setHighlightCurrentLine(false);

        try {
            if (isDarkTheme) {
                var darkTheme = Theme.load(getClass().getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
                darkTheme.apply(codeArea);
            } else {
                var lightTheme = Theme.load(getClass().getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/default.xml"));
                lightTheme.apply(codeArea);
            }
        } catch (IOException e) {
            if (isDarkTheme) {
                codeArea.setBackground(new Color(50, 50, 50));
                codeArea.setForeground(new Color(230, 230, 230));
            }
        }

        return codeArea;
    }

    /**
     * Wraps an RSyntaxTextArea in a panel with padding and border.
     * Allows specifying border thickness.
     */
    private JPanel codeAreaInPanel(RSyntaxTextArea textArea, int borderThickness) {
        var panel = new JPanel(new BorderLayout());
        // Use code background for the outer padding panel
        panel.setBackground(codeBackgroundColor);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        // Padding outside the border
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        var textAreaPanel = new JPanel(new BorderLayout());
        // Use text area's actual background for the inner panel
        textAreaPanel.setBackground(textArea.getBackground());
        // Border around the text area
        textAreaPanel.setBorder(BorderFactory.createLineBorder(codeBorderColor, borderThickness, true));
        textAreaPanel.add(textArea);

        panel.add(textAreaPanel);
        // Adjust panel size
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    /** Wraps an RSyntaxTextArea in a panel with default border thickness (3). */
    private JPanel codeAreaInPanel(RSyntaxTextArea textArea) {
        return codeAreaInPanel(textArea, 3);
    }

    /**
     * Creates a JEditorPane for HTML content. We set base CSS to match the theme.
     */
    private JEditorPane createHtmlPane() {
        var htmlPane = new JEditorPane();
        DefaultCaret caret = (DefaultCaret) htmlPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        htmlPane.setAlignmentX(LEFT_ALIGNMENT);
        htmlPane.setText("<html><body></body></html>");

        if (textBackgroundColor != null) {
            htmlPane.setBackground(textBackgroundColor);

            var kit = (HTMLEditorKit) htmlPane.getEditorKit();
            var ss = kit.getStyleSheet();

            // Base background and text color
            var bgColorHex = String.format("#%02x%02x%02x",
                                           textBackgroundColor.getRed(),
                                           textBackgroundColor.getGreen(),
                                           textBackgroundColor.getBlue());
            var textColor = isDarkTheme ? "#e6e6e6" : "#000000";
            var linkColor = isDarkTheme ? "#88b3ff" : "#0366d6";

            ss.addRule("body { font-family: sans-serif; background-color: "
                               + bgColorHex + "; color: " + textColor + "; }");
            ss.addRule("a { color: " + linkColor + "; }");
            ss.addRule("code { padding: 2px; background-color: "
                               + (isDarkTheme ? "#3c3f41" : "#f6f8fa") + "; }");
        }

        return htmlPane;
    }

    // --- Spinner Logic ---

    // We keep a reference to the spinner panel itself, so we can remove it later
    private SpinnerIndicatorPanel spinnerPanel = null;

    /**
     * Shows a small spinner (or message) at the bottom of the panel,
     * underneath whatever content the user just appended.
     * If already showing, does nothing.
     *
     * @param message The text to display next to the spinner (e.g. "Thinking...")
     */
    public void showSpinner(String message) {
        if (spinnerPanel != null) {
            // Already showing, update the message and return
            spinnerPanel.setMessage(message);
            return;
        }
        // Create a new spinner instance each time
        spinnerPanel = new SpinnerIndicatorPanel(message, isDarkTheme, textBackgroundColor);

        // Add to the end of this panel. Since we have a BoxLayout (Y_AXIS),
        // it shows up below the existing rendered content.
        add(spinnerPanel);

        revalidate();
        repaint();
    }

    /**
     * Hides the spinner panel if it is visible, removing it from the UI.
     */
    public void hideSpinner() {
        if (spinnerPanel == null) {
            return; // not showing
        }
        remove(spinnerPanel); // Remove from components
        spinnerPanel = null; // Release reference

        revalidate();
        repaint();
    }

    // --- Scrollable interface methods ---

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL
                ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
