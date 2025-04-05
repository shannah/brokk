package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.difftool.utils.Colors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Utilities;
import java.awt.*;

/**
 * LineNumberBorder is a custom border used to display line numbers in a text editor.
 * It extends EmptyBorder and is responsible for rendering the line numbers on the side
 * of the text editor while maintaining proper alignment and spacing.
 * Relies on JTextArea's model for line calculation.
 */
public class LineNumberBorder extends EmptyBorder {
    // Margin space between the line numbers and the text editor
    private static final int HORIZONTAL_MARGIN = 4;
    // Default width guess
    private static final int DEFAULT_WIDTH = 40;

    // Reference to the associated FilePanel containing the text editor
    private final FilePanel filePanel;

    // Colors for the background and line separator
    private Color background;
    private Color lineColor;
    private Color numberColor;

    // Font settings for displaying line numbers
    private Font font;
    private int fontHeight;

    /**
     * Constructs a LineNumberBorder with the specified FilePanel.
     *
     * @param filePanel The FilePanel associated with this border.
     */
    public LineNumberBorder(FilePanel filePanel) {
        // Set a default initial left margin width
        super(0, DEFAULT_WIDTH + HORIZONTAL_MARGIN, 0, 0);
        this.filePanel = filePanel;
        init();
    }

    /**
     * Initializes font and color settings for the line number display.
     */
    private void init() {
        JTextArea textArea = filePanel.getEditor();
        Font editorFont = textArea.getFont();
        Color baseColor;

        // Use editor font size, but monospaced family
        font = new Font(Font.MONOSPACED, Font.PLAIN, editorFont.getSize() - 2); // Slightly smaller
        FontMetrics fm = textArea.getFontMetrics(font);
        fontHeight = fm.getHeight();

        // Get the base panel background color (consider theme)
        baseColor = textArea.getBackground(); // Use editor background

        // Adjust colors for contrast based on editor background
        lineColor = ColorUtil.darker(baseColor); // Separator line
        background = ColorUtil.brighter(baseColor); // Bar background
        // Choose number color for contrast with bar background
        numberColor = (ColorUtil.getBrightness(background) < 128) ? Color.WHITE : Color.BLACK;

        // Adjust left inset based on max line number
        updateLeftInsets();
    }

    /** Calculate and update the left inset based on the number of lines */
    private void updateLeftInsets() {
        JTextArea textArea = filePanel.getEditor();
        int lineCount = textArea.getLineCount();
        int maxDigits = Math.max(1, (int) Math.log10(lineCount) + 1);
        FontMetrics fm = textArea.getFontMetrics(font);
        int numberWidth = fm.stringWidth(String.valueOf(lineCount)); // Width of largest number
        // Alternative: Calculate based on digits * char width
        // int numberWidth = maxDigits * fm.charWidth('0');

        left = numberWidth + HORIZONTAL_MARGIN * 2; // Margin on both sides of number
        // No need to call super constructor again, just set the 'left' field inherited from EmptyBorder
    }

    /**
     * Override getBorderInsets to return dynamic insets based on line count.
     */
    @Override
    public Insets getBorderInsets(Component c) {
         updateLeftInsets(); // Recalculate before returning
         return new Insets(top, left, bottom, right);
    }

     /**
     * Override getBorderInsets to return dynamic insets based on line count.
     */
     @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        updateLeftInsets(); // Recalculate before setting
        insets.left = left;
        insets.top = top;
        insets.right = right;
        insets.bottom = bottom;
        return insets;
    }

    /**
     * Paints the border, including the line numbers.
     *
     * @param c the component for which this border is being painted
     * @param g the paint graphics
     * @param x the x position of the painted border
     * @param y the y position of the painted border
     * @param width the width of the painted border
     * @param height the height of the painted border
     */
    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        JTextArea textArea = filePanel.getEditor();
        Rectangle clip = g.getClipBounds();

        // Paint background
        g.setColor(background);
        g.fillRect(x, y + clip.y, left - HORIZONTAL_MARGIN, clip.height);

        // Draw vertical separator line
        g.setColor(lineColor);
        g.drawLine(x + left - HORIZONTAL_MARGIN, y + clip.y, x + left - HORIZONTAL_MARGIN, y + clip.y + clip.height);

        // Set font and color for line numbers
        g.setFont(font);
        g.setColor(numberColor);

        // Determine the range of lines to draw
        int startOffset = textArea.viewToModel2D(new Point(0, clip.y));
        int endOffset = textArea.viewToModel2D(new Point(0, clip.y + clip.height));

        int startLine, endLine;
        try {
            startLine = textArea.getLineOfOffset(startOffset);
            endLine = textArea.getLineOfOffset(endOffset);
        } catch (BadLocationException e) {
            System.err.println("Error getting line numbers for painting: " + e.getMessage());
            return; // Cannot paint numbers if offsets are bad
        }

        FontMetrics editorFm = textArea.getFontMetrics(textArea.getFont());
        int editorFontHeight = editorFm.getHeight();
        int editorAscent = editorFm.getAscent();

        FontMetrics numberFm = g.getFontMetrics(font);
        int numberAscent = numberFm.getAscent();

        // Iterate through visible lines and draw numbers
        try {
            for (int line = startLine; line <= endLine; line++) {
                int lineStartOffset = textArea.getLineStartOffset(line);
                Rectangle lineRect = textArea.modelToView(lineStartOffset);
                if (lineRect == null) continue; // Skip if view rect is null

                String lineNumberStr = String.valueOf(line + 1);
                int stringWidth = numberFm.stringWidth(lineNumberStr);

                // Calculate position to draw the number string
                // Align numbers to the right, near the separator line
                int drawX = x + left - HORIZONTAL_MARGIN - stringWidth - HORIZONTAL_MARGIN / 2;
                // Align vertically using ascent information
                int drawY = y + lineRect.y + editorAscent - (editorFontHeight - fontHeight) / 2 - (editorAscent - numberAscent); // Adjust based on font heights and ascents

                // Only draw if within the visible clip vertically
                if (drawY + fontHeight >= clip.y && drawY <= clip.y + clip.height) {
                     g.drawString(lineNumberStr, drawX, drawY);
                }
            }
        } catch (BadLocationException ex) {
            // Should not happen if line numbers are valid
            System.err.println("Error calculating view for offset in LineNumberBorder: " + ex);
        }
    }
}
