package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Chrome;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;

/**
 * Multi-line cell renderer for the context history table
 */
public class MultiLineCellRenderer extends JTextArea implements TableCellRenderer {

    public MultiLineCellRenderer() {
        setLineWrap(true);
        setWrapStyleWord(true);
        setOpaque(true);
        setBorder(new EmptyBorder(2, 5, 2, 5));
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus, int row, int column) {
        // Set text, size, and font to match the table's settings
        setText(value != null ? value.toString() : "");
        setSize(table.getColumnModel().getColumn(column).getWidth(), 0);
        setFont(table.getFont());

        if (isSelected) {
            setBackground(table.getSelectionBackground());
            setForeground(table.getSelectionForeground());
        } else {
            Color bg = table.getBackground();
            Color fg = table.getForeground();

            // Ensure row is within valid bounds
            if (row < table.getRowCount()
                    && table instanceof JTable jt
                    && jt.getModel() instanceof DefaultTableModel model
                    && model.getRowCount() > row) {

                // Look for an ancestor that supports client properties
                Component ancestor = SwingUtilities.getAncestorOfClass(JComponent.class, table);
                if (ancestor instanceof JComponent comp) {
                    Object chromeObj = comp.getClientProperty("chrome");
                    if (chromeObj instanceof Chrome chrome && row < chrome.getContextManager().getContextHistory().size()) {
                        var ctx = chrome.getContextManager().getContextHistory().get(row);
                        if (ctx.getTextAreaContents() != null) {
                            // LLM conversation - use dark background
                            bg = new Color(50, 50, 50);
                            fg = new Color(220, 220, 220);
                        }
                    }
                }
            }
            setBackground(bg);
            setForeground(fg);
        }
        return this;
    }
}
