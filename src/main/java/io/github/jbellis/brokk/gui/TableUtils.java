package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.mop.ThemeColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

public final class TableUtils {

    /**
     * Returns the preferred row height for a table cell that renders
     * a {@link FileReferenceList}, by measuring a sample rendered cell.
     *
     * @param table The JTable for which to calculate the row height.
     * @return The preferred row height based on measurement.
     */
    /**
     * Shows a popup displaying all file references when there are more than can fit in the table cell.
     * 
     * @param anchor The component that anchors the popup (usually the table or cell).
     * @param files The complete list of file references to display.
     */
    public static void showOverflowPopup(Component anchor, List<FileReferenceList.FileReferenceData> files) {
        // Create a regular FileReferenceList with all files (no trimming)
        var fullList = new FileReferenceList(files);
        fullList.setOpaque(false); // For visual continuity
        
        // Create a scrollable container
        var scroll = new JScrollPane(fullList);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(
                Math.min(400, anchor.getParent().getWidth()), 200)); // Cap height
        
        // Create and configure the popup
        var popup = new JPopupMenu();
        popup.setLayout(new BorderLayout());
        popup.add(scroll);
        
        // Apply theme using existing theme colors with fallbacks
    boolean isDarkTheme = UIManager.getBoolean("laf.dark");
    Color bgColor;
    Color fgColor;
    
    try {
        // Use panel or table colors as alternatives, since popup colors don't exist
        bgColor = ThemeColors.getColor(isDarkTheme, "table_background");
        fgColor = ThemeColors.getColor(isDarkTheme, "badge_foreground");
    } catch (IllegalArgumentException e) {
        // Fallback to standard UI colors if theme colors aren't found
        bgColor = UIManager.getColor("Panel.background");
        fgColor = UIManager.getColor("Label.foreground");
    }
    
    popup.setBackground(bgColor);
    fullList.setBackground(bgColor);
    scroll.setBackground(bgColor);
    popup.setForeground(fgColor);
    fullList.setForeground(fgColor);
        
