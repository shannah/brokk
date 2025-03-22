package io.github.jbellis.brokk.gui;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.Component;

public class TableUtils {
    /**
     * Adjusts the preferred width of the specified column to fit its content.
     * Also sets a maximum width to prevent columns from becoming too wide.
     *
     * @param table The JTable whose column will be resized.
     * @param colIndex The index of the column to adjust.
     */
    public static void fitColumnWidth(JTable table, int colIndex) {
        TableColumn column = table.getColumnModel().getColumn(colIndex);
        int width = 10; // a minimum width

        // Get header width
        TableCellRenderer headerRenderer = table.getTableHeader().getDefaultRenderer();
        Component headerComp = headerRenderer.getTableCellRendererComponent(
                table, column.getHeaderValue(), false, false, 0, colIndex);
        width = Math.max(width, headerComp.getPreferredSize().width);

        // Get maximum width of cells in this column
        for (int row = 0; row < table.getRowCount(); row++) {
            TableCellRenderer cellRenderer = table.getCellRenderer(row, colIndex);
            Component comp = table.prepareRenderer(cellRenderer, row, colIndex);
            width = Math.max(width, comp.getPreferredSize().width);
        }
        
        column.setPreferredWidth(width);
        column.setMaxWidth(width);
    }
}
