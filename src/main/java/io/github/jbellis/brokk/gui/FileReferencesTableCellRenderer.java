package io.github.jbellis.brokk.gui;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Table cell renderer for displaying file references.
 */
public class FileReferencesTableCellRenderer implements TableCellRenderer {

    public FileReferencesTableCellRenderer() {
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                 boolean isSelected, boolean hasFocus,
                                                 int row, int column) {
        // Convert the value to a list of FileReferenceData
        List<FileReferenceData> fileRefs = convertToFileReferences(value);

        FileReferenceList component = new FileReferenceList(fileRefs);

        // Set colors based on selection
        if (isSelected) {
            component.setBackground(table.getSelectionBackground());
            component.setForeground(table.getSelectionForeground());
        } else {
            component.setBackground(table.getBackground());
            component.setForeground(table.getForeground());
        }
        
        // Ensure the component is properly painted in the table
        component.setOpaque(true);
        
        // Set border to match the editor's border for consistency when transitioning
        component.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

        return component;
    }

    /**
     * Converts various input types to a list of FileReferenceData objects.
     */
    @SuppressWarnings("unchecked")
    public static List<FileReferenceData> convertToFileReferences(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }

        if (value instanceof List) {
            return (List<FileReferenceData>) value;
        }  else {
            throw new IllegalArgumentException("Input is not supported for FileReferencesTableCellRenderer. Expected List<FileReferenceData>");
        }
    }
}