        // Show popup below the anchor component
        popup.show(anchor, 0, anchor.getHeight());
    }
    
    public static int measuredBadgeRowHeight(JTable table) {
        var sampleData = new FileReferenceList.FileReferenceData("sample.txt", "/sample.txt", null);
        var renderer = new FileReferencesTableCellRenderer();
        // The column index for getTableCellRendererComponent doesn't matter for row height
        var component = renderer.getTableCellRendererComponent(table, List.of(sampleData), false, false, 0, 0);
        return component.getPreferredSize().height;
    }

    /**
     * Adjusts the preferred width of the specified column to fit its content.
     * Also sets a maximum width to prevent columns from becoming too wide.
     *
     * @param table    The JTable whose column will be resized.
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

    /**
     * Table cell renderer for displaying file references.
     */
    static class FileReferencesTableCellRenderer implements TableCellRenderer {
        public FileReferencesTableCellRenderer() {
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column)
        {
            // Convert the value to a list of FileReferenceData
            List<FileReferenceList.FileReferenceData> fileRefs = convertToFileReferences(value);

            // Get the available width from the column
            int colWidth = table.getColumnModel().getColumn(column).getWidth();
            
            // Use the AdaptiveFileReferenceList instead of regular FileReferenceList
            FileReferenceList.AdaptiveFileReferenceList component = 
                new FileReferenceList.AdaptiveFileReferenceList(fileRefs, colWidth, 4);

            // Set colors based on selection
            if (isSelected) {
                component.setBackground(table.getSelectionBackground());
                component.setForeground(table.getSelectionForeground());
                component.setSelected(true);
            } else {
                component.setBackground(table.getBackground());
                component.setForeground(table.getForeground());
                component.setSelected(false);
            }

            // Ensure the component is properly painted in the table
            component.setOpaque(true);

            // Set border to match the editor's border for consistency when transitioning
            component.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
            
            // Update row height if necessary to fit the component, but only if there are file references
            // This preserves reasonable row height for other columns when no badges are present
            int prefHeight = component.getPreferredSize().height;
            if (!fileRefs.isEmpty() && table.getRowHeight(row) != prefHeight) {
                table.setRowHeight(row, prefHeight);
            }

            return component;
        }

        /**
         * Converts various input types to a list of FileReferenceData objects.
         */
        @SuppressWarnings("unchecked")
        public static List<FileReferenceList.FileReferenceData> convertToFileReferences(Object value) {
            if (value == null) {
                return new ArrayList<>();
            }

            if (value instanceof List) {
                return (List<FileReferenceList.FileReferenceData>) value;
            } else {
                throw new IllegalArgumentException("Input is not supported for FileReferencesTableCellRenderer. Expected List<FileReferenceData>");
            }
        }
    }

    /**
     * Component to display and interact with a list of file references.
     */
    static class FileReferenceList extends JPanel {
        private final List<FileReferenceData> fileReferences = new ArrayList<>();
        private boolean selected = false;

        private static final int BADGE_ARC_WIDTH = 10;
        public static final float BORDER_THICKNESS = 1.5f; // Made public

        public FileReferenceList() {
            setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
            setOpaque(true);
        }

        public FileReferenceList(List<FileReferenceData> fileReferences) {
            this();
            setFileReferences(fileReferences);
        }
        
        /**
         * Adaptive subclass that shows only file references that fit within an available width,
         * with an optional overflow indicator.
         */
        static class AdaptiveFileReferenceList extends FileReferenceList {
            private final List<FileReferenceData> allFiles;
            private final int availableWidth;
            private final int hgap;
            
            // Fields to track visible vs. hidden files
            private List<FileReferenceData> visibleFiles = List.of();
            private List<FileReferenceData> hiddenFiles = List.of();
            private boolean hasOverflow = false;
            
            /**
             * Creates a new AdaptiveFileReferenceList with the given files and width constraints.
             * 
             * @param files The complete list of file references
             * @param availableWidth The maximum available width in pixels
             * @param hgap The horizontal gap between badges
             */
            public AdaptiveFileReferenceList(List<FileReferenceData> files, int availableWidth, int hgap) {
                super();
                this.allFiles = new ArrayList<>(files);
                this.availableWidth = availableWidth;
                this.hgap = hgap;
                setFileReferences(files);
            }
            
            /**
             * Returns the list of files that are currently visible as individual badges.
             */
            public List<FileReferenceData> getVisibleFiles() {
                return visibleFiles;
            }
            
            /**
             * Returns the list of files that are hidden and represented by the overflow badge.
             */
            public List<FileReferenceData> getHiddenFiles() {
                return hiddenFiles;
            }
            
            /**
             * Returns whether this list has an overflow badge.
             */
            public boolean hasOverflow() {
                return hasOverflow;
            }
            
            @Override
            public void setFileReferences(List<FileReferenceData> fileReferences) {
                if (fileReferences == null || fileReferences.isEmpty()) {
                    this.visibleFiles = List.of();
                    this.hiddenFiles = List.of();
                    this.hasOverflow = false;
                    super.setFileReferences(fileReferences);
                    return;
                }

                // Calculate which files will fit in the available width
                var font = getFont().deriveFont(getFont().getSize() * 0.85f);
                var fm = getFontMetrics(font);
                int borderInsets = (int) Math.ceil(BORDER_THICKNESS) + 6; // Must match createBadgeLabel
                
                int currentX = 0;
                var visibleFilesList = new ArrayList<FileReferenceData>();
                
                // 1. Provisional pass - fill with real badges
                for (var ref : fileReferences) {
                    int badgeWidth = fm.stringWidth(ref.getFileName()) + borderInsets * 2;
                    if (!visibleFilesList.isEmpty()) {
                        currentX += hgap; // Add gap between badges
                    }
                    
                    if (currentX + badgeWidth <= availableWidth) {
                        visibleFilesList.add(ref);
                        currentX += badgeWidth;
                    } else {
                        break;
                    }
                }
                
                int remaining = fileReferences.size() - visibleFilesList.size();
                
                // Store the results in our fields
                this.visibleFiles = List.copyOf(visibleFilesList);
                this.hiddenFiles = remaining > 0 
                    ? List.copyOf(fileReferences.subList(visibleFilesList.size(), fileReferences.size()))
                    : List.of();
                this.hasOverflow = !hiddenFiles.isEmpty();
                
                if (hasOverflow) {
                    // 2. Compute overflow badge width
                    String overflowText = "+ " + hiddenFiles.size() + " more";
                    int overflowWidth = fm.stringWidth(overflowText) + borderInsets * 2;
                    
                    // Add gap in front of overflow badge if there are visible files
                    int need = (visibleFiles.isEmpty() ? 0 : hgap) + overflowWidth;
                    
                    // 3. Make space by removing trailing real badges if required
                    var mutableVisibleFiles = new ArrayList<>(visibleFiles);
                    while (!mutableVisibleFiles.isEmpty() && currentX + need > availableWidth) {
                        var last = mutableVisibleFiles.remove(mutableVisibleFiles.size() - 1);
                        int lastWidth = fm.stringWidth(last.getFileName()) + borderInsets * 2;
                        currentX -= lastWidth;
                        if (!mutableVisibleFiles.isEmpty()) currentX -= hgap;
                        remaining++;
                        overflowText = "+ " + remaining + " more";
                        overflowWidth = fm.stringWidth(overflowText) + borderInsets * 2;
                        need = (mutableVisibleFiles.isEmpty() ? 0 : hgap) + overflowWidth;
                    }
                    
                    // Update our fields with the adjusted visible/hidden lists
                    if (mutableVisibleFiles.size() != visibleFiles.size()) {
                        this.visibleFiles = List.copyOf(mutableVisibleFiles);
                        this.hiddenFiles = List.copyOf(fileReferences.subList(mutableVisibleFiles.size(), fileReferences.size()));
                    }

                    // Display visible files first
                    super.setFileReferences(mutableVisibleFiles);
                    
                    // Create overflow badge with updated text
                    JLabel overflow = this.createBadgeLabel(overflowText);
                    overflow.setToolTipText("Show all " + fileReferences.size() + " files");
                    
                    // Always add overflow badge - even if no space, it will wrap to next line
                    add(overflow);
                } else {
                    // All files fit, show them all
                    super.setFileReferences(fileReferences);
                }
            }
            
        }


        /**
         * Updates the displayed file references
         */
        public void setFileReferences(List<FileReferenceData> fileReferences) {
            this.fileReferences.clear();
            if (fileReferences != null) {
                this.fileReferences.addAll(fileReferences);
            }

            // Rebuild the UI
            removeAll();

            // Add each file reference as a label
            for (FileReferenceData file : this.fileReferences) {
                JLabel fileLabel = createBadgeLabel(file.getFileName());
                fileLabel.setOpaque(false);

                // Set tooltip to show the full path
                fileLabel.setToolTipText(file.getFullPath());

                add(fileLabel);
            }

            revalidate();
            repaint();
        }

        /**
         * Sets the selection state of this component
         *
         * @param selected true if this component is in a selected table row
         */
        public void setSelected(boolean selected) {
            if (this.selected == selected) {
                return; // No change needed
            }

            this.selected = selected;

            // Get theme-appropriate colors
            boolean isDarkTheme = UIManager.getBoolean("laf.dark");
            Color badgeForeground = ThemeColors.getColor(isDarkTheme, "badge_foreground");
            Color selectedBadgeForeground = ThemeColors.getColor(isDarkTheme, "selected_badge_foreground");
            
            // Update colors of all children without rebuilding the component hierarchy
            for (Component c : getComponents()) {
                if (c instanceof JLabel) {
                    c.setForeground(selected ? selectedBadgeForeground : badgeForeground);
                }
            }
            
            // Repaint but don't remove/recreate components
            repaint();
        }

        /**
         * Returns whether this component is currently selected
         */
        public boolean isSelected() {
            return selected;
        }

        protected JLabel createBadgeLabel(String text) {
            JLabel label = new JLabel(text) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    // Determine if hovering
                    boolean isHovered = getMousePosition() != null;
                    // Get theme state
                    boolean isDarkTheme = UIManager.getBoolean("laf.dark");

                    // Get colors from ThemeColors
                    Color badgeBorder = ThemeColors.getColor(isDarkTheme, "badge_border");
                    Color badgeHoverBorder = ThemeColors.getColor(isDarkTheme, "badge_hover_border");
                    Color selectedBadgeBorder = ThemeColors.getColor(isDarkTheme, "selected_badge_border");

                    // Set border color based on selection state and hover state
                    Color borderColor;
                    if (selected) {
                        borderColor = isHovered ? selectedBadgeBorder.brighter() : selectedBadgeBorder;
                    } else {
                        borderColor = isHovered ? badgeHoverBorder : badgeBorder;
                    }
                    g2d.setColor(borderColor);

                    // Use a thicker stroke for the border
                    g2d.setStroke(new BasicStroke(BORDER_THICKNESS));

                    // Draw rounded rectangle border only
                    g2d.draw(new RoundRectangle2D.Float(BORDER_THICKNESS / 2, BORDER_THICKNESS / 2,
                                                        getWidth() - BORDER_THICKNESS, getHeight() - BORDER_THICKNESS,
                                                        BADGE_ARC_WIDTH, BADGE_ARC_WIDTH));

                    g2d.dispose();

                    // Then draw the text
                    super.paintComponent(g);
                }
            };

            // Style the badge - use a smaller font for table cell
            float fontSize = label.getFont().getSize() * 0.85f;
            label.setFont(label.getFont().deriveFont(Font.PLAIN, fontSize));

            boolean isDarkTheme = UIManager.getBoolean("laf.dark");
            Color badgeForeground = ThemeColors.getColor(isDarkTheme, "badge_foreground");
            Color selectedBadgeForeground = ThemeColors.getColor(isDarkTheme, "selected_badge_foreground");

            // Set foreground color based on selection state
            label.setForeground(selected ? selectedBadgeForeground : badgeForeground);

            // Combine border for stroke painting space and text padding
            int borderStrokeInset = (int) Math.ceil(BORDER_THICKNESS);
            int textPaddingVertical = 1;
            int textPaddingHorizontal = 6;
            label.setBorder(new EmptyBorder(borderStrokeInset + textPaddingVertical,
                                            borderStrokeInset + textPaddingHorizontal,
                                            borderStrokeInset + textPaddingVertical,
                                            borderStrokeInset + textPaddingHorizontal));

            return label;
        }

        /**
         * Represents a file reference with metadata for context menu usage.
         */
        public static class FileReferenceData {
            private final String fileName;
            private final String fullPath;
            private final ProjectFile projectFile; // Optional, if available

            public FileReferenceData(String fileName, String fullPath, ProjectFile projectFile) {
                this.fileName = fileName;
                this.fullPath = fullPath;
                this.projectFile = projectFile;
            }

            // Getters
            public String getFileName() {
                return fileName;
            }

            public String getFullPath() {
                return fullPath;
            }

            public ProjectFile getRepoFile() {
                return projectFile;
            }

            @Override
            public String toString() {
                return fileName;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                FileReferenceData that = (FileReferenceData) o;
                return fullPath.equals(that.fullPath);
            }

            @Override
            public int hashCode() {
                return fullPath.hashCode();
            }
        }
    }
}
