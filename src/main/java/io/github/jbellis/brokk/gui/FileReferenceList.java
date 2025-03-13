package io.github.jbellis.brokk.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Component to display and interact with a list of file references.
 */
public class FileReferenceList extends JPanel {
    private final List<FileReferenceData> fileReferences = new ArrayList<>();

    private static final Color BADGE_BORDER = new Color(66, 139, 202);
    private static final Color BADGE_FOREGROUND = new Color(66, 139, 202);
    private static final Color BADGE_HOVER_BORDER = new Color(51, 122, 183);
    private static final int BADGE_ARC_WIDTH = 10;
    private static final float BORDER_THICKNESS = 1.5f;
    
    public FileReferenceList() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
        setOpaque(true);
    }

    public FileReferenceList(List<FileReferenceData> fileReferences) {
        this();
        setFileReferences(fileReferences);
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
     * Gets the currently displayed file references
     */
    public List<FileReferenceData> getFileReferences() {
        return new ArrayList<>(fileReferences);
    }
    
    private JLabel createBadgeLabel(String text) {
        JLabel label = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Determine if hovering
                boolean isHovered = getMousePosition() != null;

                // Set border color based on hover state
                g2d.setColor(isHovered ? BADGE_HOVER_BORDER : BADGE_BORDER);

                // Use a thicker stroke for the border
                g2d.setStroke(new BasicStroke(BORDER_THICKNESS));

                // Draw rounded rectangle border only
                g2d.draw(new RoundRectangle2D.Float(BORDER_THICKNESS/2, BORDER_THICKNESS/2,
                                                  getWidth()-BORDER_THICKNESS, getHeight()-BORDER_THICKNESS,
                                                  BADGE_ARC_WIDTH, BADGE_ARC_WIDTH));

                g2d.dispose();

                // Then draw the text
                super.paintComponent(g);
            }
        };

        // Style the badge - use a smaller font for table cell
        float fontSize = label.getFont().getSize() * 0.85f;
        label.setFont(label.getFont().deriveFont(Font.PLAIN, fontSize));
        label.setForeground(BADGE_FOREGROUND);
        label.setBorder(new EmptyBorder(1, 6, 1, 6));

        return label;
    }
}
